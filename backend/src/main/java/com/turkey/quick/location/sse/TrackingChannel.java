package com.turkey.quick.location.sse;

import java.util.Optional;

/**
 * 위치 이벤트 팬아웃에 쓰는 Redis Pub/Sub 채널명(#78).
 *
 * <p><b>채널 키가 주문인 이유</b>(사람 확인 2026-07-30): 발행자가 "이 라이더의 진행 중 배정
 * 주문"을 상태 조건과 함께 조회하므로, 배송이 끝나면 조회가 비어 발행이 멈춘다 —
 * <b>스트림이 조용해지는 안전한 실패</b>다. 라이더를 키로 잡으면 발행 경로의 DB 조회가 사라지는
 * 대신, 완료 후에도 그 라이더의 <i>다음</i> 배송 경로가 이전 고객에게 흘러 개인정보가 샌다.
 * 그걸 막으려면 연결을 능동적으로 끊어야 하는데 주문 완료 전이 코드가 아직 없다.
 *
 * <p>게다가 그 조회는 어차피 피할 수 없다 — {@code rider_location_history.order_id} 가
 * NOT NULL(V15)이라 위치 이력 저장(#102)도 같은 경로에서 주문을 풀어야 한다.
 *
 * <p>순수 함수만 담아 {@code static} 으로 둔 이유는 {@code RedisRiderLocationStore} 의
 * {@code encode}/{@code decode} 와 같다 — 이 클래스에서 실제로 깨지는 것은 Redis 호출이 아니라
 * 문자열 형식이고, 그건 목킹 없이 직접 검증할 수 있다.
 */
public final class TrackingChannel {

    /**
     * 콜론 구분 소문자 네임스페이스(저장소 관례: {@code session:}, {@code rider:location:}).
     * <b>이 접두사는 배포 호환성 표면이다</b> — 바꾸면 롤링 배포 중 구·신 버전이 서로 다른 채널을
     * 쓰게 되어 그 구간의 이벤트가 전달되지 않는다.
     */
    private static final String PREFIX = "tracking:order:";

    private TrackingChannel() {
    }

    public static String of(Long orderId) {
        return PREFIX + orderId;
    }

    /**
     * 채널명에서 주문 식별자를 되꺼낸다.
     *
     * <p><b>형식이 어긋나면 예외가 아니라 빈 결과다.</b> 이 메서드는 Redis 가 넘겨준 채널명으로
     * 리스너 스레드에서 호출되는데, 거기서 예외를 던지면
     * {@code RedisMessageListenerContainer} 가 그것을 삼켜 로그만 남기고 <b>그 메시지의 나머지
     * 수신자까지 잃는다.</b> 잘못된 채널명은 무시하고 넘어가는 것이 맞다.
     */
    public static Optional<Long> orderIdOf(String channel) {
        if (channel == null || !channel.startsWith(PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(channel.substring(PREFIX.length())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
