package com.mediafactory.feedback;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.*;

public final class DeterministicVisualFeatures {
  public static final String VERSION = "visual-v1";

  private DeterministicVisualFeatures() {}

  public static Map<String, Object> extract(BufferedImage image) {
    int stepX = Math.max(1, (image.getWidth() + 255) / 256),
        stepY = Math.max(1, (image.getHeight() + 255) / 256);
    double sum = 0, square = 0, saturation = 0;
    int n = 0, dark = 0, edges = 0, pairs = 0;
    int[] histogram = new int[32], palette = new int[6];
    for (int y = 0; y < image.getHeight(); y += stepY) {
      double previous = -1;
      for (int x = 0; x < image.getWidth(); x += stepX) {
        Color c = new Color(image.getRGB(x, y));
        double v = luminance(c);
        sum += v;
        square += v * v;
        n++;
        if (v < .1) dark++;
        histogram[Math.min(31, (int) (v * 32))]++;
        saturation += Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null)[1];
        int max = Math.max(c.getRed(), Math.max(c.getGreen(), c.getBlue())),
            min = Math.min(c.getRed(), Math.min(c.getGreen(), c.getBlue()));
        int bin =
            v < .1
                ? 0
                : v > .9
                    ? 1
                    : max - min < 25 ? 2 : c.getRed() == max ? 3 : c.getGreen() == max ? 4 : 5;
        palette[bin]++;
        if (previous >= 0) {
          pairs++;
          if (Math.abs(previous - v) > .15) edges++;
        }
        previous = v;
      }
    }
    double entropy = 0;
    for (int h : histogram)
      if (h > 0) {
        double p = (double) h / n;
        entropy -= p * Math.log(p) / Math.log(2);
      }
    int best = 0;
    for (int i = 1; i < palette.length; i++) if (palette[i] > palette[best]) best = i;
    return Map.of(
        "brightness",
        sum / n,
        "contrast",
        Math.sqrt(Math.max(0, square / n - Math.pow(sum / n, 2))),
        "saturation",
        saturation / n,
        "dark_pixel_ratio",
        (double) dark / n,
        "entropy",
        entropy,
        "edge_density",
        pairs == 0 ? 0 : (double) edges / pairs,
        "aspect_ratio",
        (double) image.getWidth() / image.getHeight(),
        "dominant_color",
        List.of("BLACK", "WHITE", "GRAY", "RED", "GREEN", "BLUE").get(best));
  }

  private static double luminance(Color c) {
    return (.2126 * c.getRed() + .7152 * c.getGreen() + .0722 * c.getBlue()) / 255;
  }
}
