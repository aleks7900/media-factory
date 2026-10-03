package com.mediafactory.analytics;

import static com.mediafactory.processing.ProcessingJson.*;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AnalyticsJobs {
  private final JdbcClient db;
  private final TransactionTemplate tx;
  private final AnalyticsImportService imports;
  private final AnalyticsAggregationService aggregation;

  public AnalyticsJobs(
      JdbcClient db,
      TransactionTemplate tx,
      AnalyticsImportService imports,
      AnalyticsAggregationService aggregation) {
    this.db = db;
    this.tx = tx;
    this.imports = imports;
    this.aggregation = aggregation;
  }

  public Object enqueue(String type, Map<String, Object> payload, String key) {
    if (!Set.of("IMPORT", "NORMALIZE", "AGGREGATE", "REBUILD", "RECONCILE").contains(type))
      throw new IllegalArgumentException("Invalid analytics job type");
    AnalyticsIngestionService.required(key, "Idempotency-Key", 200);
    if (write(payload).length() > 16000)
      throw new IllegalArgumentException("Job payload too large");
    db.sql(
            "insert into analytics_jobs(type,payload,idempotency_key) values(?,cast(? as jsonb),?)"
                + " on conflict(idempotency_key) do nothing")
        .params(type, write(payload), key)
        .update();
    var job =
        db.sql("select * from analytics_jobs where idempotency_key=?")
            .param(key)
            .query()
            .singleRow();
    if (!job.get("type").equals(type) || !map(job.get("payload")).equals(payload))
      throw new IllegalArgumentException("Job idempotency conflict");
    return job;
  }

  public Object list() {
    return db.sql("select * from analytics_jobs order by created_at desc limit 100")
        .query()
        .listOfRows();
  }

  public void runOne() {
    var job =
        tx.execute(
            status -> {
              db.sql(
                      "update analytics_jobs set status=case when attempts>=max_attempts then"
                          + " 'FAILED' else 'QUEUED' end,lease_token=null,failure_reason='Lease"
                          + " expired; safe idempotent replay' where status='RUNNING' and"
                          + " lease_until<now()")
                  .update();
              var rows =
                  db.sql(
                          "select * from analytics_jobs where status='QUEUED' and"
                              + " available_at<=now() order by created_at for update skip locked"
                              + " limit 1")
                      .query()
                      .listOfRows();
              if (rows.isEmpty()) return null;
              var row = rows.getFirst();
              UUID token = UUID.randomUUID();
              row.put("lease_token", token);
              db.sql(
                      "update analytics_jobs set"
                          + " status='RUNNING',attempts=attempts+1,lease_token=?,lease_until=now()+interval"
                          + " '2 hours' where id=?")
                  .params(token, row.get("id"))
                  .update();
              return row;
            });
    if (job == null) return;
    try {
      var payload = map(job.get("payload"));
      Object result =
          switch (job.get("type").toString()) {
            case "IMPORT" -> imports.commit(UUID.fromString(payload.get("batchId").toString()));
            case "RECONCILE" -> aggregation.diagnostics();
            default ->
                aggregation.rebuild(
                    Objects.toString(payload.get("reason"), "Scheduled analytics rollup"),
                    Objects.toString(payload.get("createdBy"), "analytics-worker"),
                    AnalyticsRebuildScope.parse(payload));
          };
      db.sql(
              "update analytics_jobs set status='SUCCEEDED',completed_at=now(),result=cast(? as"
                  + " jsonb),lease_token=null where id=? and lease_token=?")
          .params(write(result), job.get("id"), job.get("lease_token"))
          .update();
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn(
              "Analytics job failed jobId={} type={} errorType={}",
              job.get("id"),
              job.get("type"),
              e.getClass().getSimpleName());
      db.sql(
              "update analytics_jobs set status=case when attempts>=max_attempts then 'FAILED' else"
                  + " 'QUEUED' end,available_at=now()+interval '1"
                  + " minute',lease_token=null,failure_reason=? where id=? and lease_token=?")
          .params(
              e instanceof IllegalArgumentException
                  ? e.getMessage()
                  : "Analytics operation failed; inspect server diagnostics",
              job.get("id"),
              job.get("lease_token"))
          .update();
    }
  }

  public void scheduleRollup() {
    boolean dirty =
        db.sql(
                "select greatest((select max(received_at) from performance_metrics),(select"
                    + " max(received_at) from metric_snapshots))>coalesce((select max(started_at)"
                    + " from analytics_rebuild_runs where"
                    + " status='COMPLETED' and scope='{}'::jsonb),'epoch'::timestamptz)")
            .query(Boolean.class)
            .optional()
            .orElse(false);
    if (dirty)
      enqueue(
          "AGGREGATE",
          Map.of(),
          "scheduled-rollup-" + java.time.Instant.now().getEpochSecond() / 60);
  }
}
