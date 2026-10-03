package com.mediafactory.feedback;

import static com.mediafactory.feedback.FeedbackStore.*;
import static com.mediafactory.processing.ProcessingJson.*;

import com.mediafactory.provider.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HypothesisGenerationService {
  private final FeedbackStore store;
  private final TextGenerationProvider text;
  private final VisualFeatureService features;

  public HypothesisGenerationService(
      FeedbackStore store, TextGenerationProvider text, VisualFeatureService features) {
    this.store = store;
    this.text = text;
    this.features = features;
  }

  @Transactional
  public Object generate(UUID finding, String intent) {
    check(
        Set.of("EXPLORATION", "EXPLOITATION", "SATURATION").contains(intent),
        "Invalid experiment intent");
    var f = store.one("feedback_findings", finding);
    check(
        !Set.of("STALE", "DISMISSED").contains(f.get("status"))
            && !java.time.Instant.now()
                .isAfter(((java.sql.Timestamp) f.get("stale_at")).toInstant()),
        "Finding is stale or dismissed");
    check(
        !f.get("evidence_status").equals("INSUFFICIENT_DATA") || intent.equals("EXPLORATION"),
        "Insufficient data requires an explicitly exploratory hypothesis");
    check(
        "mock".equals(text.textIdentity().get("provider")),
        "Paid hypothesis generation is not enabled");
    var anchor =
        store
            .db
            .sql(
                "select r.asset_id,r.generation_id from feedback_dataset_rows r where r.run_id=?"
                    + " and r.value is not null order by r.asset_id limit 1")
            .param(f.get("analysis_run_id"))
            .query()
            .listOfRows();
    check(!anchor.isEmpty(), "No measured evidence assets");
    UUID prompt = UUID.fromString("00000000-0000-0000-0000-000000001112"), id = UUID.randomUUID();
    String template =
        store
            .db
            .sql("select positive_template from prompt_versions where id=?")
            .param(prompt)
            .query(String.class)
            .single();
    var response =
        text.generateText(
            new ProviderTypes.Request(
                id.toString(),
                template
                    + "\nEVIDENCE_DATA\n"
                    + write(
                        Map.of(
                            "scope",
                            f.get("scope"),
                            "metric",
                            f.get("target_metric"),
                            "attribute",
                            f.get("attribute_key"),
                            "value",
                            f.get("attribute_value"),
                            "statistics",
                            f.get("statistics"),
                            "warnings",
                            f.get("warnings"))),
                0,
                0));
    var narrative = map(response.output());
    validateNarrative(narrative);
    UUID cost =
        features.recordCost(
            (UUID) anchor.getFirst().get("generation_id"),
            (UUID) anchor.getFirst().get("asset_id"),
            response.usage(),
            "HYPOTHESIS_GENERATION",
            Map.of(
                "hypothesisId",
                id,
                "findingId",
                finding,
                "attribution",
                "explicit evidence anchor; analysis overhead"));
    var priority =
        Map.of(
            "evidenceStrength",
            f.get("confidence"),
            "informationGain",
            "UNCERTAINTY_REDUCTION_NOT_REVENUE",
            "intent",
            intent,
            "estimatedCost",
            "REQUIRES_REGISTERED_PLAN",
            "sampleAvailability",
            map(f.get("statistics")).get("treatment"),
            "businessRelevance",
            "HUMAN_REVIEW_REQUIRED",
            "novelty",
            intent.equals("EXPLORATION") ? "EXPLORATORY" : "NOT_MEASURED");
    store
        .db
        .sql(
            "insert into"
                + " experiment_hypotheses(id,finding_id,title,description,rationale,intent,priority,provider,model,prompt_version_id,cost_id)"
                + " values(?,?,?,?,?,?,cast(? as jsonb),?,?,?,?)")
        .params(
            id,
            finding,
            narrative.get("title"),
            narrative.get("description"),
            narrative.get("rationale"),
            intent,
            write(priority),
            response.usage().provider(),
            response.usage().model(),
            prompt,
            cost)
        .update();
    return store.one("experiment_hypotheses", id);
  }

  public static void validateNarrative(Map<String, Object> n) {
    check(
        n.keySet().equals(Set.of("title", "description", "rationale")),
        "Hypothesis output may contain narrative fields only, never numerical evidence");
    for (String key : n.keySet()) {
      check(n.get(key) instanceof String, "Narrative must be text");
      required((String) n.get(key), key);
    }
    check(n.get("title").toString().length() <= 200, "Hypothesis title too long");
  }

  @Transactional
  public Object review(UUID id, String action, Map<String, Object> input) {
    check(Set.of("APPROVED", "REJECTED", "ARCHIVED").contains(action), "Invalid hypothesis review");
    store
        .db
        .sql("select id from experiment_hypotheses where id=? for update")
        .param(id)
        .query()
        .singleRow();
    var h = store.one("experiment_hypotheses", id);
    check(
        Set.of("PROPOSED", "DRAFT", "APPROVED", "REJECTED").contains(h.get("status")),
        "Hypothesis is already converted or archived");
    store.audit(
        "HYPOTHESIS",
        id,
        action,
        Objects.toString(input.get("reason"), ""),
        Objects.toString(input.get("user"), ""));
    store
        .db
        .sql("update experiment_hypotheses set status=? where id=?")
        .params(action, id)
        .update();
    return store.one("experiment_hypotheses", id);
  }
}
