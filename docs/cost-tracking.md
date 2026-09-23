# Attempt cost accounting

Every actual provider invocation first inserts a `GenerationAttempt` and `GenerationCost` atomically. Local quota waits do not create attempted-call charges. Entries contain provider, model, `IMAGE_GENERATION`, quantity=1, input/output units, estimate/actual, currency, pricing status/version, attempt ID and timestamps. Retry and fallback create separate rows. The TASK-01 cost ledger is extended in place.

For real calls, the initial estimate and actual cost are null with `pricingStatus=UNKNOWN`: output token usage is not yet known. Failed attempts retain unknown cost rather than assuming zero. Successful responses record available token details even when pricing is unconfigured. Missing usage stays null. Mock costs are known zero; its input/output units retain the original mock semantics (prompt characters/generated item), not fabricated token counts.

`PricingService` reads model-specific rates from configuration. The configured `gpt-image-2` snapshot, version `2026-09-24`, is based on [OpenAI's pricing page](https://developers.openai.com/api/docs/pricing): per million text-input tokens 2.50 USD, image-input tokens 4.00 USD, and output tokens 15.00 USD.

```text
estimatedCost = (textInputTokens × textInputRate
              + imageInputTokens × imageInputRate
              + outputTokens × outputRate) / 1,000,000
```

Calculations use BigDecimal and eight decimal places. This is an estimate derived from reported usage, not an invoice or billing API. Actual cost remains null for OpenAI because this endpoint does not provide a verified monetary charge. Pricing for other allowed models is unconfigured and remains UNKNOWN until operators supply a sourced, versioned rate table. Missing usage also produces UNKNOWN. Do not substitute a per-image constant or infer token counts from prompt length.

Change pricing through `media-factory.image-generation.providers.<id>.pricing.[<model>]` (version, source, currency, text-input-per-million, image-input-per-million, output-per-million). Historical entries retain their version and computed amounts. Rates are the configured snapshot at execution time; persist new versions when updating them.

Generation details group totals by currency and expose `unknown_attempts`; a known subtotal is not a complete total when unknown attempts exist. Dashboard/provider summaries label their USD subtotal and unknown count. The cost UI renders null as Unknown. Pricing estimates do not reconcile account-specific discounts, credits, taxes, caching discounts or failed-request billing. Add billing reconciliation before treating estimates as invoice-grade actuals.
