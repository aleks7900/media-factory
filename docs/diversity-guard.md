# Diversity Guard and staged generation

Before enqueueing an image job, `FactoryService` resolves the immutable canonical prompt and runs `DiversityGuard`. The guard inspects the latest 50 collection generations, normalized frozen prompt equality, token Jaccard overlap, concept identity, and the latest active-model clustering result. Presets and template versions influence the resolved prompt and remain in the frozen generation snapshots.

Results are CLEAR, WARNING, or HIGH_REPETITION_RISK. Three or more identical normalized prompts occupying at least 80% of the recent window trigger high risk, except expected regeneration lineage. Related wording (Jaccard >= .80) or a saturated visual family yields a warning. Only BLOCK-mode profiles block the strongest repetition case; semantic relatedness alone never blocks generation. Recommendations explain the actual repeat count or largest-family share and suggest subject/viewpoint/composition/lighting/environment changes. Prompts are never silently rewritten.

`POST /api/v1/diversity/preflight` accepts `{conceptId,prompt,semantic}`. `semantic:true` additionally stores an immutable prompt-checksum/model text embedding and returns related image IDs from the shared CLIP space. This optional local inference happens outside generation transactions. Normal generation uses deterministic prompt evidence and the latest completed collection analysis, avoiding a synchronous model dependency before enqueueing. CLIP truncation at 77 tokens means long prompts require care when interpreting semantic preflight.

## Staged batches

`POST /api/v1/generation-batches`, with an `Idempotency-Key`, accepts:

```json
{
  "conceptId": "existing-concept-uuid",
  "total": 100,
  "batchSize": 10,
  "width": 1024,
  "height": 1024,
  "options": null,
  "prompt": {"prompt": "A quiet mountain lake at dawn"}
}
```

The dispatcher enqueues only the next chunk, waits for its image/embedding work, runs collection clustering, and compares largest-family share with the collection profile threshold before dispatching another chunk. A crossing pauses the remaining work as `PAUSED_DIVERSITY`. Generation/embedding failure also pauses; unresolved work is never treated as diverse. Row revisions and idempotent generation keys prevent concurrent dispatchers creating extra jobs.

`POST /api/v1/generation-batches/{id}/actions` accepts `{revision,action,reason,prompt}`. Actions: CONTINUE authorizes one additional chunk, STOP leaves remaining generations undispatched, CHANGE_PROMPT replaces the remaining request (including presets/template inputs) with a supplied `PromptRenderRequest` and authorizes a chunk. Previous requests and human actions are immutable audit records. Existing generated media and frozen prompts are preserved.

Collection Diversity shows paused batches and supports continue, stop, or a revised ad hoc prompt. Full structured template/preset changes are available through the API. The test fixture uses 100 requested generations, chunks of ten, and a threshold crossed by the fourth chunk: exactly 40 image jobs exist and the remaining 60 are not dispatched. There is no paid provider in that test.

This guard protects budget by stopping repeated frozen prompts or a collapsing visual distribution before more image jobs are sent. It does not guarantee semantic novelty, stock acceptance, or portfolio quality. Initial thresholds need calibration, stale cluster runs need re-analysis, and prompt token overlap is deliberately a conservative lexical signal.
