package com.mediafactory.api;

import com.mediafactory.provider.video.VideoWebhookVerifier;
import com.mediafactory.video.VideoProductionService;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verified events only wake polling; provider status remains authoritative. No adapter is enabled
 * by default.
 */
@RestController
@RequestMapping("/api/v1/video/webhooks")
public class VideoWebhookController {

  final List<VideoWebhookVerifier> adapters;
  final VideoProductionService s;

  public VideoWebhookController(List<VideoWebhookVerifier> adapters, VideoProductionService s) {
    this.adapters = adapters;
    this.s = s;
  }

  @PostMapping("/{provider}")
  public Object event(
      @PathVariable String provider,
      @RequestHeader Map<String, String> headers,
      @RequestBody byte[] body) {
    if (body.length > 1048576) {
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
    }
    var adapter =
        adapters.stream()
            .filter(a -> a.providerId().equals(provider))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    var event = adapter.verify(body, headers);
    if (event.eventId() == null || event.eventId().length() > 200
        || event.providerJobId() == null) {
      throw new IllegalArgumentException("Invalid verified event");
    }
    return s.tx.execute(
        t -> {
          int inserted =
              s.db
                  .sql(
                      "insert into video_provider_events(provider,event_id,provider_job_id)"
                          + " values(?,?,?) on conflict do nothing")
                  .params(provider, event.eventId(), event.providerJobId())
                  .update();
          if (inserted == 1) {
            s.db
                .sql(
                    "update video_productions set available_at=now() where current_attempt_id"
                        + " in(select id from video_generation_attempts where provider=? and"
                        + " provider_job_id=?) and status='GENERATING'")
                .params(provider, event.providerJobId())
                .update();
          }
          return Map.of("accepted", true, "duplicate", inserted == 0);
        });
  }
}
