package com.mediafactory.provider;

import com.mediafactory.provider.ProviderTypes.Request;
import com.mediafactory.provider.ProviderTypes.Result;

public interface TextGenerationProvider {

  Result<String> generateText(Request request);
}
