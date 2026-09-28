package com.mediafactory.stock;

import static com.mediafactory.processing.ProcessingJson.*;

import java.util.*;

/** Configurable requirements, independent of any marketplace's changing acceptance rules. */
public final class StockPlatformRequirements {
  private StockPlatformRequirements() {}

  public static void validate(Map<String, Object> p) {
    for (String key :
        List.of(
            "minimumMegapixels",
            "maximumMegapixels",
            "minimumWidth",
            "minimumHeight",
            "maximumWidth",
            "maximumHeight",
            "maximumFileSize",
            "preferredQuality",
            "minimumTitleLength",
            "maximumTitleLength",
            "minimumDescriptionLength",
            "maximumDescriptionLength",
            "minimumKeywords",
            "maximumKeywords",
            "generationWidth",
            "generationHeight")) {
      if (!(p.get(key) instanceof Number n)
          || !Double.isFinite(n.doubleValue())
          || n.doubleValue() < 0) throw new IllegalArgumentException("Invalid requirement: " + key);
    }
    for (String[] pair :
        List.of(
            new String[] {"minimumMegapixels", "maximumMegapixels"},
            new String[] {"minimumWidth", "maximumWidth"},
            new String[] {"minimumHeight", "maximumHeight"},
            new String[] {"minimumTitleLength", "maximumTitleLength"},
            new String[] {"minimumDescriptionLength", "maximumDescriptionLength"},
            new String[] {"minimumKeywords", "maximumKeywords"}))
      if (number(p, pair[0], 0) > number(p, pair[1], 0))
        throw new IllegalArgumentException("Inverted requirement: " + pair[0]);
    if (number(p, "maximumMegapixels", 0) > 64
        || integer(p, "maximumWidth", 0) > 8192
        || integer(p, "maximumHeight", 0) > 8192
        || integer(p, "maximumFileSize", 0) < 1
        || integer(p, "maximumFileSize", 0) > 67108864
        || integer(p, "preferredQuality", 0) < 1
        || integer(p, "preferredQuality", 0) > 100
        || integer(p, "maximumKeywords", 0) > 100
        || integer(p, "maximumTitleLength", 0) > 500
        || integer(p, "maximumDescriptionLength", 0) > 4000)
      throw new IllegalArgumentException("Requirements exceed supported bounds");
    if (!Set.of("ANY", "PORTRAIT", "LANDSCAPE", "SQUARE").contains(p.get("orientation"))
        || !Set.of("COMMERCIAL", "EDITORIAL", "UNDETERMINED").contains(p.get("contentType"))
        || !"sRGB".equals(p.get("colorSpace"))
        || !List.of("JPEG").equals(p.get("acceptedFormats"))
        || !Boolean.TRUE.equals(p.get("requireAiDisclosure"))
        || !"STOCK_STRICT".equals(p.get("similarityProfile")))
      throw new IllegalArgumentException("Unsupported stock policy");
    if (!(p.get("categories") instanceof List<?> categories)
        || categories.isEmpty()
        || !Set.of(
                "ANIMALS",
                "TECHNOLOGY",
                "NATURE",
                "BUSINESS",
                "PEOPLE",
                "ABSTRACT",
                "FOOD",
                "TRAVEL",
                "SCIENCE")
            .containsAll(categories))
      throw new IllegalArgumentException("Invalid internal taxonomy");
    if (!(p.get("forbiddenTerms") instanceof List<?> terms)
        || terms.size() > 100
        || terms.stream()
            .anyMatch(
                t ->
                    !(t instanceof String)
                        || t.toString().isBlank()
                        || t.toString().length() > 100))
      throw new IllegalArgumentException("Invalid forbidden terms");
    if (!"en".equals(p.get("language")))
      throw new IllegalArgumentException("Only English is enabled in this version");
    int width = integer(p, "generationWidth", 0), height = integer(p, "generationHeight", 0);
    if (width < 256 || height < 256 || width > 4096 || height > 4096)
      throw new IllegalArgumentException("Unsupported generation dimensions");
    UUID.fromString(p.get("metadataPromptVersion").toString());
    UUID.fromString(p.get("imagePromptVersion").toString());
  }
}
