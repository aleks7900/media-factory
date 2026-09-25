package com.mediafactory.wallpaper;

import static com.mediafactory.processing.ProcessingJson.*;

import com.mediafactory.processing.ProcessingPlanner;
import com.mediafactory.processing.ProcessingService;
import com.mediafactory.prompt.PromptModels.PromptRenderRequest;
import com.mediafactory.provider.ImageOptions;
import com.mediafactory.service.FactoryService;
import com.mediafactory.similarity.SimilarityService;
import com.mediafactory.storage.MediaStorage;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class WallpaperProductionService {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final Set<String> READY =
      Set.of(
          "PUBLICATION_REVIEW",
          "APPROVED_FOR_PUBLICATION",
          "PUBLISHING",
          "PUBLISHED",
          "UNPUBLISHED");
  static final Set<String> ACTIVE =
      Set.of(
          "CONCEPT_READY",
          "GENERATING",
          "QA_PENDING",
          "QA_APPROVED",
          "SIMILARITY_CHECK",
          "PROCESSING");
  final JdbcClient db;
  final TransactionTemplate tx;
  final FactoryService factory;
  final ProcessingService processing;
  final SimilarityService similarity;
  final MediaStorage storage;
  final AmoledAnalyzer amoled;
  final MeterRegistry metrics;
  final com.mediafactory.quality.QaConfiguration qaConfiguration;

  public WallpaperProductionService(
      JdbcClient db,
      TransactionTemplate tx,
      FactoryService factory,
      ProcessingService processing,
      SimilarityService similarity,
      MediaStorage storage,
      AmoledAnalyzer amoled,
      MeterRegistry metrics,
      com.mediafactory.quality.QaConfiguration qaConfiguration) {
    this.db = db;
    this.tx = tx;
    this.factory = factory;
    this.processing = processing;
    this.similarity = similarity;
    this.storage = storage;
    this.amoled = amoled;
    this.metrics = metrics;
    this.qaConfiguration = qaConfiguration;
  }

  static ResponseStatusException conflict(String reason) {
    return new ResponseStatusException(HttpStatus.CONFLICT, reason);
  }

  static Map<String, Object> clean(Map<String, Object> row) {
    var result = new LinkedHashMap<>(row);
    for (String key :
        List.of(
            "profile_snapshot",
            "definition",
            "metadata",
            "amoled_result",
            "manifest",
            "external_reference")) {
      if (result.get(key) != null) {
        result.put(key, map(result.get(key)));
      }
    }
    return result;
  }

  static void validateMetadata(Map<String, Object> metadata) {
    String title = Objects.toString(metadata.get("title"), "");
    String slug = Objects.toString(metadata.get("slug"), "");
    if (title.isBlank()
        || title.length() > 200
        || !slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")
        || slug.length() > 160) {
      throw new IllegalArgumentException("Title and lowercase hyphenated slug required");
    }
    if (write(metadata).length() > 16000) {
      throw new IllegalArgumentException("Metadata too large");
    }
    if (metadata.get("tags") instanceof List<?> tags
        && (tags.size() > 30
        || tags.stream().anyMatch(t -> !(t instanceof String) || t.toString().length() > 80))) {
      throw new IllegalArgumentException("At most 30 text tags of 80 characters");
    }
  }

  public Map<String, Object> one(UUID id) {
    return clean(
        db
            .sql(
                "select w.*,c.collection_id,col.project_id from wallpaper_productions w join"
                    + " concepts c on c.id=w.concept_id join collections col on"
                    + " col.id=c.collection_id where w.id=?")
            .param(id)
            .query()
            .listOfRows()
            .stream()
            .findFirst()
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallpaper not found")));
  }

  public List<Map<String, Object>> list() {
    return db
        .sql(
            "select w.*,c.collection_id,(select v.id from asset_variants v join processing_steps s"
                + " on s.output_artifact_id=v.artifact_id where s.run_id=w.processing_run_id and"
                + " s.node_key='ANDROID_THUMBNAIL' limit 1) thumbnail_id from wallpaper_productions"
                + " w join concepts c on c.id=w.concept_id order by w.created_at desc limit 500")
        .query()
        .listOfRows()
        .stream()
        .map(WallpaperProductionService::clean)
        .toList();
  }

  public Object profiles() {
    return db.sql("select * from wallpaper_profiles order by key").query().listOfRows().stream()
        .map(WallpaperProductionService::clean)
        .toList();
  }

  public Object dashboard() {
    var result =
        new LinkedHashMap<>(
            db.sql(
                    "select count(*) filter(where g.created_at>=date_trunc('day',now()))"
                        + " generated_today,count(*) filter(where q.final_decision='APPROVED')"
                        + " qa_approved,count(*) filter(where w.status='DUPLICATE_REJECTED')"
                        + " similarity_rejected,count(*) filter(where w.status in"
                        + " ('PUBLICATION_REVIEW','APPROVED_FOR_PUBLICATION','PUBLISHING','PUBLISHED','UNPUBLISHED'))"
                        + " processing_ready,count(*) filter(where w.status='PUBLICATION_REVIEW')"
                        + " waiting_publication_review,count(*) filter(where w.status='PUBLISHED'"
                        + " and w.published_at>=date_trunc('day',now())) published_today,count(*)"
                        + " filter(where w.amoled_result->>'classification'='AMOLED_SUITABLE' and"
                        + " w.status in"
                        + " ('PUBLICATION_REVIEW','APPROVED_FOR_PUBLICATION','PUBLISHED'))"
                        + " amoled_ready,count(*) filter(where w.status like '%FAILED' or"
                        + " w.status='PAUSED') pipeline_failures from wallpaper_productions w left"
                        + " join generations g on g.id=w.generation_id left join assets a on"
                        + " a.id=w.master_asset_id left join quality_reviews q on"
                        + " q.id=a.current_review_id")
                .query()
                .singleRow());
    result.put(
        "costs",
        db.sql(
                "select c.currency,sum(coalesce(c.actual_cost,c.estimated_cost)) total,count(*)"
                    + " filter(where c.actual_cost is null and c.estimated_cost is null) unknown"
                    + " from generation_costs c join wallpaper_productions w on"
                    + " w.generation_id=c.generation_id group by c.currency")
            .query()
            .listOfRows());
    return result;
  }

  public Object configureProfile(String key, int version, Map<String, Object> definition) {
    if (!key.matches("ANDROID_[A-Z0-9_]{1,60}")) {
      throw new IllegalArgumentException("Invalid wallpaper profile key");
    }
    if (!Boolean.TRUE.equals(definition.get("humanApprovalRequired"))) {
      throw new IllegalArgumentException("Human publication approval must remain enabled");
    }
    if (!(definition.get("amoled") instanceof Boolean)) {
      throw new IllegalArgumentException("Structured AMOLED flag required");
    }
    int width = integer(definition, "generationWidth", 0),
        height = integer(definition, "generationHeight", 0);
    if (width < 1024 || height < width || height > 4096 || width > 4096) {
      throw new IllegalArgumentException("Generation must use supported portrait dimensions");
    }
    for (String field : List.of("safeZoneTop", "safeZoneBottom")) {
      if (number(definition, field, -1) < 0 || number(definition, field, 2) >= 1) {
        throw new IllegalArgumentException("Invalid safe zones");
      }
    }
    var keys = (List<?>) definition.get("processingProfiles");
    if (keys == null
        || keys.size() > 16
        || !keys.containsAll(
        List.of(
            "WALLPAPER_MASTER",
            "ANDROID_FHD_PORTRAIT",
            "ANDROID_QHD_PORTRAIT",
            "ANDROID_GENERIC_PORTRAIT",
            "ANDROID_PREVIEW",
            "ANDROID_THUMBNAIL"))) {
      throw new IllegalArgumentException(
          "Master, device families, preview and thumbnail are required");
    }
    keys.forEach(k -> processing.profile(k.toString()));
    qaConfiguration.policy(definition.get("qaPolicy").toString());
    JSON.readValue(write(definition.get("amoledPolicy")), AmoledAnalyzer.Policy.class);
    UUID prompt = UUID.fromString(definition.get("promptVersionId").toString());
    if (!db.sql("select exists(select 1 from prompt_versions where id=? and status='PUBLISHED')")
        .param(prompt)
        .query(Boolean.class)
        .single()) {
      throw new IllegalArgumentException("Published prompt version required");
    }
    if (db.sql(
            "update wallpaper_profiles set definition=?::jsonb,version=version+1 where key=?"
                + " and version=?")
        .params(write(definition), key, version)
        .update()
        != 1) {
      throw conflict("Profile version changed");
    }
    return Map.of("key", key, "version", version + 1);
  }

  public Object devices() {
    return db.sql(
            "select d.*,d.width::float8/d.height as aspect_ratio,z.top_fraction,z.bottom_fraction"
                + " from wallpaper_device_profiles d left join wallpaper_safe_zones z on"
                + " z.key=d.safe_zone_profile order by d.width desc")
        .query()
        .listOfRows();
  }

  public Map<String, Object> start(
      UUID concept, String profile, Map<String, Object> metadata, String key, UUID parent) {
    if (key == null || key.isBlank() || key.length() > 180) {
      throw new IllegalArgumentException("Idempotency key required, maximum 180 characters");
    }
    validateMetadata(metadata);
    var p =
        db.sql("select definition from wallpaper_profiles where key=?")
            .param(profile)
            .query(String.class)
            .optional()
            .orElseThrow(() -> new IllegalArgumentException("Unknown wallpaper profile"));
    var snapshot = map(p);
    snapshot.put(
        "qaPolicySnapshot",
        map(write(qaConfiguration.policy(snapshot.get("qaPolicy").toString()))));
    // Freeze actual processing version IDs, not only a mutable profile key.
    var profiles = new ArrayList<Map<String, Object>>();
    for (Object name : (List<?>) snapshot.get("processingProfiles")) {
      profiles.add(processing.profile(name.toString()));
    }
    snapshot.put("processingVersions", profiles);
    String hash =
        ProcessingPlanner.hash(
            Map.of(
                "concept",
                concept,
                "profile",
                profile,
                "metadata",
                metadata,
                "parent",
                parent == null ? "" : parent));
    return tx.execute(
        s -> {
          db.sql("select pg_advisory_xact_lock(hashtextextended(?,7))")
              .param(key)
              .query()
              .singleRow();
          var existing =
              db
                  .sql("select id,request_hash from wallpaper_productions where request_key=?")
                  .param(key)
                  .query()
                  .listOfRows()
                  .stream()
                  .findFirst();
          if (existing.isPresent()) {
            if (!hash.equals(existing.get().get("request_hash"))) {
              throw conflict("Key already used for another wallpaper");
            }
            return one((UUID) existing.get().get("id"));
          }
          var c = factory.one("concepts", concept);
          var col = factory.one("collections", (UUID) c.get("collection_id"));
          if (!Boolean.TRUE.equals(col.get("wallpaper"))) {
            throw new IllegalArgumentException("Select a wallpaper collection");
          }
          if (Boolean.TRUE.equals(col.get("amoled")) != Boolean.TRUE.equals(
              snapshot.get("amoled"))) {
            throw new IllegalArgumentException(
                "Collection and wallpaper AMOLED profiles must match");
          }
          if (!Objects.equals(col.get("similarity_profile"), snapshot.get("similarityProfile"))) {
            throw conflict("Collection similarity profile differs from wallpaper profile");
          }
          UUID id = UUID.randomUUID();
          db.sql(
                  "insert into"
                      + " wallpaper_productions(id,concept_id,parent_id,profile_key,profile_snapshot,metadata,request_key,request_hash)"
                      + " values(?,?,?,?,?::jsonb,?::jsonb,?,?)")
              .params(id, concept, parent, profile, write(snapshot), write(metadata), key, hash)
              .update();
          event(id, null, "CONCEPT_READY", "local-workspace", "Production requested");
          metrics
              .counter("media_factory_wallpaper_production_total", "profile", profile)
              .increment();
          return one(id);
        });
  }

  public Object details(UUID id) {
    var result = new LinkedHashMap<>(one(id));
    result.put(
        "events",
        db.sql(
                "select * from wallpaper_production_events where production_id=? order by"
                    + " created_at,id")
            .param(id)
            .query()
            .listOfRows());
    UUID asset = (UUID) result.get("master_asset_id");
    result.put(
        "variants",
        result.get("processing_run_id") == null
            ? List.of()
            : processing.run((UUID) result.get("processing_run_id")).get("variants"));
    if (asset != null) {
      result.put("qa", processing.asset(asset));
      result.put(
          "similarity",
          db.sql(
                  "select final_classification,explanation,id from similarity_comparisons where"
                      + " source_asset_id=? or target_asset_id=?")
              .params(asset, asset)
              .query()
              .listOfRows());
    }
    result.put(
        "publications",
        db
            .sql(
                "select d.*,p.version from wallpaper_deliveries d join"
                    + " wallpaper_publication_packages p on p.id=d.package_id where"
                    + " p.production_id=? order by p.version desc")
            .param(id)
            .query()
            .listOfRows()
            .stream()
            .map(WallpaperProductionService::clean)
            .toList());
    result.put(
        "packages",
        db
            .sql(
                "select * from wallpaper_publication_packages where production_id=? order by"
                    + " version desc")
            .param(id)
            .query()
            .listOfRows()
            .stream()
            .map(WallpaperProductionService::clean)
            .toList());
    result.put(
        "activePublicationVersion",
        db.sql(
                "select max(p.version) from wallpaper_publication_packages p join"
                    + " wallpaper_deliveries d on d.package_id=p.id where p.production_id=? and"
                    + " d.operation='PUBLISH' and d.status='PUBLISHED' and not exists(select 1 from"
                    + " wallpaper_deliveries u where u.package_id=p.id and u.target=d.target and"
                    + " u.operation='UNPUBLISH' and u.status='UNPUBLISHED')")
            .param(id)
            .query(Integer.class)
            .optional()
            .orElse(null));
    result.put(
        "stageTimings",
        db.sql(
                "select from_status,to_status,created_at,extract(epoch from"
                    + " lead(created_at,1,now()) over(order by created_at,id)-created_at)*1000"
                    + " duration_ms from wallpaper_production_events where production_id=? order by"
                    + " created_at,id")
            .param(id)
            .query()
            .listOfRows());
    return result;
  }

  void event(UUID id, String from, String to, String actor, String reason) {
    db.sql(
            "insert into"
                + " wallpaper_production_events(production_id,from_status,to_status,actor,reason)"
                + " values(?,?,?,?,?)")
        .params(id, from, to, actor, reason)
        .update();
    org.slf4j.LoggerFactory.getLogger(getClass())
        .info("wallpaper_transition production={} from={} to={}", id, from, to);
  }

  void move(Map<String, Object> row, String next, String reason) {
    UUID id = (UUID) row.get("id");
    tx.executeWithoutResult(
        s -> {
          int n =
              db.sql(
                      "update wallpaper_productions set"
                          + " status=?,failure_code=?,revision=revision+1,updated_at=now() where"
                          + " id=? and revision=? and status=?")
                  .params(
                      next,
                      reason.isEmpty() ? null : reason,
                      id,
                      row.get("revision"),
                      row.get("status"))
                  .update();
          if (n == 0) {
            throw conflict("Wallpaper changed concurrently");
          }
          event(id, row.get("status").toString(), next, "pipeline", reason);
        });
    if (next.endsWith("REJECTED")) {
      metrics.counter("media_factory_wallpaper_rejected_total", "status", next).increment();
    }
    if (next.equals("PUBLICATION_REVIEW")) {
      metrics.counter("media_factory_wallpaper_ready_total").increment();
    }
  }

  public Object action(UUID id, int revision, String action, String reason) {
    return tx.execute(
        s -> {
          db.sql("select id from wallpaper_productions where id=? for update")
              .param(id)
              .query()
              .singleRow();
          var w = one(id);
          if (integer(w, "revision", -1) != revision) {
            throw conflict("Refresh this wallpaper before changing it");
          }
          String state = w.get("status").toString(), next;
          switch (action) {
            case "pause" -> {
              if (!ACTIVE.contains(state)) {
                throw conflict("Only active production can pause");
              }
              next = "PAUSED";
              db.sql("update wallpaper_productions set previous_status=? where id=?")
                  .params(state, id)
                  .update();
            }
            case "resume" -> {
              if (!state.equals("PAUSED")) {
                throw conflict("Production is not paused");
              }
              next = w.get("previous_status").toString();
            }
            case "cancel" -> {
              if (Set.of("PUBLISHED", "PUBLISHING", "UNPUBLISHED").contains(state)) {
                throw conflict("Use unpublish for delivered wallpapers");
              }
              next = "CANCELLED";
            }
            case "reject" -> {
              if (!Set.of("PUBLICATION_REVIEW", "APPROVED_FOR_PUBLICATION").contains(state)) {
                throw conflict("Wallpaper is not in review");
              }
              next = "REJECTED";
            }
            default -> throw new IllegalArgumentException("Unsupported production action");
          }
          if (reason == null || reason.isBlank() || reason.length() > 2000) {
            throw new IllegalArgumentException("Reason required");
          }
          db.sql(
                  "update wallpaper_productions set"
                      + " status=?,revision=revision+1,lease_token=null,lease_until=null,updated_at=now()"
                      + " where id=?")
              .params(next, id)
              .update();
          event(id, state, next, "local-workspace", reason);
          return one(id);
        });
  }

  public Object metadata(UUID id, int revision, Map<String, Object> values) {
    validateMetadata(values);
    if (db.sql(
            "update wallpaper_productions set metadata=?::jsonb,status=case when"
                + " status='APPROVED_FOR_PUBLICATION' then 'PUBLICATION_REVIEW' else status"
                + " end,approved_by=null,approved_at=null,revision=revision+1,updated_at=now()"
                + " where id=? and revision=? and status in"
                + " ('PUBLICATION_REVIEW','APPROVED_FOR_PUBLICATION','UNPUBLISHED')")
        .params(write(values), id, revision)
        .update()
        != 1) {
      throw conflict("Metadata cannot be edited at this revision/state");
    }
    event(id, null, "METADATA_EDITED", "local-workspace", "Publication approval invalidated");
    return one(id);
  }

  public Object regenerate(UUID id, String key) {
    var w = one(id);
    return start(
        (UUID) w.get("concept_id"),
        w.get("profile_key").toString(),
        map(w.get("metadata")),
        key,
        id);
  }

  public Object reprocess(UUID id, int revision, UUID runId) {
    return tx.execute(
        s -> {
          db.sql("select id from wallpaper_productions where id=? for update")
              .param(id)
              .query()
              .singleRow();
          var w = one(id);
          if (!w.get("revision").equals(revision)
              || !Set.of("PUBLICATION_REVIEW", "PROCESSING_FAILED", "UNPUBLISHED")
              .contains(w.get("status"))) {
            throw conflict("Unpublish before changing delivered variants; refresh this revision");
          }
          var run = processing.run(runId);
          if (!w.get("master_asset_id").equals(run.get("source_asset_id"))) {
            throw conflict("Processing source must be the same original");
          }
          var plan = map(run.get("plan"));
          Set<String> keys = new HashSet<>();
          for (var node : (List<Map<String, Object>>) plan.get("nodes")) {
            keys.add(node.get("key").toString());
          }
          if (!keys.containsAll(
              (List<?>) map(w.get("profile_snapshot")).get("processingProfiles"))) {
            throw conflict("Reprocessing must include every required wallpaper profile");
          }
          db.sql(
                  "update wallpaper_productions set"
                      + " processing_run_id=?,master_variant_id=null,approved_by=null,approved_at=null,status='PROCESSING',revision=revision+1,updated_at=now()"
                      + " where id=?")
              .params(runId, id)
              .update();
          event(
              id,
              w.get("status").toString(),
              "PROCESSING",
              "local-workspace",
              "New processing run " + runId);
          return one(id);
        });
  }

  public void advance(UUID id) {
    var w = one(id);
    String status = w.get("status").toString();
    if (!ACTIVE.contains(status)) {
      return;
    }
    var collectionPlan =
        db.sql("select status from wallpaper_collection_plans where collection_id=?")
            .param(w.get("collection_id"))
            .query(String.class)
            .optional();
    if (collectionPlan.isPresent()
        && (collectionPlan.get().equals("CANCELLED")
        || (!Set.of("RUNNING", "COMPLETED").contains(collectionPlan.get())
        && Set.of("CONCEPT_READY", "SIMILARITY_CHECK").contains(status)))) {
      return;
    }
    var p = map(w.get("profile_snapshot"));
    switch (status) {
      case "CONCEPT_READY" -> tx.executeWithoutResult(
          s -> {
            db.sql("select id from wallpaper_productions where id=? for update")
                .param(id)
                .query()
                .singleRow();
            var fresh = one(id);
            if (!fresh.get("status").equals(status)) {
              return;
            }
            var c = factory.one("concepts", (UUID) w.get("concept_id"));
            var col = factory.one("collections", (UUID) c.get("collection_id"));
            var vars = new LinkedHashMap<String, Object>();
            vars.put("subject", c.get("prompt"));
            vars.put("wallpaper_collectionTheme", col.get("theme"));
            vars.put("wallpaper_style", col.get("style"));
            vars.put("wallpaper_orientation", "PORTRAIT");
            vars.put(
                "wallpaper_aspectRatio",
                integer(p, "generationWidth", 1024)
                    + ":"
                    + integer(p, "generationHeight", 2048));
            vars.put("wallpaper_subjectPlacement", p.get("subjectPlacement"));
            vars.put("wallpaper_safeZoneTop", p.get("safeZoneTop").toString());
            vars.put("wallpaper_safeZoneBottom", p.get("safeZoneBottom").toString());
            vars.put(
                "wallpaper_background",
                Boolean.TRUE.equals(p.get("amoled"))
                    ? "Dominant pure black background, restrained bright focal highlights,"
                      + " clear subject separation, no gray haze, preserve important detail"
                    : "Clear negative space and balanced background");
            var prompt =
                new PromptRenderRequest(
                    UUID.fromString(p.get("promptVersionId").toString()),
                    vars,
                    List.of(),
                    null,
                    null,
                    null,
                    (UUID) c.get("id"),
                    "wallpaper",
                    null,
                    null,
                    null,
                    null);
            UUID parent =
                w.get("parent_id") == null
                    ? null
                    : (UUID) one((UUID) w.get("parent_id")).get("generation_id");
            var g =
                factory.generatePrompt(
                    (UUID) c.get("id"),
                    integer(p, "generationWidth", 1024),
                    integer(p, "generationHeight", 2048),
                    "wallpaper:" + id,
                    parent,
                    ImageOptions.defaults(),
                    prompt,
                    false);
            if (collectionPlan.isPresent()) {
              var budget =
                  db.sql(
                          "select max_cost,reserved_cost_per_attempt from"
                              + " wallpaper_collection_plans where collection_id=?")
                      .param(c.get("collection_id"))
                      .query()
                      .singleRow();
              if ((factory.route(g).stream().anyMatch(h -> !h.provider().equals("mock"))
                  || !qaConfiguration.provider().equals("mock"))
                  && (((java.math.BigDecimal) budget.get("max_cost")).signum() == 0
                  || ((java.math.BigDecimal) budget.get("reserved_cost_per_attempt"))
                  .signum()
                  == 0)) {
                throw conflict(
                    "Paid collection generation requires a nonzero explicit budget and"
                        + " per-attempt reserve");
              }
            }
            db.sql("update wallpaper_productions set generation_id=? where id=?")
                .params(g.get("id"), id)
                .update();
            move(w, "GENERATING", "");
          });
      case "GENERATING" -> {
        var g = factory.one("generations", (UUID) w.get("generation_id"));
        if ("FAILED".equals(g.get("status"))) {
          move(w, "GENERATION_FAILED", "GENERATION_FAILED");
          return;
        }
        var a =
            db.sql("select id from assets where generation_id=?")
                .param(g.get("id"))
                .query(UUID.class)
                .optional();
        if (a.isPresent()) {
          db.sql("update wallpaper_productions set master_asset_id=? where id=? and revision=?")
              .params(a.get(), id, w.get("revision"))
              .update();
          move(w, "QA_PENDING", "");
        }
      }
      case "QA_PENDING" -> {
        var a = processing.asset((UUID) w.get("master_asset_id"));
        if (a.get("current_review_id") != null) {
          var review =
              db.sql(
                      "select policy_id,policy_version,execution_status from quality_reviews where"
                          + " id=?")
                  .param(a.get("current_review_id"))
                  .query()
                  .singleRow();
          if (!Objects.equals(review.get("policy_id"), p.get("qaPolicy"))
              || !Objects.equals(
              review.get("policy_version"), map(p.get("qaPolicySnapshot")).get("version"))) {
            throw conflict("Wallpaper QA policy changed; rerun the required frozen policy");
          }
          if ("FAILED".equals(review.get("execution_status"))) {
            db.sql("update wallpaper_productions set previous_status=status where id=?")
                .param(id)
                .update();
            move(w, "PAUSED", "QA_EXECUTION_FAILED");
            return;
          }
        }
        if ("APPROVED".equals(a.get("final_decision"))) {
          move(w, "QA_APPROVED", "");
        } else if ("REJECTED".equals(a.get("final_decision"))) {
          move(w, "QA_REJECTED", "VISUAL_QA_REJECTED");
        }
      }
      case "QA_APPROVED" -> {
        if (Boolean.TRUE.equals(p.get("amoled"))) {
          var a = processing.asset((UUID) w.get("master_asset_id"));
          var result =
              amoled.analyze(
                  storage.read(a.get("storage_key").toString()),
                  JSON.readValue(write(p.get("amoledPolicy")), AmoledAnalyzer.Policy.class));
          db.sql(
                  "insert into"
                      + " wallpaper_amoled_analyses(production_id,stage,source_checksum,result)"
                      + " values(?,'ORIGINAL',?,?::jsonb) on conflict do nothing")
              .params(id, a.get("sha256"), write(result))
              .update();
          db.sql(
                  "update wallpaper_productions set amoled_result=?::jsonb where id=? and"
                      + " revision=?")
              .params(write(result), id, w.get("revision"))
              .update();
          if (!result.classification().equals("AMOLED_SUITABLE")) {
            move(w, "AMOLED_REJECTED", result.classification());
            return;
          }
          metrics.counter("media_factory_amoled_suitable_total").increment();
        }
        var model = similarity.activeModel();
        similarity.enqueue((UUID) w.get("master_asset_id"), model.id(), null);
        db.sql("update wallpaper_productions set similarity_model_id=? where id=? and revision=?")
            .params(model.id(), id, w.get("revision"))
            .update();
        move(w, "SIMILARITY_CHECK", "");
      }
      case "SIMILARITY_CHECK" -> {
        UUID asset = (UUID) w.get("master_asset_id"), model = (UUID) w.get("similarity_model_id");
        String ready = similarity.state(asset, model);
        if (ready.equals("FAILED")) {
          db.sql("update wallpaper_productions set previous_status=status where id=?")
              .param(id)
              .update();
          move(w, "PAUSED", "SIMILARITY_FAILED");
          return;
        }
        if (!ready.equals("READY")) {
          return;
        }
        String blocked =
            db.sql("select similarity_publication_block_reason(?)")
                .param(asset)
                .query(String.class)
                .optional()
                .orElse(null);
        if (blocked != null) {
          move(w, "DUPLICATE_REJECTED", "SIMILARITY_POLICY_BLOCKED");
          return;
        }
        tx.executeWithoutResult(
            s -> {
              db.sql("select id from wallpaper_productions where id=? for update")
                  .param(id)
                  .query()
                  .singleRow();
              if (!one(id).get("status").equals(status)) {
                return;
              }
              var run =
                  processing.requestFrozen(
                      asset,
                      (List<Map<String, Object>>) p.get("processingVersions"),
                      Map.of(),
                      "wallpaper-processing:" + id,
                      10);
              db.sql("update wallpaper_productions set processing_run_id=? where id=?")
                  .params(run.get("processingRunId"), id)
                  .update();
              move(w, "PROCESSING", "");
            });
      }
      case "PROCESSING" -> {
        var run =
            db.sql("select status from processing_runs where id=?")
                .param(w.get("processing_run_id"))
                .query(String.class)
                .single();
        if (run.equals("COMPLETED")) {
          var master =
              db.sql(
                      "select v.id from processing_steps s join asset_variants v on"
                          + " v.artifact_id=s.output_artifact_id where s.run_id=? and"
                          + " s.node_key='WALLPAPER_MASTER'")
                  .param(w.get("processing_run_id"))
                  .query(UUID.class)
                  .single();
          if (Boolean.TRUE.equals(p.get("amoled"))) {
            var v =
                db.sql("select * from asset_variants where id=?").param(master).query().singleRow();
            var result =
                amoled.analyze(
                    storage.read(v.get("storage_key").toString()),
                    JSON.readValue(write(p.get("amoledPolicy")), AmoledAnalyzer.Policy.class));
            db.sql(
                    "insert into"
                        + " wallpaper_amoled_analyses(production_id,stage,source_checksum,result)"
                        + " values(?,'MASTER',?,?::jsonb) on conflict do nothing")
                .params(id, v.get("sha256"), write(result))
                .update();
            db.sql(
                    "update wallpaper_productions set amoled_result=?::jsonb where id=? and"
                        + " revision=?")
                .params(write(result), id, w.get("revision"))
                .update();
            if (!result.classification().equals("AMOLED_SUITABLE")) {
              move(w, "AMOLED_REJECTED", "PROCESSED_MASTER_" + result.classification());
              return;
            }
          }
          db.sql("update wallpaper_productions set master_variant_id=? where id=? and revision=?")
              .params(master, id, w.get("revision"))
              .update();
          move(w, "PUBLICATION_REVIEW", "");
        } else if (Set.of("FAILED", "CANCELLED", "PARTIALLY_COMPLETED").contains(run)) {
          move(w, "PROCESSING_FAILED", "PROCESSING_" + run);
        }
      }
      default -> {
      }
    }
  }
}
