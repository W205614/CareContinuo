package app.carecontinuo.scheduling;

import app.carecontinuo.common.Problems;
import app.carecontinuo.identity.Accounts.Actor;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class Schedule {
    static final String SLOT_VIEW = """
            select s.id, s.doctor_id, d.display_name as doctor_name, p.name as department,
                   d.user_id as doctor_user_id, p.id as department_id, s.starts_at, s.ends_at, s.capacity,
                   s.remaining, s.stopped_at, s.stop_reason
            from schedule_slot s join doctor d on s.doctor_id = d.id join department p on d.department_id = p.id
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    Schedule(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    Overview catalog(Actor actor) {
        boolean operations = actor.has("OPERATIONS");
        var departments = jdbc.sql("select id, name from department order by name").query(Department.class).list();
        var doctors = jdbc.sql("""
                select id, department_id, display_name from doctor order by display_name
                """).query(Doctor.class).list();
        var slots = operations
                ? jdbc.sql(SLOT_VIEW + " order by starts_at desc, s.id desc limit 150").query(Slot.class).list()
                : jdbc.sql(SLOT_VIEW + " where stopped_at is null and starts_at > ? order by starts_at, s.id limit 150")
                        .param(OffsetDateTime.now(clock).plusMinutes(AppointmentWindow.BOOKING_CUTOFF_MINUTES))
                        .query(Slot.class).list();
        return new Overview(departments, doctors, slots);
    }

    Department department(Actor actor, String name) {
        requireOperations(actor);
        return jdbc.sql("""
                insert into department(name) values (?) on conflict(name) do update set name = excluded.name
                returning id, name
                """).param(name.strip()).query(Department.class).single();
    }

    Doctor doctor(Actor actor, DoctorInput input) {
        requireOperations(actor);
        if (!jdbc.sql("select exists(select 1 from department where id = ?)").param(input.departmentId())
                .query(Boolean.class).single()) {
            throw Problems.missing();
        }
        return jdbc.sql("""
                insert into doctor(user_id, department_id, display_name)
                select u.id, ?, ? from app_user u join user_role r on r.user_id = u.id
                where u.username = ? and r.role = 'DOCTOR' and u.enabled
                on conflict(user_id) do update set department_id = excluded.department_id,
                    display_name = excluded.display_name
                returning id, department_id, display_name
                """).params(input.departmentId(), input.displayName().strip(), input.username().strip())
                .query(Doctor.class).optional().orElseThrow(Problems::missing);
    }

    Slot createSlot(Actor actor, SlotInput input) {
        requireOperations(actor);
        if (!input.endsAt().isAfter(input.startsAt()) || !input.startsAt().isAfter(OffsetDateTime.now(clock))) {
            throw Problems.invalid("出诊结束时间应晚于开始时间，开始时间应在未来。");
        }
        try {
            long id = jdbc.sql("""
                    insert into schedule_slot(doctor_id, starts_at, ends_at, capacity, remaining)
                    select id, ?, ?, ?, ? from doctor where id = ? returning id
                    """).params(input.startsAt(), input.endsAt(), input.capacity(), input.capacity(), input.doctorId())
                    .query(Long.class).optional().orElseThrow(Problems::missing);
            return slot(id, false);
        } catch (DuplicateKeyException exception) {
            throw Problems.conflict("该医生已有相同起始时间的排班。");
        }
    }

    Slot slot(long id, boolean lock) {
        return jdbc.sql(SLOT_VIEW + " where s.id = ?" + (lock ? " for update of s" : ""))
                .param(id).query(Slot.class).optional().orElseThrow(Problems::missing);
    }

    static void requireOperations(Actor actor) {
        if (!actor.has("OPERATIONS")) throw Problems.forbidden();
    }

    record Department(long id, String name) { }
    record Doctor(long id, long departmentId, String displayName) { }
    record Slot(long id, long doctorId, String doctorName, String department, long doctorUserId, long departmentId,
                OffsetDateTime startsAt, OffsetDateTime endsAt, int capacity, int remaining,
                OffsetDateTime stoppedAt, String stopReason) { }
    record Overview(List<Department> departments, List<Doctor> doctors, List<Slot> slots) { }
    record NameInput(@NotBlank @Size(max = 100) String name) { }
    record DoctorInput(@NotBlank @Size(max = 80) String username, @Positive long departmentId,
                       @NotBlank @Size(max = 100) String displayName) { }
    record SlotInput(@Positive long doctorId, @NotNull OffsetDateTime startsAt, @NotNull OffsetDateTime endsAt,
                     @Positive @Max(1000) int capacity) { }
}
