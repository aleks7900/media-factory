package com.mediafactory.provider;

import java.util.Locale;

/**
 * Standard image generation provider representations, allowing mapping between
 * canonical provider IDs ("mock", "openai", "gemini"), display names, and
 * standard system enum constants.
 */
public enum ImageProviderType {
  MOCK("mock", "studio-mock-v1", "Mock Studio"),
  GPT_IMAGE_2("openai", "gpt-image-2", "GPT Image 2"),
  GEMINI_3_1_FLASH_IMAGE("gemini", "gemini-3.1-flash-image", "Gemini 3.1 Flash Image");

  private final String providerId;
  private final String defaultModel;
  private final String displayName;

  ImageProviderType(String providerId, String defaultModel, String displayName) {
    this.providerId = providerId;
    this.defaultModel = defaultModel;
    this.displayName = displayName;
  }

  public String providerId() {
    return providerId;
  }

  public String defaultModel() {
    return defaultModel;
  }

  public String displayName() {
    return displayName;
  }

  /**
   * Resolves any raw user input, enum name, or alias to the normalized provider identifier.
   */
  public static String resolveProviderId(String input) {
    if (input == null || input.isBlank()) {
      return null;
    }
    String clean = input.trim();
    for (ImageProviderType type : values()) {
      if (type.name().equalsIgnoreCase(clean)
          || type.providerId().equalsIgnoreCase(clean)
          || type.displayName().equalsIgnoreCase(clean)) {
        return type.providerId();
      }
    }
    String lower = clean.toLowerCase(Locale.ROOT);
    if (lower.equals("google") || lower.contains("gemini")) {
      return GEMINI_3_1_FLASH_IMAGE.providerId();
    }
    if (lower.equals("openai") || lower.contains("gpt")) {
      return GPT_IMAGE_2.providerId();
    }
    if (lower.contains("mock")) {
      return MOCK.providerId();
    }
    return clean;
  }

  /**
   * Resolves default model if none specified for the given provider input.
   */
  public static String resolveDefaultModel(String providerInput, String modelInput) {
    if (modelInput != null && !modelInput.isBlank()) {
      return modelInput.trim();
    }
    String providerId = resolveProviderId(providerInput);
    if (providerId == null) {
      return null;
    }
    for (ImageProviderType type : values()) {
      if (type.providerId().equalsIgnoreCase(providerId)) {
        return type.defaultModel();
      }
    }
    return null;
  }
}
