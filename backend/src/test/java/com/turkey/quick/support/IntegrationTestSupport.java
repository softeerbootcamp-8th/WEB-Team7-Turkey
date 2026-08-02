package com.turkey.quick.support;

import com.turkey.quick.location.sse.SseRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * DB·Redis 를 쓰는 테스트(통합·E2E)의 공통 부모. 매 테스트 전에 셋 다 비운다.
 *
 * <p>{@code @SpringBootTest} 와 {@code @ActiveProfiles("integration")} 은 각 하위 클래스가
 * 직접 붙인다 — E2E 는 {@code webEnvironment = RANDOM_PORT} 가 필요하고 통합 테스트는 아니라서,
 * 여기서 하나로 고정하면 오히려 맞지 않는다.
 *
 * <p>Redis 까지 여기서 비우는 이유(2026-07-29): 통합·E2E 가 인메모리 대체 대신 로컬 Docker Redis 에
 * 붙게 되면서 값이 테스트 사이에 살아남는다. 하위 클래스마다 따로 정리하게 두면 빠뜨리는 곳이
 * 생기고, 그 오염은 조용히 다른 테스트를 통과시키거나 실패시킨다.
 *
 * <p>{@code SseRegistry}까지 세 번째로 비우는 이유(위치 추적 단순화 #291 후속): 인스턴스 로컬
 * 싱글턴이라 {@code DatabaseCleaner}로 정리되지 않는데, {@code DatabaseCleaner}의 TRUNCATE가
 * auto-increment id를 리셋해 다음 테스트가 같은 배송 id를 받는다 — 이전 테스트의 아직 안 끝난
 * 연결(타임아웃 대기 중)이 다음 테스트의 레지스트리 조회에 섞여 나온 것이 실제로 발생했다.
 */
public abstract class IntegrationTestSupport {

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private RedisCleaner redisCleaner;

    @Autowired
    private SseRegistry sseRegistry;

    @BeforeEach
    void clearLeftoverDataFromPreviousTest() {
        databaseCleaner.clear();
        sseRegistry.clear();
        redisCleaner.clear();
    }
}
