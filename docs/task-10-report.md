# TASK-10 — Analytics & Asset Economics implementation report

Verified on 2026-10-01. The Analytics workspace is deployed at `http://localhost:3000` with Flyway schema version 13. This report describes the delivered implementation and its limits; it does not imply that external platform integrations were tested live.

## Architecture and data

The existing `performance_metrics` table is extended into an immutable measurement ledger. `generation_costs` remains the sole operation-cost source. External references, cumulative snapshots, import staging, daily FX rates, explicit allocations and durable jobs are separate records with distinct responsibilities. SQL views join existing generation, prompt, QA, processing, wallpaper, stock and video lineage without copying production entities.

Daily original-currency aggregates are rebuildable. Non-UTC and partial-day queries use authoritative timestamps. Legacy measurements with unknown original semantics are retained, flagged and excluded from normalized totals. DB constraints enforce deduplication, lineage and allocation conservation. No original media is overwritten.

## Attribution and economics

Reporting connects asset → generation → concept → collection → project and the generation's immutable prompt-version/experiment-variant assignment. Provider/model charge attribution uses the actual operation provider; downstream revenue uses final generation attribution. Costs from failed generations without assets remain in collection/project totals. Explicit DIRECT/EQUAL_SPLIT/WEIGHTED allocations replace, rather than duplicate, the source charge.

Profit, ROI, cost/revenue per download, rates, cost per approved/published generation, rejection cost and break-even amount use exact backend decimal arithmetic. Unknown measurements, missing rates and unpriced operations remain unavailable. Estimated costs remain identifiable as estimates. Platform and device-variant costs are not guessed by repeating the master's charge.

## Ingestion and dashboard

- Audited manual/API events, external identity mapping and cumulative snapshots.
- Generic quoted UTF-8 CSV column profiles; validation before commit; expected revenue by currency; partial failures; retained unmapped rows; mapping retry; file and semantic-row deduplication.
- Append-only signed corrections with original-event references.
- Overview, assets, collections, prompts, providers, costs, revenue, platforms and experiment tables.
- Period, currency, timezone, project, collection, provider/model and platform/pipeline filters; sorting and pagination; CSV exports containing report context.
- Daily/weekly/monthly chart controls, revenue/cost comparison, production counts, thumbnail previews and asset evidence details.
- Diagnostics for rebuilds, imports, legacy data, counter resets, suspicious totals and raw/aggregate reconciliation.
- Durable analytics IMPORT/NORMALIZE/AGGREGATE/REBUILD/RECONCILE jobs, leases, retries and scheduled refresh.

No forecasting, statistical winner declaration, hidden performance score, paid AI call or automatic strategy change was added.

## Verification results

| Check | Result |
| --- | --- |
| Backend unit tests | 115 passed |
| Backend integration tests | 122 passed, including opt-in benchmark; zero skipped in final run |
| Frontend tests | 49 passed |
| Java production JAR | Passed |
| TypeScript/Vite production build | Passed locally and in Docker |
| Docker Compose GPU-overlay stack | Started; all long-running services healthy |
| Flyway | V12 and V13 applied successfully; V1–V11 preserved |
| Backend `/actuator/health` | HTTP 200, UP |
| Frontend `/health` | HTTP 200 |
| Live analytics API smoke | 15 endpoints passed, plus asset-detail queries |
| Browser smoke | Navigation, period/timezone, monthly series, sorting, export, rebuild, 1536px/1100px layout; no JavaScript errors |
| Original media integrity | All 6 existing original assets downloaded read-only and SHA-256 verified |

The acceptance fixture contains 100 generated assets, 70 approved, 30 rejected, 50 publications, $10 cost and $50 revenue: $40 profit. Of rejected production spend, $1 belongs to ten duplicate rejections. Another fixture verifies a billed failed generation without an asset. Changing experiment weights after assignment leaves the original 50/50 cohorts unchanged. Other tests cover multi-platform sums, missing FX, out-of-order snapshots/resets, deduplication, immutable corrections, exact allocation, time boundaries, imports, first-30-day revenue, scoped date/collection replacement and repeated rebuild equality.

Runtime evidence is in ignored local folders: `backend/build/task10-*-results`, `backend/build/task10-benchmark.json`, and `frontend/test-results/analytics-*.json` / screenshots. Database backups before V12 and V13 are at `storage/data/analytics-verification/task10-before.sql` and `storage/data/analytics-verification/task10-before-v13.sql`. Synthetic benchmark and acceptance financial data stayed in disposable Testcontainers databases.

## Measured performance

Final local PostgreSQL 17 Testcontainers dataset: 10,000 generated benchmark assets, 1,000,000 external VIEW events (plus the small test setup fixture). Queries select the same 30-day period. These are single-run development-machine observations, not a production SLA, percentile study or load-concurrency test.

| Operation | Elapsed |
| --- | ---: |
| Insert 1,000,000 facts | 34,218 ms |
| Rebuild daily aggregates | 991 ms |
| Overview | 64 ms |
| Paginated asset table | 99 ms |
| Collection summary | 81 ms |
| 30-day time series | 93 ms |
| Provider comparison | 76 ms |

## Remaining limitations

- No live Android, stock or social connector exists; measurements require explicit API/CSV import. Export is never treated as stock acceptance or publication.
- FX is manually supplied by original-currency/base-currency/UTC date. No live feed, triangulation or accounting-close workflow is implemented.
- Rebuild jobs support transactional asset/collection/date/all replacement. The background scheduler currently uses full rebuilds when dirty; incremental dirty-bucket scheduling is not implemented.
- The UI exposes observed generation/approval/processing/publication counts rather than claiming a reconstructed historical transition log. First-7/30-day downloads, first-30-day revenue and bounded-window velocities are available; missing platform observation coverage remains unknown.
- Presets and processing profiles are grouped as sets to avoid duplicate attribution. Similarity uses latest collection membership; it is not a historical clustering dimension. Provider-operation/processing diagnostics are lifetime reports.
- Detailed evidence lists and charts have documented bounds. The desktop dashboard targets the existing workstation layout; mobile redesign is not included.
- A first cumulative observation is only a baseline; unknown resets and missing observation coverage cannot produce complete historical totals. Diagnostics expose these gaps.
- Dashboard aggregates normally lag ingestion by about one worker refresh interval. Rebuild uses a transactional aggregate replacement; custom timezone reports can cost more because they read timestamp facts.
- CPU/GPU utilization and local monetary rates are not fabricated. Available wall-time and byte measurements remain separate from money.
- The existing local authorization model is preserved; project selection is not multi-tenant access control. No paid provider or live external-metric verification was performed.

For setup and API examples, start with [analytics.md](analytics.md).


