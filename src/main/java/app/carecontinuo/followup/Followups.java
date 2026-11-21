package app.carecontinuo.followup;

import app.carecontinuo.audit.AuditLog;
import app.carecontinuo.care.Episodes;
import app.carecontinuo.common.Problems;
import app.carecontinuo.identity.Accounts.Actor;
import app.carecontinuo.notification.Notifications;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class Followups {
    private static final String TASK_SQL = """
            select t.id, t.episode_id, e.patient_id, t.owner_id, t.reviewer_id, t.status,
                   t.version_number, t.published_revision_id, t.needs_review, e.reference, p.display_name as patient
            from followup_task t join care_episode e on e.id = t.episode_id join patient p on p.id = e.patient_id
            """;
    private static final String REVISION_SQL = """
            select id, version_number, source_version, task_kind, title, instructions, due_at, manual_reference
            from followup_revision where task_id = ?
            """;

    private final JdbcClient jdbc;
    private final Episodes episodes;
    private final Notifications notices;
    private final AuditLog audit;
    private final Clock clock;
    private final TransactionTemplate transactions;

    Followups(JdbcClient jdbc, Episodes episodes, Notifications notices, AuditLog audit,
              Clock clock, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.episodes = episodes;
        this.notices = notices;
        this.audit = audit;
        this.clock = clock;
        this.transactions = transactions;
    }

    public List<View> list(Actor actor) {
        if (actor.has("PATIENT")) {
            var tasks = jdbc.sql(TASK_SQL + " where e.patient_id = ? and t.published_revision_id is not null order by t.id desc limit 100")
                    .param(actor.patientId()).query(Task.class).list();
            return tasks.stream().map(task -> view(actor, task, false)).toList();
        }
        if (!actor.has("FOLLOWUP") && !actor.has("REVIEWER")) throw Problems.missing();
        var tasks = jdbc.sql(TASK_SQL + " where (t.owner_id = ? and ? or t.reviewer_id = ? and ?) order by t.id desc limit 100")
                .params(actor.id(), actor.has("FOLLOWUP"), actor.id(), actor.has("REVIEWER")).query(Task.class).list();
        return tasks.stream().map(task -> view(actor, task, false)).toList();
    }

    public View get(Actor actor, long id) {
        var task = load(id);
        authorize(actor, task);
        audit.record(actor.id(), "FOLLOWUP", id, "READ");
        return view(actor, task, true);
    }

    @Transactional
    public long create(Actor actor, long episodeId, long reviewerId, Draft draft) {
        if (!actor.has("FOLLOWUP")) throw Problems.forbidden();
        var episode = episodes.lock(episodeId);
        episodes.authorized(actor, episodeId);
        episodes.requireAccountRole(reviewerId, "REVIEWER");
        if (reviewerId == actor.id()) throw Problems.invalid("请选择另一位审核人。");
        long id = jdbc.sql("""
                insert into followup_task(episode_id, owner_id, reviewer_id, status) values (?, ?, ?, 'DRAFT') returning id
                """).params(episodeId, actor.id(), reviewerId).query(Long.class).single();
        saveRevision(actor, id, 1, episode, draft);
        return id;
    }

    @Transactional
    public void edit(Actor actor, long id, int expectedVersion, Draft draft) {
        var episode = episodes.lock(load(id).episodeId());
        var task = load(id);
        requireOwner(actor, task);
        expectVersion(task, expectedVersion);
        if (!List.of("DRAFT", "APPROVED").contains(task.status())) {
            throw Problems.conflict("当前随访状态不允许修改草稿。");
        }
        int version = task.versionNumber() + 1;
        saveRevision(actor, id, version, episode, draft);
        jdbc.sql("update followup_task set version_number = ?, status = 'DRAFT' where id = ?").params(version, id).update();
    }

    private void saveRevision(Actor actor, long id, int version, Episodes.Episode episode, Draft draft) {
        if (!draft.dueAt().isAfter(OffsetDateTime.now(clock))) throw Problems.invalid("完成期限应在未来。");
        var ids = new HashSet<>(draft.segmentIds());
        if (ids.isEmpty() && (draft.manualReference() == null || draft.manualReference().isBlank())) {
            throw Problems.invalid("请至少标记一处资料出处，或填写手工记录的依据。");
        }
        if (!ids.isEmpty()) {
            var available = episodes.currentSegments(episode.id()).stream().map(Episodes.Segment::id).toList();
            if (!available.containsAll(ids)) throw Problems.conflict("出处不属于当前资料版本，请重新选择。");
        }
        long revisionId = jdbc.sql("""
                insert into followup_revision(task_id, version_number, source_version, task_kind, title,
                    instructions, due_at, manual_reference, author_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """).param(id).param(version).param(episode.sourceVersion()).param(draft.kind().name()).param(draft.title().strip())
                .param(draft.instructions().strip()).param(draft.dueAt()).param(draft.manualReference()).param(actor.id())
                .query(Long.class).single();
        for (long segmentId : ids) {
            jdbc.sql("insert into followup_revision_source(revision_id, segment_id) values (?, ?)")
                    .params(revisionId, segmentId).update();
        }
        audit.record(actor.id(), "FOLLOWUP_REVISION", revisionId, "SAVE_DRAFT");
    }

    @Transactional
    public void act(Actor actor, long id, ActionInput input) {
        var episode = episodes.lock(load(id).episodeId());
        var task = load(id);
        authorize(actor, task);
        expectVersion(task, input.version());
        var revision = revision(task);
        if (input.action() != Action.TRANSFER && input.action() != Action.CLOSE
                && input.action() != Action.CONTACT && revision.sourceVersion() != episode.sourceVersion()) {
            throw Problems.conflict("资料已更正，请按当前版本更新草稿。");
        }
        switch (input.action()) {
            case SUBMIT -> {
                requireOwner(actor, task);
                requireStatus(task, "DRAFT");
                transition(task, "IN_REVIEW");
                notices.openWork("FOLLOWUP_REVIEW", task.id(), "REVIEWER", task.reviewerId(), revision.dueAt());
            }
            case APPROVE, RETURN -> review(actor, task, revision, input);
            case DISPATCH -> dispatch(actor, task, revision);
            case FEEDBACK -> {
                if (!actor.has("PATIENT")) throw Problems.forbidden();
                requirePublished(task);
                feedback(actor, task, "PATIENT", note(input));
                transition(task, "PATIENT_REPLIED");
                notices.openWork("PATIENT_FEEDBACK", task.id(), "FOLLOWUP", task.ownerId(), revision.dueAt());
            }
            case CONTACT -> {
                requireOwner(actor, task);
                ensureOpen(task);
                feedback(actor, task, "CONTACT", note(input));
            }
            case VERIFY -> {
                requireOwner(actor, task);
                requirePublished(task);
                feedback(actor, task, "VERIFICATION", note(input));
                transition(task, "VERIFIED");
                closeWork(actor, task, "结果已核实");
            }
            case TRANSFER -> transfer(actor, task, input.newOwnerId(), note(input));
            case CLOSE -> {
                requireOwner(actor, task);
                ensureOpen(task);
                if (input.closureReason() == null) throw Problems.invalid("请选择结案原因。");
                feedback(actor, task, "CLOSURE", input.closureReason().name() + " · " + note(input));
                transition(task, "CLOSED");
                closeWork(actor, task, "人工结案");
            }
        }
        audit.record(actor.id(), "FOLLOWUP", task.id(), input.action().name());
    }

    private void review(Actor actor, Task task, Revision revision, ActionInput input) {
        requireReviewer(actor, task);
        requireStatus(task, "IN_REVIEW");
        boolean approve = input.action() == Action.APPROVE;
        String decision = approve ? "APPROVED" : "RETURNED";
        jdbc.sql("insert into followup_review(revision_id, actor_id, decision, reason) values (?, ?, ?, ?)")
                .param(revision.id()).param(actor.id()).param(decision).param(approve ? input.note() : note(input)).update();
        transition(task, approve ? "APPROVED" : "DRAFT");
        notices.closeWork("FOLLOWUP_REVIEW", task.id(), actor.id(), approve ? "审核通过" : "退回修改");
    }

    private void dispatch(Actor actor, Task task, Revision revision) {
        requireReviewer(actor, task);
        requireStatus(task, "APPROVED");
        var now = OffsetDateTime.now(clock);
        if (!revision.dueAt().isAfter(now)) throw Problems.conflict("随访期限已过，请更新草稿和期限后重新审核。");
        String kind = task.publishedRevisionId() == null ? "FOLLOWUP_PUBLISHED" : "FOLLOWUP_CORRECTION";
        String title = task.publishedRevisionId() == null ? "有新的随访安排" : "随访安排已更正";
        jdbc.sql("""
                update followup_task set status = 'DISPATCHED', published_revision_id = ?, needs_review = false where id = ?
                """).params(revision.id(), task.id()).update();
        notices.enqueueFollowup(task.patientId(), task.episodeId(), task.id(), revision.id(), kind, title, now);
        if (revision.dueAt().minusDays(1).isAfter(now)) {
            notices.enqueueFollowup(task.patientId(), task.episodeId(), task.id(), revision.id(),
                    "FOLLOWUP_DUE", "随访安排即将到期", revision.dueAt().minusDays(1));
        }
        notices.closeWork("SOURCE_CHANGED", task.id(), actor.id(), "更正后的版本已派发");
    }

    private void transfer(Actor actor, Task task, Long owner, String reason) {
        requireOwner(actor, task);
        ensureOpen(task);
        if (owner == null || owner == actor.id()) throw Problems.invalid("请选择其他随访负责人。");
        episodes.requireAccountRole(owner, "FOLLOWUP");
        jdbc.sql("update followup_task set owner_id = ? where id = ?").params(owner, task.id()).update();
        jdbc.sql("""
                update care_episode e set owner_id = ? where e.id = ? and e.owner_id = ?
                and not exists(select 1 from followup_task t where t.episode_id = e.id and t.owner_id = e.owner_id)
                """).params(owner, task.episodeId(), actor.id()).update();
        jdbc.sql("""
                update work_item w set owner_id = ? where w.status = 'OPEN' and w.owner_id = ? and (
                  (w.object_id = ? and w.kind in ('FOLLOWUP_OVERDUE', 'SOURCE_CHANGED', 'PATIENT_FEEDBACK', 'FOLLOWUP_TRANSFER'))
                  or (w.kind = 'NOTIFICATION_FAILED' and w.object_id in
                    (select id from outbox_event where episode_id = ? and object_id = ?)))
                """).params(owner, actor.id(), task.id(), task.episodeId(), task.id()).update();
        notices.openWork("FOLLOWUP_TRANSFER", task.id(), "FOLLOWUP", owner, OffsetDateTime.now(clock));
        feedback(actor, task, "CONTACT", "任务转派 · " + reason);
    }

    @Scheduled(fixedDelayString = "${care.followup.poll-ms:5000}")
    void overdue() {
        var at = OffsetDateTime.now(clock);
        var ids = jdbc.sql(TASK_SQL + """
                where t.status in ('DISPATCHED', 'PATIENT_REPLIED')
                and exists(select 1 from followup_revision r where r.id = t.published_revision_id and r.due_at <= ?)
                and not exists(select 1 from work_item w where w.kind = 'FOLLOWUP_OVERDUE' and w.object_id = t.id)
                order by t.id limit 50
                """).param(at).query(Task.class).list();
        for (var entry : ids) {
            transactions.executeWithoutResult(status -> {
                episodes.lock(entry.episodeId());
                var task = load(entry.id());
                if ((task.status().equals("DISPATCHED") || task.status().equals("PATIENT_REPLIED"))
                        && !revision(task).dueAt().isAfter(at)) {
                    notices.openWork("FOLLOWUP_OVERDUE", task.id(), "FOLLOWUP", task.ownerId(), at);
                }
            });
        }
    }

    private void closeWork(Actor actor, Task task, String resolution) {
        for (String kind : List.of("FOLLOWUP_OVERDUE", "SOURCE_CHANGED", "FOLLOWUP_REVIEW", "PATIENT_FEEDBACK", "FOLLOWUP_TRANSFER")) {
            notices.closeWork(kind, task.id(), actor.id(), resolution);
        }
    }

    private void feedback(Actor actor, Task task, String kind, String detail) {
        jdbc.sql("insert into followup_feedback(task_id, actor_id, kind, detail) values (?, ?, ?, ?)")
                .params(task.id(), actor.id(), kind, detail).update();
    }

    private View view(Actor actor, Task task, boolean expanded) {
        boolean patient = actor.has("PATIENT");
        var revision = patient ? jdbc.sql(REVISION_SQL + " and id = ?").params(task.id(), task.publishedRevisionId())
                .query(Revision.class).single() : revision(task);
        List<Episodes.Segment> sources = patient || !expanded ? List.of() : jdbc.sql("""
                select g.id, s.title, v.id as version_id, v.version_number, g.page_number, g.line_number, g.excerpt
                from followup_revision_source r join document_segment g on g.id = r.segment_id
                join document_version v on g.version_id = v.id join source_document s on v.document_id = s.id
                where r.revision_id = ? order by s.id, g.id
                """).param(revision.id()).query(Episodes.Segment.class).list();
        String feedbackSql = """
                select f.id, f.kind, f.detail, f.created_at, u.display_name as actor
                from followup_feedback f join app_user u on u.id = f.actor_id where f.task_id = ?
                """ + (patient ? " and f.kind in ('PATIENT', 'VERIFICATION')" : "") + " order by f.id";
        List<Feedback> entries = expanded ? jdbc.sql(feedbackSql).param(task.id()).query(Feedback.class).list() : List.of();
        List<Review> reviews = patient || !expanded ? List.of() : jdbc.sql("""
                select id, decision, reason, created_at from followup_review where revision_id = ? order by id
                """).param(revision.id()).query(Review.class).list();
        String instructions = patient && task.needsReview() ? null : revision.instructions();
        String state = patient && task.needsReview() ? "UNDER_CORRECTION" : task.status();
        int version = patient ? revision.versionNumber() : task.versionNumber();
        return new View(task.id(), task.episodeId(), task.reference(), task.patient(), task.ownerId(), task.reviewerId(),
                state, version, task.needsReview(), revision.taskKind(), revision.title(), instructions,
                revision.dueAt(), patient ? null : revision.manualReference(), sources, entries, reviews);
    }

    private Task load(long id) {
        return jdbc.sql(TASK_SQL + " where t.id = ?").param(id).query(Task.class).optional().orElseThrow(Problems::missing);
    }

    private Revision revision(Task task) {
        return jdbc.sql(REVISION_SQL + " and version_number = ?").params(task.id(), task.versionNumber())
                .query(Revision.class).single();
    }

    private void transition(Task task, String status) {
        jdbc.sql("update followup_task set status = ? where id = ?").params(status, task.id()).update();
    }

    private static void authorize(Actor actor, Task task) {
        if (actor.has("PATIENT") && Long.valueOf(task.patientId()).equals(actor.patientId())
                && task.publishedRevisionId() != null) return;
        if (actor.has("FOLLOWUP") && task.ownerId() == actor.id() || actor.has("REVIEWER") && task.reviewerId() == actor.id()) return;
        throw Problems.missing();
    }

    private static void requireOwner(Actor actor, Task task) {
        if (!actor.has("FOLLOWUP") || task.ownerId() != actor.id()) throw Problems.missing();
    }

    private static void requireReviewer(Actor actor, Task task) {
        if (!actor.has("REVIEWER") || task.reviewerId() != actor.id()) throw Problems.missing();
    }

    private static void expectVersion(Task task, int expected) {
        if (task.versionNumber() != expected) throw Problems.conflict("随访草稿已更新，请刷新后重试。");
    }

    private static void requireStatus(Task task, String expected) {
        if (!task.status().equals(expected)) throw Problems.conflict("当前随访状态不允许此操作。");
    }

    private static void requirePublished(Task task) {
        if (task.needsReview() || !List.of("DISPATCHED", "PATIENT_REPLIED").contains(task.status())) {
            throw Problems.conflict("随访尚未派发，或当前版本正在复核。");
        }
    }

    private static void ensureOpen(Task task) {
        if (List.of("VERIFIED", "CLOSED").contains(task.status())) throw Problems.conflict("该随访任务已结束。");
    }

    private static String note(ActionInput input) {
        if (input.note() == null || input.note().isBlank()) throw Problems.invalid("请填写处理依据或反馈内容。");
        return input.note().strip();
    }

    enum Kind { REVISIT, PENDING_RESULT }
    enum Action { SUBMIT, APPROVE, RETURN, DISPATCH, FEEDBACK, CONTACT, VERIFY, TRANSFER, CLOSE }
    enum ClosureReason { REFUSED, UNREACHABLE, WRONG_TASK }

    public record Draft(@NotNull Kind kind, @NotBlank @Size(max = 140) String title,
                        @NotBlank @Size(max = 4000) String instructions, @NotNull OffsetDateTime dueAt,
                        @NotNull @Size(max = 40) List<@NotNull @Positive Long> segmentIds, @Size(max = 600) String manualReference) { }
    public record ActionInput(@NotNull Action action, @Positive int version, @Size(max = 2000) String note,
                              @Positive Long newOwnerId, ClosureReason closureReason) { }
    public record View(long id, long episodeId, String reference, String patient, long ownerId, long reviewerId,
                       String status, int version, boolean needsReview, String kind, String title, String instructions,
                       OffsetDateTime dueAt, String manualReference, List<Episodes.Segment> sources,
                       List<Feedback> feedback, List<Review> reviews) { }
    public record Feedback(long id, String kind, String detail, OffsetDateTime createdAt, String actor) { }
    public record Review(long id, String decision, String reason, OffsetDateTime createdAt) { }
    private record Task(long id, long episodeId, long patientId, long ownerId, long reviewerId, String status,
                        int versionNumber, Long publishedRevisionId, boolean needsReview, String reference, String patient) { }
    private record Revision(long id, int versionNumber, int sourceVersion, String taskKind, String title,
                            String instructions, OffsetDateTime dueAt, String manualReference) { }
}
