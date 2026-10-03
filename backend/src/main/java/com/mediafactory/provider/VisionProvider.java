package com.mediafactory.provider;

import com.mediafactory.provider.ProviderTypes.Media;
import com.mediafactory.provider.ProviderTypes.Result;

public interface VisionProvider {

  default java.util.Map<String, String> visionIdentity() {
    throw new IllegalStateException("Vision provider must declare its provider/model identity");
  }

  Result<String> inspect(Media media);

  /** Structured semantic extraction is a separate capability from existing QA/stock inspection. */
  default Result<String> extractVisualAttributes(Media media, String versionedPrompt) {
    throw new UnsupportedOperationException("Provider does not support visual attribute schema v1");
  }
}
