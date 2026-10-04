package com.mediafactory.skills;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "media.worker.enabled", havingValue = "true", matchIfMissing = true)
public class SkillWorker {
  final SkillExecutionService service;

  public SkillWorker(SkillExecutionService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${codex.skills.poll-delay-ms:5000}")
  public void poll() {
    service.runOne();
  }
}
