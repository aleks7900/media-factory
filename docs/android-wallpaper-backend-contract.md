# Proposed Android wallpaper publication contract v1

This is a **proposed integration contract**, not a discovered production API. No separate Android backend repository or API currently exists. There are no configured production endpoints, URLs, credentials, authentication schemes or shared-storage assumptions.

The implemented boundary is `WallpaperPublicationTarget`. `MOCK` is a durable local catalog used for development/tests. `ANDROID` is deliberately unavailable and returns `ANDROID_CONTRACT_NOT_CONFIGURED`. Implementing the future transport must not change Wallpaper Factory orchestration or its domain metadata.

## Version and operations

The domain package identifies `contractVersion: media-factory-wallpaper-package/1`. A future backend must negotiate supported contract versions and reject incompatible major versions explicitly. Concrete HTTP methods, paths, headers and response DTOs must be established with that backend before enabling the adapter.

Required logical operations:

| Operation | Required behavior |
|---|---|
| Publish wallpaper | Upsert a stable external wallpaper identity and immutable publication version; activate only after all required binaries and metadata validate |
| Update wallpaper | Create a new immutable version of metadata and/or variant references; retain previous version history |
| Unpublish wallpaper | Disable discovery/download according to backend policy; preserve external identity and Media Factory history |
| Upsert collection | Create/update collection title, slug, description, theme, style, cover, order, featured and AMOLED metadata |
| Activate collection | Make a staged collection discoverable only when required members have completed publication |
| Reconcile operation | Resolve an ambiguous timeout by idempotency key or stable external ID and version |
| Categories/tags | Discover or validate the backend taxonomy; map internal category/tag keys without matching by display title alone |

The future adapter must expose unsupported capabilities before mutation. In particular, an adapter without idempotent publication/reconciliation cannot be enabled for automatic retries.

## Publication envelope

The adapter receives an idempotency key, canonical manifest SHA-256, and a versioned domain manifest. Required fields are:

- `wallpaperId`: stable Media Factory production UUID; retain it as an external integration identity.
- `publicationVersion`: positive, monotonically increasing version per wallpaper.
- `metadata`: title, slug, description, structured tags/category, `premium` boolean (`false` means free), featured boolean, sort order where supported.
- `collection`: stable Media Factory collection ID and collection metadata.
- `amoled`: structured boolean, with immutable analysis algorithm, policy thresholds, measurements, classification and warnings in `amoledAnalysis`.
- `originalAssetId`, `masterVariantId`, `generationId`, `processingRunId`: provenance references; internal references must not be interpreted as public URLs.
- `variants`: master, device-family outputs, preview and thumbnail descriptors.
- Frozen profile and prompt provenance for audit/export. The future adapter may omit private generation details from the public Android discovery API.

Each variant descriptor includes ID, type, physical width/height, numeric aspect ratio, format, file size, SHA-256, quality tier, immutable storage reference, original/parent artifact IDs and processing profile version. A master is not a substitute for a missing preview or thumbnail. Every wallpaper includes `ANDROID_GENERIC_PORTRAIT` as a fallback.

## Storage and integrity

`storageReference` currently identifies an immutable object owned by Media Factory. It is **not** a production URL and is not proof that the Android backend shares storage.

The future integration must choose and document one negotiated strategy:

1. Upload/copy binaries through a backend-supported upload API, then reference backend-owned immutable object IDs; or
2. Provide versioned externally reachable object references accepted by the backend, with an explicit access/expiry policy.

The adapter's `AssetTransfer` port isolates this choice. A successful transfer returns an external reference and verified SHA-256. Validate each binary before transfer, and verify remote integrity when the receiver supports it. No transfer implementation is fabricated in TASK-07. Mock publication records local references without copying or serving them to an Android app. Export ZIPs provide binaries for manual import.

## Idempotency, results and failures

Identical idempotency key plus manifest checksum must return the original outcome, even after a timeout. Reuse of a key with a different checksum is a conflict. Concurrent attempts must create at most one external wallpaper version. A later version cannot be replaced by delayed earlier work.

A result contains `externalId`, accepted `version`, publication `status`, and safe evidence. Persist this mapping; never recover identity solely by matching slugs or titles. Unpublish has its own stable key and must not disable a newer version accidentally.

Retry temporary unavailability, throttling with backoff, storage failures and ambiguous timeouts only under the idempotency contract. Do not blindly retry authentication/authorization failures, schema validation failures or incompatible API versions. Preserve each successfully completed collection member and resume only unresolved members.

## Authentication and authorization

The future backend determines the service authentication scheme. `Authentication.requestHeaders()` is the adapter-side boundary; no scheme or credentials are prescribed. Secrets must come from server configuration/secret storage, never manifests, frontend bundles, URLs or logs. Scope the integration identity to required administrative publication operations. The public Android API and the privileged publication API are separate trust boundaries.

Media Factory currently uses its existing private local-workspace trust model. Human publication approval records that actor and a specific immutable package. A multi-user deployment needs a real authenticated actor/authorization integration before exposing mutation endpoints remotely.

## Android consumption

The backend's future discovery response must provide collection/category metadata, premium/free/featured/AMOLED state, publication status/version, lightweight preview/thumbnail URLs, and available variant descriptors. The app selects the smallest adequate physical-pixel variant, respects quality preference and aspect compatibility, then uses the generic fallback. Screen density must not be multiplied into values already measured in physical pixels. No public Android endpoint is implemented or implied by this proposal.

## Integration analysis

1. Reusable external endpoints: none available to inspect.
2. Missing capabilities: actual external HTTP transport, taxonomy, credentials, remote asset transfer and public Android discovery.
3. Authentication: abstract port only; scheme pending backend selection.
4. Storage/upload: immutable local references and export work; remote transfer remains unconfigured.
5. Publication mapping: domain manifest and result port implemented; vendor DTO mapping belongs in the future adapter.
6. Collections: existing Media Factory collections are extended, not duplicated; remote collection mapping is proposed above.
7. Variants: TASK-06 AssetVariants with explicit physical dimensions and lineage.
8. AMOLED: structured boolean plus deterministic evidence; no battery-life claim.
9. Identity: production UUID + publication version + stable request key; mock verifies replay durably.
10. Backend changes: none performed. Implement/negotiate this capability contract when a real backend becomes available.
