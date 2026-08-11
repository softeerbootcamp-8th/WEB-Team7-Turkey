package com.turkey.quick.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.turkey.quick.location.sse.TrackingPublisher;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.FareType;
import com.turkey.quick.order.domain.OrderFareSnapshot;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import com.turkey.quick.payment.service.CustomerPaymentService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Redis GEO 와 무관한 순수 시간 판정만 검증한다(#42) — 실제 DB 제약·트랜잭션 원자성은
 * {@code DeliveryTimeoutServiceIntegrationTest} 몫이다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("배차 대기 시간 초과 자동 취소 판단 로직")
class DeliveryTimeoutServiceTest {

    @InjectMocks
    private DeliveryTimeoutService deliveryTimeoutService;

    @Mock
    private DeliveryOrderRepository deliveryOrderRepository;

    @Mock
    private OrderFareSnapshotRepository orderFareSnapshotRepository;

    @Mock
    private CustomerPaymentService customerPaymentService;

    /** #444 CANCELED SSE 발행용. */
    @Mock
    private TrackingPublisher trackingPublisher;

    private static final Long CUSTOMER_ID = 42L;
    private static final Long ORDER_ID = 100L;

    /**
     * 도메인 생성 규칙 검증 대상이 아니므로 Mockito 목으로 상태·시각만 스텁한다.
     *
     * <p>{@code getId}/{@code getRequestedAt} 은 {@code lenient} 다 — {@code expireIfStale} 이
     * 상태에 따라 조기 반환하면 뒤 조건은 평가되지 않아, 상태별 테스트마다 실제로 쓰이는 스텁이
     * 다르다(엄격 스터빙이 미사용 스텁을 실패로 본다).
     */
    private DeliveryOrder mockOrder(OrderStatus status, LocalDateTime requestedAt) {
        DeliveryOrder order = mock(DeliveryOrder.class);
        lenient().when(order.getId()).thenReturn(ORDER_ID);
        given(order.getStatus()).willReturn(status);
        lenient().when(order.getRequestedAt()).thenReturn(requestedAt);
        return order;
    }

    @Nested
    @DisplayName("지연 만료 판정 — expireIfStale")
    class ExpireIfStaleTest {

        @Test
        @DisplayName("진행 중 주문이 없으면 아무 것도 하지 않는다")
        void doesNothingWhenNoActiveOrder() {
            given(deliveryOrderRepository.findActiveByCustomerId(CUSTOMER_ID))
                    .willReturn(Optional.empty());

            boolean result = deliveryTimeoutService.expireIfStale(CUSTOMER_ID);

            assertThat(result).isFalse();
            verifyNoInteractions(customerPaymentService);
        }

        @Test
        @DisplayName("진행 중 주문이 이미 배차되어 WAITING이 아니면 손대지 않는다")
        void doesNothingWhenActiveOrderIsNotWaiting() {
            DeliveryOrder assigned = mockOrder(OrderStatus.ASSIGNED,
                    LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30));
            given(deliveryOrderRepository.findActiveByCustomerId(CUSTOMER_ID))
                    .willReturn(Optional.of(assigned));

            boolean result = deliveryTimeoutService.expireIfStale(CUSTOMER_ID);

            assertThat(result).isFalse();
            verify(deliveryOrderRepository, never()).cancelIfWaiting(any(), any(), any());
        }

        @Test
        @DisplayName("WAITING 이지만 아직 타임아웃 전이면 손대지 않는다")
        void doesNothingWhenWaitingButNotExpiredYet() {
            DeliveryOrder fresh = mockOrder(OrderStatus.WAITING,
                    LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
            given(deliveryOrderRepository.findActiveByCustomerId(CUSTOMER_ID))
                    .willReturn(Optional.of(fresh));

            boolean result = deliveryTimeoutService.expireIfStale(CUSTOMER_ID);

            assertThat(result).isFalse();
            verify(deliveryOrderRepository, never()).cancelIfWaiting(any(), any(), any());
        }

        @Test
        @DisplayName("WAITING 이고 타임아웃을 넘겼으면 취소·환급을 수행한다")
        void cancelsAndRefundsWhenWaitingAndExpired() {
            DeliveryOrder stale = mockOrder(OrderStatus.WAITING,
                    LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10));
            given(deliveryOrderRepository.findActiveByCustomerId(CUSTOMER_ID))
                    .willReturn(Optional.of(stale));
            given(deliveryOrderRepository.cancelIfWaiting(eq(ORDER_ID), any(), any())).willReturn(1);
            OrderFareSnapshot estimate = mock(OrderFareSnapshot.class);
            given(estimate.getTotalFare()).willReturn(5_000L);
            given(orderFareSnapshotRepository.findByOrder_IdAndFareType(ORDER_ID, FareType.ESTIMATE))
                    .willReturn(Optional.of(estimate));
            given(deliveryOrderRepository.getReferenceById(ORDER_ID)).willReturn(stale);

            boolean result = deliveryTimeoutService.expireIfStale(CUSTOMER_ID);

            assertThat(result).isTrue();
            verify(customerPaymentService).refundForCancel(CUSTOMER_ID, stale, 5_000L);
            verify(trackingPublisher).publishStatus(eq(ORDER_ID), eq(OrderStatus.CANCELED), any());
        }
    }

    @Nested
    @DisplayName("취소 + 환급 원자적 처리 — cancelAndRefund")
    class CancelAndRefundTest {

        @Test
        @DisplayName("조건부 UPDATE가 0행이면(이미 배차되었거나 이미 취소됨) 환급하지 않는다")
        void skipsRefundWhenConditionalUpdateAffectsNoRow() {
            given(deliveryOrderRepository.cancelIfWaiting(eq(ORDER_ID), any(), any())).willReturn(0);

            boolean result = deliveryTimeoutService.cancelAndRefund(ORDER_ID, CUSTOMER_ID);

            assertThat(result).isFalse();
            verifyNoInteractions(orderFareSnapshotRepository, customerPaymentService, trackingPublisher);
        }

        @Test
        @DisplayName("취소에 성공하면 예상 운임 스냅샷 금액만큼 환급한다")
        void refundsFareAmountFromEstimateSnapshotOnSuccess() {
            given(deliveryOrderRepository.cancelIfWaiting(eq(ORDER_ID), any(), any())).willReturn(1);
            OrderFareSnapshot estimate = mock(OrderFareSnapshot.class);
            given(estimate.getTotalFare()).willReturn(7_500L);
            given(orderFareSnapshotRepository.findByOrder_IdAndFareType(ORDER_ID, FareType.ESTIMATE))
                    .willReturn(Optional.of(estimate));
            DeliveryOrder orderRef = mock(DeliveryOrder.class);
            given(deliveryOrderRepository.getReferenceById(ORDER_ID)).willReturn(orderRef);

            boolean result = deliveryTimeoutService.cancelAndRefund(ORDER_ID, CUSTOMER_ID);

            assertThat(result).isTrue();
            verify(customerPaymentService).refundForCancel(CUSTOMER_ID, orderRef, 7_500L);
            verify(trackingPublisher).publishStatus(eq(ORDER_ID), eq(OrderStatus.CANCELED), any());
        }

        @Test
        @DisplayName("운임 스냅샷이 없으면 정합성 오류로 예외를 던진다")
        void throwsWhenEstimateSnapshotMissing() {
            given(deliveryOrderRepository.cancelIfWaiting(eq(ORDER_ID), any(), any())).willReturn(1);
            given(orderFareSnapshotRepository.findByOrder_IdAndFareType(ORDER_ID, FareType.ESTIMATE))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> deliveryTimeoutService.cancelAndRefund(ORDER_ID, CUSTOMER_ID))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
