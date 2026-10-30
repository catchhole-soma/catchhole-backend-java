package org.monitoring.catchholebackend.support;

import java.sql.SQLException;
import java.time.Duration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 격리 PostgreSQL 테스트의 fixture 정리. {@code TRUNCATE works, members CASCADE}는 연결된 표를 하나씩 AccessExclusive로 잠그므로,
 * 같은 DB에서 여러 표를 함께 읽는 다른 세션과 서로의 표 잠금을 기다리면 PostgreSQL 교착(40P01)이 난다. 실제 로그의 상대 세션은
 * 모든 test Context에서 5초마다 도는 {@code ChatDispatcher.recover}의 chat_runs + ai_token_usages 조회였고, 캐시에 남은 다른 테스트 클래스의
 * Context도 같은 일회용 DB를 계속 읽는다. 정리 직전 다른 활성 세션을 잠시 기다리고, 교착이면 짧게 쉬고 다시 시도한다.
 */
public final class PostgresFixtureCleanup {
    private static final String DEADLOCK = "40P01";
    private PostgresFixtureCleanup() {}

    /** 다른 활성 세션이 끝나기를 최대 2초 기다린 뒤 TRUNCATE하고, PostgreSQL 교착이면 200ms 쉬고 최대 10번까지 시도한다. */
    public static void truncate(JdbcTemplate jdbc, String sql) {
        awaitOtherBackends(jdbc, Duration.ofSeconds(2));
        for (int attempt = 1; ; attempt++) {
            try {
                jdbc.execute(sql);
                return;
            } catch (DataAccessException ex) {
                if (!deadlock(ex) || attempt >= 10) throw ex;
                pause(200);
            }
        }
    }

    static void awaitOtherBackends(JdbcTemplate jdbc, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && jdbc.queryForObject("""
                select count(*) from pg_stat_activity
                where datname = current_database() and pid <> pg_backend_pid() and backend_type = 'client backend' and state <> 'idle'
                """, Long.class) > 0) pause(50);
    }

    static boolean deadlock(Throwable error) {
        for (var cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql && DEADLOCK.equals(sql.getSQLState())) return true;
        return false;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
