package com.mediafactory.skills;

import static com.mediafactory.processing.ProcessingJson.*;

import com.mediafactory.processing.ProcessingPlanner;
import com.mediafactory.provider.*;
import com.mediafactory.quality.QaConfiguration;
import com.mediafactory.service.FactoryService;
import com.mediafactory.stock.StockProductionService;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Read-only plan construction. Domain services remain the eligibility/policy authorities.
 */
@Service
public class SkillPlanService {

  public static final Set<String> SKILLS =
      Set.of(
          "research-trends", "create-collection", "run-qa", "prepare-stock", "create-wallpapers");
  final JdbcClient db;
  final FactoryService factory;
  final ImageGenerationProperties providers;
  final PricingService pricing;
  final QaConfiguration qa;
  final StockProductionService stock;
  final TextGenerationProvider textProvider;
  final VisionProvider visionProvider;

  @Value("${codex.skills.approval.generation-batch-threshold:20}")
  int threshold = 20;

  @Value("${codex.skills.enabled:true}")
  boolean enabled = true;

  public SkillPlanService(
      JdbcClient db,
      FactoryService factory,
      ImageGenerationProperties providers,
      PricingService pricing,
      QaConfiguration qa,
      StockProductionService stock,
      TextGenerationProvider textProvider,
      VisionProvider visionProvider) {
    this.db = db;
    this.factory = factory;
    this.providers = providers;
    this.pricing = pricing;
    this.qa = qa;
    this.stock = stock;
    this.textProvider = textProvider;
    this.visionProvider = visionProvider;
  }

  public static void check(boolean ok, String message) {
    if (!ok) {
      throw new IllegalArgumentException(message);
    }
  }

  public static UUID id(Map<String, Object> p, String key) {
    return UUID.fromString(text(p, key));
  }

  public static String text(Map<String, Object> p, String key) {
    String s = Objects.toString(p.get(key), "");
    check(!s.isBlank(), key + " is required");
    return s;
  }

  public static List<UUID> ids(Object value) {
    if (value == null) {
      return List.of();
    }
    check(value instanceof List<?>, "Expected an ID list");
    var list = (List<?>) value;
    check(list.size() <= 1000, "At most 1000 IDs");
    return list.stream().map(x -> UUID.fromString(x.toString())).distinct().toList();
  }

  public static void keys(Map<String, Object> p, String... allowed) {
    check(
        Set.of(allowed).containsAll(p.keySet()),
        "Unsupported input field; no option is silently ignored");
  }

  public static String safeName(String s) {
    check(s != null && !s.isBlank() && s.length() <= 200, "Name must have 1–200 characters");
    return s;
  }

  public static String slug(String s) {
    check(
        s != null && s.matches("[a-z0-9]+(?:-[a-z0-9]+)*") && s.length() <= 160,
        "Safe lowercase hyphenated slug required");
    return s;
  }

  public static void validateResearch(Map<String, Object> input) {
    check(
        input.get("directions") instanceof List<?> d && !d.isEmpty() && d.size() <= 30,
        "Provide 1–30 sourced directions");
    for (Object raw : (List<?>) input.get("directions")) {
      var d = map(raw);
      keys(
          d,
          "name",
          "description",
          "visualAttributes",
          "evidence",
          "externalSignal",
          "internalCoverage",
          "notes");
      safeName(text(d, "name"));
      check(text(d, "description").length() <= 4000, "Description too long");
      check(
          d.get("evidence") instanceof List<?> e && !e.isEmpty() && e.size() <= 30,
          "Every direction needs evidence");
      for (Object source : (List<?>) d.get("evidence")) {
        var e = map(source);
        keys(e, "source", "url", "observedAt", "sourceType", "observation");
        text(e, "source");
        text(e, "sourceType");
        text(e, "observation");
        URI uri = URI.create(text(e, "url"));
        check(
            Set.of("http", "https").contains(uri.getScheme())
                && uri.getHost() != null
                && uri.getUserInfo() == null
                && uri.getFragment() == null,
            "Use a public HTTP(S) citation without credentials/fragments");
        check(
            !uri.toString()
                .toLowerCase(Locale.ROOT)
                .matches(".*(token=|signature=|api_key=|password=|credential=).*"),
            "Do not persist signed or credential-bearing URLs");
        check(
            !Instant.parse(text(e, "observedAt")).isAfter(Instant.now().plusSeconds(300)),
            "Evidence observation cannot be in the future");
      }
    }
  }

  public void checkAuxiliaryAdmission(String skill, Map<String, Object> input) {
    boolean invokesQa =
        Set.of("run-qa", "prepare-stock", "create-wallpapers").contains(skill)
            || Boolean.TRUE.equals(input.get("generate"));
    if (invokesQa) {
      check(
          qa.route().stream().allMatch("mock"::equals),
          "BUDGET_EXCEEDED: paid QA preflight pricing is unavailable for this workflow");
    }
    if (skill.equals("prepare-stock")) {
      check(
          "mock".equals(textProvider.textIdentity().get("provider"))
              && "mock".equals(visionProvider.visionIdentity().get("provider")),
          "BUDGET_EXCEEDED: paid stock metadata preflight pricing is unavailable");
    }
  }

  public Map<String, Object> plan(
      String skill, UUID project, UUID collection, Map<String, Object> input) {
    check(enabled, "Skills are disabled");
    check(SKILLS.contains(skill), "Unknown skill");
    check(write(input).length() <= 100000, "Input too large");
    factory.one("projects", project);
    if (collection != null) {
      check(
          factory.one("collections", collection).get("project_id").equals(project),
          "Collection is outside project scope");
    }
    var p = new LinkedHashMap<String, Object>();
    p.put("skill", skill);
    p.put("version", 1);
    p.put("generationCount", 0);
    p.put("items", List.of());
    p.put("warnings", new ArrayList<String>());
    var warnings = (List<String>) p.get("warnings");
    switch (skill) {
      case "research-trends" -> {
        keys(
            input,
            "mediaType",
            "market",
            "platform",
            "topic",
            "audience",
            "region",
            "timeRange",
            "directions",
            "internalCoverage");
        text(input, "topic");
        validateResearch(input);
        p.put("directionCount", ((List<?>) input.get("directions")).size());
      }
      case "create-collection" -> {
        keys(
            input,
            "name",
            "slug",
            "description",
            "mediaType",
            "theme",
            "style",
            "amoled",
            "concepts",
            "targetAssetCount",
            "promptVersionId",
            "presetKeys",
            "generate",
            "provider",
            "model",
            "maxBudget",
            "currency",
            "width",
            "height",
            "trendCandidateId",
            "experimentId",
            "diversityPlan");
        check(collection == null, "Collection creation requires a new collection");
        safeName(text(input, "name"));
        String type = Objects.toString(input.get("mediaType"), "IMAGE");
        check(
            Set.of("IMAGE", "WALLPAPER", "STOCK").contains(type),
            "Unsupported collection pipeline");
        p.put("mediaType", type);
        if (type.equals("WALLPAPER")) {
          slug(text(input, "slug"));
          text(input, "theme");
          text(input, "style");
        }
        check(
            input.get("concepts") instanceof List<?> c && !c.isEmpty() && c.size() <= 1000,
            "Provide 1–1000 diversified concepts");
        var concepts = (List<?>) input.get("concepts");
        var names = new HashSet<String>();
        var seen = new HashSet<String>();
        for (Object value : concepts) {
          var c = map(value);
          keys(c, "name", "prompt", "variables");
          safeName(text(c, "name"));
          check(names.add(text(c, "name")), "Concept names must be unique");
          String prompt = text(c, "prompt");
          check(prompt.length() <= 10000, "Concept prompt too long");
          check(
              seen.add(prompt.trim().toLowerCase(Locale.ROOT)),
              "Diversify concepts instead of repeating identical prompts");
        }
        int target = integer(input, "targetAssetCount", concepts.size());
        check(
            target >= concepts.size() && target <= 1000,
            "Target must cover concepts and be at most 1000");
        p.put("targetAssetCount", target);
        p.put("conceptCount", concepts.size());
        if (input.containsKey("trendCandidateId")) {
          UUID candidate = id(input, "trendCandidateId");
          check(
              db.sql(
                      "select count(*) from trend_candidates c join trend_research_runs r on"
                          + " r.id=c.research_run_id join skill_executions e on"
                          + " e.id=r.execution_id where c.id=? and e.project_id=?")
                  .params(candidate, project)
                  .query(Long.class)
                  .single()
                  == 1,
              "Trend candidate outside project scope");
        }
        if (Boolean.TRUE.equals(input.get("generate"))) {
          check(
              type.equals("IMAGE"),
              "Compose create-wallpapers or prepare-stock after creating specialized collections");
          generation(p, input, target);
        }
      }
      case "run-qa", "prepare-stock" -> {
        if (skill.equals("run-qa")) {
          keys(
              input,
              "assetIds",
              "generationBatchId",
              "status",
              "from",
              "to",
              "profile",
              "forceRerun",
              "technicalOnly",
              "visualQa",
              "maxAssets");
        } else {
          keys(
              input,
              "assetIds",
              "status",
              "from",
              "to",
              "profile",
              "maxAssets",
              "regenerateMetadata",
              "reprocess",
              "exportPackage",
              "exportProfile");
        }
        var assets = selectAssets(project, collection, input);
        check(!assets.isEmpty(), "No assets match the explicit scope");
        p.put("items", assets);
        if (skill.equals("run-qa")) {
          check(
              !Boolean.TRUE.equals(input.get("technicalOnly"))
                  && !Boolean.FALSE.equals(input.get("visualQa")),
              "The current QA service runs its complete policy; technical-only is unsupported");
          if (input.containsKey("profile")) {
            qa.policy(text(input, "profile"));
          }
          p.put("qaProvider", qa.provider());
          warnings.add(
              "Completed matching reviews are reused; review decisions are never automatically"
                  + " overridden");
        } else {
          check(
              !Boolean.TRUE.equals(input.get("regenerateMetadata"))
                  && !Boolean.TRUE.equals(input.get("reprocess")),
              "Use the existing stock metadata/reprocess operations explicitly, then resume");
          String profile = text(input, "profile");
          p.put("profileHash", ProcessingPlanner.hash(stock.profile(profile)));
          var eligibleAssets = new ArrayList<UUID>();
          var excluded = new ArrayList<Map<String, Object>>();
          for (UUID asset : assets) {
            try {
              var a = eligible(asset);
              check(
                  "STOCK_STRICT".equals(a.get("similarity_profile")),
                  "Stock sources require a STOCK_STRICT collection");
              eligibleAssets.add(asset);
            } catch (IllegalArgumentException failure) {
              if (input.containsKey("assetIds")) {
                throw failure;
              }
              excluded.add(Map.of("assetId", asset, "reason", failure.getMessage()));
            }
          }
          check(!eligibleAssets.isEmpty(), "No eligible approved stock assets in scope");
          p.put("items", eligibleAssets);
          p.put("excluded", excluded);
          if (Boolean.TRUE.equals(input.get("exportPackage"))) {
            text(input, "exportProfile");
          }
          warnings.add("Stock metadata approval remains a human gate before export");
        }
      }
      case "create-wallpapers" -> {
        keys(
            input,
            "assetIds",
            "conceptIds",
            "targetCount",
            "profile",
            "deviceProfiles",
            "amoled",
            "provider",
            "model",
            "maxBudget",
            "currency",
            "generate",
            "processOnly",
            "publishToBackend",
            "createPreview",
            "experimentId",
            "metadata",
            "exportPackage");
        check(collection != null, "Wallpaper collection is required");
        var col = factory.one("collections", collection);
        check(Boolean.TRUE.equals(col.get("wallpaper")), "Select a wallpaper collection");
        String profile = text(input, "profile");
        var definition =
            db.sql("select definition from wallpaper_profiles where key=?")
                .param(profile)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown wallpaper profile"));
        var def = map(definition);
        p.put("profileHash", ProcessingPlanner.hash(def));
        check(
            !input.containsKey("amoled") || Objects.equals(input.get("amoled"), def.get("amoled")),
            "AMOLED request differs from profile");
        check(
            Objects.equals(col.get("amoled"), def.get("amoled")),
            "Collection and profile AMOLED settings differ");
        check(
            !input.containsKey("deviceProfiles")
                || new HashSet<>((List<?>) input.get("deviceProfiles"))
                .equals(new HashSet<>((List<?>) def.get("processingProfiles"))),
            "Device profiles must match the versioned wallpaper production profile");
        check(
            !Boolean.FALSE.equals(input.get("createPreview")), "Profile controls preview creation");
        check(
            !input.containsKey("experimentId"),
            "Specialized wallpaper experiment enrollment is not implemented; do not lose"
                + " attribution");
        boolean process = Boolean.TRUE.equals(input.get("processOnly"));
        check(
            !(process && Boolean.TRUE.equals(input.get("generate"))),
            "Choose generate or processOnly");
        if (process) {
          var assets = ids(input.get("assetIds"));
          check(!assets.isEmpty(), "processOnly requires explicit assetIds");
          for (UUID asset : assets) {
            var a = eligible(asset);
            check(
                a.get("collection_id").equals(collection),
                "Wallpaper source must belong to the target collection");
          }
          p.put("items", assets);
        } else {
          check(
              !Boolean.FALSE.equals(input.get("generate")),
              "Choose processOnly with assets or generation");
          var concepts = ids(input.get("conceptIds"));
          if (concepts.isEmpty()) {
            concepts =
                db.sql(
                        "select id from concepts where collection_id=? order by created_at,id limit"
                            + " 1001")
                    .param(collection)
                    .query(UUID.class)
                    .list();
          }
          check(!concepts.isEmpty() && concepts.size() <= 1000, "Select 1–1000 concepts");
          for (UUID concept : concepts) {
            check(
                factory.one("concepts", concept).get("collection_id").equals(collection),
                "Concept outside collection");
          }
          int count = integer(input, "targetCount", concepts.size());
          check(count >= 1 && count <= 1000, "Target must be 1–1000");
          p.put("items", concepts);
          p.put("targetAssetCount", count);
          generation(p, input, count);
        }
        p.put("expectedProcessingProfiles", def.get("processingProfiles"));
        warnings.add(
            "Publication preparation is not publication; explicit backend approval remains"
                + " required");
      }
    }
    if (collection != null) {
      p.put(
          "feedback",
          db.sql(
                  "select id,status,evidence_status,summary from feedback_learnings where"
                      + " scope->>'collectionId'=? order by created_at desc limit 10")
              .param(collection.toString())
              .query()
              .listOfRows());
      p.put(
          "saturation",
          db
              .sql(
                  "select s.id,s.result from feedback_saturation_results s join"
                      + " feedback_analysis_runs r on r.id=s.analysis_run_id where"
                      + " r.parameters->'scope'->>'collectionId'=? order by s.created_at desc limit"
                      + " 3")
              .param(collection.toString())
              .query()
              .listOfRows()
              .stream()
              .map(SkillExecutionService::json)
              .toList());
    }
    p.put("approvalThreshold", threshold);
    p.put("estimatedQaCost", null);
    p.put("localComputeCost", "UNPRICED");
    return p;
  }

  void generation(Map<String, Object> p, Map<String, Object> input, int count) {
    String provider = Objects.toString(input.get("provider"), providers.defaultProvider()),
        model = Objects.toString(input.get("model"), providers.provider(provider).model());
    check(
        providers.provider(provider).enabled()
            && providers.provider(provider).models().contains(model),
        "Provider/model unavailable");
    var quote = pricing.quote(provider, model, Map.of());
    p.put("generationCount", count);
    p.put("provider", provider);
    p.put("model", model);
    p.put("currency", quote.currency());
    p.put(
        "estimatedGenerationCost",
        quote.estimatedCost() == null
            ? null
            : quote.estimatedCost().multiply(BigDecimal.valueOf(count)));
    p.put("paid", !provider.equals("mock"));
    p.put("approvalRequired", !provider.equals("mock") && count >= threshold);
    if (input.containsKey("currency")) {
      check(
          quote.currency().equals(input.get("currency")),
          "Requested budget currency differs from pricing currency");
    }
    if (input.containsKey("maxBudget")) {
      var budget = new BigDecimal(input.get("maxBudget").toString());
      check(budget.signum() >= 0, "Budget must be non-negative");
      if (p.get("estimatedGenerationCost") != null) {
        check(
            budget.compareTo((BigDecimal) p.get("estimatedGenerationCost")) >= 0,
            "BUDGET_EXCEEDED: budget below estimate");
      }
    } else {
      check(provider.equals("mock"), "Paid generation requires maxBudget");
    }
  }

  public Map<String, Object> eligible(UUID asset) {
    var a =
        db.sql(
                "select"
                    + " a.id,a.generation_id,g.concept_id,c.collection_id,col.project_id,col.similarity_profile,q.final_decision"
                    + " from assets a join generations g on g.id=a.generation_id join concepts c on"
                    + " c.id=g.concept_id join collections col on col.id=c.collection_id left join"
                    + " quality_reviews q on q.id=a.current_review_id where a.id=?")
            .param(asset)
            .query()
            .singleRow();
    check(
        "APPROVED".equals(a.get("final_decision")), "Source asset requires effective QA approval");
    check(
        db.sql("select similarity_publication_block_reason(?)")
            .param(asset)
            .query(String.class)
            .optional()
            .isEmpty(),
        "SIMILARITY_BLOCKED");
    return a;
  }

  List<UUID> selectAssets(UUID project, UUID collection, Map<String, Object> input) {
    var selected = ids(input.get("assetIds"));
    StringBuilder sql =
        new StringBuilder(
            "select a.id from assets a join generations g on g.id=a.generation_id join concepts c"
                + " on c.id=g.concept_id join collections col on col.id=c.collection_id where"
                + " col.project_id=:project");
    var args = new HashMap<String, Object>();
    args.put("project", project);
    if (collection != null) {
      sql.append(" and c.collection_id=:collection");
      args.put("collection", collection);
    }
    if (!selected.isEmpty()) {
      sql.append(" and a.id in (:ids)");
      args.put("ids", selected);
    }
    if (input.containsKey("generationBatchId")) {
      sql.append(
          " and exists(select 1 from generation_batch_members b where b.generation_id=g.id and"
              + " b.batch_id=:batch)");
      args.put("batch", id(input, "generationBatchId"));
    }
    for (String boundary : List.of("from", "to")) {
      if (input.containsKey(boundary)) {
        sql.append(" and g.created_at ")
            .append(boundary.equals("from") ? ">=" : "<")
            .append("cast(:")
            .append(boundary)
            .append(" as timestamptz)");
        args.put(boundary, Instant.parse(text(input, boundary)).toString());
      }
    }
    if (input.containsKey("status")) {
      sql.append(" and g.status=:status");
      args.put("status", text(input, "status"));
    }
    int limit = integer(input, "maxAssets", 1000);
    check(limit >= 1 && limit <= 1000, "maxAssets must be 1–1000");
    sql.append(" order by g.created_at,a.id limit ").append(limit + 1);
    var found = db.sql(sql.toString()).params(args).query(UUID.class).list();
    check(found.size() <= limit, "Scope exceeds maxAssets; narrow the selection");
    check(
        selected.isEmpty() || found.size() == selected.size(),
        "Asset selection is outside project/collection scope or filters");
    return found;
  }
}
