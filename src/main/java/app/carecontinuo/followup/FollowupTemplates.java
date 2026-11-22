package app.carecontinuo.followup;

import app.carecontinuo.audit.AuditLog;
import app.carecontinuo.common.Problems;
import app.carecontinuo.identity.Accounts;
import app.carecontinuo.identity.Accounts.Actor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class FollowupTemplates {
    private final JdbcClient jdbc;
    private final Accounts accounts;
    private final AuditLog audit;

    FollowupTemplates(JdbcClient jdbc, Accounts accounts, AuditLog audit) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.audit = audit;
    }

    List<Template> list(Actor actor, long departmentId) {
        requireScope(actor, departmentId);
        return jdbc.sql("""
                select id, kind, name, content from followup_template
                where department_id = ? order by kind, name, id
                """).param(departmentId).query(Template.class).list();
    }

    @Transactional
    Template create(Actor actor, Input input) {
        requireScope(accounts.actorById(actor.id()), input.departmentId());
        if (!jdbc.sql("select exists(select 1 from department where id = ? and enabled)")
                .param(input.departmentId()).query(Boolean.class).single()) throw Problems.missing();
        var created = jdbc.sql("""
                insert into followup_template(department_id, kind, name, content, created_by) values (?, ?, ?, ?, ?)
                on conflict(department_id, kind, name) do nothing returning id, kind, name, content
                """).params(input.departmentId(), input.kind().name(), input.name().strip(), input.content().strip(), actor.id())
                .query(Template.class).optional().orElseThrow(() -> Problems.conflict("该科室已有同名模板。"));
        audit.record(actor.id(), "FOLLOWUP_TEMPLATE", created.id(), "CREATE");
        return created;
    }

    private static void requireScope(Actor actor, long departmentId) {
        if (!actor.has("FOLLOWUP") || !actor.inDepartment(departmentId)) throw Problems.missing();
    }

    enum Kind { REVISIT, PENDING_RESULT, REPLY }
    record Input(@Positive long departmentId, @NotNull Kind kind, @NotBlank @Size(max = 140) String name,
                 @NotBlank @Size(max = 2000) String content) { }
    record Template(long id, String kind, String name, String content) { }
}
