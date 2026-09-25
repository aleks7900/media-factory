package com.mediafactory.wallpaper;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "media.worker.enabled", havingValue = "true", matchIfMissing = true)
public class WallpaperPipelineWorker {

  private final WallpaperProductionService service;

  public WallpaperPipelineWorker(WallpaperProductionService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${media.wallpaper.poll-ms:2000}")
  public void poll() {
    for (UUID id :
        service
            .db
            .sql(
                "select id from wallpaper_productions where status in"
                    + " ('CONCEPT_READY','GENERATING','QA_PENDING','QA_APPROVED','SIMILARITY_CHECK','PROCESSING')"
                    + " and (lease_until is null or lease_until<now()) order by created_at limit"
                    + " 10")
            .query(UUID.class)
            .list()) {
      UUID token = UUID.randomUUID();
      if (service
          .db
          .sql(
              "update wallpaper_productions set lease_token=?,lease_until=now()+interval '2"
                  + " minutes' where id=? and (lease_until is null or lease_until<now())")
          .params(token, id)
          .update()
          != 1) {
        continue;
      }
      try {
        service.advance(id);
      } catch (Exception e) {
        // Persist actionable failure without exposing provider messages/credentials in logs.
        var row = service.one(id);
        if (WallpaperProductionService.ACTIVE.contains(row.get("status"))) {
          service
              .db
              .sql(
                  "update wallpaper_productions set"
                      + " previous_status=status,status='PAUSED',failure_code='STAGE_FAILED',revision=revision+1"
                      + " where id=? and lease_token=?")
              .params(id, token)
              .update();
          service.event(
              id,
              row.get("status").toString(),
              "PAUSED",
              "pipeline",
              "STAGE_FAILED: " + e.getClass().getSimpleName());
        }
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("wallpaper_stage_failed production={} type={}", id, e.getClass().getSimpleName());
      } finally {
        service
            .db
            .sql(
                "update wallpaper_productions set lease_token=null,lease_until=null where id=? and"
                    + " lease_token=?")
            .params(id, token)
            .update();
      }
    }
  }
}
