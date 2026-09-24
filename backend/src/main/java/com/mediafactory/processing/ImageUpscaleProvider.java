package com.mediafactory.processing;

import java.util.Map;
import java.util.UUID;

public interface ImageUpscaleProvider {

  ProcessingProvider.Output upscale(Request request);

  Map<String, Object> capabilities();

  record Request(
      UUID runId, UUID inputAssetId, byte[] source, int targetScale, boolean requireGpu) {

  }
}
