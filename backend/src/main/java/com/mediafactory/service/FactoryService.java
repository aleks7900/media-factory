package com.mediafactory.service;

import com.mediafactory.domain.GenerationStatus;
import com.mediafactory.prompt.PromptEngine;
import com.mediafactory.prompt.PromptModels.PromptRenderRequest;
import com.mediafactory.provider.ImageGenerationProperties;
import com.mediafactory.provider.ImageOptions;
import com.mediafactory.provider.ProviderTypes.Request;
import com.mediafactory.provider.routing.ImageProviderRoutingStrategy;
import com.mediafactory.provider.routing.ProviderRoute;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class FactoryService {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final JdbcClient db;
  private final ImageProviderRoutingStrategy routing;
  private final ImageGenerationProperties properties;
  private final PromptEngine prompts;
  private final com.mediafactory.quality.QualityReviewService quality;
  private final com.mediafactory.similarity.DiversityGuard diversity;

  public FactoryService(JdbcClient db, ImageProviderRoutingStrategy routing,
      ImageGenerationProperties properties, PromptEngine prompts,
      com.mediafactory.quality.QualityReviewService quality, com.mediafactory.similarity.DiversityGuard diversity) {
    this.db = db;
    this.routing = routing;
    this.properties = properties;
    this.prompts = prompts;
    this.quality = quality;
    this.diversity = diversity;
  }

  public static ImageOptions options(Map<String, Object> generation) {
    return JSON.readValue(generation.get("request_options").toString(), ImageOptions.class);
  }

  public void enqueueQuality(UUID asset) {
    quality.enqueue(asset, false, null, null);
  }

  public Map<String, Object> one(String table, UUID id) {
    if (!Set.of("projects", "collections", "concepts", "generations", "assets", "jobs",
        "quality_reviews").contains(table)) {
      throw new IllegalArgumentException();
    }
    return db.sql("select * from " + table + " where id=:id").param("id", id).query().listOfRows()
        .stream().findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found"));
  }

  public List<Map<String, Object>> list(String table) {
    if (!Set.of("projects", "collections", "concepts", "generations", "assets", "jobs",
        "quality_reviews", "generation_costs").contains(table)) {
      throw new IllegalArgumentException();
    }
    return db.sql("select * from " + table + " order by created_at desc limit 200").query()
        .listOfRows();
  }

  public Map<String, Object> project(String name, String description) {
    UUID id = UUID.randomUUID();
    db.sql("insert into projects(id,name,description) values(?,?,?)").params(id, name, description)
        .update();
    return one("projects", id);
  }

  public Map<String, Object> collection(UUID project, String name) {
    one("projects", project);
    UUID id = UUID.randomUUID();
    db.sql("insert into collections(id,project_id,name) values(?,?,?)").params(id, project, name)
        .update();
    return one("collections", id);
  }

  public Map<String, Object> concept(UUID collection, String name, String prompt) {
    one("collections", collection);
    UUID id = UUID.randomUUID();
    db.sql("insert into concepts(id,collection_id,name,prompt) values(?,?,?,?)")
        .params(id, collection, name, prompt).update();
    return one("concepts", id);
  }

  @Transactional
  public Map<String, Object> generate(UUID concept, String prompt, int width, int height,
      String key, UUID parent) {
    return generateImage(concept, prompt, width, height, key, parent, ImageOptions.defaults());
  }

  @Transactional
  public Map<String, Object> generateImage(UUID concept, String prompt, int width, int height,
      String key, UUID parent, ImageOptions options) {
    return generatePrompt(concept, width, height, key, parent, options,
        PromptRenderRequest.adHoc(prompt, options.negativePrompt()));
  }

  @Transactional
  public Map<String, Object> generatePrompt(UUID concept, int width, int height, String key,
      UUID parent, ImageOptions options, PromptRenderRequest input) {
    return generatePrompt(concept, width, height, key, parent, options, input, true);
  }

  @Transactional
  public Map<String, Object> generatePrompt(UUID concept, int width, int height, String key,
      UUID parent, ImageOptions options, PromptRenderRequest input, boolean reuseParent) {
    String serialized = JSON.writeValueAsString(options);
    // Serialize identical request keys across all application instances before checking/replaying.
    db.sql("select pg_advisory_xact_lock(hashtextextended(?,0))").param(key).query().singleRow();
    var existing = db.sql(
            "select g.* from generations g join jobs j on j.generation_id=g.id where j.idempotency_key=?")
        .param(key).query().listOfRows().stream().findFirst();
    if (existing.isPresent()) {
      var row = existing.get();
      boolean samePrompt =
          row.get("prompt_request") == null ? Objects.equals(row.get("prompt"), input.prompt())
              : JSON.readTree(row.get("prompt_request").toString()).equals(JSON.valueToTree(input));
      if (!row.get("concept_id").equals(concept) || !samePrompt
          || ((Number) row.get("width")).intValue() != width
          || ((Number) row.get("height")).intValue() != height || !Objects.equals(
          row.get("parent_id"), parent) || !options(row).equals(options)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Idempotency key already used with a different request");
      }
      return row;
    }
    one("concepts", concept);
    UUID id = UUID.randomUUID();
    var resolved = parent == null || !reuseParent ? prompts.resolve(input, concept, key, true)
        : prompts.historical(parent);
    String prompt = resolved.canonical().positivePrompt();
    var diversityEvidence = diversity.enforce(concept, prompt, parent != null);
    var route = parent == null ? routing.resolve(
        new Request(id.toString(), prompt, width, height, prompts.adaptedOptions(options, null)))
        : new ProviderRoute("REGENERATE", route(one("generations", parent)));
    db.sql(
            "insert into generations(id,concept_id,parent_id,status,prompt,width,height) values(:id,:concept,:parent,'CREATED',:prompt,:width,:height)")
        .param("id", id).param("concept", concept).param("parent", parent).param("prompt", prompt)
        .param("width", width).param("height", height).update();
    db.sql(
            "update generations set request_options=cast(? as jsonb),provider_route=cast(? as jsonb),routing_mode=?,selected_provider=?,model=? where id=?")
        .params(serialized, JSON.writeValueAsString(route.providers()), route.mode(),
            route.providers().getFirst().provider(), route.providers().getFirst().model(), id)
        .update();
    UUID firstSnapshot = null;
    for (var hop : route.providers()) {
      var snapshot = parent == null || !reuseParent ? prompts.snapshot(id, resolved, hop.provider())
          : prompts.copySnapshot(parent, id, resolved, hop.provider());
      if (firstSnapshot == null) {
        firstSnapshot = snapshot;
      }
    }
    db.sql(
            "update generations set prompt_request=cast(? as jsonb),prompt_version_id=?,experiment_id=?,experiment_variant_id=?,prompt_snapshot_id=? where id=?")
        .params(JSON.writeValueAsString(input), resolved.versionId(), resolved.experimentId(),
            resolved.variantId(), firstSnapshot, id).update();
    transition(id, GenerationStatus.QUEUED);
    diversity.record(id, diversityEvidence);
    db.sql("insert into jobs(id,generation_id,idempotency_key,status) values(?,?,?,'QUEUED')")
        .params(UUID.randomUUID(), id, key).update();
    db.sql("update jobs set max_attempts=? where generation_id=?").params(route.providers().stream()
        .mapToInt(h -> properties.provider(h.provider()).retry().maxAttempts()).sum(), id).update();
    return one("generations", id);
  }

  public void transition(UUID id, GenerationStatus next) {
    var state = GenerationStatus.valueOf(
        db.sql("select status from generations where id=? for update").param(id).query(String.class)
            .single());
    if (!state.canTransitionTo(next)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Cannot transition " + state + " to " + next);
    }
    db.sql("update generations set status=?,updated_at=now() where id=?").params(next.name(), id)
        .update();
  }

  @Transactional
  public Map<String, Object> review(UUID asset, String decision, String reason) {
    var a = one("assets", asset);
    if (a.get("current_review_id") == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Run QA before reviewing this asset");
    }
    var r = quality.review((UUID) a.get("current_review_id"));
    return quality.decide((UUID) r.get("id"),
        com.mediafactory.quality.QualityModels.Decision.valueOf(decision),
        new com.mediafactory.quality.QualityReviewService.HumanCommand(
            ((Number) r.get("revision")).intValue(), null, reason), "local-workspace");
  }

  @Transactional
  public Map<String, Object> regenerate(UUID asset, String key) {
    var a = one("assets", asset);
    var g = one("generations", (UUID) a.get("generation_id"));
    var input =
        g.get("prompt_request") == null ? PromptRenderRequest.adHoc((String) g.get("prompt"),
            options(g).negativePrompt())
            : JSON.readValue(g.get("prompt_request").toString(), PromptRenderRequest.class);
    return generatePrompt((UUID) g.get("concept_id"), ((Number) g.get("width")).intValue(),
        ((Number) g.get("height")).intValue(), key, (UUID) g.get("id"), options(g), input);
  }

  @Transactional
  public Map<String, Object> retry(UUID id) {
    return retry(id, false);
  }

  @Transactional
  public Map<String, Object> retry(UUID id, boolean acknowledgeDuplicateRisk) {
    var job = db.sql("select * from jobs where id=? for update").param(id).query().listOfRows()
        .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (!job.get("status").equals("FAILED")) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Only failed jobs can be retried");
    }
    if (Boolean.TRUE.equals(job.get("recovery_required")) && !acknowledgeDuplicateRisk) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Provider outcome is unknown. A retry may create another paid image. Set acknowledgeDuplicateRisk to true after reconciliation.");
    }
    transition((UUID) job.get("generation_id"), GenerationStatus.QUEUED);
    db.sql(
            "update jobs set status='QUEUED', max_attempts=attempts+?,provider_attempts=0,execution_started=false,recovery_required=false, available_at=now(),finished_at=null,updated_at=now() where id=?")
        .params(properties.provider(route(one("generations", (UUID) job.get("generation_id"))).get(
            ((Number) job.get("route_index")).intValue()).provider()).retry().maxAttempts(), id)
        .update();
    return one("jobs", id);
  }

  public List<ProviderRoute.Hop> route(Map<String, Object> generation) {
    var values = JSON.readValue(generation.get("provider_route").toString(),
        ProviderRoute.Hop[].class);
    if (values.length > 0) {
      return List.of(values);
    }
    return routing.resolve(
        new Request(generation.get("id").toString(), (String) generation.get("prompt"),
            ((Number) generation.get("width")).intValue(),
            ((Number) generation.get("height")).intValue(), options(generation))).providers();
  }

  public Map<String, Object> details(UUID id) {
    var result = new LinkedHashMap<>(one("generations", id));
    result.put("request_options", options(result));
    result.put("provider_route", route(result));
    result.put("promptSnapshots", prompts.snapshots(id));
    result.put("attempts",
        db.sql("select * from generation_attempts where generation_id=? order by attempt_number")
            .param(id).query().listOfRows());
    result.put("assets",
        db.sql("select * from assets where generation_id=? order by created_at").param(id).query()
            .listOfRows());
    result.put("job",
        db.sql("select * from jobs where generation_id=?").param(id).query().singleRow());
    result.put("costs", db.sql(
            "select currency,sum(estimated_cost) as estimated_total,sum(actual_cost) as actual_total,count(*) filter(where estimated_cost is null) as unknown_attempts from generation_costs where generation_id=? group by currency")
        .param(id).query().listOfRows());
    return result;
  }

  public Request promptRequest(Map<String, Object> generation, ProviderRoute.Hop hop) {
    return prompts.executionRequest(generation, hop, options(generation));
  }

  public Map<String, Object> dashboard() {
    var result = new LinkedHashMap<>(db.sql("""
         select (select count(*) from assets where created_at >= date_trunc('day',now() at time zone 'UTC') at time zone 'UTC') as generated_today,
         (select count(*) from quality_reviews where decision='APPROVED' and created_at >= date_trunc('day',now() at time zone 'UTC') at time zone 'UTC') as approved_today,
         (select count(*) from quality_reviews where decision='REJECTED' and created_at >= date_trunc('day',now() at time zone 'UTC') at time zone 'UTC') as rejected_today,
         (select count(*) from generations where status in ('QA_PENDING','QA_RUNNING','NEEDS_REVIEW')) as pending_review,
         (select coalesce(sum(estimated_cost),0) from generation_costs where currency='USD') as generation_cost,
         (select count(*) from generation_costs where estimated_cost is null) as unknown_cost_attempts,
         (select count(*) from jobs where status in ('QUEUED','RUNNING'))+(select count(*) from qa_jobs where status in ('QUEUED','RUNNING')) as active_jobs,
         (select count(*) from jobs where status='FAILED')+(select count(*) from qa_jobs where status='FAILED') as failed_jobs
        """).query().singleRow());
    result.putAll(quality.dashboard());
    return result;
  }
}
