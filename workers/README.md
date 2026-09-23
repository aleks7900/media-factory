# Worker runtime

The initial worker runs in the backend JVM (`GenerationWorker`) every 1.5 seconds. This avoids an external broker while retaining transactional enqueue and multi-instance-safe claiming. Set `WORKER_ENABLED=false` to disable it for API-only deployments. Separate worker deployment can reuse the backend artifact after adding an explicit non-web application profile.

PostgreSQL owns durability. Queue polling uses row locks with `SKIP LOCKED`; a five-minute lease fences stale completions. This version processes one job per instance at a time. Use job status/failure reason/attempts and application logs for diagnosis. Do not manually reset attempts or delete originals to retry a job.
