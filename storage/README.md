# Media storage

Local development writes ignored files under `storage/data/originals/<generation>/<asset>.png`. Docker Compose stores private objects in the versioned MinIO bucket `media-factory` using the same key layout. No original is updated or deleted by application APIs.

Every original and variant records SHA-256. Local writes use CREATE_NEW; S3 writes use If-None-Match so key collisions cannot overwrite existing content. Back up PostgreSQL and object storage together. Unreferenced objects can exist after interrupted result commits; inspect and reconcile them before introducing any cleanup policy.
