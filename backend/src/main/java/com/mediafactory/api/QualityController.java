package com.mediafactory.api;

import com.mediafactory.quality.QaConfiguration;
import com.mediafactory.quality.QualityModels.Decision;
import com.mediafactory.quality.QualityModels.Finding;
import com.mediafactory.quality.QualityReviewService;
import com.mediafactory.quality.QualityReviewService.HumanCommand;
import com.mediafactory.quality.ReviewActor;
import com.mediafactory.quality.ReviewWorkflowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class QualityController {

  private final QualityReviewService reviews;
  private final ReviewWorkflowService workflow;
  private final QaConfiguration config;
  private final ReviewActor actor;
  private final JdbcClient db;

  public QualityController(QualityReviewService reviews, ReviewWorkflowService workflow,
      QaConfiguration config, ReviewActor actor, JdbcClient db) {
    this.reviews = reviews;
    this.workflow = workflow;
    this.config = config;
    this.actor = actor;
    this.db = db;
  }

  @GetMapping({"/reviews", "/reviews/queue"})
  public Object queue(@RequestParam Map<String, String> filters,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "24") int size) {
    return reviews.queue(filters, page, size);
  }

  @GetMapping("/reviews/{id}")
  public Object detail(@PathVariable UUID id) {
    return reviews.detail(id);
  }

  @PostMapping("/reviews/{id}/approve")
  public Object approve(@PathVariable UUID id, @RequestBody HumanCommand request) {
    return reviews.decide(id, Decision.APPROVED, request, actor.current());
  }

  @PostMapping("/assets/{id}/qa")
  public Object enqueueAssetQa(@PathVariable UUID id) {
    return reviews.enqueue(id, false, null, null);
  }

  @PostMapping("/reviews/{id}/reject")
  public Object reject(@PathVariable UUID id, @RequestBody HumanCommand request) {
    return reviews.decide(id, Decision.REJECTED, request, actor.current());
  }

  @PostMapping("/reviews/{id}/regenerate")
  public Object regenerate(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
      @RequestBody ReviewWorkflowService.Regenerate request) {
    return workflow.regenerate(id, key, request);
  }

  @PostMapping("/reviews/{id}/rerun")
  public Object rerun(@PathVariable UUID id, @RequestBody Rerun request) {
    return workflow.rerun(id, request.revision(), request.mockScenario());
  }

  @PostMapping("/reviews/{id}/findings")
  public Object finding(@PathVariable UUID id, @RequestBody AddedFinding request) {
    return reviews.addFinding(id, request.revision(), request.finding(), actor.current());
  }

  @PostMapping("/reviews/batch")
  public Object batch(@Valid @RequestBody Batch request) {
    var results = new ArrayList<Map<String, Object>>();
    for (var item : request.items()) {
      try {
        reviews.decide(item.id(), request.decision(),
            new HumanCommand(item.revision(), item.reasonCode(), item.reasonText()),
            actor.current());
        results.add(Map.of("id", item.id(), "success", true));
      } catch (ResponseStatusException e) {
        results.add(
            Map.of("id", item.id(), "success", false, "status", e.getStatusCode().value(), "error",
                Objects.toString(e.getReason(), "Review conflict")));
      } catch (IllegalArgumentException e) {
        results.add(
            Map.of("id", item.id(), "success", false, "status", 400, "error", e.getMessage()));
      }
    }
    return Map.of("results", results);
  }

  @GetMapping("/qa/policies")
  public Object policies() {
    return config.policies();
  }

  @GetMapping("/qa/dashboard")
  public Object dashboard() {
    return reviews.dashboard();
  }

  @GetMapping("/qa/jobs")
  public Object jobs() {
    return db.sql("select * from qa_jobs order by created_at desc limit 200").query().listOfRows();
  }

  @PutMapping("/collections/{id}/qa-policy")
  public Object policy(@PathVariable UUID id, @Valid @RequestBody PolicyRequest request) {
    config.policy(request.policyId());
    if (db.sql("update collections set qa_policy=? where id=?").params(request.policyId(), id)
        .update() != 1) {
      throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
    }
    return Map.of("id", id, "qaPolicy", request.policyId());
  }

  @PostMapping("/publications")
  public Object publish(@Valid @RequestBody PublicationRequest request) {
    return workflow.publish(request.assetId(), request.channel(), request.externalId());
  }

  public record Rerun(int revision, String mockScenario) {

  }

  public record AddedFinding(int revision, Finding finding) {

  }

  public record BatchItem(@NotNull UUID id, @Min(0) int revision, String reasonCode,
                          @Size(max = 4000) String reasonText) {

  }

  public record Batch(@NotNull @Size(min = 1, max = 100) List<@Valid BatchItem> items,
                      @NotNull Decision decision) {

  }

  public record PolicyRequest(@NotBlank String policyId) {

  }

  public record PublicationRequest(@NotNull UUID assetId, @NotBlank @Size(max = 100) String channel,
                                   @Size(max = 2000) String externalId) {

  }
}
