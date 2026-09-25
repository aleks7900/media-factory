package com.mediafactory.wallpaper;

import static com.mediafactory.processing.ProcessingJson.*;
import static com.mediafactory.wallpaper.WallpaperProductionService.*;

import com.mediafactory.similarity.PerceptualHash;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;

@Service
public class WallpaperExportService {

  final WallpaperPublicationService publication;
  private final java.util.concurrent.ConcurrentMap<UUID, UUID> active =
      new java.util.concurrent.ConcurrentHashMap<>();

  public WallpaperExportService(WallpaperPublicationService publication) {
    this.publication = publication;
  }

  public Object request(UUID production, UUID collection) {
    var s = publication.service;
    var packages = new ArrayList<UUID>();
    if (production != null) {
      packages.add((UUID) publication.prepare(production).get("id"));
    } else {
      var ids =
          s.db
              .sql(
                  "select w.id from wallpaper_productions w join concepts c on c.id=w.concept_id"
                      + " where c.collection_id=? and w.status in"
                      + " ('PUBLICATION_REVIEW','APPROVED_FOR_PUBLICATION','PUBLISHED','UNPUBLISHED')"
                      + " order by w.created_at limit 101")
              .param(collection)
              .query(UUID.class)
              .list();
      if (ids.isEmpty()) {
        throw conflict("No ready wallpapers to export");
      }
      if (ids.size() > 100) {
        throw conflict(
            "Export supports at most 100 wallpapers; export individual packages or smaller"
                + " collections");
      }
      for (UUID id : ids) {
        packages.add((UUID) publication.prepare(id).get("id"));
      }
    }
    UUID id = UUID.randomUUID();
    s.db
        .sql(
            "insert into wallpaper_exports(id,production_id,collection_id,package_ids)"
                + " values(?,?,?,?::jsonb)")
        .params(id, production, collection, write(packages))
        .update();
    return Map.of("id", id, "status", "QUEUED");
  }

  public Object list() {
    return publication
        .service
        .db
        .sql("select * from wallpaper_exports order by created_at desc limit 200")
        .query()
        .listOfRows();
  }

  public byte[] download(UUID id) {
    var row =
        publication
            .service
            .db
            .sql("select * from wallpaper_exports where id=? and status='COMPLETED'")
            .param(id)
            .query()
            .singleRow();
    byte[] bytes = publication.service.storage.read(row.get("storage_key").toString());
    if (!PerceptualHash.sha(bytes).equals(row.get("sha256"))) {
      throw conflict("Export checksum mismatch");
    }
    return bytes;
  }

  public void execute(UUID id) {
    var s = publication.service;
    UUID token = UUID.randomUUID();
    var row =
        s
            .db
            .sql(
                "update wallpaper_exports set"
                    + " status='RUNNING',lease_token=?,lease_until=now()+interval '2 minutes' where"
                    + " id=? and (status='QUEUED' or (status='RUNNING' and lease_until<now()))"
                    + " returning *")
            .params(token, id)
            .query()
            .listOfRows()
            .stream()
            .findFirst();
    if (row.isEmpty()) {
      return;
    }
    active.put(id, token);
    try {
      var ids = JSON.readValue(row.get().get("package_ids").toString(), UUID[].class);
      var manifests = new ArrayList<Map<String, Object>>();
      var bytes = new ByteArrayOutputStream();
      long total = 0;
      try (var zip = new ZipOutputStream(bytes)) {
        for (UUID packageId : ids) {
          var p = publication.packageById(packageId);
          var m = map(p.get("manifest"));
          manifests.add(m);
          String root = m.get("wallpaperId") + "/v" + m.get("publicationVersion") + "/";
          entry(zip, root + "manifest.json", canonical(m).getBytes(StandardCharsets.UTF_8));
          for (var item : (List<Map<String, Object>>) m.get("variants")) {
            long size = ((Number) item.get("fileSize")).longValue();
            total += size;
            if (total > 256L * 1024 * 1024) {
              throw new IllegalArgumentException("EXPORT_SIZE_LIMIT: export smaller collections");
            }
            byte[] content = s.storage.read(item.get("storageReference").toString());
            if (!PerceptualHash.sha(content).equals(item.get("checksum"))) {
              throw conflict("Export source checksum mismatch");
            }
            String folder =
                item.get("type").equals("WALLPAPER_MASTER")
                    ? "master"
                    : item.get("type").toString().contains("THUMBNAIL")
                      ? "thumbnails"
                        : item.get("type").toString().contains("PREVIEW") ? "previews" : "variants";
            entry(
                zip,
                root
                    + folder
                    + "/"
                    + item.get("id")
                    + "."
                    + item.get("format").toString().toLowerCase(),
                content);
          }
        }
        entry(
            zip,
            "manifest.json",
            canonical(
                Map.of(
                    "contractVersion",
                    "media-factory-wallpaper-export/1",
                    "wallpapers",
                    manifests))
                .getBytes(StandardCharsets.UTF_8));
      }
      byte[] data = bytes.toByteArray();
      String path = "wallpaper-exports/" + id + "/" + UUID.randomUUID() + ".zip";
      s.storage.putOriginal(path, data, "application/zip");
      s.db
          .sql(
              "update wallpaper_exports set"
                  + " status='COMPLETED',storage_key=?,sha256=?,completed_at=now(),lease_token=null,lease_until=null"
                  + " where id=? and lease_token=?")
          .params(path, PerceptualHash.sha(data), id, token)
          .update();
    } catch (Exception e) {
      s.db
          .sql(
              "update wallpaper_exports set"
                  + " status='FAILED',failure_code=?,completed_at=now(),lease_token=null,lease_until=null"
                  + " where id=? and lease_token=?")
          .params(
              e instanceof IllegalArgumentException
                  ? "EXPORT_SIZE_OR_FORMAT_LIMIT"
                  : "EXPORT_STORAGE_FAILED",
              id,
              token)
          .update();
    } finally {
      active.remove(id, token);
    }
  }

  public void heartbeat() {
    active.forEach(
        (id, token) ->
            publication
                .service
                .db
                .sql(
                    "update wallpaper_exports set lease_until=now()+interval '2 minutes' where id=?"
                        + " and lease_token=? and status='RUNNING'")
                .params(id, token)
                .update());
  }

  private void entry(ZipOutputStream zip, String path, byte[] data) throws java.io.IOException {
    var entry = new ZipEntry(path);
    entry.setTime(0);
    zip.putNextEntry(entry);
    zip.write(data);
    zip.closeEntry();
  }
}
