# Cost attribution

`generation_costs` remains authoritative. `actual_cost` is used when present; otherwise `estimated_cost` is explicitly an estimate. Provider/model for charge comparisons comes from the operation itself, including failed fallback attempts. Downstream revenue follows the resulting generation's final provider/model. A provider group therefore describes attributed operations and resulting assets, not a claim that every downstream charge was billed by the image provider.

Default direct attribution follows cost → generation → asset, and concept → collection → project. A failed generation without an asset remains visible in project/collection totals and the UNATTRIBUTED asset bucket. Cost is not copied to every output, platform or publication.

`POST /cost-allocations` accepts:

```json
{
  "costId": "existing-operation-cost-uuid",
  "policy": "WEIGHTED",
  "weights": {"asset-uuid-a": 1, "asset-uuid-b": 2},
  "reason": "Shared operation allocation",
  "createdBy": "local-user"
}
```

DIRECT requires one target. EQUAL_SPLIT uses equal weights. WEIGHTED accepts nonnegative weights with a positive sum. Finalized operation charges only can be allocated. Allocation truncates interim parts to 12 decimal places and assigns the exact residual to the final positive-weight target. Allocations replace the source charge in the reporting projection. Retrying the same allocation is idempotent; conflicting reallocations are rejected. Deferred PostgreSQL validation enforces conservation of the original charge.

Platform, device-variant and publication costs remain unallocated when no defensible allocation exists. Their profit/ROI is unavailable rather than repeating full master cost in each destination. Global totals still include the charge once.

Rejected, duplicate-rejected and failed-generation cost columns report production operation spend associated with those current outcomes. Rejection-reason grouping uses the highest-severity detected finding from the current QA review, with deterministic code ordering for ties; all findings remain in the QA evidence. It does not assign the entire charge independently to every reason. Explicit stock/wallpaper duplicate rejection is recognized.

Local processing reports measured wall duration and available byte counts separately. Wall duration is not CPU utilization or GPU time. No dollar rate is fabricated for local FFmpeg, embedding, image processing or GPU activity. A configured and verified charge must enter the authoritative operation-cost system before it appears as money.
