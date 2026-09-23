package com.mediafactory.prompt;
import java.util.*;
public interface PromptTemplateRenderer {
 record RenderedTemplate(String text,Set<String> variables) {}
 RenderedTemplate render(String template,Map<String,Object> variables);
 Set<String> references(String template);
}
