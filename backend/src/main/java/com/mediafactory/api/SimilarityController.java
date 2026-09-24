package com.mediafactory.api;

import static com.mediafactory.similarity.SimilarityService.JSON;

import com.mediafactory.quality.ReviewActor;
import com.mediafactory.similarity.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class SimilarityController {
  private final SimilarityService similarity;
  private final SimilarityReviewService reviews;
  private final EmbeddingModelService models;
  private final CollectionClusteringService clustering;
  private final JdbcClient db;
  private final ReviewActor actor;

  public SimilarityController(
      SimilarityService similarity,
      SimilarityReviewService reviews,
      EmbeddingModelService models,
      CollectionClusteringService clustering,
      JdbcClient db,
      ReviewActor actor) {
    this.similarity = similarity;
    this.reviews = reviews;
    this.models = models;
    this.clustering = clustering;
    this.db = db;
    this.actor = actor;
  }

  public static Object clean(Object value) {
    if (value instanceof Map<?, ?> m) {
      var out = new LinkedHashMap<String, Object>();
      m.forEach((k, v) -> out.put(k.toString(), clean(v)));
      return out;
    }
    if (value instanceof List<?> l) return l.stream().map(SimilarityController::clean).toList();
    if (value != null && value.getClass().getName().equals("org.postgresql.util.PGobject"))
      return JSON.readValue(value.toString(), Object.class);
    return value;
  }

  @GetMapping("/assets/{id}/similar")
  public Object similar(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "GLOBAL") String scope,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0.70") double minimumSimilarity,
      @RequestParam(required = false) String classification) {
    return clean(
        Map.of(
            "state",
            similarity.state(id, similarity.activeModel().id()),
            "model",
            similarity.activeModel(),
            "source",
            similarity.asset(id),
            "candidates",
            similarity.similar(id, scope, limit, minimumSimilarity, classification)));
  }

  public record Search(String query, int limit) {}

  @PostMapping("/assets/search/semantic")
  public Object semantic(@RequestBody Search request) {
    return clean(similarity.semantic(request.query(), request.limit()));
  }

  @GetMapping("/similarity-comparisons")
  public Object comparisons(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(required = false) String classification,
      @RequestParam(required = false) UUID collection,
      @RequestParam(required = false) String generationModel,
      @RequestParam(required = false) String after) {
    if (page < 0 || page > 100000) throw new IllegalArgumentException("Invalid page");
    return clean(
        db.sql(
                "select distinct s.* from similarity_comparisons s join assets a on"
                    + " a.id=s.source_asset_id join generations g on g.id=a.generation_id join"
                    + " concepts c on c.id=g.concept_id where s.model_id=? and (cast(? as text) is"
                    + " null or s.final_classification=?) and (cast(? as uuid) is null or"
                    + " c.collection_id=?) and (cast(? as text) is null or g.model=?) and (cast(?"
                    + " as timestamptz) is null or s.created_at>=cast(? as timestamptz)) order by"
                    + " s.created_at desc limit 50 offset ?")
            .params(
                similarity.activeModel().id(),
                classification,
                classification,
                collection,
                collection,
                generationModel,
                generationModel,
                after,
                after,
                page * 50)
            .query()
            .listOfRows());
  }

  public record Review(int revision, String classification, String reason) {}

  @PostMapping("/similarity-comparisons/{id}/confirm")
  public Object confirm(@PathVariable UUID id, @RequestBody Review input) {
    return clean(
        reviews.decide(
            id, input.revision(), input.classification(), input.reason(), actor.current()));
  }

  @PostMapping("/similarity-comparisons/{id}/mark-distinct")
  public Object distinct(@PathVariable UUID id, @RequestBody Review input) {
    return clean(reviews.decide(id, input.revision(), "DISTINCT", input.reason(), actor.current()));
  }

  @GetMapping("/similarity-comparisons/{id}/history")
  public Object history(@PathVariable UUID id) {
    return clean(
        db.sql("select * from similarity_review_actions where comparison_id=? order by created_at")
            .param(id)
            .query()
            .listOfRows());
  }

  @GetMapping("/duplicate-groups")
  public Object groups(@RequestParam(defaultValue = "0") int page) {
    if (page < 0 || page > 100000) throw new IllegalArgumentException("Invalid page");
    return clean(
        db.sql(
                "select g.*,(select count(*) from duplicate_group_members m where m.group_id=g.id)"
                    + " as member_count from duplicate_groups g where g.model_id=? and"
                    + " g.status='OPEN' order by g.created_at desc limit 50 offset ?")
            .params(similarity.activeModel().id(), page * 50)
            .query()
            .listOfRows());
  }

  @GetMapping("/duplicate-groups/{id}")
  public Object group(@PathVariable UUID id) {
    return clean(
        Map.of(
            "group",
            db.sql("select * from duplicate_groups where id=?").param(id).query().singleRow(),
            "members",
            db.sql(
                    "select m.*,a.width,a.height from duplicate_group_members m join assets a on"
                        + " a.id=m.asset_id where group_id=? order by m.asset_id limit 5000")
                .param(id)
                .query()
                .listOfRows()));
  }

  public record Canonical(UUID assetId, int revision, String reason) {}

  @PostMapping("/duplicate-groups/{id}/canonical")
  public Object canonical(@PathVariable UUID id, @RequestBody Canonical input) {
    return clean(
        reviews.canonical(id, input.assetId(), input.revision(), input.reason(), actor.current()));
  }

  @GetMapping("/collections/{id}/diversity")
  public Object diversity(@PathVariable UUID id) {
    return clean(clustering.diversity(id));
  }

  @GetMapping("/embedding-models")
  public Object models() {
    return clean(db.sql("select * from embedding_models order by created_at").query().listOfRows());
  }

  public record Registration(String provider) {}

  @PostMapping("/embedding-models")
  public Object register(@RequestBody Registration input) {
    return models.register(input.provider());
  }

  @PostMapping("/embedding-models/{id}/activate")
  public Object activate(@PathVariable UUID id) {
    return models.activate(id);
  }

  @GetMapping("/embedding-jobs")
  public Object jobs(@RequestParam(defaultValue = "0") int page) {
    if (page < 0 || page > 100000) throw new IllegalArgumentException("Invalid page");
    return clean(
        db.sql("select * from similarity_jobs order by created_at desc limit 50 offset ?")
            .param(page * 50)
            .query()
            .listOfRows());
  }

  public record Job(String type, UUID modelId, UUID collectionId) {}

  @PostMapping("/embedding-jobs")
  public Object enqueue(@RequestBody Job input, @RequestHeader("Idempotency-Key") String key) {
    return clean(
        models.enqueue(
            input.type(),
            input.modelId() == null ? similarity.activeModel().id() : input.modelId(),
            input.collectionId(),
            key));
  }

  @PostMapping("/embedding-jobs/{id}/retry")
  public Object retry(@PathVariable UUID id) {
    int n =
        db.sql(
                "update similarity_jobs set"
                    + " status='QUEUED',max_attempts=attempts+4,available_at=now(),failure_reason=null,finished_at=null,updated_at=now()"
                    + " where id=? and status='FAILED'")
            .param(id)
            .update();
    if (n != 1) throw SimilarityService.conflict("Only failed jobs can be retried");
    return Map.of("id", id, "status", "QUEUED");
  }

  @GetMapping("/similarity-profiles")
  public Object profiles() {
    return db.sql("select * from similarity_profiles order by id").query().listOfRows();
  }

  public record Profile(
      int revision,
      int duplicateDistance,
      int nearDistance,
      double nearSimilarity,
      double similarThreshold,
      boolean blockDuplicates,
      boolean blockNear,
      String guardMode,
      double saturationThreshold) {}

  @PutMapping("/similarity-profiles/{id}")
  public Object profile(@PathVariable String id, @RequestBody Profile input) {
    int n =
        db.sql(
                "update similarity_profiles set"
                    + " duplicate_distance=?,near_distance=?,near_similarity=?,similar_threshold=?,block_duplicates=?,block_near=?,guard_mode=?,saturation_threshold=?,revision=revision+1"
                    + " where id=? and revision=?")
            .params(
                input.duplicateDistance(),
                input.nearDistance(),
                input.nearSimilarity(),
                input.similarThreshold(),
                input.blockDuplicates(),
                input.blockNear(),
                input.guardMode(),
                input.saturationThreshold(),
                id,
                input.revision())
            .update();
    if (n != 1) throw SimilarityService.conflict("Profile changed; reload");
    return db.sql("select * from similarity_profiles where id=?").param(id).query().singleRow();
  }

  public record CollectionProfile(String profile) {}

  @PutMapping("/collections/{id}/similarity-profile")
  public Object collectionProfile(@PathVariable UUID id, @RequestBody CollectionProfile input) {
    db.sql("update collections set similarity_profile=? where id=?")
        .params(input.profile(), id)
        .update();
    return Map.of("id", id, "profile", input.profile());
  }

  @GetMapping("/similarity/dashboard")
  public Object dashboard() {
    return db.sql(
            "select (select count(*) from similarity_comparisons where model_id=? and"
                + " final_classification='EXACT_DUPLICATE') as exact_duplicates,(select count(*)"
                + " from similarity_comparisons where model_id=? and"
                + " final_classification='NEAR_DUPLICATE') as near_duplicates,(select count(*) from"
                + " similarity_comparisons where model_id=? and human_classification is null and"
                + " final_classification in"
                + " ('EXACT_DUPLICATE','PERCEPTUAL_DUPLICATE','NEAR_DUPLICATE')) as"
                + " pending_reviews,(select count(*) from similarity_jobs where status in"
                + " ('QUEUED','RUNNING')) as embedding_queue,(select count(*) from assets a where"
                + " not exists(select 1 from asset_embeddings e where e.asset_id=a.id and"
                + " model_id=?)) as assets_without_embeddings,(select count(*) from"
                + " generation_batches where status='PAUSED_DIVERSITY') as diversity_warnings")
        .params(
            similarity.activeModel().id(),
            similarity.activeModel().id(),
            similarity.activeModel().id(),
            similarity.activeModel().id())
        .query()
        .singleRow();
  }
}
