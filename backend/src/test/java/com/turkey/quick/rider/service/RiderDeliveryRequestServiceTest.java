package com.turkey.quick.rider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.order.domain.Address;
import com.turkey.quick.order.domain.Contact;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.FarePolicy;
import com.turkey.quick.order.domain.FareType;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.domain.OrderFareSnapshot;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import com.turkey.quick.order.service.DeliveryService;
import com.turkey.quick.order.service.DeliveryTimeoutService;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.dto.RiderDeliveryRequestAcceptResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestDetailResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestSummaryResponse;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("라이더 콜(배차 대기 요청) 목록·상세 조회·수락 서비스(#55/#57/#56)")
class RiderDeliveryRequestServiceTest {

    @InjectMocks
    private RiderDeliveryRequestService service;

    @Mock
    private DeliveryOrderRepository deliveryOrderRepository;

    @Mock
    private OrderFareSnapshotRepository orderFareSnapshotRepository;

    @Mock
    private RiderProfileRepository riderProfileRepository;

    @Mock
    private DeliveryService deliveryService;

    /** #42 만료 정리 호출용. 기본적으로 false(만료 아님)를 돌려주면 되므로 스텁 없이 존재만 시킨다. */
    @Mock
    private DeliveryTimeoutService deliveryTimeoutService;

    private static final Long RIDER_ID = 1L;

    private AuthenticatedRider rider(OperatingStatus status) {
        return new AuthenticatedRider(RIDER_ID, "rider01", "홍길동", status);
    }

    private DeliveryOrder order(BigDecimal pickupLat, BigDecimal pickupLon, LocalDateTime requestedAt) {
        Member customer = Member.create("customer01", "hash", "고객", "01000000000", MemberRole.CUSTOMER);
        DeliveryOrder order = DeliveryOrder.request(customer, "key-" + System.identityHashCode(pickupLat),
                ItemType.SMALL_PARCEL, 1000,
                Address.of("픽업지 도로명", "상세", "12345", pickupLat, pickupLon),
                Address.of("도착지 도로명", "상세", "54321", new BigDecimal("37.6000000"), new BigDecimal("127.1000000")),
                Contact.of("보내는사람", "01011112222"), Contact.of("받는사람", "01033334444"));
        ReflectionTestUtils.setField(order, "id", (long) System.identityHashCode(pickupLat));
        ReflectionTestUtils.setField(order, "requestedAt", requestedAt);
        return order;
    }

    private OrderFareSnapshot estimateSnapshot(DeliveryOrder order, long totalFare) {
        FarePolicy policy = FarePolicy.create("v1", 3000L, 100, 130L, 30000, LocalDateTime.now().minusDays(1));
        return OrderFareSnapshot.create(order, policy, FareType.ESTIMATE, "v1", 1000, totalFare, 0L, 0L);
    }

    @Nested
    @DisplayName("사전 조건 검증")
    class PreconditionTest {

        @Test
        @DisplayName("라이더 운행 상태가 AVAILABLE이 아니면 403으로 거부한다")
        void shouldRejectWhenRiderNotAvailable() {
            assertThatThrownBy(() -> service.getDeliveryRequests(rider(OperatingStatus.BUSY), null, null, 3000, "DISTANCE"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("AVAILABLE");
        }

        @Test
        @DisplayName("검색 반경이 0 이하면 거부한다")
        void shouldRejectWhenRadiusNotPositive() {
            assertThatThrownBy(() -> service.getDeliveryRequests(rider(OperatingStatus.AVAILABLE), null, null, 0, "DISTANCE"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("정렬 기준 값이 잘못되면 거부한다")
        void shouldRejectWhenSortInvalid() {
            assertThatThrownBy(() -> service.getDeliveryRequests(rider(OperatingStatus.AVAILABLE), null, null, 3000, "NEAREST"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("WAITING 주문이 없으면 빈 목록을 반환한다")
        void shouldReturnEmptyWhenNoWaitingOrders() {
            given(deliveryOrderRepository.findByStatus(OrderStatus.WAITING)).willReturn(List.of());

            List<RiderDeliveryRequestSummaryResponse> result =
                    service.getDeliveryRequests(rider(OperatingStatus.AVAILABLE), null, null, 3000, "DISTANCE");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("WAITING 주문에 예상 운임 스냅샷이 없으면 데이터 정합성 오류로 취급한다")
        void shouldRejectWhenEstimateSnapshotMissing() {
            DeliveryOrder near = order(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"), LocalDateTime.now());
            given(deliveryOrderRepository.findByStatus(OrderStatus.WAITING)).willReturn(List.of(near));
            given(orderFareSnapshotRepository.findByOrder_IdInAndFareType(List.of(near.getId()), FareType.ESTIMATE))
                    .willReturn(List.of());

            assertThatThrownBy(() -> service.getDeliveryRequests(rider(OperatingStatus.AVAILABLE), null, null, 3000, "DISTANCE"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * 요청에 좌표(latitude/longitude)가 없으면(#367) 위치 없음으로 degrade 한다: 반경 필터
     * 없이 전체 반환, 거리 필드 null, DISTANCE 요청은 REQUESTED_AT 로 대체(#55 계약 확정 —
     * 위치 없음은 에러가 아니다). 좌표가 있을 때의 bounding box 경로는
     * {@link WithRiderPositionTest} 를 본다.
     */
    @Nested
    @DisplayName("좌표 없이 조회하면 위치 없음으로 degrade (#55/#367)")
    class DegradedWithoutRiderPositionTest {

        @Test
        @DisplayName("반경으로 거르지 않고 전체를 반환하며, 거리 필드는 null이다")
        void shouldReturnAllWithNullDistance() {
            DeliveryOrder o1 = order(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"), LocalDateTime.now());
            DeliveryOrder o2 = order(new BigDecimal("37.9000000"), new BigDecimal("127.9000000"), LocalDateTime.now());

            given(deliveryOrderRepository.findByStatus(OrderStatus.WAITING)).willReturn(List.of(o1, o2));
            given(orderFareSnapshotRepository.findByOrder_IdInAndFareType(
                    List.of(o1.getId(), o2.getId()), FareType.ESTIMATE))
                    .willReturn(List.of(estimateSnapshot(o1, 4000L), estimateSnapshot(o2, 4000L)));

            List<RiderDeliveryRequestSummaryResponse> result =
                    service.getDeliveryRequests(rider(OperatingStatus.AVAILABLE), null, null, 3000, "DISTANCE");

            assertThat(result).hasSize(2);
            assertThat(result).allSatisfy(r -> assertThat(r.distanceToPickupMeters()).isNull());
        }

        @Test
        @DisplayName("sort=DISTANCE 를 요청해도 위치가 없으면 REQUESTED_AT(오래된 순)으로 대체한다")
        void shouldFallBackToRequestedAtWhenDistanceRequestedButNoPosition() {
            LocalDateTime older = LocalDateTime.now().minusHours(2);
            LocalDateTime newer = LocalDateTime.now();
            DeliveryOrder newerOrder = order(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"), newer);
            DeliveryOrder olderOrder = order(new BigDecimal("37.5020000"), new BigDecimal("127.0020000"), older);

            given(deliveryOrderRepository.findByStatus(OrderStatus.WAITING)).willReturn(List.of(newerOrder, olderOrder));
            given(orderFareSnapshotRepository.findByOrder_IdInAndFareType(
                    List.of(newerOrder.getId(), olderOrder.getId()), FareType.ESTIMATE))
                    .willReturn(List.of(estimateSnapshot(newerOrder, 4000L), estimateSnapshot(olderOrder, 4000L)));

            List<RiderDeliveryRequestSummaryResponse> result =
                    service.getDeliveryRequests(rider(OperatingStatus.AVAILABLE), null, null, 3000, "DISTANCE");

            assertThat(result).extracting(RiderDeliveryRequestSummaryResponse::deliveryId)
                    .containsExactly(olderOrder.getId(), newerOrder.getId());
        }

        @Test
        @DisplayName("sort=FARE 이면 위치와 무관하게 예상 정산액이 높은 순으로 정렬한다")
        void shouldSortByFareDescending() {
            DeliveryOrder cheap = order(new BigDecimal("37.5001000"), new BigDecimal("127.0001000"), LocalDateTime.now());
            DeliveryOrder expensive = order(new BigDecimal("37.5002000"), new BigDecimal("127.0002000"), LocalDateTime.now());

            given(deliveryOrderRepository.findByStatus(OrderStatus.WAITING)).willReturn(List.of(cheap, expensive));
            given(orderFareSnapshotRepository.findByOrder_IdInAndFareType(
                    List.of(cheap.getId(), expensive.getId()), FareType.ESTIMATE))
                    .willReturn(List.of(estimateSnapshot(cheap, 4000L), estimateSnapshot(expensive, 9000L)));

            List<RiderDeliveryRequestSummaryResponse> result =
                    service.getDeliveryRequests(rider(OperatingStatus.AVAILABLE), null, null, 3000, "FARE");

            assertThat(result).extracting(RiderDeliveryRequestSummaryResponse::expectedSettlementAmount)
                    .containsExactly(9000L, 4000L);
        }
    }

    /**
     * 요청에 좌표가 둘 다 있으면(#367) bounding box 쿼리로 WAITING 후보를 가져오고, 실제 반경
     * 밖인 사각형 모서리 후보는 하버사인 거리로 다시 걸러낸다. 하나만 있으면(다른 하나가 null)
     * 여전히 {@link DegradedWithoutRiderPositionTest} 와 같은 degrade 경로다.
     */
    @Nested
    @DisplayName("좌표가 있으면 bounding box로 조회한다 (#367)")
    class WithRiderPositionTest {

        private static final BigDecimal RIDER_LAT = new BigDecimal("37.5000000");
        private static final BigDecimal RIDER_LNG = new BigDecimal("127.0000000");

        /**
         * {@code getDeliveryRequests}는 좌표를 {@code org.springframework.data.geo.Point}(double)로
         * 왕복시킨 뒤 {@code BigDecimal.valueOf(double)}로 되돌려 {@code deliveryService.distance}에
         * 넘긴다 — 이 과정에서 스케일이 바뀐다(예: "37.5000000" → "37.5"). {@code BigDecimal.equals}는
         * 스케일까지 비교하므로, 스텁의 기대값도 같은 변환을 거친 값이어야 매칭된다.
         */
        private static final BigDecimal RIDER_LAT_VIA_POINT = BigDecimal.valueOf(RIDER_LAT.doubleValue());
        private static final BigDecimal RIDER_LNG_VIA_POINT = BigDecimal.valueOf(RIDER_LNG.doubleValue());

        private final DeliveryService.BoundingBox box = new DeliveryService.BoundingBox(
                new BigDecimal("37.4910000"), new BigDecimal("37.5090000"),
                new BigDecimal("126.9887000"), new BigDecimal("127.0113000"));

        @Test
        @DisplayName("bounding box 쿼리로 조회하고, 전수 스캔(findByStatus)은 호출하지 않는다")
        void shouldQueryBoundingBoxInsteadOfFullScan() {
            DeliveryOrder near = order(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"), LocalDateTime.now());
            given(deliveryService.boundingBox(RIDER_LAT, RIDER_LNG, 1000)).willReturn(box);
            given(deliveryOrderRepository.findWaitingOrdersWithinBoundingBox(
                    box.latMin(), box.latMax(), box.lngMin(), box.lngMax()))
                    .willReturn(List.of(near));
            given(orderFareSnapshotRepository.findByOrder_IdInAndFareType(List.of(near.getId()), FareType.ESTIMATE))
                    .willReturn(List.of(estimateSnapshot(near, 4000L)));
            given(deliveryService.distance(RIDER_LAT_VIA_POINT, RIDER_LNG_VIA_POINT,
                    near.getPickup().getLatitude(), near.getPickup().getLongitude()))
                    .willReturn(new BigDecimal("0.500"));

            List<RiderDeliveryRequestSummaryResponse> result = service.getDeliveryRequests(
                    rider(OperatingStatus.AVAILABLE), RIDER_LAT, RIDER_LNG, 1000, "DISTANCE");

            verify(deliveryOrderRepository, never()).findByStatus(any());
            assertThat(result).extracting(RiderDeliveryRequestSummaryResponse::deliveryId)
                    .containsExactly(near.getId());
            assertThat(result.get(0).distanceToPickupMeters()).isEqualTo(500);
        }

        @Test
        @DisplayName("사각형 후보 중 실제 반경(원) 밖인 것은 결과에서 제외한다")
        void shouldExcludeCandidatesOutsideActualRadius() {
            DeliveryOrder near = order(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"), LocalDateTime.now());
            DeliveryOrder corner = order(new BigDecimal("37.5089000"), new BigDecimal("127.0112000"), LocalDateTime.now());
            given(deliveryService.boundingBox(RIDER_LAT, RIDER_LNG, 1000)).willReturn(box);
            given(deliveryOrderRepository.findWaitingOrdersWithinBoundingBox(
                    box.latMin(), box.latMax(), box.lngMin(), box.lngMax()))
                    .willReturn(List.of(near, corner));
            given(orderFareSnapshotRepository.findByOrder_IdInAndFareType(
                    List.of(near.getId(), corner.getId()), FareType.ESTIMATE))
                    .willReturn(List.of(estimateSnapshot(near, 4000L), estimateSnapshot(corner, 4000L)));
            given(deliveryService.distance(RIDER_LAT_VIA_POINT, RIDER_LNG_VIA_POINT,
                    near.getPickup().getLatitude(), near.getPickup().getLongitude()))
                    .willReturn(new BigDecimal("0.500"));
            given(deliveryService.distance(RIDER_LAT_VIA_POINT, RIDER_LNG_VIA_POINT,
                    corner.getPickup().getLatitude(), corner.getPickup().getLongitude()))
                    .willReturn(new BigDecimal("1.300"));

            List<RiderDeliveryRequestSummaryResponse> result = service.getDeliveryRequests(
                    rider(OperatingStatus.AVAILABLE), RIDER_LAT, RIDER_LNG, 1000, "DISTANCE");

            assertThat(result).extracting(RiderDeliveryRequestSummaryResponse::deliveryId)
                    .containsExactly(near.getId());
        }

        @Test
        @DisplayName("좌표 중 하나만 있으면(경도 없음) 여전히 위치 없음으로 degrade한다")
        void shouldDegradeWhenOnlyOneCoordinateGiven() {
            DeliveryOrder order = order(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"), LocalDateTime.now());
            given(deliveryOrderRepository.findByStatus(OrderStatus.WAITING)).willReturn(List.of(order));
            given(orderFareSnapshotRepository.findByOrder_IdInAndFareType(List.of(order.getId()), FareType.ESTIMATE))
                    .willReturn(List.of(estimateSnapshot(order, 4000L)));

            List<RiderDeliveryRequestSummaryResponse> result = service.getDeliveryRequests(
                    rider(OperatingStatus.AVAILABLE), RIDER_LAT, null, 1000, "DISTANCE");

            verify(deliveryService, never()).boundingBox(any(), any(), anyInt());
            assertThat(result.get(0).distanceToPickupMeters()).isNull();
        }
    }

    @Nested
    @DisplayName("상세 조회(#57)")
    class GetDeliveryRequestTest {

        @Test
        @DisplayName("라이더 운행 상태가 AVAILABLE이 아니면 403으로 거부한다")
        void shouldRejectWhenRiderNotAvailable() {
            assertThatThrownBy(() -> service.getDeliveryRequest(rider(OperatingStatus.BUSY), 1024L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("AVAILABLE");
        }

        @Test
        @DisplayName("존재하지 않는 주문이면 404로 거부한다")
        void shouldRejectWhenOrderNotFound() {
            given(deliveryOrderRepository.findById(1024L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getDeliveryRequest(rider(OperatingStatus.AVAILABLE), 1024L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("이미 배차되어 WAITING이 아닌 주문이면 404로 거부한다")
        void shouldRejectWhenOrderNotWaiting() {
            DeliveryOrder assigned = order(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"), LocalDateTime.now());
            ReflectionTestUtils.setField(assigned, "status", OrderStatus.ASSIGNED);
            given(deliveryOrderRepository.findById(assigned.getId())).willReturn(Optional.of(assigned));

            assertThatThrownBy(() -> service.getDeliveryRequest(rider(OperatingStatus.AVAILABLE), assigned.getId()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("WAITING 주문에 예상 운임 스냅샷이 없으면 데이터 정합성 오류로 취급한다")
        void shouldRejectWhenEstimateSnapshotMissing() {
            DeliveryOrder waiting = order(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"), LocalDateTime.now());
            given(deliveryOrderRepository.findById(waiting.getId())).willReturn(Optional.of(waiting));
            given(orderFareSnapshotRepository.findByOrder_IdAndFareType(waiting.getId(), FareType.ESTIMATE))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getDeliveryRequest(rider(OperatingStatus.AVAILABLE), waiting.getId()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("정상 조회 시 상세 주소는 비우고, 소요시간을 거리로부터 계산해 반환한다")
        void shouldReturnDetailWithoutDetailAddressAndComputedMinutes() {
            DeliveryOrder waiting = order(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"), LocalDateTime.now());
            OrderFareSnapshot estimate = estimateSnapshot(waiting, 6400L);
            given(deliveryOrderRepository.findById(waiting.getId())).willReturn(Optional.of(waiting));
            given(orderFareSnapshotRepository.findByOrder_IdAndFareType(waiting.getId(), FareType.ESTIMATE))
                    .willReturn(Optional.of(estimate));
            given(deliveryService.estimateMinutes(waiting.getStraightDistanceMeters())).willReturn(2);

            RiderDeliveryRequestDetailResponse result =
                    service.getDeliveryRequest(rider(OperatingStatus.AVAILABLE), waiting.getId());

            assertThat(result.deliveryId()).isEqualTo(waiting.getId());
            assertThat(result.pickup().detailAddress()).isNull();
            assertThat(result.destination().detailAddress()).isNull();
            assertThat(result.pickup().roadAddress()).isEqualTo("픽업지 도로명");
            assertThat(result.estimatedMinutes()).isEqualTo(2);
            assertThat(result.estimatedFare().totalFare()).isEqualTo(6400L);
            assertThat(result.expectedSettlementAmount()).isEqualTo(6400L);
        }
    }

    @Nested
    @DisplayName("배차 확정(#56, ADR-006 조건부 UPDATE)")
    class AcceptDeliveryRequestTest {

        @Test
        @DisplayName("라이더 운행 상태가 AVAILABLE이 아니면 403으로 거부한다")
        void shouldRejectWhenRiderNotAvailable() {
            assertThatThrownBy(() -> service.acceptDeliveryRequest(rider(OperatingStatus.BUSY), 1024L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("AVAILABLE");
        }

        @Test
        @DisplayName("주문 조건부 UPDATE가 0행이고 주문이 존재하지 않으면 404다")
        void shouldRejectWith404WhenOrderNotFound() {
            given(deliveryOrderRepository.assignIfWaiting(eq(1024L), eq(RIDER_ID), any(LocalDateTime.class)))
                    .willReturn(0);
            given(deliveryOrderRepository.findById(1024L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.acceptDeliveryRequest(rider(OperatingStatus.AVAILABLE), 1024L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("주문 조건부 UPDATE가 0행이고 주문이 취소됐으면 409와 취소 사유를 반환한다")
        void shouldRejectWith409WhenOrderCanceled() {
            DeliveryOrder canceled = order(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"), LocalDateTime.now());
            ReflectionTestUtils.setField(canceled, "status", OrderStatus.CANCELED);
            given(deliveryOrderRepository.assignIfWaiting(eq(canceled.getId()), eq(RIDER_ID), any(LocalDateTime.class)))
                    .willReturn(0);
            given(deliveryOrderRepository.findById(canceled.getId())).willReturn(Optional.of(canceled));

            assertThatThrownBy(() -> service.acceptDeliveryRequest(rider(OperatingStatus.AVAILABLE), canceled.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("취소")
                    .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("주문 조건부 UPDATE가 0행이고 이미 다른 라이더에게 배차됐으면 409를 반환한다")
        void shouldRejectWith409WhenAlreadyAssignedToAnotherRider() {
            DeliveryOrder assigned = order(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"), LocalDateTime.now());
            ReflectionTestUtils.setField(assigned, "status", OrderStatus.ASSIGNED);
            given(deliveryOrderRepository.assignIfWaiting(eq(assigned.getId()), eq(RIDER_ID), any(LocalDateTime.class)))
                    .willReturn(0);
            given(deliveryOrderRepository.findById(assigned.getId())).willReturn(Optional.of(assigned));

            assertThatThrownBy(() -> service.acceptDeliveryRequest(rider(OperatingStatus.AVAILABLE), assigned.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("이미 다른 라이더")
                    .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("같은 라이더가 다른 주문과 동시에 경쟁해 uk_delivery_active_rider를 위반하면 409로 변환한다")
        void shouldConvertUniqueConstraintViolationTo409() {
            given(deliveryOrderRepository.assignIfWaiting(eq(1024L), eq(RIDER_ID), any(LocalDateTime.class)))
                    .willThrow(new DataIntegrityViolationException("uk_delivery_active_rider"));

            assertThatThrownBy(() -> service.acceptDeliveryRequest(rider(OperatingStatus.AVAILABLE), 1024L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("주문 UPDATE는 성공했지만 라이더 UPDATE가 0행이면 409로 실패한다(부분 성공 없음)")
        void shouldRejectWith409WhenRiderNoLongerAvailable() {
            given(deliveryOrderRepository.assignIfWaiting(eq(1024L), eq(RIDER_ID), any(LocalDateTime.class)))
                    .willReturn(1);
            given(riderProfileRepository.markBusyIfAvailable(RIDER_ID)).willReturn(0);

            assertThatThrownBy(() -> service.acceptDeliveryRequest(rider(OperatingStatus.AVAILABLE), 1024L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("이미 다른 배송을 수행 중")
                    .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("두 조건부 UPDATE가 모두 성공하면 ASSIGNED·BUSY 결과를 반환한다")
        void shouldReturnAcceptResponseWhenBothUpdatesSucceed() {
            DeliveryOrder assigned = order(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"), LocalDateTime.now());
            ReflectionTestUtils.setField(assigned, "status", OrderStatus.ASSIGNED);
            ReflectionTestUtils.setField(assigned, "assignedAt", LocalDateTime.now());
            given(deliveryOrderRepository.assignIfWaiting(eq(assigned.getId()), eq(RIDER_ID), any(LocalDateTime.class)))
                    .willReturn(1);
            given(riderProfileRepository.markBusyIfAvailable(RIDER_ID)).willReturn(1);
            given(deliveryOrderRepository.findById(assigned.getId())).willReturn(Optional.of(assigned));

            RiderDeliveryRequestAcceptResponse result =
                    service.acceptDeliveryRequest(rider(OperatingStatus.AVAILABLE), assigned.getId());

            assertThat(result.deliveryId()).isEqualTo(assigned.getId());
            assertThat(result.status()).isEqualTo(OrderStatus.ASSIGNED);
            assertThat(result.operatingStatus()).isEqualTo(OperatingStatus.BUSY);
            assertThat(result.assignedAt()).isNotNull();
        }
    }
}
