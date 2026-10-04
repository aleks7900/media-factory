package com.mediafactory.provider.video.runway;

import com.mediafactory.provider.video.VideoTypes.*;
import com.mediafactory.video.VideoFailure;
import java.util.*;

public final class RunwayVideoMapper {

  private RunwayVideoMapper() {
  }

  public static Map<String, Object> request(Request r, byte[] bytes, String mediaType) {
    if (bytes.length > 3_700_000) {
      throw new VideoFailure("RUNWAY_IMAGE_DATA_URI_LIMIT");
    }
    if (!Set.of("image/png", "image/jpeg", "image/webp").contains(mediaType)) {
      throw new VideoFailure("RUNWAY_UNSUPPORTED_IMAGE");
    }
    if (r.prompt().length() > 1000) {
      throw new VideoFailure("RUNWAY_PROMPT_TOO_LONG");
    }
    var body = new LinkedHashMap<String, Object>();
    body.put("model", r.model());
    body.put(
        "promptImage",
        "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes));
    body.put("promptText", r.prompt());
    body.put("ratio", r.width() + ":" + r.height());
    body.put("duration", r.durationSeconds());
    if (r.seed() != null) {
      if (r.seed() < 0 || r.seed() > 4294967295L) {
        throw new VideoFailure("RUNWAY_SEED_RANGE");
      }
      body.put("seed", r.seed());
    }
    return body;
  }

  public static Status status(Map<String, Object> data) {
    String status = Objects.toString(data.get("status"), "UNKNOWN");
    String state =
        switch (status) {
          case "PENDING", "THROTTLED" -> "QUEUED";
          case "RUNNING" -> "PROCESSING";
          case "SUCCEEDED" -> "SUCCEEDED";
          case "FAILED" -> "FAILED";
          case "CANCELED", "CANCELLED" -> "CANCELLED";
          default -> throw new VideoFailure("UNKNOWN_REMOTE_STATUS");
        };
    return new Status(
        state,
        null,
        "USD",
        state.equals("FAILED") ? "REMOTE_GENERATION_FAILED" : null,
        Map.of("remoteStatus", status));
  }
}
