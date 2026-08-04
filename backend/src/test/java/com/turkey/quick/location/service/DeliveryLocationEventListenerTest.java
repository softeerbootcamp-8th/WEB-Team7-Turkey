package com.turkey.quick.location.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.turkey.quick.location.repository.OrderGeoRepository;
import com.turkey.quick.order.dto.DeliveryCreatedEvent;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("배송요청 생성 커밋 후 픽업 좌표 GEO 등록 리스너")
class DeliveryLocationEventListenerTest {

    @InjectMocks
    private DeliveryLocationEventListener listener;

    @Mock
    private OrderGeoRepository orderGeoRepository;

    @Test
    @DisplayName("handle은 이벤트의 배송 id·픽업 좌표 그대로 GEO에 등록한다")
    void handleRegistersPickupCoordinates() {
        DeliveryCreatedEvent event = new DeliveryCreatedEvent(
                42L, new BigDecimal("37.5006000"), new BigDecimal("127.0366000"));

        listener.handle(event);

        verify(orderGeoRepository).registerOrUpdate(
                eq(42L), eq(new BigDecimal("37.5006000")), eq(new BigDecimal("127.0366000")));
    }
}
