package com.mediafactory.api;

import com.mediafactory.wallpaper.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class WallpaperController {
  private final WallpaperProductionService productions;
  private final WallpaperCollectionService collections;
  private final WallpaperPublicationService publications;
  private final WallpaperExportService exports;

  public WallpaperController(
      WallpaperProductionService productions,
      WallpaperCollectionService collections,
      WallpaperPublicationService publications,
      WallpaperExportService exports) {
    this.productions = productions;
    this.collections = collections;
    this.publications = publications;
    this.exports = exports;
  }

  public record Start(
      @NotNull UUID conceptId, @NotBlank String profile, @NotNull Map<String, Object> metadata) {}

  public record Action(@Min(0) int revision, @NotBlank @Size(max = 2000) String reason) {}

  public record Approval(@NotNull UUID packageId, @Min(0) int revision) {}

  public record Target(@NotBlank String target) {}

  public record Metadata(@Min(0) int revision, @NotNull Map<String, Object> metadata) {}

  public record Reprocess(@Min(0) int revision, @NotNull UUID processingRunId) {}

  public record ProfileUpdate(@Min(1) int version, @NotNull Map<String, Object> definition) {}

  public record CollectionInput(
      @NotNull UUID projectId,
      @NotBlank String title,
      @NotBlank String slug,
      String description,
      String theme,
      String style,
      boolean amoled) {}

  public record Plan(
      @Min(1) @Max(1000) int targetApproved,
      @Min(1) @Max(20) int batchSize,
      @Min(1) @Max(2000) int maxAttempts,
      @NotNull @DecimalMin("0") BigDecimal maximumCost,
      @NotNull @DecimalMin("0") BigDecimal reservedCostPerAttempt,
      @NotBlank String wallpaperProfile) {}

  @GetMapping("/wallpaper-productions")
  public Object list() {
    return productions.list();
  }

  @PostMapping("/wallpaper-productions")
  public Object start(@Valid @RequestBody Start r, @RequestHeader("Idempotency-Key") String key) {
    return productions.start(r.conceptId(), r.profile(), r.metadata(), key, null);
  }

  @GetMapping("/wallpaper-productions/{id}")
  public Object one(@PathVariable UUID id) {
    return productions.details(id);
  }

  @PostMapping("/wallpaper-productions/{id}/{action:pause|resume|cancel|reject}")
  public Object action(
      @PathVariable UUID id, @PathVariable String action, @Valid @RequestBody Action r) {
    return productions.action(id, r.revision(), action, r.reason());
  }

  @PostMapping("/wallpaper-productions/{id}/regenerate")
  public Object regenerate(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key) {
    return productions.regenerate(id, key);
  }

  @PostMapping("/wallpaper-productions/{id}/metadata")
  public Object metadata(@PathVariable UUID id, @Valid @RequestBody Metadata r) {
    return productions.metadata(id, r.revision(), r.metadata());
  }

  @PostMapping("/wallpaper-productions/{id}/reprocess")
  public Object reprocess(@PathVariable UUID id, @Valid @RequestBody Reprocess r) {
    return productions.reprocess(id, r.revision(), r.processingRunId());
  }

  @GetMapping("/wallpaper-profiles")
  public Object profiles() {
    return productions.profiles();
  }

  @PutMapping("/wallpaper-profiles/{key}")
  public Object profile(@PathVariable String key, @Valid @RequestBody ProfileUpdate r) {
    return productions.configureProfile(key, r.version(), r.definition());
  }

  @GetMapping("/wallpaper-dashboard")
  public Object dashboard() {
    return productions.dashboard();
  }

  @GetMapping("/wallpaper-device-profiles")
  public Object devices() {
    return productions.devices();
  }

  @GetMapping("/wallpaper-publication-targets")
  public Object targets() {
    return publications.targets();
  }

  @GetMapping("/wallpaper-collections")
  public Object collections() {
    return collections.list();
  }

  @PostMapping("/wallpaper-collections")
  public Object create(@Valid @RequestBody CollectionInput r) {
    return collections.create(
        r.projectId(),
        r.title(),
        r.slug(),
        Objects.toString(r.description(), ""),
        Objects.toString(r.theme(), ""),
        Objects.toString(r.style(), ""),
        r.amoled());
  }

  @PostMapping("/wallpaper-collections/{id}/produce")
  public Object produce(@PathVariable UUID id, @Valid @RequestBody Plan r) {
    return collections.produce(
        id,
        r.targetApproved(),
        r.batchSize(),
        r.maxAttempts(),
        r.maximumCost(),
        r.reservedCostPerAttempt(),
        r.wallpaperProfile());
  }

  @GetMapping("/wallpaper-collections/{id}/production-status")
  public Object status(@PathVariable UUID id) {
    return collections.status(id);
  }

  @PostMapping("/wallpaper-collections/{id}/{action:pause|resume|cancel}")
  public Object collectionAction(
      @PathVariable UUID id, @PathVariable String action, @Valid @RequestBody Action r) {
    return collections.action(id, action, r.revision());
  }

  @GetMapping("/wallpaper-collections/{id}/cover-candidates")
  public Object coverCandidates(@PathVariable UUID id) {
    return collections.coverCandidates(id);
  }

  public record Cover(@NotNull UUID assetId) {}

  @PostMapping("/wallpaper-collections/{id}/cover")
  public Object cover(@PathVariable UUID id, @Valid @RequestBody Cover r) {
    return collections.cover(id, r.assetId());
  }

  @GetMapping("/wallpapers/{id}/eligibility")
  public Object eligibility(@PathVariable UUID id) {
    return publications.eligibility(id, true);
  }

  @PostMapping("/wallpapers/{id}/prepare-publication")
  public Object prepare(@PathVariable UUID id) {
    return publications.prepare(id);
  }

  @PostMapping("/wallpapers/{id}/approve-publication")
  public Object approve(@PathVariable UUID id, @Valid @RequestBody Approval r) {
    return publications.approve(id, r.packageId(), r.revision());
  }

  @PostMapping("/wallpapers/{id}/publication-dry-run")
  public Object dry(@PathVariable UUID id, @Valid @RequestBody Target r) {
    return publications.dryRun(id, r.target());
  }

  @PostMapping("/wallpapers/{id}/publish")
  public Object publish(@PathVariable UUID id, @Valid @RequestBody Target r) {
    return publications.publish(id, r.target());
  }

  @PostMapping("/wallpapers/{id}/unpublish")
  public Object unpublish(@PathVariable UUID id, @Valid @RequestBody Target r) {
    return publications.unpublish(id, r.target());
  }

  @PostMapping("/wallpaper-deliveries/{id}/retry")
  public Object retry(@PathVariable UUID id) {
    return publications.retry(id);
  }

  @PostMapping("/wallpapers/{id}/export")
  public Object export(@PathVariable UUID id) {
    return exports.request(id, null);
  }

  @PostMapping("/wallpaper-collections/{id}/export")
  public Object collectionExport(@PathVariable UUID id) {
    return exports.request(null, id);
  }

  @PostMapping("/wallpaper-collections/{id}/{action:prepare-publication|publish|unpublish}")
  public Object collectionPublication(
      @PathVariable UUID id, @PathVariable String action, @Valid @RequestBody Target r) {
    return publications.collectionAction(id, action, r.target());
  }

  @GetMapping("/wallpaper-exports")
  public Object exports() {
    return exports.list();
  }

  @GetMapping("/wallpaper-exports/{id}/content")
  public ResponseEntity<byte[]> download(@PathVariable UUID id) {
    return ResponseEntity.ok()
        .header("Content-Type", "application/zip")
        .header("Content-Disposition", "attachment; filename=wallpaper-" + id + ".zip")
        .body(exports.download(id));
  }
}
