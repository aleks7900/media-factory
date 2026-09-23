CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE projects (id uuid PRIMARY KEY, name varchar(200) NOT NULL, description text NOT NULL DEFAULT '', created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE collections (id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES projects(id), name varchar(200) NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE concepts (id uuid PRIMARY KEY, collection_id uuid NOT NULL REFERENCES collections(id), name varchar(200) NOT NULL, prompt text NOT NULL, embedding vector(1536), created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE generations (
 id uuid PRIMARY KEY, concept_id uuid NOT NULL REFERENCES concepts(id), parent_id uuid REFERENCES generations(id),
 status varchar(24) NOT NULL CHECK (status IN ('CREATED','QUEUED','GENERATING','GENERATED','QA_PENDING','APPROVED','REJECTED','FAILED','PUBLISHED')),
 prompt text NOT NULL, width int NOT NULL CHECK(width BETWEEN 64 AND 4096), height int NOT NULL CHECK(height BETWEEN 64 AND 4096),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE jobs (
 id uuid PRIMARY KEY, generation_id uuid NOT NULL UNIQUE REFERENCES generations(id), idempotency_key varchar(200) NOT NULL UNIQUE,
 status varchar(20) NOT NULL CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')), attempts int NOT NULL DEFAULT 0 CHECK(attempts >= 0),
 max_attempts int NOT NULL DEFAULT 3, failure_reason text, provider_metadata jsonb NOT NULL DEFAULT '{}',
 available_at timestamptz NOT NULL DEFAULT now(), locked_at timestamptz, lease_token uuid,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz);
CREATE INDEX jobs_claim_idx ON jobs(status,available_at);
CREATE TABLE assets (
 id uuid PRIMARY KEY, generation_id uuid NOT NULL UNIQUE REFERENCES generations(id), storage_key text NOT NULL UNIQUE,
 sha256 char(64) NOT NULL, media_type varchar(100) NOT NULL, size_bytes bigint NOT NULL CHECK(size_bytes > 0),
 width int NOT NULL, height int NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX assets_checksum_idx ON assets(sha256);
CREATE TABLE asset_variants (id uuid PRIMARY KEY, asset_id uuid NOT NULL REFERENCES assets(id), kind varchar(60) NOT NULL,
 storage_key text NOT NULL UNIQUE, sha256 char(64) NOT NULL, size_bytes bigint NOT NULL CHECK(size_bytes > 0), created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE quality_reviews (id uuid PRIMARY KEY, asset_id uuid NOT NULL REFERENCES assets(id),
 kind varchar(20) NOT NULL CHECK(kind IN ('TECHNICAL','HUMAN')), decision varchar(20) NOT NULL CHECK(decision IN ('PASSED','APPROVED','REJECTED')),
 reasons text NOT NULL DEFAULT '', created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE publications (id uuid PRIMARY KEY, asset_id uuid NOT NULL REFERENCES assets(id), channel varchar(100) NOT NULL, external_id text, published_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE performance_metrics (id uuid PRIMARY KEY, publication_id uuid NOT NULL REFERENCES publications(id), name varchar(100) NOT NULL, value numeric(20,6) NOT NULL, measured_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE generation_costs (id uuid PRIMARY KEY, generation_id uuid NOT NULL REFERENCES generations(id), job_id uuid NOT NULL REFERENCES jobs(id),
 attempt int NOT NULL, provider varchar(100) NOT NULL, model varchar(100) NOT NULL, operation varchar(100) NOT NULL,
 input_usage bigint NOT NULL CHECK(input_usage >= 0), output_usage bigint NOT NULL CHECK(output_usage >= 0),
 estimated_cost numeric(18,8) NOT NULL CHECK(estimated_cost >= 0), currency char(3) NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(job_id,attempt,operation));
