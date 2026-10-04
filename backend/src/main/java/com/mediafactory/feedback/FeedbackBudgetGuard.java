package com.mediafactory.feedback;

import com.mediafactory.provider.PricingService;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FeedbackBudgetGuard {

  private final JdbcClient db;
  private final TransactionTemplate tx;
  private final PricingService pricing;

  @org.springframework.beans.factory.annotation.Value("${feedback.safety.minimum-resolved:10}")
  private int safetyMinimum = 10;

  @org.springframework.beans.factory.annotation.Value(
      "${feedback.safety.maximum-failure-ratio:0.5}")
  private double failureRatio = .5;

  public FeedbackBudgetGuard(JdbcClient db, TransactionTemplate tx, PricingService pricing) {
    this.db = db;
    this.tx = tx;
    this.pricing = pricing;
  }

  public boolean reserve(UUID generation, UUID job, String provider, String model) {
    return Boolean.TRUE.equals(
        tx.execute(
            status -> {
              var rows =
                  db.sql(
                          "select p.*,e.status from feedback_experiment_plans p join"
                              + " prompt_experiments e on e.id=p.experiment_id join generations g"
                              + " on g.experiment_id=e.id where g.id=? for update of p")
                      .param(generation)
                      .query()
                      .listOfRows();
              if (rows.isEmpty()) {
                return true;
              }
              var plan = rows.getFirst();
              if (plan.get("approved_at") == null || !plan.get("status").equals("RUNNING")) {
                return false;
              }
              if (db.sql("select count(*) from feedback_budget_reservations where job_id=?")
                  .param(job)
                  .query(Long.class)
                  .single()
                  > 0) {
                pause(plan, "Unreconciled reservation after interrupted attempt");
                return false;
              }
              var definition =
                  com.mediafactory.processing.ProcessingJson.map(plan.get("definition"));
              if (!provider.equals(definition.get("provider"))
                  || !model.equals(definition.get("model"))) {
                pause(plan, "Provider/model differs from registered experiment protocol");
                return false;
              }
              var quote = pricing.quote(provider, model, Map.of());
              var amounts =
                  db.sql(
                          "select coalesce(sum(coalesce(c.actual_cost,c.estimated_cost)),0)"
                              + " total,count(*) filter(where"
                              + " coalesce(c.actual_cost,c.estimated_cost) is null or"
                              + " c.currency<>?) unknown from generation_costs c join generations g"
                              + " on g.id=c.generation_id where g.experiment_id=?")
                      .params(plan.get("currency"), plan.get("experiment_id"))
                      .query()
                      .singleRow();
              BigDecimal reserved =
                  db.sql(
                          "select coalesce(sum(amount),0) from feedback_budget_reservations where"
                              + " experiment_id=?")
                      .param(plan.get("experiment_id"))
                      .query(BigDecimal.class)
                      .single();
              BigDecimal spent = (BigDecimal) amounts.get("total"),
                  max = (BigDecimal) plan.get("max_budget"),
                  estimate = quote.estimatedCost();
              if (estimate == null
                  || !quote.currency().equals(plan.get("currency").toString().trim())
                  || ((Number) amounts.get("unknown")).longValue() > 0
                  || spent.add(reserved).add(estimate).compareTo(max) > 0
                  || (!provider.equals("mock") && spent.add(reserved).compareTo(max) >= 0)) {
                pause(plan, "Budget reached or operation price/currency is unknown");
                return false;
              }
              var failures =
                  db.sql(
                          "select count(*) n,count(*) filter(where status in ('FAILED','REJECTED'))"
                              + " failures from generations where experiment_id=? and status in"
                              + " ('APPROVED','PUBLISHED','FAILED','REJECTED')")
                      .param(plan.get("experiment_id"))
                      .query()
                      .singleRow();
              long n = ((Number) failures.get("n")).longValue(),
                  failed = ((Number) failures.get("failures")).longValue();
              if (n >= safetyMinimum && (double) failed / n > failureRatio) {
                pause(
                    plan,
                    "Safety stop: resolved generation failure/rejection ratio exceeds configured"
                        + " policy");
                return false;
              }
              db.sql(
                      "insert into feedback_budget_reservations(job_id,experiment_id,amount)"
                          + " values(?,?,?)")
                  .params(job, plan.get("experiment_id"), estimate)
                  .update();
              return true;
            }));
  }

  private void pause(Map<String, Object> plan, String reason) {
    db.sql(
            "update prompt_experiments set"
                + " status='PAUSED',feedback_stage='PAUSED',revision=revision+1 where id=?")
        .param(plan.get("experiment_id"))
        .update();
    db.sql(
            "insert into feedback_audit(entity_type,entity_id,action,reason,created_by)"
                + " values('EXPERIMENT',?,'SAFETY_PAUSE',?,'feedback-budget-guard')")
        .params(plan.get("experiment_id"), reason)
        .update();
  }

  public void release(UUID job) {
    db.sql("delete from feedback_budget_reservations where job_id=?").param(job).update();
  }
}
