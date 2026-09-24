package com.mediafactory.similarity;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

/** Deterministic test fixture, deliberately not a semantic model. Explicit opt-in only. */
@Component
public class MockEmbeddingProvider implements ImageEmbeddingProvider {
  public String providerId() {
    return "mock-embedding";
  }

  public Model modelMetadata() {
    return new Model(
        null, providerId(), "deterministic-test", "v1", 512, "sha-seeded-unit-vector-v1");
  }

  private float[] vector(byte[] bytes, int dimension) {
    var random = new Random(PerceptualHash.sha(bytes).hashCode());
    float[] v = new float[dimension];
    double norm = 0;
    for (int i = 0; i < v.length; i++) {
      v[i] = (float) random.nextGaussian();
      norm += v[i] * v[i];
    }
    for (int i = 0; i < v.length; i++) v[i] /= (float) Math.sqrt(norm);
    return v;
  }

  public Result embed(List<Input> inputs, Model model) {
    return new Result(
        inputs.stream().map(i -> vector(i.bytes(), model.dimension())).toList(),
        model,
        Map.of("device", "cpu", "durationMs", 0, "externalApiCost", 0));
  }

  public Result embedText(List<String> texts, Model model) {
    return new Result(
        texts.stream()
            .map(t -> vector(t.getBytes(StandardCharsets.UTF_8), model.dimension()))
            .toList(),
        model,
        Map.of("device", "cpu"));
  }
}
