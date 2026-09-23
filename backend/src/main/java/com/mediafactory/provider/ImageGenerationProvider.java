package com.mediafactory.provider;
import com.mediafactory.provider.ProviderTypes.*;
public interface ImageGenerationProvider {
 String providerId();
 ProviderCapabilities capabilities();
 default boolean replaySafe() { return false; }
 default boolean configured() { return true; }
 /** Estimate is journaled before execution, including attempts that fail without a result. */
 Usage estimate(Request request);
 Result<Media> generate(Request request);
}
