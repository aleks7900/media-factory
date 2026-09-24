package com.mediafactory.prompt;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PromptModels {

  private PromptModels() {
  }

  public enum VariableType {STRING, INTEGER, DECIMAL, BOOLEAN, ENUM, STRING_LIST}

  public record PromptVariableDefinition(String name, String label, String description,
                                         VariableType type,
                                         Boolean required, Object defaultValue,
                                         List<String> allowedValues, BigDecimal min, BigDecimal max,
                                         Integer minLength, Integer maxLength,
                                         Integer displayOrder) {

    public PromptVariableDefinition {
      allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
    }
  }

  public record VersionInput(String positiveTemplate, String negativeTemplate,
                             List<PromptVariableDefinition> variables, String changeDescription,
                             Integer revision,
                             UUID copyFromVersionId) {

  }

  public record TemplateInput(String key, String name, String description, String category,
                              String status,
                              Integer revision) {

  }

  public record PresetInput(String key, String name, String description, String category,
                            String status,
                            String positiveFragment, String negativeFragment, Integer revision) {

  }

  public record VariantInput(String key, String name, UUID promptVersionId, Integer weight) {

  }

  public record ExperimentInput(String name, String description, String scope,
                                UUID promptTemplateId,
                                UUID collectionId, String pipelineKey, Boolean allowOverrides,
                                List<VariantInput> variants) {

  }

  public record PromptRenderRequest(UUID promptVersionId, Map<String, Object> variables,
                                    List<String> presets,
                                    String provider, UUID experimentId,
                                    String assignmentKey, UUID conceptId, String pipeline,
                                    String manualPositiveSuffix, String manualNegativeSuffix,
                                    String prompt,
                                    String negativePrompt) {

    public PromptRenderRequest {
      variables = variables == null ? Map.of()
          : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
      presets = presets == null ? List.of() : List.copyOf(presets);
    }

    public static PromptRenderRequest adHoc(String positive, String negative) {
      return new PromptRenderRequest(null, null, null, null, null, null, null, null, null, null,
          positive, negative);
    }
  }

  public record CanonicalPrompt(String positivePrompt, String negativePrompt,
                                Map<String, Object> variables,
                                List<Map<String, Object>> presets) {

  }

  public record AdaptedPrompt(String positivePrompt, String negativePrompt, String strategy,
                              List<String> warnings,
                              int characterCount, int estimatedTokens) {

  }

  public record ResolvedPrompt(String kind, UUID templateId, UUID versionId, Integer version,
                               CanonicalPrompt canonical, Map<String, Object> composition,
                               UUID experimentId, UUID variantId, String assignmentKey,
                               List<String> warnings) {

  }
}
