package com.mediafactory.similarity;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmbeddingModelService {

  private final SimilarityService similarity;
  private final List<ImageEmbeddingProvider> providers;

  public EmbeddingModelService(
      SimilarityService similarity, List<ImageEmbeddingProvider> providers) {
    this.similarity = similarity;
    this.providers = providers;
  }

  /**
   * Discover outside a transaction. Configuration controls the endpoint, never a user-supplied
   * URL.
   */
  public Object register(String provider) {
    var adapter =
        providers.stream()
            .filter(p -> p.providerId().equals(provider))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown provider"));
    var model = adapter.modelMetadata();
    if (model.dimension() < 1 || model.dimension() > 2000) {
      throw new IllegalArgumentException("HNSW supports up to 2000 dimensions here");
    }
    return similarity
        .tx()
        .execute(
            s -> {
              UUID id = UUID.randomUUID();
              similarity
                  .db()
                  .sql(
                      "insert into"
                          + " embedding_models(id,provider,model,version,dimension,preprocessing)"
                          + " values(?,?,?,?,?,?) on conflict(provider,model,version) do nothing")
                  .params(
                      id,
                      model.provider(),
                      model.model(),
                      model.version(),
                      model.dimension(),
                      model.preprocessing())
                  .update();
              UUID persisted =
                  similarity
                      .db()
                      .sql(
                          "select id from embedding_models where provider=? and model=? and"
                              + " version=?")
                      .params(model.provider(), model.model(), model.version())
                      .query(UUID.class)
                      .single();
              var existing = similarity.model(persisted);
              if (existing.dimension() != model.dimension()
                  || !existing.preprocessing().equals(model.preprocessing())) {
                throw SimilarityService.conflict(
                    "Model identity reused with incompatible preprocessing or dimension");
              }
              similarity
                  .db()
                  .sql(
                      "create index if not exists embedding_"
                          + persisted.toString().replace("-", "")
                          + "_hnsw on asset_embeddings using hnsw((embedding::vector("
                          + model.dimension()
                          + ")) vector_cosine_ops) with(m=16,ef_construction=100) where model_id='"
                          + persisted
                          + "'")
                  .update();
              return existing;
            });
  }

  @Transactional
  public Object activate(UUID id) {
    var model = similarity.model(id);
    similarity.db().sql("lock table assets in share mode").update();
    similarity.db().sql("lock table embedding_models in exclusive mode").update();
    if (similarity
        .db()
        .sql(
            "select exists(select 1 from assets a where not exists(select 1 from similarity_jobs j"
                + " where j.asset_id=a.id and j.model_id=? and j.type='GENERATE_ASSET_EMBEDDING'"
                + " and j.status='SUCCEEDED'))")
        .param(id)
        .query(Boolean.class)
        .single()) {
      throw SimilarityService.conflict(
          "Backfill and analyze all originals before model activation");
    }
    similarity.db().sql("update embedding_models set active=false where active").update();
    similarity.db().sql("update embedding_models set active=true where id=?").param(id).update();
    return model;
  }

  @Transactional
  public Object enqueue(String type, UUID model, UUID collection, String key) {
    if (!Set.of("BACKFILL", "REINDEX", "CLUSTER_COLLECTION", "ANALYZE_COLLECTION_DIVERSITY")
        .contains(type)) {
      throw new IllegalArgumentException("Unsupported similarity job type");
    }
    if (key == null || key.isBlank() || key.length() > 150) {
      throw new IllegalArgumentException("Idempotency key required (1–150 characters)");
    }
    if ((type.equals("CLUSTER_COLLECTION") || type.equals("ANALYZE_COLLECTION_DIVERSITY"))
        && collection == null) {
      throw new IllegalArgumentException("Collection required");
    }
    similarity.model(model);
    similarity
        .db()
        .sql(
            "insert into similarity_jobs(id,type,model_id,collection_id,idempotency_key)"
                + " values(?,?,?,?,?) on conflict(idempotency_key) do nothing")
        .params(UUID.randomUUID(), type, model, collection, "control:" + key)
        .update();
    var job =
        similarity
            .db()
            .sql("select * from similarity_jobs where idempotency_key=?")
            .param("control:" + key)
            .query()
            .singleRow();
    if (!Objects.equals(job.get("collection_id"), collection)
        || !job.get("model_id").equals(model)
        || !job.get("type").equals(type)) {
      throw SimilarityService.conflict("Idempotency key reused with another request");
    }
    return job;
  }
}
