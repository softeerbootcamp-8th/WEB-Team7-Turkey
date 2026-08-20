package com.turkey.quick.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SSE 팬아웃 실행기(가상 스레드 + Semaphore(1000) 상한, #566)")
class RedisMessageListenerConfigTest {

    private static final int MAX_CONCURRENT_DISPATCH = 1_000;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Executor executor = new RedisMessageListenerConfig().trackingEventExecutor(registry);

    @Test
    @DisplayName("작업을 제출하면 실제로 실행된다")
    void executesSubmittedTask() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);

        executor.execute(ran::countDown);

        assertThat(ran.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("상한을 넘기면 그 자리에서 드롭하고 카운터를 올린다 — 호출 스레드는 블로킹되지 않는다")
    void dropsBeyondLimitWithoutBlockingCaller() throws InterruptedException {
        // permit 획득(tryAcquire)은 호출 스레드에서 동기로 일어나므로, 이 루프가 끝나면
        // 실제 가상 스레드 스케줄링을 기다리지 않고도 상한이 이미 가득 찬 상태가 보장된다.
        CountDownLatch holdGate = new CountDownLatch(1);
        for (int i = 0; i < MAX_CONCURRENT_DISPATCH; i++) {
            executor.execute(() -> {
                try {
                    holdGate.await();
                } catch (InterruptedException ignored) {
                    // 테스트 종료 정리 목적의 인터럽트
                }
            });
        }

        long start = System.nanoTime();
        executor.execute(() -> { throw new AssertionError("상한 초과 시 실행되면 안 된다"); });
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("tryAcquire() 는 절대 블로킹하지 않아야 한다").isLessThan(500);
        assertThat(registry.get("sse.fanout.dropped").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("sse.fanout.in_flight").gauge().value())
                .isEqualTo((double) MAX_CONCURRENT_DISPATCH);

        holdGate.countDown();
    }
}
