package com.mediafactory.publishing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mediafactory.publishing.TikTokTestMediaHelper;
import com.mediafactory.publishing.model.*;
import com.mediafactory.publishing.service.PublishingService;
import com.mediafactory.publishing.service.PublishingWorker;
import com.mediafactory.publishing.tiktok.TikTokClient;
import com.mediafactory.publishing.tiktok.TikTokProperties;
import com.mediafactory.storage.MediaStorage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers
@SpringBootTest(properties = {
    "media.worker.enabled=false",
    "publishing.worker.enabled=false",
    "tiktok.enabled=false"
})
class PublishingIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

  @Autowired
  PublishingService service;

  @Autowired
  TikTokClient tikTokClient;

  @Autowired
  JdbcClient db;

  @MockitoBean
  MediaStorage storage;

  private final Map<String, byte[]> storageFiles = new ConcurrentHashMap<>();
  private UUID projectId;
  private PublishingWorker worker;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @BeforeEach
  void setUp() {
    db.sql("TRUNCATE publishing_events, publishing_tasks, publishing_batches, publishing_accounts, projects CASCADE").update();

    projectId = UUID.randomUUID();
    db.sql("INSERT INTO projects (id, name, description) VALUES (?, 'Publishing Test Studio', 'Studio test')")
        .param(projectId)
        .update();

    storageFiles.clear();
    doAnswer(call -> {
      String key = call.getArgument(0);
      byte[] bytes = call.getArgument(1);
      storageFiles.put(key, bytes);
      return null;
    }).when(storage).putOriginal(anyString(), any(byte[].class), anyString());

    when(storage.read(anyString())).thenAnswer(call -> {
      String key = call.getArgument(0);
      byte[] bytes = storageFiles.get(key);
      if (bytes == null) {
        throw new IllegalArgumentException("File not found in storage: " + key);
      }
      return bytes;
    });

    worker = new PublishingWorker(service, tikTokClient, storage, 4, 5, 500, 10000);
  }

  @AfterEach
  void tearDown() {
    if (worker != null) {
      worker.close();
    }
  }

  static byte[] zip(Map<String, byte[]> files) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
      for (var entry : files.entrySet()) {
        zos.putNextEntry(new ZipEntry(entry.getKey()));
        zos.write(entry.getValue());
        zos.closeEntry();
      }
    }
    return out.toByteArray();
  }

  @Test
  void completeWorkflowUploadToPublishWithSafetyGates() throws Exception {
    byte[] mp4A = TikTokTestMediaHelper.createValidMp4(15, 1080, 1920, 2048);
    byte[] mp4B = TikTokTestMediaHelper.createValidMp4(20, 1080, 1920, 2048);

    String metadata = """
        {
          "car_a.mp4": {
            "caption": "Car A Drift #cars",
            "privacy_level": "PUBLIC_TO_EVERYONE"
          },
          "car_b.mp4": {
            "caption": "Car B Race #racing",
            "privacy_level": "SELF_ONLY"
          }
        }
        """;

    Map<String, byte[]> archiveFiles = Map.of(
        "car_a.mp4", mp4A,
        "car_b.mp4", mp4B,
        "metadata.json", metadata.getBytes(StandardCharsets.UTF_8)
    );

    byte[] zipBytes = zip(archiveFiles);

    // 1. Upload & Analyze: creates DRAFT batch & tasks
    var preview = service.uploadAndAnalyzeZip(
        projectId, null, "Cars October", new ByteArrayInputStream(zipBytes), "cars_october.zip");

    assertThat(preview.batchName()).isEqualTo("Cars October");
    assertThat(preview.totalCount()).isEqualTo(2);
    assertThat(preview.validCount()).isEqualTo(2);
    assertThat(preview.invalidCount()).isEqualTo(0);
    assertThat(preview.tasks()).hasSize(2);

    UUID batchId = preview.batchId();
    var batchDraft = service.getBatchSummary(batchId);
    assertThat(batchDraft.status()).isEqualTo("DRAFT");
    assertThat(batchDraft.draft()).isEqualTo(2);
    assertThat(batchDraft.queued()).isEqualTo(0);

    // Verify worker tick does NOT touch DRAFT tasks
    worker.tick();
    var batchStillDraft = service.getBatchSummary(batchId);
    assertThat(batchStillDraft.status()).isEqualTo("DRAFT");

    // 2. Edit captions/settings in DRAFT
    UUID taskAId = preview.tasks().get(0).id();
    service.updateTaskCaptionAndSettings(taskAId, "Updated Car A Caption #cinematic", "PUBLIC_TO_EVERYONE", false, false, false);

    // 3. Confirm Publish
    var batchQueued = service.confirmAndPublishBatch(batchId);
    assertThat(batchQueued.status()).isEqualTo("QUEUED");
    assertThat(batchQueued.queued()).isEqualTo(2);

    // 4. Run worker ticks to process tasks: UPLOAD -> PROCESSING -> PUBLISHED
    // Tick 1: Claims tasks, initializes upload, stores publish_id, uploads bytes, sets PROCESSING
    worker.tick();
    Thread.sleep(100);

    // Verify tasks have publish_id saved in DB
    var tasksAfterUpload = service.listBatchTasks(batchId, null, 0, 10);
    assertThat(tasksAfterUpload).allSatisfy(t -> {
      assertThat(t.publishId()).isNotBlank();
    });

    // Advance available_at so polling is immediately eligible
    db.sql("UPDATE publishing_tasks SET available_at = now() - interval '1 second' WHERE batch_id = ?")
        .param(batchId)
        .update();

    // Tick 2: Polls status (mock transitions from PROCESSING_UPLOAD to PUBLISH_COMPLETE on 2nd poll)
    worker.tick();
    Thread.sleep(100);

    db.sql("UPDATE publishing_tasks SET available_at = now() - interval '1 second' WHERE batch_id = ?")
        .param(batchId)
        .update();

    // Tick 3: Finalizes PUBLISHED
    worker.tick();
    Thread.sleep(100);

    var finalBatch = service.getBatchSummary(batchId);
    assertThat(finalBatch.published()).isEqualTo(2);
    assertThat(finalBatch.status()).isEqualTo("COMPLETED");

    var finalTasks = service.listBatchTasks(batchId, null, 0, 10);
    assertThat(finalTasks).allSatisfy(t -> {
      assertThat(t.status()).isEqualTo("PUBLISHED");
      assertThat(t.postId()).isNotBlank();
      assertThat(t.postUrl()).contains("tiktok.com");
    });
  }

  @Test
  void duplicatePostPreventionAndRestartRecovery() throws Exception {
    byte[] mp4 = TikTokTestMediaHelper.createValidMp4(15, 1080, 1920, 2048);
    byte[] zipBytes = zip(Map.of("safe_video.mp4", mp4));

    var preview = service.uploadAndAnalyzeZip(
        projectId, null, "Restart Test", new ByteArrayInputStream(zipBytes), "restart.zip");

    UUID batchId = preview.batchId();
    service.confirmAndPublishBatch(batchId);

    // Simulate crash after init: task has publish_id saved, status = PROCESSING
    UUID taskId = preview.tasks().get(0).id();
    String existingPublishId = "v_pub_url~v2.persistent_crash_id";
    db.sql("UPDATE publishing_tasks SET status = 'PROCESSING', publish_id = ?, upload_url = 'http://test', attempts = 1, available_at = now() - interval '1 second' WHERE id = ?")
        .params(existingPublishId, taskId)
        .update();

    // Simulate backend restart by creating a new worker instance
    try (PublishingWorker restartedWorker = new PublishingWorker(service, tikTokClient, storage, 2, 5, 500, 10000)) {
      restartedWorker.tick();
      Thread.sleep(100);

      db.sql("UPDATE publishing_tasks SET available_at = now() - interval '1 second' WHERE id = ?").param(taskId).update();
      restartedWorker.tick();
      Thread.sleep(100);

      var task = service.listBatchTasks(batchId, null, 0, 10).get(0);
      assertThat(task.status()).isEqualTo("PUBLISHED");
      // Verify publish_id was preserved and never re-initialized
      assertThat(task.publishId()).isEqualTo(existingPublishId);
      assertThat(task.postId()).isNotBlank();
    }
  }

  @Test
  void batchControlsPauseResumeCancelRetry() throws Exception {
    byte[] mp4 = TikTokTestMediaHelper.createValidMp4(15, 1080, 1920, 2048);
    byte[] zipBytes = zip(Map.of("task1.mp4", mp4, "task2.mp4", mp4));

    var preview = service.uploadAndAnalyzeZip(
        projectId, null, "Controls Test", new ByteArrayInputStream(zipBytes), "controls.zip");
    UUID batchId = preview.batchId();
    service.confirmAndPublishBatch(batchId);

    // 1. Pause
    service.pauseBatch(batchId);
    var paused = service.getBatchSummary(batchId);
    assertThat(paused.paused()).isTrue();
    assertThat(paused.status()).isEqualTo("PAUSED");

    // Worker should not claim paused tasks
    worker.tick();
    var tasksAfterPause = service.listBatchTasks(batchId, null, 0, 10);
    assertThat(tasksAfterPause).allMatch(t -> t.status().equals("QUEUED"));

    // 2. Resume
    service.resumeBatch(batchId);
    var resumed = service.getBatchSummary(batchId);
    assertThat(resumed.paused()).isFalse();
    assertThat(resumed.status()).isEqualTo("RUNNING");

    // 3. Retry Failed
    db.sql("UPDATE publishing_tasks SET status = 'FAILED', last_error_code = 'TIMEOUT' WHERE batch_id = ?")
        .param(batchId)
        .update();

    service.retryFailedTasks(batchId);
    var retriedTasks = service.listBatchTasks(batchId, null, 0, 10);
    assertThat(retriedTasks).allMatch(t -> t.status().equals("QUEUED") && t.attempts() == 0);

    // 4. Cancel
    service.cancelBatch(batchId);
    var cancelled = service.getBatchSummary(batchId);
    assertThat(cancelled.isCancelled()).isTrue();
    assertThat(cancelled.status()).isEqualTo("CANCELLED");

    var cancelledTasks = service.listBatchTasks(batchId, null, 0, 10);
    assertThat(cancelledTasks).allMatch(t -> t.status().equals("CANCELLED"));
  }

  @Test
  void processesLargeBatchOfMoreThanOneHundredVideos() throws Exception {
    byte[] mp4 = TikTokTestMediaHelper.createValidMp4(10, 1080, 1920, 1024);
    Map<String, byte[]> files = new LinkedHashMap<>();

    for (int i = 1; i <= 102; i++) {
      files.put(String.format("batch_vid_%03d.mp4", i), mp4);
    }

    var preview = service.uploadAndAnalyzeZip(
        projectId, null, "Large 100+ Batch", new ByteArrayInputStream(zip(files)), "large.zip");

    assertThat(preview.totalCount()).isEqualTo(102);
    assertThat(preview.validCount()).isEqualTo(102);

    UUID batchId = preview.batchId();
    service.confirmAndPublishBatch(batchId);

    // Verify pagination works
    var page0 = service.listBatchTasks(batchId, null, 0, 20);
    var page1 = service.listBatchTasks(batchId, null, 1, 20);
    assertThat(page0).hasSize(20);
    assertThat(page1).hasSize(20);
    assertThat(page0.get(0).id()).isNotEqualTo(page1.get(0).id());

    // Run batch through worker
    worker.tick();
    Thread.sleep(100);

    var summary = service.getBatchSummary(batchId);
    assertThat(summary.totalVideos()).isEqualTo(102);
  }
}
