package com.mediafactory;

import static org.assertj.core.api.Assertions.*;

import com.mediafactory.provider.*;
import com.mediafactory.provider.resilience.*;
import com.mediafactory.quality.*;
import com.mediafactory.service.FactoryService;
import com.mediafactory.similarity.*;
import com.mediafactory.storage.MediaStorage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers
@SpringBootTest(properties = {"media.worker.enabled=false", "media.similarity.enabled=true"})
class SimilarityIntegrationTest {
  @Autowired QualityReviewService quality;
  @Autowired QaConfiguration qaConfig;
  @Autowired TechnicalQa technical;
  @Autowired QualityPolicyEngine qaEngine;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("media.storage.root", () -> "build/similarity-media/" + UUID.randomUUID());
  }

  @Autowired JdbcClient db;
  @Autowired SimilarityService similarity;
  @Autowired FactoryService factory;
  @Autowired MediaStorage storage;
  @Autowired EmbeddingModelService models;
  @Autowired SimilarityReviewService reviews;
  @Autowired CollectionClusteringService clustering;
  @Autowired ProviderRateLimiter limiter;
  @Autowired RetryDecisionService retry;
  @Autowired DiversityGuard guard;
  @Autowired GenerationBatchService batches;
  @Autowired org.springframework.transaction.support.TransactionTemplate tx;
  @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
  UUID collection, concept;
  ImageEmbeddingProvider.Model model;

  @BeforeEach
  void setup() {
    db.sql("truncate projects,provider_runtime,provider_request_events cascade").update();
    db.sql("update embedding_models set active=false").update();
    model = (ImageEmbeddingProvider.Model) models.register("mock-embedding");
    db.sql("update embedding_models set active=true where id=?").param(model.id()).update();
    collection =
        (UUID)
            factory
                .collection(
                    (UUID) factory.project("Similarity", "").get("id"), "Fixture collection")
                .get("id");
    concept = (UUID) factory.concept(collection, "Fixture", "A forest").get("id");
  }

  byte[] pixels(int seed) throws Exception {
    var image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
    var r = new Random(seed);
    for (int y = 0; y < 128; y++)
      for (int x = 0; x < 128; x++) image.setRGB(x, y, r.nextInt(0xffffff));
    var out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  UUID asset(byte[] bytes) {
    UUID g = UUID.randomUUID(), a = UUID.randomUUID();
    db.sql(
            "insert into generations(id,concept_id,status,prompt,width,height)"
                + " values(?,?,'QA_PENDING','fixture',128,128)")
        .params(g, concept)
        .update();
    String key = "similarity/" + a + ".png";
    storage.putOriginal(key, bytes, "image/png");
    db.sql(
            "insert into"
                + " assets(id,generation_id,storage_key,sha256,media_type,size_bytes,width,height)"
                + " values(?,?,?,?,'image/png',?,128,128)")
        .params(a, g, key, PerceptualHash.sha(bytes), bytes.length)
        .update();
    return a;
  }

  float[] vector(int index) {
    float[] v = new float[512];
    v[index] = 1;
    return v;
  }

  void ready(UUID asset, float[] vector) {
    similarity.persistEmbedding(asset, model, vector, Map.of("device", "cpu"));
    db.sql("update similarity_jobs set status='SUCCEEDED' where asset_id=? and model_id=?")
        .params(asset, model.id())
        .update();
  }

  SimilarityWorker worker() {
    return new SimilarityWorker(similarity, storage, limiter, retry, clustering);
  }

  @Test
  void extensionIndexesVectorDistanceAndIsolationAreRealPostgres() throws Exception {
    assertThat(
            db.sql("select extname from pg_extension where extname='vector'")
                .query(String.class)
                .single())
        .isEqualTo("vector");
    assertThat(
            db.sql("select count(*) from pg_indexes where indexdef like '%hnsw%' ")
                .query(Integer.class)
                .single())
        .isGreaterThanOrEqualTo(3);
    UUID a = asset(pixels(1)), b = asset(pixels(2));
    ready(a, vector(0));
    ready(b, vector(1));
    var nearest = similarity.nearest(vector(0), model, 2);
    assertThat(nearest.getFirst()).containsEntry("asset_id", a);
    assertThat(((Number) nearest.getFirst().get("embedding_similarity")).doubleValue())
        .isEqualTo(1);
    UUID other = UUID.randomUUID();
    db.sql(
            "insert into embedding_models(id,provider,model,version,dimension,preprocessing)"
                + " values(?,'mock-embedding','deterministic-test','v2',512,'sha-seeded-unit-vector-v1')")
        .param(other)
        .update();
    similarity.persistEmbedding(b, similarity.model(other), vector(0), Map.of());
    assertThat(similarity.nearest(vector(0), model, 2).getFirst()).containsEntry("asset_id", a);
    assertThatThrownBy(() -> db.sql("update asset_embeddings set embedding=embedding").update())
        .hasMessageContaining("immutable");
    assertThatThrownBy(() -> models.activate(other)).hasMessageContaining("Backfill");
    assertThat(
            db.sql("select count(*) from asset_embeddings where asset_id=?")
                .param(b)
                .query(Integer.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  void exactAndHumanOverridePreserveEvidenceGroupsAndCanonical() throws Exception {
    byte[] bytes = pixels(3);
    UUID a = asset(bytes), b = asset(bytes);
    similarity.analyzeExact(a, model);
    var pair = db.sql("select * from similarity_comparisons").query().singleRow();
    assertThat(pair).containsEntry("automatic_classification", "EXACT_DUPLICATE");
    UUID id = (UUID) pair.get("id");
    UUID group =
        db.sql("select id from duplicate_groups where status='OPEN'").query(UUID.class).single();
    reviews.canonical(group, a, 0, "Preferred original", "tester");
    assertThat(
            db.sql("select canonical_asset_id from duplicate_groups where id=?")
                .param(group)
                .query(UUID.class)
                .single())
        .isEqualTo(a);
    reviews.decide(id, 0, "DISTINCT", "Intentional independent usage", "tester");
    assertThat(
            db.sql("select * from similarity_comparisons where id=?").param(id).query().singleRow())
        .containsEntry("automatic_classification", "EXACT_DUPLICATE")
        .containsEntry("final_classification", "DISTINCT");
    assertThatThrownBy(() -> reviews.decide(id, 0, "NEAR_DUPLICATE", "Stale", "tester"))
        .hasMessageContaining("changed");
    reviews.decide(id, 1, "NEAR_DUPLICATE", "Second assessment", "tester");
    assertThat(
            db.sql("select count(*) from similarity_review_actions where comparison_id=?")
                .param(id)
                .query(Integer.class)
                .single())
        .isEqualTo(2);
    assertThatThrownBy(() -> db.sql("delete from similarity_review_actions").update())
        .hasMessageContaining("immutable");
    assertThat(db.sql("select count(*) from assets").query(Integer.class).single()).isEqualTo(2);
  }

  @Test
  void localJobsPersistFeaturesCostsAndIdempotencyWithoutNetwork() throws Exception {
    UUID a = asset(pixels(10)), b = asset(pixels(11));
    var worker = worker();
    var claimed = worker.claim();
    assertThat(claimed).hasSize(2);
    worker.execute(claimed);
    assertThat(similarity.state(a, model.id())).isEqualTo("READY");
    assertThat(similarity.state(b, model.id())).isEqualTo("READY");
    worker.execute(claimed);
    assertThat(db.sql("select count(*) from asset_embeddings").query(Integer.class).single())
        .isEqualTo(2);
    assertThat(
            db.sql(
                    "select count(*) from embedding_compute_usage where outcome='SUCCEEDED' and"
                        + " device='cpu' and external_api_cost=0 and estimated_compute_cost is"
                        + " null")
                .query(Integer.class)
                .single())
        .isEqualTo(2);
    assertThat(
            db.sql("select count(*) from asset_fingerprints where type='PHASH'")
                .query(Integer.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  void failureRetriesAndNeverMeansUnique() throws Exception {
    UUID a = asset(pixels(12));
    UUID unavailable = UUID.randomUUID();
    db.sql(
            "insert into embedding_models(id,provider,model,version,dimension,preprocessing)"
                + " values(?,'missing','missing','v1',512,'test')")
        .param(unavailable)
        .update();
    db.sql("update similarity_jobs set model_id=? where asset_id=?")
        .params(unavailable, a)
        .update();
    var worker = worker();
    worker.execute(worker.claim());
    assertThat(similarity.state(a, unavailable)).isEqualTo("FAILED");
    assertThat(similarity.qaFindings(a))
        .extracting(QualityModels.Finding::code)
        .contains(QualityModels.Code.SIMILARITY_INCOMPLETE);
    db.sql(
            "update similarity_jobs set"
                + " model_id=?,status='QUEUED',max_attempts=attempts+4,available_at=now() where"
                + " asset_id=?")
        .params(model.id(), a)
        .update();
    worker.execute(worker.claim());
    assertThat(similarity.state(a, model.id())).isEqualTo("READY");
  }

  @Test
  void deterministicCollectionClustersHaveCentroidsAndOutliers() throws Exception {
    for (int i = 0; i < 7; i++) {
      UUID a = asset(pixels(50 + i));
      ready(a, vector(i < 3 ? 0 : i < 6 ? 1 : 2));
    }
    UUID run = clustering.cluster(collection, model, .1, 3);
    var stats =
        SimilarityService.JSON.readTree(
            db.sql("select statistics::text from collection_clustering_runs where id=?")
                .param(run)
                .query(String.class)
                .single());
    assertThat(stats.path("clusterCount").asInt()).isEqualTo(2);
    assertThat(stats.path("outlierCount").asInt()).isEqualTo(1);
    assertThat(
            db.sql(
                    "select count(*) from collection_clusters where run_id=? and centroid is not"
                        + " null and representative_asset_id is not null")
                .param(run)
                .query(Integer.class)
                .single())
        .isEqualTo(2);
    assertThat(clustering.diversity(collection)).doesNotContainKey("embedding");
  }

  @Test
  void transientInferenceFailureRetriesWithoutHoldingTransaction() throws Exception {
    UUID a = asset(pixels(93));
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var delegate = new MockEmbeddingProvider();
    var flaky =
        new ImageEmbeddingProvider() {
          public String providerId() {
            return "mock-embedding";
          }

          public Model modelMetadata() {
            return model;
          }

          public Result embedText(List<String> texts, Model expected) {
            return delegate.embedText(texts, expected);
          }

          public Result embed(List<Input> input, Model expected) {
            assertThat(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive())
                .isFalse();
            if (calls.incrementAndGet() == 1)
              throw new ImageGenerationException(
                  ImageGenerationException.Type.UNAVAILABLE, "Simulated local worker outage");
            return delegate.embed(input, expected);
          }
        };
    var controlled =
        new SimilarityService(
            db, tx, metrics, new DefaultSimilarityPolicy(), List.of(flaky), true, 50, .7);
    var worker = new SimilarityWorker(controlled, storage, limiter, retry, clustering);
    worker.execute(worker.claim());
    assertThat(similarity.state(a, model.id())).isEqualTo("PENDING");
    assertThat(
            db.sql("select attempts from similarity_jobs where asset_id=?")
                .param(a)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
    db.sql("update similarity_jobs set available_at=now() where asset_id=?").param(a).update();
    worker.execute(worker.claim());
    assertThat(similarity.state(a, model.id())).isEqualTo("READY");
    assertThat(
            db.sql("select outcome from embedding_compute_usage order by attempt")
                .query(String.class)
                .list())
        .containsExactly("FAILED", "SUCCEEDED");
  }

  @Test
  void strictPublicationBlocksMissingAnalysisEvenIfQaApproved() throws Exception {
    UUID a = asset(pixels(30));
    UUID g = (UUID) similarity.asset(a).get("generation_id");
    UUID review = UUID.randomUUID();
    db.sql(
            "insert into"
                + " quality_reviews(id,asset_id,generation_id,kind,decision,final_decision,execution_status)"
                + " values(?,?,?,'HUMAN','APPROVED','APPROVED','COMPLETED')")
        .params(review, a, g)
        .update();
    db.sql("update assets set current_review_id=? where id=?").params(review, a).update();
    db.sql("update collections set similarity_profile='STOCK_STRICT' where id=?")
        .param(collection)
        .update();
    assertThatThrownBy(
            () ->
                db.sql("insert into publications(id,asset_id,channel) values(?,?,'test')")
                    .params(UUID.randomUUID(), a)
                    .update())
        .hasMessageContaining("Similarity analysis incomplete");
    ready(a, vector(0));
    db.sql("insert into publications(id,asset_id,channel) values(?,?,'test')")
        .params(UUID.randomUUID(), a)
        .update();
  }

  @Test
  void changingProfilesRetainsHumanPairJudgmentAndFrozenEvidence() throws Exception {
    byte[] bytes = pixels(995);
    UUID a = asset(bytes), b = asset(bytes);
    similarity.analyzeExact(a, model);
    var pair = db.sql("select * from similarity_comparisons").query().singleRow();
    reviews.decide((UUID) pair.get("id"), 0, "DISTINCT", "Intentional repeated original", "tester");
    db.sql("update collections set similarity_profile='STOCK_STRICT' where id=?")
        .param(collection)
        .update();
    similarity.compare(a, b, model, null);
    assertThat(
            db.sql("select final_classification from similarity_comparisons order by profile_id")
                .query(String.class)
                .list())
        .containsOnly("DISTINCT");
    assertThat(
            db.sql(
                    "select count(*) from similarity_review_actions where"
                        + " action='PROPAGATE_PAIR_REVIEW'")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
    assertThat(
            db.sql("select automatic_classification from similarity_comparisons")
                .query(String.class)
                .list())
        .containsOnly("EXACT_DUPLICATE");
  }

  @Test
  void semanticSimilarityAloneIsDistinctFromDuplicateAndVariantsAreExcluded() throws Exception {
    UUID a = asset(pixels(901)), b = asset(pixels(902));
    ready(a, vector(0));
    ready(b, vector(0));
    similarity.fingerprints(
        a,
        new PerceptualHash.Fingerprint(
            similarity.asset(a).get("sha256").toString(), "0".repeat(32) + "1".repeat(32), false));
    similarity.fingerprints(
        b,
        new PerceptualHash.Fingerprint(
            similarity.asset(b).get("sha256").toString(), "1".repeat(32) + "0".repeat(32), false));
    db.sql(
            "insert into asset_variants(id,asset_id,kind,storage_key,sha256,size_bytes)"
                + " values(?,?,'THUMBNAIL',?,?,100)")
        .params(UUID.randomUUID(), a, "thumbnail/" + a, similarity.asset(b).get("sha256"))
        .update();
    similarity.compare(a, b, model, 1.0);
    assertThat(
            db.sql("select final_classification from similarity_comparisons")
                .query(String.class)
                .single())
        .isEqualTo("SEMANTICALLY_SIMILAR");
    assertThat(db.sql("select count(*) from duplicate_groups").query(Integer.class).single())
        .isZero();
    assertThat(similarity.exactCandidates(b)).isEmpty();
    assertThat(similarity.nearest(vector(0), model, 50)).hasSize(2);
  }

  @Test
  void nearDuplicateFeedsQaAndCannotPublishAfterHumanQaApprovalUntilPairOverride()
      throws Exception {
    db.sql("update collections set similarity_profile='STOCK_STRICT' where id=?")
        .param(collection)
        .update();
    UUID a = asset(pixels(801)), b = asset(pixels(802));
    ready(a, vector(0));
    ready(b, vector(0));
    String bits = "0".repeat(32) + "1".repeat(32);
    String near = "111" + "0".repeat(29) + "000" + "1".repeat(29);
    similarity.fingerprints(
        a,
        new PerceptualHash.Fingerprint(similarity.asset(a).get("sha256").toString(), bits, false));
    similarity.fingerprints(
        b,
        new PerceptualHash.Fingerprint(similarity.asset(b).get("sha256").toString(), near, false));
    similarity.compare(a, b, model, 1.0);
    var findings = similarity.qaFindings(b);
    assertThat(findings)
        .anySatisfy(
            f -> {
              assertThat(f.category()).isEqualTo(QualityModels.Category.SIMILARITY);
              assertThat(f.code()).isEqualTo(QualityModels.Code.NEAR_DUPLICATE);
              assertThat(f.metadata()).containsEntry("phashDistance", "6");
            });
    var review = quality.enqueue(b, false, null, "PERFECT");
    try (var worker =
        new QualityWorker(
            db,
            tx,
            quality,
            qaConfig,
            technical,
            qaEngine,
            storage,
            limiter,
            retry,
            List.of(new MockVisionQualityProvider()),
            metrics)) {
      worker.execute(worker.claim());
    }
    var completed = quality.review((UUID) review.get("id"));
    assertThat(completed)
        .containsEntry("execution_status", "COMPLETED")
        .containsEntry("final_decision", "NEEDS_REVIEW");
    quality.decide(
        (UUID) review.get("id"),
        QualityModels.Decision.APPROVED,
        new QualityReviewService.HumanCommand(
            ((Number) completed.get("revision")).intValue(),
            "MANUAL_QUALITY_JUDGMENT",
            "Content quality approved"),
        "tester");
    assertThatThrownBy(
            () ->
                db.sql("insert into publications(id,asset_id,channel) values(?,?,'test')")
                    .params(UUID.randomUUID(), b)
                    .update())
        .hasMessageContaining("Similarity policy blocks");
    var pair =
        db.sql("select * from similarity_comparisons where final_classification='NEAR_DUPLICATE'")
            .query()
            .singleRow();
    reviews.decide(
        (UUID) pair.get("id"),
        ((Number) pair.get("revision")).intValue(),
        "DISTINCT",
        "Visually distinct despite related shape",
        "tester");
    db.sql("insert into publications(id,asset_id,channel) values(?,?,'test')")
        .params(UUID.randomUUID(), b)
        .update();
    assertThat(quality.review((UUID) review.get("id")).get("automatic_decision"))
        .isEqualTo("NEEDS_REVIEW");
  }

  @Test
  void recentPromptGuardWarnsAndStrictModeBlocksOnlyStrongRepetition() {
    for (int i = 0; i < 3; i++)
      factory.generate(concept, "A forest", 128, 128, UUID.randomUUID().toString(), null);
    assertThat(guard.evaluate(concept, "A forest", false))
        .containsEntry("decision", "HIGH_REPETITION_RISK")
        .containsEntry("blocked", false);
    db.sql("update collections set similarity_profile='STOCK_STRICT' where id=?")
        .param(collection)
        .update();
    assertThatThrownBy(() -> factory.generate(concept, "A forest", 128, 128, "blocked", null))
        .hasMessageContaining("Diversity Guard");
    assertThat(guard.evaluate(concept, "A forest with an astronaut on Mars", false))
        .containsEntry("blocked", false);
  }

  @Test
  void oneHundredBatchDispatchesOnlyFortyWhenFourthChunkSaturates() throws Exception {
    db.sql("update similarity_profiles set saturation_threshold=.25 where id='WALLPAPER'").update();
    var request =
        new GenerationBatchService.Request(
            concept,
            100,
            10,
            128,
            128,
            ImageOptions.defaults(),
            com.mediafactory.prompt.PromptModels.PromptRenderRequest.adHoc("Batch forest", null));
    var created = (Map<?, ?>) batches.create(request, "batch-100");
    UUID batch = (UUID) created.get("id");
    for (int chunk = 0; chunk < 4; chunk++) {
      batches.advance(batch);
      var pending =
          db.sql(
                  "select m.generation_id,m.ordinal from generation_batch_members m where"
                      + " m.batch_id=? and not exists(select 1 from assets a where"
                      + " a.generation_id=m.generation_id) order by m.ordinal")
              .param(batch)
              .query()
              .listOfRows();
      assertThat(pending).hasSize(10);
      for (var member : pending) {
        UUID a = UUID.randomUUID();
        byte[] bytes = pixels(100 + ((Number) member.get("ordinal")).intValue());
        String key = "batch/" + a;
        storage.putOriginal(key, bytes, "image/png");
        db.sql(
                "insert into"
                    + " assets(id,generation_id,storage_key,sha256,media_type,size_bytes,width,height)"
                    + " values(?,?,?,?,'image/png',?,128,128)")
            .params(a, member.get("generation_id"), key, PerceptualHash.sha(bytes), bytes.length)
            .update();
        int index = ((Number) member.get("ordinal")).intValue();
        ready(a, vector(index < 30 ? index : 0));
      }
    }
    // Initial three chunks are distinct; the fourth creates an 11/40 family, crossing .25.
    db.sql("update similarity_profiles set saturation_threshold=.25 where id='WALLPAPER'").update();
    try {
      batches.advance(batch);
      assertThat(
              db.sql("select * from generation_batches where id=?")
                  .param(batch)
                  .query()
                  .singleRow())
          .containsEntry("status", "PAUSED_DIVERSITY")
          .containsEntry("dispatched", 40);
      batches.advance(batch);
      assertThat(
              db.sql("select count(*) from generation_batch_members where batch_id=?")
                  .param(batch)
                  .query(Integer.class)
                  .single())
          .isEqualTo(40);
    } finally {
      db.sql("update similarity_profiles set saturation_threshold=.8 where id='WALLPAPER'")
          .update();
    }
  }

  @Test
  void oneHundredOriginalBackfillUsesBoundedBatchesAndPreservesVersions() throws Exception {
    for (int i = 0; i < 100; i++) asset(pixels(500 + i));
    var control = (Map<?, ?>) models.enqueue("BACKFILL", model.id(), collection, "backfill100");
    var worker = worker();
    long start = System.nanoTime();
    for (int i = 0; i < 25; i++) {
      var jobs = worker.claim();
      if (jobs.isEmpty()) break;
      if ("GENERATE_ASSET_EMBEDDING".equals(jobs.getFirst().get("type"))) worker.execute(jobs);
      else worker.executeControl(jobs.getFirst());
      db.sql("update similarity_jobs set available_at=now() where status='QUEUED'").update();
    }
    assertThat(
            db.sql("select count(*) from asset_embeddings where model_id=?")
                .param(model.id())
                .query(Integer.class)
                .single())
        .isEqualTo(100);
    assertThat(
            db.sql("select status from similarity_jobs where id=?")
                .param(control.get("id"))
                .query(String.class)
                .single())
        .isEqualTo("SUCCEEDED");
    assertThat(db.sql("select count(*) from asset_variants").query(Integer.class).single())
        .isZero();
    UUID hundredRun = clustering.cluster(collection, model, .16, 3);
    assertThat(
            SimilarityService.JSON
                .readTree(
                    db.sql("select statistics::text from collection_clustering_runs where id=?")
                        .param(hundredRun)
                        .query(String.class)
                        .single())
                .path("assetCount")
                .asInt())
        .isEqualTo(100);
    System.out.println(
        "Similarity fixture backfill 100 originals milliseconds="
            + (System.nanoTime() - start) / 1_000_000);
    UUID next = UUID.randomUUID();
    db.sql(
            "insert into embedding_models(id,provider,model,version,dimension,preprocessing)"
                + " values(?,'mock-embedding','deterministic-test','reindex-test',512,'sha-seeded-unit-vector-v1')"
                + " on conflict do nothing")
        .param(next)
        .update();
    next =
        db.sql("select id from embedding_models where version='reindex-test'")
            .query(UUID.class)
            .single();
    models.enqueue("REINDEX", next, collection, "reindex100");
    for (int i = 0; i < 30; i++) {
      var jobs = worker.claim();
      if (jobs.isEmpty()) break;
      if ("GENERATE_ASSET_EMBEDDING".equals(jobs.getFirst().get("type"))) worker.execute(jobs);
      else worker.executeControl(jobs.getFirst());
      db.sql("update similarity_jobs set available_at=now() where status='QUEUED'").update();
    }
    models.activate(next);
    assertThat(similarity.activeModel().id()).isEqualTo(next);
    assertThat(db.sql("select count(*) from asset_embeddings").query(Integer.class).single())
        .isEqualTo(200);
    models.activate(model.id());
    assertThat(similarity.activeModel().id()).isEqualTo(model.id());
  }
}
