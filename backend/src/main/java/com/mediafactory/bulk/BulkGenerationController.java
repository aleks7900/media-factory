package com.mediafactory.bulk;

import com.mediafactory.provider.ImageGenerationProperties;
import com.mediafactory.provider.routing.ImageProviderRouter;
import com.mediafactory.provider.video.VideoProviderRouter;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/bulk")
public class BulkGenerationController {

  final BulkGenerationService service;
  final ImageProviderRouter images;
  final ImageGenerationProperties imageConfig;
  final VideoProviderRouter videos;

  public BulkGenerationController(
      BulkGenerationService service,
      ImageProviderRouter images,
      ImageGenerationProperties imageConfig,
      VideoProviderRouter videos) {
    this.service = service;
    this.images = images;
    this.imageConfig = imageConfig;
    this.videos = videos;
  }

  @GetMapping("/capabilities")
  public Object capabilities() {
    return Map.of(
        "imageProviders",
        images.all().stream()
            .filter(p -> Set.of("openai", "mock").contains(p.providerId()))
            .map(
                p ->
                    Map.of(
                        "provider",
                        p.providerId(),
                        "enabled",
                        p.configured() && imageConfig.provider(p.providerId()).enabled(),
                        "models",
                        imageConfig.provider(p.providerId()).models(),
                        "defaultModel",
                        imageConfig.provider(p.providerId()).model(),
                        "capabilities",
                        p.capabilities()))
            .toList(),
        "videoProviders",
        videos.info(),
        "archiveLimits",
        Map.of(
            "archiveBytes",
            service.archiveParser().limits.archiveBytes(),
            "extractedBytes",
            service.archiveParser().limits.extractedBytes(),
            "files",
            service.archiveParser().limits.files(),
            "tasks",
            service.archiveParser().limits.tasks(),
            "referenceTypes",
            List.of("image/png", "image/jpeg")));
  }

  @PostMapping(value = "/batches", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Object upload(
      @RequestPart("archive") MultipartFile archive,
      @RequestPart("request") BulkGenerationService.ImportRequest request,
      @RequestHeader("Idempotency-Key") String key)
      throws java.io.IOException {
    BulkGenerationService.check(
        archive.getSize() <= service.archiveParser().limits.archiveBytes(),
        "Archive exceeds upload limit");
    BulkGenerationService.check(
        Objects.toString(archive.getOriginalFilename(), "")
            .toLowerCase(Locale.ROOT)
            .endsWith(".zip"),
        "ZIP archive required");
    return service.importArchive(request, key, archive.getOriginalFilename(), archive.getBytes());
  }

  @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Object preview(@RequestPart("archive") MultipartFile archive) throws java.io.IOException {
    BulkGenerationService.check(
        archive.getSize() <= service.archiveParser().limits.archiveBytes(),
        "Archive exceeds upload limit");
    BulkGenerationService.check(
        Objects.toString(archive.getOriginalFilename(), "")
            .toLowerCase(Locale.ROOT)
            .endsWith(".zip"),
        "ZIP archive required");
    var parsed = service.archiveParser().parse(archive.getInputStream());
    long valid = parsed.tasks().stream().filter(t -> t.error() == null).count();
    long invalid = parsed.tasks().stream().filter(t -> t.error() != null).count();
    return Map.of(
        "totalTasks", parsed.tasks().size(),
        "validTasks", valid,
        "invalidTasks", invalid,
        "referenceCount", parsed.referenceCount(),
        "sampleTasks",
        parsed.tasks().stream().limit(10).map(BulkArchiveParser.Task::name).toList());
  }

  @GetMapping("/batches")
  public Object batches(
      @RequestParam UUID projectId,
      @RequestParam String kind,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "ALL") String status,
      @RequestParam(defaultValue = "0") int page) {
    return service.list(projectId, kind, search, status, page);
  }

  @GetMapping("/batches/{id}")
  public Object batch(@PathVariable UUID id) {
    return service.detail(id);
  }

  @GetMapping("/batches/{id}/tasks")
  public Object tasks(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "ALL") String status,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "0") int page) {
    return service.tasks(id, status, search, page);
  }

  @PostMapping("/batches/{id}/{action:pause|resume|cancel|retry-failed|delete}")
  public Object batchAction(@PathVariable UUID id, @PathVariable String action) {
    return service.batchAction(id, action);
  }

  @GetMapping("/tasks/{id}")
  public Object task(@PathVariable UUID id) {
    return service.taskDetail(id);
  }

  @PostMapping("/tasks/{id}/{action:retry|cancel|regenerate|delete|reconcile}")
  public Object taskAction(
      @PathVariable UUID id,
      @PathVariable String action,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.taskAction(id, action, key);
  }

  @GetMapping("/tasks/{id}/references/{index}")
  public ResponseEntity<byte[]> reference(@PathVariable UUID id, @PathVariable int index) {
    var content = service.reference(id, index);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(content.type()))
        .header("X-Content-Type-Options", "nosniff")
        .body(content.bytes());
  }

  @GetMapping("/batches/{id}/results.zip")
  public ResponseEntity<StreamingResponseBody> export(@PathVariable UUID id) {
    var b = service.detail(id);
    String base = Objects.toString(b.get("archive_name"), "batch").replaceFirst("(?i)\\.zip$", "");
    String downloadName = base.replaceAll("[^A-Za-z0-9_-]", "_") + "-results.zip";
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/zip"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadName + "\"")
        .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate, private")
        .header(HttpHeaders.PRAGMA, "no-cache")
        .header("Expires", "0")
        .header("X-Content-Type-Options", "nosniff")
        .body(out -> service.export(id, out));
  }
}
