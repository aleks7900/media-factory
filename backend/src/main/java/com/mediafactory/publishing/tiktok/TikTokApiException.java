package com.mediafactory.publishing.tiktok;

public class TikTokApiException extends RuntimeException {

  private final int statusCode;
  private final String errorCode;
  private final boolean retryable;
  private final Long retryAfterMs;

  public TikTokApiException(int statusCode, String errorCode, String message, boolean retryable, Long retryAfterMs) {
    super(message);
    this.statusCode = statusCode;
    this.errorCode = errorCode;
    this.retryable = retryable;
    this.retryAfterMs = retryAfterMs;
  }

  public int getStatusCode() {
    return statusCode;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public boolean isRetryable() {
    return retryable;
  }

  public Long getRetryAfterMs() {
    return retryAfterMs;
  }
}
