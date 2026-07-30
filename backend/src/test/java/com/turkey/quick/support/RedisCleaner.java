package com.turkey.quick.support;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 통합·E2E 테스트는 로컬 Docker Redis 에 붙는다(2026-07-29 결정). 인메모리 대체를 쓰던 때와 달리
 * 값이 프로세스보다 오래 살아남으므로, 앞 테스트가 남긴 세션·인증번호·위치가 다음 테스트로 새어
 * 들어간다.
 *
 * <p>이게 이론적인 걱정이 아니다. #82 작업에서 실제로 물렸다 — {@code DatabaseCleaner} 의 TRUNCATE 가
 * AUTO_INCREMENT 를 1로 리셋해 모든 테스트의 첫 회원이 같은 {@code member_id} 를 받는데, 그 id 로
 * 저장된 위치가 남아 있어 첫 요청부터 중복 판정이 났다. Redis 를 비우지 않으면 같은 종류의 오염이
 * 세션·인증번호에서도 일어난다.
 *
 * <p><b>FLUSHDB 는 현재 연결된 로직 DB 만 비운다.</b> 테스트는 {@code database: 1} 을 쓰고 개발용
 * 애플리케이션은 기본 0 을 쓰므로 개발 세션은 지워지지 않는다. 그 전제가 깨지면 개발 데이터를 날리게
 * 되므로, 비우기 전에 실제 연결된 DB 인덱스를 확인한다 — 설정이 잘못됐을 때 조용히 지우는 것보다
 * 테스트가 실패하는 편이 낫다.
 */
@Component
@Profile("integration")
public class RedisCleaner {

    /** 개발용 애플리케이션이 쓰는 기본 로직 DB. 테스트가 여기에 붙어 있으면 비우지 않는다. */
    private static final int DEVELOPMENT_DATABASE_INDEX = 0;

    /**
     * {@code docker-compose.yml} 이 고정한 이미지의 메이저 버전.
     *
     * <p>이 검사가 있는 이유: 개발 PC 에 Redis 가 직접 설치돼 있으면(Homebrew 등) 그쪽이
     * {@code 127.0.0.1:6379} 를 잡고 컨테이너는 {@code *:6379} 에 바인딩하므로, 더 구체적인 호스트
     * 쪽이 이긴다. 그러면 <b>테스트가 컨테이너가 아니라 호스트 Redis 로 조용히 돌아간다</b> —
     * 2026-07-29 에 실제로 그 상태였다(호스트 8.8.0 vs 컨테이너 7.4). 호스트 Redis 를 제거해
     * 해소했지만 누군가 다시 설치하면 같은 일이 반복되고, <b>설정을 다시 읽는 것으로는 "실제로
     * 어디에 붙었는가"를 알 수 없다.</b> 그래서 연결된 인스턴스의 실체를 확인한다. H2 로 통과하고
     * MySQL 에서 깨졌던 것과 같은 종류의 문제다.
     *
     * <p>이미지를 올릴 때 이 값도 함께 올린다. 실패 메시지가 무엇을 고쳐야 하는지 알려 준다.
     */
    private static final String EXPECTED_REDIS_MAJOR_VERSION = "7.";

    /** 인스턴스 확인은 JVM 당 한 번이면 충분하다. 테스트마다 INFO 를 쏘지 않는다. */
    private static volatile boolean instanceVerified;

    private final StringRedisTemplate redisTemplate;
    private final RedisConnectionFactory connectionFactory;

    public RedisCleaner(StringRedisTemplate redisTemplate, RedisConnectionFactory connectionFactory) {
        this.redisTemplate = redisTemplate;
        this.connectionFactory = connectionFactory;
    }

    public void clear() {
        verifyNotDevelopmentDatabase();
        verifyConnectedToContainerOnce();
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private void verifyConnectedToContainerOnce() {
        if (instanceVerified) {
            return;
        }
        String version = redisTemplate.execute((RedisCallback<String>) connection ->
                connection.serverCommands().info("server").getProperty("redis_version"));
        if (version == null || !version.startsWith(EXPECTED_REDIS_MAJOR_VERSION)) {
            throw new IllegalStateException(
                    "예상과 다른 Redis 인스턴스에 연결됐습니다. redis_version=" + version
                            + ", 기대=" + EXPECTED_REDIS_MAJOR_VERSION + "x. "
                            + "docker-compose 의 Redis 컨테이너가 떠 있는지, 그리고 개발 PC 에 직접 "
                            + "설치된 Redis 가 127.0.0.1:6379 를 먼저 잡고 있지 않은지 확인하세요"
                            + "(lsof -nP -iTCP:6379 -sTCP:LISTEN). 이미지를 올렸다면 RedisCleaner 의 "
                            + "EXPECTED_REDIS_MAJOR_VERSION 도 함께 올리세요.");
        }
        instanceVerified = true;
    }

    private void verifyNotDevelopmentDatabase() {
        if (!(connectionFactory instanceof LettuceConnectionFactory lettuce)) {
            return;
        }
        if (lettuce.getDatabase() == DEVELOPMENT_DATABASE_INDEX) {
            throw new IllegalStateException(
                    "테스트가 개발용 Redis DB(index 0)에 연결돼 있어 FLUSHDB 를 거부했습니다. "
                            + "src/test/resources/application-integration.yml 의 spring.data.redis.database 를 확인하세요.");
        }
    }
}
