package com.mediafactory.analytics;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.UUID;

public record AnalyticsQuery(
    UUID projectId,
    UUID collectionId,
    UUID assetId,
    String provider,
    String model,
    String platform,
    Instant from,
    Instant to,
    String currency,
    String timezone,
    String groupBy,
    String sort,
    boolean descending,
    int page,
    int size,
    java.util.Map<String, String> filters) {

  public AnalyticsQuery(
      UUID projectId,
      UUID collectionId,
      UUID assetId,
      String provider,
      String model,
      String platform,
      Instant from,
      Instant to,
      String currency,
      String timezone,
      String groupBy,
      String sort,
      boolean descending,
      int page,
      int size) {
    this(
        projectId,
        collectionId,
        assetId,
        provider,
        model,
        platform,
        from,
        to,
        currency,
        timezone,
        groupBy,
        sort,
        descending,
        page,
        size,
        java.util.Map.of());
  }

  public AnalyticsQuery {
    filters = filters == null ? java.util.Map.of() : java.util.Map.copyOf(filters);
    for (String key : java.util.List.of("amoled", "stock", "wallpaper", "video")) {
      if (filters.containsKey(key) && !java.util.Set.of("true", "false")
          .contains(filters.get(key))) {
        throw new IllegalArgumentException("Invalid boolean filter: " + key);
      }
    }
    if (filters.containsKey("grain")
        && !java.util.Set.of("day", "week", "month").contains(filters.get("grain"))) {
      throw new IllegalArgumentException("Invalid time-series grain");
    }
    currency = currency == null ? "USD" : currency;
    Currency.getInstance(currency);
    timezone = timezone == null ? "UTC" : timezone;
    ZoneId.of(timezone);
    groupBy = groupBy == null ? "asset" : groupBy;
    sort = sort == null ? "cost" : sort;
    if (from != null && to != null && !from.isBefore(to)) {
      throw new IllegalArgumentException("from must precede exclusive to");
    }
    if (page < 0 || size < 1 || size > 200) {
      throw new IllegalArgumentException("Invalid page size");
    }
  }
}
