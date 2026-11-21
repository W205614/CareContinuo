package app.carecontinuo.agent;

import app.carecontinuo.audit.AuditLog;
import app.carecontinuo.care.Episodes;
import app.carecontinuo.common.Problems;
import app.carecontinuo.identity.Accounts;
import app.carecontinuo.identity.Accounts.Actor;
import app.carecontinuo.notification.Notifications;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class AgentJobs {
    private static final String JOB_SQL = """
            select id, episode_id, created_by, source_version, status, failure_code, outcome::text as outcome from agent_job
            """;
    private final JdbcClient jdbc;
    private final Episodes episodes;
    private final Accounts accounts;
    private final AuditLog audit;
    private final Notifications notices;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final Clock clock;
    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    AgentJobs(JdbcClient jdbc, Episodes episodes, Accounts accounts, AuditLog audit, Notifications notices,
              TransactionTemplate transactions, JsonMapper json, Clock clock,
              @Value("${CARE_AGENT_URL:http://localhost:8000}") String baseUrl) {
        this.jdbc = jdbc;
        this.episodes = episodes;
        this.accounts = accounts;
        this.audit = audit;
        this.notices = notices;
        this.transactions = transactions;
        this.json = json;
        this.clock = clock;
        endpoint = URI.create(baseUrl).resolve("/internal/v1/followup-drafts");
    }

    @Transactional
    View create(Actor actor, long episodeId) {
        if (!actor.has("FOLLOWUP")) throw Problems.missing();
        var episode = episodes.lock(episodeId);
        episodes.authorized(actor, episodeId);
        var currentJob = jdbc.sql(JOB_SQL + " where episode_id = ? and source_version = ? and created_by = ?"
                        + " and status in ('QUEUED', 'RUNNING', 'READY', 'CONFLICT') order by id desc limit 1")
                .params(episodeId, episode.sourceVersion(), actor.id()).query(Job.class).optional();
        if (currentJob.isPresent()) return view(currentJob.get(), episode);
        var fragments = episodes.currentSegments(episodeId);
        if (fragments.isEmpty()) throw Problems.invalid("资料没有可用文本。请导入文本或手工编写随访草稿。");
        if (fragments.size() > 500 || fragments.stream().mapToLong(s -> s.excerpt().length()).sum() > 60_000) {
            throw Problems.invalid("本次资料的文本过长，请改用人工录入。");
        }
        long id = jdbc.sql("""
                insert into agent_job(episode_id, created_by, source_version, status) values (?, ?, ?, 'QUEUED') returning id
                """).params(episodeId, actor.id(), episode.sourceVersion()).query(Long.class).single();
        audit.record(actor.id(), "AGENT_JOB", id, "REQUEST");
        return view(find(id), episode);
    }

    List<View> list(Actor actor, long episodeId) {
        var episode = episodes.authorized(actor, episodeId);
        return jdbc.sql(JOB_SQL + " where episode_id = ? order by id desc limit 15").param(episodeId).query(Job.class)
                .list().stream().map(job -> view(job, episode)).toList();
    }

    View get(Actor actor, long id) {
        var job = find(id);
        var episode = episodes.authorized(actor, job.episodeId());
        audit.record(actor.id(), "AGENT_JOB", id, "READ");
        return view(job, episode);
    }

    @Scheduled(fixedDelayString = "${care.agent.poll-ms:1500}", scheduler = "agentScheduler")
    void poll() {
        jdbc.sql("update agent_job set status = 'FAILED', failure_code = 'MODEL_INTERRUPTED' where status = 'RUNNING' and expires_at <= ?")
                .param(now()).update();
        var next = jdbc.sql(JOB_SQL + " where status = 'QUEUED' order by id limit 1").query(Job.class).optional();
        if (next.isEmpty()) return;
        var job = next.get();
        var request = transactions.execute(transaction -> claim(job));
        if (request == null) return;

        Outcome outcome = call(request);
        transactions.executeWithoutResult(transaction -> finish(job, request, outcome));
    }

    private ExtractionRequest claim(Job pending) {
        var episode = episodes.lock(pending.episodeId());
        var job = find(pending.id());
        if (!job.status().equals("QUEUED")) return null;
        if (job.sourceVersion() != episode.sourceVersion() || !stillAuthorized(job)) {
            stale(job.id());
            return null;
        }
        var at = now();
        jdbc.sql("update agent_job set status = 'RUNNING', started_at = ?, expires_at = ? where id = ?")
                .params(at, at.plusMinutes(2), job.id()).update();
        audit.record(job.createdBy(), "AGENT_JOB", job.id(), "LOAD_AUTHORIZED_SOURCE");
        var fragments = episodes.currentSegments(job.episodeId()).stream().map(s ->
                new Fragment(s.id(), s.versionId(), s.pageNumber(), s.lineNumber(), s.excerpt())).toList();
        return new ExtractionRequest(job.id(), job.sourceVersion(), fragments);
    }

    private Outcome call(ExtractionRequest request) {
        try {
            var invocation = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(55))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build();
            var response = http.send(invocation, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return new Outcome(null, failure(response.body(), response.statusCode()));
            return new Outcome(json.readValue(response.body(), Extraction.class), null);
        } catch (HttpTimeoutException failure) {
            return new Outcome(null, "MODEL_TIMEOUT");
        } catch (IOException failure) {
            return new Outcome(null, "AGENT_UNREACHABLE");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new Outcome(null, "MODEL_INTERRUPTED");
        } catch (JacksonException failure) {
            return new Outcome(null, "MODEL_INVALID_OUTPUT");
        }
    }

    private String failure(String body, int status) {
        if (status == 504) return "MODEL_TIMEOUT";
        String detail = json.readTree(body).path("detail").asString("");
        if (List.of("MODEL_AUTHENTICATION_FAILED", "MODEL_PERMISSION_DENIED", "MODEL_UNAVAILABLE", "MODEL_REQUEST_FAILED",
                "MODEL_QUOTA_OR_RATE_LIMIT", "MODEL_CONNECTION_FAILED", "MODEL_INVALID_OUTPUT", "MODEL_NO_USABLE_OUTPUT",
                "MODEL_SOURCE_MISMATCH", "MODEL_KEY_UNAVAILABLE", "SOURCE_TOO_LARGE").contains(detail)) return detail;
        return "AGENT_REQUEST_FAILED";
    }

    private void finish(Job started, ExtractionRequest request, Outcome outcome) {
        var episode = episodes.lock(started.episodeId());
        var job = find(started.id());
        if (!job.status().equals("RUNNING")) return;
        if (job.sourceVersion() != episode.sourceVersion() || !stillAuthorized(job)) {
            stale(job.id());
            return;
        }
        String failure = outcome.failure();
        if (failure == null && !grounded(request, outcome.result())) failure = "MODEL_SOURCE_MISMATCH";
        if (failure != null) {
            jdbc.sql("update agent_job set status = 'FAILED', failure_code = ? where id = ?").params(failure, job.id()).update();
            return;
        }
        boolean conflict = !outcome.result().conflicts().isEmpty();
        jdbc.sql("update agent_job set status = ?, outcome = cast(? as jsonb) where id = ?")
                .params(conflict ? "CONFLICT" : "READY", json.writeValueAsString(outcome.result()), job.id()).update();
        if (conflict) notices.openWork("AGENT_CONFLICT", job.id(), "FOLLOWUP", episode.ownerId(), now());
    }

    private static boolean grounded(ExtractionRequest request, Extraction result) {
        if (result == null || result.model() == null || result.model().isBlank() || result.candidates() == null
                || result.conflicts() == null || result.candidates().size() > 12 || result.conflicts().size() > 12
                || result.candidates().isEmpty() && result.conflicts().isEmpty()) return false;
        var fragments = request.fragments().stream().collect(Collectors.toMap(Fragment::id, Function.identity()));
        return result.candidates().stream().allMatch(candidate -> candidate != null && candidate.kind() != null
                        && List.of("REVISIT", "PENDING_RESULT").contains(candidate.kind())
                        && nonBlank(candidate.title(), 140) && nonBlank(candidate.instructions(), 4000)
                        && grounded(candidate.references(), fragments))
                && result.conflicts().stream().allMatch(conflict -> conflict != null && nonBlank(conflict.reason(), 400)
                        && grounded(conflict.references(), fragments)
                        && conflict.references().stream().map(Reference::segmentId).distinct().count() >= 2);
    }

    private static boolean grounded(List<Reference> references, Map<Long, Fragment> sources) {
        return references != null && !references.isEmpty() && references.stream().allMatch(ref -> ref != null
                && nonBlank(ref.quote(), 2000) && sources.containsKey(ref.segmentId())
                && sources.get(ref.segmentId()).excerpt().contains(ref.quote()));
    }

    private static boolean nonBlank(String value, int length) {
        return value != null && !value.isBlank() && value.length() <= length;
    }

    private boolean stillAuthorized(Job job) {
        try {
            Actor actor = accounts.actorById(job.createdBy());
            if (!actor.has("FOLLOWUP")) return false;
            episodes.authorized(actor, job.episodeId());
            return true;
        } catch (ResponseStatusException denied) {
            return false;
        }
    }

    private void stale(long id) {
        jdbc.sql("update agent_job set status = 'STALE', failure_code = 'SOURCE_VERSION_STALE' where id = ?").param(id).update();
    }

    private Job find(long id) {
        return jdbc.sql(JOB_SQL + " where id = ?").param(id).query(Job.class).optional().orElseThrow(Problems::missing);
    }

    private View view(Job job, Episodes.Episode episode) {
        if (episode.sourceVersion() != job.sourceVersion()) {
            return new View(job.id(), job.episodeId(), job.sourceVersion(), "STALE", "SOURCE_VERSION_STALE", null);
        }
        JsonNode outcome = job.outcome() == null ? null : json.readTree(job.outcome());
        return new View(job.id(), job.episodeId(), job.sourceVersion(), job.status(), job.failureCode(), outcome);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    record View(long id, long episodeId, int sourceVersion, String status, String failureCode, JsonNode outcome) { }
    private record Job(long id, long episodeId, long createdBy, int sourceVersion, String status, String failureCode,
                       String outcome) { }
    private record ExtractionRequest(long jobId, int sourceVersion, List<Fragment> fragments) { }
    private record Fragment(long id, long versionId, Integer pageNumber, int lineNumber, String excerpt) { }
    private record Outcome(Extraction result, String failure) { }
    private record Extraction(String model, List<Candidate> candidates, List<Conflict> conflicts) { }
    private record Candidate(String kind, String title, String instructions, LocalDate dueDate, List<Reference> references) { }
    private record Conflict(String reason, List<Reference> references) { }
    private record Reference(long segmentId, String quote) { }
}
