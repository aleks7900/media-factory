package com.mediafactory.wallpaper;

import static com.mediafactory.processing.ProcessingJson.*;

import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable local fake remote catalog. Never represents a real Android service.
 */
@Component
public class MockWallpaperPublicationTarget implements WallpaperPublicationTarget {

  private final JdbcClient db;
  private final TransactionTemplate tx;

  public MockWallpaperPublicationTarget(JdbcClient db, TransactionTemplate tx) {
    this.db = db;
    this.tx = tx;
  }

  public String key() {
    return "MOCK";
  }

  public boolean available() {
    return true;
  }

  public Result publish(Request r) {
    return save(r);
  }

  public Result update(Request r) {
    return save(r);
  }

  private Result save(Request r) {
    return tx.execute(
        s -> {
          db.sql("select pg_advisory_xact_lock(hashtextextended(?,70))")
              .param(r.idempotencyKey())
              .query()
              .singleRow();
          String id = "mock-wallpaper-" + r.manifest().get("wallpaperId");
          var prior =
              db
                  .sql(
                      "select checksum,result from mock_wallpaper_requests where idempotency_key=?")
                  .param(r.idempotencyKey())
                  .query()
                  .listOfRows()
                  .stream()
                  .findFirst();
          if (prior.isPresent()) {
            if (!r.manifestChecksum().equals(prior.get().get("checksum"))) {
              throw new Failure("IDEMPOTENCY_CONFLICT", false);
            }
            return WallpaperProductionService.JSON.readValue(
                prior.get().get("result").toString(), Result.class);
          }
          int version = integer(r.manifest(), "publicationVersion", 1);
          var current =
              db.sql("select version from mock_wallpaper_catalog where external_id=?")
                  .param(id)
                  .query(Integer.class)
                  .optional();
          if (current.isPresent() && current.get() > version) {
            throw new Failure("STALE_VERSION", false);
          }
          db.sql(
                  "insert into mock_wallpaper_catalog(external_id,version,status,manifest)"
                      + " values(?,?,'PUBLISHED',?::jsonb) on conflict(external_id) do update set"
                      + " version=excluded.version,status='PUBLISHED',manifest=excluded.manifest"
                      + " where mock_wallpaper_catalog.version<=excluded.version")
              .params(id, version, write(r.manifest()))
              .update();
          var result =
              new Result(id, version, "PUBLISHED", Map.of("mock", true, "checksumVerified", true));
          db.sql(
                  "insert into mock_wallpaper_requests(idempotency_key,checksum,result)"
                      + " values(?,?,?::jsonb)")
              .params(r.idempotencyKey(), r.manifestChecksum(), write(result))
              .update();
          return result;
        });
  }

  public Result unpublish(Reference r) {
    db.sql(
            "update mock_wallpaper_catalog set status='UNPUBLISHED' where external_id=? and"
                + " version=?")
        .params(r.externalId(), r.version())
        .update();
    return new Result(r.externalId(), r.version(), "UNPUBLISHED", Map.of("mock", true));
  }
}
