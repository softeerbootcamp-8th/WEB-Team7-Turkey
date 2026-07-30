package com.turkey.quick.location.sse;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 이 인스턴스가 들고 있는 SSE 연결 목록. 주문별로 인덱싱한다.
 *
 * <p><b>"상태를 JVM 에 두지 않는다" 규칙의 유일한 예외다</b>({@code CLAUDE.md} 「확정된 결정」).
 * {@code SseEmitter} 는 그 JVM 의 열린 HTTP 응답에 묶여 있어 직렬화해 공유할 방법이 없다.
 * 그래서 위치 이벤트를 Redis Pub/Sub 으로 모든 인스턴스에 뿌리고, <b>해당 주문의 emitter 를
 * 가진 인스턴스만</b> 실제로 전송한다. 다만 <b>연결 개수 같은 집계값은 이 예외에 포함되지
 * 않는다</b> — 그건 {@link TrackingConnectionLimiter} 가 Redis 로 센다.
 *
 * <p><b>{@code static} 필드로 만들면 안 된다.</b> 지금은 인스턴스 필드라 컨텍스트마다 하나씩
 * 생기고, 그래서 한 JVM 에 두 애플리케이션 컨텍스트를 띄우는 팬아웃 테스트가 "인스턴스 로컬"을
 * 실제로 재현할 수 있다. {@code static} 으로 바꾸면 두 컨텍스트가 같은 맵을 공유해
 * <b>테스트가 통과하면서 아무것도 증명하지 못한다</b> — 그래서 그 테스트가
 * {@code registryA != registryB} 와 "B 의 레지스트리가 비어 있음"을 함께 단언한다.
 *
 * <p>주문별 맵을 {@code compute} 로만 고치는 이유: "이 주문의 첫 연결인가 / 마지막 연결인가"를
 * 확인과 변경이 쪼개지지 않은 채로 판정해야 한다. 그 판정이 Pub/Sub 채널 구독·해제 시점
 * (#78 {@code TrackingSubscriptionManager})을 결정하므로, 어긋나면 구독이 새거나 이벤트를
 * 받지 못한다.
 */
@Component
public class TrackingEmitterRegistry {

    private final Map<Long, Map<String, TrackingConnection>> byOrder = new ConcurrentHashMap<>();

    /**
     * @return 이 주문의 <b>첫</b> 연결이면 true. 호출자가 그때만 채널을 구독하기 위한 신호다
     */
    public boolean add(TrackingConnection connection) {
        boolean[] first = {false};
        byOrder.compute(connection.orderId(), (orderId, connections) -> {
            Map<String, TrackingConnection> next =
                    connections == null ? new ConcurrentHashMap<>() : connections;
            first[0] = next.isEmpty();
            next.put(connection.emitterId(), connection);
            return next;
        });
        return first[0];
    }

    /**
     * @return 이 주문의 <b>마지막</b> 연결이 사라졌으면 true. 호출자가 그때만 채널 구독을 해제한다.
     *         이미 지워진 연결을 다시 지우면 false 다 — {@code onTimeout} 뒤에
     *         {@code onCompletion} 이 이어 오므로 정리가 두 번 불리는데, 구독 해제를 두 번
     *         하지 않기 위해서다
     */
    public boolean remove(Long orderId, String emitterId) {
        boolean[] last = {false};
        byOrder.computeIfPresent(orderId, (id, connections) -> {
            if (connections.remove(emitterId) == null) {
                return connections;
            }
            if (connections.isEmpty()) {
                last[0] = true;
                // null 을 돌려주면 키 자체가 사라진다 — 빈 맵을 남기면 주문 수만큼 누수된다.
                return null;
            }
            return connections;
        });
        return last[0];
    }

    /**
     * 사본을 돌려준다. 호출자가 순회하면서 실패한 연결을 제거하므로(전송 실패 정리) 원본을
     * 그대로 노출하면 순회 중 변경이 섞인다.
     */
    public Collection<TrackingConnection> connectionsOf(Long orderId) {
        Map<String, TrackingConnection> connections = byOrder.get(orderId);
        return connections == null ? List.of() : List.copyOf(connections.values());
    }

    /** heartbeat 가 이 인스턴스의 전 연결을 순회할 때 쓴다. 위와 같은 이유로 사본이다. */
    public Collection<TrackingConnection> all() {
        return byOrder.values().stream()
                .flatMap(connections -> connections.values().stream())
                .toList();
    }

    /** 테스트가 이 인스턴스의 연결 수를 직접 확인할 때 쓴다(팬아웃 테스트의 "B 는 비어 있다"). */
    public int size() {
        return byOrder.values().stream().mapToInt(Map::size).sum();
    }
}
