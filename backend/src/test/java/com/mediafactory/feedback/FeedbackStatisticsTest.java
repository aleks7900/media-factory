package com.mediafactory.feedback;

import static org.assertj.core.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.util.*;
import org.junit.jupiter.api.Test;

class FeedbackStatisticsTest {
  @Test
  void exactStatisticsAndReproducibleUncertainty() {
    var d = FeedbackStatistics.distribution(new double[] {1, 2, 3, 4, 100});
    assertThat(d.get("mean")).isEqualTo(22.0);
    assertThat(d.get("median")).isEqualTo(3.0);
    assertThat(d.get("p25")).isEqualTo(2.0);
    assertThat(d.get("p75")).isEqualTo(4.0);
    var a = FeedbackStatistics.compare(new double[] {1, 1, 1}, new double[] {3, 3, 3}, 42, 3);
    assertThat(a.get("absoluteDifference")).isEqualTo(2.0);
    assertThat(a.get("confidenceInterval")).isEqualTo(List.of(2.0, 2.0));
    assertThat(a)
        .isEqualTo(
            FeedbackStatistics.compare(new double[] {1, 1, 1}, new double[] {3, 3, 3}, 42, 3));
  }

  @Test
  void guardsNoEffectSmallSamplesAndMissingDenominators() {
    assertThat(FeedbackStatistics.compare(new double[] {0, 0}, new double[] {0, 0}, 42, 2))
        .containsEntry("evidenceStatus", "INCONCLUSIVE")
        .containsEntry("relativeDifference", null);
    assertThat(FeedbackStatistics.compare(new double[] {1, 2, 3}, new double[] {4, 5, 6}, 42, 20))
        .containsEntry("evidenceStatus", "INSUFFICIENT_DATA");
    assertThat(FeedbackStatistics.correlation(new double[] {1, 1}, new double[] {1, 2})).isNull();
  }

  @Test
  void positiveNegativeAndOutliers() {
    assertThat(FeedbackStatistics.correlation(new double[] {1, 2, 3}, new double[] {3, 2, 1}))
        .isEqualTo(-1.0);
    assertThat(FeedbackStatistics.compare(new double[] {3, 3, 3}, new double[] {1, 1, 1}, 1, 3))
        .containsEntry("absoluteDifference", -2.0);
    assertThat(FeedbackStatistics.distribution(new double[] {1, 1, 1, 1, 10000}))
        .containsEntry("median", 1.0);
  }

  @Test
  void deterministicPixelsAndSaturation() {
    var image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
    var features = DeterministicVisualFeatures.extract(image);
    assertThat(features)
        .containsEntry("brightness", 0.0)
        .containsEntry("dark_pixel_ratio", 1.0)
        .containsEntry("aspect_ratio", 2.0)
        .containsEntry("dominant_color", "BLACK");
    double[] values = new double[60];
    Arrays.fill(values, 0, 20, 30);
    Arrays.fill(values, 20, 40, 20);
    Arrays.fill(values, 40, 60, 10);
    assertThat(FeedbackStatistics.saturation(values, 10))
        .isEqualTo("DECLINING_MARGINAL_PERFORMANCE");
  }

  @Test
  void ratiosRetainRejectedSpend() {
    var result =
        FeedbackStatistics.compareCostPerApproved(
            new double[][] {{10, 1}, {10, 0}, {10, 1}, {10, 1}},
            new double[][] {{5, 1}, {5, 0}, {5, 1}, {5, 1}},
            42,
            2);
    assertThat(((Number) result.get("absoluteDifference")).doubleValue())
        .isCloseTo(-20.0 / 3, within(.00001));
    assertThat(
            FeedbackStatistics.compareCostPerApproved(
                    new double[][] {{10, 0}, {10, 0}}, new double[][] {{5, 0}, {5, 0}}, 42, 2)
                .get("evidenceStatus"))
        .isEqualTo("INSUFFICIENT_DATA");
  }

  @Test
  void scopeAndWindowValidation() {
    var builder = new FeedbackDatasetBuilder(null, null);
    builder.minimum = 20;
    builder.observation = 7;
    var normalized =
        builder.normalize(
            Map.of(
                "scope",
                Map.of("collectionId", UUID.randomUUID().toString()),
                "metric",
                "DOWNLOADS_7D"));
    assertThat(normalized).containsEntry("metric", "DOWNLOADS").containsEntry("observationDays", 7);
    assertThatThrownBy(
            () ->
                builder.normalize(
                    Map.of(
                        "scope",
                        Map.of(
                            "collectionId",
                            UUID.randomUUID().toString(),
                            "typoFilter",
                            "ignored"))))
        .hasMessageContaining("Unsupported scope");
  }

  @Test
  void llmCannotRewriteEvidence() {
    assertThatThrownBy(
            () ->
                HypothesisGenerationService.validateNarrative(
                    Map.of(
                        "title",
                        "test",
                        "description",
                        "test",
                        "rationale",
                        "test",
                        "sampleSize",
                        100)))
        .isInstanceOf(IllegalArgumentException.class);
    var definition = Map.<String, Object>of("value_type", "BOOLEAN", "key", "centered_subject");
    assertThatThrownBy(
            () ->
                VisualFeatureService.validateSemantic(
                    Map.of(
                        "features",
                        List.of(Map.of("key", "centered_subject", "value", true, "confidence", 2)),
                        "warnings",
                        List.of()),
                    Map.of("centered_subject", definition)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> VisualFeatureService.validateValue(definition, "true"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
