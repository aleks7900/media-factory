package com.mediafactory.processing;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Engine-neutral execution boundary. Domain code never sees CLI flags or model file paths.
 */
public interface ProcessingProvider {

  Output execute(UUID runId, byte[] source, Map<String, Object> parameters);

  Map<String, Object> capabilities();

  Map<String, Object> cropPreview(
      byte[] source, Map<String, Object> profile, List<Map<String, Object>> focalRegions);

  void cancel(UUID runId);

  record Output(
      byte[] bytes,
      Map<String, Object> validation,
      Map<String, Object> metadata,
      long durationMs) {

  }
}
