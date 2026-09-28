package com.mediafactory.stock;

import java.util.*;

public final class StockFilenameStrategy {
  private StockFilenameStrategy() {}

  public static String filename(String title, UUID production, UUID metadata) {
    String slug =
        StockKeywords.normalize(title).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    if (slug.isEmpty()) slug = "stock";
    if (slug.length() > 70) slug = slug.substring(0, 70);
    return slug + "-" + production + "-" + metadata + ".jpg";
  }
}
