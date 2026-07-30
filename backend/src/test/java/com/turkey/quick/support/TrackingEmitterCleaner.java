package com.turkey.quick.support;

import com.turkey.quick.location.sse.TrackingConnection;
import com.turkey.quick.location.sse.TrackingEmitterRegistry;
import com.turkey.quick.location.sse.TrackingSubscriptionService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code TrackingEmitterRegistry} 는 JVM 인메모리 싱글턴이라 {@code DatabaseCleaner}·
 * {@code RedisCleaner} 로 정리되지 않는다. 앞 테스트가 연 SSE 연결이 서버 쪽에서 아직 살아 있는
 * 채로 남으면(클라이언트가 소켓을 닫아도 서버는 쓰기를 시도할 때만 그것을 안다), 다음 테스트가
 * {@code fixture.assignedDelivery()} 로 만든 주문이 {@code DatabaseCleaner} 의 TRUNCATE 때문에
 * 거의 항상 같은 주문 id(1)를 받으면서 그 좀비 연결과 연결 상한(3)을 나눠 쓰게 된다.
 *
 * <p>실제로 이렇게 터졌다: {@code TrackingHeartbeatScheduler} 가 이 인스턴스의 레지스트리를
 * 순회하며 살아 있는(것처럼 보이는) 좀비 연결을 {@code touch} 하는데, 그 tick 이 하필
 * {@code rejectsBeyondConnectionLimit} 실행 중에 끼어들면 방금 {@code RedisCleaner} 가 비운
 * 연결 집계가 좀비 연결로 다시 채워져 새 연결이 조기에 429 를 받는다. 타이밍에 걸려야 나는
 * 문제라 플레이키하다 — 매 테스트 전에 좀비 연결을 실제로 끊어 두면 그 tick 이 순회할 대상이
 * 없어진다.
 *
 * <p>레지스트리에서 항목만 지우지 않고 {@link TrackingSubscriptionService#disconnect}(운영
 * 코드가 heartbeat 로 죽은 연결을 정리할 때 쓰는 것과 같은 경로)를 그대로 쓰는 이유: 정리가
 * 레지스트리 제거 하나가 아니라 Redis 연결 집계 해제 + emitter 종료까지 함께 원자적 묶음이어야
 * 하기 때문이다. 직접 지우면 그 묶음을 다시 구현하게 된다.
 */
@Component
@Profile("integration")
public class TrackingEmitterCleaner {

    private final TrackingEmitterRegistry registry;
    private final TrackingSubscriptionService subscriptionService;

    public TrackingEmitterCleaner(TrackingEmitterRegistry registry, TrackingSubscriptionService subscriptionService) {
        this.registry = registry;
        this.subscriptionService = subscriptionService;
    }

    public void clear() {
        for (TrackingConnection connection : registry.all()) {
            subscriptionService.disconnect(connection, "TEST_CLEANUP");
        }
    }
}
