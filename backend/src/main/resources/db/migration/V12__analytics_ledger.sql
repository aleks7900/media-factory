-- Extend the original performance ledger; historical measurements remain explicitly LEGACY.
ALTER TABLE performance_metrics ALTER COLUMN publication_id DROP NOT NULL;
ALTER TABLE performance_metrics ALTER COLUMN value TYPE numeric(28,12);
ALTER TABLE performance_metrics
 ADD COLUMN asset_id uuid REFERENCES assets,
 ADD COLUMN variant_id uuid REFERENCES asset_variants,
 ADD COLUMN platform varchar(100),
 ADD COLUMN source varchar(100) NOT NULL DEFAULT 'LEGACY',
 ADD COLUMN provenance varchar(12) NOT NULL DEFAULT 'EXTERNAL' CHECK(provenance IN ('INTERNAL','EXTERNAL')),
 ADD COLUMN currency char(3),
 ADD COLUMN deduplication_key varchar(200),
 ADD COLUMN received_at timestamptz NOT NULL DEFAULT now(),
 ADD COLUMN metadata jsonb NOT NULL DEFAULT '{}',
 ADD COLUMN correction_of uuid REFERENCES performance_metrics,
 ADD COLUMN reason text,
 ADD COLUMN created_by varchar(100) NOT NULL DEFAULT 'legacy',
 ADD COLUMN quality_status varchar(12) NOT NULL DEFAULT 'VALID' CHECK(quality_status IN ('VALID','WARNING','INVALID'));
UPDATE performance_metrics m SET asset_id=p.asset_id,platform=p.channel,quality_status='WARNING',
 metadata=jsonb_build_object('warning','Legacy measurement: cumulative/event and currency semantics unverified')
 FROM publications p WHERE p.id=m.publication_id;
CREATE UNIQUE INDEX analytics_event_dedup ON performance_metrics(source,deduplication_key) WHERE deduplication_key IS NOT NULL;
CREATE INDEX analytics_event_asset_time ON performance_metrics(asset_id,measured_at,name);
CREATE INDEX analytics_event_platform_time ON performance_metrics(platform,measured_at,name);
CREATE INDEX analytics_event_publication_time ON performance_metrics(publication_id,measured_at);
CREATE TABLE external_asset_references (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),asset_id uuid NOT NULL REFERENCES assets,
 variant_id uuid REFERENCES asset_variants,publication_id uuid REFERENCES publications,
 platform varchar(100) NOT NULL,external_id varchar(300) NOT NULL,external_url text,
 created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(platform,external_id));
CREATE INDEX analytics_reference_asset ON external_asset_references(asset_id,platform);
CREATE TABLE metric_snapshots (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),external_reference_id uuid NOT NULL REFERENCES external_asset_references,
 metric_type varchar(100) NOT NULL,value numeric(28,12) NOT NULL CHECK(value>=0),currency char(3),
 captured_at timestamptz NOT NULL,received_at timestamptz NOT NULL DEFAULT now(),source varchar(100) NOT NULL,
 deduplication_key varchar(200) NOT NULL,reset_policy varchar(20) NOT NULL DEFAULT 'UNKNOWN' CHECK(reset_policy IN ('UNKNOWN','RESET_TO_ZERO')),
 created_by varchar(100) NOT NULL,reason text NOT NULL,UNIQUE(source,deduplication_key),
 UNIQUE(external_reference_id,metric_type,captured_at));
CREATE INDEX analytics_snapshot_stream ON metric_snapshots(external_reference_id,metric_type,captured_at);
CREATE TABLE analytics_currency_rates (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),currency char(3) NOT NULL,base_currency char(3) NOT NULL,
 rate_date date NOT NULL,rate numeric(28,12) NOT NULL CHECK(rate>0),source varchar(200) NOT NULL,
 created_by varchar(100) NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(currency,base_currency,rate_date));
CREATE TABLE analytics_import_batches (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),source varchar(100) NOT NULL,platform varchar(100) NOT NULL,
 filename varchar(300) NOT NULL,checksum char(64) NOT NULL,profile jsonb NOT NULL,
 status varchar(30) NOT NULL DEFAULT 'VALIDATED',created_by varchar(100) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),completed_at timestamptz,
 rows_read int NOT NULL DEFAULT 0,rows_imported int NOT NULL DEFAULT 0,rows_skipped int NOT NULL DEFAULT 0,rows_failed int NOT NULL DEFAULT 0,
 UNIQUE(source,platform,checksum));
CREATE TABLE analytics_import_rows (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),batch_id uuid NOT NULL REFERENCES analytics_import_batches,
 row_number int NOT NULL,normalized jsonb NOT NULL,status varchar(30) NOT NULL,
 error text,event_id uuid REFERENCES performance_metrics,UNIQUE(batch_id,row_number));
CREATE FUNCTION analytics_fact_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 RAISE EXCEPTION 'Analytics facts are immutable; append a correction'; END $$;
CREATE TRIGGER analytics_event_immutable BEFORE UPDATE OR DELETE ON performance_metrics FOR EACH ROW EXECUTE FUNCTION analytics_fact_immutable();
CREATE TRIGGER analytics_snapshot_immutable BEFORE UPDATE OR DELETE ON metric_snapshots FOR EACH ROW EXECUTE FUNCTION analytics_fact_immutable();
CREATE TRIGGER analytics_rate_immutable BEFORE UPDATE OR DELETE ON analytics_currency_rates FOR EACH ROW EXECUTE FUNCTION analytics_fact_immutable();
CREATE FUNCTION analytics_lineage_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.variant_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM asset_variants v WHERE v.id=NEW.variant_id AND v.asset_id=NEW.asset_id) THEN
 RAISE EXCEPTION 'Analytics variant does not belong to asset'; END IF;
 IF NEW.publication_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM publications p WHERE p.id=NEW.publication_id AND p.asset_id=NEW.asset_id) THEN
 RAISE EXCEPTION 'Analytics publication does not belong to asset'; END IF;
 RETURN NEW; END $$;
CREATE TRIGGER analytics_event_lineage BEFORE INSERT ON performance_metrics FOR EACH ROW EXECUTE FUNCTION analytics_lineage_guard();
CREATE TRIGGER analytics_reference_lineage BEFORE INSERT OR UPDATE ON external_asset_references FOR EACH ROW EXECUTE FUNCTION analytics_lineage_guard();
-- Snapshot deltas are derived in timestamp order, so late arrivals rebuild deterministically.
-- Initial cumulative observation is not an invented increment; it is an unknown baseline.
CREATE VIEW analytics_snapshot_deltas AS
 SELECT s.*,r.asset_id,r.variant_id,r.publication_id,r.platform,
 CASE WHEN previous_value IS NULL THEN NULL WHEN value>=previous_value THEN value-previous_value
 WHEN reset_policy='RESET_TO_ZERO' THEN value ELSE NULL END AS delta,
 CASE WHEN previous_value IS NULL THEN 'BASELINE' WHEN value<previous_value AND reset_policy='UNKNOWN' THEN 'COUNTER_RESET' ELSE 'VALID' END AS quality
 FROM (SELECT m.*,lag(value) OVER(PARTITION BY external_reference_id,metric_type ORDER BY captured_at) AS previous_value FROM metric_snapshots m) s
 JOIN external_asset_references r ON r.id=s.external_reference_id;
-- One row per generation, including failed generations with no asset. No ancestor charge copies.
CREATE VIEW analytics_lineage AS
 SELECT g.id AS generation_id,a.id AS asset_id,c.id AS concept_id,col.id AS collection_id,col.project_id,
 col.name AS collection_name,g.status,g.created_at AS generated_at,g.final_provider AS provider,g.model,
 g.prompt_version_id,pv.prompt_template_id,g.experiment_id,g.experiment_variant_id,a.media_type,
 a.created_at AS asset_created_at,g.prompt_snapshot_id,
 coalesce(ps.variables->>'style',ps.variables->>'wallpaper_style') AS style,
 ps.presets AS presets,g.request_options->>'profile' AS generation_profile,
 vp.profile_version_id AS video_profile,vp.profile_snapshot->>'loopStrategy' AS loop_strategy,
 vp.motion_version_id AS motion_profile,wp.profile_key AS wallpaper_profile,
 coalesce((wp.profile_snapshot->>'amoled')::boolean,false) AS amoled,
 (wp.id IS NOT NULL) AS wallpaper,(vp.id IS NOT NULL) AS video,
 (EXISTS(SELECT 1 FROM stock_productions sp WHERE sp.generation_id=g.id)) AS stock,
 CASE WHEN vp.id IS NOT NULL THEN 'VIDEO' WHEN wp.id IS NOT NULL THEN 'WALLPAPER'
 WHEN EXISTS(SELECT 1 FROM stock_productions sp WHERE sp.generation_id=g.id) THEN 'STOCK' ELSE 'IMAGE' END AS pipeline,
 CASE WHEN wp.status='DUPLICATE_REJECTED' OR EXISTS(SELECT 1 FROM stock_productions sp WHERE sp.generation_id=g.id AND sp.status='DUPLICATE_REJECTED') THEN 'DUPLICATE'
 ELSE qf.category END AS rejection_reason,
 pp.profile_set AS processing_profiles,cm.cluster_id AS similarity_cluster
 FROM generations g JOIN concepts c ON c.id=g.concept_id JOIN collections col ON col.id=c.collection_id
 LEFT JOIN assets a ON a.generation_id=g.id LEFT JOIN prompt_versions pv ON pv.id=g.prompt_version_id
 LEFT JOIN rendered_prompt_snapshots ps ON ps.id=g.prompt_snapshot_id
 LEFT JOIN video_productions vp ON vp.generation_id=g.id
 LEFT JOIN wallpaper_productions wp ON wp.generation_id=g.id
 LEFT JOIN (SELECT DISTINCT ON(review_id) review_id,category FROM quality_findings WHERE detected
 ORDER BY review_id,CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'MAJOR' THEN 1 WHEN 'MINOR' THEN 2 ELSE 3 END,code,id) qf ON qf.review_id=a.current_review_id
 LEFT JOIN (SELECT asset_id,string_agg(DISTINCT profile_version_id::text,',' ORDER BY profile_version_id::text) AS profile_set FROM asset_variants GROUP BY asset_id) pp ON pp.asset_id=a.id
 LEFT JOIN (SELECT DISTINCT ON(m.asset_id) m.asset_id,m.cluster_id FROM collection_cluster_members m
 JOIN collection_clusters cc ON cc.id=m.cluster_id JOIN collection_clustering_runs cr ON cr.id=cc.run_id
 JOIN (SELECT DISTINCT ON(collection_id) id FROM collection_clustering_runs ORDER BY collection_id,created_at DESC,id) latest ON latest.id=cr.id
 ORDER BY m.asset_id,m.cluster_id) cm ON cm.asset_id=a.id;
CREATE INDEX analytics_stock_generation ON stock_productions(generation_id);
CREATE VIEW analytics_cost_facts AS
 SELECT c.id,c.generation_id,coalesce(c.asset_id,a.id) AS asset_id,c.provider,c.model,c.operation,
 coalesce(c.actual_cost,c.estimated_cost) AS amount,c.currency,c.created_at AS occurred_at,
 CASE WHEN c.actual_cost IS NOT NULL THEN 'ACTUAL' ELSE c.pricing_status END AS pricing_status,c.outcome
 FROM generation_costs c LEFT JOIN assets a ON a.generation_id=c.generation_id;
CREATE TABLE analytics_cost_allocations (
 cost_id uuid NOT NULL REFERENCES generation_costs,asset_id uuid NOT NULL REFERENCES assets,
 amount numeric(28,12) NOT NULL CHECK(amount>=0),weight numeric(28,12) NOT NULL CHECK(weight>=0),
 policy varchar(20) NOT NULL CHECK(policy IN ('DIRECT','EQUAL_SPLIT','WEIGHTED')),
 reason text NOT NULL,created_by varchar(100) NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(cost_id,asset_id));
CREATE TRIGGER analytics_allocation_immutable BEFORE UPDATE OR DELETE ON analytics_cost_allocations FOR EACH ROW EXECUTE FUNCTION analytics_fact_immutable();
CREATE FUNCTION analytics_allocation_total_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF (SELECT sum(amount) FROM analytics_cost_allocations WHERE cost_id=NEW.cost_id)
 IS DISTINCT FROM (SELECT coalesce(actual_cost,estimated_cost) FROM generation_costs WHERE id=NEW.cost_id) THEN
 RAISE EXCEPTION 'Allocation sum must equal the original cost'; END IF; RETURN NEW; END $$;
CREATE CONSTRAINT TRIGGER analytics_allocation_total AFTER INSERT ON analytics_cost_allocations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION analytics_allocation_total_guard();
CREATE FUNCTION analytics_allocated_cost_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF EXISTS(SELECT 1 FROM analytics_cost_allocations WHERE cost_id=NEW.id) AND
 (coalesce(NEW.actual_cost,NEW.estimated_cost) IS DISTINCT FROM coalesce(OLD.actual_cost,OLD.estimated_cost) OR NEW.currency<>OLD.currency) THEN
 RAISE EXCEPTION 'Allocated cost amount/currency is finalized'; END IF; RETURN NEW; END $$;
CREATE TRIGGER analytics_allocated_cost BEFORE UPDATE ON generation_costs FOR EACH ROW EXECUTE FUNCTION analytics_allocated_cost_guard();
CREATE VIEW analytics_attributed_costs AS
 SELECT c.* FROM analytics_cost_facts c WHERE NOT EXISTS(SELECT 1 FROM analytics_cost_allocations x WHERE x.cost_id=c.id)
 UNION ALL
 SELECT c.id,a.generation_id,x.asset_id,c.provider,c.model,c.operation,x.amount,c.currency,c.occurred_at,c.pricing_status,c.outcome
 FROM analytics_cost_allocations x JOIN analytics_cost_facts c ON c.id=x.cost_id JOIN assets a ON a.id=x.asset_id;
CREATE VIEW analytics_normalized_metrics AS
 SELECT id,asset_id,variant_id,publication_id,platform,name AS metric_type,value,currency,measured_at AS occurred_at,source
 FROM performance_metrics WHERE source<>'LEGACY' AND quality_status<>'INVALID'
 UNION ALL
 SELECT id,asset_id,variant_id,publication_id,platform,metric_type,delta,currency,captured_at,source
 FROM analytics_snapshot_deltas WHERE delta IS NOT NULL;
CREATE MATERIALIZED VIEW analytics_daily_aggregate AS
 SELECT (occurred_at AT TIME ZONE 'UTC')::date AS day,asset_id,publication_id,platform,metric_type,currency,variant_id,
 sum(value) AS value,count(*) AS fact_count
 FROM analytics_normalized_metrics GROUP BY 1,2,3,4,5,6,7;
CREATE UNIQUE INDEX analytics_daily_identity ON analytics_daily_aggregate(day,asset_id,publication_id,platform,metric_type,currency,variant_id) NULLS NOT DISTINCT;
CREATE INDEX analytics_daily_asset ON analytics_daily_aggregate(asset_id,day);
CREATE TABLE analytics_rebuild_runs (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),started_at timestamptz NOT NULL DEFAULT now(),completed_at timestamptz,
 status varchar(20) NOT NULL DEFAULT 'RUNNING',reason text NOT NULL,created_by varchar(100) NOT NULL,failure_reason text);
-- Same durable lease/retry pattern as processing and QA jobs. Foundation jobs require a
-- unique generation FK, which cannot represent cross-project analytics work.
CREATE TABLE analytics_jobs (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),type varchar(20) NOT NULL CHECK(type IN ('IMPORT','NORMALIZE','AGGREGATE','REBUILD','RECONCILE')),
 payload jsonb NOT NULL,idempotency_key varchar(200) NOT NULL UNIQUE,status varchar(20) NOT NULL DEFAULT 'QUEUED',
 attempts int NOT NULL DEFAULT 0,max_attempts int NOT NULL DEFAULT 3,lease_token uuid,lease_until timestamptz,
 available_at timestamptz NOT NULL DEFAULT now(),created_at timestamptz NOT NULL DEFAULT now(),completed_at timestamptz,
 failure_reason text,result jsonb);
CREATE INDEX analytics_job_claim ON analytics_jobs(status,available_at);
