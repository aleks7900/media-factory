ALTER TABLE generations ADD COLUMN request_options jsonb NOT NULL DEFAULT '{}';
ALTER TABLE generations ADD COLUMN provider_route jsonb NOT NULL DEFAULT '[]';
ALTER TABLE generations ADD COLUMN routing_mode varchar(16) NOT NULL DEFAULT 'DEFAULT';
ALTER TABLE generations ADD COLUMN selected_provider varchar(80);
ALTER TABLE generations ADD COLUMN final_provider varchar(80);
ALTER TABLE generations ADD COLUMN model varchar(100);
ALTER TABLE generations ADD COLUMN started_at timestamptz;
ALTER TABLE generations ADD COLUMN completed_at timestamptz;
ALTER TABLE generations ADD COLUMN result_metadata jsonb NOT NULL DEFAULT '{}';
ALTER TABLE jobs ADD COLUMN route_index integer NOT NULL DEFAULT 0;
ALTER TABLE jobs ADD COLUMN provider_attempts integer NOT NULL DEFAULT 0;
ALTER TABLE jobs ADD COLUMN execution_started boolean NOT NULL DEFAULT false;
ALTER TABLE jobs ADD COLUMN recovery_required boolean NOT NULL DEFAULT false;
CREATE TABLE generation_attempts (
 id uuid PRIMARY KEY, generation_id uuid NOT NULL REFERENCES generations(id), job_id uuid NOT NULL REFERENCES jobs(id),
 provider varchar(80) NOT NULL, model varchar(100) NOT NULL, attempt_number integer NOT NULL,
 status varchar(20) NOT NULL CHECK(status IN ('STARTED','SUCCEEDED','FAILED','RATE_LIMITED','TIMED_OUT')),
 started_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz, duration_ms bigint,
 provider_request_id varchar(200), error_type varchar(80), error_code varchar(80), error_message varchar(500),
 retryable boolean NOT NULL DEFAULT false, fallback boolean NOT NULL DEFAULT false,
 outcome_unknown boolean NOT NULL DEFAULT false, estimated_cost numeric(18,8), actual_cost numeric(18,8),
 currency char(3) NOT NULL DEFAULT 'USD', metadata jsonb NOT NULL DEFAULT '{}',
 UNIQUE(job_id,attempt_number)
);
CREATE INDEX generation_attempts_generation_idx ON generation_attempts(generation_id,attempt_number);
CREATE INDEX generation_attempts_provider_time_idx ON generation_attempts(provider,started_at);
ALTER TABLE generation_costs ALTER COLUMN estimated_cost DROP NOT NULL;
ALTER TABLE generation_costs ALTER COLUMN input_usage DROP NOT NULL;
ALTER TABLE generation_costs ALTER COLUMN output_usage DROP NOT NULL;
ALTER TABLE generation_costs ADD COLUMN attempt_id uuid UNIQUE REFERENCES generation_attempts(id);
ALTER TABLE generation_costs ADD COLUMN quantity integer NOT NULL DEFAULT 1;
ALTER TABLE generation_costs ADD COLUMN actual_cost numeric(18,8) CHECK(actual_cost>=0);
ALTER TABLE generation_costs ADD COLUMN pricing_status varchar(20) NOT NULL DEFAULT 'ESTIMATED';
ALTER TABLE generation_costs ADD COLUMN pricing_version varchar(100) NOT NULL DEFAULT 'task-01';
ALTER TABLE generation_costs ADD COLUMN usage_details jsonb NOT NULL DEFAULT '{}';
CREATE TABLE provider_runtime (
 provider varchar(80) PRIMARY KEY, consecutive_failures integer NOT NULL DEFAULT 0,
 health varchar(20) NOT NULL DEFAULT 'HEALTHY', open_until timestamptz, updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE provider_permits (
 token uuid PRIMARY KEY, provider varchar(80) NOT NULL, job_id uuid NOT NULL UNIQUE REFERENCES jobs(id),
 expires_at timestamptz NOT NULL
);
CREATE INDEX provider_permits_provider_idx ON provider_permits(provider,expires_at);
CREATE TABLE provider_request_events (
 id uuid PRIMARY KEY, provider varchar(80) NOT NULL, occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX provider_request_events_window_idx ON provider_request_events(provider,occurred_at);
