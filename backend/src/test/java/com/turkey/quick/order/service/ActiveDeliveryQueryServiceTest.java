package com.turkey.quick.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.turkey.quick.order.domain.Address;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.ActiveDeliveryResponse;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 진행 중 배송 요약 조회의 매핑·빈 결과 처리만 본다(#100). "진행 중"의 정의와 ≤1건 보장은
 * {@code active_customer_id} 생성 컬럼(UNIQUE)에 있어 DB 통합 테스트 몫이므로, 여기서는 리포지토리를
 * 목으로 두고 서비스가 Optional 을 어떻게 다루는지만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("진행 중 배송 요약 조회 로직")
class ActiveDeliveryQueryServiceTest {

    private static final Long CUSTOMER_ID = 7L;

    @InjectMocks
    private ActiveDeliveryQueryService activeDeliveryQueryService;

    @Mock
    private DeliveryOrderRepository deliveryOrderRepository;

    @Test
    @DisplayName("진행 중 배송이 있으면 상태·출발지·도착지·요청시각을 요약해 반환한다")
    void returnsSummaryWhenActiveExists() {
        Address pickup = mock(Address.class);
        given(pickup.getRoadAddress()).willReturn("서울 강남구 테헤란로 152");
        Address destination = mock(Address.class);
        given(destination.getRoadAddress()).willReturn("서울 송파구 올림픽로 300");

        LocalDateTime requestedAt = LocalDateTime.of(2026, 7, 28, 2, 10);
        DeliveryOrder order = mock(DeliveryOrder.class);
        given(order.getId()).willReturn(1024L);
        given(order.getStatus()).willReturn(OrderStatus.ASSIGNED);
        given(order.getPickup()).willReturn(pickup);
        given(order.getDestination()).willReturn(destination);
        given(order.getRequestedAt()).willReturn(requestedAt);
        given(deliveryOrderRepository.findActiveByCustomerId(CUSTOMER_ID)).willReturn(Optional.of(order));

        ActiveDeliveryResponse response = activeDeliveryQueryService.getActiveDelivery(CUSTOMER_ID);

        assertThat(response).isNotNull();
        assertThat(response.deliveryId()).isEqualTo(1024L);
        assertThat(response.status()).isEqualTo(OrderStatus.ASSIGNED);
        assertThat(response.pickupRoadAddress()).isEqualTo("서울 강남구 테헤란로 152");
        assertThat(response.destinationRoadAddress()).isEqualTo("서울 송파구 올림픽로 300");
        assertThat(response.requestedAt()).isEqualTo(requestedAt);
    }

    @Test
    @DisplayName("진행 중 배송이 없으면 null 을 반환한다(빈 결과)")
    void returnsNullWhenNoActiveDelivery() {
        given(deliveryOrderRepository.findActiveByCustomerId(CUSTOMER_ID)).willReturn(Optional.empty());

        assertThat(activeDeliveryQueryService.getActiveDelivery(CUSTOMER_ID)).isNull();
    }
}
