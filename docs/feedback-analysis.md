# Feedback datasets and findings

`FeedbackDatasetBuilder` delegates cohort measurements to TASK-10's `AnalyticsFeedbackDataset`. This SQL projection consumes `analytics_lineage`, `analytics_normalized_metrics`, `analytics_attributed_costs` and `analytics_currency_rates`. It does not create another download, revenue or cost ledger. TASK-05 cluster IDs and frozen TASK-03 variables/version/experiment attribution come from existing lineage.

Example POST `/api/v1/feedback/analyses`, with an Idempotency-Key:

```json
{
  "scope": {"collectionId":"<uuid>","assetType":"WALLPAPER","platform":"ANDROID"},
  "metric":"DOWNLOAD_RATE",
  "period":"90D",
  "observationDays":30,
  "attributes":["dark_pixel_ratio","centered_subject","dominant_color"],
  "combinations":[["dark_pixel_ratio","centered_subject"]],
  "minimumSample":20,
  "extractorVersion":"visual-v1",
  "minimumFeatureConfidence":0.7,
  "randomSeed":42,
  "numericBuckets":[0.2,0.4,0.6,0.8],
  "bucketVersion":"quintile-thresholds-v1"
}
```

Project or collection is required. Additional scope filters: provider, model, promptVersionId, clusterId, assetType (IMAGE/WALLPAPER/STOCK/VIDEO) and experimentId. Optional platform is explicit all-platform aggregation when absent, which may confound interpretation. Period accepts 7D, 30D, 90D, 180D, LIFETIME or CUSTOM; custom from/to are UTC instants, half-open. `asOf` controls maturity, cannot be in the future and is persisted. LIFETIME selects publication cohorts across history but still compares equal post-publication observation windows. `DOWNLOAD_RATE_30D` and other `_7D`/`_30D` suffixes normalize to the base metric and window.

Default grain is **one row per asset**, stored immutably in `feedback_dataset_rows`. Market populations require a relevant publication and a complete observation window; metrics are measured from the first relevant publication. Known mock/export wallpaper channels do not establish market exposure. QA populations instead use generated-at filters and observed approved/rejected outcomes; pending decisions stay unavailable. Failures that never produced an asset cannot have observed visual features: they remain in TASK-10 economics and the experiment operational funnel, not silently presented as measured visual examples.

Metrics: DOWNLOADS, VIEWS, LIKES, DOWNLOAD_RATE, LIKE_RATE, REVENUE, PROFIT, ROI, QA_APPROVAL_RATE, COST_PER_APPROVED_ASSET. Currency conversion uses TASK-10 immutable daily UTC rates. Missing monetary facts/rates remain null. Refunds reduce revenue. Costs include failed operations and are not copied per publication. Costs per approved asset use group total measured cost divided by approvals, including rejected-asset cost; a group with no approvals is unavailable. QA is distinct from market performance.

Snapshots retain metric values, feature IDs, override IDs, taxonomy versions, requested values and lineage/context. Parameters, canonical parameter hash, analysis/metric/extractor versions and seed are stored on the run. Late metric imports and future corrections do not mutate an old dataset. Reanalyze a frozen run for its recorded result, or create a new run for updated evidence. A parameter hash identifies equivalent settings; it does not incorrectly assume the source data never changes.

At most 12 selected dimensions, 32 categorical values per dimension and six explicit two-attribute combinations are analyzed. Pairs require both groups to meet the sample guard. Missing attributes are excluded from both groups, not treated as false. `featureRole=REQUESTED` analyzes frozen prompt variables separately. Observed production dimensions use keys such as production_provider, production_model, production_prompt_version, production_presets, production_cluster, production_processing_profile, production_video_profile, production_motion_profile, production_loop_strategy and production_amoled. Presets/profile sets are not duplicated into additive cohorts.

Findings contain scope, target, full descriptive statistics, sample sizes, effect, uncertainty, confidence, warnings and stale deadline. Evidence stores references to high-valued group/comparison examples and low-valued outliers, not copies of media. Provider/model/prompt strata and chronological thirds expose obvious imbalance and trend changes. Narrow scope filters permit within-provider/model/version comparisons. GET `/analyses/{id}/compliance` compares requested vs observed values by provider/model/prompt version with requested/observed/matched denominators. No regression adjustment or causal identification is claimed.

Finding statuses: DISCOVERED, REVIEWED, ACCEPTED_FOR_EXPERIMENT, DISMISSED, EXPERIMENT_CREATED, STALE. Review actions are audited. Once analysis completes, numerical evidence cannot be edited. Stale findings cannot generate fresh production proposals without new analysis.
