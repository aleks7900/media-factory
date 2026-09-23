# Provider routing

`ImageProviderRoutingStrategy` is replaceable. TASK-02 provides `DefaultImageProviderRoutingStrategy`; `ImageProviderRouter` discovers adapters by stable internal ID and validates availability, model allowlists and capabilities.

1. An explicit provider selects EXPLICIT mode; otherwise DEFAULT uses `MEDIA_FACTORY_IMAGE_PROVIDER`.
2. The primary model is explicitly requested or the provider's configured default.
3. If fallback is enabled, append configured fallback providers in order, removing duplicates. Each fallback uses its own configured model, not another provider's model name.
4. In production, exclude mock from fallback unless `MOCK_PRODUCTION_FALLBACK_ENABLED=true`.
5. Validate the entire route against requested features. An incompatible/disabled candidate causes a clear submission error; nothing is silently dropped or resized.
6. Persist the resolved route and request options with the generation and job in the existing submission transaction.

```text
IMAGE_FALLBACK_ENABLED=true
IMAGE_FALLBACK_PROVIDERS=mock
MOCK_PRODUCTION_FALLBACK_ENABLED=false
```

The above allows deliberate real→mock fallback in development. In production the explicit additional mock permission is required. Default fallback is disabled and its list is empty. The development default is still mock; explicitly selecting mock is allowed even in production, whereas automatic fallback is separately guarded.

The worker advances the persisted route index after an eligible failure or open circuit. Transient failures exhaust the current provider's configured attempts first. Authentication/configuration errors may fall back without retrying that provider. Invalid requests, unsupported parameters, content-policy errors and unexpected/malformed responses do not trigger fallback. Unknown paid-provider outcomes also block automatic fallback unless ambiguous retry is explicitly opted into.

Retry and fallback retain the same Generation and append GenerationAttempt records. Regenerate remains a new Generation linked to the original. Idempotent submission compares all request options as well as the TASK-01 prompt/concept/dimensions/parent fields; changed payloads using an existing key return 409. Existing generations are unaffected by later default-provider changes because routes are snapshotted. Execution rechecks provider enablement/capability policy.

Future cheapest/quality/weighted strategies can implement the strategy interface. None is included speculatively.
