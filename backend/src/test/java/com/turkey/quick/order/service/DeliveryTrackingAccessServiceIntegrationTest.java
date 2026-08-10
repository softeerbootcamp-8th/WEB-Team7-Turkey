package com.turkey.quick.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.TrackableDelivery;
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
 * 실제 MySQL 로 검증한다. 여기서만 잡히는 것이 세 가지다.
 *
 * <ul>
 *   <li><b>투영이 LAZY 를 우회하는지</b> — 테스트가 트랜잭션 밖에서 결과를 만진다
 *       ({@code @Transactional} 을 붙이지 않았다). 엔터티를 반환하는 구현으로 바꾸면 여기서
 *       {@code LazyInitializationException} 이 난다.</li>
 *   <li><b>라이더가 없는 행이 조회에서 사라지지 않는지</b> — 조회가 FK 컬럼만 읽어 조인이 없으므로
 *       {@code WAITING} 주문도 결과에 남는다. 조인하는 형태로 다시 쓰이면 그 행이 빠져 404 가
 *       된다(#401 이후로는 WAITING 이 허용이라 200 이 나와야 할 자리에 404 가 나오는 형태로
 *       드러난다).</li>
 *   <li><b>픽스처가 DDL CHECK 를 통과하는지</b> — {@code ck_delivery_assignment} 등이
 *       전이 메서드가 채우는 필드 조합과 어긋나면 INSERT 가 깨진다.</li>
 * </ul>
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
@DisplayName("DeliveryTrackingAccessService")
class DeliveryTrackingAccessServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private DeliveryTrackingAccessService accessService;

    @Autowired
    private TrackingFixture fixture;

    /** 타입을 먼저 단언하고 캐스팅한다 — 바로 캐스팅하면 다른 예외가 왔을 때 실패 메시지가 ClassCastException 이 된다. */
    private static BusinessException businessExceptionOf(Runnable action) {
        Throwable thrown = catchThrowable(action::run);

        assertThat(thrown).isInstanceOf(BusinessException.class);
        return (BusinessException) thrown;
    }

    @Nested
    @DisplayName("허용")
    class Allowed {

        @Test
        @DisplayName("본인의 배차된 주문은 라이더 식별자까지 돌려준다")
        void returnsRiderIdForOwnAssignedDelivery() {
            var scenario = fixture.assignedDelivery();

            TrackableDelivery delivery =
                    accessService.authorizeTracking(scenario.deliveryId(), scenario.customerId());

            assertThat(delivery.orderId()).isEqualTo(scenario.deliveryId());
            assertThat(delivery.status()).isEqualTo(OrderStatus.ASSIGNED);
            // 이 값이 Redis 최신 위치 조회 키가 된다. rider_profile 은 member 와 공유 PK 이므로
            // 라이더의 member_id 와 같아야 한다.
            assertThat(delivery.riderId()).isEqualTo(scenario.riderId());
        }

        @Test
        @DisplayName("추적 가능한 네 상태 모두 허용된다")
        void allowsEveryTrackableStatus() {
            for (OrderStatus status : OrderStatus.values()) {
                if (!status.isTrackable()) {
                    continue;
                }
                var scenario = fixture.deliveryWithStatus(status);

                TrackableDelivery delivery =
                        accessService.authorizeTracking(scenario.deliveryId(), scenario.customerId());

                assertThat(delivery.status()).as("status=%s", status).isEqualTo(status);
                assertThat(delivery.riderId()).as("status=%s 의 라이더", status).isNotNull();
            }
        }

        @Test
        @DisplayName("배차 전(WAITING) 주문도 구독을 허용하되, 라이더는 아직 없다(#401)")
        void allowsWaitingDeliveryWithoutRider() {
            // 조회가 라이더를 조인하지 않고 FK 컬럼만 읽으므로 라이더 없는 행도 결과에 남는다.
            // 조인하거나 상태를 쿼리에서 거르는 형태로 다시 쓰면 이 행이 빠져 404 가 되고,
            // 고객은 자기 주문이 사라진 것으로 보게 된다. 그 회귀를 잡는 케이스다.
            var scenario = fixture.deliveryWithStatus(OrderStatus.WAITING);

            TrackableDelivery delivery =
                    accessService.authorizeTracking(scenario.deliveryId(), scenario.customerId());

            assertThat(delivery.status()).isEqualTo(OrderStatus.WAITING);
            assertThat(delivery.riderId()).as("배차 전이라 라이더가 없다").isNull();
        }

        @Test
        @DisplayName("반환값을 트랜잭션 밖에서 읽어도 예외가 없다")
        void survivesOutsideTransaction() {
            // 이 테스트에 @Transactional 이 없다는 것이 검증의 핵심이다. SSE 는 emitter 콜백
            // (요청 스레드도 아니고 트랜잭션도 없다)에서 이 값을 다시 읽으므로, 엔터티를
            // 들고 나오면 LazyInitializationException 이 나고 GlobalExceptionHandler 가
            // 잡지 못해 ApiResponse 아닌 500 이 된다.
            var scenario = fixture.assignedDelivery();

            TrackableDelivery delivery =
                    accessService.authorizeTracking(scenario.deliveryId(), scenario.customerId());

            assertThat(delivery.riderId()).isNotNull();
            assertThat(delivery.status()).isNotNull();
            assertThat(delivery.orderId()).isNotNull();
        }
    }

    @Nested
    @DisplayName("거부")
    class Rejected {

        @Test
        @DisplayName("존재하지 않는 주문은 404다")
        void rejectsUnknownDelivery() {
            var scenario = fixture.assignedDelivery();

            BusinessException thrown = businessExceptionOf(() ->
                    accessService.authorizeTracking(scenario.deliveryId() + 1_000, scenario.customerId()));

            assertThat(thrown.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("다른 고객의 주문도 404다")
        void rejectsOtherCustomersDeliveryAsNotFound() {
            // 403 으로 나누면 남의 주문 id 를 찍어 보는 것만으로 "그 주문은 존재한다"를 알 수 있다.
            // order_id 가 AUTO_INCREMENT 라 열거가 쉽다.
            var target = fixture.assignedDelivery();
            var intruder = fixture.assignedDelivery();

            BusinessException thrown = businessExceptionOf(() ->
                    accessService.authorizeTracking(target.deliveryId(), intruder.customerId()));

            assertThat(thrown.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("완료된 주문은 409다")
        void rejectsCompletedDelivery() {
            var scenario = fixture.deliveryWithStatus(OrderStatus.COMPLETED);

            BusinessException thrown = businessExceptionOf(() ->
                    accessService.authorizeTracking(scenario.deliveryId(), scenario.customerId()));

            assertThat(thrown.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(thrown.getMessage()).contains("완료");
        }

        @Test
        @DisplayName("취소된 주문은 409다")
        void rejectsCanceledDelivery() {
            var scenario = fixture.deliveryWithStatus(OrderStatus.CANCELED);

            BusinessException thrown = businessExceptionOf(() ->
                    accessService.authorizeTracking(scenario.deliveryId(), scenario.customerId()));

            assertThat(thrown.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(thrown.getMessage()).contains("취소");
        }

        @Test
        @DisplayName("거부는 BusinessException 이라 ApiResponse 형태로 응답된다")
        void rejectsWithBusinessException() {
            // 다른 예외 타입으로 던지면 GlobalExceptionHandler 가 잡지 못해 ApiResponse 가 아닌
            // 스프링 기본 오류 본문이 나간다(포괄 Exception 핸들러가 없다).
            var scenario = fixture.deliveryWithStatus(OrderStatus.COMPLETED);

            assertThatThrownBy(() ->
                    accessService.authorizeTracking(scenario.deliveryId(), scenario.customerId()))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
