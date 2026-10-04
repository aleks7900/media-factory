CREATE TABLE skill_executions
(
    id              uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    skill_name      text         NOT NULL CHECK (skill_name IN
                                                 ('research-trends', 'create-collection', 'run-qa',
                                                  'prepare-stock', 'create-wallpapers')),
    skill_version   integer      NOT NULL CHECK (skill_version = 1),
    operation_id    varchar(160) NOT NULL UNIQUE,
    project_id      uuid         NOT NULL REFERENCES projects (id),
    collection_id   uuid REFERENCES collections (id),
    status          text         NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED',
                                                                              'WAITING_FOR_APPROVAL',
                                                                              'RUNNING',
                                                                              'COMPLETED',
                                                                              'PARTIALLY_COMPLETED',
                                                                              'FAILED',
                                                                              'CANCELLED')),
    requested_by    varchar(200) NOT NULL,
    input_summary   jsonb        NOT NULL CHECK (octet_length(input_summary::text) <= 131072),
    input_hash      char(64)     NOT NULL,
    plan            jsonb        NOT NULL,
    result_summary  jsonb        NOT NULL DEFAULT '{}',
    approved_by     varchar(200),
    approval_reason varchar(2000),
    approved_at     timestamptz,
    error_code      varchar(100),
    error_message   varchar(2000),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    started_at      timestamptz,
    completed_at    timestamptz,
    next_poll_at    timestamptz  NOT NULL DEFAULT now(),
    revision        integer      NOT NULL DEFAULT 0,
    max_budget      numeric(16, 8) CHECK (max_budget >= 0),
    currency        char(3)      NOT NULL DEFAULT 'USD'
);
CREATE INDEX skill_execution_queue ON skill_executions (next_poll_at) WHERE status='RUNNING';
CREATE INDEX skill_execution_scope ON skill_executions (project_id, collection_id, created_at DESC);
CREATE TABLE skill_execution_items
(
    id            uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    execution_id  uuid         NOT NULL REFERENCES skill_executions (id),
    item_key      varchar(160) NOT NULL,
    operation     text         NOT NULL,
    input         jsonb        NOT NULL DEFAULT '{}',
    resource_type text,
    resource_id   uuid,
    status        text         NOT NULL DEFAULT 'PLANNED',
    attempts      integer      NOT NULL DEFAULT 0,
    error_code    varchar(100),
    error_message varchar(2000),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    completed_at  timestamptz,
    UNIQUE (execution_id, item_key)
);
CREATE TABLE skill_execution_events
(
    id           bigserial PRIMARY KEY,
    execution_id uuid          NOT NULL REFERENCES skill_executions (id),
    action       text          NOT NULL,
    actor        varchar(200)  NOT NULL,
    reason       varchar(2000) NOT NULL,
    created_at   timestamptz   NOT NULL DEFAULT now()
);
CREATE TRIGGER skill_events_immutable
    BEFORE UPDATE OR DELETE ON skill_execution_events FOR EACH ROW
EXECUTE FUNCTION analytics_fact_immutable();
CREATE TABLE trend_research_runs
(
    id           uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    execution_id uuid        NOT NULL UNIQUE REFERENCES skill_executions (id),
    scope        jsonb       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE trend_candidates
(
    id                uuid PRIMARY KEY       DEFAULT gen_random_uuid(),
    research_run_id   uuid          NOT NULL REFERENCES trend_research_runs (id),
    name              varchar(200)  NOT NULL,
    description       varchar(4000) NOT NULL,
    media_type        varchar(40)   NOT NULL,
    visual_attributes jsonb         NOT NULL,
    evidence          jsonb         NOT NULL,
    source_count      integer       NOT NULL CHECK (source_count > 0),
    observed_at       timestamptz   NOT NULL,
    external_signal   jsonb         NOT NULL,
    internal_coverage jsonb         NOT NULL,
    notes             varchar(4000) NOT NULL DEFAULT ''
);
CREATE TRIGGER trend_candidates_immutable
    BEFORE UPDATE OR DELETE ON trend_candidates FOR EACH ROW
EXECUTE FUNCTION analytics_fact_immutable();
ALTER TABLE collections
    ADD COLUMN trend_candidate_id uuid REFERENCES trend_candidates (id);
ALTER TABLE generations
    ADD COLUMN skill_execution_id uuid REFERENCES skill_executions (id);
ALTER TABLE wallpaper_productions
    ADD COLUMN skill_execution_id uuid REFERENCES skill_executions (id);
ALTER TABLE stock_productions
    ADD COLUMN skill_execution_id uuid REFERENCES skill_executions (id);
CREATE INDEX generation_skill_origin ON generations (skill_execution_id) WHERE skill_execution_id IS NOT NULL;
CREATE TABLE skill_budget_reservations
(
    job_id       uuid PRIMARY KEY REFERENCES jobs (id),
    execution_id uuid           NOT NULL REFERENCES skill_executions (id),
    amount       numeric(16, 8) NOT NULL CHECK (amount >= 0),
    created_at   timestamptz    NOT NULL DEFAULT now()
);
CREATE FUNCTION skill_plan_guard() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Skill execution history is immutable';
END IF;
 IF
(to_jsonb(NEW)-ARRAY['status','collection_id','result_summary','approved_by','approval_reason','approved_at','error_code','error_message','started_at','completed_at','next_poll_at','revision'])
 IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','collection_id','result_summary','approved_by','approval_reason','approved_at','error_code','error_message','started_at','completed_at','next_poll_at','revision']) THEN
 RAISE EXCEPTION 'Skill execution plan is immutable';
END IF;
 IF
OLD.approved_at IS NOT NULL AND (NEW.approved_at,NEW.approved_by,NEW.approval_reason) IS DISTINCT FROM (OLD.approved_at,OLD.approved_by,OLD.approval_reason) THEN RAISE EXCEPTION 'Skill approval is immutable';
END IF;
RETURN NEW;
END $$;
CREATE TRIGGER skill_plan_immutable
    BEFORE UPDATE OR DELETE ON skill_executions FOR EACH ROW
EXECUTE FUNCTION skill_plan_guard();
