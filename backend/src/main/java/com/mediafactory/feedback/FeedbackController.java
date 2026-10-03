package com.mediafactory.feedback;

import static com.mediafactory.feedback.FeedbackStore.*;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {
  private final FeedbackStore store;
  private final VisualFeatureService features;
  private final FeedbackJobs jobs;
  private final VisualPatternAnalysisService patterns;
  private final HypothesisGenerationService hypotheses;
  private final ExperimentProposalService proposals;
  private final ExperimentAnalysisService results;

  public FeedbackController(
      FeedbackStore store,
      VisualFeatureService features,
      FeedbackJobs jobs,
      VisualPatternAnalysisService patterns,
      HypothesisGenerationService hypotheses,
      ExperimentProposalService proposals,
      ExperimentAnalysisService results) {
    this.store = store;
    this.features = features;
    this.jobs = jobs;
    this.patterns = patterns;
    this.hypotheses = hypotheses;
    this.proposals = proposals;
    this.results = results;
  }

  @GetMapping("/overview")
  public Object overview() {
    return store
        .db
        .sql(
            """
            select (select count(*) from feedback_findings where status='DISCOVERED') new_findings,
            (select count(*) from feedback_findings where confidence='HIGH') high_confidence_findings,
            (select count(*) from feedback_findings where status='STALE') stale_findings,
            (select count(*) from experiment_hypotheses where status='PROPOSED') hypotheses_awaiting_review,
            (select count(*) from feedback_experiment_plans where approved_at is null) experiments_awaiting_approval,
            (select count(*) from prompt_experiments where source_hypothesis_id is not null and status='RUNNING') running_experiments,
            (select count(*) from prompt_experiments where feedback_stage='OBSERVING') observing_experiments,
            (select count(*) from prompt_experiments where source_hypothesis_id is not null and status='COMPLETED') completed_experiments
            """)
        .query()
        .singleRow();
  }

  @GetMapping("/attributes")
  public Object attributes() {
    return features.taxonomy();
  }

  @PostMapping("/attributes")
  public Object define(@RequestBody Map<String, Object> p) {
    return features.define(p);
  }

  @GetMapping("/assets/{id}/visual-features")
  public Object features(@PathVariable UUID id) {
    return features.features(id);
  }

  @PostMapping("/assets/{id}/visual-features/extract")
  public Object extract(
      @PathVariable UUID id,
      @RequestBody Map<String, Object> p,
      @RequestHeader("Idempotency-Key") String key) {
    var payload = new LinkedHashMap<>(p);
    payload.put("assetId", id.toString());
    return jobs.enqueue("FEATURE_EXTRACTION", payload, key);
  }

  @PostMapping("/assets/{id}/visual-features/override")
  public Object override(@PathVariable UUID id, @RequestBody Map<String, Object> p) {
    return features.override(id, p);
  }

  @GetMapping("/findings/{id}")
  public Object finding(@PathVariable UUID id) {
    return patterns.detail(id);
  }

  @org.springframework.transaction.annotation.Transactional
  @PostMapping("/findings/{id}/review")
  public Object reviewFinding(@PathVariable UUID id, @RequestBody Map<String, Object> p) {
    String status = Objects.toString(p.get("status"), "");
    check(
        Set.of("REVIEWED", "ACCEPTED_FOR_EXPERIMENT", "DISMISSED").contains(status),
        "Invalid finding review");
    store.audit(
        "FINDING",
        id,
        status,
        Objects.toString(p.get("reason"), ""),
        Objects.toString(p.get("user"), ""));
    store.db.sql("update feedback_findings set status=? where id=?").params(status, id).update();
    return patterns.detail(id);
  }

  @PostMapping("/analyses")
  public Object analyze(
      @RequestBody Map<String, Object> p, @RequestHeader("Idempotency-Key") String key) {
    return jobs.enqueue("PATTERN_ANALYSIS", p, key);
  }

  @PostMapping("/jobs/{type}")
  public Object job(
      @PathVariable String type,
      @RequestBody Map<String, Object> p,
      @RequestHeader("Idempotency-Key") String key) {
    return jobs.enqueue(type, p, key);
  }

  @GetMapping("/analyses/{id}/compliance")
  public Object compliance(@PathVariable UUID id) {
    return patterns.compliance(id);
  }

  @PostMapping("/hypotheses/{id}/{action}")
  public Object hypothesis(
      @PathVariable UUID id, @PathVariable String action, @RequestBody Map<String, Object> p) {
    return action.equals("experiment")
        ? proposals.propose(id, p)
        : hypotheses.review(
            id,
            switch (action) {
              case "approve" -> "APPROVED";
              case "reject" -> "REJECTED";
              case "archive" -> "ARCHIVED";
              default -> throw new IllegalArgumentException("Invalid action");
            },
            p);
  }

  @GetMapping("/experiments")
  public Object experiments(@RequestParam(defaultValue = "0") int page) {
    check(page >= 0 && page <= 100000, "Invalid page");
    return store
        .db
        .sql(
            "select"
                + " e.*,p.primary_metric,p.target_sample,p.observation_days,p.estimated_cost,p.max_budget,p.currency,p.approved_at"
                + " from prompt_experiments e join feedback_experiment_plans p on"
                + " p.experiment_id=e.id order by e.created_at desc limit 50 offset ?")
        .param(page * 50)
        .query()
        .listOfRows();
  }

  @GetMapping("/experiments/{id}")
  public Object experiment(@PathVariable UUID id) {
    return proposals.detail(id);
  }

  @PostMapping("/experiments/{id}/{action}")
  public Object experimentAction(
      @PathVariable UUID id, @PathVariable String action, @RequestBody Map<String, Object> p) {
    return action.equals("approve")
        ? proposals.approve(id, p)
        : proposals.command(
            id,
            switch (action) {
              case "start" -> "RUNNING";
              case "pause" -> "PAUSED";
              case "cancel" -> "CANCELLED";
              default -> throw new IllegalArgumentException("Invalid experiment action");
            },
            p);
  }

  @PostMapping("/learnings/{id}/relate")
  public Object learning(@PathVariable UUID id, @RequestBody Map<String, Object> p) {
    return results.relate(id, p);
  }

  @GetMapping("/saturation")
  public Object saturation() {
    return store
        .db
        .sql("select * from feedback_saturation_results order by created_at desc limit 50")
        .query()
        .listOfRows()
        .stream()
        .map(FeedbackStore::json)
        .toList();
  }

  @GetMapping("/data-quality")
  public Object quality() {
    return Map.of(
        "runs",
        store.list("feedback_analysis_runs", 0),
        "failedJobs",
        store
            .db
            .sql(
                "select id,type,failure_reason from feedback_jobs where status='FAILED' order by"
                    + " created_at desc limit 50")
            .query()
            .listOfRows(),
        "warnings",
        List.of(
            "Observational associations do not establish causality",
            "Mock semantic labels are fixture observations",
            "Missing exposure and feature coverage must be reviewed"));
  }

  @GetMapping("/{resource}")
  public Object list(@PathVariable String resource, @RequestParam(defaultValue = "0") int page) {
    return store.list(table(resource), page);
  }

  @GetMapping("/{resource}/{id}")
  public Object one(@PathVariable String resource, @PathVariable UUID id) {
    return store.one(table(resource), id);
  }

  private String table(String r) {
    return switch (r) {
      case "findings" -> "feedback_findings";
      case "hypotheses" -> "experiment_hypotheses";
      case "analyses" -> "feedback_analysis_runs";
      case "learnings" -> "feedback_learnings";
      case "jobs" -> "feedback_jobs";
      case "results" -> "feedback_experiment_results";
      default -> throw new IllegalArgumentException("Unknown feedback resource");
    };
  }
}
