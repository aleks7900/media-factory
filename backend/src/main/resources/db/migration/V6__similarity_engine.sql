CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE similarity_profiles (
 id varchar(40) PRIMARY KEY, revision integer NOT NULL DEFAULT 1,
 duplicate_distance integer NOT NULL CHECK(duplicate_distance BETWEEN 0 AND 32),
 near_distance integer NOT NULL CHECK(near_distance BETWEEN duplicate_distance AND 40),
 near_similarity float8 NOT NULL CHECK(near_similarity BETWEEN 0 AND 1),
 similar_threshold float8 NOT NULL CHECK(similar_threshold BETWEEN 0 AND near_similarity),
 block_duplicates boolean NOT NULL, block_near boolean NOT NULL,
 guard_mode varchar(10) NOT NULL CHECK(guard_mode IN ('WARN','BLOCK')),
 saturation_threshold float8 NOT NULL CHECK(saturation_threshold BETWEEN 0 AND 1));
INSERT INTO similarity_profiles VALUES
 ('WALLPAPER',1,4,10,.94,.86,true,false,'WARN',.80),
 ('STOCK_STRICT',1,4,10,.94,.86,true,true,'BLOCK',.65),
 ('SOCIAL',1,3,8,.96,.88,false,false,'WARN',.90),
 ('GENERATION_DIVERSITY',1,4,10,.94,.86,true,false,'WARN',.75);
ALTER TABLE collections ADD COLUMN similarity_profile varchar(40) NOT NULL DEFAULT 'WALLPAPER' REFERENCES similarity_profiles(id);

CREATE TABLE embedding_models (
 id uuid PRIMARY KEY, provider varchar(80) NOT NULL, model varchar(200) NOT NULL, version varchar(100) NOT NULL,
 dimension integer NOT NULL CHECK(dimension BETWEEN 1 AND 2000), preprocessing varchar(500) NOT NULL,
 active boolean NOT NULL DEFAULT false, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(provider,model,version));
CREATE UNIQUE INDEX embedding_one_active ON embedding_models(active) WHERE active;
CREATE FUNCTION protect_model_identity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF (OLD.provider,OLD.model,OLD.version,OLD.dimension,OLD.preprocessing) IS DISTINCT FROM (NEW.provider,NEW.model,NEW.version,NEW.dimension,NEW.preprocessing) THEN RAISE EXCEPTION 'Embedding model identity is immutable'; END IF;
 RETURN NEW; END $$;
CREATE TRIGGER immutable_model_identity BEFORE UPDATE ON embedding_models FOR EACH ROW EXECUTE FUNCTION protect_model_identity();
INSERT INTO embedding_models VALUES ('00000000-0000-0000-0000-000000000501','local-clip','openai/clip-vit-base-patch32','3d74acf9a28c67741b2f4f2ea7635f0aaf6f0268',512,'RGB/bicubic-shortest224/center224/rescale255/CLIP-mean-std',true,now());
CREATE TABLE asset_fingerprints (
 id uuid PRIMARY KEY, asset_id uuid NOT NULL REFERENCES assets(id), type varchar(30) NOT NULL,
 value varchar(256) NOT NULL, algorithm varchar(80) NOT NULL, algorithm_version varchar(80) NOT NULL,
 phash_bits bit(64), low_information boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(asset_id,type,algorithm_version));
CREATE INDEX fingerprint_exact_lookup ON asset_fingerprints(type,value);
CREATE INDEX fingerprint_phash_hnsw ON asset_fingerprints USING hnsw(phash_bits bit_hamming_ops) WITH(m=16,ef_construction=100) WHERE type='PHASH';
INSERT INTO asset_fingerprints(id,asset_id,type,value,algorithm,algorithm_version)
 SELECT gen_random_uuid(),id,'SHA256',sha256,'SHA-256','1' FROM assets;
CREATE TABLE asset_embeddings (
 id uuid PRIMARY KEY, asset_id uuid NOT NULL REFERENCES assets(id), model_id uuid NOT NULL REFERENCES embedding_models(id),
 dimension integer NOT NULL, embedding vector NOT NULL, normalized boolean NOT NULL CHECK(normalized),
 metadata jsonb NOT NULL DEFAULT '{}', created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(asset_id,model_id), CHECK(vector_dims(embedding)=dimension), CHECK(abs(vector_norm(embedding)-1)<0.001));
CREATE INDEX embedding_clip_hnsw ON asset_embeddings USING hnsw((embedding::vector(512)) vector_cosine_ops) WITH(m=16,ef_construction=100) WHERE model_id='00000000-0000-0000-0000-000000000501';
CREATE FUNCTION protect_embedding() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Historical features are immutable'; END IF;
 IF NEW.dimension<>(SELECT dimension FROM embedding_models WHERE id=NEW.model_id) THEN RAISE EXCEPTION 'Incompatible embedding dimension'; END IF;
 RETURN NEW; END $$;
CREATE TRIGGER immutable_embeddings BEFORE INSERT OR UPDATE OR DELETE ON asset_embeddings FOR EACH ROW EXECUTE FUNCTION protect_embedding();
CREATE TRIGGER immutable_fingerprints BEFORE UPDATE OR DELETE ON asset_fingerprints FOR EACH ROW EXECUTE FUNCTION immutable_review_action();

CREATE TABLE similarity_comparisons (
 id uuid PRIMARY KEY, source_asset_id uuid NOT NULL REFERENCES assets(id), target_asset_id uuid NOT NULL REFERENCES assets(id),
 model_id uuid NOT NULL REFERENCES embedding_models(id), profile_id varchar(40) NOT NULL REFERENCES similarity_profiles(id),
 profile_snapshot jsonb NOT NULL, sha256_match boolean NOT NULL, phash_distance integer CHECK(phash_distance BETWEEN 0 AND 64),
 embedding_similarity float8 CHECK(embedding_similarity BETWEEN -1.00001 AND 1.00001),
 context jsonb NOT NULL, automatic_classification varchar(40) NOT NULL,
 human_classification varchar(40), final_classification varchar(40) NOT NULL,
 explanation varchar(2000) NOT NULL, reason varchar(2000), reviewed_by varchar(200), reviewed_at timestamptz,
 revision integer NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now(),
 CHECK(source_asset_id<target_asset_id), UNIQUE(source_asset_id,target_asset_id,model_id,profile_id));
CREATE INDEX similarity_target ON similarity_comparisons(target_asset_id,model_id);
CREATE INDEX similarity_review_queue ON similarity_comparisons(final_classification,created_at DESC);
CREATE TABLE similarity_evaluation_history(id uuid PRIMARY KEY,comparison_id uuid NOT NULL REFERENCES similarity_comparisons(id),evidence jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
CREATE TRIGGER immutable_similarity_evaluations BEFORE UPDATE OR DELETE ON similarity_evaluation_history FOR EACH ROW EXECUTE FUNCTION immutable_review_action();
CREATE TABLE similarity_findings (
 id uuid PRIMARY KEY, comparison_id uuid NOT NULL REFERENCES similarity_comparisons(id),
 asset_id uuid NOT NULL REFERENCES assets(id), code varchar(40) NOT NULL, metadata jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(comparison_id,asset_id));
CREATE TABLE similarity_review_actions (
 id uuid PRIMARY KEY, comparison_id uuid REFERENCES similarity_comparisons(id), group_id uuid,
 action varchar(40) NOT NULL, reason varchar(2000) NOT NULL, actor varchar(200) NOT NULL,
 before_state jsonb NOT NULL, after_state jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TRIGGER immutable_similarity_actions BEFORE UPDATE OR DELETE ON similarity_review_actions FOR EACH ROW EXECUTE FUNCTION immutable_review_action();
CREATE TABLE duplicate_groups (
 id uuid PRIMARY KEY, model_id uuid NOT NULL REFERENCES embedding_models(id), type varchar(30) NOT NULL CHECK(type IN ('EXACT','PERCEPTUAL','NEAR_DUPLICATE')),
 status varchar(20) NOT NULL DEFAULT 'OPEN', canonical_asset_id uuid REFERENCES assets(id), revision integer NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now());
ALTER TABLE similarity_review_actions ADD FOREIGN KEY(group_id) REFERENCES duplicate_groups(id);
CREATE TABLE duplicate_group_members (
 group_id uuid NOT NULL REFERENCES duplicate_groups(id), asset_id uuid NOT NULL REFERENCES assets(id),
 relationship varchar(40) NOT NULL, added_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(group_id,asset_id));
CREATE INDEX duplicate_member_asset ON duplicate_group_members(asset_id);
CREATE TABLE similarity_jobs (
 id uuid PRIMARY KEY, type varchar(50) NOT NULL, asset_id uuid REFERENCES assets(id), collection_id uuid REFERENCES collections(id),
 model_id uuid NOT NULL REFERENCES embedding_models(id), parent_id uuid REFERENCES similarity_jobs(id),
 idempotency_key varchar(200) NOT NULL UNIQUE, status varchar(20) NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
 attempts integer NOT NULL DEFAULT 0, max_attempts integer NOT NULL DEFAULT 4,
 progress integer NOT NULL DEFAULT 0, total integer NOT NULL DEFAULT 0, cursor_id uuid,
 failure_reason varchar(2000), provider_metadata jsonb NOT NULL DEFAULT '{}',
 available_at timestamptz NOT NULL DEFAULT now(), locked_at timestamptz, lease_token uuid,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz);
CREATE INDEX similarity_job_queue ON similarity_jobs(available_at,created_at) WHERE status='QUEUED';
CREATE INDEX similarity_job_asset ON similarity_jobs(asset_id,model_id);
CREATE FUNCTION enqueue_asset_similarity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 INSERT INTO asset_fingerprints(id,asset_id,type,value,algorithm,algorithm_version) VALUES(gen_random_uuid(),NEW.id,'SHA256',NEW.sha256,'SHA-256','1');
 INSERT INTO similarity_jobs(id,type,asset_id,model_id,idempotency_key)
 SELECT gen_random_uuid(),'GENERATE_ASSET_EMBEDDING',NEW.id,id,'asset:'||NEW.id||':'||id FROM embedding_models WHERE active;
 RETURN NEW; END $$;
CREATE TRIGGER asset_similarity_enqueue AFTER INSERT ON assets FOR EACH ROW EXECUTE FUNCTION enqueue_asset_similarity();
ALTER TABLE provider_permits ADD COLUMN similarity_job_id uuid UNIQUE REFERENCES similarity_jobs(id);
ALTER TABLE provider_permits DROP CONSTRAINT permit_owner;
ALTER TABLE provider_permits ADD CONSTRAINT permit_owner CHECK(num_nonnulls(job_id,qa_job_id,similarity_job_id)=1);
CREATE TABLE embedding_compute_usage (
 id uuid PRIMARY KEY, job_id uuid NOT NULL REFERENCES similarity_jobs(id), attempt integer NOT NULL,
 provider varchar(80) NOT NULL, model varchar(200) NOT NULL, version varchar(100) NOT NULL,
 operation varchar(40) NOT NULL DEFAULT 'IMAGE_EMBEDDING', input_usage integer NOT NULL, output_usage integer,
 duration_ms bigint, device varchar(30), external_api_cost numeric NOT NULL DEFAULT 0,
 estimated_compute_cost numeric, currency varchar(3) NOT NULL DEFAULT 'USD', outcome varchar(20) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(job_id,attempt));
CREATE TABLE collection_clustering_runs (
 id uuid PRIMARY KEY, collection_id uuid NOT NULL REFERENCES collections(id), model_id uuid NOT NULL REFERENCES embedding_models(id),
 algorithm varchar(100) NOT NULL, parameters jsonb NOT NULL, statistics jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX cluster_collection_runs ON collection_clustering_runs(collection_id,created_at DESC);
CREATE TABLE collection_clusters (
 id uuid PRIMARY KEY, run_id uuid NOT NULL REFERENCES collection_clustering_runs(id), ordinal integer NOT NULL,
 centroid vector, representative_asset_id uuid REFERENCES assets(id), outlier boolean NOT NULL,
 UNIQUE(run_id,ordinal));
CREATE TABLE collection_cluster_members (
 cluster_id uuid NOT NULL REFERENCES collection_clusters(id), asset_id uuid NOT NULL REFERENCES assets(id),
 distance_to_centroid float8, PRIMARY KEY(cluster_id,asset_id));
CREATE TABLE generation_batches (
 id uuid PRIMARY KEY, collection_id uuid NOT NULL REFERENCES collections(id), concept_id uuid NOT NULL REFERENCES concepts(id),
 idempotency_key varchar(200) NOT NULL UNIQUE, request jsonb NOT NULL, total integer NOT NULL CHECK(total BETWEEN 1 AND 10000),
 batch_size integer NOT NULL CHECK(batch_size BETWEEN 1 AND 50), dispatched integer NOT NULL DEFAULT 0,
 status varchar(30) NOT NULL DEFAULT 'RUNNING', pause_reason varchar(2000), revision integer NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE generation_batch_members (
 batch_id uuid NOT NULL REFERENCES generation_batches(id), generation_id uuid NOT NULL UNIQUE REFERENCES generations(id),
 ordinal integer NOT NULL, PRIMARY KEY(batch_id,ordinal));
CREATE TABLE generation_batch_actions(id uuid PRIMARY KEY,batch_id uuid NOT NULL REFERENCES generation_batches(id),actor varchar(200) NOT NULL,action varchar(40) NOT NULL,reason varchar(2000) NOT NULL,before_request jsonb NOT NULL,after_request jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
CREATE TRIGGER immutable_batch_actions BEFORE UPDATE OR DELETE ON generation_batch_actions FOR EACH ROW EXECUTE FUNCTION immutable_review_action();
CREATE TABLE diversity_guard_events (
 id uuid PRIMARY KEY, collection_id uuid NOT NULL REFERENCES collections(id), generation_id uuid REFERENCES generations(id),
 batch_id uuid REFERENCES generation_batches(id), decision varchar(40) NOT NULL, evidence jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE concept_embeddings (
 id uuid PRIMARY KEY, concept_id uuid NOT NULL REFERENCES concepts(id), model_id uuid NOT NULL REFERENCES embedding_models(id),
 prompt_sha256 varchar(64) NOT NULL, embedding vector NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(concept_id,model_id,prompt_sha256));
CREATE TRIGGER immutable_concept_embeddings BEFORE UPDATE OR DELETE ON concept_embeddings FOR EACH ROW EXECUTE FUNCTION immutable_review_action();
ALTER TABLE quality_findings DROP CONSTRAINT quality_findings_source_check;
ALTER TABLE quality_findings ADD CONSTRAINT quality_findings_source_check CHECK(source IN ('TECHNICAL','VISION_MODEL','HUMAN','SIMILARITY'));

-- Defense in depth: strict collections require complete active-model analysis and no blocking pair.
CREATE FUNCTION similarity_publication_block_reason(asset uuid) RETURNS text LANGUAGE plpgsql AS $$
DECLARE profile similarity_profiles; active_model uuid;
BEGIN
 SELECT p.* INTO profile FROM assets a JOIN generations g ON g.id=a.generation_id JOIN concepts c ON c.id=g.concept_id
 JOIN collections col ON col.id=c.collection_id JOIN similarity_profiles p ON p.id=col.similarity_profile WHERE a.id=asset;
 SELECT id INTO active_model FROM embedding_models WHERE active;
 IF profile.id='STOCK_STRICT' AND NOT EXISTS(SELECT 1 FROM similarity_jobs WHERE asset_id=asset AND model_id=active_model AND type='GENERATE_ASSET_EMBEDDING' AND status='SUCCEEDED') THEN
 RETURN 'Similarity analysis incomplete for strict publication'; END IF;
 IF EXISTS(SELECT 1 FROM similarity_comparisons s WHERE model_id=active_model AND (source_asset_id=asset OR target_asset_id=asset)
 AND (CASE WHEN human_classification IS NOT NULL THEN
   (profile.block_duplicates AND human_classification IN ('EXACT_DUPLICATE','PERCEPTUAL_DUPLICATE')) OR (profile.block_near AND human_classification='NEAR_DUPLICATE')
 ELSE (profile.block_duplicates AND (sha256_match OR (phash_distance<=profile.duplicate_distance AND NOT coalesce((context->>'lowInformation')::boolean,false))))
   OR (profile.block_near AND phash_distance<=profile.near_distance AND embedding_similarity>=profile.near_similarity AND NOT coalesce((context->>'lowInformation')::boolean,false) AND NOT coalesce((context->>'sameGenerationFamily')::boolean,false)) END)
 AND NOT EXISTS(SELECT 1 FROM duplicate_groups g JOIN duplicate_group_members m ON m.group_id=g.id WHERE g.model_id=active_model AND g.status='OPEN' AND g.canonical_asset_id=asset AND m.asset_id=CASE WHEN s.source_asset_id=asset THEN s.target_asset_id ELSE s.source_asset_id END)) THEN
 RETURN 'Similarity policy blocks publication'; END IF;
 RETURN null; END $$;
CREATE FUNCTION require_similarity_publication() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE asset uuid; reason text;
BEGIN
 IF TG_TABLE_NAME='generations' THEN
   IF NEW.status<>'PUBLISHED' THEN RETURN NEW; END IF;
   SELECT id INTO asset FROM assets WHERE generation_id=NEW.id;
 ELSE asset:=NEW.asset_id; END IF;
 reason:=similarity_publication_block_reason(asset);
 IF reason IS NOT NULL THEN RAISE EXCEPTION '%',reason; END IF;
 RETURN NEW; END $$;
CREATE TRIGGER publication_similarity BEFORE INSERT OR UPDATE ON publications FOR EACH ROW EXECUTE FUNCTION require_similarity_publication();
CREATE TRIGGER generation_similarity BEFORE UPDATE OF status ON generations FOR EACH ROW EXECUTE FUNCTION require_similarity_publication();
