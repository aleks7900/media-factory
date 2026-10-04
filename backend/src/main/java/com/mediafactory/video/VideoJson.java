package com.mediafactory.video;

import java.util.*;
import tools.jackson.databind.json.JsonMapper;

public final class VideoJson {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Set<String> JSON_COLUMNS =
      Set.of(
          "definition",
          "profile_snapshot",
          "route",
          "prompt_snapshot",
          "request_snapshot",
          "provider_prompt",
          "provider_metadata",
          "source_context",
          "parameters",
          "variants",
          "evidence",
          "metadata",
          "details");

  private VideoJson() {
  }

  public static Map<String, Object> row(Map<String, Object> input) {
    var out = new LinkedHashMap<>(input);
    out.replaceAll(
        (k, v) ->
            v != null && JSON_COLUMNS.contains(k) && !(v instanceof Map) && !(v instanceof List)
                ? JSON.readValue(v.toString(), Object.class)
                : v);
    return out;
  }

  public static List<Map<String, Object>> rows(List<Map<String, Object>> input) {
    return input.stream().map(VideoJson::row).toList();
  }
}
