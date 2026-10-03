package com.mediafactory.feedback;

import static com.mediafactory.feedback.FeedbackStore.*;
import static com.mediafactory.processing.ProcessingJson.*;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExperimentAnalysisService {
  private final FeedbackStore store;
  private final FeedbackDatasetBuilder datasets;
  private final ExperimentProposalService proposals;

  public ExperimentAnalysisService(
      FeedbackStore store, FeedbackDatasetBuilder datasets, ExperimentProposalService proposals) {
    this.store = store;
    this.datasets = datasets;
    this.proposals = proposals;
  }

  @Transactional
  public Object analyze(UUID experiment) {
    var plan = store.one("feedback_experiment_plans", experiment);
    check(plan.get("approved_at") != null, "Experiment has not been approved");
    var d = map(plan.get("definition"));
    boolean offline = plan.get("mode").equals("OFFLINE"),
        costRatio = plan.get("primary_metric").equals("COST_PER_APPROVED_ASSET");
    var parameters = new LinkedHashMap<>(map(d.get("analysisParameters")));
    var scope = new LinkedHashMap<>(map(parameters.get("scope")));
    if (!offline) {
      scope.put("experimentId", experiment.toString());
      scope.remove("promptVersionId");
      scope.remove("clusterId");
    }
    parameters.put("scope", scope);
    if (!offline) {
      parameters.put("from", Instant.EPOCH.toString());
      parameters.put("to", Instant.now().toString());
      parameters.put("asOf", parameters.get("to"));
    }
    parameters.put("metric", plan.get("primary_metric"));
    parameters.put("observationDays", plan.get("observation_days"));
    UUID run = datasets.build(parameters);
    var variants =
        store
            .db
            .sql(
                "select id,key,prompt_version_id from prompt_experiment_variants where"
                    + " experiment_id=? order by key")
            .param(experiment)
            .query()
            .listOfRows();
    check(variants.size() == 2, "Result analysis currently requires A/B");
    var results = new ArrayList<Map<String, Object>>();
    var samples = new ArrayList<double[]>();
    int min = ((Number) plan.get("minimum_sample")).intValue(),
        target = ((Number) plan.get("target_sample")).intValue();
    boolean complete = true;
    var detail = proposals.detail(experiment);
    var paired = new ArrayList<double[][]>();
    String dimension = offline ? "prompt_version_id" : "experiment_variant_id";
    for (var variant : variants) {
      Object variantIdentity = variant.get(offline ? "prompt_version_id" : "id");
      var row =
          store
              .db
              .sql(
                  "select count(*) observed,"
                      + (costRatio
                          ? "sum(value)/nullif(sum((metrics->>'approved')::numeric),0)"
                          : "avg(value)")
                      + " primary_metric,avg((metrics->>'downloads')::numeric)"
                      + " downloads,avg((metrics->>'likes')::numeric)"
                      + " likes,avg((metrics->>'revenue')::numeric)"
                      + " revenue,avg((metrics->>'profit')::numeric)"
                      + " profit,avg((metrics->>'approved')::numeric)"
                      + " qa_approval,avg((metrics->>'cost')::numeric) cost_per_asset from"
                      + " feedback_dataset_rows where run_id=? and "
                      + dimension
                      + "=? and value is not null")
              .params(run, variantIdentity)
              .query()
              .singleRow();
      row.put("variantId", variant.get("id"));
      row.put("key", variant.get("key"));
      results.add(row);
      complete &= ((Number) row.get("observed")).intValue() >= target;
      samples.add(
          store
              .db
              .sql(
                  "select value::float8 from feedback_dataset_rows where run_id=? and "
                      + dimension
                      + "=? and value is not null order by md5(asset_id::text) limit 2048")
              .params(run, variantIdentity)
              .query(Double.class)
              .list()
              .stream()
              .mapToDouble(Double::doubleValue)
              .toArray());
      if (costRatio)
        paired.add(
            store
                .db
                .sql(
                    "select value::float8 cost,(metrics->>'approved')::float8 approved from"
                        + " feedback_dataset_rows where run_id=? and "
                        + dimension
                        + "=? and value is not null order by md5(asset_id::text) limit 2048")
                .params(run, variantIdentity)
                .query()
                .listOfRows()
                .stream()
                .map(
                    r ->
                        new double[] {
                          ((Number) r.get("cost")).doubleValue(),
                          ((Number) r.get("approved")).doubleValue()
                        })
                .toArray(double[][]::new));
    }
    long pending =
        store
            .db
            .sql(
                "select count(*) from generations where experiment_id=? and status not in"
                    + " ('APPROVED','REJECTED','FAILED','PUBLISHED')")
            .param(experiment)
            .query(Long.class)
            .single();
    complete &= pending == 0;
    var comparison =
        costRatio
            ? FeedbackStatistics.compareCostPerApproved(
                paired.get(0),
                paired.get(1),
                ((Number) parameters.get("randomSeed")).longValue(),
                min)
            : FeedbackStatistics.compare(
                samples.get(0),
                samples.get(1),
                ((Number) parameters.get("randomSeed")).longValue(),
                min);
    var result = new LinkedHashMap<String, Object>();
    result.put("experimentId", experiment);
    result.put("analysisRunId", run);
    result.put("primaryMetric", plan.get("primary_metric"));
    result.put("observationDays", plan.get("observation_days"));
    result.put("variants", results);
    result.put("comparison", comparison);
    result.put("funnel", detail.get("funnel"));
    result.put("costs", detail.get("costs"));
    result.put(
        "warnings",
        List.of(
            "Attrition, platform exposure and operational failures must be reviewed separately",
            "Complete publication exposure is not established by event totals alone",
            "No automatic winner; no prompt mutation"));
    result.put("mode", plan.get("mode"));
    result.put(
        "population",
        offline
            ? "OFFLINE_HISTORICAL_PROMPT_VERSION_COMPARISON; observational, no reassignment"
            : "REGISTERED_NEW_GENERATIONS");
    result.put("complete", complete);
    String status =
        complete ? comparison.get("evidenceStatus").toString() : "INSUFFICIENT_EVIDENCE";
    UUID id = UUID.randomUUID();
    store
        .db
        .sql(
            "insert into"
                + " feedback_experiment_results(id,experiment_id,analysis_run_id,result,analysis_status)"
                + " values(?,?,?,cast(? as jsonb),?)")
        .params(id, experiment, run, write(result), status)
        .update();
    store
        .db
        .sql("update feedback_analysis_runs set status='COMPLETED',completed_at=now() where id=?")
        .param(run)
        .update();
    store
        .db
        .sql(
            "update prompt_experiments set feedback_stage=? where id=? and status not in"
                + " ('CANCELLED','PAUSED')")
        .params(complete ? "READY_FOR_ANALYSIS" : "OBSERVING", experiment)
        .update();
    UUID learning = UUID.randomUUID();
    String summary =
        "Observed "
            + plan.get("primary_metric")
            + " comparison within the registered scope; "
            + status
            + ". No causal claim or automatic adoption.";
    store
        .db
        .sql(
            "insert into"
                + " feedback_learnings(id,experiment_id,hypothesis_id,result_id,scope,summary,evidence_status,stale_at)"
                + " values(?,?,?,?,cast(? as jsonb),?,?,now()+interval '90 days')")
        .params(
            learning,
            experiment,
            plan.get("hypothesis_id"),
            id,
            write(map(d.get("scope"))),
            summary,
            status)
        .update();
    if (complete)
      store
          .db
          .sql(
              "update prompt_experiments set"
                  + " status='COMPLETED',feedback_stage='COMPLETED',ended_at=now(),revision=revision+1"
                  + " where id=? and (status='RUNNING' or (status='DRAFT' and ?))")
          .params(experiment, offline)
          .update();
    return Map.of(
        "result",
        store.one("feedback_experiment_results", id),
        "learning",
        store.one("feedback_learnings", learning));
  }

  @Transactional
  public Object relate(UUID id, Map<String, Object> input) {
    String state = Objects.toString(input.get("status"), "");
    check(Set.of("SUPERSEDED", "CONTRADICTED", "STALE").contains(state), "Invalid learning state");
    var current = store.one("feedback_learnings", id);
    UUID other = input.containsKey("relatedLearningId") ? uuid(input, "relatedLearningId") : null;
    if (!state.equals("STALE")) {
      check(other != null && !other.equals(id), "Select another learning");
      var related = store.one("feedback_learnings", other);
      check(
          current.get("scope").equals(related.get("scope")),
          "Conflicting learnings require the same scope");
    }
    store.audit(
        "LEARNING",
        id,
        state,
        Objects.toString(input.get("reason"), ""),
        Objects.toString(input.get("user"), ""));
    store
        .db
        .sql(
            "update feedback_learnings set status=?,related_learning_id=?,evidence_status=case when"
                + " ?='CONTRADICTED' then 'CONFLICTING_EVIDENCE' else evidence_status end where"
                + " id=?")
        .params(state, other, state, id)
        .update();
    if (state.equals("CONTRADICTED"))
      store
          .db
          .sql(
              "update feedback_learnings set"
                  + " status='CONTRADICTED',related_learning_id=?,evidence_status='CONFLICTING_EVIDENCE'"
                  + " where id=?")
          .params(id, other)
          .update();
    return store.one("feedback_learnings", id);
  }
}
