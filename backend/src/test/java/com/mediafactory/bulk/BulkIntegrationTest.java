package com.mediafactory.bulk;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mediafactory.provider.resilience.*;
import com.mediafactory.storage.MediaStorage;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers
@SpringBootTest(properties = "media.worker.enabled=false")
class BulkIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
  @Autowired
  BulkGenerationService service;
  @Autowired
  JdbcClient db;
  @Autowired
  ProviderRateLimiter limiter;
  @Autowired
  RetryDecisionService retries;
  @MockitoBean
  MediaStorage storage;
  Map<String, byte[]> files = new ConcurrentHashMap<>();
  UUID project;
  BulkGenerationWorker worker;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @BeforeEach
  void setup() {
    db.sql("truncate projects cascade").update();
    project = UUID.randomUUID();
    db.sql("insert into projects(id,name) values(?,'Bulk test')").param(project).update();
    doAnswer(
        call -> {
          String key = call.getArgument(0);
          byte[] bytes = call.getArgument(1);
          if (files.putIfAbsent(key, bytes) != null) {
            throw new IllegalStateException("exists");
          }
          return null;
        })
        .when(storage)
        .putOriginal(anyString(), any(byte[].class), anyString());
    when(storage.read(anyString()))
        .thenAnswer(
            call -> {
              var data = files.get(call.getArgument(0));
              if (data == null) {
                throw new IllegalArgumentException("missing");
              }
              return data;
            });
    worker = new BulkGenerationWorker(service, limiter, retries, 3, 1000, 3);
  }

  @AfterEach
  void close() {
    worker.close();
  }

  byte[] archive(int count, boolean invalid) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      for (int n = 0; n < count; n++) {
        zip.putNextEntry(new ZipEntry("task-" + n + "/task.md"));
        zip.write(("A mountain landscape " + n).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      if (invalid) {
        zip.putNextEntry(new ZipEntry("invalid/note.txt"));
        zip.write("missing prompt".getBytes());
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  BulkGenerationService.ImportRequest request() {
    return new BulkGenerationService.ImportRequest(
        project,
        "Images",
        "GPT_IMAGE",
        "mock",
        "studio-mock-v1",
        Map.of("width", 64, "height", 64),
        false);
  }

  UUID upload(int count, boolean invalid) throws Exception {
    return (UUID)
        ((Map<?, ?>)
            service.importArchive(
                request(), UUID.randomUUID().toString(), "tasks.zip", archive(count, invalid)))
            .get("id");
  }

  List<UUID> ids(UUID batch) {
    return db.sql("select id from bulk_tasks where batch_id=? order by name")
        .param(batch)
        .query(UUID.class)
        .list();
  }

  void runOne() {
    var task = worker.claim();
    assertThat(task).isNotNull();
    worker.step(task);
    db.sql("update bulk_tasks set lease_token=null,lease_until=null where id=?")
        .param(task.get("id"))
        .update();
  }

  @Test
  void imports125IndependentTasksAndIsolatesInvalidWithIdempotentReplay() throws Exception {
    String key = UUID.randomUUID().toString();
    var zip = archive(125, true);
    var first = (Map<?, ?>) service.importArchive(request(), key, "tasks.zip", zip);
    var replay = (Map<?, ?>) service.importArchive(request(), key, "tasks.zip", zip);
    assertThat(replay.get("id")).isEqualTo(first.get("id"));
    assertThat(first.get("totalTasks")).isEqualTo(126L);
    assertThat(((Map<?, ?>) first.get("counts")).get("QUEUED")).isEqualTo(125L);
    assertThat(((Map<?, ?>) first.get("counts")).get("FAILED")).isEqualTo(1L);
    assertThat(db.sql("select count(*) from bulk_batches").query(Long.class).single()).isEqualTo(1);
    assertThatThrownBy(
        () -> service.importArchive(request(), key, "different.zip", archive(1, false)))
        .hasMessageContaining("conflicts");
    assertThat((List<?>) service.tasks((UUID) first.get("id"), "ALL", "", 1)).hasSize(26);
  }

  @Test
  void completesMockRecordsCostAndExportsVerifiedResult() throws Exception {
    UUID batch = upload(2, true);
    runOne();
    runOne();
    assertThat(
        db.sql("select count(*) from bulk_tasks where status='COMPLETED'")
            .query(Long.class)
            .single())
        .isEqualTo(2);
    assertThat(db.sql("select count(*) from generation_costs").query(Long.class).single())
        .isEqualTo(2);
    assertThat(db.sql("select count(*) from assets").query(Long.class).single()).isEqualTo(2);
    var bytes = new ByteArrayOutputStream();
    service.export(batch, bytes);
    var names = new ArrayList<String>();
    try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        names.add(entry.getName());
        assertThat(zip.readAllBytes()).isNotEmpty();
      }
    }
    assertThat(names).hasSize(3).contains("manifest.json");
  }

  @Test
  void pauseCancelAndRegenerationPreserveOriginalLineage() throws Exception {
    UUID batch = upload(1, false);
    UUID original = ids(batch).getFirst();
    service.batchAction(batch, "pause");
    assertThat(worker.claim()).isNull();
    service.batchAction(batch, "resume");
    runOne();
    String regenerationKey = UUID.randomUUID().toString();
    var child = (Map<?, ?>) service.taskAction(original, "regenerate", regenerationKey);
    assertThat(((Map<?, ?>) service.taskAction(original, "regenerate", regenerationKey)).get("id"))
        .isEqualTo(child.get("id"));
    assertThat(child.get("parent_id")).isEqualTo(original);
    service.taskAction((UUID) child.get("id"), "cancel");
    assertThat(service.task((UUID) child.get("id")).get("completed_at")).isNotNull();
    assertThat(worker.claim()).isNull();
    assertThat(service.task(original).get("status")).isEqualTo("COMPLETED");
    service.taskAction(original, "delete");
    assertThat(db.sql("select count(*) from assets").query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void restartReusesPreparedAttemptWithoutCreatingAnotherCost() throws Exception {
    upload(1, false);
    var claimed = worker.claim();
    var prepared =
        worker.begin(
            claimed, UUID.randomUUID(), new BulkProcessor.Quote(java.math.BigDecimal.ZERO, "USD"));
    worker.step(prepared);
    assertThat(service.task((UUID) claimed.get("id")).get("status")).isEqualTo("COMPLETED");
    assertThat(db.sql("select count(*) from bulk_attempts").query(Long.class).single())
        .isEqualTo(1);
  }

  BulkGenerationWorker scripted(BulkProcessor processor) {
    var custom =
        new BulkGenerationService(
            db,
            service.transactions(),
            storage,
            service.domainFactory(),
            service.archiveParser(),
            List.of(processor));
    return new BulkGenerationWorker(custom, limiter, retries, 3, 1000, 3);
  }

  BulkProcessor fake(boolean async) {
    var processor = mock(BulkProcessor.class);
    when(processor.kind()).thenReturn("GPT_IMAGE");
    when(processor.estimate(any())).thenReturn(new BulkProcessor.Quote(null, "USD"));
    when(processor.asynchronous()).thenReturn(async);
    return processor;
  }

  void due() {
    db.sql("update bulk_tasks set lease_token=null,lease_until=null,available_at=now()").update();
  }

  @Test
  void ambiguousSubmissionCannotBeRetriedOrRegeneratedAndDoesNotStopNextTask() throws Exception {
    UUID batch = upload(2, false);
    var processor = fake(false);
    when(processor.result(any(), any()))
        .thenThrow(
            new ImageGenerationException(
                ImageGenerationException.Type.TIMEOUT,
                "timeout",
                java.time.Duration.ZERO,
                true,
                null));
    try (var custom = scripted(processor)) {
      var t = custom.claim();
      custom.step(t);
      UUID id = (UUID) t.get("id");
      assertThat(service.task(id).get("outcome_unknown")).isEqualTo(true);
      assertThatThrownBy(() -> service.taskAction(id, "retry")).hasMessageContaining("Unknown");
      assertThatThrownBy(() -> service.taskAction(id, "regenerate"))
          .hasMessageContaining("unreconciled");
      assertThat(custom.claim()).isNotNull();
      verify(processor, times(1)).result(any(), any());
    }
  }

  @Test
  void rateLimitRetriesAreBoundedAndEachCallHasItsOwnCostRecord() throws Exception {
    upload(1, false);
    var processor = fake(false);
    when(processor.result(any(), any()))
        .thenThrow(new ImageGenerationException(ImageGenerationException.Type.RATE_LIMIT, "quota"));
    try (var custom = scripted(processor)) {
      for (int i = 0; i < 3; i++) {
        var t = custom.claim();
        assertThat(t).isNotNull();
        custom.step(t);
        due();
      }
      assertThat(custom.claim()).isNull();
      verify(processor, times(3)).result(any(), any());
    }
    assertThat(db.sql("select count(*) from generation_costs").query(Long.class).single())
        .isEqualTo(3);
    assertThat(db.sql("select status from bulk_tasks").query(String.class).single())
        .isEqualTo("FAILED");
  }

  @Test
  void submittedVideoResumesPollingWithoutResubmittingAndStopsAfterPollFailures() throws Exception {
    upload(1, false);
    var processor = fake(true);
    when(processor.submit(any())).thenReturn("operations/existing");
    when(processor.status(anyString(), any()))
        .thenThrow(
            new ImageGenerationException(
                ImageGenerationException.Type.UNAVAILABLE, "poll unavailable"));
    try (var custom = scripted(processor)) {
      custom.step(custom.claim());
      due();
      for (int i = 0; i < 3; i++) {
        custom.step(custom.claim());
        due();
      }
      assertThat(custom.claim()).isNull();
      verify(processor, times(1)).submit(any());
      verify(processor, times(3)).status(eq("operations/existing"), any());
    }
    assertThat(db.sql("select outcome_unknown from bulk_tasks").query(Boolean.class).single())
        .isTrue();
    assertThat(db.sql("select count(*) from generation_costs").query(Long.class).single())
        .isEqualTo(1);
  }

  @Test
  void abandonedTaskWithExpiredLeaseIsReclaimedAfterRestart() throws Exception {
    upload(1, false);
    var claimed = worker.claim();
    assertThat(claimed).isNotNull();
    UUID taskId = (UUID) claimed.get("id");
    db.sql(
            "update bulk_tasks set status='GENERATING', lease_until=now() - interval '1 minute', available_at=now() - interval '1 minute' where id=?")
        .param(taskId)
        .update();

    var reclaimed = worker.claim();
    assertThat(reclaimed).isNotNull();
    assertThat(reclaimed.get("id")).isEqualTo(taskId);
    worker.step(reclaimed);
    assertThat(service.task(taskId).get("status")).isEqualTo("COMPLETED");
  }

  @Test
  void asyncPollingDelaysNextAttemptBy5SecondsToPreventTightLoop() throws Exception {
    upload(1, false);
    var processor = fake(true);
    when(processor.submit(any())).thenReturn("operations/op-123");
    when(processor.status(eq("operations/op-123"), any()))
        .thenReturn("RUNNING");
    try (var custom = scripted(processor)) {
      var claimed = custom.claim();
      assertThat(claimed).isNotNull();
      custom.step(claimed);
      due();
      claimed = custom.claim();
      assertThat(claimed).isNotNull();
      custom.step(claimed);
      assertThat(custom.claim()).isNull();

      var availableAt =
          db.sql("select available_at from bulk_tasks where id=?")
              .param(claimed.get("id"))
              .query(java.time.Instant.class)
              .single();
      assertThat(availableAt).isAfter(java.time.Instant.now());
    }
  }

  @Test
  void batchDeleteCascadesToTasksAndWorkerIgnoresDeletedTasks() throws Exception {
    UUID batch = upload(2, false);
    service.batchAction(batch, "delete");
    var deletedTasks =
        db.sql("select count(*) from bulk_tasks where batch_id=? and deleted_at is not null")
            .param(batch)
            .query(Long.class)
            .single();
    assertThat(deletedTasks).isEqualTo(2);
    assertThat(worker.claim()).isNull();
  }

  @Test
  void scale500TasksListingAndPaginationPerformance() throws Exception {
    long start = System.currentTimeMillis();
    UUID batch = upload(500, false);
    long importTime = System.currentTimeMillis() - start;
    assertThat(importTime).isLessThan(20000);

    long listStart = System.currentTimeMillis();
    var batches = (List<?>) service.list(project, "GPT_IMAGE", "%%", "ALL", 0);
    long listDuration = System.currentTimeMillis() - listStart;
    assertThat(batches).isNotEmpty();
    assertThat(listDuration).isLessThan(1500);

    long pageStart = System.currentTimeMillis();
    var page1 = (List<?>) service.tasks(batch, "ALL", "", 0);
    var page2 = (List<?>) service.tasks(batch, "ALL", "", 1);
    long pageDuration = System.currentTimeMillis() - pageStart;
    assertThat(page1).hasSize(100);
    assertThat(page2).hasSize(100);
    assertThat(pageDuration).isLessThan(500);
  }

  @Test
  void exportProducesCleanFilenamesAndManifestIncludesErrorDetails() throws Exception {
    UUID batch = upload(2, true);
    runOne();
    var bytes = new ByteArrayOutputStream();
    service.export(batch, bytes);

    String manifestContent = null;
    try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if ("manifest.json".equals(entry.getName())) {
          manifestContent = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
      }
    }
    assertThat(manifestContent).isNotNull();
    assertThat(manifestContent).contains("errorCode");
    assertThat(manifestContent).contains("VALIDATION_ERROR");
    assertThat(manifestContent).contains("status");
  }

  @Test
  void cancelledTaskDoesNotTransitionToCompleted() throws Exception {
    upload(1, false);
    var claimed = worker.claim();
    assertThat(claimed).isNotNull();
    UUID taskId = (UUID) claimed.get("id");

    service.taskAction(taskId, "cancel");

    worker.step(claimed);

    var finalTask = service.task(taskId);
    assertThat(finalTask.get("status")).isEqualTo("CANCELLED");

    var genStatus =
        db.sql("select status from generations where id=?")
            .param(finalTask.get("generation_id"))
            .query(String.class)
            .single();
    assertThat(genStatus).isEqualTo("FAILED");
  }

  @Test
  void multiWorkerConcurrencyStressTest() throws Exception {
    UUID batch = upload(500, false);
    var submissions = new ConcurrentHashMap<UUID, java.util.concurrent.atomic.AtomicInteger>();

    var baos = new ByteArrayOutputStream();
    var img = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB);
    javax.imageio.ImageIO.write(img, "png", baos);
    byte[] validPng = baos.toByteArray();

    var processor = mock(BulkProcessor.class);
    when(processor.kind()).thenReturn("GPT_IMAGE");
    when(processor.estimate(any())).thenReturn(new BulkProcessor.Quote(null, "USD"));
    when(processor.asynchronous()).thenReturn(false);
    when(processor.replaySafe(any())).thenReturn(true);
    when(processor.result(any(), any()))
        .thenAnswer(
            call -> {
              BulkProcessor.Input input = call.getArgument(1);
              submissions.computeIfAbsent(input.taskId(),
                  k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
              return new BulkProcessor.Output(
                  validPng, "image/png", Map.of(), 100L, 100L, java.math.BigDecimal.ZERO,
                  java.math.BigDecimal.ZERO, "USD");
            });

    var sharedService =
        new BulkGenerationService(
            db,
            service.transactions(),
            storage,
            service.domainFactory(),
            service.archiveParser(),
            List.of(processor));

    var worker1 = new BulkGenerationWorker(sharedService, limiter, retries, 16, 1000, 16);
    var worker2 = new BulkGenerationWorker(sharedService, limiter, retries, 16, 1000, 16);

    var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
    var tasksRemaining = new java.util.concurrent.atomic.AtomicInteger(500);
    var workers = List.of(worker1, worker2);

    for (int i = 0; i < 8; i++) {
      int workerIndex = i % 2;
      executor.submit(
          () -> {
            var w = workers.get(workerIndex);
            while (tasksRemaining.get() > 0) {
              var claimed = w.claim();
              if (claimed != null) {
                try {
                  w.step(claimed);
                } finally {
                  db.sql("update bulk_tasks set lease_token=null,lease_until=null where id=?")
                      .param(claimed.get("id"))
                      .update();
                }
                tasksRemaining.decrementAndGet();
              } else {
                try {
                  Thread.sleep(10);
                } catch (InterruptedException ignored) {
                  break;
                }
              }
            }
          });
    }

    executor.shutdown();
    assertThat(executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    worker1.close();
    worker2.close();

    assertThat(submissions).hasSize(500);
    for (var count : submissions.values()) {
      assertThat(count.get()).isEqualTo(1);
    }

    var completed =
        db.sql("select count(*) from bulk_tasks where batch_id=? and status='COMPLETED'")
            .param(batch)
            .query(Long.class)
            .single();
    assertThat(completed).isEqualTo(500);
  }
}


