# Wallpaper collections

TASK-07 extends the existing Collection with a wallpaper flag, slug, description, theme/style, structured AMOLED flag, status, cover Asset reference, order, featured flag and revision. Concepts, generations and rejected assets remain in their original domain tables.

The collection lifecycle is DRAFT → GENERATING → REVIEW/READY → PUBLISHED, with ARCHIVED available for catalog administration. A collection production plan targets ready approved wallpapers, not raw generated count. It stores target, batch size, maximum candidate attempts, monetary budget, per-candidate cost reserve, profile, status and revision.

The worker waits for the current batch's QA, similarity and processing outcomes before requesting replacements. Rejected generations remain charged to the collection. Generation failures, unknown cost and paused work require intervention rather than silently spending more. At the target, no further batch is dispatched. At maximum attempts/budget, the plan pauses. An explicit budget and reserve are required before automatic paid image generation; provider retries remain bounded by TASK-02. Reservations are admission estimates, not a remote provider-enforced hard billing ceiling.

TASK-05 clustering and `GenerationBatchService.shouldPause` are reused between batches. Excessive largest-cluster share produces PAUSED_DIVERSITY; the prompt-level DiversityGuard also remains active. Human review or changed concepts/policies are required to resolve repetition. The system does not ignore diversity checks to fill a numerical target.

## API

Under `/api/v1`:

- POST `/wallpaper-collections`: projectId, title, slug, description, theme, style and amoled.
- Add concepts with the existing POST `/api/concepts` endpoint.
- POST `/wallpaper-collections/{id}/produce`: targetApproved, batchSize, maxAttempts, maximumCost, reservedCostPerAttempt and wallpaperProfile.
- GET `/wallpaper-collections/{id}/production-status`: ready/generated/rejected/published/active counts, plan, costs and TASK-05 diversity report.
- POST `/wallpaper-collections/{id}/pause`, `/resume`, `/cancel`: revision and reason. Pausing prevents new generation/processing dispatch; completed assets remain.
- GET `/wallpaper-collections/{id}/cover-candidates`: ready candidates ranked by source resolution, with manual selection available.
- POST `/wallpaper-collections/{id}/cover`: assetId from the same ready collection; queues TASK-06 COLLECTION_COVER (1600 × 900), COLLECTION_CARD (800 × 450), COLLECTION_THUMBNAIL (240 × 135), WebP smart crop with safe fallback.
- POST `/wallpaper-collections/{id}/prepare-publication`, `/publish`, `/unpublish`: target and per-member outcomes. Already delivered members retain their stable identities on replay.
- POST `/wallpaper-collections/{id}/export`: asynchronous immutable ZIP of prepared ready packages.

The initial concept library is authored through existing Concept APIs/UI; automated text-provider concept synthesis is not performed. Collection automation cycles through those concepts and stops when diversity protection requires new direction. Cover ranking is a deterministic resolution heuristic, not a learned composition/market-performance score.
