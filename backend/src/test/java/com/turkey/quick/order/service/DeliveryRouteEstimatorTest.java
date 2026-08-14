package com.turkey.quick.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.turkey.quick.common.routing.Coordinate;
import com.turkey.quick.common.routing.RoutingClient;
import com.turkey.quick.common.routing.RoutingClient.RouteEstimate;
import com.turkey.quick.location.dto.LocationPayload;
import com.turkey.quick.location.repository.RiderLocationRepository;
import com.turkey.quick.order.domain.Address;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 경로 구간 선택과 "위치가 없을 때"의 처리만 본다(#421). 실제 OSRM 호출은
 * {@code OsrmRoutingClientTest}(#420)가, 배선 전체는 통합·E2E 가 맡는다.
 *
 * <p>여기서만 잡히는 것은 <b>상태별 목적지가 뒤바뀌는 실수</b>다 — 픽업지와 도착지는 둘 다 유효한
 * 서울 좌표라 뒤바뀌어도 아무 예외가 나지 않고, ETA 가 "그럴듯하게 틀린" 값이 된다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("추적 경로 산정")
class DeliveryRouteEstimatorTest {

    private static final Long RIDER_ID = 7L;
    private static final Long DELIVERY_ID = 1024L;

    private static final Coordinate RIDER_POSITION =
            new Coordinate(new BigDecimal("37.5100000"), new BigDecimal("127.0200000"));
    private static final Coordinate PICKUP =
            new Coordinate(new BigDecimal("37.4979000"), new BigDecimal("127.0276000"));
    private static final Coordinate DESTINATION =
            new Coordinate(new BigDecimal("37.5665000"), new BigDecimal("126.9780000"));

    @InjectMocks
    private DeliveryRouteEstimator estimator;

    @Mock
    private RoutingClient routingClient;

    @Mock
    private RiderLocationRepository riderLocationRepository;

    private static Address address(Coordinate coordinate) {
        Address address = mock(Address.class);
        given(address.getLatitude()).willReturn(coordinate.latitude());
        given(address.getLongitude()).willReturn(coordinate.longitude());
        return address;
    }

    /** 목적지 주소는 호출하는 쪽에서만 스텁한다 — 상태에 따라 한쪽만 읽히므로 둘 다 채우면 불필요 스텁이다. */
    private static DeliveryOrder order(OrderStatus status, Address target) {
        RiderProfile rider = mock(RiderProfile.class);
        given(rider.getMemberId()).willReturn(RIDER_ID);

        DeliveryOrder order = mock(DeliveryOrder.class);
        given(order.getAssignedRider()).willReturn(rider);
        given(order.getStatus()).willReturn(status);
        if (status == OrderStatus.ASSIGNED || status == OrderStatus.MOVING_TO_PICKUP) {
            given(order.getPickup()).willReturn(target);
        } else {
            given(order.getDestination()).willReturn(target);
        }
        return order;
    }

    private void givenRiderAt(Coordinate position) {
        given(riderLocationRepository.find(RIDER_ID)).willReturn(Optional.of(new LocationPayload(
                position.latitude(), position.longitude(), Instant.parse("2026-08-07T01:00:00Z"), null)));
    }

    private Coordinate capturedDestination() {
        ArgumentCaptor<Coordinate> origin = ArgumentCaptor.forClass(Coordinate.class);
        ArgumentCaptor<Coordinate> destination = ArgumentCaptor.forClass(Coordinate.class);
        verify(routingClient).findRoute(origin.capture(), destination.capture());
        assertThat(origin.getValue()).as("출발점은 항상 라이더 현재 위치다").isEqualTo(RIDER_POSITION);
        return destination.getValue();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"ASSIGNED", "MOVING_TO_PICKUP"})
    @DisplayName("픽업 전에는 픽업지까지의 경로를 구한다")
    void routesToPickupBeforePickup(OrderStatus status) {
        givenRiderAt(RIDER_POSITION);
        given(routingClient.findRoute(any(), any()))
                .willReturn(Optional.of(new RouteEstimate(Duration.ofMinutes(7), List.of(PICKUP))));

        Optional<RouteEstimate> found = estimator.findRemainingRoute(order(status, address(PICKUP)));

        // 러시아워 보정(최대 x1.3)이 실행 시각에 따라 걸릴 수 있어 상한까지 넉넉히 잡는다.
        assertThat(found).isPresent();
        assertThat(found.get().duration().toMillis())
                .isBetween(Duration.ofMinutes(7).toMillis(), Duration.ofMinutes(10).toMillis());
        assertThat(found.get().path()).containsExactly(PICKUP);
        assertThat(capturedDestination()).isEqualTo(PICKUP);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PICKED_UP", "DELIVERING"})
    @DisplayName("픽업 후에는 도착지까지의 경로를 구한다")
    void routesToDestinationAfterPickup(OrderStatus status) {
        givenRiderAt(RIDER_POSITION);
        given(routingClient.findRoute(any(), any()))
                .willReturn(Optional.of(new RouteEstimate(Duration.ofMinutes(12), List.of(DESTINATION))));

        estimator.findRemainingRoute(order(status, address(DESTINATION)));

        assertThat(capturedDestination()).isEqualTo(DESTINATION);
    }

    @Test
    @DisplayName("라이더 최신 위치가 없으면 라우팅을 부르지 않고 빈 값을 준다")
    void skipsRoutingWithoutRiderPosition() {
        // 배차 직후(첫 위치 미전송)나 TTL 10분 만료. 픽업지로 출발점을 대체하지 않기로 했다
        // (사람 확인, 2026-08-07) — 그러면 픽업 전 상태에서 출발=도착이 되어 "지금 도착"이 된다.
        given(riderLocationRepository.find(RIDER_ID)).willReturn(Optional.empty());
        // riderProfile() 을 willReturn 인자 안에서 만들면 스텁 도중에 스텁이 시작돼
        // UnfinishedStubbingException 이다. 먼저 만들어 두고 넘긴다.
        RiderProfile rider = riderProfile();
        DeliveryOrder order = mock(DeliveryOrder.class);
        given(order.getAssignedRider()).willReturn(rider);
        given(order.getId()).willReturn(DELIVERY_ID);

        assertThat(estimator.findRemainingRoute(order)).isEmpty();
        verify(routingClient, never()).findRoute(any(), any());
    }

    @Test
    @DisplayName("Redis 조회가 실패해도 예외를 던지지 않고 빈 값을 준다")
    void swallowsLocationLookupFailure() {
        // 폴링 API 는 같은 실패를 503 으로 올리지만, 추적 스냅샷은 위치가 부수적 입력이라
        // 상태·타임라인까지 못 그리게 만들면 안 된다(#421: ETA 없어도 200).
        given(riderLocationRepository.find(RIDER_ID))
                .willThrow(new IllegalStateException("Redis 연결 실패"));
        // riderProfile() 을 willReturn 인자 안에서 만들면 스텁 도중에 스텁이 시작돼
        // UnfinishedStubbingException 이다. 먼저 만들어 두고 넘긴다.
        RiderProfile rider = riderProfile();
        DeliveryOrder order = mock(DeliveryOrder.class);
        given(order.getAssignedRider()).willReturn(rider);
        given(order.getId()).willReturn(DELIVERY_ID);

        assertThat(estimator.findRemainingRoute(order)).isEmpty();
        verify(routingClient, never()).findRoute(any(), any());
    }

    private RiderProfile riderProfile() {
        RiderProfile rider = mock(RiderProfile.class);
        given(rider.getMemberId()).willReturn(RIDER_ID);
        return rider;
    }

    @Test
    @DisplayName("평일 출근·퇴근 시간대는 x1.3 배 보정한다")
    void multipliesDurationDuringWeekdayRushHour() {
        LocalDateTime morningRush = LocalDateTime.of(2026, 8, 10, 8, 0); // 월요일
        LocalDateTime eveningRush = LocalDateTime.of(2026, 8, 10, 19, 0);

        assertThat(DeliveryRouteEstimator.applyRushHourMultiplier(Duration.ofMinutes(10), morningRush))
                .isEqualTo(Duration.ofMinutes(13));
        assertThat(DeliveryRouteEstimator.applyRushHourMultiplier(Duration.ofMinutes(10), eveningRush))
                .isEqualTo(Duration.ofMinutes(13));
    }

    @Test
    @DisplayName("러시아워 시간대를 벗어나면 보정하지 않는다")
    void doesNotMultiplyOutsideRushHour() {
        LocalDateTime weekdayNoon = LocalDateTime.of(2026, 8, 10, 12, 0); // 월요일

        assertThat(DeliveryRouteEstimator.applyRushHourMultiplier(Duration.ofMinutes(10), weekdayNoon))
                .isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("주말은 같은 시각대여도 보정하지 않는다")
    void doesNotMultiplyOnWeekend() {
        LocalDateTime saturdayMorningRush = LocalDateTime.of(2026, 8, 8, 8, 0);
        assertThat(saturdayMorningRush.getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);

        assertThat(DeliveryRouteEstimator.applyRushHourMultiplier(Duration.ofMinutes(10), saturdayMorningRush))
                .isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("경계 시각(09:00, 20:00)은 러시아워에 포함되지 않는다")
    void treatsWindowEndAsExclusive() {
        LocalDateTime morningEnd = LocalDateTime.of(2026, 8, 10, 9, 0);
        LocalDateTime eveningEnd = LocalDateTime.of(2026, 8, 10, 20, 0);

        assertThat(DeliveryRouteEstimator.applyRushHourMultiplier(Duration.ofMinutes(10), morningEnd))
                .isEqualTo(Duration.ofMinutes(10));
        assertThat(DeliveryRouteEstimator.applyRushHourMultiplier(Duration.ofMinutes(10), eveningEnd))
                .isEqualTo(Duration.ofMinutes(10));
    }
}
