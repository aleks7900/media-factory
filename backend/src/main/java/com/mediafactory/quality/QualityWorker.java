package com.mediafactory.quality;

import static com.mediafactory.quality.QualityReviewService.JSON;

import com.mediafactory.provider.resilience.ImageGenerationException;
import com.mediafactory.provider.resilience.ProviderRateLimiter;
import com.mediafactory.provider.resilience.RetryDecisionService;
import com.mediafactory.quality.QualityModels.Decision;
import com.mediafactory.quality.QualityModels.Dimension;
import com.mediafactory.quality.QualityModels.DimensionName;
import com.mediafactory.quality.QualityModels.Evaluation;
import com.mediafactory.quality.QualityModels.Finding;
import com.mediafactory.quality.QualityModels.Severity;
import com.mediafactory.storage.MediaStorage;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable bounded dispatch. Storage reads, pixel work and provider calls happen outside
 * transactions.
 */
@Component
@ConditionalOnProperty(name = "media.worker.enabled", havingValue = "true", matchIfMissing = true)
public class QualityWorker implements AutoCloseable {

  private final JdbcClient db;
  private final TransactionTemplate tx;
  private final QualityReviewService reviews;
  private final QaConfiguration config;
  private final TechnicalQa technical;
  private final QualityPolicyEngine engine;
  private final MediaStorage storage;
  private final ProviderRateLimiter limiter;
  private final RetryDecisionService retry;
  private final Map<String, VisionQualityProvider> providers;
  private final MeterRegistry metrics;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final AtomicInteger running = new AtomicInteger();

  public QualityWorker(JdbcClient db, TransactionTemplate tx, QualityReviewService reviews,
      QaConfiguration config, TechnicalQa technical, QualityPolicyEngine engine,
      MediaStorage storage, ProviderRateLimiter limiter, RetryDecisionService retry,
      List<VisionQualityProvider> providers, MeterRegistry metrics) {
    this.db = db;
    this.tx = tx;
    this.reviews = reviews;
    this.config = config;
    this.technical = technical;
    this.engine = engine;
    this.storage = storage;
    this.limiter = limiter;
    this.retry = retry;
    this.metrics = metrics;
    var map = new HashMap<String, VisionQualityProvider>();
    providers.forEach(p -> map.put(p.providerId(), p));
    this.providers = Map.copyOf(map);
  }

  private static long elapsed(long start) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  @Scheduled(fixedDelayString = "${media.qa.poll-delay-ms:1000}")
  public void tick() {
    try {
      recover();
      while (running.get() < config.concurrency()) {
        var job = claim();
        if (job == null) {
          break;
        }
        running.incrementAndGet();
        executor.submit(() -> {
          try {
            execute(job);
          } finally {
            running.decrementAndGet();
          }
        });
      }
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .error("QA dispatch failed; inspect infrastructure health");
    }
  }

  @PreDestroy
  public void close() {
    executor.shutdownNow();
  }

  public Map<String, Object> claim() {
    return tx.execute(s -> {
      var rows = db.sql(
              "select * from qa_jobs where status='QUEUED' and available_at<=now() order by created_at for update skip locked limit 1")
          .query().listOfRows();
      if (rows.isEmpty()) {
        return null;
      }
      var job = rows.getFirst();
      UUID token = UUID.randomUUID();
      db.sql(
              "update qa_jobs set status='RUNNING',locked_at=now(),lease_token=?,execution_started=false,updated_at=now() where id=?")
          .params(token, job.get("id")).update();
      db.sql("update generations set status='QA_RUNNING',updated_at=now() where id=?")
          .param(job.get("generation_id")).update();
      db.sql(
              "update quality_reviews set execution_status='RUNNING',started_at=coalesce(started_at,now()) where id=?")
          .param(job.get("review_id")).update();
      return db.sql("select * from qa_jobs where id=?").param(job.get("id")).query().singleRow();
    });
  }

  public void execute(Map<String, Object> job) {
    if (db.sql(
            "update qa_jobs set execution_started=true where id=? and lease_token=? and status='RUNNING' and not execution_started")
        .params(job.get("id"), job.get("lease_token")).update() != 1) {
      return;
    }
    UUID reviewId = (UUID) job.get("review_id"), attemptId = null, permit = null;
    String provider = "unknown", model = "unknown";
    long start = System.nanoTime();
    int providerAttempt = ((Number) job.get("provider_attempts")).intValue() + 1;
    try {
      var review = reviews.review(reviewId);
      var policy = JSON.readValue(review.get("policy_snapshot").toString(), QaPolicy.class);
      var route = JSON.readTree(job.get("route").toString());
      int index = ((Number) job.get("route_index")).intValue();
      provider = route.get(index).path("provider").asText();
      model = route.get(index).path("model").asText();
      var adapter = providers.get(provider);
      if (adapter == null) {
        throw new ImageGenerationException(ImageGenerationException.Type.INVALID_REQUEST,
            "Vision provider unavailable in this deployment");
      }
      var asset = db.sql("select * from assets where id=?").param(review.get("asset_id")).query()
          .singleRow();
      @SuppressWarnings("unchecked") Map<String, Object> context = JSON.readValue(
          review.get("context_snapshot").toString(), Map.class);
      if (reviews.similarity().enabled() && "PENDING".equals(reviews.similarity()
          .state((UUID) asset.get("id"), reviews.similarity().activeModel().id()))) {
        reviews.similarity()
            .enqueue((UUID) asset.get("id"), reviews.similarity().activeModel().id(), null);
        schedule(job, Duration.ofSeconds(3), false, "Waiting for similarity analysis");
        return;
      }
      byte[] bytes = storage.read((String) asset.get("storage_key"));
      boolean duplicate = !reviews.similarity().enabled() && reviews.similarity()
          .exactDuplicate((UUID) asset.get("id"));
      var tech = technical.inspectStructured(bytes, asset.get("media_type").toString(),
          ((Number) context.get("expectedWidth")).intValue(),
          ((Number) context.get("expectedHeight")).intValue(), duplicate, policy);
      QualityReviewService.event("qa_started", reviewId, Map.of("policy", policy.id()));
      tx.executeWithoutResult(s -> {
        if (!owns(job)) {
          return;
        }
        db.sql("delete from quality_findings where review_id=? and source='TECHNICAL'")
            .param(reviewId).update();
        reviews.findings(reviewId, tech.findings());
        db.sql("update quality_reviews set technical_complete=true where id=?").param(reviewId)
            .update();
      });
      QualityReviewService.event("qa_technical_completed", reviewId, Map.of());
      // Undecodable media cannot be submitted to Vision; a deterministic content failure is complete evidence.
      if (tech.findings().stream()
          .anyMatch(f -> f.detected() && f.severity() == Severity.CRITICAL)) {
        finish(job, tech.findings(), tech.dimensions(),
            engine.evaluate(policy, tech.findings(), tech.dimensions(), true, false), false);
        return;
      }
      if (((Number) job.get("attempts")).intValue() >= ((Number) job.get(
          "max_attempts")).intValue()) {
        throw new ImageGenerationException(ImageGenerationException.Type.INVALID_REQUEST,
            "Vision call budget exhausted");
      }
      if (db.sql(
              "select count(*) from vision_qa_attempts a join qa_jobs j on j.id=a.job_id where j.generation_id=?")
          .param(job.get("generation_id")).query(Integer.class).single()
          >= config.maxGenerationCalls()) {
        throw new ImageGenerationException(ImageGenerationException.Type.INVALID_REQUEST,
            "Generation lifetime Vision call budget exhausted");
      }
      var admission = limiter.acquire("vision:" + provider, (UUID) job.get("id"), true,
          config.rate(), config.lease());
      if (!admission.acquired()) {
        schedule(job, admission.waitFor(), admission.circuitOpen() && index + 1 < route.size(),
            "Waiting for Vision capacity");
        return;
      }
      permit = admission.permit();
      attemptId = startAttempt(job, provider, model, (UUID) asset.get("id"));
      if (attemptId == null) {
        return;
      }
      metrics.counter("media_factory_vision_requests_total", "provider", provider, "model", model)
          .increment();
      QualityReviewService.event("qa_visual_started", reviewId,
          Map.of("provider", provider, "model", model));
      var result = adapter.analyze(
          new VisionQualityProvider.Request((UUID) asset.get("id"), (UUID) job.get("generation_id"),
              bytes, asset.get("media_type").toString(), model, (String) job.get("scenario"),
              context));
      result.evidence().validateVision();
      succeedAttempt(attemptId, result, elapsed(start));
      limiter.observe("vision:" + provider, null, config.circuit());
      var findings = new ArrayList<>(tech.findings());
      if (reviews.similarity().enabled()) {
        findings.addAll(reviews.similarity().qaFindings((UUID) asset.get("id")));
      }
      findings.addAll(result.evidence().findings());
      var dimensions = new ArrayList<>(result.evidence().dimensions());
      dimensions.removeIf(d -> d.dimension() == DimensionName.TECHNICAL_INTEGRITY);
      dimensions.addAll(tech.dimensions());
      var evaluation = engine.evaluate(policy, findings, dimensions, true, true);
      if (Boolean.TRUE.equals(context.get("legacyContextUnavailable"))
          && evaluation.automaticDecision() == Decision.APPROVED) {
        evaluation = new Evaluation(Decision.NEEDS_REVIEW, Decision.NEEDS_REVIEW,
            List.of("PROMPT_CONTEXT_UNAVAILABLE"));
      }
      finish(job, findings, dimensions, evaluation, true);
      QualityReviewService.event("qa_visual_completed", reviewId, Map.of("provider", provider));
    } catch (Exception failure) {
      var error = failure instanceof ImageGenerationException classified ? classified
          : new ImageGenerationException(ImageGenerationException.Type.UNEXPECTED,
              "QA execution failed; inspect infrastructure or provider configuration");
      if (attemptId != null) {
        failAttempt(attemptId, error, elapsed(start));
        limiter.observe("vision:" + provider, error, config.circuit());
        metrics.counter("media_factory_vision_failures_total", "provider", provider, "model", model,
            "type", error.type().name()).increment();
      }
      var route = JSON.readTree(job.get("route").toString());
      boolean hasFallback = ((Number) job.get("route_index")).intValue() + 1 < route.size();
      var decision = retry.decide(error, providerAttempt, config.retry(), provider.equals("mock"),
          hasFallback);
      int used = db.sql("select attempts from qa_jobs where id=?").param(job.get("id"))
          .query(Integer.class).single();
      if (used < ((Number) job.get("max_attempts")).intValue() && (decision.retry()
          || decision.fallback())) {
        schedule(job, decision.delay(), decision.fallback(), error.getMessage());
      } else {
        fail(job, error.getMessage());
      }
    } finally {
      limiter.release(permit);
    }
  }

  private UUID startAttempt(Map<String, Object> job, String provider, String model, UUID asset) {
    return tx.execute(s -> {
      if (!owns(job)) {
        return null;
      }
      UUID id = UUID.randomUUID();
      int n = db.sql(
              "update qa_jobs set attempts=attempts+1,provider_attempts=provider_attempts+1 where id=? returning attempts")
          .param(job.get("id")).query(Integer.class).single();
      db.sql(
              "insert into vision_qa_attempts(id,review_id,job_id,provider,model,attempt_number,status) values(?,?,?,?,?,?,'STARTED')")
          .params(id, job.get("review_id"), job.get("id"), provider, model, n).update();
      db.sql(
              "insert into generation_costs(id,generation_id,attempt,provider,model,operation,input_usage,output_usage,estimated_cost,currency,outcome,pricing_status,pricing_version,qa_attempt_id,review_id,asset_id) values(?,?,?,?,?,'VISUAL_QA',null,null,null,'USD','STARTED','UNKNOWN','pending',?,?,?)")
          .params(UUID.randomUUID(), job.get("generation_id"), n, provider, model, id,
              job.get("review_id"), asset).update();
      db.sql("update quality_reviews set vision_provider=?,vision_model=? where id=?")
          .params(provider, model, job.get("review_id")).update();
      return id;
    });
  }

  private void succeedAttempt(UUID id, VisionQualityProvider.Result result, long duration) {
    tx.executeWithoutResult(s -> {
      db.sql(
              "update vision_qa_attempts set status='SUCCEEDED',completed_at=now(),duration_ms=?,provider_request_id=?,metadata=cast(? as jsonb) where id=? and status='STARTED'")
          .params(duration, result.requestId(), JSON.writeValueAsString(result.metadata()), id)
          .update();
      db.sql(
              "update generation_costs set input_usage=?,output_usage=?,estimated_cost=?,currency=?,outcome='SUCCEEDED',pricing_status=?,pricing_version=? where qa_attempt_id=?")
          .params(result.inputUsage(), result.outputUsage(), result.estimatedCost(),
              result.currency(), result.estimatedCost() == null ? "UNKNOWN" : "ESTIMATED",
              result.pricingVersion(), id).update();
    });
    if (result.estimatedCost() != null) {
      metrics.counter("media_factory_vision_cost", "currency", result.currency())
          .increment(result.estimatedCost().doubleValue());
    }
  }

  private void failAttempt(UUID id, ImageGenerationException error, long duration) {
    tx.executeWithoutResult(s -> {
      db.sql(
              "update vision_qa_attempts set status='FAILED',completed_at=now(),duration_ms=?,error_type=?,failure_reason=? where id=? and status='STARTED'")
          .params(duration, error.type().name(), error.getMessage(), id).update();
      db.sql(
              "update generation_costs set outcome='FAILED' where qa_attempt_id=? and outcome='STARTED'")
          .param(id).update();
    });
  }

  private boolean owns(Map<String, Object> job) {
    var row = db.sql("select status,lease_token from qa_jobs where id=? for update")
        .param(job.get("id")).query().singleRow();
    return "RUNNING".equals(row.get("status")) && Objects.equals(row.get("lease_token"),
        job.get("lease_token"));
  }

  private void finish(Map<String, Object> job, List<Finding> findings, List<Dimension> dimensions,
      Evaluation evaluation, boolean visualComplete) {
    tx.executeWithoutResult(s -> {
      if (!owns(job)) {
        return;
      }
      UUID id = (UUID) job.get("review_id");
      db.sql("select id from generations where id=? for update").param(job.get("generation_id"))
          .query().singleRow();
      db.sql("delete from quality_findings where review_id=? and source<>'HUMAN'").param(id)
          .update();
      reviews.findings(id, findings);
      reviews.dimensions(id, dimensions);
      db.sql(
              "update quality_reviews set execution_status='COMPLETED',automatic_decision=?,final_decision=?,decision=?,rules_triggered=cast(? as jsonb),technical_complete=true,visual_complete=?,completed_at=now(),revision=revision+1 where id=?")
          .params(evaluation.automaticDecision().name(), evaluation.finalDecision().name(),
              evaluation.finalDecision().name(), JSON.writeValueAsString(evaluation.rules()),
              visualComplete, id).update();
      db.sql("update generations set status=?,updated_at=now() where id=?")
          .params(evaluation.finalDecision().name(), job.get("generation_id")).update();
      db.sql(
              "update qa_jobs set status='SUCCEEDED',lease_token=null,finished_at=now(),updated_at=now(),failure_reason=null where id=?")
          .param(job.get("id")).update();
      var review = reviews.review(id);
      String policy = review.get("policy_id").toString();
      metrics.counter("media_factory_qa_total", "policy", policy, "decision",
          evaluation.finalDecision().name()).increment();
      metrics.counter(
          "media_factory_qa_" + evaluation.finalDecision().name().toLowerCase(Locale.ROOT)
              + "_total", "policy", policy).increment();
      double seconds = db.sql(
              "select extract(epoch from(completed_at-started_at)) from quality_reviews where id=?")
          .param(id).query(Double.class).single();
      metrics.timer("media_factory_qa_duration", "policy", policy)
          .record(Duration.ofMillis((long) (seconds * 1000)));
      for (var f : findings) {
        if (f.detected()) {
          metrics.counter("media_factory_qa_findings_total", "code", f.code().name(), "severity",
              f.severity().name()).increment();
        }
      }
      QualityReviewService.event("qa_policy_decision", id,
          Map.of("automatic", evaluation.automaticDecision(), "final", evaluation.finalDecision(),
              "rules", evaluation.rules()));
    });
  }

  private void schedule(Map<String, Object> job, Duration delay, boolean fallback, String reason) {
    tx.executeWithoutResult(s -> {
      if (!owns(job)) {
        return;
      }
      db.sql(
              "update qa_jobs set status='QUEUED',lease_token=null,route_index=route_index+?,provider_attempts=case when ? then 0 else provider_attempts end,available_at=now()+(? * interval '1 millisecond'),failure_reason=?,updated_at=now() where id=?")
          .params(fallback ? 1 : 0, fallback, Math.max(1, delay.toMillis()), reason, job.get("id"))
          .update();
      db.sql("update quality_reviews set execution_status='PENDING' where id=?")
          .param(job.get("review_id")).update();
      db.sql("update generations set status='QA_PENDING',updated_at=now() where id=?")
          .param(job.get("generation_id")).update();
    });
  }

  private void fail(Map<String, Object> job, String reason) {
    tx.executeWithoutResult(s -> {
      if (!owns(job)) {
        return;
      }
      db.sql(
              "update qa_jobs set status='FAILED',lease_token=null,failure_reason=?,finished_at=now(),updated_at=now() where id=?")
          .params(reason, job.get("id")).update();
      db.sql(
              "update quality_reviews set execution_status='FAILED',automatic_decision=null,final_decision='NEEDS_REVIEW',decision='NEEDS_REVIEW',failure_reason=?,completed_at=now(),revision=revision+1 where id=?")
          .params(reason, job.get("review_id")).update();
      db.sql("update generations set status='NEEDS_REVIEW',updated_at=now() where id=?")
          .param(job.get("generation_id")).update();
      metrics.counter("media_factory_qa_execution_failed_total").increment();
    });
  }

  public void recover() {
    tx.executeWithoutResult(s -> {
      for (var job : db.sql(
              "select * from qa_jobs where status='RUNNING' and locked_at<now()-(? * interval '1 millisecond') for update skip locked")
          .param(config.lease().toMillis()).query().listOfRows()) {
        db.sql(
                "update vision_qa_attempts set status='FAILED',completed_at=now(),error_type='UNKNOWN_OUTCOME',failure_reason='QA worker lease expired' where job_id=? and status='STARTED'")
            .param(job.get("id")).update();
        db.sql(
                "update generation_costs set outcome='FAILED' where qa_attempt_id in(select id from vision_qa_attempts where job_id=?) and outcome='STARTED'")
            .param(job.get("id")).update();
        db.sql("delete from provider_permits where qa_job_id=?").param(job.get("id")).update();
        fail(job,
            "QA worker lease expired; rerun explicitly after reconciling unknown provider cost");
      }
    });
  }
}
