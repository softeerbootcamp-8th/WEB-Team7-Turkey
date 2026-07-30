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
 * <p>주문별 맵을 {@code compute} 로만 고치는 이유: 확인과 변경이 쪼개지면 마지막 연결이 빠지는
 * 순간에 빈 맵이 남거나(주문 수만큼 누수), 지워진 항목이 되살아난다.
 *
 * <p><b>"첫 연결 / 마지막 연결" 신호를 두지 않는다.</b> 처음에는 그 신호로 Pub/Sub 채널을
 * 주문별로 구독·해제할 계획이었지만, 패턴 하나로 구독하는 쪽으로 바꿨다
 * ({@link TrackingChannel#pattern()} 에 근거를 적어 뒀다). 쓰지 않는 판정을 남기면 다음 사람이
 * 그것에 의존하는 코드를 만든다.
 */
@Component
public class TrackingEmitterRegistry {

    private final Map<Long, Map<String, TrackingConnection>> byOrder = new ConcurrentHashMap<>();

    public void add(TrackingConnection connection) {
        byOrder.compute(connection.orderId(), (orderId, connections) -> {
            Map<String, TrackingConnection> next =
                    connections == null ? new ConcurrentHashMap<>() : connections;
            next.put(connection.emitterId(), connection);
            return next;
        });
    }

    /**
     * 없는 연결을 지워도 오류가 아니다 — {@code onTimeout} 뒤에 {@code onCompletion} 이 이어 오므로
     * 정리가 두 번 이상 불린다.
     */
    public void remove(Long orderId, String emitterId) {
        byOrder.computeIfPresent(orderId, (id, connections) -> {
            connections.remove(emitterId);
            // null 을 돌려주면 키 자체가 사라진다 — 빈 맵을 남기면 추적한 주문 수만큼 누수된다.
            return connections.isEmpty() ? null : connections;
        });
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
