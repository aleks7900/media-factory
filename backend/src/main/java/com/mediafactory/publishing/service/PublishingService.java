package com.mediafactory.publishing.service;

import com.mediafactory.publishing.archive.TikTokArchiveParser;
import com.mediafactory.publishing.model.*;
import com.mediafactory.publishing.tiktok.TikTokClient;
import com.mediafactory.publishing.tiktok.TikTokResponses;
import com.mediafactory.storage.MediaStorage;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PublishingService {

  private static final Logger log = LoggerFactory.getLogger(PublishingService.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final JdbcClient db;
  private final TransactionTemplate tx;
  private final MediaStorage storage;
  private final TikTokClient tikTokClient;
  private final TikTokArchiveParser archiveParser;

  public PublishingService(
      JdbcClient db,
      TransactionTemplate tx,
      MediaStorage storage,
      TikTokClient tikTokClient,
      TikTokArchiveParser archiveParser) {
    this.db = db;
    this.tx = tx;
    this.storage = storage;
    this.tikTokClient = tikTokClient;
    this.archiveParser = archiveParser;
  }

  public JdbcClient database() {
    return db;
  }

  public TransactionTemplate transactions() {
    return tx;
  }

  public MediaStorage storage() {
    return storage;
  }

  public TikTokClient tikTokClient() {
    return tikTokClient;
  }

  // --- Account Management ---

  public PublishingAccountDto getActiveAccount(PublishingPlatform platform) {
    var accounts = db.sql(
        "SELECT id, platform, account_id, username, display_name, avatar_url, access_token, refresh_token, "
            + "token_expires_at, refresh_expires_at, scopes, privacy_level_options, comment_disabled, duet_disabled, "
            + "stitch_disabled, max_video_post_duration_sec, is_active, created_at, updated_at "
            + "FROM publishing_accounts WHERE platform = ? AND is_active = true ORDER BY updated_at DESC LIMIT 1")
        .param(platform.name())
        .query(this::mapAccount)
        .list();

    if (!accounts.isEmpty()) {
      return accounts.get(0).toDto();
    }

    return ensureDefaultAccount(platform).toDto();
  }

  public PublishingAccount ensureDefaultAccount(PublishingPlatform platform) {
    var existing = db.sql("SELECT * FROM publishing_accounts WHERE platform = ? AND account_id = 'default_studio'")
        .param(platform.name())
        .query(this::mapAccount)
        .list();

    if (!existing.isEmpty()) {
      return existing.get(0);
    }

    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    Instant tokenExp = now.plusSeconds(86400 * 30);
    Instant refreshExp = now.plusSeconds(86400 * 365);
    List<String> options = List.of("PUBLIC_TO_EVERYONE", "MUTUAL_FOLLOW_FRIENDS", "SELF_ONLY");

    db.sql("INSERT INTO publishing_accounts (id, platform, account_id, username, display_name, avatar_url, "
        + "access_token, refresh_token, token_expires_at, refresh_expires_at, scopes, privacy_level_options, "
        + "comment_disabled, duet_disabled, stitch_disabled, max_video_post_duration_sec, is_active, created_at, updated_at) "
        + "VALUES (?, ?, 'default_studio', 'mediafactory_studio', 'Media Factory Studio', "
        + "'https://p16-sign-va.tiktokcdn.com/mock-avatar.jpeg', 'mock_token', 'mock_refresh', ?, ?, "
        + "'user.info.basic,video.publish,video.upload', ?::jsonb, false, false, false, 600, true, ?, ?)")
        .params(id, platform.name(), Timestamp.from(tokenExp), Timestamp.from(refreshExp), toJson(options), Timestamp.from(now), Timestamp.from(now))
        .update();

    return new PublishingAccount(
        id, platform, "default_studio", "mediafactory_studio", "Media Factory Studio",
        "https://p16-sign-va.tiktokcdn.com/mock-avatar.jpeg", "mock_token", "mock_refresh",
        tokenExp, refreshExp, "user.info.basic,video.publish,video.upload", options,
        false, false, false, 600, true, now, now);
  }

  public PublishingAccountDto connectTikTokAccount(String code) {
    TikTokResponses.TokenResponse tokenRes = tikTokClient.exchangeCode(code);
    if (!tokenRes.isSuccess()) {
      throw new IllegalArgumentException("Failed to exchange TikTok code: "
          + (tokenRes.error() != null ? tokenRes.error().message() : "Unknown error"));
    }

    TikTokResponses.CreatorInfoResponse creatorRes = tikTokClient.queryCreatorInfo(tokenRes.accessToken());
    String username = "tiktok_creator";
    String displayName = "TikTok Creator";
    String avatarUrl = null;
    List<String> privacyOptions = List.of("SELF_ONLY");
    boolean commentDisabled = false;
    boolean duetDisabled = false;
    boolean stitchDisabled = false;
    int maxDuration = 600;

    if (creatorRes.isSuccess() && creatorRes.data() != null) {
      var d = creatorRes.data();
      if (d.creatorUsername() != null) username = d.creatorUsername();
      if (d.creatorNickname() != null) displayName = d.creatorNickname();
      avatarUrl = d.creatorAvatarUrl();
      if (d.privacyLevelOptions() != null && !d.privacyLevelOptions().isEmpty()) {
        privacyOptions = d.privacyLevelOptions();
      }
      commentDisabled = Boolean.TRUE.equals(d.commentDisabled());
      duetDisabled = Boolean.TRUE.equals(d.duetDisabled());
      stitchDisabled = Boolean.TRUE.equals(d.stitchDisabled());
      if (d.maxVideoPostDurationSec() != null) maxDuration = d.maxVideoPostDurationSec();
    }

    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    Instant tokenExp = now.plusSeconds(tokenRes.expiresIn() != null ? tokenRes.expiresIn() : 86400);
    Instant refreshExp = now.plusSeconds(tokenRes.refreshExpiresIn() != null ? tokenRes.refreshExpiresIn() : 31536000);

    String openId = tokenRes.openId() != null ? tokenRes.openId() : "open_id_" + UUID.randomUUID();

    db.sql("INSERT INTO publishing_accounts (id, platform, account_id, username, display_name, avatar_url, "
        + "access_token, refresh_token, token_expires_at, refresh_expires_at, scopes, privacy_level_options, "
        + "comment_disabled, duet_disabled, stitch_disabled, max_video_post_duration_sec, is_active, created_at, updated_at) "
        + "VALUES (?, 'TIKTOK', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, true, ?, ?) "
        + "ON CONFLICT (platform, account_id) DO UPDATE SET "
        + "username = EXCLUDED.username, display_name = EXCLUDED.display_name, avatar_url = EXCLUDED.avatar_url, "
        + "access_token = EXCLUDED.access_token, refresh_token = EXCLUDED.refresh_token, "
        + "token_expires_at = EXCLUDED.token_expires_at, refresh_expires_at = EXCLUDED.refresh_expires_at, "
        + "privacy_level_options = EXCLUDED.privacy_level_options, is_active = true, updated_at = EXCLUDED.updated_at")
        .params(id, openId, username, displayName, avatarUrl,
            tokenRes.accessToken(), tokenRes.refreshToken(), Timestamp.from(tokenExp), Timestamp.from(refreshExp),
            tokenRes.scope() != null ? tokenRes.scope() : "", toJson(privacyOptions),
            commentDisabled, duetDisabled, stitchDisabled, maxDuration, Timestamp.from(now), Timestamp.from(now))
        .update();

    return getActiveAccount(PublishingPlatform.TIKTOK);
  }

  public PublishingAccount getAccountById(UUID accountId) {
    return db.sql("SELECT * FROM publishing_accounts WHERE id = ?")
        .param(accountId)
        .query(this::mapAccount)
        .single();
  }

  public String getValidAccessToken(PublishingAccount account) {
    Instant now = Instant.now();
    if (account.tokenExpiresAt() == null || account.tokenExpiresAt().isBefore(now.plusSeconds(300))) {
      log.info("Refreshing TikTok access token for account={}", account.id());
      var tokenRes = tikTokClient.refreshToken(account.refreshToken());
      if (tokenRes.isSuccess() && tokenRes.accessToken() != null) {
        Instant newExp = now.plusSeconds(tokenRes.expiresIn() != null ? tokenRes.expiresIn() : 86400);
        Instant newRefreshExp = now.plusSeconds(tokenRes.refreshExpiresIn() != null ? tokenRes.refreshExpiresIn() : 31536000);
        String newRefresh = tokenRes.refreshToken() != null ? tokenRes.refreshToken() : account.refreshToken();

        db.sql("UPDATE publishing_accounts SET access_token = ?, refresh_token = ?, token_expires_at = ?, "
            + "refresh_expires_at = ?, updated_at = now() WHERE id = ?")
            .params(tokenRes.accessToken(), newRefresh, Timestamp.from(newExp), Timestamp.from(newRefreshExp), account.id())
            .update();

        return tokenRes.accessToken();
      }
    }
    return account.accessToken();
  }

  // --- Step 1: Upload ZIP & Analyze ---

  public BatchPreview uploadAndAnalyzeZip(
      UUID projectId,
      UUID accountId,
      String batchName,
      InputStream zipStream,
      String originalFilename) {

    TikTokArchiveParser.ParsedArchive parsed;
    try {
      parsed = archiveParser.parse(zipStream);
    } catch (Exception e) {
      log.error("Failed to parse publishing archive", e);
      throw new IllegalArgumentException("Invalid ZIP archive: " + e.getMessage());
    }

    PublishingAccount account = accountId != null ? getAccountById(accountId) : ensureDefaultAccount(PublishingPlatform.TIKTOK);
    UUID batchId = UUID.randomUUID();
    String archiveStorageKey = "publishing/archives/" + batchId + "/archive.zip";
    storage.putOriginal(archiveStorageKey, parsed.archiveBytes(), "application/zip");

    String name = (batchName != null && !batchName.isBlank()) ? batchName : "Batch " + originalFilename;
    String idempotencyKey = "batch-" + parsed.archiveSha256() + "-" + batchId.toString().substring(0, 8);

    int total = parsed.items().size();
    int validCount = (int) parsed.items().stream().filter(TikTokArchiveParser.ParsedVideoItem::isValid).count();
    int invalidCount = total - validCount;

    return tx.execute(status -> {
      db.sql("INSERT INTO publishing_batches (id, project_id, account_id, platform, name, status, "
          + "archive_key, archive_sha256, archive_name, idempotency_key, total_tasks, valid_tasks, invalid_tasks, "
          + "published_tasks, processing_tasks, queued_tasks, failed_tasks, cancelled_tasks, paused, cancelled, created_at) "
          + "VALUES (?, ?, ?, 'TIKTOK', ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, ?, 0, false, false, now())")
          .params(batchId, projectId, account.id(), name, archiveStorageKey, parsed.archiveSha256(),
              originalFilename != null ? originalFilename : "upload.zip", idempotencyKey,
              total, validCount, invalidCount, invalidCount)
          .update();

      List<PublishingTaskDto> taskDtos = new ArrayList<>();

      for (var item : parsed.items()) {
        UUID taskId = UUID.randomUUID();
        String videoKey = "publishing/videos/" + batchId + "/" + item.filename();
        storage.putOriginal(videoKey, item.videoBytes(), "video/mp4");

        String thumbKey = "publishing/thumbs/" + taskId + ".png";
        if (item.thumbnailBytes() != null && item.thumbnailBytes().length > 0) {
          storage.putOriginal(thumbKey, item.thumbnailBytes(), "image/png");
        }

        String taskStatus = item.isValid() ? "DRAFT" : "FAILED";
        String caption = item.metadata() != null && item.metadata().caption() != null
            ? item.metadata().caption()
            : item.filename();
        String privacy = item.metadata() != null && item.metadata().privacyLevel() != null
            ? item.metadata().privacyLevel()
            : (account.privacyLevelOptions().contains("PUBLIC_TO_EVERYONE") ? "PUBLIC_TO_EVERYONE" : "SELF_ONLY");

        boolean comment = item.metadata() != null && Boolean.TRUE.equals(item.metadata().disableComment());
        boolean duet = item.metadata() != null && Boolean.TRUE.equals(item.metadata().disableDuet());
        boolean stitch = item.metadata() != null && Boolean.TRUE.equals(item.metadata().disableStitch());
        long coverMs = item.metadata() != null && item.metadata().coverTimestampMs() != null
            ? item.metadata().coverTimestampMs() : 1000L;

        Double duration = item.mp4Info() != null ? item.mp4Info().durationSeconds() : null;
        Integer width = item.mp4Info() != null ? item.mp4Info().width() : null;
        Integer height = item.mp4Info() != null ? item.mp4Info().height() : null;

        db.sql("INSERT INTO publishing_tasks (id, batch_id, platform, video_filename, video_storage_key, "
            + "video_sha256, video_size_bytes, duration_seconds, width, height, caption, privacy_level, "
            + "disable_comment, disable_duet, disable_stitch, video_cover_timestamp_ms, thumbnail_storage_key, "
            + "status, validation_error, created_at) "
            + "VALUES (?, ?, 'TIKTOK', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())")
            .params(taskId, batchId, item.filename(), videoKey, item.sha256(), item.sizeBytes(),
                duration, width, height, caption, privacy, comment, duet, stitch, coverMs,
                thumbKey, taskStatus, item.validationError())
            .update();

        taskDtos.add(new PublishingTaskDto(
            taskId, batchId, "TIKTOK", item.filename(), item.sizeBytes(), duration, width, height,
            caption, privacy, comment, duet, stitch, taskStatus, item.validationError(),
            null, null, null, 0, null, null, Instant.now(), null));
      }

      recordEvent(batchId, null, "BATCH_UPLOADED_DRAFT", Map.of("total", total, "valid", validCount, "invalid", invalidCount));

      return new BatchPreview(batchId, name, "TIKTOK", account.toDto(), total, validCount, invalidCount, taskDtos);
    });
  }

  // --- Step 2: Edit captions and settings in DRAFT ---

  public PublishingTaskDto updateTaskCaptionAndSettings(
      UUID taskId,
      String caption,
      String privacyLevel,
      Boolean disableComment,
      Boolean disableDuet,
      Boolean disableStitch) {

    var task = getTaskById(taskId);
    if (!"DRAFT".equals(task.status()) && !"QUEUED".equals(task.status())) {
      throw new IllegalStateException("Cannot edit caption/settings for task in " + task.status() + " status");
    }

    String newCaption = caption != null ? caption : task.caption();
    String newPrivacy = privacyLevel != null ? privacyLevel : task.privacyLevel();
    boolean newComment = disableComment != null ? disableComment : task.disableComment();
    boolean newDuet = disableDuet != null ? disableDuet : task.disableDuet();
    boolean newStitch = disableStitch != null ? disableStitch : task.disableStitch();

    db.sql("UPDATE publishing_tasks SET caption = ?, privacy_level = ?, disable_comment = ?, "
        + "disable_duet = ?, disable_stitch = ? WHERE id = ?")
        .params(newCaption, newPrivacy, newComment, newDuet, newStitch, taskId)
        .update();

    return getTaskDto(taskId);
  }

  // --- Step 3: Confirm & Publish Batch ---

  public BatchSummary confirmAndPublishBatch(UUID batchId) {
    return tx.execute(status -> {
      var batch = getBatchSummary(batchId);
      if (!"DRAFT".equals(batch.status()) && !"PAUSED".equals(batch.status())) {
        throw new IllegalStateException("Batch is already in " + batch.status() + " status");
      }

      int queuedCount = db.sql("UPDATE publishing_tasks SET status = 'QUEUED', available_at = now() "
          + "WHERE batch_id = ? AND status = 'DRAFT' AND validation_error IS NULL")
          .param(batchId)
          .update();

      db.sql("UPDATE publishing_batches SET status = 'QUEUED', queued_tasks = ?, started_at = coalesce(started_at, now()) "
          + "WHERE id = ?")
          .params(queuedCount, batchId)
          .update();

      recordEvent(batchId, null, "CONFIRM_PUBLISH", Map.of("queuedTasks", queuedCount));

      return getBatchSummary(batchId);
    });
  }

  // --- Batch Controls ---

  public BatchSummary pauseBatch(UUID batchId) {
    db.sql("UPDATE publishing_batches SET paused = true, status = 'PAUSED' WHERE id = ?").param(batchId).update();
    recordEvent(batchId, null, "BATCH_PAUSED", Map.of());
    return getBatchSummary(batchId);
  }

  public BatchSummary resumeBatch(UUID batchId) {
    db.sql("UPDATE publishing_batches SET paused = false, status = 'RUNNING' WHERE id = ?").param(batchId).update();
    recordEvent(batchId, null, "BATCH_RESUMED", Map.of());
    return getBatchSummary(batchId);
  }

  public BatchSummary cancelBatch(UUID batchId) {
    return tx.execute(status -> {
      db.sql("UPDATE publishing_batches SET cancelled = true, status = 'CANCELLED' WHERE id = ?").param(batchId).update();
      db.sql("UPDATE publishing_tasks SET status = 'CANCELLED' WHERE batch_id = ? AND status IN ('DRAFT', 'QUEUED')")
          .param(batchId)
          .update();
      recordEvent(batchId, null, "BATCH_CANCELLED", Map.of());
      return getBatchSummary(batchId);
    });
  }

  public BatchSummary retryFailedTasks(UUID batchId) {
    return tx.execute(status -> {
      int retried = db.sql("UPDATE publishing_tasks SET status = 'QUEUED', attempts = 0, available_at = now(), "
          + "last_error_code = null, last_error_message = null WHERE batch_id = ? AND status = 'FAILED' AND validation_error IS NULL")
          .param(batchId)
          .update();

      db.sql("UPDATE publishing_batches SET status = 'RUNNING' WHERE id = ? AND status = 'FAILED'").param(batchId).update();
      recordEvent(batchId, null, "RETRY_FAILED_TASKS", Map.of("retriedCount", retried));
      return getBatchSummary(batchId);
    });
  }

  // --- Queries ---

  public BatchSummary getBatchSummary(UUID batchId) {
    return db.sql("SELECT b.id, b.project_id, b.account_id, a.username as account_username, b.platform, b.name, b.status, "
        + "b.total_tasks, "
        + "(SELECT count(*) FROM publishing_tasks WHERE batch_id = b.id AND status = 'PUBLISHED') as published_count, "
        + "(SELECT count(*) FROM publishing_tasks WHERE batch_id = b.id AND status IN ('UPLOADING', 'PROCESSING')) as processing_count, "
        + "(SELECT count(*) FROM publishing_tasks WHERE batch_id = b.id AND status = 'QUEUED') as queued_count, "
        + "(SELECT count(*) FROM publishing_tasks WHERE batch_id = b.id AND status = 'FAILED') as failed_count, "
        + "(SELECT count(*) FROM publishing_tasks WHERE batch_id = b.id AND status = 'CANCELLED') as cancelled_count, "
        + "(SELECT count(*) FROM publishing_tasks WHERE batch_id = b.id AND status = 'DRAFT') as draft_count, "
        + "b.paused, b.cancelled, b.created_at, b.completed_at "
        + "FROM publishing_batches b "
        + "JOIN publishing_accounts a ON a.id = b.account_id "
        + "WHERE b.id = ?")
        .param(batchId)
        .query(this::mapBatchSummary)
        .single();
  }

  public List<BatchSummary> listBatches(UUID projectId, PublishingPlatform platform) {
    String sql = "SELECT b.id, b.project_id, b.account_id, a.username as account_username, b.platform, b.name, b.status, "
        + "b.total_tasks, "
        + "(SELECT count(*) FROM publishing_tasks WHERE batch_id = b.id AND status = 'PUBLISHED') as published_count, "
        + "(SELECT count(*) FROM publishing_tasks WHERE batch_id = b.id AND status IN ('UPLOADING', 'PROCESSING')) as processing_count, "
        + "(SELECT count(*) FROM publishing_tasks WHERE batch_id = b.id AND status = 'QUEUED') as queued_count, "
        + "(SELECT count(*) FROM publishing_tasks WHERE batch_id = b.id AND status = 'FAILED') as failed_count, "
        + "(SELECT count(*) FROM publishing_tasks WHERE batch_id = b.id AND status = 'CANCELLED') as cancelled_count, "
        + "(SELECT count(*) FROM publishing_tasks WHERE batch_id = b.id AND status = 'DRAFT') as draft_count, "
        + "b.paused, b.cancelled, b.created_at, b.completed_at "
        + "FROM publishing_batches b "
        + "JOIN publishing_accounts a ON a.id = b.account_id "
        + "WHERE (?::uuid IS NULL OR b.project_id = ?) AND (?::varchar IS NULL OR b.platform = ?) "
        + "ORDER BY b.created_at DESC LIMIT 50";

    return db.sql(sql)
        .params(projectId, projectId, platform != null ? platform.name() : null, platform != null ? platform.name() : null)
        .query(this::mapBatchSummary)
        .list();
  }

  private BatchSummary mapBatchSummary(ResultSet rs, int rowNum) throws SQLException {
    return new BatchSummary(
        rs.getObject("id", UUID.class),
        rs.getObject("project_id", UUID.class),
        rs.getObject("account_id", UUID.class),
        rs.getString("account_username"),
        rs.getString("platform"),
        rs.getString("name"),
        rs.getString("status"),
        rs.getInt("total_tasks"),
        rs.getInt("published_count"),
        rs.getInt("processing_count"),
        rs.getInt("queued_count"),
        rs.getInt("failed_count"),
        rs.getInt("cancelled_count"),
        rs.getInt("draft_count"),
        rs.getBoolean("paused"),
        rs.getBoolean("cancelled"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toInstant() : null
    );
  }

  public List<PublishingTaskDto> listBatchTasks(UUID batchId, String statusFilter, int page, int pageSize) {
    int offset = Math.max(0, page) * Math.max(1, pageSize);
    String sql = "SELECT id, batch_id, platform, video_filename, video_size_bytes, duration_seconds, "
        + "width, height, caption, privacy_level, disable_comment, disable_duet, disable_stitch, "
        + "status, validation_error, publish_id, post_id, post_url, attempts, last_error_code, "
        + "last_error_message, created_at, completed_at "
        + "FROM publishing_tasks WHERE batch_id = ? "
        + (statusFilter != null && !statusFilter.isBlank() ? "AND status = ? " : "")
        + "ORDER BY created_at ASC, id ASC LIMIT ? OFFSET ?";

    var builder = db.sql(sql).param(batchId);
    if (statusFilter != null && !statusFilter.isBlank()) {
      builder.param(statusFilter);
    }
    builder.param(pageSize).param(offset);

    return builder.query(this::mapTaskDto).list();
  }

  public byte[] getTaskThumbnail(UUID taskId) {
    var task = getTaskById(taskId);
    if (task.thumbnailStorageKey() != null) {
      return storage.read(task.thumbnailStorageKey());
    }
    return TikTokArchiveParser.generateThumbnail(task.videoFilename());
  }

  public byte[] getVideoBytes(UUID taskId) {
    var task = getTaskById(taskId);
    if (task.videoStorageKey() != null) {
      return storage.read(task.videoStorageKey());
    }
    throw new IllegalArgumentException("No video content stored for task " + taskId);
  }

  public PublishingTaskDto createTaskFromGeneration(
      UUID batchId,
      UUID generationId,
      String caption,
      String privacyLevel) {

    return tx.execute(status -> {
      var genRow = db.sql("SELECT g.id, a.id as asset_id, a.storage_key, a.sha256, a.width, a.height "
          + "FROM generations g LEFT JOIN assets a ON a.generation_id = g.id WHERE g.id = ?")
          .param(generationId)
          .query()
          .singleRow();

      UUID assetId = (UUID) genRow.get("asset_id");
      String storageKey = (String) genRow.get("storage_key");
      String sha256 = (String) genRow.get("sha256");
      Integer width = (Integer) genRow.get("width");
      Integer height = (Integer) genRow.get("height");

      byte[] videoBytes = storage.read(storageKey);
      String filename = "gen_" + generationId.toString().substring(0, 8) + ".mp4";
      UUID taskId = UUID.randomUUID();

      String videoKey = "publishing/videos/" + batchId + "/" + filename;
      storage.putOriginal(videoKey, videoBytes, "video/mp4");

      byte[] thumb = TikTokArchiveParser.generateThumbnail(caption != null ? caption : filename);
      String thumbKey = "publishing/thumbs/" + taskId + ".png";
      storage.putOriginal(thumbKey, thumb, "image/png");

      db.sql("INSERT INTO publishing_tasks (id, batch_id, platform, video_filename, video_storage_key, "
          + "video_sha256, video_size_bytes, width, height, caption, privacy_level, "
          + "source_generation_id, source_asset_id, thumbnail_storage_key, status, available_at, created_at) "
          + "VALUES (?, ?, 'TIKTOK', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', now(), now())")
          .params(taskId, batchId, filename, videoKey, sha256, (long) videoBytes.length,
              width, height, caption, privacyLevel != null ? privacyLevel : "SELF_ONLY",
              generationId, assetId, thumbKey)
          .update();

      db.sql("UPDATE publishing_batches SET total_tasks = total_tasks + 1, valid_tasks = valid_tasks + 1, "
          + "queued_tasks = queued_tasks + 1 WHERE id = ?")
          .param(batchId)
          .update();

      return getTaskDto(taskId);
    });
  }

  public void recordEvent(UUID batchId, UUID taskId, String action, Map<String, Object> detail) {
    db.sql("INSERT INTO publishing_events (batch_id, task_id, action, detail, created_at) VALUES (?, ?, ?, ?::jsonb, now())")
        .params(batchId, taskId, action, toJson(detail))
        .update();
  }

  private record InternalTask(
      UUID id, UUID batchId, String platform, String videoFilename, String videoStorageKey,
      String caption, String privacyLevel, boolean disableComment, boolean disableDuet,
      boolean disableStitch, String thumbnailStorageKey, String status) {}

  private InternalTask getTaskById(UUID taskId) {
    return db.sql("SELECT id, batch_id, platform, video_filename, video_storage_key, caption, "
        + "privacy_level, disable_comment, disable_duet, disable_stitch, thumbnail_storage_key, status "
        + "FROM publishing_tasks WHERE id = ?")
        .param(taskId)
        .query((rs, rowNum) -> new InternalTask(
            rs.getObject("id", UUID.class),
            rs.getObject("batch_id", UUID.class),
            rs.getString("platform"),
            rs.getString("video_filename"),
            rs.getString("video_storage_key"),
            rs.getString("caption"),
            rs.getString("privacy_level"),
            rs.getBoolean("disable_comment"),
            rs.getBoolean("disable_duet"),
            rs.getBoolean("disable_stitch"),
            rs.getString("thumbnail_storage_key"),
            rs.getString("status")
        ))
        .single();
  }

  private PublishingTaskDto getTaskDto(UUID taskId) {
    return db.sql("SELECT id, batch_id, platform, video_filename, video_size_bytes, duration_seconds, "
        + "width, height, caption, privacy_level, disable_comment, disable_duet, disable_stitch, "
        + "status, validation_error, publish_id, post_id, post_url, attempts, last_error_code, "
        + "last_error_message, created_at, completed_at FROM publishing_tasks WHERE id = ?")
        .param(taskId)
        .query(this::mapTaskDto)
        .single();
  }

  private PublishingTaskDto mapTaskDto(ResultSet rs, int rowNum) throws SQLException {
    return new PublishingTaskDto(
        rs.getObject("id", UUID.class),
        rs.getObject("batch_id", UUID.class),
        rs.getString("platform"),
        rs.getString("video_filename"),
        rs.getLong("video_size_bytes"),
        rs.getObject("duration_seconds") != null ? rs.getDouble("duration_seconds") : null,
        rs.getObject("width") != null ? rs.getInt("width") : null,
        rs.getObject("height") != null ? rs.getInt("height") : null,
        rs.getString("caption"),
        rs.getString("privacy_level"),
        rs.getBoolean("disable_comment"),
        rs.getBoolean("disable_duet"),
        rs.getBoolean("disable_stitch"),
        rs.getString("status"),
        rs.getString("validation_error"),
        rs.getString("publish_id"),
        rs.getString("post_id"),
        rs.getString("post_url"),
        rs.getInt("attempts"),
        rs.getString("last_error_code"),
        rs.getString("last_error_message"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toInstant() : null
    );
  }

  @SuppressWarnings("unchecked")
  private PublishingAccount mapAccount(ResultSet rs, int rowNum) throws SQLException {
    List<String> options = List.of("SELF_ONLY");
    String optsJson = rs.getString("privacy_level_options");
    if (optsJson != null) {
      try {
        List<?> parsed = JSON.readValue(optsJson, List.class);
        if (parsed != null) {
          options = (List<String>) parsed;
        }
      } catch (Exception ignored) {}
    }

    return new PublishingAccount(
        rs.getObject("id", UUID.class),
        PublishingPlatform.valueOf(rs.getString("platform")),
        rs.getString("account_id"),
        rs.getString("username"),
        rs.getString("display_name"),
        rs.getString("avatar_url"),
        rs.getString("access_token"),
        rs.getString("refresh_token"),
        rs.getTimestamp("token_expires_at").toInstant(),
        rs.getTimestamp("refresh_expires_at") != null ? rs.getTimestamp("refresh_expires_at").toInstant() : null,
        rs.getString("scopes"),
        options,
        rs.getBoolean("comment_disabled"),
        rs.getBoolean("duet_disabled"),
        rs.getBoolean("stitch_disabled"),
        rs.getInt("max_video_post_duration_sec"),
        rs.getBoolean("is_active"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant()
    );
  }

  private static String toJson(Object obj) {
    try {
      return JSON.writeValueAsString(obj);
    } catch (Exception e) {
      return "{}";
    }
  }
}
