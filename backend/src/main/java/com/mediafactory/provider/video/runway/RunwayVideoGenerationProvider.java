package com.mediafactory.provider.video.runway;

import com.mediafactory.provider.video.VideoGenerationProvider;
import com.mediafactory.provider.video.VideoTypes.*;
import com.mediafactory.video.VideoFailure;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RunwayVideoGenerationProvider implements VideoGenerationProvider {

  final RunwayVideoClient client;
  final RunwayVideoProperties config;

  public RunwayVideoGenerationProvider(RunwayVideoClient client, RunwayVideoProperties config) {
    this.client = client;
    this.config = config;
  }

  public String providerId() {
    return "runway";
  }

  public boolean configured() {
    return config.configured();
  }

  public Capabilities capabilities() {
    return new Capabilities(
        Set.of("gen4_turbo", "gen4.5"),
        Set.of("720:1280", "1280:720", "960:960"),
        Set.of(5, 10),
        false,
        true,
        false,
        false,
        true,
        false,
        false,
        false,
        false,
        false,
        true,
        false,
        false);
  }

  public Estimate estimate(Request r) {
    return new Estimate(
        config.pricePerSecond == null
            ? null
            : config.pricePerSecond.multiply(BigDecimal.valueOf(r.durationSeconds())),
        "USD",
        config.pricePerSecond == null ? "UNKNOWN" : "operator-configured-v1");
  }

  public Submission submit(Request r, byte[] source, String type) {
    capabilities().validate(r);
    var response = client.create(RunwayVideoMapper.request(r, source, type));
    String id;
    try {
      id = response.get("id").toString();
      UUID.fromString(id);
    } catch (RuntimeException invalidResponse) {
      // A successful POST may already have incurred a charge despite a malformed response.
      throw RunwayVideoExceptionMapper.transport(true);
    }
    return new Submission(id, id, Map.of("apiVersion", "2024-11-06"));
  }

  public void validateInput(Request r, byte[] source, String type) {
    capabilities().validate(r);
    RunwayVideoMapper.request(r, source, type);
  }

  public Status status(String id) {
    return RunwayVideoMapper.status(client.task(id));
  }

  public Result result(String id, Request r, byte[] source, String type) {
    var task = client.task(id);
    if (!"SUCCEEDED".equals(task.get("status"))) {
      throw new VideoFailure("REMOTE_RESULT_NOT_READY");
    }
    var urls = (List<?>) task.get("output");
    if (urls == null || urls.isEmpty()) {
      throw new VideoFailure("REMOTE_RESULT_MISSING");
    }
    return new Result(
        client.download(urls.getFirst().toString()), "video/mp4", Map.of("providerJobId", id));
  }

  public void cancel(String id) {
    client.cancel(id);
  }
}
