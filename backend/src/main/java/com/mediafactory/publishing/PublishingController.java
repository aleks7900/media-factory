package com.mediafactory.publishing;

import com.mediafactory.publishing.model.*;
import com.mediafactory.publishing.service.PublishingService;
import com.mediafactory.publishing.tiktok.TikTokClient;
import com.mediafactory.publishing.tiktok.TikTokOAuthService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/publishing")
public class PublishingController {

  private final PublishingService service;
  private final TikTokClient tikTokClient;
  private final TikTokOAuthService tikTokOAuthService;

  public PublishingController(
      PublishingService service,
      TikTokClient tikTokClient,
      TikTokOAuthService tikTokOAuthService
  ) {
    this.service = service;
    this.tikTokClient = tikTokClient;
    this.tikTokOAuthService = tikTokOAuthService;
  }

  // --- Account & OAuth Endpoints ---

  @GetMapping("/account")
  public PublishingAccountDto getAccount(
      @RequestParam(name = "platform", defaultValue = "TIKTOK") String platform) {
    return service.getActiveAccount(PublishingPlatform.valueOf(platform.toUpperCase(Locale.ROOT)));
  }

  @GetMapping("/tiktok/auth-url")
  public Map<String, Object> getTikTokAuthUrl(@RequestParam(name = "state", required = false) String state) {
    var result = tikTokOAuthService.initiateAuthorization(state);
    return Map.of(
        "url", result.authorizationUrl(),
        "attemptId", result.attemptId().toString(),
        "state", result.state(),
        "expiresAt", result.expiresAt().toString()
    );
  }

  @PostMapping("/tiktok/callback")
  public PublishingAccountDto handleTikTokCallback(@RequestBody Map<String, String> body) {
    String code = body.get("code");
    String state = body.get("state");

    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("Authorization code is required");
    }

    TikTokOAuthService.OAuthAttempt attempt = null;
    if (state != null && !state.isBlank()) {
      attempt = tikTokOAuthService.validateAndConsumeState(state, code);
    }

    try {
      PublishingAccountDto account = service.connectTikTokAccount(code);
      if (attempt != null) {
        tikTokOAuthService.recordAttemptCompletion(attempt.attemptId(), true, null);
      }
      return account;
    } catch (Exception e) {
      if (attempt != null) {
        tikTokOAuthService.recordAttemptCompletion(attempt.attemptId(), false, e.getMessage());
      }
      throw e;
    }
  }

  @GetMapping("/tiktok/callback")
  public void handleTikTokBrowserRedirect(
      @RequestParam(name = "code", required = false) String code,
      @RequestParam(name = "state", required = false) String state,
      @RequestParam(name = "error", required = false) String error,
      @RequestParam(name = "error_description", required = false) String errorDescription,
      HttpServletResponse response
  ) throws IOException {
    String frontendRedirect = determineFrontendUrl();

    if (error != null && !error.isBlank()) {
      String errParam = URLEncoder.encode(error, StandardCharsets.UTF_8);
      String descParam = errorDescription != null ? URLEncoder.encode(errorDescription, StandardCharsets.UTF_8) : "";
      response.sendRedirect(frontendRedirect + "?tiktok_error=" + errParam + "&error_description=" + descParam);
      return;
    }

    if (code == null || code.isBlank()) {
      response.sendRedirect(frontendRedirect + "?tiktok_error=missing_code");
      return;
    }

    TikTokOAuthService.OAuthAttempt attempt = null;
    if (state != null && !state.isBlank()) {
      try {
        attempt = tikTokOAuthService.validateAndConsumeState(state, code);
      } catch (Exception e) {
        response.sendRedirect(frontendRedirect + "?tiktok_error=" + URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8));
        return;
      }
    }

    try {
      service.connectTikTokAccount(code);
      if (attempt != null) {
        tikTokOAuthService.recordAttemptCompletion(attempt.attemptId(), true, null);
      }
      response.sendRedirect(frontendRedirect + "?tiktok_connected=true");
    } catch (Exception e) {
      if (attempt != null) {
        tikTokOAuthService.recordAttemptCompletion(attempt.attemptId(), false, e.getMessage());
      }
      response.sendRedirect(frontendRedirect + "?tiktok_error=" + URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8));
    }
  }

  private String determineFrontendUrl() {
    String redirectUri = tikTokClient.getProperties().getRedirectUri();
    if (redirectUri != null && redirectUri.contains("/publishing/tiktok/callback")) {
      return redirectUri.replace("/publishing/tiktok/callback", "");
    }
    return "/media-factory";
  }

  // --- ZIP Upload & Preview Workflow ---

  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public BatchPreview uploadZip(
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "batchName", required = false) String batchName,
      @RequestParam(value = "projectId", required = false) UUID projectId,
      @RequestParam(value = "accountId", required = false) UUID accountId) throws IOException {

    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("ZIP file is required");
    }

    UUID project = projectId != null ? projectId : getDefaultProjectId();
    return service.uploadAndAnalyzeZip(project, accountId, batchName, file.getInputStream(), file.getOriginalFilename());
  }

  @PutMapping("/tasks/{taskId}")
  public PublishingTaskDto updateTask(
      @PathVariable UUID taskId,
      @RequestBody Map<String, Object> body) {

    String caption = (String) body.get("caption");
    String privacyLevel = (String) body.get("privacyLevel");
    Boolean disableComment = (Boolean) body.get("disableComment");
    Boolean disableDuet = (Boolean) body.get("disableDuet");
    Boolean disableStitch = (Boolean) body.get("disableStitch");

    return service.updateTaskCaptionAndSettings(
        taskId, caption, privacyLevel, disableComment, disableDuet, disableStitch);
  }

  @PostMapping("/batches/{batchId}/publish")
  public BatchSummary confirmPublish(@PathVariable UUID batchId) {
    return service.confirmAndPublishBatch(batchId);
  }

  // --- Batch Controls ---

  @PostMapping("/batches/{batchId}/pause")
  public BatchSummary pauseBatch(@PathVariable UUID batchId) {
    return service.pauseBatch(batchId);
  }

  @PostMapping("/batches/{batchId}/resume")
  public BatchSummary resumeBatch(@PathVariable UUID batchId) {
    return service.resumeBatch(batchId);
  }

  @PostMapping("/batches/{batchId}/cancel")
  public BatchSummary cancelBatch(@PathVariable UUID batchId) {
    return service.cancelBatch(batchId);
  }

  @PostMapping("/batches/{batchId}/retry")
  public BatchSummary retryBatch(@PathVariable UUID batchId) {
    return service.retryFailedTasks(batchId);
  }

  // --- Batch & Task Queries ---

  @GetMapping("/batches")
  public List<BatchSummary> listBatches(
      @RequestParam(name = "projectId", required = false) UUID projectId,
      @RequestParam(name = "platform", required = false) String platform) {

    PublishingPlatform p = platform != null ? PublishingPlatform.valueOf(platform.toUpperCase(Locale.ROOT)) : null;
    return service.listBatches(projectId, p);
  }

  @GetMapping("/batches/{batchId}")
  public BatchSummary getBatch(@PathVariable UUID batchId) {
    return service.getBatchSummary(batchId);
  }

  @GetMapping("/batches/{batchId}/tasks")
  public List<PublishingTaskDto> listTasks(
      @PathVariable UUID batchId,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {

    return service.listBatchTasks(batchId, status, page, pageSize);
  }

  @GetMapping(value = "/tasks/{taskId}/thumbnail", produces = MediaType.IMAGE_PNG_VALUE)
  public ResponseEntity<byte[]> getThumbnail(@PathVariable UUID taskId) {
    byte[] thumb = service.getTaskThumbnail(taskId);
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
        .contentType(MediaType.IMAGE_PNG)
        .body(thumb);
  }

  @GetMapping(value = "/tasks/{taskId}/video", produces = "video/mp4")
  public ResponseEntity<byte[]> getVideo(@PathVariable UUID taskId) {
    byte[] video = service.getVideoBytes(taskId);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"video.mp4\"")
        .contentType(MediaType.parseMediaType("video/mp4"))
        .body(video);
  }

  // --- Mock upload endpoint for local testing ---
  @PutMapping(value = "/mock-upload/{publishId}")
  public ResponseEntity<Void> handleMockUpload(
      @PathVariable String publishId,
      @RequestBody(required = false) byte[] body) {
    return ResponseEntity.ok().build();
  }

  private UUID getDefaultProjectId() {
    var projects = service.database().sql("SELECT id FROM projects ORDER BY created_at ASC LIMIT 1")
        .query(UUID.class)
        .list();
    if (!projects.isEmpty()) {
      return projects.get(0);
    }
    UUID newId = UUID.randomUUID();
    service.database().sql("INSERT INTO projects (id, name, description, created_at) VALUES (?, 'Default Studio', 'Default studio project', now())")
        .param(newId)
        .update();
    return newId;
  }
}
