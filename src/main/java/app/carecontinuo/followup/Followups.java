package app.carecontinuo.followup;

import app.carecontinuo.audit.AuditLog;
import app.carecontinuo.care.Episodes;
import app.carecontinuo.common.Problems;
import app.carecontinuo.identity.Accounts;
import app.carecontinuo.identity.Accounts.Actor;
import app.carecontinuo.notification.Notifications;
import app.carecontinuo.scheduling.AppointmentWindow;
import app.carecontinuo.scheduling.Schedule;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class Followups {
    private static final String TASK_SQL = """
            select t.id, t.episode_id, e.patient_id, t.owner_id, t.reviewer_id, t.status,
                   t.version_number, t.published_revision_id, t.needs_review, e.reference, p.display_name as patient, e.department_id,
                   owner.display_name as owner_name
            from followup_task t join care_episode e on e.id = t.episode_id join patient p on p.id = e.patient_id
            join app_user owner on owner.id = t.owner_id
            """;
    private static final String REVISION_SQL = """
            select id, version_number, source_version, task_kind, title, instructions, due_at, manual_reference,
              revisit_from, revisit_to, revisit_department_id,
              (select d.name from department d where d.id = followup_revision.revisit_department_id) as revisit_department
            from followup_revision where task_id = ?
            """;

    private static final String OVERDUE = """
            t.status in ('DISPATCHED', 'PATIENT_REPLIED') and not t.needs_review
            and exists(select 1 from followup_revision r where r.id = t.published_revision_id and r.due_at <= ?)
            and not exists(select 1 from work_item w
              where w.kind = 'FOLLOWUP_OVERDUE' and w.object_id = t.id and w.status = 'OPEN')
            """;

    private final JdbcClient jdbc;
    private final Episodes episodes;
    private final Accounts accounts;
    private final Notifications notices;
    private final AuditLog audit;
    private final Clock clock;
    private final FollowupRequests requests;
    private final Schedule schedule;
    private final TransactionTemplate transactions;

    Followups(JdbcClient jdbc, Episodes episodes, Notifications notices, AuditLog audit,
              Clock clock, TransactionTemplate transactions, Accounts accounts, FollowupRequests requests, Schedule schedule) {
        this.jdbc = jdbc;
        this.episodes = episodes;
        this.accounts = accounts;
        this.notices = notices;
        this.audit = audit;
        this.clock = clock;
        this.transactions = transactions;
        this.requests = requests;
        this.schedule = schedule;
    }

    public List<View> list(Actor actor, boolean actionable) {
        if (actor.has("PATIENT")) {
            var tasks = jdbc.sql(TASK_SQL + (actionable ? """
                    join followup_revision published on published.id = t.published_revision_id
                    where e.patient_id = ? and (t.needs_review or t.status in ('DISPATCHED', 'PATIENT_REPLIED'))
                    order by t.needs_review desc, published.due_at, t.id limit 100
                    """ : " where e.patient_id = ? and t.published_revision_id is not null order by t.id desc limit 100"))
                    .param(actor.patientId()).query(Task.class).list();
            return tasks.stream().map(task -> view(actor, task, false)).toList();
        }
        if (!actor.has("FOLLOWUP") && !actor.has("REVIEWER")) throw Problems.missing();
        var tasks = jdbc.sql(TASK_SQL + """
                where (t.owner_id = :staff and :followup or t.reviewer_id = :staff and :reviewer)
                and (:demo or exists(select 1 from staff_department sd where sd.user_id = :staff
                    and sd.department_id = e.department_id))
                order by t.id desc limit 100
                """).param("staff", actor.id()).param("followup", actor.has("FOLLOWUP"))
                .param("reviewer", actor.has("REVIEWER")).param("demo", actor.demo()).query(Task.class).list();
        return tasks.stream().map(task -> view(actor, task, false)).toList();
    }

    History history(Actor actor, Boolean closed, Kind kind, Long afterId) {
        if (!actor.has("PATIENT")) throw Problems.missing();
        var rows = jdbc.sql(TASK_SQL + """
                join followup_revision published on published.id = t.published_revision_id
                where e.patient_id = :patient
                  and (cast(:closed as boolean) is null or (t.status in ('CLOSED', 'VERIFIED')) = :closed)
                  and (cast(:kind as text) is null or published.task_kind = :kind)
                  and (cast(:cursor as bigint) is null or t.id < :cursor)
                order by t.id desc limit 51
                """).param("patient", actor.patientId()).param("closed", closed)
                .param("kind", kind == null ? null : kind.name()).param("cursor", afterId).query(Task.class).list();
        return new History(rows.stream().limit(50).map(task -> view(actor, task, false)).toList(),
                rows.size() > 50 ? rows.get(49).id() : null);
    }

    record History(List<View> tasks, Long nextCursor) { }

    public View get(Actor actor, long id) {
        var task = load(id);
        authorize(actor, task);
        audit.record(actor.id(), "FOLLOWUP", id, "READ");
        return view(actor, task, true);
    }

    RevisitWindow publishedRevisitWindow(Actor actor, long id) {
        var task = load(id);
        authorize(actor, task);
        if (actor.has("REVIEWER") && actor.id() == task.reviewerId() && task.needsReview()) {
            return revision(task).revisitWindow();
        }
        return task.publishedRevisionId() == null ? null : publishedRevision(task).revisitWindow();
    }

    RevisitWindow requireRevisitBooking(Actor actor, long id) {
        if (!actor.has("PATIENT")) throw Problems.missing();
        episodes.lock(load(id).episodeId());
        var task = load(id);
        authorize(accounts.actorById(actor.id()), task);
        requirePublished(task);
        var published = publishedRevision(task);
        if (!published.taskKind().equals("REVISIT")) throw Problems.conflict("此随访事项无需预约复诊。");
        return published.revisitWindow();
    }

    Schedule.RevisitSlotPage revisitSlots(Actor actor, long id, Long appointmentId, Long afterId, Schedule.RevisitFilters filters) {
        var task = load(id);
        authorize(actor, task);
        if (!actor.has("PATIENT")) throw Problems.missing();
        var revision = publishedRevision(task);
        if (task.needsReview() || !revision.taskKind().equals("REVISIT")) {
            throw Problems.conflict("当前随访不能预约复诊。");
        }
        Long excluded = null;
        if (appointmentId != null) {
            excluded = jdbc.sql("""
                    select a.slot_id from followup_appointment l join appointment a on a.id = l.appointment_id
                    where l.task_id = ? and a.id = ? and a.status in ('BOOKED', 'STOPPED')
                    """).params(id, appointmentId).query(Long.class).optional().orElseThrow(Problems::missing);
        } else {
            requirePublished(task);
        }
        return schedule.revisitSlots(actor, revision.revisitWindow(), excluded, afterId, filters);
    }

    @Transactional
    long submitRequest(Actor actor, long id, FollowupRequests.Input input) {
        if (!actor.has("PATIENT")) throw Problems.missing();
        episodes.lock(load(id).episodeId());
        var task = load(id);
        authorize(accounts.actorById(actor.id()), task);
        requirePublished(task);
        expectVersion(task, input.version());
        var published = publishedRevision(task);
        Long appointmentId = null;
        if (input.category() == FollowupRequests.Category.REVISIT_ASSISTANCE) {
            if (!published.taskKind().equals("REVISIT")) throw Problems.conflict("此随访事项无需预约复诊。");
            var activeVisit = jdbc.sql("""
                    select a.id, a.status, s.starts_at from followup_appointment l join appointment a on a.id = l.appointment_id
                    join schedule_slot s on s.id = a.slot_id
                    where l.task_id = ? and a.status in ('BOOKED', 'STOPPED', 'CHECKED_IN', 'IN_CONSULT', 'COMPLETED')
                    order by a.id desc limit 1
                    """).param(id).query(ActiveVisit.class).optional().orElse(null);
            if (activeVisit != null && !List.of("BOOKED", "STOPPED").contains(activeVisit.status())) {
                throw Problems.conflict("该随访已有就诊记录。");
            }
            if (activeVisit != null && activeVisit.status().equals("BOOKED")
                    && !new AppointmentWindow(activeVisit.startsAt()).acceptsBookingChangesAt(OffsetDateTime.now(clock))) {
                throw Problems.conflict("原预约已过退改约截止时间。");
            }
            appointmentId = activeVisit == null ? null : activeVisit.id();
            if (input.appointmentId() != null && !input.appointmentId().equals(appointmentId)) throw Problems.missing();
            var pending = requests.openRevisit(id);
            if (pending != null) return pending;
            if (!revisitSlots(actor, id, appointmentId, null, new Schedule.RevisitFilters(null, null, null)).slots().isEmpty()) {
                throw Problems.conflict("已有符合要求的号源，请刷新并自行预约。");
            }
        } else {
            transition(task, "PATIENT_REPLIED");
        }
        long requestId = requests.submit(id, task.ownerId(), input, appointmentId);
        audit.record(actor.id(), "FOLLOWUP_REQUEST", requestId, "SUBMIT");
        return requestId;
    }

    @Transactional
    void handleRequest(Actor actor, long id, long requestId, FollowupRequests.Handling input) {
        episodes.lock(load(id).episodeId());
        var task = load(id);
        requireOwner(accounts.actorById(actor.id()), task);
        requirePublished(task);
        expectVersion(task, input.version());
        Long reply = requests.handle(id, requestId, actor.id(), task.ownerId(), input);
        if (reply != null) {
            notices.enqueueFollowupReply(task.patientId(), task.episodeId(), id, task.publishedRevisionId(),
                    requestId, reply, OffsetDateTime.now(clock));
        } else {
            notices.enqueueFollowupStarted(task.patientId(), task.episodeId(), id, task.publishedRevisionId(),
                    requestId, OffsetDateTime.now(clock));
        }
        audit.record(actor.id(), "FOLLOWUP_REQUEST", requestId, input.action().name());
    }

    @Transactional
    void updateResult(Actor actor, long id, ResultInput input) {
        episodes.lock(load(id).episodeId());
        var task = load(id);
        requireOwner(accounts.actorById(actor.id()), task);
        requirePublished(task);
        expectVersion(task, input.version());
        var published = publishedRevision(task);
        if (!published.taskKind().equals("PENDING_RESULT")) throw Problems.conflict("该随访没有待出检查结果。");
        String message = input.message() == null || input.message().isBlank() ? null : input.message().strip();
        if (input.stage() == ResultStage.PLAN_AVAILABLE && message == null) {
            throw Problems.invalid("请填写患者可见的后续安排。");
        }
        String stage = input.stage().name();
        var latest = jdbc.sql("""
                select stage, patient_message, created_at from followup_result_update
                where revision_id = ? order by id desc limit 1
                """).param(published.id()).query(ResultUpdate.class).optional();
        if (latest.isPresent() && stage.equals(latest.get().stage())
                && Objects.equals(message, latest.get().patientMessage())) return;
        long updateId = jdbc.sql("""
                insert into followup_result_update(revision_id, actor_id, stage, patient_message)
                values (?, ?, ?, ?) returning id
                """).param(published.id()).param(actor.id()).param(stage).param(message).query(Long.class).single();
        notices.enqueueResultUpdate(task.patientId(), task.episodeId(), id, published.id(), updateId, OffsetDateTime.now(clock));
        audit.record(actor.id(), "FOLLOWUP", id, input.stage().name());
    }

    private record ActiveVisit(long id, String status, OffsetDateTime startsAt) { }

    enum ResultStage { CHECKING, PLAN_AVAILABLE }
    record ResultInput(@Positive int version, @NotNull ResultStage stage, @Size(max = 2000) String message) { }
    record ResultUpdate(String stage, String patientMessage, OffsetDateTime createdAt) { }

    @Transactional
    public long create(Actor actor, long episodeId, long reviewerId, Draft draft) {
        if (!actor.has("FOLLOWUP")) throw Problems.forbidden();
        var episode = episodes.lock(episodeId);
        var current = accounts.actorById(actor.id());
        if (!current.has("FOLLOWUP")) throw Problems.forbidden();
        episodes.authorized(current, episodeId);
        accounts.requireStaffRole(reviewerId, "REVIEWER", episode.departmentId());
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
        actor = accounts.actorById(actor.id());
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
        if (draft.kind() == Kind.REVISIT) {
            if (draft.revisitFrom() == null || draft.revisitTo() == null || draft.revisitDepartmentId() == null
                    || draft.revisitFrom().isAfter(draft.revisitTo())) {
                throw Problems.invalid("请选择复诊科室和有效日期区间，单日可将起止日期设为同一天。");
            }
            boolean departmentExists = jdbc.sql("select exists(select 1 from department where id = ?)")
                    .param(draft.revisitDepartmentId()).query(Boolean.class).single();
            if (!departmentExists) throw Problems.invalid("所选复诊科室不存在。");
        } else if (draft.revisitFrom() != null || draft.revisitTo() != null || draft.revisitDepartmentId() != null) {
            throw Problems.invalid("只有复诊随访可以设置复诊日期和科室。");
        }
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
                    instructions, due_at, manual_reference, author_id, revisit_from, revisit_to, revisit_department_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """).param(id).param(version).param(episode.sourceVersion()).param(draft.kind().name()).param(draft.title().strip())
                .param(draft.instructions().strip()).param(draft.dueAt()).param(draft.manualReference()).param(actor.id())
                .param(draft.revisitFrom()).param(draft.revisitTo()).param(draft.revisitDepartmentId())
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
        actor = accounts.actorById(actor.id());
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
                requireRevisitWindow(revision);
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
                verify(actor, task, input);
                requests.finish(task.id(), actor.id(), false);
                transition(task, "VERIFIED");
                closeWork(actor, task, "结果已核实");
                notices.enqueueFollowup(task.patientId(), task.episodeId(), task.id(), task.publishedRevisionId(),
                        "FOLLOWUP_VERIFIED", "医护已核实随访结果", OffsetDateTime.now(clock));
            }
            case TRANSFER -> transfer(actor, task, input.newOwnerId(), note(input));
            case CLOSE -> {
                requireOwner(actor, task);
                ensureOpen(task);
                if (input.closureReason() == null) throw Problems.invalid("请选择结案原因。");
                feedback(actor, task, "CLOSURE", input.closureReason().name() + " · " + note(input));
                transition(task, "CLOSED");
                requests.finish(task.id(), actor.id(), false);
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
        requireRevisitWindow(revision);
        if (jdbc.sql("""
                select exists(select 1 from agent_job where episode_id = ? and source_version = ? and status = 'CONFLICT')
                """).params(task.episodeId(), revision.sourceVersion()).query(Boolean.class).single()) {
            throw Problems.conflict("当前资料仍有冲突。资料有误时请更正并重新审核，模型误报由审核医生核实后解除。");
        }
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

    public AssignmentPage unassigned(Actor actor, long departmentId, Long afterId) {
        if (!actor.has("ADMIN")) throw Problems.forbidden();
        var rows = jdbc.sql("""
                with candidates as (
                select t.id, t.episode_id, e.department_id, t.status, t.owner_id, t.reviewer_id,
                    not exists(select 1 from app_user u join user_role r on r.user_id = u.id
                        join staff_department sd on sd.user_id = u.id
                        where u.id = t.owner_id and u.enabled and r.role = 'FOLLOWUP'
                          and sd.department_id = e.department_id) as owner_unavailable,
                    not exists(select 1 from app_user u join user_role r on r.user_id = u.id
                        join staff_department sd on sd.user_id = u.id
                        where u.id = t.reviewer_id and u.enabled and r.role = 'REVIEWER'
                          and sd.department_id = e.department_id) as reviewer_unavailable
                from followup_task t join care_episode e on e.id = t.episode_id
                where e.department_id = :department and t.id > :cursor and t.status not in ('VERIFIED','CLOSED')
                )
                select * from candidates where owner_unavailable or reviewer_unavailable order by id limit 51
                """)
                .param("department", departmentId).param("cursor", afterId == null ? 0L : afterId)
                .query(Assignment.class).list();
        return new AssignmentPage(rows.stream().limit(50).toList(), rows.size() > 50 ? rows.get(49).id() : null);
    }

    @Transactional
    public void reassign(Actor actor, long id, Reassignment input) {
        if (!actor.has("ADMIN")) throw Problems.forbidden();
        episodes.lock(load(id).episodeId());
        if (!accounts.actorById(actor.id()).has("ADMIN")) throw Problems.forbidden();
        var task = load(id);
        ensureOpen(task);
        long owner = input.ownerId() == null ? task.ownerId() : input.ownerId();
        long reviewer = input.reviewerId() == null ? task.reviewerId() : input.reviewerId();
        if (owner == reviewer || (owner == task.ownerId() && reviewer == task.reviewerId())) {
            throw Problems.invalid("请重新选择不同的负责人和审核人。");
        }
        if (owner != task.ownerId()) {
            accounts.requireStaffRole(owner, "FOLLOWUP", task.departmentId());
            moveOwner(task, owner);
        }
        if (reviewer != task.reviewerId()) {
            accounts.requireStaffRole(reviewer, "REVIEWER", task.departmentId());
            jdbc.sql("update followup_task set reviewer_id = ? where id = ?").params(reviewer, id).update();
            jdbc.sql("""
                    update work_item set owner_id = ? where status = 'OPEN'
                    and kind = 'FOLLOWUP_REVIEW' and object_id = ?
                    """).params(reviewer, id).update();
        }
        audit.record(actor.id(), "FOLLOWUP", id, "REASSIGN");
    }

    public record Reassignment(@Positive Long ownerId, @Positive Long reviewerId) { }
    public record Assignment(long id, long episodeId, long departmentId, String status, long ownerId, long reviewerId,
                             boolean ownerUnavailable, boolean reviewerUnavailable) { }
    public record AssignmentPage(List<Assignment> assignments, Long nextCursor) { }

    private void transfer(Actor actor, Task task, Long owner, String reason) {
        requireOwner(actor, task);
        ensureOpen(task);
        if (owner == null || owner == actor.id()) throw Problems.invalid("请选择其他随访负责人。");
        if (owner == task.reviewerId()) throw Problems.invalid("随访负责人不能同时审核该任务。");
        accounts.requireStaffRole(owner, "FOLLOWUP", task.departmentId());
        moveOwner(task, owner);
        feedback(actor, task, "CONTACT", "任务转派 · " + reason);
    }

    private void moveOwner(Task task, long owner) {
        jdbc.sql("update followup_task set owner_id = ? where id = ?").params(owner, task.id()).update();
        jdbc.sql("""
                update care_episode e set owner_id = ? where e.id = ? and e.owner_id = ?
                and not exists(select 1 from followup_task t where t.episode_id = e.id and t.owner_id = e.owner_id)
                """).params(owner, task.episodeId(), task.ownerId()).update();
        jdbc.sql("""
                update work_item w set owner_id = ? where w.status = 'OPEN' and w.owner_id = ? and (
                  (w.object_id = ? and w.kind in ('FOLLOWUP_OVERDUE', 'SOURCE_CHANGED', 'PATIENT_FEEDBACK', 'FOLLOWUP_TRANSFER'))
                  or (w.kind = 'NOTIFICATION_FAILED' and w.object_id in
                    (select id from outbox_event where episode_id = ? and object_id = ?))
                  or (w.kind = 'AGENT_CONFLICT' and w.object_id in (select id from agent_job where episode_id = ?))
                  or (w.kind = 'FOLLOWUP_REQUEST' and w.object_id in (select id from followup_request where task_id = ?)))
                """).params(owner, task.ownerId(), task.id(), task.episodeId(), task.id(), task.episodeId(), task.id()).update();
        notices.openWork("FOLLOWUP_TRANSFER", task.id(), "FOLLOWUP", owner, OffsetDateTime.now(clock));
    }

    @Scheduled(fixedDelayString = "${care.followup.poll-ms:5000}")
    void overdue() {
        var at = OffsetDateTime.now(clock);
        var overdue = jdbc.sql(TASK_SQL + " where " + OVERDUE + " order by t.id limit 50")
                .param(at).query(Task.class).list();
        for (var entry : overdue) {
            transactions.executeWithoutResult(status -> {
                if (episodes.tryLock(entry.episodeId()) == null) return;
                jdbc.sql(TASK_SQL + " where t.id = ? and " + OVERDUE).params(entry.id(), at)
                        .query(Task.class).optional().ifPresent(task ->
                                notices.openWork("FOLLOWUP_OVERDUE", task.id(), "FOLLOWUP", task.ownerId(), at));
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

    private void verify(Actor actor, Task task, ActionInput input) {
        if (input.encounterId() != null && !jdbc.sql("""
                select exists(select 1 from followup_appointment link join encounter v on v.appointment_id = link.appointment_id
                  where link.task_id = ? and v.id = ? and v.completed_at is not null)
                """).params(task.id(), input.encounterId()).query(Boolean.class).single()) {
            throw Problems.conflict("请选择该任务关联且已完成的接诊记录。");
        }
        jdbc.sql("""
                insert into followup_feedback(task_id, actor_id, kind, detail, source_encounter_id)
                values (?, ?, 'VERIFICATION', ?, ?)
                """).param(task.id()).param(actor.id()).param(note(input)).param(input.encounterId()).update();
    }

    private View view(Actor actor, Task task, boolean expanded) {
        boolean patient = actor.has("PATIENT");
        var revision = patient ? publishedRevision(task) : revision(task);
        List<Episodes.Segment> sources = patient || !expanded ? List.of() : revisionSources(revision.id());
        Comparison previous = null;
        if (expanded && !patient && actor.has("REVIEWER") && actor.id() == task.reviewerId()
                && task.publishedRevisionId() != null && task.publishedRevisionId() != revision.id()) {
            var published = publishedRevision(task);
            previous = new Comparison(published.versionNumber(), published.title(), published.taskKind(),
                    published.revisitDepartment(), published.revisitFrom(), published.revisitTo(),
                    published.dueAt(), published.instructions(), published.manualReference(),
                    revisionSources(published.id()));
        }
        String feedbackSql = """
                select f.id, f.kind, f.detail, f.created_at, u.display_name as actor, f.source_encounter_id
                from followup_feedback f join app_user u on u.id = f.actor_id where f.task_id = ?
                """ + (patient ? " and f.kind in ('PATIENT', 'VERIFICATION')" : "") + " order by f.id";
        List<Feedback> entries = expanded ? jdbc.sql(feedbackSql).param(task.id()).query(Feedback.class).list() : List.of();
        List<Review> reviews = patient || !expanded ? List.of() : jdbc.sql("""
                select id, decision, reason, created_at from followup_review where revision_id = ? order by id
                """).param(revision.id()).query(Review.class).list();
        String instructions = patient && task.needsReview() ? null : revision.instructions();
        String state = patient && task.needsReview() ? "UNDER_CORRECTION" : task.status();
        int version = patient ? revision.versionNumber() : task.versionNumber();
        List<ResultUpdate> results = !expanded || !revision.taskKind().equals("PENDING_RESULT") || patient && task.needsReview()
                ? List.of() : jdbc.sql("""
                select stage, patient_message, created_at from followup_result_update where revision_id = ? order by id desc
                """).param(revision.id()).query(ResultUpdate.class).list();
        return new View(task.id(), task.episodeId(), task.reference(), task.patient(), task.ownerId(), task.ownerName(), task.reviewerId(), task.departmentId(),
                state, version, task.needsReview(), revision.taskKind(), revision.title(), instructions,
                revision.dueAt(), revision.revisitFrom(), revision.revisitTo(), revision.revisitDepartmentId(),
                revision.revisitDepartment(), patient ? null : revision.manualReference(), sources, entries, reviews,
                expanded ? requests.history(task.id()) : List.of(), expanded ? requests.replies(task.id()) : List.of(), previous, results);
    }

    private List<Episodes.Segment> revisionSources(long revisionId) {
        return jdbc.sql("""
                select g.id, s.title, v.id as version_id, v.version_number, g.page_number, g.line_number, g.excerpt
                from followup_revision_source r join document_segment g on g.id = r.segment_id
                join document_version v on g.version_id = v.id join source_document s on v.document_id = s.id
                where r.revision_id = ? order by s.id, g.id
                """).param(revisionId).query(Episodes.Segment.class).list();
    }

    private Task load(long id) {
        return jdbc.sql(TASK_SQL + " where t.id = ?").param(id).query(Task.class).optional().orElseThrow(Problems::missing);
    }

    private Revision revision(Task task) {
        return jdbc.sql(REVISION_SQL + " and version_number = ?").params(task.id(), task.versionNumber())
                .query(Revision.class).single();
    }

    private Revision publishedRevision(Task task) {
        return jdbc.sql(REVISION_SQL + " and id = ?").params(task.id(), task.publishedRevisionId()).query(Revision.class).single();
    }

    private static void requireRevisitWindow(Revision revision) {
        if (revision.taskKind().equals("REVISIT") && !revision.revisitWindow().configured()) {
            throw Problems.conflict("请补充复诊日期和科室后重新审核。");
        }
    }

    private void transition(Task task, String status) {
        jdbc.sql("update followup_task set status = ? where id = ?").params(status, task.id()).update();
    }

    private static void authorize(Actor actor, Task task) {
        if (actor.has("PATIENT") && Long.valueOf(task.patientId()).equals(actor.patientId())
                && task.publishedRevisionId() != null) return;
        if (actor.inDepartment(task.departmentId()) && (actor.has("FOLLOWUP") && task.ownerId() == actor.id()
                || actor.has("REVIEWER") && task.reviewerId() == actor.id())) return;
        throw Problems.missing();
    }

    private static void requireOwner(Actor actor, Task task) {
        if (!actor.has("FOLLOWUP") || task.ownerId() != actor.id() || !actor.inDepartment(task.departmentId())) throw Problems.missing();
    }

    private static void requireReviewer(Actor actor, Task task) {
        if (!actor.has("REVIEWER") || task.reviewerId() != actor.id() || !actor.inDepartment(task.departmentId())) throw Problems.missing();
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
                        @NotNull @Size(max = 40) List<@NotNull @Positive Long> segmentIds, @Size(max = 600) String manualReference,
                        LocalDate revisitFrom, LocalDate revisitTo, @Positive Long revisitDepartmentId) { }
    public record ActionInput(@NotNull Action action, @Positive int version, @Size(max = 2000) String note,
                              @Positive Long newOwnerId, ClosureReason closureReason, @Positive Long encounterId) { }
    public record View(long id, long episodeId, String reference, String patient, long ownerId, String ownerName, long reviewerId, long departmentId,
                       String status, int version, boolean needsReview, String kind, String title, String instructions,
                       OffsetDateTime dueAt, LocalDate revisitFrom, LocalDate revisitTo, Long revisitDepartmentId,
                       String revisitDepartment, String manualReference, List<Episodes.Segment> sources,
                       List<Feedback> feedback, List<Review> reviews, List<FollowupRequests.View> requests,
                       List<FollowupRequests.Reply> replies, Comparison previous, List<ResultUpdate> resultUpdates) { }
    public record Comparison(int version, String title, String kind, String revisitDepartment,
                             LocalDate revisitFrom, LocalDate revisitTo,
                             OffsetDateTime dueAt, String instructions, String manualReference,
                             List<Episodes.Segment> sources) { }
    public record Feedback(long id, String kind, String detail, OffsetDateTime createdAt, String actor,
                           Long sourceEncounterId) { }
    public record Review(long id, String decision, String reason, OffsetDateTime createdAt) { }
    private record Task(long id, long episodeId, long patientId, long ownerId, long reviewerId, String status,
                        int versionNumber, Long publishedRevisionId, boolean needsReview, String reference, String patient, long departmentId, String ownerName) { }
    private record Revision(long id, int versionNumber, int sourceVersion, String taskKind, String title,
                            String instructions, OffsetDateTime dueAt, String manualReference, LocalDate revisitFrom,
                            LocalDate revisitTo, Long revisitDepartmentId, String revisitDepartment) {
        RevisitWindow revisitWindow() {
            return new RevisitWindow(revisitFrom, revisitTo, revisitDepartmentId);
        }
    }
}
