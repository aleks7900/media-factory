package com.mediafactory.service;

import com.mediafactory.domain.GenerationStatus;
import com.mediafactory.provider.ImageGenerationProperties;
import com.mediafactory.provider.ImageGenerationProvider;
import com.mediafactory.provider.ProviderObservability;
import com.mediafactory.provider.ProviderTypes.Media;
import com.mediafactory.provider.ProviderTypes.Request;
import com.mediafactory.provider.ProviderTypes.Result;
import com.mediafactory.provider.resilience.ImageGenerationException;
import com.mediafactory.provider.resilience.ImageGenerationException.Type;
import com.mediafactory.provider.resilience.ProviderRateLimiter;
import com.mediafactory.provider.resilience.RetryDecisionService;
import com.mediafactory.provider.routing.ImageProviderRouter;
import com.mediafactory.provider.routing.ProviderRoute;
import com.mediafactory.quality.TechnicalQa;
import com.mediafactory.storage.MediaStorage;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * One provider exchange per dispatch. Backoff is durable queue time, never a sleeping transaction.
 */
@Component
@ConditionalOnProperty(name = "media.worker.enabled", havingValue = "true", matchIfMissing = true)
public class GenerationWorker implements AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final JdbcClient db;
  private final TransactionTemplate tx;
  private final FactoryService service;
  private final ImageProviderRouter router;
  private final ImageGenerationProperties properties;
  private final ProviderRateLimiter limiter;
  private final RetryDecisionService retry;
  private final GenerationAttemptRepository attempts;
  private final ProviderObservability telemetry;
  private final MediaStorage storage;
  private final TechnicalQa qa;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final AtomicInteger running = new AtomicInteger();

  public GenerationWorker(JdbcClient db, TransactionTemplate tx, FactoryService service,
      ImageProviderRouter router, ImageGenerationProperties properties, ProviderRateLimiter limiter,
      RetryDecisionService retry, GenerationAttemptRepository attempts,
      ProviderObservability telemetry, MediaStorage storage, TechnicalQa qa) {
    this.db = db;
    this.tx = tx;
    this.service = service;
    this.router = router;
    this.properties = properties;
    this.limiter = limiter;
    this.retry = retry;
    this.attempts = attempts;
    this.telemetry = telemetry;
    this.storage = storage;
    this.qa = qa;
  }

  private static long elapsed(long start) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  @Scheduled(fixedDelayString = "${media-factory.image-generation.poll-delay-ms:1000}")
  public void tick() {
    try {
      recover();
      while (running.get() < properties.workerConcurrency()) {
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
    } catch (Exception ignored) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .error("Worker cycle failed; inspect infrastructure health");
    }
  }

  @PreDestroy
  public void close() {
    executor.shutdownNow();
  }

  public Map<String, Object> claim() {
    return tx.execute(s -> {
      var rows = db.sql(
              "select * from jobs where status='QUEUED' and available_at<=now() order by created_at for update skip locked limit 1")
          .query().listOfRows();
      if (rows.isEmpty()) {
        return null;
      }
      var job = rows.getFirst();
      db.sql(
              "update jobs set status='RUNNING',locked_at=now(),lease_token=?,execution_started=false,updated_at=now() where id=?")
          .params(UUID.randomUUID(), job.get("id")).update();
      service.transition((UUID) job.get("generation_id"), GenerationStatus.GENERATING);
      db.sql(
              "update generations set started_at=coalesce(started_at,now()),completed_at=null where id=?")
          .param(job.get("generation_id")).update();
      return service.one("jobs", (UUID) job.get("id"));
    });
  }

  public void execute(Map<String, Object> job) {
    // Duplicate dispatches with the same lease cannot make two external calls.
    if (db.sql(
            "update jobs set execution_started=true where id=? and lease_token=? and status='RUNNING' and not execution_started")
        .params(job.get("id"), job.get("lease_token")).update() != 1) {
      return;
    }
    UUID generationId = (UUID) job.get("generation_id");
    UUID permit = null;
    GenerationAttemptRepository.Attempt attempt = null;
    ImageGenerationProvider provider = null;
    ProviderRoute.Hop hop = null;
    boolean providerCompleted = false;
    long started = System.nanoTime();
    List<ProviderRoute.Hop> route = List.of();
    try {
      var generation = service.one("generations", generationId);
      route = service.route(generation);
      int index = ((Number) job.get("route_index")).intValue();
      hop = route.get(index);
      telemetry.event("provider_selected", generationId, null, hop.provider(), hop.model(), 0);
      provider = router.provider(hop.provider());
      var request = router.validate(hop, service.promptRequest(generation, hop));
      var admission = limiter.acquire(hop.provider(), (UUID) job.get("id"));
      if (!admission.acquired()) {
        boolean fallback = admission.circuitOpen() && index + 1 < route.size();
        schedule(job, admission.waitFor(), fallback,
            admission.circuitOpen() ? "Provider circuit is open" : "Waiting for provider capacity");
        if (fallback) {
          telemetry.count("provider_fallback", hop.provider(), hop.model());
          telemetry.event("fallback_triggered", generationId, null, hop.provider(), hop.model(), 0);
        }
        return;
      }
      permit = admission.permit();
      attempt = attempts.start(job, hop.provider(), hop.model());
      started = System.nanoTime();
      if (attempt.number() == 1) {
        telemetry.count("image_generation", hop.provider(), hop.model());
        telemetry.event("generation_started", generationId, attempt.id(), hop.provider(),
            hop.model(), 0);
      }
      telemetry.count("provider_requests", hop.provider(), hop.model());
      telemetry.event("attempt_started", generationId, attempt.id(), hop.provider(), hop.model(),
          0);
      Result<Media> result = provider.generate(request);
      providerCompleted = true;
      long elapsed = elapsed(started);
      var quote = attempts.succeed(attempt, result, elapsed);
      limiter.observe(hop.provider(), null);
      telemetry.duration(hop.provider(), hop.model(), elapsed);
      telemetry.cost(hop.provider(), hop.model(), quote.estimatedCost(), quote.currency());
      telemetry.event("attempt_succeeded", generationId, attempt.id(), hop.provider(), hop.model(),
          elapsed);
      persist(job, generation, request, result, hop, attempt);
    } catch (Exception failure) {
      ImageGenerationException error =
          failure instanceof ImageGenerationException classified ? classified
              : new ImageGenerationException(providerCompleted ? Type.UNEXPECTED : Type.UNAVAILABLE,
                  providerCompleted ? "Result persistence failed after provider success"
                      : "Provider execution failed", Duration.ZERO,
                  providerCompleted || (provider != null && !provider.replaySafe()), null);
      if (attempt != null && !providerCompleted) {
        attempts.fail(attempt, error, elapsed(started));
        limiter.observe(attempt.provider(), error);
        telemetry.duration(attempt.provider(), attempt.model(), elapsed(started));
        telemetry.event("attempt_failed", generationId, attempt.id(), attempt.provider(),
            attempt.model(), elapsed(started));
        if (error.type() == Type.RATE_LIMIT) {
          telemetry.count("provider_rate_limits", attempt.provider(), attempt.model());
        }
        if (error.type() == Type.TIMEOUT) {
          telemetry.count("provider_timeouts", attempt.provider(), attempt.model());
        }
      }
      if (hop == null) {
        terminal(job, "Provider route cannot be resolved", false);
        return;
      }
      int number = attempt == null ? properties.provider(hop.provider()).retry().maxAttempts()
          : attempt.providerNumber();
      var decision = retry.decide(error, number, properties.provider(hop.provider()).retry(),
          provider != null && provider.replaySafe(),
          ((Number) job.get("route_index")).intValue() + 1 < route.size());
      if (!providerCompleted && (decision.retry() || decision.fallback())) {
        schedule(job, decision.delay(), decision.fallback(), error.getMessage());
        telemetry.event(decision.fallback() ? "fallback_triggered" : "retry_scheduled",
            generationId, attempt == null ? null : attempt.id(), hop.provider(), hop.model(), 0);
        if (decision.fallback()) {
          telemetry.count("provider_fallback", hop.provider(), hop.model());
        }
      } else {
        terminal(job, error.getMessage(), decision.recoveryRequired() || providerCompleted);
        telemetry.count("image_generation_failure", hop.provider(), hop.model());
      }
    } finally {
      limiter.release(permit);
    }
  }

  private void persist(Map<String, Object> job, Map<String, Object> generation, Request request,
      Result<Media> result, ProviderRoute.Hop hop, GenerationAttemptRepository.Attempt attempt) {
    byte[] bytes = result.output().bytes();
    String checksum = TechnicalQa.checksum(bytes);
    UUID assetId = UUID.randomUUID();
    String key = "originals/" + generation.get("id") + "/" + assetId + (
        result.output().contentType().equals("image/jpeg") ? ".jpeg" : ".png");
    storage.putOriginal(key, bytes, result.output().contentType());
    telemetry.event("asset_stored", attempt.generationId(), attempt.id(), hop.provider(),
        hop.model(), 0);
    var report = qa.inspect(bytes, request.width(), request.height(), false);
    var metadata = new LinkedHashMap<String, Object>();
    metadata.put("provider", hop.provider());
    metadata.put("model", hop.model());
    metadata.put("requestedPrompt", request.prompt());
    metadata.put("options", request.options());
    metadata.put("width", request.width());
    metadata.put("height", request.height());
    metadata.put("generatedAt", Instant.now().toString());
    metadata.put("details", result.metadata());
    tx.executeWithoutResult(s -> {
      if (!ownsLease(job)) {
        return;
      }
      db.sql("select pg_advisory_xact_lock(hashtextextended(?,0))").param(checksum).query()
          .singleRow();
      service.transition(attempt.generationId(), GenerationStatus.GENERATED);
      db.sql(
              "insert into assets(id,generation_id,storage_key,sha256,media_type,size_bytes,width,height) values(?,?,?,?,?,?,?,?)")
          .params(assetId, generation.get("id"), key, checksum, result.output().contentType(),
              bytes.length, report.width(), report.height()).update();
      service.transition(attempt.generationId(), GenerationStatus.QA_PENDING);
      db.sql(
              "update generations set final_provider=?,model=?,result_metadata=cast(? as jsonb),completed_at=now() where id=?")
          .params(hop.provider(), hop.model(), JSON.writeValueAsString(metadata),
              generation.get("id")).update();
      service.enqueueQuality(assetId);
      db.sql(
              "update jobs set status='SUCCEEDED',failure_reason=null,provider_metadata=cast(? as jsonb),finished_at=now(),updated_at=now(),lease_token=null where id=?")
          .params(JSON.writeValueAsString(
              Map.of("provider", hop.provider(), "model", hop.model(), "details",
                  result.metadata())), job.get("id")).update();
      telemetry.count("image_generation_success", hop.provider(), hop.model());
      telemetry.event("generation_completed", attempt.generationId(), attempt.id(), hop.provider(),
          hop.model(), 0);
    });
  }

  private boolean ownsLease(Map<String, Object> job) {
    var row = db.sql("select status,lease_token from jobs where id=? for update")
        .param(job.get("id")).query().singleRow();
    return row.get("status").equals("RUNNING") && Objects.equals(row.get("lease_token"),
        job.get("lease_token"));
  }

  private void schedule(Map<String, Object> job, Duration delay, boolean fallback, String reason) {
    tx.executeWithoutResult(s -> {
      if (!ownsLease(job)) {
        return;
      }
      service.transition((UUID) job.get("generation_id"), GenerationStatus.QUEUED);
      db.sql(
              "update jobs set status='QUEUED',failure_reason=?,available_at=now()+(? * interval '1 millisecond'),lease_token=null,route_index=route_index+?,provider_attempts=case when ? then 0 else provider_attempts end,updated_at=now() where id=?")
          .params(reason, Math.max(1, delay.toMillis()), fallback ? 1 : 0, fallback, job.get("id"))
          .update();
    });
  }

  private void terminal(Map<String, Object> job, String reason, boolean recoveryRequired) {
    tx.executeWithoutResult(s -> {
      if (!ownsLease(job)) {
        return;
      }
      service.transition((UUID) job.get("generation_id"), GenerationStatus.FAILED);
      db.sql(
              "update jobs set status='FAILED',failure_reason=?,recovery_required=?,finished_at=now(),updated_at=now(),lease_token=null where id=?")
          .params(reason, recoveryRequired, job.get("id")).update();
      db.sql("update generations set completed_at=now() where id=?").param(job.get("generation_id"))
          .update();
    });
  }

  public void recover() {
    tx.executeWithoutResult(s -> {
      var stale = db.sql(
              "select * from jobs where status='RUNNING' and locked_at<now()-(? * interval '1 millisecond') for update skip locked")
          .param(properties.leaseDuration().toMillis()).query().listOfRows();
      for (var job : stale) {
        var calls = db.sql(
                "select * from generation_attempts where job_id=? and started_at>=? order by attempt_number desc limit 1")
            .params(job.get("id"), job.get("locked_at")).query().listOfRows();
        boolean safe = calls.isEmpty() || router.all().stream().anyMatch(
            p -> p.providerId().equals(calls.getFirst().get("provider")) && p.replaySafe());
        db.sql(
                "update generation_attempts set status='FAILED',completed_at=now(),duration_ms=extract(epoch from(now()-started_at))*1000,error_type='UNKNOWN_OUTCOME',error_code='LEASE_EXPIRED',error_message='Worker lease expired; provider outcome is unknown',outcome_unknown=true where job_id=? and status='STARTED'")
            .param(job.get("id")).update();
        db.sql("update generation_costs set outcome='FAILED' where job_id=? and outcome='STARTED'")
            .param(job.get("id")).update();
        db.sql("delete from provider_permits where job_id=?").param(job.get("id")).update();
        if (safe && ((Number) job.get("attempts")).intValue() < ((Number) job.get(
            "max_attempts")).intValue()) {
          schedule(job, Duration.ofSeconds(1), false, "Recovered expired worker lease");
        } else {
          terminal(job, "Worker lease expired; reconcile provider outcome before retry", !safe);
        }
      }
    });
  }
}
