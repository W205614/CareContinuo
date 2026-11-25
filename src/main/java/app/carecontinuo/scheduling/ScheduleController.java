package app.carecontinuo.scheduling;

import app.carecontinuo.identity.Accounts;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "排班与门诊预约")
class ScheduleController {
    private final Accounts accounts;
    private final Schedule schedule;
    private final Appointments appointments;

    ScheduleController(Accounts accounts, Schedule schedule, Appointments appointments) {
        this.accounts = accounts;
        this.schedule = schedule;
        this.appointments = appointments;
    }

    @GetMapping("/schedule")
    Schedule.Overview catalog(Principal principal) {
        return schedule.catalog(accounts.actor(principal));
    }

    @GetMapping("/schedule-slots")
    Schedule.SlotPage page(Principal principal, @RequestParam(required = false) @Positive Long departmentId,
                           @RequestParam(required = false) OffsetDateTime from,
                           @RequestParam(required = false) OffsetDateTime to,
                           @RequestParam(required = false) @Positive Long afterId) {
        return schedule.page(accounts.actor(principal), departmentId, from, to, afterId);
    }

    @PostMapping("/departments")
    Schedule.Department department(Principal principal, @Valid @RequestBody Schedule.NameInput input) {
        return schedule.department(accounts.actor(principal), input.name());
    }

    @PostMapping("/doctors")
    Schedule.Doctor doctor(Principal principal, @Valid @RequestBody Schedule.DoctorInput input) {
        return schedule.doctor(accounts.actor(principal), input);
    }

    @PostMapping("/schedule-slots")
    @ResponseStatus(HttpStatus.CREATED)
    Schedule.Slot create(Principal principal, @Valid @RequestBody Schedule.SlotInput input) {
        return schedule.createSlot(accounts.actor(principal), input);
    }

    @PostMapping("/schedule-slots/stop-preview")
    List<Appointments.StopImpact> previewStop(Principal principal, @Valid @RequestBody StopSelection input) {
        return appointments.previewStop(accounts.actor(principal), input.slotIds());
    }

    @PostMapping("/schedule-slots/stop-batch")
    List<Appointments.StopResult> stopBatch(Principal principal, @Valid @RequestBody StopBatch input) {
        return appointments.stopSelection(accounts.actor(principal), input.slotIds(), input.reason().strip());
    }

    @PostMapping("/schedule-slots/{id}/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void stop(Principal principal, @PathVariable long id, @Valid @RequestBody Reason input) {
        appointments.stop(accounts.actor(principal), id, input.reason().strip());
    }

    @GetMapping("/appointments")
    List<Appointments.Appointment> list(Principal principal, @RequestParam(defaultValue = "false") boolean actionable) {
        return appointments.list(accounts.actor(principal), actionable);
    }

    @GetMapping("/appointments/history")
    Appointments.AppointmentPage appointmentHistory(Principal principal, @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) @Positive Long afterId) {
        return appointments.history(accounts.actor(principal), status, afterId);
    }

    @GetMapping("/appointments/stopped/page")
    Appointments.StopPage stoppedPage(Principal principal, @RequestParam(defaultValue = "false") boolean processed,
                                      @RequestParam(required = false) OffsetDateTime afterStart,
                                      @RequestParam(required = false) @Positive Long afterId) {
        return appointments.stoppedPage(accounts.actor(principal), processed, afterStart, afterId);
    }

    @GetMapping("/appointments/stopped")
    List<Appointments.StopFollowup> stopped(Principal principal, @RequestParam(defaultValue = "false") boolean processed,
                                           @RequestParam(required = false) Long appointmentId) {
        return appointments.stopped(accounts.actor(principal), processed, appointmentId);
    }

    @GetMapping("/appointments/{id}/callback-status")
    Appointments.CallbackProgress callbackProgress(Principal principal, @PathVariable long id) {
        return appointments.callbackProgress(accounts.actor(principal), id);
    }

    @GetMapping("/appointments/{id}/stop-contacts")
    List<Appointments.StopContact> stopContacts(Principal principal, @PathVariable long id) {
        return appointments.stopContacts(accounts.actor(principal), id);
    }

    @GetMapping("/appointments/{id}/stop-callback-assignees")
    List<Appointments.CallbackAssignee> callbackAssignees(Principal principal, @PathVariable long id) {
        return appointments.callbackAssignees(accounts.actor(principal), id);
    }

    @PostMapping("/appointments/{id}/stop-contacts")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void stopContact(Principal principal, @PathVariable long id, @Valid @RequestBody Appointments.StopContactInput input) {
        appointments.addStopContact(accounts.actor(principal), id, input);
    }

    @GetMapping("/appointments/{id}")
    Appointments.Appointment one(Principal principal, @PathVariable long id) {
        return appointments.details(accounts.actor(principal), id);
    }

    @PostMapping("/appointments")
    @ResponseStatus(HttpStatus.CREATED)
    Appointments.Change book(Principal principal, @RequestHeader("Idempotency-Key") String key,
                             @Valid @RequestBody Book input) {
        return appointments.book(accounts.actor(principal), input.slotId(), key);
    }

    @PostMapping("/appointments/{id}/cancel")
    Appointments.Change cancel(Principal principal, @PathVariable long id, @RequestHeader("Idempotency-Key") String key) {
        return appointments.cancel(accounts.actor(principal), id, key);
    }

    @PostMapping("/appointments/{id}/reschedule")
    Appointments.Change reschedule(Principal principal, @PathVariable long id,
                                   @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Book input) {
        return appointments.reschedule(accounts.actor(principal), id, input.slotId(), key);
    }

    @PostMapping("/appointments/{id}/check-in")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void checkIn(Principal principal, @PathVariable long id) {
        appointments.checkIn(accounts.actor(principal), id);
    }

    @PostMapping("/appointments/{id}/start")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void start(Principal principal, @PathVariable long id) {
        appointments.start(accounts.actor(principal), id);
    }

    @PostMapping("/appointments/{id}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void complete(Principal principal, @PathVariable long id, @Valid @RequestBody Complete input) {
        appointments.complete(accounts.actor(principal), id, input.note().strip(), input.returnPlan());
    }

    @PostMapping("/appointments/{id}/correct-attendance")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void correct(Principal principal, @PathVariable long id, @Valid @RequestBody Reason input) {
        appointments.correct(accounts.actor(principal), id, input.reason().strip());
    }

    record StopSelection(@NotNull @Size(min = 1, max = 20) List<@NotNull @Positive Long> slotIds) { }
    record StopBatch(@NotNull @Size(min = 1, max = 20) List<@NotNull @Positive Long> slotIds,
                     @NotBlank @Size(max = 400) String reason) { }
    record Reason(@NotBlank @Size(max = 400) String reason) { }
    record Book(@Positive long slotId) { }
    record Complete(@NotBlank @Size(max = 4000) String note, @Size(max = 2000) String returnPlan) { }
}
