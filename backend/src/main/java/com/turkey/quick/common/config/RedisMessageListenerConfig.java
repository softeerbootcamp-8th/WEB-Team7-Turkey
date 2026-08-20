package com.turkey.quick.common.config;

import com.turkey.quick.location.sse.TrackingChannel;
import com.turkey.quick.location.sse.TrackingCloseSubscriber;
import com.turkey.quick.location.sse.TrackingSubscriber;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub 구독 배선(#317). <b>이 저장소의 유일한 Redis {@code @Configuration} 이다</b> —
 * 나머지 Redis 사용처({@code RedisSessionStore}, {@code OrderGeoRepository},
 * {@code RedisVerificationCodeStore}, {@code RiderLocationRepository})는 스프링 부트 자동설정이
 * 준 {@code StringRedisTemplate} 을 그냥 주입받아 쓴다.
 *
 * <p>Pub/Sub 은 <b>SSE 이벤트 팬아웃 용도로만</b> 쓴다. 작업 큐·도메인 이벤트 버스·인스턴스 간
 * RPC 로 확장하지 않는다 — 그건 별도 논의가 필요하다.
 *
 * <p><b>알려진 제약</b>: ElastiCache 클러스터 모드를 켜면 이 배선은 동작하지 않는다. cluster mode
 * enabled 에서는 Redis 7 의 sharded pub/sub({@code SSUBSCRIBE})이 필요한데
 * {@code RedisMessageListenerContainer} 는 일반 pub/sub 만 다루고 {@code PSUBSCRIBE} 는 클러스터에서
 * 문제가 된다. <b>클러스터 모드 비활성을 전제로 설계했다.</b>
 */
@Slf4j
@Configuration
public class RedisMessageListenerConfig {

    /** 동시 디스패치 상한(#566, 사람 확인 2026-08-18). 근거는 클래스 아래 필드 옆 주석 참고. */
    private static final int MAX_CONCURRENT_DISPATCH = 1_000;

    /**
     * 메시지 디스패치 전용 실행기. <b>반드시 지정해야 한다.</b>
     *
     * <p>{@code RedisMessageListenerContainer} 는 미지정 시
     * {@code createDefaultTaskExecutor()} 로 {@code SimpleAsyncTaskExecutor} 를 쓰고,
     * {@code dispatchMessage} 가 <b>메시지마다 {@code executor.execute}</b> 를 부른다. 그 실행기는
     * 매 호출에 <b>새 플랫폼 스레드를 만들고 버린다</b>(동시성 제한 기본 무제한). BUSY 라이더가
     * 100명이면 초당 20개, 500명이면 초당 100개의 스레드 생성·파괴다 — 동시 구독 고객 수보다
     * 이쪽이 먼저 무너진다.
     *
     * <p><b>고정 풀(4) → 가상 스레드 + Semaphore({@value #MAX_CONCURRENT_DISPATCH})로 바꾼 이유
     * (#566)</b>: 예전엔 {@code SseEmitter.send} 가 블로킹이라 플랫폼 스레드를 4개로 고정해
     * 격리를 얻었는데, 그 대가로 <b>느린 클라이언트가 풀 크기(4)만큼만 있어도 이 인스턴스의
     * 모든 배송이 막혔다</b> — 실측(느린 클라이언트 5개 재현)으로 최대 60초 동안 무관한 위치
     * 갱신 73,956건이 큐 포화로 유실됨을 확인했다. 가상 스레드는 메시지마다 별도 스레드를 배정해
     * 이 격리 문제를 근본적으로 없앤다.
     *
     * <p><b>{@code Semaphore} 를 쓰는 이유</b>: 상한이 없으면 극단적인 메시지 폭주 시 파킹된
     * 가상 스레드가 무제한으로 쌓일 수 있다(이 앱은 힙이 512m 로 고정돼 있다, #502). permit이
     * 없으면 {@code sse.fanout.dropped} 카운터를 올리고 그 자리에서 스킵한다 — 위치 데이터는 최신 값만
     * 의미 있고(#297) 다음 위치 갱신이 곧 다시 오므로(#391) at-most-once 로 감내한다.
     *
     * <p><b>{@code tryAcquire()} (넌블로킹) 이어야 한다.</b> {@code RedisMessageListenerContainer}
     * 는 이 실행기를 자기 구독 스레드(Lettuce 이벤트루프)에서 직접 호출한다. 여기서 블로킹하면
     * Redis 구독 처리 자체가 멈춘다 — {@code acquire()} 로 바꾸지 말 것.
     */
    @Bean
    public Executor trackingEventExecutor(MeterRegistry registry) {
        ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
        Semaphore limiter = new Semaphore(MAX_CONCURRENT_DISPATCH);
        Counter dropped = Counter.builder("sse.fanout.dropped")
                .description("동시 디스패치 상한 초과로 유실된 팬아웃 이벤트(at-most-once)")
                .register(registry);
        Gauge.builder("sse.fanout.in_flight", limiter,
                        l -> MAX_CONCURRENT_DISPATCH - l.availablePermits())
                .description("현재 동시에 처리 중인 팬아웃 디스패치 수")
                .register(registry);

        return command -> {
            if (!limiter.tryAcquire()) {
                dropped.increment();
                log.warn("event=SSE_FANOUT_BACKPRESSURE_DROP");
                return;
            }
            virtualThreads.execute(() -> {
                try {
                    command.run();
                } finally {
                    limiter.release();
                }
            });
        };
    }

    /**
     * 배송별 채널을 <b>패턴 하나로</b> 구독한다. 그 선택의 근거는
     * {@link TrackingChannel#pattern()} 에 적어 뒀다 — 배송별 동적 구독은 구독이 어긋났을 때
     * 고객 스트림이 조용히 죽는 실패 모드를 만든다.
     *
     * <p>{@code ErrorHandler} 를 지정하는 이유: 컨테이너는 리스너에서 올라온 {@code Throwable} 을
     * 삼켜 로그만 남긴다. 기본 로거로는 어느 이벤트가 왜 사라졌는지 알 수 없어 관측 가능하게 만든다.
     */
    @Bean
    public RedisMessageListenerContainer trackingMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            TrackingSubscriber subscriber,
            TrackingCloseSubscriber closeSubscriber,
            Executor trackingEventExecutor) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(trackingEventExecutor);
        container.setErrorHandler(throwable ->
                log.error("event=SSE_FANOUT_ERROR reason={}", throwable.getClass().getSimpleName(), throwable));
        container.addMessageListener(subscriber, new PatternTopic(TrackingChannel.pattern()));
        // 종료 신호는 데이터와 별도 채널·별도 리스너다(#450). 접두어가 완전히 달라 위 패턴에
        // 걸리지 않는다 — 같은 접두어 아래 두면 Redis glob 의 * 가 콜론까지 매칭해 종료 신호가
        // 데이터 프레임으로 브라우저에 흘러간다.
        container.addMessageListener(closeSubscriber, new PatternTopic(TrackingChannel.closePattern()));
        return container;
    }
}
