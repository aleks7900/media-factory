package com.mediafactory.similarity;

import static com.mediafactory.similarity.SimilarityService.JSON;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DiversityGuard {
  private final SimilarityService similarity;

  public DiversityGuard(SimilarityService similarity) {
    this.similarity = similarity;
  }

  public Map<String, Object> evaluate(UUID concept, String prompt, boolean regeneration) {
    var c =
        similarity
            .db()
            .sql(
                "select c.collection_id,p.* from concepts c join collections col on"
                    + " col.id=c.collection_id join similarity_profiles p on"
                    + " p.id=col.similarity_profile where c.id=?")
            .param(concept)
            .query()
            .singleRow();
    var recent =
        similarity
            .db()
            .sql(
                "select g.id,g.concept_id,g.prompt,g.prompt_version_id,g.prompt_request,g.parent_id"
                    + " from generations g join concepts c on c.id=g.concept_id where"
                    + " c.collection_id=? order by g.created_at desc,g.id desc limit 50")
            .param(c.get("collection_id"))
            .query()
            .listOfRows();
    String normalized = normalize(prompt);
    long exact =
        recent.stream()
            .filter(r -> normalize(r.get("prompt").toString()).equals(normalized))
            .count();
    long related =
        recent.stream()
            .filter(r -> jaccard(normalized, normalize(r.get("prompt").toString())) >= .80)
            .count();
    var runs =
        similarity
            .db()
            .sql(
                "select statistics from collection_clustering_runs where collection_id=? and"
                    + " model_id=? order by created_at desc limit 1")
            .params(c.get("collection_id"), similarity.activeModel().id())
            .query(String.class)
            .list();
    double largest =
        runs.isEmpty() ? 0 : JSON.readTree(runs.getFirst()).path("largestClusterShare").asDouble();
    boolean high = !regeneration && exact >= 3 && exact / (double) Math.max(1, recent.size()) >= .8;
    String decision =
        high
            ? "HIGH_REPETITION_RISK"
            : related > 0 || largest > ((Number) c.get("saturation_threshold")).doubleValue()
                ? "WARNING"
                : "CLEAR";
    var recommendations = new ArrayList<String>();
    if (exact > 0)
      recommendations.add(
          "The same frozen prompt appears "
              + exact
              + " times in the recent "
              + recent.size()
              + " generations. Change subject, composition, or viewpoint.");
    if (related > exact)
      recommendations.add(
          "Related prompt wording appears "
              + related
              + " times; vary lighting or environment while preserving the collection theme.");
    if (largest > 0.5)
      recommendations.add(
          "The largest visual family contains "
              + Math.round(largest * 100)
              + "% of the last analyzed collection. Review its representative before adding more"
              + " images.");
    if (regeneration)
      recommendations.add("Regeneration lineage is expected to repeat the source prompt.");
    return Map.of(
        "decision",
        decision,
        "blocked",
        similarity.enabled() && high && "BLOCK".equals(c.get("guard_mode")),
        "collectionId",
        c.get("collection_id"),
        "recentWindow",
        recent.size(),
        "exactPromptRepeats",
        exact,
        "relatedPromptRepeats",
        related,
        "largestClusterShare",
        largest,
        "recommendations",
        recommendations,
        "method",
        "frozen-prompt-normalization-and-token-Jaccard-v1");
  }

  public Map<String, Object> enforce(UUID concept, String prompt, boolean regeneration) {
    var result = evaluate(concept, prompt, regeneration);
    if (Boolean.TRUE.equals(result.get("blocked")))
      throw SimilarityService.conflict(
          "Diversity Guard: repeated frozen prompt exceeds strict policy. Change the prompt or"
              + " collection policy before spending.");
    return result;
  }

  public void record(UUID generation, Map<String, Object> evidence) {
    similarity
        .db()
        .sql(
            "insert into diversity_guard_events(id,collection_id,generation_id,decision,evidence)"
                + " values(?,?,?,?,cast(? as jsonb))")
        .params(
            UUID.randomUUID(),
            evidence.get("collectionId"),
            generation,
            evidence.get("decision"),
            JSON.writeValueAsString(evidence))
        .update();
  }

  /**
   * Optional shared-space semantic preflight, outside generation transactions; never blocks by
   * itself.
   */
  public Object semantic(UUID concept, String prompt) {
    var result = new LinkedHashMap<>(evaluate(concept, prompt, false));
    var model = similarity.activeModel();
    var output = similarity.textEmbedding(List.of(prompt), model);
    float[] vector = output.vectors().getFirst();
    similarity
        .db()
        .sql(
            "insert into concept_embeddings(id,concept_id,model_id,prompt_sha256,embedding)"
                + " values(?,?,?,?,cast(? as vector)) on conflict do nothing")
        .params(
            UUID.randomUUID(),
            concept,
            model.id(),
            PerceptualHash.sha(prompt.getBytes(StandardCharsets.UTF_8)),
            ImageEmbeddingProvider.literal(vector))
        .update();
    result.put("semanticCandidates", similarity.nearest(vector, model, 10));
    result.put("semanticEvidenceIsAdvisory", true);
    return result;
  }

  static String normalize(String value) {
    return value.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
  }

  static double jaccard(String a, String b) {
    var left = new HashSet<>(Arrays.asList(a.split("\\W+")));
    var right = new HashSet<>(Arrays.asList(b.split("\\W+")));
    var union = new HashSet<>(left);
    union.addAll(right);
    left.retainAll(right);
    return union.isEmpty() ? 0 : (double) left.size() / union.size();
  }
}
