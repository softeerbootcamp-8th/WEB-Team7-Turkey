package com.turkey.quick.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 주기 작업 배선. <b>이 저장소에서 스케줄링을 켜는 첫 지점이다</b>(그전까지 {@code @Scheduled} 가
 * 한 곳도 없었다).
 *
 * <p>도입 이유는 SSE heartbeat 하나다({@code TrackingHeartbeatScheduler}, #77). 조용한 스트림이
 * CloudFront 오리진 응답 타임아웃에 끊기는 것을 막고, 같은 tick 에서 죽은 연결을 탐지하고 연결
 * 집계의 생존 시각을 갱신한다.
 *
 * <p><b>수평 확장에서 주의</b>: 여기 등록되는 작업은 <b>모든 인스턴스에서 각자 돈다.</b> heartbeat 는
 * 각 인스턴스가 자기가 들고 있는 연결만 순회하므로 그래도 맞다. 그러나 <b>DB 를 폴링하거나 공유
 * 상태를 집계하는 작업을 여기 추가하면 인스턴스 수만큼 중복 실행된다</b> — 그런 작업은 분산 락이나
 * 리더 선출이 필요하고, 그건 별도 논의 대상이다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    /**
     * 전용 스케줄러를 두는 이유: 스프링 기본 스케줄러는 <b>단일 스레드</b>라, 소켓 버퍼가 가득 찬
     * 클라이언트 하나로의 블로킹 쓰기가 <b>모든 연결의 heartbeat 를 막는다.</b> 그러면 전부
     * 임계를 넘겨 끊긴다.
     *
     * <p>2개도 근본 해결은 아니다 — 느린 소비자가 스레드를 오래 잡으면 여전히 지연된다.
     * 알려진 한계이며, 연결 수가 늘면 이 값을 올리거나 쓰기를 논블로킹으로 바꾸는 것을 검토한다.
     * ({@code SseEmitter.send} 는 블로킹이다.)
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("sse-hb-");
        // 종료 시 진행 중인 heartbeat 를 기다리지 않는다. 기다려도 얻을 것이 없고(연결은 곧 끊긴다)
        // 배포만 느려진다.
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
