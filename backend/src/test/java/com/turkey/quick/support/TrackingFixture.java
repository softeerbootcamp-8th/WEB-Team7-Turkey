package com.turkey.quick.support;

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
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실시간 추적(#77·#78) 테스트가 쓰는 배송 픽스처.
 *
 * <p><b>왜 픽스처로 만드나</b>: 배차 기능이 아직 없다({@code matching/} 이 비어 있고
 * {@code DeliveryOrder.assign()} 의 호출자가 0명이다). HTTP 로 주문을 {@code ASSIGNED} 로
 * 만드는 경로가 존재하지 않으므로 도메인 메서드를 직접 불러 상태를 만든다. 그래서
 * <b>"배차 직후 구독"의 실제 타이밍은 이 테스트들이 검증하지 못한다</b> — 알려진 공백이다.
 *
 * <p>공용 클래스로 뺀 이유: 통합·E2E·2인스턴스 테스트 세 곳이 같은 상태의 주문을 필요로 하고,
 * 아래 두 제약을 각자 다시 발견하게 두면 반드시 한 곳이 틀린다.
 *
 * <ul>
 *   <li><b>시나리오마다 고객·라이더를 새로 만든다.</b> {@code uk_delivery_active_customer} 와
 *       {@code uk_delivery_active_rider}(생성 컬럼 기반 UNIQUE)가 "진행 중 1건"을 DB 수준에서
 *       강제하므로, 같은 회원으로 진행 중 주문을 두 건 만들면 유니크 위반이 난다.</li>
 *   <li><b>상태는 전이 메서드를 순서대로 밟아 만든다.</b> {@code status} 를 직접 세팅할 수단이
 *       없고(setter 가 없다), 각 전이 메서드가 시각 컬럼을 함께 채워야 DDL CHECK
 *       ({@code ck_delivery_assignment} 등)를 통과한다. 즉 이 픽스처가 통과하는 것 자체가
 *       앱과 DB 제약이 어긋나지 않았다는 검증이다.</li>
 * </ul>
 */
@Component
@Profile("integration")
@Transactional
public class TrackingFixture {
    // @Transactional 을 메서드가 아니라 클래스에 둔 이유: assignedDelivery() 가
    // deliveryWithStatus() 를 자기 호출하는데, 자기 호출은 프록시를 지나지 않아 메서드에만 달면
    // 트랜잭션이 걸리지 않는다. 그러면 memberRepository.save() 가 각자 트랜잭션에서 커밋되어
    // detached Member 를 돌려주고, RiderProfile 의 cascade persist 가
    // "detached entity passed to persist" 로 터진다.

    /** 로그인 E2E 가 쓰는 것과 같은 값. 픽스처로 만든 회원으로 실제 로그인할 수 있어야 한다. */
    public static final String PASSWORD = "p@ssw0rd";

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    /** 주문 직선거리와 요금 구성. 3,200m / 100m 당 130원 → 거리요금 4,160원. */
    private static final int DISTANCE_METERS = 3_200;
    private static final long BASE_FARE = 3_000L;
    private static final long DISTANCE_FARE = 4_160L;

    /**
     * 로그인 아이디·휴대전화 번호를 겹치지 않게 만드는 카운터. {@code DatabaseCleaner} 가 매 테스트
     * 전에 TRUNCATE 하므로 테스트 사이의 충돌은 없지만, <b>한 테스트가 시나리오를 여러 개 만드는</b>
     * 경우(타인 주문 거부, 상태별 거부)에는 필요하다. JVM 전역으로 늘어나도 무해하다.
     */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final MemberRepository memberRepository;
    private final RiderProfileRepository riderProfileRepository;
    private final DeliveryOrderRepository deliveryOrderRepository;
    private final FarePolicyRepository farePolicyRepository;
    private final OrderFareSnapshotRepository orderFareSnapshotRepository;

    public TrackingFixture(MemberRepository memberRepository,
                           RiderProfileRepository riderProfileRepository,
                           DeliveryOrderRepository deliveryOrderRepository,
                           FarePolicyRepository farePolicyRepository,
                           OrderFareSnapshotRepository orderFareSnapshotRepository) {
        this.memberRepository = memberRepository;
        this.riderProfileRepository = riderProfileRepository;
        this.deliveryOrderRepository = deliveryOrderRepository;
        this.farePolicyRepository = farePolicyRepository;
        this.orderFareSnapshotRepository = orderFareSnapshotRepository;
    }

    /**
     * 한 시나리오가 만든 식별자들. 로그인 아이디를 함께 돌려주는 이유는 E2E 가 실제로 로그인해
     * 세션 쿠키를 얻어야 하기 때문이다.
     */
    public record Scenario(
            Long customerId,
            String customerLoginId,
            Long riderId,
            String riderLoginId,
            Long deliveryId,
            Long totalFare
    ) {
    }

    /** 가장 많이 쓰는 형태 — 라이더가 배정된 직후 상태. */
    public Scenario assignedDelivery() {
        return deliveryWithStatus(OrderStatus.ASSIGNED);
    }

    /**
     * 지정한 상태의 배송을 만든다.
     *
     * @param status {@code CANCELED} 는 {@code WAITING} 에서만 도달할 수 있어 라이더가 배정되지
     *               않는다(그래서 반환된 {@code riderId} 는 그 라이더의 프로필 id 이지만 주문에는
     *               붙어 있지 않다). MVP 에서 배차 이후 취소는 범위 밖이다
     */
    public Scenario deliveryWithStatus(OrderStatus status) {
        int n = SEQUENCE.incrementAndGet();
        Member customer = memberRepository.save(member(n, MemberRole.CUSTOMER));
        Member riderMember = memberRepository.save(member(n, MemberRole.RIDER));

        RiderProfile rider = RiderProfile.create(riderMember);
        rider.goOnline();
        riderProfileRepository.save(rider);

        DeliveryOrder order = DeliveryOrder.request(
                customer, UUID.randomUUID().toString(), ItemType.DOCUMENT, DISTANCE_METERS,
                address("서울 강남구 테헤란로 152", "06236", "37.4979", "127.0276"),
                address("서울 중구 세종대로 110", "04524", "37.5665", "126.9780"),
                Contact.of("김보내", "01011112222"),
                Contact.of("박받는", "01033334444"));
        advanceTo(order, rider, status);
        deliveryOrderRepository.save(order);

        // 예상 운임 스냅샷(#79). 실제로는 주문 생성 트랜잭션이 함께 만드는 값인데 그 API 가 아직
        // 스텁이라 픽스처가 대신 만든다. 정책을 시나리오마다 새로 만드는 이유는
        // uk_fare_policy_version 때문이고, INACTIVE 로 남겨 두면 uk_fare_policy_active 와도
        // 충돌하지 않는다(추적 조회는 정책 상태를 보지 않는다).
        FarePolicy policy = farePolicyRepository.save(FarePolicy.create(
                "fx-v" + n, BASE_FARE, 100, 130L, 30_000, LocalDateTime.now().minusDays(1)));
        orderFareSnapshotRepository.save(OrderFareSnapshot.create(
                order, policy, FareType.ESTIMATE, policy.getPolicyVersion(),
                DISTANCE_METERS, BASE_FARE, DISTANCE_FARE, 0L));

        return new Scenario(customer.getId(), customer.getLoginId(),
                rider.getMemberId(), riderMember.getLoginId(), order.getId(),
                BASE_FARE + DISTANCE_FARE);
    }

    /**
     * 목표 상태까지 전이 메서드를 순서대로 밟는다. {@code WAITING} 은 생성 직후 상태라 아무것도
     * 하지 않는다. fall-through 를 쓰지 않고 각 case 를 명시한 이유는 어느 상태가 어느 전이를
     * 요구하는지 읽는 사람이 바로 보게 하려는 것이다.
     */
    private static void advanceTo(DeliveryOrder order, RiderProfile rider, OrderStatus status) {
        switch (status) {
            case WAITING -> {
            }
            case CANCELED -> order.cancel("테스트 취소");
            case ASSIGNED -> order.assign(rider);
            case MOVING_TO_PICKUP -> {
                order.assign(rider);
                order.startMovingToPickup();
            }
            case PICKED_UP -> {
                order.assign(rider);
                order.startMovingToPickup();
                order.pickUp();
            }
            case DELIVERING -> {
                advanceTo(order, rider, OrderStatus.PICKED_UP);
                order.startDelivering();
            }
            case COMPLETED -> {
                advanceTo(order, rider, OrderStatus.DELIVERING);
                order.complete();
            }
        }
    }

    private static Member member(int sequence, MemberRole role) {
        String prefix = role == MemberRole.CUSTOMER ? "fx_customer_" : "fx_rider_";
        // 010 + 8자리 = 11자리. 고객과 라이더가 같은 번호를 쓰면 unique 제약에 걸리므로 role 로 갈라 둔다.
        String phoneNumber = "010%s%06d".formatted(role == MemberRole.CUSTOMER ? "10" : "20", sequence);
        return Member.create(prefix + sequence, PASSWORD_ENCODER.encode(PASSWORD),
                role == MemberRole.CUSTOMER ? "김고객" : "홍라이더", phoneNumber, role);
    }

    private static Address address(String roadAddress, String postalCode, String latitude, String longitude) {
        return Address.of(roadAddress, "101호", postalCode,
                new BigDecimal(latitude), new BigDecimal(longitude));
    }
}
