package com.turkey.quick.location.sse;

import java.time.Instant;

/**
 * 한 배송의 동시 구독 연결 수를 제한한다(#77 비고).
 *
 * <p><b>왜 공유 저장소가 필요한가</b>: {@code SseEmitter} 레지스트리는 인스턴스 로컬이다(그 JVM 의
 * 열린 HTTP 응답에 묶여 있어 공유가 불가능하다 — {@code CLAUDE.md} 가 "상태를 JVM 에 두지
 * 않는다" 규칙의 유일한 예외로 명시한 것이 그것이다). 그런데 <b>연결 개수 같은 집계값은 그 예외에
 * 포함되지 않는다</b> — 같은 배송의 연결이 여러 인스턴스에 흩어지므로 각 인스턴스가 자기 것만
 * 세면 실제 총합보다 적게 나온다. 그래서 이 집계만 Redis 로 뺀다.
 *
 * <p>인터페이스로 분리한 이유는 {@code RiderLocationStore} 와 같다 — 단위 테스트가 스프링 없이
 * 인메모리 대체로 돌 수 있어야 한다. 실제 원자성은 통합 테스트가 실제 Redis 로 검증한다.
 *
 * <p><b>구현이 지켜야 하는 의미론</b>:
 * <ul>
 *   <li>{@code tryAcquire} 는 <b>한도 검사와 등록이 하나의 원자적 연산</b>이어야 한다. 나누면
 *       여러 인스턴스가 같은 빈자리를 함께 보고 전부 통과한다.</li>
 *   <li>{@code release} 는 <b>멱등</b>이어야 한다. {@code SseEmitter} 의
 *       {@code onTimeout}/{@code onError} 뒤에는 {@code onCompletion} 이 이어서 오므로
 *       정리 코드가 두 번 이상 호출된다.</li>
 *   <li>갱신되지 않은 기록은 {@link TrackingStreamPolicy#STALE_AFTER} 뒤에 <b>스스로</b>
 *       사라져야 한다. 인스턴스가 비정상 종료하면 {@code release} 가 돌지 않는데, 그 기록이
 *       영구히 남으면 그 배송은 상한에 걸려 다시 구독할 수 없다. 이건 이 저장소의 배포 방식에서
 *       <b>롤링 배포마다 확정적으로 일어난다</b>({@code systemctl restart}, graceful shutdown 없음).</li>
 * </ul>
 */
public interface TrackingConnectionLimiter {

    /**
     * 연결 하나를 등록하고 한도를 넘지 않았는지 판정한다.
     *
     * @param orderId   배송요청 식별자
     * @param emitterId 이 연결의 식별자(UUID). 인스턴스가 달라도 겹치지 않아야 한다
     * @param now       판정 기준 시각. 이 값으로 stale 기록을 걷어내고 등록 시각을 기록한다
     * @return 등록했으면 true. false 면 이미 한도만큼의 살아 있는 연결이 있다는 뜻이며,
     *         <b>이 경우 아무것도 등록하지 않는다</b>(부분 성공 없음)
     */
    boolean tryAcquire(Long orderId, String emitterId, Instant now);

    /** 연결 기록을 지운다. 없는 기록을 지워도 오류가 아니다(멱등). */
    void release(Long orderId, String emitterId);

    /**
     * 연결이 아직 살아 있음을 알린다(heartbeat 마다 호출). 이 갱신이 없으면
     * {@link TrackingStreamPolicy#STALE_AFTER} 뒤에 죽은 것으로 간주돼 걷어내진다.
     *
     * <p>이미 걷어내진 뒤에 호출되면 다시 등록된다 — 그 경우 한도를 일시적으로 넘을 수 있지만,
     * 실제로 살아 있는 연결을 세는 것이므로 <b>안전한 방향</b>이다(과대 집계는 고객을 잠그고,
     * 과소 집계는 상한이 느슨해질 뿐이다).
     */
    void touch(Long orderId, String emitterId, Instant now);
}
