package com.uniblox.checkout.config;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

public final class SqlTraceInspector implements StatementInspector {
    private static final ThreadLocal<List<SqlStatement>> STATEMENTS = ThreadLocal.withInitial(ArrayList::new);

    @Override
    public String inspect(String sql) {
        STATEMENTS.get().add(new SqlStatement(
                Thread.currentThread().getName(),
                TransactionSynchronizationManager.getCurrentTransactionName(),
                sql.toLowerCase(Locale.ROOT)));
        return sql;
    }

    public static void clear() {
        STATEMENTS.get().clear();
    }

    public static List<String> statements() {
        return events().stream().map(SqlStatement::sql).toList();
    }

    public static List<SqlStatement> events() {
        return List.copyOf(STATEMENTS.get());
    }

    public record SqlStatement(String threadName, String transactionName, String sql) {
    }
}
