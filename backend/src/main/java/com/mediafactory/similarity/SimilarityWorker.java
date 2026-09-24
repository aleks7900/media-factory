package com.mediafactory.similarity;

import static com.mediafactory.similarity.SimilarityService.JSON;

import com.mediafactory.provider.ImageGenerationProperties;
import com.mediafactory.provider.resilience.*;
import com.mediafactory.storage.MediaStorage;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable leases and idempotent immutable writes. Inference/storage never run in a transaction. */
@Component
@ConditionalOnProperty(name = "media.worker.enabled", havingValue = "true", matchIfMissing = true)
public class SimilarityWorker {
  private final SimilarityService service;
  private final MediaStorage storage;
  private final ProviderRateLimiter limiter;
  private final RetryDecisionService retry;
  private final CollectionClusteringService clustering;
  private final ImageGenerationProperties.RateLimit rate =
      new ImageGenerationProperties.RateLimit(120, 1);
  private final ImageGenerationProperties.Retry retryPolicy =
      new ImageGenerationProperties.Retry(
          4, Duration.ofSeconds(3), Duration.ofSeconds(60), 2, true, false);
  private final ImageGenerationProperties.Circuit circuit =
      new ImageGenerationProperties.Circuit(3, Duration.ofSeconds(60));

  public SimilarityWorker(
      SimilarityService service,
      MediaStorage storage,
      ProviderRateLimiter limiter,
      RetryDecisionService retry,
      CollectionClusteringService clustering) {
    this.service = service;
    this.storage = storage;
    this.limiter = limiter;
    this.retry = retry;
    this.clustering = clustering;
  }

  @Scheduled(fixedDelayString = "${media.similarity.poll-delay-ms:1500}")
  public void tick() {
    if (!service.enabled()) return;
    try {
      recover();
      var jobs = claim();
      if (jobs.isEmpty()) return;
      if (jobs.getFirst().get("type").equals("GENERATE_ASSET_EMBEDDING")) execute(jobs);
      else executeControl(jobs.getFirst());
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .error("Similarity dispatch failed: {}", e.getClass().getSimpleName());
    }
  }

  public List<Map<String, Object>> claim() {
    return service
        .tx()
        .execute(
            s -> {
              var first =
                  service
                      .db()
                      .sql(
                          "select * from similarity_jobs where status='QUEUED' and"
                              + " available_at<=now() order by case when"
                              + " type='GENERATE_ASSET_EMBEDDING' then 0 else 1 end,created_at for"
                              + " update skip locked limit 1")
                      .query()
                      .listOfRows();
              if (first.isEmpty()) return List.of();
              var row = first.getFirst();
              var jobs =
                  row.get("type").equals("GENERATE_ASSET_EMBEDDING")
                      ? service
                          .db()
                          .sql(
                              "select * from similarity_jobs where status='QUEUED' and"
                                  + " available_at<=now() and type='GENERATE_ASSET_EMBEDDING' and"
                                  + " model_id=? order by created_at for update skip locked limit"
                                  + " 8")
                          .param(row.get("model_id"))
                          .query()
                          .listOfRows()
                      : first;
              var result = new ArrayList<Map<String, Object>>();
              for (var j : jobs) {
                UUID lease = UUID.randomUUID();
                service
                    .db()
                    .sql(
                        "update similarity_jobs set"
                            + " status='RUNNING',lease_token=?,locked_at=now(),updated_at=now()"
                            + " where id=?")
                    .params(lease, j.get("id"))
                    .update();
                var copy = new HashMap<>(j);
                copy.put("lease_token", lease);
                result.add(copy);
              }
              return result;
            });
  }

  public void execute(List<Map<String, Object>> jobs) {
    var model = service.model((UUID) jobs.getFirst().get("model_id"));
    UUID permit = null;
    long started = System.nanoTime();
    var startedJobs = new ArrayList<Map<String, Object>>();
    try {
      var admission =
          limiter.acquireSimilarity(
              "embedding:" + model.provider(),
              (UUID) jobs.getFirst().get("id"),
              rate,
              Duration.ofMinutes(15));
      if (!admission.acquired()) {
        jobs.forEach(
            j -> reschedule(j, admission.waitFor(), "Waiting for embedding capacity", false));
        return;
      }
      permit = admission.permit();
      var batch = new ArrayList<ImageEmbeddingProvider.Input>();
      var batchJobs = new ArrayList<Map<String, Object>>();
      int bytes = 0;
      for (var job : jobs) {
        if (!start(job, model)) continue;
        startedJobs.add(job);
        UUID asset = (UUID) job.get("asset_id");
        service.analyzeExact(asset, model); // authoritative evidence survives an embedding outage
        byte[] data;
        try {
          data = storage.read(service.asset(asset).get("storage_key").toString());
          if (data.length > 25 * 1024 * 1024)
            throw new IllegalArgumentException("Asset exceeds embedding byte limit");
          var fingerprint = new PerceptualHash().extract(data);
          if (!fingerprint.sha256().equals(service.asset(asset).get("sha256")))
            throw new IllegalArgumentException("Stored asset checksum mismatch");
          service.fingerprints(asset, fingerprint);
        } catch (RuntimeException failure) {
          fail(
              job,
              new ImageGenerationException(
                  failure instanceof IllegalArgumentException
                      ? ImageGenerationException.Type.INVALID_REQUEST
                      : ImageGenerationException.Type.UNAVAILABLE,
                  "Asset feature preparation failed: " + failure.getClass().getSimpleName()));
          service
              .metrics()
              .counter(
                  "media_factory_embedding_failures_total",
                  "provider",
                  model.provider(),
                  "model",
                  model.model(),
                  "version",
                  model.version())
              .increment();
          continue;
        }
        if (bytes + data.length > 32 * 1024 * 1024 && !batch.isEmpty()) {
          infer(batch, batchJobs, model);
          batch.clear();
          batchJobs.clear();
          bytes = 0;
        }
        batch.add(new ImageEmbeddingProvider.Input(asset, data));
        batchJobs.add(job);
        bytes += data.length;
      }
      if (!batch.isEmpty()) infer(batch, batchJobs, model);
      limiter.observe("embedding:" + model.provider(), null, circuit);
    } catch (Exception error) {
      var failure =
          error instanceof ImageGenerationException classified
              ? classified
              : new ImageGenerationException(
                  error instanceof IllegalArgumentException
                      ? ImageGenerationException.Type.INVALID_REQUEST
                      : ImageGenerationException.Type.UNAVAILABLE,
                  "Embedding processing failed: " + error.getClass().getSimpleName());
      limiter.observe("embedding:" + model.provider(), failure, circuit);
      for (var job : jobs) {
        service
            .metrics()
            .counter(
                "media_factory_embedding_failures_total",
                "provider",
                model.provider(),
                "model",
                model.model(),
                "version",
                model.version())
            .increment();
        fail(job, failure);
      }
    } finally {
      limiter.release(permit);
      service
          .metrics()
          .timer(
              "media_factory_embedding_duration",
              "provider",
              model.provider(),
              "model",
              model.model(),
              "version",
              model.version())
          .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }
  }

  private boolean start(Map<String, Object> job, ImageEmbeddingProvider.Model model) {
    return Boolean.TRUE.equals(
        service
            .tx()
            .execute(
                s -> {
                  if (!owns(job)) return false;
                  int attempt =
                      service
                          .db()
                          .sql(
                              "update similarity_jobs set attempts=attempts+1 where id=? returning"
                                  + " attempts")
                          .param(job.get("id"))
                          .query(Integer.class)
                          .single();
                  job.put("attempts", attempt);
                  service
                      .db()
                      .sql(
                          "insert into"
                              + " embedding_compute_usage(id,job_id,attempt,provider,model,version,input_usage,outcome)"
                              + " values(?,?,?,?,?,?,1,'STARTED')")
                      .params(
                          UUID.randomUUID(),
                          job.get("id"),
                          attempt,
                          model.provider(),
                          model.model(),
                          model.version())
                      .update();
                  return true;
                }));
  }

  private void infer(
      List<ImageEmbeddingProvider.Input> inputs,
      List<Map<String, Object>> jobs,
      ImageEmbeddingProvider.Model model) {
    var result = service.provider(model).embed(List.copyOf(inputs), model);
    if (result.vectors().size() != inputs.size())
      throw new IllegalArgumentException("Incomplete provider batch");
    // Commit the whole batch before retrieval so its members can find each other.
    service
        .tx()
        .executeWithoutResult(
            s -> {
              for (int i = 0; i < jobs.size(); i++)
                if (owns(jobs.get(i)))
                  service.persistEmbedding(
                      inputs.get(i).assetId(), model, result.vectors().get(i), result.metadata());
            });
    for (int i = 0; i < jobs.size(); i++) {
      var job = jobs.get(i);
      service.analyze(inputs.get(i).assetId(), model);
      service
          .tx()
          .executeWithoutResult(
              s -> {
                if (!owns(job)) return;
                service
                    .db()
                    .sql(
                        "update embedding_compute_usage set"
                            + " outcome='SUCCEEDED',output_usage=?,duration_ms=?,device=? where"
                            + " job_id=? and attempt=?")
                    .params(
                        model.dimension(),
                        result.metadata().getOrDefault("durationMs", 0),
                        result.metadata().getOrDefault("device", "unknown"),
                        job.get("id"),
                        job.get("attempts"))
                    .update();
                complete(job, result.metadata());
              });
      service
          .metrics()
          .counter(
              "media_factory_embeddings_total",
              "provider",
              model.provider(),
              "model",
              model.model(),
              "version",
              model.version())
          .increment();
    }
  }

  private boolean owns(Map<String, Object> job) {
    var row =
        service
            .db()
            .sql("select status,lease_token from similarity_jobs where id=? for update")
            .param(job.get("id"))
            .query()
            .singleRow();
    return "RUNNING".equals(row.get("status"))
        && Objects.equals(job.get("lease_token"), row.get("lease_token"));
  }

  private void complete(Map<String, Object> job, Map<String, Object> metadata) {
    service
        .db()
        .sql(
            "update similarity_jobs set"
                + " status='SUCCEEDED',lease_token=null,finished_at=now(),updated_at=now(),failure_reason=null,provider_metadata=cast(?"
                + " as jsonb) where id=? and lease_token=?")
        .params(JSON.writeValueAsString(metadata), job.get("id"), job.get("lease_token"))
        .update();
  }

  private void reschedule(Map<String, Object> job, Duration delay, String reason, boolean failed) {
    service
        .db()
        .sql(
            "update similarity_jobs set status=?,lease_token=null,available_at=now()+(?*interval '1"
                + " millisecond'),failure_reason=?,updated_at=now(),finished_at=case when ? then"
                + " now() else null end where id=? and lease_token=? and status='RUNNING'")
        .params(
            failed ? "FAILED" : "QUEUED",
            delay.toMillis(),
            reason,
            failed,
            job.get("id"),
            job.get("lease_token"))
        .update();
  }

  private void fail(Map<String, Object> job, ImageGenerationException error) {
    service
        .tx()
        .executeWithoutResult(
            s -> {
              if (!owns(job)) return;
              int attempts = ((Number) job.get("attempts")).intValue();
              var decision =
                  retry.decide(
                      error,
                      attempts,
                      new ImageGenerationProperties.Retry(
                          ((Number) job.get("max_attempts")).intValue(),
                          retryPolicy.initialDelay(),
                          retryPolicy.maxDelay(),
                          retryPolicy.multiplier(),
                          retryPolicy.jitter(),
                          false),
                      true,
                      false);
              service
                  .db()
                  .sql(
                      "update embedding_compute_usage set outcome='FAILED' where job_id=? and"
                          + " attempt=? and outcome='STARTED'")
                  .params(job.get("id"), attempts)
                  .update();
              reschedule(job, decision.delay(), error.getMessage(), !decision.retry());
            });
  }

  public void recover() {
    service
        .tx()
        .executeWithoutResult(
            s -> {
              for (var job :
                  service
                      .db()
                      .sql(
                          "select * from similarity_jobs where status='RUNNING' and"
                              + " locked_at<now()-interval '15 minutes' for update skip locked")
                      .query()
                      .listOfRows()) {
                service
                    .db()
                    .sql("delete from provider_permits where similarity_job_id=?")
                    .param(job.get("id"))
                    .update();
                fail(
                    job,
                    new ImageGenerationException(
                        ImageGenerationException.Type.UNAVAILABLE,
                        "Embedding lease expired; local inference is safe to retry"));
              }
            });
  }

  public void executeControl(Map<String, Object> job) {
    try {
      String type = job.get("type").toString();
      if (type.equals("CLUSTER_COLLECTION") || type.equals("ANALYZE_COLLECTION_DIVERSITY")) {
        var run =
            clustering.cluster(
                (UUID) job.get("collection_id"), service.model((UUID) job.get("model_id")), .16, 3);
        complete(job, Map.of("runId", run));
        return;
      }
      UUID collection = (UUID) job.get("collection_id"), cursor = (UUID) job.get("cursor_id");
      var rows =
          service
              .db()
              .sql(
                  "select a.id from assets a join generations g on g.id=a.generation_id join"
                      + " concepts c on c.id=g.concept_id where (cast(? as uuid) is null or"
                      + " c.collection_id=?) and (cast(? as uuid) is null or a.id>?) order by a.id"
                      + " limit 500")
              .params(collection, collection, cursor, cursor)
              .query(UUID.class)
              .list();
      service
          .tx()
          .executeWithoutResult(
              s -> {
                if (!owns(job)) return;
                for (UUID id : rows)
                  service.enqueue(id, (UUID) job.get("model_id"), (UUID) job.get("id"));
                if (!rows.isEmpty())
                  service
                      .db()
                      .sql("update similarity_jobs set cursor_id=?,progress=progress+? where id=?")
                      .params(rows.getLast(), rows.size(), job.get("id"))
                      .update();
              });
      if (rows.isEmpty()) {
        // Assets created during a non-active-model backfill can sort before the UUID cursor.
        // Repair missing jobs with an indexed anti-join instead of waiting forever for nonexistent
        // children.
        for (UUID missing :
            service
                .db()
                .sql(
                    "select a.id from assets a join generations g on g.id=a.generation_id join"
                        + " concepts c on c.id=g.concept_id where (cast(? as uuid) is null or"
                        + " c.collection_id=?) and not exists(select 1 from similarity_jobs j where"
                        + " j.asset_id=a.id and j.model_id=?) order by a.id limit 500")
                .params(collection, collection, job.get("model_id"))
                .query(UUID.class)
                .list()) service.enqueue(missing, (UUID) job.get("model_id"), (UUID) job.get("id"));
        int remaining =
            service
                .db()
                .sql(
                    "select count(*) from assets a join generations g on g.id=a.generation_id join"
                        + " concepts c on c.id=g.concept_id where (cast(? as uuid) is null or"
                        + " c.collection_id=?) and not exists(select 1 from similarity_jobs j where"
                        + " j.asset_id=a.id and j.model_id=? and j.type='GENERATE_ASSET_EMBEDDING'"
                        + " and j.status='SUCCEEDED')")
                .params(collection, collection, job.get("model_id"))
                .query(Integer.class)
                .single();
        int failed =
            service
                .db()
                .sql(
                    "select count(*) from similarity_jobs j join assets a on a.id=j.asset_id join"
                        + " generations g on g.id=a.generation_id join concepts c on"
                        + " c.id=g.concept_id where j.model_id=? and j.status='FAILED' and (cast(?"
                        + " as uuid) is null or c.collection_id=?)")
                .params(job.get("model_id"), collection, collection)
                .query(Integer.class)
                .single();
        if (remaining == 0) complete(job, Map.of("coverage", "complete"));
        else
          reschedule(
              job,
              Duration.ofSeconds(3),
              failed > 0
                  ? "Child embedding failures require retry"
                  : "Waiting for child embeddings",
              failed > 0);
      } else reschedule(job, Duration.ofMillis(200), "Backfill page queued", false);
    } catch (Exception e) {
      int count =
          service
              .db()
              .sql("update similarity_jobs set attempts=attempts+1 where id=? returning attempts")
              .param(job.get("id"))
              .query(Integer.class)
              .single();
      job.put("attempts", count);
      fail(
          job,
          new ImageGenerationException(
              ImageGenerationException.Type.UNAVAILABLE,
              "Similarity background operation failed: " + e.getClass().getSimpleName()));
    }
  }
}
