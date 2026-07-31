package com.turkey.quick.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 통합·E2E 테스트는 인메모리 DB 가 아니라 로컬 Docker MySQL 의 turkey 스키마를 공유한다.
 * 그래서 앞 테스트가 남긴 행이 다음 테스트로 새어 들어가고, 재실행하면 unique 제약(login_id,
 * phone_number)에 걸린다. 매 테스트 전에 테이블을 비워 그 문제를 없앤다.
 *
 * <p>E2E 는 HTTP 로 별도 스레드·커넥션에서 커밋되므로 {@code @Transactional} 롤백으로는 정리되지
 * 않는다. TRUNCATE 를 쓰는 이유이자, 이 클래스가 필요한 이유다.
 *
 * <p>테이블 목록은 information_schema 에서 매번 읽는다. 새 마이그레이션으로 테이블이 늘어도
 * 이 클래스를 고칠 필요가 없게 하기 위해서다(고치는 것을 잊으면 조용히 오염된다).
 */
@Component
@Profile("integration")
public class DatabaseCleaner {

    /** Flyway 의 마이그레이션 이력. 지우면 다음 기동에서 스키마 전체를 다시 적용하려 든다. */
    private static final String FLYWAY_HISTORY = "flyway_schema_history";

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public void clear() {
        // FK 를 켠 채로는 부모 테이블을 지울 수 없고, 삭제 순서를 손으로 관리하면 테이블이 늘 때마다 깨진다.
        em.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();
        try {
            tableNames().forEach(table -> em.createNativeQuery("TRUNCATE TABLE `" + table + "`").executeUpdate());
        } finally {
            // 같은 커넥션의 세션 설정이라, 되돌리지 않으면 커넥션 풀을 통해 다른 테스트로 샌다.
            em.createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> tableNames() {
        return em.createNativeQuery("""
                        SELECT table_name FROM information_schema.tables
                         WHERE table_schema = DATABASE()
                           AND table_type = 'BASE TABLE'
                           AND table_name <> :history
                        """)
                .setParameter("history", FLYWAY_HISTORY)
                .getResultList();
    }
}
