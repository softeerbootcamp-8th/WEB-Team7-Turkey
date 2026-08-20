package com.turkey.quick.common.retry;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

@DisplayName("MySqlDeadlockRetryPredicate")
class MySqlDeadlockRetryPredicateTest {

    private final MySqlDeadlockRetryPredicate predicate = new MySqlDeadlockRetryPredicate();

    @Test
    @DisplayName("원인 체인에 MySQL 데드락 1213/40001이 있으면 재시도한다")
    void retriesMySqlDeadlock() throws NoSuchMethodException {
        SQLException deadlock = new SQLException("Deadlock found", "40001", 1213);
        CannotAcquireLockException translated = new CannotAcquireLockException("insert failed", deadlock);

        assertThat(predicate.shouldRetry(targetMethod(), translated)).isTrue();
    }

    @Test
    @DisplayName("MySQL 락 대기 타임아웃 1205/HY000은 재시도하지 않는다")
    void doesNotRetryLockWaitTimeout() throws NoSuchMethodException {
        SQLException timeout = new SQLException("Lock wait timeout exceeded", "HY000", 1205);
        CannotAcquireLockException translated = new CannotAcquireLockException("insert failed", timeout);

        assertThat(predicate.shouldRetry(targetMethod(), translated)).isFalse();
    }

    @Test
    @DisplayName("SQLState가 다르면 오류 코드가 1213이어도 재시도하지 않는다")
    void requiresDeadlockSqlState() throws NoSuchMethodException {
        SQLException notDeadlock = new SQLException("not a deadlock", "HY000", 1213);

        assertThat(predicate.shouldRetry(targetMethod(), notDeadlock)).isFalse();
    }

    private Method targetMethod() throws NoSuchMethodException {
        return MySqlDeadlockRetryPredicateTest.class.getDeclaredMethod("target");
    }

    @SuppressWarnings("unused")
    private void target() {
    }
}
