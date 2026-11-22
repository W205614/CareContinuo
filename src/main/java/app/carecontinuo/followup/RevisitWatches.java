package app.carecontinuo.followup;

import app.carecontinuo.care.Episodes;
import app.carecontinuo.common.Problems;
import app.carecontinuo.identity.Accounts;
import app.carecontinuo.identity.Accounts.Actor;
import app.carecontinuo.notification.Notifications;
import app.carecontinuo.scheduling.Schedule;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class RevisitWatches {
    private static final Schedule.RevisitFilters UNFILTERED = new Schedule.RevisitFilters(null, null, null);
    private final JdbcClient jdbc;
    private final Followups followups;
    private final Episodes episodes;
    private final Accounts accounts;
    private final Notifications notices;
    private final Clock clock;
    private final TransactionTemplate transactions;

    RevisitWatches(JdbcClient jdbc, Followups followups, Episodes episodes, Accounts accounts, Notifications notices,
                   Clock clock, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.followups = followups;
        this.episodes = episodes;
        this.accounts = accounts;
        this.notices = notices;
        this.clock = clock;
        this.transactions = transactions;
    }

    View current(Actor actor, long taskId) {
        authorize(actor, taskId);
        return jdbc.sql("""
                select id, status, created_at, notified_at from revisit_watch
                where task_id = ? and actor_id = ? order by id desc limit 1
                """).params(taskId, actor.id()).query(View.class).optional().orElse(new View(null, "NONE", null, null));
    }

    @Transactional
    View start(Actor actor, long taskId, Long appointmentId) {
        followups.requireRevisitBooking(actor, taskId);
        var existing = current(actor, taskId);
        if (existing.status().equals("WAITING")) return existing;
        if (appointmentId == null && hasVisit(taskId)) throw Problems.conflict("请从原预约进入改约候补。");
        if (!followups.revisitSlots(actor, taskId, appointmentId, null, UNFILTERED).slots().isEmpty()) {
            throw Problems.conflict("已有符合要求的号源，请直接选择时段。");
        }
        return jdbc.sql("""
                insert into revisit_watch(task_id, actor_id, appointment_id) values (?, ?, ?)
                returning id, status, created_at, notified_at
                """).param(taskId).param(actor.id()).param(appointmentId).query(View.class).single();
    }

    @Transactional
    void cancel(Actor actor, long taskId) {
        authorize(actor, taskId);
        jdbc.sql("update revisit_watch set status = 'CANCELLED' where task_id = ? and actor_id = ? and status = 'WAITING'")
                .params(taskId, actor.id()).update();
    }

    @Scheduled(fixedDelayString = "${care.revisit.watch-poll-ms:10000}")
    void poll() {
        var candidates = jdbc.sql("""
                select w.id, t.episode_id from revisit_watch w join followup_task t on t.id = w.task_id
                where w.status = 'WAITING' order by w.checked_at nulls first, w.id limit 40
                """).query(Candidate.class).list();
        for (var candidate : candidates) {
            transactions.executeWithoutResult(status -> check(candidate));
        }
    }

    private void check(Candidate candidate) {
        if (episodes.tryLock(candidate.episodeId()) == null) return;
        var watch = jdbc.sql("""
                select w.task_id, w.actor_id, w.appointment_id, t.status as task_status,
                       t.needs_review, t.published_revision_id as revision_id, e.patient_id,
                       a.status as appointment_status, u.enabled and exists(
                           select 1 from user_role r where r.user_id = u.id and r.role = 'PATIENT') as enabled,
                       v.task_kind, v.revisit_to
                from revisit_watch w join followup_task t on t.id = w.task_id
                join care_episode e on e.id = t.episode_id join app_user u on u.id = w.actor_id
                left join appointment a on a.id = w.appointment_id
                left join followup_revision v on v.id = t.published_revision_id
                where w.id = ? and w.status = 'WAITING' for update of w
                """).param(candidate.id()).query(Pending.class).optional().orElse(null);
        if (watch == null) return;
        if (!watch.enabled() || List.of("CLOSED", "VERIFIED").contains(watch.taskStatus())
                || watch.appointmentId() != null && !"BOOKED".equals(watch.appointmentStatus()) && !"STOPPED".equals(watch.appointmentStatus())
                || watch.appointmentId() == null && hasVisit(watch.taskId())) {
            jdbc.sql("update revisit_watch set status = 'CANCELLED' where id = ?").param(candidate.id()).update();
            return;
        }
        var now = OffsetDateTime.now(clock);
        jdbc.sql("update revisit_watch set checked_at = ? where id = ?").params(now, candidate.id()).update();
        if (watch.needsReview() || !List.of("DISPATCHED", "PATIENT_REPLIED").contains(watch.taskStatus())) return;
        if (!"REVISIT".equals(watch.taskKind()) || watch.revisitTo() != null
                && now.atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate().isAfter(watch.revisitTo())) {
            jdbc.sql("update revisit_watch set status = 'CANCELLED' where id = ?").param(candidate.id()).update();
            return;
        }
        if (followups.revisitSlots(accounts.actorById(watch.actorId()), watch.taskId(), watch.appointmentId(),
                null, UNFILTERED).slots().isEmpty()) return;
        notices.enqueueSlotWatch(watch.patientId(), candidate.episodeId(), watch.taskId(), watch.revisionId(), candidate.id(), now);
        jdbc.sql("update revisit_watch set status = 'NOTIFIED', notified_at = ? where id = ?")
                .params(now, candidate.id()).update();
    }

    private boolean hasVisit(long taskId) {
        return jdbc.sql("""
                select exists(select 1 from followup_appointment r join appointment a on a.id = r.appointment_id
                    where r.task_id = ? and a.status in ('BOOKED', 'CHECKED_IN', 'IN_CONSULT', 'COMPLETED', 'STOPPED'))
                """).param(taskId).query(Boolean.class).single();
    }

    private void authorize(Actor actor, long taskId) {
        if (!actor.has("PATIENT")) throw Problems.missing();
        followups.publishedRevisitWindow(actor, taskId);
    }

    record View(Long id, String status, OffsetDateTime createdAt, OffsetDateTime notifiedAt) { }
    private record Candidate(long id, long episodeId) { }
    private record Pending(long taskId, long actorId, Long appointmentId, String taskStatus, boolean needsReview,
                           Long revisionId, long patientId, String appointmentStatus, boolean enabled, String taskKind, LocalDate revisitTo) { }
}
