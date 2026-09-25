package com.mediafactory.wallpaper;

import static com.mediafactory.processing.ProcessingJson.*;
import static org.assertj.core.api.Assertions.*;

import com.mediafactory.processing.*;
import com.mediafactory.provider.*;
import com.mediafactory.provider.resilience.*;
import com.mediafactory.provider.routing.*;
import com.mediafactory.quality.*;
import com.mediafactory.service.*;
import com.mediafactory.similarity.*;
import com.mediafactory.storage.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers
@SpringBootTest(properties = {"media.worker.enabled=false", "media.qa.requests-per-minute=1000"})
class WallpaperIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("media.storage.root", () -> "build/wallpaper-test/" + UUID.randomUUID());
  }

  @Autowired WallpaperProductionService productions;
  @Autowired WallpaperCollectionService collections;
  @Autowired WallpaperPublicationService publication;
  @Autowired WallpaperExportService exports;
  @Autowired JdbcClient db;
  @Autowired TransactionTemplate tx;
  @Autowired FactoryService factory;
  @Autowired QualityReviewService reviews;
  @Autowired QaConfiguration qaConfig;
  @Autowired TechnicalQa technical;
  @Autowired QualityPolicyEngine policy;
  @Autowired MediaStorage storage;
  @Autowired ProviderRateLimiter limiter;
  @Autowired RetryDecisionService retry;
  @Autowired MeterRegistry metrics;
  @Autowired ImageGenerationProperties images;
  @Autowired GenerationAttemptRepository attempts;
  @Autowired ProviderObservability telemetry;
  @Autowired SimilarityService similarity;
  @Autowired EmbeddingModelService models;
  @Autowired CollectionClusteringService clustering;
  @Autowired ProcessingService processing;
  @Autowired PostProcessingQa postQa;
  @Autowired MockWallpaperPublicationTarget mock;
  UUID collection, concept;

  @BeforeEach
  void setup() {
    db.sql(
            "truncate"
                + " projects,provider_runtime,provider_request_events,mock_wallpaper_catalog,mock_wallpaper_requests"
                + " cascade")
        .update();
    db.sql("update embedding_models set active=false").update();
    var model = (ImageEmbeddingProvider.Model) models.register("mock-embedding");
    db.sql("update embedding_models set active=true where id=?").param(model.id()).update();
    UUID project = (UUID) factory.project("Wallpaper tests", "").get("id");
    collection =
        (UUID)
            ((Map<?, ?>)
                    collections.create(
                        project,
                        "Nocturne",
                        "nocturne",
                        "",
                        "Nocturnal landscapes",
                        "Cinematic",
                        false))
                .get("id");
    concept =
        (UUID)
            factory
                .concept(collection, "Moonrise", "A silver moon over a misty mountain")
                .get("id");
  }

  UUID start() {
    return (UUID)
        productions
            .start(
                concept,
                "ANDROID_STANDARD",
                Map.of("title", "Moonrise", "slug", "moonrise", "tags", List.of("nature")),
                UUID.randomUUID().toString(),
                null)
            .get("id");
  }

  UUID generated() {
    UUID id = start();
    productions.advance(id);
    try (var worker =
        new GenerationWorker(
            db,
            tx,
            factory,
            new ImageProviderRouter(List.of(new MockProviders()), images),
            images,
            limiter,
            retry,
            attempts,
            telemetry,
            storage,
            technical)) {
      worker.execute(worker.claim());
    }
    productions.advance(id);
    return id;
  }

  UUID ready() {
    UUID id = generated();
    try (var worker =
        new QualityWorker(
            db,
            tx,
            reviews,
            qaConfig,
            technical,
            policy,
            storage,
            limiter,
            retry,
            List.of(new MockVisionQualityProvider()),
            metrics)) {
      worker.execute(worker.claim());
    }
    productions.advance(id);
    assertThat(productions.one(id).get("status")).isEqualTo("QA_APPROVED");
    productions.advance(id);
    var sw = new SimilarityWorker(similarity, storage, limiter, retry, clustering);
    sw.execute(sw.claim());
    productions.advance(id);
    assertThat(productions.one(id).get("status")).isEqualTo("PROCESSING");
    var executor =
        new ProcessingExecutor(db, tx, storage, new FakeProcessing(), metrics, processing, postQa);
    executor.execute(executor.claim().orElseThrow());
    productions.advance(id);
    assertThat(productions.one(id).get("status")).isEqualTo("PUBLICATION_REVIEW");
    return id;
  }

  UUID approved() {
    UUID id = ready();
    var p = publication.prepare(id);
    publication.approve(id, (UUID) p.get("id"), integer(productions.one(id), "revision", 0));
    return id;
  }

  UUID queue(UUID id) {
    return (UUID) ((Map<?, ?>) publication.publish(id, "MOCK")).get("id");
  }

  @Test
  void fullMockFlowPreservesLineageAndManifest() {
    UUID id = approved();
    var w = productions.one(id);
    var original = processing.asset((UUID) w.get("master_asset_id"));
    byte[] before = storage.read(original.get("storage_key").toString());
    UUID delivery = queue(id);
    publication.deliver(delivery);
    assertThat(productions.one(id).get("status")).isEqualTo("PUBLISHED");
    assertThat(queue(id)).isEqualTo(delivery);
    assertThat(db.sql("select count(*) from mock_wallpaper_catalog").query(Integer.class).single())
        .isEqualTo(1);
    assertThat(storage.read(original.get("storage_key").toString())).isEqualTo(before);
    assertThat(
            db.sql(
                    "select count(*) from asset_variants v join processing_artifacts p on"
                        + " p.id=v.parent_artifact_id join asset_variants master on"
                        + " master.artifact_id=p.id where master.kind='WALLPAPER_MASTER'")
                .query(Integer.class)
                .single())
        .isEqualTo(5);
    assertThatThrownBy(
            () -> db.sql("update wallpaper_publication_packages set manifest='{}'").update())
        .hasMessageContaining("immutable");
  }

  @Test
  void noPublicationBeforeHumanApprovalAndDryRunHasNoMutation() {
    UUID id = ready();
    publication.prepare(id);
    assertThatThrownBy(() -> publication.publish(id, "MOCK"))
        .hasMessageContaining("Human approval");
    publication.dryRun(id, "MOCK");
    assertThat(db.sql("select count(*) from mock_wallpaper_catalog").query(Integer.class).single())
        .isZero();
  }

  @Test
  void timeoutAfterAcceptanceReplaysSameRemoteIdentity() {
    UUID id = approved();
    UUID delivery = queue(id);
    var first = new AtomicBoolean(true);
    WallpaperPublicationTarget flaky =
        new WallpaperPublicationTarget() {
          public String key() {
            return "MOCK";
          }

          public boolean available() {
            return true;
          }

          public Result publish(Request r) {
            var result = mock.publish(r);
            if (first.getAndSet(false)) throw new Failure("TIMEOUT", true);
            return result;
          }

          public Result update(Request r) {
            return publish(r);
          }

          public Result unpublish(Reference r) {
            return mock.unpublish(r);
          }
        };
    var publisher = new WallpaperPublicationService(productions, List.of(flaky));
    publisher.deliver(delivery);
    assertThat(
            db.sql("select status from wallpaper_deliveries where id=?")
                .param(delivery)
                .query(String.class)
                .single())
        .isEqualTo("READY");
    db.sql("update wallpaper_deliveries set available_at=now() where id=?")
        .param(delivery)
        .update();
    publisher.deliver(delivery);
    assertThat(productions.one(id).get("status")).isEqualTo("PUBLISHED");
    assertThat(db.sql("select count(*) from mock_wallpaper_catalog").query(Integer.class).single())
        .isEqualTo(1);
  }

  @Test
  void rejectedQaStopsBeforeProcessing() {
    UUID id = generated();
    UUID asset = (UUID) productions.one(id).get("master_asset_id");
    var review = reviews.review((UUID) processing.asset(asset).get("current_review_id"));
    try (var worker =
        new QualityWorker(
            db,
            tx,
            reviews,
            qaConfig,
            technical,
            policy,
            storage,
            limiter,
            retry,
            List.of(new MockVisionQualityProvider()),
            metrics)) {
      worker.execute(worker.claim());
    }
    var current = reviews.review((UUID) processing.asset(asset).get("current_review_id"));
    reviews.decide(
        (UUID) current.get("id"),
        QualityModels.Decision.REJECTED,
        new QualityReviewService.HumanCommand(
            integer(current, "revision", 0), "OTHER", "Composition unsuitable"),
        "test-reviewer");
    productions.advance(id);
    assertThat(productions.one(id).get("status")).isEqualTo("QA_REJECTED");
    assertThat(productions.one(id).get("processing_run_id")).isNull();
    var child = (Map<?, ?>) productions.regenerate(id, "replacement");
    assertThat(child.get("parent_id")).isEqualTo(id);
  }

  @Test
  void unpublishAndRepublishCreatesNewHistoricalVersion() {
    UUID id = approved();
    publication.deliver(queue(id));
    publication.unpublish(id, "MOCK");
    UUID delivery =
        db.sql("select id from wallpaper_deliveries where operation='UNPUBLISH'")
            .query(UUID.class)
            .single();
    publication.deliver(delivery);
    assertThat(productions.one(id).get("status")).isEqualTo("UNPUBLISHED");
    var pack = publication.prepare(id);
    assertThat(pack.get("version")).isEqualTo(2);
    publication.approve(id, (UUID) pack.get("id"), integer(productions.one(id), "revision", 0));
    publication.deliver(queue(id));
    assertThat(productions.one(id).get("status")).isEqualTo("PUBLISHED");
    assertThat(
            db.sql("select count(*) from wallpaper_publication_packages")
                .query(Integer.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  void exportContainsManifestAndAllFrozenBinaries() throws Exception {
    UUID id = ready();
    UUID export = (UUID) ((Map<?, ?>) exports.request(id, null)).get("id");
    exports.execute(export);
    byte[] bytes = exports.download(export);
    var names = new ArrayList<String>();
    try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
      for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) names.add(e.getName());
    }
    assertThat(names).hasSize(8).contains("manifest.json");
    assertThat(names.stream().anyMatch(n -> n.contains("/master/"))).isTrue();
    assertThat(names.stream().anyMatch(n -> n.contains("/thumbnails/"))).isTrue();
  }

  @Test
  void collectionStopsAtReadyTarget() {
    ready();
    collections.produce(
        collection,
        1,
        1,
        2,
        java.math.BigDecimal.ZERO,
        java.math.BigDecimal.ZERO,
        "ANDROID_STANDARD");
    collections.advance(collection);
    assertThat(db.sql("select status from wallpaper_collection_plans").query(String.class).single())
        .isEqualTo("COMPLETED");
  }

  @Test
  void attemptLimitStopsReplacementLoop() {
    UUID id = start();
    db.sql("update wallpaper_productions set status='QA_REJECTED' where id=?").param(id).update();
    collections.produce(
        collection,
        1,
        1,
        1,
        java.math.BigDecimal.ZERO,
        java.math.BigDecimal.ZERO,
        "ANDROID_STANDARD");
    collections.advance(collection);
    assertThat(
            db.sql("select failure_reason from wallpaper_collection_plans")
                .query(String.class)
                .single())
        .isEqualTo("MAX_ATTEMPTS");
  }

  @Test
  void requestReplayAndPauseAreRevisionChecked() {
    String key = "same-request";
    var body = Map.<String, Object>of("title", "Moon", "slug", "moon");
    var first = productions.start(concept, "ANDROID_STANDARD", body, key, null);
    assertThat(productions.start(concept, "ANDROID_STANDARD", body, key, null).get("id"))
        .isEqualTo(first.get("id"));
    UUID id = (UUID) first.get("id");
    productions.action(id, 0, "pause", "Inspect direction");
    productions.advance(id);
    assertThat(productions.one(id).get("generation_id")).isNull();
    assertThatThrownBy(() -> productions.action(id, 0, "resume", "Resume"))
        .hasMessageContaining("Refresh");
  }

  @Test
  void androidAdapterIsExplicitlyUnavailable() {
    assertThat(new AndroidWallpaperBackendPublicationTarget().available()).isFalse();
    assertThatThrownBy(() -> new AndroidWallpaperBackendPublicationTarget().publish(null))
        .hasMessageContaining("ANDROID_CONTRACT_NOT_CONFIGURED");
  }

  @Test
  void collectionBudgetStopsBeforeDispatch() {
    collections.produce(
        collection,
        2,
        2,
        4,
        java.math.BigDecimal.ONE,
        new java.math.BigDecimal("0.75"),
        "ANDROID_STANDARD");
    collections.advance(collection);
    assertThat(
            db.sql("select failure_reason from wallpaper_collection_plans")
                .query(String.class)
                .single())
        .isEqualTo("MAX_COST");
    assertThat(db.sql("select count(*) from wallpaper_productions").query(Integer.class).single())
        .isZero();
  }

  @Test
  void staleSimilarityModelBlocksPublication() {
    UUID id = ready();
    db.sql("update similarity_jobs set status='FAILED' where asset_id=?")
        .param(productions.one(id).get("master_asset_id"))
        .update();
    assertThat(publication.eligibility(id, false).get("reasons").toString())
        .contains("SIMILARITY_INCOMPLETE");
    assertThatThrownBy(() -> publication.prepare(id)).hasMessageContaining("ineligible");
  }

  @Test
  void metadataEditInvalidatesApproval() {
    UUID id = approved();
    productions.metadata(
        id,
        integer(productions.one(id), "revision", 0),
        Map.of("title", "New title", "slug", "new-title"));
    assertThatThrownBy(() -> publication.publish(id, "MOCK"))
        .hasMessageContaining("Human approval");
    var newPackage = publication.prepare(id);
    assertThat(newPackage.get("version")).isEqualTo(2);
    assertThat(newPackage.get("approved_at")).isNull();
  }

  @Test
  void nonRetryableTargetFailureStopsImmediately() {
    UUID id = approved();
    UUID delivery = queue(id);
    WallpaperPublicationTarget denied =
        new WallpaperPublicationTarget() {
          public String key() {
            return "MOCK";
          }

          public boolean available() {
            return true;
          }

          public Result publish(Request r) {
            throw new Failure("AUTHORIZATION_FAILED", false);
          }

          public Result update(Request r) {
            return publish(r);
          }

          public Result unpublish(Reference r) {
            throw new Failure("AUTHORIZATION_FAILED", false);
          }
        };
    new WallpaperPublicationService(productions, List.of(denied)).deliver(delivery);
    assertThat(
            db.sql("select status from wallpaper_deliveries where id=?")
                .param(delivery)
                .query(String.class)
                .single())
        .isEqualTo("FAILED");
    assertThatThrownBy(() -> publication.retry(delivery)).hasMessageContaining("correction");
  }

  @Test
  void collectionPartialPublicationDoesNotDuplicateCompletedMember() {
    UUID first = approved();
    UUID firstDelivery = queue(first);
    publication.deliver(firstDelivery);
    // A second reviewed item without human publication approval must remain local.
    var next =
        productions.start(
            concept,
            "ANDROID_STANDARD",
            Map.of("title", "Second", "slug", "second"),
            "second-wallpaper",
            null);
    UUID second = (UUID) next.get("id");
    db.sql("update wallpaper_productions set status='PUBLICATION_REVIEW' where id=?")
        .param(second)
        .update();
    var result =
        (List<Map<String, Object>>) publication.collectionAction(collection, "publish", "MOCK");
    assertThat(result).hasSize(2);
    assertThat(result.stream().filter(r -> r.containsKey("error")).count()).isEqualTo(1);
    assertThat(db.sql("select count(*) from mock_wallpaper_catalog").query(Integer.class).single())
        .isEqualTo(1);
    assertThat(queue(first)).isEqualTo(firstDelivery);
  }

  @Test
  void collectionPausePreventsAlreadyQueuedGenerationDispatch() {
    collections.produce(
        collection,
        2,
        1,
        3,
        java.math.BigDecimal.ZERO,
        java.math.BigDecimal.ZERO,
        "ANDROID_STANDARD");
    collections.advance(collection);
    UUID id = db.sql("select id from wallpaper_productions").query(UUID.class).single();
    int revision =
        db.sql("select revision from wallpaper_collection_plans").query(Integer.class).single();
    collections.action(collection, "pause", revision);
    productions.advance(id);
    assertThat(productions.one(id).get("generation_id")).isNull();
  }

  @Test
  void snapshotVersionsAreFrozenAcrossProfileEdits() {
    UUID id = start();
    var before = productions.one(id).get("profile_snapshot");
    db.sql(
            "update wallpaper_profiles set"
                + " version=version+1,definition=jsonb_set(definition,'{generationWidth}','1200')"
                + " where key='ANDROID_STANDARD'")
        .update();
    assertThat(productions.one(id).get("profile_snapshot")).isEqualTo(before);
    db.sql(
            "update wallpaper_profiles set"
                + " definition=jsonb_set(definition,'{generationWidth}','1024') where"
                + " key='ANDROID_STANDARD'")
        .update();
  }

  @Test
  void manualCropCreatesNewVariantAndInvalidatesOldPackage() {
    UUID id = ready();
    var originalPackage = publication.prepare(id);
    var w = productions.one(id);
    var old =
        publication.variants(w).stream()
            .filter(v -> v.get("kind").equals("ANDROID_FHD_PORTRAIT"))
            .findFirst()
            .orElseThrow();
    byte[] oldBytes = storage.read(old.get("storage_key").toString());
    var profiles =
        (List<Map<String, Object>>) map(w.get("profile_snapshot")).get("processingVersions");
    UUID run =
        (UUID)
            processing
                .requestFrozen(
                    (UUID) w.get("master_asset_id"),
                    profiles,
                    Map.of(
                        "ANDROID_FHD_PORTRAIT", Map.of("x", .05, "y", 0, "width", .9, "height", 1)),
                    "crop-correction",
                    10)
                .get("processingRunId");
    productions.reprocess(id, integer(w, "revision", 0), run);
    var executor =
        new ProcessingExecutor(db, tx, storage, new FakeProcessing(), metrics, processing, postQa);
    executor.execute(executor.claim().orElseThrow());
    productions.advance(id);
    var updatedPackage = publication.prepare(id);
    assertThat(updatedPackage.get("version")).isEqualTo(2);
    assertThat(storage.read(old.get("storage_key").toString())).isEqualTo(oldBytes);
    var next =
        publication.variants(productions.one(id)).stream()
            .filter(v -> v.get("kind").equals("ANDROID_FHD_PORTRAIT"))
            .findFirst()
            .orElseThrow();
    assertThat(next.get("id")).isNotEqualTo(old.get("id"));
    assertThat(publication.packageById((UUID) originalPackage.get("id")).get("manifest"))
        .isEqualTo(originalPackage.get("manifest"));
  }

  static class FakeProcessing implements ProcessingProvider {
    public Map<String, Object> capabilities() {
      return Map.of();
    }

    public void cancel(UUID id) {}

    public Map<String, Object> cropPreview(
        byte[] b, Map<String, Object> p, List<Map<String, Object>> r) {
      return Map.of();
    }

    public Output execute(UUID run, byte[] source, Map<String, Object> node) {
      boolean up = "UPSCALE".equals(node.get("operation"));
      var p = up ? Map.<String, Object>of() : map(node.get("profile"));
      boolean master = "WALLPAPER_MASTER".equals(node.get("key"));
      int width = up || master ? 4096 : integer(p, "width", 1),
          height = up || master ? 8192 : integer(p, "height", 1);
      var metadata = new LinkedHashMap<String, Object>();
      metadata.put("device", "cpu");
      metadata.put("provider", "fake-processing");
      if (up) {
        String[] identity = node.get("model").toString().split(":");
        metadata.put("model", identity[0]);
        metadata.put("modelVersion", identity[1]);
        metadata.put("modelChecksum", identity[2]);
      }
      // Contract fixture: pixel processing is covered by worker tests and the live GPU smoke.
      return new Output(
          (run + ":" + node.get("key")).getBytes(),
          Map.of(
              "status",
              "VALID",
              "width",
              width,
              "height",
              height,
              "format",
              up || master ? "PNG" : p.get("format")),
          metadata,
          1);
    }
  }
}
