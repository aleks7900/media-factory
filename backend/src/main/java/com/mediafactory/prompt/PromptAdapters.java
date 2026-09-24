package com.mediafactory.prompt;

import com.mediafactory.prompt.PromptModels.AdaptedPrompt;
import com.mediafactory.prompt.PromptModels.CanonicalPrompt;
import com.mediafactory.provider.ProviderCapabilities;
import java.util.ArrayList;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PromptAdapters {

  @Bean
  ProviderPromptAdapter openAiPromptAdapter() {
    return new ConstraintAdapter("openai", "Avoid the following unwanted elements:\n");
  }

  @Bean
  ProviderPromptAdapter mockPromptAdapter() {
    return new ConstraintAdapter("mock", "Negative constraints:\n");
  }

  public record ConstraintAdapter(String providerId, String prefix) implements
      ProviderPromptAdapter {

    public AdaptedPrompt adapt(CanonicalPrompt p, ProviderCapabilities caps) {
      String positive = p.positivePrompt(), negative = p.negativePrompt();
      var warnings = new ArrayList<String>();
      String strategy = "POSITIVE_ONLY_V1";
      if (!negative.isBlank()) {
        if (caps.supportsNegativePrompt()) {
          strategy = "NEGATIVE_PROMPT_SEPARATE_FIELD_V1";
        } else {
          positive = positive + "\n\n" + prefix + negative;
          negative = "";
          strategy = "NEGATIVE_CONSTRAINTS_MERGED_V1";
          warnings.add(
              "Provider does not support native negative prompts; constraints were appended to the positive prompt.");
        }
      }
      if (positive.length() > 10000 || negative.length() > 10000) {
        throw PromptException.invalid(
            "Adapted prompt exceeds the 10000-character pipeline limit; no truncation was applied");
      }
      if (positive.length() > 8000) {
        warnings.add("Provider length concern: prompt exceeds 8000 characters");
      }
      return new AdaptedPrompt(positive, negative, strategy, warnings,
          positive.length() + negative.length(), (positive.length() + negative.length() + 3) / 4);
    }
  }
}
