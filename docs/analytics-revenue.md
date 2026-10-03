# Revenue and platform attribution

Initial revenue attribution is DIRECT: a mapped publication/variant/master event belongs to that master asset. Events retain platform and publication identity. Asset/project rollups sum the facts once. A stock ZIP export is not evidence of platform submission, acceptance, publication or revenue.

REVENUE adds its value. REFUND subtracts its positive value. Signed corrections adjust the original metric through an explicit original-event reference. Monetary rows require ISO currency; all other measurement rows have no currency. A download or purchase count is not converted to money using an invented rate.

Publication counts use the existing `publications` table and exclude known wallpaper MOCK/DRY_RUN/EXPORT channels. Mapping an external ID does not itself create a publication or bypass human publication approval. Future platform adapters must supply an approved publication through the production boundary when appropriate.

The Android backend remains a future external system. Generic CSV/API ingestion can consume its measurements once its contract exists. No Android, stock or social production endpoint, credential or live metric feed was invented or exercised.

Missing revenue is NULL. Zero revenue must be supplied explicitly if the platform actually reports it. Multi-platform revenue is converted per fact's date/currency before the common-currency total is calculated. Platform cost allocation is intentionally not guessed.

Asset details expose per-platform original-currency revenue, publication records, event audit and first-publication age. First-seven/first-thirty-day downloads and converted first-thirty-day revenue are exposed only after the corresponding age window has elapsed; missing observation coverage remains a limitation. The first real recorded publication is used, excluding known mock/dry-run/export channels. Bounded period queries expose views/downloads/revenue per elapsed 24-hour day. Lifetime velocity is unavailable without a selected start date. There is no predicted break-even date or future revenue extrapolation.
