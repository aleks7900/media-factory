package com.mediafactory.video;

import static com.mediafactory.processing.ProcessingJson.*;

import com.mediafactory.provider.resilience.ProviderRateLimiter;
import com.mediafactory.quality.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Sampled TASK-04 evidence with durable per-frame charging and no ambiguous paid replay. */
@Service
public class VideoSemanticQa {
  final VideoProductionService s;
  final Map<String, VisionQualityProvider> providers = new HashMap<>();
  final QaConfiguration config;
  final ProviderRateLimiter limiter;
  final String selected;
  final BigDecimal reserve;

  public VideoSemanticQa(
      VideoProductionService s,
      List<VisionQualityProvider> providers,
      QaConfiguration config,
      ProviderRateLimiter limiter,
      @Value("${VIDEO_VISION_PROVIDER:mock}") String selected,
      @Value("${VIDEO_VISION_MAX_FRAME_COST:0}") BigDecimal reserve) {
    this.s = s;
    providers.forEach(p -> this.providers.put(p.providerId(), p));
    this.config = config;
    this.limiter = limiter;
    this.selected = selected;
    this.reserve = reserve;
    if (reserve.signum() < 0) throw new IllegalArgumentException("Negative frame reserve");
  }

  public String provider() {
    return selected;
  }

  public List<Map<String, Object>> analyze(Map<String, Object> v, List<?> samples) {
    if (samples.size() > 5) throw new VideoFailure("FRAME_SAMPLE_LIMIT");
    var results = new ArrayList<Map<String, Object>>();
    int index = 0;
    for (Object value : samples) {
      var frame = map(value);
      int frameIndex = index++;
      UUID id = (UUID) v.get("id");
      var old =
          s.db
              .sql("select * from video_frame_reviews where production_id=? and frame_index=?")
              .params(id, frameIndex)
              .query()
              .listOfRows();
      if (!old.isEmpty()) {
        var prior = old.getFirst();
        results.add(
            Map.of(
                "timeSeconds",
                frame.get("timeSeconds"),
                "status",
                prior.get("status"),
                "evidence",
                map(prior.get("evidence"))));
        continue;
      }
      var provider = providers.get(selected);
      if (provider == null
          || !selected.equals("mock") && (!config.realEnabled() || reserve.signum() <= 0))
        throw new VideoFailure("VIDEO_VISION_NOT_CONFIGURED");
      var permit =
          limiter.acquireVideo(
              "video-vision:" + selected,
              (UUID) v.get("current_attempt_id"),
              config.rate(),
              Duration.ofMinutes(2));
      if (!permit.acquired()) throw new VideoFailure("BUSY");
      UUID cost = UUID.randomUUID(), review = UUID.randomUUID();
      String model = config.model(selected);
      try {
        s.tx.executeWithoutResult(
            t -> {
              s.lock(id);
              var fresh = s.one(id);
              if ("CANCELLED".equals(fresh.get("status"))) throw new VideoFailure("CANCELLED");
              if (!selected.equals("mock")) {
                BigDecimal spent =
                    s.costs(id).stream()
                        .map(c -> new BigDecimal(Objects.toString(c.get("total"), "0")))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (spent.add(reserve).compareTo(new BigDecimal(v.get("budget").toString())) > 0)
                  throw new VideoFailure("VISION_BUDGET_EXCEEDED");
              }
              s.db
                  .sql(
                      "insert into"
                          + " generation_costs(id,generation_id,asset_id,attempt,provider,model,operation,input_usage,output_usage,estimated_cost,currency,outcome,pricing_version)"
                          + " values(?,?,?,1,?,?,'VIDEO_FRAME_QA',0,0,?,'USD','STARTED','video-frame-reserve-v1')")
                  .params(
                      cost,
                      v.get("generation_id"),
                      v.get("raw_asset_id"),
                      selected,
                      model,
                      selected.equals("mock") ? BigDecimal.ZERO : reserve)
                  .update();
              s.db
                  .sql(
                      "insert into"
                          + " video_frame_reviews(id,production_id,frame_index,time_seconds,cost_id,status,provider,model)"
                          + " values(?,?,?,?,?,'STARTED',?,?)")
                  .params(review, id, frameIndex, frame.get("timeSeconds"), cost, selected, model)
                  .update();
            });
        var response =
            provider.analyze(
                new VisionQualityProvider.Request(
                    (UUID) v.get("raw_asset_id"),
                    (UUID) v.get("generation_id"),
                    Base64.getDecoder().decode(frame.get("data").toString()),
                    "image/jpeg",
                    model,
                    "PERFECT",
                    Map.of(
                        "timeSeconds",
                        frame.get("timeSeconds"),
                        "motion",
                        s.motion(id),
                        "sourceAssetId",
                        v.get("source_asset_id"),
                        "prompt",
                        v.get("prompt_snapshot"))));
        response.evidence().validateVision();
        s.tx.executeWithoutResult(
            t -> {
              s.db
                  .sql(
                      "update video_frame_reviews set status='COMPLETED',evidence=?::jsonb where"
                          + " id=?")
                  .params(write(response.evidence()), review)
                  .update();
              s.db
                  .sql(
                      "update generation_costs set"
                          + " outcome='SUCCEEDED',input_usage=?,output_usage=?,estimated_cost=?,currency=?,pricing_version=?"
                          + " where id=?")
                  .params(
                      response.inputUsage(),
                      response.outputUsage(),
                      response.estimatedCost(),
                      response.currency(),
                      response.pricingVersion(),
                      cost)
                  .update();
            });
        results.add(
            Map.of(
                "timeSeconds",
                frame.get("timeSeconds"),
                "status",
                "COMPLETED",
                "evidence",
                response.evidence()));
      } catch (RuntimeException failure) {
        s.db
            .sql("update video_frame_reviews set status='FAILED',evidence=?::jsonb where id=?")
            .params(
                write(Map.of("reason", "SEMANTIC_QA_INCOMPLETE_NO_AUTOMATIC_PAID_RETRY")), review)
            .update();
        s.db.sql("update generation_costs set outcome='FAILED' where id=?").param(cost).update();
        if (failure instanceof VideoFailure) throw failure;
        results.add(
            Map.of(
                "timeSeconds",
                frame.get("timeSeconds"),
                "status",
                "FAILED",
                "reason",
                "Human semantic review required"));
      } finally {
        limiter.release(permit.permit());
      }
    }
    return results;
  }
}
