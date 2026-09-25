package com.mediafactory.wallpaper;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class AmoledAnalyzerTest {
  private final AmoledAnalyzer analyzer = new AmoledAnalyzer();

  private BufferedImage fixture(int color) {
    var image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 100; y++) for (int x = 0; x < 100; x++) image.setRGB(x, y, color);
    return image;
  }

  @Test
  void blackBackgroundWithFocalHighlightQualifies() {
    var image = fixture(0);
    for (int y = 40; y < 60; y++) for (int x = 40; x < 60; x++) image.setRGB(x, y, 0xffffff);
    var r = analyzer.analyze(image, AmoledAnalyzer.Policy.defaults());
    assertEquals(.96, r.blackPixelRatio(), .000001);
    assertEquals(.04, r.meanLuminance(), .000001);
    assertEquals("AMOLED_SUITABLE", r.classification());
    assertEquals(10000L, r.luminanceHistogram().stream().mapToLong(Long::longValue).sum());
  }

  @Test
  void grayHazeIsNotBlack() {
    var r = analyzer.analyze(fixture(0x202020), AmoledAnalyzer.Policy.defaults());
    assertEquals(0, r.blackPixelRatio());
    assertEquals(1, r.nearBlackPixelRatio());
    assertEquals("AMOLED_BORDERLINE", r.classification());
  }

  @Test
  void whiteFails() {
    var r = analyzer.analyze(fixture(0xffffff), AmoledAnalyzer.Policy.defaults());
    assertEquals(1, r.meanLuminance(), 1e-12);
    assertEquals("NOT_AMOLED", r.classification());
  }

  @Test
  void blankBlackDoesNotPassFromAverageAlone() {
    assertEquals(
        "AMOLED_BORDERLINE",
        analyzer.analyze(fixture(0), AmoledAnalyzer.Policy.defaults()).classification());
  }

  @Test
  void rejectInvalidThresholdsAndTransparency() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AmoledAnalyzer.Policy(Double.NaN, .1, .5, .5, .7, .2, .01));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            analyzer.analyze(
                new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB),
                AmoledAnalyzer.Policy.defaults()));
    assertThrows(
        IllegalArgumentException.class,
        () -> analyzer.analyze(new byte[] {1, 2}, AmoledAnalyzer.Policy.defaults()));
  }
}
