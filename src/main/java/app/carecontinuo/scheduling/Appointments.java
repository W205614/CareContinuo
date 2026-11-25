package app.carecontinuo.scheduling;

import app.carecontinuo.audit.AuditLog;
import app.carecontinuo.common.Problems;
import app.carecontinuo.followup.RevisitScheduling;
import app.carecontinuo.followup.RevisitWindow;
import app.carecontinuo.identity.Accounts;
import app.carecontinuo.identity.Accounts.Actor;
import app.carecontinuo.notification.Notifications;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class Appointments {
    private static final String VIEW = """
            select a.id, a.slot_id, a.supersedes_id, a.status, s.starts_at, s.ends_at,
                   d.display_name as doctor, p.name as department, s.stop_reason, s.visit_location, s.preparation, patient.display_name as patient_name,
                   (select l.task_id from followup_appointment l where l.appointment_id = a.id) as followup_id
            from appointment a join schedule_slot s on s.id = a.slot_id
            join doctor d on d.id = s.doctor_id join department p on p.id = s.department_id
            join patient on patient.id = a.patient_id
            """;
    private static final String STORED = "select id, patient_id, slot_id, status from appointment where id = ?";
    private static final String STOP_AFFECTED = "exists (select 1 from work_item w where w.kind = 'STOPPED_APPOINTMENT' and w.object_id = a.id)";

    private final JdbcClient jdbc;
    private final Schedule schedule;
    private final Accounts accounts;
    private final RevisitScheduling revisits;
    private final Notifications notices;
    private final Clock clock;
    private final AuditLog audit;
    private final TransactionTemplate transactions;

    Appointments(JdbcClient jdbc, Schedule schedule, Notifications notices,
                 Clock clock, TransactionTemplate transactions, RevisitScheduling revisits, Accounts accounts, AuditLog audit) {
        this.jdbc = jdbc;
        this.schedule = schedule;
        this.accounts = accounts;
        this.audit = audit;
        this.revisits = revisits;
        this.notices = notices;
        this.clock = clock;
        this.transactions = transactions;
    }

    public List<Appointment> list(Actor actor, boolean actionable) {
        if (actor.has("PATIENT")) {
            return jdbc.sql(VIEW + (actionable ? """
                     where a.patient_id = ? and a.status in ('STOPPED', 'BOOKED', 'CHECKED_IN')
                     order by (a.status = 'STOPPED') desc, s.starts_at, a.id limit 100
                    """ : " where a.patient_id = ? order by a.id desc limit 100"))
                    .param(actor.patientId()).query(Appointment.class).list();
        }
        if (actor.has("DOCTOR")) {
            return Schedule.scoped(jdbc.sql(VIEW + " where d.user_id = :staff and " + Schedule.STAFF_SCOPE
                    + " order by s.starts_at desc, a.id desc limit 100"), actor).query(Appointment.class).list();
        }
        Schedule.requireOperations(actor);
        return Schedule.scoped(jdbc.sql(VIEW + " where " + STOP_AFFECTED + " and " + Schedule.STAFF_SCOPE
                + " order by s.starts_at desc, a.id desc limit 100"), actor).query(Appointment.class).list();
    }

    AppointmentPage history(Actor actor, String status, Long afterId) {
        if (!actor.has("PATIENT")) throw Problems.missing();
        var rows = jdbc.sql(VIEW + """
                where a.patient_id = :patient
                 and (cast(:status as text) is null or a.status = :status)
                 and (cast(:cursor as bigint) is null or a.id < :cursor)
                order by a.id desc limit 51
                """).param("patient", actor.patientId()).param("status", status).param("cursor", afterId)
                .query(Appointment.class).list();
        return new AppointmentPage(rows.stream().limit(50).toList(), rows.size() > 50 ? rows.get(49).id() : null);
    }

    record AppointmentPage(List<Appointment> appointments, Long nextCursor) { }

    Appointment details(Actor actor, long id) {
        var stored = stored(id);
        var slot = schedule.slot(stored.slotId(), false);
        if (actor.has("PATIENT")) requireOwner(actor, stored);
        else if (actor.has("DOCTOR")) requireDoctor(actor, slot);
        else if (actor.has("OPERATIONS")) {
            Schedule.requireOperations(actor, slot.departmentId());
            return jdbc.sql(VIEW + " where a.id = ? and " + STOP_AFFECTED).param(id)
                    .query(Appointment.class).optional().orElseThrow(Problems::missing);
        } else throw Problems.missing();
        return jdbc.sql(VIEW + " where a.id = ?").param(id).query(Appointment.class).single();
    }

    CallbackProgress callbackProgress(Actor actor, long id) {
        if (!actor.has("PATIENT")) throw Problems.missing();
        return callbackProgress(id, actor.patientId());
    }

    private CallbackProgress callbackProgress(long id, long patientId) {
        return jdbc.sql("""
                select case when stopped.id is null then 'NOT_APPLICABLE'
                            when a.status in ('RESCHEDULED', 'CANCELLED') then a.status
                            when callback.status = 'DONE' then 'CONTACTED'
                            when callback.id is null then 'NOT_SCHEDULED'
                            when contact.outcome = 'NO_ANSWER' then 'NO_ANSWER'
                            else 'SCHEDULED' end as status,
                       case when callback.status = 'OPEN' and contact.outcome is distinct from 'NO_ANSWER'
                            then callback.due_at end as expected_at
                from appointment a
                left join work_item stopped on stopped.kind = 'STOPPED_APPOINTMENT' and stopped.object_id = a.id
                left join work_item callback on callback.kind = 'STOP_CALLBACK' and callback.object_id = a.id
                left join lateral (
                    select outcome from stop_contact where appointment_id = a.id order by id desc limit 1
                ) contact on true
                where a.id = ? and a.patient_id = ?
                """).params(id, patientId).query(CallbackProgress.class).optional().orElseThrow(Problems::missing);
    }

    record CallbackProgress(String status, OffsetDateTime expectedAt) { }

    @Transactional
    public Change book(Actor actor, long slotId, String key) {
        if (!actor.has("PATIENT")) throw Problems.forbidden();
        return once(actor, "BOOK", key, Long.toString(slotId), () ->
                reserve(actor, schedule.slot(slotId, true), null));
    }

    @Transactional
    public Change bookRevisit(Actor actor, long followupId, long slotId, String key, RevisitWindow window) {
        if (!actor.has("PATIENT")) throw Problems.forbidden();
        return once(actor, "BOOK_REVISIT", key, followupId + ":" + slotId, () -> {
            var slot = schedule.slot(slotId, true);
            window.requireMatch(slot.startsAt(), slot.departmentId());
            return reserve(actor, slot, null);
        });
    }

    @Transactional
    Change cancel(Actor actor, long id, String key) {
        return once(actor, "CANCEL", key, Long.toString(id), () -> {
            var appointment = stored(id);
            requireOwner(actor, appointment);
            var slot = schedule.slot(appointment.slotId(), true);
            appointment = stored(id);
            if (appointment.status().equals("CANCELLED")) return new Change(id, appointment.status());
            allowChange(slot, appointment.status());
            if (slot.stoppedAt() == null) release(slot.id());
            change(appointment, "CANCELLED", actor.id(), null);
            closeStopWork(appointment, actor.id(), "患者已取消");
            return new Change(id, "CANCELLED");
        });
    }

    @Transactional
    Change reschedule(Actor actor, long id, long targetId, String key) {
        return once(actor, "RESCHEDULE", key, id + ":" + targetId, () -> {
            var appointment = stored(id);
            requireOwner(actor, appointment);
            long oldId = appointment.slotId();
            if (oldId == targetId) throw Problems.invalid("请选择不同的出诊时段。");
            var window = revisits.lockWindowForReschedule(id);
            var first = schedule.slot(Math.min(oldId, targetId), true);
            var second = schedule.slot(Math.max(oldId, targetId), true);
            var original = first.id() == oldId ? first : second;
            var target = first.id() == targetId ? first : second;
            appointment = stored(id);
            allowChange(original, appointment.status());
            if (window != null) window.requireMatch(target.startsAt(), target.departmentId());
            var replacement = reserve(actor, target, id);
            jdbc.sql("""
                    insert into followup_appointment(task_id, appointment_id)
                    select task_id, ? from followup_appointment where appointment_id = ?
                    """).params(replacement.id(), id).update();
            if (original.stoppedAt() == null) release(oldId);
            change(appointment, "RESCHEDULED", actor.id(), null);
            closeStopWork(appointment, actor.id(), "患者已改约");
            if (window != null) revisits.completeAssistance(replacement.id(), actor.id());
            return replacement;
        });
    }

    private void closeStopWork(Stored appointment, long actorId, String resolution) {
        notices.closeWork("STOPPED_APPOINTMENT", appointment.id(), actorId, resolution);
        if (notices.closeWork("STOP_CALLBACK", appointment.id(), actorId, resolution)) {
            notifyCallback(appointment, "callback:closed:" + appointment.id());
        }
    }

    private void notifyCallback(Stored appointment, String key) {
        notices.enqueue(key, appointment.patientId(), "APPOINTMENT_CALLBACK_UPDATED", appointment.id(), "停诊回电有新进展", now());
    }

    private Change reserve(Actor actor, Schedule.Slot slot, Long supersedesId) {
        if (!slot.available() || (!actor.demo() && !slot.doctorInDepartment()) || slot.stoppedAt() != null || !new AppointmentWindow(slot.startsAt()).acceptsBookingChangesAt(now())) {
            throw Problems.conflict("该时段已停诊或已过预约截止时间。");
        }
        if (jdbc.sql("""
                select exists(select 1 from appointment where patient_id = ? and slot_id = ?
                and status in ('BOOKED', 'CHECKED_IN', 'IN_CONSULT', 'COMPLETED'))
                """).params(actor.patientId(), slot.id()).query(Boolean.class).single()) {
            throw Problems.conflict("你在该时段已有有效预约。");
        }
        if (slot.remaining() == 0) throw Problems.conflict("该时段号源已用完。");
        jdbc.sql("update schedule_slot set remaining = remaining - 1 where id = ?").param(slot.id()).update();
        var appointment = jdbc.sql("""
                insert into appointment(patient_id, slot_id, supersedes_id, status) values (?, ?, ?, 'BOOKED')
                returning id, patient_id, slot_id, status
                """).param(actor.patientId()).param(slot.id()).param(supersedesId).query(Stored.class).single();
        action(appointment.id(), actor.id(), null, appointment.status(), null);
        notices.enqueue("appointment:booked:" + appointment.id(), appointment.patientId(), "APPOINTMENT_BOOKED",
                appointment.id(), "门诊预约已确认", now());
        return new Change(appointment.id(), appointment.status());
    }

    @Transactional
    void stop(Actor actor, long slotId, String reason) {
        stopSelection(actor, List.of(slotId), reason);
    }

    @Transactional
    List<StopResult> stopSelection(Actor actor, List<Long> slotIds, String reason) {
        var slots = stopSlots(actor, slotIds, true);
        var current = accounts.actorById(actor.id());
        slots.forEach(slot -> Schedule.requireOperations(current, slot.departmentId()));
        var at = now();
        return slots.stream().map(slot -> applyStop(current, slot, reason, at)).toList();
    }

    List<StopImpact> previewStop(Actor actor, List<Long> slotIds) {
        return stopSlots(actor, slotIds, false).stream().map(slot -> {
            var people = slot.stoppedAt() != null ? List.<StopPatient>of() : jdbc.sql("""
                    select a.id as appointment_id, p.display_name as name, a.status
                    from appointment a join patient p on p.id = a.patient_id
                    where a.slot_id = ? and a.status in ('BOOKED', 'CHECKED_IN') order by a.id
                    """).param(slot.id()).query(StopPatient.class).list();
            return new StopImpact(slot.id(), slot.startsAt(), slot.doctorName(), slot.department(),
                    slot.stoppedAt() != null, people);
        }).toList();
    }

    private List<Schedule.Slot> stopSlots(Actor actor, List<Long> slotIds, boolean lock) {
        Schedule.requireOperations(actor);
        var slots = slotIds.stream().distinct().sorted().map(id -> schedule.slot(id, lock)).toList();
        slots.forEach(slot -> Schedule.requireOperations(actor, slot.departmentId()));
        if (slots.size() > 1 && slots.stream().map(Schedule.Slot::doctorId).distinct().count() != 1) {
            throw Problems.invalid("一次只能批量处理同一位医生的排班。");
        }
        return slots;
    }

    private StopResult applyStop(Actor actor, Schedule.Slot slot, String reason, OffsetDateTime at) {
        if (slot.stoppedAt() != null) return new StopResult(slot.id(), "ALREADY_STOPPED", 0);
        jdbc.sql("update schedule_slot set stopped_at = ?, stop_reason = ? where id = ?")
                .params(at, reason, slot.id()).update();
        var affected = jdbc.sql("""
                select id, patient_id, slot_id, status from appointment
                where slot_id = ? and status in ('BOOKED', 'CHECKED_IN') order by id
                """).param(slot.id()).query(Stored.class).list();
        for (var appointment : affected) {
            change(appointment, "STOPPED", actor.id(), reason);
            notices.openWork("STOPPED_APPOINTMENT", appointment.id(), "OPERATIONS", null, at);
            notices.enqueue("appointment:stopped:" + appointment.id(), appointment.patientId(), "APPOINTMENT_STOPPED",
                    appointment.id(), "门诊停诊，请重新选择出诊时段", at);
        }
        return new StopResult(slot.id(), "STOPPED", affected.size());
    }

    record StopImpact(long slotId, OffsetDateTime startsAt, String doctor, String department,
                      boolean alreadyStopped, List<StopPatient> patients) { }
    record StopPatient(long appointmentId, String name, String status) { }
    record StopResult(long slotId, String status, int affected) { }

    List<StopFollowup> stopped(Actor actor, boolean processed, Long appointmentId) {
        return stoppedRows(actor, processed, appointmentId, null, null, 100);
    }

    StopPage stoppedPage(Actor actor, boolean processed, OffsetDateTime afterStart, Long afterId) {
        if ((afterStart == null) != (afterId == null)) throw Problems.invalid("停诊清单分页参数不完整。");
        var rows = stoppedRows(actor, processed, null, afterStart, afterId, 51);
        StopFollowup last = rows.size() > 50 ? rows.get(49) : null;
        return new StopPage(rows.stream().limit(50).toList(), last == null ? null : new StopCursor(last.startsAt(), last.id()));
    }

    private List<StopFollowup> stoppedRows(Actor actor, boolean processed, Long appointmentId,
                                           OffsetDateTime afterStart, Long afterId, int limit) {
        Schedule.requireOperations(actor);
        return Schedule.scoped(jdbc.sql("""
                select a.id, a.status, s.starts_at, p.name as department, patient.display_name as patient_name,
                       s.stop_reason, m.created_at as message_at, m.read_at, c.contacted_at, c.outcome as contact_outcome,
                       cb.status as callback_status, cb.due_at as callback_due_at,
                       cb.owner_id as callback_owner_id, assignee.display_name as callback_owner,
                       cb.resolution as callback_resolution
                from work_item w join appointment a on a.id = w.object_id
                join schedule_slot s on s.id = a.slot_id join department p on p.id = s.department_id
                join patient on patient.id = a.patient_id
                left join work_item cb on cb.kind = 'STOP_CALLBACK' and cb.object_id = a.id
                left join app_user assignee on assignee.id = cb.owner_id
                left join outbox_event e on e.dedupe_key = 'appointment:stopped:' || a.id
                left join inbox_message m on m.event_id = e.id and m.recipient_user_id = e.recipient_user_id
                left join lateral (select contacted_at, outcome from stop_contact
                    where appointment_id = a.id order by id desc limit 1) c on true
                where w.kind = 'STOPPED_APPOINTMENT' and
                    (cast(:id as bigint) is null and w.status = :status or a.id = :id)
                    and (cast(:cursor as timestamptz) is null or (s.starts_at, a.id) > (:cursor, :afterId)) and
                """ + Schedule.STAFF_SCOPE + " order by s.starts_at, a.id limit :limit")
                .param("status", processed ? "DONE" : "OPEN").param("id", appointmentId)
                .param("cursor", afterStart).param("afterId", afterId).param("limit", limit), actor)
                .query(StopFollowup.class).list();
    }

    List<StopContact> stopContacts(Actor actor, long id) {
        checkStopScope(actor, id);
        return jdbc.sql("""
                select c.id, c.outcome, c.note, c.contacted_at, u.display_name as operator,
                       assignee.display_name as callback_owner, c.callback_due_at
                from stop_contact c join app_user u on u.id = c.operator_id
                left join app_user assignee on assignee.id = c.callback_owner_id
                where c.appointment_id = ? order by c.id desc
                """).param(id).query(StopContact.class).list();
    }

    List<CallbackAssignee> callbackAssignees(Actor actor, long id) {
        var appointment = checkStopScope(actor, id);
        var slot = schedule.slot(appointment.slotId(), false);
        return jdbc.sql("""
                select u.id, u.display_name as name from app_user u join user_role r on r.user_id = u.id
                where u.enabled and r.role = 'OPERATIONS' and (? or exists(
                    select 1 from staff_department sd where sd.user_id = u.id and sd.department_id = ?))
                order by u.display_name, u.id
                """).params(actor.demo(), slot.departmentId()).query(CallbackAssignee.class).list();
    }

    @Transactional
    void addStopContact(Actor actor, long id, StopContactInput input) {
        var appointment = checkStopScope(actor, id);
        var slot = schedule.slot(appointment.slotId(), true);
        var current = accounts.actorById(actor.id());
        Schedule.requireOperations(current, slot.departmentId());
        var original = stored(id);
        if (!original.status().equals("STOPPED")) throw Problems.conflict("该停诊预约已处理。");
        var before = callbackProgress(id, original.patientId());
        boolean schedulingCallback = input.outcome() == ContactOutcome.CALL_BACK;
        if (schedulingCallback) {
            if (input.callbackOwnerId() == null || input.callbackDueAt() == null || !input.callbackDueAt().isAfter(now())) {
                throw Problems.invalid("请指定回电负责人和未来的回电时间。");
            }
            accounts.requireStaffRole(input.callbackOwnerId(), "OPERATIONS", slot.departmentId());
            notices.openWork("STOP_CALLBACK", id, "OPERATIONS", input.callbackOwnerId(), input.callbackDueAt());
        } else {
            if (input.callbackOwnerId() != null || input.callbackDueAt() != null) {
                throw Problems.invalid("只有约定回电时可填写负责人和回电时间。");
            }
            var assignee = jdbc.sql("""
                    select owner_id from work_item where kind = 'STOP_CALLBACK' and object_id = ? and status = 'OPEN'
                    """).param(id).query(Long.class).optional();
            if (assignee.isPresent() && assignee.get() != current.id()) throw Problems.forbidden();
            if (input.outcome() == ContactOutcome.REACHED) {
                notices.closeWork("STOP_CALLBACK", id, current.id(), "已联系到患者");
            }
        }
        long contact = jdbc.sql("""
                insert into stop_contact(appointment_id, operator_id, outcome, note, callback_owner_id, callback_due_at)
                values (?, ?, ?, ?, ?, ?) returning id
                """).param(id).param(current.id()).param(input.outcome().name())
                .param(input.note() == null ? null : input.note().strip())
                .param(input.callbackOwnerId()).param(input.callbackDueAt()).query(Long.class).single();
        audit.record(actor.id(), "STOP_CONTACT", contact, "RECORD");
        if (!before.equals(callbackProgress(id, original.patientId()))) {
            notifyCallback(original, "callback:contact:" + contact);
        }
    }

    private Appointment checkStopScope(Actor actor, long id) {
        Schedule.requireOperations(actor);
        return Schedule.scoped(jdbc.sql(VIEW + " where a.id = :id and " + STOP_AFFECTED + " and "
                + Schedule.STAFF_SCOPE).param("id", id), actor).query(Appointment.class).optional().orElseThrow(Problems::missing);
    }

    record StopPage(List<StopFollowup> rows, StopCursor nextCursor) { }
    record StopCursor(OffsetDateTime startsAt, long id) { }

    record StopFollowup(long id, String status, OffsetDateTime startsAt, String department, String patientName,
                        String stopReason, OffsetDateTime messageAt, OffsetDateTime readAt, OffsetDateTime contactedAt,
                        String contactOutcome, String callbackStatus, OffsetDateTime callbackDueAt, Long callbackOwnerId, String callbackOwner,
                        String callbackResolution) { }
    record CallbackAssignee(long id, String name) { }
    record StopContact(long id, String outcome, String note, OffsetDateTime contactedAt, String operator,
                       String callbackOwner, OffsetDateTime callbackDueAt) { }
    record StopContactInput(@NotNull ContactOutcome outcome, @Size(max = 1000) String note,
                            @Positive Long callbackOwnerId, OffsetDateTime callbackDueAt) { }
    enum ContactOutcome { REACHED, NO_ANSWER, CALL_BACK }

    @Transactional
    void checkIn(Actor actor, long id) {
        var appointment = stored(id);
        requireOwner(actor, appointment);
        var slot = schedule.slot(appointment.slotId(), true);
        appointment = stored(id);
        requireState(appointment, "BOOKED");
        var time = now();
        if (!new AppointmentWindow(slot.startsAt()).acceptsCheckInAt(time)) {
            throw Problems.conflict("当前时间不在签到窗口内。");
        }
        change(appointment, "CHECKED_IN", actor.id(), null);
    }

    @Transactional
    void start(Actor actor, long id) {
        var slot = schedule.slot(stored(id).slotId(), true);
        requireDoctor(accounts.actorById(actor.id()), slot);
        var appointment = stored(id);
        requireState(appointment, "CHECKED_IN");
        jdbc.sql("insert into encounter(appointment_id, doctor_id, started_at) values (?, ?, ?)")
                .params(id, slot.doctorId(), now()).update();
        change(appointment, "IN_CONSULT", actor.id(), null);
    }

    @Transactional
    void complete(Actor actor, long id, String note, String plan) {
        var slot = schedule.slot(stored(id).slotId(), true);
        requireDoctor(accounts.actorById(actor.id()), slot);
        var appointment = stored(id);
        requireState(appointment, "IN_CONSULT");
        jdbc.sql("update encounter set completed_at = ?, note = ?, return_plan = ? where appointment_id = ?")
                .param(now()).param(note).param(plan).param(id).update();
        change(appointment, "COMPLETED", actor.id(), null);
    }

    @Transactional
    void correct(Actor actor, long id, String reason) {
        var slot = schedule.slot(stored(id).slotId(), true);
        requireDoctor(accounts.actorById(actor.id()), slot);
        var appointment = stored(id);
        requireState(appointment, "NO_SHOW");
        if (slot.stoppedAt() != null || !now().isBefore(slot.endsAt())) {
            throw Problems.conflict("只能更正仍在出诊时段内的未到诊记录。");
        }
        change(appointment, "CHECKED_IN", actor.id(), reason);
    }

    @Scheduled(fixedDelayString = "${care.appointments.poll-ms:5000}")
    void markMissed() {
        var slots = jdbc.sql("""
                select distinct s.id from schedule_slot s join appointment a on a.slot_id = s.id
                where s.starts_at <= ? and s.stopped_at is null and a.status = 'BOOKED' order by s.id limit 50
                """).param(now().minusMinutes(AppointmentWindow.CHECK_IN_WINDOW_MINUTES)).query(Long.class).list();
        for (long slotId : slots) {
            transactions.executeWithoutResult(transaction -> {
                boolean claimed = jdbc.sql("""
                        select id from schedule_slot where id = ? and stopped_at is null for update skip locked
                        """).param(slotId).query(Long.class).optional().isPresent();
                if (!claimed) return;
                var appointments = jdbc.sql("""
                        select id, patient_id, slot_id, status from appointment where slot_id = ? and status = 'BOOKED'
                        """).param(slotId).query(Stored.class).list();
                for (var appointment : appointments) change(appointment, "NO_SHOW", null, null);
            });
        }
    }

    private Change once(Actor actor, String operation, String key, String fingerprint, Supplier<Change> write) {
        if (key == null || key.isBlank() || key.length() > 128) throw Problems.invalid("缺少有效的 Idempotency-Key。");
        int inserted = jdbc.sql("""
                insert into idempotency_request(user_id, operation, request_key, fingerprint) values (?, ?, ?, ?)
                on conflict do nothing
                """).params(actor.id(), operation, key, fingerprint).update();
        if (inserted == 0) {
            var previous = jdbc.sql("""
                    select fingerprint, result_appointment_id, result_status from idempotency_request
                    where user_id = ? and operation = ? and request_key = ?
                    """).params(actor.id(), operation, key).query(Replay.class).single();
            if (!fingerprint.equals(previous.fingerprint())) throw Problems.conflict("同一提交编号对应的请求内容已变更。");
            return new Change(previous.resultAppointmentId(), previous.resultStatus());
        }
        var result = write.get();
        jdbc.sql("""
                update idempotency_request set result_appointment_id = ?, result_status = ?
                where user_id = ? and operation = ? and request_key = ?
                """).params(result.id(), result.status(), actor.id(), operation, key).update();
        return result;
    }

    private void allowChange(Schedule.Slot slot, String status) {
        if (status.equals("STOPPED")) return;
        if (!status.equals("BOOKED") || !new AppointmentWindow(slot.startsAt()).acceptsBookingChangesAt(now())) {
            throw Problems.conflict("当前预约状态或时间不允许退改约。");
        }
    }

    private void release(long slotId) {
        jdbc.sql("update schedule_slot set remaining = remaining + 1 where id = ?").param(slotId).update();
    }

    private void change(Stored appointment, String to, Long actorId, String reason) {
        jdbc.sql("update appointment set status = ?, updated_at = ? where id = ?")
                .params(to, now(), appointment.id()).update();
        action(appointment.id(), actorId, appointment.status(), to, reason);
    }

    private void action(long id, Long actorId, String from, String to, String reason) {
        jdbc.sql("""
                insert into appointment_action(appointment_id, actor_id, from_status, to_status, reason, occurred_at)
                values (?, ?, ?, ?, ?, ?)
                """).param(id).param(actorId).param(from).param(to).param(reason).param(now()).update();
    }

    private Stored stored(long id) {
        return jdbc.sql(STORED).param(id).query(Stored.class).optional().orElseThrow(Problems::missing);
    }

    private static void requireOwner(Actor actor, Stored appointment) {
        if (!actor.has("PATIENT") || !Long.valueOf(appointment.patientId()).equals(actor.patientId())) {
            throw Problems.missing();
        }
    }

    private static void requireDoctor(Actor actor, Schedule.Slot slot) {
        if (!actor.has("DOCTOR") || slot.doctorUserId() != actor.id() || !actor.inDepartment(slot.departmentId())) throw Problems.missing();
    }

    private static void requireState(Stored appointment, String state) {
        if (!appointment.status().equals(state)) throw Problems.conflict("当前预约状态不允许此操作。");
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private record Stored(long id, long patientId, long slotId, String status) { }
    private record Replay(String fingerprint, long resultAppointmentId, String resultStatus) { }
    public record Change(long id, String status) { }
    public record Appointment(long id, long slotId, Long supersedesId, String status, OffsetDateTime startsAt,
                              OffsetDateTime endsAt, String doctor, String department, String stopReason,
                              String patientName, Long followupId, String visitLocation, String preparation) {
        public OffsetDateTime getBookingChangesCloseAt() { return new AppointmentWindow(startsAt).bookingChangesCloseAt(); }
        public OffsetDateTime getCheckInOpensAt() { return new AppointmentWindow(startsAt).checkInOpensAt(); }
        public OffsetDateTime getCheckInClosesAt() { return new AppointmentWindow(startsAt).checkInClosesAt(); }
    }
}
