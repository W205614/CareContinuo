package app.carecontinuo.followup;

import app.carecontinuo.identity.Accounts;
import app.carecontinuo.scheduling.Appointments;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/followups")
class FollowupController {
    private final Accounts accounts;
    private final Followups followups;
    private final Revisits revisits;

    FollowupController(Accounts accounts, Followups followups, Revisits revisits) {
        this.accounts = accounts;
        this.followups = followups;
        this.revisits = revisits;
    }

    @GetMapping
    List<Followups.View> list(Principal principal) {
        return followups.list(accounts.actor(principal));
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
}
