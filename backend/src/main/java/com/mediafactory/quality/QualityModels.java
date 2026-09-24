package com.mediafactory.quality;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Provider-neutral evidence contract. Scores and confidence have distinct meanings.
 */
public final class QualityModels {

  private QualityModels() {
  }

  private static void normalized(double x) {
    if (!Double.isFinite(x) || x < 0 || x > 1) {
      throw new IllegalArgumentException("Expected a finite normalized value");
    }
  }

  private static String bounded(String value) {
    if (value == null || value.isBlank() || value.length() > 4000) {
      throw new IllegalArgumentException("Evidence must contain 1–4000 characters");
    }
    return value;
  }

  public enum Severity {INFO, MINOR, MAJOR, CRITICAL}

  public enum Source {TECHNICAL, VISION_MODEL, HUMAN}

  public enum Decision {APPROVED, NEEDS_REVIEW, REJECTED}

  public enum Category {TECHNICAL, ARTIFACT, ANATOMY, TEXT, WATERMARK, PROMPT_COMPLIANCE, COMPOSITION, SUBJECT, CROP, OTHER}

  public enum Code {
    CORRUPT_FILE, UNSUPPORTED_FORMAT, FILE_SIZE, RESOLUTION_TOO_LOW, WRONG_DIMENSIONS, WRONG_ASPECT_RATIO,
    DUPLICATE_SHA256, UNEXPECTED_TRANSPARENCY, BLACK_BORDER, NEAR_SOLID_IMAGE, EXTREME_EXPOSURE, BLUR,
    GENERATIVE_ARTIFACT, REPEATED_PATTERN, BROKEN_GEOMETRY, MALFORMED_FACE, MALFORMED_EYES, EXTRA_LIMB,
    MISSING_LIMB, ANATOMY_INCONSISTENCY, UNWANTED_TEXT, UNWANTED_LOGO, POSSIBLE_WATERMARK,
    SUBJECT_MISSING, SUBJECT_PARTIALLY_MISSING, WRONG_SUBJECT, PROMPT_CONFLICT, PROMPT_REQUIREMENT_MISSING,
    SUBJECT_CROPPED, POOR_COMPOSITION, NEGATIVE_CONSTRAINT_VIOLATION, OTHER
  }

  public enum DimensionName {TECHNICAL_INTEGRITY, PROMPT_COMPLIANCE, SUBJECT_INTEGRITY, ANATOMY, COMPOSITION, VISUAL_COHERENCE, TEXT_FREE, WATERMARK_FREE, CROP_QUALITY}

  public record Finding(Category category, Code code, Severity severity, double confidence,
                        boolean detected,
                        Source source, String evidence, Map<String, String> metadata) {

    public Finding {
      Objects.requireNonNull(category);
      Objects.requireNonNull(code);
      Objects.requireNonNull(severity);
      Objects.requireNonNull(source);
      normalized(confidence);
      evidence = bounded(evidence);
      metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
      if (metadata.size() > 20 || metadata.entrySet().stream()
          .anyMatch(e -> e.getKey().length() > 100 || e.getValue().length() > 4000)) {
        throw new IllegalArgumentException("Finding metadata too large");
      }
    }
  }

  public record Dimension(DimensionName dimension, double score, double confidence,
                          boolean applicable,
                          String evidence) {

    public Dimension {
      Objects.requireNonNull(dimension);
      normalized(score);
      normalized(confidence);
      evidence = bounded(evidence);
    }
  }

  public record Evidence(List<Finding> findings, List<Dimension> dimensions) {

    public Evidence {
      findings = List.copyOf(findings);
      dimensions = List.copyOf(dimensions);
      if (findings.size() > 100 || dimensions.size() > DimensionName.values().length) {
        throw new IllegalArgumentException("Too many evidence items");
      }
      if (dimensions.stream().map(Dimension::dimension).distinct().count() != dimensions.size()) {
        throw new IllegalArgumentException("Duplicate dimension");
      }
    }

    public void validateVision() {
      if (findings.stream().anyMatch(f -> f.source() != Source.VISION_MODEL)) {
        throw new IllegalArgumentException("Invalid Vision evidence source");
      }
      if (dimensions.size() != DimensionName.values().length) {
        throw new IllegalArgumentException("Missing required dimensions");
      }
      if (dimensions.stream()
          .anyMatch(d -> !d.applicable() && d.dimension() != DimensionName.ANATOMY)) {
        throw new IllegalArgumentException("Only anatomy may be inapplicable");
      }
    }
  }

  public record Evaluation(Decision automaticDecision, Decision finalDecision, List<String> rules) {

  }
}
