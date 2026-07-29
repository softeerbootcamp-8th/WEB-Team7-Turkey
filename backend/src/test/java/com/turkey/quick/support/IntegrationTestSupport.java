package com.turkey.quick.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * DB 를 쓰는 테스트(통합·E2E)의 공통 부모. 매 테스트 전에 테이블을 비운다.
 *
 * <p>{@code @SpringBootTest} 와 {@code @ActiveProfiles("integration")} 은 각 하위 클래스가
 * 직접 붙인다 — E2E 는 {@code webEnvironment = RANDOM_PORT} 가 필요하고 통합 테스트는 아니라서,
 * 여기서 하나로 고정하면 오히려 맞지 않는다.
 */
public abstract class IntegrationTestSupport {

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @BeforeEach
    void clearLeftoverDataFromPreviousTest() {
        databaseCleaner.clear();
    }
}
