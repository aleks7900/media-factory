package com.mediafactory.stock;

import java.util.*;

/** Marketplace category IDs are adapter configuration, never stock domain values. */
public final class StockCategoryMapper {
  private StockCategoryMapper() {}

  public static String map(String internal, Map<String, Object> mapping) {
    return Objects.toString(mapping.getOrDefault(internal, internal));
  }
}
