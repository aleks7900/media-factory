package com.mediafactory.api;

import com.mediafactory.video.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/video")
public class VideoController {

  final VideoProductionService videos;
  final VideoWorkerClient worker;
  final VideoCollectionService collections;
  final ObjectProvider<VideoGenerationWorker> generation;

  public VideoController(
      VideoProductionService videos,
      VideoWorkerClient worker,
      ObjectProvider<VideoGenerationWorker> generation,
      VideoCollectionService collections) {
    this.videos = videos;
    this.worker = worker;
    this.generation = generation;
    this.collections = collections;
  }

  @PutMapping("/profiles/{key}")
  public Object profileEdit(@PathVariable String key, @Valid @RequestBody ProfileEdit edit) {
    return videos.profileVersion(key, edit.previousVersion(), edit.settings());
  }

  @GetMapping("/providers/statistics")
  public Object providerStats() {
    return videos.providerStats();
  }

  @PutMapping("/collections/{id}/plan")
  public Object plan(@PathVariable UUID id, @Valid @RequestBody Plan p) {
    return collections.configure(
        id,
        p.profile(),
        p.provider(),
        p.targetApproved(),
        p.batchSize(),
        p.maxAttempts(),
        p.budget(),
        p.reservedCostPerVideo());
  }

  @GetMapping("/collections/{id}/progress")
  public Object progress(@PathVariable UUID id) {
    return collections.progress(id);
  }

  @PostMapping("/collections/{id}/{action:pause|resume}")
  public Object planAction(@PathVariable UUID id, @PathVariable String action) {
    return collections.pause(id, action.equals("pause"));
  }

  @GetMapping("/productions")
  public Object list() {
    return videos.list();
  }

  @PostMapping("/productions")
  public Object start(@Valid @RequestBody Start r, @RequestHeader("Idempotency-Key") String key) {
    return videos.start(
        r.sourceAssetId(),
        r.profile(),
        r.provider(),
        r.allowFallback(),
        r.motion() == null ? Map.of() : r.motion(),
        r.budget(),
        r.maxAttempts(),
        key,
        null);
  }

  @GetMapping("/productions/{id}")
  public Object detail(@PathVariable UUID id) {
    return videos.detail(id);
  }

  @GetMapping("/profiles")
  public Object profiles() {
    return videos.profiles();
  }

  @GetMapping("/providers")
  public Object providers() {
    return videos.router.info();
  }

  @GetMapping("/dashboard")
  public Object dashboard() {
    return videos.dashboard();
  }

  @GetMapping("/worker-health")
  public Object health() {
    return worker.health();
  }

  @GetMapping("/productions/{id}/motion")
  public Object motion(@PathVariable UUID id) {
    return videos.motion(id);
  }

  @PutMapping("/productions/{id}/motion")
  public Object motion(@PathVariable UUID id, @Valid @RequestBody Edit r) {
    return videos.editMotion(id, r.revision(), r.motion());
  }

  @PostMapping("/productions/{id}/reprocess")
  public Object process(
      @PathVariable UUID id,
      @Valid @RequestBody Process r,
      @RequestHeader("Idempotency-Key") String key) {
    return videos.reprocess(id, r.revision(), r.settings(), r.variants(), key);
  }

  @PostMapping("/productions/{id}/regenerate")
  public Object regenerate(
      @PathVariable UUID id,
      @Valid @RequestBody Regenerate r,
      @RequestHeader("Idempotency-Key") String key) {
    return videos.regenerate(id, r.revision(), r.budget(), key);
  }

  @PostMapping("/productions/{id}/reconcile")
  public Object reconcile(@PathVariable UUID id, @Valid @RequestBody Reconcile r) {
    return generation.getObject().reconcile(id, r.revision(), r.providerJobId());
  }

  @PostMapping("/productions/{id}/{action:approve|reject|pause|resume|cancel}")
  public Object action(
      @PathVariable UUID id, @PathVariable String action, @Valid @RequestBody Action r) {
    return videos.action(id, r.revision(), action, r.acknowledgeWarnings(), r.reason());
  }

  @GetMapping("/variants/{id}/content")
  public ResponseEntity<ByteArrayResource> content(@PathVariable UUID id) {
    var row =
        videos
            .db
            .sql(
                "select storage_key,format,sha256 from asset_variants where id=? and"
                    + " video_processing_run_id is not null")
            .param(id)
            .query()
            .singleRow();
    byte[] data = videos.storage.read(row.get("storage_key").toString());
    return ResponseEntity.ok()
        .eTag('"' + row.get("sha256").toString() + '"')
        .header("Accept-Ranges", "bytes")
        .contentType(
            MediaType.parseMediaType(row.get("format").equals("MP4") ? "video/mp4" : "image/jpeg"))
        .contentLength(data.length)
        .body(new ByteArrayResource(data));
  }

  public record Start(
      @NotNull UUID sourceAssetId,
      @NotBlank String profile,
      String provider,
      boolean allowFallback,
      Map<String, Object> motion,
      @NotNull @DecimalMin("0") BigDecimal budget,
      @Min(1) @Max(10) int maxAttempts) {

  }

  public record Action(@Min(0) int revision, boolean acknowledgeWarnings, String reason) {

  }

  public record Edit(@Min(0) int revision, @NotNull Map<String, Object> motion) {

  }

  public record Process(
      @Min(0) int revision,
      @NotNull Map<String, Object> settings,
      @NotNull Map<String, Object> variants) {

  }

  public record Regenerate(@Min(0) int revision, @NotNull @DecimalMin("0") BigDecimal budget) {

  }

  public record Reconcile(@Min(0) int revision, @NotBlank String providerJobId) {

  }

  public record ProfileEdit(@Min(1) int previousVersion, @NotNull Map<String, Object> settings) {

  }

  public record Plan(
      @NotBlank String profile,
      String provider,
      int targetApproved,
      int batchSize,
      int maxAttempts,
      @NotNull BigDecimal budget,
      @NotNull BigDecimal reservedCostPerVideo) {

  }
}
