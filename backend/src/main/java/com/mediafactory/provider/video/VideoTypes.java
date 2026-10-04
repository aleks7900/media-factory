package com.mediafactory.provider.video;

import java.math.BigDecimal;
import java.util.*;

public final class VideoTypes {

  private VideoTypes() {
  }

  public record Request(
      UUID generationId,
      UUID attemptId,
      UUID sourceAssetId,
      String sourceChecksum,
      String prompt,
      String negativePrompt,
      int width,
      int height,
      int durationSeconds,
      Integer fps,
      Long seed,
      Map<String, Object> motion,
      Map<String, Object> options,
      String model) {

  }

  public record Capabilities(
      Set<String> models,
      Set<String> resolutions,
      Set<Integer> durations,
      boolean negativePrompt,
      boolean seed,
      boolean cameraMotion,
      boolean motionStrength,
      boolean firstFrame,
      boolean lastFrame,
      boolean referenceImages,
      boolean loopGeneration,
      boolean audio,
      boolean webhook,
      boolean statusPolling,
      boolean idempotentSubmission,
      boolean configurableFps) {

    public void validate(Request r) {
      if (!models.contains(r.model())
          || !resolutions.contains(r.width() + ":" + r.height())
          || !durations.contains(r.durationSeconds())) {
        throw new IllegalArgumentException("Unsupported model, resolution or duration");
      }
      if (!negativePrompt && r.negativePrompt() != null && !r.negativePrompt().isBlank()) {
        throw new IllegalArgumentException("Negative prompts are not supported by this provider");
      }
      if (!seed && r.seed() != null) {
        throw new IllegalArgumentException("Seed is not supported");
      }
      if (!configurableFps && r.fps() != null) {
        throw new IllegalArgumentException(
            "Generation FPS is provider-controlled; configure output FPS in the processing"
                + " profile");
      }
    }
  }

  public record Submission(String providerJobId, String requestId, Map<String, Object> metadata) {

  }

  public record Status(
      String state,
      BigDecimal actualCost,
      String currency,
      String errorCode,
      Map<String, Object> metadata) {

  }

  public record Result(byte[] bytes, String mediaType, Map<String, Object> metadata) {

  }

  public record Estimate(BigDecimal cost, String currency, String pricingVersion) {

  }
}
