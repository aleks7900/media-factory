package com.mediafactory.provider;

import com.mediafactory.provider.ProviderTypes.Request;
import com.mediafactory.provider.ProviderTypes.Result;

public interface TextGenerationProvider {

  default java.util.Map<String, String> textIdentity() {
    throw new IllegalStateException("Text provider must declare its provider/model identity");
  }

  Result<String> generateText(Request request);
}
