package com.mediafactory.wallpaper;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * Deterministic linear-sRGB luminance analysis. Does not change pixels or claim battery savings.
 */
@Component
public class AmoledAnalyzer {

  public Result analyze(byte[] bytes, Policy policy) {
    if (bytes.length == 0 || bytes.length > 64 * 1024 * 1024) {
      throw new IllegalArgumentException("AMOLED input exceeds limits");
    }
    try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) {
        throw new IllegalArgumentException("Unsupported AMOLED image");
      }
      var reader = readers.next();
      try {
        reader.setInput(input);
        long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
        if (pixels > 64_000_000) {
          throw new IllegalArgumentException("AMOLED image exceeds pixel limit");
        }
        return analyze(reader.read(0), policy);
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("Unreadable AMOLED image", e);
    }
  }

  public Result analyze(BufferedImage image, Policy policy) {
    long count = (long) image.getWidth() * image.getHeight(), black = 0, near = 0, bright = 0;
    if (count > 64_000_000) {
      throw new IllegalArgumentException("AMOLED image exceeds pixel limit");
    }
    double sum = 0;
    long[] histogram = new long[256];
    double[] linear = new double[256];
    for (int i = 0; i < 256; i++) {
      double srgb = i / 255.0;
      linear[i] = srgb <= .04045 ? srgb / 12.92 : Math.pow((srgb + .055) / 1.055, 2.4);
    }
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        int rgb = image.getRGB(x, y);
        if ((rgb >>> 24) != 255) {
          throw new IllegalArgumentException("AMOLED analysis requires opaque pixels");
        }
        double luminance =
            .2126 * linear[(rgb >> 16) & 255]
                + .7152 * linear[(rgb >> 8) & 255]
                + .0722 * linear[rgb & 255];
        if (luminance <= policy.blackMaximum()) {
          black++;
        }
        if (luminance <= policy.nearBlackMaximum()) {
          near++;
        }
        if (luminance >= policy.brightMinimum()) {
          bright++;
        }
        sum += luminance;
        histogram[Math.min(255, (int) Math.floor(luminance * 255))]++;
      }
    }
    double b = black / (double) count, n = near / (double) count, h = bright / (double) count;
    var warnings = new ArrayList<String>();
    if (b < policy.minimumBlackRatio()) {
      warnings.add("INSUFFICIENT_BLACK_BACKGROUND");
    }
    if (n < policy.minimumNearBlackRatio()) {
      warnings.add("GRAY_HAZE_OR_BRIGHT_BACKGROUND");
    }
    if (h > policy.maximumBrightRatio()) {
      warnings.add("EXCESSIVE_BRIGHT_AREA");
    }
    if (h < policy.minimumHighlightCoverage()) {
      warnings.add("NO_MEASURABLE_FOCAL_HIGHLIGHT");
    }
    String classification =
        warnings.isEmpty()
            ? "AMOLED_SUITABLE"
            : n >= policy.minimumNearBlackRatio() * .8 && h <= policy.maximumBrightRatio() * 1.25
              ? "AMOLED_BORDERLINE"
                : "NOT_AMOLED";
    long seen = 0;
    double median = 0;
    var bins = new ArrayList<Long>();
    for (int i = 0; i < histogram.length; i++) {
      if (seen < (count + 1) / 2 && seen + histogram[i] >= (count + 1) / 2) {
        median = i / 255.0;
      }
      seen += histogram[i];
      bins.add(histogram[i]);
    }
    return new Result(
        "linear-srgb-luminance-256-v1",
        policy,
        count,
        b,
        n,
        sum / count,
        median,
        h,
        h,
        List.copyOf(bins),
        classification,
        List.copyOf(warnings));
  }

  public record Policy(
      double blackMaximum,
      double nearBlackMaximum,
      double brightMinimum,
      double minimumBlackRatio,
      double minimumNearBlackRatio,
      double maximumBrightRatio,
      double minimumHighlightCoverage) {

    public Policy {
      for (double value :
          new double[]{
              blackMaximum,
              nearBlackMaximum,
              brightMinimum,
              minimumBlackRatio,
              minimumNearBlackRatio,
              maximumBrightRatio,
              minimumHighlightCoverage
          }) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
          throw new IllegalArgumentException("AMOLED thresholds must be finite fractions");
        }
      }
      if (blackMaximum >= nearBlackMaximum
          || nearBlackMaximum >= brightMinimum
          || minimumBlackRatio > minimumNearBlackRatio
          || minimumHighlightCoverage > maximumBrightRatio) {
        throw new IllegalArgumentException("Inconsistent AMOLED thresholds");
      }
    }

    public static Policy defaults() {
      return new Policy(.003, .015, .6, .50, .70, .15, .005);
    }
  }

  public record Result(
      String algorithm,
      Policy policy,
      long pixelCount,
      double blackPixelRatio,
      double nearBlackPixelRatio,
      double meanLuminance,
      double medianLuminance,
      double brightPixelRatio,
      double highlightCoverage,
      List<Long> luminanceHistogram,
      String classification,
      List<String> warnings) {

  }
}
