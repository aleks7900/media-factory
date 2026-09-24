package com.mediafactory.prompt;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Single-pass interpolation: inserted values are never reinterpreted as template syntax.
 */
@Component
public class SafePromptTemplateRenderer implements PromptTemplateRenderer {

  private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*}}");

  static String format(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof List<?> list) {
      return String.join(", ", list.stream().map(Object::toString).toList());
    }
    if (value instanceof BigDecimal d) {
      return d.stripTrailingZeros().toPlainString();
    }
    return value.toString();
  }

  static String normalize(String s) {
    return s == null ? "" : s.replace("\r\n", "\n").replace('\r', '\n');
  }

  public Set<String> references(String template) {
    String source = normalize(template);
    if (source.contains("{{{") || source.contains("}}}")) {
      throw PromptException.invalid("Invalid template syntax");
    }
    var matcher = TOKEN.matcher(source);
    var names = new LinkedHashSet<String>();
    int end = 0;
    while (matcher.find()) {
      checkLiteral(source.substring(end, matcher.start()));
      names.add(matcher.group(1));
      end = matcher.end();
    }
    checkLiteral(source.substring(end));
    return names;
  }

  private void checkLiteral(String value) {
    if (value.contains("{{") || value.contains("}}")) {
      throw PromptException.invalid("Invalid template syntax; only {{variableName}} is supported");
    }
  }

  public RenderedTemplate render(String template, Map<String, Object> variables) {
    var names = references(template);
    var m = TOKEN.matcher(normalize(template));
    var out = new StringBuilder();
    int end = 0;
    while (m.find()) {
      String key = m.group(1);
      if (!variables.containsKey(key)) {
        throw new PromptException("PROMPT_VARIABLE_INVALID", key, "Missing variable: " + key);
      }
      out.append(normalize(template), end, m.start()).append(format(variables.get(key)));
      end = m.end();
    }
    out.append(normalize(template).substring(end));
    return new RenderedTemplate(out.toString(), names);
  }
}
