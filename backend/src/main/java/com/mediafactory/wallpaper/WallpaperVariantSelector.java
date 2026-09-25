package com.mediafactory.wallpaper;

import java.util.Comparator;
import java.util.List;

/** Dimensions are physical pixels; callers must not multiply screen pixels by Android density. */
public final class WallpaperVariantSelector {
  private WallpaperVariantSelector() {}

  public record Variant(String key, int width, int height, String qualityTier) {
    public Variant {
      if (key == null || qualityTier == null || width <= 0 || height <= 0)
        throw new IllegalArgumentException("Invalid variant");
    }
  }

  public static Variant select(List<Variant> variants, int width, int height, String tier) {
    if (width <= 0 || height <= 0)
      throw new IllegalArgumentException("Physical screen size required");
    double aspect = (double) width / height;
    return variants.stream()
        .filter(
            v ->
                v.key().startsWith("ANDROID_")
                    && !v.key().contains("PREVIEW")
                    && !v.key().contains("THUMBNAIL"))
        .filter(v -> v.width() >= width && v.height() >= height)
        .filter(v -> Math.abs((double) v.width() / v.height() - aspect) / aspect <= .06)
        .filter(v -> tier == null || tier.equals(v.qualityTier()))
        .min(Comparator.comparingLong(v -> (long) v.width() * v.height()))
        .orElseGet(
            () ->
                variants.stream()
                    .filter(v -> v.key().equals("ANDROID_GENERIC_PORTRAIT"))
                    .findFirst()
                    .orElseThrow(
                        () -> new IllegalArgumentException("Generic fallback is required")));
  }
}
