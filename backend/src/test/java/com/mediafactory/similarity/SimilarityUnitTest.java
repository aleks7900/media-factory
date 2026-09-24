package com.mediafactory.similarity;

import static org.assertj.core.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class SimilarityUnitTest {
  static byte[] fixture(int seed, int size, String format) throws Exception {
    var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
    var g = image.createGraphics();
    g.setColor(new Color(30, 60, 90));
    g.fillRect(0, 0, size, size);
    var random = new Random(seed);
    for (int i = 0; i < 18; i++) {
      g.setColor(new Color(random.nextInt(255), random.nextInt(255), random.nextInt(255)));
      g.fillOval(random.nextInt(size), random.nextInt(size), size / 3, size / 3);
    }
    g.dispose();
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(image, format, bytes);
    return bytes.toByteArray();
  }

  @Test
  void actualDctHashSurvivesCompressionAndResizeAndSeparatesDifferentFixtures() throws Exception {
    var extractor = new PerceptualHash();
    var a = extractor.extract(fixture(42, 256, "png"));
    var jpeg = extractor.extract(fixture(42, 256, "jpg"));
    var original = ImageIO.read(new java.io.ByteArrayInputStream(fixture(42, 256, "png")));
    var resized = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
    var g = resized.createGraphics();
    g.drawImage(original, 0, 0, 128, 128, null);
    g.dispose();
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(resized, "png", bytes);
    var b = extractor.extract(bytes.toByteArray());
    assertThat(a.sha256())
        .isEqualTo(extractor.extract(fixture(42, 256, "png")).sha256())
        .isNotEqualTo(jpeg.sha256());
    assertThat(PerceptualHash.distance(a.bits(), jpeg.bits())).isLessThanOrEqualTo(4);
    assertThat(PerceptualHash.distance(a.bits(), b.bits())).isLessThanOrEqualTo(4);
    assertThat(PerceptualHash.distance(a.bits(), extractor.extract(fixture(71, 256, "png")).bits()))
        .isGreaterThan(10);
    assertThat(a.lowInformation()).isFalse();
  }

  @Test
  void normalizationRejectsInvalidDimensionNanZeroAndNonUnit() {
    assertThatThrownBy(() -> ImageEmbeddingProvider.validate(new float[] {1}, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ImageEmbeddingProvider.validate(new float[] {Float.NaN}, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ImageEmbeddingProvider.validate(new float[] {0, 0}, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ImageEmbeddingProvider.validate(new float[] {1, 1}, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ImageEmbeddingProvider.validate(new float[] {.6f, .8f}, 2))
        .containsExactly(.6f, .8f);
  }

  final Map<String, Object> profile =
      Map.of(
          "duplicate_distance",
          4,
          "near_distance",
          10,
          "near_similarity",
          .94,
          "similar_threshold",
          .86);

  String classify(boolean sha, int phash, double cosine, boolean low, boolean family) {
    return new DefaultSimilarityPolicy()
        .evaluate(
            new SimilarityPolicy.Context(sha, phash, cosine, low, true, true, true, family),
            profile)
        .classification();
  }

  @Test
  void metricsHaveSeparateMeanings() {
    assertThat(classify(true, 30, .2, false, false)).isEqualTo("EXACT_DUPLICATE");
    assertThat(classify(false, 2, .6, false, false)).isEqualTo("PERCEPTUAL_DUPLICATE");
    assertThat(classify(false, 8, .97, false, false)).isEqualTo("NEAR_DUPLICATE");
    assertThat(classify(false, 25, .99, false, false)).isEqualTo("SEMANTICALLY_SIMILAR");
    assertThat(classify(false, 25, .3, false, false)).isEqualTo("DISTINCT");
  }

  @Test
  void lineageAndLowInformationSuppressFalseDuplicateInference() {
    assertThat(classify(false, 8, .97, false, true)).isEqualTo("VISUALLY_SIMILAR");
    assertThat(classify(false, 0, .99, true, false)).isEqualTo("SEMANTICALLY_SIMILAR");
  }

  @Test
  void deterministicDbscanHasNoise() {
    UUID a = new UUID(0, 1), b = new UUID(0, 2), c = new UUID(0, 3), noise = new UUID(0, 4);
    var ids = List.of(a, b, c, noise);
    var graph =
        Map.of(a, List.of(b, c), b, List.of(a, c), c, List.of(a, b), noise, List.<UUID>of());
    var first = CollectionClusteringService.dbscan(ids, graph, 3);
    assertThat(first)
        .containsEntry(a, 0)
        .containsEntry(b, 0)
        .containsEntry(c, 0)
        .containsEntry(noise, -1);
    assertThat(CollectionClusteringService.dbscan(ids, graph, 3)).isEqualTo(first);
  }

  @Test
  void repeatedPromptChecksUseNormalizedWords() {
    assertThat(DiversityGuard.normalize("  A  WOLF ")).isEqualTo("a wolf");
    assertThat(DiversityGuard.jaccard("wolf forest snow", "wolf forest moon")).isEqualTo(.5);
  }

  @Test
  void batchThresholdStopsOnlyAfterACompletedChunk() {
    assertThat(GenerationBatchService.shouldPause(0, 1, .8)).isFalse();
    assertThat(GenerationBatchService.shouldPause(30, .79, .8)).isFalse();
    assertThat(GenerationBatchService.shouldPause(40, .81, .8)).isTrue();
  }

  @Test
  void mockBatchIsNormalizedDeterministicAndCpuOnly() {
    var p = new MockEmbeddingProvider();
    var model = p.modelMetadata();
    var inputs =
        List.of(
            new ImageEmbeddingProvider.Input(UUID.randomUUID(), new byte[] {1}),
            new ImageEmbeddingProvider.Input(UUID.randomUUID(), new byte[] {2}));
    var a = p.embed(inputs, model);
    var b = p.embed(inputs, model);
    assertThat(a.vectors()).hasSize(2);
    assertThat(a.vectors().getFirst()).containsExactly(b.vectors().getFirst());
    for (float[] v : a.vectors()) ImageEmbeddingProvider.validate(v, 512);
    assertThat(a.metadata()).containsEntry("device", "cpu");
  }
}
