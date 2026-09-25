package com.mediafactory.wallpaper;

import java.util.Map;

/** Target-specific wire DTOs, credentials and transfer mechanisms stay behind this port. */
public interface WallpaperPublicationTarget {
  String key();

  boolean available();

  Result publish(Request request);

  Result update(Request request);

  Result unpublish(Reference reference);

  record Request(String idempotencyKey, String manifestChecksum, Map<String, Object> manifest) {}

  record Reference(String idempotencyKey, String externalId, int version) {}

  record Result(String externalId, int version, String status, Map<String, Object> evidence) {}

  final class Failure extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public Failure(String code, boolean retryable) {
      super(code);
      this.code = code;
      this.retryable = retryable;
    }

    public String code() {
      return code;
    }

    public boolean retryable() {
      return retryable;
    }
  }
}
