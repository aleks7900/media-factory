package com.mediafactory.analytics;

import java.io.StringWriter;
import java.time.Instant;
import java.util.*;
import org.apache.commons.csv.CSVFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsExportService {
  private final AnalyticsAggregationService aggregation;

  public AnalyticsExportService(AnalyticsAggregationService aggregation) {
    this.aggregation = aggregation;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  @SuppressWarnings("unchecked")
  public String export(AnalyticsQuery query) {
    String generated = Instant.now().toString();
    var output = new StringWriter();
    try (var csv = CSVFormat.DEFAULT.print(output)) {
      csv.printRecord(
          "generatedAt", generated, "baseCurrency", query.currency(), "timezone", query.timezone());
      csv.printRecord(
          "fromInclusive", query.from(), "toExclusive", query.to(), "groupBy", query.groupBy());
      csv.printRecord(
          "filters", safe(com.mediafactory.processing.ProcessingJson.write(query.filters())));
      csv.printRecord(
          "note",
          "Unavailable metrics are blank; platform costs are unallocated; estimates remain"
              + " estimates.");
      List<String> columns =
          List.of(
              "group_key",
              "generated",
              "approved",
              "rejected",
              "failed",
              "published",
              "views",
              "likes",
              "downloads",
              "revenue",
              "cost",
              "profit",
              "roi",
              "cost_per_approved",
              "cost_per_download",
              "missing_money_facts");
      csv.printRecord(columns);
      for (int page = 0; page < 500; page++) {
        var q =
            new AnalyticsQuery(
                query.projectId(),
                query.collectionId(),
                query.assetId(),
                query.provider(),
                query.model(),
                query.platform(),
                query.from(),
                query.to(),
                query.currency(),
                query.timezone(),
                query.groupBy(),
                query.sort(),
                query.descending(),
                page,
                200,
                query.filters());
        var result = aggregation.query(q);
        long total = ((Number) result.get("total")).longValue();
        if (total > 100000)
          throw new IllegalArgumentException("Export exceeds 100000 groups; narrow the filters");
        var rows = (List<Map<String, Object>>) result.get("rows");
        for (var row : rows) csv.printRecord(columns.stream().map(k -> safe(row.get(k))).toList());
        if ((page + 1L) * 200 >= total) break;
      }
    } catch (java.io.IOException e) {
      throw new IllegalStateException("CSV generation failed", e);
    }
    return output.toString();
  }

  static Object safe(Object value) {
    if (!(value instanceof String text)) return value;
    return text.matches("(?s)^[\\s]*[=+@-].*") ? "'" + text : text;
  }
}
