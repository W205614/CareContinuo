package app.carecontinuo.care;

import app.carecontinuo.audit.AuditLog;
import app.carecontinuo.common.Problems;
import app.carecontinuo.identity.Accounts;
import app.carecontinuo.identity.Accounts.Actor;
import app.carecontinuo.notification.Notifications;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class Episodes {
    private static final String INFO = """
            select e.id, e.patient_id, p.display_name as patient, e.owner_id, e.kind, e.reference,
                   e.care_date, e.source_version, e.department_id
            from care_episode e join patient p on p.id = e.patient_id
            """;
    private static final String CAN_SEE = """
            (:demo or exists(select 1 from staff_department sd
                where sd.user_id = :actor and sd.department_id = e.department_id))
            and (e.owner_id = :actor and :followup or exists (select 1 from followup_task t where t.episode_id = e.id
            and (t.owner_id = :actor and :followup and t.owner_department_id = e.department_id
                or t.reviewer_id = :actor and :reviewer))
            or (:doctor and exists (select 1 from encounter visit join doctor d on d.id = visit.doctor_id
            where visit.id = e.encounter_id and d.user_id = :actor)))
            """;

    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final Notifications notices;
    private final Clock clock;
    private final Accounts accounts;
    private final boolean syntheticDischarges;
    private final boolean pdfEnabled;

    Episodes(JdbcClient jdbc, AuditLog audit, Notifications notices, Clock clock, Accounts accounts,
             @Value("${care.synthetic-discharges.enabled:false}") boolean syntheticDischarges,
             @Value("${care.documents.pdf-enabled:false}") boolean pdfEnabled) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.notices = notices;
        this.clock = clock;
        this.accounts = accounts;
        this.syntheticDischarges = syntheticDischarges;
        this.pdfEnabled = pdfEnabled;
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

    public Episode tryLock(long id) {
        return jdbc.sql(INFO + " where e.id = ? for update of e skip locked")
                .param(id).query(Episode.class).optional().orElse(null);
    }

    public Directory directory(Actor actor) {
        requireClinical(actor);
        var reviewers = accountChoices(actor, "REVIEWER");
        var owners = accountChoices(actor, "FOLLOWUP");
        var departments = jdbc.sql("""
                select id, name from department d where enabled and (:demo or exists(
                    select 1 from staff_department sd where sd.user_id = :staff and sd.department_id = d.id))
                order by name
                """).param("demo", actor.demo()).param("staff", actor.id()).query(Choice.class).list();
        return new Directory(reviewers, owners, departments,
                syntheticDischarges && actor.has("FOLLOWUP"), pdfEnabled);
    }

    @Transactional
    public long registerDischarge(Actor actor, String patientReference, String patientName, String reference,
                                  LocalDate dischargeDate, Long selectedDepartment) {
        Actor current = accounts.actorById(actor.id());
        if (!syntheticDischarges || !current.has("FOLLOWUP")) throw Problems.forbidden();
        if (selectedDepartment == null && !current.demo()) throw Problems.invalid("请选择本科室出院资料。");
        long departmentId = jdbc.sql("""
                select id from department where enabled and
                ((cast(:department as bigint) is null and name = '全科门诊') or id = :department)
                """).param("department", selectedDepartment).query(Long.class).optional().orElseThrow(Problems::missing);
        if (!current.inDepartment(departmentId)) throw Problems.missing();
        try {
            long id = jdbc.sql("""
                    insert into care_episode(patient_id, owner_id, kind, reference, care_date, department_id)
                    select id, ?, 'DISCHARGE', ?, ?, ? from patient
                    where reference = ? and display_name = ? returning id
                    """).params(actor.id(), reference, dischargeDate, departmentId, patientReference, patientName)
                    .query(Long.class).optional().orElseThrow(() -> Problems.invalid("患者编号和姓名不匹配。"));
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
                select v.id, a.patient_id, v.return_plan, s.department_id,
                    (v.completed_at at time zone 'Asia/Shanghai')::date as care_date
                from encounter v join appointment a on a.id = v.appointment_id join doctor d on d.id = v.doctor_id
                join schedule_slot s on s.id = a.slot_id
                where a.id = ? and d.user_id = ? and v.completed_at is not null for update of v
                """).params(appointmentId, actor.id()).query(CompletedVisit.class).optional().orElseThrow(Problems::missing);
        var current = accounts.actorById(actor.id());
        if (!current.has("DOCTOR") || !current.inDepartment(completed.departmentId())) throw Problems.missing();
        var existing = jdbc.sql("select id from care_episode where encounter_id = ?")
                .param(completed.id()).query(Long.class).optional();
        if (existing.isPresent()) return existing.get();
        if (completed.returnPlan() == null || completed.returnPlan().isBlank()) {
            throw Problems.conflict("请先在接诊记录中填写复诊安排。");
        }
        accounts.requireStaffRole(ownerId, "FOLLOWUP", completed.departmentId());
        long id = jdbc.sql("""
                insert into care_episode(patient_id, owner_id, encounter_id, kind, reference, care_date, department_id)
                values (?, ?, ?, 'OUTPATIENT', ?, ?, ?) returning id
                """).params(completed.patientId(), ownerId, completed.id(), "OP-" + appointmentId,
                        completed.careDate(), completed.departmentId())
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
        return currentSegments(id, null);
    }

    public List<Segment> currentSegments(long id, Long citedByRevision) {
        return jdbc.sql("""
                select g.id, s.title, v.id as version_id, v.version_number, g.page_number, g.line_number, g.excerpt
                from source_document s join lateral (select id, version_number from document_version
                    where document_id = s.id order by version_number desc limit 1) v on true
                join document_segment g on g.version_id = v.id
                where s.episode_id = :episode and (cast(:revision as bigint) is null or exists (
                    select 1 from followup_revision_source r join document_segment prior on prior.id = r.segment_id
                    join document_version original on original.id = prior.version_id
                    where r.revision_id = :revision and original.document_id = s.id))
                order by s.id, g.id
                """).param("episode", id).param("revision", citedByRevision).query(Segment.class).list();
    }

    public SourceContext sourceContext(Actor actor, long episodeId, long segmentId) {
        authorized(actor, episodeId);
        var target = jdbc.sql("""
                select g.id, s.title, v.id as version_id, v.version_number, g.page_number, g.line_number, g.excerpt
                from document_segment g join document_version v on v.id = g.version_id
                join source_document s on s.id = v.document_id where s.episode_id = ? and g.id = ?
                """).params(episodeId, segmentId).query(Segment.class).optional().orElseThrow(Problems::missing);
        var lines = jdbc.sql("""
                select id, line_number, excerpt from document_segment
                where version_id = :version and page_number is not distinct from cast(:page as integer)
                  and (page_number is not null or line_number between :first and :last)
                order by line_number, id
                """).param("version", target.versionId()).param("page", target.pageNumber())
                .param("first", target.lineNumber() - 8).param("last", target.lineNumber() + 8)
                .query(SourceLine.class).list();
        audit.record(actor.id(), "DOCUMENT_VERSION", target.versionId(), "READ_SOURCE_CONTEXT");
        return new SourceContext(target, lines);
    }

    public Attachment download(Actor actor, long episodeId, long versionId) {
        authorized(actor, episodeId);
        var attachment = jdbc.sql("""
                select v.filename, v.media_type, v.content from document_version v
                join source_document s on s.id = v.document_id where s.episode_id = ? and v.id = ?
                """).params(episodeId, versionId).query(Attachment.class).optional().orElseThrow(Problems::missing);
        if (!pdfEnabled && attachment.mediaType().equals("application/pdf")) throw Problems.missing();
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
        if (!pdfEnabled && !name.toLowerCase(java.util.Locale.ROOT).endsWith(".txt")) {
            throw Problems.invalid("当前配置仅允许 UTF-8 文本附件。");
        }
        return store(actor, id, documentId, title, DocumentText.extract(name, upload.getBytes()));
    }

    private void requireEditor(Actor actor, long id) {
        if (!actor.has("FOLLOWUP")) throw Problems.forbidden();
        lock(id);
        var current = accounts.actorById(actor.id());
        if (!current.has("FOLLOWUP")) throw Problems.forbidden();
        authorized(current, id);
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
                where episode_id = ? and status <> 'CLOSED' returning id, owner_id
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

    private List<Choice> accountChoices(Actor actor, String role) {
        return jdbc.sql("""
                select u.id, u.display_name as name from app_user u join user_role r on r.user_id = u.id
                where r.role = :role and u.enabled and (:demo or exists (
                    select 1 from staff_department candidate join staff_department own
                    on candidate.department_id = own.department_id where candidate.user_id = u.id and own.user_id = :staff))
                order by u.display_name, u.id
                """).param("role", role).param("demo", actor.demo()).param("staff", actor.id()).query(Choice.class).list();
    }

    private JdbcClient.StatementSpec entitled(String sql, Actor actor) {
        return jdbc.sql(sql).param("actor", actor.id()).param("demo", actor.demo()).param("followup", actor.has("FOLLOWUP"))
                .param("reviewer", actor.has("REVIEWER")).param("doctor", actor.has("DOCTOR"));
    }

    private static void requireClinical(Actor actor) {
        if (!actor.has("FOLLOWUP") && !actor.has("REVIEWER") && !actor.has("DOCTOR")) throw Problems.missing();
    }

    public record Episode(long id, long patientId, String patient, long ownerId, String kind, String reference,
                          LocalDate careDate, int sourceVersion, long departmentId) { }
    public record Choice(long id, String name) { }
    public record Directory(List<Choice> reviewers, List<Choice> owners, List<Choice> departments,
                            boolean syntheticDischarges, boolean pdfEnabled) { }
    public record SourceContext(Segment target, List<SourceLine> lines) { }
    public record SourceLine(long id, int lineNumber, String excerpt) { }
    public record Segment(long id, String title, long versionId, int versionNumber, Integer pageNumber,
                          int lineNumber, String excerpt) { }
    public record Document(long id, String title, long versionId, int versionNumber, String filename,
                           OffsetDateTime createdAt) { }
    public record Attachment(String filename, String mediaType, byte[] content) { }
    private record Invalidated(long id, long ownerId) { }
    private record CompletedVisit(long id, long patientId, String returnPlan, LocalDate careDate, long departmentId) { }
}
