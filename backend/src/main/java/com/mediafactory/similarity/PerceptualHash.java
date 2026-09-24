package com.mediafactory.similarity;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import javax.imageio.ImageIO;

/**
 * DCT-II of a 32x32 luminance image; median of 63 AC coefficients in the low 8x8 block.
 */
public final class PerceptualHash {

  public static final String VERSION = "dct32-low8-acmedian-v1";

  public static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static int distance(String a, String b) {
    if (a.length() != 64 || b.length() != 64) {
      throw new IllegalArgumentException("Expected 64 bits");
    }
    int result = 0;
    for (int i = 0; i < 64; i++) {
      if (a.charAt(i) != b.charAt(i)) {
        result++;
      }
    }
    return result;
  }

  public Fingerprint extract(byte[] bytes) {
    try {
      BufferedImage image;
      try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
        var readers = ImageIO.getImageReaders(stream);
        if (!readers.hasNext()) {
          throw new IllegalArgumentException("Invalid image");
        }
        var reader = readers.next();
        try {
          reader.setInput(stream);
          if ((long) reader.getWidth(0) * reader.getHeight(0) > 20_000_000) {
            throw new IllegalArgumentException("Image pixel limit exceeded");
          }
          image = reader.read(0);
        } finally {
          reader.dispose();
        }
      }
      var resized = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
      var graphics = resized.createGraphics();
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      graphics.drawImage(image, 0, 0, 32, 32, null);
      graphics.dispose();
      double[][] pixels = new double[32][32];
      double sum = 0, squares = 0;
      for (int y = 0; y < 32; y++) {
        for (int x = 0; x < 32; x++) {
          int rgb = resized.getRGB(x, y);
          double v = .299 * ((rgb >> 16) & 255) + .587 * ((rgb >> 8) & 255) + .114 * (rgb & 255);
          pixels[x][y] = v;
          sum += v;
          squares += v * v;
        }
      }
      double[] coefficients = new double[64];
      for (int u = 0; u < 8; u++) {
        for (int v = 0; v < 8; v++) {
          double value = 0;
          for (int x = 0; x < 32; x++) {
            for (int y = 0; y < 32; y++) {
              value +=
                  pixels[x][y]
                      * Math.cos((2 * x + 1) * u * Math.PI / 64)
                      * Math.cos((2 * y + 1) * v * Math.PI / 64);
            }
          }
          coefficients[u * 8 + v] =
              value * (u == 0 ? 1 / Math.sqrt(2) : 1) * (v == 0 ? 1 / Math.sqrt(2) : 1);
        }
      }
      var sorted = Arrays.copyOfRange(coefficients, 1, 64);
      Arrays.sort(sorted);
      double median = sorted[31];
      var bits = new StringBuilder("0");
      for (int i = 1; i < 64; i++) {
        bits.append(coefficients[i] > median ? '1' : '0');
      }
      return new Fingerprint(
          sha(bytes), bits.toString(), squares / 1024 - Math.pow(sum / 1024, 2) < 25);
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Image fingerprint extraction failed", e);
    }
  }

  public record Fingerprint(String sha256, String bits, boolean lowInformation) {

  }
}
