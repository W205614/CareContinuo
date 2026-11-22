package app.carecontinuo.followup;

import app.carecontinuo.care.Episodes;
import app.carecontinuo.common.Problems;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class RevisitScheduling {
    private final JdbcClient jdbc;
    private final Episodes episodes;
    private final FollowupRequests requests;

    RevisitScheduling(JdbcClient jdbc, Episodes episodes, FollowupRequests requests) {
        this.jdbc = jdbc;
        this.episodes = episodes;
        this.requests = requests;
    }

    // Linked appointments are visible only after the original booking transaction commits.
    // Take the episode lock before the slot locks so corrections cannot race this check.
    public RevisitWindow lockWindowForReschedule(long appointmentId) {
        var episodeId = jdbc.sql("""
                select t.episode_id from followup_appointment l join followup_task t on t.id = l.task_id
                where l.appointment_id = ?
                """).param(appointmentId).query(Long.class).optional();
        if (episodeId.isEmpty()) return null;
        episodes.lock(episodeId.get());
        var linked = jdbc.sql("""
                select t.needs_review, r.revisit_from, r.revisit_to, r.revisit_department_id
                from followup_appointment l join followup_task t on t.id = l.task_id
                join followup_revision r on r.id = t.published_revision_id where l.appointment_id = ?
                """).param(appointmentId).query(LinkedPlan.class).single();
        if (linked.needsReview()) throw Problems.conflict("随访安排正在复核，可取消原预约，待更正版派发后再改约。");
        return new RevisitWindow(linked.revisitFrom(), linked.revisitTo(), linked.revisitDepartmentId());
    }

    public void completeAssistance(long appointmentId, long actorId) {
        jdbc.sql("select task_id from followup_appointment where appointment_id = ?").param(appointmentId)
                .query(Long.class).optional().ifPresent(taskId -> requests.finish(taskId, actorId, true));
    }

    private record LinkedPlan(boolean needsReview, LocalDate revisitFrom, LocalDate revisitTo, Long revisitDepartmentId) { }
}
