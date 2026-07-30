package com.turkey.quick.location.sse;

import java.time.Instant;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 연결 수 제한의 Redis 구현. <b>정수 카운터가 아니라 ZSET 이다.</b>
 *
 * <p>멤버는 {@code emitterId}, score 는 마지막으로 살아 있음이 확인된 시각(epoch 밀리초)이다.
 * 즉 "카운터"라기보다 <b>생존 시간이 붙은, 셀 수 있는 연결 집합</b>이다.
 *
 * <p><b>{@code INCR}/{@code DECR} 을 쓰지 않은 이유</b>가 이 클래스의 핵심 설계 근거다.
 * 정수 하나로는 "살아 있는 보유자"와 "죽은 보유자"를 구분할 수 없다. 그러면:
 * <ul>
 *   <li>인스턴스가 비정상 종료하면 {@code DECR} 이 돌지 않아 <b>그 배송은 영구히 구독 불가</b>가
 *       된다. 이 저장소의 배포는 {@code systemctl restart} + graceful shutdown 미설정이라
 *       <b>배포마다 확정적으로</b> 일어나고, 모바일 네트워크 전환(half-open TCP)에서는 더 잦다.</li>
 *   <li>TTL 을 붙여도 해결되지 않는다. 갱신하면 활동 중인 키가 영원히 만료되지 않아 누수가
 *       영구화되고, 갱신하지 않으면 정상 연결의 카운트까지 통째로 날아가 상한이 주기적으로
 *       무효화된다.</li>
 *   <li>{@code DECR} 이 두 번 불리면 음수로 새는 반대 방향 버그가 생긴다 —
 *       {@code onTimeout} 뒤에 {@code onCompletion} 이 이어 오므로 실제로 두 번 불린다.</li>
 * </ul>
 * ZSET 은 stale score 를 걷어내는 것으로 <b>스스로 낫고</b>, {@code ZREM} 이 멱등이며,
 * 오차가 나더라도 과소 집계(상한이 느슨해짐) 쪽으로 떨어져 고객을 잠그지 않는다.
 * 덤으로 운영 중 {@code ZRANGE} 로 실제 연결 목록을 눈으로 볼 수 있다.
 *
 * <p>{@code RedisVerificationCodeStore.incrementAttempts} 가 남긴 한계("상한 검사 + 증가"를
 * 원자화하지 못한다)를 여기서는 Lua 로 닫는다.
 */
@Component
public class RedisTrackingConnectionLimiter implements TrackingConnectionLimiter {

    private static final String KEY_FORMAT = "tracking:order-connections:%d";

    /**
     * 만료 기록 정리 → 현재 수 확인 → 한도 검사 → 등록을 한 번에 수행한다.
     *
     * <p>{@code ZSCORE} 로 이미 있는 멤버인지 먼저 보는 이유: 같은 {@code emitterId} 로 다시
     * 호출되면 새 연결이 아니라 갱신이므로 한도에 이중으로 세면 안 된다. 정상 흐름에서는 UUID 라
     * 겹치지 않지만, 이 검사가 있어야 재시도가 안전해진다.
     *
     * <p>{@code EXPIRE} 를 등록과 같은 스크립트에 둔 이유는 {@code SET ... EX} 를 쓰는 것과 같다 —
     * 나누면 그 사이에 프로세스가 죽었을 때 <b>TTL 없는 키</b>가 남고, 그 배송에 아무도 다시
     * 접속하지 않으면 영구히 남는다.
     *
     * <p>정리 상한에 {@code (} 를 붙여 <b>배타 경계</b>로 만든 것이 중요하다.
     * {@code ZREMRANGEBYSCORE} 의 기본 상한은 포함이라 그냥 쓰면 <b>정확히 임계값인 기록까지
     * 지운다.</b> 그러면 heartbeat 가 딱 임계값에 도착한 살아 있는 연결이 밀려나고, 인메모리
     * 대체({@code isBefore} = 배타)와 경계에서 갈린다 — 실제로 그렇게 갈려서 테스트가 잡았다.
     */
    private static final RedisScript<Long> ACQUIRE = RedisScript.of("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. ARGV[1])
            if redis.call('ZSCORE', KEYS[1], ARGV[3]) == false
                    and redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[4]) then
              return 0
            end
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3])
            redis.call('EXPIRE', KEYS[1], ARGV[5])
            return 1
            """, Long.class);

    /**
     * 생존 시각만 갱신한다. 한도를 검사하지 않는 이유는 {@code touch} 가 <b>이미 존재하는</b>
     * 연결을 대상으로 하기 때문이다 — 여기서 거절하면 살아 있는 연결을 집계에서 지우게 된다.
     *
     * <p>{@code ZADD}+{@code EXPIRE} 를 두 명령으로 나누지 않고 스크립트로 묶은 것도 위와 같은
     * 이유다(TTL 없는 키를 만들지 않는다).
     */
    private static final RedisScript<Long> TOUCH = RedisScript.of("""
            redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisTrackingConnectionLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryAcquire(Long orderId, String emitterId, Instant now) {
        // StringRedisTemplate 이라 인자가 모두 문자열이어야 한다 — 숫자를 그대로 넘기면
        // 직렬화에서 터진다(RedisRiderLocationStore 와 같은 주의).
        Long acquired = redisTemplate.execute(ACQUIRE, List.of(key(orderId)),
                score(TrackingStreamPolicy.staleCutoff(now)),
                score(now),
                emitterId,
                String.valueOf(TrackingStreamPolicy.MAX_CONNECTIONS_PER_ORDER),
                String.valueOf(TrackingStreamPolicy.CONNECTION_KEY_TTL.toSeconds()));
        return Long.valueOf(1L).equals(acquired);
    }

    @Override
    public void release(Long orderId, String emitterId) {
        // ZREM 은 없는 멤버를 지워도 0 을 돌려줄 뿐 오류가 아니다 — 그래서 멱등이고,
        // 마지막 멤버가 빠지면 Redis 가 빈 ZSET 키를 스스로 지우므로 TTL 을 걱정할 필요가 없다.
        redisTemplate.opsForZSet().remove(key(orderId), emitterId);
    }

    @Override
    public void touch(Long orderId, String emitterId, Instant now) {
        redisTemplate.execute(TOUCH, List.of(key(orderId)),
                score(now),
                emitterId,
                String.valueOf(TrackingStreamPolicy.CONNECTION_KEY_TTL.toSeconds()));
    }

    /**
     * ZSET score 는 double 이다. epoch 밀리초는 약 1.8e12 로 double 이 정확히 표현할 수 있는
     * 범위(2^53) 안이라 밀리초 단위 비교가 어긋나지 않는다.
     */
    private static String score(Instant at) {
        return String.valueOf(at.toEpochMilli());
    }

    private static String key(Long orderId) {
        return KEY_FORMAT.formatted(orderId);
    }
}
