package com.turkey.quick.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.support.IntegrationTestSupport;
import com.turkey.quick.support.TrackingFixture;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code active_rider_id} 생성 컬럼(V10)과 {@link OrderStatus#trackableStatuses()} 의 <b>동치를
 * 고정한다</b>(#317).
 *
 * <p><b>왜 필요한가</b>: 위치 갱신 핫패스가
 * {@link DeliveryOrderRepository#findInProgressIdByActiveRiderId} 로 바뀌면서 "어느 상태가 진행
 * 중인가"라는 지식이 <b>DDL 의 CASE 식과 자바 열거형 두 곳에 존재</b>하게 됐다. 둘이 갈리면
 * 조용히 틀린다 — 새 상태를 추가하고 마이그레이션을 빠뜨리면 그 상태의 라이더 위치가 고객에게
 * 전달되지 않거나(누락), 완료된 배송이 계속 걸려 <b>다음 배송 경로가 이전 고객에게 흘러간다</b>.
 *
 * <p>DDL 텍스트를 파싱하지 않고 <b>실제 DB 동작</b>으로 검증한다. 모든 상태를 실제로 만들어
 * 조회 결과가 있는지를 {@code isTrackable()} 과 맞춰 본다.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
@DisplayName("active_rider_id 생성 컬럼 ↔ OrderStatus.trackableStatuses()")
class DeliveryOrderActiveRiderIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private DeliveryOrderRepository deliveryOrderRepository;

    @Autowired
    private TrackingFixture fixture;

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    @DisplayName("조회 결과 유무가 isTrackable() 과 일치한다")
    void lookupPresenceMatchesTrackable(OrderStatus status) {
        TrackingFixture.Scenario scenario = fixture.deliveryWithStatus(status);

        Optional<Long> found = deliveryOrderRepository.findInProgressIdByActiveRiderId(scenario.riderId());

        assertThat(found.isPresent())
                .as("%s 는 isTrackable()=%s 이므로 조회 결과도 그와 같아야 한다", status, status.isTrackable())
                .isEqualTo(status.isTrackable());
        if (status.isTrackable()) {
            assertThat(found).contains(scenario.deliveryId());
        }
    }

    @Test
    @DisplayName("생성 컬럼이 실제로 네 상태만 진행 중으로 본다")
    void generatedColumnCoversExactlyFourStatuses() {
        // 위 파라미터 테스트는 상태별로 따로 돌아 "전체 집합"을 단언하지 못한다. 열거형 쪽 집합이
        // 조용히 넓어지거나 좁아지는 것을 여기서 잡는다.
        long trackableCount = Arrays.stream(OrderStatus.values()).filter(OrderStatus::isTrackable).count();

        assertThat(trackableCount).isEqualTo(4);
        assertThat(OrderStatus.trackableStatuses())
                .containsExactlyInAnyOrder(OrderStatus.ASSIGNED, OrderStatus.MOVING_TO_PICKUP,
                        OrderStatus.PICKED_UP, OrderStatus.DELIVERING);
    }

    @Test
    @DisplayName("라이더가 없는 배송은 조회되지 않는다")
    void ignoresDeliveryWithoutRider() {
        // WAITING 은 assigned_rider_id 가 NULL 이라 생성 컬럼도 NULL 이다. 픽스처는 그 시나리오의
        // 라이더 프로필을 만들어 두므로 riderId 자체는 존재한다 — 즉 "라이더는 있는데 배정은
        // 없다"를 확인하는 것이다.
        TrackingFixture.Scenario waiting = fixture.deliveryWithStatus(OrderStatus.WAITING);

        assertThat(deliveryOrderRepository.findInProgressIdByActiveRiderId(waiting.riderId())).isEmpty();
    }
}
