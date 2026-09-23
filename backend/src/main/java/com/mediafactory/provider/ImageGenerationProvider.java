package com.mediafactory.provider;
import com.mediafactory.provider.ProviderTypes.*;
public interface ImageGenerationProvider {
 /** Estimate is journaled before execution, including attempts that fail without a result. */
 Usage estimate(Request request);
 Result<Media> generate(Request request);
}
