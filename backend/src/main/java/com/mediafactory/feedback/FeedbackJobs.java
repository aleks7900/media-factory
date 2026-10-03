package com.mediafactory.feedback;

import static com.mediafactory.feedback.FeedbackStore.*;
import static com.mediafactory.processing.ProcessingJson.*;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FeedbackJobs {
  private final FeedbackStore store;
  private final TransactionTemplate tx;
  private final VisualFeatureService features;
  private final FeedbackDatasetBuilder datasets;
  private final VisualPatternAnalysisService patterns;
  private final SaturationAnalysisService saturation;
  private final HypothesisGenerationService hypotheses;
  private final ExperimentProposalService proposals;
  private final ExperimentAnalysisService results;

  public FeedbackJobs(
      FeedbackStore store,
      TransactionTemplate tx,
      VisualFeatureService features,
      FeedbackDatasetBuilder datasets,
      VisualPatternAnalysisService patterns,
      SaturationAnalysisService saturation,
      HypothesisGenerationService hypotheses,
      ExperimentProposalService proposals,
      ExperimentAnalysisService results) {
    this.store = store;
    this.tx = tx;
    this.features = features;
    this.datasets = datasets;
    this.patterns = patterns;
    this.saturation = saturation;
    this.hypotheses = hypotheses;
    this.proposals = proposals;
    this.results = results;
  }

  public Object enqueue(String type, Map<String, Object> input, String key) {
    check(
        Set.of(
                "FEATURE_EXTRACTION",
                "REEXTRACT_VISUAL_FEATURES",
                "DATASET_BUILD",
                "PATTERN_ANALYSIS",
                "SATURATION_ANALYSIS",
                "HYPOTHESIS_GENERATION",
                "EXPERIMENT_GENERATION",
                "EXPERIMENT_ANALYSIS")
            .contains(type),
        "Unknown feedback job");
    required(key, "Idempotency-Key");
    check(key.length() <= 200 && write(input).length() < 32000, "Feedback job request too large");
    var payload = Map.copyOf(input);
    store
        .db
        .sql(
            "insert into feedback_jobs(type,payload,idempotency_key) values(?,cast(? as jsonb),?)"
                + " on conflict(idempotency_key) do nothing")
        .params(type, write(payload), key)
        .update();
    var row =
        store
            .db
            .sql("select * from feedback_jobs where idempotency_key=?")
            .param(key)
            .query()
            .singleRow();
    check(
        row.get("type").equals(type)
            && canonical(map(row.get("payload"))).equals(canonical(payload)),
        "Feedback job idempotency conflict");
    return json(row);
  }

  public void runOne() {
    var job =
        tx.execute(
            status -> {
              store
                  .db
                  .sql(
                      "update feedback_jobs set status=case when attempts>=max_attempts then"
                          + " 'FAILED' else 'QUEUED' end,lease_token=null,failure_reason='Expired"
                          + " lease; transactionally replayed' where status='RUNNING' and"
                          + " lease_until<now()")
                  .update();
              var rows =
                  store
                      .db
                      .sql(
                          "select * from feedback_jobs where status='QUEUED' and"
                              + " available_at<=now() order by created_at for update skip locked"
                              + " limit 1")
                      .query()
                      .listOfRows();
              if (rows.isEmpty()) return null;
              var row = rows.getFirst();
              UUID token = UUID.randomUUID();
              row.put("lease_token", token);
              store
                  .db
                  .sql(
                      "update feedback_jobs set"
                          + " status='RUNNING',attempts=attempts+1,lease_token=?,lease_until=now()+interval"
                          + " '2 hours' where id=?")
                  .params(token, row.get("id"))
                  .update();
              return row;
            });
    if (job == null) return;
    long started = System.nanoTime();
    try {
      tx.executeWithoutResult(
          status -> {
            var locked =
                store
                    .db
                    .sql(
                        "select id from feedback_jobs where id=? and lease_token=? and"
                            + " status='RUNNING' for update")
                    .params(job.get("id"), job.get("lease_token"))
                    .query()
                    .listOfRows();
            if (locked.isEmpty()) return;
            var p = map(job.get("payload"));
            Object result =
                switch (job.get("type").toString()) {
                  case "FEATURE_EXTRACTION", "REEXTRACT_VISUAL_FEATURES" -> extractBatch(p);
                  case "DATASET_BUILD" -> Map.of("runId", datasets.build(p));
                  case "PATTERN_ANALYSIS" -> patterns.analyze(p);
                  case "SATURATION_ANALYSIS" -> saturation.analyze(p);
                  case "HYPOTHESIS_GENERATION" ->
                      hypotheses.generate(
                          uuid(p, "findingId"), Objects.toString(p.get("intent"), "EXPLOITATION"));
                  case "EXPERIMENT_GENERATION" -> proposals.generate(uuid(p, "experimentId"));
                  default -> results.analyze(uuid(p, "experimentId"));
                };
            store
                .db
                .sql(
                    "update feedback_jobs set status='SUCCEEDED',completed_at=now(),result=cast(?"
                        + " as jsonb),lease_token=null where id=? and lease_token=?")
                .params(write(result), job.get("id"), job.get("lease_token"))
                .update();
          });
    } catch (Exception failure) {
      store
          .db
          .sql(
              "update feedback_jobs set status=case when attempts>=max_attempts then 'FAILED' else"
                  + " 'QUEUED' end,available_at=now()+interval '1"
                  + " minute',failure_reason=?,lease_token=null where id=? and lease_token=?")
          .params(
              failure instanceof IllegalArgumentException
                  ? failure.getMessage()
                  : "Feedback operation failed; inspect server diagnostics",
              job.get("id"),
              job.get("lease_token"))
          .update();
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Feedback job failed id={} type={}", job.get("id"), job.get("type"), failure);
    } finally {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .info(
              "Feedback job finished id={} type={} durationMs={}",
              job.get("id"),
              job.get("type"),
              (System.nanoTime() - started) / 1000000);
    }
  }

  private Object extractBatch(Map<String, Object> p) {
    String version = Objects.toString(p.get("extractorVersion"), "visual-v1");
    if (p.containsKey("assetId")) return features.extract(uuid(p, "assetId"), version);
    UUID collection = uuid(p, "collectionId");
    String from = Objects.toString(p.get("from"), "1970-01-01T00:00:00Z"),
        to = Objects.toString(p.get("to"), java.time.Instant.now().toString());
    var ids =
        store
            .db
            .sql(
                "select a.id from assets a join generations g on g.id=a.generation_id join concepts"
                    + " c on c.id=g.concept_id where c.collection_id=? and a.created_at>=cast(? as"
                    + " timestamptz) and a.created_at<cast(? as timestamptz) and not exists(select"
                    + " 1 from visual_feature_extractions e where e.asset_id=a.id and"
                    + " e.extractor_version=? and e.status='COMPLETED') order by a.id limit 25")
            .params(collection, from, to, version)
            .query(UUID.class)
            .list();
    for (UUID id : ids) features.extract(id, version);
    boolean more = ids.size() == 25;
    if (more) {
      var next = new LinkedHashMap<>(p);
      next.put("to", to);
      enqueue(
          "FEATURE_EXTRACTION",
          next,
          "feature-batch:" + collection + ":" + version + ":" + ids.getLast());
    }
    return Map.of("extracted", ids.size(), "continuationQueued", more);
  }
}
