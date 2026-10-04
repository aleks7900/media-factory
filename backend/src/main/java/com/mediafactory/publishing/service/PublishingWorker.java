package com.mediafactory.publishing.service;

import com.mediafactory.publishing.model.PublishingAccount;
import com.mediafactory.publishing.tiktok.TikTokApiException;
import com.mediafactory.publishing.tiktok.TikTokClient;
import com.mediafactory.publishing.tiktok.TikTokResponses;
import com.mediafactory.storage.MediaStorage;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "publishing.worker.enabled", havingValue = "true", matchIfMissing = true)
public class PublishingWorker implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(PublishingWorker.class);

  private final PublishingService service;
  private final TikTokClient tikTokClient;
  private final MediaStorage storage;
  private final ExecutorService pool;
  private final int concurrency;
  private final int maxAttempts;
  private final long initialDelayMs;
  private final long maxDelayMs;

  private final Set<UUID> active = ConcurrentHashMap.newKeySet();
  private final Map<UUID, UUID> leases = new ConcurrentHashMap<>();

  public PublishingWorker(
      PublishingService service,
      TikTokClient tikTokClient,
      MediaStorage storage,
      @Value("${publishing.worker.concurrency:2}") int concurrency,
      @Value("${publishing.worker.max-attempts:5}") int maxAttempts,
      @Value("${publishing.worker.retry.initial-delay-ms:2000}") long initialDelayMs,
      @Value("${publishing.worker.retry.max-delay-ms:60000}") long maxDelayMs) {
    this.service = service;
    this.tikTokClient = tikTokClient;
    this.storage = storage;
    this.concurrency = Math.max(1, Math.min(8, concurrency));
    this.maxAttempts = Math.max(1, Math.min(10, maxAttempts));
    this.initialDelayMs = Math.max(500, initialDelayMs);
    this.maxDelayMs = Math.max(5000, maxDelayMs);
    this.pool = Executors.newFixedThreadPool(this.concurrency);
  }

  @Scheduled(fixedDelay = 30000)
  public void heartbeat() {
    leases.forEach((id, token) ->
        service.database()
            .sql("UPDATE publishing_tasks SET lease_until = now() + interval '5 minutes' "
                + "WHERE id = ? AND lease_token = ? AND status IN ('UPLOADING', 'PROCESSING')")
            .params(id, token)
            .update());
  }

  @Scheduled(fixedDelayString = "${publishing.worker.poll-ms:1000}")
  public void tick() {
    while (active.size() < concurrency) {
      var task = claim();
      if (task == null) {
        return;
      }

      UUID taskId = (UUID) task.get("id");
      active.add(taskId);
      leases.put(taskId, (UUID) task.get("lease_token"));

      pool.submit(() -> {
        try {
          processTask(task);
        } catch (Exception e) {
          log.error("Unhandled error processing publishing task {}", taskId, e);
        } finally {
          active.remove(taskId);
          leases.remove(taskId);
          service.database()
              .sql("UPDATE publishing_tasks SET lease_token = null, lease_until = null WHERE id = ? AND lease_token = ?")
              .params(taskId, task.get("lease_token"))
              .update();
        }
      });
    }
  }

  Map<String, Object> claim() {
    return service.transactions().execute(tx -> {
      service.database().sql("SELECT pg_advisory_xact_lock(hashtext('publishing-dispatch'))").query().singleRow();

      var rows = service.database().sql(
          "SELECT t.id FROM publishing_tasks t "
              + "JOIN publishing_batches b ON b.id = t.batch_id "
              + "WHERE t.available_at <= now() "
              + "AND (t.lease_until IS NULL OR t.lease_until < now()) "
              + "AND NOT b.paused AND NOT b.cancelled "
              + "AND t.status IN ('QUEUED', 'UPLOADING', 'PROCESSING') "
              + "ORDER BY CASE WHEN t.status = 'PROCESSING' THEN 0 WHEN t.status = 'UPLOADING' THEN 1 ELSE 2 END, "
              + "t.available_at ASC, t.created_at ASC "
              + "FOR UPDATE OF t SKIP LOCKED LIMIT 1")
          .query(UUID.class)
          .list();

      if (rows.isEmpty()) {
        return null;
      }

      UUID taskId = rows.get(0);
      UUID leaseToken = UUID.randomUUID();

      service.database().sql(
          "UPDATE publishing_tasks SET lease_token = ?, lease_until = now() + interval '5 minutes', "
              + "started_at = coalesce(started_at, now()) WHERE id = ?")
          .params(leaseToken, taskId)
          .update();

      var taskData = service.database().sql(
          "SELECT t.*, b.account_id, a.username as account_username FROM publishing_tasks t "
              + "JOIN publishing_batches b ON b.id = t.batch_id "
              + "JOIN publishing_accounts a ON a.id = b.account_id "
              + "WHERE t.id = ?")
          .param(taskId)
          .query()
          .singleRow();

      var map = new LinkedHashMap<>(taskData);
      map.put("lease_token", leaseToken);
      return map;
    });
  }

  void processTask(Map<String, Object> task) {
    UUID taskId = (UUID) task.get("id");
    UUID batchId = (UUID) task.get("batch_id");
    UUID accountId = (UUID) task.get("account_id");
    String accountUsername = (String) task.get("account_username");
    String status = (String) task.get("status");
    String publishId = (String) task.get("publish_id");
    int attempts = ((Number) task.getOrDefault("attempts", 0)).intValue();

    PublishingAccount account = service.getAccountById(accountId);
    String accessToken = service.getValidAccessToken(account);

    try {
      if ("QUEUED".equals(status) || "UPLOADING".equals(status)) {
        // Step A: Upload video
        executeUpload(taskId, batchId, account, accessToken, task);
      } else if ("PROCESSING".equals(status)) {
        // Step B: Poll TikTok status
        executeStatusPoll(taskId, batchId, accountUsername, accessToken, publishId, attempts);
      }
    } catch (TikTokApiException e) {
      handleApiError(taskId, batchId, attempts, e);
    } catch (Exception e) {
      log.error("Unexpected error in publishing task {}", taskId, e);
      handleApiError(taskId, batchId, attempts, new TikTokApiException(0, "internal_error", e.getMessage(), true, 3000L));
    }
  }

  private void executeUpload(
      UUID taskId,
      UUID batchId,
      PublishingAccount account,
      String accessToken,
      Map<String, Object> task) {

    String publishId = (String) task.get("publish_id");
    String uploadUrl = (String) task.get("upload_url");
    String storageKey = (String) task.get("video_storage_key");
    String caption = (String) task.get("caption");
    String privacyLevel = (String) task.get("privacy_level");
    boolean disableComment = Boolean.TRUE.equals(task.get("disable_comment"));
    boolean disableDuet = Boolean.TRUE.equals(task.get("disable_duet"));
    boolean disableStitch = Boolean.TRUE.equals(task.get("disable_stitch"));
    Long coverTimestampMs = task.get("video_cover_timestamp_ms") != null
        ? ((Number) task.get("video_cover_timestamp_ms")).longValue() : 1000L;

    byte[] videoBytes = storage.read(storageKey);
    long videoSize = videoBytes.length;

    // 1. If we don't have a publish_id yet, initialize upload with TikTok
    if (publishId == null || publishId.isBlank() || uploadUrl == null || uploadUrl.isBlank()) {
      service.database().sql(
          "UPDATE publishing_tasks SET status = 'UPLOADING', attempts = attempts + 1 WHERE id = ?")
          .param(taskId)
          .update();

      log.info("Initializing TikTok upload for taskId={}, size={}", taskId, videoSize);
      var initRes = tikTokClient.initVideoUpload(
          accessToken, caption, privacyLevel, disableComment, disableDuet, disableStitch,
          coverTimestampMs, videoSize, videoSize, 1);

      if (!initRes.isSuccess() || initRes.data() == null) {
        String errCode = initRes.error() != null ? initRes.error().code() : "INIT_FAILED";
        String errMsg = initRes.error() != null ? initRes.error().message() : "Failed to initialize upload";
        throw new TikTokApiException(400, errCode, errMsg, false, null);
      }

      publishId = initRes.data().publishId();
      uploadUrl = initRes.data().uploadUrl();

      // Persist publish_id IMMEDIATELY so backend restart won't re-upload/duplicate post
      service.database().sql(
          "UPDATE publishing_tasks SET publish_id = ?, upload_url = ? WHERE id = ?")
          .params(publishId, uploadUrl, taskId)
          .update();

      service.recordEvent(batchId, taskId, "TIKTOK_INIT_SUCCESS", Map.of("publishId", publishId));
    }

    // 2. Upload video binary data
    log.info("Uploading video bytes for taskId={}, publishId={}", taskId, publishId);
    tikTokClient.uploadVideoChunk(uploadUrl, videoBytes, 0, videoSize - 1, videoSize);

    // 3. Transition to PROCESSING and schedule status check in 3 seconds
    service.database().sql(
        "UPDATE publishing_tasks SET status = 'PROCESSING', available_at = now() + interval '3 seconds' WHERE id = ?")
        .param(taskId)
        .update();

    service.recordEvent(batchId, taskId, "UPLOAD_COMPLETED", Map.of("publishId", publishId));
  }

  private void executeStatusPoll(
      UUID taskId,
      UUID batchId,
      String accountUsername,
      String accessToken,
      String publishId,
      int attempts) {

    log.info("Polling TikTok publish status for taskId={}, publishId={}", taskId, publishId);
    var statusRes = tikTokClient.fetchPublishStatus(accessToken, publishId);

    if (!statusRes.isSuccess() || statusRes.data() == null) {
      throw new TikTokApiException(500, "status_poll_error", "Failed to retrieve status from TikTok", true, 3000L);
    }

    String remoteStatus = statusRes.data().status();
    log.info("Publish status for taskId={} is {}", taskId, remoteStatus);

    if ("PUBLISH_COMPLETE".equalsIgnoreCase(remoteStatus)) {
      String postId = statusRes.getPostId();
      String postUrl = (postId != null && accountUsername != null)
          ? "https://www.tiktok.com/@" + accountUsername + "/video/" + postId
          : null;

      service.transactions().execute(tx -> {
        service.database().sql(
            "UPDATE publishing_tasks SET status = 'PUBLISHED', post_id = ?, post_url = ?, "
                + "completed_at = now(), last_error_code = null, last_error_message = null WHERE id = ?")
            .params(postId, postUrl, taskId)
            .update();

        service.recordEvent(batchId, taskId, "PUBLISHED", Map.of("postId", postId != null ? postId : "", "postUrl", postUrl != null ? postUrl : ""));

        checkBatchCompletion(batchId);
        return null;
      });

    } else if ("FAILED".equalsIgnoreCase(remoteStatus)) {
      String failReason = statusRes.data().failReason() != null ? statusRes.data().failReason() : "Publishing rejected by TikTok";
      service.transactions().execute(tx -> {
        service.database().sql(
            "UPDATE publishing_tasks SET status = 'FAILED', last_error_code = 'TIKTOK_REJECTED', "
                + "last_error_message = ?, completed_at = now() WHERE id = ?")
            .params(failReason, taskId)
            .update();

        service.recordEvent(batchId, taskId, "PUBLISH_FAILED", Map.of("reason", failReason));
        checkBatchCompletion(batchId);
        return null;
      });

    } else {
      // Still in PROGRESS (PROCESSING_UPLOAD or PROCESSING_DOWNLOAD)
      // Schedule next poll in 4 seconds
      service.database().sql(
          "UPDATE publishing_tasks SET available_at = now() + interval '4 seconds' WHERE id = ?")
          .param(taskId)
          .update();
    }
  }

  private void handleApiError(UUID taskId, UUID batchId, int attempts, TikTokApiException e) {
    boolean retry = e.isRetryable() && attempts < maxAttempts;
    long delayMs = e.getRetryAfterMs() != null
        ? e.getRetryAfterMs()
        : Math.min(maxDelayMs, (long) (initialDelayMs * Math.pow(2, attempts)));

    log.warn("TikTok API error for taskId={}: status={} code={} retryable={} retryInMs={} msg={}",
        taskId, e.getStatusCode(), e.getErrorCode(), retry, delayMs, e.getMessage());

    service.transactions().execute(tx -> {
      if (retry) {
        service.database().sql(
            "UPDATE publishing_tasks SET attempts = attempts + 1, "
                + "available_at = now() + (? * interval '1 millisecond'), "
                + "last_error_code = ?, last_error_message = ? WHERE id = ?")
            .params(delayMs, e.getErrorCode(), e.getMessage(), taskId)
            .update();

        service.recordEvent(batchId, taskId, "RETRY_SCHEDULED",
            Map.of("attempt", attempts + 1, "delayMs", delayMs, "error", e.getMessage()));
      } else {
        service.database().sql(
            "UPDATE publishing_tasks SET status = 'FAILED', completed_at = now(), "
                + "last_error_code = ?, last_error_message = ? WHERE id = ?")
            .params(e.getErrorCode(), e.getMessage(), taskId)
            .update();

        service.recordEvent(batchId, taskId, "TASK_FAILED",
            Map.of("error", e.getMessage(), "code", e.getErrorCode()));

        checkBatchCompletion(batchId);
      }
      return null;
    });
  }

  private void checkBatchCompletion(UUID batchId) {
    var counts = service.database().sql(
        "SELECT count(*) filter (where status in ('QUEUED', 'UPLOADING', 'PROCESSING')) as pending_count, "
            + "count(*) filter (where status = 'FAILED') as failed_count, "
            + "count(*) filter (where status = 'PUBLISHED') as published_count "
            + "FROM publishing_tasks WHERE batch_id = ?")
        .param(batchId)
        .query()
        .singleRow();

    long pending = ((Number) counts.get("pending_count")).longValue();
    long failed = ((Number) counts.get("failed_count")).longValue();
    long published = ((Number) counts.get("published_count")).longValue();

    if (pending == 0) {
      String finalStatus = (published > 0) ? "COMPLETED" : (failed > 0 ? "FAILED" : "COMPLETED");
      service.database().sql(
          "UPDATE publishing_batches SET status = ?, completed_at = now() WHERE id = ? AND status != 'CANCELLED'")
          .params(finalStatus, batchId)
          .update();

      service.recordEvent(batchId, null, "BATCH_FINISHED", Map.of("finalStatus", finalStatus, "published", published, "failed", failed));
    }
  }

  @Override
  public void close() {
    pool.shutdown();
    try {
      if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
        pool.shutdownNow();
      }
    } catch (InterruptedException e) {
      pool.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
