package com.mediafactory.provider;

import com.mediafactory.provider.ProviderTypes.Media;
import com.mediafactory.provider.ProviderTypes.Request;
import com.mediafactory.provider.ProviderTypes.Result;
import com.mediafactory.provider.ProviderTypes.Usage;

public interface ImageGenerationProvider {

  String providerId();

  ProviderCapabilities capabilities();

  default boolean replaySafe() {
    return false;
  }

  default boolean configured() {
    return true;
  }

  /**
   * Estimate is journaled before execution, including attempts that fail without a result.
   */
  Usage estimate(Request request);

  Result<Media> generate(Request request);
}
