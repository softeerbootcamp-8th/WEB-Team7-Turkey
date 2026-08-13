package com.turkey.quick.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.support.IntegrationTestSupport;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 세션 슬라이딩 갱신(#439)을 실제 Redis 로 검증한다. 인메모리 대체는 TTL 을 흉내내지 않아
 * (호출 횟수만 센다) 여기서 확인할 성질을 검증할 수 없다.
 *
 * <p>핵심은 두 가지다. <b>남은 TTL 이 실제로 늘어난다</b>, 그리고 <b>없는 세션은 되살아나지
 * 않는다</b> — extend() 가 EXPIRE 한 번만 호출해 없는 키를 새로 만들 여지가 없다.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
@DisplayName("RedisSessionStore 슬라이딩 갱신")
class RedisSessionStoreIntegrationTest extends IntegrationTestSupport {

    private static final String SESSION_ID = "sliding-session-439";
    private static final String KEY = "session:" + SESSION_ID;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("남은 TTL이 짧아진 세션을 다시 전체 TTL로 늘린다")
    void shouldExtendShortenedTtlBackToFullTtl() {
        sessionStore.create(SESSION_ID, 42L, Duration.ofMinutes(1));

        sessionStore.extend(SESSION_ID, Duration.ofHours(2));

        assertThat(redisTemplate.getExpire(KEY)).isGreaterThan(Duration.ofMinutes(90).toSeconds());
        assertThat(sessionStore.findMemberId(SESSION_ID)).contains(42L);
    }

    @Test
    @DisplayName("이미 만료돼 없는 세션은 되살리지 않는다")
    void shouldNotResurrectExpiredSession() {
        sessionStore.extend(SESSION_ID, Duration.ofHours(2));

        assertThat(redisTemplate.hasKey(KEY)).isFalse();
        assertThat(sessionStore.findMemberId(SESSION_ID)).isEmpty();
    }

    @Test
    @DisplayName("세션 생성은 String 타입 키에 JSON 값과 TTL을 SET ... EX 한 번으로 함께 건다(#511)")
    void shouldCreateSessionAsSingleStringSetWithTtl() {
        sessionStore.create(SESSION_ID, 42L, Duration.ofHours(2));

        assertThat(redisTemplate.type(KEY)).isEqualTo(DataType.STRING);
        assertThat(redisTemplate.opsForValue().get(KEY)).contains("\"memberId\":42");
        assertThat(redisTemplate.getExpire(KEY)).isGreaterThan(0);
        assertThat(sessionStore.findMemberId(SESSION_ID)).contains(42L);
    }

    @Test
    @DisplayName("배포 직후 구버전 Hash 타입 세션 키는 WRONGTYPE 대신 조회 결과 없음으로 처리한다(#511)")
    void shouldTreatLegacyHashTypeSessionAsAbsent() {
        // #511 이전 RedisSessionStore가 HSET으로 남겨둔 세션 키를 흉내낸다.
        redisTemplate.opsForHash().put(KEY, "memberId", "42");
        redisTemplate.expire(KEY, Duration.ofHours(2));

        assertThat(redisTemplate.type(KEY)).isEqualTo(DataType.HASH);
        assertThat(sessionStore.findMemberId(SESSION_ID)).isEmpty();
    }
}
