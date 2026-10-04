package com.mediafactory.api;

import com.mediafactory.stock.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class StockController {

  final StockProductionService stock;
  final StockMetadataService metadata;
  final StockExportService exports;
  final StockCollectionService collections;

  public StockController(
      StockProductionService stock,
      StockMetadataService metadata,
      StockExportService exports,
      StockCollectionService collections) {
    this.stock = stock;
    this.metadata = metadata;
    this.exports = exports;
    this.collections = collections;
  }

  @GetMapping("/stock-productions")
  public Object list() {
    return stock.list();
  }

  @PostMapping("/stock-productions")
  public Object start(@Valid @RequestBody Start r, @RequestHeader("Idempotency-Key") String key) {
    return stock.start(r.conceptId(), r.sourceAssetId(), r.profile(), key);
  }

  @GetMapping("/stock-productions/{id}")
  public Object detail(@PathVariable UUID id) {
    return stock.detail(id);
  }

  @PostMapping("/stock-productions/{id}/approve")
  public Object approve(@PathVariable UUID id, @Valid @RequestBody Action r) {
    return stock.approve(id, r.revision(), r.acknowledgeWarnings());
  }

  @PostMapping("/stock-productions/{id}/{action:reject|cancel|retry}")
  public Object action(
      @PathVariable UUID id, @PathVariable String action, @Valid @RequestBody Action r) {
    return stock.action(id, r.revision(), action);
  }

  @GetMapping({"/stock-productions/{id}/metadata", "/stock-productions/{id}/metadata/versions"})
  public Object versions(@PathVariable UUID id) {
    return metadata.versions(id);
  }

  @PutMapping("/stock-productions/{id}/metadata")
  public Object edit(@PathVariable UUID id, @Valid @RequestBody Edit r) {
    return metadata.edit(id, r.revision(), r.data());
  }

  @PostMapping("/stock-productions/{id}/metadata/regenerate")
  public Object regenerate(@PathVariable UUID id, @Valid @RequestBody Regenerate r) {
    return metadata.regenerate(id, r.revision(), r.scope());
  }

  @GetMapping("/stock-profiles")
  public Object profiles() {
    return stock.profiles();
  }

  @PostMapping("/stock-profiles/{key}/versions")
  public Object profile(@PathVariable String key, @Valid @RequestBody Profile r) {
    return stock.newProfile(key, r.previousVersion(), r.definition());
  }

  @GetMapping("/stock-export-profiles")
  public Object exportProfiles() {
    return exports.profiles();
  }

  @PostMapping("/stock-export-profiles/{key}/versions")
  public Object exportProfile(@PathVariable String key, @Valid @RequestBody Profile r) {
    return exports.profile(key, r.previousVersion(), r.definition());
  }

  @PostMapping("/stock-exports")
  public Object export(@Valid @RequestBody Export r, @RequestHeader("Idempotency-Key") String key) {
    return exports.request(
        r.profile(),
        r.stockProductionIds(),
        r.collectionId(),
        r.incremental(),
        r.policy(),
        key,
        null);
  }

  @GetMapping("/stock-exports")
  public Object exports() {
    return exports.list();
  }

  @GetMapping("/stock-exports/{id}")
  public Object export(@PathVariable UUID id) {
    return exports.detail(id);
  }

  @GetMapping("/stock-exports/{id}/validation")
  public Object validation(@PathVariable UUID id) {
    return exports.validate(id);
  }

  @PostMapping("/stock-exports/{id}/retry")
  public Object retry(@PathVariable UUID id) {
    return exports.retry(id);
  }

  @PostMapping("/stock-exports/{id}/rebuild")
  public Object rebuild(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key) {
    return exports.rebuild(id, key);
  }

  @GetMapping("/stock-exports/{id}/content")
  public ResponseEntity<byte[]> content(@PathVariable UUID id) {
    return ResponseEntity.ok()
        .header("Content-Type", "application/zip")
        .header("Content-Disposition", "attachment; filename=stock-" + id + ".zip")
        .body(exports.download(id));
  }

  @GetMapping("/stock-collections")
  public Object collections() {
    return collections.list();
  }

  @PostMapping("/stock-collections")
  public Object collection(@Valid @RequestBody Collection r) {
    return collections.create(r.projectId(), r.title());
  }

  @GetMapping("/stock-collections/{id}/production-status")
  public Object progress(@PathVariable UUID id) {
    return collections.progress(id);
  }

  @PostMapping("/stock-collections/{id}/produce")
  public Object plan(@PathVariable UUID id, @Valid @RequestBody Plan r) {
    return collections.plan(
        id,
        r.profile(),
        r.targetApproved(),
        r.batchSize(),
        r.maxGenerationAttempts(),
        r.maxGenerationCost(),
        r.reservedCostPerAttempt());
  }

  @PostMapping("/stock-collections/{id}/{action:pause|resume|cancel}")
  public Object planAction(
      @PathVariable UUID id, @PathVariable String action, @Valid @RequestBody Action r) {
    return collections.action(id, r.revision(), action);
  }

  @GetMapping("/stock-dashboard")
  public Object dashboard() {
    return collections.dashboard();
  }

  public record Start(UUID conceptId, UUID sourceAssetId, @NotBlank String profile) {

  }

  public record Action(@Min(0) int revision, boolean acknowledgeWarnings) {

  }

  public record Edit(@Min(0) int revision, @NotNull Map<String, Object> data) {

  }

  public record Regenerate(@Min(0) int revision, @NotBlank String scope) {

  }

  public record Profile(@Min(0) int previousVersion, @NotNull Map<String, Object> definition) {

  }

  public record Export(
      @NotBlank String profile,
      List<UUID> stockProductionIds,
      UUID collectionId,
      boolean incremental,
      @NotBlank String policy) {

  }

  public record Collection(@NotNull UUID projectId, @NotBlank @Size(max = 200) String title) {

  }

  public record Plan(
      @NotBlank String profile,
      int targetApproved,
      int batchSize,
      int maxGenerationAttempts,
      @NotNull BigDecimal maxGenerationCost,
      @NotNull BigDecimal reservedCostPerAttempt) {

  }
}
