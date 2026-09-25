CREATE TABLE prompt_templates
(
    id          uuid PRIMARY KEY,
    key         varchar(100) NOT NULL UNIQUE,
    name        varchar(200) NOT NULL,
    description text         NOT NULL DEFAULT '',
    category    varchar(80)  NOT NULL,
    status      varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    revision    int          NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  text         NOT NULL DEFAULT 'local-workspace'
);
CREATE TABLE prompt_versions
(
    id                 uuid PRIMARY KEY,
    prompt_template_id uuid        NOT NULL REFERENCES prompt_templates (id),
    version            int         NOT NULL,
    positive_template  text        NOT NULL,
    negative_template  text        NOT NULL DEFAULT '',
    change_description text        NOT NULL DEFAULT '',
    status             varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'DEPRECATED')),
    revision           int         NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL DEFAULT now(),
    created_by         text        NOT NULL DEFAULT 'local-workspace',
    published_at       timestamptz,
    published_by       text,
    deprecated_at      timestamptz,
    deprecated_by      text,
    UNIQUE (prompt_template_id, version)
);
CREATE TABLE prompt_variable_definitions
(
    id                uuid PRIMARY KEY,
    prompt_version_id uuid         NOT NULL REFERENCES prompt_versions (id),
    name              varchar(80)  NOT NULL,
    label             varchar(200) NOT NULL,
    description       text         NOT NULL DEFAULT '',
    type              varchar(40)  NOT NULL,
    required          boolean      NOT NULL DEFAULT false,
    default_value     jsonb,
    allowed_values    jsonb        NOT NULL DEFAULT '[]',
    min               numeric,
    max               numeric,
    min_length        int,
    max_length        int,
    display_order     int          NOT NULL DEFAULT 0,
    UNIQUE (prompt_version_id, name)
);
CREATE TABLE prompt_presets
(
    id          uuid PRIMARY KEY,
    key         varchar(100) NOT NULL UNIQUE,
    name        varchar(200) NOT NULL,
    description text         NOT NULL DEFAULT '',
    category    varchar(80)  NOT NULL,
    status      varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    revision    int          NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  text         NOT NULL DEFAULT 'local-workspace'
);
CREATE TABLE prompt_preset_versions
(
    id                uuid PRIMARY KEY,
    prompt_preset_id  uuid        NOT NULL REFERENCES prompt_presets (id),
    version           int         NOT NULL,
    positive_fragment text        NOT NULL,
    negative_fragment text        NOT NULL DEFAULT '',
    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        text        NOT NULL DEFAULT 'local-workspace',
    UNIQUE (prompt_preset_id, version)
);
CREATE TABLE prompt_experiments
(
    id                   uuid PRIMARY KEY,
    name                 varchar(200) NOT NULL,
    description          text         NOT NULL DEFAULT '',
    status               varchar(20)  NOT NULL DEFAULT 'DRAFT' CHECK (status IN
                                                                      ('DRAFT', 'RUNNING', 'PAUSED',
                                                                       'COMPLETED', 'CANCELLED')),
    scope                varchar(24)  NOT NULL CHECK (scope IN ('PROMPT_TEMPLATE', 'COLLECTION', 'PIPELINE')),
    prompt_template_id   uuid REFERENCES prompt_templates (id),
    collection_id        uuid REFERENCES collections (id),
    pipeline_key         varchar(80),
    allow_overrides      boolean      NOT NULL DEFAULT false,
    assignment_algorithm varchar(80)  NOT NULL DEFAULT 'SHA256_BASIS_POINTS_V1',
    revision             int          NOT NULL DEFAULT 0,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    created_by           text         NOT NULL DEFAULT 'local-workspace',
    started_at           timestamptz,
    ended_at             timestamptz,
    CHECK ((scope = 'PROMPT_TEMPLATE' AND prompt_template_id IS NOT NULL AND
            collection_id IS NULL AND pipeline_key IS NULL)
        OR (scope = 'COLLECTION' AND collection_id IS NOT NULL AND prompt_template_id IS NULL AND
            pipeline_key IS NULL)
        OR (scope = 'PIPELINE' AND pipeline_key IS NOT NULL AND prompt_template_id IS NULL AND
            collection_id IS NULL))
);
CREATE INDEX prompt_experiments_status_idx ON prompt_experiments (status);
CREATE TABLE prompt_experiment_variants
(
    id                uuid PRIMARY KEY,
    experiment_id     uuid         NOT NULL REFERENCES prompt_experiments (id),
    key               varchar(40)  NOT NULL,
    name              varchar(200) NOT NULL,
    prompt_version_id uuid         NOT NULL REFERENCES prompt_versions (id),
    weight            int          NOT NULL CHECK (weight BETWEEN 1 AND 10000),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (experiment_id, key),
    UNIQUE (experiment_id, id)
);
CREATE TABLE rendered_prompt_snapshots
(
    id                        uuid PRIMARY KEY,
    generation_id             uuid         NOT NULL REFERENCES generations (id),
    kind                      varchar(20)  NOT NULL CHECK (kind IN ('TEMPLATE', 'AD_HOC', 'LEGACY')),
    template_id               uuid REFERENCES prompt_templates (id),
    prompt_version_id         uuid REFERENCES prompt_versions (id),
    template_version          int,
    variables                 jsonb        NOT NULL DEFAULT '{}',
    presets                   jsonb        NOT NULL DEFAULT '[]',
    composition               jsonb        NOT NULL DEFAULT '{}',
    canonical_positive_prompt text         NOT NULL,
    canonical_negative_prompt text         NOT NULL DEFAULT '',
    provider                  varchar(100) NOT NULL,
    adapted_positive_prompt   text         NOT NULL,
    adapted_negative_prompt   text         NOT NULL DEFAULT '',
    adaptation_strategy       varchar(100) NOT NULL,
    warnings                  jsonb        NOT NULL DEFAULT '[]',
    experiment_id             uuid REFERENCES prompt_experiments (id),
    experiment_variant_id     uuid,
    assignment_key            text,
    rendered_at               timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (generation_id, provider),
    FOREIGN KEY (experiment_id, experiment_variant_id) REFERENCES prompt_experiment_variants (experiment_id, id)
);
ALTER TABLE generations
    ADD COLUMN prompt_version_id uuid REFERENCES prompt_versions (id),
 ADD COLUMN prompt_snapshot_id uuid REFERENCES rendered_prompt_snapshots(id),
 ADD COLUMN experiment_id uuid REFERENCES prompt_experiments(id), ADD COLUMN experiment_variant_id uuid,
 ADD COLUMN prompt_request jsonb,
 ADD FOREIGN KEY(experiment_id,experiment_variant_id) REFERENCES prompt_experiment_variants(experiment_id,id);
ALTER TABLE generation_attempts
    ADD COLUMN prompt_snapshot_id uuid REFERENCES rendered_prompt_snapshots (id);
CREATE INDEX generations_prompt_version_idx ON generations (prompt_version_id);
CREATE INDEX generations_experiment_idx ON generations (experiment_id);
CREATE INDEX generations_variant_idx ON generations (experiment_variant_id);

-- Preserve legacy raw strings; no invented version or experiment attribution.
INSERT INTO rendered_prompt_snapshots(id, generation_id, kind, canonical_positive_prompt,
                                      canonical_negative_prompt, provider, adapted_positive_prompt,
                                      adapted_negative_prompt, adaptation_strategy)
SELECT gen_random_uuid(),
       id,
       'LEGACY',
       prompt,
       coalesce(request_options ->>'negativePrompt',''),
       coalesce(final_provider, selected_provider, 'mock'),
       prompt,
       coalesce(request_options ->>'negativePrompt',''),
       'LEGACY_RAW_UNMODIFIED'
FROM generations;
UPDATE generations g
SET prompt_snapshot_id=s.id FROM rendered_prompt_snapshots s
WHERE s.generation_id=g.id;
UPDATE generation_attempts a
SET prompt_snapshot_id=s.id FROM rendered_prompt_snapshots s
WHERE s.generation_id=a.generation_id AND s.provider=a.provider;

CREATE FUNCTION reject_prompt_mutation() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Immutable prompt record' USING ERRCODE='23514';
END $$;
CREATE TRIGGER immutable_snapshot
    BEFORE UPDATE OR DELETE ON rendered_prompt_snapshots FOR EACH ROW
EXECUTE FUNCTION reject_prompt_mutation();
CREATE TRIGGER immutable_preset_version
    BEFORE UPDATE OR DELETE ON prompt_preset_versions FOR EACH ROW
EXECUTE FUNCTION reject_prompt_mutation();
CREATE FUNCTION protect_prompt_version() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status <> 'DRAFT' THEN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Published version is immutable' USING ERRCODE='23514';
END IF;
  IF
(to_jsonb(NEW)-ARRAY['status','deprecated_at','deprecated_by','revision']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','deprecated_at','deprecated_by','revision'])
   OR NOT (OLD.status='PUBLISHED' AND NEW.status='DEPRECATED') THEN
   RAISE EXCEPTION 'Published version is immutable' USING ERRCODE='23514';
END IF;
END IF;
 IF
TG_OP='DELETE' THEN RETURN OLD;
END IF;
RETURN NEW;
END $$;
CREATE TRIGGER protect_version
    BEFORE UPDATE OR DELETE ON prompt_versions FOR EACH ROW
EXECUTE FUNCTION protect_prompt_version();
CREATE FUNCTION protect_prompt_variables() RETURNS trigger
    LANGUAGE plpgsql AS $$ DECLARE v uuid;
BEGIN
 IF TG_OP='DELETE' THEN v=OLD.prompt_version_id;
ELSE v=NEW.prompt_version_id;
END IF;
 PERFORM
1 FROM prompt_versions WHERE id=v AND status='DRAFT' FOR
UPDATE;
IF
NOT FOUND THEN RAISE EXCEPTION 'Published variables are immutable' USING ERRCODE='23514';
END IF;
 IF
TG_OP='UPDATE' AND OLD.prompt_version_id<>NEW.prompt_version_id THEN RAISE EXCEPTION 'Cannot move variables' USING ERRCODE='23514';
END IF;
 IF
TG_OP='DELETE' THEN RETURN OLD;
END IF;
RETURN NEW;
END $$;
CREATE TRIGGER protect_variables
    BEFORE INSERT OR UPDATE OR DELETE ON prompt_variable_definitions FOR EACH ROW
EXECUTE FUNCTION protect_prompt_variables();
CREATE FUNCTION protect_experiment() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status<>'DRAFT' THEN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Experiment configuration is immutable' USING ERRCODE='23514';
END IF;
  IF
(to_jsonb(NEW)-ARRAY['status','ended_at','revision']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','ended_at','revision']) THEN
   RAISE EXCEPTION 'Experiment configuration is immutable' USING ERRCODE='23514';
END IF;
END IF;
 IF
TG_OP='DELETE' THEN RETURN OLD;
END IF;
RETURN NEW;
END $$;
CREATE TRIGGER protect_experiment_config
    BEFORE UPDATE OR DELETE ON prompt_experiments FOR EACH ROW
EXECUTE FUNCTION protect_experiment();
CREATE FUNCTION protect_variant() RETURNS trigger
    LANGUAGE plpgsql AS $$ DECLARE e uuid;
BEGIN
 IF TG_OP='DELETE' THEN e=OLD.experiment_id;
ELSE e=NEW.experiment_id;
END IF;
 PERFORM
1 FROM prompt_experiments WHERE id=e AND status='DRAFT' FOR
UPDATE;
IF
NOT FOUND THEN RAISE EXCEPTION 'Experiment variants are immutable' USING ERRCODE='23514';
END IF;
 IF
TG_OP='UPDATE' AND OLD.experiment_id<>NEW.experiment_id THEN RAISE EXCEPTION 'Cannot move variants' USING ERRCODE='23514';
END IF;
 IF
TG_OP='DELETE' THEN RETURN OLD;
END IF;
RETURN NEW;
END $$;
CREATE TRIGGER protect_variants
    BEFORE INSERT OR UPDATE OR DELETE ON prompt_experiment_variants FOR EACH ROW
EXECUTE FUNCTION protect_variant();
CREATE FUNCTION protect_generation_prompt() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.prompt_snapshot_id IS NOT NULL AND (NEW.prompt_snapshot_id,NEW.prompt_version_id,NEW.experiment_id,NEW.experiment_variant_id,NEW.prompt,NEW.prompt_request)
 IS DISTINCT FROM (OLD.prompt_snapshot_id,OLD.prompt_version_id,OLD.experiment_id,OLD.experiment_variant_id,OLD.prompt,OLD.prompt_request) THEN
 RAISE EXCEPTION 'Generation prompt attribution is immutable' USING ERRCODE='23514';
END IF;
RETURN NEW;
END $$;
CREATE TRIGGER protect_generation_attribution
    BEFORE UPDATE
    ON generations
    FOR EACH ROW EXECUTE FUNCTION protect_generation_prompt();
