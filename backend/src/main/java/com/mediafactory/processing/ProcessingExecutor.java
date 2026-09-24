package com.mediafactory.processing;

import static com.mediafactory.processing.ProcessingJson.*;

import com.mediafactory.similarity.PerceptualHash;
import com.mediafactory.storage.MediaStorage;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable DAG execution. I/O is outside transactions; completion is fenced by a lease token. */
@Component
public class ProcessingExecutor {
  private final JdbcClient db;
  private final TransactionTemplate tx;
  private final MediaStorage storage;
  private final ProcessingProvider provider;
  private final MeterRegistry metrics;
  private final ProcessingService service;
  private final PostProcessingQa visualQa;
  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(ProcessingExecutor.class);

  public ProcessingExecutor(
      JdbcClient db,
      TransactionTemplate tx,
      MediaStorage storage,
      ProcessingProvider provider,
      MeterRegistry metrics,
      ProcessingService service,
      PostProcessingQa visualQa) {
    this.db = db;
    this.tx = tx;
    this.storage = storage;
    this.provider = provider;
    this.metrics = metrics;
    this.service = service;
    this.visualQa = visualQa;
  }

  public Optional<Map<String, Object>> claim() {
    return tx.execute(
        s -> {
          db.sql("select pg_advisory_xact_lock(604060)").query().singleRow();
          db.sql("insert into processing_manifests(run_id,attempt,manifest) select id,attempt,jsonb_build_object('plan',plan,'status','LEASE_EXPIRED','attempt',attempt) from processing_runs where status='RUNNING' and lease_until<now() on conflict(run_id,attempt) do nothing").update();
          db.sql("update processing_compute_usage u set outcome='FAILED',metadata=u.metadata||'{\"failureCode\":\"LEASE_EXPIRED\"}'::jsonb where outcome='STARTED' and exists(select 1 from processing_runs r where r.id=u.run_id and r.status='RUNNING' and r.lease_until<now())").update();
          db.sql(
                  "update processing_runs set status=case when attempt<max_attempts then 'PENDING'"
                      + " else 'FAILED' end,failure_code='LEASE_EXPIRED',lease_token=null where"
                      + " status='RUNNING' and lease_until<now()")
              .update();
          if (db.sql("select exists(select 1 from processing_runs where status='RUNNING')")
              .query(Boolean.class)
              .single()) return Optional.empty();
          var next =
              db.sql(
                      "select id from processing_runs where status='PENDING' and"
                          + " available_at<=now() and not cancel_requested order by priority"
                          + " desc,created_at for update skip locked limit 1")
                  .query(UUID.class)
                  .optional();
          if (next.isEmpty()) return Optional.empty();
          return db
              .sql(
                  "update processing_runs set"
                      + " status='RUNNING',attempt=attempt+1,lease_token=?,lease_until=now()+interval"
                      + " '2 minutes',started_at=coalesce(started_at,now()) where id=? returning *")
              .params(UUID.randomUUID(), next.get())
              .query()
              .listOfRows()
              .stream()
              .findFirst();
        });
  }

  public void heartbeat(UUID id, UUID token) {
    db.sql(
            "update processing_runs set lease_until=now()+interval '2 minutes' where id=? and"
                + " lease_token=? and status='RUNNING'")
        .params(id, token)
        .update();
  }

  private void fence(UUID id, UUID token) {
    var row =
        db
            .sql(
                "select cancel_requested from processing_runs where id=? and lease_token=? and"
                    + " status='RUNNING' and lease_until>now() for update")
            .params(id, token)
            .query()
            .listOfRows()
            .stream()
            .findFirst();
    if (row.isEmpty()) throw new ProcessingFailure("LEASE_LOST");
    if (Boolean.TRUE.equals(row.get().get("cancel_requested")))
      throw new ProcessingFailure("CANCELLED");
  }

  private void check(UUID id, UUID token) {
    tx.executeWithoutResult(s -> fence(id, token));
  }

  @SuppressWarnings("unchecked")
  public void execute(Map<String, Object> run) {
    UUID id = (UUID) run.get("id"),
        assetId = (UUID) run.get("source_asset_id"),
        token = (UUID) run.get("lease_token");
    long start = System.nanoTime();
    boolean retry = false;
    String failure = null;
    var plan = map(run.get("plan"));
    var nodes = (List<Map<String, Object>>) plan.get("nodes");
    LOG.info("processing_started run={} asset={} attempt={}", id, assetId, run.get("attempt"));
    try {
      var asset = service.asset(assetId);
      byte[] original = storage.read(asset.get("storage_key").toString());
      if (!PerceptualHash.sha(original).equals(plan.get("sourceChecksum")))
        throw new ProcessingFailure("SOURCE_CHECKSUM_MISMATCH");
      if (!"APPROVED".equals(asset.get("final_decision")))
        throw new ProcessingFailure("SOURCE_NOT_APPROVED");
      var artifacts = new HashMap<String, Map<String, Object>>();
      for (var node : nodes) {
        check(id, token);
        String nodeKey = node.get("key").toString(), operation = node.get("operation").toString();
        UUID stepId = UUID.randomUUID();
        db.sql(
                "insert into processing_steps(id,run_id,node_key,operation,parameters)"
                    + " values(?,?,?,?,?::jsonb) on conflict(run_id,node_key) do nothing")
            .params(stepId, id, nodeKey, operation, write(node))
            .update();
        var step =
            db.sql("select * from processing_steps where run_id=? and node_key=?")
                .params(id, nodeKey)
                .query()
                .singleRow();
        stepId = (UUID) step.get("id");
        if (operation.equals("UPSCALE") && integer(node, "scale", 1) == 1) {
          db.sql(
                  "update processing_steps set"
                      + " status='SKIPPED',skip_reason='SOURCE_ALREADY_MEETS_REQUIREMENTS',completed_at=now()"
                      + " where id=?")
              .param(stepId)
              .update();
          continue;
        }
        List<String> deps = (List<String>) node.get("dependsOn");
        var parent = deps.isEmpty() ? null : artifacts.get(deps.getFirst());
        if (!deps.isEmpty() && parent == null) {
          failStep(stepId, "DEPENDENCY_FAILED");
          continue;
        }
        var cacheInput = new LinkedHashMap<String, Object>();
        cacheInput.put("source", assetId.toString());
        cacheInput.put(
            "checksum", parent == null ? plan.get("sourceChecksum") : parent.get("sha256"));
        cacheInput.put("engine", plan.get("engineVersion"));
        cacheInput.put("node", node);
        String cacheKey = ProcessingPlanner.hash(cacheInput);
        UUID usageId=null;
        long operationStarted=0;
        try {
          var cached =
              db
                  .sql("select * from processing_artifacts where cache_key=?")
                  .param(cacheKey)
                  .query()
                  .listOfRows()
                  .stream()
                  .findFirst();
          if (cached.isPresent()) {
            byte[] bytes = storage.read(cached.get().get("storage_key").toString());
            if (!PerceptualHash.sha(bytes).equals(cached.get().get("sha256")))
              throw new ProcessingFailure("CACHE_CHECKSUM_MISMATCH");
            artifacts.put(nodeKey, cached.get());
            db.sql(
                    "update processing_steps set"
                        + " status='SKIPPED',skip_reason='VALIDATED_ARTIFACT_REUSED',output_artifact_id=?,completed_at=now(),error_code=null,error_message=null"
                        + " where id=?")
                .params(cached.get().get("id"), stepId)
                .update();
            continue;
          }
          byte[] input =
              parent == null ? original : storage.read(parent.get("storage_key").toString());
          UUID parentId = parent == null ? null : (UUID) parent.get("id");
          db.sql(
                  "update processing_steps set"
                      + " status='RUNNING',started_at=now(),input_artifact_id=?,error_code=null,error_message=null"
                      + " where id=?")
              .params(parentId, stepId)
              .update();
          LOG.info(
              "processing_step_started run={} asset={} step={} operation={}",
              id,
              assetId,
              nodeKey,
              operation);
          var parameters = new LinkedHashMap<>(node);
          parameters.put("focalRegions", plan.getOrDefault("focalRegions",List.of()));
          usageId=UUID.randomUUID();operationStarted=System.nanoTime();
          db.sql("insert into processing_compute_usage(id,run_id,step_id,provider,model,operation,input_usage,output_usage,duration_ms,device,metadata,outcome) values(?,?,?,?,?,?,?,0,0,'unknown','{}','STARTED')")
              .params(usageId,id,stepId,operation.equals("UPSCALE")?"local-realesrgan":"local-pillow",operation.equals("UPSCALE")?node.get("model").toString().split(":")[0]:"deterministic",operation.equals("UPSCALE")?"IMAGE_UPSCALE":"IMAGE_PROCESSING",input.length).update();
          var output = provider.execute(id, input, parameters);
          db.sql("update processing_compute_usage set provider=?,model=?,output_usage=?,duration_ms=?,device=?,metadata=?::jsonb,outcome='SUCCEEDED' where id=?")
              .params(output.metadata().getOrDefault("provider","local"),output.metadata().getOrDefault("model","deterministic"),output.bytes().length,output.durationMs(),output.metadata().getOrDefault("device","cpu"),write(output.metadata()),usageId).update();
          if (!"VALID".equals(output.validation().get("status")))
            throw new ProcessingFailure("OUTPUT_VALIDATION_FAILED");
          if (operation.equals("UPSCALE")) {
            String identity =
                output.metadata().get("model")
                    + ":"
                    + output.metadata().get("modelVersion")
                    + ":"
                    + output.metadata().get("modelChecksum");
            if (!identity.equals(node.get("model")))
              throw new ProcessingFailure("MODEL_IDENTITY_MISMATCH");
            int factor = integer(node, "scale", 1);
            if (integer(output.validation(), "width", 0) != integer(plan, "sourceWidth", 0) * factor
                || integer(output.validation(), "height", 0)
                    != integer(plan, "sourceHeight", 0) * factor)
              throw new ProcessingFailure("UPSCALE_DIMENSION_MISMATCH");
          }
          if (operation.equals("DERIVE")
              && Boolean.TRUE.equals(map(node.get("profile")).get("visualQa")))
            output = visualQa.inspect(id, stepId, asset, output, map(node.get("profile")));
          String sha = PerceptualHash.sha(output.bytes()),
              format = output.validation().get("format").toString();
          int width = integer(output.validation(), "width", 0),
              height = integer(output.validation(), "height", 0);
          ProcessingPlanner.dimensions(width, height);
          UUID artifactId = UUID.randomUUID();
          String path =
              "assets/processed/"
                  + assetId
                  + "/"
                  + id
                  + "/"
                  + artifactId
                  + "."
                  + format.toLowerCase();
          storage.putOriginal(path, output.bytes(), "image/" + format.toLowerCase());
          UUID finalStepId = stepId;
          var finalOutput = output;
          Map<String, Object> artifact =
              tx.execute(
                  s -> {
                    fence(id, token);
                    var saved =
                        db.sql(
                                "insert into"
                                    + " processing_artifacts(id,source_asset_id,parent_artifact_id,run_id,cache_key,operation,storage_key,sha256,width,height,size_bytes,format,metadata)"
                                    + " values(?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb) returning *")
                            .params(
                                artifactId,
                                assetId,
                                parentId,
                                id,
                                cacheKey,
                                operation,
                                path,
                                sha,
                                width,
                                height,
                                finalOutput.bytes().length,
                                format,
                                write(finalOutput.metadata()))
                            .query()
                            .singleRow();
                    db.sql(
                            "insert into"
                                + " processing_validation_results(artifact_id,run_id,status,findings)"
                                + " values(?,?,'VALID',?::jsonb)")
                        .params(artifactId, id, write(finalOutput.validation()))
                        .update();
                    db.sql(
                            "update processing_steps set"
                                + " status='COMPLETED',output_artifact_id=?,metadata=?::jsonb,duration_ms=?,completed_at=now()"
                                + " where id=?")
                        .params(
                            artifactId,
                            write(finalOutput.metadata()),
                            finalOutput.durationMs(),
                            finalStepId)
                        .update();
                    var stages =
                        (List<Map<String, Object>>)
                            finalOutput.metadata().getOrDefault("stages", List.of());
                    for (var stage : stages)
                      db.sql(
                              "insert into"
                                  + " processing_steps(id,run_id,node_key,operation,parameters,status,input_artifact_id,output_artifact_id,metadata,duration_ms,completed_at)"
                                  + " values(?,?,?,?,?::jsonb,'COMPLETED',?,?,?::jsonb,?,now()) on"
                                  + " conflict(run_id,node_key) do update set"
                                  + " status='COMPLETED',metadata=excluded.metadata,duration_ms=excluded.duration_ms,completed_at=now()")
                          .params(
                              UUID.randomUUID(),
                              id,
                              nodeKey + ":" + stage.get("type"),
                              stage.get("type"),
                              write(node),
                              parentId,
                              artifactId,
                              write(stage),
                              stage.get("durationMs"))
                          .update();
                    if (operation.equals("DERIVE"))
                      db.sql(
                              "insert into"
                                  + " asset_variants(id,asset_id,kind,storage_key,sha256,size_bytes,source_asset_id,parent_artifact_id,processing_run_id,profile_version_id,artifact_id,width,height,format,validation_status)"
                                  + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'VALID') on"
                                  + " conflict(artifact_id) do nothing")
                          .params(
                              UUID.randomUUID(),
                              assetId,
                              nodeKey,
                              path,
                              sha,
                              finalOutput.bytes().length,
                              assetId,
                              parentId,
                              id,
                              UUID.fromString(node.get("profileVersionId").toString()),
                              artifactId,
                              width,
                              height,
                              format)
                          .update();
                    return saved;
                  });
          artifacts.put(nodeKey, artifact);
          metrics.counter("media_factory_processing_output_bytes").increment(output.bytes().length);
          if (operation.equals("UPSCALE")) {
            metrics.counter("media_factory_upscale_total").increment();
            metrics
                .timer("media_factory_upscale_duration")
                .record(output.durationMs(), TimeUnit.MILLISECONDS);
          }
          if (integer(output.metadata(), "oomRetries", 0) > 0)
            metrics
                .counter("media_factory_gpu_oom_total")
                .increment(integer(output.metadata(), "oomRetries", 0));
          LOG.info(
              "processing_step_completed run={} asset={} step={} artifact={} durationMs={}",
              id,
              assetId,
              nodeKey,
              artifactId,
              output.durationMs());
        } catch (Exception e) {
          if(usageId!=null)db.sql("update processing_compute_usage set outcome='FAILED',duration_ms=? where id=?").params((System.nanoTime()-operationStarted)/1000000,usageId).update();
          ProcessingFailure error =
              e instanceof ProcessingFailure p ? p : new ProcessingFailure("STORAGE_UNAVAILABLE");
          if (Set.of("CANCELLED", "LEASE_LOST").contains(error.code())) throw error;
          failStep(stepId, error.code());
          retry |= error.retryable();
          failure = error.code();
          if (error.code().equals("GPU_OOM"))
            metrics.counter("media_factory_gpu_oom_total").increment();
          LOG.warn(
              "processing_step_failed run={} asset={} step={} code={}",
              id,
              assetId,
              nodeKey,
              error.code());
        }
      }
    } catch (Exception e) {
      failure = e instanceof ProcessingFailure p ? p.code() : "STORAGE_UNAVAILABLE";
      retry = e instanceof ProcessingFailure p ? p.retryable() : true;
    }
    String reason = failure;
    boolean shouldRetry = retry;
    tx.executeWithoutResult(
        s -> {
          var current =
              db
                  .sql(
                      "select * from processing_runs where id=? and lease_token=? and"
                          + " status='RUNNING' for update")
                  .params(id, token)
                  .query()
                  .listOfRows()
                  .stream()
                  .findFirst();
          if (current.isEmpty()) return;
          long completed =
              db.sql(
                      "select count(*) from processing_steps where run_id=? and operation='DERIVE'"
                          + " and status in ('COMPLETED','SKIPPED')")
                  .param(id)
                  .query(Long.class)
                  .single();
          long total = nodes.stream().filter(n -> n.get("operation").equals("DERIVE")).count();
          String status =
              Boolean.TRUE.equals(current.get().get("cancel_requested"))
                      || "CANCELLED".equals(reason)
                  ? "CANCELLED"
                  : completed == total
                      ? "COMPLETED"
                      : completed > 0 ? "PARTIALLY_COMPLETED" : "FAILED";
          var steps =
              db.sql("select * from processing_steps where run_id=? order by node_key")
                  .param(id)
                  .query()
                  .listOfRows();
          db.sql(
                  "insert into processing_manifests(run_id,attempt,manifest) values(?,?,?::jsonb)"
                      + " on conflict(run_id,attempt) do nothing")
              .params(
                  id,
                  run.get("attempt"),
                  write(
                      Map.of(
                          "runId",
                          id,
                          "plan",
                          plan,
                          "steps",
                          steps,
                          "status",
                          status,
                          "actor",
                          "local-workspace")))
              .update();
          boolean again =
              shouldRetry
                  && !status.equals("CANCELLED")
                  && ((Number) run.get("attempt")).intValue()
                      < ((Number) run.get("max_attempts")).intValue();
          db.sql(
                  "update processing_runs set"
                      + " status=?,failure_code=?,failure_reason=?,completed_at=now(),lease_token=null,lease_until=null,available_at=now()+(?*interval"
                      + " '10 seconds') where id=?")
              .params(
                  again ? "PENDING" : status,
                  reason,
                  reason,
                  ((Number) run.get("attempt")).intValue(),
                  id)
              .update();
          metrics.counter("media_factory_processing_total", "status", status).increment();
          if (!status.equals("COMPLETED"))
            metrics.counter("media_factory_processing_failed_total").increment();
          LOG.info(
              "processing_completed run={} asset={} status={} completed={} total={}",
              id,
              assetId,
              status,
              completed,
              total);
        });
    metrics
        .timer("media_factory_processing_duration")
        .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
  }

  private void failStep(UUID step, String code) {
    db.sql(
            "update processing_steps set"
                + " status='FAILED',error_code=?,error_message=?,completed_at=now() where id=?")
        .params(code, code, step)
        .update();
  }
}
