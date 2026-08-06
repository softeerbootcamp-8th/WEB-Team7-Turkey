package com.turkey.quick.location.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.location.dto.CustomerDeliveryLocationResponse;
import com.turkey.quick.location.dto.LocationPayload;
import com.turkey.quick.location.repository.RiderLocationRepository;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.TrackableDelivery;
import com.turkey.quick.order.service.DeliveryTrackingAccessService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * 인가·소유권 판정은 {@link DeliveryTrackingAccessService}에 완전히 위임한다(#311) — SSE·{@code
 * /tracking}과 같은 게이트를 공유해야 부하테스트에서 세 경로를 공정하게 비교할 수 있으므로, 이 서비스가
 * 그 판정을 다시 구현하지 않는지가 핵심 검증 대상이다. 그 외 이 서비스가 새로 하는 유일한 일은 Redis
 * 조회 실패를 "위치 없음"으로 위장하지 않고 503으로 바꾸는 것이다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerLocationQueryService")
class CustomerLocationQueryServiceTest {

    private static final Long DELIVERY_ID = 10L;
    private static final Long CUSTOMER_ID = 1L;
    private static final Long RIDER_ID = 20L;
    private static final LocationPayload LOCATION = new LocationPayload(
            new BigDecimal("37.5000000"), new BigDecimal("127.0000000"),
            Instant.parse("2026-08-03T01:02:03.456Z"), null);

    @InjectMocks
    private CustomerLocationQueryService service;

    @Mock
    private DeliveryTrackingAccessService accessService;

    @Mock
    private RiderLocationRepository riderLocationRepository;

    private void givenTrackableDelivery(OrderStatus status) {
        given(accessService.authorizeTracking(DELIVERY_ID, CUSTOMER_ID))
                .willReturn(new TrackableDelivery(DELIVERY_ID, RIDER_ID, status));
    }

    @Test
    @DisplayName("인가 게이트를 통과하면 위치를 담아 응답한다")
    void returnsLocationWhenAuthorized() {
        givenTrackableDelivery(OrderStatus.DELIVERING);
        given(riderLocationRepository.find(RIDER_ID)).willReturn(Optional.of(LOCATION));

        CustomerDeliveryLocationResponse response = service.getLocation(DELIVERY_ID, CUSTOMER_ID);

        assertThat(response).isEqualTo(
                new CustomerDeliveryLocationResponse(DELIVERY_ID, OrderStatus.DELIVERING, LOCATION));
    }

    @Test
    @DisplayName("Redis 에 위치가 없으면 오류가 아니라 location null 로 응답한다")
    void respondsWithNullLocationWhenAbsent() {
        givenTrackableDelivery(OrderStatus.ASSIGNED);
        given(riderLocationRepository.find(RIDER_ID)).willReturn(Optional.empty());

        CustomerDeliveryLocationResponse response = service.getLocation(DELIVERY_ID, CUSTOMER_ID);

        assertThat(response.location()).isNull();
        assertThat(response.status()).isEqualTo(OrderStatus.ASSIGNED);
    }

    @Test
    @DisplayName("인가 게이트가 거부하면(404/409) 그 예외를 그대로 전파한다")
    void propagatesAccessGateRejection() {
        willThrow(new BusinessException(HttpStatus.NOT_FOUND, "배송요청을 찾을 수 없습니다."))
                .given(accessService).authorizeTracking(DELIVERY_ID, CUSTOMER_ID);

        assertThatThrownBy(() -> service.getLocation(DELIVERY_ID, CUSTOMER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Redis 조회가 실패하면 위치 없음으로 위장하지 않고 503 이다")
    void convertsRedisFailureTo503() {
        givenTrackableDelivery(OrderStatus.DELIVERING);
        willThrow(new RuntimeException("redis connection refused"))
                .given(riderLocationRepository).find(RIDER_ID);

        assertThatThrownBy(() -> service.getLocation(DELIVERY_ID, CUSTOMER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
