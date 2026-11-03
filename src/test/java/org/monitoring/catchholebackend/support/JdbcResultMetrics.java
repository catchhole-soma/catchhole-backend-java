package org.monitoring.catchholebackend.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;

/** Test-only SQL/column/consumed-row counts. Never reads a row value or records bound parameters. */
public final class JdbcResultMetrics implements BeanPostProcessor {
    private static final List<Query> QUERIES = new CopyOnWriteArrayList<>();
    private static volatile boolean enabled;

    public static void start() { QUERIES.clear(); enabled = true; }

    public static List<QueryResult> stop() {
        enabled = false;
        return QUERIES.stream().map(q -> new QueryResult(q.sql, List.copyOf(q.columns), q.rows,
                q.executeNanos / 1_000_000.0, q.failure)).toList();
    }

    @Override public Object postProcessAfterInitialization(Object bean, String name) {
        if (!(bean instanceof DataSource source)) return bean;
        return proxy(DataSource.class, (method, args) -> {
            Object result = invoke(source, method, args);
            return result instanceof Connection connection ? connection(connection) : result;
        });
    }

    private static Connection connection(Connection connection) {
        return proxy(Connection.class, (method, args) -> {
            Object result = invoke(connection, method, args);
            if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement statement) {
                return statement(statement, (String) args[0]);
            }
            return result;
        });
    }

    private static PreparedStatement statement(PreparedStatement statement, String sql) {
        return proxy(PreparedStatement.class, (method, args) -> {
            if (!enabled || !method.getName().equals("executeQuery")
                    || !sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("select")) {
                return invoke(statement, method, args);
            }
            Query query = new Query(sql);
            QUERIES.add(query);
            long start = System.nanoTime();
            try {
                ResultSet result = (ResultSet) invoke(statement, method, args);
                query.executeNanos = System.nanoTime() - start;
                var metadata = result.getMetaData();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    query.columns.add(metadata.getColumnName(index));
                }
                return proxy(ResultSet.class, (rsMethod, rsArgs) -> {
                    Object value = invoke(result, rsMethod, rsArgs);
                    if (rsMethod.getName().equals("next") && Boolean.TRUE.equals(value)) query.rows++;
                    return value;
                });
            } catch (Throwable failure) {
                query.executeNanos = System.nanoTime() - start;
                query.failure = failure.getClass().getSimpleName();
                throw failure;
            }
        });
    }

    private static Object invoke(Object receiver, Method method, Object[] args) throws Throwable {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> self == args[0];
                    case "hashCode" -> System.identityHashCode(self);
                    default -> "TestJdbcMetricsProxy<" + type.getSimpleName() + ">";
                };
            }
            return call.invoke(method, args);
        });
    }

    @FunctionalInterface private interface Invocation { Object invoke(Method method, Object[] args) throws Throwable; }

    private static final class Query {
        final String sql;
        final List<String> columns = new ArrayList<>();
        long rows;
        long executeNanos;
        String failure;
        Query(String sql) { this.sql = sql; }
    }

    public record QueryResult(String sql, List<String> selectedColumns, long consumedRows,
            double executeMillis, String failure) {
        public boolean candidateQuery() { return sql.toLowerCase(Locale.ROOT).contains(" from world_setting_candidates "); }
        public boolean selectsJobJson() {
            return selectedColumns.contains("automatic_input_state") || selectedColumns.contains("run_base_state")
                    || selectedColumns.contains("state_journal");
        }
    }
}
