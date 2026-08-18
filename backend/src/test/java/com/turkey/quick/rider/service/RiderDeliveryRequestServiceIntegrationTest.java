package com.turkey.quick.rider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.turkey.quick.common.exception.BusinessException;
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
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.dto.RiderDeliveryRequestAcceptResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestCursor;
import com.turkey.quick.rider.dto.RiderDeliveryRequestDetailResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestFilter;
import com.turkey.quick.rider.dto.RiderDeliveryRequestPageResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestSummaryResponse;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실제 MySQL(JPA 매핑·조회)에 붙여서 콜 목록·상세·수락을 검증한다.
 *
 * <p>라이더-측 GEO 사용처를 제거하면서(#342, 디스커션 #338) 콜 목록은 한동안 라이더 좌표를 읽지
 * 못했다 — 거리·반경 필터가 항상 위치 없음으로 degrade 했었다. #367부터 좌표를 요청 파라미터로
 * 받아 {@code idx_delivery_waiting_location} bounding box 쿼리로 실제 반경 필터링을 한다.
 * 좌표를 안 주면 여전히 이전과 같은 degrade 경로다.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class RiderDeliveryRequestServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private RiderDeliveryRequestService riderDeliveryRequestService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private DeliveryOrderRepository deliveryOrderRepository;

    @Autowired
    private OrderFareSnapshotRepository orderFareSnapshotRepository;

    @Autowired
    private FarePolicyRepository farePolicyRepository;

    @Autowired
    private RiderProfileRepository riderProfileRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Member rider;
    private FarePolicy farePolicy;

    @BeforeEach
    void setUp() {
        // #56부터는 rider_profile 행이 실제로 있어야 accept() 의 조건부 UPDATE(operating_status='AVAILABLE')가
        // 의미를 갖는다. saveRiderProfile(available=true) 로 Member+RiderProfile 을 함께 만든다.
        RiderProfile riderProfile = saveRiderProfile("integration_rider01", "01099998888", true);
        // RiderProfile.member 는 지연 로딩이라 트랜잭션 밖에서 접근하면 LazyInitializationException.
        // memberId(=PK, @MapsId)로 다시 조회한다 — 새 트랜잭션이라 지연 로딩 문제가 없다.
        rider = memberRepository.findById(riderProfile.getMemberId()).orElseThrow();
        farePolicy = farePolicyRepository.save(FarePolicy.create(
                "v1", 3000L, 100, 130L, 30000, LocalDateTime.now().minusDays(1)));
    }

    private AuthenticatedRider authenticatedRider(OperatingStatus status) {
        return new AuthenticatedRider(rider.getId(), rider.getLoginId(), rider.getName(), status);
    }

    private static final RiderDeliveryRequestFilter NO_FILTER =
            new RiderDeliveryRequestFilter(null, null, null, null, null);
    private static final RiderDeliveryRequestCursor FIRST_PAGE =
            new RiderDeliveryRequestCursor(null, null, null, null, null);
    private static final int DEFAULT_SIZE = 20;

    /** #60 이전(필터·정렬 방향·페이지네이션 없음) 동작을 검증하던 기존 테스트가 쓰는 기본 호출. */
    private List<RiderDeliveryRequestSummaryResponse> callDefault(
            AuthenticatedRider rider, BigDecimal latitude, BigDecimal longitude, int radiusMeters, String sort) {
        return riderDeliveryRequestService.getDeliveryRequests(rider, latitude, longitude, radiusMeters, sort,
                NO_FILTER, DEFAULT_SIZE, FIRST_PAGE).items();
    }

    /**
     * Member 와 RiderProfile 을 한 트랜잭션에서 저장한다({@code RiderSessionE2ETest.saveRider}와 같은 이유).
     * RiderProfile.member 는 @MapsId 라서, 서로 다른 트랜잭션에서 저장하면 두 번째 저장 시점에
     * Member 가 detached 상태로 남아 "detached entity passed to persist" 로 실패한다.
     * available=true 면 저장 전에 goOnline() 을 호출해 AVAILABLE 로 만든다(#56 accept() 검증용).
     */
    private RiderProfile saveRiderProfile(String loginId, String phoneNumber, boolean available) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Member member = memberRepository.save(
                    Member.create(loginId, "hash", "다른라이더", phoneNumber, MemberRole.RIDER));
            RiderProfile profile = RiderProfile.create(member);
            if (available) {
                profile.goOnline();
            }
            return riderProfileRepository.save(profile);
        });
    }

    private DeliveryOrder saveWaitingOrder(BigDecimal pickupLat, BigDecimal pickupLon) {
        String uniqueSuffix = String.valueOf(System.nanoTime() % 100_000_000L);
        Member customer = memberRepository.save(
                Member.create("integration_customer_" + uniqueSuffix, "hash", "고객", "010" + uniqueSuffix,
                        MemberRole.CUSTOMER));
        DeliveryOrder order = DeliveryOrder.request(customer, "req-" + System.nanoTime(), ItemType.SMALL_PARCEL, 1000,
                Address.of("픽업지 도로명", "상세", "12345", pickupLat, pickupLon),
                Address.of("도착지 도로명", "상세", "54321", new BigDecimal("37.6000000"), new BigDecimal("127.1000000")),
                Contact.of("보내는사람", "01011112222"), Contact.of("받는사람", "01033334444"));
        DeliveryOrder saved = deliveryOrderRepository.save(order);
        orderFareSnapshotRepository.save(
                OrderFareSnapshot.create(saved, farePolicy, FareType.ESTIMATE, "v1", 1000, 3000L, 130L, 0L));
        return saved;
    }

    /** #60 필터·페이지네이션 검증용 — 운임·배송거리를 직접 지정해 WAITING 주문을 만든다. */
    private DeliveryOrder saveWaitingOrderWithFareAndDistance(long totalFare, int distanceMeters) {
        String uniqueSuffix = String.valueOf(System.nanoTime() % 100_000_000L);
        Member customer = memberRepository.save(
                Member.create("integration_fd_customer_" + uniqueSuffix, "hash", "고객", "010" + uniqueSuffix,
                        MemberRole.CUSTOMER));
        DeliveryOrder order = DeliveryOrder.request(customer, "req-" + System.nanoTime(), ItemType.SMALL_PARCEL,
                distanceMeters,
                Address.of("픽업지 도로명", "상세", "12345", new BigDecimal("37.5010000"), new BigDecimal("127.0010000")),
                Address.of("도착지 도로명", "상세", "54321", new BigDecimal("37.6000000"), new BigDecimal("127.1000000")),
                Contact.of("보내는사람", "01011112222"), Contact.of("받는사람", "01033334444"));
        DeliveryOrder saved = deliveryOrderRepository.save(order);
        orderFareSnapshotRepository.save(
                OrderFareSnapshot.create(saved, farePolicy, FareType.ESTIMATE, "v1", distanceMeters, totalFare, 0L, 0L));
        return saved;
    }

    @Test
    @DisplayName("WAITING 주문만 반환하고, 이미 배차된(ASSIGNED) 주문은 제외한다")
    void shouldReturnOnlyWaitingOrders() {
        DeliveryOrder waiting = saveWaitingOrder(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"));

        DeliveryOrder assignedTarget = saveWaitingOrder(new BigDecimal("37.5020000"), new BigDecimal("127.0020000"));
        RiderProfile assignedRiderProfile = saveRiderProfile("integration_rider02", "01077776666", false);
        assignedTarget.assign(assignedRiderProfile);
        deliveryOrderRepository.save(assignedTarget);

        List<RiderDeliveryRequestSummaryResponse> result = callDefault(
                authenticatedRider(OperatingStatus.AVAILABLE), null, null, 100_000, "REQUESTED_AT");

        assertThat(result).extracting(RiderDeliveryRequestSummaryResponse::deliveryId)
                .contains(waiting.getId())
                .doesNotContain(assignedTarget.getId());
    }

    @Test
    @DisplayName("좌표를 안 주면 반경으로 거르지 않고 거리 필드는 null이다(#55/#367 degrade)")
    void shouldDegradeGracefullyWithoutRiderPosition() {
        // 좁은 반경(100m)을 줘도 먼 주문이 그대로 반환된다 — 좌표를 안 줬으므로 반경 필터가
        // 적용되지 않기 때문이다.
        DeliveryOrder order = saveWaitingOrder(new BigDecimal("37.9000000"), new BigDecimal("127.9000000"));

        List<RiderDeliveryRequestSummaryResponse> result = callDefault(
                authenticatedRider(OperatingStatus.AVAILABLE), null, null, 100, "DISTANCE");

        assertThat(result).extracting(RiderDeliveryRequestSummaryResponse::deliveryId).contains(order.getId());
        assertThat(result.stream().filter(r -> r.deliveryId().equals(order.getId())).findFirst().orElseThrow()
                .distanceToPickupMeters()).isNull();
    }

    @Test
    @DisplayName("좌표를 주면 bounding box 인덱스로 반경 내 주문만 반환하고 거리 필드를 채운다(#367)")
    void shouldFilterByBoundingBoxWhenPositionGiven() {
        BigDecimal riderLat = new BigDecimal("37.5000000");
        BigDecimal riderLng = new BigDecimal("127.0000000");
        // 라이더 위치에서 약 150m — 반경(3km) 안
        DeliveryOrder near = saveWaitingOrder(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"));
        // 라이더 위치에서 수십 km — 반경(3km) 훨씬 밖(사각형 범위 자체에도 안 걸림)
        DeliveryOrder far = saveWaitingOrder(new BigDecimal("37.9000000"), new BigDecimal("127.9000000"));

        List<RiderDeliveryRequestSummaryResponse> result = callDefault(
                authenticatedRider(OperatingStatus.AVAILABLE), riderLat, riderLng, 3000, "DISTANCE");

        assertThat(result).extracting(RiderDeliveryRequestSummaryResponse::deliveryId)
                .contains(near.getId())
                .doesNotContain(far.getId());
        assertThat(result.stream().filter(r -> r.deliveryId().equals(near.getId())).findFirst().orElseThrow()
                .distanceToPickupMeters()).isNotNull();
    }

    @Test
    @DisplayName("사각형(bounding box) 범위엔 들어오지만 실제 반경(원) 밖인 주문은 제외한다(#367)")
    void shouldExcludeBoundingBoxCornerOutsideActualRadius() {
        BigDecimal riderLat = new BigDecimal("37.5000000");
        BigDecimal riderLng = new BigDecimal("127.0000000");
        int radiusMeters = 1000;
        // 반경 1km를 감싸는 사각형의 대각선 모서리 방향 — 사각형 안에는 들어오지만 원(1km) 밖이다
        // (사각형이 원보다 4/π≈1.27배 넓어서 생기는 모서리 후보, #367 처리 흐름 ④의 실제 예시).
        DeliveryOrder corner = saveWaitingOrder(new BigDecimal("37.5088000"), new BigDecimal("127.0112000"));

        List<RiderDeliveryRequestSummaryResponse> result = callDefault(
                authenticatedRider(OperatingStatus.AVAILABLE), riderLat, riderLng, radiusMeters, "DISTANCE");

        assertThat(result).extracting(RiderDeliveryRequestSummaryResponse::deliveryId)
                .doesNotContain(corner.getId());
    }

    @Test
    @DisplayName("예상 정산액은 실제 저장된 ESTIMATE 운임 스냅샷의 총 운임과 같다")
    void shouldExposeExpectedSettlementAmountFromFareSnapshot() {
        DeliveryOrder order = saveWaitingOrder(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"));

        List<RiderDeliveryRequestSummaryResponse> result = callDefault(
                authenticatedRider(OperatingStatus.AVAILABLE), null, null, 100_000, "REQUESTED_AT");

        RiderDeliveryRequestSummaryResponse summary = result.stream()
                .filter(r -> r.deliveryId().equals(order.getId())).findFirst().orElseThrow();
        assertThat(summary.expectedSettlementAmount()).isEqualTo(3130L);
    }

    /**
     * #559: {@code DeliveryOrderRepository.findByStatus} 가 엔터티 대신 {@code
     * WaitingDeliverySummary} 투영을 반환하도록 바뀌었다. 이 메서드는 파생 쿼리라 컬럼 별칭을
     * 직접 쓰지 않으므로, {@code pickup}/{@code destination}(둘 다 {@code @Embeddable Address})
     * 평탄화 getter({@code getPickupRoadAddress()} 등)를 스프링 데이터가 실제로 매핑해주는지가
     * 이 이슈의 핵심 리스크였다 — 불일치하면 해당 필드가 예외 없이 조용히 null이 된다. 목(mock)이
     * 아니라 실제 MySQL로 확인해야 하는 이유가 이것이다.
     */
    @Test
    @DisplayName("좌표 없이 조회해도(findByStatus 경로) 프로젝션이 모든 필드를 채운다 — 임베더블 평탄화 검증(#559)")
    void shouldPopulateAllSummaryFieldsViaFindByStatusProjection() {
        DeliveryOrder order = saveWaitingOrder(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"));

        List<RiderDeliveryRequestSummaryResponse> result = callDefault(
                authenticatedRider(OperatingStatus.AVAILABLE), null, null, 100_000, "REQUESTED_AT");

        RiderDeliveryRequestSummaryResponse summary = result.stream()
                .filter(r -> r.deliveryId().equals(order.getId())).findFirst().orElseThrow();
        assertThat(summary.itemType()).isEqualTo(ItemType.SMALL_PARCEL);
        assertThat(summary.pickupRoadAddress()).isEqualTo("픽업지 도로명");
        assertThat(summary.destinationRoadAddress()).isEqualTo("도착지 도로명");
        assertThat(summary.straightDistanceMeters()).isEqualTo(1000);
        // MySQL DATETIME 컬럼의 소수 자릿수(밀리초)가 자바 LocalDateTime.now()의 마이크로초
        // 정밀도보다 낮아, 저장 전 메모리 값과 라운드트립한 값이 마지막 자릿수에서 갈린다 —
        // 프로젝션과 무관하게 어떤 LocalDateTime 컬럼이든 겪는 문제라 근사 비교로 검증한다.
        assertThat(summary.requestedAt()).isCloseTo(order.getRequestedAt(), within(1, ChronoUnit.SECONDS));
    }

    /**
     * #559: {@code findWaitingOrdersWithinBoundingBox} 는 네이티브 쿼리라 {@code SELECT} 절의
     * {@code AS} 별칭을 {@link com.turkey.quick.order.dto.WaitingDeliverySummary} 의 게터 이름과
     * 손으로 맞춰야 한다 — 오탈자가 나도 컴파일은 통과하고 해당 필드만 null이 되므로 실제 DB로
     * 검증한다.
     */
    @Test
    @DisplayName("좌표를 주고 조회해도(bounding box 경로) 네이티브 프로젝션이 모든 필드를 채운다(#559)")
    void shouldPopulateAllSummaryFieldsViaBoundingBoxProjection() {
        BigDecimal riderLat = new BigDecimal("37.5000000");
        BigDecimal riderLng = new BigDecimal("127.0000000");
        DeliveryOrder order = saveWaitingOrder(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"));

        List<RiderDeliveryRequestSummaryResponse> result = callDefault(
                authenticatedRider(OperatingStatus.AVAILABLE), riderLat, riderLng, 3000, "DISTANCE");

        RiderDeliveryRequestSummaryResponse summary = result.stream()
                .filter(r -> r.deliveryId().equals(order.getId())).findFirst().orElseThrow();
        assertThat(summary.itemType()).isEqualTo(ItemType.SMALL_PARCEL);
        assertThat(summary.pickupRoadAddress()).isEqualTo("픽업지 도로명");
        assertThat(summary.destinationRoadAddress()).isEqualTo("도착지 도로명");
        assertThat(summary.straightDistanceMeters()).isEqualTo(1000);
        // MySQL DATETIME 컬럼의 소수 자릿수(밀리초)가 자바 LocalDateTime.now()의 마이크로초
        // 정밀도보다 낮아, 저장 전 메모리 값과 라운드트립한 값이 마지막 자릿수에서 갈린다 —
        // 프로젝션과 무관하게 어떤 LocalDateTime 컬럼이든 겪는 문제라 근사 비교로 검증한다.
        assertThat(summary.requestedAt()).isCloseTo(order.getRequestedAt(), within(1, ChronoUnit.SECONDS));
        assertThat(summary.distanceToPickupMeters()).isNotNull();
    }

    @Test
    @DisplayName("운임 범위 필터로 실제 DB에서도 범위 밖 주문을 제외한다(#60)")
    void shouldExcludeOrdersOutsideFareRangeInRealDb() {
        DeliveryOrder cheap = saveWaitingOrderWithFareAndDistance(3000L, 1000);
        DeliveryOrder expensive = saveWaitingOrderWithFareAndDistance(9000L, 1000);

        RiderDeliveryRequestPageResponse result = riderDeliveryRequestService.getDeliveryRequests(
                authenticatedRider(OperatingStatus.AVAILABLE), null, null, 100_000, "REQUESTED_AT",
                new RiderDeliveryRequestFilter(5000L, null, null, null, null), DEFAULT_SIZE, FIRST_PAGE);

        assertThat(result.items()).extracting(RiderDeliveryRequestSummaryResponse::deliveryId)
                .contains(expensive.getId())
                .doesNotContain(cheap.getId());
    }

    @Test
    @DisplayName("배송거리 범위 필터로 실제 DB에서도 범위 밖 주문을 제외한다(#60)")
    void shouldExcludeOrdersOutsideDistanceRangeInRealDb() {
        DeliveryOrder near = saveWaitingOrderWithFareAndDistance(4000L, 1000);
        DeliveryOrder far = saveWaitingOrderWithFareAndDistance(4000L, 5000);

        RiderDeliveryRequestPageResponse result = riderDeliveryRequestService.getDeliveryRequests(
                authenticatedRider(OperatingStatus.AVAILABLE), null, null, 100_000, "REQUESTED_AT",
                new RiderDeliveryRequestFilter(null, null, null, 2000, null), DEFAULT_SIZE, FIRST_PAGE);

        assertThat(result.items()).extracting(RiderDeliveryRequestSummaryResponse::deliveryId)
                .contains(near.getId())
                .doesNotContain(far.getId());
    }

    /**
     * keyset 페이지네이션이 offset과 다르게 목록 변경에 안전한지 실제 DB로 검증한다(#60).
     * sort=FARE는 방향을 요청하지 않아도 항상 내림차순(#522)이라, 이 테스트도 내림차순 기준으로
     * 검증한다 — o3(9000)·o2(5000)·o1(3000) 순.
     *
     * <p>1페이지(size=2)를 받은 뒤, 두 페이지 사이에 1페이지에서 이미 보여준 두 항목(o3=9000,
     * o2=5000) "사이" 값(운임 7000)을 가진 새 주문을 끼워 넣는다. offset 방식이었다면 이 삽입으로
     * 뒤 항목이 한 칸씩 밀려 2페이지가 이미 1페이지에서 보여준 o2를 다시 보여주는 중복이 생긴다.
     * keyset은 "커서(o2의 운임+id)보다 작은 값"만 비교하므로 새로 끼어든 항목(7000 — 커서 값
     * 5000보다 큼)이 있어도 o2를 중복해서 보여주지 않고, o1만 정확히 반환한다.
     */
    @Test
    @DisplayName("페이지 사이에 새 주문이 끼어들어도 keyset은 중복 없이 다음 항목만 반환한다(#60/#522, FARE 내림차순)")
    void keysetPaginationSurvivesConcurrentInsertBetweenPages() {
        DeliveryOrder o1 = saveWaitingOrderWithFareAndDistance(3000L, 1000);
        DeliveryOrder o2 = saveWaitingOrderWithFareAndDistance(5000L, 1000);
        DeliveryOrder o3 = saveWaitingOrderWithFareAndDistance(9000L, 1000);

        RiderDeliveryRequestPageResponse firstPage = riderDeliveryRequestService.getDeliveryRequests(
                authenticatedRider(OperatingStatus.AVAILABLE), null, null, 100_000, "FARE",
                NO_FILTER, 2, FIRST_PAGE);
        assertThat(firstPage.items()).extracting(RiderDeliveryRequestSummaryResponse::deliveryId)
                .containsExactly(o3.getId(), o2.getId());

        // 1페이지와 2페이지 사이에 o3·o2 사이 값(7000)을 가진 새 주문이 생긴다.
        saveWaitingOrderWithFareAndDistance(7000L, 1000);

        RiderDeliveryRequestSummaryResponse lastOfFirstPage = firstPage.items().get(1);
        RiderDeliveryRequestCursor afterO2 = new RiderDeliveryRequestCursor(
                null, lastOfFirstPage.expectedSettlementAmount(), null, null, lastOfFirstPage.deliveryId());

        RiderDeliveryRequestPageResponse secondPage = riderDeliveryRequestService.getDeliveryRequests(
                authenticatedRider(OperatingStatus.AVAILABLE), null, null, 100_000, "FARE",
                NO_FILTER, 2, afterO2);

        assertThat(secondPage.items()).extracting(RiderDeliveryRequestSummaryResponse::deliveryId)
                .containsExactly(o1.getId());
        assertThat(secondPage.hasNext()).isFalse();
    }

    @Test
    @DisplayName("[#57] WAITING 주문 상세를 조회하면 상세 주소 없이 반환된다")
    void shouldReturnDetailForWaitingOrder() {
        DeliveryOrder order = saveWaitingOrder(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"));

        RiderDeliveryRequestDetailResponse result = riderDeliveryRequestService.getDeliveryRequest(
                authenticatedRider(OperatingStatus.AVAILABLE), order.getId());

        assertThat(result.deliveryId()).isEqualTo(order.getId());
        assertThat(result.pickup().roadAddress()).isEqualTo("픽업지 도로명");
        assertThat(result.pickup().detailAddress()).isNull();
        assertThat(result.destination().detailAddress()).isNull();
        assertThat(result.estimatedFare().totalFare()).isEqualTo(3130L);
        assertThat(result.estimatedMinutes()).isPositive();
    }

    @Test
    @DisplayName("[#57] 이미 배차된 주문의 상세를 조회하면 404다")
    void shouldRejectDetailForAssignedOrder() {
        DeliveryOrder assignedTarget = saveWaitingOrder(new BigDecimal("37.5020000"), new BigDecimal("127.0020000"));
        RiderProfile assignedRiderProfile = saveRiderProfile("integration_rider03", "01066665555", false);
        assignedTarget.assign(assignedRiderProfile);
        deliveryOrderRepository.save(assignedTarget);

        assertThatThrownBy(() -> riderDeliveryRequestService.getDeliveryRequest(
                authenticatedRider(OperatingStatus.AVAILABLE), assignedTarget.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("[#57] 존재하지 않는 주문 ID를 조회하면 404다")
    void shouldRejectDetailForNonExistentOrder() {
        assertThatThrownBy(() -> riderDeliveryRequestService.getDeliveryRequest(
                authenticatedRider(OperatingStatus.AVAILABLE), 999_999_999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private Member fetchMember(RiderProfile profile) {
        return memberRepository.findById(profile.getMemberId()).orElseThrow();
    }

    @Test
    @DisplayName("[#56] 배차를 확정하면 주문은 ASSIGNED, 라이더는 BUSY로 함께 바뀐다")
    void shouldAssignOrderAndMarkRiderBusyOnSuccess() {
        DeliveryOrder order = saveWaitingOrder(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"));

        RiderDeliveryRequestAcceptResponse result = riderDeliveryRequestService.acceptDeliveryRequest(
                authenticatedRider(OperatingStatus.AVAILABLE), order.getId());

        assertThat(result.status()).isEqualTo(OrderStatus.ASSIGNED);
        assertThat(result.operatingStatus()).isEqualTo(OperatingStatus.BUSY);

        DeliveryOrder persisted = deliveryOrderRepository.findById(order.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.ASSIGNED);
        assertThat(persisted.getAssignedRider().getMemberId()).isEqualTo(rider.getId());
        RiderProfile persistedProfile = riderProfileRepository.findById(rider.getId()).orElseThrow();
        assertThat(persistedProfile.getOperatingStatus()).isEqualTo(OperatingStatus.BUSY);
    }

    @Test
    @DisplayName("[#56] 취소된 주문을 수락하려 하면 409와 취소 사유를 반환하고, 상태는 그대로다")
    void shouldRejectAcceptWhenOrderCanceled() {
        DeliveryOrder order = saveWaitingOrder(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"));
        order.cancel("고객 취소");
        deliveryOrderRepository.save(order);

        assertThatThrownBy(() -> riderDeliveryRequestService.acceptDeliveryRequest(
                authenticatedRider(OperatingStatus.AVAILABLE), order.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("취소")
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        DeliveryOrder persisted = deliveryOrderRepository.findById(order.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.CANCELED);
        RiderProfile persistedProfile = riderProfileRepository.findById(rider.getId()).orElseThrow();
        assertThat(persistedProfile.getOperatingStatus()).isEqualTo(OperatingStatus.AVAILABLE);
    }

    @Test
    @DisplayName("[#56, ADR-006] 두 라이더가 같은 주문을 동시에 수락하면 한 명만 성공한다")
    void shouldAssignExactlyOneRiderOnConcurrentAcceptSameOrder() throws Exception {
        DeliveryOrder order = saveWaitingOrder(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"));
        Member riderX = rider;
        Member riderY = fetchMember(saveRiderProfile("integration_rider_concurrent_y", "01012340000", true));

        int attempts = 2;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(attempts);
        var succeeded = new AtomicInteger();
        var conflicted = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            for (Member candidate : List.of(riderX, riderY)) {
                pool.submit(() -> {
                    try {
                        start.await();
                        riderDeliveryRequestService.acceptDeliveryRequest(
                                new AuthenticatedRider(candidate.getId(), candidate.getLoginId(), candidate.getName(),
                                        OperatingStatus.AVAILABLE),
                                order.getId());
                        succeeded.incrementAndGet();
                    } catch (BusinessException e) {
                        conflicted.incrementAndGet();
                    } catch (Exception ignored) {
                        // 경쟁 패배는 위 BusinessException(409)으로만 오는 게 정상이라, 그 외는 잡되 집계하지 않는다.
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await(10, TimeUnit.SECONDS);
        }

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(conflicted.get()).isEqualTo(1);

        DeliveryOrder persisted = deliveryOrderRepository.findById(order.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.ASSIGNED);
        Long winnerId = persisted.getAssignedRider().getMemberId();
        Long loserId = winnerId.equals(riderX.getId()) ? riderY.getId() : riderX.getId();
        assertThat(riderProfileRepository.findById(winnerId).orElseThrow().getOperatingStatus())
                .isEqualTo(OperatingStatus.BUSY);
        assertThat(riderProfileRepository.findById(loserId).orElseThrow().getOperatingStatus())
                .isEqualTo(OperatingStatus.AVAILABLE);
    }

    @Test
    @DisplayName("[#56, ADR-006] 한 라이더가 서로 다른 두 주문을 동시에 수락하면 한 건만 배차된다")
    void shouldAssignExactlyOneOrderWhenSameRiderRacesTwoOrders() throws Exception {
        DeliveryOrder orderA = saveWaitingOrder(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"));
        DeliveryOrder orderB = saveWaitingOrder(new BigDecimal("37.5020000"), new BigDecimal("127.0020000"));

        int attempts = 2;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(attempts);
        var succeeded = new AtomicInteger();
        var conflicted = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            for (DeliveryOrder target : List.of(orderA, orderB)) {
                pool.submit(() -> {
                    try {
                        start.await();
                        riderDeliveryRequestService.acceptDeliveryRequest(
                                authenticatedRider(OperatingStatus.AVAILABLE), target.getId());
                        succeeded.incrementAndGet();
                    } catch (BusinessException e) {
                        conflicted.incrementAndGet();
                    } catch (Exception ignored) {
                        // no-op
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await(10, TimeUnit.SECONDS);
        }

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(conflicted.get()).isEqualTo(1);

        DeliveryOrder persistedA = deliveryOrderRepository.findById(orderA.getId()).orElseThrow();
        DeliveryOrder persistedB = deliveryOrderRepository.findById(orderB.getId()).orElseThrow();
        long assignedCount = List.of(persistedA, persistedB).stream()
                .filter(o -> o.getStatus() == OrderStatus.ASSIGNED).count();
        assertThat(assignedCount).isEqualTo(1);
        assertThat(riderProfileRepository.findById(rider.getId()).orElseThrow().getOperatingStatus())
                .isEqualTo(OperatingStatus.BUSY);
    }
}
