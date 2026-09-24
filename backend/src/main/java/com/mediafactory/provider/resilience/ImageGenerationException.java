package com.mediafactory.provider.resilience;

import java.time.Duration;

/**
 * Safe messages only. Never attach a raw HTTP/SDK exception or response as the cause.
 */
public class ImageGenerationException extends RuntimeException {

  private final Type type;
  private final Duration retryAfter;
  private final boolean outcomeUnknown;
  private final String requestId;

  public ImageGenerationException(Type type, String safeMessage) {
    this(type, safeMessage, Duration.ZERO, false, null);
  }

  public ImageGenerationException(Type type, String safeMessage, Duration retryAfter,
      boolean outcomeUnknown, String requestId) {
    super(safeMessage);
    this.type = type;
    this.retryAfter = retryAfter;
    this.outcomeUnknown = outcomeUnknown;
    this.requestId = requestId;
  }

  public Type type() {
    return type;
  }

  public Duration retryAfter() {
    return retryAfter;
  }

  public boolean outcomeUnknown() {
    return outcomeUnknown;
  }

  public String requestId() {
    return requestId;
  }

  public boolean retryable() {
    return type == Type.RATE_LIMIT || type == Type.TIMEOUT || type == Type.UNAVAILABLE;
  }

  public boolean fallbackEligible() {
    return retryable() || type == Type.AUTHENTICATION;
  }

  public enum Type {AUTHENTICATION, RATE_LIMIT, TIMEOUT, UNAVAILABLE, INVALID_REQUEST, CONTENT_POLICY, UNEXPECTED}
}
