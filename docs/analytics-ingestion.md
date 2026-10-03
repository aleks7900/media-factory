# Analytics ingestion

All routes are under `/api/v1/analytics`.

1. `POST /references`: map `{assetId, variantId?, publicationId?, platform, externalId, externalUrl?}`. The same identity cannot be silently reassigned.
2. `POST /events`: submit `{assetId, variantId?, publicationId?, platform, type, value, currency?, occurredAt, source, deduplicationKey, reason, createdBy, metadata?}`.
3. `POST /snapshots`: submit `{referenceId, type, value, currency?, capturedAt, source, deduplicationKey, resetPolicy, reason, createdBy}` for cumulative counters.

Sources are explicit, for example `WALLPAPER_BACKEND`, `STOCK_PLATFORM`, `SOCIAL_PLATFORM`, `CSV_IMPORT`, `MANUAL_IMPORT`, `API`. They are not inferred from media type. External events require an existing matching asset/platform/publication/variant mapping. Audited `MANUAL_IMPORT` can address an internal asset directly. Reserved `MEDIA_FACTORY` and `LEGACY` sources cannot be supplied through the external API.

Types support uppercase extensible names, including VIEW, LIKE, DOWNLOAD, REVENUE, REFUND, IMPRESSION, CLICK, SAVE, SHARE, PURCHASE and SUBSCRIPTION_ATTRIBUTION. Production counts, publication records and operation costs come from their production ledgers, not arbitrary external insertions. REVENUE and REFUND are monetary; a PURCHASE count is not automatically money.

Values use decimal arithmetic with at most 16 integer and 12 fractional digits. Timestamps require an offset and at most microsecond precision; timestamps more than five minutes ahead are rejected. Non-correction negative measurements are rejected. Reason and actor are mandatory. This local application's actor field is an audit label, not an authenticated remote identity.

## Counter semantics

The first snapshot is a baseline with unknown prior activity. `100 → 120 → 150` produces deltas `unknown, 20, 30`. `150 → 10` with `UNKNOWN` produces a visible reset warning and no negative increment. `RESET_TO_ZERO` explicitly treats the new counter value as activity after a reset. Late observations recompute ordered deltas on the next rebuild. Deltas belong to the later observation timestamp; activity is not fabricated across the interval between polls.

The same source/asset/platform/publication/variant/metric cannot mix event and snapshot ingestion. Snapshot source and currency cannot change within a stream. Separate publications retain separate facts. First baselines and unexplained resets mean incomplete coverage, shown in diagnostics.

## Corrections

Append an event with `correctionOf` pointing to an existing event and a signed adjustment. Asset, variant, publication, platform, metric and currency must match. A correction does not delete or overwrite the original. Choose `occurredAt` explicitly: it determines the reporting period and conversion date.

## Jobs

`POST /jobs/{IMPORT|NORMALIZE|AGGREGATE|REBUILD|RECONCILE}` accepts an `Idempotency-Key` and a JSON payload. IMPORT takes `batchId`; rebuild operations accept `reason` and `createdBy`. GET `/jobs` lists recent executions. Jobs use PostgreSQL row claiming, lease tokens, attempt counts, delayed retries and terminal failures, consistent with the existing QA/processing job pattern. The original foundation `jobs` table requires a unique generation and cannot represent cross-project analytics jobs.

The worker checks jobs every five seconds and dirty aggregates every sixty seconds by default. `media.worker.enabled=false` disables background execution in tests. Normalization is a deterministic SQL view. NORMALIZE/AGGREGATE/REBUILD transactionally replace aggregates; optional `assetId`, `collectionId`, `from` and `to` scope the rebuild. Rebuild dates are UTC calendar dates with exclusive `to`. Omit scope to rebuild everything. Scoped runs do not advance the global freshness watermark. Reconciliation reports differences without mutating facts.

