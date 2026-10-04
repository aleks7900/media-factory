package com.mediafactory.bulk;

import static com.mediafactory.bulk.BulkGenerationService.*;
import static com.mediafactory.processing.ProcessingJson.*;

import com.mediafactory.provider.ImageGenerationProperties;
import com.mediafactory.provider.resilience.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "media.worker.enabled", havingValue = "true", matchIfMissing = true)
public class BulkGenerationWorker implements AutoCloseable {
  final BulkGenerationService s;
  final ProviderRateLimiter limiter;
  final RetryDecisionService retries;
  final ExecutorService pool;
  final int concurrency, rpm, maxAttempts;
  final Set<UUID> active = ConcurrentHashMap.newKeySet();
  final Map<UUID, UUID> leases = new ConcurrentHashMap<>();

  static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BulkGenerationWorker.class);
  final long retryInitialDelayMs, retryMaxDelayMs;
  final double retryMultiplier;
  final boolean retryJitter;

  @Scheduled(fixedDelay = 30000)
  public void heartbeat() {
    leases.forEach(
        (id, token) ->
            s.database()
                .sql(
                    "update bulk_tasks set lease_until=now()+interval '5 minutes' where id=? and"
                        + " lease_token=? and status='GENERATING'")
                .params(id, token)
                .update());
  }

  @org.springframework.beans.factory.annotation.Autowired
  public BulkGenerationWorker(
      BulkGenerationService s,
      ProviderRateLimiter limiter,
      RetryDecisionService retries,
      @Value("${bulk.worker.concurrency:3}") int concurrency,
      @Value("${bulk.worker.requests-per-minute:20}") int rpm,
      @Value("${bulk.worker.max-attempts:3}") int maxAttempts,
      @Value("${bulk.worker.retry.initial-delay-ms:2000}") long initialDelayMs,
      @Value("${bulk.worker.retry.max-delay-ms:120000}") long maxDelayMs,
      @Value("${bulk.worker.retry.multiplier:2.0}") double multiplier,
      @Value("${bulk.worker.retry.jitter:true}") boolean jitter) {
    this.s = s;
    this.limiter = limiter;
    this.retries = retries;
    this.concurrency = Math.max(1, Math.min(16, concurrency));
    this.rpm = Math.max(1, rpm);
    this.maxAttempts = Math.max(1, Math.min(10, maxAttempts));
    this.retryInitialDelayMs = Math.max(100, initialDelayMs);
    this.retryMaxDelayMs = Math.max(1000, maxDelayMs);
    this.retryMultiplier = Math.max(1.0, multiplier);
    this.retryJitter = jitter;
    pool = Executors.newFixedThreadPool(this.concurrency);
  }

  public BulkGenerationWorker(
      BulkGenerationService s,
      ProviderRateLimiter limiter,
      RetryDecisionService retries,
      int concurrency,
      int rpm,
      int maxAttempts) {
    this(s, limiter, retries, concurrency, rpm, maxAttempts, 2000L, 120000L, 2.0, true);
  }


  @Scheduled(fixedDelayString = "${bulk.worker.poll-ms:1000}")
  public void tick() {
    while (active.size() < concurrency) {
      var t = claim();
      if (t == null) return;
      UUID id = (UUID) t.get("id");
      active.add(id);
      leases.put(id, (UUID) t.get("lease_token"));
      pool.submit(
          () -> {
            try {
              step(t);
            } catch (Exception error) {
              log.error(
                  "bulk_cycle_failed taskId={} errorType={}",
                  id,
                  error.getClass().getSimpleName());
            } finally {
              active.remove(id);
              leases.remove(id);
              s.database()
                  .sql(
                      "update bulk_tasks set lease_token=null,lease_until=null where id=? and"
                          + " lease_token=?")
                  .params(id, t.get("lease_token"))
                  .update();
            }
          });
    }
  }

  Map<String, Object> claim() {
    var claimed =
        s.transactions()
            .execute(
                tx -> {
                  s.database()
                      .sql("select pg_advisory_xact_lock(hashtext('bulk-dispatch'))")
                      .query()
                      .singleRow();
                  var rows =
                      s.database()
                          .sql(
                              "select t.id from bulk_tasks t join bulk_batches b on b.id=t.batch_id"
                                  + " where t.available_at<=now() and (t.lease_until is null or"
                                  + " t.lease_until<now()) and b.deleted_at is null and t.deleted_at is null and ((t.status='GENERATING') or"
                                  + " (t.status in ('QUEUED','RETRYING') and not b.paused and not"
                                  + " b.cancelled and not t.cancel_requested and (select count(*) from"
                                  + " bulk_tasks busy join bulk_batches owner on"
                                  + " owner.id=busy.batch_id where busy.status='GENERATING' and busy.lease_until>now() and"
                                  + " owner.provider=b.provider) < ?)) order by case when"
                                  + " t.status='GENERATING' then 0 else 1"
                                  + " end,t.available_at,t.created_at for update of t skip locked"
                                  + " limit 1")
                          .param(concurrency)
                          .query(UUID.class)
                          .list();
                  if (rows.isEmpty()) return null;
                  UUID id = rows.getFirst(), token = UUID.randomUUID();
                  s.database()
                      .sql(
                          "update bulk_tasks set lease_token=?,lease_until=now()+interval '5"
                              + " minutes',status='GENERATING',started_at=coalesce(started_at,now()),available_at=now()+interval"
                              + " '10 seconds' where id=?")
                      .params(token, id)
                      .update();
                  return s.task(id);
                });
    // Never acquire the parent batch lock while holding a child lock: batch controls
    // acquire the parent first and then update children.
    if (claimed != null)
      s.database()
          .sql(
              "update bulk_batches set started_at=coalesce(started_at,now()),completed_at=null"
                  + " where id=?")
          .param(claimed.get("batch_id"))
          .update();
    return claimed;
  }

  void step(Map<String, Object> claimed) {
    UUID id = (UUID) claimed.get("id");
    UUID permit = null;
    boolean externalStarted = false;
    boolean polling = false;
    var t = s.task(id);
    if (t.get("deleted_at") != null || t.get("batch_deleted") != null) return;
    BulkProcessor processor = s.processor(t.get("kind").toString());
    log.info(
        "bulk_task_started batchId={} taskId={} provider={} model={} attempt={}",
        t.get("batch_id"),
        id,
        t.get("provider"),
        t.get("model"),
        t.get("attempts"));
    try {
      boolean unsent =
          t.get("current_attempt_id") == null
              || s.database()
                  .sql("select status from bulk_attempts where id=?")
                  .param(t.get("current_attempt_id"))
                  .query(String.class)
                  .single()
                  .matches("PREPARED|WAITING_CAPACITY");
      if (Boolean.TRUE.equals(t.get("cancel_requested"))
          && t.get("remote_job_id") == null
          && unsent) {
        terminal(t, "CANCELLED", "CANCELLED", false);
        return;
      }
      if (Boolean.TRUE.equals(t.get("paused")) && t.get("remote_job_id") == null && unsent) {
        s.database().sql("update bulk_tasks set status='QUEUED' where id=?").param(id).update();
        return;
      }
      boolean continuing = t.get("current_attempt_id") != null;
      if (!continuing) {
        var provisional = new LinkedHashMap<>(t);
        provisional.put("current_attempt_id", UUID.randomUUID());
        var input = s.input(provisional);
        processor.validate(input);
        var quote = processor.estimate(input);
        t = begin(t, (UUID) provisional.get("current_attempt_id"), quote);
      }
      var input = s.input(t);
      String remote = Objects.toString(t.get("remote_job_id"), null);
      polling = remote != null;
      if (continuing
          && remote == null
          && !s.database()
              .sql("select status from bulk_attempts where id=?")
              .param(t.get("current_attempt_id"))
              .query(String.class)
              .single()
              .matches("WAITING_CAPACITY|PREPARED")) {
        byte[] recovered = null;
        try {
          recovered = s.mediaStorage().read(outputKey(t));
        } catch (RuntimeException missing) {
        }
        if (recovered != null) {
          complete(
              t,
              new BulkProcessor.Output(
                  recovered,
                  mediaType(recovered),
                  Map.of("recoveredFromImmutableStorage", true),
                  null,
                  null,
                  null,
                  null,
                  "USD"));
          return;
        }
        if (!processor.replaySafe(input)) {
          terminal(t, "FAILED", "PROVIDER_OUTCOME_UNKNOWN", true);
          return;
        }
      }
      var admission =
          limiter.acquireBulk(
              t.get("provider").toString(),
              id,
              new ImageGenerationProperties.RateLimit(rpm, concurrency),
              Duration.ofMinutes(5));
      if (!admission.acquired()) {
        // No request happened. A fresh attempt remains safe to resume; do not classify it as
        // ambiguous.
        s.database()
            .sql(
                "update bulk_attempts set status='WAITING_CAPACITY' where id=? and"
                    + " status='STARTED'")
            .param(t.get("current_attempt_id"))
            .update();
        s.database()
            .sql(
                "update bulk_tasks set available_at=now()+(? * interval '1 millisecond') where"
                    + " id=?")
            .params(admission.waitFor().toMillis(), id)
            .update();
        return;
      }
      permit = admission.permit();
      s.database()
          .sql("update bulk_attempts set status='STARTED' where id=?")
          .param(t.get("current_attempt_id"))
          .update();
      if (processor.asynchronous()) {
        if (remote == null) {
          externalStarted = true;
          remote = processor.submit(input);
          final String submitted = remote;
          final Map<String, Object> current = t;
          s.transactions()
              .executeWithoutResult(
                  tx -> {
                    s.database()
                        .sql(
                            "update bulk_tasks set remote_job_id=?,available_at=now()+interval '10"
                                + " seconds' where id=? and lease_token=?")
                        .params(submitted, id, claimed.get("lease_token"))
                        .update();
                    s.database()
                        .sql(
                            "update bulk_attempts set remote_job_id=?,status='SUBMITTED' where"
                                + " id=?")
                        .params(submitted, current.get("current_attempt_id"))
                        .update();
                  });
          log.info(
              "bulk_task_submitted batchId={} taskId={} provider={} model={} attempt={} remoteJobId={}",
              t.get("batch_id"),
              id,
              t.get("provider"),
              t.get("model"),
              t.get("attempts"),
              submitted);
          return;
        }
        var deadline =
            s.database()
                .sql("select remote_deadline_at from bulk_tasks where id=?")
                .param(id)
                .query(java.time.OffsetDateTime.class)
                .optional();
        if (deadline.isPresent() && deadline.get().toInstant().isBefore(Instant.now())) {
          terminal(t, "FAILED", "PROVIDER_POLL_DEADLINE", true);
          return;
        }
        String state = processor.status(remote, input);
        if (Set.of("FAILED", "CANCELLED", "REJECTED").contains(state)) {
          terminal(t, "FAILED", "PROVIDER_" + state, false);
          return;
        }
        if (!state.equals("SUCCEEDED")) {
          s.database()
              .sql("update bulk_tasks set available_at=now()+interval '5 seconds' where id=?")
              .param(id)
              .update();
          return;
        }
      }
      externalStarted = !processor.asynchronous();
      var output = processor.result(remote, input);
      complete(t, output);
    } catch (Exception error) {
      var current = s.task(id);
      ImageGenerationException classified =
          error instanceof ImageGenerationException known
              ? known
              : new ImageGenerationException(
                  error instanceof IllegalArgumentException
                      ? ImageGenerationException.Type.INVALID_REQUEST
                      : ImageGenerationException.Type.UNEXPECTED,
                  "Bulk operation failed",
                  Duration.ofSeconds(10),
                  externalStarted,
                  null);
      boolean safe = false;
      try {
        safe = processor.replaySafe(s.input(current));
      } catch (RuntimeException unavailable) {
      }
      var decision =
          retries.decide(
              classified,
              polling
                  ? ((Number) current.get("poll_failures")).intValue() + 1
                  : ((Number) current.get("attempts")).intValue(),
              new ImageGenerationProperties.Retry(
                  maxAttempts,
                  Duration.ofMillis(retryInitialDelayMs),
                  Duration.ofMillis(retryMaxDelayMs),
                  (int) retryMultiplier,
                  retryJitter,
                  false),
              safe || polling,
              false);
      if (decision.retry() && !Boolean.TRUE.equals(current.get("cancel_requested"))) {
        log.warn(
            "bulk_task_retry batchId={} taskId={} provider={} model={} attempt={} errorCode={} delayMs={}",
            current.get("batch_id"),
            id,
            current.get("provider"),
            current.get("model"),
            current.get("attempts"),
            classified.type().name(),
            decision.delay().toMillis());
        if (polling) {
          s.database()
              .sql(
                  "update bulk_tasks set available_at=now()+(? * interval '1"
                      + " millisecond'),poll_failures=poll_failures+1,retry_count=retry_count+1,error_code=?"
                      + " where id=?")
              .params(decision.delay().toMillis(), classified.type().name(), id)
              .update();
        } else {
          s.database()
              .sql(
                  "update bulk_attempts set status='FAILED',completed_at=now(),error_code=? where"
                      + " id=?")
              .params(classified.type().name(), current.get("current_attempt_id"))
              .update();
          s.database()
              .sql(
                  "update bulk_tasks set"
                  + " status='RETRYING',retry_count=retry_count+1,current_attempt_id=null,remote_job_id=null,error_code=?,error_message=?,available_at=now()+(?"
                  + " * interval '1 millisecond') where id=? and status='GENERATING' and not"
                  + " cancel_requested")
              .params(
                  classified.type().name(),
                  "Retryable provider failure",
                  decision.delay().toMillis(),
                  id)
              .update();
        }
      } else {
        String terminalCode =
            decision.recoveryRequired() || polling
                ? "PROVIDER_OUTCOME_UNKNOWN"
                : classified.type().name();
        log.error(
            "bulk_task_failed batchId={} taskId={} provider={} model={} attempt={} errorCode={} error={}",
            current.get("batch_id"),
            id,
            current.get("provider"),
            current.get("model"),
            current.get("attempts"),
            terminalCode,
            classified.getMessage());
        terminal(current, "FAILED", terminalCode, decision.recoveryRequired() || polling);
      }
    } finally {
      limiter.release(permit);
    }
  }

  Map<String, Object> begin(Map<String, Object> t, UUID attempt, BulkProcessor.Quote quote) {
    return s.transactions()
        .execute(
            tx -> {
              UUID cost = UUID.randomUUID();
              int number = ((Number) t.get("attempts")).intValue() + 1;
              s.database()
                  .sql(
                      "insert into"
                          + " generation_costs(id,generation_id,attempt,provider,model,operation,input_usage,output_usage,estimated_cost,currency,pricing_status,pricing_version)"
                          + " values(?,?,?,?,?,'BULK_GENERATION',null,null,?,?,?,'bulk-provider-estimate-v1')")
                  .params(
                      cost,
                      t.get("generation_id"),
                      number,
                      t.get("provider"),
                      t.get("model"),
                      quote.amount(),
                      quote.currency(),
                      quote.amount() == null ? "UNKNOWN" : "ESTIMATED")
                  .update();
              s.database()
                  .sql(
                      "insert into bulk_attempts(id,task_id,number,status,provider,model,cost_id)"
                          + " values(?,?,?,'PREPARED',?,?,?)")
                  .params(attempt, t.get("id"), number, t.get("provider"), t.get("model"), cost)
                  .update();
              s.database()
                  .sql(
                      "update bulk_tasks set"
                          + " current_attempt_id=?,attempts=?,poll_failures=0,remote_deadline_at=now()+interval"
                          + " '2 hours' where id=?")
                  .params(attempt, number, t.get("id"))
                  .update();
              s.database()
                  .sql(
                      "update generations set"
                          + " status='GENERATING',started_at=coalesce(started_at,now()) where id=?")
                  .param(t.get("generation_id"))
                  .update();
              s.event(
                  (UUID) t.get("batch_id"),
                  (UUID) t.get("id"),
                  "ATTEMPT_STARTED",
                  Map.of("attemptId", attempt, "number", number));
              return s.task((UUID) t.get("id"));
            });
  }

  String outputKey(Map<String, Object> t) {
    return "bulk/"
        + t.get("batch_id")
        + "/results/"
        + t.get("id")
        + "/"
        + t.get("current_attempt_id")
        + ".bin";
  }

  static String mediaType(byte[] bytes) {
    if (bytes.length > 12
        && bytes[4] == 'f'
        && bytes[5] == 't'
        && bytes[6] == 'y'
        && bytes[7] == 'p') return "video/mp4";
    if (bytes.length > 8 && (bytes[0] & 255) == 137 && bytes[1] == 'P') return "image/png";
    if (bytes.length > 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216) return "image/jpeg";
    throw new IllegalArgumentException("Invalid generated media signature");
  }

  void complete(Map<String, Object> t, BulkProcessor.Output result) throws IOException {
    String type = mediaType(result.bytes());
    check(type.equals(result.mediaType()), "Provider media type mismatch");
    var config = map(t.get("configuration"));
    int width = integer(config, "width", t.get("kind").equals("GPT_IMAGE") ? 1024 : 1280),
        height = integer(config, "height", t.get("kind").equals("GPT_IMAGE") ? 1024 : 720);
    if (type.startsWith("image/")) {
      var image = ImageIO.read(new ByteArrayInputStream(result.bytes()));
      check(image != null, "Invalid generated image");
      width = image.getWidth();
      height = image.getHeight();
    }
    String key = outputKey(t), hash = sha(result.bytes());
    try {
      s.mediaStorage().putOriginal(key, result.bytes(), type);
    } catch (RuntimeException existing) {
      check(sha(s.mediaStorage().read(key)).equals(hash), "Immutable output conflict");
    }
    final int w = width, h = height;
    s.transactions()
        .executeWithoutResult(
            tx -> {
              s.database()
                  .sql("select id from bulk_tasks where id=? for update")
                  .param(t.get("id"))
                  .query()
                  .singleRow();
              var current = s.task((UUID) t.get("id"));
              if (current.get("asset_id") != null) return;
              UUID asset = UUID.randomUUID();
              s.database()
                  .sql(
                      "insert into"
                          + " assets(id,generation_id,storage_key,sha256,media_type,size_bytes,width,height)"
                          + " values(?,?,?,?,?,?,?,?)")
                  .params(
                      asset, t.get("generation_id"), key, hash, type, result.bytes().length, w, h)
                  .update();
              s.database()
                  .sql(
                      "update generations set"
                          + " status='GENERATED',final_provider=?,model=?,completed_at=now(),updated_at=now()"
                          + " where id=?")
                  .params(t.get("provider"), t.get("model"), t.get("generation_id"))
                  .update();
              boolean recovered =
                  Boolean.TRUE.equals(result.metadata().get("recoveredFromImmutableStorage"));
              s.database()
                  .sql(
                      "update generation_costs set"
                          + " input_usage=?,output_usage=?,estimated_cost=coalesce(?,estimated_cost),actual_cost=?,pricing_status=case"
                          + " when cast(? as numeric) is not null then 'ACTUAL' else pricing_status"
                          + " end where id=(select cost_id from bulk_attempts where id=?)")
                  .params(
                      recovered ? null : result.inputUsage(),
                      recovered ? null : result.outputUsage(),
                      result.estimatedCost(),
                      result.actualCost(),
                      result.actualCost(),
                      t.get("current_attempt_id"))
                  .update();
              s.database()
                  .sql("update bulk_attempts set status='COMPLETED',completed_at=now() where id=?")
                  .param(t.get("current_attempt_id"))
                  .update();
              s.database()
                  .sql(
                      "update bulk_tasks set"
                          + " status=?,asset_id=?,provider_metadata=?::jsonb,error_code=null,error_message=null,completed_at=now()"
                          + " where id=?")
                  .params(
                      Boolean.TRUE.equals(current.get("cancel_requested"))
                          ? "CANCELLED"
                          : "COMPLETED",
                      asset,
                      write(result.metadata()),
                      t.get("id"))
                  .update();
              s.event(
                  (UUID) t.get("batch_id"),
                  (UUID) t.get("id"),
                  "OUTPUT_STORED",
                  Map.of("assetId", asset, "sha256", hash));
              log.info(
                  "bulk_task_completed batchId={} taskId={} provider={} model={} assetId={}",
                  t.get("batch_id"),
                  t.get("id"),
                  t.get("provider"),
                  t.get("model"),
                  asset);
            });
  }

  void terminal(Map<String, Object> t, String status, String code, boolean unknown) {
    s.transactions()
        .executeWithoutResult(
            tx -> {
              s.database()
                  .sql(
                      "update bulk_tasks set"
                          + " status=?,error_code=?,error_message=?,outcome_unknown=?,completed_at=now()"
                          + " where id=? and status not in ('COMPLETED','CANCELLED')")
                  .params(
                      status,
                      code,
                      unknown
                          ? "Provider submission may have succeeded; reconcile before retrying"
                          : "Inspect task settings or provider availability",
                      unknown,
                      t.get("id"))
                  .update();
              if (t.get("current_attempt_id") != null)
                s.database()
                    .sql(
                        "update bulk_attempts set status=?,error_code=?,completed_at=now() where"
                            + " id=?")
                    .params(unknown ? "OUTCOME_UNKNOWN" : status, code, t.get("current_attempt_id"))
                    .update();
              s.event((UUID) t.get("batch_id"), (UUID) t.get("id"), status, Map.of("code", code));
              s.database()
                  .sql(
                      "update generations set status='FAILED',completed_at=now(),updated_at=now()"
                          + " where id=? and status in ('CREATED','QUEUED','GENERATING')")
                  .param(t.get("generation_id"))
                  .update();
            });
  }

  public void close() {
    pool.shutdownNow();
  }
}
