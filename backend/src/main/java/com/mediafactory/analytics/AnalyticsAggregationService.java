package com.mediafactory.analytics;

import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AnalyticsAggregationService {

  private static final Map<String, String> GROUPS =
      Map.ofEntries(
          Map.entry("asset", "l.asset_id::text"),
          Map.entry(
              "publicationDate", "to_char(pub.first_publication at time zone :zone,'YYYY-MM-DD')"),
          Map.entry("collection", "l.collection_id::text"),
          Map.entry("project", "l.project_id::text"),
          Map.entry("concept", "l.concept_id::text"),
          Map.entry("prompt", "l.prompt_version_id::text"),
          Map.entry("template", "l.prompt_template_id::text"),
          Map.entry("experiment", "l.experiment_id::text"),
          Map.entry("variant", "l.experiment_variant_id::text"),
          Map.entry("provider", "coalesce(f.provider,l.provider)"),
          Map.entry("model", "coalesce(f.model,l.model)"),
          Map.entry("platform", "f.platform"),
          Map.entry("style", "l.style"),
          Map.entry("preset", "l.presets::text"),
          Map.entry("generationProfile", "l.generation_profile"),
          Map.entry("videoProfile", "l.video_profile::text"),
          Map.entry("motionProfile", "l.motion_profile::text"),
          Map.entry("loopStrategy", "l.loop_strategy"),
          Map.entry("wallpaperProfile", "l.wallpaper_profile"),
          Map.entry("pipeline", "l.pipeline"),
          Map.entry("rejectionReason", "l.rejection_reason"),
          Map.entry("processingProfile", "l.processing_profiles"),
          Map.entry("cluster", "l.similarity_cluster::text"),
          Map.entry("publication", "f.publication_id::text"),
          Map.entry("deviceVariant", "f.variant_id::text"),
          Map.entry("amoled", "l.amoled::text"),
          Map.entry("generationDate", "to_char(l.generated_at at time zone :zone,'YYYY-MM-DD')"),
          Map.entry("date", "to_char(f.occurred_at at time zone :zone,'YYYY-MM-DD')"),
          Map.entry("overview", "'ALL'"));
  private static final Set<String> SORTS =
      Set.of(
          "views",
          "likes",
          "downloads",
          "revenue",
          "cost",
          "profit",
          "roi",
          "generated",
          "approved",
          "group_key");
  private final JdbcClient db;
  private final TransactionTemplate tx;
  private final MeterRegistry metrics;
  public AnalyticsAggregationService(JdbcClient db, TransactionTemplate tx, MeterRegistry metrics) {
    this.db = db;
    this.tx = tx;
    this.metrics = metrics;
  }

  public Map<String, Object> query(AnalyticsQuery q) {
    return metrics
        .timer("media_factory_analytics_aggregation_duration")
        .record(() -> queryInternal(q));
  }

  private Map<String, Object> queryInternal(AnalyticsQuery q) {
    if (!GROUPS.containsKey(q.groupBy()) || !SORTS.contains(q.sort())) {
      throw new IllegalArgumentException("Unsupported dimension or sort");
    }
    // UTC daily aggregates are used only for aligned UTC boundaries. Other ranges use authoritative
    // timestamps.
    boolean daily = q.timezone().equals("UTC") && aligned(q.from()) && aligned(q.to());
    String group = GROUPS.get(q.groupBy());
    if (q.groupBy().equals("date")) {
      group =
          "to_char(date_trunc('"
              + q.filters().getOrDefault("grain", "day")
              + "',f.occurred_at at time zone :zone),'YYYY-MM-DD')";
    }
    String metricSource =
        daily
            ? "select"
              + " asset_id,publication_id,variant_id,platform,metric_type,value,currency,day::timestamp"
              + " at time zone 'UTC' as occurred_at from analytics_daily_aggregate"
            : "select"
              + " asset_id,publication_id,variant_id,platform,metric_type,value,currency,occurred_at"
              + " from analytics_normalized_metrics";
    String sql =
        """
            with facts as (
             select l.generation_id,l.asset_id,null::text as platform,l.generated_at as occurred_at,
              'PRODUCTION'::text as kind,0::numeric as value,null::text as currency,l.provider,l.model,null::uuid as publication_id,null::uuid as variant_id from analytics_lineage l
             union all
             select generation_id,asset_id,null,occurred_at,'COST',amount,currency,provider,model,null,null from analytics_attributed_costs
             union all
             select a.generation_id,p.asset_id,p.channel,p.published_at,'PUBLICATION',0,null,null,null,p.id,null from publications p join assets a on a.id=p.asset_id
              where p.channel not in ('WALLPAPER_MOCK','WALLPAPER_DRY_RUN','WALLPAPER_EXPORT')
             union all
             select a.generation_id,m.asset_id,m.platform,m.occurred_at,m.metric_type,
              case when m.metric_type='REFUND' then -m.value else m.value end,m.currency,null,null,m.publication_id,m.variant_id
             from (%s) m join assets a on a.id=m.asset_id
            ), filtered as (
             select %s as group_key,l.*,f.kind,f.value,f.currency,f.occurred_at,
              case when f.currency=:currency then 1::numeric else fx.rate end as rate
             from facts f join analytics_lineage l on l.generation_id=f.generation_id
             left join (select asset_id,min(published_at) as first_publication from publications where channel not in ('WALLPAPER_MOCK','WALLPAPER_DRY_RUN','WALLPAPER_EXPORT') group by asset_id) pub on pub.asset_id=l.asset_id
             left join analytics_currency_rates fx on fx.currency=f.currency and fx.base_currency=:currency
              and fx.rate_date=(f.occurred_at at time zone 'UTC')::date
             where (cast(:project as uuid) is null or l.project_id=cast(:project as uuid))
              and (cast(:collection as uuid) is null or l.collection_id=cast(:collection as uuid))
              and (cast(:asset as uuid) is null or l.asset_id=cast(:asset as uuid))
              and (cast(:provider as text) is null or coalesce(f.provider,l.provider)=:provider)
              and (cast(:model as text) is null or coalesce(f.model,l.model)=:model)
              and (cast(:platform as text) is null or f.platform=:platform)
              and (cast(:status as text) is null or l.status=:status)
              and (cast(:assetType as text) is null or l.media_type like :assetType||'/%%')
              and (cast(:prompt as text) is null or l.prompt_version_id::text=:prompt)
              and (cast(:preset as text) is null or l.presets @> jsonb_build_array(jsonb_build_object('key',cast(:preset as text))))
              and (cast(:amoled as boolean) is null or l.amoled=cast(:amoled as boolean))
              and (cast(:stock as boolean) is null or l.stock=cast(:stock as boolean))
              and (cast(:wallpaper as boolean) is null or l.wallpaper=cast(:wallpaper as boolean))
              and (cast(:video as boolean) is null or l.video=cast(:video as boolean))
              and (cast(:from as timestamptz) is null or f.occurred_at>=cast(:from as timestamptz))
              and (cast(:to as timestamptz) is null or f.occurred_at<cast(:to as timestamptz))
            ), totals as (
             select coalesce(group_key,'UNATTRIBUTED') as group_key,
              count(*) filter(where kind='PRODUCTION') as generated,
              count(distinct concept_id) filter(where kind='PRODUCTION') as concepts,
              count(distinct asset_id) filter(where kind='PRODUCTION') as assets_generated,
              count(distinct asset_id) filter(where kind='PRODUCTION' and (
                exists(select 1 from processing_runs r where r.source_asset_id=filtered.asset_id and r.status='COMPLETED')
                or exists(select 1 from video_processing_runs r where r.raw_asset_id=filtered.asset_id and r.status='COMPLETED'))) as processed,
              count(*) filter(where kind='PRODUCTION' and status in ('APPROVED','PUBLISHED')) as approved,
              count(*) filter(where kind='PRODUCTION' and status='REJECTED') as rejected,
              count(*) filter(where kind='PRODUCTION' and status='FAILED') as failed,
              count(distinct asset_id) filter(where kind='PUBLICATION') as published,
              sum(value) filter(where kind='VIEW') as views,sum(value) filter(where kind='LIKE') as likes,
              sum(value) filter(where kind='DOWNLOAD') as downloads,
              case when count(*) filter(where kind in ('REVENUE','REFUND') and rate is null)=0
                then sum(value*rate) filter(where kind in ('REVENUE','REFUND')) end as revenue,
              case when count(*) filter(where kind='COST' and (rate is null or value is null))=0
                then sum(value*rate) filter(where kind='COST') end as cost,
              case when count(*) filter(where kind='COST' and status='REJECTED' and (rate is null or value is null))=0
                then sum(value*rate) filter(where kind='COST' and status='REJECTED') end as rejected_cost,
              case when count(*) filter(where kind='COST' and status='REJECTED' and rejection_reason='DUPLICATE' and (rate is null or value is null))=0
                then sum(value*rate) filter(where kind='COST' and status='REJECTED' and rejection_reason='DUPLICATE') end as duplicate_rejected_cost,
              case when count(*) filter(where kind='COST' and status='FAILED' and (rate is null or value is null))=0
                then sum(value*rate) filter(where kind='COST' and status='FAILED') end as failed_generation_cost,
              count(*) filter(where kind in ('COST','REVENUE','REFUND') and (rate is null or value is null)) as missing_money_facts
             from filtered group by group_key
            ), calculated as (select *,revenue-cost as profit,(revenue-cost)/nullif(cost,0) as roi,
             cost/nullif(approved,0) as cost_per_approved,cost/nullif(downloads,0) as cost_per_download,
             cost/nullif(published,0) as cost_per_published,cost/nullif(generated,0) as cost_per_generation,
             approved::numeric/nullif(generated,0) as approval_rate,revenue/nullif(views,0) as revenue_per_view,
             cost/nullif(revenue,0) as cost_per_revenue,
             case when revenue is not null and cost is not null then greatest(cost-revenue,0) end as break_even_remaining,
             revenue/nullif(downloads,0) as revenue_per_download,likes/nullif(views,0) as like_rate,
             downloads/nullif(views,0) as download_rate,
             views/nullif(cast(:windowDays as numeric),0) as views_per_day,
             downloads/nullif(cast(:windowDays as numeric),0) as downloads_per_day,
             revenue/nullif(cast(:windowDays as numeric),0) as revenue_per_day from totals)
            select *,count(*) over() as total_rows from calculated order by %s %s nulls last,group_key limit :limit offset :offset
            """
            .formatted(metricSource, group, q.sort(), q.descending() ? "desc" : "asc");
    var statement =
        db.sql(sql)
            .param("currency", q.currency())
            .param("zone", q.timezone())
            .param("project", q.projectId())
            .param("collection", q.collectionId())
            .param("asset", q.assetId())
            .param("provider", q.provider())
            .param("model", q.model())
            .param("platform", q.platform())
            .param("from", q.from() == null ? null : Timestamp.from(q.from()))
            .param("to", q.to() == null ? null : Timestamp.from(q.to()))
            .param("limit", q.size())
            .param("windowDays", windowDays(q))
            .param("offset", Math.multiplyExact(q.page(), q.size()));
    for (String filter :
        List.of("status", "assetType", "prompt", "preset", "amoled", "stock", "wallpaper",
            "video")) {
      statement = statement.param(filter, q.filters().get(filter));
    }
    var rows = statement.query().listOfRows();
    if (q.groupBy().equals("asset")) {
      var ids =
          rows.stream()
              .map(r -> r.get("group_key").toString())
              .filter(k -> !k.equals("UNATTRIBUTED"))
              .map(UUID::fromString)
              .toList();
      if (!ids.isEmpty()) {
        var previews =
            db.sql(
                    "select distinct on(asset_id) asset_id,id from asset_variants where asset_id in"
                        + " (:ids) and kind like '%THUMBNAIL%' and format in ('JPEG','PNG','WEBP')"
                        + " and size_bytes<=1048576 order by asset_id,created_at desc,id")
                .param("ids", ids)
                .query()
                .listOfRows();
        Map<String, Object> byAsset = new HashMap<>();
        for (var preview : previews) {
          byAsset.put(preview.get("asset_id").toString(), preview.get("id"));
        }
        for (var row : rows) {
          row.put("thumbnail_id", byAsset.get(row.get("group_key")));
        }
      }
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("rows", rows);
    result.put("total", rows.isEmpty() ? 0 : rows.getFirst().get("total_rows"));
    result.put("currency", q.currency());
    result.put("timezone", q.timezone());
    result.put("from", q.from());
    result.put("to", q.to());
    result.put("generatedAt", Instant.now());
    result.put("source", daily ? "DAILY_AGGREGATE" : "AUTHORITATIVE_FACTS");
    result.put(
        "warnings",
        List.of(
            "Missing metrics and unpriced costs remain null. Platform costs are unallocated;"
                + " production cost is not repeated for each platform.",
            "Approval counts describe current status of generations created in the selected period;"
                + " metric and cost timestamps use their own occurrence dates."));
    return result;
  }

  private boolean aligned(Instant i) {
    return i == null || (i.getEpochSecond() % 86400 == 0 && i.getNano() == 0);
  }

  private BigDecimal windowDays(AnalyticsQuery query) {
    if (query.from() == null) {
      return null;
    }
    var duration =
        java.time.Duration.between(query.from(), query.to() == null ? Instant.now() : query.to());
    if (duration.isNegative() || duration.isZero()) {
      return null;
    }
    return BigDecimal.valueOf(duration.getSeconds())
        .add(BigDecimal.valueOf(duration.getNano(), 9))
        .divide(BigDecimal.valueOf(86400), 12, java.math.RoundingMode.HALF_EVEN);
  }

  public Object rebuild(String reason, String actor) {
    return rebuild(reason, actor, AnalyticsRebuildScope.all());
  }

  public Object rebuild(String reason, String actor, AnalyticsRebuildScope scope) {
    AnalyticsIngestionService.required(reason, "reason", 2000);
    AnalyticsIngestionService.required(actor, "createdBy", 100);
    return metrics
        .timer("media_factory_analytics_rebuild_duration")
        .record(
            () ->
                tx.execute(
                    status -> {
                      db.sql("select pg_advisory_xact_lock(10102026)").query().singleRow();
                      UUID id = UUID.randomUUID();
                      db.sql(
                              "insert into analytics_rebuild_runs(id,reason,created_by,scope)"
                                  + " values(?,?,?,cast(? as jsonb))")
                          .params(
                              id,
                              reason,
                              actor,
                              com.mediafactory.processing.ProcessingJson.write(scope.audit()))
                          .update();
                      String filter =
                          """
                              (cast(:asset as uuid) is null or asset_id=cast(:asset as uuid))
                              and (cast(:collection as uuid) is null or asset_id in(select asset_id from analytics_lineage where collection_id=cast(:collection as uuid)))
                              and (cast(:from as date) is null or day>=cast(:from as date))
                              and (cast(:to as date) is null or day<cast(:to as date))
                              """;
                      db.sql("delete from analytics_daily_aggregate where " + filter)
                          .param("asset", scope.assetId())
                          .param("collection", scope.collectionId())
                          .param("from", scope.from())
                          .param("to", scope.to())
                          .update();
                      int rows =
                          db.sql(
                                  """
                                      insert into analytics_daily_aggregate(day,asset_id,publication_id,platform,metric_type,currency,variant_id,value,fact_count)
                                      select day,asset_id,publication_id,platform,metric_type,currency,variant_id,sum(value),count(*)
                                      from(select *, (occurred_at at time zone 'UTC')::date as day from analytics_normalized_metrics) facts
                                      where
                                      """
                                      + filter
                                      + " group by"
                                      + " day,asset_id,publication_id,platform,metric_type,currency,variant_id")
                              .param("asset", scope.assetId())
                              .param("collection", scope.collectionId())
                              .param("from", scope.from())
                              .param("to", scope.to())
                              .update();
                      db.sql(
                              "update analytics_rebuild_runs set"
                                  + " status='COMPLETED',completed_at=now() where id=?")
                          .param(id)
                          .update();
                      return Map.of(
                          "id",
                          id,
                          "status",
                          "COMPLETED",
                          "scope",
                          scope.audit(),
                          "aggregateRows",
                          rows);
                    }));
  }

  public Map<String, Object> diagnostics() {
    var result = new LinkedHashMap<String, Object>();
    result.put(
        "lastRebuild",
        db
            .sql("select * from analytics_rebuild_runs order by started_at desc limit 1")
            .query()
            .listOfRows()
            .stream()
            .findFirst()
            .orElse(Map.of()));
    result.put(
        "snapshotWarnings",
        db.sql(
                "select quality,count(*) as count from analytics_snapshot_deltas where"
                    + " quality<>'VALID' group by quality")
            .query()
            .listOfRows());
    result.put(
        "legacyMeasurements",
        db.sql("select count(*) from performance_metrics where source='LEGACY'")
            .query(Long.class)
            .single());
    result.put(
        "imports",
        db.sql("select status,count(*) as count from analytics_import_rows group by status")
            .query()
            .listOfRows());
    result.put(
        "reconciliation",
        db.sql(
                """
                    with raw as (select metric_type,currency,sum(value) as value from analytics_normalized_metrics group by 1,2),
                     rollup as (select metric_type,currency,sum(value) as value from analytics_daily_aggregate group by 1,2)
                    select coalesce(r.metric_type,a.metric_type) as metric,coalesce(r.currency,a.currency) as currency,
                     r.value as raw_value,a.value as aggregate_value,coalesce(r.value,0)-coalesce(a.value,0) as difference
                    from raw r full join rollup a on r.metric_type=a.metric_type and r.currency is not distinct from a.currency
                    """)
            .query()
            .listOfRows());
    return result;
  }
}
