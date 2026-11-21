package app.carecontinuo.care;

import app.carecontinuo.audit.AuditLog;
import app.carecontinuo.common.Problems;
import app.carecontinuo.identity.Accounts.Actor;
import app.carecontinuo.notification.Notifications;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class Episodes {
    private static final String INFO = """
            select e.id, e.patient_id, p.display_name as patient, e.owner_id, e.kind, e.reference,
                   e.care_date, e.source_version
            from care_episode e join patient p on p.id = e.patient_id
            """;
    private static final String CAN_SEE = """
            (e.owner_id = :actor and :followup or exists (select 1 from followup_task t where t.episode_id = e.id
            and (t.owner_id = :actor and :followup or t.reviewer_id = :actor and :reviewer))
            or (:doctor and exists (select 1 from encounter visit join doctor d on d.id = visit.doctor_id
            where visit.id = e.encounter_id and d.user_id = :actor)))
            """;

    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final Notifications notices;
    private final Clock clock;

    Episodes(JdbcClient jdbc, AuditLog audit, Notifications notices, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.notices = notices;
        this.clock = clock;
    }

    public List<Episode> list(Actor actor) {
        requireClinical(actor);
        return entitled(INFO + " where " + CAN_SEE + " order by e.id desc limit 100", actor)
                .query(Episode.class).list();
    }

    public Episode authorized(Actor actor, long id) {
        requireClinical(actor);
        return entitled(INFO + " where e.id = :episode and " + CAN_SEE, actor).param("episode", id)
                .query(Episode.class).optional().orElseThrow(Problems::missing);
    }

    public Episode lock(long id) {
        return jdbc.sql(INFO + " where e.id = ? for update of e").param(id).query(Episode.class)
                .optional().orElseThrow(Problems::missing);
    }

    public Directory directory(Actor actor) {
        requireClinical(actor);
        var patients = actor.has("FOLLOWUP")
                ? jdbc.sql("select id, display_name as name from patient order by id limit 100").query(Choice.class).list()
                : List.<Choice>of();
        var reviewers = accountChoices("REVIEWER");
        var owners = accountChoices("FOLLOWUP");
        return new Directory(patients, reviewers, owners);
    }

    public void requireAccountRole(long id, String role) {
        boolean recognized = jdbc.sql("""
                select exists(select 1 from app_user u join user_role r on r.user_id = u.id
                where u.id = ? and u.enabled and r.role = ?)
                """).params(id, role).query(Boolean.class).single();
        if (!recognized) throw Problems.invalid("该账号不具备所需的业务权限。");
    }

    @Transactional
    public long registerDischarge(Actor actor, long patientId, String reference, LocalDate dischargeDate) {
        if (!actor.has("FOLLOWUP")) throw Problems.forbidden();
        try {
            long id = jdbc.sql("""
                    insert into care_episode(patient_id, owner_id, kind, reference, care_date)
                    select id, ?, 'DISCHARGE', ?, ? from patient where id = ? returning id
                    """).params(actor.id(), reference, dischargeDate, patientId).query(Long.class)
                    .optional().orElseThrow(Problems::missing);
            audit.record(actor.id(), "EPISODE", id, "REGISTER_DISCHARGE");
            return id;
        } catch (DuplicateKeyException conflict) {
            throw Problems.conflict("该出院记录编号已登记。");
        }
    }

    @Transactional
    public long registerOutpatient(Actor actor, long appointmentId, long ownerId) {
        if (!actor.has("DOCTOR")) throw Problems.missing();
        var completed = jdbc.sql("""
                select v.id, a.patient_id, v.return_plan, (v.completed_at at time zone 'Asia/Shanghai')::date as care_date
                from encounter v join appointment a on a.id = v.appointment_id join doctor d on d.id = v.doctor_id
                where a.id = ? and d.user_id = ? and v.completed_at is not null for update of v
                """).params(appointmentId, actor.id()).query(CompletedVisit.class).optional().orElseThrow(Problems::missing);
        var existing = jdbc.sql("select id from care_episode where encounter_id = ?")
                .param(completed.id()).query(Long.class).optional();
        if (existing.isPresent()) return existing.get();
        if (completed.returnPlan() == null || completed.returnPlan().isBlank()) {
            throw Problems.conflict("请先在接诊记录中填写复诊安排。");
        }
        requireAccountRole(ownerId, "FOLLOWUP");
        long id = jdbc.sql("""
                insert into care_episode(patient_id, owner_id, encounter_id, kind, reference, care_date)
                values (?, ?, ?, 'OUTPATIENT', ?, ?) returning id
                """).params(completed.patientId(), ownerId, completed.id(), "OP-" + appointmentId, completed.careDate())
                .query(Long.class).single();
        store(actor, id, null, "门诊复诊安排", DocumentText.extract("outpatient-return.txt",
                completed.returnPlan().getBytes(StandardCharsets.UTF_8)));
        audit.record(actor.id(), "EPISODE", id, "REGISTER_OUTPATIENT_HANDOFF");
        return id;
    }

    public List<Document> documents(Actor actor, long id) {
        authorized(actor, id);
        audit.record(actor.id(), "EPISODE", id, "READ_DOCUMENTS");
        return jdbc.sql("""
                select s.id, s.title, v.id as version_id, v.version_number, v.filename, v.created_at
                from source_document s join document_version v on s.id = v.document_id
                where s.episode_id = ? order by s.id, v.version_number desc
                """).param(id).query(Document.class).list();
    }

    public List<Segment> segments(Actor actor, long id) {
        authorized(actor, id);
        audit.record(actor.id(), "EPISODE", id, "READ_DOCUMENT_TEXT");
        return currentSegments(id);
    }

    public List<Segment> currentSegments(long id) {
        return jdbc.sql("""
                select g.id, s.title, v.id as version_id, v.version_number, g.page_number, g.line_number, g.excerpt
                from source_document s join lateral (select id, version_number from document_version
                    where document_id = s.id order by version_number desc limit 1) v on true
                join document_segment g on g.version_id = v.id
                where s.episode_id = ? order by s.id, g.id
                """).param(id).query(Segment.class).list();
    }

    public Attachment download(Actor actor, long episodeId, long versionId) {
        authorized(actor, episodeId);
        var attachment = jdbc.sql("""
                select v.filename, v.media_type, v.content from document_version v
                join source_document s on s.id = v.document_id where s.episode_id = ? and v.id = ?
                """).params(episodeId, versionId).query(Attachment.class).optional().orElseThrow(Problems::missing);
        audit.record(actor.id(), "DOCUMENT_VERSION", versionId, "DOWNLOAD");
        return attachment;
    }

    @Transactional
    public long addText(Actor actor, long id, Long documentId, String title, String content) {
        requireEditor(actor, id);
        return store(actor, id, documentId, title,
                DocumentText.extract("合成出院资料.txt", content.getBytes(StandardCharsets.UTF_8)));
    }

    @Transactional
    public long addFile(Actor actor, long id, Long documentId, String title, MultipartFile upload) throws IOException {
        requireEditor(actor, id);
        String name = upload.getOriginalFilename();
        if (name == null || name.isBlank() || upload.isEmpty()) throw Problems.invalid("请选择一个资料文件。");
        return store(actor, id, documentId, title, DocumentText.extract(name, upload.getBytes()));
    }

    private void requireEditor(Actor actor, long id) {
        if (!actor.has("FOLLOWUP")) throw Problems.forbidden();
        lock(id);
        authorized(actor, id);
    }

    private long store(Actor actor, long episodeId, Long documentId, String title, DocumentText.Extracted extracted) {
        long source;
        if (documentId == null) {
            source = jdbc.sql("insert into source_document(episode_id, title) values (?, ?) returning id")
                    .params(episodeId, title).query(Long.class).single();
        } else {
            source = jdbc.sql("select id from source_document where episode_id = ? and id = ?")
                    .params(episodeId, documentId).query(Long.class).optional().orElseThrow(Problems::missing);
        }
        long version = jdbc.sql("""
                insert into document_version(document_id, version_number, filename, media_type, content, author_id)
                values (?, (select coalesce(max(version_number), 0) + 1 from document_version where document_id = ?),
                    ?, ?, ?, ?) returning id
                """).params(source, source, extracted.filename(), extracted.mediaType(), extracted.content(), actor.id())
                .query(Long.class).single();
        for (var segment : extracted.segments()) {
            jdbc.sql("insert into document_segment(version_id, page_number, line_number, excerpt) values (?, ?, ?, ?)")
                    .param(version).param(segment.pageNumber()).param(segment.lineNumber()).param(segment.excerpt()).update();
        }
        jdbc.sql("update care_episode set source_version = source_version + 1 where id = ?").param(episodeId).update();
        invalidate(episodeId);
        audit.record(actor.id(), "DOCUMENT_VERSION", version, documentId == null ? "IMPORT" : "CORRECT");
        return version;
    }

    private void invalidate(long episodeId) {
        jdbc.sql("""
                update work_item w set status = 'DONE', resolved_at = now(), resolution = '资料版本已更新'
                from agent_job j where w.kind = 'AGENT_CONFLICT' and w.status = 'OPEN' and w.object_id = j.id
                  and j.episode_id = ? and j.source_version < (select source_version from care_episode where id = ?)
                """).params(episodeId, episodeId).update();
        var tasks = jdbc.sql("""
                update followup_task set status = 'DRAFT', needs_review = published_revision_id is not null
                where episode_id = ? and status not in ('CLOSED', 'VERIFIED') returning id, owner_id
                """).param(episodeId).query(Invalidated.class).list();
        var now = OffsetDateTime.now(clock);
        for (var task : tasks) {
            notices.closeWork("FOLLOWUP_REVIEW", task.id(), null, "资料版本已更新");
            notices.closeWork("FOLLOWUP_OVERDUE", task.id(), null, "资料版本已更新");
            jdbc.sql("""
                    update inbox_message as m set corrected_at = coalesce(m.corrected_at, ?)
                    from outbox_event e where m.event_id = e.id and e.episode_id = ? and e.object_id = ?
                    """).params(now, episodeId, task.id()).update();
            jdbc.sql("""
                    update outbox_event set state = 'STALE' where episode_id = ? and object_id = ?
                    and state in ('PENDING', 'FAILED')
                    """).params(episodeId, task.id()).update();
            jdbc.sql("""
                    update work_item w set status = 'DONE', resolved_at = ?, resolution = '旧版本提醒失效'
                    from outbox_event e where w.kind = 'NOTIFICATION_FAILED' and w.object_id = e.id
                    and w.status = 'OPEN' and e.episode_id = ? and e.object_id = ? and e.state = 'STALE'
                    """).params(now, episodeId, task.id()).update();
            notices.openWork("SOURCE_CHANGED", task.id(), "FOLLOWUP", task.ownerId(), now);
        }
    }

    private List<Choice> accountChoices(String role) {
        return jdbc.sql("""
                select u.id, u.display_name as name from app_user u join user_role r on r.user_id = u.id
                where r.role = ? and u.enabled order by u.display_name, u.id
                """).param(role).query(Choice.class).list();
    }

    private JdbcClient.StatementSpec entitled(String sql, Actor actor) {
        return jdbc.sql(sql).param("actor", actor.id()).param("followup", actor.has("FOLLOWUP"))
                .param("reviewer", actor.has("REVIEWER")).param("doctor", actor.has("DOCTOR"));
    }

    private static void requireClinical(Actor actor) {
        if (!actor.has("FOLLOWUP") && !actor.has("REVIEWER") && !actor.has("DOCTOR")) throw Problems.missing();
    }

    public record Episode(long id, long patientId, String patient, long ownerId, String kind, String reference,
                          LocalDate careDate, int sourceVersion) { }
    public record Choice(long id, String name) { }
    public record Directory(List<Choice> patients, List<Choice> reviewers, List<Choice> owners) { }
    public record Segment(long id, String title, long versionId, int versionNumber, Integer pageNumber,
                          int lineNumber, String excerpt) { }
    public record Document(long id, String title, long versionId, int versionNumber, String filename,
                           OffsetDateTime createdAt) { }
    public record Attachment(String filename, String mediaType, byte[] content) { }
    private record Invalidated(long id, long ownerId) { }
    private record CompletedVisit(long id, long patientId, String returnPlan, LocalDate careDate) { }
}
