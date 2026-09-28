package com.mediafactory.stock;

import static com.mediafactory.processing.ProcessingJson.*;
import static com.mediafactory.stock.StockProductionService.*;

import com.mediafactory.similarity.PerceptualHash;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.springframework.stereotype.Service;

@Service
public class StockExportService {
  final StockProductionService s;
  final Map<String, StockExportAdapter> adapters;
  final java.util.concurrent.ConcurrentMap<UUID, UUID> active =
      new java.util.concurrent.ConcurrentHashMap<>();

  public StockExportService(StockProductionService s, List<StockExportAdapter> adapters) {
    this.s = s;
    var map = new HashMap<String, StockExportAdapter>();
    for (var a : adapters) map.put(a.platformId(), a);
    this.adapters = Map.copyOf(map);
  }

  public Object profiles() {
    return s
        .db
        .sql("select * from stock_export_profiles order by profile_key,version desc")
        .query()
        .listOfRows()
        .stream()
        .map(StockProductionService::json)
        .toList();
  }

  public Object profile(String key, int previous, Map<String, Object> definition) {
    if (!key.matches("[A-Z_]{2,60}") || !adapters.containsKey(definition.get("adapter")))
      throw new IllegalArgumentException("Unknown adapter");
    if (integer(definition, "maximumAssets", 0) < 1
        || integer(definition, "maximumAssets", 0) > 500
        || number(definition, "maximumBytes", 0) < 1
        || number(definition, "maximumBytes", 0) > 536870912
        || !"UTF-8".equals(definition.get("encoding")))
      throw new IllegalArgumentException("Invalid export limits");
    adapters.get(definition.get("adapter")).csv(List.of(), definition);
    return s.tx.execute(
        t -> {
          s.db
              .sql("select pg_advisory_xact_lock(hashtext(?))")
              .param("stock-export-profile:" + key)
              .query()
              .singleRow();
          int version =
              s.db
                  .sql(
                      "select coalesce(max(version),0) from stock_export_profiles where"
                          + " profile_key=?")
                  .param(key)
                  .query(Integer.class)
                  .single();
          if (version != previous) throw conflict("Export profile changed");
          return json(
              s.db
                  .sql(
                      "insert into stock_export_profiles(profile_key,version,definition)"
                          + " values(?,?,?::jsonb) returning *")
                  .params(key, version + 1, write(definition))
                  .query()
                  .singleRow());
        });
  }

  public Map<String, Object> request(
      String profile,
      List<UUID> ids,
      UUID collection,
      boolean incremental,
      String policy,
      String key,
      UUID parent) {
    if (!Set.of("STRICT", "VALID_ONLY").contains(policy)
        || key == null
        || key.isBlank()
        || key.length() > 180)
      throw new IllegalArgumentException("Policy and Idempotency-Key required");
    if ((collection == null) == (ids == null || ids.isEmpty()))
      throw new IllegalArgumentException("Provide production IDs or a collection");
    String hash =
        com.mediafactory.processing.ProcessingPlanner.hash(
            Arrays.asList(
                profile,
                ids == null ? null : ids.stream().sorted().toList(),
                collection,
                incremental,
                policy,
                parent));
    return s.tx.execute(
        t -> {
          s.db
              .sql("select pg_advisory_xact_lock(hashtext(?))")
              .param("stock-export:" + key)
              .query()
              .singleRow();
          var replay =
              s.db
                  .sql("select id,request_hash from stock_exports where request_key=?")
                  .param(key)
                  .query()
                  .listOfRows();
          if (!replay.isEmpty()) {
            if (!hash.equals(replay.getFirst().get("request_hash")))
              throw conflict("Export idempotency input differs");
            return detail((UUID) replay.getFirst().get("id"));
          }
          var p =
              json(
                  s.db
                      .sql(
                          "select * from stock_export_profiles where profile_key=? order by version"
                              + " desc limit 1")
                      .param(profile)
                      .query()
                      .singleRow());
          var config = map(p.get("definition"));
          List<UUID> selected =
              collection == null
                  ? ids
                  : s.db
                      .sql(
                          "select s.id from stock_productions s join concepts c on"
                              + " c.id=s.concept_id where c.collection_id=? order by s.id limit"
                              + " 501")
                      .param(collection)
                      .query(UUID.class)
                      .list();
          selected = selected.stream().distinct().sorted().toList();
          if (incremental)
            selected =
                selected.stream()
                    .filter(
                        id ->
                            !s.db
                                .sql(
                                    "select exists(select 1 from stock_export_items i join"
                                        + " stock_exports e on e.id=i.export_id join"
                                        + " stock_productions p on p.id=i.production_id where"
                                        + " i.production_id=? and e.status='READY' and"
                                        + " i.status='INCLUDED' and"
                                        + " i.metadata_version_id=p.metadata_version_id and"
                                        + " i.variant_id=p.stock_variant_id)")
                                .param(id)
                                .query(Boolean.class)
                                .single())
                    .toList();
          if (selected.isEmpty() || selected.size() > integer(config, "maximumAssets", 500))
            throw conflict("Export selection is empty or exceeds profile asset limit");
          UUID id = UUID.randomUUID();
          s.db
              .sql(
                  "insert into"
                      + " stock_exports(id,profile_version_id,policy,request_key,request_hash,parent_id)"
                      + " values(?,?,?,?,?,?)")
              .params(id, p.get("id"), policy, key, hash, parent)
              .update();
          for (UUID production : selected) {
            s.db
                .sql("select id from stock_productions where id=? for update")
                .param(production)
                .query()
                .singleRow();
            var row = s.one(production);
            var snapshot = new LinkedHashMap<String, Object>();
            snapshot.put("production", row);
            snapshot.put("profile", map(row.get("profile_snapshot")));
            snapshot.put(
                "variant",
                row.get("stock_variant_id") == null
                    ? Map.of()
                    : s.variant((UUID) row.get("stock_variant_id")));
            snapshot.put(
                "metadataVersion",
                row.get("metadata_version_id") == null
                    ? Map.of()
                    : s.metadata.version((UUID) row.get("metadata_version_id")));
            snapshot.put(
                "promptSnapshots",
                s.db
                    .sql("select * from rendered_prompt_snapshots where generation_id=?")
                    .param(row.get("generation_id"))
                    .query()
                    .listOfRows());
            String title =
                row.get("metadata_version_id") == null
                    ? "stock"
                    : map(map(snapshot.get("metadataVersion")).get("data")).get("title").toString();
            String filename =
                StockFilenameStrategy.filename(
                    title,
                    production,
                    row.get("metadata_version_id") == null
                        ? production
                        : (UUID) row.get("metadata_version_id"));
            s.db
                .sql(
                    "insert into"
                        + " stock_export_items(export_id,production_id,variant_id,metadata_version_id,filename,snapshot)"
                        + " values(?,?,?,?,?,?::jsonb)")
                .params(
                    id,
                    production,
                    row.get("stock_variant_id"),
                    row.get("metadata_version_id"),
                    filename,
                    write(snapshot))
                .update();
          }
          return detail(id);
        });
  }

  public List<Map<String, Object>> list() {
    return s
        .db
        .sql(
            "select e.*,p.profile_key,p.version,(select count(*) from stock_export_items i where"
                + " i.export_id=e.id) asset_count from stock_exports e join stock_export_profiles p"
                + " on p.id=e.profile_version_id order by created_at desc limit 200")
        .query()
        .listOfRows()
        .stream()
        .map(StockProductionService::json)
        .toList();
  }

  public Map<String, Object> detail(UUID id) {
    var e =
        json(
            s.db
                .sql(
                    "select e.*,p.profile_key,p.version,p.definition from stock_exports e join"
                        + " stock_export_profiles p on p.id=e.profile_version_id where e.id=?")
                .param(id)
                .query()
                .singleRow());
    e.put(
        "items",
        s
            .db
            .sql("select * from stock_export_items where export_id=? order by production_id")
            .param(id)
            .query()
            .listOfRows()
            .stream()
            .map(StockProductionService::json)
            .toList());
    return e;
  }

  public Object retry(UUID id) {
    int changed =
        s.db
            .sql(
                "update stock_exports set"
                    + " status='PREPARING',failure_code=null,attempt=0,lease_token=null,lease_until=null"
                    + " where id=? and status='FAILED' and failure_code in"
                    + " ('STORAGE_FAILURE','LEASE_EXHAUSTED')")
            .param(id)
            .update();
    if (changed != 1) throw conflict("Correct invalid items and rebuild as a new export");
    return detail(id);
  }

  public Object rebuild(UUID id, String key) {
    var e = detail(id);
    var ids =
        ((List<Map<String, Object>>) e.get("items"))
            .stream().map(i -> (UUID) i.get("production_id")).toList();
    return request(
        e.get("profile_key").toString(), ids, null, false, e.get("policy").toString(), key, id);
  }

  public byte[] download(UUID id) {
    var e = detail(id);
    if (!e.get("status").equals("READY")) throw conflict("Export not ready");
    byte[] bytes = s.storage.read(e.get("storage_key").toString());
    if (!PerceptualHash.sha(bytes).equals(e.get("sha256")))
      throw conflict("Export checksum mismatch");
    return bytes;
  }

  public Object validate(UUID id) {
    var e = detail(id);
    var results = new ArrayList<Map<String, Object>>();
    for (var i : (List<Map<String, Object>>) e.get("items"))
      results.add(Map.of("productionId", i.get("production_id"), "issues", eligibility(i)));
    return Map.of(
        "exportId", id, "currentValidation", results, "frozenValidation", e.get("validation"));
  }

  List<String> eligibility(Map<String, Object> i) {
    var row = s.one((UUID) i.get("production_id"));
    var errors = new ArrayList<>(s.gates(row, true));
    if (!Objects.equals(row.get("metadata_version_id"), i.get("metadata_version_id"))
        || !Objects.equals(row.get("stock_variant_id"), i.get("variant_id")))
      errors.add("FROZEN_SELECTION_CHANGED");
    return errors;
  }

  public void execute(UUID id) {
    UUID token = UUID.randomUUID();
    int claimed =
        s.db
            .sql(
                "update stock_exports set"
                    + " status='VALIDATING',attempt=attempt+1,lease_token=?,lease_until=now()+interval"
                    + " '2 minutes' where id=? and attempt<3 and (status='PREPARING' or (status in"
                    + " ('VALIDATING','BUILDING') and lease_until<now()))")
            .params(token, id)
            .update();
    if (claimed == 0) return;
    active.put(id, token);
    Path workspace = null;
    try {
      var e = detail(id);
      var config = map(e.get("definition"));
      var items = (List<Map<String, Object>>) e.get("items");
      var included = new ArrayList<Map<String, Object>>();
      var report = new ArrayList<Map<String, Object>>();
      var checksums = new HashSet<String>();
      var sources = new HashSet<UUID>();
      long total = 0;
      for (var item : items) {
        var errors = new ArrayList<>(eligibility(item));
        var snap = map(item.get("snapshot"));
        var variant = map(snap.get("variant"));
        var prod = map(snap.get("production"));
        if (!variant.isEmpty()) {
          String checksum = variant.get("sha256").toString();
          if (!checksums.add(checksum)) errors.add("EXACT_DUPLICATE_IN_BATCH");
          UUID source = UUID.fromString(prod.get("source_asset_id").toString());
          if (!sources.add(source)) errors.add("REPEATED_SOURCE_IN_BATCH");
        }
        if (errors.isEmpty()) {
          byte[] content = s.storage.read(variant.get("storage_key").toString());
          var tech =
              s.technical.validate(
                  content,
                  variant.get("sha256").toString(),
                  map(snap.get("profile")),
                  s.encoderEvidence(UUID.fromString(variant.get("id").toString())));
          if (!tech.valid()) errors.add("TECHNICAL_REVALIDATION_FAILED");
          else total += content.length;
        }
        var validation =
            Map.of(
                "productionId",
                item.get("production_id"),
                "filename",
                item.get("filename"),
                "issues",
                errors,
                "metadataValidation",
                map(snap.get("metadataVersion")).getOrDefault("validation", Map.of()));
        report.add(validation);
        s.db
            .sql(
                "update stock_export_items set status=?,validation=?::jsonb where export_id=? and"
                    + " production_id=? and exists(select 1 from stock_exports e where"
                    + " e.id=export_id and e.lease_token=?)")
            .params(
                errors.isEmpty() ? "INCLUDED" : "SKIPPED",
                write(validation),
                id,
                item.get("production_id"),
                token)
            .update();
        if (errors.isEmpty()) included.add(item);
      }
      var validation =
          Map.of(
              "total",
              items.size(),
              "valid",
              included.size(),
              "failed",
              items.size() - included.size(),
              "items",
              report);
      s.db
          .sql("update stock_exports set validation=?::jsonb where id=? and lease_token=?")
          .params(write(validation), id, token)
          .update();
      if (included.isEmpty()
          || ("STRICT".equals(e.get("policy")) && included.size() != items.size()))
        throw new ExportFailure("ELIGIBILITY_FAILED");
      if (total > number(config, "maximumBytes", 536870912))
        throw new ExportFailure("PACKAGE_SIZE_LIMIT");
      if (s.db
              .sql("update stock_exports set status='BUILDING' where id=? and lease_token=?")
              .params(id, token)
              .update()
          != 1) throw new ExportFailure("LEASE_LOST");
      workspace = Files.createTempDirectory("stock-export-" + id + "-");
      Path zipPath = workspace.resolve("package.zip");
      var csvRows = new ArrayList<Map<String, Object>>();
      var assets = new ArrayList<Map<String, Object>>();
      for (var item : included) {
        var snap = map(item.get("snapshot"));
        var v = map(snap.get("variant"));
        var m = map(snap.get("metadataVersion"));
        csvRows.add(Map.of("filename", item.get("filename"), "metadata", m.get("data")));
        var a = new LinkedHashMap<String, Object>();
        a.put("stockProductionId", item.get("production_id"));
        a.put("filename", item.get("filename"));
        a.put("checksum", v.get("sha256"));
        a.put("variant", v);
        a.put("metadataVersion", m);
        a.put("profile", snap.get("profile"));
        a.put("production", snap.get("production"));
        a.put("promptSnapshots", snap.get("promptSnapshots"));
        assets.add(a);
      }
      byte[] csv = adapters.get(config.get("adapter")).csv(csvRows, config);
      byte[] validationBytes = canonical(validation).getBytes(StandardCharsets.UTF_8);
      var manifest =
          Map.of(
              "schemaVersion",
              "media-factory-stock-export/1",
              "exportId",
              id,
              "parentExportId",
              Objects.toString(e.get("parent_id"), ""),
              "profile",
              Map.of(
                  "key", e.get("profile_key"), "version", e.get("version"), "definition", config),
              "assets",
              assets,
              "csvSha256",
              PerceptualHash.sha(csv),
              "validationSha256",
              PerceptualHash.sha(validationBytes));
      byte[] manifestBytes = canonical(manifest).getBytes(StandardCharsets.UTF_8);
      try (var zip = new ZipOutputStream(Files.newOutputStream(zipPath))) {
        for (var a : assets) {
          var v = map(a.get("variant"));
          byte[] bytes = s.storage.read(v.get("storage_key").toString());
          if (!PerceptualHash.sha(bytes).equals(a.get("checksum")))
            throw new ExportFailure("SOURCE_CHECKSUM_CHANGED");
          entry(zip, "images/" + a.get("filename"), bytes);
        }
        entry(zip, "metadata.csv", csv);
        entry(zip, "manifest.json", manifestBytes);
        entry(zip, "validation-report.json", validationBytes);
      }
      if (Files.size(zipPath) > number(config, "maximumBytes", 536870912) + 8388608)
        throw new ExportFailure("PACKAGE_SIZE_LIMIT");
      byte[] zipBytes = Files.readAllBytes(zipPath);
      String prefix = "stock-exports/" + id + "/" + token + "/";
      s.storage.putOriginal(prefix + "metadata.csv", csv, "text/csv; charset=utf-8");
      s.storage.putOriginal(prefix + "manifest.json", manifestBytes, "application/json");
      s.storage.putOriginal(prefix + "package.zip", zipBytes, "application/zip");
      s.tx.executeWithoutResult(
          t -> {
            var locked =
                s.db
                    .sql("select lease_token from stock_exports where id=? for update")
                    .param(id)
                    .query()
                    .singleRow();
            if (!token.equals(locked.get("lease_token"))) throw new ExportFailure("LEASE_LOST");
            for (var item : included) {
              s.db
                  .sql("select id from stock_productions where id=? for update")
                  .param(item.get("production_id"))
                  .query()
                  .singleRow();
              if (!eligibility(item).isEmpty()) throw new ExportFailure("ELIGIBILITY_CHANGED");
            }
            s.db
                .sql(
                    "update stock_exports set"
                        + " status='READY',manifest=?::jsonb,storage_key=?,sha256=?,csv_key=?,csv_sha256=?,manifest_key=?,manifest_sha256=?,completed_at=now(),lease_token=null,lease_until=null"
                        + " where id=?")
                .params(
                    write(manifest),
                    prefix + "package.zip",
                    PerceptualHash.sha(zipBytes),
                    prefix + "metadata.csv",
                    PerceptualHash.sha(csv),
                    prefix + "manifest.json",
                    PerceptualHash.sha(manifestBytes),
                    id)
                .update();
            for (var item : included) {
              s.db
                  .sql(
                      "update stock_productions set"
                          + " status='EXPORTED',exported_at=now(),updated_at=now(),revision=revision+1"
                          + " where id=?")
                  .param(item.get("production_id"))
                  .update();
              s.event((UUID) item.get("production_id"), "EXPORTED", Map.of("exportId", id));
            }
          });
      s.metrics
          .counter("media_factory_stock_export_total", "adapter", config.get("adapter").toString())
          .increment();
      s.metrics.counter("media_factory_stock_export_assets_total").increment(included.size());
    } catch (Exception error) {
      String code = error instanceof ExportFailure f ? f.code : "STORAGE_FAILURE";
      s.db
          .sql(
              "update stock_exports set"
                  + " status='FAILED',failure_code=?,lease_token=null,lease_until=null,completed_at=now()"
                  + " where id=? and lease_token=?")
          .params(code, id, token)
          .update();
      s.metrics.counter("media_factory_stock_export_failed_total", "reason", code).increment();
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("stock_export_failed exportId={} code={}", id, code);
    } finally {
      active.remove(id, token);
      if (workspace != null)
        try {
          Files.deleteIfExists(workspace.resolve("package.zip"));
          Files.deleteIfExists(workspace);
        } catch (IOException ignored) {
          org.slf4j.LoggerFactory.getLogger(getClass())
              .warn("stock_export_cleanup_failed exportId={}", id);
        }
    }
  }

  static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
    var e = new ZipEntry(name);
    e.setTime(0);
    zip.putNextEntry(e);
    zip.write(bytes);
    zip.closeEntry();
  }

  public void heartbeat() {
    active.forEach(
        (id, token) ->
            s.db
                .sql(
                    "update stock_exports set lease_until=now()+interval '2 minutes' where id=? and"
                        + " lease_token=? and status in ('VALIDATING','BUILDING')")
                .params(id, token)
                .update());
  }

  static class ExportFailure extends RuntimeException {
    final String code;

    ExportFailure(String code) {
      super(code);
      this.code = code;
    }
  }
}
