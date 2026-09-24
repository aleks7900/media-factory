package com.mediafactory.api;

import com.mediafactory.prompt.PromptModels.PromptRenderRequest;
import com.mediafactory.quality.ReviewActor;
import com.mediafactory.similarity.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class DiversityController {
  private final DiversityGuard guard;
  private final GenerationBatchService batches;
  private final JdbcClient db;
  private final ReviewActor actor;

  public DiversityController(
      DiversityGuard guard, GenerationBatchService batches, JdbcClient db, ReviewActor actor) {
    this.guard = guard;
    this.batches = batches;
    this.db = db;
    this.actor = actor;
  }

  public record Preflight(UUID conceptId, String prompt, boolean semantic) {}

  @PostMapping("/diversity/preflight")
  public Object preflight(@RequestBody Preflight input) {
    if (input.prompt() == null || input.prompt().isBlank() || input.prompt().length() > 16000)
      throw new IllegalArgumentException("Prompt required");
    return SimilarityController.clean(
        input.semantic()
            ? guard.semantic(input.conceptId(), input.prompt())
            : guard.evaluate(input.conceptId(), input.prompt(), false));
  }

  @GetMapping("/generation-batches")
  public Object batches() {
    return SimilarityController.clean(
        db.sql("select * from generation_batches order by created_at desc limit 100")
            .query()
            .listOfRows());
  }

  @PostMapping("/generation-batches")
  public Object create(
      @RequestBody GenerationBatchService.Request input,
      @RequestHeader("Idempotency-Key") String key) {
    return SimilarityController.clean(batches.create(input, key));
  }

  public record Action(int revision, String action, String reason, PromptRenderRequest prompt) {}

  @PostMapping("/generation-batches/{id}/actions")
  public Object action(@PathVariable UUID id, @RequestBody Action input) {
    return SimilarityController.clean(
        batches.action(
            id, input.revision(), input.action(), input.reason(), input.prompt(), actor.current()));
  }
}
