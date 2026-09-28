package com.mediafactory.video;

import static com.mediafactory.processing.ProcessingJson.*;
import static com.mediafactory.video.VideoJson.*;

import com.mediafactory.similarity.PerceptualHash;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Leased local processing. No database transaction spans an FFmpeg or Vision call. */
@Component
@ConditionalOnProperty(name = "media.worker.enabled", havingValue = "true", matchIfMissing = true)
public class VideoPipelineWorker implements AutoCloseable {
  final VideoProductionService s;
  final VideoWorkerClient worker;
  final VideoGenerationWorker generation;
  final VideoSemanticQa vision;
  final ExecutorService pool = Executors.newFixedThreadPool(2);
  final ConcurrentMap<UUID, UUID> active = new ConcurrentHashMap<>();

  public VideoPipelineWorker(
      VideoProductionService s,
      VideoWorkerClient worker,
      VideoGenerationWorker generation,
      VideoSemanticQa vision) {
    this.s = s;
    this.worker = worker;
    this.generation = generation;
    this.vision = vision;
  }

  @Scheduled(fixedDelay = 2000)
  public void tick() {
    for (UUID id :
        s.db
            .sql(
                "select id from video_productions where status in ('RAW_READY','PROCESSING') and"
                    + " not paused and available_at<=now() and (lease_until is null or"
                    + " lease_until<now()) order by created_at limit 2")
            .query(UUID.class)
            .list()) {
      if (active.size() >= 2) break;
      UUID token = UUID.randomUUID();
      if (s.db
              .sql(
                  "update video_productions set lease_token=?,lease_until=now()+interval '3"
                      + " minutes' where id=? and (lease_until is null or lease_until<now()) and"
                      + " not paused and status in ('RAW_READY','PROCESSING')")
              .params(token, id)
              .update()
          != 1) continue;
      active.put(id, token);
      pool.submit(
          () -> {
            try {
              step(id, token);
            } catch (RuntimeException error) {
              String code =
                  error instanceof VideoFailure ? error.getMessage() : "LOCAL_PIPELINE_FAILED";
              s.tx.executeWithoutResult(
                  t -> {
                    s.lock(id);
                    var v = s.one(id);
                    if (!token.equals(v.get("lease_token")) || "CANCELLED".equals(v.get("status")))
                      return;
                    if (Set.of("BUSY", "ALREADY_RUNNING", "WORKER_UNAVAILABLE").contains(code)) {
                      s.db
                          .sql(
                              "update video_productions set available_at=now()+interval '20"
                                  + " seconds' where id=?")
                          .param(id)
                          .update();
                      return;
                    }
                    if (v.get("current_run_id") != null)
                      s.db
                          .sql(
                              "update video_processing_runs set"
                                  + " status='FAILED',failure_code=?,completed_at=now() where id=?"
                                  + " and status<>'COMPLETED'")
                          .params(code, v.get("current_run_id"))
                          .update();
                    s.move(v, "PROCESSING_FAILED", code);
                  });
            } finally {
              active.remove(id, token);
              s.db
                  .sql(
                      "update video_productions set lease_token=null,lease_until=null where id=?"
                          + " and lease_token=?")
                  .params(id, token)
                  .update();
            }
          });
    }
  }

  @Scheduled(fixedDelay = 15000)
  public void heartbeat() {
    active.forEach(
        (id, token) ->
            s.db
                .sql(
                    "update video_productions set lease_until=now()+interval '3 minutes' where id=?"
                        + " and lease_token=?")
                .params(id, token)
                .update());
  }

  public void step(UUID id, UUID token) {
    var v = s.one(id);
    if (Boolean.TRUE.equals(v.get("paused")) || v.get("status").equals("CANCELLED")) return;
    var raw = s.source((UUID) v.get("raw_asset_id"));
    byte[] bytes = s.storage.read(raw.get("storage_key").toString());
    if (!PerceptualHash.sha(bytes).equals(raw.get("sha256")))
      throw new VideoFailure("RAW_CHECKSUM_CHANGED");
    if (v.get("status").equals("RAW_READY")) {
      var result =
          worker.execute(id, bytes, "ANALYZE", map(v.get("profile_snapshot")), Map.of(), Map.of());
      var technical = map(result.get("technical"));
      boolean valid = Boolean.TRUE.equals(technical.get("valid"));
      var frames =
          valid
              ? vision.analyze(v, (List<?>) result.getOrDefault("samples", List.of()))
              : List.of();
      result.remove("samples");
      result.put("semanticFrames", frames);
      result.put("semanticProvider", vision.provider());
      result.put(
          "semanticLimitation",
          vision.provider().equals("mock")
              ? "Mock evidence is synthetic; human temporal review is mandatory"
              : "Sampled Vision evidence cannot prove temporal identity or absence of defects;"
                    + " human review is mandatory");
      s.tx.executeWithoutResult(
          t -> {
            s.lock(id);
            var fresh = s.one(id);
            if (!token.equals(fresh.get("lease_token")) || !fresh.get("status").equals("RAW_READY"))
              return;
            s.db
                .sql(
                    "insert into video_quality_results(production_id,asset_id,evidence,type)"
                        + " values(?,?,?::jsonb,'RAW')")
                .params(id, raw.get("id"), write(result))
                .update();
            if (!valid) {
              s.move(fresh, "VALIDATION_FAILED", "RAW_VALIDATION_FAILED");
              return;
            }
            var metadata = map(technical.get("metadata"));
            s.db
                .sql(
                    "insert into video_media_metadata(asset_id,metadata) values(?,?::jsonb) on"
                        + " conflict(asset_id) do nothing")
                .params(raw.get("id"), write(metadata))
                .update();
            s.db
                .sql("update assets set width=?,height=? where id=?")
                .params(metadata.get("width"), metadata.get("height"), raw.get("id"))
                .update();
            UUID review = UUID.randomUUID();
            s.db
                .sql(
                    "insert into"
                        + " quality_reviews(id,asset_id,generation_id,kind,decision,automatic_decision,final_decision,execution_status,policy_id,policy_version,context_snapshot,technical_complete,visual_complete,completed_at)"
                        + " values(?,?,?,'ADVANCED','NEEDS_REVIEW','NEEDS_REVIEW','NEEDS_REVIEW','COMPLETED','video-human-review','1',?::jsonb,true,false,now())")
                .params(review, raw.get("id"), v.get("generation_id"), write(result))
                .update();
            s.db
                .sql("update assets set current_review_id=? where id=?")
                .params(review, raw.get("id"))
                .update();
            s.db
                .sql("update generations set status='NEEDS_REVIEW' where id=?")
                .param(v.get("generation_id"))
                .update();
            s.reprocess(
                id, integer(fresh, "revision", 0), Map.of(), Map.of(), "video-initial:" + id);
          });
      return;
    }
    var run =
        row(
            s.db
                .sql("select * from video_processing_runs where id=?")
                .param(v.get("current_run_id"))
                .query()
                .singleRow());
    UUID runId = (UUID) run.get("id");
    if (!Set.of("QUEUED", "RUNNING").contains(run.get("status"))) return;
    s.db
        .sql(
            "update video_processing_runs set"
                + " status='RUNNING',attempt=attempt+1,started_at=coalesce(started_at,now()) where"
                + " id=? and status in ('QUEUED','RUNNING')")
        .param(runId)
        .update();
    var motion =
        map(
            s.db
                .sql("select definition from motion_plan_versions where id=?")
                .param(run.get("motion_version_id"))
                .query()
                .singleRow()
                .get("definition"));
    var result =
        worker.execute(
            runId, bytes, "PROCESS", map(run.get("parameters")), motion, map(run.get("variants")));
    var outputs = new ArrayList<Map<String, Object>>();
    var ids = new HashMap<String, UUID>();
    for (Object value : (List<?>) result.get("outputs")) {
      var output = map(value);
      String kind = output.get("kind").toString();
      byte[] content = Base64.getDecoder().decode(output.remove("data").toString());
      String sha = PerceptualHash.sha(content);
      if (!sha.equals(output.get("sha256"))) throw new VideoFailure("OUTPUT_CHECKSUM_MISMATCH");
      UUID artifact =
          UUID.nameUUIDFromBytes(
              (runId + ":" + kind).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      String key =
          "video/processed/"
              + id
              + "/"
              + runId
              + "/"
              + kind
              + "/"
              + sha
              + (output.get("mediaType").equals("video/mp4") ? ".mp4" : ".jpg");
      generation.putImmutable(key, content, output.get("mediaType").toString());
      output.put("id", artifact);
      output.put("storageKey", key);
      output.put("sizeBytes", content.length);
      ids.put(kind, artifact);
      outputs.add(output);
    }
    if (!ids.containsKey("MASTER_VIDEO")) throw new VideoFailure("MASTER_MISSING");
    s.tx.executeWithoutResult(
        t -> {
          s.lock(id);
          var fresh = s.one(id);
          if (!token.equals(fresh.get("lease_token"))
              || !fresh.get("status").equals("PROCESSING")
              || !runId.equals(fresh.get("current_run_id"))) return;
          for (var output : outputs) {
            var metadata = map(output.get("metadata"));
            String kind = output.get("kind").toString();
            s.db
                .sql(
                    "insert into"
                        + " asset_variants(id,asset_id,source_asset_id,kind,storage_key,sha256,size_bytes,width,height,format,validation_status,video_processing_run_id,video_parent_variant_id)"
                        + " values(?,?,?,?,?,?,?,?,?,?,'PASSED',?,?)")
                .params(
                    output.get("id"),
                    raw.get("id"),
                    raw.get("id"),
                    kind,
                    output.get("storageKey"),
                    output.get("sha256"),
                    output.get("sizeBytes"),
                    metadata.get("width"),
                    metadata.get("height"),
                    output.get("mediaType").equals("video/mp4") ? "MP4" : "JPEG",
                    runId,
                    ids.get(output.get("parent")))
                .update();
            s.db
                .sql("insert into video_media_metadata(variant_id,metadata) values(?,?::jsonb)")
                .params(output.get("id"), write(metadata))
                .update();
          }
          var evidence = map(result.get("evidence"));
          int sequence = 0;
          for (Object value : (List<?>) evidence.get("operations")) {
            var op = map(value);
            s.db
                .sql(
                    "insert into"
                        + " video_processing_operations(run_id,sequence,type,parameters,filter_graph,encoder,device,duration_ms)"
                        + " values(?,?,?,?::jsonb,?,?,?,?)")
                .params(
                    runId,
                    ++sequence,
                    op.get("type"),
                    write(op.getOrDefault("parameters", Map.of())),
                    Objects.toString(op.get("filterGraph"), ""),
                    op.get("encoder"),
                    op.get("device"),
                    op.get("durationMs"))
                .update();
          }
          for (String phase : List.of("raw", "processed", "master"))
            s.db
                .sql("insert into video_loop_analysis(run_id,phase,evidence) values(?,?,?::jsonb)")
                .params(runId, phase, write(map(evidence.get(phase)).get("loop")))
                .update();
          s.db
              .sql(
                  "insert into video_quality_results(production_id,run_id,asset_id,evidence,type)"
                      + " values(?,?,?,?::jsonb,'PROCESSED')")
              .params(id, runId, raw.get("id"), write(evidence.get("master")))
              .update();
          s.db
              .sql(
                  "update video_processing_runs set"
                      + " status='COMPLETED',evidence=?::jsonb,ffmpeg_version=?,duration_ms=?,input_bytes=?,output_bytes=?,completed_at=now()"
                      + " where id=?")
              .params(
                  write(evidence),
                  evidence.get("ffmpegVersion"),
                  evidence.get("durationMs"),
                  evidence.get("inputBytes"),
                  evidence.get("outputBytes"),
                  runId)
              .update();
          s.db
              .sql("update video_productions set master_variant_id=? where id=?")
              .params(ids.get("MASTER_VIDEO"), id)
              .update();
          s.move(fresh, "REVIEW", "");
        });
  }

  public void close() {
    pool.shutdownNow();
  }
}
