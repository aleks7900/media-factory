package com.mediafactory.similarity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ImageEmbeddingProvider {
  record Model(
      UUID id,
      String provider,
      String model,
      String version,
      int dimension,
      String preprocessing) {}

  record Input(UUID assetId, byte[] bytes) {}

  record Result(List<float[]> vectors, Model model, Map<String, Object> metadata) {}

  String providerId();

  Result embed(List<Input> inputs, Model expected);

  Result embedText(List<String> texts, Model expected);

  Model modelMetadata();

  static float[] validate(float[] vector, int dimension) {
    if (vector.length != dimension)
      throw new IllegalArgumentException("Embedding dimension mismatch");
    double norm = 0;
    for (float value : vector) {
      if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite embedding");
      norm += value * value;
    }
    if (Math.abs(Math.sqrt(norm) - 1) > .001)
      throw new IllegalArgumentException("Embedding is not L2 normalized");
    return vector.clone();
  }

  static String literal(float[] vector) {
    return java.util.Arrays.toString(vector);
  }
}
