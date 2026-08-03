package com.turkey.quick.location.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.location.dto.LocationPayload;
import com.turkey.quick.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 조건부 갱신의 <b>실제 원자성</b>을 실제 Redis 로 검증한다(#317).
 *
 * <p>이 테스트가 인메모리 대체를 두지 않은 이유이기도 하다 — 검증할 가치가 있는 성질이
 * "Redis 서버 한 곳에서 스크립트가 직렬화 실행된다"는 것이라, 한 JVM 안의 스레드로는 재현할 수
 * 없다. 통합·E2E 는 로컬 Docker Redis 에 붙는다(2026-07-29 결정, testing.md).
 *
 * <p>핵심은 두 가지다. <b>같은 시각으로 동시에 쓰면 정확히 하나만 성공한다</b>(비원자적 구현에서는
 * 여럿이 검사를 함께 통과해 모두 쓴다). 그리고 <b>서로 다른 시각으로 동시에 쓰면 최종 값이 항상
 * 가장 최신이다</b>(비원자적 구현에서는 마지막에 도착한 쓰기가 이겨서 오래된 좌표가 최신을 덮는다 —
 * 수평 확장에서 막으려는 증상 자체다).
 *
 * <p>저장값 확인은 {@code find} 가 없어(#317 범위) {@code StringRedisTemplate} 으로 원시 문자열을
 * 직접 읽고 {@link RiderLocationRepository#encode} 결과와 비교한다.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
@DisplayName("RiderLocationRepository 조건부 갱신")
class RiderLocationRepositoryIntegrationTest extends IntegrationTestSupport {

    private static final Long RIDER_ID = 7L;

    /**
     * {@code RiderLocationRepository} 의 키 형식과 같아야 한다. 손상된 값을 심고 TTL 을 직접 읽는
     * 케이스가 있어 어쩔 수 없이 중복하는데, 형식이 갈리면 그 케이스가 <b>조용히 아무것도 검증하지
     * 않게</b> 되므로 아래 {@code usesExpectedKey} 가 둘이 같은지 확인한다.
     */
    private static final String KEY_FORMAT = "rider:location:%d";

    /** 최신 위치 TTL(초). 저장소의 상수와 같은 값이다. */
    private static final long EXPECTED_TTL_SECONDS = 600;

    @Autowired
    private RiderLocationRepository repository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static LocationPayload at(Instant measuredAt) {
        return new LocationPayload(
                new BigDecimal("37.4979"), new BigDecimal("127.0276"), measuredAt, null);
    }

    private static Instant minute(int minute) {
        return Instant.parse("2026-08-03T12:%02d:00Z".formatted(minute));
    }

    private static String key(Long riderId) {
        return KEY_FORMAT.formatted(riderId);
    }

    private String stored(Long riderId) {
        return redisTemplate.opsForValue().get(key(riderId));
    }

    @Nested
    @DisplayName("단일 요청")
    class SingleWriter {

        @Test
        @DisplayName("저장된 값이 없으면 저장한다")
        void savesWhenAbsent() {
            LocationPayload location = at(minute(0));

            assertThat(repository.saveIfNewer(RIDER_ID, location)).isTrue();
            assertThat(stored(RIDER_ID)).isEqualTo(RiderLocationRepository.encode(location));
        }

        @Test
        @DisplayName("더 최신이면 덮어쓴다")
        void overwritesWithNewerLocation() {
            repository.saveIfNewer(RIDER_ID, at(minute(0)));
            LocationPayload latest = at(minute(1));

            assertThat(repository.saveIfNewer(RIDER_ID, latest)).isTrue();
            assertThat(stored(RIDER_ID)).isEqualTo(RiderLocationRepository.encode(latest));
        }

        @Test
        @DisplayName("과거 측정 시각이면 저장하지 않고 기존 값을 남긴다")
        void keepsLatestWhenOlder() {
            LocationPayload latest = at(minute(1));
            repository.saveIfNewer(RIDER_ID, latest);

            assertThat(repository.saveIfNewer(RIDER_ID, at(minute(0)))).isFalse();
            assertThat(stored(RIDER_ID)).isEqualTo(RiderLocationRepository.encode(latest));
        }

        @Test
        @DisplayName("같은 측정 시각이면 저장하지 않는다")
        void rejectsSameTimestamp() {
            // Lua 의 비교가 >= 인지 > 인지가 갈리는 경계값이다.
            repository.saveIfNewer(RIDER_ID, at(minute(0)));

            assertThat(repository.saveIfNewer(RIDER_ID, at(minute(0)))).isFalse();
        }

        @Test
        @DisplayName("측정 시각이 밀리초만 앞서도 최신으로 본다")
        void comparesAtMillisecondResolution() {
            // 고정 폭 인코딩의 마지막 자리까지 비교에 쓰이는지 확인한다. 폭이나 자릿수가 틀어지면
            // BUSY 5초 주기보다 훨씬 잦은 갱신에서 조용히 판정이 어긋난다.
            repository.saveIfNewer(RIDER_ID, at(minute(0)));
            LocationPayload oneMilliLater = at(minute(0).plusMillis(1));

            assertThat(repository.saveIfNewer(RIDER_ID, oneMilliLater)).isTrue();
            assertThat(stored(RIDER_ID)).isEqualTo(RiderLocationRepository.encode(oneMilliLater));
        }

        @Test
        @DisplayName("손상된 값은 새 값으로 덮는다")
        void overwritesCorruptedValue() {
            // 사전순 비교만 하면 'g' > '2' 라 이 값이 어떤 시각보다도 크게 나와 영원히 덮이지
            // 않는다. Lua 가 저장된 앞부분을 패턴으로 검증하는 이유가 이 케이스다. #297 이전 형식
            // (좌표가 앞)으로 남은 값도 같은 경로로 덮인다.
            redisTemplate.opsForValue().set(key(RIDER_ID), "garbage-not-a-location");
            LocationPayload location = at(minute(0));

            assertThat(repository.saveIfNewer(RIDER_ID, location)).isTrue();
            assertThat(stored(RIDER_ID)).isEqualTo(RiderLocationRepository.encode(location));
        }

        @Test
        @DisplayName("저장과 함께 TTL 이 걸린다")
        void setsTtlAtomically() {
            // SET ... EX 를 EXPIRE 와 나눠 쓰면 그 사이에 프로세스가 죽었을 때 TTL 없는 키가
            // 남고, 그 라이더가 다시 위치를 보내지 않으면 옛 좌표가 영구히 남는다.
            repository.saveIfNewer(RIDER_ID, at(minute(0)));

            assertThat(redisTemplate.getExpire(key(RIDER_ID), TimeUnit.SECONDS))
                    .isPositive()
                    .isLessThanOrEqualTo(EXPECTED_TTL_SECONDS);
        }

        @Test
        @DisplayName("조건에 걸려 쓰지 않으면 TTL 도 연장하지 않는다")
        void leavesTtlUntouchedWhenRejected() {
            // 거절된 쓰기가 TTL 을 연장하면, 오래된 시각을 계속 재전송하는 클라이언트가 죽은
            // 위치를 영구히 살려 둘 수 있다. Lua 가 SET 을 아예 건너뛰는지 확인한다.
            repository.saveIfNewer(RIDER_ID, at(minute(1)));
            redisTemplate.expire(key(RIDER_ID), 30, TimeUnit.SECONDS);

            repository.saveIfNewer(RIDER_ID, at(minute(0)));

            // isPositive 를 함께 두는 이유: 키가 지워지면 getExpire 가 -2 라 상한만으로는 통과한다.
            assertThat(redisTemplate.getExpire(key(RIDER_ID), TimeUnit.SECONDS))
                    .isPositive()
                    .isLessThanOrEqualTo(30);
        }

        @Test
        @DisplayName("저장소가 쓰는 키를 이 테스트가 실제로 들여다본다")
        void usesExpectedKey() {
            // 위 케이스들이 저장소와 다른 키를 보고 있으면 아무것도 검증하지 못한다.
            repository.saveIfNewer(RIDER_ID, at(minute(0)));

            assertThat(redisTemplate.hasKey(key(RIDER_ID))).isTrue();
        }
    }

    @Nested
    @DisplayName("동시 요청")
    class ConcurrentWriters {

        private static final int THREADS = 16;

        /**
         * 스레드를 동시에 풀어 각자 {@code saveIfNewer} 를 한 번 호출하고, 성공 횟수를 센다.
         *
         * @param measuredAt 스레드 번호로 측정 시각을 만드는 함수
         */
        private int raceAndCountWinners(Long riderId, IntFunction<Instant> measuredAt)
                throws InterruptedException {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(THREADS);
            AtomicInteger written = new AtomicInteger();

            try (var pool = Executors.newFixedThreadPool(THREADS)) {
                for (int i = 0; i < THREADS; i++) {
                    int index = i;
                    pool.submit(() -> {
                        try {
                            start.await();
                            if (repository.saveIfNewer(riderId, at(measuredAt.apply(index)))) {
                                written.incrementAndGet();
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
            return written.get();
        }

        @Test
        @DisplayName("같은 측정 시각으로 동시에 쓰면 정확히 하나만 성공한다")
        void exactlyOneWriterWinsOnIdenticalTimestamp() throws InterruptedException {
            // 비원자적 구현에서는 여럿이 "저장된 값이 없다"를 함께 보고 전부 쓴다. Redis 서버가
            // 스크립트를 직렬화 실행하므로 최초 한 번만 조건을 통과해야 한다.
            Instant sameInstant = minute(0);

            int winners = raceAndCountWinners(RIDER_ID, index -> sameInstant);

            assertThat(winners).isEqualTo(1);
            assertThat(stored(RIDER_ID)).isEqualTo(RiderLocationRepository.encode(at(sameInstant)));
        }

        @Test
        @DisplayName("서로 다른 시각으로 동시에 쓰면 최종 값은 항상 가장 최신이다")
        void keepsNewestRegardlessOfArrivalOrder() throws InterruptedException {
            // 수평 확장에서 막으려는 증상 자체다 — 비원자적 구현에서는 마지막에 도착한 쓰기가
            // 이겨서 오래된 좌표가 최신을 덮는다. 라운드를 여러 번 도는 이유: 한 번은 우연히 가장
            // 최신이 마지막에 도착해 통과할 수 있다(16스레드면 1/16). 라운드마다 다른 라이더 id 를
            // 쓴다 — 같은 키를 재사용하면 앞 라운드의 값이 다음 라운드의 기준선이 된다.
            int rounds = 5;
            String newest = RiderLocationRepository.encode(at(minute(THREADS - 1)));

            for (int round = 0; round < rounds; round++) {
                Long riderId = 100L + round;

                int winners = raceAndCountWinners(riderId, RiderLocationRepositoryIntegrationTest::minute);

                assertThat(stored(riderId)).as("라운드 %d 의 최종 값", round).isEqualTo(newest);
                // 성공 횟수는 도착 순서에 따라 1~16 사이로 달라진다. 단조 증가해야 하므로
                // 최소 한 번(가장 최신)은 성공한다.
                assertThat(winners).as("라운드 %d 의 성공 횟수", round).isBetween(1, THREADS);
            }
        }
    }
}
