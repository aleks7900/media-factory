package com.mediafactory.provider.video;

import com.mediafactory.provider.video.VideoTypes.*;

/** Async provider-neutral port; legacy synchronous foundation API remains source-compatible. */
public interface VideoGenerationProvider {
  String providerId();

  boolean configured();

  Capabilities capabilities();

  Estimate estimate(Request request);

  default void validateInput(Request request, byte[] source, String mediaType) {
    capabilities().validate(request);
  }

  Submission submit(Request request, byte[] source, String mediaType);

  Status status(String providerJobId);

  Result result(String providerJobId, Request request, byte[] source, String mediaType);

  default void cancel(String providerJobId) {}
}
