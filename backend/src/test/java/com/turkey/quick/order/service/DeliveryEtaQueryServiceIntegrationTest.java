package com.turkey.quick.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.common.routing.RoutingClient;
import com.turkey.quick.location.dto.LocationPayload;
import com.turkey.quick.location.repository.RiderLocationRepository;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.DeliveryEtaResponse;
import com.turkey.quick.support.IntegrationTestSupport;
import com.turkey.quick.support.TrackingFixture;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * ETA 폴링 조회를 실제 MySQL 로 검증한다(#447).
 *
 * <p><b>{@code @Transactional} 을 붙이지 않은 것이 검증의 핵심이다.</b> 이 서비스는 트랜잭션 밖에서
 * 경로를 산정하는데, 그때 {@code DeliveryRouteEstimator} 가 {@code assignedRider.getMemberId()} 를
 * 읽는다. {@code findWithAssignedRiderByIdAndCustomerId} 의 {@code left join fetch} 를 빠뜨리면
 * 여기서 {@code LazyInitializationException} 이 난다(OSIV 가 꺼져 있다) —
 * <b>단위 테스트는 리포지토리를 목으로 두므로 이 실수를 절대 잡지 못한다.</b>
 *
 * <p>같은 이유로 {@code left} 여야 하는 것도 여기서 지킨다. WAITING 은 {@code assigned_rider_id} 가
 * NULL 이라 inner join 이면 주문이 있는데도 결과가 비어 <b>404</b> 가 된다 — 계약은 200 + null 이다.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
@DisplayName("DeliveryEtaQueryService")
class DeliveryEtaQueryServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private DeliveryEtaQueryService queryService;

    @Autowired
    private TrackingFixture fixture;

    @Autowired
    private RiderLocationRepository riderLocationRepository;

    /**
     * OSRM 은 개발 PC·CI 어디에서도 닿지 않는다(#420: WAS 보안그룹 전용). 실제 클라이언트를 그대로
     * 두면 모든 케이스가 연결 실패 → 빈 경로로 흘러 <b>성공 경로를 한 번도 지나지 않는다.</b>
     * 그래서 포트만 목으로 바꾼다 — 스텁하지 않은 테스트는 기본값(빈 Optional)이라 "경로 서버가
     * 응답하지 않는 상황"과 같은 결과가 된다.
     */
    @MockitoBean
    private RoutingClient routingClient;

    private void givenRiderAt(Long riderId) {
        riderLocationRepository.saveIfNewer(riderId, new LocationPayload(
                new BigDecimal("37.5100000"), new BigDecimal("127.0200000"), Instant.now(), null));
    }

    @Test
    @DisplayName("배차된 본인 주문에 라이더 위치와 경로가 있으면 도착 예정 시각을 채운다")
    void fillsEtaForOwnAssignedDelivery() {
        var scenario = fixture.assignedDelivery();
        givenRiderAt(scenario.riderId());
        given(routingClient.findRoute(any(), any())).willReturn(Optional.of(Duration.ofSeconds(420)));
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

        DeliveryEtaResponse response = queryService.getEta(scenario.deliveryId(), scenario.customerId());

        // 러시아워 보정(평일 07-09·18-20시 x1.3)이 실행 시각에 따라 걸릴 수 있어 상한을 넉넉히 잡는다.
        // 이걸 정확히 +420 초로 고정하면 그 시간대에 돌릴 때만 깨지는 플레이키가 된다.
        assertThat(response.estimatedArrivalAt())
                .isBetween(before.plusSeconds(420), LocalDateTime.now(ZoneOffset.UTC).plusSeconds(546));
        assertThat(response.status()).isEqualTo(OrderStatus.ASSIGNED);
        assertThat(response.deliveryId()).isEqualTo(scenario.deliveryId());
    }

    @Test
    @DisplayName("라이더 최신 위치가 없으면 ETA 만 null 이다")
    void returnsNullEtaWithoutRiderPosition() {
        // 배차 직후(첫 위치 미전송)·Redis TTL(10분) 만료에 정상적으로 일어나는 상황이다.
        // RedisCleaner 가 매 테스트 전에 FLUSHDB 하므로 위치를 심지 않으면 이 상태가 된다.
        var scenario = fixture.assignedDelivery();

        DeliveryEtaResponse response = queryService.getEta(scenario.deliveryId(), scenario.customerId());

        assertThat(response.estimatedArrivalAt()).isNull();
        assertThat(response.status()).isEqualTo(OrderStatus.ASSIGNED);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"WAITING", "COMPLETED", "CANCELED"})
    @DisplayName("추적 불가 상태도 404·409 가 아니라 200 + null 이다")
    void returnsNullEtaForNonTrackableStatus(OrderStatus status) {
        // WAITING 케이스가 left join fetch 회귀를 막는다 — inner join 이면 여기서 404 가 난다.
        var scenario = fixture.deliveryWithStatus(status);

        DeliveryEtaResponse response = queryService.getEta(scenario.deliveryId(), scenario.customerId());

        assertThat(response.estimatedArrivalAt()).isNull();
        assertThat(response.status()).isEqualTo(status);
    }

    @Test
    @DisplayName("타인의 주문은 404 다")
    void rejectsForeignOrder() {
        var mine = fixture.assignedDelivery();
        var other = fixture.assignedDelivery();

        Throwable thrown = catchThrowable(() -> queryService.getEta(other.deliveryId(), mine.customerId()));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("없는 주문도 타인의 주문과 같은 404 다")
    void rejectsMissingOrder() {
        var scenario = fixture.assignedDelivery();

        Throwable thrown = catchThrowable(
                () -> queryService.getEta(scenario.deliveryId() + 9_999L, scenario.customerId()));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
