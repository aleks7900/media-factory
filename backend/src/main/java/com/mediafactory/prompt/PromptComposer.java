package com.mediafactory.prompt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class PromptComposer {

  public String compose(String base, List<String> presets, String constraints, String override) {
    var parts = new ArrayList<String>();
    parts.add(base);
    parts.addAll(presets);
    parts.add(constraints);
    parts.add(override);
    return String.join("\n\n",
        parts.stream().filter(Objects::nonNull).map(SafePromptTemplateRenderer::normalize)
            .map(String::strip).filter(s -> !s.isEmpty()).toList());
  }

  public Map<String, String> constraints(String pipeline) {
    return switch (pipeline == null ? "default" : pipeline) {
      case "default" -> Map.of("positive", "", "negative", "");
      case "wallpaper" -> Map.of("positive",
          "Vertical composition, subject centered, safe lock-screen composition.", "negative",
          "UI, text");
      case "stock" -> Map.of("positive", "Commercial composition, clean framing.", "negative",
          "trademarks, visible logos");
      default -> throw PromptException.invalid("Unknown pipeline: " + pipeline);
    };
  }

  public List<String> lint(String positive, String negative) {
    var warnings = new ArrayList<String>();
    if (positive.length() > 8000) {
      warnings.add("Extremely long prompt; check provider length limits");
    }
    if (negative.isBlank()) {
      warnings.add("Empty negative prompt");
    }
    var phrases = new HashSet<String>();
    for (String p : positive.toLowerCase(Locale.ROOT).split("[,\\n]")) {
      p = p.strip();
      if (!p.isEmpty() && !phrases.add(p)) {
        warnings.add("Duplicate phrase: " + p);
        break;
      }
    }
    if (positive.toLowerCase(Locale.ROOT).contains("no text") && positive.toLowerCase(Locale.ROOT)
        .contains("include text")) {
      warnings.add("Potential conflict: no text / include text");
    }
    return warnings;
  }
}
