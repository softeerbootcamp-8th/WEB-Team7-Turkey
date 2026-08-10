package com.turkey.quick.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.DeliveryEtaResponse;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * ETA 폴링 응답의 분기만 본다(#447). 구간 선택·러시아워 보정은 {@link DeliveryRouteEstimatorTest},
 * DB 배선은 {@code DeliveryEtaQueryServiceIntegrationTest} 가 맡는다.
 *
 * <p>여기서만 잡히는 것은 <b>추적 불가 상태에서 라우팅을 부르는 실수</b>다.
 * {@code DeliveryRouteEstimator.targetOf} 는 WAITING·COMPLETED·CANCELED 에
 * {@code IllegalStateException} 을 던지므로, 그 상태를 걸러내지 않으면 폴링이 500 을 뱉는다 —
 * 게다가 {@code GlobalExceptionHandler} 를 지나며 사유를 알 수 없는 응답이 된다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ETA 폴링 조회")
class DeliveryEtaQueryServiceTest {

    private static final Long DELIVERY_ID = 1024L;
    private static final Long CUSTOMER_ID = 7L;

    @InjectMocks
    private DeliveryEtaQueryService queryService;

    @Mock
    private DeliveryOrderRepository deliveryOrderRepository;

    @Mock
    private DeliveryRouteEstimator routeEstimator;

    private void givenOrderWithStatus(OrderStatus status) {
        DeliveryOrder order = mock(DeliveryOrder.class);
        given(order.getStatus()).willReturn(status);
        given(order.getId()).willReturn(DELIVERY_ID);
        given(deliveryOrderRepository.findWithAssignedRiderByIdAndCustomerId(DELIVERY_ID, CUSTOMER_ID))
                .willReturn(Optional.of(order));
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"WAITING", "COMPLETED", "CANCELED"})
    @DisplayName("추적 불가 상태면 라우팅을 부르지 않고 ETA 만 비운다")
    void skipsRoutingWhenNotTrackable(OrderStatus status) {
        givenOrderWithStatus(status);

        DeliveryEtaResponse response = queryService.getEta(DELIVERY_ID, CUSTOMER_ID);

        assertThat(response.estimatedArrivalAt()).isNull();
        // 상태는 그대로 내려간다 — 프론트가 "왜 ETA 가 없는지"를 이 값으로 구분한다.
        assertThat(response.status()).isEqualTo(status);
        // 부르기만 해도 IllegalStateException 이라, 호출 자체가 없어야 한다.
        verifyNoInteractions(routeEstimator);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class,
            names = {"ASSIGNED", "MOVING_TO_PICKUP", "PICKED_UP", "DELIVERING"})
    @DisplayName("추적 가능 상태면 소요 시간을 응답 시각에 더해 도착 예정 시각을 만든다")
    void fillsEtaWhenTrackable(OrderStatus status) {
        givenOrderWithStatus(status);
        given(routeEstimator.findRemainingRoute(any())).willReturn(Optional.of(Duration.ofSeconds(420)));
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

        DeliveryEtaResponse response = queryService.getEta(DELIVERY_ID, CUSTOMER_ID);

        // 기준 시각이 "지금"이라 정확한 값을 못 박을 수 없다. 호출 전후로 구간을 잡는다.
        assertThat(response.estimatedArrivalAt())
                .isBetween(before.plusSeconds(420), LocalDateTime.now(ZoneOffset.UTC).plusSeconds(420));
        assertThat(response.deliveryId()).isEqualTo(DELIVERY_ID);
    }

    @Test
    @DisplayName("경로를 얻지 못하면(위치 없음·경로 서버 장애) 오류가 아니라 ETA 만 비운다")
    void returnsNullEtaWhenRouteUnavailable() {
        // 폴링 경로라 오류로 올리지 않는 것이 계약이다(사람 확인, 2026-08-10) — 배차 직후
        // 첫 위치 미전송, Redis TTL 만료, OSRM 장애가 전부 정상적으로 일어나는 상황이다.
        givenOrderWithStatus(OrderStatus.DELIVERING);
        given(routeEstimator.findRemainingRoute(any())).willReturn(Optional.empty());

        DeliveryEtaResponse response = queryService.getEta(DELIVERY_ID, CUSTOMER_ID);

        assertThat(response.estimatedArrivalAt()).isNull();
        assertThat(response.status()).isEqualTo(OrderStatus.DELIVERING);
    }

    @Test
    @DisplayName("없는 주문과 타인의 주문은 같은 404 다")
    void rejectsMissingOrForeignOrderWithNotFound() {
        // 쿼리에 고객 조건이 있어 둘이 구분되지 않는다. 사유를 나누면 남의 주문 id 를 찍어 보는
        // 것만으로 존재 여부가 새어 나간다(order_id 는 AUTO_INCREMENT 라 열거가 쉽다).
        given(deliveryOrderRepository.findWithAssignedRiderByIdAndCustomerId(DELIVERY_ID, CUSTOMER_ID))
                .willReturn(Optional.empty());

        BusinessException thrown = catchThrowableOfType(
                () -> queryService.getEta(DELIVERY_ID, CUSTOMER_ID), BusinessException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(routeEstimator);
    }
}
