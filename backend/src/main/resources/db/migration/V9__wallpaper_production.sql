-- Wallpaper orchestration references the existing generation, QA, similarity and processing records.
ALTER TABLE collections ADD COLUMN wallpaper boolean NOT NULL DEFAULT false,
 ADD COLUMN slug varchar(160), ADD COLUMN description text NOT NULL DEFAULT '',
 ADD COLUMN theme varchar(300) NOT NULL DEFAULT '', ADD COLUMN style varchar(200) NOT NULL DEFAULT '',
 ADD COLUMN amoled boolean NOT NULL DEFAULT false, ADD COLUMN wallpaper_status varchar(24) NOT NULL DEFAULT 'DRAFT',
 ADD COLUMN cover_asset_id uuid REFERENCES assets, ADD COLUMN sort_order integer NOT NULL DEFAULT 0,
 ADD COLUMN featured boolean NOT NULL DEFAULT false, ADD COLUMN wallpaper_revision integer NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX wallpaper_collection_slug ON collections(slug) WHERE wallpaper;
ALTER TABLE collections ADD CONSTRAINT wallpaper_collection_status CHECK(wallpaper_status IN ('DRAFT','GENERATING','REVIEW','READY','PUBLISHED','ARCHIVED'));

CREATE TABLE wallpaper_safe_zones(key varchar(80) PRIMARY KEY, top_fraction numeric NOT NULL CHECK(top_fraction BETWEEN 0 AND .8), bottom_fraction numeric NOT NULL CHECK(bottom_fraction BETWEEN 0 AND .8), CHECK(top_fraction+bottom_fraction<1));
INSERT INTO wallpaper_safe_zones VALUES ('ANDROID_HOME',.12,.12),('ANDROID_LOCK',.28,.10);
CREATE TABLE wallpaper_device_profiles (
 key varchar(80) PRIMARY KEY, width integer NOT NULL CHECK(width BETWEEN 64 AND 8192),
 height integer NOT NULL CHECK(height BETWEEN 64 AND 8192), format varchar(10) NOT NULL CHECK(format IN ('JPEG','PNG','WEBP')),
 quality integer NOT NULL CHECK(quality BETWEEN 1 AND 100), crop_mode varchar(20) NOT NULL,
 safe_zone_profile varchar(80) REFERENCES wallpaper_safe_zones, quality_tier varchar(20) NOT NULL,
 enabled boolean NOT NULL DEFAULT true, processing_profile varchar(80) NOT NULL REFERENCES processing_profiles(key)
);
INSERT INTO processing_profiles(key,name) VALUES
 ('WALLPAPER_MASTER','Lossless wallpaper master'),('ANDROID_FHD_PORTRAIT','Android FHD'),
 ('ANDROID_QHD_PORTRAIT','Android QHD'),('ANDROID_TALL_PORTRAIT','Android tall'),
 ('ANDROID_GENERIC_PORTRAIT','Android fallback'),('ANDROID_PREVIEW','Android preview medium'),
 ('ANDROID_PREVIEW_SMALL','Android preview small'),('ANDROID_THUMBNAIL','Android thumbnail'),
 ('ANDROID_LOCK_SCREEN','Android lock screen'),('ANDROID_HOME_SCREEN','Android home screen'),
 ('COLLECTION_COVER','Collection cover'),('COLLECTION_CARD','Collection card'),('COLLECTION_THUMBNAIL','Collection thumbnail');
INSERT INTO wallpaper_device_profiles VALUES
 ('ANDROID_FHD_PORTRAIT',1080,2400,'JPEG',95,'SMART_FILL','ANDROID_HOME','STANDARD',true,'ANDROID_FHD_PORTRAIT'),
 ('ANDROID_QHD_PORTRAIT',1440,3200,'JPEG',95,'SMART_FILL','ANDROID_HOME','PREMIUM',true,'ANDROID_QHD_PORTRAIT'),
 ('ANDROID_TALL_PORTRAIT',1080,2520,'JPEG',95,'SMART_FILL','ANDROID_HOME','STANDARD',true,'ANDROID_TALL_PORTRAIT'),
 ('ANDROID_GENERIC_PORTRAIT',1080,1920,'JPEG',95,'SMART_FILL','ANDROID_HOME','STANDARD',true,'ANDROID_GENERIC_PORTRAIT'),
 ('ANDROID_PREVIEW',540,1200,'WEBP',85,'FIT',null,'PREVIEW',true,'ANDROID_PREVIEW'),
 ('ANDROID_PREVIEW_SMALL',270,600,'WEBP',80,'FIT',null,'PREVIEW',true,'ANDROID_PREVIEW_SMALL'),
 ('ANDROID_THUMBNAIL',180,400,'WEBP',80,'FIT',null,'THUMBNAIL',true,'ANDROID_THUMBNAIL'),
 ('ANDROID_LOCK_SCREEN',1440,3200,'JPEG',95,'SMART_FILL','ANDROID_LOCK','PREMIUM',true,'ANDROID_LOCK_SCREEN'),
 ('ANDROID_HOME_SCREEN',1440,3200,'JPEG',95,'SMART_FILL','ANDROID_HOME','PREMIUM',true,'ANDROID_HOME_SCREEN');
INSERT INTO processing_profile_versions(profile_id,version,status,definition,published_at)
 SELECT p.id,1,'PUBLISHED',jsonb_build_object('width',d.width,'height',d.height,'format',d.format,'mode',d.crop_mode,
 'quality',d.quality,'minimumQuality',least(80,d.quality),'maxBytes',CASE WHEN d.quality_tier IN ('PREVIEW','THUMBNAIL') THEN 1048576 ELSE 20971520 END,
 'safeTop',coalesce(z.top_fraction,0),'safeBottom',coalesce(z.bottom_fraction,0),'padding',.1,'background','BLACK',
 'unsafeCrop','LETTERBOX','sharpen',.10,'colorSpace','sRGB','metadataPolicy','PUBLIC_STRIP'),now()
 FROM wallpaper_device_profiles d JOIN processing_profiles p ON p.key=d.processing_profile LEFT JOIN wallpaper_safe_zones z ON z.key=d.safe_zone_profile;
INSERT INTO processing_profile_versions(profile_id,version,status,definition,published_at)
 SELECT id,1,'PUBLISHED','{"width":2160,"height":4800,"minimumWidth":2160,"minimumHeight":4800,"mode":"PRESERVE","format":"PNG","quality":100,"minimumQuality":100,"maxBytes":67108864,"sharpen":0,"denoise":0,"colorSpace":"sRGB","metadataPolicy":"PUBLIC_STRIP"}',now() FROM processing_profiles WHERE key='WALLPAPER_MASTER';
INSERT INTO processing_profile_versions(profile_id,version,status,definition,published_at)
 SELECT id,1,'PUBLISHED',jsonb_build_object('width',CASE key WHEN 'COLLECTION_COVER' THEN 1600 WHEN 'COLLECTION_CARD' THEN 800 ELSE 240 END,
 'height',CASE key WHEN 'COLLECTION_COVER' THEN 900 WHEN 'COLLECTION_CARD' THEN 450 ELSE 135 END,
 'mode','SMART_FILL','format','WEBP','quality',90,'minimumQuality',80,'maxBytes',2097152,'unsafeCrop','LETTERBOX','background','BLACK','padding',.1),now()
 FROM processing_profiles WHERE key IN ('COLLECTION_COVER','COLLECTION_CARD','COLLECTION_THUMBNAIL');

INSERT INTO prompt_templates(id,key,name,category) VALUES ('00000000-0000-0000-0000-000000000701','wallpaper-production','Wallpaper production','WALLPAPER');
INSERT INTO prompt_versions(id,prompt_template_id,version,positive_template,negative_template) VALUES
 ('00000000-0000-0000-0000-000000000702','00000000-0000-0000-0000-000000000701',1,
 '{{subject}}. Collection theme: {{wallpaper_collectionTheme}}. Style: {{wallpaper_style}}. {{wallpaper_orientation}} composition, aspect {{wallpaper_aspectRatio}}. Clear focal subject placed {{wallpaper_subjectPlacement}}, away from edges. Reserve top {{wallpaper_safeZoneTop}} and bottom {{wallpaper_safeZoneBottom}} as quiet negative space for interface readability. {{wallpaper_background}}. High detail, coherent lighting, wallpaper-friendly composition.',
 'accidental text, lettering, watermark, clock, notification graphics, launcher interface, cropped subject, malformed anatomy');
INSERT INTO prompt_variable_definitions(id,prompt_version_id,name,label,type,required,max_length,display_order)
 SELECT gen_random_uuid(),'00000000-0000-0000-0000-000000000702',name,name,'STRING',true,4000,ordinal::int
 FROM unnest(ARRAY['subject','wallpaper_collectionTheme','wallpaper_style','wallpaper_orientation','wallpaper_aspectRatio','wallpaper_subjectPlacement','wallpaper_safeZoneTop','wallpaper_safeZoneBottom','wallpaper_background']) WITH ORDINALITY AS vars(name,ordinal);
UPDATE prompt_versions SET status='PUBLISHED',published_at=now(),published_by='migration' WHERE id='00000000-0000-0000-0000-000000000702';

CREATE TABLE wallpaper_profiles(key varchar(80) PRIMARY KEY, version integer NOT NULL DEFAULT 1, definition jsonb NOT NULL);
INSERT INTO wallpaper_profiles(key,definition) SELECT key,jsonb_build_object(
 'promptVersionId','00000000-0000-0000-0000-000000000702','generationWidth',1024,'generationHeight',2048,
 'qaPolicy','wallpaper-standard','similarityProfile','WALLPAPER','amoled',key='ANDROID_AMOLED',
 'safeZoneTop',CASE WHEN key='ANDROID_LOCK_SCREEN' THEN .28 ELSE .12 END,'safeZoneBottom',.12,
 'subjectPlacement','CENTER_LOWER','humanApprovalRequired',true,'publicationTarget','EXPORT',
 'processingProfiles',to_jsonb(ARRAY['WALLPAPER_MASTER','ANDROID_FHD_PORTRAIT','ANDROID_QHD_PORTRAIT','ANDROID_GENERIC_PORTRAIT','ANDROID_PREVIEW','ANDROID_THUMBNAIL']
 || CASE WHEN key='ANDROID_LOCK_SCREEN' THEN ARRAY['ANDROID_LOCK_SCREEN'] WHEN key='ANDROID_HOME_SCREEN' THEN ARRAY['ANDROID_HOME_SCREEN'] WHEN key='ANDROID_PREMIUM' THEN ARRAY['ANDROID_TALL_PORTRAIT'] ELSE ARRAY[]::text[] END),
 'amoledPolicy','{"blackMaximum":0.003,"nearBlackMaximum":0.015,"brightMinimum":0.6,"minimumBlackRatio":0.5,"minimumNearBlackRatio":0.7,"maximumBrightRatio":0.15,"minimumHighlightCoverage":0.005}'::jsonb)
 FROM unnest(ARRAY['ANDROID_STANDARD','ANDROID_PREMIUM','ANDROID_AMOLED','ANDROID_LOCK_SCREEN','ANDROID_HOME_SCREEN']) AS keys(key);

CREATE TABLE wallpaper_productions (
 id uuid PRIMARY KEY, concept_id uuid NOT NULL REFERENCES concepts, parent_id uuid REFERENCES wallpaper_productions,
 generation_id uuid UNIQUE REFERENCES generations, master_asset_id uuid REFERENCES assets,
 master_variant_id uuid REFERENCES asset_variants, processing_run_id uuid REFERENCES processing_runs,
 status varchar(40) NOT NULL DEFAULT 'CONCEPT_READY', previous_status varchar(40),
 profile_key varchar(80) NOT NULL REFERENCES wallpaper_profiles, profile_snapshot jsonb NOT NULL,
 metadata jsonb NOT NULL, amoled_result jsonb, similarity_model_id uuid REFERENCES embedding_models,
 request_key varchar(200) UNIQUE NOT NULL, request_hash char(64) NOT NULL,
 revision integer NOT NULL DEFAULT 0, failure_code varchar(100), approved_by varchar(200), approved_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz,
 lease_token uuid, lease_until timestamptz,
 CHECK(status IN ('CONCEPT_READY','GENERATING','QA_PENDING','QA_APPROVED','SIMILARITY_CHECK','PROCESSING','PUBLICATION_REVIEW','APPROVED_FOR_PUBLICATION','PUBLISHING','PUBLISHED','QA_REJECTED','DUPLICATE_REJECTED','AMOLED_REJECTED','PROCESSING_FAILED','GENERATION_FAILED','PUBLICATION_FAILED','PAUSED','CANCELLED','UNPUBLISHED','REJECTED'))
);
CREATE INDEX wallpaper_productions_status ON wallpaper_productions(status,created_at);
CREATE TABLE wallpaper_production_events(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),production_id uuid NOT NULL REFERENCES wallpaper_productions,
 from_status varchar(40),to_status varchar(40) NOT NULL,actor varchar(200) NOT NULL,reason varchar(2000) NOT NULL DEFAULT '',created_at timestamptz NOT NULL DEFAULT now());
CREATE TRIGGER immutable_wallpaper_events BEFORE UPDATE OR DELETE ON wallpaper_production_events FOR EACH ROW EXECUTE FUNCTION immutable_review_action();
CREATE FUNCTION protect_wallpaper_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF (NEW.concept_id,NEW.profile_key,NEW.profile_snapshot,NEW.request_key,NEW.request_hash,NEW.parent_id) IS DISTINCT FROM (OLD.concept_id,OLD.profile_key,OLD.profile_snapshot,OLD.request_key,OLD.request_hash,OLD.parent_id) THEN RAISE EXCEPTION 'Wallpaper production identity is immutable'; END IF;
 RETURN NEW; END $$;
CREATE TRIGGER wallpaper_snapshot_immutable BEFORE UPDATE ON wallpaper_productions FOR EACH ROW EXECUTE FUNCTION protect_wallpaper_snapshot();

CREATE TABLE wallpaper_collection_plans(collection_id uuid PRIMARY KEY REFERENCES collections,
 target_approved integer NOT NULL CHECK(target_approved BETWEEN 1 AND 1000),batch_size integer NOT NULL CHECK(batch_size BETWEEN 1 AND 20),
 max_attempts integer NOT NULL CHECK(max_attempts BETWEEN 1 AND 2000),max_cost numeric(18,8) NOT NULL CHECK(max_cost>=0),
 currency varchar(3) NOT NULL DEFAULT 'USD',reserved_cost_per_attempt numeric(18,8) NOT NULL CHECK(reserved_cost_per_attempt>=0),
 profile_key varchar(80) NOT NULL REFERENCES wallpaper_profiles,status varchar(30) NOT NULL DEFAULT 'RUNNING',
 failure_reason varchar(2000),revision integer NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now());

CREATE TABLE wallpaper_publication_packages(id uuid PRIMARY KEY,production_id uuid NOT NULL REFERENCES wallpaper_productions,
 version integer NOT NULL CHECK(version>0),manifest jsonb NOT NULL,manifest_sha256 char(64) NOT NULL,
 approved_by varchar(200),approved_at timestamptz,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(production_id,version));
CREATE FUNCTION protect_wallpaper_package() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' OR (NEW.production_id,NEW.version,NEW.manifest,NEW.manifest_sha256) IS DISTINCT FROM (OLD.production_id,OLD.version,OLD.manifest,OLD.manifest_sha256) THEN RAISE EXCEPTION 'Publication manifest is immutable'; END IF;
 IF OLD.approved_at IS NOT NULL AND (NEW.approved_by,NEW.approved_at) IS DISTINCT FROM (OLD.approved_by,OLD.approved_at) THEN RAISE EXCEPTION 'Publication approval is immutable'; END IF;
 RETURN NEW; END $$;
CREATE TRIGGER wallpaper_package_immutable BEFORE UPDATE OR DELETE ON wallpaper_publication_packages FOR EACH ROW EXECUTE FUNCTION protect_wallpaper_package();
CREATE TABLE wallpaper_deliveries(id uuid PRIMARY KEY,package_id uuid NOT NULL REFERENCES wallpaper_publication_packages,target varchar(80) NOT NULL,
 status varchar(30) NOT NULL DEFAULT 'READY',operation varchar(20) NOT NULL DEFAULT 'PUBLISH',idempotency_key varchar(200) UNIQUE NOT NULL,
 attempt integer NOT NULL DEFAULT 0,max_attempts integer NOT NULL DEFAULT 3,external_reference jsonb,publication_id uuid REFERENCES publications,
 failure_code varchar(100),available_at timestamptz NOT NULL DEFAULT now(),lease_token uuid,lease_until timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),UNIQUE(package_id,target,operation));
CREATE TABLE wallpaper_exports(id uuid PRIMARY KEY,production_id uuid REFERENCES wallpaper_productions,collection_id uuid REFERENCES collections,
 package_ids jsonb NOT NULL,status varchar(20) NOT NULL DEFAULT 'QUEUED',storage_key text,sha256 char(64),failure_code varchar(100),
 created_at timestamptz NOT NULL DEFAULT now(),completed_at timestamptz, CHECK((production_id IS NULL)<>(collection_id IS NULL)));
CREATE TABLE mock_wallpaper_catalog(external_id text PRIMARY KEY,version integer NOT NULL,status varchar(20) NOT NULL,manifest jsonb NOT NULL);
CREATE TABLE mock_wallpaper_requests(idempotency_key varchar(200) PRIMARY KEY,checksum char(64) NOT NULL,result jsonb NOT NULL);
CREATE TABLE wallpaper_amoled_analyses(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),production_id uuid NOT NULL REFERENCES wallpaper_productions,
 stage varchar(20) NOT NULL,source_checksum char(64) NOT NULL,result jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(production_id,stage,source_checksum));
CREATE TRIGGER immutable_amoled_analysis BEFORE UPDATE OR DELETE ON wallpaper_amoled_analyses FOR EACH ROW EXECUTE FUNCTION immutable_review_action();
ALTER TABLE wallpaper_exports ADD COLUMN lease_token uuid,ADD COLUMN lease_until timestamptz;
