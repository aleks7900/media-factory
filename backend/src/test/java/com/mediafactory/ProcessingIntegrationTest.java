package com.mediafactory;

import static org.assertj.core.api.Assertions.*;

import com.mediafactory.processing.*;
import com.mediafactory.similarity.PerceptualHash;
import com.mediafactory.storage.MediaStorage;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
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
@SpringBootTest(properties = {"media.worker.enabled=false"})
class ProcessingIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("media.storage.root", () -> "build/processing-test/" + UUID.randomUUID());
  }

  @Autowired JdbcClient db;
  @Autowired ProcessingService service;
  @Autowired MediaStorage storage;
  @Autowired TransactionTemplate tx;
  @Autowired MeterRegistry metrics;
  @Autowired PostProcessingQa qa;
  UUID asset;
  byte[] original = "original-test-bytes".getBytes();
  MockProcessing provider;
  ProcessingExecutor executor;

  @BeforeEach
  void setup() {
    db.sql("update processing_runs set status='CANCELLED' where status in ('PENDING','RUNNING')")
        .update();
    UUID project = UUID.randomUUID(),
        collection = UUID.randomUUID(),
        concept = UUID.randomUUID(),
        generation = UUID.randomUUID(),
        review = UUID.randomUUID();
    asset = UUID.randomUUID();
    db.sql("insert into projects(id,name) values(?,'processing')").param(project).update();
    db.sql("insert into collections(id,project_id,name) values(?,?,'processing')")
        .params(collection, project)
        .update();
    db.sql("insert into concepts(id,collection_id,name,prompt) values(?,?,'test','test')")
        .params(concept, collection)
        .update();
    db.sql(
            "insert into generations(id,concept_id,status,prompt,width,height)"
                + " values(?,?,'APPROVED','test',1000,1000)")
        .params(generation, concept)
        .update();
    String key = "test-processing/" + asset;
    storage.putOriginal(key, original, "image/png");
    db.sql(
            "insert into"
                + " assets(id,generation_id,storage_key,sha256,media_type,size_bytes,width,height)"
                + " values(?,?,?,?,'image/png',?,1000,1000)")
        .params(asset, generation, key, PerceptualHash.sha(original), original.length)
        .update();
    db.sql(
            "insert into quality_reviews(id,asset_id,generation_id,kind,decision,final_decision)"
                + " values(?,?,?,'HUMAN','APPROVED','APPROVED')")
        .params(review, asset, generation)
        .update();
    db.sql("update assets set current_review_id=? where id=?").params(review, asset).update();
    provider = new MockProcessing();
    executor = new ProcessingExecutor(db, tx, storage, provider, metrics, service, qa);
  }

  UUID request(String... profiles) {
    return (UUID)
        service.request(asset, List.of(profiles), Map.of(), null, 10).get("processingRunId");
  }

  void work() {
    var run = executor.claim().orElseThrow();
    executor.execute(run);
  }

  @Test
  void pipelinePreservesOriginalLineageAndIdempotency() {
    UUID run = request("STOCK_STANDARD", "SOCIAL_SQUARE");
    assertThat(request("SOCIAL_SQUARE", "STOCK_STANDARD")).isEqualTo(run);
    work();
    assertThat(service.run(run).get("status")).isEqualTo("COMPLETED");
    assertThat(provider.upscales.get()).isEqualTo(1);
    assertThat(
            db.sql("select count(*) from asset_variants where asset_id=?")
                .param(asset)
                .query(Long.class)
                .single())
        .isEqualTo(2);
    assertThat(
            db.sql(
                    "select count(*) from processing_artifacts where source_asset_id=? and"
                        + " parent_artifact_id is not null")
                .param(asset)
                .query(Long.class)
                .single())
        .isEqualTo(2);
    assertThat(storage.read(service.asset(asset).get("storage_key").toString()))
        .isEqualTo(original);
    assertThat(
            db.sql(
                    "select count(*) from asset_embeddings where asset_id in(select id from"
                        + " asset_variants where asset_id=?)")
                .param(asset)
                .query(Long.class)
                .single())
        .isZero();
    assertThatThrownBy(
            () ->
                db.sql("update processing_manifests set manifest='{}' where run_id=?")
                    .param(run)
                    .update())
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () -> db.sql("update processing_runs set plan='{}' where id=?").param(run).update())
        .hasMessageContaining("immutable");
  }

  @Test
  void partialFailureRetryReusesExpensiveArtifact() {
    provider.failSquare = true;
    UUID run = request("STOCK_STANDARD", "SOCIAL_SQUARE");
    work();
    assertThat(service.run(run).get("status")).isEqualTo("PARTIALLY_COMPLETED");
    provider.failSquare = false;
    service.retry(run);
    work();
    assertThat(service.run(run).get("status")).isEqualTo("COMPLETED");
    assertThat(provider.upscales.get()).isEqualTo(1);
    assertThat(
            db.sql("select count(*) from processing_manifests where run_id=?")
                .param(run)
                .query(Long.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  void rejectsUnapprovedAndDifferentIdempotencyPayload() {
    service.request(asset, List.of("PREVIEW"), Map.of(), "key-" + asset, 10);
    assertThatThrownBy(
            () -> service.request(asset, List.of("THUMBNAIL"), Map.of(), "key-" + asset, 10))
        .isInstanceOf(IllegalArgumentException.class);
    db.sql("update assets set current_review_id=null where id=?").param(asset).update();
    assertThatThrownBy(() -> request("PREVIEW")).hasMessageContaining("QA-approved");
  }

  @Test
  void publishedVersionImmutableAndDraftEditable() {
    var p = service.profile("PREVIEW");
    assertThatThrownBy(
            () ->
                db.sql("update processing_profile_versions set definition='{}' where id=?")
                    .param(p.get("id"))
                    .update())
        .hasMessageContaining("immutable");
    var draft =
        service.draft(
            "CUSTOM_" + asset.toString().replace("-", "").toUpperCase(),
            ProcessingJson.map(p.get("definition")));
    service.transition((UUID) draft.get("id"), "PUBLISHED");
    assertThatThrownBy(
            () ->
                db.sql("delete from processing_profile_versions where id=?")
                    .param(draft.get("id"))
                    .update())
        .hasMessageContaining("immutable");
  }

  @Test
  void cancellationAndSingleGlobalClaim() {
    UUID run = request("PREVIEW");
    var first = executor.claim().orElseThrow();
    assertThat(executor.claim()).isEmpty();
    service.cancel(run);
    executor.execute(first);
    assertThat(service.run(run).get("status")).isEqualTo("CANCELLED");
  }

  static class MockProcessing implements ProcessingProvider {
    AtomicInteger upscales = new AtomicInteger();
    boolean failSquare;

    public Map<String, Object> capabilities() {
      return Map.of();
    }

    public void cancel(UUID id) {}

    public Map<String, Object> cropPreview(
        byte[] b, Map<String, Object> p, List<Map<String, Object>> r) {
      return Map.of();
    }

    public Output execute(UUID run, byte[] source, Map<String, Object> node) {
      boolean up = node.get("operation").equals("UPSCALE");
      if (up) upscales.incrementAndGet();
      if (failSquare && "SOCIAL_SQUARE".equals(node.get("key")))
        throw new ProcessingFailure("OUTPUT_TOO_LARGE");
      var profile = up ? Map.<String, Object>of() : ProcessingJson.map(node.get("profile"));
      var metadata =
          new HashMap<String, Object>(
              Map.of(
                  "device",
                  "cpu",
                  "provider",
                  "mock-processing",
                  "model",
                  "deterministic",
                  "modelVersion",
                  "test"));
      if (up) {
        String[] identity = node.get("model").toString().split(":");
        metadata.put("model", identity[0]);
        metadata.put("modelVersion", identity[1]);
        metadata.put("modelChecksum", identity[2]);
      }
      int
          w =
              up
                  ? 1000 * ((Number) node.get("scale")).intValue()
                  : ProcessingJson.integer(profile, "width", 2000),
          h = up ? w : ProcessingJson.integer(profile, "height", 2000);
      return new Output(
          (run + ":" + node.get("key")).getBytes(),
          Map.of(
              "status",
              "VALID",
              "format",
              up ? "PNG" : profile.get("format"),
              "width",
              w,
              "height",
              h),
          metadata,
          10);
    }
  }
}
