package com.mediafactory.analytics;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsDataQualityService {
  private final JdbcClient db;

  public AnalyticsDataQualityService(JdbcClient db) {
    this.db = db;
  }

  public Object report() {
    var result = new LinkedHashMap<String, Object>();
    result.put(
        "unpricedCosts",
        db.sql("select count(*) from analytics_cost_facts where amount is null")
            .query(Long.class)
            .single());
    result.put(
        "unmappedImportRows",
        db.sql("select count(*) from analytics_import_rows where status='UNMAPPED'")
            .query(Long.class)
            .single());
    result.put(
        "invalidImportRows",
        db.sql("select count(*) from analytics_import_rows where status='INVALID'")
            .query(Long.class)
            .single());
    result.put(
        "counterWarnings",
        db.sql(
                "select quality,count(*) as count from analytics_snapshot_deltas where"
                    + " quality<>'VALID' group by quality")
            .query()
            .listOfRows());
    result.put(
        "metricWarnings",
        db.sql(
                """
                with totals as (select asset_id,platform,sum(value) filter(where metric_type='VIEW') as views,
                 sum(value) filter(where metric_type='LIKE') as likes,sum(value) filter(where metric_type='DOWNLOAD') as downloads
                 from analytics_daily_aggregate group by asset_id,platform)
                select *,case when views<0 or likes<0 or downloads<0 then 'NEGATIVE_CORRECTED_TOTAL'
                 else 'ENGAGEMENT_EXCEEDS_RECORDED_VIEWS' end as warning from totals
                where views<0 or likes<0 or downloads<0 or likes>views or downloads>views limit 200
                """)
            .query()
            .listOfRows());
    result.put(
        "interpretation",
        "Warnings require source review; engagement can exceed views when platform coverage"
            + " differs. No automatic correction is applied.");
    return result;
  }
}
