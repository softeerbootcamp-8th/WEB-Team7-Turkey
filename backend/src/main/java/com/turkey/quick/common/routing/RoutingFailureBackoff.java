package com.turkey.quick.common.routing;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * 라우팅 서버가 계속 죽어 있을 때 호출 자체를 잠시 멈추는 장치(#420, 사람 확인 2026-08-07).
 *
 * <p><b>TCP 재전송을 오마주한 형태다.</b> TCP 가 ACK 이 안 오면 무한정 같은 속도로 재전송하지 않고
 * RTO 를 배로 늘려 가며 기다리듯, 여기서도 연속 실패가 {@link #FAILURE_THRESHOLD} 회에 이르면
 * 대기 시간을 두고 그 사이의 호출을 건너뛴다. 대기 시간은 실패가 이어질 때마다 두 배가 되고
 * ({@link #INITIAL_BACKOFF} → … → {@link #MAX_BACKOFF} 상한), 한 번이라도 성공하면 초기화된다.
 *
 * <p><b>이게 없으면</b> OSRM 이 죽어 있는 동안 모든 고객의 추적 API 가 매 호출 읽기 타임아웃
 * (700ms)만큼 느려진다. 백업 폴링이 1분 주기(#422)라 동시 추적 수에 비례해 계속 쌓인다.
 * 서버가 죽은 것을 이미 세 번 확인했는데 네 번째 호출로 또 확인할 이유가 없다.
 *
 * <p><b>일부러 단순하게 둔 것</b>: 대기가 끝난 뒤 흘려보내는 호출 수를 1개로 제한하지 않는다
 * (엄밀한 half-open 이 아니다). 그러려면 in-flight 상태가 하나 더 필요한데, 그 사이에 몰릴 수 있는
 * 요청이 많아야 몇 건이고(추적 API 는 1분 폴링), 그중 하나만 실패해도 즉시 다시 닫히므로 손해가
 * 크지 않다. 실패 카운트도 인스턴스별로 따로 센다 — 공유하려면 Redis 가 필요한데, 각 인스턴스가
 * 스스로 세 번 확인하는 비용이 그보다 싸다.
 *
 * <p>라이브러리(resilience4j 등)를 쓰지 않은 이유는 새 의존성 추가가 사람 확인 사항인데
 * (CLAUDE.md), 필요한 동작이 이 정도라서다.
 */
class RoutingFailureBackoff {

    /** 이 횟수만큼 연속 실패하면 호출을 멈춘다. TCP 가 몇 번 재전송해 보고 물러서는 것과 같은 자리. */
    static final int FAILURE_THRESHOLD = 3;

    static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);

    /** 상한. 이보다 길면 OSRM 이 복구된 뒤에도 ETA 가 한참 비어 있게 된다. */
    static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    /**
     * @param consecutiveFailures 마지막 성공 이후의 연속 실패 수
     * @param backoffNanos        현재 대기 길이. 0 이면 아직 한 번도 물러선 적이 없다는 뜻이다
     * @param resumeAtNanos       이 시각부터 호출을 다시 허용한다.
     *                            {@code consecutiveFailures < FAILURE_THRESHOLD} 일 때는 의미가 없다
     */
    private record State(int consecutiveFailures, long backoffNanos, long resumeAtNanos) {

        static final State CLOSED = new State(0, 0L, 0L);
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);

    /**
     * 시각 공급자. {@code System::nanoTime} 이 기본이고 테스트만 다른 값을 넣는다 — 실제 시간을
     * 기다리는 테스트는 느리거나 불안정해진다.
     */
    private final LongSupplier nanoTime;

    RoutingFailureBackoff() {
        this(System::nanoTime);
    }

    RoutingFailureBackoff(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    /**
     * 지금 라우팅 서버를 호출해도 되는지.
     *
     * @return false 면 호출하지 말고 곧바로 "경로 없음"으로 처리한다
     */
    boolean allowsCall() {
        State current = state.get();
        if (current.consecutiveFailures() < FAILURE_THRESHOLD) {
            return true;
        }
        // 뺄셈으로 비교한다 — System.nanoTime() 은 음수일 수 있고 언젠가 한 바퀴 돌기 때문에
        // 두 값을 부등호로 직접 비교하면 그 순간 판정이 뒤집힌다(Javadoc 이 명시하는 사용법).
        return nanoTime.getAsLong() - current.resumeAtNanos() >= 0;
    }

    /** 호출이 성공했다. 대기 길이까지 초기화한다. */
    void recordSuccess() {
        state.set(State.CLOSED);
    }

    /** 호출이 실패했다(연결 실패·타임아웃·서버 오류). */
    void recordFailure() {
        long now = nanoTime.getAsLong();
        state.updateAndGet(current -> {
            int failures = current.consecutiveFailures() + 1;
            if (failures < FAILURE_THRESHOLD) {
                return new State(failures, current.backoffNanos(), current.resumeAtNanos());
            }
            long next = current.backoffNanos() == 0L
                    ? INITIAL_BACKOFF.toNanos()
                    : Math.min(current.backoffNanos() * 2, MAX_BACKOFF.toNanos());
            return new State(failures, next, now + next);
        });
    }
}
