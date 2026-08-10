package com.turkey.quick.location.sse;

import tools.jackson.databind.ObjectMapper;
import com.turkey.quick.location.dto.LocationPayload;
import com.turkey.quick.location.dto.StatusChangedPayload;
import com.turkey.quick.order.domain.OrderStatus;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 라이더의 새 위치, 그리고 배송 상태 전이(#398)를 그 배송의 Pub/Sub 채널로 발행한다(#317).
 * 둘 다 같은 채널을 쓰고 페이로드의 {@code type}으로만 구분된다({@link LocationPayload}는
 * 5초~30초 주기로 계속 오지만, {@link StatusChangedPayload}는 전이가 실제로 일어날 때만 온다).
 *
 * <p>이 발행이 팬아웃의 전부다 — 어느 인스턴스에 SSE 연결이 있든 {@link TrackingSubscriber} 가
 * 받아서 자기 연결로만 보낸다. 발행자는 구독자가 있는지 알지 않는다.
 *
 * <p><b>어떤 예외도 밖으로 내지 않는다.</b> 이 호출은 라이더 위치 갱신 POST 경로에 있고, 여기서
 * 실패가 올라가면 위치 갱신 자체가 실패한다 — 그러면 Redis 장애가 고객 추적을 넘어 배차 후보
 * 갱신(#83)까지 같이 죽인다. 전달은 at-most-once 이고 유실은 다음 위치(BUSY 5초 주기)가 복구한다.
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
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(deliveryId, new StatusChangedPayload(status, occurredAt));
                }
            });
            return;
        }
        publish(deliveryId, new StatusChangedPayload(status, occurredAt));
    }

    private void publish(Long deliveryId, Object payload) {
        try {
            // 직렬화를 여기서 한 번만 한다. 구독자는 파싱하지 않고 이 문자열을 SSE data: 로 그대로
            // 흘리므로, 페이로드 계약이 이 한 줄에만 존재한다.
            redisTemplate.convertAndSend(TrackingChannel.of(deliveryId),
                    objectMapper.writeValueAsString(location));
        } catch (RuntimeException e) {
            log.warn("event=SSE_PUBLISH_FAILED deliveryId={} reason={}",
                    deliveryId, e.getClass().getSimpleName(), e);
        }
    }
}
