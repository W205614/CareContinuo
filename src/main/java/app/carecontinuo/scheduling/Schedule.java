package app.carecontinuo.scheduling;

import app.carecontinuo.common.Problems;
import app.carecontinuo.followup.RevisitWindow;
import app.carecontinuo.identity.Accounts;
import app.carecontinuo.identity.Accounts.Actor;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Schedule {
    static final String SLOT_VIEW = """
            select s.id, s.doctor_id, d.display_name as doctor_name, p.name as department,
                   d.user_id as doctor_user_id, p.id as department_id, s.starts_at, s.ends_at, s.capacity,
                   s.remaining, s.stopped_at, s.stop_reason, s.visit_location, s.preparation, p.enabled and u.enabled
                       and exists(select 1 from user_role r where r.user_id = u.id and r.role = 'DOCTOR') as available,
                   exists(select 1 from staff_department sd where sd.user_id = u.id
                          and sd.department_id = s.department_id) as doctor_in_department
            from schedule_slot s join doctor d on s.doctor_id = d.id join department p on s.department_id = p.id
            join app_user u on u.id = d.user_id
            """;

    private final JdbcClient jdbc;
    static final String STAFF_SCOPE = """
            (:demo or exists(select 1 from staff_department sd
                             where sd.user_id = :staff and sd.department_id = s.department_id))
            """;
    private static final String BOOKABLE = """
            s.stopped_at is null and p.enabled and u.enabled and s.starts_at > :cutoff
            and exists(select 1 from user_role r where r.user_id = u.id and r.role = 'DOCTOR')
            and (:demo or exists(select 1 from staff_department sd where sd.user_id = u.id
                                and sd.department_id = s.department_id))
            """;
    private static final String REVISIT_WINDOW = BOOKABLE + """
            and (cast(:excluded as bigint) is null or s.id <> :excluded)
            and (cast(:department as bigint) is null or s.department_id = :department
                and (s.starts_at at time zone 'Asia/Shanghai')::date between :first and :last)
            """;
    private final Clock clock;
    private final Accounts accounts;

    Schedule(JdbcClient jdbc, Clock clock, Accounts accounts) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.accounts = accounts;
    }

    Overview catalog(Actor actor) {
        boolean operations = actor.has("OPERATIONS");
        var departments = jdbc.sql("select id, name from department order by name").query(Department.class).list();
        var doctors = jdbc.sql("""
                select id, department_id, display_name from doctor order by display_name
                """).query(Doctor.class).list();
        var slots = operations
                ? scoped(jdbc.sql(SLOT_VIEW + " where " + STAFF_SCOPE + " order by starts_at desc, s.id desc limit 150"), actor)
                        .query(Slot.class).list()
                : jdbc.sql(SLOT_VIEW + " where " + BOOKABLE + " order by starts_at, s.id limit 150")
                        .param("cutoff", bookingCutoff()).param("demo", actor.demo())
                        .query(Slot.class).list();
        return new Overview(departments, doctors, slots);
    }

    SlotPage page(Actor actor, Long departmentId, OffsetDateTime from, OffsetDateTime to, Long afterId) {
        if (from != null && to != null && !from.isBefore(to)) throw Problems.invalid("日期区间无效。");
        boolean operations = actor.has("OPERATIONS");
        var query = jdbc.sql(SLOT_VIEW + " where " + (operations ? STAFF_SCOPE : BOOKABLE) + """
                and s.id > :cursor
                and (cast(:department as bigint) is null or s.department_id = :department)
                and (cast(:fromTime as timestamptz) is null or s.starts_at >= :fromTime)
                and (cast(:toTime as timestamptz) is null or s.starts_at < :toTime)
                order by s.id limit 51
                """).param("cursor", afterId == null ? 0L : afterId).param("department", departmentId)
                .param("fromTime", from).param("toTime", to);
        if (operations) scoped(query, actor);
        else query.param("cutoff", bookingCutoff()).param("demo", actor.demo());
        var rows = query.query(Slot.class).list();
        return new SlotPage(rows.stream().limit(50).toList(), rows.size() > 50 ? rows.get(49).id() : null);
    }

    public RevisitSlotPage revisitSlots(Actor actor, RevisitWindow window, Long excludedSlot, Long afterId,
                                        RevisitFilters filters) {
        var rows = scopedRevisit(SLOT_VIEW + " where " + REVISIT_WINDOW + """
                and s.remaining > 0
                and (cast(:cursor as bigint) is null or (s.starts_at, s.id) >
                    ((select starts_at from schedule_slot where id = :cursor), :cursor))
                and (cast(:date as date) is null or (s.starts_at at time zone 'Asia/Shanghai')::date = :date)
                and (cast(:doctor as text) is null or strpos(lower(d.display_name), lower(:doctor)) > 0)
                and (cast(:period as text) is null or
                    ((s.starts_at at time zone 'Asia/Shanghai')::time < time '12:00') = (:period = 'AM'))
                order by s.starts_at, s.id limit 51
                """, actor, window, excludedSlot).param("cursor", afterId).param("date", filters.date())
                .param("doctor", filters.doctor()).param("period", filters.period() == null ? null : filters.period().name())
                .query(Slot.class).list();
        var availability = Availability.AVAILABLE;
        if (rows.isEmpty()) {
            if (afterId != null || filters.active()) {
                availability = Availability.NO_MATCH;
            } else {
                boolean released = scopedRevisit("select exists (" + SLOT_VIEW + " where " + REVISIT_WINDOW + ")",
                        actor, window, excludedSlot).query(Boolean.class).single();
                availability = released ? Availability.FULL : Availability.NOT_RELEASED;
            }
        }
        return new RevisitSlotPage(rows.stream().limit(50).toList(), rows.size() > 50 ? rows.get(49).id() : null, availability);
    }

    private JdbcClient.StatementSpec scopedRevisit(String sql, Actor actor, RevisitWindow window, Long excludedSlot) {
        return jdbc.sql(sql).param("cutoff", bookingCutoff()).param("demo", actor.demo()).param("excluded", excludedSlot)
                .param("department", window.revisitDepartmentId()).param("first", window.revisitFrom()).param("last", window.revisitTo());
    }

    public enum DayPeriod { AM, PM }
    public enum Availability { AVAILABLE, FULL, NOT_RELEASED, NO_MATCH }
    public record RevisitFilters(LocalDate date, String doctor, DayPeriod period) {
        public RevisitFilters {
            doctor = doctor == null || doctor.isBlank() ? null : doctor.strip();
            if (doctor != null && doctor.length() > 100) throw Problems.invalid("医生姓名筛选过长。");
        }

        boolean active() {
            return date != null || doctor != null || period != null;
        }
    }

    public record RevisitSlotPage(List<Slot> slots, Long nextCursor, Availability availability) { }

    private OffsetDateTime bookingCutoff() {
        return OffsetDateTime.now(clock).plusMinutes(AppointmentWindow.BOOKING_CUTOFF_MINUTES);
    }

    static JdbcClient.StatementSpec scoped(JdbcClient.StatementSpec sql, Actor actor) {
        return sql.param("demo", actor.demo()).param("staff", actor.id());
    }

    Department department(Actor actor, String name) {
        requireDemoOperations(actor);
        return jdbc.sql("""
                insert into department(name) values (?) on conflict(name) do update set name = excluded.name
                returning id, name
                """).param(name.strip()).query(Department.class).single();
    }

    Doctor doctor(Actor actor, DoctorInput input) {
        requireDemoOperations(actor);
        if (!jdbc.sql("select exists(select 1 from department where id = ? and enabled)").param(input.departmentId())
                .query(Boolean.class).single()) {
            throw Problems.missing();
        }
        return jdbc.sql("""
                insert into doctor(user_id, department_id, display_name)
                select u.id, ?, ? from app_user u join user_role r on r.user_id = u.id
                where u.username = ? and r.role = 'DOCTOR' and u.enabled and (? or
                    exists(select 1 from staff_department sd where sd.user_id = u.id and sd.department_id = ?))
                on conflict(user_id) do update set department_id = excluded.department_id,
                    display_name = excluded.display_name
                returning id, department_id, display_name
                """).params(input.departmentId(), input.displayName().strip(), input.username().strip(), actor.demo(), input.departmentId())
                .query(Doctor.class).optional().orElseThrow(Problems::missing);
    }

    @Transactional
    Slot createSlot(Actor actor, SlotInput input) {
        requireOperations(actor);
        if (!input.endsAt().isAfter(input.startsAt()) || !input.startsAt().isAfter(OffsetDateTime.now(clock))) {
            throw Problems.invalid("出诊结束时间应晚于开始时间，开始时间应在未来。");
        }
        try {
            long departmentId = jdbc.sql("""
                    select d.department_id from doctor d join department p on p.id = d.department_id
                    join app_user u on u.id = d.user_id
                    where d.id = ? and p.enabled and u.enabled
                    and exists(select 1 from user_role r where r.user_id = u.id and r.role = 'DOCTOR')
                    for share of d, p
                    """).param(input.doctorId()).query(Long.class).optional().orElseThrow(Problems::missing);
            requireOperations(accounts.actorById(actor.id()), departmentId);
            long id = jdbc.sql("""
                    insert into schedule_slot(doctor_id, department_id, starts_at, ends_at, capacity, remaining, visit_location, preparation)
                    values (?, ?, ?, ?, ?, ?, nullif(btrim(?), ''), nullif(btrim(?), '')) returning id
                    """).param(input.doctorId()).param(departmentId).param(input.startsAt()).param(input.endsAt())
                    .param(input.capacity()).param(input.capacity()).param(input.visitLocation()).param(input.preparation())
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

    static void requireOperations(Actor actor, long departmentId) {
        requireOperations(actor);
        if (!actor.inDepartment(departmentId)) throw Problems.missing();
    }

    private static void requireDemoOperations(Actor actor) {
        if (!actor.demo() || !actor.has("OPERATIONS")) throw Problems.forbidden();
    }

    public record SlotPage(List<Slot> slots, Long nextCursor) { }
    record Department(long id, String name) { }
    record Doctor(long id, long departmentId, String displayName) { }
    public record Slot(long id, long doctorId, String doctorName, String department, long doctorUserId, long departmentId,
                OffsetDateTime startsAt, OffsetDateTime endsAt, int capacity, int remaining,
                OffsetDateTime stoppedAt, String stopReason, String visitLocation, String preparation,
                boolean available, boolean doctorInDepartment) { }
    record Overview(List<Department> departments, List<Doctor> doctors, List<Slot> slots) { }
    record NameInput(@NotBlank @Size(max = 100) String name) { }
    record DoctorInput(@NotBlank @Size(max = 80) String username, @Positive long departmentId,
                       @NotBlank @Size(max = 100) String displayName) { }
    record SlotInput(@Positive long doctorId, @NotNull OffsetDateTime startsAt, @NotNull OffsetDateTime endsAt,
                     @Positive @Max(1000) int capacity, @Size(max = 200) String visitLocation,
                     @Size(max = 1000) String preparation) { }
}
