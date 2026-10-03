package com.mediafactory.feedback;

import static com.mediafactory.feedback.FeedbackStore.*;
import static com.mediafactory.processing.ProcessingJson.*;

import com.mediafactory.prompt.*;
import com.mediafactory.prompt.PromptModels.*;
import com.mediafactory.provider.*;
import com.mediafactory.service.FactoryService;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExperimentProposalService {
  private final FeedbackStore store;
  private final PromptCatalog prompts;
  private final FactoryService factory;
  private final PricingService pricing;

  public ExperimentProposalService(
      FeedbackStore store, PromptCatalog prompts, FactoryService factory, PricingService pricing) {
    this.store = store;
    this.prompts = prompts;
    this.factory = factory;
    this.pricing = pricing;
  }

  @Transactional
  public Object propose(UUID hypothesis, Map<String, Object> input) {
    store
        .db
        .sql("select id from experiment_hypotheses where id=? for update")
        .param(hypothesis)
        .query()
        .singleRow();
    var previous =
        store
            .db
            .sql("select experiment_id from feedback_experiment_plans where hypothesis_id=?")
            .param(hypothesis)
            .query(UUID.class)
            .list();
    if (!previous.isEmpty()) return detail(previous.getFirst());
    var h = store.one("experiment_hypotheses", hypothesis);
    check(h.get("status").equals("APPROVED"), "Approve the hypothesis before creating a proposal");
    var f = store.one("feedback_findings", (UUID) h.get("finding_id"));
    check(
        !f.get("status").equals("STALE")
            && ((java.sql.Timestamp) f.get("stale_at"))
                .toInstant()
                .isAfter(java.time.Instant.now()),
        "Finding is stale");
    var analysis = store.one("feedback_analysis_runs", (UUID) f.get("analysis_run_id"));
    var params = map(analysis.get("parameters"));
    UUID control = uuid(input, "controlVersionId"), concept = uuid(input, "conceptId");
    var version = prompts.version(control);
    check(version.get("status").equals("PUBLISHED"), "Control must be published");
    String type = Objects.toString(input.get("type"), "PROMPT_VARIABLE");
    check(
        Set.of("PROMPT_VARIABLE", "PROMPT_VERSION").contains(type),
        "Initial execution supports prompt variable/version experiments; other types require"
            + " immutable adapter support");
    UUID treatment;
    if (type.equals("PROMPT_VARIABLE")) {
      String variable = Objects.toString(input.get("variable"), "");
      check(!variable.isBlank(), "Select a controllable variable");
      var defs = prompts.variables(control);
      check(
          defs.stream().anyMatch(d -> d.name().equals(variable)),
          "Required prompt variable is not controllable");
      var modified =
          defs.stream()
              .map(
                  d ->
                      d.name().equals(variable)
                          ? new PromptVariableDefinition(
                              d.name(),
                              d.label(),
                              d.description(),
                              d.type(),
                              d.required(),
                              input.get("treatmentValue"),
                              d.allowedValues(),
                              d.min(),
                              d.max(),
                              d.minLength(),
                              d.maxLength(),
                              d.displayOrder())
                          : d)
              .toList();
      var created =
          prompts.createVersion(
              (UUID) version.get("prompt_template_id"),
              new VersionInput(
                  version.get("positive_template").toString(),
                  version.get("negative_template").toString(),
                  modified,
                  "Feedback hypothesis " + hypothesis + ": one variable default changed",
                  null,
                  null));
      treatment = (UUID) created.get("id");
      prompts.publish(treatment, ((Number) created.get("revision")).intValue());
    } else {
      treatment = uuid(input, "treatmentVersionId");
      check(!control.equals(treatment), "Control and treatment must differ");
    }
    // Require complete default inputs: execution cannot change variables/presets after
    // registration.
    new PromptVariableValidator().resolve(prompts.variables(control), Map.of());
    new PromptVariableValidator().resolve(prompts.variables(treatment), Map.of());
    var collection =
        store
            .db
            .sql(
                "select c.collection_id,col.project_id from concepts c join collections col on"
                    + " col.id=c.collection_id where c.id=?")
            .param(concept)
            .query()
            .singleRow();
    var scope = map(f.get("scope"));
    check(
        scope.get("collectionId") == null
            || scope
                .get("collectionId")
                .toString()
                .equals(collection.get("collection_id").toString()),
        "Proposal collection differs from evidence scope");
    check(
        scope.get("projectId") == null
            || scope.get("projectId").toString().equals(collection.get("project_id").toString()),
        "Proposal project differs from evidence scope");
    int minimum = integer(params, "minimumSample", 20),
        target = integer(input, "targetSample", 50),
        days = integer(params, "observationDays", 30);
    check(
        target >= minimum && target <= 1000,
        "Target per variant must satisfy minimum and be at most 1000");
    int width = integer(input, "width", 1024), height = integer(input, "height", 1024);
    check(
        width >= 64 && height >= 64 && width <= 4096 && height <= 4096,
        "Invalid experiment dimensions");
    String provider = Objects.toString(input.get("provider"), "mock"),
        model = Objects.toString(input.get("model"), "studio-mock-v1"),
        currency = Objects.toString(input.get("currency"), "USD");
    check(
        scope.get("provider") == null || scope.get("provider").equals(provider),
        "Registered provider must match evidence scope");
    check(
        scope.get("model") == null || scope.get("model").equals(model),
        "Registered model must match evidence scope");
    var quote = pricing.quote(provider, model, Map.of());
    check(
        quote.estimatedCost() != null && quote.currency().equals(currency),
        "Known same-currency price is required");
    BigDecimal estimate = quote.estimatedCost().multiply(BigDecimal.valueOf(target * 2L)),
        budget = new BigDecimal(Objects.toString(input.get("maxBudget"), "0"));
    check(
        budget.signum() >= 0 && budget.compareTo(estimate) >= 0,
        "Max budget must cover generation estimate");
    String mode = Objects.toString(input.get("mode"), "ONLINE");
    check(Set.of("ONLINE", "OFFLINE").contains(mode), "Invalid experiment mode");
    check(
        !mode.equals("ONLINE")
            || scope.get("assetType") == null
            || scope.get("assetType").equals("IMAGE"),
        "Online execution currently supports the image pipeline; use OFFLINE for wallpaper, stock"
            + " or video cohorts");
    var experiment =
        prompts.createExperiment(
            new ExperimentInput(
                h.get("title").toString(),
                "Feedback hypothesis " + hypothesis,
                "COLLECTION",
                null,
                (UUID) collection.get("collection_id"),
                null,
                false,
                List.of(
                    new VariantInput("A", "Control", control, 5000),
                    new VariantInput("B", "Treatment", treatment, 5000))));
    UUID id = (UUID) experiment.get("id");
    var definition = new LinkedHashMap<String, Object>();
    definition.putAll(
        Map.of(
            "type",
            type,
            "conceptId",
            concept,
            "controlVersionId",
            control,
            "treatmentVersionId",
            treatment,
            "provider",
            provider,
            "model",
            model,
            "width",
            width,
            "height",
            height,
            "scope",
            scope,
            "analysisParameters",
            params));
    definition.put(
        "change",
        Map.of(
            "variable",
            Objects.toString(input.get("variable"), "prompt_version"),
            "treatmentValue",
            input.getOrDefault("treatmentValue", treatment.toString())));
    definition.put(
        "costComponents",
        Map.of(
            "generation",
            estimate,
            "qa",
            "UNKNOWN_UNLESS_MOCK",
            "processing",
            "UNPRICED_LOCAL_COMPUTE"));
    definition.put(
        "eligibility",
        "New generations from registered concept only; no regeneration or variable/preset"
            + " overrides");
    definition.put(
        "completion",
        "Target per variant, terminal production funnel and full observation window; no winner"
            + " stopping");
    store
        .db
        .sql(
            "insert into"
                + " feedback_experiment_plans(experiment_id,hypothesis_id,definition,primary_metric,secondary_metrics,minimum_sample,target_sample,observation_days,estimated_cost,max_budget,currency,mode)"
                + " values(?,?,cast(? as jsonb),?,cast(? as jsonb),?,?,?,?,?,?,?)")
        .params(
            id,
            hypothesis,
            write(definition),
            f.get("target_metric"),
            write(List.of("QA_APPROVAL_RATE", "DOWNLOADS", "LIKES", "REVENUE", "PROFIT")),
            minimum,
            target,
            days,
            estimate,
            budget,
            currency,
            mode)
        .update();
    store
        .db
        .sql(
            "update prompt_experiments set source_hypothesis_id=?,feedback_stage='READY_FOR_REVIEW'"
                + " where id=?")
        .params(hypothesis, id)
        .update();
    store
        .db
        .sql("update experiment_hypotheses set status='CONVERTED_TO_EXPERIMENT' where id=?")
        .param(hypothesis)
        .update();
    store
        .db
        .sql("update feedback_findings set status='EXPERIMENT_CREATED' where id=?")
        .param(f.get("id"))
        .update();
    return detail(id);
  }

  @Transactional
  public Object approve(UUID id, Map<String, Object> input) {
    store
        .db
        .sql("select experiment_id from feedback_experiment_plans where experiment_id=? for update")
        .param(id)
        .query()
        .singleRow();
    var plan = store.one("feedback_experiment_plans", id);
    check(plan.get("approved_at") == null, "Experiment is already approved");
    store.audit(
        "EXPERIMENT",
        id,
        "APPROVE",
        Objects.toString(input.get("reason"), ""),
        Objects.toString(input.get("user"), ""));
    store
        .db
        .sql(
            "update feedback_experiment_plans set approved_at=now(),approved_by=? where"
                + " experiment_id=?")
        .params(input.get("user"), id)
        .update();
    store
        .db
        .sql("update prompt_experiments set feedback_stage='APPROVED' where id=?")
        .param(id)
        .update();
    return detail(id);
  }

  @Transactional
  public Object command(UUID id, String command, Map<String, Object> input) {
    var e = prompts.experiment(id);
    var plan = store.one("feedback_experiment_plans", id);
    check(
        plan.get("approved_at") != null || command.equals("CANCELLED"),
        "Human approval is required");
    store.audit(
        "EXPERIMENT",
        id,
        command,
        Objects.toString(input.get("reason"), ""),
        Objects.toString(input.get("user"), ""));
    prompts.changeExperiment(id, command, ((Number) e.get("revision")).intValue());
    store
        .db
        .sql("update prompt_experiments set feedback_stage=? where id=?")
        .params(command, id)
        .update();
    return detail(id);
  }

  @Transactional
  public Object generate(UUID id) {
    store
        .db
        .sql("select experiment_id from feedback_experiment_plans where experiment_id=? for update")
        .param(id)
        .query()
        .singleRow();
    var plan = store.one("feedback_experiment_plans", id);
    check(plan.get("approved_at") != null, "Human approval is required");
    var e = prompts.experiment(id);
    check(e.get("status").equals("RUNNING"), "Start the approved experiment first");
    var d = map(plan.get("definition"));
    int target = ((Number) plan.get("target_sample")).intValue();
    var variants = prompts.variants(id);
    var assigned = new HashMap<String, Integer>();
    for (var v : variants)
      assigned.put(v.get("key").toString(), ((Number) v.get("generations")).intValue());
    int created = 0;
    // The same TASK-03 assignment algorithm is used; idempotency keys are deterministic and
    // retained.
    for (int i = 0;
        i < target * 100
            && created < 25
            && (assigned.get("A") < target || assigned.get("B") < target);
        i++) {
      String key = "feedback:" + id + ":" + i;
      var selected = ExperimentAssignment.assign(id, key, variants);
      String variant = selected.get("key").toString();
      if (assigned.get(variant) >= target) continue;
      if (store
              .db
              .sql("select count(*) from jobs where idempotency_key=?")
              .param(key)
              .query(Long.class)
              .single()
          > 0) continue;
      var request =
          new PromptRenderRequest(
              uuid(d, "controlVersionId"),
              Map.of(),
              List.of(),
              d.get("provider").toString(),
              id,
              key,
              uuid(d, "conceptId"),
              "default",
              null,
              null,
              null,
              null);
      factory.generatePrompt(
          uuid(d, "conceptId"),
          integer(d, "width", 1024),
          integer(d, "height", 1024),
          key,
          null,
          new ImageOptions(
              d.get("provider").toString(),
              d.get("model").toString(),
              null,
              null,
              null,
              null,
              null,
              null,
              false,
              1),
          request);
      assigned.put(variant, assigned.get(variant) + 1);
      created++;
    }
    return Map.of(
        "experimentId",
        id,
        "created",
        created,
        "assigned",
        assigned,
        "more",
        assigned.get("A") < target || assigned.get("B") < target);
  }

  public Map<String, Object> detail(UUID id) {
    var out = new LinkedHashMap<>(prompts.experiment(id));
    out.put("plan", store.one("feedback_experiment_plans", id));
    out.put(
        "funnel",
        store
            .db
            .sql(
                """
                select v.id,v.key,count(g.id) assigned,count(a.id) generated,count(*) filter(where g.status in ('APPROVED','PUBLISHED')) qa_approved,
                count(*) filter(where l.rejection_reason='DUPLICATE') duplicate_rejected,count(*) filter(where g.status='REJECTED') qa_rejected,count(*) filter(where g.status='FAILED') failed,
                count(*) filter(where l.processing_profiles is not null) processed,count(*) filter(where exists(select 1 from publications p where p.asset_id=a.id)) published
                from prompt_experiment_variants v left join generations g on g.experiment_variant_id=v.id left join assets a on a.generation_id=g.id left join analytics_lineage l on l.generation_id=g.id
                where v.experiment_id=? group by v.id,v.key order by v.key
                """)
            .param(id)
            .query()
            .listOfRows());
    out.put(
        "costs",
        store
            .db
            .sql(
                "select g.experiment_variant_id,c.currency,sum(c.estimated_cost)"
                    + " estimated,sum(c.actual_cost) actual,count(*) filter(where c.actual_cost is"
                    + " null) estimated_only from generation_costs c join generations g on"
                    + " g.id=c.generation_id where g.experiment_id=? group by"
                    + " g.experiment_variant_id,c.currency")
            .param(id)
            .query()
            .listOfRows());
    out.put(
        "timeline",
        store
            .db
            .sql("select * from feedback_audit where entity_id=? order by created_at")
            .param(id)
            .query()
            .listOfRows());
    out.put(
        "results",
        store
            .db
            .sql(
                "select * from feedback_experiment_results where experiment_id=? order by"
                    + " created_at desc limit 20")
            .param(id)
            .query()
            .listOfRows()
            .stream()
            .map(FeedbackStore::json)
            .toList());
    var plan = map(out.get("plan"));
    var definition = map(plan.get("definition"));
    out.put("controlPrompt", prompts.version(uuid(definition, "controlVersionId")));
    out.put("treatmentPrompt", prompts.version(uuid(definition, "treatmentVersionId")));
    out.put(
        "budget",
        store
            .db
            .sql(
                "select p.max_budget,coalesce(sum(coalesce(c.actual_cost,c.estimated_cost)),0)"
                    + " recorded_spend,case when count(*) filter(where c.id is not null and"
                    + " (coalesce(c.actual_cost,c.estimated_cost) is null or"
                    + " c.currency<>p.currency))=0 then"
                    + " p.max_budget-coalesce(sum(coalesce(c.actual_cost,c.estimated_cost)),0) end"
                    + " remaining,count(*) filter(where c.id is not null and"
                    + " (coalesce(c.actual_cost,c.estimated_cost) is null or"
                    + " c.currency<>p.currency)) unknown_charges from feedback_experiment_plans p"
                    + " left join generations g on g.experiment_id=p.experiment_id left join"
                    + " generation_costs c on c.generation_id=g.id where p.experiment_id=? group by"
                    + " p.experiment_id")
            .param(id)
            .query()
            .singleRow());
    return out;
  }
}
