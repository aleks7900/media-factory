package com.mediafactory.skills;

import static com.mediafactory.processing.ProcessingJson.*;

import com.mediafactory.provider.PricingService;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Last admission check before every image-provider attempt, including backend retries/fallback.
 */
@Service
public class SkillBudgetGuard {

  final JdbcClient db;
  final TransactionTemplate tx;
  final PricingService pricing;

  public SkillBudgetGuard(JdbcClient db, TransactionTemplate tx, PricingService pricing) {
    this.db = db;
    this.tx = tx;
    this.pricing = pricing;
  }

  public boolean reserve(UUID generation, UUID job, String provider, String model) {
    return Boolean.TRUE.equals(
        tx.execute(
            t -> {
              var rows =
                  db.sql(
                          "select e.* from skill_executions e join generations g on"
                              + " g.skill_execution_id=e.id where g.id=? for update of e")
                      .param(generation)
                      .query()
                      .listOfRows();
              if (rows.isEmpty()) {
                return true;
              }
              var e = SkillExecutionService.json(rows.getFirst());
              if (!e.get("status").equals("RUNNING")) {
                return false;
              }
              var plan = map(e.get("plan"));
              String reason = null;
              if (!Objects.equals(plan.get("provider"), provider)
                  || !Objects.equals(plan.get("model"), model)) {
                reason = "Provider or model differs from frozen skill plan";
              }
              if (db.sql("select count(*) from skill_budget_reservations where job_id=?")
                  .param(job)
                  .query(Long.class)
                  .single()
                  > 0) {
                reason = "Interrupted attempt reservation requires reconciliation";
              }
              var quote = pricing.quote(provider, model, Map.of());
              var costs =
                  db.sql(
                          "select coalesce(sum(coalesce(c.actual_cost,c.estimated_cost)),0)"
                              + " spent,count(*) filter(where"
                              + " coalesce(c.actual_cost,c.estimated_cost) is null or"
                              + " c.currency<>?) unknown from generation_costs c join generations g"
                              + " on g.id=c.generation_id where g.skill_execution_id=?")
                      .params(e.get("currency"), e.get("id"))
                      .query()
                      .singleRow();
              BigDecimal reserved =
                  db.sql(
                          "select coalesce(sum(amount),0) from skill_budget_reservations where"
                              + " execution_id=?")
                      .param(e.get("id"))
                      .query(BigDecimal.class)
                      .single();
              if (quote.estimatedCost() == null
                  || !quote.currency().equals(e.get("currency").toString().trim())
                  || ((Number) costs.get("unknown")).longValue() > 0
                  || e.get("max_budget") == null) {
                reason = "Authoritative generation price or charge unavailable";
              } else if (((BigDecimal) costs.get("spent"))
                  .add(reserved)
                  .add(quote.estimatedCost())
                  .compareTo((BigDecimal) e.get("max_budget"))
                  > 0) {
                reason = "Generation admission budget exceeded";
              }
              if (reason != null) {
                db.sql(
                        "update skill_executions set"
                            + " status='WAITING_FOR_APPROVAL',error_code='BUDGET_EXCEEDED',error_message=?"
                            + " where id=?")
                    .params(reason, e.get("id"))
                    .update();
                db.sql(
                        "insert into skill_execution_events(execution_id,action,actor,reason)"
                            + " values(?,'BUDGET_PAUSE','skill-budget-guard',?)")
                    .params(e.get("id"), reason)
                    .update();
                return false;
              }
              db.sql(
                      "insert into skill_budget_reservations(job_id,execution_id,amount)"
                          + " values(?,?,?)")
                  .params(job, e.get("id"), quote.estimatedCost())
                  .update();
              return true;
            }));
  }

  public void release(UUID job) {
    db.sql("delete from skill_budget_reservations where job_id=?").param(job).update();
  }
}
