package com.mediafactory.prompt;
import com.mediafactory.provider.ProviderCapabilities;
import com.mediafactory.prompt.PromptModels.*;
public interface ProviderPromptAdapter {
 String providerId();
 AdaptedPrompt adapt(CanonicalPrompt prompt,ProviderCapabilities capabilities);
}
