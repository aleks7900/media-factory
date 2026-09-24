package com.mediafactory.similarity;

import com.mediafactory.provider.resilience.ImageGenerationException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class LocalClipEmbeddingProvider implements ImageEmbeddingProvider {
  private final URI endpoint;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  public LocalClipEmbeddingProvider(
      @Value("${media.similarity.embedding.endpoint:http://localhost:8001}") String endpoint) {
    this.endpoint = URI.create(endpoint);
  }

  public String providerId() {
    return "local-clip";
  }

  public Model modelMetadata() {
    return metadata(call("/health", null));
  }

  private Model metadata(Map<String, Object> body) {
    return new Model(
        null,
        body.get("provider").toString(),
        body.get("model").toString(),
        body.get("version").toString(),
        ((Number) body.get("dimension")).intValue(),
        body.get("preprocessing").toString());
  }

  public Result embed(List<Input> inputs, Model expected) {
    if (inputs.isEmpty() || inputs.size() > 16)
      throw new IllegalArgumentException("Embedding batch must contain 1–16 images");
    return result(
        call(
            "/v1/images/embed",
            Map.of(
                "images",
                inputs.stream()
                    .map(
                        i ->
                            Map.of(
                                "id",
                                i.assetId().toString(),
                                "data",
                                Base64.getEncoder().encodeToString(i.bytes())))
                    .toList())),
        expected,
        inputs.size());
  }

  public Result embedText(List<String> texts, Model expected) {
    return result(call("/v1/texts/embed", Map.of("texts", texts)), expected, texts.size());
  }

  private Result result(Map<String, Object> body, Model expected, int count) {
    Model actual = metadata(body);
    if (!actual.provider().equals(expected.provider())
        || !actual.model().equals(expected.model())
        || !actual.version().equals(expected.version())
        || actual.dimension() != expected.dimension()
        || !actual.preprocessing().equals(expected.preprocessing()))
      throw new IllegalArgumentException(
          "Worker model identity differs from requested immutable model");
    @SuppressWarnings("unchecked")
    var rows = (List<List<Number>>) body.remove("vectors");
    if (rows == null || rows.size() != count)
      throw new IllegalArgumentException("Incomplete embedding batch");
    var vectors = new ArrayList<float[]>();
    for (var row : rows) {
      float[] v = new float[row.size()];
      for (int i = 0; i < v.length; i++) v[i] = row.get(i).floatValue();
      vectors.add(ImageEmbeddingProvider.validate(v, expected.dimension()));
    }
    return new Result(List.copyOf(vectors), expected, Collections.unmodifiableMap(body));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> call(String path, Object body) {
    try {
      var builder = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(120));
      if (body != null)
        builder
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
      else builder.GET();
      var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
      byte[] bytes;
      try (var stream = response.body()) {
        bytes = stream.readNBytes(2 * 1024 * 1024 + 1);
      }
      if (bytes.length > 2 * 1024 * 1024)
        throw new IllegalArgumentException("Embedding response too large");
      if (response.statusCode() != 200)
        throw new ImageGenerationException(
            response.statusCode() < 500
                ? ImageGenerationException.Type.INVALID_REQUEST
                : ImageGenerationException.Type.UNAVAILABLE,
            "Local embedding worker returned HTTP " + response.statusCode());
      return new LinkedHashMap<>(JSON.readValue(bytes, Map.class));
    } catch (ImageGenerationException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ImageGenerationException(
          ImageGenerationException.Type.UNEXPECTED, "Embedding inference interrupted");
    } catch (Exception e) {
      throw new ImageGenerationException(
          ImageGenerationException.Type.UNAVAILABLE,
          "Local embedding worker unavailable or incompatible");
    }
  }
}
