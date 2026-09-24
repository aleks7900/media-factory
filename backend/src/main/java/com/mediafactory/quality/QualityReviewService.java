package com.mediafactory.quality;

import com.mediafactory.quality.QualityModels.Decision;
import com.mediafactory.quality.QualityModels.Dimension;
import com.mediafactory.quality.QualityModels.Finding;
import com.mediafactory.quality.QualityModels.Source;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class QualityReviewService {

  public static final String PROMPT_VERSION = "visual-quality/v1";
  public static final Set<String> REASONS = Set.of("AI_FALSE_POSITIVE", "AI_FALSE_NEGATIVE",
      "INTENTIONAL_STYLE", "PROMPT_CONTEXT", "TECHNICAL_EXCEPTION", "MANUAL_QUALITY_JUDGMENT",
      "OTHER");
  static final JsonMapper JSON = JsonMapper.builder().build();
  private final JdbcClient db;
  private final QaConfiguration config;
  private final MeterRegistry metrics;
  public final com.mediafactory.similarity.SimilarityService similarity;

  public QualityReviewService(JdbcClient db, QaConfiguration config, MeterRegistry metrics, com.mediafactory.similarity.SimilarityService similarity) {
    this.db = db;
    this.config = config;
    this.metrics = metrics;
    this.similarity = similarity;
  }

  public static void event(String name, UUID review, Map<String, ?> details) {
    org.slf4j.LoggerFactory.getLogger(QualityReviewService.class).atInfo()
        .addKeyValue("event", name).addKeyValue("review_id", review).addKeyValue("details", details)
        .log("Quality assurance event");
  }
  public com.mediafactory.similarity.SimilarityService similarity() { return similarity; }

  public static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  public Map<String, Object> review(UUID id) {
    return db.sql("select * from quality_reviews where id=?").param(id).query().listOfRows()
        .stream().findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Review not found"));
  }

  @Transactional
  public Map<String, Object> enqueue(UUID assetId, boolean rerun, String policyId,
      String scenario) {
    var a = db.sql(
            "select a.*,g.status,g.width as expected_width,g.height as expected_height,g.final_provider,g.model,g.result_metadata,c.collection_id,col.qa_policy,col.name as collection_name from assets a join generations g on g.id=a.generation_id join concepts c on c.id=g.concept_id join collections col on col.id=c.collection_id where a.id=? for update of a,g")
        .param(assetId).query().listOfRows().stream().findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
      if ("PUBLISHED".equals(a.get("status"))) {
          throw conflict("Published assets cannot be re-reviewed; regenerate a new asset");
      }
    var snapshots = db.sql(
            "select * from rendered_prompt_snapshots where generation_id=? order by case when provider=? then 0 else 1 end,rendered_at")
        .params(a.get("generation_id"), Objects.toString(a.get("final_provider"), "mock")).query()
        .listOfRows();
    var context = new LinkedHashMap<String, Object>();
    context.put("assetId", assetId);
    context.put("generationId", a.get("generation_id"));
    context.put("collectionId", a.get("collection_id"));
    context.put("collectionName", a.get("collection_name"));
    context.put("generationProvider", a.get("final_provider"));
    context.put("generationModel", a.get("model"));
    context.put("expectedWidth", a.get("expected_width"));
    context.put("expectedHeight", a.get("expected_height"));
    context.put("mimeType", a.get("media_type"));
    context.put("sha256", a.get("sha256"));
    String pipeline = "default";
    if (!snapshots.isEmpty()) {
      var snapshot = new LinkedHashMap<>(snapshots.getFirst());
        for (String field : List.of("variables", "presets", "composition", "warnings")) {
            if (snapshot.get(field) != null) {
                snapshot.put(field, JSON.readTree(snapshot.get(field).toString()));
            }
        }
      context.put("promptSnapshot", snapshot);
        if (snapshot.get("composition") != null) {
            pipeline = JSON.valueToTree(snapshot.get("composition")).path("pipeline")
                    .asText("default");
        }
    } else {
      context.put("legacyContextUnavailable", true);
    }
    context.put("pipeline", pipeline);
    String selectedPolicy = policyId != null ? policyId
        : a.get("qa_policy") != null ? a.get("qa_policy").toString() : config.policyFor(pipeline);
    var policy = config.policy(selectedPolicy);
    String provider = config.provider(), model = config.model(provider);
    String identity =
        assetId + ":" + policy.id() + ":" + policy.version() + ":" + provider + ":" + model + ":"
            + PROMPT_VERSION;
    if (a.get("current_review_id") != null) {
      var current = review((UUID) a.get("current_review_id"));
        if (Set.of("PENDING", "RUNNING").contains(current.get("execution_status"))) {
            return current;
        }
        if (!rerun && identity.equals(current.get("idempotency_key"))) {
            return current;
        }
    }
    String mockScenario = scenario == null ? config.scenario() : scenario;
    MockVisionQualityProvider.Scenario.valueOf(mockScenario);
    UUID id = UUID.randomUUID();
    db.sql(
            "insert into quality_reviews(id,asset_id,generation_id,kind,decision,final_decision,execution_status,policy_id,policy_version,policy_snapshot,context_snapshot,vision_provider,vision_model,qa_prompt_version,idempotency_key) values(?,?,?,'ADVANCED','NEEDS_REVIEW','NEEDS_REVIEW','PENDING',?,?,cast(? as jsonb),cast(? as jsonb),?,?,?,?)")
        .params(id, assetId, a.get("generation_id"), policy.id(), policy.version(),
            JSON.writeValueAsString(policy), JSON.writeValueAsString(context), provider, model,
            PROMPT_VERSION, identity).update();
    var route = config.route().stream().map(p -> Map.of("provider", p, "model", config.model(p)))
        .toList();
    db.sql(
            "insert into qa_jobs(id,review_id,generation_id,max_attempts,route,scenario) values(?,?,?,?,cast(? as jsonb),?)")
        .params(UUID.randomUUID(), id, a.get("generation_id"), config.maxCalls(),
            JSON.writeValueAsString(route), mockScenario).update();
    db.sql("update assets set current_review_id=? where id=?").params(id, assetId).update();
    db.sql("update generations set status='QA_PENDING',updated_at=now() where id=?")
        .param(a.get("generation_id")).update();
    return review(id);
  }

  public void findings(UUID review, List<Finding> findings) {
      for (var f : findings) {
          db.sql(
                          "insert into quality_findings(id,review_id,category,code,severity,confidence,detected,source,evidence,metadata) values(?,?,?,?,?,?,?,?,?,cast(? as jsonb))")
                  .params(UUID.randomUUID(), review, f.category().name(), f.code().name(),
                          f.severity().name(), f.confidence(), f.detected(), f.source().name(),
                          f.evidence(),
                          JSON.writeValueAsString(f.metadata())).update();
      }
  }

  public void dimensions(UUID review, List<Dimension> dimensions) {
      for (var d : dimensions) {
          db.sql(
                          "insert into quality_dimension_results(id,review_id,dimension,score,confidence,applicable,evidence) values(?,?,?,?,?,?,?)")
                  .params(UUID.randomUUID(), review, d.dimension().name(), d.score(),
                          d.confidence(),
                          d.applicable(), d.evidence()).update();
      }
  }

  public Map<String, Object> detail(UUID id) {
    var r = new LinkedHashMap<>(review(id));
      for (String field : List.of("policy_snapshot", "context_snapshot", "rules_triggered")) {
          r.put(field, JSON.readTree(r.get(field).toString()));
      }
    r.put("findings", db.sql(
            "select * from quality_findings where review_id=? order by case severity when 'CRITICAL' then 0 when 'MAJOR' then 1 when 'MINOR' then 2 else 3 end,created_at")
        .param(id).query().listOfRows());
    r.put("dimensions",
        db.sql("select * from quality_dimension_results where review_id=? order by dimension")
            .param(id).query().listOfRows());
    r.put("actions",
        db.sql("select * from human_review_actions where review_id=? order by created_at").param(id)
            .query().listOfRows());
    r.put("attempts",
        db.sql("select * from vision_qa_attempts where review_id=? order by attempt_number")
            .param(id).query().listOfRows());
    r.put("history", db.sql(
            "select id,execution_status,automatic_decision,final_decision,created_at from quality_reviews where asset_id=? order by created_at desc")
        .param(r.get("asset_id")).query().listOfRows());
    r.put("asset",
        db.sql("select * from assets where id=?").param(r.get("asset_id")).query().singleRow());
    r.put("costs", db.sql(
            "select operation,currency,sum(estimated_cost) as estimated_cost,count(*) filter(where estimated_cost is null) as unknown_attempts from generation_costs where generation_id=? group by operation,currency")
        .param(r.get("generation_id")).query().listOfRows());
    return r;
  }

  public Map<String, Object> queue(Map<String, String> filters, int page, int size) {
      if (page < 0 || page > 100000 || size < 1 || size > 100) {
          throw new IllegalArgumentException("Invalid page size");
      }
    StringBuilder where = new StringBuilder(" where a.current_review_id=r.id");
    var args = new ArrayList<Object>();
    Map<String, String> columns = Map.of("decision", "r.final_decision", "executionStatus",
        "r.execution_status", "collection", "c.collection_id::text", "pipeline",
        "r.context_snapshot->>'pipeline'", "provider", "r.vision_provider");
    columns.forEach((key, col) -> {
      String value = filters.get(key);
      if (value != null && !value.isBlank()) {
        where.append(" and ").append(col).append("=?");
        args.add(value);
      }
    });
    if (filters.containsKey("finding") && !filters.get("finding").isBlank()) {
      where.append(
          " and exists(select 1 from quality_findings f where f.review_id=r.id and f.detected and f.code=?)");
      args.add(filters.get("finding"));
    }
    if (filters.containsKey("severity") && !filters.get("severity").isBlank()) {
      where.append(
          " and exists(select 1 from quality_findings f where f.review_id=r.id and f.detected and f.severity=?)");
      args.add(filters.get("severity"));
    }
      for (String key : List.of("createdFrom", "createdTo")) {
          if (filters.containsKey(key) && !filters.get(key).isBlank()) {
              where.append(" and r.created_at ").append(key.equals("createdFrom") ? ">=" : "<=")
                      .append(" cast(? as timestamptz)");
              args.add(java.time.Instant.parse(filters.get(key)).toString());
          }
      }
    String from = " from quality_reviews r join assets a on a.id=r.asset_id join generations g on g.id=a.generation_id join concepts c on c.id=g.concept_id join collections col on col.id=c.collection_id";
    long total = db.sql("select count(*)" + from + where).params(args).query(Long.class).single();
    var paged = new ArrayList<>(args);
    paged.add(size);
    paged.add(page * size);
    var rows = db.sql(
            "select r.*,a.width,a.height,a.media_type,col.name as collection_name,c.collection_id,g.final_provider as generation_provider,(select max(case f.severity when 'CRITICAL' then 4 when 'MAJOR' then 3 when 'MINOR' then 2 else 1 end) from quality_findings f where f.review_id=r.id and f.detected) as highest_severity,(select score from quality_dimension_results d where d.review_id=r.id and d.dimension='PROMPT_COMPLIANCE') as prompt_compliance"
                + from + where + " order by r.created_at desc,r.id limit ? offset ?").params(paged)
        .query().listOfRows();
    return Map.of("items", rows, "total", total, "page", page, "size", size);
  }

  @Transactional
  public Map<String, Object> decide(UUID id, Decision decision, HumanCommand command,
      String actor) {
      if (decision == Decision.NEEDS_REVIEW) {
          throw new IllegalArgumentException("Choose approve or reject");
      }
    var r = lockCurrent(id, command.revision());
    boolean override =
        r.get("automatic_decision") != null && !r.get("automatic_decision").equals("NEEDS_REVIEW")
            && !r.get("automatic_decision").equals(decision.name());
    boolean changing =
        r.get("reviewed_at") != null && !Objects.equals(r.get("final_decision"), decision.name());
      if ((override || changing) && (command.reasonCode() == null || !REASONS.contains(
              command.reasonCode()))) {
          throw new IllegalArgumentException("An override reason is required");
      }
      if (command.reasonCode() != null && !command.reasonCode().isBlank() && !REASONS.contains(
              command.reasonCode())) {
          throw new IllegalArgumentException("Unknown override reason");
      }
    String text = Objects.toString(command.reasonText(), "");
      if (text.length() > 4000 || "OTHER".equals(command.reasonCode()) && text.isBlank()) {
          throw new IllegalArgumentException("OTHER requires a reason, up to 4000 characters");
      }
    String action =
        override ? (decision == Decision.APPROVED ? "OVERRIDE_APPROVAL" : "OVERRIDE_REJECTION")
            : (decision == Decision.APPROVED ? "APPROVE" : "REJECT");
    audit(id, action, Objects.toString(r.get("final_decision"), null), decision.name(),
        command.reasonCode(), text, actor, Map.of());
    db.sql(
            "update quality_reviews set decision=?,final_decision=?,human_override=?,reviewed_at=now(),revision=revision+1 where id=?")
        .params(decision.name(), decision.name(), override, id).update();
    db.sql("update generations set status=?,updated_at=now() where id=?")
        .params(decision.name(), r.get("generation_id")).update();
      if (override) {
          metrics.counter("media_factory_qa_human_override_total", "policy",
                          Objects.toString(r.get("policy_id"), "legacy"), "decision", decision.name())
                  .increment();
      }
    event("qa_human_decision", id, Map.of("action", action));
    return detail(id);
  }

  public Map<String, Object> lockCurrent(UUID id, int revision) {
    // Global mutation order: generation -> asset -> review. Worker commits use the same order.
    var initial = review(id);
    var g = db.sql("select status from generations where id=? for update")
        .param(initial.get("generation_id")).query(String.class).single();
    var a = db.sql("select current_review_id from assets where id=? for update")
        .param(initial.get("asset_id")).query().singleRow();
    var r = db.sql("select * from quality_reviews where id=? for update").param(id).query()
        .singleRow();
      if (!Objects.equals(a.get("current_review_id"), id)
              || ((Number) r.get("revision")).intValue() != revision) {
          throw conflict("Review changed; refresh before applying this action");
      }
      if ("PUBLISHED".equals(g)) {
          throw conflict("Published assets cannot be changed");
      }
      if (Set.of("PENDING", "RUNNING").contains(r.get("execution_status"))) {
          throw conflict("Wait for QA execution to finish before human review");
      }
    return r;
  }

  @Transactional
  public Map<String, Object> addFinding(UUID id, int revision, Finding finding, String actor) {
    var r = lockCurrent(id, revision);
      if (finding.source() != Source.HUMAN) {
          throw new IllegalArgumentException("Human finding must identify its source");
      }
    findings(id, List.of(finding));
    audit(id, "ADD_FINDING", r.get("final_decision").toString(), "NEEDS_REVIEW", null,
        finding.evidence(), actor, Map.of("code", finding.code()));
    db.sql(
            "update quality_reviews set final_decision='NEEDS_REVIEW',decision='NEEDS_REVIEW',revision=revision+1 where id=?")
        .param(id).update();
    db.sql("update generations set status='NEEDS_REVIEW',updated_at=now() where id=?")
        .param(r.get("generation_id")).update();
    return detail(id);
  }

  public void audit(UUID review, String action, String previous, String next, String code,
      String text, String actor, Map<String, ?> metadata) {
    db.sql(
            "insert into human_review_actions(id,review_id,action,previous_decision,new_decision,reason_code,reason_text,actor,metadata) values(?,?,?,?,?,?,?,?,cast(? as jsonb))")
        .params(UUID.randomUUID(), review, action, previous, next, code, text, actor,
            JSON.writeValueAsString(metadata)).update();
  }

  public Map<String, Object> dashboard() {
    return db.sql("""
        select count(*) filter(where r.execution_status in ('PENDING','RUNNING')) as pending_qa,
        count(*) filter(where r.final_decision='NEEDS_REVIEW' and r.execution_status in ('COMPLETED','FAILED')) as needs_human_review,
        count(*) filter(where r.final_decision='APPROVED' and coalesce(r.reviewed_at,r.completed_at,r.created_at)>=date_trunc('day',now() at time zone 'UTC') at time zone 'UTC') as approved_today,
        count(*) filter(where r.final_decision='REJECTED' and coalesce(r.reviewed_at,r.completed_at,r.created_at)>=date_trunc('day',now() at time zone 'UTC') at time zone 'UTC') as rejected_today,
        coalesce(avg(case when r.automatic_decision='APPROVED' then 1.0 else 0.0 end) filter(where r.execution_status='COMPLETED' and r.kind='ADVANCED'),0) as auto_approval_rate,
        coalesce(avg(case when r.human_override then 1.0 else 0.0 end) filter(where r.reviewed_at is not null),0) as human_override_rate,
        coalesce(avg(extract(epoch from(r.completed_at-r.started_at))) filter(where r.completed_at is not null),0) as average_qa_seconds,
        (select coalesce(sum(estimated_cost),0) from generation_costs where operation='VISUAL_QA' and currency='USD' and created_at>=date_trunc('day',now() at time zone 'UTC') at time zone 'UTC') as qa_cost_today,
        (select count(*) from generation_costs where operation='VISUAL_QA' and estimated_cost is null) as unknown_qa_costs
        from assets a join quality_reviews r on r.id=a.current_review_id
        """).query().singleRow();
  }

  public record HumanCommand(int revision, String reasonCode, String reasonText) {

  }
}
