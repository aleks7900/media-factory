-- Preserve the populated derived data while allowing transactional scoped replacement.
ALTER MATERIALIZED VIEW analytics_daily_aggregate RENAME TO analytics_daily_aggregate_previous;
CREATE TABLE analytics_daily_aggregate AS SELECT * FROM analytics_daily_aggregate_previous;
DROP MATERIALIZED VIEW analytics_daily_aggregate_previous;
ALTER TABLE analytics_daily_aggregate ADD COLUMN calculated_at timestamptz NOT NULL DEFAULT now();
CREATE UNIQUE INDEX analytics_daily_identity ON analytics_daily_aggregate(day,asset_id,publication_id,platform,metric_type,currency,variant_id) NULLS NOT DISTINCT;
CREATE INDEX analytics_daily_asset ON analytics_daily_aggregate(asset_id,day);
ALTER TABLE analytics_rebuild_runs ADD COLUMN scope jsonb NOT NULL DEFAULT '{}';
CREATE INDEX analytics_video_processing_asset ON video_processing_runs(raw_asset_id,status);
CREATE INDEX analytics_publication_asset_time ON publications(asset_id,published_at);
