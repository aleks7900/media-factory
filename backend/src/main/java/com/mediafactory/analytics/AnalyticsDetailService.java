package com.mediafactory.analytics;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsDetailService {

  private final JdbcClient db;

  public AnalyticsDetailService(JdbcClient db) {
    this.db = db;
  }

  public Object asset(UUID id) {
    return asset(id, "USD");
  }

  public Object asset(UUID id, String currency) {
    AnalyticsIngestionService.currency(currency);
    var result = new LinkedHashMap<String, Object>();
    result.put("currency", currency);
    result.put(
        "lineage",
        db.sql("select * from analytics_lineage where asset_id=?").param(id).query().singleRow());
    result.put(
        "costs",
        db.sql(
                "select * from analytics_attributed_costs where asset_id=? order by occurred_at,id"
                    + " limit 500")
            .param(id)
            .query()
            .listOfRows());
    result.put(
        "costConversions",
        db.sql(
                """
                    select c.id,c.amount as original_amount,c.currency as original_currency,? as base_currency,
                     case when c.currency=? then 1 else fx.rate end as exchange_rate,fx.rate_date,fx.source as rate_source,
                     c.amount*case when c.currency=? then 1 else fx.rate end as converted_amount,c.pricing_status
                    from analytics_attributed_costs c left join analytics_currency_rates fx on fx.currency=c.currency
                     and fx.base_currency=? and fx.rate_date=(c.occurred_at at time zone 'UTC')::date
                    where c.asset_id=? order by c.occurred_at,c.id limit 500
                    """)
            .params(currency, currency, currency, currency, id)
            .query()
            .listOfRows());
    result.put(
        "publications",
        db.sql(
                "select p.*,exists(select 1 from wallpaper_deliveries d where d.publication_id=p.id"
                    + " and d.target='MOCK') as mock from publications p where asset_id=? order by"
                    + " published_at")
            .param(id)
            .query()
            .listOfRows());
    result.put(
        "externalReferences",
        db.sql("select * from external_asset_references where asset_id=? order by created_at")
            .param(id)
            .query()
            .listOfRows());
    result.put(
        "revenue",
        db.sql(
                "select platform,currency,sum(case when metric_type='REFUND' then -value else value"
                    + " end) as amount from analytics_normalized_metrics where asset_id=? and"
                    + " metric_type in ('REVENUE','REFUND') group by 1,2")
            .param(id)
            .query()
            .listOfRows());
    result.put(
        "qa",
        db.sql(
                "select id,decision,final_decision,rules_triggered,created_at from quality_reviews"
                    + " where asset_id=? order by created_at desc limit 100")
            .param(id)
            .query()
            .listOfRows());
    result.put(
        "processing",
        db.sql(
                "select id,status,started_at,completed_at,failure_code from processing_runs where"
                    + " source_asset_id=? order by created_at desc limit 100")
            .param(id)
            .query()
            .listOfRows());
    result.put(
        "clusters",
        db.sql(
                "select m.cluster_id,c.run_id,r.model_id,r.created_at from"
                    + " collection_cluster_members m join collection_clusters c on"
                    + " c.id=m.cluster_id join collection_clustering_runs r on r.id=c.run_id where"
                    + " m.asset_id=? order by r.created_at desc limit 100")
            .param(id)
            .query()
            .listOfRows());
    result.put(
        "events",
        db.sql(
                "select id,name,value,currency,measured_at,source,platform,correction_of,reason"
                    + " from performance_metrics where asset_id=? order by measured_at desc,id"
                    + " limit 200")
            .param(id)
            .query()
            .listOfRows());
    result.put(
        "cohorts",
        db.sql(
                """
                    with first_publication as (select min(published_at) as at from publications where asset_id=? and channel not in ('WALLPAPER_MOCK','WALLPAPER_DRY_RUN','WALLPAPER_EXPORT')),
                    valued as (select m.*,case when m.currency=? then 1 else fx.rate end as rate
                     from analytics_normalized_metrics m left join analytics_currency_rates fx on fx.currency=m.currency and fx.base_currency=?
                      and fx.rate_date=(m.occurred_at at time zone 'UTC')::date where m.asset_id=?)
                    select p.at as first_publication,extract(epoch from (now()-p.at))/86400 as days_since_first_publication,
                     case when p.at+interval '7 days'<=now() then sum(m.value) filter(where m.metric_type='DOWNLOAD' and m.occurred_at<p.at+interval '7 days') end as downloads_first_7_days,
                     case when p.at+interval '30 days'<=now() then sum(m.value) filter(where m.metric_type='DOWNLOAD' and m.occurred_at<p.at+interval '30 days') end as downloads_first_30_days,
                     case when p.at+interval '30 days'<=now() and count(*) filter(where m.metric_type in ('REVENUE','REFUND') and m.occurred_at<p.at+interval '30 days' and rate is null)=0
                      then sum(case when m.metric_type='REFUND' then -m.value else m.value end*rate) filter(where m.metric_type in ('REVENUE','REFUND') and m.occurred_at<p.at+interval '30 days') end as revenue_first_30_days
                    from first_publication p left join valued m on m.occurred_at>=p.at group by p.at
                    """)
            .params(id, currency, currency, id)
            .query()
            .singleRow());
    return result;
  }

  public Object providerOperations() {
    return db.sql(
            """
                with attempts as (
                 select provider,model,status,duration_ms from generation_attempts
                 union all select provider,model,case when status='GENERATED' then 'SUCCEEDED' when status in ('PROVIDER_FAILED','PROVIDER_REJECTED','TIMED_OUT','DOWNLOAD_FAILED') then 'FAILED' else status end,duration_ms from video_generation_attempts
                ) select provider,model,count(*) as requests,count(*) filter(where status='SUCCEEDED') as successes,
                 count(*) filter(where status in ('FAILED','TIMED_OUT','RATE_LIMITED')) as failures,
                 count(*) filter(where status='RATE_LIMITED') as rate_limits,avg(duration_ms) as average_latency_ms
                from attempts group by provider,model order by provider,model
                """)
        .query()
        .listOfRows();
  }

  public Object processing() {
    return Map.of(
        "image",
        db.sql(
                "select operation,count(*) as operations,count(*) filter(where status='FAILED') as"
                    + " failures,avg(duration_ms) as average_wall_time_ms from processing_steps"
                    + " group by operation")
            .query()
            .listOfRows(),
        "video",
        db.sql(
                "select parameters->>'loopStrategy' as loop_strategy,count(*) as runs,count(*)"
                    + " filter(where status='FAILED') as failures,avg(duration_ms) as"
                    + " average_wall_time_ms,sum(input_bytes) as input_bytes,sum(output_bytes) as"
                    + " output_bytes from video_processing_runs group by 1")
            .query()
            .listOfRows(),
        "limitation",
        "Durations are measured wall time, not CPU/GPU utilization or monetary cost.");
  }
}
