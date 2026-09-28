package com.mediafactory.stock;

import static com.mediafactory.processing.ProcessingJson.*;
import static com.mediafactory.stock.StockProductionService.*;

import com.mediafactory.prompt.*;
import com.mediafactory.prompt.PromptModels.*;
import com.mediafactory.provider.*;
import com.mediafactory.provider.ProviderTypes.*;
import com.mediafactory.similarity.PerceptualHash;
import com.mediafactory.storage.MediaStorage;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class StockMetadataService {
  final JdbcClient db;
  final TransactionTemplate tx;
  final PromptEngine prompts;
  final MediaStorage storage;
  final TextGenerationProvider text;
  final VisionProvider vision;
  final StockMetadataValidator validator;
  final MeterRegistry metrics;

  public StockMetadataService(
      JdbcClient db,
      TransactionTemplate tx,
      PromptEngine prompts,
      MediaStorage storage,
      TextGenerationProvider text,
      VisionProvider vision,
      StockMetadataValidator validator,
      MeterRegistry metrics) {
    this.db = db;
    this.tx = tx;
    this.prompts = prompts;
    this.storage = storage;
    this.text = text;
    this.vision = vision;
    this.validator = validator;
    this.metrics = metrics;
  }

  public Map<String, Object> version(UUID id) {
    return json(
        db.sql("select * from stock_metadata_versions where id=?").param(id).query().singleRow());
  }

  public List<Map<String, Object>> versions(UUID production) {
    return db
        .sql(
            "select m.*,case when s.metadata_version_id=m.id then m.source else 'SUPERSEDED' end"
                + " lifecycle from stock_metadata_versions m join stock_productions s on"
                + " s.id=m.production_id where m.production_id=? order by m.version desc")
        .param(production)
        .query()
        .listOfRows()
        .stream()
        .map(StockProductionService::json)
        .toList();
  }

  Map<String, Object> normalize(
      Map<String, Object> input, Map<String, Object> profile, String source) {
    var d = new LinkedHashMap<String, Object>();
    d.put("title", Objects.toString(input.get("title"), "").strip());
    d.put("description", Objects.toString(input.get("description"), "").strip());
    d.put(
        "keywords",
        map(write(
                Map.of(
                    "k",
                    StockKeywords.normalize(
                        (List<?>) input.getOrDefault("keywords", List.of()),
                        integer(profile, "maximumKeywords", 49),
                        source))))
            .get("k"));
    d.put("categories", input.getOrDefault("categories", List.of()));
    d.put("contentType", input.getOrDefault("contentType", "UNDETERMINED"));
    d.put("aiGenerated", input.getOrDefault("aiGenerated", true));
    d.put("riskFlags", input.getOrDefault("riskFlags", List.of()));
    d.put("language", profile.getOrDefault("language", "en"));
    if (write(d).length() > 32000) throw new IllegalArgumentException("Metadata too large");
    return d;
  }

  Map<String, Object> validation(
      Map<String, Object> s, Map<String, Object> d, Map<String, Object> observations) {
    var result =
        new LinkedHashMap<>(
            map(write(validator.validate(d, map(s.get("profile_snapshot")), observations))));
    var issues =
        new ArrayList<Map<String, Object>>((List<Map<String, Object>>) result.get("issues"));
    String title = StockKeywords.normalize(d.get("title").toString());
    var others =
        db.sql(
                "select m.data,s.id from stock_productions s join stock_metadata_versions m on"
                    + " m.id=s.metadata_version_id join concepts c on c.id=s.concept_id where"
                    + " s.id<>? and c.collection_id=(select collection_id from concepts where id=?)"
                    + " limit 2000")
            .params(s.get("id"), s.get("concept_id"))
            .query()
            .listOfRows();
    Set<String> words = new HashSet<>(Arrays.asList(title.split(" ")));
    Set<String> keywords =
        new HashSet<>(
            ((List<Map<String, Object>>) d.get("keywords"))
                .stream().map(k -> k.get("normalizedValue").toString()).toList());
    for (var row : others) {
      var other = map(row.get("data"));
      String ot = StockKeywords.normalize(other.get("title").toString());
      var ow = new HashSet<>(Arrays.asList(ot.split(" ")));
      var union = new HashSet<>(words);
      union.addAll(ow);
      var intersection = new HashSet<>(words);
      intersection.retainAll(ow);
      String code =
          title.equals(ot)
              ? "IDENTICAL_TITLE"
              : !union.isEmpty() && (double) intersection.size() / union.size() >= .8
                  ? "NEAR_IDENTICAL_TITLE"
                  : null;
      if (code == null
          && keywords.equals(
              new HashSet<>(
                  ((List<Map<String, Object>>) other.get("keywords"))
                      .stream()
                          .map(
                              k ->
                                  Objects.toString(
                                      k.get("normalizedValue"), k.get("value").toString()))
                          .toList()))) code = "IDENTICAL_KEYWORD_SET";
      if (code != null) {
        issues.add(
            Map.of(
                "field",
                "metadata",
                "code",
                code,
                "severity",
                "WARNING",
                "value",
                row.get("id").toString()));
        break;
      }
    }
    result.put("issues", issues);
    if (!issues.isEmpty() && result.get("status").equals("PASS")) result.put("status", "WARNING");
    return result;
  }

  public Map<String, Object> save(
      Map<String, Object> s,
      Map<String, Object> input,
      Map<String, Object> observations,
      Map<String, Object> provenance,
      String source,
      UUID request) {
    // Caller holds the production row lock; append a new record for approvals as well as edits.
    var data =
        normalize(
            input, map(s.get("profile_snapshot")), source.equals("EDITED") ? "MANUAL" : "LLM");
    var result = validation(s, data, observations);
    UUID id = UUID.randomUUID();
    int version =
        db.sql(
                "select coalesce(max(version),0)+1 from stock_metadata_versions where"
                    + " production_id=?")
            .param(s.get("id"))
            .query(Integer.class)
            .single();
    db.sql(
            "insert into"
                + " stock_metadata_versions(id,production_id,version,previous_version_id,request_id,data,validation,observations,provenance,source,created_by)"
                + " values(?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?)")
        .params(
            id,
            s.get("id"),
            version,
            s.get("metadata_version_id"),
            request,
            write(data),
            write(result),
            write(observations),
            write(provenance),
            source,
            source.equals("GENERATED") ? "metadata-worker" : "local-workspace")
        .update();
    for (var k : (List<Map<String, Object>>) data.get("keywords"))
      db.sql(
              "insert into"
                  + " stock_keywords(metadata_version_id,rank,value,normalized_value,source,confidence)"
                  + " values(?,?,?,?,?,?)")
          .params(
              id,
              k.get("rank"),
              k.get("value"),
              k.get("normalizedValue"),
              k.get("source"),
              k.get("confidence"))
          .update();
    return version(id);
  }

  public Object edit(UUID production, int revision, Map<String, Object> input) {
    return tx.execute(
        t -> {
          var s =
              json(
                  db.sql("select * from stock_productions where id=? for update")
                      .param(production)
                      .query()
                      .singleRow());
          if (integer(s, "revision", -1) != revision
              || !Set.of("METADATA_REVIEW", "READY_FOR_EXPORT", "EXPORTED", "REVIEW_REJECTED")
                  .contains(s.get("status")))
            throw conflict("Metadata changed or production is not editable");
          var old = version((UUID) s.get("metadata_version_id"));
          var data = new LinkedHashMap<>(input);
          data.put("riskFlags", map(old.get("data")).getOrDefault("riskFlags", List.of()));
          var m =
              save(
                  s,
                  data,
                  map(old.get("observations")),
                  map(old.get("provenance")),
                  "EDITED",
                  UUID.randomUUID());
          db.sql(
                  "update stock_productions set"
                      + " metadata_version_id=?,status='METADATA_REVIEW',approved_at=null,approved_by=null,revision=revision+1,updated_at=now()"
                      + " where id=?")
              .params(m.get("id"), production)
              .update();
          audit(production, "METADATA_EDITED", m.get("id"));
          return m;
        });
  }

  public Object regenerate(UUID production, int revision, String scope) {
    if (!Set.of("ALL", "TITLE", "DESCRIPTION", "KEYWORDS").contains(scope))
      throw new IllegalArgumentException("Invalid metadata scope");
    return tx.execute(
        t -> {
          var s =
              db.sql("select * from stock_productions where id=? for update")
                  .param(production)
                  .query()
                  .singleRow();
          if (integer(s, "revision", -1) != revision
              || s.get("stock_variant_id") == null
              || !Set.of(
                      "METADATA_REVIEW",
                      "READY_FOR_EXPORT",
                      "EXPORTED",
                      "METADATA_FAILED",
                      "REVIEW_REJECTED")
                  .contains(s.get("status"))) throw conflict("Metadata cannot be regenerated now");
          UUID request = UUID.randomUUID();
          db.sql(
                  "update stock_productions set"
                      + " metadata_request_id=?,metadata_scope=?,status='METADATA_GENERATION',approved_at=null,approved_by=null,attempt=0,revision=revision+1,updated_at=now()"
                      + " where id=?")
              .params(request, scope, production)
              .update();
          return Map.of("requestId", request, "status", "METADATA_GENERATION");
        });
  }

  public void generate(Map<String, Object> s) {
    var p = map(s.get("profile_snapshot"));
    var v =
        db.sql("select * from asset_variants where id=?")
            .param(s.get("stock_variant_id"))
            .query()
            .singleRow();
    byte[] bytes = storage.read(v.get("storage_key").toString());
    if (!PerceptualHash.sha(bytes).equals(v.get("sha256")))
      throw conflict("Stock image checksum changed");
    // A checksum-keyed observation cache avoids repeat Vision calls after metadata-only edits.
    String visionKey =
        ProcessingPlannerHash(List.of("stock-vision-v1", v.get("sha256"), vision.visionIdentity()));
    var cached =
        db.sql("select result from stock_metadata_cache where cache_key=?")
            .param(visionKey)
            .query(String.class)
            .optional();
    Map<String, Object> observations;
    if (cached.isPresent()) observations = map(cached.get());
    else {
      observations =
          map(
              operation(
                      s,
                      "STOCK_VISION_ANALYSIS",
                      () -> vision.inspect(new Media(bytes, "image/jpeg")))
                  .output());
      db.sql(
              "insert into stock_metadata_cache(cache_key,result) values(?,?::jsonb) on conflict do"
                  + " nothing")
          .params(visionKey, write(observations))
          .update();
    }
    var qa =
        db.sql(
                "select f.code,f.severity,f.evidence from quality_findings f join assets a on"
                    + " a.current_review_id=f.review_id where a.id=? and f.detected order by"
                    + " f.code,f.id limit 30")
            .param(s.get("source_asset_id"))
            .query()
            .listOfRows();
    observations = new LinkedHashMap<>(observations);
    observations.put("qaFindings", qa);
    observations.put("stockChecksum", v.get("sha256"));
    var concept =
        db.sql("select prompt from concepts where id=?")
            .param(s.get("concept_id"))
            .query(String.class)
            .single();
    var rendered =
        prompts.resolve(
            new PromptRenderRequest(
                UUID.fromString(p.get("metadataPromptVersion").toString()),
                Map.of(
                    "language",
                    p.get("language"),
                    "visual_description",
                    canonical(observations),
                    "concept",
                    concept,
                    "stock_profile",
                    canonical(
                        p.entrySet().stream()
                            .filter(
                                e ->
                                    !e.getKey().equals("processingVersions")
                                        && !e.getKey().equals("qaPolicySnapshot"))
                            .collect(
                                java.util.stream.Collectors.toMap(
                                    Map.Entry::getKey, Map.Entry::getValue)))),
                List.of(),
                null,
                null,
                s.get("metadata_request_id").toString(),
                (UUID) s.get("concept_id"),
                "stock-metadata",
                null,
                null,
                null,
                null),
            (UUID) s.get("concept_id"),
            s.get("metadata_request_id").toString(),
            true);
    String cacheKey =
        ProcessingPlannerHash(
            Arrays.asList(
                v.get("sha256"),
                rendered.versionId(),
                rendered.canonical(),
                p,
                s.get("metadata_scope"),
                text.textIdentity()));
    var existing =
        db.sql("select result from stock_metadata_cache where cache_key=?")
            .param(cacheKey)
            .query(String.class)
            .optional();
    Map<String, Object> generated;
    if (existing.isPresent()) generated = map(existing.get());
    else {
      generated =
          map(
              operation(
                      s,
                      "STOCK_METADATA_GENERATION",
                      () ->
                          text.generateText(
                              new Request(
                                  s.get("metadata_request_id").toString(),
                                  rendered.canonical().positivePrompt(),
                                  0,
                                  0)))
                  .output());
      db.sql(
              "insert into stock_metadata_cache(cache_key,result) values(?,?::jsonb) on conflict do"
                  + " nothing")
          .params(cacheKey, write(generated))
          .update();
    }
    if (!s.get("metadata_scope").equals("ALL") && s.get("metadata_version_id") != null) {
      var old = map(version((UUID) s.get("metadata_version_id")).get("data"));
      String field = s.get("metadata_scope").toString().toLowerCase(Locale.ROOT);
      var combined = new LinkedHashMap<>(old);
      combined.put(field, generated.get(field));
      generated = combined;
    }
    var risk =
        new LinkedHashSet<String>((List<String>) generated.getOrDefault("riskFlags", List.of()));
    for (var finding : qa) {
      String code = finding.get("code").toString();
      if (code.equals("UNWANTED_LOGO")) risk.add("VISIBLE_LOGO");
      if (code.equals("UNWANTED_TEXT")) risk.add("TRADEMARK_LIKE_TEXT");
      if (code.equals("POSSIBLE_WATERMARK")) risk.add("COPYRIGHT_RISK");
    }
    generated = new LinkedHashMap<>(generated);
    generated.put("riskFlags", List.copyOf(risk));
    var provenance = new LinkedHashMap<String, Object>();
    provenance.put("prompt", rendered);
    provenance.put("visionCacheKey", visionKey);
    provenance.put("metadataCacheKey", cacheKey);
    provenance.put("cacheHit", existing.isPresent());
    provenance.put("stockVariantId", v.get("id"));
    provenance.put("stockChecksum", v.get("sha256"));
    var finalGenerated = generated;
    var finalObservations = observations;
    tx.executeWithoutResult(
        t -> {
          var fresh =
              json(
                  db.sql("select * from stock_productions where id=? for update")
                      .param(s.get("id"))
                      .query()
                      .singleRow());
          if (!Objects.equals(fresh.get("revision"), s.get("revision"))
              || !fresh.get("status").equals("METADATA_GENERATION")) return;
          var m =
              save(
                  fresh,
                  finalGenerated,
                  finalObservations,
                  provenance,
                  "GENERATED",
                  (UUID) s.get("metadata_request_id"));
          db.sql(
                  "update stock_productions set"
                      + " metadata_version_id=?,status='METADATA_REVIEW',failure_code=null,revision=revision+1,updated_at=now()"
                      + " where id=?")
              .params(m.get("id"), s.get("id"))
              .update();
          audit((UUID) s.get("id"), "METADATA_GENERATED", m.get("id"));
        });
    metrics.counter("media_factory_stock_metadata_generated_total").increment();
  }

  private String ProcessingPlannerHash(Object value) {
    return com.mediafactory.processing.ProcessingPlanner.hash(value);
  }

  Result<String> operation(
      Map<String, Object> s, String operation, java.util.function.Supplier<Result<String>> call) {
    var identity =
        operation.equals("STOCK_VISION_ANALYSIS") ? vision.visionIdentity() : text.textIdentity();
    UUID id = UUID.randomUUID(), cost = UUID.randomUUID();
    tx.executeWithoutResult(
        t -> {
          db.sql(
                  "insert into"
                      + " generation_costs(id,generation_id,attempt,provider,model,operation,input_usage,output_usage,estimated_cost,currency,outcome,pricing_status)"
                      + " values(?,?,1,?,?,?,null,null,null,'USD','STARTED','UNKNOWN')")
              .params(
                  cost,
                  s.get("generation_id"),
                  identity.get("provider"),
                  identity.get("model"),
                  operation)
              .update();
          db.sql(
                  "insert into"
                      + " stock_operations(id,production_id,operation,provider,model,status,cost_id)"
                      + " values(?,?,?,?,?,'STARTED',?)")
              .params(
                  id, s.get("id"), operation, identity.get("provider"), identity.get("model"), cost)
              .update();
        });
    try {
      var r = call.get();
      var u = r.usage();
      tx.executeWithoutResult(
          t -> {
            db.sql(
                    "update generation_costs set"
                        + " provider=?,model=?,input_usage=?,output_usage=?,estimated_cost=?,currency=?,outcome='SUCCEEDED',pricing_status=?"
                        + " where id=?")
                .params(
                    u.provider(),
                    u.model(),
                    u.inputUsage(),
                    u.outputUsage(),
                    u.estimatedCost(),
                    u.currency(),
                    u.estimatedCost() == null ? "UNKNOWN" : "ESTIMATED",
                    cost)
                .update();
            db.sql(
                    "update stock_operations set"
                        + " provider=?,model=?,status='SUCCEEDED',result=?::jsonb,completed_at=now()"
                        + " where id=?")
                .params(
                    u.provider(),
                    u.model(),
                    write(Map.of("usage", u, "metadata", r.metadata())),
                    id)
                .update();
          });
      return r;
    } catch (RuntimeException e) {
      db.sql("update generation_costs set outcome='FAILED' where id=?").param(cost).update();
      db.sql("update stock_operations set status='FAILED',completed_at=now() where id=?")
          .param(id)
          .update();
      metrics.counter("media_factory_stock_metadata_failed_total").increment();
      throw e;
    }
  }

  void audit(UUID id, String action, Object version) {
    db.sql("insert into stock_events(production_id,action,details) values(?,?,?::jsonb)")
        .params(id, action, write(Map.of("metadataVersionId", version)))
        .update();
    org.slf4j.LoggerFactory.getLogger(getClass())
        .info("stock_event productionId={} action={} metadataVersionId={}", id, action, version);
  }
}
