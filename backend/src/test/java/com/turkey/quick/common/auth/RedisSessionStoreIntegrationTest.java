package com.turkey.quick.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.support.IntegrationTestSupport;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 세션 슬라이딩 갱신(#439)을 실제 Redis 로 검증한다. 인메모리 대체는 TTL 을 흉내내지 않아
 * (호출 횟수만 센다) 여기서 확인할 성질을 검증할 수 없다.
 *
 * <p>핵심은 두 가지다. <b>남은 TTL 이 실제로 늘어난다</b>, 그리고 <b>없는 세션은 되살아나지
 * 않는다</b> — 후자는 HSET 을 먼저 하면 memberId 없는 반쪽 세션 키가 새로 생기는 실수를 고정한다.
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
    void 남은_TTL이_짧아진_세션을_다시_전체_TTL로_늘린다() {
        sessionStore.create(SESSION_ID, 42L, "RIDER", Duration.ofMinutes(1));

        sessionStore.extend(SESSION_ID, Duration.ofHours(2));

        assertThat(redisTemplate.getExpire(KEY)).isGreaterThan(Duration.ofMinutes(90).toSeconds());
        assertThat(sessionStore.findMemberId(SESSION_ID)).contains(42L);
        // 저장된 expiresAt 도 함께 갱신돼 실제 TTL 과 어긋나지 않는다(생성 시 값은 +1분이었다).
        LocalDateTime expiresAt =
                LocalDateTime.parse((String) redisTemplate.opsForHash().get(KEY, "expiresAt"));
        assertThat(expiresAt).isAfter(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(90));
    }

    @Test
    void 이미_만료돼_없는_세션은_되살리지_않는다() {
        sessionStore.extend(SESSION_ID, Duration.ofHours(2));

        assertThat(redisTemplate.hasKey(KEY)).isFalse();
        assertThat(sessionStore.findMemberId(SESSION_ID)).isEmpty();
    }
}
