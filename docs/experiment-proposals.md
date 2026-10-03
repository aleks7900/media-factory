# Registered experiment proposals

There is one experiment architecture: TASK-03 `prompt_experiments` / `prompt_experiment_variants`. TASK-11 adds source_hypothesis_id and a progress stage, plus one immutable `feedback_experiment_plans` row per experiment. It is a registered protocol, not a second generation/experiment service. Finding → analysis → frozen assets/metrics remains traversable.

POST `/feedback/hypotheses/{id}/experiment` after hypothesis approval:

```json
{
 "type":"PROMPT_VARIABLE",
 "controlVersionId":"<published-version>",
 "conceptId":"<concept-in-evidence-scope>",
 "variable":"background",
 "treatmentValue":"black",
 "targetSample":50,
 "provider":"mock",
 "model":"studio-mock-v1",
 "width":1024,"height":1024,
 "currency":"USD","maxBudget":0,"mode":"ONLINE"
}
```

The service copies the control into a new validated/published treatment version, changing one variable default. Control and treatment must resolve without later variable/preset overrides. PROMPT_VERSION can instead reference two published versions of the same template; its broader changes must be reviewed as a version comparison. Initial online execution intentionally supports these safely controllable types. Provider/model/preset/processing-profile/threshold interventions are future adapters; submitting those unsupported experiment types is rejected rather than silently ignoring the requested change.

The plan freezes primary metric, secondary metrics, observation days, minimum/target samples, concept, dimensions, provider/model, versions, eligibility and cost assumptions. Sample target is per variant, 2× total, maximum 1,000 each. Generation estimate comes from configured provider pricing. QA/local processing amounts are explicitly unknown unless priced; they are not fabricated as zero. The maximum budget must cover the known generation estimate. Changing a registered plan requires a replacement proposal; results cannot redefine its primary metric.

Approve via `/experiments/{id}/approve`, then `/start`, with `{reason,user}`. The database also blocks starting through the older TASK-03 API without approval. Feedback experiments are excluded from implicit TASK-03 enrollment; new requests must identify the experiment explicitly. Enrollments enforce plan concept/dimensions/provider/model and empty overrides. Regeneration cannot silently add repeated assets to this trial. The existing SHA256_BASIS_POINTS_V1 assignment is reused with stable idempotency keys; proposed generation batches fill A/B targets without changing assignment afterward.

Queue EXPERIMENT_GENERATION with experimentId to enqueue up to 25 ordinary FactoryService generations; repeat batches until targets are reached. Generation → QA → similarity → processing → publication uses the existing services and review gates. Feedback does not manufacture a special approved publication path. Variant attribution remains on immutable generation snapshots and TASK-10 lineage.

Before each image provider call, `FeedbackBudgetGuard` locks the plan and accounts for existing actual/estimated charges plus concurrent reservations. Unknown prices/currencies, a reached budget or an unreconciled interrupted reservation pause the experiment before another call. Mock zero-cost plans may run at a zero budget. Reservations are released after the attempt outcome is recorded; a process crash conservatively leaves a reservation requiring reconciliation. Provider bills can exceed estimates: a monetary budget is an admission limit based on known quotes, not a guarantee about an external provider's final invoice. Downstream operations remain subject to their existing cost/approval policies.

An operational safety stop pauses generation when failures/rejections exceed the configured ratio after the minimum resolved count. It is not an early winner rule. Manual pause/cancel is audited; paused jobs retain their durable queue state. Offline plans never start generation. They compare historical assets from registered prompt versions without reassigning those assets, and results explicitly state that this is observational offline evaluation.

Progress stages map onto existing statuses: READY_FOR_REVIEW and APPROVED retain DRAFT; RUNNING/OBSERVING/READY_FOR_ANALYSIS retain RUNNING; PAUSED, COMPLETED and CANCELLED use TASK-03 states. Observing starts when result analysis is requested; completion requires measured sample targets and terminal production work, not just enqueueing images.

### Current execution boundary

Online proposals support ordinary IMAGE generation only. Wallpaper, stock and video asset-type scopes are rejected for ONLINE mode; they remain available for historical and OFFLINE analysis. Specialized production-profile enrollment is not implemented. This prevents creating image assets that could never enter a registered specialized cohort. The existing downstream processing and publication APIs must be invoked through their ordinary workflows; the Feedback worker does not automatically approve or publish assets.

For online results, the registered experiment/variant IDs define membership. Historical prompt-version and similarity-cluster filters are removed from the result snapshot: retaining the control-only prompt filter would exclude the treatment, and selecting a post-generation cluster would introduce selection bias. The original evidence scope remains frozen in the protocol. Registered provider/model must match any corresponding evidence filters, and a different runtime provider/model pauses the experiment.

Frozen plans are not edited in place. Replacement currently requires another reviewed hypothesis and a new proposal; there is no dedicated draft-edit UI. Reconciliation of a crashed attempt reservation is an operator task after checking the durable attempt and cost records.
