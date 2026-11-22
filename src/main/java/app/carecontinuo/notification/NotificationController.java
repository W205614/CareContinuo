package app.carecontinuo.notification;

import app.carecontinuo.identity.Accounts;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class NotificationController {
    private final Accounts accounts;
    private final Notifications notifications;

    NotificationController(Accounts accounts, Notifications notifications) {
        this.accounts = accounts;
        this.notifications = notifications;
    }

    @GetMapping("/inbox")
    List<Notifications.Message> inbox(Principal principal) {
        return notifications.inbox(accounts.actor(principal));
    }

    @GetMapping("/inbox/page")
    Notifications.MessagePage inboxPage(Principal principal, @RequestParam(required = false) Boolean read,
                                        @RequestParam(required = false) Notifications.MessageGroup group,
                                        @RequestParam(required = false) Long afterId) {
        return notifications.inboxPage(accounts.actor(principal), read, group, afterId);
    }

    @GetMapping("/inbox/highlights")
    List<Notifications.Message> unreadHighlights(Principal principal) {
        return notifications.unreadHighlights(accounts.actor(principal));
    }

    @GetMapping("/inbox/revisit-replies")
    List<Notifications.Message> unreadRevisitReplies(Principal principal) {
        return notifications.unreadRevisitReplies(accounts.actor(principal));
    }

    @GetMapping("/inbox/followup-progress")
    List<Notifications.Message> unreadFollowupProgress(Principal principal) {
        return notifications.unreadFollowupProgress(accounts.actor(principal));
    }

    @GetMapping("/inbox/{id}")
    Notifications.Message message(Principal principal, @PathVariable long id) {
        return notifications.message(accounts.actor(principal), id);
    }

    @PostMapping("/inbox/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(Principal principal, @PathVariable long id) {
        notifications.read(accounts.actor(principal), id);
    }

    @GetMapping("/work-items")
    List<Notifications.Work> work(Principal principal) {
        return notifications.work(accounts.actor(principal));
    }

    @GetMapping("/work-items/page")
    Notifications.WorkPage workPage(Principal principal, @RequestParam(required = false) Long departmentId,
                                     @RequestParam(required = false) String state,
                                     @RequestParam(required = false) String kind,
                                     @RequestParam(required = false) Long afterId) {
        return notifications.workPage(accounts.actor(principal), departmentId, state, kind, afterId);
    }

    @GetMapping("/work-items/{id}")
    Notifications.Work workItem(Principal principal, @PathVariable long id) {
        return notifications.workItem(accounts.actor(principal), id);
    }

    @PostMapping("/work-items/{id}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void retry(Principal principal, @PathVariable long id) {
        notifications.retry(accounts.actor(principal), id);
    }
}
