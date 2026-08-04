package com.turkey.quick.common.config;

import com.turkey.quick.location.sse.TrackingChannel;
import com.turkey.quick.location.sse.TrackingSubscriber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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
     * <p><b>풀 크기를 1이 아니라 4로 둔 이유</b>(트레이드오프): {@code SseEmitter.send} 는
     * 블로킹이라, 단일 스레드면 소켓 버퍼가 가득 찬 클라이언트 하나가
     * <b>모든 배송의 위치 전달을 멈춘다.</b> 여러 스레드면 그 격리는 얻지만 같은 채널 메시지의
     * 처리 순서가 뒤집힐 수 있다. 순서 역전은 프론트가 {@code measuredAt} 이 뒤로 가는 이벤트를
     * 버리는 것으로 복구되고, 멈춘 디스패처는 <b>모든 고객의 지도가 조용히 얼어붙는</b> 것이라
     * 복구할 방법이 없다. 그래서 격리를 골랐다.
     */
    @Bean
    public ThreadPoolTaskExecutor trackingEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1_000);
        executor.setThreadNamePrefix("sse-fanout-");
        // 종료 시 남은 메시지를 기다리지 않는다. at-most-once 라 유실은 감내하기로 정했고,
        // 기다리면 배포만 느려진다.
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
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
            ThreadPoolTaskExecutor trackingEventExecutor) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(trackingEventExecutor);
        container.setErrorHandler(throwable ->
                log.error("event=SSE_FANOUT_ERROR reason={}", throwable.getClass().getSimpleName(), throwable));
        container.addMessageListener(subscriber, new PatternTopic(TrackingChannel.pattern()));
        return container;
    }
}
