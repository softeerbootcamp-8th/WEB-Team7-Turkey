package com.turkey.quick.loadtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turkey.quick.common.auth.RedisSessionStore;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.order.domain.Address;
import com.turkey.quick.order.domain.Contact;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.FarePolicy;
import com.turkey.quick.order.domain.FareType;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.domain.OrderFareSnapshot;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.order.repository.FarePolicyRepository;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 배차 accept 부하 테스트용 시더. {@code --spring.profiles.active=loadtest-seed} 로만 로드되므로
 * 일반 실행·배포에는 절대 끼어들지 않는다(정합성/성능 코드 아님, 테스트 하네스).
 *
 * <p>ADR-006(조건부 UPDATE) 검증 실험을 돌리려면 accept 성공마다 WAITING 주문 1건 + AVAILABLE
 * 라이더 1명이 소비되므로(상태 고갈), 측정 전에 (주문·라이더·세션)을 대량 선적재하고 {@code pairs.json}
 * 을 내보낸다. 설계 배경은 {@code loadtest/README.md}·{@code loadtest/adr006-validation.md}.
 *
 * <p>세션은 로그인 폭주를 피하려고 {@link RedisSessionStore#create}를 그대로 호출해 Redis에 직접
 * 심는다 — 실제 로그인이 쓰는 포맷과 100% 동일하므로 인터셉터가 그대로 인증을 통과시킨다.
 *
 * <p>사용:
 * <pre>
 *   # 처리량(무경쟁, 1:1): 주문·라이더 각 N개
 *   bootRun --args='--spring.profiles.active=loadtest-seed --seed.mode=throughput --seed.orders=12000'
 *   # 경쟁(B:1): 주문 K개, 각 주문에 라이더 B명 → pairs = K*B 행
 *   bootRun --args='--spring.profiles.active=loadtest-seed --seed.mode=contention --seed.orders=500 --seed.ridersPerOrder=20'
 *   # 리셋: 시드가 남긴 주문을 WAITING, 라이더를 AVAILABLE 로 원복(반복 측정용)
 *   bootRun --args='--spring.profiles.active=loadtest-seed --seed.mode=reset'
 * </pre>
 */
@Component
@Profile("loadtest-seed")
public class AcceptLoadSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AcceptLoadSeeder.class);

    /** 시드가 만든 행만 리셋·정리 대상으로 식별하기 위한 login_id 접두어. */
    private static final String PREFIX = "loadtest_";
    private static final Duration SESSION_TTL = Duration.ofHours(2);
    private static final int BATCH = 500;

    // 서울 대략 범위(#380 균등 난수 규약). accept 자체는 좌표를 안 보지만 콜 목록(#367)과 분포를 맞춘다.
    private static final double LAT_MIN = 37.43, LAT_MAX = 37.70;
    private static final double LON_MIN = 126.80, LON_MAX = 127.18;

    private final MemberRepository memberRepository;
    private final RiderProfileRepository riderProfileRepository;
    private final DeliveryOrderRepository deliveryOrderRepository;
    private final OrderFareSnapshotRepository orderFareSnapshotRepository;
    private final FarePolicyRepository farePolicyRepository;
    private final RedisSessionStore sessionStore;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbcTemplate;
    private final ConfigurableApplicationContext context;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom secureRandom = new SecureRandom();

    public AcceptLoadSeeder(MemberRepository memberRepository,
                            RiderProfileRepository riderProfileRepository,
                            DeliveryOrderRepository deliveryOrderRepository,
                            OrderFareSnapshotRepository orderFareSnapshotRepository,
                            FarePolicyRepository farePolicyRepository,
                            RedisSessionStore sessionStore,
                            PlatformTransactionManager transactionManager,
                            JdbcTemplate jdbcTemplate,
                            ConfigurableApplicationContext context) {
        this.memberRepository = memberRepository;
        this.riderProfileRepository = riderProfileRepository;
        this.deliveryOrderRepository = deliveryOrderRepository;
        this.orderFareSnapshotRepository = orderFareSnapshotRepository;
        this.farePolicyRepository = farePolicyRepository;
        this.sessionStore = sessionStore;
        this.tx = new TransactionTemplate(transactionManager);
        this.jdbcTemplate = jdbcTemplate;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String mode = arg(args, "seed.mode", "throughput");
        int exitCode = 0;
        try {
            switch (mode) {
                case "throughput" -> seedThroughput(intArg(args, "seed.orders", 12_000), outputPath(args));
                case "contention" -> seedContention(
                        intArg(args, "seed.orders", 500),
                        intArg(args, "seed.ridersPerOrder", 20),
                        outputPath(args));
                case "reset" -> reset();
                case "clean" -> clean();
                default -> {
                    log.error("알 수 없는 seed.mode={} (throughput|contention|reset)", mode);
                    exitCode = 2;
                }
            }
        } catch (Exception e) {
            log.error("시딩 실패", e);
            exitCode = 1;
        }
        // 시더는 데이터만 넣고 끝나는 배치성 실행이라, 웹서버를 계속 띄워두지 않고 종료한다.
        int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }

    // ── throughput: 무경쟁 1:1 ─────────────────────────────────────────────
    private void seedThroughput(int orders, String output) throws Exception {
        FarePolicy policy = ensureFarePolicy();
        List<Pair> pairs = new ArrayList<>(orders);
        for (int base = 0; base < orders; base += BATCH) {
            final int from = base;
            final int to = Math.min(base + BATCH, orders);
            List<Pair> batch = tx.execute(s -> {
                List<Pair> out = new ArrayList<>();
                for (int i = from; i < to; i++) {
                    Long orderId = createWaitingOrder(policy, i);
                    String sid = createAvailableRiderWithSession(i);
                    out.add(new Pair(sid, orderId));
                }
                return out;
            });
            pairs.addAll(batch);
            log.info("throughput seeding {}/{}", pairs.size(), orders);
        }
        writePairs(output, pairs);
        log.info("✅ throughput 시드 완료 — {}쌍 → {}", pairs.size(), output);
    }

    // ── contention: 주문 K개 × 라이더 B명(B:1 경쟁) ──────────────────────────
    private void seedContention(int orders, int ridersPerOrder, String output) throws Exception {
        FarePolicy policy = ensureFarePolicy();
        List<Pair> pairs = new ArrayList<>(orders * ridersPerOrder);
        int riderSeq = 0;
        for (int k = 0; k < orders; k++) {
            final int orderIdx = k;
            final int startRider = riderSeq;
            List<Pair> group = tx.execute(s -> {
                List<Pair> out = new ArrayList<>();
                Long orderId = createWaitingOrder(policy, orderIdx);
                for (int b = 0; b < ridersPerOrder; b++) {
                    String sid = createAvailableRiderWithSession(startRider + b);
                    out.add(new Pair(sid, orderId)); // 같은 orderId 를 B번 반복(경쟁 구조를 데이터에 인코딩)
                }
                return out;
            });
            riderSeq += ridersPerOrder;
            pairs.addAll(group);
            if (k % 50 == 0) {
                log.info("contention seeding order {}/{} (riders {})", k, orders, pairs.size());
            }
        }
        writePairs(output, pairs);
        log.info("✅ contention 시드 완료 — 주문 {}개 × {}명 = {}쌍 → {}",
                orders, ridersPerOrder, pairs.size(), output);
    }

    // ── reset: 반복 측정용 원복 ──────────────────────────────────────────────
    private void reset() {
        // 시드가 만든 행만(login_id 접두어) 원복한다. 생성 컬럼 active_rider_id/active_customer_id 는
        // status·assigned_rider_id 를 되돌리면 자동 재계산된다.
        int orderRows = jdbcTemplate.update(
                "UPDATE delivery_order o JOIN member m ON o.customer_id = m.member_id "
                        + "SET o.status='WAITING', o.assigned_rider_id=NULL, o.assigned_at=NULL "
                        + "WHERE m.login_id LIKE ?", PREFIX + "%");
        int riderRows = jdbcTemplate.update(
                "UPDATE rider_profile p JOIN member m ON p.member_id = m.member_id "
                        + "SET p.operating_status='AVAILABLE', p.status_changed_at=CURRENT_TIMESTAMP(3) "
                        + "WHERE m.login_id LIKE ?", PREFIX + "%");
        log.info("✅ reset 완료 — 주문 {}건 WAITING, 라이더 {}명 AVAILABLE 로 원복", orderRows, riderRows);
    }

    // ── clean: 시드가 남긴 loadtest_ 데이터 삭제 (FK 순서) ──────────────────────
    private void clean() {
        jdbcTemplate.update("DELETE ofs FROM order_fare_snapshot ofs "
                + "JOIN delivery_order o ON ofs.order_id=o.order_id "
                + "JOIN member m ON o.customer_id=m.member_id WHERE m.login_id LIKE ?", PREFIX + "%");
        jdbcTemplate.update("DELETE o FROM delivery_order o JOIN member m ON o.customer_id=m.member_id "
                + "WHERE m.login_id LIKE ?", PREFIX + "%");
        jdbcTemplate.update("DELETE p FROM rider_profile p JOIN member m ON p.member_id=m.member_id "
                + "WHERE m.login_id LIKE ?", PREFIX + "%");
        int n = jdbcTemplate.update("DELETE FROM member WHERE login_id LIKE ?", PREFIX + "%");
        log.info("✅ clean 완료 — loadtest_ 회원 {}명 삭제 (세션은 2h TTL 로 소멸)", n);
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    /** WAITING 주문 1건 + 전용 고객 + ESTIMATE 스냅샷을 만들고 orderId 를 돌려준다. */
    private Long createWaitingOrder(FarePolicy policy, int seq) {
        String suffix = uniqueSuffix(seq);
        Member customer = memberRepository.save(Member.create(
                PREFIX + "c_" + suffix, "seed", "부하고객", phone(seq, 1), MemberRole.CUSTOMER));
        int distance = 500 + ThreadLocalRandom.current().nextInt(9_500);
        DeliveryOrder order = deliveryOrderRepository.save(DeliveryOrder.request(
                customer, "req-" + suffix, ItemType.SMALL_PARCEL, distance,
                Address.of("픽업 " + suffix, null, "12345", randLat(), randLon()),
                Address.of("도착 " + suffix, null, "54321", randLat(), randLon()),
                Contact.of("보내는사람", "01011112222"), Contact.of("받는사람", "01033334444")));
        // accept 자체는 안 읽지만, 콜 목록/상세 API(#367)가 ESTIMATE 스냅샷 없으면 IllegalStateException.
        orderFareSnapshotRepository.save(OrderFareSnapshot.create(
                order, policy, FareType.ESTIMATE, "v1", distance, 3000L, 130L, 0L));
        return order.getId();
    }

    /** AVAILABLE 라이더 1명 + Redis 세션을 만들고 sessionId 를 돌려준다. */
    private String createAvailableRiderWithSession(int seq) {
        String suffix = uniqueSuffix(seq);
        Member member = memberRepository.save(Member.create(
                PREFIX + "r_" + suffix, "seed", "부하라이더", phone(seq, 2), MemberRole.RIDER));
        RiderProfile profile = RiderProfile.create(member);
        profile.goOnline(); // UNAVAILABLE → AVAILABLE (accept 조건부 UPDATE 대상)
        riderProfileRepository.save(profile);
        String sessionId = generateSessionId();
        sessionStore.create(sessionId, member.getId(), MemberRole.RIDER.name(), SESSION_TTL);
        return sessionId;
    }

    private FarePolicy ensureFarePolicy() {
        // 멱등: policy_version 유니크(uk_fare_policy_version) 때문에 재시드 시 재INSERT 하면 실패한다.
        // 스냅샷 FK 참조용으로는 아무 정책이나 되므로, 있으면 재사용하고 없을 때만 만든다.
        return tx.execute(s -> farePolicyRepository.findAll().stream().findFirst()
                .orElseGet(() -> farePolicyRepository.save(FarePolicy.create(
                        "v1", 3000L, 100, 130L, 30_000, LocalDateTime.now().minusDays(1)))));
    }

    private void writePairs(String output, List<Pair> pairs) throws Exception {
        File file = new File(output);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        Files.writeString(file.toPath(), objectMapper.writeValueAsString(pairs));
    }

    private BigDecimal randLat() {
        return round7(LAT_MIN + ThreadLocalRandom.current().nextDouble(LAT_MAX - LAT_MIN));
    }

    private BigDecimal randLon() {
        return round7(LON_MIN + ThreadLocalRandom.current().nextDouble(LON_MAX - LON_MIN));
    }

    private BigDecimal round7(double v) {
        return BigDecimal.valueOf(v).setScale(7, java.math.RoundingMode.HALF_UP);
    }

    /** login_id·request_key 유니크 보장 — seq + nanoTime. */
    private String uniqueSuffix(int seq) {
        return seq + "_" + System.nanoTime();
    }

    // phone_number 유니크 보장 — nanoTime%1e8 은 회차 누적 시 birthday 충돌이 난다(#벤치 실측).
    // JVM 프로세스 내 단조 증가 카운터로 대량 시드에서도 충돌 없이 유일하게 만든다.
    private static final java.util.concurrent.atomic.AtomicLong PHONE_SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime() % 1_000_000_000L);

    /** phone_number 유니크 보장 — 11자리 숫자(형식 CHECK 없음, UNIQUE 만). */
    private String phone(int seq, int kind) {
        long n = PHONE_SEQ.getAndIncrement() % 10_000_000_000L;
        return String.format("0%010d", n);
    }

    private String generateSessionId() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String outputPath(ApplicationArguments args) {
        return arg(args, "seed.output", "loadtest/local/accept/seed/pairs.json");
    }

    private String arg(ApplicationArguments args, String name, String def) {
        List<String> v = args.getOptionValues(name);
        return (v == null || v.isEmpty()) ? def : v.get(0);
    }

    private int intArg(ApplicationArguments args, String name, int def) {
        List<String> v = args.getOptionValues(name);
        return (v == null || v.isEmpty()) ? def : Integer.parseInt(v.get(0).trim());
    }

    /** pairs.json 한 행. k6 가 {sessionId, orderId} 로 읽는다. */
    private record Pair(String sessionId, Long orderId) {
    }
}
