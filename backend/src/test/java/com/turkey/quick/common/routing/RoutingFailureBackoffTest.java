package com.turkey.quick.common.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("라우팅 실패 백오프(TCP 재전송 오마주)")
class RoutingFailureBackoffTest {

    /**
     * 실제 시간을 기다리지 않기 위한 시계. {@code System.nanoTime()} 은 음수일 수도 있어서,
     * 부호에 기대는 비교가 섞여 있으면 여기서 드러나도록 <b>음수에서 시작</b>한다.
     */
    private final AtomicLong clock = new AtomicLong(-1_000_000_000L);

    private final RoutingFailureBackoff backoff = new RoutingFailureBackoff(clock::get);

    private void advance(Duration elapsed) {
        clock.addAndGet(elapsed.toNanos());
    }

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            backoff.recordFailure();
        }
    }

    @Nested
    @DisplayName("회로를 여는 조건")
    class OpeningTest {

        @Test
        @DisplayName("실패가 임계치 미만이면 계속 호출을 허용한다")
        void shouldAllowCallsBelowThreshold() {
            fail(RoutingFailureBackoff.FAILURE_THRESHOLD - 1);

            assertThat(backoff.allowsCall()).isTrue();
        }

        @Test
        @DisplayName("연속 실패가 임계치에 이르면 호출을 멈춘다")
        void shouldStopCallsAtThreshold() {
            fail(RoutingFailureBackoff.FAILURE_THRESHOLD);

            assertThat(backoff.allowsCall()).isFalse();
        }

        @Test
        @DisplayName("중간에 한 번이라도 성공하면 실패 수가 초기화돼 회로가 열리지 않는다")
        void shouldNotOpenWhenSuccessInterruptsFailureStreak() {
            fail(RoutingFailureBackoff.FAILURE_THRESHOLD - 1);
            backoff.recordSuccess();
            fail(RoutingFailureBackoff.FAILURE_THRESHOLD - 1);

            assertThat(backoff.allowsCall()).isTrue();
        }
    }

    @Nested
    @DisplayName("대기 시간")
    class BackoffDurationTest {

        @Test
        @DisplayName("첫 대기가 끝나면 다시 호출을 허용한다")
        void shouldResumeAfterInitialBackoff() {
            fail(RoutingFailureBackoff.FAILURE_THRESHOLD);

            advance(RoutingFailureBackoff.INITIAL_BACKOFF.minusMillis(1));
            assertThat(backoff.allowsCall()).as("대기 종료 직전").isFalse();

            advance(Duration.ofMillis(1));
            assertThat(backoff.allowsCall()).as("대기 종료 시점").isTrue();
        }

        @Test
        @DisplayName("대기 후 다시 실패하면 대기 시간이 두 배가 된다")
        void shouldDoubleBackoffOnRepeatedFailure() {
            fail(RoutingFailureBackoff.FAILURE_THRESHOLD);
            advance(RoutingFailureBackoff.INITIAL_BACKOFF);

            // 대기가 끝난 뒤 흘려보낸 호출이 또 실패한 상황이다.
            backoff.recordFailure();

            advance(RoutingFailureBackoff.INITIAL_BACKOFF);
            assertThat(backoff.allowsCall()).as("두 배가 됐으므로 아직 대기 중").isFalse();

            advance(RoutingFailureBackoff.INITIAL_BACKOFF);
            assertThat(backoff.allowsCall()).as("두 배 시간이 지나면 재개").isTrue();
        }

        @Test
        @DisplayName("대기 시간은 상한을 넘지 않는다")
        void shouldCapBackoff() {
            // 상한(30초)은 1초에서 다섯 번만 두 배가 되면 닿는다. 넉넉히 20번 실패시킨다.
            fail(RoutingFailureBackoff.FAILURE_THRESHOLD + 20);

            advance(RoutingFailureBackoff.MAX_BACKOFF);

            assertThat(backoff.allowsCall()).isTrue();
        }

        @Test
        @DisplayName("성공하면 대기 시간까지 초기화돼 다음 회로 개방은 다시 최소 대기부터 시작한다")
        void shouldResetBackoffLengthOnSuccess() {
            fail(RoutingFailureBackoff.FAILURE_THRESHOLD + 3);   // 대기가 여러 번 두 배가 된 상태
            backoff.recordSuccess();

            fail(RoutingFailureBackoff.FAILURE_THRESHOLD);
            advance(RoutingFailureBackoff.INITIAL_BACKOFF);

            assertThat(backoff.allowsCall()).isTrue();
        }
    }
}
