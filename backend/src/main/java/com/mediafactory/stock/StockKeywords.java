package com.mediafactory.stock;

import java.text.Normalizer;
import java.util.*;

public final class StockKeywords {
  private StockKeywords() {}

  public record Keyword(
      String value, String normalizedValue, int rank, String source, double confidence) {}

  public static String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}\\s-]", " ")
        .replaceAll("\\s+", " ")
        .trim();
  }

  public static List<Keyword> normalize(List<?> input, int maximum, String defaultSource) {
    if (input.size() > 200) throw new IllegalArgumentException("At most 200 input keywords");
    var result = new ArrayList<Keyword>();
    var seen = new HashSet<String>();
    for (Object item : input) {
      var m =
          item instanceof Map<?, ?>
              ? com.mediafactory.processing.ProcessingJson.map(item)
              : Map.<String, Object>of("value", item.toString());
      String value = normalize(Objects.toString(m.get("value"), ""));
      if (value.isBlank()) continue;
      if (value.length() > 160) throw new IllegalArgumentException("Keyword too long");
      String identity =
          Map.of("wolves", "wolf", "people", "person", "children", "child")
              .getOrDefault(value, value);
      if (!seen.add(identity)) continue;
      String source = Objects.toString(m.get("source"), defaultSource);
      if (!Set.of("VISION", "LLM", "CONCEPT", "PROMPT", "MANUAL").contains(source))
        source = defaultSource;
      double confidence = m.get("confidence") instanceof Number n ? n.doubleValue() : 1;
      if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1)
        throw new IllegalArgumentException("Invalid keyword confidence");
      result.add(new Keyword(value, value, result.size() + 1, source, confidence));
      if (result.size() == maximum) break;
    }
    return List.copyOf(result);
  }
}
