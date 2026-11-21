package app.carecontinuo.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class AuditLog {
    private final JdbcClient jdbc;

    AuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(long actorId, String type, long id, String action) {
        jdbc.sql("insert into audit_log(actor_id, object_type, object_id, action) values (?, ?, ?, ?)")
                .params(actorId, type, id, action).update();
    }
}
