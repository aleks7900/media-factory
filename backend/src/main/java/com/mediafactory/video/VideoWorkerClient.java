package com.mediafactory.video;

import static com.mediafactory.processing.ProcessingJson.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class VideoWorkerClient {

  private final URI endpoint;
  private final HttpClient client =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(5))
          .build();

  public VideoWorkerClient(
      @Value("${VIDEO_WORKER_ENDPOINT:http://localhost:8003}") String endpoint) {
    this.endpoint = URI.create(endpoint);
  }

  public Map<String, Object> health() {
    return call("/health", null, 15);
  }

  public Map<String, Object> execute(
      UUID run,
      byte[] input,
      String operation,
      Map<String, Object> profile,
      Map<String, Object> motion,
      Map<String, Object> variants) {
    return call(
        "/v1/execute",
        Map.of(
            "runId",
            run,
            "operation",
            operation,
            "data",
            Base64.getEncoder().encodeToString(input),
            "profile",
            profile,
            "motion",
            motion,
            "variants",
            variants),
        900);
  }

  public void cancel(UUID run) {
    call("/v1/cancel/" + run, Map.of(), 10);
  }

  Map<String, Object> call(String path, Object body, int seconds) {
    try {
      var builder =
          HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(seconds));
      if (body == null) {
        builder.GET();
      } else {
        builder
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(write(body)));
      }
      var response = client.send(builder.build(), info -> new BoundedVideoBody(190_000_000));
      var result = map(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        throw new VideoFailure(Objects.toString(result.get("code"), "WORKER_FAILED"));
      }
      return result;
    } catch (VideoFailure e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new VideoFailure("WORKER_INTERRUPTED");
    } catch (Exception e) {
      throw new VideoFailure("WORKER_UNAVAILABLE");
    }
  }
}
