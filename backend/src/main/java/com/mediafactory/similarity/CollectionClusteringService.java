package com.mediafactory.similarity;

import static com.mediafactory.similarity.SimilarityService.JSON;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class CollectionClusteringService {
  private final SimilarityService similarity;

  public CollectionClusteringService(SimilarityService similarity) {
    this.similarity = similarity;
  }

  /**
   * Deterministic DBSCAN over bounded database-retrieved neighborhoods, with explicit noise (-1).
   */
  public static Map<UUID, Integer> dbscan(
      List<UUID> ordered, Map<UUID, List<UUID>> neighbors, int minimum) {
    var labels = new LinkedHashMap<UUID, Integer>();
    int cluster = 0;
    for (UUID point : ordered) {
      if (labels.containsKey(point)) continue;
      var near = neighbors.getOrDefault(point, List.of());
      if (near.size() + 1 < minimum) {
        labels.put(point, -1);
        continue;
      }
      int label = cluster++;
      labels.put(point, label);
      var queue = new ArrayDeque<>(near);
      var queued = new HashSet<>(near);
      while (!queue.isEmpty()) {
        UUID other = queue.removeFirst();
        Integer old = labels.get(other);
        if (old != null && old == -1) labels.put(other, label);
        if (old != null) continue;
        labels.put(other, label);
        var next = neighbors.getOrDefault(other, List.of());
        if (next.size() + 1 >= minimum) for (UUID id : next) if (queued.add(id)) queue.addLast(id);
      }
    }
    return labels;
  }

  public UUID cluster(
      UUID collection, ImageEmbeddingProvider.Model model, double epsilon, int minPoints) {
    if (epsilon <= 0 || epsilon >= 1 || minPoints < 2 || minPoints > 50)
      throw new IllegalArgumentException("Invalid clustering parameters");
    long started = System.nanoTime();
    try {
      var ids =
          similarity
              .db()
              .sql(
                  "select a.id from assets a join generations g on g.id=a.generation_id join"
                      + " concepts c on c.id=g.concept_id where c.collection_id=? order by a.id"
                      + " limit 5001")
              .param(collection)
              .query(UUID.class)
              .list();
      if (ids.size() > 5000)
        throw new IllegalArgumentException(
            "Collection exceeds 5000-asset clustering run limit; split into collections");
      var graph = new LinkedHashMap<UUID, List<UUID>>();
      var vectors = new LinkedHashMap<UUID, float[]>();
      double nearestSum = 0;
      int nearestCount = 0;
      // Collection snapshot is bounded; only this run's vectors are loaded, never the library.
      for (UUID id : ids) {
        var values =
            similarity
                .db()
                .sql("select embedding::text from asset_embeddings where asset_id=? and model_id=?")
                .params(id, model.id())
                .query(String.class)
                .list();
        if (values.isEmpty())
          throw SimilarityService.conflict("Backfill collection embeddings before clustering");
        vectors.put(id, JSON.readValue(values.getFirst(), float[].class));
      }
      String expr = "e.embedding::vector(" + model.dimension() + ")";
      for (UUID id : ids) {
        String v = ImageEmbeddingProvider.literal(vectors.get(id));
        var rows =
            similarity
                .db()
                .sql(
                    "select e.asset_id,1-("
                        + expr
                        + " <=> cast(? as vector("
                        + model.dimension()
                        + "))) as similarity from asset_embeddings e join assets a on"
                        + " a.id=e.asset_id join generations g on g.id=a.generation_id join"
                        + " concepts c on c.id=g.concept_id where e.model_id='"
                        + model.id()
                        + "' and c.collection_id=? and e.asset_id<>? order by "
                        + expr
                        + " <=> cast(? as vector("
                        + model.dimension()
                        + ")) limit 200")
                .params(v, collection, id, v)
                .query()
                .listOfRows();
        if (!rows.isEmpty()) {
          nearestSum += ((Number) rows.getFirst().get("similarity")).doubleValue();
          nearestCount++;
        }
        graph.put(
            id,
            rows.stream()
                .filter(r -> 1 - ((Number) r.get("similarity")).doubleValue() <= epsilon)
                .map(r -> (UUID) r.get("asset_id"))
                .sorted(Comparator.comparing(UUID::toString))
                .toList());
      }
      var labels = dbscan(ids, graph, minPoints);
      var grouped = new TreeMap<Integer, List<UUID>>();
      labels.forEach((id, label) -> grouped.computeIfAbsent(label, k -> new ArrayList<>()).add(id));
      int max =
          grouped.entrySet().stream()
              .filter(e -> e.getKey() >= 0)
              .mapToInt(e -> e.getValue().size())
              .max()
              .orElse(0);
      int outliers = grouped.getOrDefault(-1, List.of()).size();
      long pairs =
          similarity
              .db()
              .sql(
                  "select count(*) from similarity_comparisons s join assets a on"
                      + " a.id=s.source_asset_id join generations g on g.id=a.generation_id join"
                      + " concepts c on c.id=g.concept_id join assets b on b.id=s.target_asset_id"
                      + " join generations h on h.id=b.generation_id join concepts d on"
                      + " d.id=h.concept_id where c.collection_id=? and d.collection_id=? and"
                      + " s.model_id=? and s.profile_id=(select similarity_profile from collections"
                      + " where id=?) and final_classification in"
                      + " ('EXACT_DUPLICATE','PERCEPTUAL_DUPLICATE','NEAR_DUPLICATE')")
              .params(collection, collection, model.id(), collection)
              .query(Long.class)
              .single();
      var stats = new LinkedHashMap<String, Object>();
      stats.put("assetCount", ids.size());
      stats.put("clusterCount", grouped.size() - (grouped.containsKey(-1) ? 1 : 0));
      stats.put("largestClusterShare", ids.isEmpty() ? 0 : (double) max / ids.size());
      stats.put(
          "meanNearestNeighborSimilarity", nearestCount == 0 ? null : nearestSum / nearestCount);
      stats.put("nearDuplicatePairCount", pairs);
      stats.put("outlierCount", outliers);
      stats.put("coverage", 1);
      stats.put("neighborhoodLimit", 200);
      UUID run = UUID.randomUUID();
      similarity
          .tx()
          .executeWithoutResult(
              s -> {
                similarity
                    .db()
                    .sql(
                        "insert into"
                            + " collection_clustering_runs(id,collection_id,model_id,algorithm,parameters,statistics)"
                            + " values(?,?,?,'DBSCAN-cosine-bounded-v1',cast(? as jsonb),cast(? as"
                            + " jsonb))")
                    .params(
                        run,
                        collection,
                        model.id(),
                        JSON.writeValueAsString(
                            Map.of(
                                "epsilon",
                                epsilon,
                                "minPoints",
                                minPoints,
                                "neighborLimit",
                                200,
                                "order",
                                "UUID ascending",
                                "maxAssets",
                                5000)),
                        JSON.writeValueAsString(stats))
                    .update();
                for (var entry : grouped.entrySet()) {
                  UUID cluster = UUID.randomUUID();
                  boolean noise = entry.getKey() == -1;
                  float[] centroid = new float[model.dimension()];
                  if (!noise) {
                    for (UUID id : entry.getValue()) {
                      float[] v = vectors.get(id);
                      for (int i = 0; i < v.length; i++) centroid[i] += v[i];
                    }
                    double norm = 0;
                    for (float f : centroid) norm += f * f;
                    if (norm == 0) throw new IllegalArgumentException("Zero cluster centroid");
                    for (int i = 0; i < centroid.length; i++)
                      centroid[i] /= (float) Math.sqrt(norm);
                  }
                  UUID representative =
                      noise
                          ? null
                          : entry.getValue().stream()
                              .min(
                                  Comparator.comparingDouble(
                                      id -> distance(vectors.get(id), centroid)))
                              .orElseThrow();
                  similarity
                      .db()
                      .sql(
                          "insert into"
                              + " collection_clusters(id,run_id,ordinal,centroid,representative_asset_id,outlier)"
                              + " values(?,?,?,cast(? as vector),?,?)")
                      .params(
                          cluster,
                          run,
                          entry.getKey(),
                          noise ? null : ImageEmbeddingProvider.literal(centroid),
                          representative,
                          noise)
                      .update();
                  for (UUID id : entry.getValue())
                    similarity
                        .db()
                        .sql(
                            "insert into"
                                + " collection_cluster_members(cluster_id,asset_id,distance_to_centroid)"
                                + " values(?,?,?)")
                        .params(cluster, id, noise ? null : distance(vectors.get(id), centroid))
                        .update();
                }
              });
      return run;
    } finally {
      similarity
          .metrics()
          .timer(
              "media_factory_collection_clustering_duration",
              "model",
              model.model(),
              "version",
              model.version())
          .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }
  }

  static double distance(float[] a, float[] b) {
    double dot = 0;
    for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
    return Math.max(0, 1 - dot);
  }

  public Map<String, Object> diversity(UUID collection) {
    var output = new LinkedHashMap<String, Object>();
    output.put("collectionId", collection);
    output.put("model", similarity.activeModel());
    var runs =
        similarity
            .db()
            .sql(
                "select * from collection_clustering_runs where collection_id=? and model_id=?"
                    + " order by created_at desc limit 1")
            .params(collection, similarity.activeModel().id())
            .query()
            .listOfRows();
    output.put("run", runs.isEmpty() ? null : runs.getFirst());
    var clusters = new ArrayList<Map<String, Object>>();
    if (!runs.isEmpty())
      for (var row :
          similarity
              .db()
              .sql(
                  "select id,run_id,ordinal,representative_asset_id,outlier from"
                      + " collection_clusters where run_id=? order by ordinal")
              .param(runs.getFirst().get("id"))
              .query()
              .listOfRows()) {
        var item = new LinkedHashMap<>(row);
        item.put(
            "members",
            similarity
                .db()
                .sql(
                    "select asset_id,distance_to_centroid from collection_cluster_members where"
                        + " cluster_id=? order by asset_id limit 5000")
                .param(row.get("id"))
                .query()
                .listOfRows());
        clusters.add(item);
      }
    output.put("clusters", clusters);
    output.put(
        "embeddingCoverage",
        similarity
            .db()
            .sql(
                "select count(*) as total,count(e.id) as ready from assets a join generations g on"
                    + " g.id=a.generation_id join concepts c on c.id=g.concept_id left join"
                    + " asset_embeddings e on e.asset_id=a.id and e.model_id=? where"
                    + " c.collection_id=?")
            .params(similarity.activeModel().id(), collection)
            .query()
            .singleRow());
    return output;
  }
}
