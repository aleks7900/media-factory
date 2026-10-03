package com.mediafactory.analytics;

import java.time.LocalDate;
import java.util.*;

public record AnalyticsRebuildScope(UUID assetId, UUID collectionId, LocalDate from, LocalDate to) {
  public AnalyticsRebuildScope {
    if (from != null && to != null && !from.isBefore(to))
      throw new IllegalArgumentException("Rebuild from must precede exclusive to");
  }

  public static AnalyticsRebuildScope all() {
    return new AnalyticsRebuildScope(null, null, null, null);
  }

  public static AnalyticsRebuildScope parse(Map<String, ?> input) {
    return new AnalyticsRebuildScope(
        uuid(input.get("assetId")),
        uuid(input.get("collectionId")),
        date(input.get("from")),
        date(input.get("to")));
  }

  private static UUID uuid(Object value) {
    return value == null ? null : UUID.fromString(value.toString());
  }

  private static LocalDate date(Object value) {
    return value == null ? null : LocalDate.parse(value.toString());
  }

  public Map<String, Object> audit() {
    var result = new LinkedHashMap<String, Object>();
    if (assetId != null) result.put("assetId", assetId);
    if (collectionId != null) result.put("collectionId", collectionId);
    if (from != null) result.put("from", from);
    if (to != null) result.put("to", to);
    return result;
  }
}
