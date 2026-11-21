package app.carecontinuo.identity;

import app.carecontinuo.common.Problems;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

@Component
public class Accounts {
    private final JdbcClient jdbc;
    private final boolean demo;

    Accounts(JdbcClient jdbc, @Value("${care.demo.unrestricted-staff:false}") boolean demo, Environment environment) {
        this.jdbc = jdbc;
        this.demo = demo && environment.acceptsProfiles(Profiles.of("demo & !preprod"));
    }

    public Actor actor(Principal principal) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && attributes.getRequest().getAttribute(Accounts.class.getName()) instanceof Identity known) {
            return actorById(known.id());
        }
        return actorById(identity(principal).id());
    }

    Identity identity(Principal principal) {
        return findIdentity(principal).orElseThrow(Problems::missing);
    }

    Optional<Identity> findIdentity(Principal principal) {
        if (principal instanceof Authentication auth && auth.getPrincipal() instanceof OidcUser user) {
            return oidcIdentity(user.getIssuer().toString(), user.getSubject());
        }
        return jdbc.sql("select id, auth_version from app_user where username = ? and enabled")
                .param(principal.getName()).query(Identity.class).optional();
    }

    Optional<Identity> oidcIdentity(String issuer, String subject) {
        return jdbc.sql("""
                select id, auth_version from app_user
                where enabled and oidc_issuer = ? and oidc_subject = ? and patient_id is null
                """).params(issuer, subject).query(Identity.class).optional();
    }

    public Actor actorById(long id) {
        return jdbc.sql("""
                select u.id, u.username, u.display_name, u.patient_id,
                    array(select role from user_role where user_id = u.id order by role) as roles,
                    array(select department_id from staff_department where user_id = u.id order by department_id)
                        as departments
                from app_user u where u.id = ? and u.enabled
                """).param(id).query((row, index) -> new Actor(row.getLong("id"), row.getString("username"),
                        row.getString("display_name"), row.getObject("patient_id", Long.class),
                        List.of((String[]) row.getArray("roles").getArray()),
                        List.of((Long[]) row.getArray("departments").getArray()), demo))
                .optional().orElseThrow(Problems::missing);
    }

    public void requireStaffRole(long id, String role, long departmentId) {
        Actor staff = actorById(id);
        if (!staff.has(role) || !staff.inDepartment(departmentId)) {
            throw Problems.invalid("该账号不具备本科室所需权限。");
        }
    }

    record Identity(long id, long authVersion) { }

    public record Actor(long id, String username, String displayName, Long patientId, List<String> roles,
                        List<Long> departmentIds, boolean demo) {
        public boolean inDepartment(long id) {
            return demo || departmentIds.contains(id);
        }

        public boolean has(String role) {
            return roles.contains(role);
        }
    }
}
