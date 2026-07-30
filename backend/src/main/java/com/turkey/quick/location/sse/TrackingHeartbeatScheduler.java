package com.turkey.quick.location.sse;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 이 인스턴스가 들고 있는 SSE 연결에 주기적으로 주석 프레임을 보낸다.
 *
 * <p>한 tick 이 세 가지를 동시에 한다 — 그게 heartbeat 를 도입한 이유다.
 * <ol>
 *   <li><b>keep-alive</b>: CloudFront 는 스트리밍 응답에서 패킷 사이 간격에 타임아웃을 적용한다.
 *       그런데 정지한 라이더의 좌표는 {@code DUPLICATE} 로 판정돼 발행조차 되지 않으므로
 *       (신호 대기·픽업지 대기·실내) <b>조용한 구간이 무한할 수 있다.</b> 이것 없이는 정상 운행
 *       중에 연결이 끊기고 재연결이 반복돼, 재연결마다 세션·주문 조회가 다시 돌아 폴링과 비용이
 *       비슷해진다.</li>
 *   <li><b>죽은 연결 탐지</b>: SSE 는 <b>쓰기를 시도할 때만</b> 끊김을 알 수 있다. 이것 없이는
 *       탭을 닫은 클라이언트의 emitter 가 타임아웃(5분)까지 살아 있는 것으로 취급된다.</li>
 *   <li><b>연결 집계 생존 갱신</b>: 갱신하지 않으면 조용한 스트림이 전부 stale 로 판정돼
 *       연결 상한이 사실상 무력해진다.</li>
 * </ol>
 *
 * <p><b>모든 인스턴스에서 각자 돈다.</b> 자기 레지스트리만 순회하므로 그래도 맞다 — 공유 상태를
 * 집계하는 작업이 아니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrackingHeartbeatScheduler {

    /**
     * 프로퍼티로 덮을 수 있게 두되 <b>기본값은 정책 상수에서 이어 붙인다</b> — 컴파일 타임 상수
     * 연결이라 어노테이션에 쓸 수 있고, 값의 출처가
     * {@link TrackingStreamPolicy#HEARTBEAT_INTERVAL_MILLIS} 하나로 유지된다.
     * 프로퍼티는 테스트가 간격을 짧게 줄여 실제 tick 을 기다리지 않기 위한 것이다.
     */
    private static final String INTERVAL_MILLIS =
            "${tracking.sse.heartbeat-interval-ms:" + TrackingStreamPolicy.HEARTBEAT_INTERVAL_MILLIS + "}";

    private final TrackingEmitterRegistry registry;
    private final TrackingConnectionLimiter connectionLimiter;
    private final TrackingSubscriptionService subscriptionService;

    /**
     * {@code fixedRate} 가 아니라 {@code fixedDelay} 인 이유: 느린 소비자 때문에 한 tick 이 길어지면
     * {@code fixedRate} 는 밀린 실행을 몰아 내보내 같은 연결에 heartbeat 가 연달아 나간다.
     * 간격이 조금 늘어나는 것은 stale 임계가 3배 여유를 두고 있어 감내할 수 있다.
     */
    @Scheduled(fixedDelayString = INTERVAL_MILLIS)
    public void sendHeartbeats() {
        Instant now = Instant.now();
        for (TrackingConnection connection : registry.all()) {
            if (connection.sendHeartbeat()) {
                touch(connection, now);
            } else {
                // 전송 실패가 곧 "클라이언트가 사라졌다"는 신호다. 여기서 정리하지 않으면
                // emitter 타임아웃까지 연결 자리를 차지한다.
                subscriptionService.disconnect(connection, "HEARTBEAT_FAILED");
            }
        }
    }

    /**
     * 생존 갱신 실패는 연결을 끊을 이유가 아니다. 갱신되지 않은 기록은 stale 임계가 걷어내고,
     * 그 결과는 "상한이 느슨해짐"이라 안전한 방향이다 — 반대로 여기서 연결을 끊으면 Redis 장애가
     * 고객 화면을 통째로 죽인다.
     */
    private void touch(TrackingConnection connection, Instant now) {
        try {
            connectionLimiter.touch(connection.orderId(), connection.emitterId(), now);
        } catch (DataAccessException e) {
            log.warn("event=SSE_TOUCH_FAILED orderId={} emitterId={}",
                    connection.orderId(), connection.emitterId(), e);
        }
    }
}
