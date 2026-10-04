package com.mediafactory.skills;

import static com.mediafactory.processing.ProcessingJson.*;
import static com.mediafactory.skills.SkillPlanService.*;

import com.mediafactory.processing.ProcessingPlanner;
import com.mediafactory.prompt.PromptModels.PromptRenderRequest;
import com.mediafactory.provider.*;
import com.mediafactory.quality.QualityReviewService;
import com.mediafactory.service.FactoryService;
import com.mediafactory.similarity.DiversityGuard;
import com.mediafactory.stock.*;
import com.mediafactory.wallpaper.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Transactional adapters to existing services; never performs provider or media processing itself.
 */
@Service
public class SkillDomainOperations {
  final JdbcClient db;
  final FactoryService factory;
  final QualityReviewService qa;
  final StockProductionService stock;
  final StockCollectionService stockCollections;
  final StockExportService stockExports;
  final WallpaperProductionService wallpapers;
  final WallpaperCollectionService wallpaperCollections;
  final WallpaperPublicationService publications;
  final WallpaperExportService wallpaperExports;
  final DiversityGuard diversity;
  final ImageGenerationProperties providers;
  final SkillPlanService plans;

  @Value("${codex.skills.stage-size:5}")
  int stageSize = 5;

  public SkillDomainOperations(
      JdbcClient db,
      FactoryService factory,
      QualityReviewService qa,
      StockProductionService stock,
      StockCollectionService stockCollections,
      StockExportService stockExports,
      WallpaperProductionService wallpapers,
      WallpaperCollectionService wallpaperCollections,
      WallpaperPublicationService publications,
      WallpaperExportService wallpaperExports,
      DiversityGuard diversity,
      ImageGenerationProperties providers,
      SkillPlanService plans) {
    this.db = db;
    this.factory = factory;
    this.qa = qa;
    this.stock = stock;
    this.stockCollections = stockCollections;
    this.stockExports = stockExports;
    this.wallpapers = wallpapers;
    this.wallpaperCollections = wallpaperCollections;
    this.publications = publications;
    this.wallpaperExports = wallpaperExports;
    this.diversity = diversity;
    this.providers = providers;
    this.plans = plans;
  }

  Map<String, Object> row(String table, UUID id) {
    check(
        Set.of(
                "quality_reviews",
                "generations",
                "stock_productions",
                "wallpaper_productions",
                "stock_exports",
                "wallpaper_exports")
            .contains(table),
        "Unsupported resource");
    return SkillExecutionService.json(
        db.sql("select * from " + table + " where id=?").param(id).query().singleRow());
  }

  void item(UUID execution, String key, String operation, Object input) {
    db.sql(
            "insert into skill_execution_items(execution_id,item_key,operation,input)"
                + " values(?,?,?,?::jsonb) on conflict do nothing")
        .params(execution, key, operation, write(input))
        .update();
  }

  List<Map<String, Object>> items(UUID e) {
    return db
        .sql("select * from skill_execution_items where execution_id=? order by item_key")
        .param(e)
        .query()
        .listOfRows()
        .stream()
        .map(SkillExecutionService::json)
        .toList();
  }

  void resource(Map<String, Object> i, String type, UUID resource) {
    db.sql(
            "update skill_execution_items set"
                + " resource_type=?,resource_id=?,status='RUNNING',attempts=attempts+1 where id=?")
        .params(type, resource, i.get("id"))
        .update();
  }

  void state(Map<String, Object> i, String status, String code) {
    db.sql(
            "update skill_execution_items set status=?,error_code=?,completed_at=case when ? in"
                + " ('COMPLETED','FAILED','REJECTED') then now() else null end where id=?")
        .params(status, code, status, i.get("id"))
        .update();
  }

  public void advance(Map<String, Object> e) {
    UUID execution = (UUID) e.get("id");
    String skill = e.get("skill_name").toString();
    var input = map(e.get("input_summary"));
    var plan = map(e.get("plan"));
    if (skill.equals("research-trends")) {
      research(e, input);
      return;
    }
    if (skill.equals("create-collection") && e.get("collection_id") == null) {
      createCollection(e, input, plan);
    }
    if (items(execution).isEmpty()) {
      if (skill.equals("create-collection")) {
        if (!Boolean.TRUE.equals(input.get("generate"))) {
          finish(
              execution,
              "COMPLETED",
              Map.of(
                  "collectionId",
                  e.get("collection_id"),
                  "concepts",
                  plan.get("conceptCount"),
                  "plannedGenerations",
                  plan.get("targetAssetCount"),
                  "attempted",
                  0,
                  "generated",
                  0));
          return;
        }
        var concepts =
            db.sql("select id from concepts where collection_id=? order by created_at,id")
                .param(e.get("collection_id"))
                .query(UUID.class)
                .list();
        int target = integer(plan, "generationCount", 0);
        for (int n = 0; n < target; n++)
          item(
              execution,
              String.format("%05d", n),
              "GENERATE",
              Map.of("conceptId", concepts.get(n % concepts.size())));
      } else if (skill.equals("run-qa") || skill.equals("prepare-stock")) {
        int n = 0;
        for (UUID asset : ids(plan.get("items")))
          item(
              execution,
              String.format("%05d", n++),
              skill.equals("run-qa") ? "QA" : "STOCK",
              Map.of("assetId", asset));
      } else {
        var selected = ids(plan.get("items"));
        boolean process = Boolean.TRUE.equals(input.get("processOnly"));
        int count = process ? selected.size() : integer(plan, "targetAssetCount", selected.size());
        for (int n = 0; n < count; n++)
          item(
              execution,
              String.format("%05d", n),
              "WALLPAPER",
              Map.of(process ? "assetId" : "conceptId", selected.get(n % selected.size())));
      }
    }
    for (var i : items(execution))
      if (i.get("resource_id") != null
          && !Set.of("COMPLETED", "FAILED", "REJECTED").contains(i.get("status")))
        refresh(i, input);
    var all = items(execution);
    boolean active = all.stream().anyMatch(i -> i.get("status").equals("RUNNING")),
        waiting = all.stream().anyMatch(i -> i.get("status").equals("WAITING"));
    if (waiting) {
      finish(execution, "WAITING_FOR_APPROVAL", summary(all));
      return;
    }
    if (!active) {
      int dispatched = 0;
      for (var i : all)
        if (i.get("status").equals("PLANNED")
            && dispatched++ < Math.max(1, Math.min(stageSize, 20))) dispatch(e, i, input, plan);
    }
    all = items(execution);
    if (all.stream()
        .allMatch(i -> Set.of("COMPLETED", "FAILED", "REJECTED").contains(i.get("status")))) {
      if (Boolean.TRUE.equals(input.get("exportPackage"))
          && !all.stream().anyMatch(i -> i.get("operation").equals("EXPORT"))) {
        var succeeded = all.stream().filter(i -> i.get("status").equals("COMPLETED")).toList();
        if (!succeeded.isEmpty()) {
          if (skill.equals("prepare-stock")) {
            item(execution, "zz-export", "EXPORT", Map.of());
            var exportItem = items(execution).getLast();
            var exported =
                stockExports.request(
                    text(input, "exportProfile"),
                    succeeded.stream().map(i -> (UUID) i.get("resource_id")).toList(),
                    null,
                    false,
                    "STRICT",
                    "skill:" + execution + ":export",
                    null);
            resource(exportItem, "stock_exports", (UUID) exported.get("id"));
            return;
          }
          if (skill.equals("create-wallpapers")) {
            for (var production : succeeded) {
              item(
                  execution,
                  "zz-export-" + production.get("item_key"),
                  "EXPORT",
                  Map.of("productionId", production.get("resource_id")));
            }
            for (var exportItem : items(execution))
              if (exportItem.get("operation").equals("EXPORT")
                  && exportItem.get("resource_id") == null) {
                var exported =
                    map(
                        wallpaperExports.request(
                            id(map(exportItem.get("input")), "productionId"), null));
                resource(exportItem, "wallpaper_exports", (UUID) exported.get("id"));
              }
            return;
          }
        }
      }
      boolean failure = all.stream().anyMatch(i -> !i.get("status").equals("COMPLETED"));
      finish(
          execution,
          failure
              ? (all.stream().anyMatch(i -> i.get("status").equals("COMPLETED"))
                  ? "PARTIALLY_COMPLETED"
                  : "FAILED")
              : "COMPLETED",
          summary(all));
    } else
      db.sql("update skill_executions set result_summary=?::jsonb where id=?")
          .params(write(summary(all)), execution)
          .update();
  }

  void createCollection(
      Map<String, Object> e, Map<String, Object> input, Map<String, Object> plan) {
    UUID project = (UUID) e.get("project_id");
    String type = plan.get("mediaType").toString();
    Map<String, Object> c =
        switch (type) {
          case "WALLPAPER" ->
              map(
                  wallpaperCollections.create(
                      project,
                      text(input, "name"),
                      text(input, "slug"),
                      Objects.toString(input.get("description"), ""),
                      Objects.toString(input.get("theme"), ""),
                      Objects.toString(input.get("style"), ""),
                      Boolean.TRUE.equals(input.get("amoled"))));
          case "STOCK" -> map(stockCollections.create(project, text(input, "name")));
          default -> factory.collection(project, text(input, "name"));
        };
    UUID collection = (UUID) c.get("id");
    for (Object value : (List<?>) input.get("concepts")) {
      var concept = map(value);
      factory.concept(collection, text(concept, "name"), text(concept, "prompt"));
    }
    if (input.containsKey("trendCandidateId"))
      db.sql("update collections set trend_candidate_id=? where id=?")
          .params(id(input, "trendCandidateId"), collection)
          .update();
    db.sql("update skill_executions set collection_id=? where id=?")
        .params(collection, e.get("id"))
        .update();
    e.put("collection_id", collection);
  }

  void dispatch(
      Map<String, Object> e,
      Map<String, Object> item,
      Map<String, Object> input,
      Map<String, Object> plan) {
    UUID execution = (UUID) e.get("id");
    var selected = map(item.get("input"));
    String key = "skill:" + execution + ":" + item.get("item_key");
    plans.checkAuxiliaryAdmission(e.get("skill_name").toString(), input);
    switch (item.get("operation").toString()) {
      case "QA" -> {
        UUID asset = id(selected, "assetId");
        var review =
            qa.enqueue(
                asset,
                Boolean.TRUE.equals(input.get("forceRerun")),
                input.containsKey("profile") ? text(input, "profile") : null,
                null);
        resource(item, "quality_reviews", (UUID) review.get("id"));
      }
      case "STOCK" -> {
        UUID asset = id(selected, "assetId");
        plans.eligible(asset);
        check(
            ProcessingPlanner.hash(stock.profile(text(input, "profile")))
                .equals(plan.get("profileHash")),
            "Stock profile changed after planning; create a new plan");
        var production = stock.start(null, asset, text(input, "profile"), key);
        db.sql("update stock_productions set skill_execution_id=? where id=?")
            .params(execution, production.get("id"))
            .update();
        resource(item, "stock_productions", (UUID) production.get("id"));
      }
      case "GENERATE" -> {
        UUID concept = id(selected, "conceptId");
        var c = factory.one("concepts", concept);
        guard(execution, concept, c.get("prompt").toString());
        PromptRenderRequest prompt;
        if (input.containsKey("promptVersionId")) {
          var definitions = (List<?>) input.get("concepts");
          var original =
              definitions.stream()
                  .map(com.mediafactory.processing.ProcessingJson::map)
                  .filter(v -> v.get("name").equals(c.get("name")))
                  .findFirst()
                  .orElseThrow();
          prompt =
              new PromptRenderRequest(
                  id(input, "promptVersionId"),
                  original.containsKey("variables") ? map(original.get("variables")) : Map.of(),
                  ((List<?>) input.getOrDefault("presetKeys", List.of()))
                      .stream().map(Object::toString).toList(),
                  plan.get("provider").toString(),
                  input.containsKey("experimentId") ? id(input, "experimentId") : null,
                  key,
                  concept,
                  "default",
                  null,
                  null,
                  null,
                  null);
        } else {
          check(
              !input.containsKey("experimentId"),
              "Experiment enrollment needs a published prompt version");
          prompt = PromptRenderRequest.adHoc(c.get("prompt").toString(), null);
        }
        var generation =
            factory.generatePrompt(
                concept,
                integer(input, "width", 1024),
                integer(input, "height", 1024),
                key,
                null,
                new ImageOptions(
                    plan.get("provider").toString(),
                    plan.get("model").toString(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    1),
                prompt);
        db.sql("update generations set skill_execution_id=? where id=?")
            .params(execution, generation.get("id"))
            .update();
        resource(item, "generations", (UUID) generation.get("id"));
      }
      case "WALLPAPER" -> {
        String profile = text(input, "profile");
        var definition =
            db.sql("select definition from wallpaper_profiles where key=?")
                .param(profile)
                .query(String.class)
                .single();
        check(
            ProcessingPlanner.hash(map(definition)).equals(plan.get("profileHash")),
            "Wallpaper profile changed after planning; create a new plan");
        UUID concept =
            selected.containsKey("assetId")
                ? (UUID) plans.eligible(id(selected, "assetId")).get("concept_id")
                : id(selected, "conceptId");
        var c = factory.one("concepts", concept);
        if (!selected.containsKey("assetId")) {
          check(
              Objects.equals(providers.defaultProvider(), plan.get("provider"))
                  && Objects.equals(
                      providers.provider(providers.defaultProvider()).model(), plan.get("model")),
              "Wallpaper provider defaults changed or differ from the plan");
          guard(execution, concept, c.get("prompt").toString());
        }
        var metadata =
            new LinkedHashMap<String, Object>(
                input.containsKey("metadata") ? map(input.get("metadata")) : Map.of());
        metadata.putIfAbsent("title", c.get("name"));
        metadata.putIfAbsent("slug", "skill-" + execution + "-" + item.get("item_key"));
        var production =
            selected.containsKey("assetId")
                ? wallpapers.startFromAsset(id(selected, "assetId"), profile, metadata, key)
                : wallpapers.start(concept, profile, metadata, key, null);
        db.sql("update wallpaper_productions set skill_execution_id=? where id=?")
            .params(execution, production.get("id"))
            .update();
        resource(item, "wallpaper_productions", (UUID) production.get("id"));
      }
      default -> throw new IllegalArgumentException("Unsupported skill step");
    }
  }

  void guard(UUID execution, UUID concept, String prompt) {
    var evidence = diversity.evaluate(concept, prompt, false);
    if ("WARNING".equals(evidence.get("decision"))) {
      db.sql(
              "insert into skill_execution_events(execution_id,action,actor,reason)"
                  + " values(?,'DIVERSITY_WARNING','skill-orchestrator',?)")
          .params(execution, write(evidence))
          .update();
    }
    if ("HIGH_REPETITION_RISK".equals(evidence.get("decision"))
        || Boolean.TRUE.equals(evidence.get("blocked")))
      throw new IllegalArgumentException("SIMILARITY_BLOCKED: diversity review required");
  }

  void refresh(Map<String, Object> item, Map<String, Object> input) {
    String type = item.get("resource_type").toString();
    UUID resource = (UUID) item.get("resource_id");
    var r = row(type, resource);
    String status = Objects.toString(r.get("status"), "");
    state(item, "RUNNING", null);
    switch (type) {
      case "quality_reviews" -> {
        String execution = r.get("execution_status").toString();
        if (execution.equals("FAILED")) state(item, "FAILED", "QA_FAILED");
        else if (execution.equals("COMPLETED")) {
          String decision = r.get("final_decision").toString();
          state(
              item,
              decision.equals("REJECTED")
                  ? "REJECTED"
                  : decision.equals("NEEDS_REVIEW") ? "WAITING" : "COMPLETED",
              decision);
        }
      }
      case "generations" -> {
        if (Set.of("APPROVED", "PUBLISHED").contains(status)) state(item, "COMPLETED", null);
        else if (status.equals("REJECTED")) state(item, "REJECTED", "QA_REJECTED");
        else if (status.equals("FAILED")) state(item, "FAILED", "PROVIDER_ERROR");
        else if (status.equals("QA_PENDING")) {
          var review =
              db.sql(
                      "select q.execution_status,q.final_decision from assets a join"
                          + " quality_reviews q on q.id=a.current_review_id where"
                          + " a.generation_id=?")
                  .param(resource)
                  .query()
                  .listOfRows();
          if (!review.isEmpty()
              && review.getFirst().get("execution_status").equals("COMPLETED")
              && review.getFirst().get("final_decision").equals("NEEDS_REVIEW"))
            state(item, "WAITING", "HUMAN_QA_REQUIRED");
        }
      }
      case "stock_productions" -> {
        if (status.equals("READY_FOR_EXPORT") || status.equals("EXPORTED"))
          state(item, "COMPLETED", null);
        else if (status.contains("REJECTED")) state(item, "REJECTED", status);
        else if (status.contains("FAILED") || status.equals("CANCELLED"))
          state(item, "FAILED", status);
        else if (status.contains("REVIEW")
            || status.equals("PAUSED")
            || humanQa(r.get("source_asset_id"))) state(item, "WAITING", status);
      }
      case "wallpaper_productions" -> {
        if (Set.of("PUBLICATION_REVIEW", "APPROVED_FOR_PUBLICATION", "PUBLISHED", "UNPUBLISHED")
            .contains(status)) {
          publications.prepare(resource);
          if (Boolean.TRUE.equals(input.get("publishToBackend")) && !status.equals("PUBLISHED"))
            state(item, "WAITING", "PUBLICATION_APPROVAL_REQUIRED");
          else state(item, "COMPLETED", null);
        } else if (status.equals("PAUSED") && "STAGE_FAILED".equals(r.get("failure_code")))
          state(item, "FAILED", "STAGE_FAILED");
        else if (status.equals("PAUSED") || humanQa(r.get("master_asset_id")))
          state(item, "WAITING", Objects.toString(r.get("failure_code"), "HUMAN_QA_REQUIRED"));
        else if (status.contains("FAILED") || status.equals("CANCELLED"))
          state(item, "FAILED", status);
        else if (status.contains("REJECTED")) state(item, "REJECTED", status);
      }
      case "stock_exports", "wallpaper_exports" -> {
        if (status.equals(type.equals("stock_exports") ? "READY" : "COMPLETED")) {
          if (type.equals("stock_exports")) stockExports.download(resource);
          else wallpaperExports.download(resource);
          state(item, "COMPLETED", null);
        } else if (status.equals("FAILED")) state(item, "FAILED", "EXPORT_FAILED");
      }
    }
  }

  boolean humanQa(Object asset) {
    return asset != null
        && db.sql(
                    "select count(*) from assets a join quality_reviews q on"
                        + " q.id=a.current_review_id where a.id=? and"
                        + " q.execution_status='COMPLETED' and q.final_decision='NEEDS_REVIEW'")
                .param(asset)
                .query(Long.class)
                .single()
            > 0;
  }

  Map<String, Object> summary(List<Map<String, Object>> items) {
    var out = new LinkedHashMap<String, Object>();
    out.put("planned", items.stream().filter(i -> !i.get("operation").equals("EXPORT")).count());
    for (String state : List.of("PLANNED", "RUNNING", "WAITING", "COMPLETED", "FAILED", "REJECTED"))
      out.put(
          state.equals("PLANNED") ? "pending" : state.toLowerCase(Locale.ROOT),
          items.stream().filter(i -> i.get("status").equals(state)).count());
    out.put("attempted", items.stream().filter(i -> i.get("resource_id") != null).count());
    var reviews =
        items.stream()
            .filter(
                i ->
                    "quality_reviews".equals(i.get("resource_type"))
                        && i.get("resource_id") != null)
            .map(i -> (UUID) i.get("resource_id"))
            .toList();
    if (!reviews.isEmpty()) {
      out.put(
          "qaDecisions",
          db.sql(
                  "select execution_status,final_decision,count(*) count from quality_reviews where"
                      + " id in (:ids) group by execution_status,final_decision")
              .param("ids", reviews)
              .query()
              .listOfRows());
      out.put(
          "qaFindings",
          db.sql(
                  "select code,severity,count(*) count from quality_findings where detected and"
                      + " review_id in (:ids) group by code,severity order by count(*) desc,code")
              .param("ids", reviews)
              .query()
              .listOfRows());
    }
    out.put(
        "resources",
        items.stream()
            .filter(i -> i.get("resource_id") != null)
            .map(
                i ->
                    Map.of(
                        "type",
                        i.get("resource_type"),
                        "id",
                        i.get("resource_id"),
                        "status",
                        i.get("status")))
            .toList());
    return out;
  }

  void finish(UUID id, String status, Object summary) {
    db.sql(
            "update skill_executions set status=?,result_summary=?::jsonb,completed_at=case when ?"
                + " in ('COMPLETED','FAILED','PARTIALLY_COMPLETED') then now() else null"
                + " end,revision=revision+1 where id=?")
        .params(status, write(summary), status, id)
        .update();
  }

  void research(Map<String, Object> e, Map<String, Object> input) {
    UUID execution = (UUID) e.get("id"), run = UUID.randomUUID();
    db.sql("insert into trend_research_runs(id,execution_id,scope) values(?,?,?::jsonb)")
        .params(
            run,
            execution,
            write(
                input.entrySet().stream()
                    .filter(k -> !k.getKey().equals("directions"))
                    .collect(
                        java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))))
        .update();
    var candidates = new ArrayList<UUID>();
    for (Object raw : (List<?>) input.get("directions")) {
      var d = map(raw);
      UUID id = UUID.randomUUID();
      var evidence = (List<?>) d.get("evidence");
      long sources =
          evidence.stream()
              .map(com.mediafactory.processing.ProcessingJson::map)
              .map(v -> v.get("url"))
              .distinct()
              .count();
      db.sql(
              "insert into"
                  + " trend_candidates(id,research_run_id,name,description,media_type,visual_attributes,evidence,source_count,observed_at,external_signal,internal_coverage,notes)"
                  + " values(?,?,?,?,?,?::jsonb,?::jsonb,?,now(),?::jsonb,?::jsonb,?)")
          .params(
              id,
              run,
              text(d, "name"),
              text(d, "description"),
              Objects.toString(input.get("mediaType"), "IMAGE"),
              write(d.getOrDefault("visualAttributes", Map.of())),
              write(evidence),
              sources,
              write(d.getOrDefault("externalSignal", Map.of("status", "QUALITATIVE_OBSERVATION"))),
              write(d.getOrDefault("internalCoverage", Map.of("status", "NOT_MEASURED"))),
              Objects.toString(d.get("notes"), ""))
          .update();
      candidates.add(id);
    }
    finish(
        execution,
        "COMPLETED",
        Map.of(
            "researchRunId",
            run,
            "candidateIds",
            candidates,
            "generated",
            0,
            "evidenceType",
            "EXTERNAL_RESEARCH_NOT_INTERNAL_PERFORMANCE"));
  }
}
