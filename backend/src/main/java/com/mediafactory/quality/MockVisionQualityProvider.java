package com.mediafactory.quality;

import com.mediafactory.provider.resilience.ImageGenerationException;
import com.mediafactory.provider.resilience.ImageGenerationException.Type;
import com.mediafactory.quality.QualityModels.Category;
import com.mediafactory.quality.QualityModels.Code;
import com.mediafactory.quality.QualityModels.Dimension;
import com.mediafactory.quality.QualityModels.DimensionName;
import com.mediafactory.quality.QualityModels.Evidence;
import com.mediafactory.quality.QualityModels.Finding;
import com.mediafactory.quality.QualityModels.Severity;
import com.mediafactory.quality.QualityModels.Source;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MockVisionQualityProvider implements VisionQualityProvider {

  public String providerId() {
    return "mock";
  }

  public Set<String> capabilities() {
    return Set.of("image/png", "image/jpeg", "STRUCTURED_EVIDENCE", "DETERMINISTIC_SCENARIOS");
  }

  public Result analyze(Request request) {
    Scenario scenario = Scenario.valueOf(request.scenario());
    switch (scenario) {
      case RATE_LIMIT ->
          throw new ImageGenerationException(Type.RATE_LIMIT, "Mock Vision rate limit");
      case TIMEOUT -> throw new ImageGenerationException(Type.TIMEOUT, "Mock Vision timeout");
      case PROVIDER_ERROR ->
          throw new ImageGenerationException(Type.UNAVAILABLE, "Mock Vision provider unavailable");
      case INVALID_RESPONSE -> throw new ImageGenerationException(Type.UNEXPECTED,
          "Vision response failed schema validation");
      default -> {
      }
    }
    var findings = new ArrayList<Finding>();
    switch (scenario) {
      case MINOR_ARTIFACT ->
          findings.add(finding(Category.ARTIFACT, Code.GENERATIVE_ARTIFACT, Severity.MINOR, .95));
      case MAJOR_ARTIFACT ->
          findings.add(finding(Category.ARTIFACT, Code.GENERATIVE_ARTIFACT, Severity.MAJOR, .55));
      case MALFORMED_FACE ->
          findings.add(finding(Category.ANATOMY, Code.MALFORMED_FACE, Severity.CRITICAL, .98));
      case UNWANTED_TEXT ->
          findings.add(finding(Category.TEXT, Code.UNWANTED_TEXT, Severity.MAJOR, .98));
      case WATERMARK -> findings.add(
          finding(Category.WATERMARK, Code.POSSIBLE_WATERMARK, Severity.CRITICAL, .98));
      case LOW_PROMPT_COMPLIANCE -> findings.add(
          new Finding(Category.PROMPT_COMPLIANCE, Code.PROMPT_REQUIREMENT_MISSING, Severity.MAJOR,
              .9, true, Source.VISION_MODEL, "Mock: a required subject detail is absent",
              Map.of("requirement", "Frozen prompt requirement", "observation",
                  "Mock missing subject detail")));
      default -> {
      }
    }
    var dimensions = new ArrayList<Dimension>();
    for (var name : DimensionName.values()) {
      dimensions.add(new Dimension(name,
          scenario == Scenario.LOW_PROMPT_COMPLIANCE && name == DimensionName.PROMPT_COMPLIANCE
              ? .35 : 1, scenario == Scenario.UNCERTAIN ? .5 : .99,
          name != DimensionName.ANATOMY || scenario == Scenario.MALFORMED_FACE,
          "Deterministic mock " + scenario + "; not an actual visual assessment"));
    }
    return new Result(new Evidence(findings, dimensions), 0L, 0L, BigDecimal.ZERO, "USD",
        "mock-free-v1", null, Map.of("scenario", scenario.name()));
  }

  private Finding finding(Category category, Code code, Severity severity, double confidence) {
    return new Finding(category, code, severity, confidence, true, Source.VISION_MODEL,
        "Mock fixture: " + code, Map.of());
  }

  public enum Scenario {PERFECT, MINOR_ARTIFACT, MAJOR_ARTIFACT, MALFORMED_FACE, UNWANTED_TEXT, WATERMARK, LOW_PROMPT_COMPLIANCE, UNCERTAIN, RATE_LIMIT, TIMEOUT, INVALID_RESPONSE, PROVIDER_ERROR}
}
