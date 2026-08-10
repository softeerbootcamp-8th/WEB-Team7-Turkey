package com.turkey.quick.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.DeliveryStatusStepResponse;
import com.turkey.quick.order.dto.DeliveryTrackingResponse;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import com.turkey.quick.support.TrackingFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

/**
 * 추적 스냅샷 조회를 실제 MySQL 로 검증한다(#79).
 *
 * <p><b>{@code @Transactional} 을 붙이지 않은 것이 검증의 일부다.</b> 응답을 트랜잭션 밖에서 읽으므로,
 * 라이더 이름·연락처를 지연 로딩 상태로 흘려보내는 구현이면 여기서 {@code LazyInitializationException}
 * 이 난다(OSIV 가 꺼져 있다).
 *
 * <p>타임라인은 {@code order_status_history} 가 아니라 {@code delivery_order} 의 시각 컬럼에서 온다 —
 * 그 테이블에 행을 쓰는 코드가 아직 없다. 그래서 이 테스트가 "전이 메서드가 채운 시각"과
 * "응답 타임라인"이 어긋나지 않는지를 보는 유일한 장치다.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
@DisplayName("DeliveryTrackingQueryService")
class DeliveryTrackingQueryServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private DeliveryTrackingQueryService queryService;

    @Autowired
    private OrderFareSnapshotRepository orderFareSnapshotRepository;

    @Autowired
    private TrackingFixture fixture;

    private static BusinessException businessExceptionOf(Runnable action) {
        Throwable thrown = catchThrowable(action::run);

        assertThat(thrown).isInstanceOf(BusinessException.class);
        return (BusinessException) thrown;
    }

    @Nested
    @DisplayName("조회 성공")
    class Found {

        @Test
        @DisplayName("배차된 본인 주문의 상태·라이더·운임을 돌려준다")
        void returnsSnapshotOfOwnAssignedDelivery() {
            var scenario = fixture.assignedDelivery();

            DeliveryTrackingResponse response =
                    queryService.getTracking(scenario.deliveryId(), scenario.customerId());

            assertThat(response.deliveryId()).isEqualTo(scenario.deliveryId());
            assertThat(response.status()).isEqualTo(OrderStatus.ASSIGNED);
            assertThat(response.riderName()).isEqualTo("홍라이더");
            assertThat(response.riderPhoneNumber()).isNotBlank();
            assertThat(response.totalFare()).isEqualTo(scenario.totalFare());
        }

        @Test
        @DisplayName("도착 예정 시각은 현재 항상 null 이다")
        void returnsNullEstimatedArrival() {
            // 사람 확인(2026-07-31): 산정 근거가 없어 값을 만들지 않는다. 지도·경로 API 가 붙을 때
            // 채운다. 임의의 값을 넣으면 화면이 그것을 약속으로 취급한다.
            var scenario = fixture.assignedDelivery();

            assertThat(queryService.getTracking(scenario.deliveryId(), scenario.customerId())
                    .estimatedArrivalAt()).isNull();
        }

        @Test
        @DisplayName("타임라인은 도달한 단계만 시간 순으로 담는다")
        void returnsOnlyReachedSteps() {
            var scenario = fixture.deliveryWithStatus(OrderStatus.PICKED_UP);

            DeliveryTrackingResponse response =
                    queryService.getTracking(scenario.deliveryId(), scenario.customerId());

            assertThat(response.steps())
                    .extracting(DeliveryStatusStepResponse::status)
                    .containsExactly(OrderStatus.WAITING, OrderStatus.ASSIGNED,
                            OrderStatus.MOVING_TO_PICKUP, OrderStatus.PICKED_UP);
            assertThat(response.steps())
                    .allSatisfy(step -> assertThat(step.occurredAt()).isNotNull());
            assertThat(response.steps())
                    .extracting(DeliveryStatusStepResponse::occurredAt)
                    .isSorted();
        }

        @Test
        @DisplayName("배차 직후에는 이동·수령 단계가 타임라인에 없다")
        void omitsUnreachedSteps() {
            // 도달하지 않은 단계를 occurredAt=null 로 채워 보내면 화면이 "이미 지난 단계"로 그린다.
            var scenario = fixture.assignedDelivery();

            assertThat(queryService.getTracking(scenario.deliveryId(), scenario.customerId()).steps())
                    .extracting(DeliveryStatusStepResponse::status)
                    .containsExactly(OrderStatus.WAITING, OrderStatus.ASSIGNED)
                    .doesNotContain(OrderStatus.MOVING_TO_PICKUP, OrderStatus.DELIVERING);
        }

        @Test
        @DisplayName("배차 전(WAITING)도 조회되지만 라이더는 null이다(#401)")
        void returnsSnapshotForWaitingWithoutRider() {
            var scenario = fixture.deliveryWithStatus(OrderStatus.WAITING);

            DeliveryTrackingResponse response =
                    queryService.getTracking(scenario.deliveryId(), scenario.customerId());

            assertThat(response.status()).isEqualTo(OrderStatus.WAITING);
            assertThat(response.riderName()).isNull();
            assertThat(response.riderPhoneNumber()).isNull();
            assertThat(response.steps())
                    .extracting(DeliveryStatusStepResponse::status)
                    .containsExactly(OrderStatus.WAITING);
        }

        @Test
        @DisplayName("추적 가능한 네 상태 모두 조회된다")
        void returnsSnapshotForEveryTrackableStatus() {
            for (OrderStatus status : OrderStatus.values()) {
                if (!status.isTrackable()) {
                    continue;
                }
                var scenario = fixture.deliveryWithStatus(status);

                DeliveryTrackingResponse response =
                        queryService.getTracking(scenario.deliveryId(), scenario.customerId());

                assertThat(response.status()).as("status=%s", status).isEqualTo(status);
                assertThat(response.riderName()).as("status=%s 의 라이더 이름", status).isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("거부")
    class Rejected {

        @Test
        @DisplayName("다른 고객의 주문은 404다")
        void rejectsOtherCustomersDelivery() {
            var target = fixture.assignedDelivery();
            var intruder = fixture.assignedDelivery();

            BusinessException thrown = businessExceptionOf(() ->
                    queryService.getTracking(target.deliveryId(), intruder.customerId()));

            assertThat(thrown.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("완료·취소는 스트림과 같은 409다(#401부터 WAITING 제외)")
        void rejectsTerminalStatuses() {
            // 스트림과 판정이 갈리면 "REST 는 200 인데 스트림은 열리지 않는" 조합이 생기고,
            // 프론트는 EventSource 오류의 사유를 알 방법이 없어진다.
            for (OrderStatus status : new OrderStatus[]{OrderStatus.COMPLETED, OrderStatus.CANCELED}) {
                var scenario = fixture.deliveryWithStatus(status);

                BusinessException thrown = businessExceptionOf(() ->
                        queryService.getTracking(scenario.deliveryId(), scenario.customerId()));

                assertThat(thrown.getStatus()).as("status=%s", status).isEqualTo(HttpStatus.CONFLICT);
            }
        }

        @Test
        @DisplayName("예상 운임 스냅샷이 없으면 400이 아니라 500이다")
        void rejectsMissingFareSnapshotAsServerError() {
            // 클라이언트가 고칠 수 있는 문제가 아니라 데이터 불변식이 깨진 상태다.
            var scenario = fixture.assignedDelivery();
            orderFareSnapshotRepository.deleteAll();

            BusinessException thrown = businessExceptionOf(() ->
                    queryService.getTracking(scenario.deliveryId(), scenario.customerId()));

            assertThat(thrown.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
