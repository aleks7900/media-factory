# Image providers

TASK-02 extends the existing `ImageGenerationProvider`, `ProviderTypes.Request`, `GenerationWorker`, `FactoryService`, and cost ledger. It does not add a second generation pipeline. The flow is Generation → routing strategy → shared resilience controls → provider adapter → immutable storage → Asset → technical QA.

## OpenAI

The adapter uses `POST /v1/images/generations` over Java's HTTP client. Default model: `gpt-image-2`. The initial allowlist also permits `gpt-image-1.5` and `gpt-image-1`; model selection is validated before enqueue. OpenAI is disabled by default and requires `OPENAI_IMAGE_ENABLED=true` plus `OPENAI_API_KEY` in the backend environment.

The integrated capability subset is one image, PNG/JPEG, quality AUTO/LOW/MEDIUM/HIGH, and 1024×1024, 1024×1536 or 1536×1024. PNG transparency is accepted through the API. Seeds, negative prompts, reference images/editing, WebP, multiple images, and other dimensions are rejected explicitly. Some upstream models support more features; they are deliberately not advertised by this adapter until storage/QA support is implemented. See the official [Images API contract](https://developers.openai.com/api/reference/resources/images/methods/generate).

`OpenAiImageClient` owns transport, bounded response buffering, credentials and deadlines. `OpenAiImageMapper` owns request/response translation. `OpenAiImageExceptionMapper` maps errors into provider-neutral categories, discarding raw error text. Responses are base64-decoded with a 25 MiB media cap and a 36 MiB encoded response cap. URL responses are not fetched, avoiding arbitrary URL downloads. No SDK types enter services.

The default endpoint requires HTTPS, rejects userinfo/query/fragment, and never follows redirects with credentials. A local HTTP override exists solely for loopback test fixtures. Each request includes `X-Client-Request-Id` for correlation; this is **not an idempotency guarantee**. The adapter does not assume provider-side deduplication or status lookup. Organization verification/model access may be required by the provider.

## Mock

`MEDIA_FACTORY_IMAGE_PROVIDER=mock` runs without API credentials. Existing deterministic images, video fixture, text, vision and upscale mocks remain available. `MOCK_IMAGE_SCENARIO` accepts `success`, `rate-limit`, `timeout`, or `provider-error`. The image adapter is replay-safe; simulated failures pass through exactly the same attempt/retry/cost pipeline.

## Configuration

All generic settings live under `media-factory.image-generation`: default provider, environment, worker concurrency, lease duration, routes, enabled state, model allowlist, connect/request timeouts, RPM, concurrent requests, retry timing and circuit cooldown. Spring external configuration may override any of these. Common settings are passed through Compose and listed in `.env.example`.

```text
MEDIA_FACTORY_IMAGE_PROVIDER=openai
OPENAI_IMAGE_ENABLED=true
OPENAI_API_KEY=<set locally; never commit>
OPENAI_IMAGE_MODEL=gpt-image-2
OPENAI_REQUESTS_PER_MINUTE=20
OPENAI_MAX_CONCURRENT=3
OPENAI_IMAGE_TIMEOUT=120s
```

Never place API keys in prompts, request metadata, frontend configuration, or database rows. REST provider information is constructed from an explicit allowlist of fields. Secret configuration has a redacted `toString`. Provider bodies, auth headers, raw exception causes and full prompts are not logged. Returned request IDs/revised prompts have configured API-key values redacted. Actuator exposes only health, info and Prometheus, not environment/configuration endpoints.

## API

`POST /api/v1/generations/images`, with `Idempotency-Key`, accepts:

```json
{"conceptId":"<uuid>","prompt":"A ceramic sculpture in soft studio light","provider":null,"model":null,"aspectRatio":"SQUARE","quality":"LOW","format":"PNG"}
```

It returns HTTP 202 with `generationId`, `jobId`, and `status`. `provider=null` selects Auto. Portrait uses 1024×1536, landscape 1536×1024. Explicit custom dimensions require both width/height and `aspectRatio=CUSTOM`. Unknown JSON fields are rejected. The legacy `/api/generations` endpoint continues accepting TASK-01 payloads and uses the configured default provider.

`GET /api/v1/generations/{id}` returns route, request settings, attempt history, final provider/model, timestamps, original assets, job/failure state, and currency-separated costs. `GET /api/v1/providers/image` returns enabled/health/model/capability data and UTC daily request, success, latency and cost statistics. No active paid generation is used as a health probe.

## Adding an adapter

Implement the existing interface with a stable ID, capabilities, neutral result/usage, configured-state check and replay-safety declaration. Return only approved safe metadata. Translate every transport failure into `ImageGenerationException`, including outcome ambiguity and Retry-After. Add the provider configuration/model allowlist/pricing, register the bean, and add adapter contract tests against HTTP fixtures. The registry discovers providers; no changes to domain services or worker routing are needed. External operations must honor configured timeouts and return before the lease expires.
