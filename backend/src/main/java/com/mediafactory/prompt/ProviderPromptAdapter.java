package com.mediafactory.prompt;

import com.mediafactory.prompt.PromptModels.AdaptedPrompt;
import com.mediafactory.prompt.PromptModels.CanonicalPrompt;
import com.mediafactory.provider.ProviderCapabilities;

public interface ProviderPromptAdapter {

  String providerId();

  AdaptedPrompt adapt(CanonicalPrompt prompt, ProviderCapabilities capabilities);
}
