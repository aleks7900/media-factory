package com.mediafactory.similarity;

import java.util.Map;

/** Classification is separate from retrieval and feature extraction. */
public interface SimilarityPolicy {
  record Context(
      boolean exact,
      Integer phash,
      Double cosine,
      boolean lowInformation,
      boolean sameCollection,
      boolean sameConcept,
      boolean samePromptVersion,
      boolean sameGenerationFamily) {}

  record Decision(String classification, String explanation) {}

  Decision evaluate(Context context, Map<String, Object> profile);
}
