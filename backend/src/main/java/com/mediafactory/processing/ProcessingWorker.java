package com.mediafactory.processing;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "media.worker.enabled", havingValue = "true", matchIfMissing = true)
public class ProcessingWorker {
  private final ProcessingExecutor executor;
  private final ProcessingService service;
  private final JdbcClient db;
  private final String autoProfiles;
  private final ExecutorService thread = Executors.newSingleThreadExecutor();
  private volatile Map<String, Object> active;

  public ProcessingWorker(
      ProcessingExecutor executor,
      ProcessingService service,
      JdbcClient db,
      MeterRegistry metrics,
      @Value("${PROCESSING_AUTO_PROFILES:}") String autoProfiles) {
    this.executor = executor;
    this.service = service;
    this.db = db;
    this.autoProfiles = autoProfiles;
    metrics.gauge(
        "media_factory_processing_queue_size",
        this,
        w ->
            w.db
                .sql("select count(*) from processing_runs where status='PENDING'")
                .query(Long.class)
                .single());
    metrics.gauge("media_factory_gpu_jobs_active", this, w -> w.active == null ? 0 : 1);
  }

  @Scheduled(fixedDelay = 2000)
  public void poll() {
    if (active != null) return;
    executor
        .claim()
        .ifPresent(
            run -> {
              active = run;
              thread.submit(
                  () -> {
                    try {
                      executor.execute(run);
                    } finally {
                      active = null;
                    }
                  });
            });
  }

  @Scheduled(fixedDelay = 15000)
  public void heartbeat() {
    var run = active;
    if (run != null) executor.heartbeat((UUID) run.get("id"), (UUID) run.get("lease_token"));
  }

  @Scheduled(fixedDelay = 30000)
  public void automatic() {
    if (autoProfiles.isBlank()) return;
    for (UUID id :
        db.sql(
                "select a.id from assets a join quality_reviews q on q.id=a.current_review_id where"
                    + " q.final_decision='APPROVED' and not exists(select 1 from processing_runs r"
                    + " where r.source_asset_id=a.id) order by a.created_at limit 20")
            .query(UUID.class)
            .list())
      try {
        service.request(
            id,
            Arrays.stream(autoProfiles.split(",")).map(String::trim).toList(),
            Map.of(),
            null,
            0);
      } catch (IllegalArgumentException ignored) {
      }
  }

  @PreDestroy
  public void close() {
    thread.shutdownNow();
  }
}
