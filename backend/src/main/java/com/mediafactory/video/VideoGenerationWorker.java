package com.mediafactory.video;

import static com.mediafactory.processing.ProcessingJson.*;
import static com.mediafactory.video.VideoJson.*;

import com.mediafactory.provider.ImageGenerationProperties.*;
import com.mediafactory.provider.resilience.*;
import com.mediafactory.provider.video.VideoTypes;
import com.mediafactory.similarity.PerceptualHash;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "media.worker.enabled", havingValue = "true", matchIfMissing = true)
public class VideoGenerationWorker implements AutoCloseable {

  static final Set<String> LIVE =
      Set.of(
          "SUBMITTING",
          "SUBMITTED",
          "PROVIDER_QUEUED",
          "PROVIDER_PROCESSING",
          "DOWNLOADING",
          "SUBMISSION_UNKNOWN",
          "TIMED_OUT");
  final VideoProductionService s;
  final ProviderRateLimiter limiter;
  final RetryDecisionService retries;
  final int remoteLimit, pollSeconds;
  final Semaphore downloads = new Semaphore(2);
  final ConcurrentMap<UUID, UUID> active = new ConcurrentHashMap<>();
  final ExecutorService pool = Executors.newFixedThreadPool(3);
  @Value("${VIDEO_REQUESTS_PER_MINUTE:60}")
  int requestsPerMinute = 60;
  @Value("${VIDEO_MAX_HTTP_CALLS:3}")
  int httpConcurrency = 3;

  public VideoGenerationWorker(
      VideoProductionService s,
      ProviderRateLimiter limiter,
      RetryDecisionService retries,
      @Value("${VIDEO_MAX_REMOTE_JOBS:3}") int remoteLimit,
      @Value("${VIDEO_POLL_SECONDS:10}") int pollSeconds) {
    this.s = s;
    this.limiter = limiter;
    this.retries = retries;
    this.remoteLimit = Math.max(1, remoteLimit);
    this.pollSeconds = Math.max(2, pollSeconds);
  }

  @Scheduled(fixedDelay = 1000)
  public void tick() {
    for (UUID id :
        s.db
            .sql(
                "select id from video_productions where status in"
                    + " ('GENERATION_QUEUED','GENERATING') and not paused and available_at<=now()"
                    + " and (lease_until is null or lease_until<now()) order by created_at limit 3")
            .query(UUID.class)
            .list()) {
      if (active.size() >= 3) {
        break;
      }
      UUID token = UUID.randomUUID();
      if (s.db
          .sql(
              "update video_productions set lease_token=?,lease_until=now()+interval '3"
                  + " minutes' where id=? and (lease_until is null or lease_until<now())")
          .params(token, id)
          .update()
          != 1) {
        continue;
      }
      active.put(id, token);
      pool.submit(
          () -> {
            try {
              step(id);
            } catch (Exception error) {
              var v = s.one(id);
              s.event(
                  id,
                  "GENERATION_STAGE_ERROR",
                  Map.of("errorType", error.getClass().getSimpleName()));
              if (!v.get("status").equals("CANCELLED")) {
                s.move(
                    v,
                    "GENERATION_FAILED",
                    error instanceof VideoFailure ? error.getMessage() : "GENERATION_STAGE_FAILED");
              }
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

  public void step(UUID id) {
    var v = s.one(id);
    if (Boolean.TRUE.equals(v.get("paused")) || v.get("status").equals("CANCELLED")) {
      return;
    }
    var a =
        v.get("current_attempt_id") == null
            ? prepare(v)
            : attempt((UUID) v.get("current_attempt_id"));
    if (a == null) {
      return;
    }
    String status = a.get("status").toString();
    if (status.equals("SUBMITTING")) {
      var provider = s.router.provider(a.get("provider").toString());
      if (provider.capabilities().idempotentSubmission()) {
        s.db
            .sql("update video_generation_attempts set status='REQUESTED' where id=?")
            .param(a.get("id"))
            .update();
      } else {
        unknown(v, a, "SUBMISSION_INTERRUPTED");
        return;
      }
      a = attempt((UUID) a.get("id"));
      status = "REQUESTED";
    }
    if (status.equals("REQUESTED")) {
      submit(v, a);
      return;
    }
    if (Set.of("SUBMITTED", "PROVIDER_QUEUED", "PROVIDER_PROCESSING").contains(status)) {
      poll(v, a);
      return;
    }
    if (status.equals("DOWNLOADING")) {
      download(v, a);
      return;
    }
    if (status.equals("PROVIDER_FAILED")) {
      fallback(v, a);
    }
  }

  public Map<String, Object> attempt(UUID id) {
    return row(
        s.db
            .sql("select * from video_generation_attempts where id=?")
            .param(id)
            .query()
            .singleRow());
  }

  Map<String, Object> prepare(Map<String, Object> v) {
    return s.tx.execute(
        t -> {
          s.lock((UUID) v.get("id"));
          var fresh = s.one((UUID) v.get("id"));
          if (!fresh.get("status").equals("GENERATION_QUEUED")
              || Boolean.TRUE.equals(fresh.get("paused"))) {
            return null;
          }
          if (fresh.get("current_attempt_id") != null) {
            return attempt((UUID) fresh.get("current_attempt_id"));
          }
          int count = integer(fresh, "attempt_count", 0) + 1;
          if (count > integer(fresh, "max_attempts", 3)) {
            s.move(fresh, "GENERATION_FAILED", "ATTEMPT_LIMIT");
            return null;
          }
          var route = (List<Map<String, Object>>) fresh.get("route");
          var selected = route.get(integer(fresh, "route_index", 0));
          UUID id = UUID.randomUUID(), cost = UUID.randomUUID();
          var p = map(fresh.get("profile_snapshot"));
          // Generation keeps its original motion plan; edits apply to regeneration/reprocessing.
          var plan =
              map(
                  s.db
                      .sql(
                          "select definition from motion_plan_versions where production_id=? and"
                              + " version=1")
                      .param(v.get("id"))
                      .query()
                      .singleRow()
                      .get("definition"));
          var prompt = map(map(fresh.get("prompt_snapshot")).get("canonical"));
          var request =
              new VideoTypes.Request(
                  (UUID) fresh.get("generation_id"),
                  id,
                  (UUID) fresh.get("source_asset_id"),
                  fresh.get("source_checksum").toString(),
                  prompt.get("positivePrompt").toString(),
                  Objects.toString(prompt.get("negativePrompt"), ""),
                  integer(p, "width", 720),
                  integer(p, "height", 1280),
                  integer(p, "duration", 5),
                  null,
                  null,
                  plan,
                  Map.of(),
                  selected.get("model").toString());
          var provider = s.router.provider(selected.get("provider").toString());
          provider.capabilities().validate(request);
          var estimate = provider.estimate(request);
          if (!provider.providerId().equals("mock-video")) {
            var costs = s.costs((UUID) v.get("id"));
            boolean unknown =
                costs.stream()
                    .anyMatch(
                        c ->
                            integer(c, "unknown", 0) > 0
                                || !"USD".equals(Objects.toString(c.get("currency"), "").strip()));
            BigDecimal spent =
                costs.stream()
                    .map(
                        c ->
                            c.get("total") == null
                                ? BigDecimal.ZERO
                                : new BigDecimal(c.get("total").toString()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (unknown
                || estimate.cost() == null
                || !estimate.currency().equals("USD")
                || spent
                .add(estimate.cost())
                .compareTo(new BigDecimal(fresh.get("budget").toString()))
                > 0) {
              s.move(fresh, "GENERATION_FAILED", "BUDGET_OR_PRICING_UNKNOWN");
              return null;
            }
          }
          s.db
              .sql(
                  "insert into"
                      + " generation_costs(id,generation_id,attempt,provider,model,operation,input_usage,output_usage,estimated_cost,currency,outcome,pricing_status,pricing_version)"
                      + " values(?,?,?,?,?,'VIDEO_GENERATION',1,?,?,?,'STARTED',?,?)")
              .params(
                  cost,
                  fresh.get("generation_id"),
                  count,
                  provider.providerId(),
                  request.model(),
                  request.durationSeconds(),
                  estimate.cost(),
                  estimate.currency(),
                  estimate.cost() == null ? "UNKNOWN" : "ESTIMATED",
                  estimate.pricingVersion())
              .update();
          s.db
              .sql(
                  "insert into"
                      + " video_generation_attempts(id,production_id,generation_id,attempt,provider,model,request_snapshot,provider_prompt,cost_id,fallback)"
                      + " values(?,?,?,?,?,?,?::jsonb,?::jsonb,?,?)")
              .params(
                  id,
                  fresh.get("id"),
                  fresh.get("generation_id"),
                  count,
                  provider.providerId(),
                  request.model(),
                  write(request),
                  write(
                      Map.of(
                          "positivePrompt",
                          request.prompt(),
                          "negativePrompt",
                          request.negativePrompt(),
                          "adaptation",
                          "CANONICAL_MOTION_TEXT")),
                  cost,
                  count > 1)
              .update();
          s.db
              .sql("update video_productions set current_attempt_id=?,attempt_count=? where id=?")
              .params(id, count, fresh.get("id"))
              .update();
          return attempt(id);
        });
  }

  VideoTypes.Request request(Map<String, Object> a) {
    return JsonMapper.builder()
        .build()
        .readValue(write(a.get("request_snapshot")), VideoTypes.Request.class);
  }

  void submit(Map<String, Object> v, Map<String, Object> a) {
    String providerId = a.get("provider").toString();
    UUID attemptId = (UUID) a.get("id");
    var r = request(a);
    var source = s.source(r.sourceAssetId());
    byte[] bytes;
    try {
      bytes = s.storage.read(source.get("storage_key").toString());
      if (!PerceptualHash.sha(bytes).equals(r.sourceChecksum())) {
        throw new VideoFailure("SOURCE_CHECKSUM_CHANGED");
      }
      s.router.provider(providerId).validateInput(r, bytes, source.get("media_type").toString());
    } catch (RuntimeException invalidInput) {
      s.db
          .sql(
              "update video_generation_attempts set"
                  + " status='PROVIDER_REJECTED',error_code='PREFLIGHT_FAILED',completed_at=now()"
                  + " where id=?")
          .param(attemptId)
          .update();
      s.db
          .sql(
              "update generation_costs set actual_cost=0,outcome='FAILED',pricing_status='ACTUAL'"
                  + " where id=?")
          .param(a.get("cost_id"))
          .update();
      s.move(s.one((UUID) v.get("id")), "GENERATION_FAILED", "PREFLIGHT_FAILED");
      return;
    }
    boolean claimed =
        s.tx.execute(
            t -> {
              s.db
                  .sql("select pg_advisory_xact_lock(hashtext(?))")
                  .param("video-remote:" + providerId)
                  .query()
                  .singleRow();
              var fresh = s.one((UUID) v.get("id"));
              if (fresh.get("status").equals("CANCELLED")
                  || Boolean.TRUE.equals(fresh.get("paused"))) {
                return false;
              }
              int live =
                  s.db
                      .sql(
                          "select count(*) from video_generation_attempts where provider=? and"
                              + " status in"
                              + " ('SUBMITTING','SUBMITTED','PROVIDER_QUEUED','PROVIDER_PROCESSING','DOWNLOADING','SUBMISSION_UNKNOWN','TIMED_OUT')")
                      .param(providerId)
                      .query(Integer.class)
                      .single();
              if (live >= remoteLimit) {
                return false;
              }
              return s.db
                  .sql(
                      "update video_generation_attempts set status='SUBMITTING' where id=? and"
                          + " status='REQUESTED'")
                  .param(attemptId)
                  .update()
                  == 1;
            });
    if (!claimed) {
      defer((UUID) v.get("id"), pollSeconds);
      return;
    }
    var permit =
        limiter.acquireVideo(
            "video:" + providerId,
            attemptId,
            new RateLimit(requestsPerMinute, httpConcurrency),
            Duration.ofMinutes(2));
    if (!permit.acquired()) {
      s.db
          .sql("update video_generation_attempts set status='REQUESTED' where id=?")
          .param(attemptId)
          .update();
      defer((UUID) v.get("id"), Math.max(2, permit.waitFor().toSeconds()));
      return;
    }
    try {
      var submitted =
          s.router.provider(providerId).submit(r, bytes, source.get("media_type").toString());
      s.tx.executeWithoutResult(
          t -> {
            s.db
                .sql(
                    "update video_generation_attempts set"
                        + " provider_job_id=?,provider_metadata=?::jsonb,status='SUBMITTED',submitted_at=now(),next_poll_at=now()+(?*interval"
                        + " '1 second') where id=? and status='SUBMITTING'")
                .params(
                    submitted.providerJobId(), write(submitted.metadata()), pollSeconds, attemptId)
                .update();
            var fresh = s.one((UUID) v.get("id"));
            s.move(fresh, "GENERATING", "");
            defer((UUID) v.get("id"), pollSeconds);
            s.db
                .sql("update generations set status='GENERATING' where id=? and status='QUEUED'")
                .param(v.get("generation_id"))
                .update();
          });
      limiter.observe("video:" + providerId, null, new Circuit(3, Duration.ofSeconds(60)));
    } catch (ImageGenerationException e) {
      submissionFailure(v, a, e);
    } catch (RuntimeException e) {
      // Includes failure to persist a successful provider response. Never infer no charge.
      unknown(s.one((UUID) v.get("id")), a, "SUBMISSION_REQUIRES_RECONCILIATION");
    } finally {
      limiter.release(permit.permit());
    }
  }

  void submissionFailure(
      Map<String, Object> v, Map<String, Object> a, ImageGenerationException error) {
    var provider = s.router.provider(a.get("provider").toString());
    int failures = integer(a, "submit_failures", 0) + 1;
    var decision =
        retries.decide(
            error,
            failures,
            new Retry(3, Duration.ofSeconds(5), Duration.ofSeconds(60), 2, true, false),
            provider.capabilities().idempotentSubmission(),
            true);
    limiter.observe(
        "video:" + provider.providerId(), error, new Circuit(3, Duration.ofSeconds(60)));
    if (decision.recoveryRequired()) {
      unknown(v, a, "SUBMISSION_OUTCOME_UNKNOWN");
      return;
    }
    s.db
        .sql(
            "update video_generation_attempts set"
                + " submit_failures=?,error_type=?,error_code=?,retryable=?,status=? where id=?")
        .params(
            failures,
            error.type().name(),
            "SUBMIT_FAILED",
            decision.retry(),
            decision.retry() ? "REQUESTED" : "PROVIDER_FAILED",
            a.get("id"))
        .update();
    if (decision.retry()) {
      defer((UUID) v.get("id"), Math.max(2, decision.delay().toSeconds()));
    } else {
      s.db
          .sql("update generation_costs set outcome='FAILED' where id=?")
          .param(a.get("cost_id"))
          .update();
      if (decision.fallback()) {
        fallback(s.one((UUID) v.get("id")), a);
      } else {
        s.move(s.one((UUID) v.get("id")), "GENERATION_FAILED", error.type().name());
      }
    }
  }

  void unknown(Map<String, Object> v, Map<String, Object> a, String reason) {
    s.tx.executeWithoutResult(
        t -> {
          s.db
              .sql(
                  "update video_generation_attempts set"
                      + " status='SUBMISSION_UNKNOWN',recovery_required=true,error_code=? where"
                      + " id=?")
              .params(reason, a.get("id"))
              .update();
          s.move(s.one((UUID) v.get("id")), "SUBMISSION_UNKNOWN", reason);
        });
  }

  void poll(Map<String, Object> v, Map<String, Object> a) {
    boolean expired =
        s.db
            .sql("select deadline_at<now() from video_generation_attempts where id=?")
            .param(a.get("id"))
            .query(Boolean.class)
            .single();
    if (expired) {
      s.db
          .sql(
              "update video_generation_attempts set"
                  + " status='TIMED_OUT',recovery_required=true,error_code='POLL_DEADLINE' where"
                  + " id=?")
          .param(a.get("id"))
          .update();
      s.move(v, "GENERATION_FAILED", "REMOTE_JOB_TIMED_OUT_RECONCILE");
      return;
    }
    var permit =
        limiter.acquireVideo(
            "video:" + a.get("provider"),
            (UUID) a.get("id"),
            new RateLimit(requestsPerMinute, httpConcurrency),
            Duration.ofMinutes(2));
    if (!permit.acquired()) {
      defer((UUID) v.get("id"), pollSeconds);
      return;
    }
    try {
      var state =
          s.router
              .provider(a.get("provider").toString())
              .status(a.get("provider_job_id").toString());
      String next =
          switch (state.state()) {
            case "QUEUED" -> "PROVIDER_QUEUED";
            case "PROCESSING" -> "PROVIDER_PROCESSING";
            case "SUCCEEDED" -> "DOWNLOADING";
            case "CANCELLED" -> "CANCELLED";
            default -> "PROVIDER_FAILED";
          };
      s.db
          .sql(
              "update video_generation_attempts set"
                  + " status=?,provider_metadata=provider_metadata||?::jsonb,poll_attempts=poll_attempts+1,started_at=case"
                  + " when ?='PROVIDER_PROCESSING' then coalesce(started_at,now()) else started_at"
                  + " end,next_poll_at=now()+(?*interval '1 second') where id=?")
          .params(next, write(state.metadata()), next, pollSeconds, a.get("id"))
          .update();
      if (state.actualCost() != null) {
        s.db
            .sql(
                "update generation_costs set actual_cost=?,currency=?,pricing_status='ACTUAL' where"
                    + " id=?")
            .params(state.actualCost(), state.currency(), a.get("cost_id"))
            .update();
      }
      if (next.equals("PROVIDER_FAILED")) {
        s.db
            .sql("update generation_costs set outcome='FAILED' where id=?")
            .param(a.get("cost_id"))
            .update();
        fallback(v, attempt((UUID) a.get("id")));
      } else if (next.equals("CANCELLED")) {
        s.move(v, "GENERATION_FAILED", "PROVIDER_CANCELLED");
      } else {
        defer((UUID) v.get("id"), next.equals("DOWNLOADING") ? 0 : pollSeconds);
      }
    } catch (RuntimeException error) {
      s.db
          .sql(
              "update video_generation_attempts set"
                  + " poll_attempts=poll_attempts+1,error_code='POLL_TRANSIENT_ERROR' where id=?")
          .param(a.get("id"))
          .update();
      defer((UUID) v.get("id"), Math.min(60, pollSeconds * (1 + integer(a, "poll_attempts", 0))));
    } finally {
      limiter.release(permit.permit());
    }
  }

  void fallback(Map<String, Object> v, Map<String, Object> a) {
    var route = (List<?>) v.get("route");
    int next = integer(v, "route_index", 0) + 1;
    if (next >= route.size() || integer(v, "attempt_count", 0) >= integer(v, "max_attempts", 3)) {
      s.move(s.one((UUID) v.get("id")), "GENERATION_FAILED", "PROVIDER_FAILED");
      return;
    }
    s.tx.executeWithoutResult(
        t -> {
          s.db
              .sql(
                  "update video_productions set"
                      + " route_index=?,current_attempt_id=null,status='GENERATION_QUEUED',revision=revision+1,available_at=now()"
                      + " where id=? and status<>'CANCELLED'")
              .params(next, v.get("id"))
              .update();
          s.event((UUID) v.get("id"), "FALLBACK_SELECTED", Map.of("routeIndex", next));
        });
  }

  void download(Map<String, Object> v, Map<String, Object> a) {
    if (!downloads.tryAcquire()) {
      defer((UUID) v.get("id"), 2);
      return;
    }
    try {
      var r = request(a);
      var image = s.source(r.sourceAssetId());
      var result =
          s.router
              .provider(a.get("provider").toString())
              .result(
                  a.get("provider_job_id").toString(),
                  r,
                  s.storage.read(image.get("storage_key").toString()),
                  image.get("media_type").toString());
      if (result.bytes().length == 0
          || result.bytes().length > 134217728
          || !result.mediaType().equals("video/mp4")) {
        throw new VideoFailure("INVALID_VIDEO_RESULT");
      }
      String sha = PerceptualHash.sha(result.bytes()),
          key = "video/raw/" + r.generationId() + "/" + sha + ".mp4";
      putImmutable(key, result.bytes(), result.mediaType());
      UUID raw =
          UUID.nameUUIDFromBytes(
              ("raw-video:" + a.get("id")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      s.tx.executeWithoutResult(
          t -> {
            s.lock((UUID) v.get("id"));
            var fresh = s.one((UUID) v.get("id"));
            s.db
                .sql(
                    "update video_generation_attempts set"
                        + " status='GENERATED',completed_at=now(),duration_ms=(extract(epoch"
                        + " from(now()-created_at))*1000)::bigint,provider_metadata=provider_metadata||?::jsonb"
                        + " where id=?")
                .params(write(result.metadata()), a.get("id"))
                .update();
            s.db
                .sql("update generation_costs set outcome='SUCCEEDED' where id=?")
                .param(a.get("cost_id"))
                .update();
            if (fresh.get("status").equals("CANCELLED")) {
              return;
            }
            s.db
                .sql(
                    "insert into"
                        + " assets(id,generation_id,storage_key,sha256,media_type,size_bytes,width,height)"
                        + " values(?,?,?,?,?,?,?,?) on conflict(generation_id) do nothing")
                .params(
                    raw,
                    r.generationId(),
                    key,
                    sha,
                    "video/mp4",
                    result.bytes().length,
                    r.width(),
                    r.height())
                .update();
            s.db
                .sql("update video_productions set raw_asset_id=? where id=?")
                .params(raw, v.get("id"))
                .update();
            s.db
                .sql(
                    "update generations set status='GENERATED',final_provider=?,model=? where"
                        + " id=?")
                .params(a.get("provider"), a.get("model"), r.generationId())
                .update();
            s.move(fresh, "RAW_READY", "");
          });
    } catch (RuntimeException error) {
      int failures = integer(a, "download_attempts", 0) + 1;
      s.db
          .sql(
              "update video_generation_attempts set"
                  + " download_attempts=?,error_code='DOWNLOAD_FAILED',status=case when ?>=3 then"
                  + " 'DOWNLOAD_FAILED' else status end where id=?")
          .params(failures, failures, a.get("id"))
          .update();
      if (failures >= 3) {
        s.move(s.one((UUID) v.get("id")), "GENERATION_FAILED", "DOWNLOAD_FAILED_RETRY_SAME_JOB");
      } else {
        defer((UUID) v.get("id"), 10);
      }
    } finally {
      downloads.release();
    }
  }

  public void putImmutable(String key, byte[] bytes, String type) {
    try {
      s.storage.putOriginal(key, bytes, type);
    } catch (RuntimeException failure) {
      try {
        if (PerceptualHash.sha(s.storage.read(key)).equals(PerceptualHash.sha(bytes))) {
          return;
        }
      } catch (RuntimeException ignored) {
      }
      throw failure;
    }
  }

  void defer(UUID id, long seconds) {
    s.db
        .sql("update video_productions set available_at=now()+(?*interval '1 second') where id=?")
        .params(seconds, id)
        .update();
  }

  public Object reconcile(UUID id, int revision, String providerJobId) {
    return s.tx.execute(
        t -> {
          s.lock(id);
          var v = s.one(id);
          if (integer(v, "revision", 0) != revision
              || v.get("current_attempt_id") == null) {
            throw VideoProductionService.conflict("Video changed");
          }
          var a = attempt((UUID) v.get("current_attempt_id"));
          if (!Set.of("SUBMISSION_UNKNOWN", "TIMED_OUT", "DOWNLOAD_FAILED")
              .contains(a.get("status"))) {
            throw VideoProductionService.conflict("No uncertain job to reconcile");
          }
          String job =
              providerJobId == null
                  ? Objects.toString(a.get("provider_job_id"), null)
                  : providerJobId;
          if (job == null || job.isBlank() || job.length() > 200) {
            throw new IllegalArgumentException("Known provider job ID required");
          }
          s.db
              .sql(
                  "update video_generation_attempts set"
                      + " provider_job_id=?,status=?,recovery_required=false,download_attempts=0,deadline_at=now()+interval"
                      + " '15 minutes' where id=?")
              .params(
                  job,
                  a.get("status").equals("DOWNLOAD_FAILED") ? "DOWNLOADING" : "SUBMITTED",
                  a.get("id"))
              .update();
          // Reconciliation of a cancelled production only lets cancellation reach the known job.
          if (!"CANCELLED".equals(v.get("status"))) {
            s.move(v, "GENERATING", "");
          }
          defer(id, 0);
          s.event(id, "REMOTE_JOB_RECONCILED", Map.of("attemptId", a.get("id")));
          return s.one(id);
        });
  }

  public void close() {
    pool.shutdownNow();
  }
}
