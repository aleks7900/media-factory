package com.mediafactory.video;

import static com.mediafactory.processing.ProcessingJson.*;
import static com.mediafactory.video.VideoJson.*;

import com.mediafactory.processing.ProcessingPlanner;
import com.mediafactory.prompt.PromptEngine;
import com.mediafactory.prompt.PromptModels.PromptRenderRequest;
import com.mediafactory.provider.video.VideoProviderRouter;
import com.mediafactory.provider.video.VideoTypes;
import com.mediafactory.quality.QualityModels.Decision;
import com.mediafactory.quality.QualityReviewService;
import com.mediafactory.storage.MediaStorage;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VideoProductionService {

  public final JdbcClient db;
  public final TransactionTemplate tx;
  public final VideoProviderRouter router;
  public final MediaStorage storage;
  final MotionPlanner motion;
  final PromptEngine prompts;
  final QualityReviewService reviews;
  final MeterRegistry metrics;

  public VideoProductionService(
      JdbcClient db,
      TransactionTemplate tx,
      MotionPlanner motion,
      PromptEngine prompts,
      VideoProviderRouter router,
      MediaStorage storage,
      QualityReviewService reviews,
      MeterRegistry metrics) {
    this.db = db;
    this.tx = tx;
    this.motion = motion;
    this.prompts = prompts;
    this.router = router;
    this.storage = storage;
    this.reviews = reviews;
    this.metrics = metrics;
  }

  public static ResponseStatusException conflict(String text) {
    return new ResponseStatusException(HttpStatus.CONFLICT, text);
  }

  public Map<String, Object> one(UUID id) {
    return row(
        db
            .sql(
                "select v.*,g.concept_id,c.collection_id,col.project_id from video_productions v"
                    + " join generations g on g.id=v.generation_id join concepts c on"
                    + " c.id=g.concept_id join collections col on col.id=c.collection_id where"
                    + " v.id=?")
            .param(id)
            .query()
            .listOfRows()
            .stream()
            .findFirst()
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Video production not found")));
  }

  public Map<String, Object> source(UUID id) {
    return row(
        db.sql(
                "select a.*,g.concept_id,g.prompt as image_prompt,c.name as"
                    + " concept_name,c.collection_id,col.project_id,q.final_decision,q.context_snapshot"
                    + " as observations from assets a join generations g on g.id=a.generation_id"
                    + " join concepts c on c.id=g.concept_id join collections col on"
                    + " col.id=c.collection_id left join quality_reviews q on"
                    + " q.id=a.current_review_id where a.id=?")
            .param(id)
            .query()
            .singleRow());
  }

  public Map<String, Object> profile(String key) {
    return row(
        db.sql(
                "select * from video_profile_versions where profile_key=? order by version desc"
                    + " limit 1")
            .param(key)
            .query()
            .singleRow());
  }

  public Object profiles() {
    return rows(
        db.sql("select * from video_profile_versions order by profile_key,version desc")
            .query()
            .listOfRows());
  }

  public Object profileVersion(String key, int previousVersion, Map<String, Object> changes) {
    return tx.execute(
        t -> {
          db.sql("select pg_advisory_xact_lock(hashtext(?))")
              .param("video-profile:" + key)
              .query()
              .singleRow();
          var prior = profile(key);
          if (integer(prior, "version", 0) != previousVersion) {
            throw conflict("Profile changed");
          }
          var definition = VideoProfiles.settings(map(prior.get("definition")), changes);
          UUID id = UUID.randomUUID();
          db.sql(
                  "insert into video_profile_versions(id,profile_key,version,definition)"
                      + " values(?,?,?,?::jsonb)")
              .params(id, key, previousVersion + 1, write(definition))
              .update();
          return profile(key);
        });
  }

  public Object providerStats() {
    return db.sql(
            "select a.provider,a.model,count(*) attempts,count(*) filter(where a.status in"
                + " ('SUBMITTED','PROVIDER_QUEUED','PROVIDER_PROCESSING','SUBMISSION_UNKNOWN','TIMED_OUT'))"
                + " active_jobs,count(*) filter(where"
                + " a.status='GENERATED')::numeric/nullif(count(*),0) success_rate,count(*)"
                + " filter(where a.status in"
                + " ('PROVIDER_FAILED','PROVIDER_REJECTED'))::numeric/nullif(count(*),0)"
                + " failure_rate,avg(a.duration_ms)"
                + " average_duration_ms,c.currency,sum(coalesce(c.actual_cost,c.estimated_cost))"
                + " generation_cost from video_generation_attempts a join generation_costs c on"
                + " c.id=a.cost_id group by a.provider,a.model,c.currency")
        .query()
        .listOfRows();
  }

  public Object list() {
    return rows(
        db.sql(
                "select v.*,c.collection_id,c.name as concept_name,(select id from asset_variants"
                    + " where video_processing_run_id=v.current_run_id and kind='VIDEO_THUMBNAIL')"
                    + " thumbnail_id,(select id from asset_variants where"
                    + " video_processing_run_id=v.current_run_id and kind='VIDEO_PREVIEW')"
                    + " preview_id from video_productions v join generations g on"
                    + " g.id=v.generation_id join concepts c on c.id=g.concept_id order by"
                    + " v.created_at desc limit 1000")
            .query()
            .listOfRows());
  }

  public Map<String, Object> detail(UUID id) {
    var v = one(id);
    v.put("source", source((UUID) v.get("source_asset_id")));
    v.put(
        "motionVersions",
        rows(
            db.sql("select * from motion_plan_versions where production_id=? order by version desc")
                .param(id)
                .query()
                .listOfRows()));
    v.put(
        "attempts",
        rows(
            db.sql("select * from video_generation_attempts where production_id=? order by attempt")
                .param(id)
                .query()
                .listOfRows()));
    v.put(
        "runs",
        rows(
            db.sql(
                    "select * from video_processing_runs where production_id=? order by version"
                        + " desc")
                .param(id)
                .query()
                .listOfRows()));
    v.put(
        "variants",
        rows(
            db.sql(
                    "select a.*,m.metadata from asset_variants a join video_processing_runs r on"
                        + " r.id=a.video_processing_run_id left join video_media_metadata m on"
                        + " m.variant_id=a.id where r.production_id=? order by a.created_at,a.kind")
                .param(id)
                .query()
                .listOfRows()));
    v.put(
        "qa",
        rows(
            db.sql(
                    "select * from video_quality_results where production_id=? order by created_at"
                        + " desc")
                .param(id)
                .query()
                .listOfRows()));
    v.put(
        "events",
        rows(
            db.sql("select * from video_events where production_id=? order by created_at")
                .param(id)
                .query()
                .listOfRows()));
    v.put("costs", costs(id));
    return v;
  }

  public Map<String, Object> start(
      UUID sourceId,
      String profileKey,
      String provider,
      boolean fallback,
      Map<String, Object> motionInput,
      BigDecimal budget,
      int maxAttempts,
      String key,
      UUID parent) {
    if (key == null || key.isBlank() || key.length() > 200) {
      throw new IllegalArgumentException("Idempotency-Key is required");
    }
    if (budget == null || budget.signum() < 0 || maxAttempts < 1 || maxAttempts > 10) {
      throw new IllegalArgumentException("Invalid video budget or attempt limit");
    }
    String hash =
        ProcessingPlanner.hash(
            Arrays.asList(
                sourceId,
                profileKey,
                provider,
                fallback,
                motionInput,
                budget,
                maxAttempts,
                parent));
    return tx.execute(
        t -> {
          db.sql("select pg_advisory_xact_lock(hashtext(?))")
              .param("video:" + key)
              .query()
              .singleRow();
          var old =
              db.sql("select id,request_hash from video_productions where idempotency_key=?")
                  .param(key)
                  .query()
                  .listOfRows();
          if (!old.isEmpty()) {
            if (!old.getFirst().get("request_hash").equals(hash)) {
              throw conflict("Idempotency key input changed");
            }
            return one((UUID) old.getFirst().get("id"));
          }
          var a = source(sourceId);
          if (!a.get("media_type").toString().startsWith("image/")
              || !"APPROVED".equals(a.get("final_decision"))) {
            throw conflict("Video requires an approved source image");
          }
          var version = profile(profileKey);
          var p = VideoProfiles.settings(map(version.get("definition")), Map.of());
          var plan = motion.plan(a, motionInput);
          var route = router.route(provider, fallback);
          UUID id = UUID.randomUUID(), generation = UUID.randomUUID(), motionId = UUID.randomUUID();
          var rendered =
              prompts.resolve(
                  new PromptRenderRequest(
                      UUID.fromString(p.get("promptVersion").toString()),
                      motion.variables(plan),
                      List.of(),
                      null,
                      null,
                      id.toString(),
                      (UUID) a.get("concept_id"),
                      "video",
                      null,
                      null,
                      null,
                      null),
                  (UUID) a.get("concept_id"),
                  id.toString(),
                  true);
          for (var r : route) {
            var request =
                new VideoTypes.Request(
                    generation,
                    UUID.randomUUID(),
                    sourceId,
                    a.get("sha256").toString(),
                    rendered.canonical().positivePrompt(),
                    rendered.canonical().negativePrompt(),
                    integer(p, "width", 720),
                    integer(p, "height", 1280),
                    integer(p, "duration", 5),
                    null,
                    null,
                    plan,
                    Map.of(),
                    r.get("model").toString());
            router.provider(r.get("provider").toString()).capabilities().validate(request);
          }
          UUID parentGeneration =
              parent == null
                  ? (UUID) a.get("generation_id")
                  : (UUID) one(parent).get("generation_id");
          db.sql(
                  "insert into generations(id,concept_id,parent_id,status,prompt,width,height)"
                      + " values(?,?,?,'QUEUED',?,?,?)")
              .params(
                  generation,
                  a.get("concept_id"),
                  parentGeneration,
                  rendered.canonical().positivePrompt(),
                  p.get("width"),
                  p.get("height"))
              .update();
          db.sql(
                  "insert into"
                      + " video_productions(id,source_asset_id,source_checksum,generation_id,parent_production_id,profile_version_id,profile_snapshot,status,route,prompt_snapshot,max_attempts,budget,idempotency_key,request_hash)"
                      + " values(?,?,?,?,?,?,?::jsonb,'GENERATION_QUEUED',?::jsonb,?::jsonb,?,?,?,?)")
              .params(
                  id,
                  sourceId,
                  a.get("sha256"),
                  generation,
                  parent,
                  version.get("id"),
                  write(p),
                  write(route),
                  write(rendered),
                  maxAttempts,
                  budget,
                  key,
                  hash)
              .update();
          db.sql(
                  "insert into"
                      + " motion_plan_versions(id,production_id,version,definition,source_context,created_by)"
                      + " values(?,?,1,?::jsonb,?::jsonb,'local-workspace')")
              .params(
                  motionId,
                  id,
                  write(plan),
                  write(
                      Map.of(
                          "sourceAssetId",
                          sourceId,
                          "checksum",
                          a.get("sha256"),
                          "imagePrompt",
                          a.get("image_prompt"))))
              .update();
          db.sql("update video_productions set motion_version_id=? where id=?")
              .params(motionId, id)
              .update();
          event(id, "VIDEO_REQUESTED", Map.of("sourceAssetId", sourceId, "profile", profileKey));
          metrics.counter("media_factory_video_production_total").increment();
          return one(id);
        });
  }

  public void event(UUID id, String action, Map<String, ?> details) {
    db.sql("insert into video_events(production_id,action,details) values(?,?,?::jsonb)")
        .params(id, action, write(details))
        .update();
    org.slf4j.LoggerFactory.getLogger(getClass())
        .info("video_event productionId={} action={}", id, action);
  }

  public boolean move(Map<String, Object> v, String status, String reason) {
    return tx.execute(
        t -> {
          int changed =
              db.sql(
                      "update video_productions set"
                          + " status=?,failure_code=?,revision=revision+1,updated_at=now() where"
                          + " id=? and revision=? and status<>'CANCELLED'")
                  .params(status, reason, v.get("id"), v.get("revision"))
                  .update();
          if (changed == 1) {
            event((UUID) v.get("id"), status, Map.of("reason", reason));
          }
          return changed == 1;
        });
  }

  public Map<String, Object> motion(UUID production) {
    var v = one(production);
    return row(
        db.sql("select * from motion_plan_versions where id=?")
            .param(v.get("motion_version_id"))
            .query()
            .singleRow());
  }

  public Object editMotion(UUID id, int revision, Map<String, Object> input) {
    return tx.execute(
        t -> {
          lock(id);
          var v = one(id);
          if (integer(v, "revision", 0) != revision) {
            throw conflict("Video changed");
          }
          var plan = motion.plan(source((UUID) v.get("source_asset_id")), input);
          UUID next = UUID.randomUUID();
          int version =
              db.sql(
                      "select coalesce(max(version),0)+1 from motion_plan_versions where"
                          + " production_id=?")
                  .param(id)
                  .query(Integer.class)
                  .single();
          db.sql(
                  "insert into"
                      + " motion_plan_versions(id,production_id,version,definition,source_context,created_by)"
                      + " values(?,?,?,?::jsonb,?::jsonb,'local-workspace')")
              .params(
                  next,
                  id,
                  version,
                  write(plan),
                  write(Map.of("sourceAssetId", v.get("source_asset_id"))))
              .update();
          db.sql(
                  "update video_productions set"
                      + " motion_version_id=?,revision=revision+1,updated_at=now() where id=?")
              .params(next, id)
              .update();
          event(
              id,
              "MOTION_PLAN_EDITED",
              Map.of("version", version, "appliesTo", "next regeneration/reprocess"));
          return motion(id);
        });
  }

  public Map<String, Object> reprocess(
      UUID id,
      int revision,
      Map<String, Object> changes,
      Map<String, Object> variants,
      String key) {
    if (key == null || key.isBlank() || key.length() > 200) {
      throw new IllegalArgumentException("Idempotency-Key required");
    }
    return tx.execute(
        t -> {
          lock(id);
          var v = one(id);
          String hash = ProcessingPlanner.hash(Arrays.asList(id, changes, variants));
          var old =
              db.sql("select * from video_processing_runs where idempotency_key=?")
                  .param(key)
                  .query()
                  .listOfRows();
          if (!old.isEmpty()) {
            if (!old.getFirst().get("request_hash").equals(hash)) {
              throw conflict("Reprocess idempotency input changed");
            }
            return row(old.getFirst());
          }
          if (integer(v, "revision", 0) != revision
              || v.get("raw_asset_id") == null
              || !Set.of(
                  "RAW_READY",
                  "REVIEW",
                  "READY",
                  "QA_REJECTED",
                  "PROCESSING_FAILED",
                  "LOOP_FAILED")
              .contains(v.get("status"))) {
            throw conflict("Video is not ready for reprocessing");
          }
          var p = VideoProfiles.settings(map(v.get("profile_snapshot")), changes);
          var plan = map(motion(id).get("definition"));
          if (p.get("loopStrategy").equals("PING_PONG")
              && !Boolean.TRUE.equals(plan.get("reversible"))) {
            throw conflict("Motion plan does not permit reversal");
          }
          var selected = variants.isEmpty() ? map(p.get("variants")) : variants;
          if (selected.size() > 7) {
            throw new IllegalArgumentException("Too many variants");
          }
          for (var e : selected.entrySet()) {
            if (!Set.of(
                    "ANDROID_VIDEO_FHD",
                    "ANDROID_VIDEO_QHD",
                    "ANDROID_VIDEO_GENERIC",
                    "SOCIAL_VERTICAL",
                    "SOCIAL_HORIZONTAL",
                    "SOCIAL_SQUARE",
                    "VIDEO_PREVIEW")
                .contains(e.getKey())) {
              throw new IllegalArgumentException("Unknown video variant");
            }
            VideoProfiles.settings(p, map(e.getValue()));
          }
          UUID run = UUID.randomUUID();
          int version =
              db.sql(
                      "select coalesce(max(version),0)+1 from video_processing_runs where"
                          + " production_id=?")
                  .param(id)
                  .query(Integer.class)
                  .single();
          db.sql(
                  "insert into"
                      + " video_processing_runs(id,production_id,raw_asset_id,motion_version_id,version,parameters,variants,request_hash,idempotency_key)"
                      + " values(?,?,?,?,?,?::jsonb,?::jsonb,?,?)")
              .params(
                  run,
                  id,
                  v.get("raw_asset_id"),
                  v.get("motion_version_id"),
                  version,
                  write(p),
                  write(selected),
                  hash,
                  key)
              .update();
          db.sql(
                  "update video_productions set"
                      + " current_run_id=?,master_variant_id=null,status='PROCESSING',approved_at=null,approved_by=null,revision=revision+1,updated_at=now()"
                      + " where id=?")
              .params(run, id)
              .update();
          event(id, "REPROCESS_REQUESTED", Map.of("runId", run, "version", version));
          return row(
              db.sql("select * from video_processing_runs where id=?")
                  .param(run)
                  .query()
                  .singleRow());
        });
  }

  public Object regenerate(UUID id, int revision, BigDecimal budget, String key) {
    var v = one(id);
    if (integer(v, "revision", 0) != revision) {
      throw conflict("Video changed");
    }
    var profile = profileKey(v);
    var route = (List<Map<String, Object>>) v.get("route");
    return start(
        (UUID) v.get("source_asset_id"),
        profile,
        route.getFirst().get("provider").toString(),
        route.size() > 1,
        map(motion(id).get("definition")),
        budget,
        integer(v, "max_attempts", 3),
        key,
        id);
  }

  String profileKey(Map<String, Object> v) {
    return db.sql("select profile_key from video_profile_versions where id=?")
        .param(v.get("profile_version_id"))
        .query(String.class)
        .single();
  }

  void lock(UUID id) {
    db.sql("select id from video_productions where id=? for update").param(id).query().singleRow();
  }

  public Object action(UUID id, int revision, String action, boolean acknowledge, String reason) {
    return tx.execute(
        t -> {
          lock(id);
          var v = one(id);
          if (integer(v, "revision", 0) != revision) {
            throw conflict("Video changed");
          }
          switch (action) {
            case "pause", "resume" -> {
              db.sql("update video_productions set paused=?,revision=revision+1 where id=?")
                  .params(action.equals("pause"), id)
                  .update();
            }
            case "cancel" -> {
              db.sql(
                      "update video_productions set"
                          + " status='CANCELLED',revision=revision+1,updated_at=now() where id=?")
                  .param(id)
                  .update();
            }
            case "approve", "reject" -> {
              if (!Set.of("REVIEW", "READY", "QA_REJECTED", "LOOP_FAILED").contains(v.get("status"))
                  || v.get("master_variant_id") == null) {
                throw conflict("A completed master is required for review");
              }
              var run =
                  row(
                      db.sql("select * from video_processing_runs where id=?")
                          .param(v.get("current_run_id"))
                          .query()
                          .singleRow());
              if (!run.get("status").equals("COMPLETED")) {
                throw conflict("Processing not complete");
              }
              if (action.equals("approve") && !acknowledge) {
                throw conflict("Review the video, loop boundary and QA warnings before approving");
              }
              var raw = source((UUID) v.get("raw_asset_id"));
              if (raw.get("current_review_id") != null) {
                var qr = reviews.review((UUID) raw.get("current_review_id"));
                reviews.decide(
                    (UUID) qr.get("id"),
                    action.equals("approve") ? Decision.APPROVED : Decision.REJECTED,
                    new QualityReviewService.HumanCommand(
                        integer(qr, "revision", 0),
                        "OTHER",
                        Objects.toString(reason, "Video and temporal evidence reviewed")),
                    "local-workspace");
              }
              db.sql(
                      "update video_productions set status=?,approved_at=case when ? then now()"
                          + " else null"
                          + " end,approved_by='local-workspace',revision=revision+1,updated_at=now()"
                          + " where id=?")
                  .params(
                      action.equals("approve") ? "READY" : "QA_REJECTED",
                      action.equals("approve"),
                      id)
                  .update();
            }
            default -> throw new IllegalArgumentException("Unknown video action");
          }
          event(id, action.toUpperCase(), Map.of("reason", Objects.toString(reason, "")));
          return one(id);
        });
  }

  public List<Map<String, Object>> costs(UUID id) {
    return db.sql(
            "select currency,sum(coalesce(actual_cost,estimated_cost)) total,count(*) filter(where"
                + " actual_cost is null and estimated_cost is null) unknown from generation_costs"
                + " where generation_id=(select generation_id from video_productions where id=?)"
                + " group by currency")
        .param(id)
        .query()
        .listOfRows();
  }

  public Object dashboard() {
    var result =
        new LinkedHashMap<>(
            db.sql(
                    "select count(*) total,count(*) filter(where created_at::date=current_date and"
                        + " raw_asset_id is not null) generated_today,count(*) filter(where"
                        + " status='READY') approved,count(*) filter(where status='GENERATING')"
                        + " generating,count(*) filter(where status='GENERATION_QUEUED')"
                        + " queued,count(*) filter(where status='REVIEW') review,count(*)"
                        + " filter(where status='PROCESSING') processing,count(*) filter(where"
                        + " status='GENERATION_FAILED') generation_failed,count(*) filter(where"
                        + " status='QA_REJECTED') qa_failed,count(*) filter(where"
                        + " status='LOOP_FAILED') loop_failed,count(*) filter(where status like"
                        + " '%FAILED' or status='SUBMISSION_UNKNOWN') failed from"
                        + " video_productions")
                .query()
                .singleRow());
    result.put(
        "costs",
        db.sql(
                "select currency,sum(coalesce(actual_cost,estimated_cost)) total,count(*)"
                    + " filter(where actual_cost is null and estimated_cost is null)"
                    + " unknown,sum(coalesce(actual_cost,estimated_cost))/nullif((select count(*)"
                    + " from video_productions where status='READY'),0) cost_per_approved from"
                    + " generation_costs where generation_id in(select generation_id from"
                    + " video_productions) group by currency")
            .query()
            .listOfRows());
    result.put(
        "timings",
        db.sql(
                "select (select avg(duration_ms) from video_generation_attempts)"
                    + " provider_ms,(select avg(duration_ms) from video_processing_runs)"
                    + " processing_ms,(select count(*) from video_processing_runs where"
                    + " status='RUNNING') active_ffmpeg_jobs,(select count(*) from"
                    + " video_generation_attempts where status in"
                    + " ('SUBMITTED','PROVIDER_QUEUED','PROVIDER_PROCESSING','SUBMISSION_UNKNOWN','TIMED_OUT'))"
                    + " active_provider_jobs")
            .query()
            .singleRow());
    return result;
  }
}
