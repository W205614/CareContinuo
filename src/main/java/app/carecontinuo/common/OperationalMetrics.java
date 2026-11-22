package app.carecontinuo.common;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
@Profile("preprod")
class OperationalMetrics {
    OperationalMetrics(MeterRegistry registry, JdbcClient jdbc) {
        Gauge.builder("care.outbox.oldest.ready.seconds", jdbc, connection -> read(connection, """
                select coalesce(extract(epoch from clock_timestamp() - min(available_at)), 0)
                from outbox_event where state = 'PENDING' and available_at <= clock_timestamp()
                """)).register(registry);
        Gauge.builder("care.outbox.failed", jdbc, connection -> read(connection, """
                select count(*) from outbox_event where state = 'FAILED'
                """)).register(registry);
        Gauge.builder("care.followup.response.overdue", jdbc, connection -> read(connection, """
                select count(*) from work_item where kind = 'FOLLOWUP_REQUEST' and status = 'OPEN'
                    and due_at <= clock_timestamp()
                """)).register(registry);
        Gauge.builder("care.agent.expired.running", jdbc, connection -> read(connection, """
                select count(*) from agent_job where status = 'RUNNING' and expires_at <= clock_timestamp()
                """)).register(registry);
        Gauge.builder("care.database.lock.waiters", jdbc, connection -> read(connection, """
                select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'
                """)).register(registry);
    }

    private static double read(JdbcClient jdbc, String sql) {
        try {
            return jdbc.sql(sql).query(Double.class).single();
        } catch (DataAccessException unavailable) {
            return Double.NaN;
        }
    }
}
