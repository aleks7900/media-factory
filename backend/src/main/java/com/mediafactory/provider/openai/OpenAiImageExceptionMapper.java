package com.mediafactory.provider.openai;

import com.mediafactory.provider.resilience.ImageGenerationException;
import com.mediafactory.provider.resilience.ImageGenerationException.Type;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import tools.jackson.databind.json.JsonMapper;

public final class OpenAiImageExceptionMapper {

  private OpenAiImageExceptionMapper() {
  }

  public static ImageGenerationException map(int status, byte[] body, String retryAfter,
      String requestId) {
    String code = "";
    try {
      code = JsonMapper.builder().build().readTree(body).path("error").path("code").asText("");
    } catch (Exception ignored) {
    }
    Type type;
    if (code.equals("content_policy_violation") || code.equals("moderation_blocked")) {
      type = Type.CONTENT_POLICY;
    } else if (status == 401 || status == 403 || code.equals("insufficient_quota")) {
      type = Type.AUTHENTICATION;
    } else if (status == 429) {
      type = Type.RATE_LIMIT;
    } else if (status == 408 || status == 504) {
      type = Type.TIMEOUT;
    } else if (status == 500 || status == 502 || status == 503) {
      type = Type.UNAVAILABLE;
    } else if (status >= 400 && status < 500) {
      type = Type.INVALID_REQUEST;
    } else {
      type = Type.UNEXPECTED;
    }
    return new ImageGenerationException(type, "Image provider returned " + type.name(),
        retryAfter(retryAfter, Instant.now()), type == Type.TIMEOUT, requestId);
  }

  static Duration retryAfter(String header, Instant now) {
    if (header == null) {
      return Duration.ZERO;
    }
    try {
      return Duration.ofSeconds(Math.max(0, Long.parseLong(header.trim())));
    } catch (Exception ignored) {
      try {
        var delay = Duration.between(now,
            ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        return delay.isNegative() ? Duration.ZERO : delay;
      } catch (Exception invalid) {
        return Duration.ZERO;
      }
    }
  }
}
