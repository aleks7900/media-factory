# TASK-13 — Automation: Night Production & Morning Review Queue

## Objective

Build a production-ready **Automation & Autonomous Production Scheduler** for Media Factory.

The system must support unattended overnight production while preserving:

* budget limits;
* provider limits;
* QA;
* similarity/diversity protection;
* immutable lineage;
* human approval;
* crash recovery;
* observability;
* experiment attribution.

Primary workflow:

```text
DAYTIME
   ↓
Collections / Experiments / Production Plans
   ↓
Eligible Night Work
   ↓
════════════ NIGHT WINDOW ════════════
   ↓
Automation Scheduler
   ↓
Budget / Capacity / Eligibility Check
   ↓
Production Planning
   ↓
Generation Batch
   ↓
QA
   ↓
Similarity
   ↓
Processing
   ↓
Wallpaper / Stock / Video Preparation
   ↓
Repeat within limits
   ↓
════════════ MORNING ════════════════
   ↓
Build Review Queue
   ↓
Prioritize
   ↓
Human Review
   ↓
Approve / Reject / Regenerate / Publish
```

The goal is:

> The user can leave Media Factory in the evening and return in the morning to a controlled, already processed queue of useful assets requiring human decisions rather than a pile of raw generations.

---

# 1. Inspect TASK-01 → TASK-12 First

Before implementation inspect the actual repository.

Reuse existing:

```text
TASK-01
Jobs
Assets
Collections
Lifecycle

TASK-02
Providers
Rate limits
Retry
Fallback
Cost tracking

TASK-03
Prompt Engine
Experiments

TASK-04
QA
Human review

TASK-05
Similarity
Duplicate detection
Diversity Guard

TASK-06
Image Processing

TASK-07
Wallpaper Production

TASK-08
Stock Factory

TASK-09
Video Factory

TASK-10
Analytics

TASK-11
Feedback Engine
Experiment proposals

TASK-12
Codex Skills
```

Do not create duplicate scheduling/business logic where equivalent infrastructure already exists.

---

# 2. Core Principle

Automation must orchestrate existing production systems.

It must NOT create shortcuts around:

```text
QA

Similarity

Budget Guards

Human Approval

Publication Gates
```

Target:

```text
AUTOMATION
    │
    ▼
Production Plan
    │
    ▼
Existing Media Factory Pipelines
```

not:

```text
AUTOMATION
    ↓
Direct provider calls
```

---

# 3. Architecture

```text
                 AUTOMATION SCHEDULER
                         │
                         ▼
                  Nightly Run
                         │
              ┌──────────┴──────────┐
              ▼                     ▼
        Eligibility Engine      Budget Engine
              │                     │
              └──────────┬──────────┘
                         ▼
                 Production Planner
                         │
                         ▼
                  Batch Executor
                         │
          ┌──────────────┼───────────────┐
          ▼              ▼               ▼
     Wallpaper          Stock           Video
      Pipeline         Pipeline         Pipeline
          │              │               │
          └──────────────┼───────────────┘
                         ▼
                         QA
                         ↓
                     Similarity
                         ↓
                     Processing
                         ↓
                  Review Candidates
                         │
                         ▼
                 Morning Queue Builder
                         │
                         ▼
                   REVIEW QUEUE
```

---

# 4. Automation Domain

Create or extend:

```text
AutomationPlan

AutomationSchedule

AutomationRun

AutomationRunItem

AutomationBudget

AutomationPolicy

MorningReviewQueue

MorningReviewQueueItem
```

Reuse existing entities when appropriate.

---

# 5. AutomationPlan

Represents WHAT may be produced automatically.

Suggested fields:

```text
id

name

description

projectId

enabled

productionType

scope

scheduleId

policyId

budgetId

priority

createdAt
updatedAt
```

Production types:

```text
WALLPAPER

STOCK

VIDEO

EXPERIMENT

MIXED
```

---

# 6. AutomationSchedule

Represents WHEN automation runs.

Support:

```text
timezone

startTime

endTime

daysOfWeek

enabled
```

Example:

```text
start:
23:00

end:
07:00

timezone:
Europe/Chisinau
```

Do not hardcode timezone.

---

# 7. Night Window

Automation must understand an execution window.

Example:

```text
23:00
↓
07:00
```

Do not start expensive new batches near the end if they cannot reasonably finish according to configured policy.

---

# 8. Scheduling Technology

Inspect current Spring Boot infrastructure.

Prefer an appropriate durable scheduling approach.

Possible options:

```text
Quartz

existing durable job scheduler

database-backed scheduler
```

Do not rely only on:

```java
@Scheduled
```

if that would make production runs fragile across restarts/multiple backend instances.

---

# 9. Distributed Safety

If multiple backend instances run:

```text
only one scheduler
```

must claim a particular automation run.

Use:

```text
database lock

scheduler clustering

lease
```

or equivalent existing mechanism.

---

# 10. Run Identity

Every execution receives:

```text
automationRunId
```

and immutable:

```text
schedule snapshot

policy snapshot

budget snapshot

production-plan snapshot
```

so historical runs remain reproducible.

---

# 11. AutomationRun

Suggested:

```text
id

automationPlanId

scheduledFor

startedAt
completedAt

status

windowStart
windowEnd

policySnapshot

budgetSnapshot

plannedItemCount

completedItemCount

failedItemCount

actualCost
```

---

# 12. Run Status

Use:

```text
SCHEDULED

PLANNING

RUNNING

PAUSED

BUDGET_EXHAUSTED

CAPACITY_EXHAUSTED

WINDOW_ENDED

PARTIALLY_COMPLETED

COMPLETED

FAILED

CANCELLED
```

---

# 13. AutomationRunItem

Each meaningful production unit should be traceable.

Suggested:

```text
id

automationRunId

projectId

collectionId

conceptId

experimentId

variantId

generationId

assetId

status

priority

estimatedCost

actualCost

createdAt
completedAt
```

---

# 14. Eligibility Engine

Create:

```text
AutomationEligibilityService
```

Before night production determine which work is eligible.

Possible inputs:

```text
approved production plans

approved collections

approved experiments

collection targets

unfinished batches

backlogs

manual overnight requests
```

---

# 15. Explicit Automation Opt-In

A collection must NOT become eligible simply because it exists.

Require explicit state/config such as:

```text
automationEnabled = true
```

or an approved AutomationPlan.

---

# 16. Eligible Collection Example

```text
Collection:
Cyber Wolves AMOLED

automationEnabled:
true

targetAssetCount:
100

currentApprovedAssets:
63

remainingTarget:
37
```

Automation may consider producing toward the remaining target.

---

# 17. Production Target

Support targets such as:

```text
TARGET_APPROVED_ASSETS

TARGET_GENERATIONS

TARGET_STOCK_READY_ASSETS

TARGET_WALLPAPER_READY_ASSETS

TARGET_VIDEO_READY_ASSETS
```

Prefer outcome-based targets where useful.

---

# 18. Avoid Infinite Generation

If target is:

```text
100 approved assets
```

and QA approval is poor, automation must NOT generate infinitely trying to reach 100.

Require:

```text
maxGenerationsPerRun

maxAttemptsPerTarget

maxCostPerRun
```

---

# 19. AutomationPolicy

Create versioned:

```text
AutomationPolicy
```

Possible fields:

```text
maxGenerationsPerRun

maxGenerationBatches

batchSize

maxRunCost

maxCollectionCost

maxProviderCost

minimumRemainingWindow

stopOnHighFailureRate

stopOnHighQaRejectionRate

stopOnDiversityCollapse

allowProviderFallback

allowCpuFallback

requireHumanPublication
```

---

# 20. Policy Versioning

Persist exact policy snapshot with every AutomationRun.

Changing policy tomorrow must not change interpretation of yesterday's run.

---

# 21. Night Production Planner

Create:

```text
NightProductionPlanner
```

Responsibilities:

```text
find eligible work

calculate remaining targets

inspect provider availability

inspect budget

inspect TASK-11 findings

inspect TASK-05 saturation

prioritize work

build bounded execution plan
```

---

# 22. Planner Output

Create:

```text
NightProductionPlan
```

Example:

```text
Run:
AUTO-2026-09-24-001

Window:
23:00–07:00

Budget:
$25

Planned:

Cyber Wolves
20 generations

Space AMOLED
15 generations

Stock Remote Work
10 generations

Experiment EXP-17
20 generations
```

---

# 23. Priority

Do not hide prioritization inside an LLM.

Support explicit dimensions:

```text
manual priority

target deficit

experiment priority

collection saturation

recent production

estimated cost

pipeline readiness
```

---

# 24. Priority Classes

Possible:

```text
CRITICAL

HIGH

NORMAL

LOW
```

Use cases:

```text
approved experiment
→ HIGH

collection almost complete
→ NORMAL

highly saturated collection
→ LOW
```

Do not hardcode business assumptions where project configuration should decide them.

---

# 25. Manual Overnight Queue

Allow the user to explicitly queue work:

```text
Produce 50 Cyber Wolves tonight.
```

Persist as:

```text
OvernightProductionRequest
```

or equivalent AutomationPlan item.

---

# 26. Manual Request Priority

Explicit user overnight requests should be distinguishable from automatically discovered work.

Example:

```text
source:
MANUAL
```

vs:

```text
source:
AUTOMATION_POLICY
```

---

# 27. TASK-11 Experiment Integration

Approved experiments may become night-work candidates.

Only experiments in an executable state may run.

Automation must preserve:

```text
experimentId

variantId

assignment
```

---

# 28. Experiment Balance

Automation must not accidentally generate:

```text
A = 50
B = 5
```

because one variant happened to be scheduled first.

Use TASK-03 experiment allocation rules.

---

# 29. TASK-11 Feedback

Planner may inspect:

```text
active learnings

saturation warnings

exploration opportunities
```

But feedback findings alone must not authorize paid generation.

There must already be:

```text
approved experiment
```

or:

```text
approved automation plan
```

---

# 30. Production Batches

Do not schedule a giant batch:

```text
500 generations
```

as one opaque operation.

Use:

```text
Batch 1
↓
Evaluate
↓
Batch 2
↓
Evaluate
↓
...
```

---

# 31. Default Batch Strategy

Make configurable:

```yaml
automation:
  production:
    batch-size: 10
```

Do not assume 10 is universally optimal.

---

# 32. Between-Batch Evaluation

After each batch inspect:

```text
generation failure rate

QA rejection rate

near-duplicate rate

diversity

cost

provider health

remaining window
```

---

# 33. Adaptive Stopping

Automation may STOP or PAUSE based on deterministic safety policies.

Example:

```text
near duplicate rate > configured threshold
↓
PAUSE collection
```

This is different from autonomous creative optimization.

---

# 34. Diversity Collapse

Integrate TASK-05.

Example:

```text
Batch 1:
near duplicates = 10%

Batch 2:
near duplicates = 45%

Batch 3:
do not blindly continue
```

Transition:

```text
PAUSED_DIVERSITY
```

---

# 35. QA Collapse

If:

```text
QA rejection rate
```

crosses configured safety threshold:

```text
PAUSED_QA
```

for that work item/collection.

Do not necessarily stop unrelated collections.

---

# 36. Provider Failure

If provider becomes:

```text
DEGRADED
```

or:

```text
UNAVAILABLE
```

use TASK-02 routing/fallback policy.

Do not implement new provider retry rules in automation.

---

# 37. Provider Fallback

Automation may allow:

```text
allowProviderFallback = true
```

but TASK-02 remains authoritative.

Preserve provider/model lineage.

---

# 38. Rate Limits

Night scheduler must respect TASK-02 rate limits.

Do not increase concurrency simply because the system is unattended.

---

# 39. Concurrency Budget

Create configurable global limits:

```text
maxConcurrentGenerations

maxConcurrentQa

maxConcurrentProcessing

maxConcurrentVideoJobs
```

Reuse existing worker capacity controls where available.

---

# 40. GPU Capacity

TASK-06/TASK-09 local GPU work may share one GPU.

Automation must not simultaneously launch:

```text
many upscale jobs
+
video jobs
+
embedding jobs
```

until VRAM is exhausted.

---

# 41. Resource Scheduler

Create/use:

```text
ResourceCapacityService
```

Capabilities may include:

```text
GPU

CPU

MEMORY

PROVIDER_CONCURRENCY
```

---

# 42. GPU Work Classes

Examples:

```text
UPSCALE

VIDEO_PROCESSING

EMBEDDING

LOCAL_VISION
```

Support concurrency policies.

---

# 43. GPU OOM

If worker returns:

```text
GPU_OUT_OF_MEMORY
```

follow TASK-06 worker policy:

```text
smaller tile

reduced concurrency

configured CPU fallback
```

Do not silently change image model/quality.

---

# 44. Cost Budget

Automation requires strict budget enforcement.

Support:

```text
dailyBudget

nightlyBudget

planBudget

collectionBudget

experimentBudget
```

---

# 45. Budget Reservation

Before starting a paid generation, reserve estimated cost when feasible.

Pattern:

```text
Remaining budget
↓
Reserve estimated operation cost
↓
Execute
↓
Record actual cost
↓
Release/adjust reservation
```

This reduces concurrent budget overshoot.

---

# 46. Budget Authority

TASK-10/TASK-02 cost infrastructure remains authoritative for actual costs.

Automation should not create independent contradictory accounting.

---

# 47. Unknown Cost

If provider cost cannot be estimated reliably:

```text
UNKNOWN
```

must be represented.

Policy may decide whether automation can execute unknown-cost operations.

Default:

```text
do not allow unknown-cost paid automation
```

unless explicitly enabled.

---

# 48. Budget Exhaustion

When budget is reached:

```text
BUDGET_EXHAUSTED
```

Stop launching new paid operations.

Allow already-running safe operations to complete according to policy.

---

# 49. Pipeline

For image production:

```text
Generation
↓
Asset
↓
Technical QA
↓
Visual QA
↓
Similarity
↓
Processing
↓
Target Pipeline
```

Do not postpone all QA until morning.

---

# 50. Why QA Runs at Night

The morning queue should preferably contain:

```text
reviewable processed candidates
```

not:

```text
500 unvalidated raw generations
```

---

# 51. Wallpaper Night Pipeline

```text
Concept
↓
Generation
↓
QA
↓
Similarity
↓
Master Processing
↓
Wallpaper Variants
↓
Preview
↓
Morning Review Candidate
```

---

# 52. Stock Night Pipeline

```text
Concept
↓
Generation
↓
QA
↓
Similarity
↓
Stock Processing
↓
4MP+ Validation
↓
Metadata
↓
Morning Review Candidate
```

Do not externally submit stock automatically unless separately authorized.

---

# 53. Video Night Pipeline

```text
Source Asset
↓
Image-to-Video
↓
Video QA
↓
FFmpeg Processing
↓
Loop Validation
↓
Preview
↓
Morning Review Candidate
```

---

# 54. Failed Items

Failures must remain visible.

Do not hide failed generations simply because they do not belong in the main visual review queue.

Morning report should include:

```text
production failures
```

separately.

---

# 55. Morning Queue Builder

Create:

```text
MorningReviewQueueService
```

At the end of the night window or configured morning time:

```text
AutomationRun
↓
Collect Reviewable Items
↓
Group
↓
Prioritize
↓
Deduplicate
↓
Build Morning Queue
```

---

# 56. MorningReviewQueue

Suggested:

```text
id

date

projectId

automationRunIds

status

createdAt

totalItems

pendingItems

completedItems
```

---

# 57. Queue Status

Use:

```text
OPEN

IN_REVIEW

COMPLETED

ARCHIVED
```

---

# 58. Review Queue Item

Suggested:

```text
id

queueId

assetId

variantId

automationRunId

collectionId

conceptId

experimentId

priority

reviewReason

automaticDecision

qaReviewId

similarityStatus

createdAt

reviewedAt
```

---

# 59. Review Reasons

Support explicit reasons:

```text
NEW_ASSET

QA_NEEDS_REVIEW

SIMILARITY_REVIEW

EXPERIMENT_CANDIDATE

STOCK_READY

WALLPAPER_READY

VIDEO_READY

AUTOMATION_WARNING
```

An asset may have multiple reasons.

---

# 60. Do Not Add Everything

Not every night-generated item must require human review.

Example:

```text
technical failure
```

can go to:

```text
Failures
```

instead of primary review queue.

---

# 61. Review Priority

Build deterministic priority from separate factors:

```text
manual priority

QA uncertainty

similarity uncertainty

experiment importance

collection priority

publication readiness
```

Expose why an item has its priority.

---

# 62. No Opaque AI Priority

Do not simply ask LLM:

```text
rank these 500 images
```

and trust the ordering.

If Vision/LLM signals are used, expose them separately.

---

# 63. Review Queue Sections

Frontend should support grouped review:

```text
Needs Attention

Ready for Approval

Experiments

Wallpaper Ready

Stock Ready

Video Ready

Similarity Review

Failures
```

---

# 64. Morning Dashboard

Create:

```text
Morning Review
```

dashboard.

Top summary:

```text
Night production:
84 generated

QA:
61 approved
12 needs review
11 rejected

Similarity:
7 near duplicates

Processing:
54 completed

Ready for review:
47

Cost:
$...

Run duration:
...
```

Use actual values.

---

# 65. Night Summary

Show per AutomationRun:

```text
planned

attempted

generated

failed

QA approved

QA rejected

near duplicates

processed

review candidates

actual cost
```

---

# 66. Collection Breakdown

Example:

```text
Cyber Wolves
20 generated
14 ready
3 rejected
3 near duplicates

Space AMOLED
15 generated
13 ready
2 rejected
```

---

# 67. Cost Breakdown

Show:

```text
generation

visual QA

processing

video

other AI
```

Use TASK-10 cost data.

---

# 68. Review Workspace Integration

Reuse TASK-04 Review Workspace.

Do not create a second unrelated image reviewer.

Morning queue should feed the existing review UI.

---

# 69. Review Actions

Support existing:

```text
APPROVE

REJECT

REGENERATE

MARK DISTINCT

CONFIRM DUPLICATE
```

plus target-specific actions when available.

---

# 70. Batch Review

Support:

```text
approve selected

reject selected

assign collection

mark reviewed
```

But high-risk operations should preserve existing confirmation rules.

---

# 71. Keyboard Workflow

Reuse/add:

```text
A
→ approve

R
→ reject

G
→ regenerate

← →
→ navigate
```

where existing Review Workspace already supports these.

---

# 72. Side-by-Side Similarity

For similarity-review items show:

```text
new asset
vs
nearest existing asset
```

plus:

```text
pHash distance

embedding similarity

cluster
```

---

# 73. Experiment Review

For experiment assets show:

```text
experiment

variant

prompt diff

automatic QA

generation cost
```

Do not expose downstream experiment result before observation completes.

---

# 74. Wallpaper Review

Show:

```text
master

phone preview

device variants

crop

safe zones

AMOLED metrics
```

---

# 75. Stock Review

Show:

```text
stock variant

resolution

megapixels

title

description

keywords

stock validation
```

---

# 76. Video Review

Show:

```text
preview

duration

loop

motion plan

QA findings
```

---

# 77. Morning Digest

Create a structured morning digest.

Example:

```text
Night run completed.

Generated:
84

Ready for review:
47

Needs manual QA:
12

Near duplicates:
7

Rejected:
11

Failed:
3

Total cost:
$...

Main issues:
- malformed anatomy: 4
- prompt mismatch: 3
- similarity: 7
```

---

# 78. Digest Persistence

Store digest with the AutomationRun or MorningReviewQueue.

Do not regenerate historical summaries from changing data without preserving original snapshot.

---

# 79. Notifications

Architecture should support notification hooks:

```text
IN_APP

EMAIL

WEBHOOK
```

Do not require all channels for TASK-13.

Implement at least internal/in-app notification if architecture supports it.

---

# 80. Morning Notification

Example:

```text
Your overnight Media Factory run is complete.

47 assets are ready for review.
```

Link/reference should open Morning Review.

---

# 81. Failure Notification

Critical automation failure should create a visible notification.

Examples:

```text
all providers unavailable

database/storage failure

budget configuration invalid

scheduler failure
```

---

# 82. Do Not Wake User for Normal Rejections

Normal:

```text
QA rejection

near duplicate

single generation failure
```

belongs in morning summary unless configured otherwise.

---

# 83. Quiet Hours

Automation must not confuse:

```text
production window
```

with:

```text
notification window
```

Notifications can be deferred until morning.

---

# 84. Pause Automation

Support:

```text
PAUSE ALL AUTOMATION
```

and per:

```text
plan

project

collection
```

---

# 85. Emergency Stop

Create an operational control:

```text
STOP NEW WORK
```

It must:

```text
prevent new jobs
```

while handling currently running jobs safely.

---

# 86. Cancel

Cancellation semantics must be explicit.

Do not assume paid provider calls can always be cancelled after submission.

States may include:

```text
CANCELLATION_REQUESTED

CANCELLED

TOO_LATE_TO_CANCEL
```

where applicable.

---

# 87. Restart Recovery

If backend crashes at 03:17:

```text
restart
↓
find RUNNING AutomationRun
↓
inspect child jobs
↓
reconcile
↓
continue safely
```

Do not start a second night run.

---

# 88. Stale Work Recovery

Detect:

```text
RUNNING
```

items without heartbeat/progress for too long.

Use existing TASK-01/TASK-02 recovery mechanisms.

---

# 89. Paid Request Recovery

Never blindly repeat an uncertain paid provider request after crash.

If external request status is unknown:

```text
UNKNOWN_EXTERNAL_STATE
```

and reconcile where provider supports it.

---

# 90. Idempotency

Night runs must be idempotent.

Identity may include:

```text
automationPlanId
+
scheduled occurrence
```

so scheduler restart does not create duplicate runs.

---

# 91. Duplicate Schedule Fire

If same schedule fires twice:

```text
second invocation
→ existing AutomationRun
```

not another run.

---

# 92. Missed Schedule

Define policy:

```text
SKIP

RUN_IMMEDIATELY

RUN_NEXT_WINDOW
```

Default should be configurable.

---

# 93. Daylight Saving Time

Scheduling must use timezone-aware APIs.

Do not manually calculate UTC offsets.

Persist timezone ID.

---

# 94. Manual Run

Support:

```text
Run tonight's automation now
```

through:

```text
POST /automation/plans/{id}/run
```

or actual project conventions.

Manual run must still enforce budgets/policies.

---

# 95. Dry Run

Support:

```text
DRY RUN
```

Output:

```text
eligible collections

planned batches

estimated generations

estimated cost

provider requirements

GPU requirements

warnings
```

No paid generation.

---

# 96. Planner Preview

Frontend:

```text
Automation
→ Tonight
```

should show planned work before night starts.

Example:

```text
Tonight

Cyber Wolves       20
Space AMOLED        15
Stock Remote Work   10
Experiment EXP-17   20

Estimated cost:
$18.40

Max budget:
$25
```

---

# 97. User Override

Allow user before run to:

```text
disable item

change priority

change max count

change budget

pause collection
```

Preserve audit history.

---

# 98. Automation Templates

Support reusable templates:

```text
NIGHT_WALLPAPER_PRODUCTION

NIGHT_STOCK_PRODUCTION

NIGHT_VIDEO_PRODUCTION

NIGHT_EXPERIMENTS

MIXED_NIGHT_FACTORY
```

Templates create configuration; they do not hardcode business logic.

---

# 99. Wallpaper Automation Example

```text
Plan:
AMOLED Night Factory

Window:
23:00–07:00

Budget:
$20

Collections:
Cyber Wolves
Space Black
Neon Cars

Batch size:
10

Target:
approved wallpaper-ready assets
```

---

# 100. Stock Automation Example

```text
Plan:
Stock Night Factory

Sources:
approved stock concepts

Target:
stock-ready assets

Steps:
generation
QA
similarity
processing
metadata

External submission:
DISABLED
```

---

# 101. Video Automation Example

Because video is more expensive, allow stricter:

```text
maxVideoGenerations

maxVideoCost

maxConcurrentVideoJobs
```

---

# 102. Experiment Automation Example

```text
Plan:
Approved Experiments

Only:
APPROVED experiments

Max cost:
$10/night
```

Respect experiment-specific budgets first.

---

# 103. Automation and Codex Skills

Integrate TASK-12.

Possible future/user commands:

```text
Schedule this collection for tonight.

Add Cyber Wolves to tonight's production.

Run QA automatically overnight.

Prepare stock assets tonight.

Create wallpapers overnight.
```

Codex Skills should call Automation APIs rather than creating ad-hoc cron jobs.

---

# 104. New Codex Skill

Add:

```text
skills/schedule-production/
```

if appropriate to existing TASK-12 conventions.

Purpose:

> Create, inspect or modify Media Factory automation plans using the production Automation subsystem.

---

# 105. schedule-production Examples

```text
Produce 30 AMOLED wallpapers tonight.

Run stock preparation every night.

Add collection X to tonight's queue.

Pause wallpaper automation.

Show me what is scheduled tonight.
```

---

# 106. Skill Safety

`schedule-production` must clearly surface:

```text
schedule

target

estimated cost

maximum budget

publication behavior
```

before enabling large recurring paid automation.

---

# 107. REST API

Follow existing conventions.

Potential endpoints:

```text
GET    /api/v1/automation/plans

POST   /api/v1/automation/plans

GET    /api/v1/automation/plans/{id}

PATCH  /api/v1/automation/plans/{id}

POST   /api/v1/automation/plans/{id}/enable

POST   /api/v1/automation/plans/{id}/disable

POST   /api/v1/automation/plans/{id}/dry-run

POST   /api/v1/automation/plans/{id}/run

GET    /api/v1/automation/runs

GET    /api/v1/automation/runs/{id}

POST   /api/v1/automation/runs/{id}/pause

POST   /api/v1/automation/runs/{id}/cancel

GET    /api/v1/review-queues/morning

GET    /api/v1/review-queues/{id}
```

Adapt to actual implementation.

---

# 108. Tonight API

Useful endpoint:

```text
GET /api/v1/automation/tonight
```

returns:

```text
active window

plans

estimated work

estimated cost

warnings
```

---

# 109. Automation Frontend

Add:

```text
Automation
```

navigation.

Pages:

```text
Tonight

Plans

Runs

Morning Review

Budgets

Capacity

Settings
```

---

# 110. Tonight Page

Show:

```text
Night Window

Automation Status

Planned Work

Estimated Cost

Budget

Provider Health

GPU Capacity

Warnings
```

---

# 111. Live Night View

While running:

```text
Current batch

Generation progress

QA progress

Similarity progress

Processing progress

Cost

Remaining budget

Remaining window
```

---

# 112. Run History

Show historical runs:

```text
date

plan

duration

generated

approved

review-ready

failed

cost

status
```

---

# 113. Run Detail

Show complete pipeline funnel:

```text
Planned
↓
Started
↓
Generated
↓
QA Approved
↓
Similarity Accepted
↓
Processed
↓
Review Ready
↓
Human Approved
```

---

# 114. Automation Settings

Expose safe settings:

```text
timezone

night window

global nightly budget

concurrency

notification time

default batch size
```

Advanced settings should be separated from basic UI.

---

# 115. Provider Health

Before run, show:

```text
provider

status

available models

rate-limit state

recent failure rate
```

using TASK-02.

---

# 116. Capacity Page

Show:

```text
GPU worker

CPU workers

generation slots

QA slots

processing slots

video slots
```

No fake capacity values.

---

# 117. Metrics

Add:

```text
media_factory_automation_runs_total

media_factory_automation_run_duration_seconds

media_factory_automation_generations_total

media_factory_automation_cost_total

media_factory_automation_items_failed_total

media_factory_automation_items_review_ready_total

media_factory_automation_budget_exhausted_total

media_factory_automation_diversity_pauses_total
```

Avoid high-cardinality labels.

---

# 118. Logs

Structured:

```text
automationRunId

automationPlanId

batchId

collectionId

experimentId

jobId

stage

status

cost
```

Never log secrets.

---

# 119. Tracing

Where existing observability supports it:

```text
AutomationRun
↓
Batch
↓
Generation
↓
QA
↓
Similarity
↓
Processing
```

should be traceable.

---

# 120. Audit Events

Record:

```text
AUTOMATION_PLAN_CREATED

AUTOMATION_ENABLED

AUTOMATION_DISABLED

AUTOMATION_RUN_STARTED

AUTOMATION_RUN_PAUSED

AUTOMATION_RUN_COMPLETED

AUTOMATION_BUDGET_EXHAUSTED

AUTOMATION_DIVERSITY_PAUSED

AUTOMATION_CANCELLED
```

---

# 121. Database

Create/refine migrations for:

```text
automation_plan

automation_schedule

automation_policy

automation_run

automation_run_item

automation_budget

morning_review_queue

morning_review_queue_item
```

Do not duplicate existing generic job tables.

---

# 122. Important Indexes

Consider:

```text
automation_plan.enabled

automation_run.automation_plan_id

automation_run.status

automation_run.scheduled_for

automation_run_item.automation_run_id

automation_run_item.collection_id

automation_run_item.asset_id

morning_review_queue.date

morning_review_queue_item.queue_id

morning_review_queue_item.asset_id
```

---

# 123. Optimistic Locking

Automation configuration edits should use optimistic locking where appropriate.

Prevent two users/processes from silently overwriting automation policy.

---

# 124. Tests — Scheduler

Test:

```text
schedule fires once

duplicate fire

disabled plan

manual run

missed run

timezone

restart

multiple backend instances
```

---

# 125. Tests — Eligibility

Test:

```text
automation-enabled collection

disabled collection

completed target

remaining target

paused collection

approved experiment

unapproved experiment
```

---

# 126. Tests — Budget

Test:

```text
enough budget

budget nearly exhausted

budget exhausted

concurrent reservations

actual cost > estimate

unknown cost
```

---

# 127. Tests — Batch Production

Test:

```text
10
↓
evaluate
↓
10
↓
evaluate
```

Verify automation does not enqueue all work blindly.

---

# 128. Tests — QA Collapse

Synthetic:

```text
batch 1:
10% rejected

batch 2:
80% rejected
```

Verify configured pause.

---

# 129. Tests — Diversity Collapse

Synthetic:

```text
near duplicate rate
→ exceeds threshold
```

Verify:

```text
PAUSED_DIVERSITY
```

---

# 130. Tests — Provider Failure

Simulate:

```text
rate limit

timeout

provider unavailable
```

Verify TASK-02 rules are respected.

---

# 131. Tests — GPU Capacity

Simulate:

```text
upscale
+
video
+
embedding
```

Verify configured GPU concurrency.

---

# 132. Tests — GPU OOM

Verify:

```text
OOM
↓
worker retry policy
↓
smaller tile / configured fallback
```

without silent model substitution.

---

# 133. Tests — Night Window

Verify new batches stop when:

```text
remainingWindow < minimumRemainingWindow
```

---

# 134. Tests — Restart

Simulate:

```text
02:00 run starts

03:00 crash

03:05 restart
```

Verify:

```text
same AutomationRun resumes
```

and paid generations are not duplicated.

---

# 135. Tests — Morning Queue

Create mixed outcomes:

```text
approved

needs review

rejected

near duplicate

failed

stock ready

wallpaper ready
```

Verify correct queue sections.

---

# 136. Tests — Review Deduplication

One asset may be:

```text
QA_NEEDS_REVIEW
+
SIMILARITY_REVIEW
```

It should appear as one review item with multiple reasons where architecture permits.

---

# 137. Tests — Experiment Attribution

Verify overnight generation retains:

```text
experimentId

variantId
```

through morning review.

---

# 138. Tests — Cost Attribution

Verify:

```text
automation run
→ generation
→ QA
→ processing
```

costs aggregate correctly from authoritative cost records.

---

# 139. Tests — Partial Completion

Example:

```text
planned 50

generated 38

QA approved 29

processed 27
```

Verify run can end:

```text
PARTIALLY_COMPLETED
```

with correct summary.

---

# 140. Integration Test — Full Night

Use accelerated simulated clock.

Scenario:

```text
23:00
↓
Night Run Starts
↓
3 Collections
↓
Batch Generation
↓
QA
↓
Similarity
↓
Processing
↓
Budget Check
↓
Next Batch
↓
07:00
↓
Stop New Work
↓
Build Morning Queue
↓
Digest
```

No paid APIs.

---

# 141. Integration Test — Wallpaper Night

```text
Collection
↓
Generation
↓
QA
↓
Similarity
↓
Processing
↓
Wallpaper Variants
↓
Preview
↓
Morning Queue
```

---

# 142. Integration Test — Stock Night

```text
Collection
↓
Generation
↓
QA
↓
Similarity
↓
Stock Processing
↓
4MP+
↓
Metadata
↓
Morning Queue
```

External stock submission disabled.

---

# 143. Integration Test — Experiment Night

```text
Approved Experiment
↓
Balanced A/B Generation
↓
QA
↓
Similarity
↓
Processing
↓
Publication-ready
↓
TASK-10 attribution
```

---

# 144. CI

CI must use:

```text
Mock Image Provider

Mock Vision Provider

Mock Video Provider

Mock LLM

Mock Storage

Mock external publication
```

No paid APIs.

No GPU requirement.

---

# 145. Documentation

Create:

```text
docs/automation.md

docs/night-production.md

docs/morning-review.md

docs/automation-budget.md

docs/automation-recovery.md

docs/automation-capacity.md

docs/automation-scheduling.md
```

---

# 146. Operations Runbook

Create:

```text
docs/runbooks/automation.md
```

Include:

```text
automation did not start

automation stuck

provider unavailable

budget exhausted

GPU worker unavailable

database restart

duplicate scheduler fire

morning queue missing

emergency stop

safe resume
```

---

# 147. Definition of Done

TASK-13 is complete when the user can configure:

```text
Night window:
23:00–07:00

Night budget:
$20

Wallpaper plan:
Cyber Wolves

Target:
20 new approved wallpaper-ready assets
```

and leave the system unattended.

During the night:

```text
23:00
↓
Automation starts
↓
Eligibility check
↓
Budget check
↓
Batch 1
↓
Generation
↓
QA
↓
Similarity
↓
Processing
↓
Evaluate
↓
Batch 2
↓
...
↓
Stop because target/budget/window reached
```

In the morning the UI must show:

```text
Night completed

Generated:
26

QA approved:
21

Rejected:
3

Near duplicates:
2

Wallpaper ready:
19

Needs manual review:
5

Cost:
actual authoritative amount
```

and provide one actionable:

```text
Morning Review Queue
```

---

# 148. Acceptance Scenario — Autonomous Night Factory

Configure:

```text
Automation:
Night Wallpaper Factory

Window:
23:00–07:00

Budget:
$25

Collections:

Cyber Wolves
target +20 approved

Space AMOLED
target +20 approved

Neon Cars
target +10 approved
```

Expected:

```text
23:00
↓
Run Created
↓
Plan Calculated
↓
Budget Reserved
↓
Cyber Wolves Batch
↓
QA
↓
Similarity
↓
Processing
↓
Evaluate
↓
Space AMOLED Batch
↓
...
↓
07:00
↓
No New Work
↓
Finalize Run
↓
Morning Queue
↓
Digest
```

---

# 149. Acceptance Scenario — Diversity Protection

Configure a collection whose recent generations become highly repetitive.

Expected:

```text
Batch 1
↓
OK

Batch 2
↓
Similarity rises

Batch 3
↓
Diversity threshold exceeded

Collection:
PAUSED_DIVERSITY
```

Other eligible collections continue.

Morning report explains:

```text
Cyber Wolves paused after 20 generations because
near-duplicate rate exceeded configured threshold.
```

---

# 150. Acceptance Scenario — Budget Protection

Configure:

```text
night budget:
$10
```

Expected:

```text
cost reservations
↓
actual costs
↓
remaining budget insufficient
↓
stop launching paid work
↓
BUDGET_EXHAUSTED
```

No uncontrolled overspend.

---

# 151. Acceptance Scenario — Crash Recovery

Simulate backend crash during paid generation.

Expected:

```text
restart
↓
same AutomationRun recovered
↓
existing jobs reconciled
↓
unknown external request not blindly repeated
↓
remaining pipeline continues
```

---

# 152. Verification Checklist

Before completing TASK-13:

1. inspect existing scheduling/job infrastructure;
2. inspect TASK-01 → TASK-12;
3. implement AutomationPlan;
4. implement versioned AutomationPolicy;
5. implement timezone-aware schedule;
6. implement AutomationRun;
7. implement AutomationRunItem;
8. implement eligibility;
9. implement production planner;
10. implement explicit automation opt-in;
11. implement target deficits;
12. implement bounded max generations;
13. implement batch execution;
14. implement between-batch evaluation;
15. integrate TASK-02 provider health;
16. integrate rate limits;
17. integrate retry/fallback;
18. integrate TASK-04 QA;
19. integrate TASK-05 similarity;
20. integrate diversity guard;
21. integrate TASK-06 processing;
22. integrate TASK-07 wallpaper pipeline;
23. integrate TASK-08 stock pipeline;
24. integrate TASK-09 video pipeline;
25. integrate TASK-10 cost accounting;
26. integrate TASK-11 experiments;
27. integrate TASK-12 Codex Skills;
28. implement budget reservation;
29. implement unknown-cost handling;
30. implement budget exhaustion;
31. implement GPU capacity;
32. implement concurrency limits;
33. implement night window enforcement;
34. implement minimum remaining window;
35. implement manual run;
36. implement dry run;
37. implement pause;
38. implement emergency stop;
39. implement cancellation;
40. implement restart recovery;
41. implement stale-run recovery;
42. implement schedule idempotency;
43. implement duplicate-fire protection;
44. implement missed-run policy;
45. test timezone/DST;
46. implement MorningReviewQueue;
47. implement queue deduplication;
48. implement review reasons;
49. implement priority explanation;
50. integrate Review Workspace;
51. implement morning dashboard;
52. implement digest;
53. implement notifications;
54. implement frontend Tonight page;
55. implement Plans;
56. implement Runs;
57. implement Budgets;
58. implement Capacity;
59. implement Settings;
60. implement observability;
61. implement audit events;
62. implement scheduler tests;
63. implement budget tests;
64. implement diversity tests;
65. implement QA-collapse tests;
66. implement provider-failure tests;
67. implement GPU-capacity tests;
68. implement restart tests;
69. implement morning-queue tests;
70. implement experiment-attribution tests;
71. implement cost-attribution tests;
72. run full simulated night;
73. run wallpaper night;
74. run stock night;
75. run experiment night;
76. verify no paid API calls in CI;
77. run backend tests;
78. run frontend tests;
79. run production builds;
80. document remaining limitations.

---

# 153. Final Codex Report

At completion provide:

## Scheduling Architecture

Describe:

```text
AutomationPlan
↓
Schedule
↓
AutomationRun
↓
NightProductionPlan
↓
Batch Executor
```

## Production Planning

Report:

```text
eligibility

prioritization

targets

batching

night-window enforcement
```

## Budget Protection

Report:

```text
nightly budget

plan budget

experiment budget

reservations

actual cost reconciliation

unknown-cost behavior
```

## Capacity

Report:

```text
generation concurrency

QA concurrency

processing concurrency

GPU scheduling

video scheduling
```

## Safety

Report:

```text
QA collapse

diversity collapse

provider failure

budget exhaustion

emergency stop
```

## Recovery

Report:

```text
restart

duplicate scheduler fire

stale jobs

uncertain paid requests

idempotency
```

## Morning Review

Report:

```text
queue construction

priority

deduplication

review reasons

Review Workspace integration

digest
```

## Pipeline Integrations

Report integration with:

```text
TASK-02 Providers

TASK-03 Prompts

TASK-04 QA

TASK-05 Similarity

TASK-06 Processing

TASK-07 Wallpapers

TASK-08 Stock

TASK-09 Video

TASK-10 Analytics

TASK-11 Feedback

TASK-12 Codex Skills
```

## Frontend

Report:

```text
Tonight

Plans

Runs

Morning Review

Budgets

Capacity

Settings
```

## Tests

Report all:

```text
scheduler

eligibility

budget

batch

QA collapse

diversity collapse

provider failure

GPU

restart

review queue

full-night integration
```

## Remaining Limitations

Explicitly document:

```text
provider cancellation limitations

unknown provider costs

GPU scheduling limitations

scheduler clustering limitations

notification limitations

external publication limitations

remaining manual review points
```

---

# Engineering Principles

1. Automation orchestrates existing pipelines; it does not replace them.
2. Automation must be explicitly enabled.
3. Existing collections are not automatically eligible.
4. Paid autonomous work must have a budget.
5. Unknown-cost paid work is disabled by default.
6. Never allow infinite generation toward an outcome target.
7. Every run has bounded generation count.
8. Every run has bounded cost.
9. Every run has a bounded execution window.
10. Generate in stages rather than giant batches.
11. Evaluate quality between batches.
12. Evaluate similarity between batches.
13. Evaluate diversity between batches.
14. Evaluate cost between batches.
15. Evaluate provider health between batches.
16. Stop launching work when remaining window is insufficient.
17. QA runs during the night, not only in the morning.
18. Similarity runs during the night.
19. Processing runs during the night where capacity permits.
20. Morning should contain reviewable results rather than raw output.
21. Human approval remains authoritative.
22. Automation must not publish externally unless explicitly authorized.
23. Stock external submission remains disabled by default.
24. Automation must not bypass TASK-04 QA.
25. Automation must not bypass TASK-05 similarity.
26. Automation must not bypass TASK-06 processing validation.
27. Automation must preserve TASK-03 prompt lineage.
28. Automation must preserve TASK-11 experiment attribution.
29. Approved experiments may run automatically; proposed experiments may not.
30. Findings alone do not authorize paid generation.
31. Do not silently change prompt versions.
32. Do not silently change providers.
33. Do not silently change models.
34. Do not silently change processing quality after GPU failure.
35. Use TASK-02 fallback rules.
36. Respect provider rate limits.
37. Respect worker concurrency.
38. Respect GPU capacity.
39. Prevent GPU workloads from exhausting VRAM through uncontrolled concurrency.
40. Never blindly retry uncertain paid requests.
41. Every scheduled occurrence must be idempotent.
42. Duplicate scheduler fires must not create duplicate runs.
43. Backend restart must recover the existing run.
44. Historical runs preserve their policy snapshot.
45. Historical runs preserve their schedule snapshot.
46. Historical runs preserve their budget snapshot.
47. Failed/rejected assets remain in production history.
48. Do not delete duplicates automatically.
49. Do not overwrite originals.
50. Partial completion is a valid result.
51. Successful work must survive failures elsewhere in the run.
52. A failing collection should not necessarily stop unrelated collections.
53. Diversity collapse should pause the affected scope.
54. QA collapse should pause the affected scope.
55. Budget exhaustion stops new paid work.
56. Emergency stop prevents new work safely.
57. Running external jobs may not always be cancellable.
58. Actual cost comes from authoritative cost records.
59. Estimated cost and actual cost are distinct.
60. Morning priority must be explainable.
61. Do not use an opaque LLM to rank the entire review queue.
62. Review queues should deduplicate multiple reasons for the same asset.
63. Morning review must reuse the existing Review Workspace.
64. Automation configuration must be auditable.
65. Manual overrides must be auditable.
66. Codex Skills should operate the Automation subsystem, not create random OS cron jobs.
67. Timezone must be explicit.
68. DST must be handled by timezone-aware scheduling.
69. CI must not call paid providers.
70. CI must not require GPU.
71. Production scheduler must support multi-instance safety.
72. Automation must be observable.
73. Every run should be traceable:

```text
Schedule
↓
AutomationRun
↓
Batch
↓
Generation
↓
Asset
↓
QA
↓
Similarity
↓
Processing
↓
Variant
↓
Morning Review
↓
Human Decision
```

74. Costs should be traceable:

```text
AutomationRun
↓
Generation / QA / Video / Processing
↓
GenerationCost
↓
TASK-10
```

75. Experiments should remain traceable:

```text
TASK-11 Hypothesis
↓
PromptExperiment
↓
AutomationRun
↓
Generation
↓
Asset
↓
Publication
↓
Analytics
↓
Experiment Result
```

76. Automation should maximize unattended useful work, not unattended volume.
77. Producing fewer diverse approved assets is preferable to generating hundreds of repetitive failures.
78. The system must fail safely when providers, GPU, storage or budget controls fail.
79. The user must always be able to see why automation stopped.
80. The user must always be able to see what automation spent.
81. The user must always be able to see what was produced.
82. The user must always be able to see what needs attention.
83. The user must be able to pause all automation immediately.
84. Automation should turn nighttime compute/provider capacity into a morning decision queue.
85. The final operational loop should be:

```text
EVENING
↓
Approve Plans / Experiments
↓
NIGHT
↓
Plan
↓
Generate
↓
QA
↓
Similarity
↓
Process
↓
Validate
↓
MORNING
↓
Review Queue
↓
Human Decisions
↓
Publish / Regenerate
↓
Analytics
↓
Feedback
↓
Next Approved Production Plan
↺
```

The result of TASK-13 should transform Media Factory from a system that requires constant manual triggering into a **controlled autonomous production factory** capable of doing expensive, repetitive production work overnight while the human remains responsible for creative and publication decisions in the morning.
