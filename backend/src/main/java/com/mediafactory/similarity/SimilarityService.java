package com.mediafactory.similarity;

import static com.mediafactory.quality.QualityModels.Category;
import static com.mediafactory.quality.QualityModels.Code;
import static com.mediafactory.quality.QualityModels.Finding;
import static com.mediafactory.quality.QualityModels.Severity;
import static com.mediafactory.quality.QualityModels.Source;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SimilarityService {

  public static final JsonMapper JSON = JsonMapper.builder().build();
  public final boolean enabled;
  final JdbcClient db;
  final TransactionTemplate tx;
  final MeterRegistry metrics;
  private final SimilarityPolicy policy;
  private final Map<String, ImageEmbeddingProvider> providers;
  private final int topK;
  private final double minimum;

  public SimilarityService(
      JdbcClient db,
      TransactionTemplate tx,
      MeterRegistry metrics,
      SimilarityPolicy policy,
      List<ImageEmbeddingProvider> providers,
      @Value("${media.similarity.enabled:true}") boolean enabled,
      @Value("${media.similarity.top-k:50}") int topK,
      @Value("${media.similarity.minimum-similarity:0.70}") double minimum) {
    this.db = db;
    this.tx = tx;
    this.metrics = metrics;
    this.policy = policy;
    this.enabled = enabled;
    this.topK = Math.clamp(topK, 1, 200);
    this.minimum = minimum;
    var map = new HashMap<String, ImageEmbeddingProvider>();
    providers.forEach(p -> map.put(p.providerId(), p));
    this.providers = Map.copyOf(map);
    metrics.gauge(
        "media_factory_embedding_backlog",
        this,
        s ->
            s.db
                .sql("select count(*) from similarity_jobs where status in ('QUEUED','RUNNING')")
                .query(Long.class)
                .single()
                .doubleValue());
  }

  public static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  public JdbcClient db() {
    return db;
  }

  public TransactionTemplate tx() {
    return tx;
  }

  public MeterRegistry metrics() {
    return metrics;
  }

  public boolean enabled() {
    return enabled;
  }

  public ImageEmbeddingProvider provider(ImageEmbeddingProvider.Model model) {
    var p = providers.get(model.provider());
    if (p == null) {
      throw new IllegalArgumentException("Embedding provider unavailable");
    }
    return p;
  }

  public ImageEmbeddingProvider.Model model(UUID id) {
    var r = db.sql("select * from embedding_models where id=?").param(id).query().singleRow();
    return new ImageEmbeddingProvider.Model(
        id,
        (String) r.get("provider"),
        (String) r.get("model"),
        (String) r.get("version"),
        ((Number) r.get("dimension")).intValue(),
        (String) r.get("preprocessing"));
  }

  public ImageEmbeddingProvider.Model activeModel() {
    return model(db.sql("select id from embedding_models where active").query(UUID.class).single());
  }

  public Map<String, Object> asset(UUID id) {
    return db
        .sql(
            "select a.*,g.concept_id,g.parent_id,g.prompt_version_id,g.model as"
                + " generation_model,c.collection_id,col.project_id,col.similarity_profile from"
                + " assets a join generations g on g.id=a.generation_id join concepts c on"
                + " c.id=g.concept_id join collections col on col.id=c.collection_id where a.id=?")
        .param(id)
        .query()
        .listOfRows()
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
  }

  public boolean exactDuplicate(UUID id) {
    return db.sql(
            "select exists(select 1 from assets a join assets b on a.sha256=b.sha256 and b.id<>a.id"
                + " where a.id=? and (b.created_at,b.id)<(a.created_at,a.id))")
        .param(id)
        .query(Boolean.class)
        .single();
  }

  public Map<String, Object> profile(UUID asset) {
    return db.sql(
            "select p.* from similarity_profiles p join collections col on"
                + " col.similarity_profile=p.id join concepts c on c.collection_id=col.id join"
                + " generations g on g.concept_id=c.id join assets a on a.generation_id=g.id where"
                + " a.id=?")
        .param(asset)
        .query()
        .singleRow();
  }

  public String state(UUID asset, UUID model) {
    var rows =
        db.sql(
                "select status from similarity_jobs where asset_id=? and model_id=? and"
                    + " type='GENERATE_ASSET_EMBEDDING' order by created_at desc limit 1")
            .params(asset, model)
            .query(String.class)
            .list();
    return rows.isEmpty()
        ? "PENDING"
        : switch (rows.getFirst()) {
          case "SUCCEEDED" -> "READY";
          case "FAILED" -> "FAILED";
          default -> "PENDING";
        };
  }

  @Transactional
  public UUID enqueue(UUID asset, UUID model, UUID parent) {
    String key = "asset:" + asset + ":" + model;
    UUID id = UUID.randomUUID();
    db.sql(
            "insert into similarity_jobs(id,type,asset_id,model_id,parent_id,idempotency_key)"
                + " values(?,'GENERATE_ASSET_EMBEDDING',?,?,?,?) on conflict(idempotency_key) do"
                + " nothing")
        .params(id, asset, model, parent, key)
        .update();
    return db.sql("select id from similarity_jobs where idempotency_key=?")
        .param(key)
        .query(UUID.class)
        .single();
  }

  public void fingerprints(UUID asset, PerceptualHash.Fingerprint f) {
    db.sql(
            "insert into"
                + " asset_fingerprints(id,asset_id,type,value,algorithm,algorithm_version,phash_bits,low_information)"
                + " values(?,?,'PHASH',?,'DCT-II',?,cast(? as bit(64)),?) on conflict do nothing")
        .params(
            UUID.randomUUID(),
            asset,
            f.bits(),
            PerceptualHash.VERSION,
            f.bits(),
            f.lowInformation())
        .update();
  }

  public void persistEmbedding(
      UUID asset,
      ImageEmbeddingProvider.Model model,
      float[] vector,
      Map<String, Object> metadata) {
    db.sql(
            "insert into"
                + " asset_embeddings(id,asset_id,model_id,dimension,embedding,normalized,metadata)"
                + " values(?,?,?,?,cast(? as vector),true,cast(? as jsonb)) on"
                + " conflict(asset_id,model_id) do nothing")
        .params(
            UUID.randomUUID(),
            asset,
            model.id(),
            model.dimension(),
            ImageEmbeddingProvider.literal(
                ImageEmbeddingProvider.validate(vector, model.dimension())),
            JSON.writeValueAsString(metadata))
        .update();
  }

  /**
   * Database performs candidate search. Values inserted into index expressions are validated model
   * metadata.
   */
  public List<Map<String, Object>> nearest(
      float[] vector, ImageEmbeddingProvider.Model model, int limit) {
    return nearest(vector, model, limit, null, null);
  }

  public List<Map<String, Object>> nearest(
      float[] vector, ImageEmbeddingProvider.Model model, int limit, String scope, UUID source) {
    ImageEmbeddingProvider.validate(vector, model.dimension());
    String expression = "e.embedding::vector(" + model.dimension() + ")";
    String filter = "";
    var params = new ArrayList<Object>();
    params.add(ImageEmbeddingProvider.literal(vector));
    if (source != null) {
      params.add(source);
      filter = " and e.asset_id<>?";
      var a = asset(source);
      if ("SAME_COLLECTION".equals(scope)) {
        filter += " and c.collection_id=?";
        params.add(a.get("collection_id"));
      } else if ("PROJECT".equals(scope)) {
        filter += " and col.project_id=?";
        params.add(a.get("project_id"));
      }
    }
    params.add(ImageEmbeddingProvider.literal(vector));
    params.add(Math.clamp(limit, 1, 201));
    String where = filter;
    return tx.execute(
        s -> {
          db.sql("set local hnsw.ef_search=200").update();
          db.sql("set local hnsw.iterative_scan='strict_order'").update();
          return db.sql(
                  "select e.asset_id,1-("
                      + expression
                      + " <=> cast(? as vector("
                      + model.dimension()
                      + "))) as embedding_similarity from asset_embeddings e join assets a on"
                      + " a.id=e.asset_id join generations g on g.id=a.generation_id join concepts"
                      + " c on c.id=g.concept_id join collections col on col.id=c.collection_id"
                      + " where e.model_id='"
                      + model.id()
                      + "'"
                      + where
                      + " order by "
                      + expression
                      + " <=> cast(? as vector("
                      + model.dimension()
                      + ")) limit ?")
              .params(params)
              .query()
              .listOfRows();
        });
  }

  public List<UUID> exactCandidates(UUID asset) {
    return db.sql(
            "select b.id from assets a join assets b on a.sha256=b.sha256 and a.id<>b.id where"
                + " a.id=? order by b.created_at,b.id limit 200")
        .param(asset)
        .query(UUID.class)
        .list();
  }

  public void analyzeExact(UUID asset, ImageEmbeddingProvider.Model model) {
    for (UUID other : exactCandidates(asset)) {
      compare(asset, other, model, null);
    }
  }

  public void analyze(UUID asset, ImageEmbeddingProvider.Model model) {
    var candidates = new LinkedHashMap<UUID, Double>();
    exactCandidates(asset).forEach(id -> candidates.put(id, null));
    var hashes =
        db.sql(
                "select phash_bits::text from asset_fingerprints where asset_id=? and type='PHASH'"
                    + " and algorithm_version=?")
            .params(asset, PerceptualHash.VERSION)
            .query(String.class)
            .list();
    if (!hashes.isEmpty()) {
      db.sql(
              "select asset_id from (select asset_id,phash_bits <~> cast(? as bit(64)) as distance"
                  + " from asset_fingerprints where type='PHASH' and algorithm_version=? and"
                  + " asset_id<>? order by phash_bits <~> cast(? as bit(64)) limit ?) candidates"
                  + " where distance<=(select max(near_distance) from similarity_profiles)")
          .params(hashes.getFirst(), PerceptualHash.VERSION, asset, hashes.getFirst(), topK)
          .query(UUID.class)
          .list()
          .forEach(id -> candidates.putIfAbsent(id, null));
    }
    var vectors =
        db.sql("select embedding::text from asset_embeddings where asset_id=? and model_id=?")
            .params(asset, model.id())
            .query(String.class)
            .list();
    if (!vectors.isEmpty()) {
      for (var row : nearest(JSON.readValue(vectors.getFirst(), float[].class), model, topK + 1)) {
        UUID id = (UUID) row.get("asset_id");
        double similarity = ((Number) row.get("embedding_similarity")).doubleValue();
        if (!id.equals(asset) && similarity >= minimum) {
          candidates.put(id, similarity);
        }
      }
    }
    candidates.forEach((id, cosine) -> compare(asset, id, model, cosine));
  }

  public void compare(UUID first, UUID second, ImageEmbeddingProvider.Model model, Double cosine) {
    UUID a = first.toString().compareTo(second.toString()) < 0 ? first : second,
        b = a.equals(first) ? second : first;
    tx.executeWithoutResult(
        status -> {
          db.sql("select pg_advisory_xact_lock(hashtextextended(?,0))")
              .param("similarity:" + model.id())
              .query()
              .singleRow();
          var source = asset(a);
          var target = asset(b);
          boolean exact = source.get("sha256").equals(target.get("sha256"));
          var hashes =
              db.sql(
                      "select asset_id,phash_bits::text as bits,low_information from"
                          + " asset_fingerprints where asset_id in (?,?) and type='PHASH' and"
                          + " algorithm_version=?")
                  .params(a, b, PerceptualHash.VERSION)
                  .query()
                  .listOfRows();
          Integer distance =
              hashes.size() == 2
                  ? PerceptualHash.distance(
                  hashes.get(0).get("bits").toString(), hashes.get(1).get("bits").toString())
                  : null;
          boolean low =
              hashes.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("low_information")));
          Double sim = cosine;
          if (sim == null) {
            sim =
                db.sql(
                        "select 1-(x.embedding <=> y.embedding) from asset_embeddings x join"
                            + " asset_embeddings y on x.model_id=y.model_id where x.asset_id=? and"
                            + " y.asset_id=? and x.model_id=?")
                    .params(a, b, model.id())
                    .query(Double.class)
                    .optional()
                    .orElse(null);
          }
          boolean family =
              root((UUID) source.get("generation_id"))
                  .equals(root((UUID) target.get("generation_id")));
          var context =
              new SimilarityPolicy.Context(
                  exact,
                  distance,
                  sim,
                  low,
                  source.get("collection_id").equals(target.get("collection_id")),
                  source.get("concept_id").equals(target.get("concept_id")),
                  source.get("prompt_version_id") != null
                      && source.get("prompt_version_id").equals(target.get("prompt_version_id")),
                  family);
          var profiles = new LinkedHashMap<String, Map<String, Object>>();
          for (UUID id : List.of(a, b)) {
            var p = profile(id);
            profiles.put(p.get("id").toString(), p);
          }
          for (var p : profiles.values()) {
            var frozen =
                db.sql(
                        "select profile_snapshot::text from similarity_comparisons where"
                            + " source_asset_id=? and target_asset_id=? and model_id=? and"
                            + " profile_id=?")
                    .params(a, b, model.id(), p.get("id"))
                    .query(String.class)
                    .optional();
            @SuppressWarnings("unchecked")
            Map<String, Object> evaluationProfile =
                frozen.isPresent() ? JSON.readValue(frozen.get(), Map.class) : p;
            var decision = policy.evaluate(context, evaluationProfile);
            if (decision.classification().equals("DISTINCT")) {
              continue;
            }
            UUID id = UUID.randomUUID();
            // A later stage may fill missing metrics; human state and previous classification
            // remain audited.
            int inserted =
                db.sql(
                        "insert into"
                            + " similarity_comparisons(id,source_asset_id,target_asset_id,model_id,profile_id,profile_snapshot,sha256_match,phash_distance,embedding_similarity,context,automatic_classification,final_classification,explanation)"
                            + " values(?,?,?,?,?,cast(? as jsonb),?,?,?,cast(? as jsonb),?,?,?) on"
                            + " conflict(source_asset_id,target_asset_id,model_id,profile_id) do"
                            + " update set"
                            + " phash_distance=coalesce(similarity_comparisons.phash_distance,excluded.phash_distance),embedding_similarity=coalesce(similarity_comparisons.embedding_similarity,excluded.embedding_similarity),automatic_classification=excluded.automatic_classification,final_classification=coalesce(similarity_comparisons.human_classification,excluded.automatic_classification),explanation=excluded.explanation,context=excluded.context,revision=similarity_comparisons.revision+1"
                            + " where similarity_comparisons.phash_distance is distinct from"
                            + " excluded.phash_distance or"
                            + " similarity_comparisons.embedding_similarity is distinct from"
                            + " excluded.embedding_similarity")
                    .params(
                        id,
                        a,
                        b,
                        model.id(),
                        p.get("id"),
                        JSON.writeValueAsString(p),
                        exact,
                        distance,
                        sim,
                        JSON.writeValueAsString(context),
                        decision.classification(),
                        decision.classification(),
                        decision.explanation())
                    .update();
            var fresh =
                db.sql("select * from similarity_comparisons where id=?")
                    .param(id)
                    .query()
                    .listOfRows();
            var priorHuman =
                db.sql(
                        "select * from similarity_comparisons where source_asset_id=? and"
                            + " target_asset_id=? and model_id=? and human_classification is not"
                            + " null order by reviewed_at desc limit 1")
                    .params(a, b, model.id())
                    .query()
                    .listOfRows();
            if (!fresh.isEmpty() && !priorHuman.isEmpty()) {
              var human = priorHuman.getFirst();
              db.sql(
                      "update similarity_comparisons set"
                          + " human_classification=?,final_classification=?,reason=?,reviewed_by=?,reviewed_at=?,revision=revision+1"
                          + " where id=?")
                  .params(
                      human.get("human_classification"),
                      human.get("human_classification"),
                      human.get("reason"),
                      human.get("reviewed_by"),
                      human.get("reviewed_at"),
                      id)
                  .update();
              var carried =
                  db.sql("select * from similarity_comparisons where id=?")
                      .param(id)
                      .query()
                      .singleRow();
              db.sql(
                      "insert into"
                          + " similarity_review_actions(id,comparison_id,action,reason,actor,before_state,after_state)"
                          + " values(?,?,'PROPAGATE_PAIR_REVIEW',?,?,cast(? as jsonb),cast(? as"
                          + " jsonb))")
                  .params(
                      UUID.randomUUID(),
                      id,
                      "Retained pair decision from comparison " + human.get("id"),
                      human.get("reviewed_by"),
                      JSON.writeValueAsString(fresh.getFirst()),
                      JSON.writeValueAsString(carried))
                  .update();
            }
            var row =
                db.sql(
                        "select * from similarity_comparisons where source_asset_id=? and"
                            + " target_asset_id=? and model_id=? and profile_id=?")
                    .params(a, b, model.id(), p.get("id"))
                    .query()
                    .singleRow();
            id = (UUID) row.get("id");
            for (UUID asset : List.of(a, b)) {
              db.sql(
                      "insert into similarity_findings(id,comparison_id,asset_id,code,metadata)"
                          + " values(?,?,?,?,cast(? as jsonb)) on conflict do nothing")
                  .params(
                      UUID.randomUUID(),
                      id,
                      asset,
                      row.get("automatic_classification"),
                      JSON.writeValueAsString(
                          Map.of("similarAssetId", asset.equals(a) ? b : a, "comparisonId", id)))
                  .update();
            }
            if (inserted > 0) {
              db.sql(
                      "insert into similarity_evaluation_history(id,comparison_id,evidence)"
                          + " values(?,?,cast(? as jsonb))")
                  .params(UUID.randomUUID(), id, JSON.writeValueAsString(row))
                  .update();
              if ("EXACT_DUPLICATE".equals(row.get("automatic_classification"))) {
                metrics.counter("media_factory_exact_duplicates_total").increment();
              }
              if ("NEAR_DUPLICATE".equals(row.get("automatic_classification"))) {
                metrics.counter("media_factory_near_duplicates_total").increment();
              }
              groupPair(row);
            }
          }
        });
  }

  private UUID root(UUID generation) {
    return db.sql(
            "with recursive family as(select id,parent_id,0 depth from generations where id=? union"
                + " all select g.id,g.parent_id,f.depth+1 from generations g join family f on"
                + " g.id=f.parent_id where f.depth<100) select id from family order by depth desc"
                + " limit 1")
        .param(generation)
        .query(UUID.class)
        .single();
  }

  void groupPair(Map<String, Object> row) {
    String classification = row.get("final_classification").toString();
    String type =
        switch (classification) {
          case "EXACT_DUPLICATE" -> "EXACT";
          case "PERCEPTUAL_DUPLICATE" -> "PERCEPTUAL";
          case "NEAR_DUPLICATE" -> "NEAR_DUPLICATE";
          default -> null;
        };
    if (type == null) {
      return;
    }
    var groups =
        db.sql(
                "select distinct g.id from duplicate_groups g join duplicate_group_members m on"
                    + " m.group_id=g.id where g.model_id=? and g.type=? and g.status='OPEN' and"
                    + " m.asset_id in (?,?) order by g.id")
            .params(
                row.get("model_id"), type, row.get("source_asset_id"), row.get("target_asset_id"))
            .query(UUID.class)
            .list();
    UUID group = groups.isEmpty() ? UUID.randomUUID() : groups.getFirst();
    if (groups.isEmpty()) {
      db.sql("insert into duplicate_groups(id,model_id,type) values(?,?,?)")
          .params(group, row.get("model_id"), type)
          .update();
    }
    for (UUID other : groups) {
      if (other.equals(group)) {
        continue;
      }
      db.sql(
              "insert into duplicate_group_members(group_id,asset_id,relationship) select"
                  + " ?,asset_id,relationship from duplicate_group_members where group_id=? on"
                  + " conflict do nothing")
          .params(group, other)
          .update();
      db.sql(
              "update duplicate_groups set status='MERGED',revision=revision+1,updated_at=now()"
                  + " where id=?")
          .param(other)
          .update();
    }
    for (String key : List.of("source_asset_id", "target_asset_id")) {
      db.sql(
              "insert into duplicate_group_members(group_id,asset_id,relationship) values(?,?,?) on"
                  + " conflict do nothing")
          .params(group, row.get(key), classification)
          .update();
    }
  }

  public List<Finding> qaFindings(UUID asset) {
    UUID model = activeModel().id();
    var result = new ArrayList<Finding>();
    if (!"READY".equals(state(asset, model))) {
      result.add(
          new Finding(
              Category.SIMILARITY,
              Code.SIMILARITY_INCOMPLETE,
              Severity.MAJOR,
              1,
              true,
              Source.SIMILARITY,
              "Similarity analysis is " + state(asset, model) + "; uniqueness is unknown.",
              Map.of()));
    }
    for (var row :
        db.sql(
                "select * from similarity_comparisons where model_id=? and profile_id=? and"
                    + " (source_asset_id=? or target_asset_id=?) and final_classification in"
                    + " ('EXACT_DUPLICATE','PERCEPTUAL_DUPLICATE','NEAR_DUPLICATE') limit 50")
            .params(model, profile(asset).get("id"), asset, asset)
            .query()
            .listOfRows()) {
      String c = row.get("final_classification").toString();
      var metadata = new HashMap<String, String>();
      metadata.put("comparisonId", row.get("id").toString());
      metadata.put(
          "similarAssetId",
          (asset.equals(row.get("source_asset_id"))
              ? row.get("target_asset_id")
              : row.get("source_asset_id"))
              .toString());
      metadata.put("phashDistance", Objects.toString(row.get("phash_distance"), "unknown"));
      metadata.put(
          "embeddingSimilarity", Objects.toString(row.get("embedding_similarity"), "unknown"));
      result.add(
          new Finding(
              Category.SIMILARITY,
              c.equals("EXACT_DUPLICATE")
                  ? Code.DUPLICATE_SHA256
                  : c.equals("PERCEPTUAL_DUPLICATE")
                    ? Code.PERCEPTUAL_DUPLICATE
                      : Code.NEAR_DUPLICATE,
              Severity.MAJOR,
              1,
              true,
              Source.SIMILARITY,
              row.get("explanation").toString(),
              metadata));
    }
    return result;
  }

  public List<Map<String, Object>> similar(
      UUID asset, String scope, int limit, double minimum, String classification) {
    if (!Set.of("GLOBAL", "PROJECT", "SAME_COLLECTION").contains(scope)
        || limit < 1
        || limit > 100
        || !Double.isFinite(minimum)
        || minimum < 0
        || minimum > 1) {
      throw new IllegalArgumentException("Invalid similarity filters");
    }
    long start = System.nanoTime();
    try {
      var a = asset(asset);
      var model = activeModel();
      var vectors =
          db.sql("select embedding::text from asset_embeddings where asset_id=? and model_id=?")
              .params(asset, model.id())
              .query(String.class)
              .list();
      var ids = new LinkedHashSet<UUID>();
      exactCandidates(asset).forEach(ids::add);
      db.sql(
              "select case when source_asset_id=? then target_asset_id else source_asset_id end"
                  + " from similarity_comparisons where model_id=? and (source_asset_id=? or"
                  + " target_asset_id=?) limit 200")
          .params(asset, model.id(), asset, asset)
          .query(UUID.class)
          .list()
          .forEach(ids::add);
      if (!vectors.isEmpty()) {
        nearest(JSON.readValue(vectors.getFirst(), float[].class), model, 201, scope, asset)
            .forEach(
                r -> {
                  if (((Number) r.get("embedding_similarity")).doubleValue() >= minimum) {
                    ids.add((UUID) r.get("asset_id"));
                  }
                });
      }
      var output = new ArrayList<Map<String, Object>>();
      for (UUID id : ids) {
        if (id.equals(asset)) {
          continue;
        }
        var b = asset(id);
        if (scope.equals("PROJECT") && !a.get("project_id").equals(b.get("project_id"))
            || scope.equals("SAME_COLLECTION")
            && !a.get("collection_id").equals(b.get("collection_id"))) {
          continue;
        }
        var comparisons =
            db.sql(
                    "select * from similarity_comparisons where model_id=? and profile_id=? and"
                        + " ((source_asset_id=? and target_asset_id=?) or (source_asset_id=? and"
                        + " target_asset_id=?))")
                .params(model.id(), a.get("similarity_profile"), asset, id, id, asset)
                .query()
                .listOfRows();
        var item = new LinkedHashMap<String, Object>();
        item.put("asset", b);
        item.put("comparison", comparisons.isEmpty() ? null : comparisons.getFirst());
        Double similarity =
            db.sql(
                    "select 1-(x.embedding <=> y.embedding) from asset_embeddings x join"
                        + " asset_embeddings y on x.model_id=y.model_id where x.asset_id=? and"
                        + " y.asset_id=? and x.model_id=?")
                .params(asset, id, model.id())
                .query(Double.class)
                .optional()
                .orElse(null);
        item.put("embeddingSimilarity", similarity);
        if (classification != null
            && (comparisons.isEmpty()
            || !classification.equals(comparisons.getFirst().get("final_classification")))) {
          continue;
        }
        if (similarity != null && similarity < minimum && comparisons.isEmpty()) {
          continue;
        }
        output.add(item);
      }
      output.sort(
          Comparator.comparingDouble(
                  (Map<String, Object> r) ->
                      r.get("embeddingSimilarity") == null
                          ? -1
                          : ((Number) r.get("embeddingSimilarity")).doubleValue())
              .reversed());
      return output.stream().limit(limit).toList();
    } finally {
      metrics.counter("media_factory_similarity_queries_total", "scope", scope).increment();
      metrics
          .timer("media_factory_similarity_query_duration", "scope", scope)
          .record(Duration.ofNanos(System.nanoTime() - start));
    }
  }

  public Object semantic(String query, int limit) {
    if (query == null || query.isBlank() || query.length() > 16000 || limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Invalid semantic search");
    }
    var model = activeModel();
    var output = textEmbedding(List.of(query), model);
    return nearest(output.vectors().getFirst(), model, limit).stream()
        .map(
            row ->
                Map.of(
                    "asset",
                    asset((UUID) row.get("asset_id")),
                    "cosineSimilarity",
                    row.get("embedding_similarity"),
                    "classification",
                    "SEMANTICALLY_SIMILAR"))
        .toList();
  }

  public ImageEmbeddingProvider.Result textEmbedding(
      List<String> texts, ImageEmbeddingProvider.Model model) {
    UUID usage = UUID.randomUUID();
    long started = System.nanoTime();
    db.sql(
            "insert into"
                + " embedding_compute_usage(id,attempt,provider,model,version,operation,input_usage,outcome)"
                + " values(?,1,?,?,?,'TEXT_EMBEDDING',?,'STARTED')")
        .params(usage, model.provider(), model.model(), model.version(), texts.size())
        .update();
    try {
      var result = provider(model).embedText(texts, model);
      db.sql(
              "update embedding_compute_usage set"
                  + " outcome='SUCCEEDED',output_usage=?,duration_ms=?,device=? where id=?")
          .params(
              model.dimension() * texts.size(),
              (System.nanoTime() - started) / 1_000_000,
              result.metadata().getOrDefault("device", "unknown"),
              usage)
          .update();
      return result;
    } catch (RuntimeException failure) {
      db.sql("update embedding_compute_usage set outcome='FAILED',duration_ms=? where id=?")
          .params((System.nanoTime() - started) / 1_000_000, usage)
          .update();
      throw failure;
    }
  }
}
