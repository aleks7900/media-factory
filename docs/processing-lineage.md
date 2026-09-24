# Processing lineage, cache and retention

`Asset(original) → ProcessingArtifact(lossless upscale) → ProcessingArtifact(final) → AssetVariant`.

Artifacts have explicit source_asset_id, parent_artifact_id, run_id, operation, immutable storage key, SHA-256, dimensions and metadata. Variants retain source, parent, artifact, profile version and run IDs with `asset_role=DERIVED`. Original byte checksums are verified before execution. Final hashes reuse TASK-05's `PerceptualHash.sha`; derivatives never trigger master fingerprint ingestion.

Storage keys are `assets/processed/{sourceId}/{runId}/{artifactId}.{format}`. IDs remain authoritative; paths are not identities. Existing MediaStorage write-once semantics protect every object. PostgreSQL makes plans, artifacts, manifests and validation immutable. The database transaction registers a final variant only after worker validation and immutable storage write. A lease fence prevents a cancelled/stale worker from publishing a result.

The cache binds source identity/checksum, model version/checksum, engine version and all operation parameters. Reused objects are read and checksum-verified. Successful branch outputs remain registered after another branch fails. Retry preserves prior manifests and reuses expensive validated upscale outputs.

Retention policy v1: original, final variants and lossless upscale cache artifacts are retained indefinitely to guarantee retry reuse. Temporary execution directories are cleaned on success/failure; no lossy intermediate file chains are created. A storage write followed by a failed database commit can leave an unreferenced immutable object. Automated object garbage collection and configurable durable-cache TTL are not implemented; operators must reconcile unreferenced keys before any deletion. Do not delete referenced intermediates because their lineage is part of the audit record.
