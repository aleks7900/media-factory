package com.mediafactory.feedback;

import static com.mediafactory.processing.ProcessingJson.*;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class FeedbackStore {
  public final JdbcClient db;
  private static final Set<String> TABLES =
      Set.of(
          "visual_attribute_definitions",
          "visual_feature_extractions",
          "feedback_analysis_runs",
          "feedback_findings",
          "experiment_hypotheses",
          "feedback_experiment_plans",
          "feedback_experiment_results",
          "feedback_learnings",
          "feedback_jobs");

  public FeedbackStore(JdbcClient db) {
    this.db = db;
  }

  public Map<String, Object> one(String table, UUID id) {
    if (!TABLES.contains(table)) throw new IllegalArgumentException("Unknown feedback resource");
    return db
        .sql(
            "select * from "
                + table
                + " where "
                + (table.equals("feedback_experiment_plans") ? "experiment_id" : "id")
                + "=?")
        .param(id)
        .query()
        .listOfRows()
        .stream()
        .findFirst()
        .map(FeedbackStore::json)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Feedback record not found"));
  }

  public List<Map<String, Object>> list(String table, int page) {
    if (!TABLES.contains(table) || page < 0 || page > 100000)
      throw new IllegalArgumentException("Invalid list");
    String date = table.equals("feedback_analysis_runs") ? "started_at" : "created_at";
    return db
        .sql("select * from " + table + " order by " + date + " desc limit 50 offset ?")
        .param(page * 50)
        .query()
        .listOfRows()
        .stream()
        .map(FeedbackStore::json)
        .toList();
  }

  public static Map<String, Object> json(Map<String, Object> row) {
    var result = new LinkedHashMap<>(row);
    result.replaceAll(
        (k, v) ->
            v != null && v.getClass().getSimpleName().equals("PGobject")
                ? com.mediafactory.prompt.PromptCatalog.JSON.readValue(v.toString(), Object.class)
                : v);
    return result;
  }

  public void audit(String type, UUID id, String action, String reason, String user) {
    required(reason, "reason");
    required(user, "user");
    db.sql(
            "insert into feedback_audit(entity_type,entity_id,action,reason,created_by)"
                + " values(?,?,?,?,?)")
        .params(type, id, action, reason, user)
        .update();
  }

  public static void required(String s, String label) {
    if (s == null || s.isBlank() || s.length() > 2000)
      throw new IllegalArgumentException("Invalid " + label);
  }

  public static UUID uuid(Map<String, Object> m, String key) {
    return UUID.fromString(Objects.toString(m.get(key), ""));
  }

  public static void check(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }
}
