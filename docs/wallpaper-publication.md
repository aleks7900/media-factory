# Wallpaper publication and exports

Publication eligibility is enforced server-side: current QA approval, complete active-model similarity analysis, no TASK-05 blocking reason, successful required variants, readable matching checksums, generic fallback, preview/thumbnail, valid metadata and suitable AMOLED analysis where requested. Human approval identifies a particular immutable package/version, not just a wallpaper title.

`WallpaperPublicationTarget` isolates publish/update/unpublish. MOCK persists a fake remote catalog and idempotency ledger in local PostgreSQL. ANDROID is disabled. No real external HTTP API is invented or called.

## Workflow

1. POST `/api/v1/wallpapers/{id}/publication-dry-run` with target MOCK or ANDROID. Inspect eligibility, planned operations and target availability; no remote mutation occurs.
2. POST `/prepare-publication`. The package freezes metadata, collection, original/master references, variants/checksums, prompt/profile provenance, processing lineage and version. Inspect it in Wallpaper Review.
3. POST `/approve-publication` with packageId and current production revision. Human approval is recorded once. Metadata/reprocessing changes require renewed review.
4. POST `/publish` with target MOCK queues a durable delivery. The worker calls the target outside DB transactions and persists the result/external identity afterward.
5. POST `/unpublish` disables the mock catalog entry without deleting originals, costs, packages or variants.
6. After unpublish, preparing again creates a new version. Approve and publish it to update the same external wallpaper identity.

Retries reuse the exact package and idempotency key. A timeout after acceptance replays the stored mock result. Authentication/validation/incompatible-contract failures are not automatically retried. Transient failures use bounded durable retries; manual retry is available for eligible failure codes through `/api/v1/wallpaper-deliveries/{id}/retry`. Short DB locks serialize claim per production, while renewable leases/fencing protect state updates. No binary transfer runs inside a DB transaction.

Collection operations return per-member results. Partial success is retained and retry does not duplicate completed wallpapers. A real remote collection activation saga remains part of the future adapter contract; the local mock has no Android discovery service.

## Export fallback

POST `/api/v1/wallpapers/{id}/export` or `/api/v1/wallpaper-collections/{id}/export` queues an export; GET `/api/v1/wallpaper-exports` shows status. Download completed packages from `/{id}/content`.

ZIPs contain a root manifest and versioned wallpaper directories with manifest.json, master/, variants/, previews/ and thumbnails/. Each binary is verified against its frozen checksum; the ZIP itself has a recorded SHA-256 and immutable storage key. PostgreSQL/packages and storage remain authoritative; ZIP is a delivery artifact. Export workers recover abandoned leases and never overwrite an earlier ZIP.

Exports currently cap total uncompressed image data at 256 MiB and reject collections exceeding 100 ready wallpapers per request. Export smaller groups for larger catalogs; a future streaming/partitioned export implementation can lift this bound. Source assets and publication history are never deleted by unpublish or export.

The deployment follows the existing private local-workspace actor model. Production multi-user authorization, public delivery URLs and real Android credentials require an authenticated deployment and the actual adapter contract. Credentials never enter frontend DTOs or manifests.

