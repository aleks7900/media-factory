package com.mediafactory.provider.video.runway;

import com.mediafactory.provider.resilience.ImageGenerationException;
import com.mediafactory.provider.resilience.ImageGenerationException.Type;
import java.time.Duration;

public final class RunwayVideoExceptionMapper {

  private RunwayVideoExceptionMapper() {
  }

  public static ImageGenerationException http(int code, boolean submission, String retry) {
    Type type =
        switch (code) {
          case 401, 403 -> Type.AUTHENTICATION;
          case 429 -> Type.RATE_LIMIT;
          case 400, 404, 422 -> Type.INVALID_REQUEST;
          default -> code >= 500 ? Type.UNAVAILABLE : Type.UNEXPECTED;
        };
    long delay = 10;
    try {
      delay = Math.min(300, Math.max(1, Long.parseLong(retry)));
    } catch (Exception ignored) {
    }
    return new ImageGenerationException(
        type,
        "Runway video request failed (HTTP " + code + ")",
        Duration.ofSeconds(delay),
        submission && code >= 500,
        null);
  }

  public static ImageGenerationException transport(boolean submit) {
    return new ImageGenerationException(
        Type.TIMEOUT, "Runway video transport failed", Duration.ofSeconds(10), submit, null);
  }
}
