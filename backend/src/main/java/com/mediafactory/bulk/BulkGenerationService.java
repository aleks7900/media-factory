package com.mediafactory.bulk;

import static com.mediafactory.processing.ProcessingJson.*;

import com.mediafactory.processing.ProcessingPlanner;
import com.mediafactory.service.FactoryService;
import com.mediafactory.storage.MediaStorage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BulkGenerationService {
  final JdbcClient db;
  final TransactionTemplate tx;
  final MediaStorage storage;
  final FactoryService factory;
  final BulkArchiveParser parser;
  final Map<String, BulkProcessor> processors;

  JdbcClient database() {
    return db;
  }

  TransactionTemplate transactions() {
    return tx;
  }

  MediaStorage mediaStorage() {
    return storage;
  }

  FactoryService domainFactory() {
    return factory;
  }

  BulkArchiveParser archiveParser() {
    return parser;
  }

  public BulkGenerationService(
      JdbcClient db,
      TransactionTemplate tx,
      MediaStorage storage,
      FactoryService factory,
      BulkArchiveParser parser,
      List<BulkProcessor> processors) {
    this.db = db;
    this.tx = tx;
    this.storage = storage;
    this.factory = factory;
    this.parser = parser;
    var map = new HashMap<String, BulkProcessor>();
    processors.forEach(p -> map.put(p.kind(), p));
    this.processors = Map.copyOf(map);
  }

  public record ImportRequest(
      UUID projectId,
      String name,
      String kind,
      String provider,
      String model,
      Map<String, Object> options,
      boolean authorizePaid) {}

  static void check(boolean ok, String message) {
    if (!ok) throw new IllegalArgumentException(message);
  }

  static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static Map<String, Object> clean(Map<String, Object> row) {
    var result = new LinkedHashMap<>(row);
    result.replaceAll(
        (k, v) ->
            v != null && v.getClass().getSimpleName().equals("PGobject")
                ? tools.jackson.databind.json.JsonMapper.builder()
                    .build()
                    .readValue(v.toString(), Object.class)
                : v);
    return result;
  }

  Map<String, Object> batch(UUID id) {
    return clean(
        db.sql("select * from bulk_batches where id=?").param(id).query().listOfRows().stream()
            .findFirst()
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch not found")));
  }

  Map<String, Object> task(UUID id) {
    return clean(
        db
            .sql(
                "select"
                    + " t.*,b.kind,b.provider,b.model,b.configuration,b.project_id,b.paused,b.cancelled,b.deleted_at"
                    + " batch_deleted from bulk_tasks t join bulk_batches b on b.id=t.batch_id"
                    + " where t.id=?")
            .param(id)
            .query()
            .listOfRows()
            .stream()
            .findFirst()
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found")));
  }

  BulkProcessor processor(String kind) {
    var p = processors.get(kind);
    check(p != null, "Unsupported bulk type");
    return p;
  }

  @Transactional
  public Object importArchive(ImportRequest r, String key, String filename, byte[] archive)
      throws IOException {
    check(
        r.projectId() != null
            && r.name() != null
            && !r.name().isBlank()
            && r.name().length() <= 200,
        "Project and batch name required");
    check(key != null && key.matches("[A-Za-z0-9:_-]{1,160}"), "Idempotency key required");
    check(
        r.provider() != null && r.model() != null && r.model().length() <= 160,
        "Provider and model required");
    check(r.options() != null, "Options required");
    for (String dimension : List.of("width", "height")) {
      int value = integer(r.options(), dimension, 1024);
      check(value >= 64 && value <= 4096, "Dimensions must be between 64 and 4096");
    }
    check(
        r.provider().startsWith("mock") || r.authorizePaid(),
        "Explicit paid-provider authorization required");
    factory.one("projects", r.projectId());
    processor(r.kind());
    String checksum = sha(archive), hash = ProcessingPlanner.hash(List.of(r, checksum));
    db.sql("select pg_advisory_xact_lock(hashtext(?))").param("bulk:" + key).query().singleRow();
    var existing =
        db.sql("select id,request_hash from bulk_batches where idempotency_key=?")
            .param(key)
            .query()
            .listOfRows();
    if (!existing.isEmpty()) {
      if (!hash.equals(existing.getFirst().get("request_hash")))
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Import key conflicts with previous archive/options");
      return detail((UUID) existing.getFirst().get("id"));
    }
    var parsed = parser.parse(new ByteArrayInputStream(archive));
    UUID id = UUID.randomUUID();
    UUID collection = (UUID) factory.collection(r.projectId(), r.name()).get("id");
    String archiveKey = "bulk/" + id + "/source.zip";
    storage.putOriginal(archiveKey, archive, "application/zip");
    String display = filename == null ? "tasks.zip" : filename.replaceAll("[\\\\/\\p{Cntrl}]", "_");
    if (display.length() > 200) display = display.substring(display.length() - 200);
    db.sql(
            "insert into"
                + " bulk_batches(id,project_id,collection_id,name,kind,provider,model,configuration,archive_key,archive_sha256,archive_name,idempotency_key,request_hash)"
                + " values(?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?)")
        .params(
            id,
            r.projectId(),
            collection,
            r.name(),
            r.kind(),
            r.provider(),
            r.model(),
            write(r.options()),
            archiveKey,
            checksum,
            display,
            key,
            hash)
        .update();
    for (var item : parsed.tasks()) {
      UUID task = UUID.randomUUID(), generation = UUID.randomUUID();
      String error = item.error();
      var references = new ArrayList<Map<String, Object>>();
      int index = 0;
      for (var ref : item.references()) {
        String path = "bulk/" + id + "/inputs/" + task + "/" + (index++);
        storage.putOriginal(path, ref.bytes(), ref.mediaType());
        references.add(
            Map.of(
                "name",
                ref.name(),
                "mediaType",
                ref.mediaType(),
                "key",
                path,
                "sha256",
                sha(ref.bytes()),
                "size",
                ref.bytes().length));
      }
      var input =
          new BulkProcessor.Input(
              task,
              generation,
              UUID.randomUUID(),
              r.provider(),
              r.model(),
              item.prompt(),
              r.options(),
              item.references());
      if (error == null)
        try {
          check(item.prompt().length() <= 10000, "Prompt exceeds 10000 characters");
          processor(r.kind()).validate(input);
        } catch (RuntimeException invalid) {
          error =
              invalid instanceof IllegalArgumentException
                      || invalid
                          instanceof com.mediafactory.provider.resilience.ImageGenerationException
                  ? invalid.getMessage()
                  : "Provider unavailable or configuration invalid";
        }
      UUID concept =
          (UUID)
              factory
                  .concept(
                      collection,
                      item.name(),
                      item.prompt().isBlank() ? "Invalid archive task" : item.prompt())
                  .get("id");
      db.sql(
              "insert into"
                  + " generations(id,concept_id,status,prompt,width,height,selected_provider,model)"
                  + " values(?,?,'CREATED',?,?,?,?,?)")
          .params(
              generation,
              concept,
              item.prompt(),
              integer(r.options(), "width", r.kind().equals("GPT_IMAGE") ? 1024 : 1280),
              integer(r.options(), "height", r.kind().equals("GPT_IMAGE") ? 1024 : 720),
              r.provider(),
              r.model())
          .update();
      db.sql(
              "insert into"
                  + " bulk_tasks(id,batch_id,name,prompt,inputs,generation_id,status,validation_error,error_code,error_message,completed_at)"
                  + " values(?,?,?,?,?::jsonb,?,?,?,?,?,case when ? then now() else null end)")
          .params(
              task,
              id,
              item.name(),
              item.prompt(),
              write(references),
              generation,
              error == null ? "QUEUED" : "FAILED",
              error,
              error == null ? null : "VALIDATION_ERROR",
              error,
              error != null)
          .update();
    }
    event(
        id,
        null,
        "IMPORTED",
        Map.of("taskCount", parsed.tasks().size(), "referenceCount", parsed.referenceCount()));
    return detail(id);
  }

  void event(UUID batch, UUID task, String action, Object detail) {
    db.sql("insert into bulk_events(batch_id,task_id,action,detail) values(?,?,?,?::jsonb)")
        .params(batch, task, action, write(detail))
        .update();
    org.slf4j.LoggerFactory.getLogger(getClass())
        .info("bulk_event batchId={} taskId={} action={}", batch, task, action);
  }

  public Map<String, Object> detail(UUID id) {
    var b = batch(id);
    var counts =
        db.sql(
                "select status,count(*) count from bulk_tasks where batch_id=? and deleted_at is"
                    + " null group by status")
            .param(id)
            .query()
            .listOfRows();
    var stats = new LinkedHashMap<String, Long>();
    for (String state :
        List.of("PENDING", "QUEUED", "GENERATING", "COMPLETED", "FAILED", "RETRYING", "CANCELLED"))
      stats.put(state, 0L);
    counts.forEach(
        c -> stats.put(c.get("status").toString(), ((Number) c.get("count")).longValue()));
    long total = stats.values().stream().mapToLong(Long::longValue).sum();
    b.put("counts", stats);
    b.put("totalTasks", total);
    b.put("progress", total == 0 ? 0 : 100 * stats.get("COMPLETED") / total);
    String status =
        Boolean.TRUE.equals(b.get("cancelled"))
            ? "CANCELLED"
            : Boolean.TRUE.equals(b.get("paused"))
                ? "PAUSED"
                : stats.get("GENERATING") > 0 || stats.get("RETRYING") > 0
                    ? "RUNNING"
                    : stats.get("QUEUED") > 0
                        ? "QUEUED"
                        : stats.get("FAILED") > 0
                            ? "FAILED"
                            : stats.get("CANCELLED") == total && total > 0
                                ? "CANCELLED"
                                : "COMPLETED";
    b.put("status", status);
    if (stats.get("QUEUED") + stats.get("PENDING") + stats.get("GENERATING") + stats.get("RETRYING")
        == 0)
      b.put(
          "completed_at",
          db.sql("select max(completed_at) completed_at from bulk_tasks where batch_id=?")
              .param(id)
              .query()
              .singleRow()
              .get("completed_at"));
    b.put(
        "references",
        db.sql(
                "select coalesce(sum(jsonb_array_length(inputs)),0) from bulk_tasks where"
                    + " batch_id=?")
            .param(id)
            .query(Long.class)
            .single());
    b.put(
        "costs",
        db.sql(
                "select c.currency,sum(c.estimated_cost) estimated_cost,sum(c.actual_cost)"
                    + " actual_cost,count(*) filter(where c.estimated_cost is null)"
                    + " unknown_estimates from generation_costs c join bulk_tasks t on"
                    + " t.generation_id=c.generation_id where t.batch_id=? group by c.currency")
            .param(id)
            .query()
            .listOfRows());
    return b;
  }

  public Object list(UUID project, String kind, String search, String status, int page) {
    check(page >= 0 && page <= 100000, "Invalid page");
    var ids =
        db.sql(
                "with filtered as (select b.id,b.created_at,case when b.cancelled then 'CANCELLED'"
                    + " when b.paused then 'PAUSED' when count(*) filter(where t.status in"
                    + " ('GENERATING','RETRYING'))>0 then 'RUNNING' when count(*) filter(where"
                    + " t.status in ('QUEUED','PENDING'))>0 then 'QUEUED' when count(*)"
                    + " filter(where t.status='FAILED')>0 then 'FAILED' when count(t.id)>0 and"
                    + " count(*) filter(where t.status='CANCELLED')=count(t.id) then 'CANCELLED'"
                    + " else 'COMPLETED' end state from bulk_batches b left join bulk_tasks t on"
                    + " t.batch_id=b.id and t.deleted_at is null where b.project_id=:project and"
                    + " b.kind=:kind and b.deleted_at is null and b.name ilike :search group by"
                    + " b.id) select id from filtered where (:status='ALL' or state=:status) order"
                    + " by created_at desc,id limit 100 offset :offset")
            .param("project", project)
            .param("kind", kind)
            .param("search", "%" + Objects.toString(search, "") + "%")
            .param("status", Objects.toString(status, "ALL"))
            .param("offset", page * 100)
            .query(UUID.class)
            .list();
    return ids.stream().map(this::detail).toList();
  }

  public Object tasks(UUID batch, String status, String search, int page) {
    check(page >= 0 && page <= 100000, "Invalid page");
    return db
        .sql(
            "select t.*,a.media_type,a.width,a.height,a.sha256,a.size_bytes from bulk_tasks t left"
                + " join assets a on a.id=t.asset_id where t.batch_id=:batch and t.deleted_at is"
                + " null and (:status='ALL' or t.status=:status) and t.name ilike :search order by"
                + " t.created_at,t.id limit 100 offset :offset")
        .param("batch", batch)
        .param("status", Objects.toString(status, "ALL"))
        .param("search", "%" + Objects.toString(search, "") + "%")
        .param("offset", page * 100)
        .query()
        .listOfRows()
        .stream()
        .map(BulkGenerationService::clean)
        .toList();
  }

  public Object taskDetail(UUID id) {
    var t = task(id);
    t.put(
        "attemptHistory",
        db.sql("select * from bulk_attempts where task_id=? order by number")
            .param(id)
            .query()
            .listOfRows());
    return t;
  }

  @Transactional
  public Object batchAction(UUID id, String action) {
    db.sql("select id from bulk_batches where id=? for no key update")
        .param(id)
        .query()
        .singleRow();
    var b = batch(id);
    check(b.get("deleted_at") == null, "Batch is deleted");
    switch (action) {
      case "pause" -> db.sql("update bulk_batches set paused=true where id=?").param(id).update();
      case "resume" -> {
        check(!Boolean.TRUE.equals(b.get("cancelled")), "Cancelled batch cannot resume");
        db.sql("update bulk_batches set paused=false where id=?").param(id).update();
      }
      case "cancel", "delete" -> {
        db.sql(
                "update bulk_batches set cancelled=true,deleted_at=case when ? then now() else"
                    + " deleted_at end where id=?")
            .params(action.equals("delete"), id)
            .update();
        db.sql(
                "update bulk_tasks set cancel_requested=true,status=case when status in"
                    + " ('QUEUED','PENDING','RETRYING') then 'CANCELLED' else status"
                    + " end,completed_at=case when status in ('QUEUED','PENDING','RETRYING') then"
                    + " now() else completed_at end where batch_id=? and status not in"
                    + " ('COMPLETED','FAILED','CANCELLED')")
            .param(id)
            .update();
      }
      case "retry-failed" -> {
        check(!Boolean.TRUE.equals(b.get("cancelled")), "Cancelled batch cannot retry");
        db.sql(
                "update bulk_tasks set"
                    + " status='RETRYING',retry_count=retry_count+1,error_code=null,error_message=null,available_at=now(),completed_at=null,current_attempt_id=null,remote_job_id=null"
                    + " where batch_id=? and status='FAILED' and validation_error is null and not"
                    + " outcome_unknown and deleted_at is null")
            .param(id)
            .update();
      }
      default -> throw new IllegalArgumentException("Unknown batch action");
    }
    event(id, null, action, Map.of());
    return detail(id);
  }

  @Transactional
  public Object taskAction(UUID id, String action) {
    return taskAction(id, action, UUID.randomUUID().toString());
  }

  @Transactional
  public Object taskAction(UUID id, String action, String operationKey) {
    db.sql("select id from bulk_tasks where id=? for update").param(id).query().singleRow();
    var t = task(id);
    check(t.get("deleted_at") == null && t.get("batch_deleted") == null, "Task deleted");
    switch (action) {
      case "reconcile" -> {
        check(
            Boolean.TRUE.equals(t.get("outcome_unknown")) && t.get("status").equals("FAILED"),
            "No unresolved outcome");
        check(t.get("current_attempt_id") != null, "No attempt to reconcile");
        if (t.get("remote_job_id") == null) {
          String outputKey =
              "bulk/"
                  + t.get("batch_id")
                  + "/results/"
                  + id
                  + "/"
                  + t.get("current_attempt_id")
                  + ".bin";
          try {
            check(storage.read(outputKey).length > 0, "No persisted output");
          } catch (RuntimeException unavailable) {
            throw new IllegalArgumentException(
                "No remote job or persisted output to reconcile; contact the provider before"
                    + " creating new work");
          }
        }
        db.sql(
                "update bulk_tasks set"
                    + " status='GENERATING',outcome_unknown=false,poll_failures=0,remote_deadline_at=now()+interval"
                    + " '2 hours',available_at=now(),completed_at=null,lease_token=null,lease_until=null"
                    + " where id=?")
            .param(id)
            .update();
      }
      case "retry" -> {
        check(
            t.get("status").equals("FAILED") && t.get("validation_error") == null,
            "Task is not retryable");
        check(
            !Boolean.TRUE.equals(t.get("outcome_unknown")),
            "Unknown provider outcome requires reconciliation; no blind retry");
        check(!Boolean.TRUE.equals(t.get("cancelled")), "Batch cancelled");
        db.sql(
                "update bulk_tasks set"
                    + " status='RETRYING',retry_count=retry_count+1,current_attempt_id=null,remote_job_id=null,error_code=null,error_message=null,available_at=now(),completed_at=null"
                    + " where id=?")
            .param(id)
            .update();
      }
      case "cancel", "delete" ->
          db.sql(
                  "update bulk_tasks set cancel_requested=true,status=case when status in"
                      + " ('QUEUED','PENDING','RETRYING') then 'CANCELLED' else status"
                      + " end,completed_at=case when status in ('QUEUED','PENDING','RETRYING') then"
                      + " now() else completed_at end,deleted_at=case when ? then now() else"
                      + " deleted_at end where id=?")
              .params(action.equals("delete"), id)
              .update();
      case "regenerate" -> {
        check(
            operationKey != null && operationKey.matches("[A-Za-z0-9:_-]{1,160}"),
            "Idempotency key required for regeneration");
        db.sql("select pg_advisory_xact_lock(hashtext(?))")
            .param("bulk-regenerate:" + operationKey)
            .query()
            .singleRow();
        var previous =
            db.sql("select id,parent_id from bulk_tasks where regeneration_key=?")
                .param(operationKey)
                .query()
                .listOfRows();
        if (!previous.isEmpty()) {
          check(
              id.equals(previous.getFirst().get("parent_id")),
              "Regeneration key belongs to another task");
          return taskDetail((UUID) previous.getFirst().get("id"));
        }
        check(
            Set.of("COMPLETED", "FAILED", "CANCELLED").contains(t.get("status")),
            "Wait for the current task");
        check(
            t.get("validation_error") == null && !Boolean.TRUE.equals(t.get("outcome_unknown")),
            "Invalid or unreconciled task cannot regenerate");
        check(!Boolean.TRUE.equals(t.get("cancelled")), "Batch cancelled");
        UUID child = UUID.randomUUID(), generation = UUID.randomUUID();
        db.sql(
                "insert into"
                    + " generations(id,concept_id,parent_id,status,prompt,width,height,selected_provider,model)"
                    + " select"
                    + " ?,concept_id,id,'CREATED',prompt,width,height,selected_provider,model from"
                    + " generations where id=?")
            .params(generation, t.get("generation_id"))
            .update();
        db.sql(
                "insert into"
                    + " bulk_tasks(id,batch_id,name,prompt,inputs,generation_id,parent_id,regeneration_key,status)"
                    + " select ?,batch_id,name,prompt,inputs,?,id,?,'QUEUED' from bulk_tasks where"
                    + " id=?")
            .params(child, generation, operationKey, id)
            .update();
        event((UUID) t.get("batch_id"), id, "REGENERATED", Map.of("childTaskId", child));
        return taskDetail(child);
      }
      default -> throw new IllegalArgumentException("Unknown task action");
    }
    event((UUID) t.get("batch_id"), id, action, Map.of());
    return taskDetail(id);
  }

  BulkProcessor.Input input(Map<String, Object> t) {
    var refs = new ArrayList<BulkArchiveParser.Reference>();
    for (Object raw : (List<?>) t.get("inputs")) {
      var r = map(raw);
      byte[] bytes = storage.read(r.get("key").toString());
      check(sha(bytes).equals(r.get("sha256")), "Reference checksum mismatch");
      refs.add(
          new BulkArchiveParser.Reference(
              r.get("name").toString(), r.get("mediaType").toString(), bytes));
    }
    return new BulkProcessor.Input(
        (UUID) t.get("id"),
        (UUID) t.get("generation_id"),
        (UUID) t.get("current_attempt_id"),
        t.get("provider").toString(),
        t.get("model").toString(),
        t.get("prompt").toString(),
        map(t.get("configuration")),
        refs);
  }

  public record Content(byte[] bytes, String type) {}

  public Content reference(UUID id, int index) {
    var refs = (List<?>) task(id).get("inputs");
    check(index >= 0 && index < refs.size(), "Reference not found");
    var ref = map(refs.get(index));
    byte[] bytes = storage.read(ref.get("key").toString());
    check(sha(bytes).equals(ref.get("sha256")), "Reference checksum mismatch");
    return new Content(bytes, ref.get("mediaType").toString());
  }

  public void export(UUID batch, OutputStream output) throws IOException {
    var rows =
        db.sql(
                "select"
                    + " t.id,t.name,t.status,t.provider_metadata,a.storage_key,a.sha256,a.media_type"
                    + " from bulk_tasks t left join assets a on a.id=t.asset_id where t.batch_id=?"
                    + " and t.deleted_at is null order by t.created_at,t.id")
            .param(batch)
            .query()
            .listOfRows();
    var manifest = new ArrayList<Map<String, Object>>();
    long total = 0;
    try (var zip = new ZipOutputStream(output)) {
      for (var row : rows) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("taskId", row.get("id"));
        entry.put("name", row.get("name"));
        entry.put("status", row.get("status"));
        entry.put("metadata", clean(row).get("provider_metadata"));
        if (row.get("status").equals("COMPLETED") && row.get("storage_key") != null) {
          byte[] bytes = storage.read(row.get("storage_key").toString());
          check(sha(bytes).equals(row.get("sha256")), "Result checksum mismatch");
          total += bytes.length;
          check(
              total <= 2L * 1024 * 1024 * 1024,
              "Export exceeds 2 GiB; download outputs individually");
          String type = row.get("media_type").toString();
          String name =
              row.get("name").toString().replaceAll("[^A-Za-z0-9_-]", "_")
                  + "-"
                  + row.get("id")
                  + (type.equals("video/mp4")
                      ? ".mp4"
                      : type.equals("image/png") ? ".png" : ".jpg");
          zip.putNextEntry(new ZipEntry(name));
          zip.write(bytes);
          zip.closeEntry();
          entry.put("file", name);
          entry.put("sha256", row.get("sha256"));
        }
        manifest.add(entry);
      }
      zip.putNextEntry(new ZipEntry("manifest.json"));
      zip.write(
          write(Map.of("batchId", batch, "tasks", manifest)).getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
  }
}
