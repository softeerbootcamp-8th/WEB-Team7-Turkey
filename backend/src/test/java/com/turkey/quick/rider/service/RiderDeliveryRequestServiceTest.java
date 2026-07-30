package com.turkey.quick.rider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.location.repository.RiderGeoRepository;
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
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.dto.RiderDeliveryRequestDetailResponse;
import com.turkey.quick.rider.dto.RiderDeliveryRequestSummaryResponse;
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
import org.springframework.data.geo.Point;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("라이더 콜(배차 대기 요청) 목록·상세 조회 서비스(#55/#57)")
class RiderDeliveryRequestServiceTest {

    @InjectMocks
    private RiderDeliveryRequestService service;

    @Mock
    private DeliveryOrderRepository deliveryOrderRepository;

    @Mock
    private OrderFareSnapshotRepository orderFareSnapshotRepository;

    @Mock
    private RiderGeoRepository riderGeoRepository;

    @Mock
    private DeliveryService deliveryService;

    private static final Long RIDER_ID = 1L;
    private static final BigDecimal RIDER_LAT = new BigDecimal("37.5000000");
    private static final BigDecimal RIDER_LON = new BigDecimal("127.0000000");

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
            assertThatThrownBy(() -> service.getDeliveryRequests(rider(OperatingStatus.BUSY), 3000, "DISTANCE"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("AVAILABLE");
        }

        @Test
        @DisplayName("검색 반경이 0 이하면 거부한다")
        void shouldRejectWhenRadiusNotPositive() {
            assertThatThrownBy(() -> service.getDeliveryRequests(rider(OperatingStatus.AVAILABLE), 0, "DISTANCE"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("정렬 기준 값이 잘못되면 거부한다")
        void shouldRejectWhenSortInvalid() {
            assertThatThrownBy(() -> service.getDeliveryRequests(rider(OperatingStatus.AVAILABLE), 3000, "NEAREST"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("WAITING 주문이 없으면 빈 목록을 반환한다")
        void shouldReturnEmptyWhenNoWaitingOrders() {
            given(deliveryOrderRepository.findByStatus(OrderStatus.WAITING)).willReturn(List.of());

            List<RiderDeliveryRequestSummaryResponse> result =
                    service.getDeliveryRequests(rider(OperatingStatus.AVAILABLE), 3000, "DISTANCE");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("WAITING 주문에 예상 운임 스냅샷이 없으면 데이터 정합성 오류로 취급한다")
        void shouldRejectWhenEstimateSnapshotMissing() {
            DeliveryOrder near = order(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"), LocalDateTime.now());
            given(deliveryOrderRepository.findByStatus(OrderStatus.WAITING)).willReturn(List.of(near));
            given(orderFareSnapshotRepository.findByOrder_IdInAndFareType(List.of(near.getId()), FareType.ESTIMATE))
                    .willReturn(List.of());
            given(riderGeoRepository.findPosition(RIDER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getDeliveryRequests(rider(OperatingStatus.AVAILABLE), 3000, "DISTANCE"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("라이더 위치를 아는 경우")
    class WithRiderPositionTest {

        @Test
        @DisplayName("반경 밖 주문은 제외하고, 반경 안 주문은 거리와 함께 반환한다")
        void shouldFilterByRadiusAndFillDistance() {
            DeliveryOrder near = order(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"), LocalDateTime.now());
            DeliveryOrder far = order(new BigDecimal("37.9000000"), new BigDecimal("127.9000000"), LocalDateTime.now());

            given(deliveryOrderRepository.findByStatus(OrderStatus.WAITING)).willReturn(List.of(near, far));
            given(orderFareSnapshotRepository.findByOrder_IdInAndFareType(
                    List.of(near.getId(), far.getId()), FareType.ESTIMATE))
                    .willReturn(List.of(estimateSnapshot(near, 5000L), estimateSnapshot(far, 5000L)));
            given(riderGeoRepository.findPosition(RIDER_ID))
                    .willReturn(Optional.of(new Point(RIDER_LON.doubleValue(), RIDER_LAT.doubleValue())));

            given(deliveryService.distance(argThat(bd -> bd != null && bd.compareTo(RIDER_LAT) == 0),
                    argThat(bd -> bd != null && bd.compareTo(RIDER_LON) == 0),
                    eq(near.getPickup().getLatitude()), eq(near.getPickup().getLongitude())))
                    .willReturn(new BigDecimal("0.50000000"));
            given(deliveryService.distance(argThat(bd -> bd != null && bd.compareTo(RIDER_LAT) == 0),
                    argThat(bd -> bd != null && bd.compareTo(RIDER_LON) == 0),
                    eq(far.getPickup().getLatitude()), eq(far.getPickup().getLongitude())))
                    .willReturn(new BigDecimal("60.00000000"));

            List<RiderDeliveryRequestSummaryResponse> result =
                    service.getDeliveryRequests(rider(OperatingStatus.AVAILABLE), 3000, "DISTANCE");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).deliveryId()).isEqualTo(near.getId());
            assertThat(result.get(0).distanceToPickupMeters()).isEqualTo(500);
        }

        @Test
        @DisplayName("sort=FARE 이면 예상 정산액이 높은 순으로 정렬한다")
        void shouldSortByFareDescending() {
            DeliveryOrder cheap = order(new BigDecimal("37.5001000"), new BigDecimal("127.0001000"), LocalDateTime.now());
            DeliveryOrder expensive = order(new BigDecimal("37.5002000"), new BigDecimal("127.0002000"), LocalDateTime.now());

            given(deliveryOrderRepository.findByStatus(OrderStatus.WAITING)).willReturn(List.of(cheap, expensive));
            given(orderFareSnapshotRepository.findByOrder_IdInAndFareType(
                    List.of(cheap.getId(), expensive.getId()), FareType.ESTIMATE))
                    .willReturn(List.of(estimateSnapshot(cheap, 4000L), estimateSnapshot(expensive, 9000L)));
            given(riderGeoRepository.findPosition(RIDER_ID)).willReturn(Optional.empty());

            List<RiderDeliveryRequestSummaryResponse> result =
                    service.getDeliveryRequests(rider(OperatingStatus.AVAILABLE), 3000, "FARE");

            assertThat(result).extracting(RiderDeliveryRequestSummaryResponse::expectedSettlementAmount)
                    .containsExactly(9000L, 4000L);
        }
    }

    @Nested
    @DisplayName("라이더 위치를 모르는 경우(graceful degrade, #55 계약 확정)")
    class WithoutRiderPositionTest {

        @Test
        @DisplayName("반경으로 거르지 않고 전체를 반환하며, 거리 필드는 null이다")
        void shouldReturnAllWithNullDistance() {
            DeliveryOrder o1 = order(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"), LocalDateTime.now());
            DeliveryOrder o2 = order(new BigDecimal("37.9000000"), new BigDecimal("127.9000000"), LocalDateTime.now());

            given(deliveryOrderRepository.findByStatus(OrderStatus.WAITING)).willReturn(List.of(o1, o2));
            given(orderFareSnapshotRepository.findByOrder_IdInAndFareType(
                    List.of(o1.getId(), o2.getId()), FareType.ESTIMATE))
                    .willReturn(List.of(estimateSnapshot(o1, 4000L), estimateSnapshot(o2, 4000L)));
            given(riderGeoRepository.findPosition(RIDER_ID)).willReturn(Optional.empty());

            List<RiderDeliveryRequestSummaryResponse> result =
                    service.getDeliveryRequests(rider(OperatingStatus.AVAILABLE), 3000, "DISTANCE");

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
            given(riderGeoRepository.findPosition(RIDER_ID)).willReturn(Optional.empty());

            List<RiderDeliveryRequestSummaryResponse> result =
                    service.getDeliveryRequests(rider(OperatingStatus.AVAILABLE), 3000, "DISTANCE");

            assertThat(result).extracting(RiderDeliveryRequestSummaryResponse::deliveryId)
                    .containsExactly(olderOrder.getId(), newerOrder.getId());
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
}
