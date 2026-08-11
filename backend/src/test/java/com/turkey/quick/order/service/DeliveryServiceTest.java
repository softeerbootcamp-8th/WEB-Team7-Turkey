package com.turkey.quick.order.service;

import com.turkey.quick.order.domain.Address;
import com.turkey.quick.order.domain.FarePolicy;
import com.turkey.quick.order.domain.FarePolicyStatus;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.order.domain.Contact;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.FareType;
import com.turkey.quick.order.domain.OrderFareSnapshot;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.AddressRequest;
import com.turkey.quick.order.dto.ContactRequest;
import com.turkey.quick.order.dto.DeliveryCancelResponse;
import com.turkey.quick.order.dto.DeliveryCreateRequest;
import com.turkey.quick.order.dto.DeliveryCreateResponse;
import com.turkey.quick.order.dto.FareQuoteRequest;
import com.turkey.quick.order.dto.FareQuoteResponse;
import com.turkey.quick.location.sse.TrackingPublisher;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.order.repository.FarePolicyRepository;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import com.turkey.quick.payment.service.CustomerPaymentService;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("배송 운임 및 시간 계산 서비스")
class DeliveryServiceTest {

    @InjectMocks
    private DeliveryService deliveryService;

    @Mock
    private FarePolicyRepository farePolicyRepository;

    @Mock
    private DeliveryOrderRepository deliveryOrderRepository;

    @Mock
    private OrderFareSnapshotRepository orderFareSnapshotRepository;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private CustomerPaymentService customerPaymentService;

    /** #42 지연 만료 호출용. 이 테스트가 보는 분기와 무관하므로 별도 스텁 없이 존재만 시킨다. */
    @Mock
    private DeliveryTimeoutService deliveryTimeoutService;

    /** #444 CANCELED SSE 발행용. */
    @Mock
    private TrackingPublisher trackingPublisher;

    //  픽스 데이터를 상수로 분리
    private static final AddressRequest YANGJAE_STATION = new AddressRequest(
            "양재역", "상세", "54299",
            new BigDecimal("37.4909375"), new BigDecimal("127.0334375"));

    private static final AddressRequest SINSA_STATION = new AddressRequest(
            "신사역", "상세", "54299",
            new BigDecimal("37.5141299"), new BigDecimal("127.0291812"));

    private static final AddressRequest JEJU_AIRPORT = new AddressRequest(
            "제주국제공항", "제주 공항로 2", "63115",
            new BigDecimal("33.510413"), new BigDecimal("126.491353"));

    @Nested
    @DisplayName("거리 계산 알고리즘 검증")
    class DistanceAlgorithmTest {

        @Test
        @DisplayName("하버사인 공식은 평면 피타고라스 공식보다 실측 지도 거리에 더 가까워야 한다")
        void haversineShouldBeMoreAccurateThanPythagoras() {
            // given
            BigDecimal naverRealDistance = new BigDecimal("451.0"); // 네이버 지도 실측 451.0

            // when
            BigDecimal haversine = deliveryService.distance(
                    YANGJAE_STATION.latitude(), YANGJAE_STATION.longitude(),
                    JEJU_AIRPORT.latitude(), JEJU_AIRPORT.longitude()
            );
            BigDecimal pythagoras = deliveryService.pureStraightDistance(
                    YANGJAE_STATION.latitude(), YANGJAE_STATION.longitude(),
                    JEJU_AIRPORT.latitude(), JEJU_AIRPORT.longitude()
            );

            // then
            BigDecimal haversineDiff = haversine.subtract(naverRealDistance).abs();
            BigDecimal pythagorasDiff = pythagoras.subtract(naverRealDistance).abs();

            assertThat(haversineDiff)
                    .as("하버사인 오차가 피타고라스 오차보다 작아야 함")
                    .isLessThan(pythagorasDiff);
        }
    }

    @Nested
    @DisplayName("bounding box 좌표 범위 계산(#367, 라이더 콜 목록 위치 검색)")
    class BoundingBoxTest {

        private static final int RADIUS_METERS = 3000;

        @Test
        @DisplayName("좌표가 없으면 거부한다")
        void shouldRejectNullCoordinates() {
            assertThatThrownBy(() -> deliveryService.boundingBox(null, SINSA_STATION.longitude(), RADIUS_METERS))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> deliveryService.boundingBox(SINSA_STATION.latitude(), null, RADIUS_METERS))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("남북(위도) 경계까지의 실제 거리는 반경(m)과 거의 같다")
        void latBoundaryShouldMatchRadius() {
            BigDecimal lat = SINSA_STATION.latitude();
            BigDecimal lng = SINSA_STATION.longitude();

            DeliveryService.BoundingBox box = deliveryService.boundingBox(lat, lng, RADIUS_METERS);

            double northMeters = deliveryService.distance(lat, lng, box.latMax(), lng)
                    .multiply(BigDecimal.valueOf(1000)).doubleValue();
            double southMeters = deliveryService.distance(lat, lng, box.latMin(), lng)
                    .multiply(BigDecimal.valueOf(1000)).doubleValue();

            assertThat(northMeters).isCloseTo(RADIUS_METERS, Offset.offset(1.0));
            assertThat(southMeters).isCloseTo(RADIUS_METERS, Offset.offset(1.0));
        }

        @Test
        @DisplayName("동서(경도) 경계까지의 실제 거리도 반경(m)에 가깝다(위도 보정 근사)")
        void lngBoundaryShouldBeCloseToRadius() {
            BigDecimal lat = SINSA_STATION.latitude();
            BigDecimal lng = SINSA_STATION.longitude();

            DeliveryService.BoundingBox box = deliveryService.boundingBox(lat, lng, RADIUS_METERS);

            double eastMeters = deliveryService.distance(lat, lng, lat, box.lngMax())
                    .multiply(BigDecimal.valueOf(1000)).doubleValue();
            double westMeters = deliveryService.distance(lat, lng, lat, box.lngMin())
                    .multiply(BigDecimal.valueOf(1000)).doubleValue();

            assertThat(eastMeters).isCloseTo(RADIUS_METERS, Offset.offset(50.0));
            assertThat(westMeters).isCloseTo(RADIUS_METERS, Offset.offset(50.0));
        }

        @Test
        @DisplayName("사각형은 원보다 넓다 — 모서리는 반경보다 멀다")
        void cornerShouldBeFartherThanRadius() {
            BigDecimal lat = SINSA_STATION.latitude();
            BigDecimal lng = SINSA_STATION.longitude();

            DeliveryService.BoundingBox box = deliveryService.boundingBox(lat, lng, RADIUS_METERS);

            double cornerMeters = deliveryService.distance(lat, lng, box.latMax(), box.lngMax())
                    .multiply(BigDecimal.valueOf(1000)).doubleValue();

            assertThat(cornerMeters).isGreaterThan(RADIUS_METERS);
        }
    }

    @Nested
    @DisplayName("예상 운임 및 소요시간 견적")
    class QuoteFareTest {

        @Test
        @DisplayName("활성화된 운임 정책(기본 1.5km 1만원, 100m당 140원)에 따라 정확한 요금을 산정한다")
        void shouldCalculateCorrectFareUsingActivePolicy() {
            // given
            FarePolicy activePolicy = createMockFarePolicy();
            given(farePolicyRepository.findByStatus(FarePolicyStatus.ACTIVE))
                    .willReturn(Optional.of(activePolicy));

            FareQuoteRequest request = new FareQuoteRequest(ItemType.DOCUMENT, YANGJAE_STATION, SINSA_STATION);

            // when
            FareQuoteResponse response = deliveryService.quoteFare(request);

            // then
            assertThat(response.estimatedMinutes()).isPositive();
            assertThat(response.fare().totalFare())
                    .as("기본요금 및 거리 요금 합산 검증")
                    .isEqualTo(5010L);
        }
    }

    /**
     * 배송요청 생성 + 포인트 차감(#37, #40).
     *
     * <p>이 층에서 증명할 것은 <b>서비스의 판단</b>이다: 요금 대조 결과에 따라 저장 경로를 타는가,
     * 멱등 재전송이 차감까지 건너뛰는가, 차감에 넘기는 금액이 요청값이 아니라 서버 계산값인가.
     * 트랜잭션 롤백과 DB 제약(진행 중 1건, 이중 차감)은 여기서 증명되지 않는다 —
     * {@code DeliveryServiceIntegrationTest} 몫이다.
     *
     * <p>요금은 모킹하지 않고 {@code QuoteFareTest} 와 같은 정책 스텁으로 실제 계산한다. 다만
     * 기대 금액을 하드코딩하지 않고 {@link #serverFare()} 로 먼저 구해 쓴다 — 거리 계산이 바뀔 때
     * 이 테스트가 요금 계산의 회귀 테스트 노릇을 하지 않게 하려는 것이다.
     */
    @Nested
    @DisplayName("배송요청 생성")
    class CreateDeliveryTest {

        private static final Long CUSTOMER_ID = 7L;
        private static final String REQUEST_KEY = "6c1f1a0e-6f7a-4b2b-9a3f-6b0d7f2a1c34";

        private void givenActivePolicy() {
            given(farePolicyRepository.findByStatus(FarePolicyStatus.ACTIVE))
                    .willReturn(Optional.of(createMockFarePolicy()));
        }

        /** 같은 좌표·물품에 대해 서버가 산정하는 요금. 요청의 estimatedFare 는 이 값과 맞춰야 통과한다. */
        private long serverFare() {
            return deliveryService.quoteFare(
                            new FareQuoteRequest(ItemType.DOCUMENT, YANGJAE_STATION, SINSA_STATION))
                    .fare().totalFare();
        }

        private DeliveryCreateRequest request(long estimatedFare) {
            return new DeliveryCreateRequest(
                    REQUEST_KEY, estimatedFare, ItemType.DOCUMENT, YANGJAE_STATION, SINSA_STATION,
                    new ContactRequest("김고객", "010-1234-5678"),
                    new ContactRequest("이수령", "010-9876-5432"));
        }

        private void givenNoExistingOrder() {
            given(deliveryOrderRepository.findByCustomer_IdAndRequestKey(CUSTOMER_ID, REQUEST_KEY))
                    .willReturn(Optional.empty());
            given(memberRepository.getReferenceById(CUSTOMER_ID)).willReturn(
                    Member.create("cust01", "hash", "김고객", "01055556666", MemberRole.CUSTOMER));
            given(deliveryOrderRepository.saveAndFlush(any(DeliveryOrder.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
        }

        @Test
        @DisplayName("정상 요청이면 WAITING 주문을 만들고 서버 계산 요금만큼 차감을 요청한다")
        void createsWaitingOrderAndChargesServerFare() {
            givenActivePolicy();
            long fare = serverFare();
            givenNoExistingOrder();

            DeliveryCreateResponse response =
                    deliveryService.createDelivery(request(fare), CUSTOMER_ID);

            assertThat(response.status()).isEqualTo(OrderStatus.WAITING);
            assertThat(response.requestKey()).isEqualTo(REQUEST_KEY);
            assertThat(response.estimatedFare().totalFare()).isEqualTo(fare);

            // 차감 금액은 요청값이 아니라 서버 계산값이다. 요청 멱등키를 원장 키로 그대로 넘긴다.
            verify(customerPaymentService).payForOrder(
                    eq(CUSTOMER_ID), any(DeliveryOrder.class), eq(fare), eq(REQUEST_KEY));
        }

        @Test
        @DisplayName("화면이 안내한 요금과 서버 계산이 다르면 409 로 거부하고 주문도 차감도 하지 않는다")
        void rejectsWhenFareChanged() {
            givenActivePolicy();
            long fare = serverFare();

            Throwable thrown = catchThrowable(
                    () -> deliveryService.createDelivery(request(fare + 1), CUSTOMER_ID));

            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.CONFLICT);

            // 사용자가 동의하지 않은 금액이 빠져나가지 않는다 — 저장 경로 자체를 타지 않는다
            verify(deliveryOrderRepository, never()).saveAndFlush(any());
            verifyNoInteractions(customerPaymentService);
        }

        @Test
        @DisplayName("같은 요청키로 재전송하면 새 주문을 만들지 않고 포인트도 다시 차감하지 않는다")
        void doesNotChargeAgainOnResend() {
            DeliveryOrder existing = DeliveryOrder.request(
                    Member.create("cust01", "hash", "김고객", "01055556666", MemberRole.CUSTOMER),
                    REQUEST_KEY, ItemType.DOCUMENT, 2600,
                    Address.of("픽업", "상세", "06236",
                            YANGJAE_STATION.latitude(), YANGJAE_STATION.longitude()),
                    Address.of("도착", "상세", "06232",
                            SINSA_STATION.latitude(), SINSA_STATION.longitude()),
                    Contact.of("김고객", "01012345678"), Contact.of("이수령", "01098765432"));
            given(deliveryOrderRepository.findByCustomer_IdAndRequestKey(CUSTOMER_ID, REQUEST_KEY))
                    .willReturn(Optional.of(existing));
            // 응답 요금은 지금 다시 계산하지 않고 저장된 ESTIMATE 스냅샷에서 되읽는다
            given(orderFareSnapshotRepository.findByOrder_IdAndFareType(null, FareType.ESTIMATE))
                    .willReturn(Optional.of(OrderFareSnapshot.create(
                            existing, createMockFarePolicy(), FareType.ESTIMATE,
                            "1.0", 2600, 1500L, 3510L, 0L)));

            DeliveryCreateResponse response =
                    deliveryService.createDelivery(request(9_999L), CUSTOMER_ID);

            // estimatedFare 가 서버 계산과 달라도 대조 자체를 하지 않는다 — 이미 확정된 주문이다
            assertThat(response.estimatedFare().totalFare()).isEqualTo(5_010L);
            verify(deliveryOrderRepository, never()).saveAndFlush(any());
            verifyNoInteractions(customerPaymentService);
            verifyNoInteractions(farePolicyRepository);
        }
    }

    /**
     * 배송요청 취소(#47).
     *
     * <p>이 층에서 증명할 것은 서비스의 분기 판단이다 — 소유권·상태별로 어떤 경로를 타는지,
     * 조건부 UPDATE 결과에 따라 환급을 호출하는지 안 하는지. 실제 DB 원자성·동시성(배차 확정과
     * 취소가 동시에 오는 경우)은 {@code DeliveryOrderServiceCancelIntegrationTest} 몫이다.
     */
    @Nested
    @DisplayName("배송요청 취소(#47)")
    class CancelDeliveryTest {

        private static final Long CANCEL_CUSTOMER_ID = 9L;
        private static final Long CANCEL_DELIVERY_ID = 42L;

        private DeliveryOrder waitingOrder() {
            Member customer = Member.create("cust02", "hash", "고객", "01099998888", MemberRole.CUSTOMER);
            DeliveryOrder order = DeliveryOrder.request(customer, "cancel-key-1", ItemType.SMALL_PARCEL, 1000,
                    Address.of("픽업", "상세", "12345",
                            new BigDecimal("37.5000000"), new BigDecimal("127.0000000")),
                    Address.of("도착", "상세", "54321",
                            new BigDecimal("37.6000000"), new BigDecimal("127.1000000")),
                    Contact.of("보내는사람", "01011112222"), Contact.of("받는사람", "01033334444"));
            ReflectionTestUtils.setField(order, "id", CANCEL_DELIVERY_ID);
            return order;
        }

        private OrderFareSnapshot estimateSnapshot(DeliveryOrder order, long totalFare) {
            return OrderFareSnapshot.create(order, createMockFarePolicy(), FareType.ESTIMATE,
                    "1.0", 1000, totalFare, 0L, 0L);
        }

        @Test
        @DisplayName("존재하지 않거나 타인의 주문이면 404이고 아무 것도 하지 않는다")
        void rejectsWhenNotFoundOrNotOwned() {
            given(deliveryOrderRepository.findByIdAndCustomer_Id(CANCEL_DELIVERY_ID, CANCEL_CUSTOMER_ID))
                    .willReturn(Optional.empty());

            Throwable thrown = catchThrowable(
                    () -> deliveryService.cancelDelivery(CANCEL_DELIVERY_ID, CANCEL_CUSTOMER_ID, "사유"));

            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(deliveryOrderRepository, never()).cancelIfWaiting(any(), any(), any());
            verifyNoInteractions(customerPaymentService);
        }

        @Test
        @DisplayName("이미 취소된 주문은 그 결과를 그대로 돌려주고 다시 취소·환급하지 않는다")
        void isIdempotentWhenAlreadyCanceled() {
            DeliveryOrder order = waitingOrder();
            order.cancel("먼저 취소됨");
            given(deliveryOrderRepository.findByIdAndCustomer_Id(CANCEL_DELIVERY_ID, CANCEL_CUSTOMER_ID))
                    .willReturn(Optional.of(order));

            DeliveryCancelResponse response =
                    deliveryService.cancelDelivery(CANCEL_DELIVERY_ID, CANCEL_CUSTOMER_ID, "다시취소");

            assertThat(response.status()).isEqualTo(OrderStatus.CANCELED);
            assertThat(response.canceledAt()).isEqualTo(order.getCanceledAt());
            verify(deliveryOrderRepository, never()).cancelIfWaiting(any(), any(), any());
            verifyNoInteractions(customerPaymentService);
            verifyNoInteractions(trackingPublisher);
        }

        @Test
        @DisplayName("WAITING 주문을 취소하면 상태 전이 후 예상 운임만큼 환급한다")
        void cancelsWaitingOrderAndRefundsEstimatedFare() {
            DeliveryOrder order = waitingOrder();
            given(deliveryOrderRepository.findByIdAndCustomer_Id(CANCEL_DELIVERY_ID, CANCEL_CUSTOMER_ID))
                    .willReturn(Optional.of(order));
            given(deliveryOrderRepository.cancelIfWaiting(eq(CANCEL_DELIVERY_ID), any(), eq("변심")))
                    .willReturn(1);
            given(orderFareSnapshotRepository.findByOrder_IdAndFareType(CANCEL_DELIVERY_ID, FareType.ESTIMATE))
                    .willReturn(Optional.of(estimateSnapshot(order, 4_500L)));
            DeliveryOrder orderRef = waitingOrder();
            given(deliveryOrderRepository.getReferenceById(CANCEL_DELIVERY_ID)).willReturn(orderRef);

            DeliveryCancelResponse response =
                    deliveryService.cancelDelivery(CANCEL_DELIVERY_ID, CANCEL_CUSTOMER_ID, "변심");

            assertThat(response.deliveryId()).isEqualTo(CANCEL_DELIVERY_ID);
            assertThat(response.status()).isEqualTo(OrderStatus.CANCELED);
            verify(customerPaymentService).refundForCancel(CANCEL_CUSTOMER_ID, orderRef, 4_500L);
            verify(trackingPublisher).publishStatus(eq(CANCEL_DELIVERY_ID), eq(OrderStatus.CANCELED), any());
        }

        @Test
        @DisplayName("조건부 UPDATE가 0행이고 재조회 결과가 CANCELED가 아니면(배차 등 다른 전이) 409이고 환급하지 않는다")
        void rejectsWithConflictWhenLostRaceToOtherTransition() {
            DeliveryOrder order = waitingOrder();
            given(deliveryOrderRepository.findByIdAndCustomer_Id(CANCEL_DELIVERY_ID, CANCEL_CUSTOMER_ID))
                    .willReturn(Optional.of(order));
            given(deliveryOrderRepository.cancelIfWaiting(eq(CANCEL_DELIVERY_ID), any(), any()))
                    .willReturn(0);
            DeliveryOrder assigned = waitingOrder();
            ReflectionTestUtils.setField(assigned, "status", OrderStatus.ASSIGNED);
            given(deliveryOrderRepository.findByIdForShare(CANCEL_DELIVERY_ID))
                    .willReturn(Optional.of(assigned));

            Throwable thrown = catchThrowable(
                    () -> deliveryService.cancelDelivery(CANCEL_DELIVERY_ID, CANCEL_CUSTOMER_ID, null));

            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.CONFLICT);
            verifyNoInteractions(customerPaymentService);
            verifyNoInteractions(trackingPublisher);
        }

        @Test
        @DisplayName("조건부 UPDATE가 0행이어도 재조회 결과가 CANCELED면(동시 취소 경쟁에서 짐) 200으로 멱등 응답한다")
        void isIdempotentWhenLostRaceToAnotherCancel() {
            DeliveryOrder order = waitingOrder();
            given(deliveryOrderRepository.findByIdAndCustomer_Id(CANCEL_DELIVERY_ID, CANCEL_CUSTOMER_ID))
                    .willReturn(Optional.of(order));
            given(deliveryOrderRepository.cancelIfWaiting(eq(CANCEL_DELIVERY_ID), any(), any()))
                    .willReturn(0);
            DeliveryOrder canceledByOther = waitingOrder();
            canceledByOther.cancel("다른 요청이 먼저 취소함");
            given(deliveryOrderRepository.findByIdForShare(CANCEL_DELIVERY_ID))
                    .willReturn(Optional.of(canceledByOther));

            DeliveryCancelResponse response =
                    deliveryService.cancelDelivery(CANCEL_DELIVERY_ID, CANCEL_CUSTOMER_ID, "내 취소 시도");

            assertThat(response.deliveryId()).isEqualTo(CANCEL_DELIVERY_ID);
            assertThat(response.status()).isEqualTo(OrderStatus.CANCELED);
            assertThat(response.canceledAt()).isEqualTo(canceledByOther.getCanceledAt());
            verifyNoInteractions(customerPaymentService);
            verifyNoInteractions(trackingPublisher);
        }

        @Test
        @DisplayName("운임 스냅샷이 없으면 데이터 정합성 오류(500)다")
        void rejectsWhenFareSnapshotMissing() {
            DeliveryOrder order = waitingOrder();
            given(deliveryOrderRepository.findByIdAndCustomer_Id(CANCEL_DELIVERY_ID, CANCEL_CUSTOMER_ID))
                    .willReturn(Optional.of(order));
            given(deliveryOrderRepository.cancelIfWaiting(eq(CANCEL_DELIVERY_ID), any(), any()))
                    .willReturn(1);
            given(orderFareSnapshotRepository.findByOrder_IdAndFareType(CANCEL_DELIVERY_ID, FareType.ESTIMATE))
                    .willReturn(Optional.empty());

            Throwable thrown = catchThrowable(
                    () -> deliveryService.cancelDelivery(CANCEL_DELIVERY_ID, CANCEL_CUSTOMER_ID, null));

            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private FarePolicy createMockFarePolicy() {
        return FarePolicy.create(
                "1.0",
                1500L,        // 기본 거리 (1.5km)
                100,          // 추가 거리 단위 (100m)
                130L,         // 단위당 추가 요금 (130원)
                30000,        // 제한 무게
                LocalDateTime.now()
        );
    }
}
