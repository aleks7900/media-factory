package com.mediafactory.stock;

import static com.mediafactory.processing.ProcessingJson.*;
import static com.mediafactory.stock.StockProductionService.*;

import com.mediafactory.similarity.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class StockCollectionService {

  final StockProductionService s;
  final CollectionClusteringService clusters;

  public StockCollectionService(StockProductionService s, CollectionClusteringService clusters) {
    this.s = s;
    this.clusters = clusters;
  }

  public Object create(UUID project, String name) {
    if (name == null || name.isBlank() || name.length() > 200) {
      throw new IllegalArgumentException("Collection title required");
    }
    return s.tx.execute(
        t -> {
          var c = s.factory.collection(project, name);
          s.db
              .sql(
                  "update collections set"
                      + " stock=true,qa_policy='stock',similarity_profile='STOCK_STRICT' where"
                      + " id=?")
              .param(c.get("id"))
              .update();
          return c;
        });
  }

  public Object list() {
    return s.db
        .sql(
            "select c.*,p.status production_status,p.target_approved from collections c left join"
                + " stock_collection_plans p on p.collection_id=c.id where c.stock order by"
                + " c.created_at desc")
        .query()
        .listOfRows();
  }

  public Map<String, Object> progress(UUID id) {
    var result =
        new LinkedHashMap<String, Object>(
            s.db
                .sql(
                    "select count(*) attempts,count(*) filter(where s.status in"
                        + " ('READY_FOR_EXPORT','EXPORTED')) ready,count(*) filter(where"
                        + " s.status='METADATA_REVIEW') review,count(*) filter(where s.status in"
                        + " ('DRAFT','SOURCE_READY','QA_PENDING','QA_APPROVED','SIMILARITY_CHECK','PROCESSING','TECHNICAL_VALIDATION','METADATA_GENERATION','METADATA_REVIEW'))"
                        + " active,count(*) filter(where s.status like '%REJECTED') rejected from"
                        + " stock_productions s join concepts c on c.id=s.concept_id where"
                        + " c.collection_id=?")
                .param(id)
                .query()
                .singleRow());
    result.put(
        "plan",
        s
            .db
            .sql("select * from stock_collection_plans where collection_id=?")
            .param(id)
            .query()
            .listOfRows()
            .stream()
            .findFirst()
            .orElse(Map.of()));
    result.put("costs", costs(id));
    result.put("diversity", clusters.diversity(id));
    return result;
  }

  public List<Map<String, Object>> costs(UUID collection) {
    // Distinct referenced generations/runs prevent double counting when one original is curated
    // twice.
    return s.db
        .sql(
            "with production as (select s.* from stock_productions s join concepts c on"
                + " c.id=s.concept_id where (?::uuid is null or c.collection_id=?)), usage as"
                + " (select gc.currency,coalesce(gc.actual_cost,gc.estimated_cost) cost from"
                + " generation_costs gc where gc.generation_id in(select generation_id from"
                + " production) union all select u.currency,u.external_cost from"
                + " processing_compute_usage u where (u.currency is not null or u.external_cost is"
                + " null or u.external_cost<>0) and u.run_id in(select processing_run_id from"
                + " production)) select currency,coalesce(sum(cost),0) total,count(*) filter(where"
                + " cost is null) unknown,case when (select count(*) from production where status"
                + " in ('READY_FOR_EXPORT','EXPORTED'))>0 then sum(cost)/(select count(*) from"
                + " production where status in ('READY_FOR_EXPORT','EXPORTED')) else null end"
                + " cost_per_ready from usage group by currency")
        .params(collection, collection)
        .query()
        .listOfRows();
  }

  public Object plan(
      UUID collection,
      String profile,
      int target,
      int batch,
      int attempts,
      BigDecimal budget,
      BigDecimal reserve) {
    if (target < 1
        || target > 1000
        || batch < 1
        || batch > 20
        || attempts < target
        || attempts > 2000
        || budget == null
        || reserve == null
        || budget.signum() < 0
        || reserve.signum() < 0) {
      throw new IllegalArgumentException("Invalid bounded production plan");
    }
    s.profile(profile);
    if (!Boolean.TRUE.equals(s.factory.one("collections", collection).get("stock"))) {
      throw conflict("Stock collection required");
    }
    s.db
        .sql(
            "insert into"
                + " stock_collection_plans(collection_id,profile_key,target_approved,batch_size,max_attempts,max_cost,reserve_per_attempt)"
                + " values(?,?,?,?,?,?,?) on conflict(collection_id) do nothing")
        .params(collection, profile, target, batch, attempts, budget, reserve)
        .update();
    return progress(collection);
  }

  public Object action(UUID collection, int revision, String action) {
    String status =
        switch (action) {
          case "pause" -> "PAUSED";
          case "resume" -> "RUNNING";
          case "cancel" -> "CANCELLED";
          default -> throw new IllegalArgumentException("Unknown plan action");
        };
    if (s.db
        .sql(
            "update stock_collection_plans set status=?,failure_reason=null,revision=revision+1"
                + " where collection_id=? and revision=? and status not in"
                + " ('COMPLETED','CANCELLED')")
        .params(status, collection, revision)
        .update()
        != 1) {
      throw conflict("Plan changed or completed");
    }
    return progress(collection);
  }

  void pause(UUID id, String reason) {
    s.db
        .sql(
            "update stock_collection_plans set status='PAUSED',failure_reason=?,revision=revision+1"
                + " where collection_id=? and status='RUNNING'")
        .params(reason, id)
        .update();
  }

  public void advance(UUID id) {
    var plan =
        s.db
            .sql("select * from stock_collection_plans where collection_id=?")
            .param(id)
            .query()
            .singleRow();
    if (!plan.get("status").equals("RUNNING")) {
      return;
    }
    var p = progress(id);
    if (integer(p, "ready", 0) >= integer(plan, "target_approved", 1)) {
      s.db
          .sql(
              "update stock_collection_plans set status='COMPLETED',revision=revision+1 where"
                  + " collection_id=? and status='RUNNING'")
          .param(id)
          .update();
      return;
    }
    if (integer(p, "active", 0) > 0) {
      return;
    }
    if (integer(p, "attempts", 0) >= integer(plan, "max_attempts", 1)) {
      pause(id, "MAX_ATTEMPTS");
      return;
    }
    if (integer(p, "attempts", 0) > 0) {
      UUID run = clusters.cluster(id, s.similarity.activeModel(), .16, 3);
      var stats =
          map(
              s.db
                  .sql("select statistics from collection_clustering_runs where id=?")
                  .param(run)
                  .query(String.class)
                  .single());
      double threshold =
          s.db
              .sql("select saturation_threshold from similarity_profiles where id='STOCK_STRICT'")
              .query(Double.class)
              .single();
      if (GenerationBatchService.shouldPause(
          integer(p, "attempts", 0), number(stats, "largestClusterShare", 0), threshold)) {
        pause(id, "DIVERSITY");
        return;
      }
    }
    s.tx.executeWithoutResult(
        t -> {
          var fresh =
              s.db
                  .sql("select * from stock_collection_plans where collection_id=? for update")
                  .param(id)
                  .query()
                  .singleRow();
          if (!fresh.get("status").equals("RUNNING")
              || !fresh.get("revision").equals(plan.get("revision"))) {
            return;
          }
          var current = progress(id);
          if (integer(current, "active", 0) > 0) {
            return;
          }
          int count =
              Math.min(
                  integer(plan, "batch_size", 1),
                  Math.min(
                      integer(plan, "target_approved", 1) - integer(current, "ready", 0),
                      integer(plan, "max_attempts", 1) - integer(current, "attempts", 0)));
          var costs = costs(id);
          if (costs.stream()
              .anyMatch(
                  c ->
                      integer(c, "unknown", 0) > 0
                          || !Objects.toString(c.get("currency"), "UNKNOWN")
                          .trim()
                          .equals("USD"))) {
            pause(id, "UNKNOWN_OR_MIXED_COST");
            return;
          }
          var total =
              costs.stream()
                  .map(c -> (BigDecimal) c.get("total"))
                  .reduce(BigDecimal.ZERO, BigDecimal::add);
          if (total
              .add(
                  ((BigDecimal) plan.get("reserve_per_attempt"))
                      .multiply(BigDecimal.valueOf(count)))
              .compareTo((BigDecimal) plan.get("max_cost"))
              > 0) {
            pause(id, "MAX_COST");
            return;
          }
          var concepts =
              s.db
                  .sql("select id from concepts where collection_id=? order by created_at,id")
                  .param(id)
                  .query(UUID.class)
                  .list();
          if (concepts.isEmpty()) {
            pause(id, "CONCEPTS_REQUIRED");
            return;
          }
          for (int i = 0; i < count; i++) {
            int ordinal = integer(current, "attempts", 0) + i;
            s.start(
                concepts.get(ordinal % concepts.size()),
                null,
                plan.get("profile_key").toString(),
                "stock-collection:" + id + ":" + ordinal);
          }
          s.db
              .sql("update stock_collection_plans set revision=revision+1 where collection_id=?")
              .param(id)
              .update();
        });
  }

  public Object dashboard() {
    var result =
        new LinkedHashMap<String, Object>(
            s.db
                .sql(
                    "select count(*) candidates,count(*) filter(where created_at>=current_date and"
                        + " generation_id is not null) generated_today,count(*) filter(where"
                        + " approved_at>=current_date) stock_ready_today,count(*) filter(where"
                        + " status='METADATA_GENERATION') metadata_pending,count(*) filter(where"
                        + " status='METADATA_REVIEW') metadata_review,count(*) filter(where"
                        + " status='READY_FOR_EXPORT') ready_for_export,count(*) filter(where"
                        + " exported_at>=current_date) exported_today,count(*) filter(where"
                        + " status='EXPORTED') exported,count(*) filter(where status='PROCESSING')"
                        + " processing,count(*) filter(where status='VALIDATION_FAILED')"
                        + " technical_failures,count(*) filter(where status='QA_REJECTED')"
                        + " qa_rejected,count(*) filter(where status='DUPLICATE_REJECTED')"
                        + " duplicate_rejected,coalesce(count(*) filter(where"
                        + " status='QA_REJECTED')::numeric/nullif(count(*),0),0)"
                        + " qa_rejection_rate,coalesce(count(*) filter(where"
                        + " status='DUPLICATE_REJECTED')::numeric/nullif(count(*),0),0)"
                        + " duplicate_rejection_rate from stock_productions")
                .query()
                .singleRow());
    result.put("costs", costs(null));
    return result;
  }
}
