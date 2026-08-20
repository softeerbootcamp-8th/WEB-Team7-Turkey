package com.turkey.quick.common.retry;

import java.lang.reflect.Method;
import java.sql.SQLException;
import org.springframework.resilience.retry.MethodRetryPredicate;

/** MySQL이 즉시 희생자를 롤백하는 데드락(1213/40001)만 재시도한다. */
public final class MySqlDeadlockRetryPredicate implements MethodRetryPredicate {

    private static final int DEADLOCK_ERROR_CODE = 1213;
    private static final String DEADLOCK_SQL_STATE = "40001";

    @Override
    public boolean shouldRetry(Method method, Throwable throwable) {
        Throwable cause = throwable;

        while (cause != null) {
            if (cause instanceof SQLException sqlException
                    && sqlException.getErrorCode() == DEADLOCK_ERROR_CODE
                    && DEADLOCK_SQL_STATE.equals(sqlException.getSQLState())) {
                return true;
            }

            Throwable next = cause.getCause();
            if (next == cause) {
                break;
            }
            cause = next;
        }

        return false;
    }
}
