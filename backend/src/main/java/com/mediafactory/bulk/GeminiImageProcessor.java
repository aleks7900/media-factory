package com.mediafactory.bulk;

import com.mediafactory.provider.routing.ImageProviderRouter;
import org.springframework.stereotype.Component;

/**
 * Dedicated BulkProcessor for GEMINI_IMAGE batches.
 * Shares the unified image processor implementation with GptImageProcessor.
 */
@Component
public class GeminiImageProcessor extends GptImageProcessor {

  public GeminiImageProcessor(ImageProviderRouter router) {
    super(router);
  }

  @Override
  public String kind() {
    return "GEMINI_IMAGE";
  }
}
