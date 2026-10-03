package com.mediafactory.analytics;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** TASK-10 cohort projection shared with the feedback engine. No second measurement ledger. */
@Service
public class AnalyticsFeedbackDataset {
  private final JdbcClient db;

  public AnalyticsFeedbackDataset(JdbcClient db) {
    this.db = db;
  }

  public int snapshot(UUID run, Map<String, Object> parameters) {
    var scope = com.mediafactory.processing.ProcessingJson.map(parameters.get("scope"));
    String metric = parameters.get("metric").toString();
    String expression =
        switch (metric) {
          case "DOWNLOADS" -> "downloads";
          case "VIEWS" -> "views";
          case "LIKES" -> "likes";
          case "DOWNLOAD_RATE" -> "downloads/nullif(views,0)";
          case "LIKE_RATE" -> "likes/nullif(views,0)";
          case "REVENUE" -> "revenue";
          case "PROFIT" -> "revenue-cost";
          case "ROI" -> "(revenue-cost)/nullif(cost,0)";
          case "QA_APPROVAL_RATE" -> "approved";
          case "COST_PER_APPROVED_ASSET" -> "case when approved is not null then cost end";
          default -> throw new IllegalArgumentException("Unsupported feedback metric");
        };
    boolean qa = Set.of("QA_APPROVAL_RATE", "COST_PER_APPROVED_ASSET").contains(metric);
    var args = new HashMap<String, Object>();
    args.put("run", run);
    args.put("from", parameters.get("from"));
    args.put("to", parameters.get("to"));
    args.put("asOf", parameters.get("asOf"));
    args.put("days", parameters.get("observationDays"));
    args.put("currency", parameters.get("currency"));
    args.put("extractor", parameters.get("extractorVersion"));
    args.put("platform", scope.get("platform"));
    args.put("confidence", parameters.get("minimumFeatureConfidence"));
    StringBuilder filters = new StringBuilder();
    Map<String, String> allowed =
        Map.of(
            "projectId",
            "l.project_id",
            "collectionId",
            "l.collection_id",
            "provider",
            "l.provider",
            "model",
            "l.model",
            "promptVersionId",
            "l.prompt_version_id",
            "clusterId",
            "l.similarity_cluster",
            "assetType",
            "l.pipeline",
            "experimentId",
            "l.experiment_id");
    allowed.forEach(
        (key, column) -> {
          if (scope.get(key) != null && !scope.get(key).toString().isBlank()) {
            filters.append(" and ").append(column).append("=:").append(key);
            args.put(
                key,
                key.endsWith("Id") ? UUID.fromString(scope.get(key).toString()) : scope.get(key));
          }
        });
    String sql =
        """
        with population as (
         select l.*,p.at as published_at,ps.variables,
          case when l.status in ('APPROVED','PUBLISHED') then 1.0 when l.status='REJECTED' then 0.0 else null end as approved
         from analytics_lineage l
         left join rendered_prompt_snapshots ps on ps.id=l.prompt_snapshot_id
         left join lateral(select min(published_at) at from publications p where p.asset_id=l.asset_id
          and (cast(:platform as text) is null or p.channel=:platform)
          and p.channel not in ('WALLPAPER_MOCK','WALLPAPER_DRY_RUN','WALLPAPER_EXPORT')) p on true
         where l.asset_id is not null
        """
            + filters
            + " and "
            + (qa ? "l.generated_at" : "p.at")
            + ">=cast(:from as timestamptz) and "
            + (qa ? "l.generated_at" : "p.at")
            + "<cast(:to as timestamptz)"
            + (qa
                ? ""
                : " and p.at+cast(:days as int)*interval '1 day'<=cast(:asOf as timestamptz)")
            + """
            ), performance as (
             select p.asset_id,
              sum(m.value) filter(where m.metric_type='DOWNLOAD') downloads,
              sum(m.value) filter(where m.metric_type='VIEW') views,
              sum(m.value) filter(where m.metric_type='LIKE') likes,
              case when count(*) filter(where m.metric_type in ('REVENUE','REFUND') and m.currency<>:currency and fx.rate is null)=0
               then sum((case when m.metric_type='REFUND' then -m.value else m.value end)*case when m.currency=:currency then 1 else fx.rate end) filter(where m.metric_type in ('REVENUE','REFUND')) end revenue
             from population p left join analytics_normalized_metrics m on m.asset_id=p.asset_id
              and m.occurred_at>=p.published_at and m.occurred_at<p.published_at+cast(:days as int)*interval '1 day'
              and (cast(:platform as text) is null or m.platform=:platform)
             left join analytics_currency_rates fx on fx.currency=m.currency and fx.base_currency=:currency and fx.rate_date=(m.occurred_at at time zone 'UTC')::date
             group by p.asset_id
            ), costs as (
             select p.asset_id,case when count(c.id)>0 and count(*) filter(where c.amount is null or (c.currency<>:currency and fx.rate is null))=0
              then sum(c.amount*case when c.currency=:currency then 1 else fx.rate end) end cost
             from population p left join analytics_attributed_costs c on c.asset_id=p.asset_id and c.occurred_at<=cast(:asOf as timestamptz)
             left join analytics_currency_rates fx on fx.currency=c.currency and fx.base_currency=:currency and fx.rate_date=(c.occurred_at at time zone 'UTC')::date group by p.asset_id
            ), valued as (
             select p.*,m.downloads,m.views,m.likes,m.revenue,c.cost from population p join performance m using(asset_id) join costs c using(asset_id)
            ), frozen_features as (
             select f.asset_id,jsonb_object_agg(d.key,coalesce(o.override_value,f.value)) filter(where f.role='OBSERVED_ATTRIBUTE') as features,
              jsonb_object_agg(d.key,f.value) filter(where f.role='REQUESTED_ATTRIBUTE') requested,jsonb_agg(jsonb_build_object('featureId',f.id,'overrideId',o.id,'definitionId',d.id,'taxonomyVersion',d.version,'source',f.source,'confidence',f.confidence)) ids
             from asset_visual_features f join population p on p.asset_id=f.asset_id
             join visual_attribute_definitions d on d.id=f.attribute_definition_id
             join visual_feature_extractions e on e.id=f.extraction_id and e.status='COMPLETED'
             left join lateral(select o.id,o.override_value from visual_feature_overrides o join visual_attribute_definitions od on od.id=o.attribute_definition_id
              where o.asset_id=f.asset_id and od.key=d.key and f.role='OBSERVED_ATTRIBUTE' order by o.created_at desc,o.id desc limit 1)o on true
             where f.extractor_version=:extractor and (f.source<>'VISION_MODEL' or f.confidence>=:confidence or o.id is not null) group by f.asset_id
            )
            insert into feedback_dataset_rows(run_id,asset_id,generation_id,collection_id,provider,model,prompt_version_id,experiment_variant_id,cluster_id,generated_at,published_at,value,metrics,features,requested,feature_ids,context)
            select :run,v.asset_id,v.generation_id,v.collection_id,v.provider,v.model,v.prompt_version_id,v.experiment_variant_id,v.similarity_cluster,v.generated_at,v.published_at,
            """
            + expression
            + """
            ,jsonb_build_object('downloads',downloads,'views',views,'likes',likes,'revenue',revenue,'cost',cost,'approved',approved,'profit',revenue-cost),
             coalesce(f.features,'{}')||jsonb_strip_nulls(jsonb_build_object('production_provider',v.provider,'production_model',v.model,'production_prompt_version',v.prompt_version_id,'production_presets',v.presets::text,'production_cluster',v.similarity_cluster,'production_processing_profile',v.processing_profiles,'production_video_profile',v.video_profile,'production_motion_profile',v.motion_profile,'production_loop_strategy',v.loop_strategy,'production_amoled',v.amoled)),
             coalesce(f.requested,'{}')||coalesce(v.variables,'{}'),coalesce(f.ids,'[]'),
             jsonb_build_object('status',v.status,'pipeline',v.pipeline,'rejectionReason',v.rejection_reason,'presets',v.presets,'processingProfiles',v.processing_profiles,'conceptId',v.concept_id,'metricVersion','analytics-cohort-v1')
            from valued v left join frozen_features f on f.asset_id=v.asset_id
            """;
    return db.sql(sql).params(args).update();
  }
}
