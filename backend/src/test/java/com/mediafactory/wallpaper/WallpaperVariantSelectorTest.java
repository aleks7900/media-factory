package com.mediafactory.wallpaper;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class WallpaperVariantSelectorTest {
  private final List<WallpaperVariantSelector.Variant> variants =
      List.of(
          new WallpaperVariantSelector.Variant("ANDROID_FHD_PORTRAIT", 1080, 2400, "STANDARD"),
          new WallpaperVariantSelector.Variant("ANDROID_QHD_PORTRAIT", 1440, 3200, "PREMIUM"),
          new WallpaperVariantSelector.Variant("ANDROID_GENERIC_PORTRAIT", 1080, 1920, "STANDARD"));

  @Test
  void oversizedScreenNeverDownloadsMasterOrCover() {
    var all = new java.util.ArrayList<>(variants);
    all.add(new WallpaperVariantSelector.Variant("WALLPAPER_MASTER", 3600, 8000, "PREMIUM"));
    all.add(new WallpaperVariantSelector.Variant("COLLECTION_COVER", 2160, 4800, "STANDARD"));
    assertEquals(
        "ANDROID_GENERIC_PORTRAIT", WallpaperVariantSelector.select(all, 2160, 4800, null).key());
  }

  @Test
  void smallestAdequateVariant() {
    assertEquals(
        "ANDROID_FHD_PORTRAIT", WallpaperVariantSelector.select(variants, 1080, 2400, null).key());
  }

  @Test
  void largerScreenUsesQhd() {
    assertEquals(
        "ANDROID_QHD_PORTRAIT", WallpaperVariantSelector.select(variants, 1200, 2666, null).key());
  }

  @Test
  void unsupportedAspectUsesFallback() {
    assertEquals(
        "ANDROID_GENERIC_PORTRAIT",
        WallpaperVariantSelector.select(variants, 2000, 2000, null).key());
  }

  @Test
  void qualityPreferenceDoesNotForceMaster() {
    assertEquals(
        "ANDROID_GENERIC_PORTRAIT",
        WallpaperVariantSelector.select(variants, 1440, 3200, "STANDARD").key());
  }
}
