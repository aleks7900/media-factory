package com.mediafactory.wallpaper;

import static com.mediafactory.processing.ProcessingJson.*;
import static com.mediafactory.wallpaper.WallpaperProductionService.*;

import com.mediafactory.processing.ProcessingPlanner;
import com.mediafactory.similarity.PerceptualHash;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class WallpaperPublicationService {

  final WallpaperProductionService service;
  final List<WallpaperPublicationTarget> targets;
  private final java.util.concurrent.ConcurrentMap<UUID, UUID> active =
      new java.util.concurrent.ConcurrentHashMap<>();

  public WallpaperPublicationService(
      WallpaperProductionService service, List<WallpaperPublicationTarget> targets) {
    this.service = service;
    this.targets = targets;
  }

  public Object targets() {
    return targets.stream().map(t -> Map.of("key", t.key(), "available", t.available())).toList();
  }

  public Object collectionAction(UUID collection, String action, String target) {
    var ids =
        service
            .db
            .sql(
                "select w.id from wallpaper_productions w join concepts c on c.id=w.concept_id"
                    + " where c.collection_id=? and w.status in"
                    + " ('PUBLICATION_REVIEW','APPROVED_FOR_PUBLICATION','PUBLISHING','PUBLISHED','PUBLICATION_FAILED','UNPUBLISHED')"
                    + " order by w.created_at limit 1000")
            .param(collection)
            .query(UUID.class)
            .list();
    if (ids.isEmpty()) {
      throw conflict("Collection has no ready wallpapers");
    }
    var results = new ArrayList<Map<String, Object>>();
    for (UUID id : ids) {
      try {
        Object result =
            switch (action) {
              case "prepare-publication" -> prepare(id);
              case "publish" -> publish(id, target);
              case "unpublish" -> unpublish(id, target);
              default ->
                  throw new IllegalArgumentException("Unknown collection publication action");
            };
        results.add(Map.of("wallpaperId", id, "result", result));
      } catch (RuntimeException e) {
        results.add(
            Map.of(
                "wallpaperId",
                id,
                "error",
                e instanceof org.springframework.web.server.ResponseStatusException r
                    ? Objects.toString(r.getReason(), "ACTION_FAILED")
                    : "ACTION_FAILED"));
      }
    }
    return results;
  }

  WallpaperPublicationTarget target(String key) {
    return targets.stream()
        .filter(t -> t.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown publication target"));
  }

  public Map<String, Object> eligibility(UUID id, boolean verifyFiles) {
    var w = service.one(id);
    var reasons = new ArrayList<String>();
    UUID asset = (UUID) w.get("master_asset_id");
    if (asset == null) {
      reasons.add("MASTER_MISSING");
    } else {
      if (!"APPROVED".equals(service.processing.asset(asset).get("final_decision"))) {
        reasons.add("QA_NOT_APPROVED");
      }
      UUID model = service.similarity.activeModel().id();
      if (!service.similarity.state(asset, model).equals("READY")) {
        reasons.add("SIMILARITY_INCOMPLETE");
      }
      if (service
          .db
          .sql("select similarity_publication_block_reason(?)")
          .param(asset)
          .query(String.class)
          .optional()
          .isPresent()) {
        reasons.add("SIMILARITY_BLOCKED");
      }
    }
    var p = map(w.get("profile_snapshot"));
    if (Boolean.TRUE.equals(p.get("amoled"))
        && (w.get("amoled_result") == null
        || !"AMOLED_SUITABLE".equals(map(w.get("amoled_result")).get("classification")))) {
      reasons.add("AMOLED_NOT_SUITABLE");
    }
    var variants = variants(w);
    Set<String> found = new HashSet<>();
    for (var v : variants) {
      found.add(v.get("kind").toString());
      if (!"VALID".equals(v.get("validation_status"))) {
        reasons.add("VARIANT_NOT_VALID:" + v.get("kind"));
      }
      if (verifyFiles) {
        try {
          verified(v);
        } catch (RuntimeException e) {
          reasons.add("VARIANT_UNREADABLE_OR_CHECKSUM_MISMATCH:" + v.get("kind"));
        }
      }
    }
    for (Object required : (List<?>) p.get("processingProfiles")) {
      if (!found.contains(required.toString())) {
        reasons.add("MISSING_VARIANT:" + required);
      }
    }
    if (!found.contains("ANDROID_GENERIC_PORTRAIT")) {
      reasons.add("FALLBACK_REQUIRED");
    }
    if (!READY.contains(w.get("status")) && !"PUBLICATION_FAILED".equals(w.get("status"))) {
      reasons.add("PRODUCTION_NOT_READY");
    }
    validateMetadata(map(w.get("metadata")));
    return Map.of(
        "eligible",
        reasons.isEmpty(),
        "reasons",
        reasons,
        "variants",
        variants,
        "humanApprovalRequired",
        true);
  }

  List<Map<String, Object>> variants(Map<String, Object> w) {
    if (w.get("processing_run_id") == null) {
      return List.of();
    }
    return service
        .db
        .sql(
            "select v.*,p.metadata as processing_metadata from asset_variants v join"
                + " processing_steps s on s.output_artifact_id=v.artifact_id join"
                + " processing_artifacts p on p.id=v.artifact_id where s.run_id=? and"
                + " s.operation='DERIVE' order by v.kind")
        .param(w.get("processing_run_id"))
        .query()
        .listOfRows();
  }

  byte[] verified(Map<String, Object> v) {
    byte[] bytes = service.storage.read(v.get("storage_key").toString());
    if (!PerceptualHash.sha(bytes).equals(v.get("sha256"))) {
      throw conflict("Binary checksum mismatch");
    }
    return bytes;
  }

  public Map<String, Object> prepare(UUID id) {
    var w = service.one(id);
    var eligibility = eligibility(id, true);
    if (!Boolean.TRUE.equals(eligibility.get("eligible"))) {
      throw conflict("Publication ineligible: " + eligibility.get("reasons"));
    }
    var col = service.factory.one("collections", (UUID) w.get("collection_id"));
    var manifest = new LinkedHashMap<String, Object>();
    manifest.put("contractVersion", "media-factory-wallpaper-package/1");
    manifest.put("wallpaperId", id.toString());
    manifest.put("metadata", map(w.get("metadata")));
    manifest.put("amoled", Boolean.TRUE.equals(map(w.get("profile_snapshot")).get("amoled")));
    manifest.put("amoledAnalysis", w.get("amoled_result"));
    manifest.put(
        "collection",
        Map.of(
            "id",
            col.get("id"),
            "title",
            col.get("name"),
            "slug",
            col.get("slug"),
            "description",
            col.get("description"),
            "theme",
            col.get("theme"),
            "amoled",
            col.get("amoled")));
    manifest.put("originalAssetId", w.get("master_asset_id"));
    manifest.put("masterVariantId", w.get("master_variant_id"));
    manifest.put("generationId", w.get("generation_id"));
    manifest.put("processingRunId", w.get("processing_run_id"));
    manifest.put("profileSnapshot", w.get("profile_snapshot"));
    manifest.put(
        "generationProvenance",
        service.factory.details((UUID) w.get("generation_id")).get("promptSnapshots"));
    var descriptors = new ArrayList<Map<String, Object>>();
    for (var v : variants(w)) {
      var d = new LinkedHashMap<String, Object>();
      d.put("id", v.get("id"));
      d.put("type", v.get("kind"));
      d.put("width", v.get("width"));
      d.put("height", v.get("height"));
      d.put(
          "aspectRatio",
          ((Number) v.get("width")).doubleValue() / ((Number) v.get("height")).doubleValue());
      d.put("format", v.get("format"));
      d.put("fileSize", v.get("size_bytes"));
      d.put("checksum", v.get("sha256"));
      d.put("storageReference", v.get("storage_key"));
      d.put("sourceAssetId", v.get("source_asset_id"));
      d.put("parentArtifactId", v.get("parent_artifact_id"));
      d.put("artifactId", v.get("artifact_id"));
      d.put("profileVersionId", v.get("profile_version_id"));
      d.put(
          "qualityTier",
          service
              .db
              .sql("select quality_tier from wallpaper_device_profiles where key=?")
              .param(v.get("kind"))
              .query(String.class)
              .optional()
              .orElse("MASTER"));
      d.put("processing", map(v.get("processing_metadata")));
      descriptors.add(d);
    }
    manifest.put("variants", descriptors);
    return service.tx.execute(
        s -> {
          service
              .db
              .sql("select id from wallpaper_productions where id=? for update")
              .param(id)
              .query()
              .singleRow();
          if (!service.one(id).get("revision").equals(w.get("revision"))) {
            throw conflict("Wallpaper changed during package validation");
          }
          var previous =
              service
                  .db
                  .sql(
                      "select * from wallpaper_publication_packages where production_id=? order by"
                          + " version desc limit 1")
                  .param(id)
                  .query()
                  .listOfRows()
                  .stream()
                  .findFirst();
          if (previous.isPresent()) {
            var before = new LinkedHashMap<>(map(previous.get().get("manifest")));
            before.remove("publicationVersion");
            if ((!"UNPUBLISHED".equals(w.get("status"))
                || previous.get().get("approved_at") == null)
                && ProcessingPlanner.hash(before).equals(ProcessingPlanner.hash(manifest))) {
              return clean(previous.get());
            }
          }
          int version = previous.map(v -> integer(v, "version", 0) + 1).orElse(1);
          manifest.put("publicationVersion", version);
          UUID packageId = UUID.randomUUID();
          service
              .db
              .sql(
                  "insert into"
                      + " wallpaper_publication_packages(id,production_id,version,manifest,manifest_sha256)"
                      + " values(?,?,?,?::jsonb,?)")
              .params(packageId, id, version, canonical(manifest), ProcessingPlanner.hash(manifest))
              .update();
          service.event(
              id,
              w.get("status").toString(),
              "PACKAGE_PREPARED",
              "local-workspace",
              "Version " + version);
          return packageById(packageId);
        });
  }

  Map<String, Object> packageById(UUID id) {
    return clean(
        service
            .db
            .sql("select * from wallpaper_publication_packages where id=?")
            .param(id)
            .query()
            .singleRow());
  }

  Map<String, Object> latest(UUID id) {
    return clean(
        service
            .db
            .sql(
                "select * from wallpaper_publication_packages where production_id=? order by"
                    + " version desc limit 1")
            .param(id)
            .query()
            .listOfRows()
            .stream()
            .findFirst()
            .orElseThrow(() -> conflict("Prepare a publication package first")));
  }

  public Object dryRun(UUID id, String target) {
    var eligibility = eligibility(id, true);
    var adapter = target(target);
    return Map.of(
        "eligibility",
        eligibility,
        "target",
        target,
        "targetAvailable",
        adapter.available(),
        "remoteMutation",
        false,
        "plannedOperations",
        List.of(
            "VERIFY_IMMUTABLE_BINARIES",
            "TRANSFER_OR_REFERENCE_ASSETS",
            "UPSERT_COLLECTION",
            "UPSERT_WALLPAPER_VERSION",
            "ACTIVATE"),
        "warnings",
        adapter.available()
            ? List.of("Mock target: no Android application receives this publication")
            : List.of("ANDROID_CONTRACT_NOT_CONFIGURED"));
  }

  public Object approve(UUID id, UUID packageId, int revision) {
    if (!Boolean.TRUE.equals(eligibility(id, true).get("eligible"))) {
      throw conflict("Wallpaper is not eligible");
    }
    return service.tx.execute(
        s -> {
          service
              .db
              .sql("select id from wallpaper_productions where id=? for update")
              .param(id)
              .query()
              .singleRow();
          var w = service.one(id);
          var p = latest(id);
          if (!p.get("id").equals(packageId)
              || !w.get("revision").equals(revision)
              || !Set.of("PUBLICATION_REVIEW", "UNPUBLISHED").contains(w.get("status"))) {
            throw conflict("Review the latest package at the current revision");
          }
          if (!ProcessingPlanner.hash(map(p.get("manifest")).get("metadata"))
              .equals(ProcessingPlanner.hash(w.get("metadata")))) {
            throw conflict("Prepare updated metadata before approval");
          }
          if (!Objects.toString(map(p.get("manifest")).get("processingRunId"))
              .equals(Objects.toString(w.get("processing_run_id")))) {
            throw conflict("Prepare a new package after reprocessing");
          }
          service
              .db
              .sql(
                  "update wallpaper_publication_packages set"
                      + " approved_by='local-workspace',approved_at=now() where id=? and"
                      + " approved_at is null")
              .param(packageId)
              .update();
          service
              .db
              .sql(
                  "update wallpaper_productions set"
                      + " status='APPROVED_FOR_PUBLICATION',approved_by='local-workspace',approved_at=now(),revision=revision+1,updated_at=now()"
                      + " where id=?")
              .param(id)
              .update();
          service.event(
              id,
              w.get("status").toString(),
              "APPROVED_FOR_PUBLICATION",
              "local-workspace",
              "Package " + packageId);
          return service.one(id);
        });
  }

  public Object publish(UUID id, String target) {
    if (!target(target).available()) {
      throw conflict("ANDROID_CONTRACT_NOT_CONFIGURED");
    }
    if (!Boolean.TRUE.equals(eligibility(id, true).get("eligible"))) {
      throw conflict("Publication eligibility changed");
    }
    return service.tx.execute(
        s -> {
          service
              .db
              .sql("select id from wallpaper_productions where id=? for update")
              .param(id)
              .query()
              .singleRow();
          var w = service.one(id);
          var p = latest(id);
          if (p.get("approved_at") == null
              || !Set.of(
                  "APPROVED_FOR_PUBLICATION", "PUBLISHING", "PUBLISHED", "PUBLICATION_FAILED")
              .contains(w.get("status"))) {
            throw conflict("Human approval required");
          }
          UUID delivery = UUID.randomUUID();
          String key = "wallpaper:" + id + ":" + p.get("version") + ":" + target;
          service
              .db
              .sql(
                  "insert into wallpaper_deliveries(id,package_id,target,idempotency_key)"
                      + " values(?,?,?,?) on conflict(idempotency_key) do nothing")
              .params(delivery, p.get("id"), target, key)
              .update();
          var saved =
              service
                  .db
                  .sql("select * from wallpaper_deliveries where idempotency_key=?")
                  .param(key)
                  .query()
                  .singleRow();
          if (!"PUBLISHED".equals(saved.get("status"))) {
            service
                .db
                .sql(
                    "update wallpaper_productions set"
                        + " status='PUBLISHING',revision=revision+1,updated_at=now() where id=?")
                .param(id)
                .update();
          }
          return clean(saved);
        });
  }

  public Object retry(UUID delivery) {
    if (service
        .db
        .sql(
            "update wallpaper_deliveries set"
                + " status='READY',max_attempts=attempt+3,available_at=now(),failure_code=null"
                + " where id=? and status='FAILED' and failure_code in"
                + " ('TIMEOUT','REMOTE_UNAVAILABLE','STORAGE_UNAVAILABLE')")
        .param(delivery)
        .update()
        != 1) {
      throw conflict("Failure requires correction rather than retry");
    }
    return Map.of("id", delivery, "status", "READY");
  }

  public Object unpublish(UUID id, String target) {
    return service.tx.execute(
        s -> {
          service
              .db
              .sql("select id from wallpaper_productions where id=? for update")
              .param(id)
              .query()
              .singleRow();
          if (!Set.of("PUBLISHED", "UNPUBLISHED").contains(service.one(id).get("status"))) {
            throw conflict("Wait for publication to finish before unpublishing");
          }
          var prior =
              service
                  .db
                  .sql(
                      "select d.* from wallpaper_deliveries d join wallpaper_publication_packages p"
                          + " on p.id=d.package_id where p.production_id=? and d.target=? and"
                          + " d.status='PUBLISHED' order by p.version desc limit 1")
                  .params(id, target)
                  .query()
                  .listOfRows()
                  .stream()
                  .findFirst()
                  .orElseThrow(() -> conflict("No published delivery to unpublish"));
          UUID delivery = UUID.randomUUID();
          service
              .db
              .sql(
                  "insert into"
                      + " wallpaper_deliveries(id,package_id,target,operation,idempotency_key,external_reference)"
                      + " values(?,?,?,'UNPUBLISH',?,?::jsonb) on conflict(idempotency_key) do"
                      + " nothing")
              .params(
                  delivery,
                  prior.get("package_id"),
                  target,
                  "unpublish:" + prior.get("id"),
                  prior.get("external_reference").toString())
              .update();
          return Map.of("status", "QUEUED");
        });
  }

  public void deliver(UUID id) {
    UUID token = UUID.randomUUID();
    var claimed =
        service.tx.execute(
            s -> {
              UUID production =
                  service
                      .db
                      .sql(
                          "select w.id from wallpaper_productions w join"
                              + " wallpaper_publication_packages p on p.production_id=w.id join"
                              + " wallpaper_deliveries d on d.package_id=p.id where d.id=? for"
                              + " update of w")
                      .param(id)
                      .query(UUID.class)
                      .single();
              if (service
                  .db
                  .sql(
                      "select exists(select 1 from wallpaper_deliveries d join"
                          + " wallpaper_publication_packages p on p.id=d.package_id where"
                          + " p.production_id=? and d.id<>? and d.status='RUNNING' and"
                          + " d.lease_until>now())")
                  .params(production, id)
                  .query(Boolean.class)
                  .single()) {
                return Optional.<Map<String, Object>>empty();
              }
              return service
                  .db
                  .sql(
                      "update wallpaper_deliveries set"
                          + " status='RUNNING',attempt=attempt+1,lease_token=?,lease_until=now()+interval"
                          + " '2 minutes' where id=? and attempt<max_attempts and ((status='READY'"
                          + " and available_at<=now()) or (status='RUNNING' and lease_until<now()))"
                          + " returning *")
                  .params(token, id)
                  .query()
                  .listOfRows()
                  .stream()
                  .findFirst();
            });
    if (claimed.isEmpty()) {
      return;
    }
    active.put(id, token);
    var d = claimed.get();
    var p = packageById((UUID) d.get("package_id"));
    UUID production = (UUID) p.get("production_id");
    try {
      var adapter = target(d.get("target").toString());
      boolean unpublish = "UNPUBLISH".equals(d.get("operation"));
      WallpaperPublicationTarget.Result result;
      if (unpublish) {
        var ref = map(d.get("external_reference"));
        result =
            adapter.unpublish(
                new WallpaperPublicationTarget.Reference(
                    d.get("idempotency_key").toString(),
                    ref.get("externalId").toString(),
                    integer(ref, "version", 1)));
      } else {
        if (p.get("approved_at") == null
            || !Boolean.TRUE.equals(eligibility(production, true).get("eligible"))) {
          throw new WallpaperPublicationTarget.Failure("ELIGIBILITY_CHANGED", false);
        }
        var request =
            new WallpaperPublicationTarget.Request(
                d.get("idempotency_key").toString(),
                p.get("manifest_sha256").toString(),
                map(p.get("manifest")));
        result = integer(p, "version", 1) == 1 ? adapter.publish(request) : adapter.update(request);
      }
      service.tx.executeWithoutResult(
          s -> {
            if (service
                .db
                .sql(
                    "update wallpaper_deliveries set"
                        + " status=?,external_reference=?::jsonb,lease_token=null,lease_until=null,updated_at=now()"
                        + " where id=? and lease_token=?")
                .params(result.status(), write(result), id, token)
                .update()
                != 1) {
              throw conflict("Publication lease expired");
            }
            if (!unpublish) {
              UUID publication = UUID.randomUUID();
              service
                  .db
                  .sql("insert into publications(id,asset_id,channel,external_id) values(?,?,?,?)")
                  .params(
                      publication,
                      service.one(production).get("master_asset_id"),
                      "WALLPAPER_" + adapter.key(),
                      result.externalId())
                  .update();
              service
                  .db
                  .sql("update wallpaper_deliveries set publication_id=? where id=?")
                  .params(publication, id)
                  .update();
            }
            service
                .db
                .sql(
                    "update wallpaper_productions set status=?,published_at=case when ?='PUBLISHED'"
                        + " then now() else published_at end,revision=revision+1,updated_at=now()"
                        + " where id=?")
                .params(result.status(), result.status(), production)
                .update();
            service.event(
                production, "PUBLISHING", result.status(), "publication-worker", adapter.key());
            service
                .db
                .sql(
                    "update collections col set wallpaper_status=case when exists (select 1 from"
                        + " wallpaper_productions w join concepts c on c.id=w.concept_id where"
                        + " c.collection_id=col.id and w.status='PUBLISHED') then 'PUBLISHED' else"
                        + " 'READY' end where col.id=(select c.collection_id from concepts c join"
                        + " wallpaper_productions w on w.concept_id=c.id where w.id=?) and"
                        + " col.wallpaper_status<>'ARCHIVED'")
                .param(production)
                .update();
          });
      service
          .metrics
          .counter(
              "media_factory_wallpaper_published_total",
              "target",
              adapter.key(),
              "status",
              result.status())
          .increment();
    } catch (Exception e) {
      String code =
          e instanceof WallpaperPublicationTarget.Failure f ? f.code() : "STORAGE_UNAVAILABLE";
      boolean retry = e instanceof WallpaperPublicationTarget.Failure f ? f.retryable() : true;
      String status =
          retry && integer(d, "attempt", 1) < integer(d, "max_attempts", 3) ? "READY" : "FAILED";
      service
          .db
          .sql(
              "update wallpaper_deliveries set status=?,failure_code=?,available_at=now()+interval"
                  + " '10 seconds',lease_token=null,lease_until=null,updated_at=now() where id=?"
                  + " and lease_token=?")
          .params(status, code, id, token)
          .update();
      if (status.equals("FAILED")) {
        service
            .db
            .sql(
                "update wallpaper_productions set"
                    + " status='PUBLICATION_FAILED',failure_code=?,revision=revision+1 where id=?"
                    + " and not exists(select 1 from wallpaper_deliveries where package_id=? and"
                    + " status='RUNNING' and lease_token<>?)")
            .params(code, production, p.get("id"), token)
            .update();
      }
      service
          .metrics
          .counter("media_factory_wallpaper_publication_failed_total", "code", code)
          .increment();
    } finally {
      active.remove(id, token);
    }
  }

  public void heartbeat() {
    active.forEach(
        (id, token) ->
            service
                .db
                .sql(
                    "update wallpaper_deliveries set lease_until=now()+interval '2 minutes' where"
                        + " id=? and lease_token=? and status='RUNNING'")
                .params(id, token)
                .update());
  }
}
