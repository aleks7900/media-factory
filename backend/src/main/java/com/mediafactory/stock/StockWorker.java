package com.mediafactory.stock;

import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "media.worker.enabled", havingValue = "true", matchIfMissing = true)
public class StockWorker {
  static final Set<String> ACTIVE =
      Set.of(
          "DRAFT",
          "SOURCE_READY",
          "QA_PENDING",
          "QA_APPROVED",
          "SIMILARITY_CHECK",
          "PROCESSING",
          "TECHNICAL_VALIDATION",
          "METADATA_GENERATION");
  final StockProductionService s;
  final StockExportService exports;
  final StockCollectionService collections;
  final ConcurrentMap<UUID, UUID> active = new ConcurrentHashMap<>();

  public StockWorker(
      StockProductionService s, StockExportService exports, StockCollectionService collections) {
    this.s = s;
    this.exports = exports;
    this.collections = collections;
  }

  @Scheduled(fixedDelay = 2000)
  public void tick() {
    for (UUID id :
        s.db
            .sql(
                "select id from stock_productions where status in"
                    + " ('DRAFT','SOURCE_READY','QA_PENDING','QA_APPROVED','SIMILARITY_CHECK','PROCESSING','TECHNICAL_VALIDATION','METADATA_GENERATION')"
                    + " and (lease_until is null or lease_until<now()) order by created_at limit"
                    + " 10")
            .query(UUID.class)
            .list()) {
      UUID token = UUID.randomUUID();
      if (s.db
              .sql(
                  "update stock_productions set lease_token=?,lease_until=now()+interval '2"
                      + " minutes' where id=? and (lease_until is null or lease_until<now())")
              .params(token, id)
              .update()
          != 1) continue;
      active.put(id, token);
      long start = System.nanoTime();
      try {
        s.advance(id);
      } catch (Exception error) {
        failed(id, token);
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn(
                "stock_stage_failed productionId={} stage={} errorType={}",
                id,
                s.one(id).get("status"),
                error.getClass().getSimpleName());
      } finally {
        active.remove(id, token);
        s.db
            .sql(
                "update stock_productions set lease_token=null,lease_until=null where id=? and"
                    + " lease_token=?")
            .params(id, token)
            .update();
        s.metrics
            .timer("media_factory_stock_stage_duration")
            .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
      }
    }
  }

  void failed(UUID id, UUID token) {
    s.tx.executeWithoutResult(
        t -> {
          s.db
              .sql("select id from stock_productions where id=? for update")
              .param(id)
              .query()
              .singleRow();
          var row = s.one(id);
          String stage = row.get("status").toString();
          if (!token.equals(row.get("lease_token")) || !ACTIVE.contains(stage)) return;
          int attempts = ((Number) row.get("attempt")).intValue() + 1;
          s.db
              .sql("update stock_productions set attempt=? where id=?")
              .params(attempts, id)
              .update();
          if (attempts >= 3)
            s.move(
                row,
                stage.equals("METADATA_GENERATION")
                    ? "METADATA_FAILED"
                    : stage.equals("PROCESSING") ? "PROCESSING_FAILED" : "VALIDATION_FAILED",
                "STAGE_EXECUTION_FAILED:" + stage);
        });
  }

  @Scheduled(fixedDelay = 3000)
  public void exports() {
    s.db
        .sql(
            "update stock_exports set"
                + " status='FAILED',failure_code='LEASE_EXHAUSTED',lease_token=null,lease_until=null"
                + " where status in ('BUILDING','VALIDATING') and lease_until<now() and attempt>=3")
        .update();
    for (UUID id :
        s.db
            .sql(
                "select id from stock_exports where status='PREPARING' or (status in"
                    + " ('VALIDATING','BUILDING') and lease_until<now()) order by created_at limit"
                    + " 1")
            .query(UUID.class)
            .list()) exports.execute(id);
  }

  @Scheduled(fixedDelay = 5000)
  public void collections() {
    for (UUID id :
        s.db
            .sql(
                "select collection_id from stock_collection_plans where status='RUNNING' order by"
                    + " created_at limit 5")
            .query(UUID.class)
            .list())
      try {
        collections.advance(id);
      } catch (Exception e) {
        collections.pause(id, "PLAN_EXECUTION_FAILED");
      }
  }

  @Scheduled(fixedDelay = 15000)
  public void heartbeat() {
    active.forEach(
        (id, token) ->
            s.db
                .sql(
                    "update stock_productions set lease_until=now()+interval '2 minutes' where id=?"
                        + " and lease_token=?")
                .params(id, token)
                .update());
    exports.heartbeat();
  }
}
