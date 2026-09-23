package com.mediafactory.provider;
import com.mediafactory.provider.ProviderTypes.*;
public interface TextGenerationProvider { Result<String> generateText(Request request); }
