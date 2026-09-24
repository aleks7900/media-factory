package com.mediafactory.prompt;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ExperimentAssignment {

  private ExperimentAssignment() {
  }

  public static int bucket(UUID experiment, String key) {
    try {
      return (int) Long.remainderUnsigned(ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
          .digest((experiment + ":" + key).getBytes(StandardCharsets.UTF_8))).getLong(), 10000);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static Map<String, Object> assign(UUID experiment, String key,
      List<Map<String, Object>> variants) {
    if (key == null || key.isBlank()) {
      throw PromptException.invalid("Experiment requires a stable assignment key");
    }
    int total = variants.stream().mapToInt(v -> ((Number) v.get("weight")).intValue()).sum();
    if (total != 10000) {
      throw PromptException.invalid("Variant weights must total 10000 basis points");
    }
    int bucket = bucket(experiment, key), end = 0;
    for (var v : variants.stream().sorted(Comparator.comparing(v -> (String) v.get("key")))
        .toList()) {
      end += ((Number) v.get("weight")).intValue();
      if (bucket < end) {
        return v;
      }
    }
    throw new IllegalStateException();
  }

  public static boolean transition(String from, String to) {
    return switch (from) {
      case "DRAFT" -> Set.of("RUNNING", "CANCELLED").contains(to);
      case "RUNNING" -> Set.of("PAUSED", "COMPLETED", "CANCELLED").contains(to);
      case "PAUSED" -> Set.of("RUNNING", "CANCELLED").contains(to);
      default -> false;
    };
  }
}
