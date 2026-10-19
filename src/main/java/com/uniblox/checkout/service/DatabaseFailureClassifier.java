package com.uniblox.checkout.service;

import org.hibernate.PessimisticLockException;
import org.hibernate.exception.LockAcquisitionException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;

import java.sql.SQLException;

public final class DatabaseFailureClassifier {
    private static final int H2_LOCK_TIMEOUT_ERROR = 50200;

    private DatabaseFailureClassifier() {
    }

    public static boolean isRetryableLockFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CannotAcquireLockException
                    || cause instanceof PessimisticLockingFailureException
                    || cause instanceof PessimisticLockException
                    || cause instanceof LockAcquisitionException) {
                return true;
            }
            if (cause instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                if (sqlException.getErrorCode() == H2_LOCK_TIMEOUT_ERROR
                        || "HYT00".equals(state)
                        || "HYT01".equals(state)
                        || "40001".equals(state)
                        || "40P01".equals(state)
                        || "55P03".equals(state)) {
                    return true;
                }
            }
        }
        return false;
    }
}