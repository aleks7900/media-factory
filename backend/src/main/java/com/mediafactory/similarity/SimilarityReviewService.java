package com.mediafactory.similarity;

import static com.mediafactory.similarity.SimilarityService.JSON;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SimilarityReviewService {

  private final SimilarityService similarity;

  public SimilarityReviewService(SimilarityService similarity) {
    this.similarity = similarity;
  }

  @Transactional
  public Object decide(UUID id, int revision, String classification, String reason, String actor) {
    if (!Set.of("DISTINCT", "NEAR_DUPLICATE", "PERCEPTUAL_DUPLICATE").contains(classification)) {
      throw new IllegalArgumentException("Unsupported human classification");
    }
    validateReason(reason);
    var initial =
        similarity
            .db()
            .sql("select * from similarity_comparisons where id=?")
            .param(id)
            .query()
            .singleRow();
    similarity
        .db()
        .sql("select pg_advisory_xact_lock(hashtextextended(?,0))")
        .param("similarity:" + initial.get("model_id"))
        .query()
        .singleRow();
    var before =
        similarity
            .db()
            .sql("select * from similarity_comparisons where id=? for update")
            .param(id)
            .query()
            .singleRow();
    if (((Number) before.get("revision")).intValue() != revision) {
      throw SimilarityService.conflict("Comparison changed; reload before reviewing");
    }
    similarity
        .db()
        .sql("select pg_advisory_xact_lock(hashtextextended(?,0))")
        .param("similarity:" + before.get("model_id"))
        .query()
        .singleRow();
    similarity
        .db()
        .sql(
            "update similarity_comparisons set"
                + " human_classification=?,final_classification=?,reason=?,reviewed_by=?,reviewed_at=now(),revision=revision+1"
                + " where id=?")
        .params(classification, classification, reason, actor, id)
        .update();
    var after =
        similarity
            .db()
            .sql("select * from similarity_comparisons where id=?")
            .param(id)
            .query()
            .singleRow();
    similarity
        .db()
        .sql(
            "insert into"
                + " similarity_review_actions(id,comparison_id,action,reason,actor,before_state,after_state)"
                + " values(?,?,?,?,?,cast(? as jsonb),cast(? as jsonb))")
        .params(
            UUID.randomUUID(),
            id,
            classification,
            reason,
            actor,
            JSON.writeValueAsString(before),
            JSON.writeValueAsString(after))
        .update();
    for (var sibling :
        similarity
            .db()
            .sql(
                "select * from similarity_comparisons where source_asset_id=? and target_asset_id=?"
                    + " and model_id=? and id<>? for update")
            .params(
                before.get("source_asset_id"),
                before.get("target_asset_id"),
                before.get("model_id"),
                id)
            .query()
            .listOfRows()) {
      similarity
          .db()
          .sql(
              "update similarity_comparisons set"
                  + " human_classification=?,final_classification=?,reason=?,reviewed_by=?,reviewed_at=now(),revision=revision+1"
                  + " where id=?")
          .params(classification, classification, reason, actor, sibling.get("id"))
          .update();
      var updated =
          similarity
              .db()
              .sql("select * from similarity_comparisons where id=?")
              .param(sibling.get("id"))
              .query()
              .singleRow();
      similarity
          .db()
          .sql(
              "insert into"
                  + " similarity_review_actions(id,comparison_id,action,reason,actor,before_state,after_state)"
                  + " values(?,?,?,?,?,cast(? as jsonb),cast(? as jsonb))")
          .params(
              UUID.randomUUID(),
              sibling.get("id"),
              classification,
              reason,
              actor,
              JSON.writeValueAsString(sibling),
              JSON.writeValueAsString(updated))
          .update();
    }
    // Retain historical families; rebuild affected membership from current pair decisions.
    var groups =
        similarity
            .db()
            .sql(
                "select distinct g.id from duplicate_groups g join duplicate_group_members m on"
                    + " m.group_id=g.id where g.model_id=? and g.status='OPEN' and m.asset_id in"
                    + " (?,?)")
            .params(
                before.get("model_id"),
                before.get("source_asset_id"),
                before.get("target_asset_id"))
            .query(UUID.class)
            .list();
    var members = new LinkedHashSet<UUID>();
    for (UUID group : groups) {
      members.addAll(
          similarity
              .db()
              .sql("select asset_id from duplicate_group_members where group_id=?")
              .param(group)
              .query(UUID.class)
              .list());
      similarity
          .db()
          .sql(
              "update duplicate_groups set status='SUPERSEDED',revision=revision+1,updated_at=now()"
                  + " where id=?")
          .param(group)
          .update();
    }
    for (UUID member : members) {
      for (var pair :
          similarity
              .db()
              .sql(
                  "select * from similarity_comparisons where model_id=? and (source_asset_id=? or"
                      + " target_asset_id=?) and final_classification in"
                      + " ('EXACT_DUPLICATE','PERCEPTUAL_DUPLICATE','NEAR_DUPLICATE')")
              .params(before.get("model_id"), member, member)
              .query()
              .listOfRows()) {
        similarity.groupPair(pair);
      }
    }
    similarity.groupPair(after);
    similarity
        .metrics()
        .counter("media_factory_similarity_reviews_total", "classification", classification)
        .increment();
    return after;
  }

  @Transactional
  public Object canonical(UUID group, UUID asset, int revision, String reason, String actor) {
    validateReason(reason);
    var before =
        similarity
            .db()
            .sql("select * from duplicate_groups where id=? for update")
            .param(group)
            .query()
            .singleRow();
    if (((Number) before.get("revision")).intValue() != revision
        || !"OPEN".equals(before.get("status"))) {
      throw SimilarityService.conflict("Duplicate group changed; reload");
    }
    if (!similarity
        .db()
        .sql("select exists(select 1 from duplicate_group_members where group_id=? and asset_id=?)")
        .params(group, asset)
        .query(Boolean.class)
        .single()) {
      throw new IllegalArgumentException("Canonical asset must be a group member");
    }
    similarity
        .db()
        .sql(
            "update duplicate_groups set canonical_asset_id=?,revision=revision+1,updated_at=now()"
                + " where id=?")
        .params(asset, group)
        .update();
    var after =
        similarity
            .db()
            .sql("select * from duplicate_groups where id=?")
            .param(group)
            .query()
            .singleRow();
    similarity
        .db()
        .sql(
            "insert into"
                + " similarity_review_actions(id,group_id,action,reason,actor,before_state,after_state)"
                + " values(?,?,'SELECT_CANONICAL',?,?,cast(? as jsonb),cast(? as jsonb))")
        .params(
            UUID.randomUUID(),
            group,
            reason,
            actor,
            JSON.writeValueAsString(before),
            JSON.writeValueAsString(after))
        .update();
    return after;
  }

  private void validateReason(String reason) {
    if (reason == null || reason.isBlank() || reason.length() > 2000) {
      throw new IllegalArgumentException("Review reason must contain 1–2000 characters");
    }
  }
}
