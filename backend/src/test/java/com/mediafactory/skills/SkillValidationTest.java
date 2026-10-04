package com.mediafactory.skills;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SkillValidationTest {
  @Test
  void rejectsUnsafeSourceAndPath() {
    var evidence =
        Map.of(
            "source",
            "fixture",
            "url",
            "file:///etc/passwd",
            "observedAt",
            "2026-01-01T00:00:00Z",
            "sourceType",
            "PAGE",
            "observation",
            "text");
    assertThatThrownBy(
            () ->
                SkillPlanService.validateResearch(
                    Map.of(
                        "directions",
                        List.of(
                            Map.of(
                                "name",
                                "test",
                                "description",
                                "test",
                                "evidence",
                                List.of(evidence))))))
        .hasMessageContaining("HTTP");
    assertThatThrownBy(() -> SkillPlanService.slug("../outside"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unsupportedOptionsAreNotIgnored() {
    assertThatThrownBy(() -> SkillPlanService.keys(Map.of("skipQa", true), "profile"))
        .hasMessageContaining("Unsupported");
  }

  @Test
  void providerErrorsAreNotEchoed() {
    assertThat(
            SkillExecutionService.classify(new RuntimeException("token=secret from remote host")))
        .isEqualTo("UNKNOWN");
  }
}
