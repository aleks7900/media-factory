package com.mediafactory.skills;

import static com.mediafactory.processing.ProcessingJson.*;
import static com.mediafactory.skills.SkillPlanService.*;

import com.mediafactory.processing.ProcessingPlanner;
import com.mediafactory.quality.ReviewActor;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SkillExecutionService {
  final JdbcClient db;
  final SkillPlanService plans;
  final ReviewActor actor;
  final TransactionTemplate tx;
  final SkillDomainOperations operations;
  final MeterRegistry metrics;

  public SkillExecutionService(
      JdbcClient db,
      SkillPlanService plans,
      ReviewActor actor,
      TransactionTemplate tx,
      SkillDomainOperations operations,
      MeterRegistry metrics) {
    this.db = db;
    this.plans = plans;
    this.actor = actor;
    this.tx = tx;
    this.operations = operations;
    this.metrics = metrics;
  }

  public record Request(
      String skillName,
      String operationId,
      UUID projectId,
      UUID collectionId,
      Map<String, Object> input) {}

  public static Map<String, Object> json(Map<String, Object> row) {
    var copy = new LinkedHashMap<>(row);
    copy.replaceAll(
        (k, v) ->
            v != null && v.getClass().getSimpleName().equals("PGobject")
                ? tools.jackson.databind.json.JsonMapper.builder()
                    .build()
                    .readValue(v.toString(), Object.class)
                : v);
    return copy;
  }

  public Map<String, Object> one(UUID id) {
    return json(
        db.sql("select * from skill_executions where id=?").param(id).query().listOfRows().stream()
            .findFirst()
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Skill execution not found")));
  }

  public Object list(UUID project, int page) {
    check(page >= 0 && page <= 100000, "Invalid page");
    return db.sql(
            "select"
                + " id,skill_name,skill_version,operation_id,project_id,collection_id,status,created_at,completed_at,error_code"
                + " from skill_executions where project_id=? order by created_at desc,id limit 50"
                + " offset ?")
        .params(project, page * 50)
        .query()
        .listOfRows();
  }

  @Transactional
  public Object plan(Request r) {
    check(
        r.operationId() != null && r.operationId().matches("[A-Za-z0-9:_-]{1,160}"),
        "Safe operationId required");
    check(r.projectId() != null && r.input() != null, "Project and input required");
    String hash =
        ProcessingPlanner.hash(
            Arrays.asList(r.skillName(), r.projectId(), r.collectionId(), r.input()));
    db.sql("select pg_advisory_xact_lock(hashtext(?))")
        .param("skill:" + r.operationId())
        .query()
        .singleRow();
    var previous =
        db.sql("select id,input_hash from skill_executions where operation_id=?")
            .param(r.operationId())
            .query()
            .listOfRows();
    if (!previous.isEmpty()) {
      if (!hash.equals(previous.getFirst().get("input_hash")))
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Operation ID conflicts with original input");
      return detail((UUID) previous.getFirst().get("id"));
    }
    var plan = plans.plan(r.skillName(), r.projectId(), r.collectionId(), r.input());
    UUID id = UUID.randomUUID();
    db.sql(
            "insert into"
                + " skill_executions(id,skill_name,skill_version,operation_id,project_id,collection_id,requested_by,input_summary,input_hash,plan,max_budget,currency)"
                + " values(?,?,1,?,?,?,?,?::jsonb,?,?::jsonb,?,?)")
        .params(
            id,
            r.skillName(),
            r.operationId(),
            r.projectId(),
            r.collectionId(),
            actor.current(),
            write(r.input()),
            hash,
            write(plan),
            r.input().containsKey("maxBudget")
                ? new BigDecimal(r.input().get("maxBudget").toString())
                : BigDecimal.ZERO,
            plan.getOrDefault("currency", "USD"))
        .update();
    event(id, "PLANNED", "Dry-run plan recorded; no production work started");
    metrics.counter("media_factory_skill_executions_total", "skill", r.skillName()).increment();
    return detail(id);
  }

  @Transactional
  public Object action(UUID id, String action, String reason) {
    check(
        reason != null && !reason.isBlank() && reason.length() <= 2000,
        "An attribution reason is required");
    db.sql("select id from skill_executions where id=? for update").param(id).query().singleRow();
    var e = one(id);
    String status = e.get("status").toString();
    var plan = map(e.get("plan"));
    switch (action) {
      case "approve" -> {
        check(
            Set.of("PLANNED", "WAITING_FOR_APPROVAL").contains(status),
            "Approval requires a planned or waiting execution");
        if (e.get("approved_at") == null)
          db.sql(
                  "update skill_executions set approved_by=?,approval_reason=?,approved_at=now()"
                      + " where id=?")
              .params(actor.current(), reason, id)
              .update();
      }
      case "start", "resume" -> {
        check(plans.enabled, "Skills are disabled");
        plans.checkAuxiliaryAdmission(e.get("skill_name").toString(), map(e.get("input_summary")));
        if (status.equals("RUNNING") || status.equals("COMPLETED")) return detail(id);
        check(
            Set.of("PLANNED", "WAITING_FOR_APPROVAL", "PARTIALLY_COMPLETED", "FAILED")
                .contains(status),
            "Execution cannot resume from this state; inspect existing domain jobs");
        if (Boolean.TRUE.equals(plan.get("paid")))
          check(
              plan.get("estimatedGenerationCost") != null,
              "Cost estimate unavailable; paid generation is blocked until authoritative pricing is"
                  + " available");
        if (Boolean.TRUE.equals(plan.get("approvalRequired")) && e.get("approved_at") == null) {
          db.sql(
                  "update skill_executions set"
                      + " status='WAITING_FOR_APPROVAL',error_code='APPROVAL_REQUIRED' where id=?")
              .param(id)
              .update();
          event(id, "APPROVAL_REQUIRED", reason);
          return detail(id);
        }
        db.sql(
                "update skill_executions set"
                    + " status='RUNNING',started_at=coalesce(started_at,now()),completed_at=null,error_code=null,error_message=null,next_poll_at=now(),revision=revision+1"
                    + " where id=?")
            .param(id)
            .update();
        db.sql(
                "update skill_execution_items set"
                    + " status='PLANNED',error_code=null,error_message=null where execution_id=?"
                    + " and status='WAITING' and resource_id is null")
            .param(id)
            .update();
        db.sql(
                "update skill_execution_items set status='RUNNING',completed_at=null where"
                    + " execution_id=? and resource_id is not null and status in"
                    + " ('FAILED','REJECTED','WAITING')")
            .param(id)
            .update();
      }
      case "cancel" -> {
        check(!Set.of("COMPLETED", "FAILED").contains(status), "Execution is terminal");
        db.sql(
                "update skill_executions set"
                    + " status='CANCELLED',completed_at=now(),revision=revision+1 where id=?")
            .param(id)
            .update();
      }
      default -> throw new IllegalArgumentException("Unknown action");
    }
    event(id, action.toUpperCase(Locale.ROOT), reason);
    return detail(id);
  }

  void event(UUID id, String action, String reason) {
    db.sql("insert into skill_execution_events(execution_id,action,actor,reason) values(?,?,?,?)")
        .params(id, action, actor.current(), reason)
        .update();
  }

  public Map<String, Object> detail(UUID id) {
    var e = one(id);
    e.put(
        "items",
        db
            .sql(
                "select * from skill_execution_items where execution_id=? order by"
                    + " created_at,item_key")
            .param(id)
            .query()
            .listOfRows()
            .stream()
            .map(SkillExecutionService::json)
            .toList());
    e.put(
        "events",
        db.sql(
                "select action,actor,reason,created_at from skill_execution_events where"
                    + " execution_id=? order by id desc limit 100")
            .param(id)
            .query()
            .listOfRows());
    e.put(
        "costs",
        db.sql(
                """
                select currency,sum(estimated_cost) estimated_cost,sum(actual_cost) actual_cost,
                count(*) filter(where actual_cost is null) actual_unavailable
                from generation_costs c where c.created_at >= (select created_at from skill_executions where id=?) and (
                  c.generation_id in (select id from generations where skill_execution_id=?)
                  or c.review_id in (select resource_id from skill_execution_items where execution_id=? and resource_type='quality_reviews')
                  or c.generation_id in (select generation_id from stock_productions where skill_execution_id=?
                  union select generation_id from wallpaper_productions where skill_execution_id=?)) group by currency
                """)
            .params(id, id, id, id, id)
            .query()
            .listOfRows());
    e.put(
        "costAttribution",
        "Ledger costs since planning for linked generations/reviews; shared concurrent asset work"
            + " may overlap. Not asset lifetime cost.");
    return e;
  }

  public void runOne() {
    if (!plans.enabled) return;
    UUID selected =
        tx.execute(
            s -> {
              var rows =
                  db.sql(
                          "select id from skill_executions where status='RUNNING' and"
                              + " next_poll_at<=now() order by next_poll_at for update skip locked"
                              + " limit 1")
                      .query(UUID.class)
                      .list();
              if (rows.isEmpty()) return null;
              UUID id = rows.getFirst();
              db.sql(
                      "update skill_executions set next_poll_at=now()+interval '30 seconds' where"
                          + " id=?")
                  .param(id)
                  .update();
              return id;
            });
    if (selected == null) return;
    long started = System.nanoTime();
    try {
      tx.executeWithoutResult(
          s -> {
            db.sql("select id from skill_executions where id=? for update")
                .param(selected)
                .query()
                .singleRow();
            var e = one(selected);
            if (!e.get("status").equals("RUNNING")) return;
            operations.advance(e);
          });
    } catch (Exception error) {
      String code = classify(error);
      boolean wait =
          Set.of("SIMILARITY_BLOCKED", "BUDGET_EXCEEDED", "APPROVAL_REQUIRED").contains(code);
      tx.executeWithoutResult(
          s -> {
            db.sql(
                    "update skill_executions set"
                        + " status=?,error_code=?,error_message=?,completed_at=case when ? then"
                        + " null else now() end where id=? and status='RUNNING'")
                .params(
                    wait ? "WAITING_FOR_APPROVAL" : "PARTIALLY_COMPLETED",
                    code,
                    "Inspect domain state and execution items before resuming",
                    wait,
                    selected)
                .update();
            event(
                selected,
                code,
                "Workflow paused; successful items and durable domain jobs are retained");
          });
      metrics
          .counter(
              "media_factory_skill_failures_total",
              "skill",
              one(selected).get("skill_name").toString(),
              "status",
              code)
          .increment();
    } finally {
      var e = one(selected);
      metrics
          .timer(
              "media_factory_skill_duration_seconds",
              "skill",
              e.get("skill_name").toString(),
              "status",
              e.get("status").toString())
          .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
      org.slf4j.LoggerFactory.getLogger(getClass())
          .info(
              "skill_execution skillName={} skillVersion={} operationId={} projectId={}"
                  + " collectionId={} status={}",
              e.get("skill_name"),
              e.get("skill_version"),
              e.get("operation_id"),
              e.get("project_id"),
              e.get("collection_id"),
              e.get("status"));
    }
  }

  public static String classify(Exception e) {
    String m = Objects.toString(e.getMessage(), "").toUpperCase(Locale.ROOT);
    if (m.contains("DIVERSITY") || m.contains("SIMILARITY_BLOCKED")) return "SIMILARITY_BLOCKED";
    if (m.contains("BUDGET") || m.contains("PRICE")) return "BUDGET_EXCEEDED";
    if (m.contains("APPROV")) return "APPROVAL_REQUIRED";
    if (e instanceof IllegalArgumentException) return "VALIDATION_ERROR";
    return "UNKNOWN";
  }
}
