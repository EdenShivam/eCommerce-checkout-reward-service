package com.uniblox.checkout.config;

import com.uniblox.checkout.service.DatabaseFailureClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CyclicBarrier;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:database-lock-classification-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")
class DatabaseLockClassificationTest {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void h2LockTimeoutIsClassifiedFromObservedSqlException() throws Exception {
        CountDownLatch updateStarted = new CountDownLatch(1);
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try (ExecutorService executor = Executors.newFixedThreadPool(1)) {
            transaction.executeWithoutResult(status -> {
                jdbcTemplate.queryForObject("SELECT stock FROM products WHERE id = 2 FOR UPDATE", Integer.class);
                Future<?> blockedUpdate = executor.submit(() -> {
                    updateStarted.countDown();
                    try {
                        jdbcTemplate.update("UPDATE products SET stock = stock WHERE id = 2");
                    } catch (RuntimeException exception) {
                        observedFailure.set(exception);
                    }
                });
                try {
                    assertThat(updateStarted.await(2, TimeUnit.SECONDS)).isTrue();
                    blockedUpdate.get(12, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError("Expected the competing H2 update to time out", exception);
                }
            });
        }

        Throwable failure = observedFailure.get();
        assertThat(failure).isNotNull();
        SQLException sqlException = findSqlException(failure);
        assertThat((Object) sqlException).isNotNull();
        assertThat(sqlException.getSQLState()).isEqualTo("HYT00");
        assertThat(sqlException.getErrorCode()).isEqualTo(50200);
        assertThat(DatabaseFailureClassifier.isRetryableLockFailure(failure)).isTrue();
    }

    @Test
    void h2OppositeProductLockOrderCapturesDeadlockOrTimeoutClassification() throws Exception {
        CyclicBarrier bothFirstRowsLocked = new CyclicBarrier(2);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> runOppositeLockOrder(transaction, 2, 3, bothFirstRowsLocked, failures));
            Future<?> second = executor.submit(() -> runOppositeLockOrder(transaction, 3, 2, bothFirstRowsLocked, failures));
            first.get(12, TimeUnit.SECONDS);
            second.get(12, TimeUnit.SECONDS);
        }

        assertThat(failures).hasSize(1);
        Throwable failure = failures.getFirst();
        assertThat(DatabaseFailureClassifier.isRetryableLockFailure(failure)).isTrue();
        SQLException sqlException = findSqlException(failure);
        assertThat((Object) sqlException).isNotNull();
        assertThat(sqlException.getSQLState()).isIn("HYT00", "40001", "40P01");
    }

    private void runOppositeLockOrder(TransactionTemplate transaction,
                                      long firstProduct,
                                      long secondProduct,
                                      CyclicBarrier barrier,
                                      List<Throwable> failures) {
        try {
            transaction.executeWithoutResult(status -> {
                jdbcTemplate.update("UPDATE products SET stock = stock WHERE id = ?", firstProduct);
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError("Both deadlock participants must acquire their first row", exception);
                }
                jdbcTemplate.update("UPDATE products SET stock = stock WHERE id = ?", secondProduct);
            });
        } catch (Throwable failure) {
            failures.add(failure);
        }
    }

    private SQLException findSqlException(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException;
            }
        }
        return null;
    }
}