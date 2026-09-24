# TASK-10 — Analytics & Asset Economics

## Objective

Build a production-ready **Analytics & Asset Economics Engine** for Media Factory.

The system must answer the fundamental question:

> Which generated assets, concepts, prompts, providers, models, styles, collections and pipelines actually produce value relative to their cost?

TASK-10 must connect the complete production lineage:

```text
Concept
↓
Prompt
↓
Generation
↓
Asset
↓
QA
↓
Processing
↓
Publication
↓
Views
↓
Likes
↓
Downloads
↓
Revenue
↓
Cost
↓
Profit / ROI
```

Primary metrics:

```text
views

likes

downloads

revenue

generation cost

QA cost

processing cost

total cost

cost per asset

cost per approved asset

cost per download

revenue per asset

profit per asset

ROI
```

Analytics must support aggregation by:

```text
Asset
Collection
Project
Concept
Prompt Template
Prompt Version
Prompt Experiment
Prompt Variant
Provider
Model
Style
Preset
Generation Profile
Processing Profile
Pipeline
Publication Platform
Stock Platform
Wallpaper Collection
Video Profile
Date / Time Range
```

TASK-10 must become the **data foundation for TASK-11 Feedback Engine**.

TASK-10 itself must **measure and expose performance**, not automatically change generation strategy.

---

# 1. Inspect Existing Architecture First

Before implementation inspect TASK-01 through TASK-09.

Identify and reuse existing entities/services for:

```text
Project
Collection
Concept

Generation
GenerationAttempt
GenerationCost

Asset
AssetVariant

QualityReview

PromptTemplate
PromptVersion
PromptExperiment
PromptExperimentVariant

SimilarityResult

ProcessingRun
ProcessingProfile

Publication

StockProduction
StockExport

VideoProduction
VideoGenerationAttempt

MediaStorage
Job
```

Do not create duplicate analytics copies of domain data unnecessarily.

Analytics should reference authoritative production entities.

---

# 2. Core Architecture

Target architecture:

```text
PRODUCTION EVENTS
      │
      ├── Generation
      ├── QA
      ├── Processing
      ├── Publication
      └── Export
      │
      ▼
ANALYTICS INGESTION
      │
      ├── Internal Events
      └── External Metrics
             │
             ├── Wallpaper Backend
             ├── Stock Platforms
             └── Social / Video Platforms
      │
      ▼
NORMALIZATION
      │
      ▼
Analytics Event Store
      │
      ▼
Metric Aggregation
      │
      ├── Asset
      ├── Collection
      ├── Prompt
      ├── Provider
      ├── Model
      └── Pipeline
      │
      ▼
Asset Economics Engine
      │
      ├── Cost
      ├── Revenue
      ├── Profit
      ├── ROI
      └── Unit Economics
      │
      ▼
Analytics API
      │
      ▼
Dashboard
```

---

# 3. Separate Facts From Aggregates

Do not store only mutable counters such as:

```text
asset.views = 9234
asset.downloads = 521
```

Maintain source facts/snapshots/events from which analytics can be reconstructed.

Architecture should distinguish:

```text
Raw Metric Data
```

from:

```text
Aggregated Analytics
```

---

# 4. Analytics Event

Create/refine:

```text
AnalyticsEvent
```

Suggested fields:

```text
id

eventType

occurredAt
receivedAt

projectId
collectionId

assetId
assetVariantId

publicationId

platform
externalAssetId

value
currency

source

deduplicationKey

metadata
```

Use only relevant fields for each event.

---

# 5. Event Types

Support at least:

```text
VIEW

LIKE

DOWNLOAD

REVENUE

PUBLICATION

GENERATION_COST

QA_COST

PROCESSING_COST

OTHER_COST
```

Architecture should allow future:

```text
IMPRESSION

CLICK

SAVE

SHARE

PURCHASE

SUBSCRIPTION_ATTRIBUTION

REFUND
```

without schema redesign.

---

# 6. Internal vs External Metrics

Distinguish:

```text
INTERNAL
```

Example:

```text
generation cost
QA cost
processing cost
```

from:

```text
EXTERNAL
```

Example:

```text
views
likes
downloads
revenue
```

Do not mix metric provenance.

---

# 7. Metric Source

Persist source explicitly.

Examples:

```text
MEDIA_FACTORY

WALLPAPER_BACKEND

STOCK_PLATFORM

SOCIAL_PLATFORM

MANUAL_IMPORT

CSV_IMPORT

API
```

Do not infer source from asset type.

---

# 8. Analytics Ingestion

Create:

```text
AnalyticsIngestionService
```

Responsibilities:

```text
accept metrics

validate

normalize

deduplicate

map external asset → internal asset

persist source data

trigger aggregate updates
```

---

# 9. External Asset Mapping

Create:

```text
ExternalAssetReference
```

Fields:

```text
id

assetId
assetVariantId
publicationId

platform

externalId

externalUrl

createdAt
```

This is critical.

Example:

```text
Internal Asset
asset_123

↓ published as

Wallpaper Backend
wallpaper_8291

Stock Platform
external_55182

Social Platform
post_9123
```

Analytics must map all external metrics back to the original Media Factory asset.

---

# 10. Asset Lineage Attribution

Analytics must follow lineage.

Example:

```text
Concept
↓
Generation
↓
Master Asset
↓
Stock Variant
↓
Stock Publication
↓
Download
↓
Revenue
```

Revenue should be attributable back to:

```text
Asset
Generation
Concept
Collection
Prompt Version
Provider
Model
```

without duplicating the entire lineage into every analytics event.

---

# 11. Metric Model

Use normalized metric identifiers.

Example:

```text
MetricType

VIEW
LIKE
DOWNLOAD
REVENUE
COST
```

Where useful, cost should have subtype:

```text
GENERATION

VISION_QA

UPSCALE

VIDEO_GENERATION

METADATA_GENERATION

EXTERNAL_PROCESSING

OTHER
```

---

# 12. Counter vs Monetary Metrics

Treat these differently.

Counters:

```text
views
likes
downloads
```

Money:

```text
revenue
cost
```

Do not use floating point for money.

Use:

```text
BigDecimal
```

plus:

```text
currency
```

---

# 13. Currency

Every monetary fact must contain:

```text
amount

currency
```

Never assume all providers/platforms use one currency.

---

# 14. Analytics Base Currency

Configure:

```yaml
analytics:
  base-currency: USD
```

or project-selected currency.

Original amount/currency must remain immutable.

Converted values are additional analytics data.

---

# 15. Currency Conversion

Architecture must support:

```text
originalAmount

originalCurrency

convertedAmount

baseCurrency

exchangeRate

exchangeRateDate

exchangeRateSource
```

Do not destroy original monetary values.

TASK-10 may use configurable/manual FX rates initially if no FX integration exists.

---

# 16. Revenue Event

Example:

```json
{
  "eventType": "REVENUE",
  "assetId": "...",
  "platform": "STOCK_PLATFORM",
  "occurredAt": "...",
  "value": "0.99",
  "currency": "USD",
  "source": "CSV_IMPORT",
  "deduplicationKey": "..."
}
```

---

# 17. Metric Snapshots

Some platforms expose cumulative counters rather than individual events.

Example:

```text
views = 14,921
likes = 712
downloads = 381
```

Create:

```text
MetricSnapshot
```

rather than pretending each increment is a separate source event.

---

# 18. Snapshot Fields

Suggested:

```text
id

externalAssetReferenceId

metricType

value

capturedAt

source

deduplicationKey
```

---

# 19. Snapshot Delta

For cumulative metrics calculate:

```text
delta =
current snapshot
-
previous snapshot
```

Example:

```text
previous downloads = 100

current downloads = 127

delta = 27
```

Handle resets/corrections explicitly.

---

# 20. Counter Reset Detection

If:

```text
current < previous
```

do not automatically create negative engagement.

Mark:

```text
COUNTER_RESET

CORRECTION

UNKNOWN
```

depending on source semantics.

---

# 21. Deduplication

Analytics ingestion must be idempotent.

Use:

```text
source

platform

externalAssetId

metric/event ID

timestamp

deduplicationKey
```

where available.

Importing the same CSV twice must not double revenue.

---

# 22. Import Batch

Create:

```text
AnalyticsImportBatch
```

Fields:

```text
id

source

platform

filename

checksum

status

startedAt
completedAt

rowsRead

rowsImported

rowsSkipped

rowsFailed
```

---

# 23. CSV Import

Support generic CSV import for platforms without API integration.

Architecture:

```text
CSV
↓
Parser
↓
Platform Mapper
↓
Normalized Analytics
↓
Deduplication
↓
Storage
```

---

# 24. CSV Mapping Profiles

Create:

```text
AnalyticsImportProfile
```

Example mappings:

```text
external filename → asset

downloads column → DOWNLOAD

earnings column → REVENUE

date column → occurredAt
```

Do not hardcode CSV column names into core services.

---

# 25. Import Validation

Before commit show:

```text
rows detected

assets mapped

assets unmapped

invalid values

unknown currencies

duplicates

expected revenue
```

Support dry-run.

---

# 26. Unmapped Metrics

Do not silently discard external rows that cannot map to an internal asset.

Persist/report:

```text
UNMAPPED
```

with reason.

Allow later manual mapping and reprocessing.

---

# 27. Wallpaper Analytics Integration

TASK-07 should expose or integrate metrics such as:

```text
views

downloads

likes
```

Potential flow:

```text
Android Backend
↓
Analytics Import
↓
ExternalAssetReference
↓
Media Factory Asset
```

Do not couple Analytics directly to Android database internals if an API/event boundary exists.

---

# 28. Stock Analytics Integration

TASK-08 prepares:

```text
StockProduction

StockExport

External submission lineage
```

TASK-10 must be able to ingest:

```text
downloads

sales

revenue

rejections
```

when platform data becomes available.

Do not assume every exported file was actually published.

---

# 29. Video Analytics

TASK-09 videos should support future:

```text
views

likes

downloads

watch metrics

revenue
```

Core TASK-10 only needs generic metric extensibility.

---

# 30. Cost Ingestion

Do not duplicate existing `GenerationCost`.

Create a unified analytics view over existing cost records.

Cost sources may include:

```text
image generation

video generation

Vision QA

LLM metadata

upscale API

external processing
```

---

# 31. Local Processing Cost

Do not invent monetary costs for:

```text
local FFmpeg

local GPU upscale

local embedding generation
```

unless a real cost model has been configured.

Instead track compute usage separately.

---

# 32. Compute Metrics

Support:

```text
CPU time

GPU time

wall time

input bytes

output bytes
```

This prepares later infrastructure-cost estimation.

---

# 33. Cost Attribution

Every cost should be attributable to the smallest meaningful production object.

Example:

```text
GenerationCost
↓
Generation
↓
Asset
```

or:

```text
VideoGenerationCost
↓
VideoProduction
↓
Video Asset
```

Analytics then rolls cost upward.

---

# 34. Shared Cost

Some costs may affect multiple assets.

Example:

```text
batch LLM analysis
```

Support allocation strategies:

```text
DIRECT

EQUAL_SPLIT

WEIGHTED

UNALLOCATED
```

Never silently duplicate shared cost across every asset.

---

# 35. Asset Economics

Create:

```text
AssetEconomicsService
```

For each asset calculate:

```text
views

likes

downloads

revenue

directCost

allocatedCost

totalCost

profit

ROI
```

---

# 36. Total Cost

Conceptually:

```text
totalCost =
generationCost
+
qaCost
+
processingCost
+
metadataCost
+
videoCost
+
allocatedSharedCost
+
otherConfiguredCost
```

Do not double-count costs inherited through lineage.

---

# 37. Revenue

Conceptually:

```text
totalRevenue =
sum(all attributed revenue events)
```

across publications/platforms for the asset.

---

# 38. Profit

```text
profit =
revenue
-
totalCost
```

Use base currency only after proper currency conversion.

---

# 39. ROI

```text
ROI =
(revenue - cost)
───────────────
      cost
```

Handle:

```text
cost = 0
```

explicitly.

Do not divide by zero.

Possible representation:

```text
ROI = null
```

when undefined.

---

# 40. ROI Percentage

UI may show:

```text
ROI × 100
```

Example:

```text
cost = $1
revenue = $4

ROI = 3
ROI % = 300%
```

Keep calculation semantics documented.

---

# 41. Cost Per Download

```text
costPerDownload =
totalCost / downloads
```

If:

```text
downloads = 0
```

return:

```text
null
```

not zero.

---

# 42. Revenue Per Download

```text
revenuePerDownload =
revenue / downloads
```

Again handle zero denominator explicitly.

---

# 43. Revenue Per View

```text
revenuePerView =
revenue / views
```

Useful for comparing distribution channels.

---

# 44. Engagement Metrics

Calculate where data exists:

```text
likeRate =
likes / views

downloadRate =
downloads / views
```

Do not fabricate rates if denominator is unavailable.

---

# 45. Asset Performance Snapshot

Create materialized/derived model such as:

```text
AssetPerformanceSnapshot
```

Possible fields:

```text
assetId

periodStart
periodEnd

views
likes
downloads

revenue
cost
profit

likeRate
downloadRate

costPerDownload
revenuePerDownload
roi

calculatedAt
```

---

# 46. Lifetime vs Period Metrics

Support:

```text
LIFETIME
```

and arbitrary:

```text
DATE RANGE
```

Example:

```text
Today

7 days

30 days

90 days

Year

Lifetime
```

---

# 47. Time Series

Analytics must support:

```text
daily

weekly

monthly
```

aggregation.

Example:

```text
date       views    downloads    revenue
Sep 20     1200     41           $8.20
Sep 21     1500     55           $10.10
Sep 22     2300     89           $17.90
```

---

# 48. Aggregation Service

Create:

```text
AnalyticsAggregationService
```

Support grouping by:

```text
asset

collection

concept

prompt version

experiment variant

provider

model

platform

profile

date
```

---

# 49. Collection Analytics

For a collection calculate:

```text
assets generated

assets approved

assets published

views

likes

downloads

revenue

cost

profit

ROI
```

Also:

```text
approval rate

cost per approved asset

revenue per asset
```

---

# 50. Collection Cost

Include cost of rejected generations.

Example:

```text
100 generated
30 rejected
70 approved
```

Collection economics must include cost of all 100 generations.

Do not calculate production cost only from successful assets.

---

# 51. Cost Per Approved Asset

```text
collectionProductionCost
────────────────────────
approvedAssets
```

This is one of the core Media Factory metrics.

---

# 52. Cost Per Published Asset

Also expose:

```text
collectionProductionCost
────────────────────────
publishedAssets
```

when publication exists.

---

# 53. Prompt Analytics

Connect performance to:

```text
PromptTemplate

PromptVersion
```

Show:

```text
generations

approved assets

approval rate

average QA score

average generation cost

views

downloads

revenue

profit

ROI
```

Do not automatically call one prompt “best”.

TASK-11 can later use these facts for optimization.

---

# 54. Prompt Experiment Analytics

TASK-03 A/B experiments must expose per variant:

```text
assigned generations

approved

rejected

generation cost

views

likes

downloads

revenue
```

Also calculate descriptive rates.

Do not automatically declare statistical winner in TASK-10.

---

# 55. Experiment Attribution

Generation already stores:

```text
experimentId

variantId
```

Analytics must use immutable generation attribution.

Never infer historical experiment assignment from current experiment configuration.

---

# 56. Provider Analytics

For each AI provider show:

```text
requests

successes

failures

rate limits

average latency

generation cost

cost per successful generation

cost per approved asset

approval rate
```

---

# 57. Provider Revenue Attribution

Also expose downstream:

```text
revenue

profit

ROI
```

for assets generated by each provider/model.

This allows later answering:

```text
Does a more expensive provider create more valuable assets?
```

without making the decision automatically.

---

# 58. Model Analytics

Group independently by:

```text
provider

model
```

Do not assume one model per provider.

Metrics:

```text
generation count

approval rate

cost

cost per approved

downloads

revenue

profit

ROI
```

---

# 59. Style Analytics

If style/preset data exists from TASK-03:

```text
AMOLED

CINEMATIC

NEON

PHOTOREALISTIC

FANTASY
```

aggregate:

```text
assets

approval rate

downloads

revenue

cost

profit
```

---

# 60. Concept Analytics

For concepts show:

```text
generation count

variants

approved

rejected

downloads

revenue

cost

profit
```

This prepares TASK-11 for concept-level feedback.

---

# 61. Similarity Analytics

TASK-05 clusters should be available as an analytics dimension.

Example:

```text
Cluster A
50 assets
3,000 downloads

Cluster B
8 assets
2,700 downloads
```

This helps later evaluate whether generating many similar assets adds value.

Do not automatically prune clusters in TASK-10.

---

# 62. Duplicate Waste Analytics

Calculate:

```text
duplicateGenerationCost
```

for generations ultimately rejected because of:

```text
EXACT_DUPLICATE

NEAR_DUPLICATE
```

This reveals money spent generating redundant media.

---

# 63. QA Economics

Track cost lost to QA rejection.

Example:

```text
generation cost of QA-rejected assets
```

Expose:

```text
qaRejectedCost

qaRejectionRate
```

---

# 64. Rejection Reason Analytics

Group by:

```text
ARTIFACT

ANATOMY

TEXT

WATERMARK

PROMPT_MISMATCH

LOW_QUALITY

DUPLICATE

TECHNICAL_FAILURE

OTHER
```

Use actual TASK-04 reason taxonomy.

---

# 65. Rejection Cost

Calculate:

```text
cost by rejection reason
```

Example:

```text
ANATOMY          $18.21
DUPLICATE        $14.90
PROMPT_MISMATCH   $8.20
```

This is highly useful for later optimization.

---

# 66. Processing Analytics

TASK-06 processing profiles should expose:

```text
assets processed

processing failures

average processing duration

GPU time

output size

QA pass rate after processing
```

If real monetary cost model exists, include cost.

---

# 67. Stock Analytics

For stock assets expose:

```text
stock-ready

exported

submitted

accepted

rejected

downloads

revenue

cost

profit

ROI
```

Only show lifecycle metrics supported by actual data.

Do not infer `accepted` from `exported`.

---

# 68. Wallpaper Analytics

For wallpaper assets expose:

```text
views

likes

downloads

download rate

collection

device variant

AMOLED / normal

revenue if available
```

---

# 69. AMOLED Analytics

TASK-07 AMOLED collections should be independently filterable.

Example comparison:

```text
AMOLED assets

views

downloads

download rate

revenue
```

versus other wallpaper groups.

Do not turn this into an automatic recommendation in TASK-10.

---

# 70. Video Analytics

For TASK-09 expose:

```text
video generation count

approved videos

generation cost

processing duration

loop QA

downloads/views

revenue
```

Group by:

```text
video provider

model

motion profile

loop strategy

video profile
```

where data exists.

---

# 71. Loop Strategy Analytics

Compare descriptive metrics for:

```text
DIRECT

CROSSFADE

PING_PONG
```

including:

```text
QA approval

processing time

downloads

engagement
```

Do not infer causality merely from correlation.

---

# 72. Platform Analytics

Group by publication platform.

Example:

```text
Wallpaper Backend

Stock Platform A

Stock Platform B

Social Platform
```

Metrics:

```text
published assets

views

downloads

revenue

profit
```

---

# 73. Multi-Platform Attribution

One master asset may appear on multiple platforms.

Example:

```text
Asset A

Wallpaper backend → 5,000 downloads

Stock platform → $20 revenue

Social → 100,000 views
```

Keep platform-specific facts separate while supporting asset-level rollup.

---

# 74. Publication Analytics

Each:

```text
Publication
```

should have its own analytics.

This allows multiple publications of the same asset without merging source data prematurely.

---

# 75. Revenue Attribution Rules

Create explicit:

```text
RevenueAttributionPolicy
```

Initial default:

```text
DIRECT
```

Revenue associated with publication/asset maps directly to that asset.

Do not implement complex marketing attribution unless real data requires it.

---

# 76. Cost Attribution Rules

Similarly create:

```text
CostAttributionPolicy
```

Avoid arbitrary hidden assumptions.

Every dashboard metric should be explainable.

---

# 77. Data Quality

Create:

```text
AnalyticsDataQualityService
```

Detect:

```text
unmapped external assets

duplicate imports

missing currency

missing dates

counter resets

negative unexpected values

future timestamps

impossible metric values
```

---

# 78. Data Quality Status

Possible:

```text
VALID

WARNING

INVALID
```

Expose issues rather than silently correcting everything.

---

# 79. Metric Corrections

External platforms may revise revenue/download counts.

Support:

```text
CORRECTION
```

without deleting original history.

Preserve audit trail.

---

# 80. Manual Metrics

Allow manual metric import/correction where necessary.

Require:

```text
reason

createdBy

createdAt
```

Do not silently modify analytics facts.

---

# 81. Aggregation Strategy

Do not recompute millions of raw events on every dashboard request.

Use:

```text
incremental aggregates

scheduled rollups

materialized views
```

or appropriate architecture.

Start simple but production-ready.

---

# 82. Daily Aggregate

Create/refine:

```text
AnalyticsDailyAggregate
```

Dimensions may include:

```text
date

assetId

publicationId

platform
```

Metrics:

```text
views

likes

downloads

revenue

cost
```

Avoid excessive high-dimensional preaggregation initially.

---

# 83. Collection Daily Aggregate

Where needed:

```text
CollectionDailyAggregate
```

may be derived from asset aggregates.

Prefer derivation unless performance requires materialization.

---

# 84. Rebuildability

Aggregates must be rebuildable from authoritative facts.

Provide:

```text
AnalyticsRebuildJob
```

for:

```text
asset

collection

date range

all
```

---

# 85. Rebuild Safety

Rebuild should:

```text
recalculate
↓
replace aggregate transactionally
```

without duplicating metrics.

---

# 86. Analytics Jobs

Support:

```text
IMPORT

NORMALIZE

AGGREGATE

REBUILD

RECONCILE
```

with existing job infrastructure.

---

# 87. Analytics Dashboard

Create top-level:

```text
ANALYTICS
```

Dashboard sections:

```text
Overview

Assets

Collections

Prompts

Providers

Costs

Revenue

Platforms

Experiments
```

---

# 88. Overview

Top cards:

```text
Views

Likes

Downloads

Revenue

Cost

Profit

ROI
```

for selected period.

---

# 89. Period Selector

Support:

```text
Today

7D

30D

90D

YTD

Lifetime

Custom
```

Use explicit date boundaries.

---

# 90. Main Time-Series Chart

Show:

```text
Views
Downloads
Revenue
Cost
```

with metric selector.

Do not put incompatible scales on one unreadable axis.

---

# 91. Revenue vs Cost

Create chart:

```text
Revenue
vs
Cost
```

over time.

Also show:

```text
cumulative revenue

cumulative cost
```

where useful.

---

# 92. Profit

Show:

```text
Revenue - Cost
```

over selected period.

Make currency explicit.

---

# 93. Asset Performance Table

Columns:

```text
Preview

Asset

Collection

Published

Views

Likes

Downloads

Revenue

Cost

Profit

ROI
```

Sortable/filterable.

---

# 94. Asset Detail Analytics

Display:

```text
performance timeline

publication breakdown

cost breakdown

revenue breakdown

prompt

provider/model

QA

similarity cluster

processing lineage
```

This should connect analytics back to production.

---

# 95. Cost Breakdown

Example:

```text
Image generation      $0.18
Vision QA             $0.02
Upscale               $0.00
Metadata              $0.01
Video generation      $0.42
──────────────────────────
Total                 $0.63
```

Only show actual/configured costs.

---

# 96. Revenue Breakdown

Example:

```text
Wallpaper Backend      $3.20
Stock Platform A       $5.40
Stock Platform B       $1.10
──────────────────────────
Total                  $9.70
```

---

# 97. Collection Leaderboard

Avoid a simplistic unexplained “best collection” score.

Instead provide sortable factual columns:

```text
Downloads

Revenue

Profit

ROI

Approval Rate

Cost per Approved Asset
```

User chooses sorting dimension.

---

# 98. Asset Ranking

Likewise allow:

```text
sort by downloads

sort by revenue

sort by profit

sort by ROI

sort by views
```

Do not combine unrelated metrics into a hidden magic score.

---

# 99. Provider Comparison

Create comparison table:

```text
Provider
Model
Generated
Approved
Approval Rate
Cost
Cost/Approved
Downloads
Revenue
Profit
ROI
```

---

# 100. Prompt Comparison

Create:

```text
Prompt Version
Generated
Approved
Approval Rate
Cost
Downloads
Revenue
Profit
```

Include experiment attribution where applicable.

---

# 101. Conversion Funnel

Where data exists:

```text
Generated
↓
QA Approved
↓
Processed
↓
Published
↓
Viewed
↓
Downloaded
↓
Revenue
```

Show absolute counts and conversion rates.

---

# 102. Production Funnel

Separate from customer funnel:

```text
Ideas
↓
Generations
↓
QA Approved
↓
Similarity Accepted
↓
Processed
↓
Published
```

This reveals production waste.

---

# 103. Production Waste

Calculate costs associated with:

```text
failed generation

QA rejection

duplicate rejection

processing failure

never published
```

Do not assume every unpublished asset is waste; label carefully.

---

# 104. Cost Efficiency Dashboard

Show:

```text
Cost per generation

Cost per approved asset

Cost per published asset

Cost per download

Cost per $1 revenue
```

---

# 105. Cost Per $1 Revenue

```text
cost
───────
revenue
```

Handle zero revenue as undefined.

---

# 106. Break-Even

For assets with cost and revenue show:

```text
remaining amount to break even
```

Formula:

```text
max(cost - revenue, 0)
```

---

# 107. Break-Even Status

Possible factual states:

```text
NOT_RECOUPED

BREAK_EVEN

PROFITABLE
```

These are arithmetic states, not subjective quality judgments.

---

# 108. Cohort Analytics

Support grouping assets by:

```text
generation date

publication date

collection

prompt version
```

This helps compare similarly aged assets.

---

# 109. Age-Normalized Metrics

Raw lifetime downloads unfairly favor old assets.

Provide:

```text
downloads first 7 days

downloads first 30 days

revenue first 30 days
```

where data allows.

---

# 110. Asset Age

Calculate:

```text
days since publication
```

using first publication or platform-specific publication date.

Be explicit which date is used.

---

# 111. Velocity

Calculate:

```text
views/day

downloads/day

revenue/day
```

for selected windows.

Do not extrapolate future performance in TASK-10.

---

# 112. Trend Indicator

May show descriptive change:

```text
last 7 days
vs
previous 7 days
```

Example:

```text
downloads +18%
```

This is historical comparison, not a forecast.

---

# 113. No Prediction Yet

TASK-10 must not automatically predict:

```text
future revenue

future downloads

winning prompts
```

That belongs to future optimization/prediction work.

TASK-10 supplies trustworthy historical data.

---

# 114. Analytics Filters

Support combinations:

```text
project

collection

asset type

provider

model

prompt

preset

platform

status

date

AMOLED

stock

wallpaper

video
```

---

# 115. Saved Views

Optionally support:

```text
SavedAnalyticsView
```

Examples:

```text
Stock — Last 30 Days

AMOLED Wallpapers

Video Costs

Prompt Experiments
```

Do not overengineer if dashboard filtering is not mature yet.

---

# 116. Analytics API

Possible endpoints:

```text
GET /api/v1/analytics/overview

GET /api/v1/analytics/timeseries

GET /api/v1/analytics/assets

GET /api/v1/analytics/assets/{assetId}

GET /api/v1/analytics/collections

GET /api/v1/analytics/prompts

GET /api/v1/analytics/providers

GET /api/v1/analytics/platforms

GET /api/v1/analytics/costs

GET /api/v1/analytics/revenue
```

---

# 117. Analytics Query Model

Create a common query object:

```text
AnalyticsQuery

projectId

collectionId

assetType

provider

model

platform

from

to

groupBy

currency
```

Avoid implementing unrelated filter logic independently in every endpoint.

---

# 118. Import API

Possible:

```text
POST /api/v1/analytics/imports

GET /api/v1/analytics/imports

GET /api/v1/analytics/imports/{id}

POST /api/v1/analytics/imports/{id}/retry
```

Support dry-run.

---

# 119. Manual Metric API

If required:

```text
POST /api/v1/analytics/events
```

with proper validation/audit.

Do not expose unrestricted arbitrary event insertion without validation.

---

# 120. Performance

Analytics endpoints should not perform expensive full lineage traversal per row.

Use:

```text
optimized queries

aggregates

indexes

batch loading
```

Avoid N+1 queries.

---

# 121. Database

Create/refine migrations for:

```text
analytics_event

metric_snapshot

external_asset_reference

analytics_import_batch

analytics_import_error

analytics_daily_aggregate

analytics_currency_rate
```

Only create separate tables when justified.

Reuse existing:

```text
GenerationCost

Publication

PerformanceMetric
```

where appropriate.

---

# 122. Existing PerformanceMetric

TASK-01 introduced:

```text
PerformanceMetric
```

Inspect it before creating new analytics entities.

If it already supports required semantics:

```text
extend/refactor it
```

instead of maintaining two competing performance systems.

---

# 123. Indexes

Important indexes likely include:

```text
analytics_event.asset_id

analytics_event.publication_id

analytics_event.event_type

analytics_event.occurred_at

analytics_event.platform

metric_snapshot.external_asset_reference_id

metric_snapshot.captured_at

external_asset_reference.asset_id

external_asset_reference.platform

analytics_daily_aggregate.date
```

Add composite indexes based on actual query patterns.

---

# 124. Uniqueness

Enforce deduplication where possible at database level.

Example:

```text
source + deduplication_key
```

must be unique when key exists.

Do not rely only on application checks.

---

# 125. Partitioning Readiness

Do not prematurely partition small tables.

However design event tables so future:

```text
date partitioning
```

is possible if event volume becomes large.

---

# 126. Retention

Do not delete raw revenue/cost facts merely because aggregates exist.

Raw analytics facts are valuable for reconciliation.

Define explicit retention policy for high-volume non-financial events if needed.

---

# 127. Financial Data Precision

Use consistent decimal scale/rounding rules.

Document:

```text
calculation precision

display precision

currency rounding
```

Do not round each component prematurely before aggregation.

---

# 128. Time Zones

Store analytics timestamps consistently, preferably UTC.

Convert to selected display timezone in API/UI.

Do not aggregate daily metrics using ambiguous server-local timezone.

---

# 129. Data Reconciliation

Create:

```text
AnalyticsReconciliationService
```

Check:

```text
raw events
vs
aggregates

external snapshot
vs
internal aggregate

revenue imports
vs
calculated revenue
```

Report discrepancies.

---

# 130. Reconciliation UI

Provide diagnostics:

```text
Last analytics sync

Last aggregate rebuild

Unmapped rows

Import errors

Data-quality warnings
```

---

# 131. Observability

Metrics:

```text
media_factory_analytics_events_ingested_total

media_factory_analytics_events_duplicate_total

media_factory_analytics_import_rows_total

media_factory_analytics_import_errors_total

media_factory_analytics_unmapped_total

media_factory_analytics_aggregation_duration

media_factory_analytics_rebuild_duration
```

Keep labels low-cardinality.

---

# 132. Logging

Structured logs should include:

```text
importBatchId

assetId

publicationId

platform

metricType
```

Do not log entire large CSV contents.

---

# 133. Analytics Permissions

If project already has authentication/authorization, respect it.

Users must not gain analytics access to projects they cannot otherwise access.

Do not create a new auth architecture solely for TASK-10.

---

# 134. Export Analytics

Allow exporting analytics tables as:

```text
CSV
```

for selected filters/date range.

Useful datasets:

```text
Asset Performance

Collection Performance

Provider Performance

Prompt Performance

Cost Breakdown

Revenue Breakdown
```

---

# 135. Analytics Export Reproducibility

Include:

```text
generatedAt

filters

date range

base currency
```

so exported reports remain interpretable.

---

# 136. Unit Tests — Economics

Test:

```text
revenue

cost

profit

ROI

zero-cost ROI

cost per download

zero downloads

revenue per view

like rate

download rate
```

Use exact decimal assertions.

---

# 137. Unit Tests — Currency

Test:

```text
same currency

currency conversion

missing exchange rate

historical rate

rounding
```

Original amount must remain unchanged.

---

# 138. Unit Tests — Snapshots

Test:

```text
100 → 120 = +20

120 → 150 = +30

150 → 10 = reset/correction
```

Do not generate -140 engagement accidentally.

---

# 139. Unit Tests — Deduplication

Import identical:

```text
revenue event
```

twice.

Verify:

```text
revenue counted once
```

---

# 140. CSV Import Tests

Test:

```text
valid file

duplicate file

invalid row

unknown asset

unknown currency

quoted values

UTF-8

partial failure
```

---

# 141. Cost Attribution Tests

Test:

```text
direct cost

shared cost

equal split

unallocated cost
```

Verify no double counting.

---

# 142. Lineage Attribution Test

Create:

```text
Concept
↓
Prompt v3
↓
Generation using Provider A / Model B
↓
Asset
↓
Publication
↓
Revenue
```

Verify revenue appears under:

```text
Asset

Concept

Prompt v3

Provider A

Model B

Collection
```

without duplicating total revenue.

---

# 143. Rejected Asset Cost Test

Generate:

```text
10 assets

3 QA rejected

2 duplicate rejected

5 approved
```

Verify:

```text
collection cost
```

contains cost of all 10.

Verify:

```text
cost per approved asset
```

uses denominator 5.

---

# 144. Prompt Experiment Test

Generate:

```text
Variant A = 50 assets
Variant B = 50 assets
```

Add metrics.

Verify analytics keeps attribution to immutable experiment variant.

---

# 145. Multi-Platform Test

Publish one asset to:

```text
Wallpaper Backend

Stock Platform
```

Add separate metrics.

Verify:

```text
platform breakdown
```

remains separate while:

```text
asset total
```

is correctly aggregated.

---

# 146. Aggregate Rebuild Test

Create raw metrics.

Build aggregates.

Delete/rebuild derived aggregate data.

Verify calculated results are identical.

---

# 147. Integration Tests

Use:

```text
PostgreSQL Testcontainers
```

and mock platform import adapters.

Test:

```text
Asset
↓
Publication
↓
External Mapping
↓
Metric Import
↓
Normalization
↓
Aggregation
↓
Economics
↓
Analytics API
```

---

# 148. Performance Tests

Generate representative dataset, for example:

```text
10,000 assets

1,000,000 analytics facts/snapshots
```

Measure:

```text
overview query

asset table

collection aggregation

30-day time series

provider comparison
```

Do not set unrealistic requirements before measuring.

---

# 149. Frontend Tests

Test:

```text
period filters

metric filters

sorting

pagination

empty states

zero revenue

zero downloads

negative profit

currency display

loading

API failure
```

---

# 150. Documentation

Create:

```text
docs/analytics.md

docs/analytics-data-model.md

docs/analytics-ingestion.md

docs/asset-economics.md

docs/analytics-cost-attribution.md

docs/analytics-revenue.md

docs/analytics-imports.md

docs/analytics-dashboard.md
```

---

# 151. Data Model Documentation

Document difference between:

```text
event

snapshot

aggregate

economics
```

This distinction must remain clear.

---

# 152. Economics Documentation

Document formulas for:

```text
cost

revenue

profit

ROI

cost per approved asset

cost per download

revenue per download

download rate
```

Include zero-denominator behavior.

---

# 153. Attribution Documentation

Document exactly how metrics roll up:

```text
Publication
→ Asset
→ Generation
→ Prompt
→ Concept
→ Collection
```

and how duplicate counting is prevented.

---

# 154. Import Documentation

Document:

```text
CSV profiles

mapping

dry-run

deduplication

unmapped rows

retry

corrections
```

---

# 155. Definition of Done

TASK-10 is complete when:

```text
Asset
↓
Publication
↓
Views
Likes
Downloads
Revenue
↓
Cost
↓
Profit
↓
ROI
```

works end-to-end.

And:

```text
100 generated assets
↓
70 approved
↓
50 published
↓
external metrics imported
↓
analytics dashboard
```

correctly shows:

```text
generation cost

cost per approved asset

views

likes

downloads

revenue

profit

ROI
```

And the same performance can be grouped by:

```text
Asset

Collection

Prompt Version

Provider

Model

Platform
```

without double counting.

---

# 156. Verification

Before completing TASK-10:

1. run backend unit tests;
2. run integration tests;
3. run frontend tests;
4. run production builds;
5. apply Flyway migrations;
6. create test collection;
7. generate test assets;
8. create generation costs;
9. reject several assets;
10. approve several assets;
11. create publications;
12. create external asset references;
13. ingest views;
14. ingest likes;
15. ingest downloads;
16. ingest revenue;
17. verify event deduplication;
18. import same metrics twice;
19. verify totals unchanged;
20. ingest cumulative snapshot;
21. ingest second snapshot;
22. verify correct delta;
23. simulate counter reset;
24. verify no negative engagement;
25. calculate asset total cost;
26. calculate revenue;
27. calculate profit;
28. calculate ROI;
29. test zero-cost asset;
30. test zero-download asset;
31. calculate cost per download;
32. calculate download rate;
33. calculate collection metrics;
34. verify rejected generation cost included;
35. calculate cost per approved asset;
36. group by prompt version;
37. group by experiment variant;
38. group by provider;
39. group by model;
40. group by platform;
41. group by collection;
42. verify multi-platform asset attribution;
43. verify no revenue double counting;
44. test currency conversion;
45. verify original monetary values unchanged;
46. import CSV;
47. test dry-run;
48. test unknown asset mapping;
49. test invalid currency;
50. resolve unmapped row;
51. rebuild aggregates;
52. verify rebuilt totals;
53. test Today filter;
54. test 7D;
55. test 30D;
56. test Lifetime;
57. verify time-series boundaries;
58. verify UTC/date aggregation behavior;
59. test analytics export;
60. verify export contains filters/date range;
61. verify dashboard pagination;
62. verify sorting;
63. verify empty states;
64. verify data-quality warnings;
65. verify no N+1 queries on asset table;
66. run representative performance test;
67. verify no sensitive provider information exposed.

---

# 157. Final Codex Report

At completion provide:

## Architecture

Describe:

```text
Production
→ Publication
→ Analytics Ingestion
→ Raw Metrics
→ Aggregation
→ Economics
→ Dashboard
```

## Data Model

Report implemented:

```text
AnalyticsEvent

MetricSnapshot

ExternalAssetReference

ImportBatch

Aggregates
```

and how existing `PerformanceMetric` was reused/refactored.

## Metrics

List implemented:

```text
views

likes

downloads

revenue

cost

profit

ROI

cost per approved asset

cost per download

revenue per download

conversion rates
```

## Attribution

Explain attribution to:

```text
Asset

Collection

Concept

Prompt

Experiment Variant

Provider

Model

Platform
```

and how double counting is prevented.

## Imports

Report:

```text
supported import profiles

CSV

deduplication

dry-run

unmapped handling

corrections
```

## Economics

Explain:

```text
direct cost

shared cost

currency conversion

profit

ROI
```

## Frontend

Report implemented:

```text
Overview

Asset analytics

Collection analytics

Prompt analytics

Provider analytics

Cost

Revenue

Platform analytics
```

## Performance

Report representative dataset size and measured query performance.

## Tests

Report:

```text
unit

integration

currency

deduplication

attribution

aggregate rebuild

frontend

performance
```

## Remaining Limitations

Explicitly document:

```text
platform integrations not yet implemented

manual imports

missing metrics

FX limitations

attribution limitations

analytics latency

data-quality limitations
```

Never claim external platform metrics were tested live unless they actually were.

---

# Engineering Principles

Throughout TASK-10 follow these rules:

1. Analytics must be reconstructable from authoritative facts.
2. Do not rely only on mutable counters.
3. Distinguish raw events, cumulative snapshots and aggregates.
4. External metrics must map through explicit external asset references.
5. Publication-level data remains distinguishable from asset-level totals.
6. One asset may exist on multiple platforms.
7. Multi-platform rollups must not double-count metrics.
8. Monetary values use `BigDecimal`, never floating point.
9. Every monetary value has an explicit currency.
10. Preserve original monetary values after currency conversion.
11. Exchange-rate provenance must be recorded.
12. Cost calculations must include failed and rejected generation attempts where applicable.
13. Do not calculate production cost only from approved assets.
14. Failed billed API calls count toward cost.
15. Duplicate-generation cost must be measurable.
16. QA-rejected generation cost must be measurable.
17. Local compute must not be assigned fake monetary cost unless a cost model is configured.
18. Shared costs must use explicit allocation rules.
19. Never silently duplicate shared costs across assets.
20. Revenue and cost attribution must be explainable.
21. Analytics ingestion must be idempotent.
22. Importing the same file twice must not double metrics.
23. Use database constraints for deduplication where possible.
24. Preserve corrections/audit history.
25. Do not silently discard unmapped external metrics.
26. Aggregates must be rebuildable.
27. Dashboard requests should use efficient aggregates/queries.
28. Avoid N+1 lineage traversal.
29. Store timestamps consistently and aggregate using explicit timezone rules.
30. Handle zero denominators explicitly.
31. `0 downloads` does not mean `cost per download = 0`.
32. `0 cost` makes standard ROI undefined.
33. Avoid one opaque “performance score”.
34. Expose individual metrics so humans can choose what matters.
35. Prompt analytics must use immutable prompt-version attribution.
36. A/B analytics must use immutable experiment-variant attribution.
37. Do not reconstruct historical experiment assignment from current config.
38. Correlation must not be presented as causation.
39. Lifetime metrics must be distinguishable from age-normalized metrics.
40. Older assets should not automatically appear superior merely because they have existed longer; provide comparable time windows.
41. Do not forecast future performance in TASK-10.
42. Do not automatically change generation strategy in TASK-10.
43. Do not automatically declare A/B winners.
44. TASK-10 provides factual measurements; TASK-11 will consume them for feedback/optimization.
45. Every dashboard number should be traceable to underlying data.
46. Every important formula must be documented.
47. Analytics imports must support dry-run and diagnostics.
48. Data-quality problems should be visible rather than silently hidden.
49. Financial facts should have stronger retention/audit guarantees than disposable telemetry.
50. Design analytics so TASK-11 can later correlate **concept → prompt → provider → model → visual attributes → QA → similarity → processing → publication → performance → revenue → cost**.

The result of TASK-10 should turn Media Factory from a system that merely **produces media** into a system that can accurately measure the complete economics and performance of every generated asset — from the first paid generation attempt through QA and processing to views, likes, downloads, revenue, profit and ROI — while preserving enough attribution to power the future **TASK-11 Feedback Engine**.
