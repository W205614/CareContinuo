package app.carecontinuo.identity;

import app.carecontinuo.followup.Followups;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
class StaffAdminController {
    private final Accounts accounts;
    private final StaffAdministration administration;
    private final Followups followups;

    StaffAdminController(Accounts accounts, StaffAdministration administration, Followups followups) {
        this.accounts = accounts;
        this.administration = administration;
        this.followups = followups;
    }

    @GetMapping("/departments")
    List<StaffAdministration.Department> departments(Principal principal) {
        return administration.departments(accounts.actor(principal));
    }

    @PostMapping("/departments")
    @ResponseStatus(HttpStatus.CREATED)
    StaffAdministration.Department createDepartment(Principal principal,
                                                   @Valid @RequestBody StaffAdministration.DepartmentInput input) {
        return administration.saveDepartment(accounts.actor(principal), null, input);
    }

    @PostMapping("/departments/{id}")
    StaffAdministration.Department updateDepartment(Principal principal, @PathVariable @Positive long id,
                                                   @Valid @RequestBody StaffAdministration.DepartmentInput input) {
        return administration.saveDepartment(accounts.actor(principal), id, input);
    }

    @GetMapping("/staff")
    StaffAdministration.StaffPage staff(Principal principal, @RequestParam(required = false) @Positive Long afterId) {
        return administration.people(accounts.actor(principal), afterId);
    }

    @PostMapping("/staff")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Long> createStaff(Principal principal, @Valid @RequestBody StaffAdministration.NewStaff input) {
        return Map.of("id", administration.create(accounts.actor(principal), input));
    }

    @PostMapping("/staff/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void updateStaff(Principal principal, @PathVariable @Positive long id,
                     @Valid @RequestBody StaffAdministration.StaffInput input) {
        administration.update(accounts.actor(principal), id, input);
    }

    @PostMapping("/staff/{id}/revoke-sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(Principal principal, @PathVariable @Positive long id) {
        administration.revoke(accounts.actor(principal), id);
    }

    @GetMapping("/assignments")
    Followups.AssignmentPage assignments(Principal principal, @RequestParam @Positive long departmentId,
                                         @RequestParam(required = false) @Positive Long afterId) {
        return followups.unassigned(accounts.actor(principal), departmentId, afterId);
    }

    @PostMapping("/followups/{id}/reassign")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reassign(Principal principal, @PathVariable @Positive long id,
                  @Valid @RequestBody Followups.Reassignment input) {
        followups.reassign(accounts.actor(principal), id, input);
    }
}
