package com.mediafactory.analytics;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "media.worker.enabled", havingValue = "true", matchIfMissing = true)
public class AnalyticsWorker {

  private final AnalyticsJobs jobs;

  public AnalyticsWorker(AnalyticsJobs jobs) {
    this.jobs = jobs;
  }

  @Scheduled(fixedDelayString = "${media.analytics.job-delay-ms:5000}")
  public void poll() {
    jobs.runOne();
  }

  @Scheduled(fixedDelayString = "${media.analytics.aggregate-delay-ms:60000}")
  public void aggregate() {
    jobs.scheduleRollup();
  }
}
