package com.mediafactory.feedback;

import static com.mediafactory.feedback.FeedbackStore.*;
import static com.mediafactory.processing.ProcessingJson.*;

import com.mediafactory.analytics.AnalyticsFeedbackDataset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedbackDatasetBuilder {

  private final FeedbackStore store;
  private final AnalyticsFeedbackDataset analytics;

  @Value("${feedback.minimum-sample-size:20}")
  int minimum;

  @Value("${feedback.minimum-observation-days:7}")
  int observation;

  public FeedbackDatasetBuilder(FeedbackStore store, AnalyticsFeedbackDataset analytics) {
    this.store = store;
    this.analytics = analytics;
  }

  public Map<String, Object> normalize(Map<String, Object> input) {
    var p = new LinkedHashMap<>(input);
    check(p.get("scope") instanceof Map, "Explicit scope is required");
    var scope = map(p.get("scope"));
    check(
        Set.of(
                "projectId",
                "collectionId",
                "provider",
                "model",
                "promptVersionId",
                "clusterId",
                "assetType",
                "experimentId",
                "platform")
            .containsAll(scope.keySet()),
        "Unsupported scope filter");
    check(
        scope.get("collectionId") != null || scope.get("projectId") != null,
        "Project or collection scope is required");
    scope.forEach(
        (key, value) -> {
          check(value != null && !value.toString().isBlank(), "Scope filters cannot be empty");
          if (key.endsWith("Id")) {
            UUID.fromString(value.toString());
          }
        });
    p.putIfAbsent("featureRole", "OBSERVED");
    check(Set.of("OBSERVED", "REQUESTED").contains(p.get("featureRole")), "Invalid feature role");
    p.putIfAbsent("metric", "DOWNLOAD_RATE");
    p.putIfAbsent("currency", "USD");
    java.util.Currency.getInstance(p.get("currency").toString());
    String metric = p.get("metric").toString();
    if (metric.endsWith("_7D") || metric.endsWith("_30D")) {
      int split = metric.lastIndexOf('_');
      p.put("metric", metric.substring(0, split));
      p.put("observationDays", Integer.parseInt(metric.substring(split + 1, metric.length() - 1)));
    }
    p.putIfAbsent("minimumFeatureConfidence", .7);
    check(
        number(p, "minimumFeatureConfidence", .7) >= 0
            && number(p, "minimumFeatureConfidence", .7) <= 1,
        "Invalid feature confidence threshold");
    p.putIfAbsent("extractorVersion", "visual-v1");
    p.putIfAbsent("randomSeed", 42L);
    p.putIfAbsent("minimumSample", minimum);
    p.putIfAbsent("observationDays", 30);
    p.putIfAbsent("asOf", Instant.now().toString());
    p.putIfAbsent("to", p.get("asOf"));
    String period = Objects.toString(p.get("period"), "90D");
    check(
        Set.of("7D", "30D", "90D", "180D", "LIFETIME", "CUSTOM").contains(period),
        "Invalid period");
    if (!p.containsKey("from")) {
      check(!period.equals("CUSTOM"), "Custom period needs from");
      p.put(
          "from",
          period.equals("LIFETIME")
              ? Instant.EPOCH.toString()
              : Instant.parse(p.get("to").toString())
                  .minus(Duration.ofDays(Integer.parseInt(period.replace("D", ""))))
                  .toString());
    }
    Instant from = Instant.parse(p.get("from").toString()),
        to = Instant.parse(p.get("to").toString()),
        asOf = Instant.parse(p.get("asOf").toString());
    check(
        from.isBefore(to) && !asOf.isAfter(Instant.now().plusSeconds(300)),
        "Invalid observation boundaries");
    int min = integer(p, "minimumSample", minimum), days = integer(p, "observationDays", 30);
    check(min >= minimum && min <= 10000, "Minimum sample is below policy or above 10000");
    boolean qa = Set.of("QA_APPROVAL_RATE", "COST_PER_APPROVED_ASSET").contains(p.get("metric"));
    check(days >= (qa ? 0 : observation) && days <= 180, "Observation window violates policy");
    p.putIfAbsent("attributes", List.of("dark_pixel_ratio", "dominant_color"));
    var attrs = (List<?>) p.get("attributes");
    check(!attrs.isEmpty() && attrs.size() <= 12, "Select 1–12 attributes");
    attrs.forEach(a -> check(a.toString().matches("[a-z][a-z0-9_]{0,99}"), "Invalid attribute"));
    p.putIfAbsent("numericBuckets", List.of(.2, .4, .6, .8));
    p.putIfAbsent("bucketVersion", "quintile-thresholds-v1");
    var buckets = (List<?>) p.get("numericBuckets");
    check(buckets.size() >= 1 && buckets.size() <= 10, "Use 1–10 numeric thresholds");
    double last = Double.NEGATIVE_INFINITY;
    for (Object b : buckets) {
      check(b instanceof Number, "Invalid threshold");
      double n = ((Number) b).doubleValue();
      check(Double.isFinite(n) && n > last, "Numeric thresholds must be finite and increasing");
      last = n;
    }
    p.putIfAbsent("combinations", List.of());
    check(((List<?>) p.get("combinations")).size() <= 6, "At most six explicit combinations");
    for (Object pair : (List<?>) p.get("combinations")) {
      check(
          pair instanceof List<?> l
              && l.size() == 2
              && new HashSet<>(l).size() == 2
              && attrs.containsAll(l),
          "Combinations must contain two selected attributes");
    }
    return p;
  }

  @Transactional
  public UUID build(Map<String, Object> input) {
    var p = normalize(input);
    UUID id = UUID.randomUUID();
    String hash;
    try {
      hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(canonical(p).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    store
        .db
        .sql(
            "insert into"
                + " feedback_analysis_runs(id,parameters,parameter_hash,target_metric,period_start,period_end,extractor_version,random_seed)"
                + " values(?,cast(? as jsonb),?,?,cast(? as timestamptz),cast(? as"
                + " timestamptz),?,?)")
        .params(
            id,
            write(p),
            hash,
            p.get("metric"),
            p.get("from"),
            p.get("to"),
            p.get("extractorVersion"),
            ((Number) p.get("randomSeed")).longValue())
        .update();
    int count = analytics.snapshot(id, p);
    var coverage =
        store
            .db
            .sql(
                "select count(*) filter(where value is null) unavailable,count(*) filter(where"
                    + " feature_ids='[]') missing_features from feedback_dataset_rows where"
                    + " run_id=?")
            .param(id)
            .query()
            .singleRow();
    var warnings = new ArrayList<String>();
    warnings.add("OBSERVATIONAL_CORRELATION_NOT_CAUSATION");
    warnings.add("PLATFORM_COVERAGE_NOT_PROVEN");
    warnings.add(
        "SURVIVORSHIP_AND_SELECTION_BIAS: market population requires publication and completed"
            + " exposure window");
    warnings.add(
        "ASSET_GRAIN: failed generations without assets remain in TASK-10 economics but cannot have"
            + " observed visual features");
    if (((Number) coverage.get("unavailable")).longValue() > 0) {
      warnings.add("METRIC_UNAVAILABLE:" + coverage.get("unavailable"));
    }
    if (((Number) coverage.get("missing_features")).longValue() > 0) {
      warnings.add("MISSING_VISUAL_FEATURES:" + coverage.get("missing_features"));
    }
    store
        .db
        .sql(
            "update feedback_analysis_runs set status='DATASET_READY',asset_count=?,warnings=cast(?"
                + " as jsonb) where id=?")
        .params(count, write(warnings), id)
        .update();
    return id;
  }
}
