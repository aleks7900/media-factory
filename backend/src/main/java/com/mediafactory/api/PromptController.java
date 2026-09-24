package com.mediafactory.api;

import com.mediafactory.prompt.PromptCatalog;
import com.mediafactory.prompt.PromptEngine;
import com.mediafactory.prompt.PromptModels.ExperimentInput;
import com.mediafactory.prompt.PromptModels.PresetInput;
import com.mediafactory.prompt.PromptModels.PromptRenderRequest;
import com.mediafactory.prompt.PromptModels.TemplateInput;
import com.mediafactory.prompt.PromptModels.VersionInput;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PromptController {

  private final PromptCatalog catalog;
  private final PromptEngine engine;

  public PromptController(PromptCatalog catalog, PromptEngine engine) {
    this.catalog = catalog;
    this.engine = engine;
  }

  @PostMapping("/prompts/render-draft")
  public Object previewDraft(@RequestBody DraftPreview r) {
    return engine.previewDraft(r.version(), r.variables(), r.presets(), r.provider());
  }

  @GetMapping("/prompt-versions")
  public Object publishedVersions() {
    return catalog.publishedVersions();
  }

  @PostMapping("/prompt-templates")
  public Object create(@RequestBody TemplateInput r) {
    return catalog.createTemplate(r);
  }

  @GetMapping("/prompt-templates")
  public Object templates() {
    return catalog.templates();
  }

  @GetMapping("/prompt-templates/{id}")
  public Object template(@PathVariable UUID id) {
    return catalog.one("prompt_templates", id);
  }

  @PatchMapping("/prompt-templates/{id}")
  public Object editTemplate(@PathVariable UUID id, @RequestBody TemplateInput r) {
    return catalog.updateTemplate(id, r);
  }

  @PostMapping("/prompt-templates/{id}/versions")
  public Object createVersion(@PathVariable UUID id, @RequestBody VersionInput r) {
    return catalog.createVersion(id, r);
  }

  @GetMapping("/prompt-templates/{id}/versions")
  public Object versions(@PathVariable UUID id) {
    return catalog.versions(id);
  }

  @GetMapping("/prompt-versions/{id}")
  public Object version(@PathVariable UUID id) {
    return catalog.version(id);
  }

  @PatchMapping("/prompt-versions/{id}")
  public Object editVersion(@PathVariable UUID id, @RequestBody VersionInput r) {
    return catalog.editVersion(id, r);
  }

  @PostMapping("/prompt-versions/{id}/publish")
  public Object publish(@PathVariable UUID id, @RequestBody Revision r) {
    return catalog.publish(id, r.revision());
  }

  @PostMapping("/prompt-versions/{id}/deprecate")
  public Object deprecate(@PathVariable UUID id, @RequestBody Revision r) {
    return catalog.deprecate(id, r.revision());
  }

  @PostMapping("/prompt-versions/{id}/validate")
  public Object validate(@PathVariable UUID id) {
    return Map.of("valid", true, "warnings", catalog.validateVersion(id));
  }

  @PostMapping("/prompts/render")
  public Object preview(@RequestBody PromptRenderRequest r) {
    return engine.preview(r);
  }

  @PostMapping("/prompt-presets")
  public Object createPreset(@RequestBody PresetInput r) {
    return catalog.createPreset(r);
  }

  @GetMapping("/prompt-presets")
  public Object presets() {
    return catalog.presets();
  }

  @GetMapping("/prompt-presets/{id}")
  public Object preset(@PathVariable UUID id) {
    return catalog.preset(id);
  }

  @PatchMapping("/prompt-presets/{id}")
  public Object editPreset(@PathVariable UUID id, @RequestBody PresetInput r) {
    return catalog.updatePreset(id, r);
  }

  @PostMapping("/prompt-experiments")
  public Object createExperiment(@RequestBody ExperimentInput r) {
    return catalog.createExperiment(r);
  }

  @GetMapping("/prompt-experiments")
  public Object experiments() {
    return catalog.list("prompt_experiments").stream()
        .map(e -> catalog.experiment((UUID) e.get("id"))).toList();
  }

  @GetMapping("/prompt-experiments/{id}")
  public Object experiment(@PathVariable UUID id) {
    return catalog.experiment(id);
  }

  @PostMapping("/prompt-experiments/{id}/{action:start|pause|complete|cancel}")
  public Object lifecycle(@PathVariable UUID id, @PathVariable String action,
      @RequestBody Revision r) {
    return catalog.changeExperiment(id, switch (action) {
      case "start" -> "RUNNING";
      case "pause" -> "PAUSED";
      case "complete" -> "COMPLETED";
      default -> "CANCELLED";
    }, r.revision());
  }

  public record Revision(Integer revision) {

  }

  public record DraftPreview(VersionInput version, Map<String, Object> variables,
                             List<String> presets,
                             String provider) {

  }
}
