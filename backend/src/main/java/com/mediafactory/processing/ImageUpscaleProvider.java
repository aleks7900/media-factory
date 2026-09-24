package com.mediafactory.processing;

import java.util.*;

public interface ImageUpscaleProvider {
  record Request(
      UUID runId, UUID inputAssetId, byte[] source, int targetScale, boolean requireGpu) {}

  ProcessingProvider.Output upscale(Request request);

  Map<String, Object> capabilities();
}
