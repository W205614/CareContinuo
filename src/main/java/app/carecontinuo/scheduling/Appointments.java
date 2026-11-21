package app.carecontinuo.scheduling;

import app.carecontinuo.common.Problems;
import app.carecontinuo.identity.Accounts.Actor;
import app.carecontinuo.notification.Notifications;
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
                   d.display_name as doctor, p.name as department, s.stop_reason, patient.display_name as patient_name
            from appointment a join schedule_slot s on s.id = a.slot_id
            join doctor d on d.id = s.doctor_id join department p on p.id = d.department_id
            join patient on patient.id = a.patient_id
            """;
    private static final String STORED = "select id, patient_id, slot_id, status from appointment where id = ?";
    private static final String STOP_AFFECTED = "exists (select 1 from work_item w where w.kind = 'STOPPED_APPOINTMENT' and w.object_id = a.id)";

    private final JdbcClient jdbc;
    private final Schedule schedule;
    private final Notifications notices;
    private final Clock clock;
    private final TransactionTemplate transactions;

    Appointments(JdbcClient jdbc, Schedule schedule, Notifications notices,
                 Clock clock, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.schedule = schedule;
        this.notices = notices;
        this.clock = clock;
        this.transactions = transactions;
    }

    public List<Appointment> list(Actor actor) {
        if (actor.has("PATIENT")) {
            return jdbc.sql(VIEW + " where a.patient_id = ? order by a.id desc limit 100")
                    .param(actor.patientId()).query(Appointment.class).list();
        }
        if (actor.has("DOCTOR")) {
            return jdbc.sql(VIEW + " where d.user_id = ? order by s.starts_at desc, a.id desc limit 100")
                    .param(actor.id()).query(Appointment.class).list();
        }
        Schedule.requireOperations(actor);
        return jdbc.sql(VIEW + " where " + STOP_AFFECTED + " order by s.starts_at desc, a.id desc limit 100")
                .query(Appointment.class).list();
    }

    Appointment details(Actor actor, long id) {
        var stored = stored(id);
        var slot = schedule.slot(stored.slotId(), false);
        if (actor.has("PATIENT")) requireOwner(actor, stored);
        else if (actor.has("DOCTOR")) requireDoctor(actor, slot);
        else if (actor.has("OPERATIONS")) {
            return jdbc.sql(VIEW + " where a.id = ? and " + STOP_AFFECTED).param(id)
                    .query(Appointment.class).optional().orElseThrow(Problems::missing);
        } else throw Problems.missing();
        return jdbc.sql(VIEW + " where a.id = ?").param(id).query(Appointment.class).single();
    }

    @Transactional
    public Change book(Actor actor, long slotId, String key) {
        if (!actor.has("PATIENT")) throw Problems.forbidden();
        return once(actor, "BOOK", key, Long.toString(slotId), () ->
                reserve(actor, schedule.slot(slotId, true), null));
    }

    @Transactional
    public Change bookRevisit(Actor actor, long followupId, long slotId, String key) {
        if (!actor.has("PATIENT")) throw Problems.forbidden();
        return once(actor, "BOOK_REVISIT", key, followupId + ":" + slotId, () ->
                reserve(actor, schedule.slot(slotId, true), null));
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
            notices.closeWork("STOPPED_APPOINTMENT", id, actor.id(), "患者已取消");
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
            var first = schedule.slot(Math.min(oldId, targetId), true);
            var second = schedule.slot(Math.max(oldId, targetId), true);
            var original = first.id() == oldId ? first : second;
            var target = first.id() == targetId ? first : second;
            appointment = stored(id);
            allowChange(original, appointment.status());
            var replacement = reserve(actor, target, id);
            jdbc.sql("""
                    insert into followup_appointment(task_id, appointment_id)
                    select task_id, ? from followup_appointment where appointment_id = ?
                    """).params(replacement.id(), id).update();
            if (original.stoppedAt() == null) release(oldId);
            change(appointment, "RESCHEDULED", actor.id(), null);
            notices.closeWork("STOPPED_APPOINTMENT", id, actor.id(), "患者已改约");
            return replacement;
        });
    }

    private Change reserve(Actor actor, Schedule.Slot slot, Long supersedesId) {
        if (slot.stoppedAt() != null || !new AppointmentWindow(slot.startsAt()).acceptsBookingChangesAt(now())) {
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
        Schedule.requireOperations(actor);
        var slot = schedule.slot(slotId, true);
        if (slot.stoppedAt() != null) return;
        var at = now();
        jdbc.sql("update schedule_slot set stopped_at = ?, stop_reason = ? where id = ?")
                .params(at, reason, slotId).update();
        var affected = jdbc.sql("""
                select id, patient_id, slot_id, status from appointment
                where slot_id = ? and status in ('BOOKED', 'CHECKED_IN') order by id
                """).param(slotId).query(Stored.class).list();
        for (var appointment : affected) {
            change(appointment, "STOPPED", actor.id(), reason);
            notices.openWork("STOPPED_APPOINTMENT", appointment.id(), "OPERATIONS", null, at);
            notices.enqueue("appointment:stopped:" + appointment.id(), appointment.patientId(), "APPOINTMENT_STOPPED",
                    appointment.id(), "门诊停诊，请重新选择出诊时段", at);
        }
    }

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
        requireDoctor(actor, slot);
        var appointment = stored(id);
        requireState(appointment, "CHECKED_IN");
        jdbc.sql("insert into encounter(appointment_id, doctor_id, started_at) values (?, ?, ?)")
                .params(id, slot.doctorId(), now()).update();
        change(appointment, "IN_CONSULT", actor.id(), null);
    }

    @Transactional
    void complete(Actor actor, long id, String note, String plan) {
        var slot = schedule.slot(stored(id).slotId(), true);
        requireDoctor(actor, slot);
        var appointment = stored(id);
        requireState(appointment, "IN_CONSULT");
        jdbc.sql("update encounter set completed_at = ?, note = ?, return_plan = ? where appointment_id = ?")
                .param(now()).param(note).param(plan).param(id).update();
        change(appointment, "COMPLETED", actor.id(), null);
    }

    @Transactional
    void correct(Actor actor, long id, String reason) {
        var slot = schedule.slot(stored(id).slotId(), true);
        requireDoctor(actor, slot);
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
                schedule.slot(slotId, true);
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
        if (!actor.has("DOCTOR") || slot.doctorUserId() != actor.id()) throw Problems.missing();
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
                              String patientName) { }
}
