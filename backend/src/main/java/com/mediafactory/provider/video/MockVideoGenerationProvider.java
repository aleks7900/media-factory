package com.mediafactory.provider.video;

import com.mediafactory.provider.video.VideoTypes.*;
import com.mediafactory.video.VideoWorkerClient;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class MockVideoGenerationProvider implements VideoGenerationProvider {

  final VideoWorkerClient worker;

  public MockVideoGenerationProvider(VideoWorkerClient worker) {
    this.worker = worker;
  }

  public String providerId() {
    return "mock-video";
  }

  public boolean configured() {
    return true;
  }

  public Capabilities capabilities() {
    return new Capabilities(
        Set.of("deterministic-motion-v1"),
        Set.of("720:1280", "1280:720", "960:960", "320:240", "240:320"),
        Set.of(2, 5, 10),
        true,
        true,
        true,
        true,
        true,
        false,
        false,
        true,
        false,
        false,
        true,
        true,
        true);
  }

  public Estimate estimate(Request r) {
    return new Estimate(BigDecimal.ZERO, "USD", "mock-free-v1");
  }

  public Submission submit(Request r, byte[] source, String type) {
    capabilities().validate(r);
    return new Submission("mock-" + r.attemptId(), r.attemptId().toString(), Map.of("mock", true));
  }

  public Status status(String id) {
    if (!id.startsWith("mock-")) {
      throw new IllegalArgumentException("Invalid mock task");
    }
    return new Status("SUCCEEDED", BigDecimal.ZERO, "USD", null, Map.of("mock", true));
  }

  public Result result(String id, Request r, byte[] source, String type) {
    var response =
        worker.execute(
            r.attemptId(),
            source,
            "MOCK",
            Map.of(
                "width",
                r.width(),
                "height",
                r.height(),
                "duration",
                r.durationSeconds(),
                "fps",
                r.fps() == null ? 24 : r.fps()),
            Map.of(),
            Map.of());
    return new Result(
        Base64.getDecoder().decode(response.get("data").toString()),
        "video/mp4",
        Map.of("mock", true, "generator", "FFmpeg deterministic image motion"));
  }
}
