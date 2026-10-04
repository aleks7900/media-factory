package com.mediafactory.video;

import static com.mediafactory.processing.ProcessingJson.*;
import static org.assertj.core.api.Assertions.*;

import com.mediafactory.provider.resilience.*;
import com.mediafactory.service.FactoryService;
import com.mediafactory.similarity.PerceptualHash;
import com.mediafactory.storage.MediaStorage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers
@SpringBootTest(properties = "media.worker.enabled=false")
class VideoIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

  @Container
  static GenericContainer<?> ffmpeg =
      new GenericContainer<>("media-factory-video:latest").withExposedPorts(8000);
  @Autowired
  VideoProductionService service;
  @Autowired
  VideoWorkerClient client;
  @Autowired
  VideoSemanticQa semantic;
  @Autowired
  FactoryService factory;
  @Autowired
  JdbcClient db;
  @Autowired
  MediaStorage storage;
  @Autowired
  ProviderRateLimiter limiter;
  @Autowired
  RetryDecisionService retry;
  @Autowired
  VideoCollectionService collections;
  UUID source;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("media.storage.root", () -> "build/video-it/" + UUID.randomUUID());
    r.add(
        "VIDEO_WORKER_ENDPOINT",
        () -> "http://" + ffmpeg.getHost() + ":" + ffmpeg.getMappedPort(8000));
  }

  @BeforeEach
  void setup() throws Exception {
    db.sql("truncate projects,provider_runtime,provider_request_events cascade").update();
    db.sql("delete from video_profile_versions where profile_key='TEST_VIDEO'").update();
    UUID project = (UUID) factory.project("Video test", "").get("id");
    UUID collection = (UUID) factory.collection(project, "Motion studies").get("id");
    UUID concept =
        (UUID)
            factory.concept(collection, "Geometric landscape", "Colored geometric forms").get("id");
    UUID generation = UUID.randomUUID();
    source = UUID.randomUUID();
    UUID review = UUID.randomUUID();
    var image = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 240; y++) {
      for (int x = 0; x < 320; x++) {
        image.setRGB(
            x, y, ((x * 17 + y * 7) % 256) << 16 | ((x + y * 2) % 256) << 8 | (y * 3) % 256);
      }
    }
    var output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    byte[] bytes = output.toByteArray();
    String key = "video-test/" + source + ".png";
    storage.putOriginal(key, bytes, "image/png");
    db.sql(
            "insert into generations(id,concept_id,status,prompt,width,height)"
                + " values(?,?,'APPROVED','Colored forms',320,240)")
        .params(generation, concept)
        .update();
    db.sql(
            "insert into"
                + " assets(id,generation_id,storage_key,sha256,media_type,size_bytes,width,height)"
                + " values(?,?,?,?,'image/png',?,320,240)")
        .params(source, generation, key, PerceptualHash.sha(bytes), bytes.length)
        .update();
    db.sql(
            "insert into quality_reviews(id,asset_id,generation_id,kind,decision,final_decision)"
                + " values(?,?,?,'HUMAN','APPROVED','APPROVED')")
        .params(review, source, generation)
        .update();
    db.sql("update assets set current_review_id=? where id=?").params(review, source).update();
    var profile = map(service.profile("GENERIC_VIDEO").get("definition"));
    profile.putAll(
        Map.of(
            "width",
            320,
            "height",
            240,
            "duration",
            2,
            "variants",
            Map.of("VIDEO_PREVIEW", Map.of("width", 160, "height", 120))));
    db.sql(
            "insert into video_profile_versions(profile_key,version,definition)"
                + " values('TEST_VIDEO',1,?::jsonb)")
        .param(write(profile))
        .update();
  }

  Map<String, Object> start(String key) {
    return service.start(
        source, "TEST_VIDEO", null, false, Map.of(), BigDecimal.ZERO, 3, key, null);
  }

  @Test
  void realFfmpegMockGenerationReprocessingAndHumanApprovalPreserveRaw() {
    var v = start("video-e2e");
    UUID id = (UUID) v.get("id");
    assertThat(start("video-e2e").get("id")).isEqualTo(id);
    try (var gen = new VideoGenerationWorker(service, limiter, retry, 3, 2);
        var pipeline = new VideoPipelineWorker(service, client, gen, semantic)) {
      gen.step(id);
      gen.step(id);
      gen.step(id);
      assertThat(service.one(id).get("status")).isEqualTo("RAW_READY");
      UUID token = UUID.randomUUID();
      db.sql("update video_productions set lease_token=? where id=?").params(token, id).update();
      pipeline.step(id, token);
      pipeline.step(id, token);
      v = service.one(id);
      assertThat(v.get("status")).isEqualTo("REVIEW");
      UUID raw = (UUID) v.get("raw_asset_id");
      String checksum = service.source(raw).get("sha256").toString();
      UUID first = (UUID) v.get("master_variant_id");
      service.action(
          id,
          integer(v, "revision", 0),
          "approve",
          true,
          "Reviewed source, temporal and loop evidence");
      assertThat(service.one(id).get("status")).isEqualTo("READY");
      service.reprocess(
          id,
          integer(service.one(id), "revision", 0),
          Map.of("quality", 24),
          Map.of(),
          "second-local-run");
      pipeline.step(id, token);
      assertThat(service.one(id).get("master_variant_id")).isNotEqualTo(first);
      assertThat(service.source(raw).get("sha256")).isEqualTo(checksum);
      assertThat(
          db.sql("select count(*) from video_generation_attempts where production_id=?")
              .param(id)
              .query(Integer.class)
              .single())
          .isEqualTo(1);
      assertThat(
          db.sql(
                  "select count(*) from video_processing_runs where production_id=? and"
                      + " status='COMPLETED'")
              .param(id)
              .query(Integer.class)
              .single())
          .isEqualTo(2);
      assertThat(
          db.sql(
                  "select sum(coalesce(actual_cost,estimated_cost)) from generation_costs where"
                      + " generation_id=?")
              .param(v.get("generation_id"))
              .query(BigDecimal.class)
              .single())
          .isEqualByComparingTo(BigDecimal.ZERO);
    }
  }

  @Test
  void twentyQueuedProductionsRespectThreeRemoteSlots() {
    var ids = new ArrayList<UUID>();
    for (int n = 0; n < 20; n++) {
      ids.add((UUID) start("bulk-" + n).get("id"));
    }
    try (var gen = new VideoGenerationWorker(service, limiter, retry, 3, 2)) {
      ids.parallelStream().forEach(gen::step);
    }
    assertThat(
        db.sql("select count(*) from video_generation_attempts where status='SUBMITTED'")
            .query(Integer.class)
            .single())
        .isEqualTo(3);
    assertThat(
        db.sql("select count(*) from video_generation_attempts where status='REQUESTED'")
            .query(Integer.class)
            .single())
        .isEqualTo(17);
  }

  @Test
  void collectionPlanRespectsBatchAndAttemptLimits() {
    UUID collection = (UUID) service.source(source).get("collection_id");
    collections.configure(
        collection, "TEST_VIDEO", "mock-video", 2, 1, 1, BigDecimal.ZERO, BigDecimal.ZERO);
    collections.advance(collection);
    collections.advance(collection);
    assertThat(db.sql("select count(*) from video_productions").query(Integer.class).single())
        .isEqualTo(1);
    assertThat(
        db.sql("select failure_reason from video_collection_plans where collection_id=?")
            .param(collection)
            .query(String.class)
            .single())
        .isEqualTo("ATTEMPT_LIMIT");
  }

  @Test
  void restartResumesPollingWithoutSecondSubmission() {
    var fake = new FakeProvider("fake");
    var isolated = serviceWith(fake);
    UUID id =
        (UUID)
            isolated
                .start(
                    source,
                    "TEST_VIDEO",
                    "fake",
                    false,
                    Map.of(),
                    BigDecimal.ONE,
                    3,
                    "recovery",
                    null)
                .get("id");
    try (var gen = new VideoGenerationWorker(isolated, limiter, retry, 3, 2)) {
      gen.step(id);
    }
    try (var restarted = new VideoGenerationWorker(isolated, limiter, retry, 3, 2)) {
      restarted.step(id);
      restarted.step(id);
    }
    assertThat(fake.submissions).isEqualTo(1);
    assertThat(fake.polls).isEqualTo(2);
    assertThat(
        db.sql("select status from video_generation_attempts where production_id=?")
            .param(id)
            .query(String.class)
            .single())
        .isEqualTo("PROVIDER_PROCESSING");
  }

  @Test
  void uncertainSubmissionCannotBlindlyRetryOrFallback() {
    var fake = new FakeProvider("fake");
    fake.uncertain = true;
    var isolated = serviceWith(fake);
    UUID id =
        (UUID)
            isolated
                .start(
                    source,
                    "TEST_VIDEO",
                    "fake",
                    false,
                    Map.of(),
                    BigDecimal.ONE,
                    3,
                    "uncertain",
                    null)
                .get("id");
    try (var gen = new VideoGenerationWorker(isolated, limiter, retry, 3, 2)) {
      gen.step(id);
      gen.step(id);
    }
    assertThat(fake.submissions).isEqualTo(1);
    assertThat(isolated.one(id).get("status")).isEqualTo("SUBMISSION_UNKNOWN");
  }

  @Test
  void failedBilledProviderAndFallbackHaveSeparateCosts() {
    var first = new FakeProvider("first");
    first.failed = true;
    var second = new FakeProvider("second");
    var router =
        new com.mediafactory.provider.video.VideoProviderRouter(
            List.of(first, second), "first", "second", "gen4_turbo");
    var isolated =
        new VideoProductionService(
            db,
            service.tx,
            service.motion,
            service.prompts,
            router,
            storage,
            service.reviews,
            service.metrics);
    UUID id =
        (UUID)
            isolated
                .start(
                    source,
                    "TEST_VIDEO",
                    "first",
                    true,
                    Map.of(),
                    BigDecimal.ONE,
                    3,
                    "fallback",
                    null)
                .get("id");
    try (var gen = new VideoGenerationWorker(isolated, limiter, retry, 3, 2)) {
      gen.step(id);
      gen.step(id);
      gen.step(id);
    }
    assertThat(first.submissions).isEqualTo(1);
    assertThat(second.submissions).isEqualTo(1);
    assertThat(
        db.sql("select count(*) from generation_costs where generation_id=?")
            .param(isolated.one(id).get("generation_id"))
            .query(Integer.class)
            .single())
        .isEqualTo(2);
    assertThat(isolated.costs(id).getFirst().get("total").toString()).isEqualTo("0.35000000");
  }

  VideoProductionService serviceWith(FakeProvider fake) {
    return new VideoProductionService(
        db,
        service.tx,
        service.motion,
        service.prompts,
        new com.mediafactory.provider.video.VideoProviderRouter(
            List.of(fake), fake.id, "", "gen4_turbo"),
        storage,
        service.reviews,
        service.metrics);
  }

  static class FakeProvider implements com.mediafactory.provider.video.VideoGenerationProvider {

    final String id;
    int submissions, polls;
    boolean uncertain, failed;

    FakeProvider(String id) {
      this.id = id;
    }

    public String providerId() {
      return id;
    }

    public boolean configured() {
      return true;
    }

    public com.mediafactory.provider.video.VideoTypes.Capabilities capabilities() {
      return new com.mediafactory.provider.video.VideoTypes.Capabilities(
          Set.of("gen4_turbo"),
          Set.of("320:240"),
          Set.of(2),
          true,
          true,
          false,
          false,
          true,
          false,
          false,
          false,
          false,
          false,
          true,
          false,
          false);
    }

    public com.mediafactory.provider.video.VideoTypes.Estimate estimate(
        com.mediafactory.provider.video.VideoTypes.Request r) {
      return new com.mediafactory.provider.video.VideoTypes.Estimate(
          new BigDecimal("0.20"), "USD", "fixture");
    }

    public com.mediafactory.provider.video.VideoTypes.Submission submit(
        com.mediafactory.provider.video.VideoTypes.Request r, byte[] source, String type) {
      submissions++;
      if (uncertain) {
        throw new ImageGenerationException(
            ImageGenerationException.Type.TIMEOUT,
            "fixture timeout",
            java.time.Duration.ZERO,
            true,
            null);
      }
      return new com.mediafactory.provider.video.VideoTypes.Submission(
          UUID.randomUUID().toString(), "fixture", Map.of());
    }

    public com.mediafactory.provider.video.VideoTypes.Status status(String id) {
      polls++;
      return new com.mediafactory.provider.video.VideoTypes.Status(
          failed ? "FAILED" : "PROCESSING",
          failed ? new BigDecimal("0.15") : null,
          "USD",
          failed ? "FIXTURE_FAILURE" : null,
          Map.of());
    }

    public com.mediafactory.provider.video.VideoTypes.Result result(
        String id,
        com.mediafactory.provider.video.VideoTypes.Request r,
        byte[] source,
        String type) {
      throw new AssertionError("No result expected");
    }
  }
}
