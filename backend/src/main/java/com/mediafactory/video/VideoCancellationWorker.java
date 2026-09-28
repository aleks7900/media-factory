package com.mediafactory.video;

import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "media.worker.enabled", havingValue = "true", matchIfMissing = true)
public class VideoCancellationWorker {
  final VideoProductionService s;
  final VideoWorkerClient worker;

  public VideoCancellationWorker(VideoProductionService s, VideoWorkerClient worker) {
    this.s = s;
    this.worker = worker;
  }

  @Scheduled(fixedDelay = 10000)
  public void tick() {
    for (var row :
        s.db
            .sql(
                "select r.id from video_processing_runs r join video_productions v on"
                    + " v.id=r.production_id where v.status='CANCELLED' and r.status in"
                    + " ('QUEUED','RUNNING') limit 10")
            .query()
            .listOfRows()) {
      try {
        worker.cancel((UUID) row.get("id"));
        s.db
            .sql(
                "update video_processing_runs set"
                    + " status='CANCELLED',completed_at=now(),failure_code='USER_CANCELLED' where"
                    + " id=? and status in ('QUEUED','RUNNING')")
            .param(row.get("id"))
            .update();
      } catch (RuntimeException ignored) {
        /* Durable state retries cancellation on the next tick. */
      }
    }
    for (var a :
        s.db
            .sql(
                "select a.* from video_generation_attempts a join video_productions v on"
                    + " v.id=a.production_id where v.status='CANCELLED' and a.status in"
                    + " ('REQUESTED','SUBMITTED','PROVIDER_QUEUED','PROVIDER_PROCESSING','DOWNLOADING','TIMED_OUT')"
                    + " limit 10")
            .query()
            .listOfRows()) {
      try {
        if (a.get("provider_job_id") != null)
          s.router
              .provider(a.get("provider").toString())
              .cancel(a.get("provider_job_id").toString());
        s.db
            .sql(
                "update video_generation_attempts set"
                    + " status='CANCELLED',completed_at=now(),error_code='USER_CANCELLED' where"
                    + " id=? and status<>'GENERATED'")
            .param(a.get("id"))
            .update();
        s.event(
            (UUID) a.get("production_id"),
            "CANCELLATION_CONFIRMED",
            Map.of("attemptId", a.get("id")));
      } catch (RuntimeException ignored) {
        /* Retain remote capacity and estimated cost until confirmed. */
      }
    }
  }
}
