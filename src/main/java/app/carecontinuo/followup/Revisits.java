package app.carecontinuo.followup;

import app.carecontinuo.audit.AuditLog;
import app.carecontinuo.common.Problems;
import app.carecontinuo.identity.Accounts.Actor;
import app.carecontinuo.scheduling.Appointments;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class Revisits {
    private final JdbcClient jdbc;
    private final Followups followups;
    private final Appointments appointments;
    private final AuditLog audit;

    Revisits(JdbcClient jdbc, Followups followups, Appointments appointments, AuditLog audit) {
        this.jdbc = jdbc;
        this.followups = followups;
        this.appointments = appointments;
        this.audit = audit;
    }

    @Transactional
    Appointments.Change book(Actor actor, long taskId, long slotId, String key) {
        followups.requireRevisitBooking(actor, taskId);
        boolean alreadyHasVisit = jdbc.sql("""
                select exists(select 1 from followup_appointment r join appointment a on a.id = r.appointment_id
                where r.task_id = ? and a.status in ('BOOKED', 'CHECKED_IN', 'IN_CONSULT', 'COMPLETED', 'STOPPED'))
                """).param(taskId).query(Boolean.class).single();
        var booked = appointments.bookRevisit(actor, taskId, slotId, key);
        var alreadyLinked = jdbc.sql("select exists(select 1 from followup_appointment where task_id = ? and appointment_id = ?)")
                .params(taskId, booked.id()).query(Boolean.class).single();
        if (alreadyLinked) return booked;
        if (alreadyHasVisit) throw Problems.conflict("已有有效复诊预约，请通过原预约退改约。");
        jdbc.sql("insert into followup_appointment(task_id, appointment_id) values (?, ?)").params(taskId, booked.id()).update();
        audit.record(actor.id(), "FOLLOWUP", taskId, "BOOK_REVISIT");
        return booked;
    }

    List<LinkedVisit> list(Actor actor, long taskId) {
        followups.requireReadAccess(actor, taskId);
        audit.record(actor.id(), "FOLLOWUP", taskId, "READ_REVISITS");
        return jdbc.sql("""
                select a.id as appointment_id, a.supersedes_id, a.status, s.starts_at, d.display_name as doctor,
                       dep.name as department, v.id as encounter_id, v.completed_at
                from followup_appointment r join appointment a on a.id = r.appointment_id
                join schedule_slot s on s.id = a.slot_id join doctor d on d.id = s.doctor_id
                join department dep on dep.id = d.department_id left join encounter v on v.appointment_id = a.id
                where r.task_id = ? order by a.id desc limit 100
                """).param(taskId).query(LinkedVisit.class).list();
    }

    record LinkedVisit(long appointmentId, Long supersedesId, String status, OffsetDateTime startsAt, String doctor,
                       String department, Long encounterId, OffsetDateTime completedAt) { }
}
