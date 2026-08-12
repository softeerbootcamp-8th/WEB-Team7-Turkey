package com.turkey.quick.rider.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.auth.InMemorySessionStore;
import com.turkey.quick.common.response.ApiResponse;
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
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.order.repository.FarePolicyRepository;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.repository.PointWalletRepository;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #55 「퀵 요청 목록 보기」·#57 「퀵 요청 상세사항 보기」·#56 「배달 확정하기」의 완료 조건
 * (정상 흐름 + 예외 흐름)을 실제 HTTP 로 검증한다.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class RiderDeliveryRequestE2ETest extends IntegrationTestSupport {

    private static final String ENDPOINT = "/api/rider/requests";
    private static final String LOGIN_ENDPOINT = "/api/rider/login";
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private RiderProfileRepository riderProfileRepository;

    @Autowired
    private DeliveryOrderRepository deliveryOrderRepository;

    @Autowired
    private OrderFareSnapshotRepository orderFareSnapshotRepository;

    @Autowired
    private FarePolicyRepository farePolicyRepository;

    @Autowired
    private InMemorySessionStore sessionStore;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private PointWalletRepository pointWalletRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TestConfiguration
    static class FakeInfraConfig {

        @Bean
        @Primary
        InMemorySessionStore sessionStore() {
            return new InMemorySessionStore();
        }
    }

    /** Member 저장과 RiderProfile(@MapsId) 저장을 한 트랜잭션으로 묶는다(RiderLoginE2ETest와 같은 이유). */
    private Member saveRider(String loginId, String rawPassword, String phoneNumber, boolean available) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Member member = memberRepository.save(
                    Member.create(loginId, PASSWORD_ENCODER.encode(rawPassword), "홍길동", phoneNumber, MemberRole.RIDER));
            RiderProfile profile = RiderProfile.create(member);
            if (available) {
                profile.goOnline();
            }
            riderProfileRepository.save(profile);
            return member;
        });
    }

    private String loginAndGetSessionCookie(String loginId, String rawPassword) {
        var response = rest.postForEntity(LOGIN_ENDPOINT,
                Map.of("loginId", loginId, "password", rawPassword), ApiResponse.class);
        String setCookie = response.getHeaders().get(HttpHeaders.SET_COOKIE).get(0);
        return setCookie.split(";")[0];
    }

    private HttpEntity<Void> withCookie(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return new HttpEntity<>(headers);
    }

    private DeliveryOrder saveWaitingOrderWithFareSnapshot() {
        return saveWaitingOrderAt(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"));
    }

    /**
     * #367 bounding box 필터 검증용 — 픽업 좌표를 지정해 WAITING 주문을 만든다. 한 테스트에서
     * 여러 번 호출될 수 있어(반경 안/밖 주문을 함께 만드는 테스트), {@code policy_version}은
     * {@code uk_fare_policy_version} 유니크 제약을 피하려고 매번 새로 발급한다.
     */
    private DeliveryOrder saveWaitingOrderAt(BigDecimal pickupLat, BigDecimal pickupLng) {
        String uniqueSuffix = String.valueOf(System.nanoTime() % 100_000_000L);
        FarePolicy policy = farePolicyRepository.save(
                FarePolicy.create("v1-" + uniqueSuffix, 3000L, 100, 130L, 30000, LocalDateTime.now().minusDays(1)));
        Member customer = memberRepository.save(
                Member.create("e2e_rider_requests_customer_" + uniqueSuffix, "hash", "고객", "010" + uniqueSuffix,
                        MemberRole.CUSTOMER));
        DeliveryOrder order = DeliveryOrder.request(customer, "req-e2e-" + System.nanoTime(), ItemType.SMALL_PARCEL, 1000,
                Address.of("픽업지 도로명", "상세", "12345", pickupLat, pickupLng),
                Address.of("도착지 도로명", "상세", "54321", new BigDecimal("37.6000000"), new BigDecimal("127.1000000")),
                Contact.of("보내는사람", "01011112222"), Contact.of("받는사람", "01033334444"));
        DeliveryOrder saved = deliveryOrderRepository.save(order);
        orderFareSnapshotRepository.save(
                OrderFareSnapshot.create(saved, policy, FareType.ESTIMATE, policy.getPolicyVersion(), 1000, 3000L, 130L, 0L));
        return saved;
    }

    /** #60 필터·페이지네이션 검증용 — 예상 정산액(baseFare+130)을 지정해 WAITING 주문을 만든다. */
    private DeliveryOrder saveWaitingOrderWithFare(long baseFare) {
        String uniqueSuffix = String.valueOf(System.nanoTime() % 100_000_000L);
        FarePolicy policy = farePolicyRepository.save(
                FarePolicy.create("v1-" + uniqueSuffix, 3000L, 100, 130L, 30000, LocalDateTime.now().minusDays(1)));
        Member customer = memberRepository.save(
                Member.create("e2e_rider_requests_customer_" + uniqueSuffix, "hash", "고객", "010" + uniqueSuffix,
                        MemberRole.CUSTOMER));
        DeliveryOrder order = DeliveryOrder.request(customer, "req-e2e-" + System.nanoTime(), ItemType.SMALL_PARCEL, 1000,
                Address.of("픽업지 도로명", "상세", "12345", new BigDecimal("37.5010000"), new BigDecimal("127.0010000")),
                Address.of("도착지 도로명", "상세", "54321", new BigDecimal("37.6000000"), new BigDecimal("127.1000000")),
                Contact.of("보내는사람", "01011112222"), Contact.of("받는사람", "01033334444"));
        DeliveryOrder saved = deliveryOrderRepository.save(order);
        orderFareSnapshotRepository.save(
                OrderFareSnapshot.create(saved, policy, FareType.ESTIMATE, policy.getPolicyVersion(),
                        1000, baseFare, 130L, 0L));
        return saved;
    }

    /** OrderFareSnapshot 합(3000+130) — 아래 픽스처가 "이미 낸 요금"으로 미리 차감해 두는 금액. */
    private static final long WALLET_FIXTURE_FARE = 3_130L;

    /**
     * #42/#446 만료 정리 검증용 — 이미 요금을 낸(지갑에서 차감된) 고객의 WAITING 주문을 만들고
     * {@code requested_at}을 배차 대기 타임아웃 이전으로 되밀어 "만료된 주문"으로 만든다.
     * {@code createDelivery}를 거치지 않아 실제 차감이 없으므로, 지갑을 미리
     * {@code balance - WALLET_FIXTURE_FARE}로 만들어 둔다 — 만료 취소가 전액 환급하면 잔액이
     * {@code balance}로 돌아온다.
     */
    private DeliveryOrder saveExpiredWaitingOrderWithWallet(long balance) {
        DeliveryOrder order = new TransactionTemplate(transactionManager).execute(status -> {
            String uniqueSuffix = String.valueOf(System.nanoTime() % 100_000_000L);
            FarePolicy policy = farePolicyRepository.save(
                    FarePolicy.create("v1-" + uniqueSuffix, 3000L, 100, 130L, 30000, LocalDateTime.now().minusDays(1)));
            Member customer = memberRepository.save(
                    Member.create("e2e_expired_customer_" + uniqueSuffix, "hash", "고객", "010" + uniqueSuffix,
                            MemberRole.CUSTOMER));
            PointWallet wallet = PointWallet.create(customer);
            wallet.credit(balance);
            wallet.debit(WALLET_FIXTURE_FARE);
            pointWalletRepository.save(wallet);
            DeliveryOrder saved = deliveryOrderRepository.save(DeliveryOrder.request(customer,
                    "req-e2e-" + System.nanoTime(), ItemType.SMALL_PARCEL, 1000,
                    Address.of("픽업지 도로명", "상세", "12345", new BigDecimal("37.5010000"), new BigDecimal("127.0010000")),
                    Address.of("도착지 도로명", "상세", "54321", new BigDecimal("37.6000000"), new BigDecimal("127.1000000")),
                    Contact.of("보내는사람", "01011112222"), Contact.of("받는사람", "01033334444")));
            orderFareSnapshotRepository.save(OrderFareSnapshot.create(saved, policy, FareType.ESTIMATE,
                    policy.getPolicyVersion(), 1000, 3000L, 130L, 0L));
            return saved;
        });
        jdbcTemplate.update("UPDATE delivery_order SET requested_at = ? WHERE order_id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10), order.getId());
        return order;
    }

    @Test
    void AVAILABLE_라이더가_조회하면_200과_WAITING_목록을_반환한다() {
        saveRider("e2e_rider_requests01", "p@ssw0rd", "01022223333", true);
        saveWaitingOrderWithFareSnapshot();
        String cookie = loginAndGetSessionCookie("e2e_rider_requests01", "p@ssw0rd");

        var response = rest.exchange(ENDPOINT, HttpMethod.GET, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().success()).isTrue();
        Map<?, ?> page = (Map<?, ?>) response.getBody().data();
        List<?> items = (List<?>) page.get("items");
        assertThat(items).isNotEmpty();
    }

    @Test
    void 라이더_좌표를_보내면_반경_내_주문만_반환한다() {
        saveRider("e2e_rider_requests11", "p@ssw0rd", "01099998887", true);
        DeliveryOrder near = saveWaitingOrderWithFareSnapshot();
        DeliveryOrder far = saveWaitingOrderAt(new BigDecimal("37.9000000"), new BigDecimal("127.9000000"));
        String cookie = loginAndGetSessionCookie("e2e_rider_requests11", "p@ssw0rd");

        var response = rest.exchange(
                ENDPOINT + "?latitude=37.5000000&longitude=127.0000000&radiusMeters=3000",
                HttpMethod.GET, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> page = (Map<?, ?>) response.getBody().data();
        List<Integer> deliveryIds = ((List<?>) page.get("items")).stream()
                .map(o -> (Integer) ((Map<?, ?>) o).get("deliveryId"))
                .toList();
        assertThat(deliveryIds).contains(near.getId().intValue()).doesNotContain(far.getId().intValue());
    }

    @Test
    void 라이더_좌표가_범위를_벗어나면_400을_반환한다() {
        saveRider("e2e_rider_requests12", "p@ssw0rd", "01099998886", true);
        String cookie = loginAndGetSessionCookie("e2e_rider_requests12", "p@ssw0rd");

        var response = rest.exchange(
                ENDPOINT + "?latitude=91&longitude=127.0000000",
                HttpMethod.GET, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().success()).isFalse();
    }

    @Test
    void 운임_범위_필터를_보내면_범위_내_주문만_반환한다() {
        saveRider("e2e_rider_requests13", "p@ssw0rd", "01099998885", true);
        DeliveryOrder cheap = saveWaitingOrderWithFare(3000L);
        DeliveryOrder expensive = saveWaitingOrderWithFare(9000L);
        String cookie = loginAndGetSessionCookie("e2e_rider_requests13", "p@ssw0rd");

        var response = rest.exchange(ENDPOINT + "?fareMin=5000", HttpMethod.GET, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> page = (Map<?, ?>) response.getBody().data();
        List<Integer> deliveryIds = ((List<?>) page.get("items")).stream()
                .map(o -> (Integer) ((Map<?, ?>) o).get("deliveryId"))
                .toList();
        assertThat(deliveryIds).contains(expensive.getId().intValue()).doesNotContain(cheap.getId().intValue());
    }

    @Test
    void size로_페이지가_잘리고_hasNext가_표시된다() {
        saveRider("e2e_rider_requests14", "p@ssw0rd", "01099998884", true);
        saveWaitingOrderWithFare(3000L);
        saveWaitingOrderWithFare(5000L);
        saveWaitingOrderWithFare(9000L);
        String cookie = loginAndGetSessionCookie("e2e_rider_requests14", "p@ssw0rd");

        var response = rest.exchange(ENDPOINT + "?size=2", HttpMethod.GET, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> page = (Map<?, ?>) response.getBody().data();
        assertThat((List<?>) page.get("items")).hasSize(2);
        assertThat(page.get("hasNext")).isEqualTo(true);
    }

    @Test
    void fareMin이_fareMax보다_크면_400을_반환한다() {
        saveRider("e2e_rider_requests15", "p@ssw0rd", "01099998883", true);
        String cookie = loginAndGetSessionCookie("e2e_rider_requests15", "p@ssw0rd");

        var response = rest.exchange(ENDPOINT + "?fareMin=9000&fareMax=3000",
                HttpMethod.GET, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().success()).isFalse();
    }

    @Test
    void 페이지_크기가_0이면_400을_반환한다() {
        saveRider("e2e_rider_requests16", "p@ssw0rd", "01099998882", true);
        String cookie = loginAndGetSessionCookie("e2e_rider_requests16", "p@ssw0rd");

        var response = rest.exchange(ENDPOINT + "?size=0", HttpMethod.GET, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().success()).isFalse();
    }

    @Test
    void 세션_쿠키가_없으면_401을_반환한다() {
        var response = rest.exchange(ENDPOINT, HttpMethod.GET, withCookie(null), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 라이더_운행_상태가_AVAILABLE이_아니면_403을_반환한다() {
        saveRider("e2e_rider_requests02", "p@ssw0rd", "01055556666", false);
        String cookie = loginAndGetSessionCookie("e2e_rider_requests02", "p@ssw0rd");

        var response = rest.exchange(ENDPOINT, HttpMethod.GET, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().success()).isFalse();
    }

    @Test
    void AVAILABLE_라이더가_상세를_조회하면_200과_상세주소_없는_정보를_반환한다() {
        saveRider("e2e_rider_requests03", "p@ssw0rd", "01011119999", true);
        DeliveryOrder order = saveWaitingOrderWithFareSnapshot();
        String cookie = loginAndGetSessionCookie("e2e_rider_requests03", "p@ssw0rd");

        var response = rest.exchange(ENDPOINT + "/" + order.getId(), HttpMethod.GET, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().success()).isTrue();
        Map<?, ?> data = (Map<?, ?>) response.getBody().data();
        assertThat(data.get("deliveryId")).isEqualTo(order.getId().intValue());
        Map<?, ?> pickup = (Map<?, ?>) data.get("pickup");
        assertThat(pickup.get("detailAddress")).isNull();
    }

    @Test
    void 상세_조회_시_세션_쿠키가_없으면_401을_반환한다() {
        var response = rest.exchange(ENDPOINT + "/1", HttpMethod.GET, withCookie(null), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 상세_조회_시_라이더_운행_상태가_AVAILABLE이_아니면_403을_반환한다() {
        saveRider("e2e_rider_requests04", "p@ssw0rd", "01022224444", false);
        DeliveryOrder order = saveWaitingOrderWithFareSnapshot();
        String cookie = loginAndGetSessionCookie("e2e_rider_requests04", "p@ssw0rd");

        var response = rest.exchange(ENDPOINT + "/" + order.getId(), HttpMethod.GET, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void 존재하지_않는_배송요청_상세를_조회하면_404를_반환한다() {
        saveRider("e2e_rider_requests05", "p@ssw0rd", "01033335555", true);
        String cookie = loginAndGetSessionCookie("e2e_rider_requests05", "p@ssw0rd");

        var response = rest.exchange(ENDPOINT + "/999999999", HttpMethod.GET, withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().success()).isFalse();
    }

    @Test
    void AVAILABLE_라이더가_배차를_확정하면_200과_ASSIGNED_BUSY_결과를_반환한다() {
        saveRider("e2e_rider_requests06", "p@ssw0rd", "01044445556", true);
        DeliveryOrder order = saveWaitingOrderWithFareSnapshot();
        String cookie = loginAndGetSessionCookie("e2e_rider_requests06", "p@ssw0rd");

        var response = rest.exchange(ENDPOINT + "/" + order.getId() + "/accept", HttpMethod.POST,
                withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().data();
        assertThat(data.get("status")).isEqualTo("ASSIGNED");
        assertThat(data.get("operatingStatus")).isEqualTo("BUSY");

        DeliveryOrder persisted = deliveryOrderRepository.findById(order.getId()).orElseThrow();
        assertThat(persisted.getStatus().name()).isEqualTo("ASSIGNED");
    }

    @Test
    void 배차_확정_시_세션_쿠키가_없으면_401을_반환한다() {
        var response = rest.exchange(ENDPOINT + "/1/accept", HttpMethod.POST, withCookie(null), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 배차_확정_시_라이더_운행_상태가_AVAILABLE이_아니면_403을_반환한다() {
        saveRider("e2e_rider_requests07", "p@ssw0rd", "01044445557", false);
        DeliveryOrder order = saveWaitingOrderWithFareSnapshot();
        String cookie = loginAndGetSessionCookie("e2e_rider_requests07", "p@ssw0rd");

        var response = rest.exchange(ENDPOINT + "/" + order.getId() + "/accept", HttpMethod.POST,
                withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void 존재하지_않는_배송요청을_수락하면_404를_반환한다() {
        saveRider("e2e_rider_requests08", "p@ssw0rd", "01044445558", true);
        String cookie = loginAndGetSessionCookie("e2e_rider_requests08", "p@ssw0rd");

        var response = rest.exchange(ENDPOINT + "/999999999/accept", HttpMethod.POST,
                withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 이미_배차된_배송요청을_다시_수락하면_409를_반환한다() {
        saveRider("e2e_rider_requests09", "p@ssw0rd", "01044445559", true);
        saveRider("e2e_rider_requests10", "p@ssw0rd", "01044445560", true);
        DeliveryOrder order = saveWaitingOrderWithFareSnapshot();
        String firstCookie = loginAndGetSessionCookie("e2e_rider_requests09", "p@ssw0rd");
        String secondCookie = loginAndGetSessionCookie("e2e_rider_requests10", "p@ssw0rd");

        var first = rest.exchange(ENDPOINT + "/" + order.getId() + "/accept", HttpMethod.POST,
                withCookie(firstCookie), ApiResponse.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        var second = rest.exchange(ENDPOINT + "/" + order.getId() + "/accept", HttpMethod.POST,
                withCookie(secondCookie), ApiResponse.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().success()).isFalse();
    }

    @Test
    void 배차_대기_타임아웃을_넘긴_주문을_수락하면_409와_취소_환급이_일어난다() {
        // 만료 정리(#42)는 컨트롤러가 수락 트랜잭션 밖에서 먼저 수행한다(#446 커넥션 풀 교착 회피).
        // 실제 HTTP 로 그 배선을 검증한다 — 만료 주문 수락 → 그 자리에서 취소·전액 환급 → 수락은 409.
        saveRider("e2e_rider_requests17", "p@ssw0rd", "01044445561", true);
        long balanceBeforeExpiry = 50_000L;
        DeliveryOrder expired = saveExpiredWaitingOrderWithWallet(balanceBeforeExpiry);
        Long customerId = expired.getCustomer().getId();
        String cookie = loginAndGetSessionCookie("e2e_rider_requests17", "p@ssw0rd");

        var response = rest.exchange(ENDPOINT + "/" + expired.getId() + "/accept", HttpMethod.POST,
                withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().success()).isFalse();

        DeliveryOrder persisted = deliveryOrderRepository.findById(expired.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.CANCELED);
        // 스캐너를 기다리지 않고, 수락 시도 자체가 만료 정리를 트리거해 전액 환급까지 끝나 있어야 한다.
        assertThat(pointWalletRepository.findByMemberId(customerId).orElseThrow().getBalance())
                .isEqualTo(balanceBeforeExpiry);
    }
}
