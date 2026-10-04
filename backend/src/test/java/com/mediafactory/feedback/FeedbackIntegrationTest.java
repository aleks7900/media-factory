package com.mediafactory.feedback;

import static com.mediafactory.processing.ProcessingJson.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mediafactory.processing.*;
import com.mediafactory.prompt.*;
import com.mediafactory.prompt.PromptModels.*;
import com.mediafactory.provider.*;
import com.mediafactory.provider.resilience.*;
import com.mediafactory.provider.routing.*;
import com.mediafactory.quality.*;
import com.mediafactory.service.*;
import com.mediafactory.similarity.*;
import com.mediafactory.storage.MediaStorage;
import io.micrometer.core.instrument.MeterRegistry;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers
@SpringBootTest(
    properties = {"media.worker.enabled=false", "feedback.minimum-sample-size=2"},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeedbackIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
  final Map<String, byte[]> media = new HashMap<>();
  @Autowired
  JdbcClient db;
  @Autowired
  VisualFeatureService features;
  @Autowired
  FeedbackDatasetBuilder datasets;
  @Autowired
  VisualPatternAnalysisService patterns;
  @Autowired
  HypothesisGenerationService hypotheses;
  @Autowired
  ExperimentProposalService proposals;
  @Autowired
  ExperimentAnalysisService analysis;
  @Autowired
  FeedbackJobs jobs;
  @Autowired
  FeedbackStore store;
  @Autowired
  PromptCatalog prompts;
  @Autowired
  FeedbackBudgetGuard budget;
  @Autowired
  SaturationAnalysisService saturation;
  @MockitoBean
  MediaStorage storage;
  @Autowired
  org.springframework.context.ApplicationContext context;
  UUID project, collection, concept;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  <T> T bean(Class<T> type) {
    return context.getBean(type);
  }

  @BeforeEach
  void setup() throws Exception {
    db.sql("truncate projects,feedback_jobs cascade").update();
    project = UUID.randomUUID();
    collection = UUID.randomUUID();
    concept = UUID.randomUUID();
    db.sql("insert into projects(id,name) values(?,'Feedback test')").param(project).update();
    db.sql("insert into collections(id,project_id,name) values(?,?,'Wolves')")
        .params(collection, project)
        .update();
    db.sql("insert into concepts(id,collection_id,name,prompt) values(?,?,'Wolf','Wolf')")
        .params(concept, collection)
        .update();
    db.sql(
            "insert into"
                + " generations(id,concept_id,status,prompt,width,height,final_provider,model,created_at)"
                + " select md5('feedback-gen-'||n)::uuid,?,'APPROVED','Wolf',64,64,case when n<=50"
                + " then 'ProviderA' else 'ProviderB' end,'model','2026-01-01' from"
                + " generate_series(1,100)n")
        .param(concept)
        .update();
    db.sql(
            "insert into"
                + " assets(id,generation_id,storage_key,sha256,media_type,size_bytes,width,height,created_at)"
                + " select"
                + " md5('feedback-asset-'||n)::uuid,md5('feedback-gen-'||n)::uuid,'feedback/'||n,repeat('0',64),'image/png',100,64,64,'2026-01-01'"
                + " from generate_series(1,100)n")
        .update();
    db.sql(
            "insert into quality_reviews(id,asset_id,generation_id,kind,decision,final_decision)"
                + " select gen_random_uuid(),id,generation_id,'HUMAN','APPROVED','APPROVED' from"
                + " assets")
        .update();
    db.sql(
            "update assets a set current_review_id=r.id from quality_reviews r where"
                + " r.asset_id=a.id")
        .update();
    db.sql(
            "insert into publications(id,asset_id,channel,published_at) select"
                + " gen_random_uuid(),id,'TEST','2026-01-02' from assets")
        .update();
    db.sql(
            "insert into"
                + " performance_metrics(id,asset_id,publication_id,platform,name,value,measured_at,source,deduplication_key,reason,created_by)"
                + " select gen_random_uuid(),p.asset_id,p.id,'TEST','DOWNLOAD',case when"
                + " g.final_provider='ProviderA' then 30 else 10"
                + " end,'2026-01-03','TEST_FIXTURE',p.id::text,'Test','test' from publications p"
                + " join assets a on a.id=p.asset_id join generations g on g.id=a.generation_id")
        .update();
    media.clear();
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", out);
    when(storage.read(anyString()))
        .thenAnswer(call -> media.getOrDefault(call.getArgument(0), out.toByteArray()));
    doAnswer(
        call -> {
          media.put(call.getArgument(0), call.getArgument(1));
          return null;
        })
        .when(storage)
        .putOriginal(anyString(), any(byte[].class), anyString());
    for (var a : db.sql("select id,storage_key from assets").query().listOfRows()) {
      var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
      var random = new Random(a.get("id").hashCode());
      for (int y = 0; y < 64; y++) {
        for (int x = 0; x < 64; x++) {
          image.setRGB(x, y, random.nextInt(0xffffff));
        }
      }
      var imageBytes = new ByteArrayOutputStream();
      ImageIO.write(image, "png", imageBytes);
      byte[] bytes = imageBytes.toByteArray();
      media.put(a.get("storage_key").toString(), bytes);
      db.sql("update assets set sha256=?,size_bytes=? where id=?")
          .params(PerceptualHash.sha(bytes), bytes.length, a.get("id"))
          .update();
    }
  }

  Map<String, Object> parameters() {
    return new LinkedHashMap<>(
        Map.of(
            "scope",
            Map.of("collectionId", collection.toString(), "platform", "TEST"),
            "metric",
            "DOWNLOADS",
            "from",
            "2026-01-01T00:00:00Z",
            "to",
            "2026-02-01T00:00:00Z",
            "asOf",
            "2026-03-01T00:00:00Z",
            "minimumSample",
            20,
            "observationDays",
            30,
            "attributes",
            List.of("dark_background", "dominant_color", "brightness", "production_provider"),
            "extractorVersion",
            "visual-v1"));
  }

  void seedFeatures() {
    db.sql(
            "insert into visual_feature_extractions(id,asset_id,extractor_version,status) select"
                + " gen_random_uuid(),id,'visual-v1','COMPLETED' from assets")
        .update();
    db.sql(
            "insert into"
                + " asset_visual_features(extraction_id,asset_id,attribute_definition_id,value,confidence,source,role,extractor_version,provenance)"
                + " select"
                + " e.id,e.asset_id,d.id,to_jsonb(g.final_provider='ProviderA'),1,'COMPUTER_VISION','OBSERVED_ATTRIBUTE','visual-v1','{}'"
                + " from visual_feature_extractions e join assets a on a.id=e.asset_id join"
                + " generations g on g.id=a.generation_id cross join visual_attribute_definitions d"
                + " where d.key='dark_background'")
        .update();
  }

  @Test
  void extractionCacheVersionAndOverrideSurvive() {
    UUID asset = db.sql("select id from assets limit 1").query(UUID.class).single();
    features.extract(asset, "visual-v1");
    features.extract(asset, "visual-v1");
    assertThat(db.sql("select count(*) from visual_feature_extractions").query(Long.class).single())
        .isEqualTo(1);
    UUID feature =
        db.sql(
                "select f.id from asset_visual_features f join visual_attribute_definitions d on"
                    + " d.id=f.attribute_definition_id where f.asset_id=? and d.key='brightness'")
            .param(asset)
            .query(UUID.class)
            .single();
    features.override(
        asset,
        Map.of(
            "featureId",
            feature.toString(),
            "value",
            .8,
            "reason",
            "Measured correction",
            "user",
            "test"));
    features.extract(asset, "visual-v1-r2");
    var p = parameters();
    p.put("extractorVersion", "visual-v1-r2");
    UUID run = datasets.build(p);
    assertThat(
        db.sql(
                "select features->>'brightness' from feedback_dataset_rows where run_id=? and"
                    + " asset_id=?")
            .params(run, asset)
            .query(String.class)
            .single())
        .isEqualTo("0.8");
    assertThat(
        db.sql(
                "select count(*) from generation_costs where"
                    + " operation='VISUAL_FEATURE_EXTRACTION'")
            .query(Long.class)
            .single())
        .isEqualTo(2);
    assertThatThrownBy(() -> db.sql("update asset_visual_features set confidence=0").update())
        .hasMessageContaining("immutable");
  }

  @Test
  void patternStatisticsConfoundingAndReproducibility() {
    seedFeatures();
    var result = map(patterns.analyze(parameters()));
    UUID run = (UUID) result.get("id");
    assertThat(result.get("asset_count")).isEqualTo(100);
    var finding =
        FeedbackStore.json(
            db.sql(
                    "select * from feedback_findings where analysis_run_id=? and"
                        + " attribute_key='dark_background' and attribute_value='true'")
                .param(run)
                .query()
                .singleRow());
    var stats = map(finding.get("statistics"));
    assertThat(((Number) stats.get("absoluteDifference")).doubleValue()).isEqualTo(20);
    assertThat(finding.get("warnings").toString()).contains("IMBALANCE");
    assertThat(map(stats.get("control")).get("count")).isEqualTo(50);
    assertThat(stats.get("confidenceInterval")).isEqualTo(List.of(20.0, 20.0));
    var again = map(patterns.analyze(Map.of("runId", run.toString())));
    assertThat(again.get("id")).isEqualTo(run);
    assertThat(patterns.compliance(run)).isNotNull();
    assertThat(saturation.analyze(Map.of("runId", run.toString()))).isNotNull();
  }

  @Test
  @Timeout(300)
  void approvalImmutableProposalAssignmentAndResult() {
    seedFeatures();
    var run = map(patterns.analyze(parameters()));
    UUID finding =
        db.sql(
                "select id from feedback_findings where analysis_run_id=? and"
                    + " attribute_key='dark_background' limit 1")
            .param(run.get("id"))
            .query(UUID.class)
            .single();
    var h = map(hypotheses.generate(finding, "EXPLOITATION"));
    UUID hypothesis = (UUID) h.get("id");
    hypotheses.review(hypothesis, "APPROVED", Map.of("reason", "Review evidence", "user", "test"));
    var t =
        prompts.createTemplate(
            new TemplateInput(
                "feedback-" + UUID.randomUUID(), "Feedback", "Fixture", "TEST", null, null));
    var v =
        prompts.createVersion(
            (UUID) t.get("id"),
            new VersionInput(
                "Stock illustration: Wolf with {{background}} background",
                "",
                List.of(
                    new PromptVariableDefinition(
                        "background",
                        "Background",
                        "",
                        VariableType.STRING,
                        true,
                        "blue",
                        List.of(),
                        null,
                        null,
                        1,
                        100,
                        0)),
                "Fixture",
                null,
                null));
    UUID control = (UUID) v.get("id");
    prompts.publish(control, 0);
    var proposal =
        map(
            proposals.propose(
                hypothesis,
                Map.of(
                    "controlVersionId",
                    control.toString(),
                    "conceptId",
                    concept.toString(),
                    "variable",
                    "background",
                    "treatmentValue",
                    "black",
                    "targetSample",
                    20,
                    "maxBudget",
                    0,
                    "width",
                    1024,
                    "height",
                    1024)));
    UUID experiment = (UUID) proposal.get("id");
    assertThatThrownBy(
        () ->
            db.sql("update prompt_experiment_variants set weight=4000 where experiment_id=?")
                .param(experiment)
                .update())
        .hasMessageContaining("immutable");
    assertThatThrownBy(() -> proposals.generate(experiment)).hasMessageContaining("approval");
    assertThatThrownBy(() -> prompts.changeExperiment(experiment, "RUNNING", 0))
        .isInstanceOf(Exception.class);
    proposals.approve(experiment, Map.of("reason", "Approve mock test", "user", "test"));
    proposals.command(experiment, "RUNNING", Map.of("reason", "Start mock test", "user", "test"));
    var generated = map(proposals.generate(experiment));
    assertThat(((Number) generated.get("created")).intValue()).isEqualTo(25);
    proposals.generate(experiment);
    assertThat(
        db.sql("select count(*) from generations where experiment_id=?")
            .param(experiment)
            .query(Long.class)
            .single())
        .isEqualTo(40);
    assertThat(prompts.variables(control).getFirst().defaultValue()).isEqualTo("blue");
    var attribution =
        db.sql(
                "select experiment_variant_id,count(*) n from generations where experiment_id=?"
                    + " group by experiment_variant_id")
            .param(experiment)
            .query()
            .listOfRows();
    assertThat(attribution).allMatch(r -> ((Number) r.get("n")).intValue() == 20);
    var result = map(analysis.analyze(experiment));
    assertThat(map(result.get("result")).get("analysis_status")).isEqualTo("INSUFFICIENT_EVIDENCE");
    assertThat(result).containsKey("learning");
    assertThatThrownBy(
        () ->
            prompts.changeExperiment(
                experiment,
                "COMPLETED",
                ((Number) prompts.experiment(experiment).get("revision")).intValue()))
        .hasMessageContaining("completion");
    var firstJob =
        db.sql(
                "select j.id,j.generation_id from jobs j join generations g on g.id=j.generation_id"
                    + " where g.experiment_id=? limit 1")
            .param(experiment)
            .query()
            .singleRow();
    UUID generation = (UUID) firstJob.get("generation_id"), job = (UUID) firstJob.get("id");
    assertThat(budget.reserve(generation, job, "unexpected-provider", "studio-mock-v1")).isFalse();
    assertThat(prompts.experiment(experiment).get("status")).isEqualTo("PAUSED");
    proposals.command(
        experiment, "RUNNING", Map.of("reason", "Resume after route guard test", "user", "test"));
    assertThat(budget.reserve(generation, job, "mock", "studio-mock-v1")).isTrue();
    assertThat(budget.reserve(generation, job, "mock", "studio-mock-v1")).isFalse();
    assertThat(prompts.experiment(experiment).get("status")).isEqualTo("PAUSED");
    budget.release(job);
    proposals.command(
        experiment,
        "RUNNING",
        Map.of("reason", "Test reservation reconciled without provider call", "user", "test"));
    normalPipeline(experiment);
    var complete = map(analysis.analyze(experiment));
    assertThat(map(complete.get("result")).get("analysis_status")).isEqualTo("SUFFICIENT_EVIDENCE");
    assertThat(prompts.experiment(experiment).get("status")).isEqualTo("COMPLETED");
    UUID oldLearning = (UUID) map(result.get("learning")).get("id"),
        newLearning = (UUID) map(complete.get("learning")).get("id");
    analysis.relate(
        oldLearning,
        Map.of(
            "status",
            "CONTRADICTED",
            "relatedLearningId",
            newLearning.toString(),
            "reason",
            "Preserve opposing evidence fixture",
            "user",
            "test"));
    assertThat(store.one("feedback_learnings", newLearning).get("evidence_status"))
        .isEqualTo("CONFLICTING_EVIDENCE");
    assertThatThrownBy(
        () ->
            db.sql("update feedback_findings set statistics='{}' where id=?")
                .param(finding)
                .update())
        .hasMessageContaining("immutable");
  }

  void normalPipeline(UUID experiment) {
    var model =
        (ImageEmbeddingProvider.Model) bean(EmbeddingModelService.class).register("mock-embedding");
    db.sql("update embedding_models set active=false").update();
    db.sql("update embedding_models set active=true where id=?").param(model.id()).update();
    try (var worker =
        new GenerationWorker(
            db,
            bean(TransactionTemplate.class),
            bean(FactoryService.class),
            bean(ImageProviderRouter.class),
            bean(ImageGenerationProperties.class),
            bean(ProviderRateLimiter.class),
            bean(RetryDecisionService.class),
            bean(GenerationAttemptRepository.class),
            bean(ProviderObservability.class),
            storage,
            bean(TechnicalQa.class))) {
      org.springframework.test.util.ReflectionTestUtils.setField(worker, "feedbackBudget", budget);
      for (int i = 0; i < 40; i++) {
        var job = worker.claim();
        assertThat(job).isNotNull();
        worker.execute(job);
      }
    }
    var similarity =
        new SimilarityWorker(
            bean(SimilarityService.class),
            storage,
            bean(ProviderRateLimiter.class),
            bean(RetryDecisionService.class),
            bean(CollectionClusteringService.class));
    for (int i = 0; i < 50; i++) {
      var batch = similarity.claim();
      if (batch.isEmpty()) {
        break;
      }
      if (batch.getFirst().get("type").equals("GENERATE_ASSET_EMBEDDING")) {
        similarity.execute(batch);
      } else {
        similarity.executeControl(batch.getFirst());
      }
    }
    for (var comparison :
        db.sql(
                "select id,revision from similarity_comparisons where"
                    + " final_classification<>'DISTINCT'")
            .query()
            .listOfRows()) {
      bean(SimilarityReviewService.class)
          .decide(
              (UUID) comparison.get("id"),
              ((Number) comparison.get("revision")).intValue(),
              "DISTINCT",
              "Intentional independent mock experiment fixture",
              "test");
    }
    try (var qa =
        new QualityWorker(
            db,
            bean(TransactionTemplate.class),
            bean(QualityReviewService.class),
            bean(QaConfiguration.class),
            bean(TechnicalQa.class),
            bean(QualityPolicyEngine.class),
            storage,
            bean(ProviderRateLimiter.class),
            bean(RetryDecisionService.class),
            List.of(new MockVisionQualityProvider()),
            bean(MeterRegistry.class))) {
      for (int i = 0; i < 40; i++) {
        var job = qa.claim();
        assertThat(job).isNotNull();
        qa.execute(job);
      }
    }
    ProcessingProvider mockProcessing =
        new ProcessingProvider() {
          public Map<String, Object> capabilities() {
            return Map.of();
          }

          public void cancel(UUID id) {
          }

          public Map<String, Object> cropPreview(
              byte[] b, Map<String, Object> p, List<Map<String, Object>> r) {
            return Map.of();
          }

          public Output execute(UUID run, byte[] source, Map<String, Object> node) {
            var profile = map(node.get("profile"));
            return new Output(
                source,
                Map.of(
                    "status",
                    "VALID",
                    "format",
                    profile.get("format"),
                    "width",
                    integer(profile, "width", 320),
                    "height",
                    integer(profile, "height", 320)),
                Map.of(
                    "device",
                    "cpu",
                    "provider",
                    "mock-processing",
                    "model",
                    "fixture",
                    "modelVersion",
                    "v1"),
                1);
          }
        };
    var processing = bean(ProcessingService.class);
    var executor =
        new ProcessingExecutor(
            db,
            bean(TransactionTemplate.class),
            storage,
            mockProcessing,
            bean(MeterRegistry.class),
            processing,
            bean(PostProcessingQa.class));
    var newAssets =
        db.sql(
                "select a.*,g.experiment_variant_id from assets a join generations g on"
                    + " g.id=a.generation_id where g.experiment_id=?")
            .param(experiment)
            .query()
            .listOfRows();
    assertThat(newAssets).hasSize(40);
    for (var asset : newAssets) {
      var review = bean(QualityReviewService.class).review((UUID) asset.get("current_review_id"));
      if (!review.get("final_decision").equals("APPROVED")) {
        bean(QualityReviewService.class)
            .decide(
                (UUID) review.get("id"),
                QualityModels.Decision.APPROVED,
                new QualityReviewService.HumanCommand(
                    ((Number) review.get("revision")).intValue(),
                    "MANUAL_QUALITY_JUDGMENT",
                    "Approved deterministic mock fixture"),
                "test");
      }
      var request =
          processing.request((UUID) asset.get("id"), List.of("THUMBNAIL"), Map.of(), null, 10);
      executor.execute(executor.claim().orElseThrow());
      assertThat(processing.run((UUID) request.get("processingRunId")).get("status"))
          .isEqualTo("COMPLETED");
      UUID publication = UUID.randomUUID();
      db.sql(
              "insert into publications(id,asset_id,channel,published_at)"
                  + " values(?,?,'TEST',now()-interval '35 days')")
          .params(publication, asset.get("id"))
          .update();
      db.sql(
              "insert into"
                  + " performance_metrics(id,asset_id,publication_id,platform,name,value,measured_at,source,deduplication_key,reason,created_by)"
                  + " select gen_random_uuid(),?,?,'TEST','DOWNLOAD',case when key='A' then 10 else"
                  + " 30 end,now()-interval '34 days','TEST_FIXTURE',?,'Simulated observation"
                  + " window','test' from prompt_experiment_variants where id=?")
          .params(
              asset.get("id"),
              publication,
              publication.toString(),
              asset.get("experiment_variant_id"))
          .update();
    }
    assertThat(
        db.sql(
                "select count(*) from asset_embeddings where asset_id in(select a.id from"
                    + " assets a join generations g on g.id=a.generation_id where"
                    + " g.experiment_id=?)")
            .param(experiment)
            .query(Long.class)
            .single())
        .isEqualTo(40);
  }

  @Test
  void jobsDeduplicateAndExecute() {
    UUID asset = db.sql("select id from assets limit 1").query(UUID.class).single();
    var payload = Map.<String, Object>of("assetId", asset.toString());
    var first = map(jobs.enqueue("FEATURE_EXTRACTION", payload, "fixture"));
    assertThat(map(jobs.enqueue("FEATURE_EXTRACTION", payload, "fixture")).get("id"))
        .isEqualTo(first.get("id"));
    assertThatThrownBy(
        () ->
            jobs.enqueue(
                "FEATURE_EXTRACTION",
                Map.of("assetId", UUID.randomUUID().toString()),
                "fixture"))
        .hasMessageContaining("conflict");
    jobs.runOne();
    assertThat(store.one("feedback_jobs", (UUID) first.get("id")).get("status"))
        .isEqualTo("SUCCEEDED");
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
      named = "FEEDBACK_BENCHMARK",
      matches = "true")
  void representativeScaleBenchmark() throws Exception {
    long start = System.nanoTime();
    db.sql(
            "insert into"
                + " generations(id,concept_id,status,prompt,width,height,final_provider,model,created_at)"
                + " select"
                + " md5('bench-f-gen-'||n)::uuid,?,'APPROVED','Fixture',64,64,'mock','mock','2026-01-01'"
                + " from generate_series(1,100000)n")
        .param(concept)
        .update();
    db.sql(
            "insert into"
                + " assets(id,generation_id,storage_key,sha256,media_type,size_bytes,width,height,created_at)"
                + " select"
                + " md5('bench-f-asset-'||n)::uuid,md5('bench-f-gen-'||n)::uuid,'feedback-bench/'||n,repeat('0',64),'image/png',100,64,64,'2026-01-01'"
                + " from generate_series(1,100000)n")
        .update();
    db.sql(
            "insert into quality_reviews(id,asset_id,generation_id,kind,decision,final_decision)"
                + " select"
                + " md5('bench-f-review-'||n)::uuid,md5('bench-f-asset-'||n)::uuid,md5('bench-f-gen-'||n)::uuid,'HUMAN','APPROVED','APPROVED'"
                + " from generate_series(1,100000)n")
        .update();
    db.sql(
            "update assets a set current_review_id=r.id from quality_reviews r where"
                + " r.asset_id=a.id and a.storage_key like 'feedback-bench/%'")
        .update();
    db.sql(
            "insert into publications(id,asset_id,channel,published_at) select"
                + " md5('bench-f-pub-'||n)::uuid,md5('bench-f-asset-'||n)::uuid,'BENCHMARK','2026-01-02'"
                + " from generate_series(1,100000)n")
        .update();
    db.sql(
            "insert into"
                + " performance_metrics(id,asset_id,publication_id,platform,name,value,measured_at,source,deduplication_key,reason,created_by)"
                + " select"
                + " gen_random_uuid(),md5('bench-f-asset-'||((n-1)%100000+1))::uuid,md5('bench-f-pub-'||((n-1)%100000+1))::uuid,'BENCHMARK','DOWNLOAD',case"
                + " when n%2=0 then 3 else 1 end,'2026-01-03','BENCHMARK',n::text,'Benchmark"
                + " fixture','test' from generate_series(1,1000000)n")
        .update();
    db.sql(
            "insert into visual_feature_extractions(id,asset_id,extractor_version,status) select"
                + " md5('bench-f-extract-'||n)::uuid,md5('bench-f-asset-'||n)::uuid,'visual-v1','COMPLETED'"
                + " from generate_series(1,100000)n")
        .update();
    db.sql(
            "insert into"
                + " asset_visual_features(extraction_id,asset_id,attribute_definition_id,value,confidence,source,role,extractor_version,provenance)"
                + " select"
                + " md5('bench-f-extract-'||n)::uuid,md5('bench-f-asset-'||n)::uuid,d.id,case when"
                + " d.value_type='BOOLEAN' then to_jsonb(n%2=0) when d.value_type='ENUM' then"
                + " to_jsonb('BLUE'::text) else to_jsonb(case when n%2=0 then .2 else .8 end)"
                + " end,1,'COMPUTER_VISION','OBSERVED_ATTRIBUTE','visual-v1','{}' from"
                + " generate_series(1,100000)n cross join visual_attribute_definitions d where"
                + " d.key in"
                + " ('brightness','contrast','saturation','dark_pixel_ratio','entropy','edge_density','aspect_ratio','dominant_color','dark_background','centered_subject')")
        .update();
    long seeded = System.nanoTime();
    var p = parameters();
    p.put("scope", Map.of("collectionId", collection.toString(), "platform", "BENCHMARK"));
    p.put("attributes", List.of("dark_background"));
    long heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    var result = map(patterns.analyze(p));
    long done = System.nanoTime();
    long heapAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    assertThat(result.get("asset_count")).isEqualTo(100000);
    assertThat(result.get("finding_count")).isEqualTo(2);
    var report =
        Map.of(
            "assets",
            100000,
            "visualFeatures",
            1000000,
            "metricEvents",
            1000000,
            "seedMs",
            (seeded - start) / 1000000,
            "analysisMs",
            (done - seeded) / 1000000,
            "heapBeforeBytes",
            heapBefore,
            "heapAfterBytes",
            heapAfter,
            "bootstrapSampleCap",
            2048);
    java.nio.file.Files.createDirectories(java.nio.file.Path.of("build"));
    java.nio.file.Files.writeString(
        java.nio.file.Path.of("build/task11-benchmark.json"), write(report));
    System.out.println("FEEDBACK_BENCHMARK " + write(report));
  }
}
