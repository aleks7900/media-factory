package com.mediafactory.prompt;

import com.mediafactory.prompt.PromptModels.PromptVariableDefinition;
import com.mediafactory.prompt.PromptModels.VariableType;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PromptVariableValidator {

  public void definitions(List<PromptVariableDefinition> definitions) {
    if (definitions == null || definitions.size() > 100) {
      throw PromptException.invalid("Supply at most 100 variable definitions");
    }
    var names = new HashSet<String>();
    for (var d : definitions) {
      if (d == null || d.name() == null || !d.name().matches("[A-Za-z][A-Za-z0-9_]{0,79}")
          || !names.add(d.name())) {
        throw PromptException.invalid("Invalid or duplicate variable name");
      }
      if (d.type() == null) {
        bad(d, "Type is required");
      }
      if (d.label() != null && d.label().length() > 200) {
        bad(d, "Label exceeds 200 characters");
      }
      if ((d.min() != null || d.max() != null) && d.type() != VariableType.INTEGER
          && d.type() != VariableType.DECIMAL) {
        bad(d, "Numeric bounds require a numeric type");
      }
      if ((d.minLength() != null || d.maxLength() != null) && !Set.of(VariableType.STRING,
          VariableType.ENUM, VariableType.STRING_LIST).contains(d.type())) {
        bad(d, "Length bounds require a string, enum or list");
      }
      if (!d.allowedValues().isEmpty() && d.type() != VariableType.ENUM) {
        bad(d, "Allowed values apply only to enums");
      }
      if (d.min() != null && d.max() != null && d.min().compareTo(d.max()) > 0) {
        bad(d, "Minimum exceeds maximum");
      }
      if ((d.minLength() != null && d.minLength() < 0) || (d.maxLength() != null
          && d.maxLength() < 0) || (d.minLength() != null && d.maxLength() != null
          && d.minLength() > d.maxLength())) {
        bad(d, "Invalid length bounds");
      }
      if (d.type() == VariableType.ENUM && (d.allowedValues().isEmpty()
          || new HashSet<>(d.allowedValues()).size() != d.allowedValues().size())) {
        bad(d, "Enum needs unique allowed values");
      }
      if (d.defaultValue() != null) {
        validate(d, d.defaultValue());
      }
    }
  }

  public Map<String, Object> resolve(List<PromptVariableDefinition> definitions,
      Map<String, Object> values) {
    definitions(definitions);
    var result = new LinkedHashMap<String, Object>();
    var known = new HashSet<String>();
    definitions.forEach(d -> known.add(d.name()));
    for (String key : values.keySet()) {
      if (!known.contains(key)) {
        throw new PromptException("PROMPT_VARIABLE_INVALID", key, "Unknown variable: " + key);
      }
    }
    for (var d : definitions) {
      Object v = values.containsKey(d.name()) ? values.get(d.name()) : d.defaultValue();
      if (v == null) {
        if (Boolean.TRUE.equals(d.required())) {
          bad(d, "Required value is missing");
        }
        result.put(d.name(), "");
      } else {
        result.put(d.name(), validate(d, v));
      }
    }
    return Collections.unmodifiableMap(result);
  }

  private Object validate(PromptVariableDefinition d, Object v) {
    switch (d.type()) {
      case STRING, ENUM -> {
        if (!(v instanceof String)) {
          bad(d, "Expected a string");
        }
        String s = (String) v;
        if (Boolean.TRUE.equals(d.required()) && s.isBlank()) {
          bad(d, "Required value is blank");
        }
        length(d, s.length());
        if (d.type() == VariableType.ENUM && !d.allowedValues().contains(s)) {
          bad(d, "Value is not an allowed enum member");
        }
      }
      case BOOLEAN -> {
        if (!(v instanceof Boolean)) {
          bad(d, "Expected a boolean");
        }
      }
      case INTEGER, DECIMAL -> {
        if (!(v instanceof Number)) {
          bad(d, "Expected a number");
        }
        BigDecimal n;
        try {
          n = new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
          throw new PromptException("PROMPT_VARIABLE_INVALID", d.name(),
              "Expected a finite number");
        }
        if (d.type() == VariableType.INTEGER && n.stripTrailingZeros().scale() > 0) {
          bad(d, "Expected an integer");
        }
        if (d.min() != null && n.compareTo(d.min()) < 0) {
          bad(d, "Value is below minimum");
        }
        if (d.max() != null && n.compareTo(d.max()) > 0) {
          bad(d, "Value exceeds maximum");
        }
        return n;
      }
      case STRING_LIST -> {
        if (!(v instanceof List<?>)) {
          bad(d, "Expected a string list");
        }
        var list = (List<?>) v;
        length(d, list.size());
        if (list.size() > 100) {
          bad(d, "At most 100 list items allowed");
        }
        for (Object item : list) {
          if (!(item instanceof String) || ((String) item).length() > 10000) {
            bad(d, "List items must be strings of at most 10000 characters");
          }
        }
        return List.copyOf(list);
      }
    }
    return v;
  }

  private void length(PromptVariableDefinition d, int n) {
    if (n > 10000 || (d.minLength() != null && n < d.minLength()) || (d.maxLength() != null
        && n > d.maxLength())) {
      bad(d, "Length is outside allowed bounds");
    }
  }

  private void bad(PromptVariableDefinition d, String message) {
    throw new PromptException("PROMPT_VARIABLE_INVALID", d.name(), message);
  }
}
