package app.carecontinuo.followup;

import app.carecontinuo.audit.AuditLog;
import app.carecontinuo.care.Episodes;
import app.carecontinuo.common.Problems;
import app.carecontinuo.followup.Followups.Task;
import app.carecontinuo.identity.Accounts;
import app.carecontinuo.identity.Accounts.Actor;
import app.carecontinuo.notification.Notifications;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class DepartmentHandoffs {
    private static final String WORK = "FOLLOWUP_HANDOFF";
    private static final String CANDIDATES = """
            from department d join staff_department sd on sd.department_id = d.id
            join app_user u on u.id = sd.user_id join user_role r on r.user_id = u.id
            where d.enabled and u.enabled and r.role = 'FOLLOWUP'
            """;
    private static final String ACCESS = """
            exists(select 1 from staff_department sd where sd.user_id = :actor and
                (h.sender_id = :actor and sd.department_id = h.from_department_id
                 or h.recipient_id = :actor and sd.department_id = h.to_department_id))
            """;
    private static final String VIEW = """
            select h.id, h.sender_id, h.recipient_id, sender.display_name as sender, recipient.display_name as recipient,
                origin.name as from_department, target.name as to_department,
                case when t.owner_id = :actor and (h.sender_id = :actor or h.status = 'ACCEPTED')
                     then h.task_id end as task_id,
                h.status, h.created_at, h.due_at, h.resolved_at, h.return_reason
            from followup_handoff h join followup_task t on t.id = h.task_id
            join department origin on origin.id = h.from_department_id
            join department target on target.id = h.to_department_id join app_user sender on sender.id = h.sender_id
            join app_user recipient on recipient.id = h.recipient_id where
            """;
    private final JdbcClient jdbc;
    private final Followups followups;
    private final Episodes episodes;
    private final Accounts accounts;
    private final Notifications notices;
    private final AuditLog audit;
    private final Clock clock;
    private final TransactionTemplate transactions;

    DepartmentHandoffs(JdbcClient jdbc, Followups followups, Episodes episodes, Accounts accounts,
                        Notifications notices, AuditLog audit, Clock clock, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.followups = followups;
        this.episodes = episodes;
        this.accounts = accounts;
        this.notices = notices;
        this.audit = audit;
        this.clock = clock;
        this.transactions = transactions;
    }

    List<Assignee> assignees(Actor actor, long taskId) {
        var task = followups.load(taskId);
        requireSender(actor, task);
        Followups.requirePublished(task);
        return jdbc.sql("select d.id as department_id, d.name as department, u.id, u.display_name as name "
                + CANDIDATES + " and d.id <> ? and u.id not in (?, ?) order by d.name, u.display_name, u.id")
                .params(task.ownerDepartmentId(), task.ownerId(), task.reviewerId()).query(Assignee.class).list();
    }

    List<View> list(Actor actor) {
        return scope(jdbc.sql(VIEW + ACCESS + " order by (h.status = 'PENDING') desc, h.id desc limit 50"), actor)
                .query(View.class).list();
    }

    View get(Actor actor, long id) {
        var view = jdbc.sql(VIEW + ACCESS + " and h.id = :id").param("id", id);
        return scope(view, actor).query(View.class).optional().orElseThrow(Problems::missing);
    }

    private JdbcClient.StatementSpec scope(JdbcClient.StatementSpec query, Actor actor) {
        if (!actor.has("FOLLOWUP")) throw Problems.missing();
        return query.param("actor", actor.id());
    }

    @Transactional
    long start(Actor actor, long taskId, Start input) {
        episodes.lock(followups.load(taskId).episodeId());
        var task = followups.load(taskId);
        var sender = accounts.actorById(actor.id());
        requireSender(sender, task);
        Followups.requirePublished(task);
        if (input.version() != task.versionNumber()) throw Problems.conflict("随访版本已更新，请重新打开任务。");
        if (input.departmentId() == task.ownerDepartmentId()
                || input.recipientId() == task.ownerId() || input.recipientId() == task.reviewerId()
                || !eligible(input.recipientId(), input.departmentId())) {
            throw Problems.invalid("请选择其他科室有随访权限且未审核此任务的人员。");
        }
        var now = OffsetDateTime.now(clock);
        var pending = jdbc.sql("select * from followup_handoff where task_id = ? and status = 'PENDING'")
                .param(taskId).query(Handoff.class).optional();
        if (pending.isPresent()) {
            if (!settleUnavailable(pending.get(), task, now)) throw Problems.conflict("该随访已有待接手的交接。");
        }
        var due = now.plusHours(24);
        long id = jdbc.sql("""
                insert into followup_handoff(task_id, revision_id, sender_id, recipient_id, from_department_id,
                    to_department_id, created_at, due_at) values (?, ?, ?, ?, ?, ?, ?, ?) returning id
                """).params(taskId, task.publishedRevisionId(), sender.id(), input.recipientId(), task.ownerDepartmentId(),
                        input.departmentId(), now, due).query(Long.class).single();
        notices.openWork(WORK, id, "FOLLOWUP", input.recipientId(), due);
        audit.record(sender.id(), WORK, id, "REQUEST");
        return id;
    }

    @Transactional
    void decide(Actor actor, long id, Decision input) {
        var original = load(id);
        if (!actor.has("FOLLOWUP") || actor.id() != original.recipientId()
                || !actor.departmentIds().contains(original.toDepartmentId())) throw Problems.missing();
        episodes.lock(followups.load(original.taskId()).episodeId());
        var handoff = load(id);
        var task = followups.load(handoff.taskId());
        if (!eligible(actor.id(), handoff.toDepartmentId())) throw Problems.missing();
        var now = OffsetDateTime.now(clock);
        if (!handoff.status().equals("PENDING") || !now.isBefore(handoff.dueAt()) || !matches(handoff, task)) {
            throw Problems.conflict("该交接已处理、超时或随访已变更。");
        }
        if (input.action() == Action.ACCEPT) {
            followups.moveOwner(task, actor.id(), handoff.toDepartmentId());
            resolve(handoff, "ACCEPTED", actor.id(), now, null, "已确认接手");
        } else {
            if (input.reason() == null || input.reason().isBlank()) throw Problems.invalid("请填写退回原因。");
            resolve(handoff, "RETURNED", actor.id(), now, input.reason().strip(), "已退回原负责人");
        }
    }

    @Scheduled(fixedDelayString = "${care.followup.poll-ms:5000}")
    void expire() {
        var now = OffsetDateTime.now(clock);
        var rows = jdbc.sql("""
                select h.id, t.episode_id from followup_handoff h join followup_task t on t.id = h.task_id
                where h.status = 'PENDING' and (h.due_at <= ? or h.sender_id <> t.owner_id
                    or h.from_department_id <> t.owner_department_id or h.revision_id is distinct from t.published_revision_id
                    or t.needs_review or t.status not in ('DISPATCHED', 'PATIENT_REPLIED') or h.recipient_id = t.reviewer_id)
                order by h.due_at, h.id limit 50
                """).param(now).query(Due.class).list();
        for (var row : rows) {
            transactions.executeWithoutResult(status -> {
                if (episodes.tryLock(row.episodeId()) == null) return;
                var handoff = load(row.id());
                if (handoff.status().equals("PENDING")) {
                    settleUnavailable(handoff, followups.load(handoff.taskId()), OffsetDateTime.now(clock));
                }
            });
        }
    }

    private boolean settleUnavailable(Handoff handoff, Task task, OffsetDateTime now) {
        if (!now.isBefore(handoff.dueAt())) {
            resolve(handoff, "TIMED_OUT", null, now, null, "交接超时");
            return true;
        }
        if (!matches(handoff, task)) {
            resolve(handoff, "SUPERSEDED", null, now, null, "随访已变更");
            return true;
        }
        return false;
    }

    private void resolve(Handoff handoff, String status, Long actor, OffsetDateTime now, String reason, String resolution) {
        jdbc.sql("update followup_handoff set status = ?, resolved_at = ?, return_reason = ? where id = ?")
                .params(status, now, reason, handoff.id()).update();
        notices.closeWork(WORK, handoff.id(), actor, resolution);
        audit.record(actor, WORK, handoff.id(), status);
    }

    private boolean eligible(long recipient, long department) {
        return jdbc.sql("select exists(select 1 " + CANDIDATES + " and u.id = ? and d.id = ?)")
                .params(recipient, department).query(Boolean.class).single();
    }

    private Handoff load(long id) {
        return jdbc.sql("select * from followup_handoff where id = ?").param(id)
                .query(Handoff.class).optional().orElseThrow(Problems::missing);
    }

    private static boolean matches(Handoff handoff, Task task) {
        return handoff.senderId() == task.ownerId() && handoff.fromDepartmentId() == task.ownerDepartmentId()
                && handoff.recipientId() != task.reviewerId() && Objects.equals(handoff.revisionId(), task.publishedRevisionId())
                && !task.needsReview() && List.of("DISPATCHED", "PATIENT_REPLIED").contains(task.status());
    }

    private static void requireSender(Actor actor, Task task) {
        Followups.requireOwner(actor, task);
        if (!actor.departmentIds().contains(task.ownerDepartmentId())) throw Problems.missing();
    }

    enum Action { ACCEPT, RETURN }
    record Start(@Positive int version, @Positive long departmentId, @Positive long recipientId) { }
    record Decision(@NotNull Action action, @Size(max = 200) String reason) { }
    record Assignee(long departmentId, String department, long id, String name) { }
    record View(long id, long senderId, long recipientId, String sender, String recipient, String fromDepartment,
                String toDepartment, Long taskId, String status, OffsetDateTime createdAt, OffsetDateTime dueAt,
                OffsetDateTime resolvedAt, String returnReason) { }
    private record Due(long id, long episodeId) { }
    private record Handoff(long id, long taskId, Long revisionId, long senderId, long recipientId, long fromDepartmentId,
                           long toDepartmentId, String status, OffsetDateTime dueAt) { }
}
