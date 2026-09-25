CREATE TABLE processing_profiles
(
    id         uuid PRIMARY KEY            DEFAULT gen_random_uuid(),
    key        varchar(80) UNIQUE NOT NULL,
    name       varchar(160)       NOT NULL,
    created_at timestamptz        NOT NULL DEFAULT now()
);
CREATE TABLE processing_profile_versions
(
    id           uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    profile_id   uuid        NOT NULL REFERENCES processing_profiles,
    version      integer     NOT NULL CHECK (version > 0),
    status       varchar(20) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'DEPRECATED')),
    definition   jsonb       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    UNIQUE (profile_id, version)
);
CREATE FUNCTION protect_processing_profile() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status <> 'DRAFT' AND (TG_OP='DELETE' OR NEW.definition IS DISTINCT FROM OLD.definition OR NEW.version<>OLD.version OR NEW.profile_id<>OLD.profile_id OR NEW.status NOT IN ('PUBLISHED','DEPRECATED')) THEN
  RAISE EXCEPTION 'Published processing profiles are immutable';
END IF;
 IF
TG_OP='DELETE' THEN RETURN OLD;
END IF;
RETURN NEW;
END $$;
CREATE TRIGGER processing_profile_immutable
    BEFORE UPDATE OR DELETE ON processing_profile_versions FOR EACH ROW
EXECUTE FUNCTION protect_processing_profile();
CREATE TABLE wallpaper_targets
(
    key       varchar(80) PRIMARY KEY,
    width     integer     NOT NULL CHECK (width > 0),
    height    integer     NOT NULL CHECK (height > 0),
    crop_mode varchar(20) NOT NULL DEFAULT 'SMART_FILL',
    quality   integer     NOT NULL DEFAULT 95,
    format    varchar(10) NOT NULL DEFAULT 'JPEG'
);
INSERT INTO wallpaper_targets
VALUES ('ANDROID_1440_3200', 1440, 3200, 'SMART_FILL', 95, 'JPEG'),
       ('PHONE_1290_2796', 1290, 2796, 'SMART_FILL', 95, 'JPEG'),
       ('ANDROID_1080_2400', 1080, 2400, 'SMART_FILL', 95, 'JPEG');
INSERT INTO processing_profiles(key, name)
VALUES ('STOCK_STANDARD', 'Stock · 4 MP'),
       ('STOCK_4K', 'Stock · 4K'),
       ('WALLPAPER_ANDROID', 'Android wallpaper'),
       ('WALLPAPER_PHONE', 'Phone wallpaper'),
       ('SOCIAL_PORTRAIT', 'Social portrait'),
       ('SOCIAL_SQUARE', 'Social square'),
       ('THUMBNAIL', 'Thumbnail'),
       ('PREVIEW', 'Preview');
INSERT INTO processing_profile_versions(profile_id, version, status, definition, published_at)
SELECT id,
       1,
       'PUBLISHED',
       jsonb_build_object('width',
                          CASE key WHEN 'STOCK_4K' THEN 2400 WHEN 'WALLPAPER_ANDROID' THEN 1440 WHEN 'WALLPAPER_PHONE' THEN 1290 WHEN 'SOCIAL_PORTRAIT' THEN 1080 WHEN 'SOCIAL_SQUARE' THEN 1080 WHEN 'THUMBNAIL' THEN 320 ELSE 1280 END,
                          'height',
                          CASE key WHEN 'STOCK_4K' THEN 2400 WHEN 'WALLPAPER_ANDROID' THEN 3200 WHEN 'WALLPAPER_PHONE' THEN 2796 WHEN 'SOCIAL_PORTRAIT' THEN 1350 WHEN 'SOCIAL_SQUARE' THEN 1080 WHEN 'THUMBNAIL' THEN 320 ELSE 1280 END,
                          'mode', CASE
                                      WHEN key LIKE 'STOCK%' THEN 'PRESERVE' WHEN key LIKE 'WALLPAPER%' OR key LIKE 'SOCIAL%' THEN 'SMART_FILL' ELSE 'FIT' END,
                          'format',
                          CASE WHEN key IN ('THUMBNAIL','PREVIEW') THEN 'WEBP' ELSE 'JPEG' END,
                          'quality', 95, 'minimumQuality', 88, 'maxBytes', 20971520,
                          'minimumMegapixels', CASE WHEN key LIKE 'STOCK%' THEN 4 ELSE 0 END,
                          'minimumWidth', CASE WHEN key ='STOCK_4K' THEN 2400 ELSE 0 END,
                          'minimumHeight', CASE WHEN key ='STOCK_4K' THEN 2400 ELSE 0 END,
                          'denoise', 0, 'sharpen', 0.15, 'background', 'BLACK', 'padding', 0.15,
                          'safeTop', CASE WHEN key LIKE 'WALLPAPER%' THEN 0.15 ELSE 0 END,
                          'safeBottom', CASE WHEN key LIKE 'WALLPAPER%' THEN 0.10 ELSE 0 END,
                          'unsafeCrop', 'LETTERBOX', 'requireGpu', false, 'visualQa', false,
                          'colorSpace', 'sRGB', 'metadataPolicy', 'PUBLIC_STRIP'),
       now()
FROM processing_profiles;
CREATE TABLE processing_runs
(
    id               uuid PRIMARY KEY,
    source_asset_id  uuid                NOT NULL REFERENCES assets,
    status           varchar(24)         NOT NULL DEFAULT 'PENDING'
        CHECK (status IN
               ('PENDING', 'RUNNING', 'PARTIALLY_COMPLETED', 'COMPLETED', 'FAILED', 'CANCELLED')),
    idempotency_key  varchar(200) UNIQUE NOT NULL,
    request_hash     char(64)            NOT NULL,
    plan             jsonb               NOT NULL,
    priority         integer             NOT NULL DEFAULT 10 CHECK (priority BETWEEN 0 AND 100),
    attempt          integer             NOT NULL DEFAULT 0,
    max_attempts     integer             NOT NULL DEFAULT 3,
    available_at     timestamptz         NOT NULL DEFAULT now(),
    lease_token      uuid,
    lease_until      timestamptz,
    cancel_requested boolean             NOT NULL DEFAULT false,
    failure_code     varchar(80),
    failure_reason   text,
    created_at       timestamptz         NOT NULL DEFAULT now(),
    started_at       timestamptz,
    completed_at     timestamptz
);
CREATE INDEX processing_runs_asset ON processing_runs (source_asset_id);
CREATE INDEX processing_runs_queue ON processing_runs (status, priority DESC, created_at);
CREATE TABLE processing_artifacts
(
    id                 uuid PRIMARY KEY,
    source_asset_id    uuid            NOT NULL REFERENCES assets,
    parent_artifact_id uuid REFERENCES processing_artifacts,
    run_id             uuid            NOT NULL REFERENCES processing_runs,
    cache_key          char(64) UNIQUE NOT NULL,
    operation          varchar(40)     NOT NULL,
    storage_key        text UNIQUE     NOT NULL,
    sha256             char(64)        NOT NULL,
    width              integer         NOT NULL,
    height             integer         NOT NULL,
    size_bytes         bigint          NOT NULL CHECK (size_bytes > 0),
    format             varchar(10)     NOT NULL,
    metadata           jsonb           NOT NULL,
    created_at         timestamptz     NOT NULL DEFAULT now()
);
CREATE TABLE processing_steps
(
    id                 uuid PRIMARY KEY,
    run_id             uuid         NOT NULL REFERENCES processing_runs,
    node_key           varchar(120) NOT NULL,
    operation          varchar(40)  NOT NULL,
    parameters         jsonb        NOT NULL,
    status             varchar(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN
                                                                      ('PENDING', 'RUNNING',
                                                                       'COMPLETED', 'SKIPPED',
                                                                       'FAILED')),
    input_artifact_id  uuid REFERENCES processing_artifacts,
    output_artifact_id uuid REFERENCES processing_artifacts,
    error_code         varchar(80),
    error_message      text,
    skip_reason        varchar(100),
    metadata           jsonb        NOT NULL DEFAULT '{}',
    started_at         timestamptz,
    completed_at       timestamptz,
    duration_ms        bigint,
    UNIQUE (run_id, node_key)
);
CREATE INDEX processing_steps_run_status ON processing_steps (run_id, status);
CREATE TABLE processing_validation_results
(
    id          uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    artifact_id uuid        NOT NULL REFERENCES processing_artifacts,
    run_id      uuid        NOT NULL REFERENCES processing_runs,
    status      varchar(20) NOT NULL,
    findings    jsonb       NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE processing_manifests
(
    id         uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    run_id     uuid        NOT NULL REFERENCES processing_runs,
    attempt    integer     NOT NULL,
    manifest   jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (run_id, attempt)
);
CREATE TABLE processing_compute_usage
(
    id                     uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    run_id                 uuid           NOT NULL REFERENCES processing_runs,
    step_id                uuid           NOT NULL REFERENCES processing_steps,
    provider               varchar(80)    NOT NULL,
    model                  varchar(160)   NOT NULL,
    operation              varchar(40)    NOT NULL,
    input_usage            bigint         NOT NULL,
    output_usage           bigint         NOT NULL,
    duration_ms            bigint         NOT NULL,
    device                 varchar(20)    NOT NULL,
    external_cost          numeric(16, 8) NOT NULL DEFAULT 0,
    estimated_compute_cost numeric(16, 8),
    currency               varchar(3),
    metadata               jsonb          NOT NULL,
    created_at             timestamptz    NOT NULL DEFAULT now()
);
ALTER TABLE processing_compute_usage
    ADD COLUMN outcome varchar(20) NOT NULL DEFAULT 'SUCCEEDED' CHECK (outcome IN ('STARTED', 'SUCCEEDED', 'FAILED'));
ALTER TABLE asset_variants
    ADD COLUMN source_asset_id uuid REFERENCES assets,
 ADD COLUMN parent_artifact_id uuid REFERENCES processing_artifacts, ADD COLUMN processing_run_id uuid REFERENCES processing_runs,
 ADD COLUMN profile_version_id uuid REFERENCES processing_profile_versions, ADD COLUMN artifact_id uuid UNIQUE REFERENCES processing_artifacts,
 ADD COLUMN width integer, ADD COLUMN height integer, ADD COLUMN format varchar(10), ADD COLUMN validation_status varchar(20),
 ADD COLUMN asset_role varchar(20) NOT NULL DEFAULT 'DERIVED';
ALTER TABLE asset_variants ALTER COLUMN kind TYPE varchar(80);
UPDATE asset_variants
SET source_asset_id=asset_id;
ALTER TABLE asset_variants
    ADD CONSTRAINT variant_source_matches_master CHECK (source_asset_id = asset_id AND asset_role = 'DERIVED');
CREATE FUNCTION check_processing_lineage() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.parent_artifact_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM processing_artifacts p WHERE p.id=NEW.parent_artifact_id AND p.source_asset_id=NEW.source_asset_id) THEN RAISE EXCEPTION 'Processing parent must share the same original master';
END IF;
 IF
NOT EXISTS(SELECT 1 FROM processing_runs r WHERE r.id=NEW.run_id AND r.source_asset_id=NEW.source_asset_id) THEN RAISE EXCEPTION 'Artifact run must share the same original master';
END IF;
RETURN NEW;
END $$;
CREATE TRIGGER processing_artifact_lineage
    BEFORE INSERT
    ON processing_artifacts
    FOR EACH ROW EXECUTE FUNCTION check_processing_lineage();
CREATE INDEX variants_source_kind ON asset_variants (source_asset_id, kind);
CREATE FUNCTION protect_processing_history() RETURNS trigger
    LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Processing history is immutable';
END $$;
CREATE TRIGGER processing_artifacts_immutable
    BEFORE UPDATE OR DELETE ON processing_artifacts FOR EACH ROW
EXECUTE FUNCTION protect_processing_history();
CREATE TRIGGER processing_manifests_immutable
    BEFORE UPDATE OR DELETE ON processing_manifests FOR EACH ROW
EXECUTE FUNCTION protect_processing_history();
CREATE TRIGGER processing_validation_immutable
    BEFORE UPDATE OR DELETE ON processing_validation_results FOR EACH ROW
EXECUTE FUNCTION protect_processing_history();
CREATE FUNCTION protect_processing_plan() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.plan IS DISTINCT FROM OLD.plan OR NEW.source_asset_id<>OLD.source_asset_id OR NEW.request_hash<>OLD.request_hash OR NEW.idempotency_key<>OLD.idempotency_key THEN RAISE EXCEPTION 'Resolved processing plan is immutable';
END IF;
RETURN NEW;
END $$;
CREATE TRIGGER processing_plan_immutable
    BEFORE UPDATE
    ON processing_runs
    FOR EACH ROW EXECUTE FUNCTION protect_processing_plan();
