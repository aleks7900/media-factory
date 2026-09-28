package com.mediafactory.stock;

import static com.mediafactory.processing.ProcessingJson.*;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class StockMetadataValidator {
  public record Issue(String field, String code, String severity, String value) {}

  public record Result(String status, List<Issue> issues) {
    public boolean valid() {
      return !status.equals("FAIL");
    }
  }

  public Result validate(
      Map<String, Object> data, Map<String, Object> p, Map<String, Object> observations) {
    var issues = new ArrayList<Issue>();
    length(
        issues,
        data,
        "title",
        integer(p, "minimumTitleLength", 5),
        integer(p, "maximumTitleLength", 180));
    length(
        issues,
        data,
        "description",
        integer(p, "minimumDescriptionLength", 10),
        integer(p, "maximumDescriptionLength", 1000));
    var keywords = (List<Map<String, Object>>) data.getOrDefault("keywords", List.of());
    if (keywords.size() < integer(p, "minimumKeywords", 10)
        || keywords.size() > integer(p, "maximumKeywords", 49))
      issues.add(new Issue("keywords", "COUNT", "FAIL", "" + keywords.size()));
    var seen = new HashSet<String>();
    String observed = StockKeywords.normalize(write(observations));
    for (var k : keywords) {
      String value = Objects.toString(k.get("value"), "");
      if (!seen.add(StockKeywords.normalize(value)))
        issues.add(new Issue("keywords", "DUPLICATE", "FAIL", value));
      if (Arrays.stream(value.split(" ")).noneMatch(observed::contains))
        issues.add(new Issue("keywords", "LOW_RELEVANCE_KEYWORD", "WARNING", value));
    }
    var categories = (List<?>) data.getOrDefault("categories", List.of());
    if (categories.isEmpty() || !((List<?>) p.get("categories")).containsAll(categories))
      issues.add(new Issue("categories", "INVALID_CATEGORY", "FAIL", categories.toString()));
    if (!Boolean.TRUE.equals(data.get("aiGenerated")))
      issues.add(new Issue("aiGenerated", "AI_DISCLOSURE_REQUIRED", "FAIL", ""));
    if (!Set.of("COMMERCIAL", "EDITORIAL", "UNDETERMINED").contains(data.get("contentType")))
      issues.add(new Issue("contentType", "CLASSIFICATION_REQUIRED", "FAIL", ""));
    String text = write(data).toLowerCase(Locale.ROOT);
    for (Object term : (List<?>) p.getOrDefault("forbiddenTerms", List.of()))
      if (text.contains(term.toString().toLowerCase(Locale.ROOT)))
        issues.add(
            new Issue("metadata", "FORBIDDEN_OR_UNSUPPORTED_CLAIM", "FAIL", term.toString()));
    var risks = (List<?>) data.getOrDefault("riskFlags", List.of());
    if (!risks.isEmpty())
      issues.add(new Issue("riskFlags", "IP_REVIEW_SIGNAL", "WARNING", risks.toString()));
    if (!risks.isEmpty() && "COMMERCIAL".equals(data.get("contentType")))
      issues.add(new Issue("contentType", "RISK_REQUIRES_REVIEW", "FAIL", risks.toString()));
    if (Boolean.TRUE.equals(observations.get("mock")))
      issues.add(
          new Issue(
              "metadata",
              "MOCK_OBSERVATIONS_REQUIRE_HUMAN_REVIEW",
              "WARNING",
              "Local deterministic metadata; not a semantic visual assessment"));
    return new Result(
        issues.stream().anyMatch(i -> i.severity().equals("FAIL"))
            ? "FAIL"
            : issues.isEmpty() ? "PASS" : "WARNING",
        issues);
  }

  private void length(List<Issue> issues, Map<String, Object> d, String field, int min, int max) {
    String s = Objects.toString(d.get(field), "");
    if (s.length() < min || s.length() > max)
      issues.add(new Issue(field, "LENGTH", "FAIL", min + ".." + max));
    if (s.stripLeading().matches("^[=+@\\-].*"))
      issues.add(new Issue(field, "SPREADSHEET_FORMULA", "FAIL", "Unsafe leading character"));
  }
}
