package app.carecontinuo.identity;

import app.carecontinuo.audit.AuditLog;
import app.carecontinuo.common.Problems;
import app.carecontinuo.identity.Accounts.Actor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class StaffAdministration {
    private final JdbcClient jdbc;
    private final Accounts accounts;
    private final AuditLog audit;

    StaffAdministration(JdbcClient jdbc, Accounts accounts, AuditLog audit) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.audit = audit;
    }

    List<Department> departments(Actor actor) {
        requireAdmin(actor);
        return jdbc.sql("select id, name, enabled from department order by name").query(Department.class).list();
    }

    @Transactional
    Department saveDepartment(Actor actor, Long id, DepartmentInput input) {
        lockAdministration();
        requireAdmin(accounts.actorById(actor.id()));
        try {
            var query = id == null
                    ? jdbc.sql("insert into department(name, enabled) values (?, ?) returning id, name, enabled")
                        .params(input.name().strip(), input.enabled())
                    : jdbc.sql("update department set name = ?, enabled = ? where id = ? returning id, name, enabled")
                        .params(input.name().strip(), input.enabled(), id);
            var department = query.query(Department.class).optional().orElseThrow(Problems::missing);
            audit.record(actor.id(), "DEPARTMENT", department.id(), id == null ? "CREATE" : "UPDATE");
            return department;
        } catch (DuplicateKeyException conflict) {
            throw Problems.conflict("科室名称已存在。");
        }
    }

    StaffPage people(Actor actor, Long afterId) {
        requireAdmin(actor);
        var rows = jdbc.sql("""
                select u.id, u.username, u.display_name, u.enabled, u.oidc_issuer, u.oidc_subject,
                    array(select r.role from user_role r where r.user_id = u.id order by r.role) as roles,
                    array(select sd.department_id from staff_department sd where sd.user_id = u.id order by sd.department_id)
                        as departments, d.department_id as doctor_department_id
                from app_user u left join doctor d on d.user_id = u.id
                where u.patient_id is null and u.id > ? order by u.id limit 51
                """).param(afterId == null ? 0L : afterId).query((row, index) -> new Staff(row.getLong("id"),
                        row.getString("username"), row.getString("display_name"), row.getBoolean("enabled"),
                        row.getString("oidc_issuer"), row.getString("oidc_subject"),
                        List.of((String[]) row.getArray("roles").getArray()),
                        List.of((Long[]) row.getArray("departments").getArray()),
                        row.getObject("doctor_department_id", Long.class))).list();
        return new StaffPage(rows.stream().limit(50).toList(), rows.size() > 50 ? rows.get(49).id() : null);
    }

    @Transactional
    long create(Actor actor, NewStaff input) {
        lockAdministration();
        requireAdmin(accounts.actorById(actor.id()));
        long id = insert(input.username().strip(), input.staff().displayName().strip(),
                input.issuer().strip(), input.subject());
        save(actor.id(), id, input.staff());
        return id;
    }

    @Transactional
    void update(Actor actor, long id, StaffInput input) {
        lockAdministration();
        requireAdmin(accounts.actorById(actor.id()));
        save(actor.id(), id, input);
        if (!hasEnabledAdmin()) throw Problems.conflict("必须保留至少一位已绑定且启用的管理员。");
    }

    @Transactional
    void revoke(Actor actor, long id) {
        lockAdministration();
        requireAdmin(accounts.actorById(actor.id()));
        if (jdbc.sql("update app_user set auth_version = auth_version + 1 where id = ? and patient_id is null")
                .param(id).update() == 0) throw Problems.missing();
        audit.record(actor.id(), "STAFF", id, "REVOKE_SESSION");
    }

    @Transactional
    void bootstrap(String username, String displayName, String issuer, String subject) {
        lockAdministration();
        if (hasEnabledAdmin()) throw Problems.conflict("已存在可用的管理员。");
        long id = insert(username, displayName, issuer, subject);
        jdbc.sql("insert into user_role(user_id, role) values (?, 'ADMIN')").param(id).update();
        audit.record(id, "STAFF", id, "BOOTSTRAP");
    }

    private long insert(String username, String displayName, String issuer, String subject) {
        try {
            return jdbc.sql("""
                    insert into app_user(username, display_name, oidc_issuer, oidc_subject)
                    values (?, ?, ?, ?) returning id
                    """).params(username, displayName, issuer, subject).query(Long.class).single();
        } catch (DuplicateKeyException conflict) {
            throw Problems.conflict("账号名称或 OIDC 身份已登记。");
        }
    }

    private void save(long actorId, long id, StaffInput input) {
        if (input.roles().stream().anyMatch(role -> role != Role.ADMIN) && input.departmentIds().isEmpty()) {
            throw Problems.invalid("业务角色需要至少一个科室。");
        }
        if (input.roles().contains(Role.DOCTOR) && (input.doctorDepartmentId() == null
                || !input.departmentIds().contains(input.doctorDepartmentId()))) {
            throw Problems.invalid("医生主科室必须在授权科室内。");
        }
        int changed = jdbc.sql("""
                update app_user set display_name = ?, enabled = ?, auth_version = auth_version + 1
                where id = ? and patient_id is null
                """).params(input.displayName().strip(), input.enabled(), id).update();
        if (changed == 0) throw Problems.missing();
        jdbc.sql("delete from user_role where user_id = ?").param(id).update();
        for (var role : input.roles()) {
            jdbc.sql("insert into user_role(user_id, role) values (?, ?)").params(id, role.name()).update();
        }
        jdbc.sql("delete from staff_department where user_id = ?").param(id).update();
        for (long departmentId : input.departmentIds()) {
            int inserted = jdbc.sql("""
                    insert into staff_department(user_id, department_id)
                    select ?, id from department where id = ?
                    """).params(id, departmentId).update();
            if (inserted == 0) throw Problems.invalid("科室不存在。");
        }
        if (input.roles().contains(Role.DOCTOR)) {
            jdbc.sql("""
                    insert into doctor(user_id, department_id, display_name) values (?, ?, ?)
                    on conflict (user_id) do update set department_id = excluded.department_id,
                        display_name = excluded.display_name
                    """).params(id, input.doctorDepartmentId(), input.displayName().strip()).update();
        }
        audit.record(actorId, "STAFF", id, input.enabled() ? "SAVE_PERMISSIONS" : "DISABLE");
    }

    private void lockAdministration() {
        jdbc.sql("select id from staff_admin_guard where id = 1 for update").query(Integer.class).single();
    }

    private boolean hasEnabledAdmin() {
        return jdbc.sql("""
                select exists(select 1 from app_user u join user_role r on r.user_id = u.id
                where u.enabled and u.oidc_subject is not null and r.role = 'ADMIN')
                """).query(Boolean.class).single();
    }

    static void requireAdmin(Actor actor) {
        if (!actor.has("ADMIN")) throw Problems.forbidden();
    }

    enum Role { ADMIN, OPERATIONS, DOCTOR, FOLLOWUP, REVIEWER }
    record Department(long id, String name, boolean enabled) { }
    record DepartmentInput(@NotBlank @Size(max = 100) String name, @NotNull Boolean enabled) { }
    record NewStaff(@NotBlank @Size(max = 80) @Pattern(regexp = "[a-zA-Z0-9._-]+") String username,
                    @NotBlank @Size(max = 512) String issuer, @NotBlank @Size(max = 255) String subject,
                    @NotNull @jakarta.validation.Valid StaffInput staff) { }
    record StaffInput(@NotBlank @Size(max = 100) String displayName, @NotNull Boolean enabled,
                      @NotNull @Size(min = 1, max = 5) Set<@NotNull Role> roles,
                      @NotNull @Size(max = 100) Set<@NotNull @Positive Long> departmentIds,
                      @Positive Long doctorDepartmentId) { }
    record Staff(long id, String username, String displayName, boolean enabled, String oidcIssuer, String oidcSubject,
                 List<String> roles, List<Long> departmentIds, Long doctorDepartmentId) { }
    record StaffPage(List<Staff> staff, Long nextCursor) { }
}
