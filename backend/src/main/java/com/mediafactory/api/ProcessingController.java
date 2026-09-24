package com.mediafactory.api;

import com.mediafactory.processing.*;
import com.mediafactory.storage.MediaStorage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ProcessingController {
  private final ProcessingService service;
  private final MediaStorage storage;

  public ProcessingController(ProcessingService service, MediaStorage storage) {
    this.service = service;
    this.storage = storage;
  }

  public record ProcessRequest(
      @NotEmpty @Size(max = 16) List<@NotBlank String> profiles, Map<String, Object> manualCrops) {}

  public record BatchRequest(
      @NotEmpty @Size(max = 100) List<@NotNull UUID> assetIds,
      @NotEmpty @Size(max = 16) List<@NotBlank String> profiles) {}

  @PostMapping("/assets/{id}/process")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Object process(
      @PathVariable UUID id,
      @Valid @RequestBody ProcessRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.request(
        id, body.profiles(), body.manualCrops() == null ? Map.of() : body.manualCrops(), key, 10);
  }

  @PostMapping("/processing-batches")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Object batch(@Valid @RequestBody BatchRequest body) {
    return service.batch(body.assetIds(), body.profiles());
  }

  @GetMapping("/processing-runs")
  public Object runs() {
    return service.runs();
  }

  @GetMapping("/processing-runs/{id}")
  public Object run(@PathVariable UUID id) {
    return service.run(id);
  }

  @PostMapping("/processing-runs/{id}/retry")
  public Object retry(@PathVariable UUID id) {
    return service.retry(id);
  }

  @PostMapping("/processing-runs/{id}/cancel")
  public Object cancel(@PathVariable UUID id) {
    return service.cancel(id);
  }

  @GetMapping("/processing-profiles")
  public Object profiles() {
    return service.profiles();
  }

  @GetMapping("/processing-profiles/{key}")
  public Object profile(@PathVariable String key) {
    return service.profile(key);
  }

  @GetMapping("/processing-profiles/{key}/versions")
  public Object versions(@PathVariable String key) {
    return service.profiles().stream().filter(p -> key.equals(p.get("key"))).toList();
  }

  @PostMapping("/processing-profiles/{key}/drafts")
  public Object draft(@PathVariable String key, @RequestBody Map<String, Object> definition) {
    return service.draft(key, definition);
  }

  @PostMapping("/processing-profile-versions/{id}/{action}")
  public Object publish(@PathVariable UUID id, @PathVariable String action) {
    return service.transition(
        id,
        switch (action) {
          case "publish" -> "PUBLISHED";
          case "deprecate" -> "DEPRECATED";
          default -> throw new IllegalArgumentException("Invalid action");
        });
  }

  @GetMapping("/assets/{id}/variants")
  public Object variants(@PathVariable UUID id) {
    return service.variants(id);
  }

  @GetMapping("/processing-worker")
  public Object worker() {
    return service.worker();
  }

  @GetMapping("/processing-costs")
  public Object costs() {
    return service.usage();
  }

  @GetMapping("/wallpaper-targets")
  public Object targets() {
    return service.targets();
  }

  @PutMapping("/wallpaper-targets/{key}")
  public Object target(@PathVariable String key, @RequestBody Map<String, Object> definition) {
    return service.target(key, definition);
  }

  @PostMapping("/processing-profiles/{key}/from-target/{target}")
  public Object targetDraft(@PathVariable String key, @PathVariable String target) {
    return service.targetDraft(key, target);
  }

  @GetMapping("/assets/{id}/crop-preview/{profile}")
  public Object preview(@PathVariable UUID id, @PathVariable String profile) {
    return service.preview(id, profile);
  }

  @GetMapping("/variants/{id}/content")
  public ResponseEntity<byte[]> content(@PathVariable UUID id) {
    var v = service.variant(id);
    String format = Objects.toString(v.get("format"), "PNG").toLowerCase();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("image/" + format))
        .header("X-Content-Type-Options", "nosniff")
        .cacheControl(
            CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePrivate().immutable())
        .body(storage.read(v.get("storage_key").toString()));
  }
}
