package app.carecontinuo.agent;

import app.carecontinuo.identity.Accounts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class AgentController {
    private final Accounts accounts;
    private final AgentJobs jobs;

    AgentController(Accounts accounts, AgentJobs jobs) {
        this.accounts = accounts;
        this.jobs = jobs;
    }

    @PostMapping("/episodes/{id}/agent-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    AgentJobs.View create(Principal principal, @PathVariable long id) {
        return jobs.create(accounts.actor(principal), id);
    }

    @GetMapping("/episodes/{id}/agent-jobs")
    List<AgentJobs.View> list(Principal principal, @PathVariable long id) {
        return jobs.list(accounts.actor(principal), id);
    }

    @PostMapping("/agent-jobs/{id}/resolve-conflict")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resolveConflict(Principal principal, @PathVariable long id, @Valid @RequestBody Resolution input) {
        jobs.resolveConflict(accounts.actor(principal), id, input.reason());
    }

    @GetMapping("/agent-jobs/{id}")
    AgentJobs.View get(Principal principal, @PathVariable long id) {
        return jobs.get(accounts.actor(principal), id);
    }

    record Resolution(@NotBlank @Size(max = 2000) String reason) { }
}
