package com.mediafactory.stock;

import static com.mediafactory.processing.ProcessingJson.*;
import static org.assertj.core.api.Assertions.*;

import com.mediafactory.processing.*;
import com.mediafactory.provider.*;
import com.mediafactory.provider.ProviderTypes.*;
import com.mediafactory.provider.resilience.*;
import com.mediafactory.provider.routing.*;
import com.mediafactory.quality.*;
import com.mediafactory.service.*;
import com.mediafactory.similarity.*;
import com.mediafactory.storage.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.apache.commons.csv.*;
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
@SpringBootTest(properties = {"media.worker.enabled=false", "media.qa.requests-per-minute=10000"})
class StockIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("media.storage.root", () -> "build/stock-test/" + UUID.randomUUID());
  }

  @Autowired StockProductionService stock;
  @Autowired StockMetadataService metadata;
  @Autowired StockExportService exports;
  @Autowired StockCollectionService collections;
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
  UUID collection, concept;

  @BeforeEach
  void setup() {
    db.sql(
            "truncate"
                + " projects,provider_runtime,provider_request_events,stock_exports,stock_metadata_cache"
                + " cascade")
        .update();
    db.sql("update embedding_models set active=false").update();
    var m = (ImageEmbeddingProvider.Model) models.register("mock-embedding");
    db.sql("update embedding_models set active=true where id=?").param(m.id()).update();
    UUID project = (UUID) factory.project("Stock tests", "").get("id");
    collection = (UUID) ((Map<?, ?>) collections.create(project, "Test stock")).get("id");
    concept =
        (UUID)
            factory
                .concept(
                    collection,
                    "Abstract study",
                    "Distinct abstract geometric forms in a balanced composition")
                .get("id");
  }

  UUID start() {
    return (UUID)
        stock.start(concept, null, "STOCK_GENERIC", UUID.randomUUID().toString()).get("id");
  }

  UUID generated() {
    UUID id = start();
    stock.advance(id);
    try (var w =
        new GenerationWorker(
            db,
            tx,
            factory,
            new ImageProviderRouter(List.of(new FixtureImages()), images),
            images,
            limiter,
            retry,
            attempts,
            telemetry,
            storage,
            technical)) {
      w.execute(w.claim());
    }
    stock.advance(id);
    return id;
  }

  UUID ready() {
    UUID id = generated();
    try (var w =
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
      w.execute(w.claim());
    }
    var review =
        reviews.review(
            (UUID)
                processing
                    .asset((UUID) stock.one(id).get("source_asset_id"))
                    .get("current_review_id"));
    reviews.decide(
        (UUID) review.get("id"),
        QualityModels.Decision.APPROVED,
        new QualityReviewService.HumanCommand(
            integer(review, "revision", 0), "OTHER", "Deterministic test fixture inspected"),
        "test-reviewer");
    stock.advance(id);
    assertThat(stock.one(id).get("status")).isEqualTo("QA_APPROVED");
    stock.advance(id);
    var sw = new SimilarityWorker(similarity, storage, limiter, retry, clustering);
    sw.execute(sw.claim());
    stock.advance(id);
    assertThat(stock.one(id).get("status")).isEqualTo("PROCESSING");
    var executor =
        new ProcessingExecutor(
            db, tx, storage, new FixtureProcessing(), metrics, processing, postQa);
    executor.execute(executor.claim().orElseThrow());
    stock.advance(id);
    assertThat(stock.one(id).get("status")).isEqualTo("TECHNICAL_VALIDATION");
    stock.advance(id);
    assertThat(stock.one(id).get("status")).isEqualTo("METADATA_GENERATION");
    stock.advance(id);
    assertThat(stock.one(id).get("status")).isEqualTo("METADATA_REVIEW");
    return id;
  }

  UUID approved() {
    UUID id = ready();
    stock.approve(id, integer(stock.one(id), "revision", 0), true);
    return id;
  }

  UUID export(List<UUID> ids, String policy) {
    UUID id =
        (UUID)
            exports
                .request(
                    "GENERIC_CSV", ids, null, false, policy, UUID.randomUUID().toString(), null)
                .get("id");
    exports.execute(id);
    return id;
  }

  Map<String, byte[]> unzip(byte[] bytes) throws Exception {
    var result = new LinkedHashMap<String, byte[]>();
    try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry e;
      while ((e = zip.getNextEntry()) != null) result.put(e.getName(), zip.readAllBytes());
    }
    return result;
  }

  @Test
  void endToEndPreservesOriginalAndRecordsMetadataCosts() throws Exception {
    UUID id = approved();
    var s = stock.one(id);
    var source = processing.asset((UUID) s.get("source_asset_id"));
    assertThat(PerceptualHash.sha(storage.read(source.get("storage_key").toString())))
        .isEqualTo(source.get("sha256"));
    UUID e = export(List.of(id), "STRICT");
    assertThat(exports.detail(e).get("status")).isEqualTo("READY");
    var files = unzip(exports.download(e));
    assertThat(files)
        .hasSize(4)
        .containsKeys("metadata.csv", "manifest.json", "validation-report.json");
    assertThat(
            db.sql(
                    "select count(*) from generation_costs where generation_id=? and operation like"
                        + " 'STOCK_%' and outcome='SUCCEEDED'")
                .param(s.get("generation_id"))
                .query(Integer.class)
                .single())
        .isEqualTo(2);
    assertThat(metadata.versions(id)).hasSize(2);
    assertThat(collections.costs(collection)).allMatch(c -> c.get("currency") != null);
  }

  @Test
  void approvalRequiresExplicitWarningAcknowledgement() {
    UUID id = ready();
    assertThatThrownBy(() -> stock.approve(id, integer(stock.one(id), "revision", 0), false))
        .hasMessageContaining("acknowledge");
    UUID e = export(List.of(id), "STRICT");
    assertThat(exports.detail(e).get("status")).isEqualTo("FAILED");
  }

  @Test
  void editsRegenerationAndSecondExportKeepFirstBytes() throws Exception {
    UUID id = approved();
    UUID e1 = export(List.of(id), "STRICT");
    byte[] before = exports.download(e1);
    var s = stock.one(id);
    var old = metadata.version((UUID) s.get("metadata_version_id"));
    var changed = new LinkedHashMap<>(map(old.get("data")));
    changed.put("title", "Quiet, \"blue\" café – abstract composition");
    metadata.edit(id, integer(s, "revision", 0), changed);
    metadata.regenerate(id, integer(stock.one(id), "revision", 0), "KEYWORDS");
    stock.advance(id);
    var latest = metadata.versions(id).getFirst();
    assertThat(map(latest.get("data")).get("title")).isEqualTo(changed.get("title"));
    assertThat(metadata.versions(id)).hasSize(4);
    stock.approve(id, integer(stock.one(id), "revision", 0), true);
    UUID e2 = export(List.of(id), "STRICT");
    assertThat(exports.detail(e2).get("status")).isEqualTo("READY");
    assertThat(exports.download(e1)).isEqualTo(before);
    assertThat(exports.download(e2)).isNotEqualTo(before);
  }

  @Test
  void strictAndValidOnlyReportSkippedAssets() {
    UUID a = approved(), b = ready();
    UUID strict = export(List.of(a, b), "STRICT");
    assertThat(exports.detail(strict).get("status")).isEqualTo("FAILED");
    UUID partial = export(List.of(a, b), "VALID_ONLY");
    assertThat(exports.detail(partial).get("status")).isEqualTo("READY");
    assertThat(integer(map(exports.detail(partial).get("validation")), "valid", 0)).isEqualTo(1);
    assertThat(integer(map(exports.detail(partial).get("validation")), "failed", 0)).isEqualTo(1);
  }

  @Test
  void replayIsStableAndDifferentInputConflicts() {
    String key = UUID.randomUUID().toString();
    var a = stock.start(concept, null, "STOCK_GENERIC", key);
    assertThat(stock.start(concept, null, "STOCK_GENERIC", key).get("id")).isEqualTo(a.get("id"));
    assertThatThrownBy(() -> stock.start(concept, null, "STOCK_HIGH_QUALITY", key))
        .hasMessageContaining("Idempotency");
  }

  @Test
  void metadataAndProfilesAreDatabaseImmutable() {
    UUID id = approved();
    var m = metadata.versions(id).getFirst();
    assertThatThrownBy(
            () ->
                db.sql("update stock_metadata_versions set data='{}' where id=?")
                    .param(m.get("id"))
                    .update())
        .hasMessageContaining("immutable");
    assertThatThrownBy(() -> db.sql("update stock_profile_versions set definition='{}'").update())
        .hasMessageContaining("immutable");
  }

  @Test
  void unchangedMetadataUsesObservationAndTextCaches() {
    UUID id = ready();
    int before = db.sql("select count(*) from stock_operations").query(Integer.class).single();
    metadata.regenerate(id, integer(stock.one(id), "revision", 0), "ALL");
    stock.advance(id);
    assertThat(db.sql("select count(*) from stock_operations").query(Integer.class).single())
        .isEqualTo(before);
    assertThat(metadata.versions(id)).hasSize(2);
  }

  @Test
  void metadataInvalidCannotBeApproved() {
    UUID id = ready();
    var data = new LinkedHashMap<>(map(metadata.versions(id).getFirst().get("data")));
    data.put("keywords", List.of());
    metadata.edit(id, integer(stock.one(id), "revision", 0), data);
    assertThatThrownBy(() -> stock.approve(id, integer(stock.one(id), "revision", 0), true))
        .hasMessageContaining("validation failed");
  }

  @Test
  void frozenSelectionChangedFailsQueuedExport() {
    UUID id = approved();
    UUID export =
        (UUID)
            exports
                .request(
                    "GENERIC_CSV",
                    List.of(id),
                    null,
                    false,
                    "STRICT",
                    UUID.randomUUID().toString(),
                    null)
                .get("id");
    var s = stock.one(id);
    var data = new LinkedHashMap<>(map(metadata.versions(id).getFirst().get("data")));
    data.put("title", "A different abstract composition");
    metadata.edit(id, integer(s, "revision", 0), data);
    exports.execute(export);
    assertThat(exports.detail(export).get("status")).isEqualTo("FAILED");
  }

  @Test
  void rejectedCandidatesStillCountInCosts() {
    UUID id = ready();
    db.sql(
            "insert into"
                + " generation_costs(id,generation_id,attempt,provider,model,operation,input_usage,output_usage,estimated_cost,currency)"
                + " values(?,?,1,'fake','fake','TEST',1,1,2.5,'USD')")
        .params(UUID.randomUUID(), stock.one(id).get("generation_id"))
        .update();
    stock.action(id, integer(stock.one(id), "revision", 0), "reject");
    assertThat(collections.costs(collection))
        .anyMatch(c -> ((BigDecimal) c.get("total")).compareTo(new BigDecimal("2.5")) >= 0);
  }

  @Test
  void boundedPlanStopsForBudgetAndWaitsForHumanReview() {
    collections.plan(collection, "STOCK_GENERIC", 10, 2, 20, BigDecimal.ZERO, BigDecimal.ONE);
    collections.advance(collection);
    assertThat(map(collections.progress(collection).get("plan")).get("failure_reason"))
        .isEqualTo("MAX_COST");
  }

  @Test
  void fiftyApprovedAssetsExportWithCsvMappingAndChecksums() throws Exception {
    var ids = new ArrayList<UUID>();
    for (int n = 0; n < 50; n++) ids.add(approved());
    UUID e = export(ids, "STRICT");
    assertThat(exports.detail(e).get("status")).isEqualTo("READY");
    var files = unzip(exports.download(e));
    assertThat(files.keySet().stream().filter(k -> k.startsWith("images/")).count()).isEqualTo(50);
    assertThat(files).hasSize(53);
    try (var csv =
        CSVParser.parse(
            new String(files.get("metadata.csv"), StandardCharsets.UTF_8),
            CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get())) {
      var rows = csv.getRecords();
      assertThat(rows).hasSize(50);
      for (var row : rows) assertThat(files).containsKey("images/" + row.get("filename"));
    }
    var manifest = map(new String(files.get("manifest.json"), StandardCharsets.UTF_8));
    for (var item : (List<Map<String, Object>>) manifest.get("assets"))
      assertThat(PerceptualHash.sha(files.get("images/" + item.get("filename"))))
          .isEqualTo(item.get("checksum"));
    assertThatThrownBy(
            () ->
                exports.request(
                    "GENERIC_CSV",
                    null,
                    collection,
                    true,
                    "STRICT",
                    UUID.randomUUID().toString(),
                    null))
        .hasMessageContaining("empty");
  }

  @Test
  void workerRetriesOriginalStageAndDoesNotOverrideCancellationOrAnotherLease() {
    UUID id =
        (UUID) stock.start(concept, null, "STOCK_GENERIC", UUID.randomUUID().toString()).get("id");
    UUID token = UUID.randomUUID();
    db.sql("update stock_productions set lease_token=?,attempt=2 where id=?")
        .params(token, id)
        .update();
    var worker = new StockWorker(stock, exports, collections);
    worker.failed(id, UUID.randomUUID());
    assertThat(stock.one(id).get("status")).isEqualTo("DRAFT");
    worker.failed(id, token);
    assertThat(stock.one(id).get("status")).isEqualTo("VALIDATION_FAILED");
    stock.action(id, integer(stock.one(id), "revision", 0), "retry");
    assertThat(stock.one(id).get("status")).isEqualTo("DRAFT");
    stock.action(id, integer(stock.one(id), "revision", 0), "cancel");
    worker.failed(id, token);
    assertThat(stock.one(id).get("status")).isEqualTo("CANCELLED");
  }

  @Test
  void collectionExportIncludesApprovedVersions() {
    approved();
    approved();
    UUID id =
        (UUID)
            exports
                .request(
                    "GENERIC_CSV",
                    null,
                    collection,
                    false,
                    "STRICT",
                    UUID.randomUUID().toString(),
                    null)
                .get("id");
    exports.execute(id);
    assertThat(exports.detail(id).get("status")).isEqualTo("READY");
    assertThat(integer(map(exports.detail(id).get("validation")), "valid", 0)).isEqualTo(2);
  }

  @Test
  void exactAndNearDuplicatesBlockEvenPreviouslyApprovedCandidates() {
    UUID a = approved(), b = approved();
    UUID source = (UUID) stock.one(a).get("source_asset_id"),
        target = (UUID) stock.one(b).get("source_asset_id");
    similarity.compare(source, target, similarity.activeModel(), 0.98);
    for (String classification : List.of("EXACT_DUPLICATE", "NEAR_DUPLICATE")) {
      db.sql(
              "update similarity_comparisons set human_classification=?,final_classification=?"
                  + " where (source_asset_id=? and target_asset_id=?) or (source_asset_id=? and"
                  + " target_asset_id=?)")
          .params(classification, classification, source, target, target, source)
          .update();
      assertThat(stock.gates(stock.one(a), true)).contains("Similarity policy blocks publication");
      UUID id = export(List.of(a, b), "STRICT");
      assertThat(exports.detail(id).get("status")).isEqualTo("FAILED");
    }
  }

  @Test
  void failedPackageBuildCleansTemporaryFilesAndCanRetry() throws Exception {
    UUID production = approved();
    UUID id =
        (UUID)
            exports
                .request(
                    "GENERIC_CSV",
                    List.of(production),
                    null,
                    false,
                    "STRICT",
                    UUID.randomUUID().toString(),
                    null)
                .get("id");
    StockExportAdapter broken =
        new StockExportAdapter() {
          public String platformId() {
            return "GENERIC_CSV";
          }

          public byte[] csv(List<Map<String, Object>> items, Map<String, Object> profile) {
            throw new IllegalStateException("Injected export failure");
          }
        };
    new StockExportService(stock, List.of(broken)).execute(id);
    assertThat(exports.detail(id).get("status")).isEqualTo("FAILED");
    try (var paths =
        java.nio.file.Files.list(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")))) {
      assertThat(
              paths
                  .filter(p -> p.getFileName().toString().startsWith("stock-export-" + id + "-"))
                  .toList())
          .isEmpty();
    }
    exports.retry(id);
    exports.execute(id);
    assertThat(exports.detail(id).get("status")).isEqualTo("READY");
  }

  static class FixtureImages extends MockProviders {
    @Override
    public Result<Media> generate(Request request) {
      try {
        var image =
            new BufferedImage(request.width(), request.height(), BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        var random = new Random(request.operationId().hashCode());
        for (int y = 0; y < request.height(); y += 128)
          for (int x = 0; x < request.width(); x += 128) {
            g.setColor(new Color(random.nextInt(0xffffff)));
            g.fillRect(x, y, 128, 128);
          }
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new Result<>(
            new Media(out.toByteArray(), "image/png"),
            new Usage("mock", "stock-fixture-v1", "IMAGE_GENERATION", 1, 1, BigDecimal.ZERO, "USD"),
            Map.of("fixture", "true"));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  static class FixtureProcessing implements ProcessingProvider {
    public Map<String, Object> capabilities() {
      return Map.of();
    }

    public void cancel(UUID id) {}

    public Map<String, Object> cropPreview(
        byte[] b, Map<String, Object> p, List<Map<String, Object>> r) {
      return Map.of();
    }

    public Output execute(UUID run, byte[] source, Map<String, Object> node) {
      try {
        var sourceImage = ImageIO.read(new ByteArrayInputStream(source));
        boolean up = node.get("operation").equals("UPSCALE");
        var p = up ? Map.<String, Object>of() : map(node.get("profile"));
        boolean thumb = node.get("key").equals("THUMBNAIL");
        int w = thumb ? 320 : sourceImage.getWidth(), h = thumb ? 320 : sourceImage.getHeight();
        var target = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = target.createGraphics();
        g.drawImage(sourceImage, 0, 0, w, h, null);
        g.dispose();
        var out = new ByteArrayOutputStream();
        String format = up ? "PNG" : "JPEG";
        ImageIO.write(target, format, out);
        var metadata =
            new HashMap<String, Object>(
                Map.of("device", "cpu", "provider", "fixture-processing", "quality", 95));
        if (up) {
          String[] identity = node.get("model").toString().split(":");
          metadata.put("model", identity[0]);
          metadata.put("modelVersion", identity[1]);
          metadata.put("modelChecksum", identity[2]);
        }
        return new Output(
            out.toByteArray(),
            Map.of("status", "VALID", "width", w, "height", h, "format", format),
            metadata,
            1);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
