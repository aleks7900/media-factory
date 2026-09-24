package com.mediafactory.similarity;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DefaultSimilarityPolicy implements SimilarityPolicy {

  public Decision evaluate(Context c, Map<String, Object> p) {
    if (c.exact()) {
      return new Decision(
          "EXACT_DUPLICATE",
          "SHA-256 proves identical bytes; lineage does not change byte identity.");
    }
    if (c.phash() != null
        && !c.lowInformation()
        && c.phash() <= ((Number) p.get("duplicate_distance")).intValue()) {
      return new Decision(
          "PERCEPTUAL_DUPLICATE",
          "DCT pHash distance is within the configured perceptual threshold; human confirmation"
              + " remains available.");
    }
    if (c.phash() != null
        && !c.lowInformation()
        && c.phash() <= ((Number) p.get("near_distance")).intValue()
        && c.cosine() != null
        && c.cosine() >= ((Number) p.get("near_similarity")).doubleValue()) {
      return new Decision(
          c.sameGenerationFamily() ? "VISUALLY_SIMILAR" : "NEAR_DUPLICATE",
          c.sameGenerationFamily()
              ? "Strong visual match within regeneration lineage; expected similarity downgraded."
              : "Both pHash and image embedding corroborate a near duplicate.");
    }
    if (c.cosine() != null && c.cosine() >= ((Number) p.get("similar_threshold")).doubleValue()) {
      return new Decision(
          "SEMANTICALLY_SIMILAR",
          "Embedding similarity indicates related content; it does not prove duplication.");
    }
    if (c.phash() != null && c.phash() <= ((Number) p.get("near_distance")).intValue()) {
      return new Decision(
          "VISUALLY_SIMILAR",
          c.lowInformation()
              ? "Low-information pHash is ambiguous; duplicate inference suppressed."
              : "Perceptual structure is related without corroborating embedding evidence.");
    }
    return new Decision(
        "DISTINCT", "No configured similarity rule matched the retrieved candidate.");
  }
}
