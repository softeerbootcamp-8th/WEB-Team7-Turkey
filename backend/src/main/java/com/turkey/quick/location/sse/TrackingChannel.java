package com.turkey.quick.location.sse;

import java.util.Optional;

/**
 * 위치 이벤트 팬아웃에 쓰는 Redis Pub/Sub 채널명(#317, 원본 #78).
 *
 * <p><b>채널 키가 배송인 이유</b>: 그 배송을 구독 중인 고객에게만 보내면 되고, 라이더를 키로 잡으면
 * 배송이 끝난 뒤 그 라이더의 <i>다음</i> 배송 경로가 이전 고객에게 흘러 개인정보가 샌다. 배송을
 * 키로 두면 라이더가 그 배송에 위치를 더 보내지 않는 순간 발행이 멈춰 <b>스트림이 조용해지는 안전한
 * 실패</b>가 된다(주문 완료 시 능동적 연결 종료는 아직 없다, #80).
 *
 * <p>순수 함수만 담아 {@code static} 으로 뒀다 — 이 클래스에서 실제로 깨지는 것은 Redis 호출이
 * 아니라 문자열 형식이고, 그건 목킹 없이 직접 검증할 수 있다.
 */
public final class TrackingChannel {

    /**
     * 콜론 구분 소문자 네임스페이스(저장소 관례: {@code session:}, {@code riders:geo},
     * {@code rider:location:}). <b>이 접두사는 배포 호환성 표면이다</b> — 바꾸면 롤링 배포 중 구·신
     * 버전이 서로 다른 채널을 쓰게 되어 그 구간의 이벤트가 전달되지 않는다.
     *
     * <p>알려진 한계: Pub/Sub 채널은 {@code SELECT} 로 격리되지 않아 <b>테스트(로직 DB 1)와 개발용
     * 앱(DB 0)이 같은 채널을 공유한다.</b> 프로파일별 접두어 분리는 미결이다(CLAUDE.md).
     */
    private static final String PREFIX = "tracking:order:";

    /**
     * 연결 종료 신호 채널(#450). 데이터 채널과 <b>완전히 다른 접두어여야 한다.</b>
     *
     * <p>{@code tracking:order:{id}:close} 처럼 데이터 채널 아래에 두면 안 된다 — Redis glob 의
     * {@code *} 는 콜론을 포함해 매칭하므로 {@link #pattern()}({@code tracking:order:*})에 그대로
     * 걸리고, {@link TrackingSubscriber} 가 종료 신호를 <b>데이터 프레임으로 브라우저에 흘려보낸다.</b>
     *
     * <p>같은 채널에 {@code type} 필드로 얹지 않고 채널을 나눈 <b>결정적 이유는 누출을 구조로
     * 막기 위해서다.</b> 같은 채널이면 {@link TrackingSubscriber} 가 릴레이할지 닫을지를 판단해야
     * 하고, 그 판단이 틀리면 종료 신호가 SSE {@code data:} 로 브라우저에 도착한다. 채널을 나누면
     * <b>리스너가 아예 달라 그 실수가 성립하지 않는다.</b> 판별자를 이미 디스패치를 수행하는
     * 계층(Redis 패턴 매칭)에 두는 것이기도 하다.
     *
     * <p>부수적으로 {@code TrackingSubscriber} 의 "파싱하지 않고 흘린다"도 유지된다. 다만 그건
     * 불가침 계약이 아니라 #78 의 구현 선택이다 — 서버측 순서 가드처럼 파싱이 필요한 요구가 생기면
     * 그때는 채널을 더 쪼갤 게 아니라 파싱하는 것이 맞다.
     */
    private static final String CLOSE_PREFIX = "tracking:close:";

    private TrackingChannel() {
    }

    public static String of(Long deliveryId) {
        return PREFIX + deliveryId;
    }

    /** 그 배송을 구독 중인 연결을 닫으라는 신호를 보낼 채널(#450). */
    public static String closeOf(Long deliveryId) {
        return CLOSE_PREFIX + deliveryId;
    }

    /** 모든 배송의 종료 신호를 한 번에 구독하는 패턴. {@link #pattern()} 과 겹치지 않는다. */
    public static String closePattern() {
        return CLOSE_PREFIX + "*";
    }

    /** {@link #deliveryIdOf} 와 같은 규약 — 형식이 어긋나면 예외가 아니라 빈 결과다. */
    public static Optional<Long> deliveryIdOfClose(String channel) {
        return parseAfter(channel, CLOSE_PREFIX);
    }

    /**
     * 모든 배송 채널을 한 번에 구독하는 패턴({@code PSUBSCRIBE}).
     *
     * <p><b>배송별로 구독·해제하지 않고 패턴 하나로 받는 이유</b>:
     * <ul>
     *   <li>배송별 동적 구독은 refcount 가 필요하고, 같은 배송이 1→0 과 0→1 을 동시에 겪을 때
     *       (탭을 닫고 바로 다시 여는 흔한 동작) 구독 호출 순서가 뒤집힐 수 있다. 그 결과가
     *       "구독이 없는데 연결은 있다"면 <b>고객 스트림이 조용히 죽는다</b> — 탐지하기 가장
     *       어려운 실패다.</li>
     *   <li>{@code addMessageListener} 는 실제 SUBSCRIBE 왕복이 끝날 때까지 블록하므로, Redis
     *       지연이 연결 생성 경로에 얹힌다.</li>
     *   <li>구독자(리스너)와 연결 정리가 서로를 필요로 해 순환 의존이 생긴다.</li>
     * </ul>
     * 대가는 <b>모든 인스턴스가 모든 추적 이벤트를 받아 걸러낸다</b>는 것이다. 비용은
     * (BUSY 라이더 수 × 위치 전송 트리거 빈도, 최소 0.5초~최대 120초 간격, #391) × 인스턴스 수
     * 만큼의 맵 조회이고, 대부분은 연결이 없어 즉시 끝난다.
     * 인스턴스를 크게 늘려 이 비용이 문제가 되면 <b>채널 이름이 이미 배송별이므로</b> 값 형식을
     * 바꾸지 않고 배송별 구독으로 전환할 수 있다.
     */
    public static String pattern() {
        return PREFIX + "*";
    }

    /**
     * 채널명에서 배송 식별자를 되꺼낸다.
     *
     * <p><b>형식이 어긋나면 예외가 아니라 빈 결과다.</b> 이 메서드는 Redis 가 넘겨준 채널명으로
     * 리스너 스레드에서 호출되는데, 거기서 예외를 던지면
     * {@code RedisMessageListenerContainer} 가 그것을 삼켜 로그만 남기고 <b>그 메시지의 나머지
     * 수신자까지 잃는다.</b>
     */
    public static Optional<Long> deliveryIdOf(String channel) {
        return parseAfter(channel, PREFIX);
    }

    private static Optional<Long> parseAfter(String channel, String prefix) {
        if (channel == null || !channel.startsWith(prefix)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(channel.substring(prefix.length())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
