package app.carecontinuo.notification;

import app.carecontinuo.common.Problems;
import app.carecontinuo.identity.Accounts.Actor;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class Notifications {
    private static final Logger LOG = LoggerFactory.getLogger(Notifications.class);
    private static final List<Integer> RETRY_MINUTES = List.of(1, 5, 15);
    private static final String INBOX_SQL = """
            select m.id, e.kind, e.object_id, e.title, m.created_at, m.read_at, m.corrected_at,
              case when e.episode_id is not null then (select max(new_m.id) from inbox_message new_m join outbox_event new_e on new_m.event_id = new_e.id
               where new_e.kind = 'FOLLOWUP_CORRECTION' and new_e.object_id = e.object_id
               and new_e.episode_id = e.episode_id and new_e.revision_id > e.revision_id
               and new_m.recipient_user_id = m.recipient_user_id and new_m.corrected_at is null) end as replacement_id,
              req.id as followup_request_id,
              case when e.kind = 'FOLLOWUP_REPLY' and req.category = 'REVISIT_ASSISTANCE' then req.id end as revisit_request_id,
              watch.appointment_id as revisit_appointment_id
            from inbox_message m join outbox_event e on e.id = m.event_id
            left join followup_request req on req.id = case when e.kind in ('FOLLOWUP_REPLY', 'FOLLOWUP_REQUEST_STARTED')
              then split_part(e.dedupe_key, ':', 2)::bigint end
              and req.task_id = e.object_id
            left join revisit_watch watch on watch.id = case when e.kind = 'FOLLOWUP_SLOT_AVAILABLE'
              then split_part(e.dedupe_key, ':', 2)::bigint end and watch.task_id = e.object_id
            where m.recipient_user_id = :actor
            """;

    static final String BLOCKED_BY_CONFLICT = """
            exists(select 1 from care_episode ep join agent_job j on j.episode_id = ep.id
              join followup_revision r on r.source_version = ep.source_version
              where ep.id = e.episode_id and r.id = e.revision_id
                and j.source_version = ep.source_version and j.status = 'CONFLICT')
            """;

    private static final String CAN_ACCESS_WORK = """
            (w.owner_id = :actor or (w.owner_id is null and w.role = 'OPERATIONS'))
            and exists(select 1 from work_item_department scope where scope.id = w.id and
                ((:demo and not scope.strict_department) or exists(select 1 from staff_department sd
                    where sd.department_id = scope.department_id and sd.user_id = :actor)))
            and exists(select 1 from app_user u join user_role r on r.user_id = u.id
              where u.id = :actor and u.enabled and r.role = w.role)
            """;

    private static final String WORK_VIEW = """
            select w.id, w.kind, w.object_id, w.status, w.due_at, w.resolution, owner.display_name as owner,
              case when w.kind in ('STOPPED_APPOINTMENT', 'STOP_CALLBACK') then 'appointment'
                   when w.kind = 'FOLLOWUP_HANDOFF' then 'handoff'
                   when ev.episode_id is not null or req.id is not null or w.kind in ('FOLLOWUP_OVERDUE',
                     'FOLLOWUP_REVIEW', 'PATIENT_FEEDBACK', 'FOLLOWUP_TRANSFER', 'SOURCE_CHANGED') then 'followup'
                   when ev.kind in ('APPOINTMENT_STOPPED', 'APPOINTMENT_CALLBACK_UPDATED') then 'appointment'
                   when j.id is not null then 'episode' end as target_type,
              case when w.kind in ('STOPPED_APPOINTMENT', 'STOP_CALLBACK', 'FOLLOWUP_OVERDUE', 'FOLLOWUP_REVIEW', 'PATIENT_FEEDBACK',
                'FOLLOWUP_TRANSFER', 'SOURCE_CHANGED', 'FOLLOWUP_HANDOFF') then w.object_id
                else coalesce(req.task_id, j.episode_id, case
                  when ev.episode_id is not null or ev.kind in ('APPOINTMENT_STOPPED', 'APPOINTMENT_CALLBACK_UPDATED')
                    then ev.object_id end) end as target_id,
              req.category as request_category,
              case when ev.state = 'FAILED' and ev.manual_retry_at is not null then 'RETRY_FAILED'
                   else ev.state end as delivery_status
            from work_item w left join app_user owner on owner.id = w.owner_id
            left join followup_request req on w.kind = 'FOLLOWUP_REQUEST' and req.id = w.object_id
            left join outbox_event ev on w.kind = 'NOTIFICATION_FAILED' and ev.id = w.object_id
            left join agent_job j on w.kind = 'AGENT_CONFLICT' and j.id = w.object_id
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    Notifications(JdbcClient jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    public void enqueue(String key, long patientId, String kind, long objectId, String title, OffsetDateTime at) {
        jdbc.sql("""
                insert into outbox_event(dedupe_key, recipient_user_id, kind, object_id, title, available_at)
                select ?, id, ?, ?, ?, ? from app_user where patient_id = ?
                on conflict(dedupe_key) do nothing
                """).params(key, kind, objectId, title, at, patientId).update();
    }

    public void enqueueFollowup(long patientId, long episodeId, long taskId, long revisionId, String kind,
                                String title, OffsetDateTime at) {
        enqueueFollowupEvent(kind + ":" + revisionId, patientId, episodeId, taskId, revisionId, kind, title, at);
    }

    public void enqueueFollowupReply(long patientId, long episodeId, long taskId, long revisionId,
                                     long requestId, long replyId, OffsetDateTime at) {
        enqueueFollowupEvent("reply:" + requestId + ":" + replyId, patientId, episodeId, taskId, revisionId,
                "FOLLOWUP_REPLY", "随访有新的医护回复", at);
    }

    public void enqueueFollowupStarted(long patientId, long episodeId, long taskId, long revisionId,
                                       long requestId, OffsetDateTime at) {
        enqueueFollowupEvent("request-started:" + requestId, patientId, episodeId, taskId, revisionId,
                "FOLLOWUP_REQUEST_STARTED", "医护已开始处理您的随访反馈", at);
    }

    public void enqueueResultUpdate(long patientId, long episodeId, long taskId, long revisionId,
                                    long updateId, OffsetDateTime at) {
        enqueueFollowupEvent("result-update:" + updateId, patientId, episodeId, taskId, revisionId,
                "FOLLOWUP_RESULT_UPDATED", "检查结果进度已更新", at);
    }

    public void enqueueSlotWatch(long patientId, long episodeId, long taskId, long revisionId,
                                 long watchId, OffsetDateTime at) {
        enqueueFollowupEvent("slot-watch:" + watchId, patientId, episodeId, taskId, revisionId,
                "FOLLOWUP_SLOT_AVAILABLE", "复诊有新的可预约号源", at);
    }

    private void enqueueFollowupEvent(String key, long patientId, long episodeId, long taskId, long revisionId,
                                      String kind, String title, OffsetDateTime at) {
        jdbc.sql("""
                insert into outbox_event(dedupe_key, recipient_user_id, kind, object_id, title, available_at,
                    episode_id, revision_id)
                select ?, id, ?, ?, ?, ?, ?, ? from app_user where patient_id = ?
                on conflict(dedupe_key) do nothing
                """).params(key, kind, taskId, title, at, episodeId, revisionId, patientId).update();
    }

    @Scheduled(fixedDelayString = "${care.notifications.poll-ms:2000}")
    void poll() {
        var ids = jdbc.sql("""
                select id from outbox_event e where state = 'PENDING' and available_at <= ? and not
                """ + BLOCKED_BY_CONFLICT + " order by available_at, id limit 100")
                .param(OffsetDateTime.now(clock)).query(Long.class).list();
        for (long id : ids) {
            try {
                transactions.executeWithoutResult(status -> deliver(id));
            } catch (DataAccessException failure) {
                LOG.warn("Station message delivery failed for event {} ({})", id, failure.getClass().getSimpleName());
                transactions.executeWithoutResult(status -> recordFailure(id));
            }
        }
    }

    private void deliver(long id) {
        if (!tryLockEpisode(id)) return;
        var event = pending(id);
        if (event == null || event.blockedByConflict()) return;
        if (event.episodeId() != null && !dispatchable(event)) {
            jdbc.sql("update outbox_event set state = 'STALE' where id = ?").param(id).update();
            closeWork("NOTIFICATION_FAILED", id, null, "当前版本不再适用此提醒");
            return;
        }
        jdbc.sql("""
                insert into inbox_message(event_id, recipient_user_id) values (?, ?)
                on conflict (event_id, recipient_user_id) do nothing
                """).params(id, event.recipientUserId()).update();
        jdbc.sql("update outbox_event set state = 'SENT' where id = ?").param(id).update();
        closeWork("NOTIFICATION_FAILED", id, null, "站内消息已生成");
    }

    private void recordFailure(long id) {
        if (!tryLockEpisode(id)) return;
        var event = pending(id);
        if (event == null || event.blockedByConflict()) return;
        int attempts = event.attempts();
        var now = OffsetDateTime.now(clock);
        if (attempts < RETRY_MINUTES.size()) {
            jdbc.sql("update outbox_event set attempts = attempts + 1, available_at = ? where id = ?")
                    .params(now.plusMinutes(RETRY_MINUTES.get(attempts)), id).update();
            return;
        }
        jdbc.sql("update outbox_event set attempts = attempts + 1, state = 'FAILED' where id = ?").param(id).update();
        if (event.episodeId() == null) {
            openWork("NOTIFICATION_FAILED", id, "OPERATIONS", null, now);
        } else {
            Long owner = jdbc.sql("select owner_id from followup_task where id = ?")
                    .param(event.objectId()).query(Long.class).single();
            openWork("NOTIFICATION_FAILED", id, "FOLLOWUP", owner, now);
        }
    }

    private boolean tryLockEpisode(long eventId) {
        return jdbc.sql("""
                select o.episode_id is null or exists (
                    select 1 from care_episode e where e.id = o.episode_id for update of e skip locked)
                from outbox_event o where o.id = ?
                """).param(eventId).query(Boolean.class).optional().orElse(false);
    }

    // Document correction and message publication hold the same episode lock before touching events.
    private void lockEpisode(long eventId) {
        jdbc.sql("""
                select e.id from care_episode e join outbox_event o on o.episode_id = e.id where o.id = ? for update of e
                """).param(eventId).query(Long.class).optional();
    }

    private boolean dispatchable(Pending event) {
        return jdbc.sql("""
                select exists(select 1 from followup_task t join care_episode e on e.id = t.episode_id
                  join followup_revision r on r.id = t.published_revision_id and r.source_version = e.source_version
                  where t.id = ? and r.id = ? and not t.needs_review
                    and (t.status in ('DISPATCHED', 'PATIENT_REPLIED')
                    or (? not in ('FOLLOWUP_DUE', 'FOLLOWUP_SLOT_AVAILABLE') and t.status = 'VERIFIED')))
                """).params(event.objectId(), event.revisionId(), event.kind()).query(Boolean.class).single();
    }

    private Pending pending(long id) {
        return jdbc.sql("""
                select recipient_user_id, attempts, episode_id, object_id, revision_id, kind,
                """ + BLOCKED_BY_CONFLICT + """
                  as blocked_by_conflict from outbox_event e
                where id = ? and state = 'PENDING' and available_at <= ? for update of e skip locked
                """).params(id, OffsetDateTime.now(clock)).query(Pending.class).optional().orElse(null);
    }

    public void openWork(String kind, long objectId, String role, Long ownerId, OffsetDateTime dueAt) {
        jdbc.sql("""
                insert into work_item(kind, object_id, role, owner_id, due_at) values (?, ?, ?, ?, ?)
                on conflict(kind, object_id) do update set status = 'OPEN', owner_id = excluded.owner_id,
                    due_at = excluded.due_at, resolution = null, resolved_at = null, resolved_by = null
                """).param(kind).param(objectId).param(role).param(ownerId).param(dueAt).update();
    }

    public boolean closeWork(String kind, long objectId, Long actorId, String resolution) {
        return jdbc.sql("""
                update work_item set status = 'DONE', resolution = ?, resolved_by = ?, resolved_at = now()
                where kind = ? and object_id = ? and status = 'OPEN'
                """).param(resolution).param(actorId).param(kind).param(objectId).update() > 0;
    }

    List<Message> inbox(Actor actor) {
        return jdbc.sql(INBOX_SQL + " order by m.created_at desc, m.id desc limit 100")
                .param("actor", actor.id()).query(Message.class).list();
    }

    MessagePage inboxPage(Actor actor, Boolean read, MessageGroup group, Long afterId) {
        if (!actor.has("PATIENT")) throw Problems.missing();
        var rows = jdbc.sql(INBOX_SQL + """
                and (cast(:read as boolean) is null or (m.read_at is not null) = :read)
                and (cast(:group as text) is null or split_part(e.kind, '_', 1) = :group)
                and (cast(:cursor as bigint) is null or m.id < :cursor)
                order by m.id desc limit 51
                """).param("actor", actor.id()).param("read", read).param("group", group == null ? null : group.name())
                .param("cursor", afterId).query(Message.class).list();
        return new MessagePage(rows.stream().limit(50).toList(), rows.size() > 50 ? rows.get(49).id() : null);
    }

    enum MessageGroup { APPOINTMENT, FOLLOWUP }
    record MessagePage(List<Message> messages, Long nextCursor) { }

    List<Message> unreadHighlights(Actor actor) {
        if (!actor.has("PATIENT")) throw Problems.missing();
        return jdbc.sql(INBOX_SQL + """
                 and m.read_at is null and m.id in (
                   select distinct on (split_part(event.kind, '_', 1), event.object_id) message.id
                   from inbox_message message join outbox_event event on event.id = message.event_id
                   where message.recipient_user_id = :actor and message.corrected_at is null
                     and event.kind in ('FOLLOWUP_REPLY', 'FOLLOWUP_REQUEST_STARTED', 'FOLLOWUP_RESULT_UPDATED',
                                        'FOLLOWUP_VERIFIED', 'APPOINTMENT_CALLBACK_UPDATED')
                   order by split_part(event.kind, '_', 1), event.object_id, event.id desc
                 )
                 order by m.created_at desc, m.id desc limit 20
                """).param("actor", actor.id()).query(Message.class).list();
    }

    List<Message> unreadRevisitReplies(Actor actor) {
        return jdbc.sql(INBOX_SQL + """
                 and m.read_at is null and m.corrected_at is null and e.kind = 'FOLLOWUP_REPLY' and req.category = 'REVISIT_ASSISTANCE'
                 order by m.created_at desc, m.id desc limit 20
                """).param("actor", actor.id()).query(Message.class).list();
    }

    List<Message> unreadFollowupProgress(Actor actor) {
        return jdbc.sql(INBOX_SQL + """
                 and m.read_at is null and m.corrected_at is null
                 and (e.kind in ('FOLLOWUP_REQUEST_STARTED', 'FOLLOWUP_RESULT_UPDATED', 'FOLLOWUP_VERIFIED')
                      or (e.kind = 'FOLLOWUP_REPLY' and req.category <> 'REVISIT_ASSISTANCE'))
                 order by m.created_at desc, m.id desc limit 20
                """).param("actor", actor.id()).query(Message.class).list();
    }

    Message message(Actor actor, long id) {
        return jdbc.sql(INBOX_SQL + " and m.id = :id").param("actor", actor.id()).param("id", id).query(Message.class)
                .optional().orElseThrow(Problems::missing);
    }

    void read(Actor actor, long id) {
        if (jdbc.sql("""
                update inbox_message set read_at = coalesce(read_at, now()) where id = ? and recipient_user_id = ?
                """).params(id, actor.id()).update() == 0) throw Problems.missing();
    }

    List<Work> work(Actor actor) {
        return jdbc.sql(WORK_VIEW + " where " + CAN_ACCESS_WORK + " order by (w.status = 'OPEN') desc, w.due_at, w.id limit 100")
                .param("actor", actor.id()).param("demo", actor.demo()).query(Work.class).list();
    }

    WorkPage workPage(Actor actor, Long departmentId, String state, String kind, Long afterId) {
        if (state != null && !List.of("OPEN", "DONE").contains(state)) throw Problems.invalid("任务状态无效。");
        var due = afterId == null ? null : jdbc.sql("select w.due_at from work_item w where w.id = :id and "
                + CAN_ACCESS_WORK).param("id", afterId).param("actor", actor.id()).param("demo", actor.demo())
                .query(OffsetDateTime.class).optional().orElseThrow(Problems::missing);
        var rows = jdbc.sql(WORK_VIEW + " join work_item_department scope on scope.id = w.id where "
                + CAN_ACCESS_WORK + """
                and (cast(:department as bigint) is null or scope.department_id = :department)
                and (cast(:state as text) is null or w.status = :state)
                and (cast(:kind as text) is null or w.kind = :kind)
                and (cast(:due as timestamptz) is null or (w.due_at, w.id) > (:due, :cursor))
                order by w.due_at, w.id limit 51
                """).param("actor", actor.id()).param("demo", actor.demo()).param("kind", kind)
                .param("department", departmentId).param("state", state).param("cursor", afterId).param("due", due)
                .query(Work.class).list();
        return new WorkPage(rows.stream().limit(50).toList(), rows.size() > 50 ? rows.get(49).id() : null);
    }

    Work workItem(Actor actor, long id) {
        return jdbc.sql(WORK_VIEW + " where w.id = :id and " + CAN_ACCESS_WORK)
                .param("id", id).param("actor", actor.id()).param("demo", actor.demo())
                .query(Work.class).optional().orElseThrow(Problems::missing);
    }

    record WorkPage(List<Work> work, Long nextCursor) { }

    @Transactional
    void retry(Actor actor, long id) {
        long eventId = jdbc.sql("""
                select w.object_id from work_item w where w.id = :id
                and w.kind = 'NOTIFICATION_FAILED' and w.status = 'OPEN' and
                """ + CAN_ACCESS_WORK).param("id", id).param("actor", actor.id()).param("demo", actor.demo()).query(Long.class)
                .optional().orElseThrow(Problems::missing);
        lockEpisode(eventId);
        int matched = jdbc.sql("""
                update outbox_event e set state = 'PENDING', attempts = 0, available_at = :now, manual_retry_at = :now
                from work_item w where w.id = :id and w.kind = 'NOTIFICATION_FAILED' and w.status = 'OPEN'
                and w.object_id = e.id and e.id = :event and e.state = 'FAILED' and
                """ + CAN_ACCESS_WORK).param("now", OffsetDateTime.now(clock)).param("id", id)
                .param("event", eventId).param("actor", actor.id()).param("demo", actor.demo()).update();
        if (matched == 0) throw Problems.missing();
    }

    private record Pending(long recipientUserId, int attempts, Long episodeId, long objectId, Long revisionId,
                           String kind, boolean blockedByConflict) { }
    record Message(long id, String kind, long objectId, String title, OffsetDateTime createdAt, OffsetDateTime readAt,
                   OffsetDateTime correctedAt, Long replacementId, Long followupRequestId, Long revisitRequestId,
                   Long revisitAppointmentId) { }
    record Work(long id, String kind, long objectId, String status, OffsetDateTime dueAt, String resolution,
                String owner, String targetType, Long targetId, String requestCategory, String deliveryStatus) { }
}
