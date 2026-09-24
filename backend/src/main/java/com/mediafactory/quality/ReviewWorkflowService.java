package com.mediafactory.quality;

import static com.mediafactory.quality.QualityReviewService.JSON;

import com.mediafactory.prompt.PromptModels.PromptRenderRequest;
import com.mediafactory.service.FactoryService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewWorkflowService {

  private final JdbcClient db;
  private final FactoryService factory;
  private final QualityReviewService reviews;
  private final ReviewActor actor;

  public ReviewWorkflowService(JdbcClient db, FactoryService factory, QualityReviewService reviews,
      ReviewActor actor) {
    this.db = db;
    this.factory = factory;
    this.reviews = reviews;
    this.actor = actor;
  }

  @Transactional
  public Map<String, Object> regenerate(UUID reviewId, String key, Regenerate input) {
    if (!Set.of("SAME_PROMPT", "MODIFIED_VARIABLES", "MANUAL_OVERRIDE").contains(input.mode())) {
      throw new IllegalArgumentException("Invalid regeneration mode");
    }
    if (key == null || key.isBlank() || key.length() > 200) {
      throw new IllegalArgumentException("Idempotency-Key required");
    }
    String request = JSON.writeValueAsString(input);
    db.sql("select pg_advisory_xact_lock(hashtextextended(?,0))").param(key).query().singleRow();
    var prior = db.sql(
            "select rr.* from regeneration_requests rr join jobs j on j.generation_id=rr.child_generation_id where j.idempotency_key=?")
        .param(key).query().listOfRows();
    if (!prior.isEmpty()) {
      var p = prior.getFirst();
      if (!reviewId.equals(p.get("review_id")) || !JSON.readTree(request)
          .equals(JSON.readTree(p.get("request").toString()))) {
        throw QualityReviewService.conflict(
            "Idempotency key already used with another regeneration request");
      }
      return factory.details((UUID) p.get("child_generation_id"));
    }
    var review = reviews.lockCurrent(reviewId, input.revision());
    var generation = factory.one("generations", (UUID) review.get("generation_id"));
    String feedback = Objects.toString(input.feedback(), "");
    if (feedback.length() > 4000) {
      throw new IllegalArgumentException("Feedback exceeds 4000 characters");
    }
    Map<String, Object> child;
    if (input.mode().equals("SAME_PROMPT")) {
      child = factory.regenerate((UUID) review.get("asset_id"), key);
    } else {
      PromptRenderRequest prompt;
      if (input.mode().equals("MANUAL_OVERRIDE")) {
        if (input.prompt() == null || input.prompt().isBlank() || input.prompt().length() > 10000
            || Objects.toString(input.negativePrompt(), "").length() > 10000) {
          throw new IllegalArgumentException("Manual prompt must contain 1–10000 characters");
        }
        prompt = PromptRenderRequest.adHoc(input.prompt(), input.negativePrompt());
      } else {
        if (generation.get("prompt_request") == null) {
          throw new IllegalArgumentException("Legacy generation requires manual prompt mode");
        }
        var original = JSON.readValue(generation.get("prompt_request").toString(),
            PromptRenderRequest.class);
        if (generation.get("prompt_version_id") == null) {
          throw new IllegalArgumentException("Modified variables require a versioned template");
        }
        var snapshot = JSON.readTree(review.get("context_snapshot").toString())
            .path("promptSnapshot");
        @SuppressWarnings("unchecked") var variables = new LinkedHashMap<String, Object>(
            JSON.convertValue(snapshot.path("variables"), Map.class));
        if (input.variables() != null) {
          variables.putAll(input.variables());
        }
        prompt = new PromptRenderRequest((UUID) generation.get("prompt_version_id"), variables,
            original.presets(), original.provider(), null, null,
            (UUID) generation.get("concept_id"), original.pipeline(),
            original.manualPositiveSuffix(), original.manualNegativeSuffix(), null, null);
      }
      child = factory.generatePrompt((UUID) generation.get("concept_id"),
          ((Number) generation.get("width")).intValue(),
          ((Number) generation.get("height")).intValue(), key, (UUID) generation.get("id"),
          FactoryService.options(generation), prompt, false);
    }
    db.sql(
            "insert into regeneration_requests(id,review_id,parent_generation_id,child_generation_id,mode,feedback,request,actor) values(?,?,?,?,?,?,cast(? as jsonb),?)")
        .params(UUID.randomUUID(), reviewId, generation.get("id"), child.get("id"), input.mode(),
            feedback, request, actor.current()).update();
    reviews.audit(reviewId, "REQUEST_REGENERATION", review.get("final_decision").toString(),
        review.get("final_decision").toString(), null, feedback, actor.current(),
        Map.of("mode", input.mode(), "childGenerationId", child.get("id")));
    db.sql("update quality_reviews set revision=revision+1 where id=?").param(reviewId).update();
    QualityReviewService.event("qa_regeneration_requested", reviewId,
        Map.of("mode", input.mode(), "child_generation_id", child.get("id")));
    return child;
  }

  @Transactional
  public Map<String, Object> rerun(UUID id, int revision, String scenario) {
    var review = reviews.lockCurrent(id, revision);
    return reviews.enqueue((UUID) review.get("asset_id"), true, null, scenario);
  }

  @Transactional
  public Map<String, Object> publish(UUID asset, String channel, String externalId) {
    var a = factory.one("assets", asset);
    db.sql("select id from generations where id=? for update").param(a.get("generation_id")).query()
        .singleRow();
    a = db.sql("select * from assets where id=? for update").param(asset).query().singleRow();
    if (a.get("current_review_id") == null || !"APPROVED".equals(
        reviews.review((UUID) a.get("current_review_id")).get("final_decision"))) {
      throw QualityReviewService.conflict("Effective QA approval required for publication");
    }
    UUID id = UUID.randomUUID();
    var similarityBlock = db.sql("select similarity_publication_block_reason(?)").param(asset)
        .query(String.class).optional();
    if (similarityBlock.isPresent()) {
      throw QualityReviewService.conflict(similarityBlock.get());
    }
    db.sql("insert into publications(id,asset_id,channel,external_id) values(?,?,?,?)")
        .params(id, asset, channel, externalId).update();
    db.sql("update generations set status='PUBLISHED',updated_at=now() where id=?")
        .param(a.get("generation_id")).update();
    return db.sql("select * from publications where id=?").param(id).query().singleRow();
  }

  public record Regenerate(int revision, String mode, Map<String, Object> variables, String prompt,
                           String negativePrompt, String feedback) {

  }
}
