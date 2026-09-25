# Android backend integration status

Real Android wallpaper backend integration was not implemented because no external backend repository/API contract was provided. The publication boundary, mock implementation, dry-run flow and future backend contract were implemented instead.

See [the proposed versioned contract and integration analysis](android-wallpaper-backend-contract.md). It describes the minimal capabilities, metadata/collection/variant/AMOLED mapping, identity/version/idempotency requirements and transfer/authentication boundaries without inventing production endpoints or credentials.

`AndroidWallpaperBackendPublicationTarget` explicitly reports unavailable and fails before mutation. To activate a future integration, inspect its real repository/HTTP API, implement adapter-local DTO mapping, authentication and asset transfer, and add contract-server tests for its actual status codes and payloads. No Media Factory generation, QA, similarity, processing or storage service should need replacement.

The mock target tests publish, update, unpublish, duplicate request replay and timeout reconciliation. They are domain contract tests, not evidence that any external Android HTTP API was tested. Android app discovery/downloads and backend-side staged collection activation remain unverified until that backend exists.
