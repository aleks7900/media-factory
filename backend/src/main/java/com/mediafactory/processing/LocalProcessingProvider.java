package com.mediafactory.processing;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalProcessingProvider
    implements ProcessingProvider, ImageUpscaleProvider, SubjectDetectionProvider {

  private final URI endpoint;
  private final HttpClient client =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(5)).build();

  public LocalProcessingProvider(
      @Value("${PROCESSING_ENDPOINT:http://localhost:8002}") String endpoint) {
    this.endpoint = URI.create(endpoint);
  }

  public Map<String, Object> capabilities() {
    return call("/health", null, 15);
  }

  public Output upscale(ImageUpscaleProvider.Request request) {
    return execute(
        request.runId(),
        request.source(),
        Map.of(
            "operation",
            "UPSCALE",
            "scale",
            request.targetScale(),
            "requireGpu",
            request.requireGpu()));
  }

  public Map<String, Object> detect(
      byte[] source, Map<String, Object> profile, List<Map<String, Object>> regions) {
    return cropPreview(source, profile, regions);
  }

  public Map<String, Object> cropPreview(
      byte[] source, Map<String, Object> profile, List<Map<String, Object>> regions) {
    return call(
        "/v1/crop-preview",
        Map.of(
            "data",
            Base64.getEncoder().encodeToString(source),
            "profile",
            profile,
            "focalRegions",
            regions),
        120);
  }

  public void cancel(UUID id) {
    call("/v1/cancel/" + id, Map.of(), 10);
  }

  public Output execute(UUID id, byte[] source, Map<String, Object> parameters) {
    var request = new LinkedHashMap<>(parameters);
    request.put("runId", id.toString());
    request.put("data", Base64.getEncoder().encodeToString(source));
    var response = call("/v1/execute", request, 1800);
    return new Output(
        Base64.getDecoder().decode(response.get("data").toString()),
        ProcessingJson.map(response.get("validation")),
        ProcessingJson.map(response.get("metadata")),
        ((Number) response.get("durationMs")).longValue());
  }

  private Map<String, Object> call(String path, Object body, int seconds) {
    try {
      var builder =
          HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(seconds));
      if (body == null) {
        builder.GET();
      } else {
        builder
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(ProcessingJson.write(body)));
      }
      var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
      byte[] data;
      try (var stream = response.body()) {
        data = stream.readNBytes(90 * 1024 * 1024 + 1);
      }
      if (data.length > 90 * 1024 * 1024) {
        throw new ProcessingFailure("OUTPUT_TOO_LARGE");
      }
      var result = ProcessingJson.map(new String(data, java.nio.charset.StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        throw new ProcessingFailure(result.getOrDefault("code", "WORKER_FAILURE").toString());
      }
      return result;
    } catch (ProcessingFailure e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingFailure("WORKER_UNAVAILABLE");
    } catch (Exception e) {
      throw new ProcessingFailure("WORKER_UNAVAILABLE");
    }
  }
}
