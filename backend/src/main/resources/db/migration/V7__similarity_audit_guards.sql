-- V6 is already deployed. Add hardening without changing its applied checksum.
ALTER TABLE embedding_compute_usage
    ALTER COLUMN job_id DROP NOT NULL;
CREATE FUNCTION immutable_similarity_history() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Similarity history is immutable';
END $$;
CREATE TRIGGER immutable_clustering_runs
    BEFORE UPDATE OR DELETE ON collection_clustering_runs FOR EACH ROW
EXECUTE FUNCTION immutable_similarity_history();
CREATE TRIGGER immutable_clusters
    BEFORE UPDATE OR DELETE ON collection_clusters FOR EACH ROW
EXECUTE FUNCTION immutable_similarity_history();
CREATE TRIGGER immutable_cluster_members
    BEFORE UPDATE OR DELETE ON collection_cluster_members FOR EACH ROW
EXECUTE FUNCTION immutable_similarity_history();
CREATE TRIGGER immutable_diversity_events
    BEFORE UPDATE OR DELETE ON diversity_guard_events FOR EACH ROW
EXECUTE FUNCTION immutable_similarity_history();
ALTER TABLE similarity_comparisons
    ADD CONSTRAINT valid_automatic_classification CHECK (automatic_classification IN
                                                         ('EXACT_DUPLICATE', 'PERCEPTUAL_DUPLICATE',
                                                          'NEAR_DUPLICATE', 'VISUALLY_SIMILAR',
                                                          'SEMANTICALLY_SIMILAR', 'DISTINCT'));
ALTER TABLE similarity_comparisons
    ADD CONSTRAINT valid_human_classification CHECK (human_classification IN
                                                     ('EXACT_DUPLICATE', 'PERCEPTUAL_DUPLICATE',
                                                      'NEAR_DUPLICATE', 'VISUALLY_SIMILAR',
                                                      'SEMANTICALLY_SIMILAR', 'DISTINCT'));
ALTER TABLE similarity_comparisons
    ADD CONSTRAINT consistent_final_classification CHECK (final_classification =
                                                          coalesce(human_classification, automatic_classification));
CREATE FUNCTION protect_comparison_identity() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
 IF (OLD.source_asset_id,OLD.target_asset_id,OLD.model_id,OLD.profile_id,OLD.profile_snapshot) IS DISTINCT FROM (NEW.source_asset_id,NEW.target_asset_id,NEW.model_id,NEW.profile_id,NEW.profile_snapshot) THEN RAISE EXCEPTION 'Comparison identity and initial policy snapshot are immutable';
END IF;
RETURN NEW;
END $$;
CREATE TRIGGER comparison_identity
    BEFORE UPDATE
    ON similarity_comparisons
    FOR EACH ROW EXECUTE FUNCTION protect_comparison_identity();
