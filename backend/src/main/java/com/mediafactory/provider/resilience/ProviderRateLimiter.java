package com.mediafactory.provider.resilience;

import com.mediafactory.provider.ImageGenerationProperties;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared rolling-minute quotas and leased concurrency permits across all worker instances.
 */
@Service
public class ProviderRateLimiter {

  private final JdbcClient db;
  private final TransactionTemplate tx;
  private final ImageGenerationProperties properties;

  public ProviderRateLimiter(JdbcClient db, TransactionTemplate tx,
      ImageGenerationProperties properties) {
    this.db = db;
    this.tx = tx;
    this.properties = properties;
  }

  public Admission acquire(String provider, UUID jobId) {
    return acquire(provider, jobId, false, properties.provider(provider).rateLimit(),
        properties.leaseDuration());
  }

  public Admission acquire(String provider, UUID jobId, boolean qaJob,
      ImageGenerationProperties.RateLimit rate, Duration lease) {
    return acquireOwned(provider, jobId, qaJob ? "qa_job_id" : "job_id", rate, lease);
  }

  public Admission acquireSimilarity(String provider, UUID jobId,
      ImageGenerationProperties.RateLimit rate, Duration lease) {
    return acquireOwned(provider, jobId, "similarity_job_id", rate, lease);
  }

  private Admission acquireOwned(String provider, UUID jobId, String owner,
      ImageGenerationProperties.RateLimit rate, Duration lease) {
    return tx.execute(s -> {
      db.sql("insert into provider_runtime(provider) values(?) on conflict do nothing")
          .param(provider).update();
      var runtime = db.sql(
              "select *,greatest(0,extract(epoch from (open_until-now()))*1000) as remaining from provider_runtime where provider=? for update")
          .param(provider).query().singleRow();
      long remaining = ((Number) runtime.get("remaining")).longValue();
      if (remaining > 0) {
        return new Admission(null, Duration.ofMillis(remaining), true);
      }
      db.sql("delete from provider_permits where provider=? and expires_at<=now()").param(provider)
          .update();
      db.sql(
              "delete from provider_request_events where provider=? and occurred_at<=now()-interval '1 minute'")
          .param(provider).update();
      int max = rate.concurrentRequests();
      if ("UNAVAILABLE".equals(runtime.get("health"))) {
        max = 1; // half-open single probe, shared across processes
      }
      int active = db.sql("select count(*) from provider_permits where provider=?").param(provider)
          .query(Integer.class).single();
      if (active >= max) {
        return new Admission(null, Duration.ofSeconds(1), false);
      }
      int requests = db.sql("select count(*) from provider_request_events where provider=?")
          .param(provider).query(Integer.class).single();
      if (requests >= rate.requestsPerMinute()) {
        long wait = db.sql(
                "select greatest(1,extract(epoch from (min(occurred_at)+interval '1 minute'-now()))*1000) from provider_request_events where provider=?")
            .param(provider).query(Long.class).single();
        return new Admission(null, Duration.ofMillis(wait + 1), false);
      }
      UUID token = UUID.randomUUID();
      db.sql("insert into provider_permits(token,provider," + owner
              + ",expires_at) values(?,?,?,now()+(? * interval '1 millisecond'))")
          .params(token, provider, jobId, lease.toMillis()).update();
      db.sql("insert into provider_request_events(id,provider) values(?,?)")
          .params(UUID.randomUUID(), provider).update();
      return new Admission(token, Duration.ZERO, false);
    });
  }

  public void release(UUID token) {
    if (token != null) {
      db.sql("delete from provider_permits where token=?").param(token).update();
    }
  }

  public void observe(String provider, ImageGenerationException error) {
    observe(provider, error, properties.provider(provider).circuit());
  }

  public void observe(String provider, ImageGenerationException error,
      ImageGenerationProperties.Circuit circuit) {
    tx.executeWithoutResult(s -> {
      db.sql("insert into provider_runtime(provider) values(?) on conflict do nothing")
          .param(provider).update();
      var row = db.sql("select * from provider_runtime where provider=? for update").param(provider)
          .query().singleRow();
      if (error == null) {
        db.sql(
                "update provider_runtime set health='HEALTHY',consecutive_failures=0,open_until=null,updated_at=now() where provider=?")
            .param(provider).update();
        return;
      }
      if (error.type() == ImageGenerationException.Type.INVALID_REQUEST
          || error.type() == ImageGenerationException.Type.CONTENT_POLICY) {
        return;
      }
      int failures = ((Number) row.get("consecutive_failures")).intValue() + 1;
      boolean open = failures >= circuit.failureThreshold()
          || error.type() == ImageGenerationException.Type.AUTHENTICATION;
      long delay = Math.max(circuit.cooldown().toMillis(), error.retryAfter().toMillis());
      db.sql(
              "update provider_runtime set health=?,consecutive_failures=?,open_until=case when ? then now()+(? * interval '1 millisecond') else null end,updated_at=now() where provider=?")
          .params(open ? "UNAVAILABLE" : "DEGRADED", failures, open, delay, provider).update();
    });
  }

  public record Admission(UUID permit, Duration waitFor, boolean circuitOpen) {

    public boolean acquired() {
      return permit != null;
    }
  }
}
