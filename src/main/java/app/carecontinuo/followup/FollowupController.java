package app.carecontinuo.followup;

import app.carecontinuo.identity.Accounts;
import app.carecontinuo.scheduling.Appointments;
import app.carecontinuo.scheduling.Schedule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
@RequestMapping("/api/v1/followups")
class FollowupController {
    private final Accounts accounts;
    private final Followups followups;
    private final Revisits revisits;
    private final RevisitWatches watches;
    private final FollowupTemplates templates;

    FollowupController(Accounts accounts, Followups followups, Revisits revisits, RevisitWatches watches,
                       FollowupTemplates templates) {
        this.accounts = accounts;
        this.followups = followups;
        this.revisits = revisits;
        this.watches = watches;
        this.templates = templates;
    }

    @GetMapping
    List<Followups.View> list(Principal principal, @RequestParam(defaultValue = "false") boolean actionable) {
        return followups.list(accounts.actor(principal), actionable);
    }

    @GetMapping("/templates")
    List<FollowupTemplates.Template> templates(Principal principal, @RequestParam @Positive long departmentId) {
        return templates.list(accounts.actor(principal), departmentId);
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    FollowupTemplates.Template template(Principal principal, @Valid @RequestBody FollowupTemplates.Input input) {
        return templates.create(accounts.actor(principal), input);
    }

    @GetMapping("/history")
    Followups.History history(Principal principal, @RequestParam(required = false) Boolean closed,
                              @RequestParam(required = false) Followups.Kind kind,
                              @RequestParam(required = false) @Positive Long afterId) {
        return followups.history(accounts.actor(principal), closed, kind, afterId);
    }

    @GetMapping("/{id}")
    Followups.View get(Principal principal, @PathVariable long id) {
        return followups.get(accounts.actor(principal), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Long> create(Principal principal, @Valid @RequestBody Create input) {
        return Map.of("id", followups.create(accounts.actor(principal), input.episodeId(), input.reviewerId(), input.draft()));
    }

    @PostMapping("/{id}/draft")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void edit(Principal principal, @PathVariable long id, @Valid @RequestBody Edit input) {
        followups.edit(accounts.actor(principal), id, input.version(), input.draft());
    }

    @PostMapping("/{id}/actions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void act(Principal principal, @PathVariable long id, @Valid @RequestBody Followups.ActionInput input) {
        followups.act(accounts.actor(principal), id, input);
    }

    @GetMapping("/{id}/revisit-slots")
    Schedule.RevisitSlotPage revisitSlots(Principal principal, @PathVariable long id,
                                           @RequestParam(required = false) Long appointmentId,
                                           @RequestParam(required = false) Long afterId,
                                           @RequestParam(required = false) LocalDate date,
                                           @RequestParam(required = false) String doctor,
                                           @RequestParam(required = false) Schedule.DayPeriod period) {
        return followups.revisitSlots(accounts.actor(principal), id, appointmentId, afterId,
                new Schedule.RevisitFilters(date, doctor, period));
    }

    @GetMapping("/{id}/revisit-watch")
    RevisitWatches.View watch(Principal principal, @PathVariable long id) {
        return watches.current(accounts.actor(principal), id);
    }

    @PostMapping("/{id}/revisit-watch")
    RevisitWatches.View watch(Principal principal, @PathVariable long id, @Valid @RequestBody WatchInput input) {
        return watches.start(accounts.actor(principal), id, input.appointmentId());
    }

    @PostMapping("/{id}/revisit-watch/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancelWatch(Principal principal, @PathVariable long id) {
        watches.cancel(accounts.actor(principal), id);
    }

    @PostMapping("/{id}/requests")
    Map<String, Long> submitRequest(Principal principal, @PathVariable long id,
                                    @Valid @RequestBody FollowupRequests.Input input) {
        return Map.of("id", followups.submitRequest(accounts.actor(principal), id, input));
    }

    @PostMapping("/{id}/requests/{requestId}/actions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void handleRequest(Principal principal, @PathVariable long id, @PathVariable long requestId,
                       @Valid @RequestBody FollowupRequests.Handling input) {
        followups.handleRequest(accounts.actor(principal), id, requestId, input);
    }

    @PostMapping("/{id}/result-updates")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void updateResult(Principal principal, @PathVariable long id, @Valid @RequestBody Followups.ResultInput input) {
        followups.updateResult(accounts.actor(principal), id, input);
    }

    @GetMapping("/{id}/appointments")
    List<Revisits.LinkedVisit> linkedVisits(Principal principal, @PathVariable long id) {
        return revisits.list(accounts.actor(principal), id);
    }

    @PostMapping("/{id}/appointments")
    @ResponseStatus(HttpStatus.CREATED)
    Appointments.Change book(Principal principal, @PathVariable long id, @RequestHeader("Idempotency-Key") String key,
                             @Valid @RequestBody Revisit input) {
        return revisits.book(accounts.actor(principal), id, input.slotId(), key);
    }

    record Create(@Positive long episodeId, @Positive long reviewerId, @NotNull @Valid Followups.Draft draft) { }
    record Edit(@Positive int version, @NotNull @Valid Followups.Draft draft) { }
    record Revisit(@Positive long slotId) { }
    record WatchInput(@Positive Long appointmentId) { }
}
