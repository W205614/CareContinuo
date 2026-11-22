package app.carecontinuo.followup;

import app.carecontinuo.common.Problems;
import app.carecontinuo.notification.Notifications;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class FollowupRequests {
    private static final String SELECT = """
            select id, category, patient_note, status, outcome, public_reply, created_at, started_at, resolved_at,
                   response_due_at, next_update_at, appointment_id
            from followup_request
            """;
    private final JdbcClient jdbc;
    private final Notifications notices;
    private final Clock clock;

    FollowupRequests(JdbcClient jdbc, Notifications notices, Clock clock) {
        this.jdbc = jdbc;
        this.notices = notices;
        this.clock = clock;
    }

    List<View> history(long taskId) {
        return jdbc.sql(SELECT + " where task_id = ? order by id desc").param(taskId).query(View.class).list();
    }

    List<Reply> replies(long taskId) {
        return jdbc.sql("""
                select r.id, r.request_id, r.outcome, r.public_reply, r.next_update_at, r.created_at
                from followup_request_reply r join followup_request q on q.id = r.request_id
                where q.task_id = ? order by r.id desc
                """).param(taskId).query(Reply.class).list();
    }

    Long openRevisit(long taskId) {
        return jdbc.sql("""
                select id from followup_request where task_id = ? and category = 'REVISIT_ASSISTANCE'
                  and status <> 'RESOLVED'
                """).param(taskId).query(Long.class).optional().orElse(null);
    }

    long submit(long taskId, long ownerId, Input input, Long appointmentId) {
        var beijing = ZoneId.of("Asia/Shanghai");
        var responseDay = clock.instant().atZone(beijing).toLocalDate().plusDays(1);
        while (responseDay.getDayOfWeek() == DayOfWeek.SATURDAY || responseDay.getDayOfWeek() == DayOfWeek.SUNDAY) {
            responseDay = responseDay.plusDays(1);
        }
        var dueAt = responseDay.atTime(17, 0).atZone(beijing).toOffsetDateTime();
        long id = jdbc.sql("""
                insert into followup_request(task_id, category, patient_note, response_due_at, appointment_id)
                values (?, ?, ?, ?, ?) returning id
                """).param(taskId).param(input.category().name()).param(clean(input.note())).param(dueAt).param(appointmentId)
                .query(Long.class).single();
        notices.openWork("FOLLOWUP_REQUEST", id, "FOLLOWUP", ownerId, dueAt);
        return id;
    }

    Long handle(long taskId, long id, long actorId, long ownerId, Handling input) {
        var request = jdbc.sql(SELECT + " where task_id = ? and id = ?")
                .params(taskId, id).query(View.class).optional().orElseThrow(Problems::missing);
        if (request.status().equals("RESOLVED")) throw Problems.conflict("该请求已处理。");
        if (input.action() == Action.START) {
            jdbc.sql("""
                    update followup_request set status = 'IN_PROGRESS', started_at = coalesce(started_at, now())
                    where id = ?
                    """).param(id).update();
            return null;
        }

        boolean assistance = request.category().equals("REVISIT_ASSISTANCE");
        var outcome = input.outcome();
        if (outcome == null || assistance == (outcome == Outcome.REPLIED)) {
            throw Problems.invalid("请选择适用的处理结果。");
        }
        var reply = clean(input.reply());
        if (reply == null) throw Problems.invalid("请填写患者可见的回复。");
        boolean needsFollowup = outcome == Outcome.NO_SLOTS || outcome == Outcome.REVIEW_NEEDED;
        var nextUpdate = input.nextUpdateAt();
        if (needsFollowup && (nextUpdate == null || !nextUpdate.isAfter(OffsetDateTime.now(clock)))) {
            throw Problems.invalid("请填写未来的下次更新时间。");
        }
        if (!needsFollowup && nextUpdate != null) throw Problems.invalid("当前结果无需安排下次更新。");
        long replyId = jdbc.sql("""
                insert into followup_request_reply(request_id, outcome, public_reply, next_update_at)
                values (?, ?, ?, ?) returning id
                """).param(id).param(outcome.name()).param(reply).param(nextUpdate).query(Long.class).single();
        jdbc.sql("""
                update followup_request set status = :status, outcome = :outcome, public_reply = :reply,
                  started_at = coalesce(started_at, now()), resolved_at = case when :finished then now() end,
                  resolved_by = case when :finished then :actor end, next_update_at = :next
                where id = :id
                """).param("status", needsFollowup ? "IN_PROGRESS" : "RESOLVED").param("outcome", outcome.name())
                .param("reply", reply).param("finished", !needsFollowup).param("next", nextUpdate)
                .param("actor", actorId).param("id", id).update();
        if (needsFollowup) notices.openWork("FOLLOWUP_REQUEST", id, "FOLLOWUP", ownerId, nextUpdate);
        else notices.closeWork("FOLLOWUP_REQUEST", id, actorId, "已回复患者");
        return replyId;
    }

    void finish(long taskId, long actorId, boolean booked) {
        var ids = jdbc.sql("""
                update followup_request set status = 'RESOLVED', outcome = :outcome, next_update_at = null, resolved_at = now(), resolved_by = :actor
                where task_id = :task and status <> 'RESOLVED'
                  and (not :booked or category = 'REVISIT_ASSISTANCE')
                returning id
                """).param("outcome", booked ? "BOOKED" : "TASK_ENDED").param("actor", actorId)
                .param("task", taskId).param("booked", booked).query(Long.class).list();
        for (long id : ids) notices.closeWork("FOLLOWUP_REQUEST", id, actorId, booked ? "患者已预约" : "随访已结束");
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    enum Category { REVISIT_ASSISTANCE, COMPLETED, DIFFICULTY, QUESTION }
    enum Action { START, REPLY }
    enum Outcome { SLOTS_AVAILABLE, NO_SLOTS, REVIEW_NEEDED, REPLIED }

    record Input(@NotNull Category category, @Positive int version, @Size(max = 2000) String note,
                 @Positive Long appointmentId) { }
    record Handling(@NotNull Action action, @Positive int version, Outcome outcome, @Size(max = 2000) String reply,
                    OffsetDateTime nextUpdateAt) { }
    record View(long id, String category, String patientNote, String status, String outcome, String publicReply,
                OffsetDateTime createdAt, OffsetDateTime startedAt, OffsetDateTime resolvedAt,
                OffsetDateTime responseDueAt, OffsetDateTime nextUpdateAt, Long appointmentId) { }
    record Reply(long id, long requestId, String outcome, String publicReply, OffsetDateTime nextUpdateAt, OffsetDateTime createdAt) { }
}
