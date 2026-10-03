# Analytics data model

## Authoritative records

| Record | Purpose |
| --- | --- |
| `performance_metrics` | Existing foundation table extended into the immutable event ledger. Original `name`, `value`, `measured_at` fields are retained. |
| `generation_costs` | Sole monetary operation-cost source, including unsuccessful operations and explicit estimates. |
| `external_asset_references` | Unique `(platform, external_id)` mapping to asset, optional variant and publication. |
| `metric_snapshots` | Immutable cumulative observations with timestamp, source, key and reset policy. |
| `analytics_currency_rates` | Audited immutable daily conversion rates. |
| `analytics_cost_allocations` | Explicit allocation of an existing operation cost, never a second cost ledger. |
| `analytics_import_batches`, `analytics_import_rows` | File identity, frozen column mapping, staged normalized rows and per-row diagnostics. |
| `analytics_jobs`, `analytics_rebuild_runs` | Durable queue execution and successful aggregate refresh audit. |

`analytics_lineage` is a view with one row per generation, including generations without an asset. It joins immutable prompt/experiment assignment and existing production records. Presets are grouped as a recorded preset **set**; processing profiles are grouped as a profile **set** to prevent multiple variants multiplying a master's economics. Similarity membership uses the latest clustering run per collection, not an invented historical membership snapshot.

`analytics_cost_facts` projects existing operation charges. `analytics_attributed_costs` substitutes complete explicit allocations where present. `analytics_snapshot_deltas` orders cumulative observations by captured time. `analytics_normalized_metrics` combines external event increments and valid snapshot deltas.

`analytics_daily_aggregate` is a PostgreSQL derived table grouped by UTC date, asset, variant, publication, platform, metric and original currency. Its unique index treats NULL dimensions as equal. It is entirely reconstructable; no financial input is stored only in an aggregate.

V12 introduced the initial materialized projection; V13 preserves its existing data while converting it to a table that supports transactional scoped replacement. Rebuilds can target an asset, collection, UTC date range, or all facts. A refresh never adds a second copy of the previous aggregate.

Legacy performance rows retain IDs and original values. Since the foundation did not declare cumulative/event or currency semantics, they are marked `LEGACY`/`WARNING` and excluded from normalized totals until reviewed and explicitly reimported. They are not silently reinterpreted as revenue.

## Constraints

Database uniqueness enforces source/idempotency-key deduplication. Variant and publication references must belong to the stated asset. Snapshot timestamps are unique within an external-reference/metric stream. Facts and exchange rates cannot be updated or deleted. A deferred allocation constraint requires allocations to sum exactly to the original operation charge. An allocated charge's amount/currency cannot subsequently change.

Indexes cover asset/time/type, publication/time, platform/time, snapshot stream, external mapping and aggregate date/asset. No premature partitioning is introduced.

