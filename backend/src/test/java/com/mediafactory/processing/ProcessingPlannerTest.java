package com.mediafactory.processing;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ProcessingPlannerTest {
  ProcessingPlanner planner = new ProcessingPlanner();

  Map<String, Object> profile(int w, int h) {
    return new HashMap<>(Map.of("width", w, "height", h, "mode", "FIT", "format", "JPEG"));
  }

  @Test
  void smallestNecessaryFactor() {
    assertThat(planner.scale(1000, 1000, profile(900, 900))).isEqualTo(1);
    assertThat(planner.scale(1000, 1000, profile(1800, 1800))).isEqualTo(2);
    assertThat(planner.scale(1000, 1000, profile(3000, 3000))).isEqualTo(4);
  }

  @Test
  void stockUsesUnroundedPixels() {
    var p = profile(2000, 2000);
    p.put("mode", "PRESERVE");
    p.put("minimumMegapixels", 4);
    assertThat(planner.scale(2000, 2000, p)).isEqualTo(1);
    assertThat(planner.scale(1999, 2000, p)).isEqualTo(2);
  }

  @Test
  void preventsHugeOutputs() {
    assertThatThrownBy(() -> planner.scale(1000, 1000, profile(8192, 8192)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ProcessingPlanner.dimensions(8192, 8192))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void stableCacheIdentity() {
    assertThat(ProcessingPlanner.hash(Map.of("b", 2, "a", 1)))
        .isEqualTo(ProcessingPlanner.hash(new LinkedHashMap<>(Map.of("a", 1, "b", 2))));
    assertThat(ProcessingPlanner.hash(Map.of("version", 1)))
        .isNotEqualTo(ProcessingPlanner.hash(Map.of("version", 2)));
  }

  @Test
  void dagSharesUpscale() {
    var a =
        Map.<String, Object>of(
            "id",
            UUID.randomUUID(),
            "key",
            "STOCK",
            "version",
            1,
            "definition",
            profile(1800, 1800));
    var b =
        Map.<String, Object>of(
            "id",
            UUID.randomUUID(),
            "key",
            "SOCIAL",
            "version",
            1,
            "definition",
            profile(2000, 2000));
    var plan = planner.plan(UUID.randomUUID(), "a".repeat(64), 1000, 1000, List.of(a, b), Map.of());
    assertThat((List<?>) plan.get("nodes")).hasSize(3);
    assertThat(ProcessingJson.write(plan)).contains("upscale-2");
  }

  @Test
  void failureRetryClassification() {
    assertThat(new ProcessingFailure("GPU_OOM").retryable()).isFalse();
    assertThat(new ProcessingFailure("CORRUPT_SOURCE").retryable()).isFalse();
    assertThat(new ProcessingFailure("WORKER_BUSY").retryable()).isTrue();
  }
}
