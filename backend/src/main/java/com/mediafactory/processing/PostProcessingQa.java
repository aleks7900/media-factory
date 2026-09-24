package com.mediafactory.processing;

import com.mediafactory.quality.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Reuses TASK-04 evidence and policy engine. It does not create a new master asset/review. */
@Component
public class PostProcessingQa {
  private final List<VisionQualityProvider> providers;
  private final QaConfiguration config;
  private final QualityPolicyEngine engine;
  private final JdbcClient db;

  public PostProcessingQa(
      List<VisionQualityProvider> providers,
      QaConfiguration config,
      QualityPolicyEngine engine,
      JdbcClient db) {
    this.providers = providers;
    this.config = config;
    this.engine = engine;
    this.db = db;
  }

  public ProcessingProvider.Output inspect(
      UUID runId,
      UUID stepId,
      Map<String, Object> asset,
      ProcessingProvider.Output output,
      Map<String, Object> profile) {
    String providerId = profile.getOrDefault("visionProvider", "mock").toString();
    // Explicit profile and global opt-in are both required for paid QA.
    if (!providerId.equals("mock") && !providerId.equals(config.provider()))
      throw new ProcessingFailure("VISUAL_QA_PROVIDER_DISABLED");
    var provider =
        providers.stream()
            .filter(p -> p.providerId().equals(providerId))
            .findFirst()
            .orElseThrow(() -> new ProcessingFailure("VISUAL_QA_PROVIDER_DISABLED"));
    long start = System.nanoTime();
    String model=profile.getOrDefault("visionModel",config.model(providerId)).toString();
    var result =
        provider.analyze(
            new VisionQualityProvider.Request(
                (UUID) asset.get("id"),
                (UUID) asset.get("generation_id"),
                output.bytes(),
                "image/" + output.validation().get("format").toString().toLowerCase(),
                model,
                profile.getOrDefault("visionScenario",config.scenario()).toString(),
                Map.of(
                    "processingRunId",
                    runId.toString(),
                    "purpose",
                    "post-processing artifact QA")));
    var policy = profile.containsKey("qaPolicySnapshot")?tools.jackson.databind.json.JsonMapper.builder().build().readValue(ProcessingJson.write(profile.get("qaPolicySnapshot")),QaPolicy.class):config.policy(profile.getOrDefault("qaPolicy", "default").toString());
    var evaluation =
        engine.evaluate(
            policy, result.evidence().findings(), result.evidence().dimensions(), true, true);
    var evidence =
        Map.of(
            "evidence",
            result.evidence(),
            "policy",
            policy,
            "evaluation",
            evaluation,
            "provider",
            providerId,
            "model",
            model);
    db.sql(
            "insert into"
                + " processing_compute_usage(run_id,step_id,provider,model,operation,input_usage,output_usage,duration_ms,device,external_cost,currency,metadata)"
                + " values(?,?,?,?,'VISUAL_QA',?,?,?,'external',?,?,?::jsonb)")
        .params(
            runId,
            stepId,
            providerId,
            model,
            Objects.requireNonNullElse(result.inputUsage(), 0L),
            Objects.requireNonNullElse(result.outputUsage(), 0L),
            (System.nanoTime() - start) / 1000000,
            result.estimatedCost(),
            result.currency(),
            ProcessingJson.write(evidence))
        .update();
    if (evaluation.finalDecision() != QualityModels.Decision.APPROVED)
      throw new ProcessingFailure("POST_PROCESSING_QA_" + evaluation.finalDecision());
    var metadata = new LinkedHashMap<>(output.metadata());
    metadata.put("visualQa", evidence);
    return new ProcessingProvider.Output(
        output.bytes(), output.validation(), metadata, output.durationMs());
  }
}
