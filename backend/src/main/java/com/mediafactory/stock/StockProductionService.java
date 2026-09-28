package com.mediafactory.stock;

import static com.mediafactory.processing.ProcessingJson.*;

import com.mediafactory.processing.*;
import com.mediafactory.prompt.PromptModels.PromptRenderRequest;
import com.mediafactory.provider.ImageOptions;
import com.mediafactory.quality.*;
import com.mediafactory.service.FactoryService;
import com.mediafactory.similarity.*;
import com.mediafactory.storage.MediaStorage;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StockProductionService {
  final JdbcClient db;
  final TransactionTemplate tx;
  final FactoryService factory;
  final ProcessingService processing;
  final SimilarityService similarity;
  final QualityReviewService reviews;
  final QaConfiguration qa;
  final MediaStorage storage;
  final StockTechnicalValidator technical;
  final StockMetadataService metadata;
  final MeterRegistry metrics;
  static final Set<String> ACTIVE =
      Set.of(
          "DRAFT",
          "SOURCE_READY",
          "QA_PENDING",
          "QA_APPROVED",
          "SIMILARITY_CHECK",
          "PROCESSING",
          "TECHNICAL_VALIDATION",
          "METADATA_GENERATION");

  public StockProductionService(
      JdbcClient db,
      TransactionTemplate tx,
      FactoryService factory,
      ProcessingService processing,
      SimilarityService similarity,
      QualityReviewService reviews,
      QaConfiguration qa,
      MediaStorage storage,
      StockTechnicalValidator technical,
      StockMetadataService metadata,
      MeterRegistry metrics) {
    this.db = db;
    this.tx = tx;
    this.factory = factory;
    this.processing = processing;
    this.similarity = similarity;
    this.reviews = reviews;
    this.qa = qa;
    this.storage = storage;
    this.technical = technical;
    this.metadata = metadata;
    this.metrics = metrics;
  }

  static ResponseStatusException conflict(String m) {
    return new ResponseStatusException(HttpStatus.CONFLICT, m);
  }

  public static Map<String, Object> json(Map<String, Object> r) {
    var out = new LinkedHashMap<>(r);
    for (String k :
        List.of(
            "profile_snapshot",
            "definition",
            "data",
            "validation",
            "observations",
            "provenance",
            "result",
            "snapshot",
            "manifest")) if (out.get(k) != null) out.put(k, map(out.get(k)));
    return out;
  }

  public Map<String, Object> one(UUID id) {
    return json(
        db
            .sql(
                "select s.*,c.collection_id,c.name as concept_name,col.project_id from"
                    + " stock_productions s join concepts c on c.id=s.concept_id join collections"
                    + " col on col.id=c.collection_id where s.id=?")
            .param(id)
            .query()
            .listOfRows()
            .stream()
            .findFirst()
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Stock production not found")));
  }

  public Object list() {
    return db
        .sql(
            "select s.*,c.collection_id,m.data as metadata,(select v.id from processing_steps ps"
                + " join asset_variants v on v.artifact_id=ps.output_artifact_id where"
                + " ps.run_id=s.processing_run_id and ps.node_key='THUMBNAIL' limit 1) thumbnail_id"
                + " from stock_productions s join concepts c on c.id=s.concept_id left join"
                + " stock_metadata_versions m on m.id=s.metadata_version_id order by s.created_at"
                + " desc limit 1000")
        .query()
        .listOfRows()
        .stream()
        .map(
            r -> {
              var out = json(r);
              if (out.get("metadata") != null) out.put("metadata", map(out.get("metadata")));
              return out;
            })
        .toList();
  }

  public Object profiles() {
    return db
        .sql("select * from stock_profile_versions order by profile_key,version desc")
        .query()
        .listOfRows()
        .stream()
        .map(StockProductionService::json)
        .toList();
  }

  public Map<String, Object> profile(String key) {
    var r =
        json(
            db.sql(
                    "select * from stock_profile_versions where profile_key=? order by version desc"
                        + " limit 1")
                .param(key)
                .query()
                .singleRow());
    if (!Boolean.TRUE.equals(map(r.get("definition")).get("enabled")))
      throw conflict("Profile requires requirement review before enabling");
    return r;
  }

  public Object newProfile(String key, int previous, Map<String, Object> p) {
    StockPlatformRequirements.validate(p);
    if (!key.matches("STOCK_[A-Z0-9_]{1,60}"))
      throw new IllegalArgumentException("Invalid profile key");
    for (String k :
        List.of(
            "minimumMegapixels",
            "maximumMegapixels",
            "minimumWidth",
            "minimumHeight",
            "maximumWidth",
            "maximumHeight",
            "maximumFileSize",
            "preferredQuality",
            "minimumTitleLength",
            "maximumTitleLength",
            "minimumDescriptionLength",
            "maximumDescriptionLength",
            "minimumKeywords",
            "maximumKeywords"))
      if (!(p.get(k) instanceof Number n)
          || !Double.isFinite(n.doubleValue())
          || n.doubleValue() < 0) throw new IllegalArgumentException("Invalid " + k);
    if (number(p, "minimumMegapixels", 0) > number(p, "maximumMegapixels", 64)
        || number(p, "maximumMegapixels", 0) > 64
        || integer(p, "maximumFileSize", 0) > 67108864
        || integer(p, "minimumKeywords", 0) > integer(p, "maximumKeywords", 0)
        || integer(p, "maximumKeywords", 0) > 100
        || integer(p, "maximumTitleLength", 0) > 500
        || integer(p, "maximumDescriptionLength", 0) > 4000
        || !"sRGB".equals(p.get("colorSpace"))
        || !List.of("JPEG").equals(p.get("acceptedFormats")))
      throw new IllegalArgumentException(
          "Invalid profile bounds; initial export supports JPEG/sRGB");
    processing.profile(p.get("processingProfile").toString());
    qa.policy(p.get("qaPolicy").toString());
    return tx.execute(
        t -> {
          db.sql("select pg_advisory_xact_lock(hashtext(?))")
              .param("stock-profile:" + key)
              .query()
              .singleRow();
          int v =
              db.sql(
                      "select coalesce(max(version),0) from stock_profile_versions where"
                          + " profile_key=?")
                  .param(key)
                  .query(Integer.class)
                  .single();
          if (v != previous) throw conflict("Profile changed");
          return json(
              db.sql(
                      "insert into stock_profile_versions(profile_key,version,definition)"
                          + " values(?,?,?::jsonb) returning *")
                  .params(key, v + 1, write(p))
                  .query()
                  .singleRow());
        });
  }

  public Map<String, Object> start(UUID concept, UUID asset, String key, String requestKey) {
    if (requestKey == null || requestKey.isBlank() || requestKey.length() > 180)
      throw new IllegalArgumentException("Idempotency-Key required (1..180 characters)");
    if (asset != null) {
      var a = processing.asset(asset);
      var g = factory.one("generations", (UUID) a.get("generation_id"));
      if (concept != null && !concept.equals(g.get("concept_id")))
        throw conflict("Asset belongs to another concept");
      concept = (UUID) g.get("concept_id");
    }
    if (concept == null) throw new IllegalArgumentException("Concept or source asset required");
    UUID cId = concept;
    String hash = ProcessingPlanner.hash(Arrays.asList(concept, asset, key));
    return tx.execute(
        t -> {
          db.sql("select pg_advisory_xact_lock(hashtext(?))")
              .param("stock:" + requestKey)
              .query()
              .singleRow();
          var old =
              db.sql("select id,request_hash from stock_productions where request_key=?")
                  .param(requestKey)
                  .query()
                  .listOfRows();
          if (!old.isEmpty()) {
            if (!hash.equals(old.getFirst().get("request_hash")))
              throw conflict("Idempotency key reused for different input");
            return one((UUID) old.getFirst().get("id"));
          }
          var c = factory.one("concepts", cId);
          var col = factory.one("collections", (UUID) c.get("collection_id"));
          if (!"STOCK_STRICT".equals(col.get("similarity_profile")))
            throw conflict("Stock collection must use STOCK_STRICT similarity");
          var profile = profile(key);
          var p = new LinkedHashMap<>(map(profile.get("definition")));
          p.put(
              "processingVersions",
              List.of(
                  processing.profile(p.get("processingProfile").toString()),
                  processing.profile("THUMBNAIL")));
          p.put("qaPolicySnapshot", map(write(qa.policy(p.get("qaPolicy").toString()))));
          UUID id = UUID.randomUUID();
          Object generation = asset == null ? null : processing.asset(asset).get("generation_id");
          db.sql(
                  "insert into"
                      + " stock_productions(id,concept_id,generation_id,source_asset_id,profile_version_id,profile_snapshot,status,request_key,request_hash)"
                      + " values(?,?,?,?,?,?::jsonb,?,?,?)")
              .params(
                  id,
                  cId,
                  generation,
                  asset,
                  profile.get("id"),
                  write(p),
                  asset == null ? "DRAFT" : "SOURCE_READY",
                  requestKey,
                  hash)
              .update();
          event(id, "STARTED", Map.of("profile", key));
          metrics.counter("media_factory_stock_production_total", "profile", key).increment();
          return one(id);
        });
  }

  void event(UUID id, String action, Object details) {
    db.sql("insert into stock_events(production_id,action,details) values(?,?,?::jsonb)")
        .params(id, action, write(details))
        .update();
    org.slf4j.LoggerFactory.getLogger(getClass())
        .info("stock_event productionId={} action={}", id, action);
  }

  boolean move(Map<String, Object> s, String next, String reason) {
    return tx.execute(
        t -> {
          int changed =
              db.sql(
                      "update stock_productions set"
                          + " status=?,failure_code=?,revision=revision+1,updated_at=now() where"
                          + " id=? and revision=?")
                  .params(next, reason, s.get("id"), s.get("revision"))
                  .update();
          if (changed == 1) event((UUID) s.get("id"), next, Map.of("reason", reason));
          return changed == 1;
        });
  }

  public Object detail(UUID id) {
    var s = one(id);
    s.put("metadataVersions", metadata.versions(id));
    s.put(
        "technical",
        db
            .sql(
                "select * from stock_validation_results where production_id=? order by created_at"
                    + " desc")
            .param(id)
            .query()
            .listOfRows()
            .stream()
            .map(StockProductionService::json)
            .toList());
    s.put(
        "events",
        db.sql("select * from stock_events where production_id=? order by created_at")
            .param(id)
            .query()
            .listOfRows());
    s.put(
        "exports",
        db.sql(
                "select e.id,e.status,e.created_at,i.metadata_version_id from stock_exports e join"
                    + " stock_export_items i on i.export_id=e.id where i.production_id=? order by"
                    + " e.created_at desc")
            .param(id)
            .query()
            .listOfRows());
    if (s.get("source_asset_id") != null) {
      s.put("qa", processing.asset((UUID) s.get("source_asset_id")));
      s.put(
          "similarity",
          db.sql(
                  "select final_classification,explanation from similarity_comparisons where"
                      + " source_asset_id=? or target_asset_id=?")
              .params(s.get("source_asset_id"), s.get("source_asset_id"))
              .query()
              .listOfRows());
    }
    if (s.get("stock_variant_id") != null)
      s.put("variant", variant((UUID) s.get("stock_variant_id")));
    return s;
  }

  Map<String, Object> variant(UUID id) {
    return db.sql("select * from asset_variants where id=?").param(id).query().singleRow();
  }

  Map<String, Object> encoderEvidence(UUID variantId) {
    return db.sql(
            "select a.metadata from asset_variants v join processing_artifacts a on"
                + " a.id=v.artifact_id where v.id=?")
        .param(variantId)
        .query(String.class)
        .optional()
        .map(com.mediafactory.processing.ProcessingJson::map)
        .orElse(Map.of());
  }

  public List<String> gates(Map<String, Object> s, boolean requireApproval) {
    var errors = new ArrayList<String>();
    if (s.get("source_asset_id") == null) {
      errors.add("SOURCE_MISSING");
      return errors;
    }
    var a = processing.asset((UUID) s.get("source_asset_id"));
    if (!"APPROVED".equals(a.get("final_decision"))) errors.add("QA_NOT_APPROVED");
    if (a.get("current_review_id") != null) {
      var review = reviews.review((UUID) a.get("current_review_id"));
      var frozen = map(s.get("profile_snapshot"));
      if (!Objects.equals(review.get("policy_id"), frozen.get("qaPolicy"))
          || !Objects.equals(
              review.get("policy_version"), map(frozen.get("qaPolicySnapshot")).get("version")))
        errors.add("QA_POLICY_CHANGED");
    }
    if (!"STOCK_STRICT"
        .equals(
            factory.one("collections", (UUID) s.get("collection_id")).get("similarity_profile")))
      errors.add("STOCK_SIMILARITY_POLICY_CHANGED");
    if (!"READY"
        .equals(similarity.state((UUID) s.get("source_asset_id"), similarity.activeModel().id())))
      errors.add("SIMILARITY_INCOMPLETE");
    db.sql("select similarity_publication_block_reason(?)")
        .param(s.get("source_asset_id"))
        .query(String.class)
        .optional()
        .ifPresent(errors::add);
    if (s.get("stock_variant_id") == null) errors.add("STOCK_VARIANT_MISSING");
    else {
      var v = variant((UUID) s.get("stock_variant_id"));
      if (!"VALID".equals(v.get("validation_status"))) errors.add("VARIANT_INVALID");
      var validation =
          db.sql(
                  "select result from stock_validation_results where production_id=? and"
                      + " variant_id=? order by created_at desc limit 1")
              .params(s.get("id"), v.get("id"))
              .query(String.class)
              .optional();
      if (validation.isEmpty() || !Boolean.TRUE.equals(map(validation.get()).get("valid")))
        errors.add("TECHNICAL_VALIDATION_FAILED");
    }
    if (requireApproval) {
      if (s.get("metadata_version_id") == null) errors.add("METADATA_MISSING");
      else {
        var m = metadata.version((UUID) s.get("metadata_version_id"));
        if (!"APPROVED".equals(m.get("source"))) errors.add("METADATA_NOT_APPROVED");
        if ("FAIL".equals(map(m.get("validation")).get("status"))) errors.add("METADATA_INVALID");
      }
      if (!Set.of("READY_FOR_EXPORT", "EXPORTED").contains(s.get("status")))
        errors.add("NOT_READY_FOR_EXPORT");
    }
    return errors;
  }

  public Object approve(UUID id, int revision, boolean acknowledgeWarnings) {
    return tx.execute(
        t -> {
          db.sql("select id from stock_productions where id=? for update")
              .param(id)
              .query()
              .singleRow();
          var s = one(id);
          if (integer(s, "revision", -1) != revision || !"METADATA_REVIEW".equals(s.get("status")))
            throw conflict("Stock review changed");
          var errors = gates(s, false);
          if (!errors.isEmpty()) throw conflict(String.join(", ", errors));
          var m = metadata.version((UUID) s.get("metadata_version_id"));
          if ("FAIL".equals(map(m.get("validation")).get("status")))
            throw conflict("Metadata validation failed");
          if ("WARNING".equals(map(m.get("validation")).get("status")) && !acknowledgeWarnings)
            throw conflict("Explicitly acknowledge metadata warnings");
          var approved =
              metadata.save(
                  s,
                  map(m.get("data")),
                  map(m.get("observations")),
                  map(m.get("provenance")),
                  "APPROVED",
                  UUID.randomUUID());
          db.sql(
                  "update stock_productions set"
                      + " metadata_version_id=?,approved_at=now(),approved_by='local-workspace'"
                      + " where id=?")
              .params(approved.get("id"), id)
              .update();
          move(s, "READY_FOR_EXPORT", "");
          metrics.counter("media_factory_stock_ready_total").increment();
          long elapsed =
              db.sql(
                      "select greatest(0,extract(epoch from(now()-created_at))*1000)::bigint from"
                          + " stock_productions where id=?")
                  .param(id)
                  .query(Long.class)
                  .single();
          metrics
              .timer("media_factory_stock_pipeline_duration")
              .record(elapsed, java.util.concurrent.TimeUnit.MILLISECONDS);
          return one(id);
        });
  }

  public Object action(UUID id, int revision, String action) {
    return tx.execute(
        t -> {
          db.sql("select id from stock_productions where id=? for update")
              .param(id)
              .query()
              .singleRow();
          var s = one(id);
          if (integer(s, "revision", -1) != revision) throw conflict("Production changed");
          String next =
              switch (action) {
                case "reject" -> "REVIEW_REJECTED";
                case "cancel" -> "CANCELLED";
                case "retry" -> {
                  String failure = Objects.toString(s.get("failure_code"), "");
                  if (s.get("status").toString().endsWith("FAILED")
                      && failure.startsWith("STAGE_EXECUTION_FAILED:")) {
                    String failedStage = failure.substring("STAGE_EXECUTION_FAILED:".length());
                    if (!StockWorker.ACTIVE.contains(failedStage))
                      throw conflict("Unknown failed stage");
                    yield failedStage;
                  }
                  yield switch (s.get("status").toString()) {
                    case "PROCESSING_FAILED" -> {
                      processing.retry((UUID) s.get("processing_run_id"));
                      yield "PROCESSING";
                    }
                    case "VALIDATION_FAILED" -> "TECHNICAL_VALIDATION";
                    case "METADATA_FAILED" -> "METADATA_GENERATION";
                    default -> throw conflict("Retry is not available for this state");
                  };
                }
                default -> throw new IllegalArgumentException("Unknown action");
              };
          if (!action.equals("retry") && Set.of("EXPORTED", "CANCELLED").contains(s.get("status")))
            throw conflict("Historical exported production cannot be cancelled or rejected");
          db.sql("update stock_productions set attempt=0 where id=?").param(id).update();
          move(s, next, "OPERATOR_" + action.toUpperCase());
          return one(id);
        });
  }

  public void advance(UUID id) {
    var s = one(id);
    String status = s.get("status").toString();
    var p = map(s.get("profile_snapshot"));
    var plan =
        db
            .sql("select * from stock_collection_plans where collection_id=?")
            .param(s.get("collection_id"))
            .query()
            .listOfRows()
            .stream()
            .findFirst();
    if (plan.isPresent()
        && !Set.of("RUNNING", "COMPLETED").contains(plan.get().get("status"))
        && Set.of("DRAFT", "SIMILARITY_CHECK", "METADATA_GENERATION").contains(status)) return;
    switch (status) {
      case "DRAFT" ->
          tx.executeWithoutResult(
              t -> {
                db.sql("select id from stock_productions where id=? for update")
                    .param(id)
                    .query()
                    .singleRow();
                if (!one(id).get("status").equals(status)) return;
                var c = factory.one("concepts", (UUID) s.get("concept_id"));
                var g =
                    factory.generatePrompt(
                        (UUID) c.get("id"),
                        integer(p, "generationWidth", 2048),
                        integer(p, "generationHeight", 2048),
                        "stock:" + id,
                        null,
                        ImageOptions.defaults(),
                        new PromptRenderRequest(
                            UUID.fromString(p.get("imagePromptVersion").toString()),
                            Map.of("subject", c.get("prompt")),
                            List.of(),
                            null,
                            null,
                            null,
                            (UUID) c.get("id"),
                            "stock",
                            null,
                            null,
                            null,
                            null),
                        false);
                if (plan.isPresent()
                    && (factory.route(g).stream().anyMatch(h -> !h.provider().equals("mock"))
                        || !qa.provider().equals("mock"))
                    && (((java.math.BigDecimal) plan.get().get("max_cost")).signum() == 0
                        || ((java.math.BigDecimal) plan.get().get("reserve_per_attempt")).signum()
                            == 0))
                  throw conflict("Paid collection dispatch requires explicit budget and reserve");
                db.sql("update stock_productions set generation_id=? where id=?")
                    .params(g.get("id"), id)
                    .update();
                move(s, "SOURCE_READY", "");
              });
      case "SOURCE_READY" -> {
        if (s.get("source_asset_id") == null) {
          var assets =
              db.sql("select id from assets where generation_id=?")
                  .param(s.get("generation_id"))
                  .query(UUID.class)
                  .list();
          if (assets.isEmpty()) {
            if ("FAILED"
                .equals(factory.one("generations", (UUID) s.get("generation_id")).get("status")))
              move(s, "QA_REJECTED", "GENERATION_FAILED");
            return;
          }
          db.sql("update stock_productions set source_asset_id=? where id=? and revision=?")
              .params(assets.getFirst(), id, s.get("revision"))
              .update();
          s.putAll(one(id));
        }
        var a = processing.asset((UUID) s.get("source_asset_id"));
        boolean rerun = false;
        if (a.get("current_review_id") != null) {
          var r = reviews.review((UUID) a.get("current_review_id"));
          rerun = !p.get("qaPolicy").equals(r.get("policy_id"));
        }
        reviews.enqueue((UUID) s.get("source_asset_id"), rerun, p.get("qaPolicy").toString(), null);
        move(s, "QA_PENDING", "");
      }
      case "QA_PENDING" -> {
        var a = processing.asset((UUID) s.get("source_asset_id"));
        if ("APPROVED".equals(a.get("final_decision"))) move(s, "QA_APPROVED", "");
        else if ("REJECTED".equals(a.get("final_decision")))
          move(s, "QA_REJECTED", "VISUAL_QA_REJECTED");
      }
      case "QA_APPROVED" -> {
        var model = similarity.activeModel();
        similarity.enqueue((UUID) s.get("source_asset_id"), model.id(), null);
        db.sql("update stock_productions set similarity_model_id=? where id=? and revision=?")
            .params(model.id(), id, s.get("revision"))
            .update();
        move(s, "SIMILARITY_CHECK", "");
      }
      case "SIMILARITY_CHECK" -> {
        String state =
            similarity.state((UUID) s.get("source_asset_id"), (UUID) s.get("similarity_model_id"));
        if (state.equals("FAILED")) {
          move(s, "DUPLICATE_REJECTED", "SIMILARITY_FAILED");
          return;
        }
        if (!state.equals("READY")) return;
        var block =
            db.sql("select similarity_publication_block_reason(?)")
                .param(s.get("source_asset_id"))
                .query(String.class)
                .optional();
        if (block.isPresent()) {
          move(s, "DUPLICATE_REJECTED", block.get());
          return;
        }
        var run =
            processing.requestFrozen(
                (UUID) s.get("source_asset_id"),
                (List<Map<String, Object>>) p.get("processingVersions"),
                Map.of(),
                "stock-processing:" + id,
                10);
        db.sql("update stock_productions set processing_run_id=? where id=? and revision=?")
            .params(run.get("processingRunId"), id, s.get("revision"))
            .update();
        move(s, "PROCESSING", "");
      }
      case "PROCESSING" -> {
        String r =
            db.sql("select status from processing_runs where id=?")
                .param(s.get("processing_run_id"))
                .query(String.class)
                .single();
        if (r.equals("COMPLETED")) {
          UUID v =
              db.sql(
                      "select v.id from processing_steps ps join asset_variants v on"
                          + " v.artifact_id=ps.output_artifact_id where ps.run_id=? and"
                          + " ps.node_key=?")
                  .params(s.get("processing_run_id"), p.get("processingProfile"))
                  .query(UUID.class)
                  .single();
          db.sql("update stock_productions set stock_variant_id=? where id=? and revision=?")
              .params(v, id, s.get("revision"))
              .update();
          move(s, "TECHNICAL_VALIDATION", "");
        } else if (Set.of("FAILED", "PARTIALLY_COMPLETED", "CANCELLED").contains(r))
          move(s, "PROCESSING_FAILED", r);
      }
      case "TECHNICAL_VALIDATION" -> {
        var v = variant((UUID) s.get("stock_variant_id"));
        var result =
            technical.validate(
                storage.read(v.get("storage_key").toString()),
                v.get("sha256").toString(),
                p,
                encoderEvidence((UUID) v.get("id")));
        db.sql(
                "insert into"
                    + " stock_validation_results(production_id,variant_id,profile_version_id,result)"
                    + " values(?,?,?,?::jsonb)")
            .params(id, v.get("id"), s.get("profile_version_id"), write(result))
            .update();
        move(
            s,
            result.valid() ? "METADATA_GENERATION" : "VALIDATION_FAILED",
            result.valid() ? "" : "TECHNICAL_VALIDATION_FAILED");
        if (!result.valid())
          metrics.counter("media_factory_stock_validation_failed_total").increment();
      }
      case "METADATA_GENERATION" -> metadata.generate(s);
      default -> {}
    }
  }
}
