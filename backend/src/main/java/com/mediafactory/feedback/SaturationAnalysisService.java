package com.mediafactory.feedback;

import static com.mediafactory.feedback.FeedbackStore.*;
import static com.mediafactory.processing.ProcessingJson.*;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SaturationAnalysisService {
  private final FeedbackStore store;
  private final FeedbackDatasetBuilder datasets;

  public SaturationAnalysisService(FeedbackStore store, FeedbackDatasetBuilder datasets) {
    this.store = store;
    this.datasets = datasets;
  }

  @Transactional
  public Object analyze(Map<String, Object> input) {
    UUID run = input.containsKey("runId") ? uuid(input, "runId") : datasets.build(input);
    var clusters =
        store
            .db
            .sql(
                """
                with ordered as(select *,ntile(3) over(partition by cluster_id order by generated_at,asset_id) third from feedback_dataset_rows where run_id=? and cluster_id is not null and value is not null)
                select cluster_id,third,count(*) n,avg(value) mean,percentile_cont(.5) within group(order by value) median,
                sum((metrics->>'cost')::numeric) cost,sum((metrics->>'revenue')::numeric) revenue,sum((metrics->>'downloads')::numeric) downloads
                from ordered group by cluster_id,third order by cluster_id,third
                """)
            .param(run)
            .query()
            .listOfRows();
    var counts =
        store
            .db
            .sql(
                "select count(*) assets,count(distinct cluster_id) clusters,count(*) filter(where"
                    + " cluster_id is null) unclustered from feedback_dataset_rows where run_id=?")
            .param(run)
            .query()
            .singleRow();
    var params = map(store.one("feedback_analysis_runs", run).get("parameters"));
    var grouped = new LinkedHashMap<Object, List<Map<String, Object>>>();
    clusters.forEach(
        r -> grouped.computeIfAbsent(r.get("cluster_id"), k -> new ArrayList<>()).add(r));
    var findings = new ArrayList<Map<String, Object>>();
    long largest = 0;
    for (var entry : grouped.entrySet()) {
      var slices = entry.getValue();
      long n = slices.stream().mapToLong(r -> ((Number) r.get("n")).longValue()).sum();
      largest = Math.max(largest, n);
      boolean enough =
          slices.size() == 3
              && slices.stream()
                  .allMatch(
                      r ->
                          ((Number) r.get("n")).intValue() >= integer(params, "minimumSample", 20));
      boolean declining =
          enough
              && number(slices.get(0), "mean", 0) > number(slices.get(1), "mean", 0)
              && number(slices.get(1), "mean", 0) > number(slices.get(2), "mean", 0)
              && number(slices.get(2), "mean", 0) < number(slices.get(0), "mean", 0) * .8;
      var similarity =
          store
              .db
              .sql(
                  "select avg(1-m.distance_to_centroid) average_similarity_to_centroid,count(*)"
                      + " filter(where c.outlier) outliers from collection_cluster_members m join"
                      + " collection_clusters c on c.id=m.cluster_id where m.cluster_id=?")
              .param(entry.getKey())
              .query()
              .singleRow();
      var item = new LinkedHashMap<String, Object>();
      item.put("clusterId", entry.getKey());
      item.put("assetCount", n);
      item.put("slices", slices);
      item.put("similarity", similarity);
      item.put(
          "status",
          !enough
              ? "INSUFFICIENT_DATA"
              : declining ? "DECLINING_MARGINAL_PERFORMANCE" : "NO_DECLINING_PATTERN");
      findings.add(item);
    }
    var diversity =
        store
            .db
            .sql(
                """
                with sampled as(select e.*,r.generated_at from asset_embeddings e join feedback_dataset_rows r on r.asset_id=e.asset_id where r.run_id=? order by e.model_id,md5(e.asset_id::text) limit 200)
                select a.model_id,count(*) pairs,avg(1-(a.embedding<=>b.embedding)) average_pairwise_similarity,
                 min(a.generated_at) earliest_generation,max(a.generated_at) latest_generation
                from sampled a join sampled b on a.asset_id<b.asset_id and a.model_id=b.model_id group by a.model_id
                """)
            .param(run)
            .query()
            .listOfRows();
    var novelty =
        store
            .db
            .sql(
                """
                with sampled as(select e.*,r.generated_at from asset_embeddings e join feedback_dataset_rows r on r.asset_id=e.asset_id where r.run_id=? order by r.generated_at,e.asset_id limit 200)
                select a.asset_id,a.model_id,min(a.embedding<=>b.embedding) novelty_distance_to_earlier_sample
                from sampled a left join sampled b on a.model_id=b.model_id and b.generated_at<a.generated_at group by a.asset_id,a.model_id
                """)
            .param(run)
            .query()
            .listOfRows();
    var result = new LinkedHashMap<String, Object>();
    result.put("runId", run);
    result.put("population", counts);
    result.put("clusters", findings);
    result.put("diversity", diversity);
    result.put("novelty", novelty);
    result.put(
        "largestClusterRatio",
        ((Number) counts.get("assets")).longValue() == 0
            ? null
            : (double) largest / ((Number) counts.get("assets")).longValue());
    result.put(
        "warnings",
        List.of(
            "DESCRIPTIVE_NOT_CAUSAL; no automatic stop or deletion",
            "PAIRWISE_AND_NOVELTY_SAMPLE_LIMIT_200; same embedding model only",
            "UNCLUSTERED_IS_NOT_AN_OUTLIER; cluster metadata defines outliers"));
    UUID id = UUID.randomUUID();
    store
        .db
        .sql(
            "insert into feedback_saturation_results(id,analysis_run_id,result) values(?,?,cast(?"
                + " as jsonb))")
        .params(id, run, write(result))
        .update();
    return Map.of("id", id, "result", result);
  }
}
