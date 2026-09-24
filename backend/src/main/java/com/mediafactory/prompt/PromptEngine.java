package com.mediafactory.prompt;

import static com.mediafactory.prompt.PromptCatalog.JSON;
import static com.mediafactory.prompt.PromptCatalog.bounded;
import static com.mediafactory.prompt.PromptCatalog.require;
import static com.mediafactory.prompt.PromptCatalog.text;

import com.mediafactory.prompt.PromptModels.AdaptedPrompt;
import com.mediafactory.prompt.PromptModels.CanonicalPrompt;
import com.mediafactory.prompt.PromptModels.PromptRenderRequest;
import com.mediafactory.prompt.PromptModels.ResolvedPrompt;
import com.mediafactory.prompt.PromptModels.VersionInput;
import com.mediafactory.provider.ImageOptions;
import com.mediafactory.provider.ProviderTypes.Request;
import com.mediafactory.provider.routing.ImageProviderRouter;
import com.mediafactory.provider.routing.ProviderRoute;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PromptEngine {

  private final PromptCatalog catalog;
  private final PromptTemplateRenderer renderer;
  private final PromptVariableValidator validator;
  private final PromptComposer composer;
  private final ImageProviderRouter router;
  private final JdbcClient db;
  private final Map<String, ProviderPromptAdapter> adapters;

  public PromptEngine(PromptCatalog catalog, PromptTemplateRenderer renderer,
      PromptVariableValidator validator, PromptComposer composer, ImageProviderRouter router,
      JdbcClient db, List<ProviderPromptAdapter> adapters) {
    this.catalog = catalog;
    this.renderer = renderer;
    this.validator = validator;
    this.composer = composer;
    this.router = router;
    this.db = db;
    var map = new HashMap<String, ProviderPromptAdapter>();
    for (var a : adapters) {
      if (map.put(a.providerId(), a) != null) {
        throw new IllegalStateException("Duplicate prompt adapter");
      }
    }
    this.adapters = Map.copyOf(map);
  }

  /**
   * Preview has no generation/attempt side effects and never calls an image provider.
   */
  @Transactional(readOnly = true)
  public Map<String, Object> preview(PromptRenderRequest request) {
    var resolved = resolve(request, request.conceptId(), request.assignmentKey(), false);
    var result = new LinkedHashMap<String, Object>();
    result.put("canonical", resolved.canonical());
    result.put("resolution", resolved);
    result.put("warnings", resolved.warnings());
    if (request.provider() != null && !request.provider().isBlank() && !request.provider()
        .equals("canonical")) {
      result.put("providerAdaptation", adapt(resolved.canonical(), request.provider()));
    }
    return result;
  }

  public Map<String, Object> previewDraft(VersionInput version, Map<String, Object> variables,
      List<String> presetKeys, String provider) {
    bounded(version.positiveTemplate(), 10000, "Positive template", true);
    bounded(version.negativeTemplate(), 10000, "Negative template", false);
    var warnings = catalog.validateContent(version.positiveTemplate(), version.negativeTemplate(),
        version.variables());
    var values = validator.resolve(version.variables(), variables == null ? Map.of() : variables);
    var presets = catalog.selectedPresets(presetKeys == null ? List.of() : presetKeys);
    var canonical = new CanonicalPrompt(
        composer.compose(renderer.render(version.positiveTemplate(), values).text(),
            presets.stream().map(p -> (String) p.get("positive_fragment")).toList(), "", ""),
        composer.compose(renderer.render(version.negativeTemplate(), values).text(),
            presets.stream().map(p -> (String) p.get("negative_fragment")).toList(), "", ""),
        values, presets);
    require(!canonical.positivePrompt().isBlank() && canonical.positivePrompt().length() <= 10000
            && canonical.negativePrompt().length() <= 10000,
        "Composed prompt is empty or exceeds 10000 characters");
    var result = new LinkedHashMap<String, Object>();
    result.put("canonical", canonical);
    result.put("warnings", warnings);
    if (provider != null && !provider.equals("canonical")) {
      result.put("providerAdaptation", adapt(canonical, provider));
    }
    return result;
  }

  public ResolvedPrompt resolve(PromptRenderRequest request, UUID concept, String assignmentKey,
      boolean production) {
    require(request != null, "Prompt request is required");
    bounded(request.manualPositiveSuffix(), 3000, "Manual positive suffix", false);
    bounded(request.manualNegativeSuffix(), 3000, "Manual negative suffix", false);
    var constraints = composer.constraints(request.pipeline());
    Map<String, Object> version = null;
    UUID experiment = null, variant = null;
    String positive, negative, kind;
    Map<String, Object> values = Map.of();
    var warnings = new ArrayList<String>();
    if (request.promptVersionId() == null) {
      require(request.experimentId() == null && request.variables().isEmpty(),
          "Ad-hoc prompts cannot have template variables or experiments");
      bounded(request.prompt(), 10000, "Ad-hoc positive prompt", true);
      bounded(request.negativePrompt(), 10000, "Ad-hoc negative prompt", false);
      positive = request.prompt();
      negative = text(request.negativePrompt());
      kind = "AD_HOC";
    } else {
      require(request.prompt() == null && request.negativePrompt() == null,
          "Choose a version or an ad-hoc prompt, not both");
      version = catalog.one("prompt_versions", request.promptVersionId());
      UUID template = (UUID) version.get("prompt_template_id");
      UUID collection = null;
      if (concept != null) {
        var rows = db.sql("select collection_id from concepts where id=?").param(concept)
            .query(UUID.class).list();
        require(!rows.isEmpty(), "Unknown concept");
        collection = rows.getFirst();
      }
      final UUID collectionId = collection;
      List<Map<String, Object>> experiments;
      if (request.experimentId() != null) {
        var e = catalog.one("prompt_experiments", request.experimentId());
        require(e.get("status").equals("RUNNING"), "Explicit experiment is not running");
        require(eligible(e, template, collectionId, request.pipeline()),
            "Generation is outside experiment scope");
        experiments = List.of(e);
      } else {
        experiments = db.sql("select * from prompt_experiments where status='RUNNING'").query()
            .listOfRows().stream()
            .filter(e -> eligible(e, template, collectionId, request.pipeline())).toList();
      }
      require(experiments.size() <= 1,
          "Multiple eligible experiments; select experimentId explicitly");
      if (!experiments.isEmpty()) {
        var e = experiments.getFirst();
        experiment = (UUID) e.get("id");
        if (production) {
          e = catalog.one("prompt_experiments", experiment, true);
          require(e.get("status").equals("RUNNING"), "Experiment is no longer running");
        }
        boolean override = !text(request.manualPositiveSuffix()).isBlank() || !text(
            request.manualNegativeSuffix()).isBlank();
        require(!override || Boolean.TRUE.equals(e.get("allow_overrides")),
            "Experiment does not allow manual overrides");
        var selected = ExperimentAssignment.assign(experiment, assignmentKey,
            catalog.variants(experiment));
        variant = (UUID) selected.get("id");
        version = catalog.one("prompt_versions", (UUID) selected.get("prompt_version_id"));
        require(version.get("prompt_template_id").equals(template),
            "Experiment template is incompatible");
      }
      if (production) {
        catalog.one("prompt_templates", template, true);
        version = catalog.one("prompt_versions", (UUID) version.get("id"), true);
        require(version.get("status").equals("PUBLISHED"),
            "Generation requires a published prompt version");
        require(catalog.one("prompt_templates", template, true).get("status").equals("ACTIVE"),
            "Template is archived");
      }
      var definitions = catalog.variables((UUID) version.get("id"));
      warnings.addAll(catalog.validateContent((String) version.get("positive_template"),
          (String) version.get("negative_template"), definitions));
      values = validator.resolve(definitions, request.variables());
      positive = renderer.render((String) version.get("positive_template"), values).text();
      negative = renderer.render((String) version.get("negative_template"), values).text();
      kind = "TEMPLATE";
    }
    var presets = catalog.selectedPresets(request.presets());
    positive = composer.compose(positive,
        presets.stream().map(p -> (String) p.get("positive_fragment")).toList(),
        constraints.get("positive"), request.manualPositiveSuffix());
    negative = composer.compose(negative,
        presets.stream().map(p -> (String) p.get("negative_fragment")).toList(),
        constraints.get("negative"), request.manualNegativeSuffix());
    require(!positive.isBlank() && positive.length() <= 10000 && negative.length() <= 10000,
        "Composed prompt is empty or exceeds 10000 characters");
    warnings.addAll(composer.lint(positive, negative));
    var composition = Map.<String, Object>of("pipeline",
        request.pipeline() == null ? "default" : request.pipeline(), "pipelineConstraints",
        constraints, "manualPositiveSuffix", text(request.manualPositiveSuffix()),
        "manualNegativeSuffix", text(request.manualNegativeSuffix()), "order",
        List.of("TEMPLATE", "PRESETS_IN_REQUEST_ORDER", "PIPELINE", "MANUAL_SUFFIX"),
        "engineVersion", "1");
    return new ResolvedPrompt(kind,
        version == null ? null : (UUID) version.get("prompt_template_id"),
        version == null ? null : (UUID) version.get("id"),
        version == null ? null : ((Number) version.get("version")).intValue(),
        new CanonicalPrompt(positive, negative, values, presets), composition, experiment, variant,
        experiment == null ? null : assignmentKey, warnings.stream().distinct().toList());
  }

  private boolean eligible(Map<String, Object> e, UUID template, UUID collection, String pipeline) {
    return switch ((String) e.get("scope")) {
      case "PROMPT_TEMPLATE" -> template.equals(e.get("prompt_template_id"));
      case "COLLECTION" -> collection != null && collection.equals(e.get("collection_id"));
      case "PIPELINE" ->
          Objects.equals(pipeline == null ? "default" : pipeline, e.get("pipeline_key"));
      default -> false;
    };
  }

  public AdaptedPrompt adapt(CanonicalPrompt canonical, String provider) {
    var adapter = adapters.get(provider);
    require(adapter != null, "No prompt adapter for provider: " + provider);
    var implementation = router.all().stream().filter(p -> p.providerId().equals(provider))
        .findFirst().orElseThrow(() -> PromptException.invalid("Unknown provider"));
    return adapter.adapt(canonical, implementation.capabilities());
  }

  public ImageOptions adaptedOptions(ImageOptions o, String negative) {
    return new ImageOptions(o.provider(), o.model(), o.aspectRatio(), o.quality(), o.format(),
        negative == null || negative.isBlank() ? null : negative, o.seed(), o.referenceImage(),
        o.transparentBackground(), o.numberOfImages());
  }

  public UUID snapshot(UUID generation, ResolvedPrompt resolved, String provider) {
    var adapted = adapt(resolved.canonical(), provider);
    return insertSnapshot(generation, resolved, provider, adapted);
  }

  public ResolvedPrompt historical(UUID generation) {
    var s = snapshots(generation).getFirst();
    return new ResolvedPrompt((String) s.get("kind"), (UUID) s.get("template_id"),
        (UUID) s.get("prompt_version_id"), (Integer) s.get("template_version"),
        new CanonicalPrompt((String) s.get("canonical_positive_prompt"),
            (String) s.get("canonical_negative_prompt"), (Map<String, Object>) s.get("variables"),
            (List<Map<String, Object>>) s.get("presets")),
        (Map<String, Object>) s.get("composition"), (UUID) s.get("experiment_id"),
        (UUID) s.get("experiment_variant_id"), (String) s.get("assignment_key"),
        (List<String>) s.get("warnings"));
  }

  public UUID copySnapshot(UUID parent, UUID generation, ResolvedPrompt resolved, String provider) {
    var rows = db.sql(
            "select * from rendered_prompt_snapshots where generation_id=? and provider=?")
        .params(parent, provider).query().listOfRows();
    if (rows.isEmpty()) {
      return snapshot(generation, resolved, provider);
    }
    var s = rows.getFirst();
    return insertSnapshot(generation, resolved, provider,
        new AdaptedPrompt((String) s.get("adapted_positive_prompt"),
            (String) s.get("adapted_negative_prompt"), (String) s.get("adaptation_strategy"),
            Arrays.asList(JSON.readValue(s.get("warnings").toString(), String[].class)), 0, 0));
  }

  private UUID insertSnapshot(UUID generation, ResolvedPrompt r, String provider, AdaptedPrompt a) {
    UUID id = UUID.randomUUID();
    var warnings = new ArrayList<>(r.warnings());
    warnings.addAll(a.warnings());
    db.sql("""
        insert into rendered_prompt_snapshots(id,generation_id,kind,template_id,prompt_version_id,template_version,variables,presets,composition,
        canonical_positive_prompt,canonical_negative_prompt,provider,adapted_positive_prompt,adapted_negative_prompt,adaptation_strategy,warnings,experiment_id,experiment_variant_id,assignment_key)
        values(?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),cast(? as jsonb),?,?,?,?,?,?,cast(? as jsonb),?,?,?)
        """).params(id, generation, r.kind(), r.templateId(), r.versionId(), r.version(),
        JSON.writeValueAsString(r.canonical().variables()),
        JSON.writeValueAsString(r.canonical().presets()), JSON.writeValueAsString(r.composition()),
        r.canonical().positivePrompt(), r.canonical().negativePrompt(), provider,
        a.positivePrompt(), a.negativePrompt(), a.strategy(), JSON.writeValueAsString(warnings),
        r.experimentId(), r.variantId(), r.assignmentKey()).update();
    return id;
  }

  public List<Map<String, Object>> snapshots(UUID generation) {
    var rows = db.sql(
            "select * from rendered_prompt_snapshots where generation_id=? order by rendered_at,provider")
        .param(generation).query().listOfRows();
    for (var r : rows) {
      for (String k : List.of("variables", "presets", "composition", "warnings")) {
        r.put(k, JSON.readValue(r.get(k).toString(), Object.class));
      }
    }
    return rows;
  }

  /**
   * Called before an external exchange. Existing adaptations are reused byte-for-byte.
   */
  @Transactional
  public Request executionRequest(Map<String, Object> generation, ProviderRoute.Hop hop,
      ImageOptions options) {
    UUID id = (UUID) generation.get("id");
    db.sql("select id from generations where id=? for update").param(id).query().singleRow();
    var rows = db.sql(
            "select * from rendered_prompt_snapshots where generation_id=? and provider=?")
        .params(id, hop.provider()).query().listOfRows();
    if (rows.isEmpty()) { // Existing TASK-02 jobs or explicit route recovery only; never read current templates.
      var source = db.sql(
              "select * from rendered_prompt_snapshots where generation_id=? order by rendered_at limit 1")
          .param(id).query().singleRow();
      var canonical = new CanonicalPrompt((String) source.get("canonical_positive_prompt"),
          (String) source.get("canonical_negative_prompt"), Map.of(), List.of());
      var adapted = adapt(canonical, hop.provider());
      UUID newId = UUID.randomUUID();
      db.sql("""
              insert into rendered_prompt_snapshots(id,generation_id,kind,template_id,prompt_version_id,template_version,variables,presets,composition,canonical_positive_prompt,canonical_negative_prompt,provider,adapted_positive_prompt,adapted_negative_prompt,adaptation_strategy,warnings,experiment_id,experiment_variant_id,assignment_key)
              select ?,generation_id,kind,template_id,prompt_version_id,template_version,variables,presets,composition,canonical_positive_prompt,canonical_negative_prompt,?,?,?,?,cast(? as jsonb),experiment_id,experiment_variant_id,assignment_key from rendered_prompt_snapshots where id=?
              """).params(newId, hop.provider(), adapted.positivePrompt(), adapted.negativePrompt(),
              adapted.strategy(), JSON.writeValueAsString(adapted.warnings()), source.get("id"))
          .update();
      rows = List.of(catalog.one("rendered_prompt_snapshots", newId));
    }
    var snapshot = rows.getFirst();
    return new Request(id.toString(), (String) snapshot.get("adapted_positive_prompt"),
        ((Number) generation.get("width")).intValue(),
        ((Number) generation.get("height")).intValue(),
        adaptedOptions(options, (String) snapshot.get("adapted_negative_prompt")));
  }
}
