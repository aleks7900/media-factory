package com.mediafactory.feedback;

import static com.mediafactory.feedback.FeedbackStore.*;
import static com.mediafactory.processing.ProcessingJson.*;

import com.mediafactory.provider.*;
import com.mediafactory.storage.MediaStorage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VisualFeatureService {

  private final FeedbackStore store;
  private final MediaStorage storage;
  private final VisionProvider vision;

  public VisualFeatureService(FeedbackStore store, MediaStorage storage, VisionProvider vision) {
    this.store = store;
    this.storage = storage;
    this.vision = vision;
  }

  public static void validateSemantic(
      Map<String, Object> output, Map<String, Map<String, Object>> definitions) {
    check(output.keySet().equals(Set.of("features", "warnings")), "Invalid Vision schema keys");
    check(
        output.get("features") instanceof List<?> && output.get("warnings") instanceof List<?>,
        "Invalid Vision schema arrays");
    check(((List<?>) output.get("features")).size() <= 50, "Too many semantic features");
    Set<Object> keys = new HashSet<>();
    for (Object item : (List<?>) output.get("features")) {
      var f = map(item);
      check(f.keySet().equals(Set.of("key", "value", "confidence")), "Invalid feature schema");
      check(keys.add(f.get("key")), "Duplicate semantic feature");
      var d = definitions.get(f.get("key"));
      check(d != null, "Unknown semantic attribute");
      validateValue(d, f.get("value"));
      check(f.get("confidence") instanceof Number, "Missing confidence");
      double c = ((Number) f.get("confidence")).doubleValue();
      check(Double.isFinite(c) && c >= 0 && c <= 1, "Invalid confidence");
    }
  }

  public static void validateValue(Map<String, Object> d, Object v) {
    boolean valid =
        switch (d.get("value_type").toString()) {
          case "BOOLEAN" -> v instanceof Boolean;
          case "NUMBER" -> v instanceof Number n && Double.isFinite(n.doubleValue());
          case "ENUM" ->
              v instanceof String && ((List<?>) json(d).get("allowed_values")).contains(v);
          default -> v instanceof String s && !s.isBlank() && s.length() <= 500;
        };
    check(valid, "Attribute value type mismatch: " + d.get("key"));
  }

  public List<Map<String, Object>> taxonomy() {
    return store
        .db
        .sql("select * from visual_attribute_definitions order by key,version desc")
        .query()
        .listOfRows()
        .stream()
        .map(FeedbackStore::json)
        .toList();
  }

  @Transactional
  public Object define(Map<String, Object> input) {
    String key = Objects.toString(input.get("key"), "");
    check(key.matches("[a-z][a-z0-9_]{0,99}"), "Invalid attribute key");
    String type = Objects.toString(input.get("valueType"), "");
    check(Set.of("BOOLEAN", "ENUM", "NUMBER", "STRING").contains(type), "Invalid value type");
    required((String) input.get("name"), "name");
    required((String) input.get("description"), "description");
    required((String) input.get("category"), "category");
    store
        .db
        .sql("select pg_advisory_xact_lock(hashtextextended(?,0))")
        .param("taxonomy:" + key)
        .query()
        .singleRow();
    int version =
        store
            .db
            .sql("select coalesce(max(version),0)+1 from visual_attribute_definitions where key=?")
            .param(key)
            .query(Integer.class)
            .single();
    UUID id = UUID.randomUUID();
    store
        .db
        .sql(
            "insert into"
                + " visual_attribute_definitions(id,key,version,name,description,category,value_type,allowed_values)"
                + " values(?,?,?,?,?,?,?,cast(? as jsonb))")
        .params(
            id,
            key,
            version,
            input.get("name"),
            input.get("description"),
            input.get("category"),
            type,
            write(input.getOrDefault("allowedValues", List.of())))
        .update();
    return store.one("visual_attribute_definitions", id);
  }

  @Transactional
  public Object extract(UUID asset, String version) {
    check(
        version.matches("visual-v1(?:-r[1-9][0-9]{0,5})?"),
        "Unknown extractor implementation; use visual-v1 or explicit visual-v1-rN re-extraction"
            + " revision");
    store
        .db
        .sql("select pg_advisory_xact_lock(hashtextextended(?,0))")
        .param(asset + ":" + version)
        .query()
        .singleRow();
    var existing =
        store
            .db
            .sql(
                "select id from visual_feature_extractions where asset_id=? and extractor_version=?"
                    + " and status='COMPLETED'")
            .params(asset, version)
            .query(UUID.class)
            .list();
    if (!existing.isEmpty()) {
      return store.one("visual_feature_extractions", existing.getFirst());
    }
    var a =
        store
            .db
            .sql(
                "select a.*,g.prompt_snapshot_id from assets a join generations g on"
                    + " g.id=a.generation_id where a.id=?")
            .param(asset)
            .query()
            .singleRow();
    check(
        ((Number) a.get("size_bytes")).longValue() <= 40_000_000,
        "Feature extraction input exceeds 40 MB; use a validated preview");
    UUID id = UUID.randomUUID(), prompt = UUID.fromString("00000000-0000-0000-0000-000000001111");
    store
        .db
        .sql(
            "insert into"
                + " visual_feature_extractions(id,asset_id,extractor_version,prompt_version_id)"
                + " values(?,?,?,?)")
        .params(id, asset, version, prompt)
        .update();
    var definitions = new HashMap<String, Map<String, Object>>();
    taxonomy().forEach(d -> definitions.putIfAbsent(d.get("key").toString(), d));
    var warnings = new ArrayList<String>();
    if (a.get("media_type").toString().startsWith("image/")) {
      byte[] bytes = storage.read(a.get("storage_key").toString());
      check(
          com.mediafactory.similarity.PerceptualHash.sha(bytes)
              .equals(a.get("sha256").toString().trim()),
          "Original asset checksum mismatch");
      try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
        var readers = ImageIO.getImageReaders(stream);
        check(readers.hasNext(), "Unsupported image encoding");
        var reader = readers.next();
        try {
          reader.setInput(stream);
          check(
              (long) reader.getWidth(0) * reader.getHeight(0) <= 40_000_000,
              "Image pixel limit exceeded");
          var image = reader.read(0);
          DeterministicVisualFeatures.extract(image)
              .forEach(
                  (k, v) ->
                      feature(
                          id,
                          asset,
                          definitions.get(k),
                          v,
                          1,
                          "COMPUTER_VISION",
                          "OBSERVED_ATTRIBUTE",
                          version,
                          Map.of(
                              "algorithm",
                              DeterministicVisualFeatures.VERSION,
                              "sampleGridMaximum",
                              256)));
        } finally {
          reader.dispose();
        }
      } catch (IOException e) {
        throw new IllegalArgumentException("Cannot decode feature image", e);
      }
      check(
          "mock".equals(vision.visionIdentity().get("provider")),
          "Paid semantic extraction is not enabled; configure and review a provider adapter first");
      String template =
          store
              .db
              .sql("select positive_template from prompt_versions where id=?")
              .param(prompt)
              .query(String.class)
              .single();
      var response =
          vision.extractVisualAttributes(
              new ProviderTypes.Media(bytes, a.get("media_type").toString()), template);
      Map<String, Object> decoded = map(response.output());
      validateSemantic(decoded, definitions);
      for (Object item : (List<?>) decoded.get("features")) {
        var f = map(item);
        var d = definitions.get(f.get("key").toString());
        // CV measurements cannot be replaced by semantic output.
        check(
            !Set.of(
                    "brightness",
                    "contrast",
                    "saturation",
                    "dark_pixel_ratio",
                    "entropy",
                    "edge_density",
                    "aspect_ratio",
                    "dominant_color")
                .contains(f.get("key")),
            "Vision attempted to replace a deterministic measurement");
        feature(
            id,
            asset,
            d,
            f.get("value"),
            ((Number) f.get("confidence")).doubleValue(),
            "VISION_MODEL",
            "OBSERVED_ATTRIBUTE",
            version,
            Map.of(
                "provider",
                response.usage().provider(),
                "model",
                response.usage().model(),
                "promptVersion",
                prompt));
      }
      ((List<?>) decoded.get("warnings")).forEach(w -> warnings.add(w.toString()));
      UUID cost =
          recordCost(
              (UUID) a.get("generation_id"),
              asset,
              response.usage(),
              "VISUAL_FEATURE_EXTRACTION",
              Map.of("extractionId", id));
      store
          .db
          .sql("update visual_feature_extractions set provider=?,model=?,cost_id=? where id=?")
          .params(response.usage().provider(), response.usage().model(), cost, id)
          .update();
    } else {
      warnings.add(
          "VIDEO_SEMANTICS_UNAVAILABLE: image extraction skipped; existing video lineage remains"
              + " available");
    }
    if (a.get("prompt_snapshot_id") != null) {
      var snapshots =
          store
              .db
              .sql("select variables from rendered_prompt_snapshots where id=?")
              .param(a.get("prompt_snapshot_id"))
              .query()
              .singleRow();
      map(snapshots.get("variables"))
          .forEach(
              (k, v) -> {
                if (definitions.containsKey(k)) {
                  var d = definitions.get(k);
                  try {
                    validateValue(d, v);
                    feature(
                        id,
                        asset,
                        d,
                        v,
                        1,
                        "PROMPT",
                        "REQUESTED_ATTRIBUTE",
                        version,
                        Map.of("snapshotId", a.get("prompt_snapshot_id")));
                  } catch (IllegalArgumentException ignored) {
                    warnings.add("REQUESTED_TYPE_MISMATCH:" + k);
                  }
                }
              });
    }
    store
        .db
        .sql(
            "update visual_feature_extractions set status='COMPLETED',warnings=cast(? as"
                + " jsonb),completed_at=now() where id=?")
        .params(write(warnings), id)
        .update();
    return store.one("visual_feature_extractions", id);
  }

  private void feature(
      UUID extraction,
      UUID asset,
      Map<String, Object> d,
      Object value,
      double confidence,
      String source,
      String role,
      String version,
      Map<String, Object> provenance) {
    validateValue(d, value);
    store
        .db
        .sql(
            "insert into"
                + " asset_visual_features(extraction_id,asset_id,attribute_definition_id,value,confidence,source,role,extractor_version,provenance)"
                + " values(?,?,?,cast(? as jsonb),?,?,?,?,cast(? as jsonb))")
        .params(
            extraction,
            asset,
            d.get("id"),
            write(value),
            confidence,
            source,
            role,
            version,
            write(provenance))
        .update();
  }

  public Object features(UUID asset) {
    return Map.of(
        "history",
        store
            .db
            .sql(
                "select f.*,d.key,d.version as taxonomy_version from asset_visual_features f join"
                    + " visual_attribute_definitions d on d.id=f.attribute_definition_id where"
                    + " asset_id=? order by f.created_at desc limit 500")
            .param(asset)
            .query()
            .listOfRows()
            .stream()
            .map(FeedbackStore::json)
            .toList(),
        "overrides",
        store
            .db
            .sql(
                "select * from visual_feature_overrides where asset_id=? order by created_at desc"
                    + " limit 200")
            .param(asset)
            .query()
            .listOfRows()
            .stream()
            .map(FeedbackStore::json)
            .toList());
  }

  @Transactional
  public Object override(UUID asset, Map<String, Object> input) {
    var original =
        store
            .db
            .sql(
                "select f.*,d.value_type,d.allowed_values,d.key from asset_visual_features f join"
                    + " visual_attribute_definitions d on d.id=f.attribute_definition_id where"
                    + " f.id=? and f.asset_id=? and f.role='OBSERVED_ATTRIBUTE'")
            .params(uuid(input, "featureId"), asset)
            .query()
            .singleRow();
    validateValue(original, input.get("value"));
    required((String) input.get("reason"), "reason");
    required((String) input.get("user"), "user");
    UUID id = UUID.randomUUID();
    store
        .db
        .sql(
            "insert into"
                + " visual_feature_overrides(id,asset_id,attribute_definition_id,original_feature_id,original_value,override_value,reason,created_by)"
                + " values(?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,?)")
        .params(
            id,
            asset,
            original.get("attribute_definition_id"),
            original.get("id"),
            original.get("value").toString(),
            write(input.get("value")),
            input.get("reason"),
            input.get("user"))
        .update();
    return Map.of("id", id);
  }

  public UUID recordCost(
      UUID generation,
      UUID asset,
      ProviderTypes.Usage usage,
      String operation,
      Map<String, Object> details) {
    UUID id = UUID.randomUUID();
    store
        .db
        .sql(
            "insert into"
                + " generation_costs(id,generation_id,asset_id,attempt,provider,model,operation,input_usage,output_usage,estimated_cost,currency,outcome,pricing_status,usage_details)"
                + " values(?,?,?,1,?,?,?,?,?,?,?,'SUCCEEDED',?,cast(? as jsonb))")
        .params(
            id,
            generation,
            asset,
            usage.provider(),
            usage.model(),
            operation,
            usage.inputUsage(),
            usage.outputUsage(),
            usage.estimatedCost(),
            usage.currency(),
            usage.estimatedCost() == null ? "UNKNOWN" : "ESTIMATED",
            write(details))
        .update();
    return id;
  }
}
