package com.mediafactory.feedback;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "media.worker.enabled", havingValue = "true", matchIfMissing = true)
public class FeedbackWorker {

  private final FeedbackJobs jobs;
  private final VisualPatternAnalysisService patterns;

  public FeedbackWorker(FeedbackJobs jobs, VisualPatternAnalysisService patterns) {
    this.jobs = jobs;
    this.patterns = patterns;
  }

  @Scheduled(fixedDelayString = "${feedback.poll-delay-ms:5000}")
  public void work() {
    jobs.runOne();
  }

  @Scheduled(fixedDelayString = "${feedback.stale-check-ms:3600000}")
  public void stale() {
    patterns.stale();
  }
}
