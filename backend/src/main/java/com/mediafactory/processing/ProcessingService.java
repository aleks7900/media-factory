package com.mediafactory.processing;

import static com.mediafactory.processing.ProcessingJson.integer;
import static com.mediafactory.processing.ProcessingJson.map;
import static com.mediafactory.processing.ProcessingJson.write;

import com.mediafactory.storage.MediaStorage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ProcessingService {

  final JdbcClient db;
  final TransactionTemplate tx;
  final ProcessingPlanner planner;
  final MediaStorage storage;
  final ProcessingProvider provider;
  final com.mediafactory.quality.QaConfiguration qa;

  public ProcessingService(
      JdbcClient db,
      TransactionTemplate tx,
      ProcessingPlanner planner,
      MediaStorage storage,
      ProcessingProvider provider, com.mediafactory.quality.QaConfiguration qa) {
    this.db = db;
    this.tx = tx;
    this.planner = planner;
    this.storage = storage;
    this.provider = provider;
    this.qa = qa;
  }

  public List<Map<String, Object>> profiles() {
    return db
        .sql(
            "select p.key,p.name,v.* from processing_profiles p join processing_profile_versions v"
                + " on v.profile_id=p.id order by p.key,v.version desc")
        .query()
        .listOfRows()
        .stream()
        .map(this::profileJson)
        .toList();
  }

  Map<String, Object> profileJson(Map<String, Object> row) {
    var result = new LinkedHashMap<>(row);
    result.put("definition", map(row.get("definition")));
    return result;
  }

  public Map<String, Object> profile(String key) {
    return profileJson(
        db
            .sql(
                "select p.key,p.name,v.* from processing_profiles p join"
                    + " processing_profile_versions v on v.profile_id=p.id where p.key=? and"
                    + " v.status='PUBLISHED' order by v.version desc limit 1")
            .param(key)
            .query()
            .listOfRows()
            .stream()
            .findFirst()
            .orElseThrow(
                () -> new IllegalArgumentException("Published profile not found: " + key)));
  }

  public Map<String, Object> draft(String key, Map<String, Object> definition) {
    if (!key.matches("[A-Z][A-Z0-9_]{1,79}")) {
      throw new IllegalArgumentException("Invalid profile key");
    }
    planner.validate(definition);
    return tx.execute(
        s -> {
          db.sql(
                  "insert into processing_profiles(key,name) values(?,?) on conflict(key) do"
                      + " nothing")
              .params(key, key)
              .update();
          UUID id =
              db.sql("select id from processing_profiles where key=? for update")
                  .param(key)
                  .query(UUID.class)
                  .single();
          return profileJson(
              db.sql(
                      "insert into"
                          + " processing_profile_versions(profile_id,version,status,definition)"
                          + " select ?,coalesce(max(version),0)+1,'DRAFT',?::jsonb from"
                          + " processing_profile_versions where profile_id=? returning *")
                  .params(id, write(definition), id)
                  .query()
                  .singleRow());
        });
  }

  public Object transition(UUID id, String status) {
    if (!Set.of("PUBLISHED", "DEPRECATED").contains(status)) {
      throw new IllegalArgumentException("Invalid profile status");
    }
    return db
        .sql(
            "update processing_profile_versions set status=?,published_at=case when ?='PUBLISHED'"
                + " then now() else published_at end where id=? and status=case when ?='PUBLISHED'"
                + " then 'DRAFT' else 'PUBLISHED' end returning *")
        .params(status, status, id, status)
        .query()
        .listOfRows()
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Invalid profile transition"));
  }

  public Map<String, Object> asset(UUID id) {
    return db
        .sql(
            "select a.*,q.final_decision from assets a left join quality_reviews q on"
                + " q.id=a.current_review_id where a.id=?")
        .param(id)
        .query()
        .listOfRows()
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Asset not found"));
  }

  public Map<String, Object> request(
      UUID id, List<String> keys, Map<String, Object> manual, String key, int priority) {
    return requestInternal(id, keys, manual, key, priority, null);
  }

  public Map<String, Object> requestFrozen(UUID id, List<Map<String, Object>> versions,
      Map<String, Object> manual, String key, int priority) {
    var resolved = versions.stream().map(v -> profileJson(db.sql(
            "select p.key,p.name,v.* from processing_profile_versions v join processing_profiles p on p.id=v.profile_id where v.id=? and v.status in ('PUBLISHED','DEPRECATED')")
        .param(UUID.fromString(v.get("id").toString())).query().singleRow())).toList();
    return requestInternal(id, resolved.stream().map(v -> v.get("key").toString()).toList(),
        manual, key, priority, resolved);
  }

  private Map<String, Object> requestInternal(UUID id, List<String> keys,
      Map<String, Object> manual,
      String key, int priority, List<Map<String, Object>> frozenVersions) {
    if (keys.isEmpty() || keys.size() > 16 || new HashSet<>(keys).size() != keys.size()) {
      throw new IllegalArgumentException("Select 1–16 distinct profiles");
    }
    if (key != null && (key.isBlank() || key.length() > 200)) {
      throw new IllegalArgumentException("Invalid idempotency key");
    }
    return tx.execute(
        s -> {
          var a = asset(id);
          if (!"APPROVED".equals(a.get("final_decision"))) {
            throw new IllegalArgumentException("Only QA-approved master assets can be processed");
          }
          if (!a.get("media_type").toString().startsWith("image/")) {
            throw new IllegalArgumentException("Only images are supported");
          }
          if (((Number) a.get("width")).longValue() * ((Number) a.get("height")).longValue()
              > 20000000
              || ((Number) a.get("size_bytes")).longValue() > 67108864) {
            throw new IllegalArgumentException("Source exceeds input resource limits");
          }
          var profiles = (frozenVersions == null ? keys.stream().sorted().map(this::profile)
              : frozenVersions.stream()).map(p -> {
            var definition = new LinkedHashMap<>(map(p.get("definition")));
            if (Boolean.TRUE.equals(definition.get("visualQa"))) {
              String vision = definition.getOrDefault("visionProvider", "mock").toString();
              definition.put("visionModel", qa.model(vision));
              definition.put("visionScenario", qa.scenario());
              definition.put("qaPolicySnapshot",
                  map(write(qa.policy(definition.getOrDefault("qaPolicy", "default").toString()))));
            }
            var frozen = new LinkedHashMap<>(p);
            frozen.put("definition", definition);
            return (Map<String, Object>) frozen;
          }).toList();
          var plan = new LinkedHashMap<>(
              planner.plan(
                  id,
                  a.get("sha256").toString(),
                  ((Number) a.get("width")).intValue(),
                  ((Number) a.get("height")).intValue(),
                  profiles,
                  manual));
          plan.put("focalRegions", focalRegions(id));
          String hash = ProcessingPlanner.hash(plan),
              actualKey = key == null ? "processing:" + hash : key;
          UUID run = UUID.randomUUID();
          db.sql(
                  "insert into"
                      + " processing_runs(id,source_asset_id,idempotency_key,request_hash,plan,priority)"
                      + " values(?,?,?,?,?::jsonb,?) on conflict(idempotency_key) do nothing")
              .params(run, id, actualKey, hash, write(plan), priority)
              .update();
          var row =
              db.sql("select * from processing_runs where idempotency_key=?")
                  .param(actualKey)
                  .query()
                  .singleRow();
          if (!hash.equals(row.get("request_hash").toString())) {
            throw new IllegalArgumentException(
                "Idempotency key was already used for a different request");
          }
          return Map.of("processingRunId", row.get("id"), "status", row.get("status"));
        });
  }

  public Object batch(List<UUID> ids, List<String> profiles) {
    if (ids.isEmpty() || ids.size() > 100) {
      throw new IllegalArgumentException("Batch supports 1–100 assets");
    }
    return ids.stream()
        .distinct()
        .map(
            id -> {
              try {
                return request(id, profiles, Map.of(), null, 0);
              } catch (IllegalArgumentException e) {
                return Map.<String, Object>of("assetId", id, "error", e.getMessage());
              }
            })
        .toList();
  }

  public List<Map<String, Object>> runs() {
    return db
        .sql(
            "select r.*,a.width,a.height,(select count(*) from processing_steps s where"
                + " s.run_id=r.id and s.operation='DERIVE' and s.status in ('COMPLETED','SKIPPED'))"
                + " completed_variants from processing_runs r join assets a on"
                + " a.id=r.source_asset_id order by r.created_at desc limit 200")
        .query()
        .listOfRows()
        .stream()
        .map(this::runJson)
        .toList();
  }

  Map<String, Object> runJson(Map<String, Object> row) {
    var result = new LinkedHashMap<>(row);
    result.put("plan", map(row.get("plan")));
    return result;
  }

  public Map<String, Object> run(UUID id) {
    var result =
        runJson(
            db
                .sql("select * from processing_runs where id=?")
                .param(id)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Processing run not found")));
    result.put(
        "steps",
        db.sql("select * from processing_steps where run_id=? order by started_at,node_key")
            .param(id)
            .query()
            .listOfRows());
    result.put(
        "variants",
        db.sql(
                "select v.* from asset_variants v join processing_steps s on"
                    + " s.output_artifact_id=v.artifact_id where s.run_id=? and s.operation='DERIVE'")
            .param(id)
            .query()
            .listOfRows());
    result.put(
        "validation",
        db.sql("select * from processing_validation_results where run_id=?")
            .param(id)
            .query()
            .listOfRows());
    result.put(
        "manifests",
        db
            .sql("select * from processing_manifests where run_id=? order by attempt")
            .param(id)
            .query()
            .listOfRows()
            .stream()
            .map(r -> map(r.get("manifest")))
            .toList());
    return result;
  }

  public Object retry(UUID id) {
    return db
        .sql(
            "update processing_runs set"
                + " status='PENDING',cancel_requested=false,available_at=now(),failure_code=null,failure_reason=null,max_attempts=greatest(max_attempts,attempt+1)"
                + " where id=? and status in ('FAILED','PARTIALLY_COMPLETED','CANCELLED') returning"
                + " id,status")
        .param(id)
        .query()
        .listOfRows()
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Run is not retryable"));
  }

  public Object cancel(UUID id) {
    int changed =
        db.sql(
                "update processing_runs set cancel_requested=true,status=case when status='PENDING'"
                    + " then 'CANCELLED' else status end where id=? and status in"
                    + " ('PENDING','RUNNING')")
            .param(id)
            .update();
    if (changed > 0) {
      try {
        provider.cancel(id);
      } catch (ProcessingFailure ignored) {
      }
    }
    return Map.of("cancelRequested", changed > 0);
  }

  public Object variants(UUID id) {
    return db.sql(
            "select v.*,p.key profile, pv.version profile_version,"
                + " (v.width::numeric*v.height/1000000) megapixels from asset_variants v left join"
                + " processing_profile_versions pv on pv.id=v.profile_version_id left join"
                + " processing_profiles p on p.id=pv.profile_id where v.asset_id=? order by"
                + " v.created_at desc")
        .param(id)
        .query()
        .listOfRows();
  }

  public Map<String, Object> variant(UUID id) {
    return db.sql("select * from asset_variants where id=?").param(id).query().listOfRows().stream()
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Variant not found"));
  }

  public Object preview(UUID asset, String profile) {
    var a = asset(asset);
    return provider.cropPreview(
        storage.read(a.get("storage_key").toString()),
        map(profile(profile).get("definition")),
        focalRegions(asset));
  }

  public List<Map<String, Object>> focalRegions(UUID asset) {
    var results = new ArrayList<Map<String, Object>>();
    for (var row :
        db.sql(
                "select f.metadata from quality_findings f join assets a on"
                    + " a.current_review_id=f.review_id where a.id=?")
            .param(asset)
            .query()
            .listOfRows()) {
      var metadata = map(row.get("metadata"));
      Object focal = metadata.get("focalRegion");
      if (focal != null) {
        try {
          var region = map(focal);
          if (region
              .keySet()
              .containsAll(Set.of("x", "y", "width", "height", "type", "confidence"))) {
            results.add(region);
          }
        } catch (Exception ignored) {
        }
      }
    }
    return results;
  }

  public Object worker() {
    try {
      return provider.capabilities();
    } catch (ProcessingFailure e) {
      return Map.of("status", "OFFLINE", "gpuAvailable", false, "activeJobs", 0);
    }
  }

  public Object usage() {
    return db.sql("select * from processing_compute_usage order by created_at desc limit 500")
        .query()
        .listOfRows();
  }

  public Object targets() {
    return db.sql("select *,width::numeric/height aspect_ratio from wallpaper_targets order by key")
        .query()
        .listOfRows();
  }

  public Object target(String key, Map<String, Object> definition) {
    if (!key.matches("[A-Z][A-Z0-9_]{1,79}")) {
      throw new IllegalArgumentException("Invalid target key");
    }
    planner.validate(definition);
    db.sql(
            "insert into wallpaper_targets(key,width,height,crop_mode,quality,format)"
                + " values(?,?,?,?,?,?) on conflict(key) do update set"
                + " width=excluded.width,height=excluded.height,crop_mode=excluded.crop_mode,quality=excluded.quality,format=excluded.format")
        .params(
            key,
            integer(definition, "width", 0),
            integer(definition, "height", 0),
            definition.get("mode"),
            integer(definition, "quality", 95),
            definition.get("format"))
        .update();
    return definition;
  }

  public Object targetDraft(String key, String target) {
    var row =
        db.sql("select * from wallpaper_targets where key=?").param(target).query().singleRow();
    var definition = new LinkedHashMap<>(map(profile("WALLPAPER_ANDROID").get("definition")));
    definition.put("width", row.get("width"));
    definition.put("height", row.get("height"));
    definition.put("mode", row.get("crop_mode"));
    definition.put("quality", row.get("quality"));
    definition.put("format", row.get("format"));
    definition.put("wallpaperTarget", target);
    return draft(key, definition);
  }
}
