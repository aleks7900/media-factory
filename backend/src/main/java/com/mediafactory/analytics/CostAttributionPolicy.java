package com.mediafactory.analytics;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CostAttributionPolicy {
  private final JdbcClient db;
  private final TransactionTemplate tx;

  public CostAttributionPolicy(JdbcClient db, TransactionTemplate tx) {
    this.db = db;
    this.tx = tx;
  }

  public record Allocation(
      UUID costId, String policy, Map<UUID, BigDecimal> weights, String reason, String createdBy) {}

  public Object allocate(Allocation r) {
    AnalyticsIngestionService.required(r.reason(), "reason", 2000);
    AnalyticsIngestionService.required(r.createdBy(), "createdBy", 100);
    if (r.weights() == null
        || r.weights().isEmpty()
        || r.weights().size() > 1000
        || !Set.of("DIRECT", "EQUAL_SPLIT", "WEIGHTED").contains(r.policy()))
      throw new IllegalArgumentException("Invalid allocation policy");
    if (r.policy().equals("DIRECT") && r.weights().size() != 1)
      throw new IllegalArgumentException("DIRECT requires one target");
    return tx.execute(
        status -> {
          var cost =
              db.sql("select * from generation_costs where id=? for update")
                  .param(r.costId())
                  .query()
                  .singleRow();
          if (!Objects.equals(cost.get("outcome"), "SUCCEEDED")
              && !Objects.equals(cost.get("outcome"), "FAILED"))
            throw new IllegalArgumentException("Only finalized operation costs can be allocated");
          BigDecimal amount =
              (BigDecimal)
                  (cost.get("actual_cost") != null
                      ? cost.get("actual_cost")
                      : cost.get("estimated_cost"));
          if (amount == null)
            throw new IllegalArgumentException("Cannot allocate an unpriced operation");
          var ids =
              r.weights().keySet().stream().sorted(Comparator.comparing(UUID::toString)).toList();
          var weights =
              ids.stream()
                  .map(id -> r.policy().equals("WEIGHTED") ? r.weights().get(id) : BigDecimal.ONE)
                  .toList();
          var amounts = AssetEconomics.allocate(amount, weights, 12);
          var existing =
              db.sql("select * from analytics_cost_allocations where cost_id=? order by asset_id")
                  .param(r.costId())
                  .query()
                  .listOfRows();
          if (!existing.isEmpty()) {
            if (existing.size() != ids.size())
              throw new IllegalArgumentException("Allocation already finalized");
            for (int i = 0; i < ids.size(); i++)
              if (!existing.get(i).get("asset_id").equals(ids.get(i))
                  || ((BigDecimal) existing.get(i).get("amount")).compareTo(amounts.get(i)) != 0
                  || !existing.get(i).get("policy").equals(r.policy()))
                throw new IllegalArgumentException("Allocation already finalized");
            return existing;
          }
          for (int i = 0; i < ids.size(); i++)
            db.sql(
                    "insert into"
                        + " analytics_cost_allocations(cost_id,asset_id,amount,weight,policy,reason,created_by)"
                        + " values(?,?,?,?,?,?,?)")
                .params(
                    r.costId(),
                    ids.get(i),
                    amounts.get(i),
                    weights.get(i),
                    r.policy(),
                    r.reason(),
                    r.createdBy())
                .update();
          return db.sql(
                  "select * from analytics_cost_allocations where cost_id=? order by asset_id")
              .param(r.costId())
              .query()
              .listOfRows();
        });
  }
}
