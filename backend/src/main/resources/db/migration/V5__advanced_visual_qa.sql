ALTER TABLE generations DROP CONSTRAINT generations_status_check;
ALTER TABLE generations
    ADD CONSTRAINT generations_status_check CHECK (status IN
                                                   ('CREATED', 'QUEUED', 'GENERATING', 'GENERATED',
                                                    'QA_PENDING', 'QA_RUNNING', 'NEEDS_REVIEW',
                                                    'APPROVED', 'REJECTED', 'FAILED', 'PUBLISHED'));
ALTER TABLE collections
    ADD COLUMN qa_policy varchar(80);
ALTER TABLE quality_reviews DROP CONSTRAINT quality_reviews_kind_check;
ALTER TABLE quality_reviews DROP CONSTRAINT quality_reviews_decision_check;
ALTER TABLE quality_reviews
    ADD CONSTRAINT quality_reviews_kind_check CHECK (kind IN ('TECHNICAL', 'HUMAN', 'ADVANCED'));
ALTER TABLE quality_reviews
    ADD CONSTRAINT quality_reviews_decision_check CHECK (decision IN
                                                         ('PASSED', 'APPROVED', 'REJECTED',
                                                          'NEEDS_REVIEW'));
ALTER TABLE quality_reviews
    ADD COLUMN generation_id uuid REFERENCES generations (id);
UPDATE quality_reviews r
SET generation_id=a.generation_id FROM assets a
WHERE r.asset_id=a.id;
ALTER TABLE quality_reviews
    ALTER COLUMN generation_id SET NOT NULL;
ALTER TABLE quality_reviews
    ADD COLUMN execution_status varchar(20) NOT NULL DEFAULT 'COMPLETED' CHECK (execution_status IN
                                                                                ('PENDING',
                                                                                 'RUNNING',
                                                                                 'COMPLETED',
                                                                                 'FAILED'));
ALTER TABLE quality_reviews
    ADD COLUMN automatic_decision varchar(20);
ALTER TABLE quality_reviews
    ADD COLUMN final_decision varchar(20);
UPDATE quality_reviews
SET final_decision=CASE WHEN decision = 'PASSED' THEN 'NEEDS_REVIEW' ELSE decision END;
ALTER TABLE quality_reviews
    ADD COLUMN policy_id varchar(80);
ALTER TABLE quality_reviews
    ADD COLUMN policy_version varchar(80);
ALTER TABLE quality_reviews
    ADD COLUMN policy_snapshot jsonb NOT NULL DEFAULT '{}';
ALTER TABLE quality_reviews
    ADD COLUMN context_snapshot jsonb NOT NULL DEFAULT '{}';
ALTER TABLE quality_reviews
    ADD COLUMN rules_triggered jsonb NOT NULL DEFAULT '[]';
ALTER TABLE quality_reviews
    ADD COLUMN vision_provider varchar(80);
ALTER TABLE quality_reviews
    ADD COLUMN vision_model varchar(100);
ALTER TABLE quality_reviews
    ADD COLUMN qa_prompt_version varchar(80);
ALTER TABLE quality_reviews
    ADD COLUMN technical_complete boolean NOT NULL DEFAULT false;
ALTER TABLE quality_reviews
    ADD COLUMN visual_complete boolean NOT NULL DEFAULT false;
ALTER TABLE quality_reviews
    ADD COLUMN human_override boolean NOT NULL DEFAULT false;
ALTER TABLE quality_reviews
    ADD COLUMN revision integer NOT NULL DEFAULT 0;
ALTER TABLE quality_reviews
    ADD COLUMN started_at timestamptz;
ALTER TABLE quality_reviews
    ADD COLUMN completed_at timestamptz;
ALTER TABLE quality_reviews
    ADD COLUMN reviewed_at timestamptz;
ALTER TABLE quality_reviews
    ADD COLUMN failure_reason varchar(500);
ALTER TABLE quality_reviews
    ADD COLUMN idempotency_key text;
CREATE INDEX quality_reviews_identity_idx ON quality_reviews (idempotency_key, created_at);
ALTER TABLE assets
    ADD COLUMN current_review_id uuid REFERENCES quality_reviews (id);
UPDATE assets a
SET current_review_id=(SELECT r.id
                       FROM quality_reviews r
                       WHERE r.asset_id = a.id
                       ORDER BY r.created_at DESC, r.id LIMIT 1);
CREATE TABLE quality_findings
(
    id         uuid PRIMARY KEY,
    review_id  uuid             NOT NULL REFERENCES quality_reviews (id),
    category   varchar(40)      NOT NULL,
    code       varchar(80)      NOT NULL,
    severity   varchar(20)      NOT NULL CHECK (severity IN ('INFO', 'MINOR', 'MAJOR', 'CRITICAL')),
    confidence double precision NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    detected   boolean          NOT NULL,
    source     varchar(30)      NOT NULL CHECK (source IN ('TECHNICAL', 'VISION_MODEL', 'HUMAN')),
    evidence   varchar(4000)    NOT NULL,
    metadata   jsonb            NOT NULL DEFAULT '{}',
    created_at timestamptz      NOT NULL DEFAULT now()
);
CREATE INDEX quality_findings_filter_idx ON quality_findings (code, severity, review_id) WHERE detected;
CREATE TABLE quality_dimension_results
(
    id         uuid PRIMARY KEY,
    review_id  uuid             NOT NULL REFERENCES quality_reviews (id),
    dimension  varchar(60)      NOT NULL,
    score      double precision NOT NULL CHECK (score BETWEEN 0 AND 1),
    confidence double precision NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    applicable boolean          NOT NULL,
    evidence   varchar(4000)    NOT NULL,
    UNIQUE (review_id, dimension)
);
CREATE TABLE qa_jobs
(
    id                uuid PRIMARY KEY,
    review_id         uuid        NOT NULL UNIQUE REFERENCES quality_reviews (id),
    generation_id     uuid        NOT NULL REFERENCES generations (id),
    status            varchar(20) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    attempts          integer     NOT NULL DEFAULT 0,
    provider_attempts integer     NOT NULL DEFAULT 0,
    route_index       integer     NOT NULL DEFAULT 0,
    max_attempts      integer     NOT NULL,
    route             jsonb       NOT NULL,
    scenario          varchar(40) NOT NULL,
    failure_reason    varchar(500),
    available_at      timestamptz NOT NULL DEFAULT now(),
    locked_at         timestamptz,
    lease_token       uuid,
    execution_started boolean     NOT NULL DEFAULT false,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    finished_at       timestamptz
);
CREATE INDEX qa_jobs_claim_idx ON qa_jobs (status, available_at);
CREATE TABLE vision_qa_attempts
(
    id                  uuid PRIMARY KEY,
    review_id           uuid         NOT NULL REFERENCES quality_reviews (id),
    job_id              uuid         NOT NULL REFERENCES qa_jobs (id),
    provider            varchar(80)  NOT NULL,
    model               varchar(100) NOT NULL,
    attempt_number      integer      NOT NULL,
    status              varchar(20)  NOT NULL,
    started_at          timestamptz  NOT NULL DEFAULT now(),
    completed_at        timestamptz,
    duration_ms         bigint,
    error_type          varchar(80),
    failure_reason      varchar(500),
    provider_request_id varchar(200),
    metadata            jsonb        NOT NULL DEFAULT '{}',
    UNIQUE (job_id, attempt_number)
);
ALTER TABLE provider_permits
    ALTER COLUMN job_id DROP NOT NULL;
ALTER TABLE provider_permits
    ADD COLUMN qa_job_id uuid UNIQUE REFERENCES qa_jobs (id);
ALTER TABLE provider_permits
    ADD CONSTRAINT permit_owner CHECK ((job_id IS NULL) <> (qa_job_id IS NULL));
ALTER TABLE generation_costs
    ALTER COLUMN job_id DROP NOT NULL;
ALTER TABLE generation_costs
    ADD COLUMN qa_attempt_id uuid UNIQUE REFERENCES vision_qa_attempts (id);
ALTER TABLE generation_costs
    ADD COLUMN review_id uuid REFERENCES quality_reviews (id);
ALTER TABLE generation_costs
    ADD COLUMN asset_id uuid REFERENCES assets (id);
CREATE TABLE human_review_actions
(
    id                uuid PRIMARY KEY,
    review_id         uuid          NOT NULL REFERENCES quality_reviews (id),
    action            varchar(40)   NOT NULL,
    previous_decision varchar(20),
    new_decision      varchar(20),
    reason_code       varchar(60),
    reason_text       varchar(4000) NOT NULL DEFAULT '',
    actor             varchar(200)  NOT NULL,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    metadata          jsonb         NOT NULL DEFAULT '{}'
);
CREATE FUNCTION immutable_review_action() RETURNS trigger
    LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Human review audit is immutable';
END $$;
CREATE TRIGGER human_actions_immutable
    BEFORE UPDATE OR DELETE ON human_review_actions FOR EACH ROW
EXECUTE FUNCTION immutable_review_action();
CREATE TABLE regeneration_requests
(
    id                   uuid PRIMARY KEY,
    review_id            uuid         NOT NULL REFERENCES quality_reviews (id),
    parent_generation_id uuid         NOT NULL REFERENCES generations (id),
    child_generation_id  uuid         NOT NULL UNIQUE REFERENCES generations (id),
    mode                 varchar(30)  NOT NULL,
    feedback             text         NOT NULL DEFAULT '',
    request              jsonb        NOT NULL,
    actor                varchar(200) NOT NULL,
    created_at           timestamptz  NOT NULL DEFAULT now()
);
CREATE FUNCTION require_publication_approval() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
approved boolean;
BEGIN
 IF
TG_TABLE_NAME='publications' THEN
SELECT r.final_decision = 'APPROVED'
INTO approved
FROM assets a
         JOIN quality_reviews r ON r.id = a.current_review_id
WHERE a.id = NEW.asset_id FOR UPDATE OF a,r;
ELSE
  IF NEW.status<>'PUBLISHED' OR OLD.status='PUBLISHED' THEN RETURN NEW;
END IF;
SELECT r.final_decision = 'APPROVED'
INTO approved
FROM assets a
         JOIN quality_reviews r ON r.id = a.current_review_id
WHERE a.generation_id = NEW.id FOR UPDATE OF a,r;
END IF;
 IF
approved IS DISTINCT FROM true THEN RAISE EXCEPTION 'Effective QA approval required for publication';
END IF;
RETURN NEW;
END $$;
CREATE TRIGGER publication_approval
    BEFORE INSERT OR UPDATE ON publications FOR EACH ROW
EXECUTE FUNCTION require_publication_approval();
CREATE TRIGGER generation_publication_approval
    BEFORE UPDATE OF status
    ON generations
    FOR EACH ROW EXECUTE FUNCTION require_publication_approval();
ALTER TABLE quality_reviews
    ADD CONSTRAINT automatic_qa_decision CHECK (automatic_decision IN
                                                ('APPROVED', 'REJECTED', 'NEEDS_REVIEW'));
ALTER TABLE quality_reviews
    ADD CONSTRAINT final_qa_decision CHECK (final_decision IN ('APPROVED', 'REJECTED', 'NEEDS_REVIEW'));
CREATE INDEX quality_reviews_queue_idx ON quality_reviews (final_decision, execution_status, created_at DESC);
CREATE INDEX quality_reviews_generation_idx ON quality_reviews (generation_id, created_at DESC);
CREATE INDEX vision_attempts_review_idx ON vision_qa_attempts (review_id, attempt_number);
CREATE INDEX qa_costs_review_idx ON generation_costs (review_id) WHERE operation='VISUAL_QA';
CREATE FUNCTION protect_qa_history() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
 IF
OLD.policy_snapshot IS DISTINCT FROM NEW.policy_snapshot OR OLD.context_snapshot IS DISTINCT FROM NEW.context_snapshot OR OLD.qa_prompt_version IS DISTINCT FROM NEW.qa_prompt_version THEN RAISE EXCEPTION 'QA context and policy snapshots are immutable';
END IF;
 IF
OLD.execution_status IN ('COMPLETED','FAILED') AND (OLD.automatic_decision IS DISTINCT FROM NEW.automatic_decision OR OLD.execution_status<>NEW.execution_status OR OLD.rules_triggered IS DISTINCT FROM NEW.rules_triggered) THEN RAISE EXCEPTION 'Original QA result is immutable; create a new review';
END IF;
 IF
NOT EXISTS(SELECT 1 FROM assets WHERE current_review_id=OLD.id) AND OLD.execution_status IN ('COMPLETED','FAILED') THEN RAISE EXCEPTION 'Historical QA review is immutable';
END IF;
RETURN NEW;
END $$;
CREATE TRIGGER protect_qa_review
    BEFORE UPDATE
    ON quality_reviews
    FOR EACH ROW EXECUTE FUNCTION protect_qa_history();
CREATE FUNCTION protect_qa_evidence() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
 IF
EXISTS(SELECT 1 FROM quality_reviews WHERE id=OLD.review_id AND execution_status IN ('COMPLETED','FAILED')) THEN RAISE EXCEPTION 'Completed QA evidence is immutable';
END IF;
RETURN OLD;
END $$;
CREATE TRIGGER protect_qa_findings
    BEFORE UPDATE OR DELETE ON quality_findings FOR EACH ROW
EXECUTE FUNCTION protect_qa_evidence();
CREATE TRIGGER protect_qa_dimensions
    BEFORE UPDATE OR DELETE ON quality_dimension_results FOR EACH ROW
EXECUTE FUNCTION protect_qa_evidence();
