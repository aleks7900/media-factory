package com.mediafactory.analytics;

import static com.mediafactory.processing.ProcessingJson.write;

import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AnalyticsIngestionService {
  private final JdbcClient db;
  private final TransactionTemplate tx;
  private final MeterRegistry metrics;

  public AnalyticsIngestionService(JdbcClient db, TransactionTemplate tx, MeterRegistry metrics) {
    this.db = db;
    this.tx = tx;
    this.metrics = metrics;
  }

  public record Event(
      UUID assetId,
      UUID variantId,
      UUID publicationId,
      String platform,
      String type,
      BigDecimal value,
      String currency,
      Instant occurredAt,
      String source,
      String deduplicationKey,
      UUID correctionOf,
      String reason,
      String createdBy,
      Map<String, Object> metadata) {}

  public record Reference(
      UUID assetId,
      UUID variantId,
      UUID publicationId,
      String platform,
      String externalId,
      String externalUrl) {}

  public record Snapshot(
      UUID referenceId,
      String type,
      BigDecimal value,
      String currency,
      Instant capturedAt,
      String source,
      String deduplicationKey,
      String resetPolicy,
      String reason,
      String createdBy) {}

  public record Rate(
      String currency,
      String baseCurrency,
      LocalDate date,
      BigDecimal rate,
      String source,
      String createdBy) {}

  static void required(String value, String name, int max) {
    if (value == null || value.isBlank() || value.length() > max)
      throw new IllegalArgumentException(name + " is required (maximum " + max + ")");
  }

  static void currency(String value) {
    try {
      Currency.getInstance(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid ISO currency");
    }
  }

  static void measurement(
      String type, BigDecimal value, String currency, Instant at, boolean correction) {
    required(type, "type", 100);
    if (!type.matches("[A-Z][A-Z0-9_]{0,99}")
        || type.endsWith("_COST")
        || Set.of("COST", "PRODUCTION", "PUBLICATION").contains(type))
      throw new IllegalArgumentException(
          "External metric type required; costs come from the production cost ledger");
    if (value == null
        || value.precision() > 28
        || value.precision() - value.scale() > 16
        || value.scale() > 12
        || (!correction && value.signum() < 0))
      throw new IllegalArgumentException("Invalid decimal measurement");
    if (at == null || at.getNano() % 1000 != 0 || at.isAfter(Instant.now().plusSeconds(300)))
      throw new IllegalArgumentException(
          "Invalid or future timestamp; maximum precision is microseconds");
    if (Set.of("REVENUE", "REFUND").contains(type)) currency(currency);
    else if (currency != null)
      throw new IllegalArgumentException("Currency is only valid for monetary metrics");
    if (type.equals("REFUND") && value.signum() < 0 && !correction)
      throw new IllegalArgumentException("Refund amount must be positive");
  }

  public Map<String, Object> reference(Reference r) {
    if (r.assetId() == null) throw new IllegalArgumentException("assetId is required");
    required(r.platform(), "platform", 100);
    required(r.externalId(), "externalId", 300);
    return tx.execute(
        s -> {
          db.sql(
                  "insert into"
                      + " external_asset_references(asset_id,variant_id,publication_id,platform,external_id,external_url)"
                      + " values(?,?,?,?,?,?) on conflict(platform,external_id) do nothing")
              .params(
                  r.assetId(),
                  r.variantId(),
                  r.publicationId(),
                  r.platform(),
                  r.externalId(),
                  r.externalUrl())
              .update();
          var row =
              db.sql("select * from external_asset_references where platform=? and external_id=?")
                  .params(r.platform(), r.externalId())
                  .query()
                  .singleRow();
          if (!Objects.equals(row.get("asset_id"), r.assetId())
              || !Objects.equals(row.get("variant_id"), r.variantId())
              || !Objects.equals(row.get("publication_id"), r.publicationId()))
            throw new IllegalArgumentException(
                "External ID is already mapped to different lineage");
          return row;
        });
  }

  public Map<String, Object> event(Event e) {
    validate(e);
    return tx.execute(
        s -> {
          // Lock a source/asset stream to make event-vs-snapshot exclusion race-safe.
          streamLock(e.source(), e.assetId(), e.platform(), e.type());
          if (!e.source().equals("MANUAL_IMPORT")
              && !db.sql(
                      "select exists(select 1 from external_asset_references where asset_id=? and"
                          + " platform=? and publication_id is not distinct from cast(? as uuid)"
                          + " and variant_id is not distinct from cast(? as uuid))")
                  .params(e.assetId(), e.platform(), e.publicationId(), e.variantId())
                  .query(Boolean.class)
                  .single())
            throw new IllegalArgumentException(
                "Map the external asset/publication before ingesting external metrics");
          if (db.sql(
                  "select exists(select 1 from metric_snapshots s join external_asset_references r"
                      + " on r.id=s.external_reference_id where s.source=? and r.asset_id=? and"
                      + " r.platform=? and s.metric_type=? and r.publication_id is not distinct"
                      + " from cast(? as uuid) and r.variant_id is not distinct from cast(? as"
                      + " uuid))")
              .params(
                  e.source(), e.assetId(), e.platform(), e.type(), e.publicationId(), e.variantId())
              .query(Boolean.class)
              .single())
            throw new IllegalArgumentException(
                "This source/asset metric is a cumulative snapshot stream");
          if (e.correctionOf() != null) {
            var original =
                db
                    .sql("select * from performance_metrics where id=?")
                    .param(e.correctionOf())
                    .query()
                    .listOfRows()
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Correction target not found"));
            if (!Objects.equals(original.get("asset_id"), e.assetId())
                || !Objects.equals(original.get("publication_id"), e.publicationId())
                || !Objects.equals(original.get("variant_id"), e.variantId())
                || !Objects.equals(original.get("platform"), e.platform())
                || !Objects.equals(original.get("name"), e.type())
                || !Objects.equals(original.get("currency"), e.currency()))
              throw new IllegalArgumentException(
                  "Correction lineage/type/currency must match original fact");
          }
          UUID id = UUID.randomUUID();
          int inserted =
              db.sql(
                      "insert into"
                          + " performance_metrics(id,asset_id,variant_id,publication_id,platform,name,value,currency,measured_at,source,deduplication_key,correction_of,reason,created_by,metadata)"
                          + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb)) on"
                          + " conflict(source,deduplication_key) where deduplication_key is not"
                          + " null do nothing")
                  .params(
                      id,
                      e.assetId(),
                      e.variantId(),
                      e.publicationId(),
                      e.platform(),
                      e.type(),
                      e.value(),
                      e.currency(),
                      java.sql.Timestamp.from(e.occurredAt()),
                      e.source(),
                      e.deduplicationKey(),
                      e.correctionOf(),
                      e.reason(),
                      e.createdBy(),
                      write(e.metadata() == null ? Map.of() : e.metadata()))
                  .update();
          metrics
              .counter(
                  inserted == 1
                      ? "media_factory_analytics_events_ingested_total"
                      : "media_factory_analytics_events_duplicate_total")
              .increment();
          var row =
              db.sql("select * from performance_metrics where source=? and deduplication_key=?")
                  .params(e.source(), e.deduplicationKey())
                  .query()
                  .singleRow();
          if (!Objects.equals(row.get("asset_id"), e.assetId())
              || !Objects.equals(row.get("variant_id"), e.variantId())
              || !Objects.equals(row.get("publication_id"), e.publicationId())
              || !Objects.equals(row.get("platform"), e.platform())
              || !Objects.equals(row.get("name"), e.type())
              || ((BigDecimal) row.get("value")).compareTo(e.value()) != 0
              || !Objects.equals(row.get("currency"), e.currency())
              || !((java.sql.Timestamp) row.get("measured_at")).toInstant().equals(e.occurredAt())
              || !Objects.equals(row.get("correction_of"), e.correctionOf()))
            throw new IllegalArgumentException(
                "Idempotency key already belongs to a different measurement");
          return Map.of("id", row.get("id"), "duplicate", inserted == 0);
        });
  }

  public void validate(Event e) {
    if (e.assetId() == null) throw new IllegalArgumentException("assetId is required");
    required(e.platform(), "platform", 100);
    required(e.source(), "source", 100);
    if (Set.of("LEGACY", "MEDIA_FACTORY").contains(e.source()))
      throw new IllegalArgumentException("Reserved internal source");
    required(e.deduplicationKey(), "deduplicationKey", 200);
    required(e.reason(), "reason", 2000);
    required(e.createdBy(), "createdBy", 100);
    measurement(e.type(), e.value(), e.currency(), e.occurredAt(), e.correctionOf() != null);
    if (e.metadata() != null && write(e.metadata()).length() > 16000)
      throw new IllegalArgumentException("Metadata too large");
  }

  private void streamLock(String source, UUID asset, String platform, String type) {
    db.sql("select pg_advisory_xact_lock(hashtextextended(?,0))")
        .param(source + "|" + asset + "|" + platform + "|" + type)
        .query()
        .singleRow();
  }

  public Map<String, Object> snapshot(Snapshot s) {
    measurement(s.type(), s.value(), s.currency(), s.capturedAt(), false);
    required(s.source(), "source", 100);
    required(s.deduplicationKey(), "deduplicationKey", 200);
    if (Set.of("LEGACY", "MEDIA_FACTORY").contains(s.source()))
      throw new IllegalArgumentException("Reserved internal source");
    required(s.reason(), "reason", 2000);
    required(s.createdBy(), "createdBy", 100);
    if (!Set.of("UNKNOWN", "RESET_TO_ZERO").contains(s.resetPolicy()))
      throw new IllegalArgumentException("Explicit reset policy required");
    return tx.execute(
        status -> {
          var r =
              db.sql("select * from external_asset_references where id=?")
                  .param(s.referenceId())
                  .query()
                  .singleRow();
          streamLock(s.source(), (UUID) r.get("asset_id"), (String) r.get("platform"), s.type());
          if (db.sql(
                  "select exists(select 1 from performance_metrics where source=? and asset_id=?"
                      + " and platform=? and name=? and publication_id is not distinct from cast(?"
                      + " as uuid) and variant_id is not distinct from cast(? as uuid))")
              .params(
                  s.source(),
                  r.get("asset_id"),
                  r.get("platform"),
                  s.type(),
                  r.get("publication_id"),
                  r.get("variant_id"))
              .query(Boolean.class)
              .single())
            throw new IllegalArgumentException("This source/asset metric is an event stream");
          if (db.sql(
                  "select exists(select 1 from metric_snapshots where external_reference_id=? and"
                      + " metric_type=? and (currency is distinct from cast(? as char(3)) or"
                      + " source<>?))")
              .params(s.referenceId(), s.type(), s.currency(), s.source())
              .query(Boolean.class)
              .single())
            throw new IllegalArgumentException(
                "Snapshot source and currency cannot change within a stream");
          int inserted =
              db.sql(
                      "insert into"
                          + " metric_snapshots(external_reference_id,metric_type,value,currency,captured_at,source,deduplication_key,reset_policy,reason,created_by)"
                          + " values(?,?,?,?,?,?,?,?,?,?) on conflict(source,deduplication_key) do"
                          + " nothing")
                  .params(
                      s.referenceId(),
                      s.type(),
                      s.value(),
                      s.currency(),
                      java.sql.Timestamp.from(s.capturedAt()),
                      s.source(),
                      s.deduplicationKey(),
                      s.resetPolicy(),
                      s.reason(),
                      s.createdBy())
                  .update();
          var row =
              db.sql("select * from metric_snapshots where source=? and deduplication_key=?")
                  .params(s.source(), s.deduplicationKey())
                  .query()
                  .singleRow();
          if (!Objects.equals(row.get("external_reference_id"), s.referenceId())
              || !Objects.equals(row.get("metric_type"), s.type())
              || ((BigDecimal) row.get("value")).compareTo(s.value()) != 0
              || !Objects.equals(row.get("currency"), s.currency())
              || !Objects.equals(row.get("reset_policy"), s.resetPolicy())
              || !((java.sql.Timestamp) row.get("captured_at")).toInstant().equals(s.capturedAt()))
            throw new IllegalArgumentException("Snapshot idempotency conflict");
          return Map.of("id", row.get("id"), "duplicate", inserted == 0);
        });
  }

  public Object rate(Rate r) {
    currency(r.currency());
    currency(r.baseCurrency());
    required(r.source(), "source", 200);
    required(r.createdBy(), "createdBy", 100);
    if (r.date() == null
        || r.rate() == null
        || r.rate().signum() <= 0
        || r.rate().scale() > 12
        || r.rate().precision() > 28
        || r.currency().equals(r.baseCurrency()))
      throw new IllegalArgumentException("Invalid exchange rate");
    return db.sql(
            "insert into"
                + " analytics_currency_rates(currency,base_currency,rate_date,rate,source,created_by)"
                + " values(?,?,?,?,?,?) returning *")
        .params(r.currency(), r.baseCurrency(), r.date(), r.rate(), r.source(), r.createdBy())
        .query()
        .singleRow();
  }
}
