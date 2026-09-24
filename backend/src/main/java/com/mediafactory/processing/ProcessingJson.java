package com.mediafactory.processing;

import java.util.*;
import tools.jackson.databind.json.JsonMapper;

public final class ProcessingJson {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  public static String write(Object value) {
    return JSON.writeValueAsString(value);
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> map(Object value) {
    return value instanceof Map<?, ?>
        ? (Map<String, Object>) value
        : JSON.readValue(value.toString(), Map.class);
  }

  public static String canonical(Object value) {
    return write(sorted(value));
  }

  private static Object sorted(Object value) {
    if (value instanceof Map<?, ?> m) {
      var result = new TreeMap<String, Object>();
      m.forEach((k, v) -> result.put(k.toString(), sorted(v)));
      return result;
    }
    if (value instanceof List<?> l) return l.stream().map(ProcessingJson::sorted).toList();
    return value;
  }

  public static int integer(Map<String, Object> m, String key, int fallback) {
    return ((Number) m.getOrDefault(key, fallback)).intValue();
  }

  public static double number(Map<String, Object> m, String key, double fallback) {
    return ((Number) m.getOrDefault(key, fallback)).doubleValue();
  }
}
