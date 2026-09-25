package com.mediafactory.wallpaper;

import static com.mediafactory.processing.ProcessingJson.*;
import static com.mediafactory.wallpaper.WallpaperProductionService.*;

import com.mediafactory.similarity.CollectionClusteringService;
import com.mediafactory.similarity.GenerationBatchService;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class WallpaperCollectionService {

  final WallpaperProductionService service;
  final CollectionClusteringService clustering;

  public WallpaperCollectionService(
      WallpaperProductionService service, CollectionClusteringService clustering) {
    this.service = service;
    this.clustering = clustering;
  }

  public Object create(
      UUID project,
      String title,
      String slug,
      String description,
      String theme,
      String style,
      boolean amoled) {
    validateMetadata(Map.of("title", title, "slug", slug));
    return service.tx.execute(
        s -> {
          var c = service.factory.collection(project, title);
          UUID id = (UUID) c.get("id");
          service
              .db
              .sql(
                  "update collections set"
                      + " wallpaper=true,slug=?,description=?,theme=?,style=?,amoled=?,qa_policy='wallpaper-standard',similarity_profile='WALLPAPER'"
                      + " where id=?")
              .params(slug, description, theme, style, amoled, id)
              .update();
          return service.factory.one("collections", id);
        });
  }

  public Object list() {
    return service
        .db
        .sql(
            "select c.*,p.target_approved,p.max_attempts,p.status as production_status from"
                + " collections c left join wallpaper_collection_plans p on p.collection_id=c.id"
                + " where c.wallpaper order by c.created_at desc")
        .query()
        .listOfRows();
  }

  public Map<String, Object> status(UUID id) {
    var c = service.factory.one("collections", id);
    var result = new LinkedHashMap<String, Object>();
    result.put("collection", c);
    result.put(
        "plan",
        service
            .db
            .sql("select * from wallpaper_collection_plans where collection_id=?")
            .param(id)
            .query()
            .listOfRows()
            .stream()
            .findFirst()
            .orElse(Map.of()));
    var counts =
        service
            .db
            .sql(
                "select count(*) attempts,count(*) filter(where w.generation_id is not null)"
                    + " generated,count(*) filter(where w.status in"
                    + " ('PUBLICATION_REVIEW','APPROVED_FOR_PUBLICATION','PUBLISHING','PUBLISHED','UNPUBLISHED'))"
                    + " ready,count(*) filter(where w.status='PUBLISHED') published,count(*)"
                    + " filter(where w.status like '%REJECTED') rejected,count(*) filter(where"
                    + " w.status in"
                    + " ('CONCEPT_READY','GENERATING','QA_PENDING','QA_APPROVED','SIMILARITY_CHECK','PROCESSING','PAUSED'))"
                    + " active from wallpaper_productions w join concepts c on c.id=w.concept_id"
                    + " where c.collection_id=?")
            .param(id)
            .query()
            .singleRow();
    result.putAll(counts);
    var costs =
        service
            .db
            .sql(
                "select gc.currency,coalesce(sum(coalesce(gc.actual_cost,gc.estimated_cost)),0)"
                    + " total,count(*) filter(where gc.actual_cost is null and gc.estimated_cost is"
                    + " null) unknown from generation_costs gc join wallpaper_productions w on"
                    + " w.generation_id=gc.generation_id join concepts c on c.id=w.concept_id where"
                    + " c.collection_id=? group by gc.currency")
            .param(id)
            .query()
            .listOfRows();
    result.put("costs", costs);
    result.put(
        "processingCosts",
        service
            .db
            .sql(
                "select u.currency,sum(u.external_cost) external_cost,sum(u.duration_ms)"
                    + " duration_ms from processing_compute_usage u join wallpaper_productions w on"
                    + " w.processing_run_id=u.run_id join concepts c on c.id=w.concept_id where"
                    + " c.collection_id=? group by u.currency")
            .param(id)
            .query()
            .listOfRows());
    int ready = integer(counts, "ready", 0);
    result.put(
        "costPerReady",
        costs.stream()
            .map(
                r -> {
                  var row = new LinkedHashMap<>(r);
                  row.put(
                      "perReady",
                      ready == 0
                          ? null
                          : ((BigDecimal) r.get("total"))
                              .divide(
                                  BigDecimal.valueOf(ready), 8, java.math.RoundingMode.HALF_UP));
                  return row;
                })
            .toList());
    result.put("diversity", clustering.diversity(id));
    return result;
  }

  public Object produce(
      UUID id,
      int target,
      int batch,
      int attempts,
      BigDecimal maxCost,
      BigDecimal reserve,
      String profile) {
    if (target < 1
        || target > 1000
        || batch < 1
        || batch > 20
        || attempts < target
        || attempts > 2000
        || maxCost.signum() < 0
        || reserve.signum() < 0) {
      throw new IllegalArgumentException("Invalid collection production limits");
    }
    var c = service.factory.one("collections", id);
    if (!Boolean.TRUE.equals(c.get("wallpaper"))) {
      throw new IllegalArgumentException("Wallpaper collection required");
    }
    return service.tx.execute(
        s -> {
          service
              .db
              .sql("select id from collections where id=? for update")
              .param(id)
              .query()
              .singleRow();
          if (service
              .db
              .sql("select exists(select 1 from wallpaper_collection_plans where collection_id=?)")
              .param(id)
              .query(Boolean.class)
              .single()) {
            throw conflict("A production plan already exists; pause/resume it instead");
          }
          service
              .db
              .sql(
                  "insert into"
                      + " wallpaper_collection_plans(collection_id,target_approved,batch_size,max_attempts,max_cost,reserved_cost_per_attempt,profile_key)"
                      + " values(?,?,?,?,?,?,?)")
              .params(id, target, batch, attempts, maxCost, reserve, profile)
              .update();
          service
              .db
              .sql(
                  "update collections set"
                      + " wallpaper_status='GENERATING',wallpaper_revision=wallpaper_revision+1"
                      + " where id=?")
              .param(id)
              .update();
          return status(id);
        });
  }

  public Object action(UUID id, String action, int revision) {
    String state =
        switch (action) {
          case "pause" -> "PAUSED";
          case "resume" -> "RUNNING";
          case "cancel" -> "CANCELLED";
          default -> throw new IllegalArgumentException("Unknown collection action");
        };
    if (service
        .db
        .sql(
            "update wallpaper_collection_plans set"
                + " status=?,failure_reason=null,revision=revision+1,updated_at=now() where"
                + " collection_id=? and revision=? and status not in ('COMPLETED','CANCELLED')")
        .params(state, id, revision)
        .update()
        != 1) {
      throw conflict("Collection plan changed");
    }
    return status(id);
  }

  void pause(UUID id, String reason) {
    service
        .db
        .sql(
            "update wallpaper_collection_plans set"
                + " status=?,failure_reason=?,revision=revision+1,updated_at=now() where"
                + " collection_id=? and status='RUNNING'")
        .params(reason.equals("DIVERSITY") ? "PAUSED_DIVERSITY" : "PAUSED_LIMIT", reason, id)
        .update();
  }

  public void advance(UUID id) {
    var plan =
        service
            .db
            .sql("select * from wallpaper_collection_plans where collection_id=?")
            .param(id)
            .query()
            .singleRow();
    if (!plan.get("status").equals("RUNNING")) {
      return;
    }
    var progress = status(id);
    int ready = integer(progress, "ready", 0), attempts = integer(progress, "attempts", 0);
    if (ready >= integer(plan, "target_approved", 1)) {
      service
          .db
          .sql(
              "update wallpaper_collection_plans set status='COMPLETED',revision=revision+1 where"
                  + " collection_id=? and status='RUNNING'")
          .param(id)
          .update();
      service
          .db
          .sql("update collections set wallpaper_status='READY' where id=?")
          .param(id)
          .update();
      service.metrics.counter("media_factory_wallpaper_collection_ready_total").increment();
      return;
    }
    if (integer(progress, "active", 0) > 0) {
      return;
    }
    if (attempts >= integer(plan, "max_attempts", 1)) {
      pause(id, "MAX_ATTEMPTS");
      return;
    }
    if (attempts > 0) {
      UUID clusteringRun = clustering.cluster(id, service.similarity.activeModel(), .16, 3);
      var stats =
          map(
              service
                  .db
                  .sql("select statistics from collection_clustering_runs where id=?")
                  .param(clusteringRun)
                  .query(String.class)
                  .single());
      double threshold =
          service
              .db
              .sql(
                  "select p.saturation_threshold from similarity_profiles p join collections c on"
                      + " c.similarity_profile=p.id where c.id=?")
              .param(id)
              .query(Double.class)
              .single();
      if (GenerationBatchService.shouldPause(
          attempts, number(stats, "largestClusterShare", 0), threshold)) {
        pause(id, "DIVERSITY");
        return;
      }
    }
    service.tx.executeWithoutResult(
        s -> {
          var locked =
              service
                  .db
                  .sql("select * from wallpaper_collection_plans where collection_id=? for update")
                  .param(id)
                  .query()
                  .singleRow();
          if (!locked.get("status").equals("RUNNING")
              || !locked.get("revision").equals(plan.get("revision"))) {
            return;
          }
          var current = status(id);
          if (integer(current, "active", 0) > 0) {
            return;
          }
          int count =
              Math.min(
                  integer(plan, "batch_size", 1),
                  Math.min(
                      integer(plan, "target_approved", 1) - integer(current, "ready", 0),
                      integer(plan, "max_attempts", 1) - integer(current, "attempts", 0)));
          var costs = (List<Map<String, Object>>) current.get("costs");
          if (costs.stream()
              .anyMatch(r -> integer(r, "unknown", 0) > 0 || !"USD".equals(r.get("currency")))) {
            pause(id, "UNKNOWN_OR_MIXED_COST");
            return;
          }
          BigDecimal spent =
              costs.stream()
                  .map(r -> (BigDecimal) r.get("total"))
                  .reduce(BigDecimal.ZERO, BigDecimal::add);
          BigDecimal reserve = (BigDecimal) plan.get("reserved_cost_per_attempt");
          if (spent
              .add(reserve.multiply(BigDecimal.valueOf(count)))
              .compareTo((BigDecimal) plan.get("max_cost"))
              > 0) {
            pause(id, "MAX_COST");
            return;
          }
          var concepts =
              service
                  .db
                  .sql("select id,name from concepts where collection_id=? order by created_at,id")
                  .param(id)
                  .query()
                  .listOfRows();
          if (concepts.isEmpty()) {
            pause(id, "CONCEPTS_REQUIRED");
            return;
          }
          int start = integer(current, "attempts", 0);
          for (int i = 0; i < count; i++) {
            var concept = concepts.get((start + i) % concepts.size());
            String key = "collection:" + id + ":" + (start + i);
            service.start(
                (UUID) concept.get("id"),
                plan.get("profile_key").toString(),
                Map.of(
                    "title",
                    concept.get("name"),
                    "slug",
                    "wallpaper-" + id.toString().substring(0, 8) + "-" + (start + i),
                    "premium",
                    false,
                    "featured",
                    false,
                    "tags",
                    List.of()),
                key,
                null);
          }
          service
              .db
              .sql(
                  "update wallpaper_collection_plans set revision=revision+1,updated_at=now() where"
                      + " collection_id=?")
              .param(id)
              .update();
        });
  }

  public Object cover(UUID collection, UUID asset) {
    if (!service
        .db
        .sql(
            "select exists(select 1 from wallpaper_productions w join concepts c on"
                + " c.id=w.concept_id where c.collection_id=? and w.master_asset_id=? and w.status"
                + " in ('PUBLICATION_REVIEW','APPROVED_FOR_PUBLICATION','PUBLISHED'))")
        .params(collection, asset)
        .query(Boolean.class)
        .single()) {
      throw conflict("Cover must be a ready wallpaper in this collection");
    }
    var run =
        service.processing.request(
            asset,
            List.of("COLLECTION_COVER", "COLLECTION_CARD", "COLLECTION_THUMBNAIL"),
            Map.of(),
            null,
            10);
    service
        .db
        .sql(
            "update collections set cover_asset_id=?,wallpaper_revision=wallpaper_revision+1 where"
                + " id=?")
        .params(asset, collection)
        .update();
    return run;
  }

  public Object coverCandidates(UUID id) {
    return service
        .db
        .sql(
            "select w.master_asset_id,w.metadata,a.width,a.height from wallpaper_productions w join"
                + " concepts c on c.id=w.concept_id join assets a on a.id=w.master_asset_id where"
                + " c.collection_id=? and w.status in"
                + " ('PUBLICATION_REVIEW','APPROVED_FOR_PUBLICATION','PUBLISHED') order by"
                + " a.width::bigint*a.height desc,w.created_at limit 10")
        .param(id)
        .query()
        .listOfRows()
        .stream()
        .map(WallpaperProductionService::clean)
        .toList();
  }
}
