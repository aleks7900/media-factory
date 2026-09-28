# Video providers

The async `provider.video.VideoGenerationProvider` port exposes capabilities, estimate, preflight, submit, status, result and cancellation. Domain services receive provider-neutral records. The original foundation's synchronous port remains compatible with earlier callers.

`mock-video` produces a real deterministic MP4 from the source image using FFmpeg. It simulates an asynchronous submitted job and returns completion on polling. It is free and intended for orchestration tests; its fixed zoom motion is not a semantic interpretation of arbitrary instructions.

The Runway adapter uses the [official API](https://docs.dev.runwayml.com/guides/using-the-api/) and [official image-to-video request schema](https://github.com/runwayml/sdk-python/blob/main/src/runwayml/types/image_to_video_create_params.py): `POST /v1/image_to_video`, `GET/DELETE /v1/tasks/{id}`, Bearer authentication and `X-Runway-Version: 2024-11-06`. Supported conservative combinations: `gen4_turbo`/`gen4.5`, 5/10 seconds, 720:1280, 1280:720, 960:960. Native negative prompts and requested generation FPS are rejected. Camera/intensity instructions are rendered as prompt text, not invented native API parameters. Optional seeds are bounded to the documented unsigned range. Source data URIs are capped below the documented payload limit.

## Configuration

| Variable | Default / meaning |
|---|---|
| `VIDEO_DEFAULT_PROVIDER` | `mock-video` |
| `VIDEO_FALLBACK_PROVIDERS` | empty; only used with explicit request fallback permission |
| `VIDEO_MAX_REMOTE_JOBS` | 3 active remote jobs per provider, including unresolved uncertain jobs |
| `VIDEO_REQUESTS_PER_MINUTE` | 60 shared database rate limit |
| `VIDEO_MAX_HTTP_CALLS` | 3 short provider HTTP calls |
| `VIDEO_POLL_SECONDS` | 10; minimum 2 |
| `RUNWAY_VIDEO_ENABLED` | false |
| `RUNWAY_VIDEO_API_KEY` | empty; never returned in API responses |
| `RUNWAY_VIDEO_MODEL` | gen4_turbo |
| `RUNWAY_VIDEO_USD_PER_SECOND` | unknown until operator supplies a verified estimate |
| `RUNWAY_VIDEO_DOWNLOAD_HOSTS` | empty; exact approved HTTPS result hosts required before enabling |

Do not enable an adapter without verifying current provider pricing, access, supported model parameters and actual output CDN hosts. Unknown pricing blocks a paid submission. Estimates are not invoices; Runway's status contract does not return a billed amount here. Costs retain conservative estimates unless a provider reports actual cost. Failed and fallback attempts remain separate cost rows.

Safe rate-limit failures retry the same request with bounded backoff. Ambiguous transport/5xx submission and lost successful responses become `SUBMISSION_UNKNOWN`; no new paid submission or fallback occurs automatically. Supply a known provider task ID to reconcile. Polling failures keep the existing remote ID. At the 15-minute deadline the attempt requires reconciliation, rather than generating again. Downloads retry the same remote job up to three times, then permit explicit same-job reconciliation.

Remote output is immediately downloaded with timeout/size/content-type limits, exact HTTPS host allowlisting, private-address checks and redirects disabled. URLs are not persisted or exposed. Configure trusted CDN hostnames; DNS and outbound network policy remain deployment responsibilities.

Runway uses polling. Its advertised webhook capability is false. `VideoWebhookVerifier` is an extension point requiring adapter signature/timestamp verification; no verifier is registered by default, so webhook routes return 404. Verified duplicate events are deduplicated and only wake authoritative polling.

No live paid Runway generation is part of automated verification. Fake HTTP tests validate request mapping, statuses, authentication failure, rate limits, unavailable/ambiguous responses and download restrictions.
