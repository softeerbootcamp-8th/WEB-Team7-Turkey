package com.turkey.quick.location.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.support.IntegrationTestSupport;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 연결 수 제한의 <b>실제 원자성과 자기치유</b>를 실제 Redis 로 검증한다.
 *
 * <p>{@link InMemoryTrackingConnectionLimiterTest} 는 의미론만 본다 — 스레드가 한 JVM 안에만
 * 있어서 "Redis 서버 한 곳에서 스크립트가 직렬화 실행된다"는 성질과는 무관하다. 비원자적 구현
 * (조회 → 세기 → 등록을 따로 호출)에서는 여러 요청이 같은 빈자리를 함께 보고 전부 통과하는데,
 * 그건 여기서만 드러난다.
 *
 * <p>{@code kill -9} 시나리오는 <b>대기 없이</b> 재현한다 — 판정 시각을 인자로 받게 만들어 뒀으므로
 * (스크립트가 Redis {@code TIME} 을 쓰지 않는다) 미래 시각을 넘기면 stale 상황이 된다.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
@DisplayName("RedisTrackingConnectionLimiter")
class RedisTrackingConnectionLimiterIntegrationTest extends IntegrationTestSupport {

    private static final Long ORDER_ID = 42L;

    /** {@code RedisTrackingConnectionLimiter} 의 키 형식과 같아야 한다. 아래 usesExpectedKey 가 확인한다. */
    private static final String KEY_FORMAT = "tracking:order-connections:%d";

    @Autowired
    private RedisTrackingConnectionLimiter limiter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final Instant now = Instant.parse("2026-07-30T02:00:00Z");

    private static String key(Long orderId) {
        return KEY_FORMAT.formatted(orderId);
    }

    private long aliveCount(Long orderId) {
        Long size = redisTemplate.opsForZSet().zCard(key(orderId));
        return size == null ? 0 : size;
    }

    private void fillToLimit() {
        for (int i = 0; i < TrackingStreamPolicy.MAX_CONNECTIONS_PER_ORDER; i++) {
            limiter.tryAcquire(ORDER_ID, "emitter-" + i, now);
        }
    }

    @Nested
    @DisplayName("한도")
    class Limit {

        @Test
        @DisplayName("한도까지는 등록되고 그 다음은 거절된다")
        void acceptsUpToLimitThenRejects() {
            fillToLimit();

            assertThat(limiter.tryAcquire(ORDER_ID, "one-too-many", now)).isFalse();
            assertThat(aliveCount(ORDER_ID))
                    .isEqualTo(TrackingStreamPolicy.MAX_CONNECTIONS_PER_ORDER);
        }

        @Test
        @DisplayName("같은 연결 식별자를 다시 등록해도 한도를 이중으로 쓰지 않는다")
        void doesNotDoubleCountSameEmitter() {
            limiter.tryAcquire(ORDER_ID, "same", now);

            assertThat(limiter.tryAcquire(ORDER_ID, "same", now)).isTrue();
            assertThat(aliveCount(ORDER_ID)).isEqualTo(1);
        }

        @Test
        @DisplayName("배송별로 따로 센다")
        void countsPerOrder() {
            fillToLimit();

            assertThat(limiter.tryAcquire(99L, "other-order", now)).isTrue();
        }

        @Test
        @DisplayName("저장소가 쓰는 키를 이 테스트가 실제로 들여다본다")
        void usesExpectedKey() {
            // 위 케이스들이 구현과 다른 키를 보고 있으면 아무것도 검증하지 못한다.
            limiter.tryAcquire(ORDER_ID, "emitter", now);

            assertThat(redisTemplate.hasKey(key(ORDER_ID))).isTrue();
        }
    }

    @Nested
    @DisplayName("TTL")
    class Ttl {

        @Test
        @DisplayName("등록과 함께 TTL 이 걸린다")
        void setsTtlOnAcquire() {
            // ZADD 와 EXPIRE 를 나누면 그 사이에 프로세스가 죽었을 때 TTL 없는 키가 남고,
            // 그 배송에 아무도 다시 접속하지 않으면 영구히 남는다. 그래서 한 스크립트에 묶었다.
            limiter.tryAcquire(ORDER_ID, "emitter", now);

            assertThat(redisTemplate.getExpire(key(ORDER_ID), TimeUnit.SECONDS))
                    .isPositive()
                    .isLessThanOrEqualTo(TrackingStreamPolicy.CONNECTION_KEY_TTL.toSeconds());
        }

        @Test
        @DisplayName("갱신도 TTL 을 다시 건다")
        void refreshesTtlOnTouch() {
            limiter.tryAcquire(ORDER_ID, "emitter", now);
            redisTemplate.expire(key(ORDER_ID), 5, TimeUnit.SECONDS);

            limiter.touch(ORDER_ID, "emitter", now.plusSeconds(10));

            assertThat(redisTemplate.getExpire(key(ORDER_ID), TimeUnit.SECONDS))
                    .isGreaterThan(5);
        }

        @Test
        @DisplayName("마지막 연결을 해제하면 키가 사라진다")
        void removesKeyWhenLastConnectionReleased() {
            // 빈 ZSET 은 Redis 가 스스로 지운다. 그래서 해제 경로에서 TTL 을 걱정할 필요가 없다.
            limiter.tryAcquire(ORDER_ID, "solo", now);

            limiter.release(ORDER_ID, "solo");

            assertThat(redisTemplate.hasKey(key(ORDER_ID))).isFalse();
        }
    }

    @Nested
    @DisplayName("해제")
    class Release {

        @Test
        @DisplayName("해제하면 자리가 다시 생긴다")
        void freesSlot() {
            fillToLimit();

            limiter.release(ORDER_ID, "emitter-0");

            assertThat(limiter.tryAcquire(ORDER_ID, "replacement", now)).isTrue();
        }

        @Test
        @DisplayName("두 번 해제해도 자리가 두 칸 생기지 않는다")
        void isIdempotent() {
            // onTimeout·onError 뒤에 onCompletion 이 이어서 오므로 정리가 실제로 두 번 불린다.
            // 정수 카운터였다면 여기서 음수로 새어 상한이 무력해진다.
            fillToLimit();

            limiter.release(ORDER_ID, "emitter-0");
            limiter.release(ORDER_ID, "emitter-0");

            assertThat(aliveCount(ORDER_ID))
                    .isEqualTo(TrackingStreamPolicy.MAX_CONNECTIONS_PER_ORDER - 1);
        }
    }

    @Nested
    @DisplayName("자기치유")
    class SelfHealing {

        @Test
        @DisplayName("갱신되지 않은 연결은 stale 임계를 넘으면 자리를 내놓는다")
        void reclaimsStaleConnections() {
            // kill -9 / OOM / half-open TCP 로 release 가 돌지 않은 상황이다. 걷어내지지 않으면
            // 그 배송은 영구히 구독 불가가 된다 — 이 저장소는 systemctl restart + graceful
            // shutdown 미설정이라 배포마다 확정적으로 일어난다.
            fillToLimit();
            Instant afterStale = now.plus(TrackingStreamPolicy.STALE_AFTER).plusSeconds(1);

            assertThat(limiter.tryAcquire(ORDER_ID, "fresh", afterStale)).isTrue();
            // 죽은 기록 전부가 한 번에 걷어내져야 한다.
            assertThat(aliveCount(ORDER_ID)).isEqualTo(1);
        }

        @Test
        @DisplayName("갱신된 연결은 stale 로 판정되지 않는다")
        void keepsTouchedConnections() {
            limiter.tryAcquire(ORDER_ID, "alive", now);
            Instant later = now.plus(TrackingStreamPolicy.STALE_AFTER).plusSeconds(1);
            limiter.touch(ORDER_ID, "alive", later);

            // 이 시점에 새 연결이 들어오면 stale 정리가 돌지만 갱신된 기록은 남아야 한다.
            limiter.tryAcquire(ORDER_ID, "second", later);

            assertThat(aliveCount(ORDER_ID)).isEqualTo(2);
        }

        @Test
        @DisplayName("stale 임계 직전에는 아직 자리를 차지한다")
        void keepsConnectionJustBeforeThreshold() {
            fillToLimit();

            // 정확히 임계값이면 아직 살아 있다(경계 포함). 여기서 부등호가 뒤집히면 정상 연결이
            // heartbeat 한 번 늦었을 때 밀려난다.
            assertThat(limiter.tryAcquire(ORDER_ID, "fresh",
                    now.plus(TrackingStreamPolicy.STALE_AFTER))).isFalse();
        }
    }

    @Nested
    @DisplayName("동시 요청")
    class ConcurrentAcquire {

        private static final int THREADS = 16;

        @Test
        @DisplayName("동시에 몰려도 정확히 한도만큼만 성공한다")
        void allowsExactlyLimitUnderContention() throws InterruptedException {
            // 비원자적 구현(조회 → 세기 → 등록)에서는 여럿이 같은 빈자리를 함께 보고 전부
            // 통과한다. Redis 가 스크립트를 직렬화 실행하므로 한도만큼만 통과해야 한다.
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(THREADS);
            AtomicInteger acquired = new AtomicInteger();

            try (var pool = Executors.newFixedThreadPool(THREADS)) {
                for (int i = 0; i < THREADS; i++) {
                    String emitterId = "emitter-" + i;
                    pool.submit(() -> {
                        try {
                            start.await();
                            if (limiter.tryAcquire(ORDER_ID, emitterId, now)) {
                                acquired.incrementAndGet();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertThat(done.await(10, TimeUnit.SECONDS)).as("모든 스레드가 끝나야 한다").isTrue();
            }

            assertThat(acquired.get()).isEqualTo(TrackingStreamPolicy.MAX_CONNECTIONS_PER_ORDER);
            assertThat(aliveCount(ORDER_ID))
                    .isEqualTo(TrackingStreamPolicy.MAX_CONNECTIONS_PER_ORDER);
        }
    }
}
