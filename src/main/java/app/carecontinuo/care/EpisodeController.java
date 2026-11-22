package app.carecontinuo.care;

import app.carecontinuo.identity.Accounts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
class EpisodeController {
    private final Accounts accounts;
    private final Episodes episodes;

    EpisodeController(Accounts accounts, Episodes episodes) {
        this.accounts = accounts;
        this.episodes = episodes;
    }

    @GetMapping("/care-directory")
    Episodes.Directory directory(Principal principal) {
        return episodes.directory(accounts.actor(principal));
    }

    @GetMapping("/episodes")
    List<Episodes.Episode> list(Principal principal) {
        return episodes.list(accounts.actor(principal));
    }

    @PostMapping("/episodes")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Long> add(Principal principal, @Valid @RequestBody Discharge input) {
        return Map.of("id", episodes.registerDischarge(accounts.actor(principal), input.patientId(),
                input.reference().strip(), input.careDate(), input.departmentId()));
    }

    @PostMapping("/appointments/{id}/return-plan")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Long> outpatient(Principal principal, @PathVariable long id, @Valid @RequestBody Assignment input) {
        return Map.of("id", episodes.registerOutpatient(accounts.actor(principal), id, input.ownerId()));
    }

    @GetMapping("/episodes/{id}/documents")
    List<Episodes.Document> documents(Principal principal, @PathVariable long id) {
        return episodes.documents(accounts.actor(principal), id);
    }

    @GetMapping("/episodes/{id}/segments")
    List<Episodes.Segment> segments(Principal principal, @PathVariable long id) {
        return episodes.segments(accounts.actor(principal), id);
    }

    @PostMapping(value = "/episodes/{id}/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Long> text(Principal principal, @PathVariable long id, @Valid @RequestBody Text input) {
        return Map.of("versionId", episodes.addText(accounts.actor(principal), id, input.documentId(),
                input.title().strip(), input.text()));
    }

    @PostMapping(value = "/episodes/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Long> file(Principal principal, @PathVariable long id,
                           @RequestParam(required = false) Long documentId,
                           @RequestParam @NotBlank @Size(max = 200) String title,
                           @RequestParam("file") MultipartFile upload) throws IOException {
        return Map.of("versionId", episodes.addFile(accounts.actor(principal), id, documentId, title.strip(), upload));
    }

    @GetMapping("/episodes/{id}/segments/{segmentId}/context")
    Episodes.SourceContext sourceContext(Principal principal, @PathVariable long id, @PathVariable long segmentId) {
        return episodes.sourceContext(accounts.actor(principal), id, segmentId);
    }

    @GetMapping("/episodes/{id}/versions/{versionId}/download")
    ResponseEntity<byte[]> download(Principal principal, @PathVariable long id, @PathVariable long versionId,
                                    @RequestParam(defaultValue = "false") boolean inline) {
        var file = episodes.download(accounts.actor(principal), id, versionId);
        var builder = inline && MediaType.APPLICATION_PDF_VALUE.equals(file.mediaType())
                ? ContentDisposition.inline() : ContentDisposition.attachment();
        String disposition = builder.filename(file.filename(), StandardCharsets.UTF_8).build().toString();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition).body(file.content());
    }

    record Discharge(@Positive long patientId, @NotBlank @Size(max = 100) String reference,
                     @NotNull LocalDate careDate, @Positive Long departmentId) { }
    record Assignment(@Positive long ownerId) { }
    record Text(@Positive Long documentId, @NotBlank @Size(max = 200) String title,
                @NotBlank @Size(max = 80000) String text) { }
}
