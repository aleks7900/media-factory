package com.mediafactory.analytics;

import static com.mediafactory.processing.ProcessingJson.*;

import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.apache.commons.csv.CSVFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AnalyticsImportService {
  private final JdbcClient db;
  private final AnalyticsIngestionService ingestion;
  private final TransactionTemplate tx;
  private final io.micrometer.core.instrument.MeterRegistry metrics;

  public AnalyticsImportService(
      JdbcClient db,
      AnalyticsIngestionService ingestion,
      TransactionTemplate tx,
      io.micrometer.core.instrument.MeterRegistry metrics) {
    this.db = db;
    this.ingestion = ingestion;
    this.tx = tx;
    this.metrics = metrics;
  }

  /** Column names are supplied by the platform mapping profile, not hardcoded in ingestion. */
  public record Request(
      String source,
      String platform,
      String filename,
      String csv,
      Map<String, String> columns,
      String createdBy) {}

  static String hash(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public Object preview(Request r) {
    AnalyticsIngestionService.required(r.source(), "source", 100);
    AnalyticsIngestionService.required(r.platform(), "platform", 100);
    AnalyticsIngestionService.required(r.filename(), "filename", 300);
    AnalyticsIngestionService.required(r.createdBy(), "createdBy", 100);
    if (r.csv() == null || r.csv().length() > 2_000_000 || r.columns() == null)
      throw new IllegalArgumentException("CSV/profile missing or CSV exceeds 2 MB");
    if (!r.columns().keySet().containsAll(Set.of("externalId", "type", "value", "occurredAt")))
      throw new IllegalArgumentException("Mapping requires externalId, type, value, occurredAt");
    String checksum = hash(r.csv());
    return tx.execute(
        status -> {
          var existing =
              db.sql(
                      "select id from analytics_import_batches where source=? and platform=? and"
                          + " checksum=?")
                  .params(r.source(), r.platform(), checksum)
                  .query(UUID.class)
                  .optional();
          if (existing.isPresent()) {
            var saved =
                db.sql("select profile from analytics_import_batches where id=?")
                    .param(existing.get())
                    .query()
                    .singleRow();
            if (!map(saved.get("profile")).equals(r.columns()))
              throw new IllegalArgumentException(
                  "This CSV was already validated with a different mapping profile");
            return detail(existing.get());
          }
          UUID id = UUID.randomUUID();
          db.sql(
                  "insert into"
                      + " analytics_import_batches(id,source,platform,filename,checksum,profile,created_by)"
                      + " values(?,?,?,?,?,cast(? as jsonb),?)")
              .params(
                  id,
                  r.source(),
                  r.platform(),
                  r.filename(),
                  checksum,
                  write(r.columns()),
                  r.createdBy())
              .update();
          int count = 0;
          Set<String> seen = new HashSet<>();
          try (var parser =
              CSVFormat.DEFAULT
                  .builder()
                  .setHeader()
                  .setSkipHeaderRecord(true)
                  .get()
                  .parse(new StringReader(r.csv().replaceFirst("^\\uFEFF", "")))) {
            for (var record : parser) {
              if (++count > 10000)
                throw new IllegalArgumentException("Maximum 10000 rows per import");
              Map<String, Object> normalized = new LinkedHashMap<>();
              String rowStatus = "VALID", error = null;
              try {
                for (var mapping : r.columns().entrySet())
                  normalized.put(mapping.getKey(), record.get(mapping.getValue()));
                normalized.put("source", r.source());
                normalized.put("platform", r.platform());
                normalized.put("createdBy", r.createdBy());
                normalized.put("deduplicationKey", semanticKey(normalized));
                String money = Objects.toString(normalized.get("currency"), "");
                AnalyticsIngestionService.measurement(
                    normalized.get("type").toString(),
                    new BigDecimal(normalized.get("value").toString()),
                    money.isBlank() ? null : money,
                    Instant.parse(normalized.get("occurredAt").toString()),
                    false);
                var event = resolve(normalized);
                ingestion.validate(event);
                if (!seen.add(event.deduplicationKey())
                    || db.sql(
                            "select exists(select 1 from performance_metrics where source=? and"
                                + " deduplication_key=?)")
                        .params(event.source(), event.deduplicationKey())
                        .query(Boolean.class)
                        .single()) rowStatus = "DUPLICATE";
              } catch (Unmapped e) {
                rowStatus = "UNMAPPED";
                error = e.getMessage();
              } catch (Exception e) {
                rowStatus = "INVALID";
                error = e.getMessage();
              }
              db.sql(
                      "insert into"
                          + " analytics_import_rows(batch_id,row_number,normalized,status,error)"
                          + " values(?,?,cast(? as jsonb),?,?)")
                  .params(id, count, write(normalized), rowStatus, error)
                  .update();
              metrics.counter("media_factory_analytics_import_rows_total").increment();
              if (rowStatus.equals("INVALID"))
                metrics.counter("media_factory_analytics_import_errors_total").increment();
              if (rowStatus.equals("UNMAPPED"))
                metrics.counter("media_factory_analytics_unmapped_total").increment();
            }
          } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Invalid CSV", e);
          }
          db.sql("update analytics_import_batches set rows_read=? where id=?")
              .params(count, id)
              .update();
          return detail(id);
        });
  }

  private String semanticKey(Map<String, Object> n) {
    // Optional source event ID distinguishes genuine equal-value events at the same instant.
    return hash(
        write(
            List.of(
                n.get("platform"),
                n.get("externalId"),
                n.get("type"),
                new BigDecimal(n.get("value").toString()).stripTrailingZeros().toPlainString(),
                Instant.parse(n.get("occurredAt").toString()).toString(),
                Objects.toString(n.get("currency"), ""),
                Objects.toString(n.get("eventId"), ""))));
  }

  private AnalyticsIngestionService.Event resolve(Map<String, Object> n) {
    var ref =
        db
            .sql("select * from external_asset_references where platform=? and external_id=?")
            .params(n.get("platform"), n.get("externalId"))
            .query()
            .listOfRows()
            .stream()
            .findFirst()
            .orElseThrow(() -> new Unmapped("External asset is not mapped"));
    String currency = Objects.toString(n.get("currency"), "");
    return new AnalyticsIngestionService.Event(
        (UUID) ref.get("asset_id"),
        (UUID) ref.get("variant_id"),
        (UUID) ref.get("publication_id"),
        n.get("platform").toString(),
        n.get("type").toString(),
        new BigDecimal(n.get("value").toString()),
        currency.isBlank() ? null : currency,
        Instant.parse(n.get("occurredAt").toString()),
        n.get("source").toString(),
        n.get("deduplicationKey").toString(),
        null,
        "CSV import",
        n.get("createdBy").toString(),
        Map.of("externalId", n.get("externalId")));
  }

  public Object commit(UUID id) {
    // Each row commits independently: a bad row cannot roll back valid imported facts.
    for (var row :
        db.sql(
                "select * from analytics_import_rows where batch_id=? and status not in"
                    + " ('IMPORTED','DUPLICATE') order by row_number")
            .param(id)
            .query()
            .listOfRows()) {
      try {
        tx.executeWithoutResult(
            s -> {
              var locked =
                  db.sql("select * from analytics_import_rows where id=? for update")
                      .param(row.get("id"))
                      .query()
                      .singleRow();
              if (Set.of("IMPORTED", "DUPLICATE").contains(locked.get("status"))) return;
              var result = ingestion.event(resolve(map(locked.get("normalized"))));
              db.sql("update analytics_import_rows set status=?,event_id=?,error=null where id=?")
                  .params(
                      Boolean.TRUE.equals(result.get("duplicate")) ? "DUPLICATE" : "IMPORTED",
                      result.get("id"),
                      row.get("id"))
                  .update();
            });
      } catch (Exception e) {
        db.sql(
                "update analytics_import_rows set status=?,error=? where id=? and status not in"
                    + " ('IMPORTED','DUPLICATE')")
            .params(
                e instanceof Unmapped ? "UNMAPPED" : "INVALID",
                e instanceof IllegalArgumentException
                    ? e.getMessage()
                    : "Row could not be imported; verify mapping and lineage",
                row.get("id"))
            .update();
      }
    }
    db.sql(
            """
            update analytics_import_batches b set status='COMPLETED',completed_at=now(),
             rows_imported=(select count(*) from analytics_import_rows where batch_id=b.id and status='IMPORTED'),
             rows_skipped=(select count(*) from analytics_import_rows where batch_id=b.id and status='DUPLICATE'),
             rows_failed=(select count(*) from analytics_import_rows where batch_id=b.id and status in ('INVALID','UNMAPPED')) where id=?
            """)
        .param(id)
        .update();
    return detail(id);
  }

  public Object detail(UUID id) {
    return Map.of(
        "batch",
        db.sql("select * from analytics_import_batches where id=?").param(id).query().singleRow(),
        "validation",
        db.sql(
                "select status,count(*) as count from analytics_import_rows where batch_id=? group"
                    + " by status")
            .param(id)
            .query()
            .listOfRows(),
        "expectedRevenue",
        db.sql(
                "select normalized->>'currency' as currency,sum((normalized->>'value')::numeric) as"
                    + " amount from analytics_import_rows where batch_id=? and status='VALID' and"
                    + " normalized->>'type'='REVENUE' group by 1")
            .param(id)
            .query()
            .listOfRows(),
        "rows",
        db.sql(
                "select * from analytics_import_rows where batch_id=? order by row_number limit"
                    + " 10000")
            .param(id)
            .query()
            .listOfRows());
  }

  public Object list() {
    return db.sql("select * from analytics_import_batches order by created_at desc limit 100")
        .query()
        .listOfRows();
  }

  private static class Unmapped extends IllegalArgumentException {
    Unmapped(String s) {
      super(s);
    }
  }
}
