package com.mediafactory.feedback;

import static com.mediafactory.feedback.FeedbackStore.*;
import static com.mediafactory.processing.ProcessingJson.*;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VisualPatternAnalysisService {
  private final FeedbackStore store;
  private final FeedbackDatasetBuilder datasets;

  @Value("${feedback.finding.stale-after-days:90}")
  int staleDays;

  public VisualPatternAnalysisService(FeedbackStore store, FeedbackDatasetBuilder datasets) {
    this.store = store;
    this.datasets = datasets;
  }

  @Transactional
  public Object analyze(Map<String, Object> input) {
    UUID run = input.containsKey("runId") ? uuid(input, "runId") : datasets.build(input);
    var analysis = store.one("feedback_analysis_runs", run);
    store
        .db
        .sql("select id from feedback_analysis_runs where id=? for update")
        .param(run)
        .query()
        .singleRow();
    if (analysis.get("status").equals("COMPLETED")) return store.one("feedback_analysis_runs", run);
    var p = map(analysis.get("parameters"));
    var attrs = (List<?>) p.get("attributes");
    var combinations = (List<?>) p.get("combinations");
    int tests = 0;
    for (Object a : attrs) tests += analyzeDimension(run, p, List.of(a.toString()));
    for (Object pair : combinations)
      tests += analyzeDimension(run, p, ((List<?>) pair).stream().map(Object::toString).toList());
    store
        .db
        .sql(
            "update feedback_findings set"
                + " statistics=statistics||jsonb_build_object('testedComparisons',cast(? as"
                + " int),'multipleTesting','EXPLORATORY_NO_FORMAL_SIGNIFICANCE_CLAIM') where"
                + " analysis_run_id=?")
        .params(tests, run)
        .update();
    store
        .db
        .sql(
            "update feedback_analysis_runs set"
                + " status='COMPLETED',completed_at=now(),finding_count=? where id=?")
        .params(tests, run)
        .update();
    return store.one("feedback_analysis_runs", run);
  }

  private int analyzeDimension(UUID run, Map<String, Object> p, List<String> keys) {
    String source =
        Objects.toString(p.get("featureRole"), "OBSERVED").equals("REQUESTED")
            ? "requested"
            : "features";
    Map<String, Object> args = new HashMap<>();
    args.put("run", run);
    args.put("seed", p.get("randomSeed").toString());
    String expression;
    if (keys.size() == 2) {
      args.put("k0", keys.get(0));
      args.put("k1", keys.get(1));
      expression =
          "case when jsonb_exists("
              + source
              + ",:k0) and jsonb_exists("
              + source
              + ",:k1) then jsonb_build_object(cast(:k0 as text),"
              + source
              + "->:k0,cast(:k1 as text),"
              + source
              + "->:k1) end";
    } else {
      args.put("k0", keys.getFirst());
      String value = source + "->:k0";
      StringBuilder bucket = new StringBuilder("case ");
      int i = 0;
      for (Object threshold : (List<?>) p.get("numericBuckets")) {
        args.put("b" + i, threshold);
        bucket
            .append("when (")
            .append(value)
            .append(")::text::numeric<:b")
            .append(i)
            .append(" then to_jsonb('bucket_")
            .append(i)
            .append("'::text) ");
        i++;
      }
      bucket.append("else to_jsonb('bucket_").append(i).append("'::text) end");
      expression =
          "case when jsonb_typeof("
              + value
              + ")='number' then "
              + bucket
              + " else "
              + value
              + " end";
    }
    // Scope and SQL expressions are internal; keys/values are always bound parameters.
    String base =
        "with candidates as (select *,"
            + expression
            + " as feature_value from feedback_dataset_rows where run_id=:run) ";
    var groups =
        store
            .db
            .sql(
                base
                    + "select feature_value,count(*) n from candidates where value is not null and"
                    + " feature_value is not null group by feature_value order by n"
                    + " desc,feature_value limit 32")
            .params(args)
            .query()
            .listOfRows();
    int n = 0;
    for (var group : groups) {
      args.put("value", group.get("feature_value").toString());
      String present = "feature_value=cast(:value as jsonb)",
          absent = "feature_value<>cast(:value as jsonb)";
      boolean costRatio = p.get("metric").equals("COST_PER_APPROVED_ASSET");
      var control = distribution(base, absent, args, costRatio);
      var treatment = distribution(base, present, args, costRatio);
      int min = integer(p, "minimumSample", 20);
      long countA = ((Number) control.get("count")).longValue(),
          countB = ((Number) treatment.get("count")).longValue();
      if (keys.size() == 2 && (countA < min || countB < min)) continue;
      var stats =
          new LinkedHashMap<>(
              costRatio
                  ? FeedbackStatistics.compareCostPerApproved(
                      pairedSample(base, absent, args),
                      pairedSample(base, present, args),
                      ((Number) p.get("randomSeed")).longValue(),
                      min)
                  : FeedbackStatistics.compare(
                      sample(base, absent, args),
                      sample(base, present, args),
                      ((Number) p.get("randomSeed")).longValue(),
                      min));
      stats.put("control", control);
      stats.put("treatment", treatment);
      stats.put("bootstrapSampleLimitPerGroup", 2048);
      stats.put("populationGrain", "ASSET");
      stats.put("bucketVersion", p.get("bucketVersion"));
      stats.put("numericThresholds", p.get("numericBuckets"));
      if (control.get("mean") != null && treatment.get("mean") != null) {
        double a = ((Number) control.get("mean")).doubleValue(),
            b = ((Number) treatment.get("mean")).doubleValue();
        stats.put("absoluteDifference", b - a);
        stats.put("relativeDifference", a == 0 ? null : (b - a) / Math.abs(a));
      }
      var warnings = new ArrayList<String>();
      warnings.add("EXPLORATORY: intervals are unadjusted; no significance claim");
      warnings.add("UNKNOWN_ATTRIBUTES_EXCLUDED_FROM_COMPARISON");
      if (groups.size() == 32) warnings.add("DIMENSION_CAPPED_AT_32_VALUES");
      if (countA > 2048 || countB > 2048)
        warnings.add(
            "BOOTSTRAP_USES_DETERMINISTIC_SUBSAMPLE; exact population distributions shown");
      var confounders =
          store
              .db
              .sql(
                  base
                      + "select coalesce(provider,'UNKNOWN') provider,coalesce(model,'UNKNOWN')"
                      + " model,prompt_version_id,count(*) filter(where "
                      + present
                      + ") treatment,count(*) filter(where "
                      + absent
                      + ") control from candidates where value is not null and feature_value is not"
                      + " null group by provider,model,prompt_version_id order by count(*) desc"
                      + " limit 50")
              .params(args)
              .query()
              .listOfRows();
      stats.put("strata", confounders);
      if (confounders.stream()
          .anyMatch(
              r ->
                  ((Number) r.get("control")).longValue() == 0
                      || ((Number) r.get("treatment")).longValue() == 0))
        warnings.add("PROVIDER_MODEL_PROMPT_IMBALANCE: compare within strata before interpreting");
      var periods =
          store
              .db
              .sql(
                  base
                      + "select ntile_period,avg(value) filter(where "
                      + present
                      + ") treatment,avg(value) filter(where "
                      + absent
                      + ") control,count(*) n from (select *,ntile(3) over(order by"
                      + " generated_at,asset_id) ntile_period from candidates where value is not"
                      + " null and feature_value is not null) t group by ntile_period order by"
                      + " ntile_period")
              .params(args)
              .query()
              .listOfRows();
      stats.put("temporalSlices", periods);
      if (keys.size() == 1 && !costRatio) {
        var corr =
            store
                .db
                .sql(
                    "select corr(("
                        + source
                        + "->>:k0)::numeric,value) correlation from feedback_dataset_rows where"
                        + " run_id=:run and jsonb_typeof("
                        + source
                        + "->:k0)='number'")
                .params(args)
                .query()
                .singleRow();
        stats.put("numericPearsonCorrelation", corr.get("correlation"));
      }
      if (costRatio)
        warnings.add(
            "PRIMARY_MEAN_IS_COST_SUM_PER_APPROVAL; quantiles describe per-generated-asset costs");
      boolean sufficient = countA >= min && countB >= min;
      stats.put("sufficient", sufficient);
      String status =
          sufficient
              ? Objects.toString(stats.get("evidenceStatus"), "INCONCLUSIVE")
              : "INSUFFICIENT_DATA";
      // Deliberately conservative: observational scans never receive HIGH confidence in v1.
      String confidence =
          sufficient && warnings.size() == 2 && status.equals("SUFFICIENT_EVIDENCE")
              ? "MEDIUM"
              : "LOW";
      UUID id = UUID.randomUUID();
      store
          .db
          .sql(
              "insert into"
                  + " feedback_findings(id,analysis_run_id,attribute_key,attribute_value,target_metric,scope,statistics,warnings,confidence,evidence_status,stale_at)"
                  + " values(?,?,?,cast(? as jsonb),?,cast(? as jsonb),cast(? as jsonb),cast(? as"
                  + " jsonb),?,?,now()+cast(? as int)*interval '1 day')")
          .params(
              id,
              run,
              String.join("+", keys),
              group.get("feature_value").toString(),
              p.get("metric"),
              write(p.get("scope")),
              write(stats),
              write(warnings),
              confidence,
              status,
              staleDays)
          .update();
      args.put("finding", id);
      for (var role :
          Map.of("POSITIVE_EXAMPLE", present, "NEGATIVE_EXAMPLE", absent, "OUTLIER", present)
              .entrySet()) {
        args.put("role", role.getKey());
        store
            .db
            .sql(
                base
                    + "insert into feedback_finding_evidence(finding_id,asset_id,role,value) select"
                    + " :finding,asset_id,:role,value from candidates where value is not null and "
                    + role.getValue()
                    + " order by value "
                    + (role.getKey().equals("OUTLIER") ? "asc" : "desc")
                    + ",asset_id limit 3")
            .params(args)
            .update();
      }
      n++;
    }
    return n;
  }

  private Map<String, Object> distribution(
      String base, String condition, Map<String, Object> args, boolean ratio) {
    return store
        .db
        .sql(
            base
                + "select count(*) count,"
                + (ratio
                    ? "sum(value)/nullif(sum((metrics->>'approved')::numeric),0)"
                    : "avg(value)")
                + " mean,percentile_cont(.5) within group(order by value)"
                + " median,percentile_cont(.25) within group(order by value)"
                + " p25,percentile_cont(.75) within group(order by value) p75,percentile_cont(.9)"
                + " within group(order by value) p90 from candidates where value is not null and "
                + condition)
        .params(args)
        .query()
        .singleRow();
  }

  private double[][] pairedSample(String base, String condition, Map<String, Object> args) {
    return store
        .db
        .sql(
            base
                + "select value::float8 cost,(metrics->>'approved')::float8 approved from"
                + " candidates where value is not null and "
                + condition
                + " order by md5(asset_id::text||cast(:seed as text)),asset_id limit 2048")
        .params(args)
        .query()
        .listOfRows()
        .stream()
        .map(
            r ->
                new double[] {
                  ((Number) r.get("cost")).doubleValue(), ((Number) r.get("approved")).doubleValue()
                })
        .toArray(double[][]::new);
  }

  private double[] sample(String base, String condition, Map<String, Object> args) {
    return store
        .db
        .sql(
            base
                + "select value::float8 from candidates where value is not null and "
                + condition
                + " order by md5(asset_id::text||cast(:seed as text)),asset_id limit 2048")
        .params(args)
        .query(Double.class)
        .list()
        .stream()
        .mapToDouble(Double::doubleValue)
        .toArray();
  }

  public Object detail(UUID id) {
    var result = new LinkedHashMap<>(store.one("feedback_findings", id));
    result.put(
        "analysis", store.one("feedback_analysis_runs", (UUID) result.get("analysis_run_id")));
    result.put(
        "evidence",
        store
            .db
            .sql(
                "select e.*,v.id thumbnail_variant_id from feedback_finding_evidence e left join"
                    + " lateral(select id from asset_variants where asset_id=e.asset_id and"
                    + " lower(kind) like '%thumbnail%' order by created_at desc limit 1)v on true"
                    + " where e.finding_id=? order by role,value desc")
            .param(id)
            .query()
            .listOfRows());
    return result;
  }

  public Object compliance(UUID run) {
    return store
        .db
        .sql(
            "select provider,model,prompt_version_id,r.key,count(*) requested,count(*) filter(where"
                + " jsonb_exists(features,r.key)) observed,count(*) filter(where"
                + " features->r.key=r.value) matched from feedback_dataset_rows cross join lateral"
                + " jsonb_each(requested) r where run_id=? group by"
                + " provider,model,prompt_version_id,r.key order by count(*) desc limit 200")
        .param(run)
        .query()
        .listOfRows();
  }

  public void stale() {
    store
        .db
        .sql(
            "update feedback_findings set status='STALE' where stale_at<now() and status not in"
                + " ('STALE','DISMISSED')")
        .update();
    store
        .db
        .sql(
            "update feedback_learnings set status='STALE' where stale_at<now() and status='ACTIVE'")
        .update();
  }
}
