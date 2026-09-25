package com.mediafactory.similarity;

import static com.mediafactory.similarity.SimilarityService.JSON;

import com.mediafactory.prompt.PromptModels.PromptRenderRequest;
import com.mediafactory.provider.ImageOptions;
import com.mediafactory.service.FactoryService;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GenerationBatchService {

  private final SimilarityService similarity;
  private final FactoryService factory;
  private final CollectionClusteringService clustering;
  private final boolean worker;

  public GenerationBatchService(
      SimilarityService similarity,
      FactoryService factory,
      CollectionClusteringService clustering,
      @org.springframework.beans.factory.annotation.Value("${media.worker.enabled:true}")
      boolean worker) {
    this.similarity = similarity;
    this.factory = factory;
    this.clustering = clustering;
    this.worker = worker;
  }

  public static boolean shouldPause(int dispatched, double largestShare, double threshold) {
    return dispatched > 0 && largestShare >= threshold;
  }

  @Transactional
  public Object create(Request request, String key) {
    if (request.total() < 1
        || request.total() > 10000
        || request.batchSize() < 1
        || request.batchSize() > 50
        || request.prompt() == null
        || request.width() < 64
        || request.height() < 64
        || request.width() > 4096
        || request.height() > 4096
        || key == null
        || key.isBlank()
        || key.length() > 150) {
      throw new IllegalArgumentException("Invalid generation batch");
    }
    var concept = factory.one("concepts", request.conceptId());
    String body = JSON.writeValueAsString(request);
    UUID id = UUID.randomUUID();
    similarity
        .db()
        .sql(
            "insert into"
                + " generation_batches(id,collection_id,concept_id,idempotency_key,request,total,batch_size)"
                + " values(?,?,?,?,cast(? as jsonb),?,?) on conflict(idempotency_key) do nothing")
        .params(
            id,
            concept.get("collection_id"),
            request.conceptId(),
            key,
            body,
            request.total(),
            request.batchSize())
        .update();
    var row =
        similarity
            .db()
            .sql("select * from generation_batches where idempotency_key=?")
            .param(key)
            .query()
            .singleRow();
    if (!JSON.readTree(row.get("request").toString()).equals(JSON.readTree(body))) {
      throw SimilarityService.conflict("Batch key already used with another request");
    }
    return row;
  }

  @Scheduled(fixedDelayString = "${media.similarity.batch-poll-ms:5000}")
  public void tick() {
    if (!worker || !similarity.enabled()) {
      return;
    }
    for (UUID id :
        similarity
            .db()
            .sql(
                "select id from generation_batches where status='RUNNING' order by created_at limit"
                    + " 5")
            .query(UUID.class)
            .list()) {
      try {
        advance(id);
      } catch (org.springframework.web.server.ResponseStatusException
               | IllegalArgumentException e) {
        var current =
            similarity
                .db()
                .sql("select revision from generation_batches where id=?")
                .param(id)
                .query(Integer.class)
                .single();
        pause(
            id,
            "Generation request or diversity policy requires a changed prompt/preset or resolved"
                + " analysis: "
                + Objects.toString(e.getMessage(), "validation failure")
                .substring(
                    0,
                    Math.min(
                        1000, Objects.toString(e.getMessage(), "validation failure").length())),
            current);
      } catch (Exception e) {
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("Batch dispatch deferred: {}", e.getClass().getSimpleName());
      }
    }
  }

  public void advance(UUID id) {
    var row =
        similarity
            .db()
            .sql("select * from generation_batches where id=?")
            .param(id)
            .query()
            .singleRow();
    if (!row.get("status").equals("RUNNING")) {
      return;
    }
    int dispatched = ((Number) row.get("dispatched")).intValue();
    UUID collection = (UUID) row.get("collection_id");
    if (dispatched > 0) {
      int missing =
          similarity
              .db()
              .sql(
                  "select count(*) from generation_batch_members m join generations g on"
                      + " g.id=m.generation_id left join assets a on a.generation_id=g.id where"
                      + " m.batch_id=? and g.status<>'FAILED' and (a.id is null or not"
                      + " exists(select 1 from similarity_jobs j where j.asset_id=a.id and"
                      + " j.model_id=? and j.status in ('SUCCEEDED','FAILED')))")
              .params(id, similarity.activeModel().id())
              .query(Integer.class)
              .single();
      if (missing > 0) {
        return;
      }
      boolean failed =
          similarity
              .db()
              .sql(
                  "select exists(select 1 from generation_batch_members m join generations g on"
                      + " g.id=m.generation_id left join assets a on a.generation_id=g.id where"
                      + " m.batch_id=? and (g.status='FAILED' or exists(select 1 from"
                      + " similarity_jobs j where j.asset_id=a.id and j.model_id=? and"
                      + " j.status='FAILED')))")
              .params(id, similarity.activeModel().id())
              .query(Boolean.class)
              .single();
      if (failed) {
        pause(
            id,
            "Generation or embedding failed; resolve failures before continuing",
            (int) row.get("revision"));
        return;
      }
      UUID run = clustering.cluster(collection, similarity.activeModel(), .16, 3);
      var stats =
          JSON.readTree(
              similarity
                  .db()
                  .sql("select statistics::text from collection_clustering_runs where id=?")
                  .param(run)
                  .query(String.class)
                  .single());
      double threshold =
          similarity
              .db()
              .sql(
                  "select p.saturation_threshold from collections c join similarity_profiles p on"
                      + " p.id=c.similarity_profile where c.id=?")
              .param(collection)
              .query(Double.class)
              .single();
      // CONTINUE authorizes one additional chunk; that authorization is consumed by dispatch below.
      if (dispatched < ((Number) row.get("total")).intValue()
          && shouldPause(dispatched, stats.path("largestClusterShare").asDouble(), threshold)
          && !"HUMAN_CONTINUE".equals(row.get("pause_reason"))) {
        pause(
            id,
            "Largest visual family share "
                + stats.path("largestClusterShare").asDouble()
                + " exceeds "
                + threshold,
            (int) row.get("revision"));
        return;
      }
    }
    similarity
        .tx()
        .executeWithoutResult(
            s -> {
              var locked =
                  similarity
                      .db()
                      .sql("select * from generation_batches where id=? for update")
                      .param(id)
                      .query()
                      .singleRow();
              if (!locked.get("status").equals("RUNNING")
                  || !locked.get("revision").equals(row.get("revision"))) {
                return;
              }
              int total = ((Number) locked.get("total")).intValue();
              if (dispatched >= total) {
                similarity
                    .db()
                    .sql(
                        "update generation_batches set"
                            + " status='COMPLETED',revision=revision+1,updated_at=now() where id=?")
                    .param(id)
                    .update();
                return;
              }
              var request = JSON.readValue(locked.get("request").toString(), Request.class);
              int end = Math.min(total, dispatched + request.batchSize());
              for (int index = dispatched; index < end; index++) {
                var generation =
                    factory.generatePrompt(
                        request.conceptId(),
                        request.width(),
                        request.height(),
                        "batch:" + id + ":" + index,
                        null,
                        request.options() == null ? ImageOptions.defaults() : request.options(),
                        request.prompt());
                similarity
                    .db()
                    .sql(
                        "insert into generation_batch_members(batch_id,generation_id,ordinal)"
                            + " values(?,?,?)")
                    .params(id, generation.get("id"), index)
                    .update();
              }
              similarity
                  .db()
                  .sql(
                      "update generation_batches set"
                          + " dispatched=?,revision=revision+1,pause_reason=null,updated_at=now()"
                          + " where id=?")
                  .params(end, id)
                  .update();
            });
  }

  private void pause(UUID id, String reason, int revision) {
    similarity
        .db()
        .sql(
            "update generation_batches set"
                + " status='PAUSED_DIVERSITY',pause_reason=?,revision=revision+1,updated_at=now()"
                + " where id=? and revision=? and status='RUNNING'")
        .params(reason, id, revision)
        .update();
  }

  @Transactional
  public Object action(
      UUID id,
      int revision,
      String action,
      String reason,
      PromptRenderRequest changed,
      String actor) {
    if (!Set.of("CONTINUE", "STOP", "CHANGE_PROMPT").contains(action)
        || reason == null
        || reason.isBlank()
        || reason.length() > 2000) {
      throw new IllegalArgumentException("Action and reason required");
    }
    var row =
        similarity
            .db()
            .sql("select * from generation_batches where id=? for update")
            .param(id)
            .query()
            .singleRow();
    if (!row.get("revision").equals(revision) || !row.get("status").equals("PAUSED_DIVERSITY")) {
      throw SimilarityService.conflict("Batch must be paused at the current revision");
    }
    var input = JSON.readValue(row.get("request").toString(), Request.class);
    if (action.equals("CHANGE_PROMPT")) {
      if (changed == null) {
        throw new IllegalArgumentException("Changed prompt/presets required");
      }
      input =
          new Request(
              input.conceptId(),
              input.total(),
              input.batchSize(),
              input.width(),
              input.height(),
              input.options(),
              changed);
    }
    similarity
        .db()
        .sql(
            "insert into"
                + " generation_batch_actions(id,batch_id,actor,action,reason,before_request,after_request)"
                + " values(?,?,?,?,?,cast(? as jsonb),cast(? as jsonb))")
        .params(
            UUID.randomUUID(),
            id,
            actor,
            action,
            reason,
            row.get("request").toString(),
            JSON.writeValueAsString(input))
        .update();
    similarity
        .db()
        .sql(
            "update generation_batches set status=?,pause_reason='HUMAN_CONTINUE',request=cast(? as"
                + " jsonb),revision=revision+1,updated_at=now() where id=?")
        .params(action.equals("STOP") ? "STOPPED" : "RUNNING", JSON.writeValueAsString(input), id)
        .update();
    return similarity
        .db()
        .sql("select * from generation_batches where id=?")
        .param(id)
        .query()
        .singleRow();
  }

  public record Request(
      UUID conceptId,
      int total,
      int batchSize,
      int width,
      int height,
      ImageOptions options,
      PromptRenderRequest prompt) {

  }
}
