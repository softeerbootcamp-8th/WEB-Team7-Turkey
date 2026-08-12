package com.turkey.quick.location.sse;

import tools.jackson.databind.ObjectMapper;
import com.turkey.quick.location.dto.LocationPayload;
import com.turkey.quick.location.dto.StatusChangedPayload;
import com.turkey.quick.order.domain.OrderStatus;
import java.time.Instant;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 라이더의 새 위치, 그리고 배송 상태 전이(#398)를 그 배송의 Pub/Sub 채널로 발행한다(#317).
 * 둘 다 같은 채널을 쓰고 페이로드의 {@code type}으로만 구분된다({@link LocationPayload}는 BUSY
 * 위치 전송 트리거(20m 이동 또는 120초 경과, #391)마다 계속 오지만, {@link StatusChangedPayload}는
 * 전이가 실제로 일어날 때만 온다).
 *
 * <p>이 발행이 팬아웃의 전부다 — 어느 인스턴스에 SSE 연결이 있든 {@link TrackingSubscriber} 가
 * 받아서 자기 연결로만 보낸다. 발행자는 구독자가 있는지 알지 않는다.
 *
 * <p><b>어떤 예외도 밖으로 내지 않는다.</b> 이 호출은 라이더 위치 갱신 POST 경로에 있고, 여기서
 * 실패가 올라가면 위치 갱신 자체가 실패한다 — 그러면 Redis 장애가 고객 추적을 넘어 배차 후보
 * 갱신(#83)까지 같이 죽인다. 전달은 at-most-once 이고 유실은 다음 위치 전송(BUSY, #391)이 복구한다.
 *
 * <p><b>채널 키를 정하는 책임은 여기 없다.</b> 호출자({@code RiderLocationService})가
 * {@code findInProgressByRiderId} 로 라이더의 수행 중 배송을 풀어 넘긴다 — 그래서 라이더가 자기
 * 배송 외의 채널로 발행할 방법이 없다.
 *
 * <p><b>{@code PUBLISH} 의 수신자 수는 쓰지 않는다.</b> 모든 인스턴스가 패턴
 * ({@code tracking:order:*})으로 구독하므로 자기 자신이 포함돼 항상 1 이상이고, 즉 "앱이 떠 있는가"
 * 밖에 알려주지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrackingPublisher {

    /**
     * 종료 신호의 본문(#450). 구독자는 채널명만 보고 본문은 읽지 않으므로 값 자체에 의미는 없다.
     * Redis PUBLISH 는 빈 본문도 허용하지만, 로그·모니터링에서 무엇이 오갔는지 알아보기 쉽게
     * 사람이 읽을 수 있는 상수를 둔다.
     */
    private static final String CLOSE_SIGNAL = "close";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void publish(Long deliveryId, LocationPayload location) {
        publish(deliveryId, (Object) location);
    }

    /**
     * 상태 전이가 실제로 일어났을 때만 부른다(#398) — 위치처럼 주기적으로 발행하지 않는다.
     *
     * <p>호출자가 트랜잭션 안에 있으면 발행을 커밋 후로 미룬다. 커밋 전에 발행하면 Redis 알림이
     * 먼저 도착해, 그 알림을 받은 고객이 곧바로 재조회해도 아직 커밋 전이라 옛 상태를 읽는
     * "이른 재조회" 경쟁이 생긴다 — 트랜잭션 안 어디서 호출하든(맨 끝이어도) 이 메서드가 반환된
     * 뒤에야 커밋되므로, 호출 위치로는 막을 수 없다. 트랜잭션이 없으면(예: 트랜잭션 없는 서비스에서
     * 직접 호출) 그대로 즉시 발행한다.
     */
    public void publishStatus(Long deliveryId, OrderStatus status, Instant occurredAt) {
        afterCommit(() -> publish(deliveryId, new StatusChangedPayload(status, occurredAt)));
    }

    /**
     * 그 배송을 구독 중인 SSE 연결을 닫으라는 신호를 발행한다(#450).
     *
     * <p>완료·취소 시점에 부른다. 받은 인스턴스는 <b>자기 레지스트리의</b> emitter 만 닫고, 그러면
     * 브라우저가 자동 재연결 → 서버가 DB 로 터미널 판정 → 409 → 영구 종료 → 재조회로 이어진다.
     * 즉 <b>이 신호가 유실돼도 정합성은 깨지지 않는다</b> — emitter 절대 수명(5분)이 같은 사슬을
     * 만들기 때문이고, 이 신호는 그 5분을 재연결 왕복(약 3초)으로 줄이는 최적화다.
     *
     * <p>본문을 쓰지 않는 이유: 구독자는 채널명에서 배송 식별자만 꺼내면 되고, 그래야
     * {@link TrackingSubscriber} 와 같은 "파싱하지 않는다" 규약을 유지할 수 있다. 나중에 종료
     * 사유(완료/취소)를 구분해야 하면 그때 본문을 채운다.
     */
    public void publishClose(Long deliveryId) {
        afterCommit(() -> publishRaw(TrackingChannel.closeOf(deliveryId), () -> CLOSE_SIGNAL, deliveryId));
    }

    /**
     * 호출자가 트랜잭션 안에 있으면 발행을 커밋 후로 미룬다. 커밋 전에 발행하면 Redis 알림이 먼저
     * 도착해, 그 알림을 받은 고객이 곧바로 재조회해도 아직 커밋 전이라 옛 상태를 읽는 "이른 재조회"
     * 경쟁이 생긴다 — 트랜잭션 안 어디서 호출하든(맨 끝이어도) 그 메서드가 반환된 뒤에야 커밋되므로,
     * 호출 위치로는 막을 수 없다. 트랜잭션이 없으면 그대로 즉시 발행한다.
     */
    private void afterCommit(Runnable publishAction) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishAction.run();
                }
            });
            return;
        }
        publishAction.run();
    }

    private void publish(Long deliveryId, Object payload) {
        // 직렬화를 여기서 한 번만 한다. 구독자는 파싱하지 않고 이 문자열을 SSE data: 로 그대로
        // 흘리므로, 페이로드 계약이 이 한 줄에만 존재한다.
        publishRaw(TrackingChannel.of(deliveryId), () -> objectMapper.writeValueAsString(payload), deliveryId);
    }

    /**
     * <b>어떤 예외도 밖으로 내지 않는다</b>(클래스 Javadoc 참고). 직렬화 실패와 Redis 실패를 함께
     * 잡는다 — 처리 방식이 같다. 전달은 at-most-once 이고, 유실은 다음 위치 전송(BUSY, #391)이나
     * 재연결 경로가 복구한다.
     *
     * <p><b>본문을 {@link Supplier} 로 받는 이유</b>: 직렬화도 이 try 안에서 일어나야 한다.
     * 밖에서 미리 문자열을 만들어 넘기면 직렬화 실패가 이 방어막을 우회해 호출자에게 올라가는데,
     * {@link #publishStatus} 는 트랜잭션 커밋 후 콜백에서 실행되므로 거기서 터지면 커밋 완료
     * 처리에까지 영향이 간다.
     */
    private void publishRaw(String channel, Supplier<String> body, Long deliveryId) {
        try {
            redisTemplate.convertAndSend(channel, body.get());
        } catch (RuntimeException e) {
            log.warn("event=SSE_PUBLISH_FAILED deliveryId={} channel={} reason={}",
                    deliveryId, channel, e.getClass().getSimpleName(), e);
        }
    }
}
