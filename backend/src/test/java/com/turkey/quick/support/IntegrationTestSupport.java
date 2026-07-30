package com.turkey.quick.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * DB·Redis 를 쓰는 테스트(통합·E2E)의 공통 부모. 매 테스트 전에 양쪽을 비운다.
 *
 * <p>{@code @SpringBootTest} 와 {@code @ActiveProfiles("integration")} 은 각 하위 클래스가
 * 직접 붙인다 — E2E 는 {@code webEnvironment = RANDOM_PORT} 가 필요하고 통합 테스트는 아니라서,
 * 여기서 하나로 고정하면 오히려 맞지 않는다.
 *
 * <p>Redis 까지 여기서 비우는 이유(2026-07-29): 통합·E2E 가 인메모리 대체 대신 로컬 Docker Redis 에
 * 붙게 되면서 값이 테스트 사이에 살아남는다. 하위 클래스마다 따로 정리하게 두면 빠뜨리는 곳이
 * 생기고, 그 오염은 조용히 다른 테스트를 통과시키거나 실패시킨다.
 *
 * <p>SSE emitter 레지스트리까지 세 번째로 정리하는 이유(#77 후속): 위 둘은 외부 저장소라 여기서
 * 비울 수 있지만, {@code TrackingEmitterRegistry} 는 JVM 인메모리 싱글턴이라 같은 방식으로 비울
 * 수 없다 — 그래서 지금까지 빠져 있었고, 실제로 좀비 연결이 남아 있는 채로 도는 heartbeat 가 다음
 * 테스트의 Redis 연결 집계를 다시 채워 {@code CustomerTrackingStreamE2ETest} 의 연결 상한 테스트를
 * 간헐적으로 실패시켰다. {@code trackingEmitterCleaner} 를 반드시 {@code redisCleaner} 보다 먼저
 * 불러야 한다 — 좀비 연결을 끊으면서 나온 Redis 해제 호출이 뒤따르는 flush 로 덮여도 상관없게
 * 순서를 잡은 것이다(반대 순서면 flush 직후 그 해제 호출이 남기는 것은 없지만, 굳이 순서를 바꿀
 * 이유도 없다).
 */
public abstract class IntegrationTestSupport {

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private RedisCleaner redisCleaner;

    @Autowired
    private TrackingEmitterCleaner trackingEmitterCleaner;

    @BeforeEach
    void clearLeftoverDataFromPreviousTest() {
        databaseCleaner.clear();
        trackingEmitterCleaner.clear();
        redisCleaner.clear();
    }
}
