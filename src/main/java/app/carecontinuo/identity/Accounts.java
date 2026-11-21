package app.carecontinuo.identity;

import app.carecontinuo.common.Problems;
import java.security.Principal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class Accounts {
    private final JdbcClient jdbc;

    Accounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Actor actor(Principal principal) {
        var found = jdbc.sql("select id from app_user where username = ? and enabled")
                .param(principal.getName()).query(Long.class).optional().orElseThrow(Problems::missing);
        return actorById(found);
    }

    public Actor actorById(long id) {
        var user = jdbc.sql("select id, username, display_name, patient_id from app_user where id = ? and enabled")
                .param(id).query((row, index) -> new Actor(row.getLong("id"),
                        row.getString("username"), row.getString("display_name"),
                        row.getObject("patient_id", Long.class), List.of())).optional().orElseThrow(Problems::missing);
        var roles = jdbc.sql("select role from user_role where user_id = ? order by role")
                .param(user.id()).query(String.class).list();
        return new Actor(user.id(), user.username(), user.displayName(), user.patientId(), roles);
    }

    public record Actor(long id, String username, String displayName, Long patientId, List<String> roles) {
        public boolean has(String role) {
            return roles.contains(role);
        }
    }
}
