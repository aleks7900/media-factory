package com.mediafactory.wallpaper;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "media.worker.enabled", havingValue = "true", matchIfMissing = true)
public class WallpaperOperationsWorker {
  private final WallpaperCollectionService collections;
  private final WallpaperPublicationService publications;
  private final WallpaperExportService exports;

  public WallpaperOperationsWorker(
      WallpaperCollectionService collections,
      WallpaperPublicationService publications,
      WallpaperExportService exports) {
    this.collections = collections;
    this.publications = publications;
    this.exports = exports;
  }

  @Scheduled(fixedDelay = 5000)
  public void collections() {
    for (UUID id :
        collections
            .service
            .db
            .sql(
                "select collection_id from wallpaper_collection_plans where status='RUNNING' order"
                    + " by created_at limit 5")
            .query(UUID.class)
            .list())
      try {
        collections.advance(id);
      } catch (Exception e) {
        collections.pause(id, "COLLECTION_STAGE_FAILED");
      }
  }

  @Scheduled(fixedDelay = 2000)
  public void publications() {
    publications
        .service
        .db
        .sql(
            "update wallpaper_productions w set"
                + " status='PUBLICATION_FAILED',failure_code='LEASE_EXHAUSTED',revision=revision+1"
                + " where exists(select 1 from wallpaper_publication_packages p join"
                + " wallpaper_deliveries d on d.package_id=p.id where p.production_id=w.id and"
                + " d.status='RUNNING' and d.lease_until<now() and d.attempt>=d.max_attempts)")
        .update();
    publications
        .service
        .db
        .sql(
            "update wallpaper_deliveries set"
                + " status='FAILED',failure_code='LEASE_EXHAUSTED',lease_token=null,lease_until=null"
                + " where status='RUNNING' and lease_until<now() and attempt>=max_attempts")
        .update();
    for (UUID id :
        publications
            .service
            .db
            .sql(
                "select id from wallpaper_deliveries where (status='READY' and available_at<=now())"
                    + " or (status='RUNNING' and lease_until<now()) order by created_at limit 10")
            .query(UUID.class)
            .list()) publications.deliver(id);
  }

  @Scheduled(fixedDelay = 5000)
  public void exports() {
    for (UUID id :
        publications
            .service
            .db
            .sql(
                "select id from wallpaper_exports where status='QUEUED' or (status='RUNNING' and"
                    + " lease_until<now()) order by created_at limit 1")
            .query(UUID.class)
            .list()) exports.execute(id);
  }

  @Scheduled(fixedDelay = 15000)
  public void heartbeat() {
    exports.heartbeat();
    publications.heartbeat();
  }
}
