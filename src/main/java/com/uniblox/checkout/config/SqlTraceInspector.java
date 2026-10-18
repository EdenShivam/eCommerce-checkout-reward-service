package com.uniblox.checkout.config;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import java.util.*;

public final class SqlTraceInspector implements StatementInspector {
    private static final ThreadLocal<List<String>> STATEMENTS = ThreadLocal.withInitial(ArrayList::new);
    @Override public String inspect(String sql) { STATEMENTS.get().add(sql.toLowerCase(Locale.ROOT)); return sql; }
    public static void clear() { STATEMENTS.get().clear(); }
    public static List<String> statements() { return List.copyOf(STATEMENTS.get()); }
}
