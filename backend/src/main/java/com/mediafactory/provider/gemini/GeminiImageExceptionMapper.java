package com.mediafactory.provider.gemini;

import com.mediafactory.provider.resilience.ImageGenerationException;
import com.mediafactory.provider.resilience.ImageGenerationException.Type;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import tools.jackson.databind.json.JsonMapper;

public final class GeminiImageExceptionMapper {

  private GeminiImageExceptionMapper() {
  }

  public static ImageGenerationException map(int status, byte[] body, String retryAfter,
      String requestId) {
    String code = "";
    String message = "";
    String errorStatus = "";
    try {
      var root = JsonMapper.builder().build().readTree(body);
      var err = root.path("error");
      code = err.path("code").asText("");
      message = err.path("message").asText("");
      errorStatus = err.path("status").asText("");
    } catch (Exception ignored) {
    }

    String lowerMessage = message.toLowerCase(Locale.ROOT);
    String lowerStatus = errorStatus.toLowerCase(Locale.ROOT);

    Type type;
    if (lowerMessage.contains("safety") || lowerMessage.contains("policy")
        || lowerMessage.contains("blocked") || lowerStatus.contains("safety")) {
      type = Type.CONTENT_POLICY;
    } else if (status == 401 || status == 403 || status == 402
        || lowerMessage.contains("api key") || lowerMessage.contains("credits")
        || lowerStatus.equals("unauthenticated") || lowerStatus.equals("permission_denied")) {
      type = Type.AUTHENTICATION;
    } else if (status == 429 || lowerStatus.equals("resource_exhausted")
        || lowerMessage.contains("quota") || lowerMessage.contains("rate limit")) {
      type = Type.RATE_LIMIT;
    } else if (status == 408 || status == 504 || lowerStatus.equals("deadline_exceeded")) {
      type = Type.TIMEOUT;
    } else if (status == 500 || status == 502 || status == 503 || lowerStatus.equals("unavailable")
        || lowerStatus.equals("internal")) {
      type = Type.UNAVAILABLE;
    } else if (status >= 400 && status < 500) {
      type = Type.INVALID_REQUEST;
    } else {
      type = Type.UNEXPECTED;
    }

    String safeMessage = message.isBlank()
        ? "Gemini image provider returned " + type.name()
        : "Gemini image provider error: " + message;
    return new ImageGenerationException(type, safeMessage,
        retryAfter(retryAfter, Instant.now()), type == Type.TIMEOUT, requestId);
  }

  static Duration retryAfter(String header, Instant now) {
    if (header == null || header.isBlank()) {
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
