CREATE TABLE bulk_batches (
 id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES projects(id), collection_id uuid NOT NULL REFERENCES collections(id),
 name varchar(200) NOT NULL, kind varchar(30) NOT NULL CHECK(kind IN ('GPT_IMAGE','GEMINI_VIDEO')),
 provider varchar(80) NOT NULL, model varchar(160) NOT NULL, configuration jsonb NOT NULL,
 archive_key text NOT NULL, archive_sha256 char(64) NOT NULL, archive_name varchar(200) NOT NULL,
 idempotency_key varchar(160) NOT NULL UNIQUE, request_hash char(64) NOT NULL,
 paused boolean NOT NULL DEFAULT false, cancelled boolean NOT NULL DEFAULT false, deleted_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), started_at timestamptz, completed_at timestamptz
);
CREATE TABLE bulk_tasks (
 id uuid PRIMARY KEY, batch_id uuid NOT NULL REFERENCES bulk_batches(id), name varchar(200) NOT NULL,
 prompt text NOT NULL, inputs jsonb NOT NULL DEFAULT '[]', generation_id uuid REFERENCES generations(id),
 parent_id uuid REFERENCES bulk_tasks(id), regeneration_key varchar(160) UNIQUE,
 status varchar(20) NOT NULL CHECK(status IN ('PENDING','QUEUED','GENERATING','COMPLETED','FAILED','RETRYING','CANCELLED')),
 validation_error text, error_code varchar(100), error_message varchar(1000),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0), retry_count integer NOT NULL DEFAULT 0,
 poll_failures integer NOT NULL DEFAULT 0 CHECK(poll_failures>=0),
 remote_job_id text, remote_deadline_at timestamptz, current_attempt_id uuid, outcome_unknown boolean NOT NULL DEFAULT false,
 available_at timestamptz NOT NULL DEFAULT now(), lease_token uuid, lease_until timestamptz,
 cancel_requested boolean NOT NULL DEFAULT false, deleted_at timestamptz,
 asset_id uuid REFERENCES assets(id), provider_metadata jsonb NOT NULL DEFAULT '{}',
 created_at timestamptz NOT NULL DEFAULT now(), started_at timestamptz, completed_at timestamptz
);
CREATE INDEX bulk_task_claim ON bulk_tasks(available_at,created_at) WHERE status IN ('QUEUED','RETRYING','GENERATING');
CREATE INDEX bulk_task_batch ON bulk_tasks(batch_id,created_at,id);
CREATE TABLE bulk_attempts (
 id uuid PRIMARY KEY, task_id uuid NOT NULL REFERENCES bulk_tasks(id), number integer NOT NULL,
 status varchar(30) NOT NULL, provider varchar(80) NOT NULL, model varchar(160) NOT NULL,
 remote_job_id text, cost_id uuid REFERENCES generation_costs(id), error_code varchar(100),
 created_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz,
 UNIQUE(task_id,number)
);
ALTER TABLE bulk_tasks ADD CONSTRAINT bulk_current_attempt FOREIGN KEY(current_attempt_id) REFERENCES bulk_attempts(id);
ALTER TABLE provider_permits ADD COLUMN bulk_task_id uuid UNIQUE REFERENCES bulk_tasks(id);
ALTER TABLE provider_permits DROP CONSTRAINT permit_owner;
ALTER TABLE provider_permits ADD CONSTRAINT permit_owner CHECK(num_nonnulls(job_id,qa_job_id,similarity_job_id,video_attempt_id,bulk_task_id)=1);
CREATE TABLE bulk_events (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, batch_id uuid NOT NULL REFERENCES bulk_batches(id),
 task_id uuid REFERENCES bulk_tasks(id), action varchar(80) NOT NULL, detail jsonb NOT NULL DEFAULT '{}',
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX bulk_events_batch ON bulk_events(batch_id,id);
