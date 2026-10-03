# Analytics dashboard and API

The dark Analytics workspace contains Overview, Assets, Collections, Prompts, Providers, Costs, Revenue, Platforms and Experiments sections. Cards distinguish zero from unavailable and show negative profit explicitly. Tables use server sorting/pagination; chart scales switch between individual metrics.

Period choices: Today, 7D, 30D, 90D, YTD, Lifetime and Custom. Dates are half-open `[from,to)`. The UI converts calendar boundaries for UTC, Europe/Bucharest, America/New_York and Asia/Tokyo, including DST. The API accepts valid IANA zones. Day/week/month chart intervals are explicit. A chart displays at most 200 intervals with a visible truncation notice; narrow the period or choose a wider interval.

Filters include project, collection, provider, model, platform and stock/wallpaper/video/AMOLED. API filters additionally include status, assetType, prompt-version ID and preset key. Grouping supports asset, collection, project, concept, prompt version/template, experiment/variant, provider/model, platform, recorded preset set, style, generation profile, video/motion/wallpaper profile, loop strategy, pipeline, AMOLED, generation date, publication date, primary rejection reason, processing-profile set, latest similarity cluster, publication and device variant. Missing dimensions are UNATTRIBUTED rather than reconstructed from today's configuration.

GET endpoints under `/api/v1/analytics`:

| Endpoint | Purpose |
| --- | --- |
| `/overview`, `/assets`, `/collections`, `/prompts`, `/providers`, `/platforms`, `/costs`, `/revenue`, `/experiments` | Common filtered, sorted and paginated metric projection |
| `/timeseries` | Time-series projection; `grain=day|week|month` |
| `/assets/{id}` | Lifetime lineage, costs, conversions, publications, revenue, QA, processing, clusters and recent event evidence |
| `/provider-operations` | Lifetime request outcomes/rate limits/latency for image and video generation attempts |
| `/processing` | Lifetime image/video measured processing duration and available byte counts |
| `/diagnostics` | Rebuild status, imports, counter warnings, legacy counts and raw-vs-aggregate reconciliation |
| `/data-quality` | Unmapped/invalid rows, unpriced charges and suspicious engagement totals |
| `/export` | CSV of the selected query, including filters, boundaries, currency, timezone and generation time |

Common parameters: `projectId`, `collectionId`, `assetId`, `provider`, `model`, `platform`, `from`, `to`, `currency`, `timezone`, `groupBy`, `sort`, `direction`, `page`, `size`. Size is 1–200. Sort columns are allowlisted. CSV export uses a repeatable-read transaction and a 100,000-group limit. Text cells are escaped against spreadsheet formula execution.

Aligned UTC queries use daily aggregates; non-UTC or partial-day queries use authoritative timestamp facts for correct boundaries. Queries batch joins rather than traversing lineage in application code per row. Aggregates refresh on a roughly one-minute schedule, or by **Refresh aggregates**. Costs are read from their authoritative projection. Diagnostics identify freshness and reconciliation differences.

Asset details expose original evidence with bounded lists (200 recent events, 500 cost rows, 100 review/processing/cluster rows); they are not an unlimited raw-ledger export. Preset/profile sets are explicit cohort dimensions, not additive per-member allocations. Provider operation and processing endpoints currently report lifetime diagnostics separately from the common filtered economic table.

No dashboard score selects a winner. Age-normalized observations do not prove complete platform coverage or causation. No automatic recommendation or prediction is produced.


