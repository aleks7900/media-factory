package com.mediafactory.prompt;

import java.util.Map;
import java.util.Set;

public interface PromptTemplateRenderer {

  RenderedTemplate render(String template, Map<String, Object> variables);

  Set<String> references(String template);

  record RenderedTemplate(String text, Set<String> variables) {

  }
}
