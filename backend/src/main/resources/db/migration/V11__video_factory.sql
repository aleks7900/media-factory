-- Video orchestration references existing generations/assets/costs and extends shared rate limits.
CREATE TABLE video_profile_versions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), profile_key varchar(80) NOT NULL, version int NOT NULL,
 definition jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(profile_key,version)
);
CREATE TABLE video_productions (
 id uuid PRIMARY KEY, source_asset_id uuid NOT NULL REFERENCES assets, source_checksum char(64) NOT NULL,
 generation_id uuid NOT NULL UNIQUE REFERENCES generations, parent_production_id uuid REFERENCES video_productions,
 profile_version_id uuid NOT NULL REFERENCES video_profile_versions, profile_snapshot jsonb NOT NULL,
 motion_version_id uuid, raw_asset_id uuid UNIQUE REFERENCES assets, master_variant_id uuid REFERENCES asset_variants,
 current_run_id uuid, current_attempt_id uuid, status varchar(40) NOT NULL DEFAULT 'DRAFT',
 paused boolean NOT NULL DEFAULT false, revision int NOT NULL DEFAULT 0, attempt_count int NOT NULL DEFAULT 0,
 route jsonb NOT NULL, route_index int NOT NULL DEFAULT 0, prompt_snapshot jsonb NOT NULL DEFAULT '{}',
 max_attempts int NOT NULL DEFAULT 3 CHECK(max_attempts BETWEEN 1 AND 10), budget numeric NOT NULL DEFAULT 0 CHECK(budget>=0),
 idempotency_key varchar(200) NOT NULL UNIQUE, request_hash char(64) NOT NULL,
 lease_token uuid, lease_until timestamptz, available_at timestamptz NOT NULL DEFAULT now(), failure_code varchar(160),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), approved_at timestamptz, approved_by varchar(100),
 CHECK(status IN ('DRAFT','MOTION_PLAN_READY','GENERATION_QUEUED','GENERATING','SUBMISSION_UNKNOWN','RAW_READY','TECHNICAL_VALIDATION','QA_PENDING','PROCESSING','LOOP_ANALYSIS','VARIANTS_GENERATING','REVIEW','APPROVED','READY','GENERATION_FAILED','VALIDATION_FAILED','QA_REJECTED','PROCESSING_FAILED','LOOP_FAILED','CANCELLED'))
);
CREATE INDEX video_production_work ON video_productions(status,available_at,lease_until);
CREATE INDEX video_production_source ON video_productions(source_asset_id);
CREATE TABLE motion_plan_versions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),production_id uuid NOT NULL REFERENCES video_productions ON DELETE CASCADE,
 version int NOT NULL, definition jsonb NOT NULL, source_context jsonb NOT NULL, created_by varchar(100) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(production_id,version)
);
ALTER TABLE video_productions ADD FOREIGN KEY(motion_version_id) REFERENCES motion_plan_versions;
CREATE TABLE video_generation_attempts (
 id uuid PRIMARY KEY, production_id uuid NOT NULL REFERENCES video_productions ON DELETE CASCADE,
 generation_id uuid NOT NULL REFERENCES generations, attempt int NOT NULL, provider varchar(80) NOT NULL, model varchar(120) NOT NULL,
 provider_job_id varchar(200), status varchar(40) NOT NULL DEFAULT 'REQUESTED', request_snapshot jsonb NOT NULL,
 provider_prompt jsonb NOT NULL DEFAULT '{}', provider_metadata jsonb NOT NULL DEFAULT '{}', cost_id uuid NOT NULL UNIQUE REFERENCES generation_costs,
 fallback boolean NOT NULL DEFAULT false, submit_failures int NOT NULL DEFAULT 0, poll_attempts int NOT NULL DEFAULT 0, download_attempts int NOT NULL DEFAULT 0,
 deadline_at timestamptz NOT NULL DEFAULT now()+interval '15 minutes',
 next_poll_at timestamptz NOT NULL DEFAULT now(), submitted_at timestamptz, started_at timestamptz, completed_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), duration_ms bigint, error_type varchar(50), error_code varchar(160),
 retryable boolean NOT NULL DEFAULT false, recovery_required boolean NOT NULL DEFAULT false,
 UNIQUE(production_id,attempt), UNIQUE(provider,provider_job_id),
 CHECK(status IN ('REQUESTED','SUBMITTING','SUBMITTED','SUBMISSION_UNKNOWN','PROVIDER_QUEUED','PROVIDER_PROCESSING','DOWNLOADING','GENERATED','PROVIDER_FAILED','PROVIDER_REJECTED','TIMED_OUT','DOWNLOAD_FAILED','CANCELLED'))
);
ALTER TABLE video_productions ADD FOREIGN KEY(current_attempt_id) REFERENCES video_generation_attempts;
CREATE INDEX video_attempt_remote ON video_generation_attempts(provider,status,next_poll_at);
CREATE TABLE video_provider_events(provider varchar(80) NOT NULL,event_id varchar(200) NOT NULL,provider_job_id varchar(200) NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(provider,event_id));
ALTER TABLE provider_permits ADD COLUMN video_attempt_id uuid UNIQUE REFERENCES video_generation_attempts ON DELETE CASCADE;
ALTER TABLE provider_permits DROP CONSTRAINT permit_owner;
ALTER TABLE provider_permits ADD CONSTRAINT permit_owner CHECK(num_nonnulls(job_id,qa_job_id,similarity_job_id,video_attempt_id)=1);
CREATE TABLE video_processing_runs (
 id uuid PRIMARY KEY, production_id uuid NOT NULL REFERENCES video_productions ON DELETE CASCADE,
 raw_asset_id uuid NOT NULL REFERENCES assets, motion_version_id uuid NOT NULL REFERENCES motion_plan_versions,
 version int NOT NULL, parameters jsonb NOT NULL, variants jsonb NOT NULL, request_hash char(64) NOT NULL,
 idempotency_key varchar(200) NOT NULL UNIQUE, status varchar(24) NOT NULL DEFAULT 'QUEUED',
 attempt int NOT NULL DEFAULT 0, lease_token uuid, lease_until timestamptz, failure_code varchar(160),
 evidence jsonb NOT NULL DEFAULT '{}', ffmpeg_version varchar(400), duration_ms bigint, input_bytes bigint, output_bytes bigint,
 started_at timestamptz,completed_at timestamptz,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(production_id,version), CHECK(status IN ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED'))
);
ALTER TABLE video_productions ADD FOREIGN KEY(current_run_id) REFERENCES video_processing_runs;
ALTER TABLE asset_variants ADD COLUMN video_processing_run_id uuid REFERENCES video_processing_runs,
 ADD COLUMN video_parent_variant_id uuid REFERENCES asset_variants;
CREATE UNIQUE INDEX video_variant_run_kind ON asset_variants(video_processing_run_id,kind) WHERE video_processing_run_id IS NOT NULL;
CREATE TABLE video_processing_operations (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),run_id uuid NOT NULL REFERENCES video_processing_runs ON DELETE CASCADE,
 sequence int NOT NULL,type varchar(50) NOT NULL,parameters jsonb NOT NULL,filter_graph text NOT NULL,
 encoder varchar(80),device varchar(30),duration_ms bigint,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(run_id,sequence)
);
CREATE TABLE video_quality_results (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),production_id uuid NOT NULL REFERENCES video_productions ON DELETE CASCADE,
 run_id uuid REFERENCES video_processing_runs, asset_id uuid NOT NULL REFERENCES assets, evidence jsonb NOT NULL,
 type varchar(30) NOT NULL,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE video_loop_analysis (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),run_id uuid NOT NULL REFERENCES video_processing_runs ON DELETE CASCADE,
 phase varchar(20) NOT NULL,evidence jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(run_id,phase)
);
CREATE TABLE video_media_metadata (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),asset_id uuid UNIQUE REFERENCES assets,variant_id uuid UNIQUE REFERENCES asset_variants,
 metadata jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),CHECK(num_nonnulls(asset_id,variant_id)=1)
);
CREATE TABLE video_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),production_id uuid NOT NULL REFERENCES video_productions ON DELETE CASCADE,
 action varchar(80) NOT NULL, details jsonb NOT NULL DEFAULT '{}',actor varchar(100) NOT NULL DEFAULT 'worker',created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE video_frame_reviews (
 id uuid PRIMARY KEY,production_id uuid NOT NULL REFERENCES video_productions ON DELETE CASCADE,
 frame_index int NOT NULL,time_seconds numeric NOT NULL,cost_id uuid UNIQUE REFERENCES generation_costs,
 status varchar(20) NOT NULL CHECK(status IN ('STARTED','COMPLETED','FAILED')),
 provider varchar(80) NOT NULL,model varchar(120) NOT NULL,evidence jsonb NOT NULL DEFAULT '{}',
 created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(production_id,frame_index)
);
CREATE TABLE video_collection_plans (
 collection_id uuid PRIMARY KEY REFERENCES collections ON DELETE CASCADE,profile_key varchar(80) NOT NULL,
 target_approved int NOT NULL CHECK(target_approved BETWEEN 1 AND 1000),batch_size int NOT NULL CHECK(batch_size BETWEEN 1 AND 20),
 max_attempts int NOT NULL CHECK(max_attempts BETWEEN 1 AND 2000),budget numeric NOT NULL CHECK(budget>=0),
 provider varchar(80),reserved_cost_per_video numeric NOT NULL DEFAULT 0 CHECK(reserved_cost_per_video>=0),status varchar(20) NOT NULL DEFAULT 'RUNNING',revision int NOT NULL DEFAULT 0,
 failure_reason varchar(160),created_at timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION video_history_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Video history is immutable'; END $$;
CREATE TRIGGER video_profile_immutable BEFORE UPDATE ON video_profile_versions FOR EACH ROW EXECUTE FUNCTION video_history_immutable();
CREATE TRIGGER motion_version_immutable BEFORE UPDATE ON motion_plan_versions FOR EACH ROW EXECUTE FUNCTION video_history_immutable();
CREATE TRIGGER video_operation_immutable BEFORE UPDATE ON video_processing_operations FOR EACH ROW EXECUTE FUNCTION video_history_immutable();
CREATE TRIGGER video_quality_immutable BEFORE UPDATE ON video_quality_results FOR EACH ROW EXECUTE FUNCTION video_history_immutable();
CREATE TRIGGER video_loop_immutable BEFORE UPDATE ON video_loop_analysis FOR EACH ROW EXECUTE FUNCTION video_history_immutable();
CREATE TRIGGER video_metadata_immutable BEFORE UPDATE ON video_media_metadata FOR EACH ROW EXECUTE FUNCTION video_history_immutable();
CREATE TRIGGER video_event_immutable BEFORE UPDATE ON video_events FOR EACH ROW EXECUTE FUNCTION video_history_immutable();
CREATE FUNCTION video_completed_run_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF OLD.status='COMPLETED' THEN RAISE EXCEPTION 'Completed video run is immutable'; END IF; RETURN NEW; END $$;
CREATE TRIGGER video_run_immutable BEFORE UPDATE ON video_processing_runs FOR EACH ROW EXECUTE FUNCTION video_completed_run_guard();
CREATE FUNCTION video_variant_lineage_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.video_processing_run_id IS NOT NULL THEN
  IF NOT EXISTS(SELECT 1 FROM video_processing_runs r WHERE r.id=NEW.video_processing_run_id AND r.raw_asset_id=NEW.asset_id) THEN RAISE EXCEPTION 'Video variant raw lineage mismatch'; END IF;
  IF NEW.video_parent_variant_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM asset_variants p WHERE p.id=NEW.video_parent_variant_id AND p.asset_id=NEW.asset_id) THEN RAISE EXCEPTION 'Video parent lineage mismatch'; END IF;
 END IF; RETURN NEW; END $$;
CREATE TRIGGER video_variant_lineage BEFORE INSERT ON asset_variants FOR EACH ROW EXECUTE FUNCTION video_variant_lineage_guard();
INSERT INTO prompt_templates(id,key,name,category) VALUES
 ('00000000-0000-0000-0000-000000000901','VIDEO_IMAGE_TO_VIDEO','Image to video motion','video'),
 ('00000000-0000-0000-0000-000000000903','VIDEO_WALLPAPER_LOOP','Subtle wallpaper animation','video'),
 ('00000000-0000-0000-0000-000000000905','VIDEO_CINEMATIC','Cinematic motion','video'),
 ('00000000-0000-0000-0000-000000000907','VIDEO_SOCIAL','Social motion','video');
INSERT INTO prompt_versions(id,prompt_template_id,version,positive_template,negative_template)
SELECT (left(id::text,35)||(right(id::text,1)::int+1)::text)::uuid,id,1,
 'Animate the supplied image while preserving its subject identity and composition. Subject: {{main_subject}}. Subject motion: {{subject_motion}}. Environment: {{environment_motion}}. Camera: {{camera_motion}}. Strength: {{motion_strength}}. Speed: {{motion_speed}}. Loop intent: {{loop_intent}}. Style: {{style}}. Continuous shot, coherent geometry and stable lighting.',
 '' FROM prompt_templates WHERE key IN ('VIDEO_IMAGE_TO_VIDEO','VIDEO_WALLPAPER_LOOP','VIDEO_CINEMATIC','VIDEO_SOCIAL');
INSERT INTO prompt_variable_definitions(id,prompt_version_id,name,label,type,required,max_length)
SELECT gen_random_uuid(),v.id,n,n,'STRING',true,1200 FROM prompt_versions v JOIN prompt_templates t ON t.id=v.prompt_template_id
 CROSS JOIN unnest(ARRAY['main_subject','subject_motion','environment_motion','camera_motion','motion_strength','motion_speed','loop_intent','style']) n
 WHERE t.key IN ('VIDEO_IMAGE_TO_VIDEO','VIDEO_WALLPAPER_LOOP','VIDEO_CINEMATIC','VIDEO_SOCIAL');
UPDATE prompt_versions SET status='PUBLISHED',published_at=now(),published_by='migration' WHERE id IN
 ('00000000-0000-0000-0000-000000000902','00000000-0000-0000-0000-000000000904','00000000-0000-0000-0000-000000000906','00000000-0000-0000-0000-000000000908');
INSERT INTO video_profile_versions(profile_key,version,definition)
SELECT k,1,jsonb_build_object('enabled',true,'width',CASE WHEN k='SOCIAL_HORIZONTAL' THEN 1280 WHEN k='SOCIAL_SQUARE' THEN 960 ELSE 720 END,
 'height',CASE WHEN k='SOCIAL_HORIZONTAL' THEN 720 WHEN k='SOCIAL_SQUARE' THEN 960 ELSE 1280 END,'fps',24,'duration',5,'codec','H264','quality',18,
 'audioPolicy','REMOVE','encodingMode','CPU','cropMode','FIT','focalX',0.5,'focalY',0.5,'loopStrategy',CASE WHEN k LIKE '%LOOP' THEN 'AUTO' ELSE 'DIRECT' END,
 'crossfadeSeconds',0.5,'directThreshold',0.90,'crossfadeThreshold',0.45,'minimumLoopScore',0.8,'maximumDuration',30,'maximumBytes',134217728,
 'stabilize',false,'interpolate',false,'denoise',0,'sharpen',0,'saturation',1,'targetBitrate',6000000,'bitrateMode','QUALITY',
 'promptVersion',CASE WHEN k='WALLPAPER_LOOP' THEN '00000000-0000-0000-0000-000000000904' WHEN k='CINEMATIC_LOOP' THEN '00000000-0000-0000-0000-000000000906' WHEN k LIKE 'SOCIAL_%' THEN '00000000-0000-0000-0000-000000000908' ELSE '00000000-0000-0000-0000-000000000902' END,
 'variants','{"ANDROID_VIDEO_FHD":{"width":1080,"height":1920,"fps":30,"quality":22,"cropMode":"FILL"},"ANDROID_VIDEO_GENERIC":{"width":720,"height":1280,"fps":24,"quality":24,"cropMode":"FILL"},"SOCIAL_VERTICAL":{"width":1080,"height":1920,"fps":30,"quality":22,"cropMode":"FILL"},"SOCIAL_HORIZONTAL":{"width":1920,"height":1080,"fps":30,"quality":22,"cropMode":"FILL"},"SOCIAL_SQUARE":{"width":1080,"height":1080,"fps":30,"quality":22,"cropMode":"FILL"},"VIDEO_PREVIEW":{"width":360,"height":640,"fps":24,"quality":28,"cropMode":"FIT"}}'::jsonb)
FROM unnest(ARRAY['WALLPAPER_LOOP','CINEMATIC_LOOP','SOCIAL_VERTICAL','SOCIAL_HORIZONTAL','SOCIAL_SQUARE','GENERIC_VIDEO']) k;
