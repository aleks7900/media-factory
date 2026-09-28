package com.mediafactory.video;

import static com.mediafactory.processing.ProcessingJson.*;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Collection budgets reserve each candidate's entire allowance before it is queued. */
@Service
public class VideoCollectionService {
  final VideoProductionService s;
  final boolean enabled;

  public VideoCollectionService(
      VideoProductionService s, @Value("${media.worker.enabled:true}") boolean enabled) {
    this.s = s;
    this.enabled = enabled;
  }

  public Object configure(
      UUID collection,
      String profile,
      String provider,
      int target,
      int batch,
      int attempts,
      BigDecimal budget,
      BigDecimal allowance) {
    if (target < 1
        || target > 1000
        || batch < 1
        || batch > 20
        || attempts < 1
        || attempts > 2000
        || budget == null
        || budget.signum() < 0
        || allowance == null
        || allowance.signum() < 0) throw new IllegalArgumentException("Invalid collection limits");
    s.profile(profile);
    s.router.route(provider, false);
    return s.tx.execute(
        t -> {
          s.db
              .sql("select id from collections where id=? for update")
              .param(collection)
              .query()
              .singleRow();
          s.db
              .sql(
                  "insert into"
                      + " video_collection_plans(collection_id,profile_key,target_approved,batch_size,max_attempts,budget,provider,reserved_cost_per_video)"
                      + " values(?,?,?,?,?,?,?,?) on conflict(collection_id) do update set"
                      + " profile_key=excluded.profile_key,target_approved=excluded.target_approved,batch_size=excluded.batch_size,max_attempts=excluded.max_attempts,budget=excluded.budget,provider=excluded.provider,reserved_cost_per_video=excluded.reserved_cost_per_video,status='RUNNING',revision=video_collection_plans.revision+1")
              .params(collection, profile, target, batch, attempts, budget, provider, allowance)
              .update();
          return progress(collection);
        });
  }

  public Map<String, Object> progress(UUID id) {
    var result = new LinkedHashMap<String, Object>();
    result.put(
        "plan",
        s.db
            .sql("select * from video_collection_plans where collection_id=?")
            .param(id)
            .query()
            .listOfRows());
    result.put(
        "progress",
        s.db
            .sql(
                "select count(*) candidates,count(*) filter(where v.status='READY')"
                    + " approved,count(*) filter(where v.status='REVIEW')"
                    + " review,coalesce(sum(v.budget),0) reserved from video_productions v join"
                    + " generations g on g.id=v.generation_id join concepts c on c.id=g.concept_id"
                    + " where c.collection_id=?")
            .param(id)
            .query()
            .singleRow());
    return result;
  }

  public Object pause(UUID id, boolean paused) {
    s.db
        .sql("update video_collection_plans set status=?,revision=revision+1 where collection_id=?")
        .params(paused ? "PAUSED" : "RUNNING", id)
        .update();
    return progress(id);
  }

  @Scheduled(fixedDelay = 5000)
  public void tick() {
    if (!enabled) return;
    for (UUID id :
        s.db
            .sql("select collection_id from video_collection_plans where status='RUNNING'")
            .query(UUID.class)
            .list())
      try {
        advance(id);
      } catch (RuntimeException error) {
        s.db
            .sql(
                "update video_collection_plans set status='PAUSED',failure_reason='PLAN_FAILED'"
                    + " where collection_id=?")
            .param(id)
            .update();
      }
  }

  public void advance(UUID collection) {
    s.tx.executeWithoutResult(
        t -> {
          var p =
              s.db
                  .sql("select * from video_collection_plans where collection_id=? for update")
                  .param(collection)
                  .query()
                  .singleRow();
          if (!"RUNNING".equals(p.get("status"))) return;
          var stats = map(progress(collection).get("progress"));
          int count = integer(stats, "candidates", 0), ready = integer(stats, "approved", 0);
          if (ready >= integer(p, "target_approved", 1)) {
            stop(collection, "COMPLETED", "TARGET_REACHED");
            return;
          }
          if (count >= integer(p, "max_attempts", 1)) {
            stop(collection, "PAUSED", "ATTEMPT_LIMIT");
            return;
          }
          BigDecimal remaining =
              new BigDecimal(p.get("budget").toString())
                  .subtract(new BigDecimal(stats.get("reserved").toString()));
          BigDecimal allowance = new BigDecimal(p.get("reserved_cost_per_video").toString());
          int active =
              s.db
                  .sql(
                      "select count(*) from video_productions v join generations g on"
                          + " g.id=v.generation_id join concepts c on c.id=g.concept_id where"
                          + " c.collection_id=? and v.status not in"
                          + " ('READY','QA_REJECTED','GENERATION_FAILED','VALIDATION_FAILED','PROCESSING_FAILED','LOOP_FAILED','CANCELLED')")
                  .param(collection)
                  .query(Integer.class)
                  .single();
          int slots =
              Math.min(
                  integer(p, "batch_size", 1) - active,
                  Math.min(
                      integer(p, "max_attempts", 1) - count,
                      integer(p, "target_approved", 1) - ready - active));
          for (int n = 0; n < slots; n++) {
            if (remaining.compareTo(allowance) < 0) {
              stop(collection, "PAUSED", "BUDGET_RESERVED");
              return;
            }
            var candidates =
                s.db
                    .sql(
                        "select a.id from assets a join generations g on g.id=a.generation_id join"
                            + " concepts c on c.id=g.concept_id join quality_reviews q on"
                            + " q.id=a.current_review_id where c.collection_id=? and a.media_type"
                            + " like 'image/%' and q.final_decision='APPROVED' and not"
                            + " exists(select 1 from video_productions v where"
                            + " v.source_asset_id=a.id and v.status not in"
                            + " ('QA_REJECTED','GENERATION_FAILED','VALIDATION_FAILED','PROCESSING_FAILED','LOOP_FAILED','CANCELLED'))"
                            + " order by (select count(*) from video_productions v where"
                            + " v.source_asset_id=a.id),a.created_at limit 1")
                    .param(collection)
                    .query(UUID.class)
                    .list();
            if (candidates.isEmpty()) {
              if (active == 0) stop(collection, "PAUSED", "NO_APPROVED_SOURCE");
              return;
            }
            s.start(
                candidates.getFirst(),
                p.get("profile_key").toString(),
                Objects.toString(p.get("provider"), null),
                false,
                Map.of(),
                allowance,
                1,
                "video-plan:" + collection + ":" + (count + n),
                null);
            remaining = remaining.subtract(allowance);
          }
        });
  }

  void stop(UUID id, String state, String reason) {
    s.db
        .sql(
            "update video_collection_plans set status=?,failure_reason=?,revision=revision+1 where"
                + " collection_id=?")
        .params(state, reason, id)
        .update();
  }
}
