# TASK-04 — Advanced Visual QA

## Objective

Build a production-ready Advanced Visual Quality Assurance system for Media Factory.

The QA system must automatically inspect generated images before they are allowed to continue through production pipelines.

Implement:

* deterministic technical image validation;
* multimodal Vision Model review;
* visual artifact detection;
* anatomy/problem detection where applicable;
* unwanted text/logo/watermark detection;
* prompt-compliance evaluation;
* composition evaluation;
* subject integrity checks;
* pipeline-specific QA policies;
* structured issue taxonomy;
* severity levels;
* configurable acceptance/rejection rules;
* multi-dimensional QA scores;
* confidence tracking;
* automatic QA decisions;
* `NEEDS_REVIEW` state for uncertain cases;
* human approval/rejection;
* human override of AI decisions;
* override reasons and audit history;
* regeneration workflow;
* QA history;
* QA provider abstraction;
* cost tracking;
* retry/fallback integration;
* dashboard/review UI;
* batch review;
* metrics and observability.

The primary principle is:

```text
Generated Asset
      ↓
Technical QA
      ↓
Visual AI QA
      ↓
Policy Engine
      ↓
┌──────────┬───────────────┬──────────┐
│ APPROVED │ NEEDS_REVIEW  │ REJECTED │
└──────────┴───────────────┴──────────┘
                    ↓
               Human Review
                    ↓
          APPROVE / REJECT
                    ↓
              optional
             REGENERATE
```

QA must produce structured evidence and individual dimensions.

Do NOT implement quality as a single arbitrary AI-generated number.

---

# 1. Inspect Existing Architecture First

Before implementation:

1. inspect TASK-01 foundation;
2. inspect TASK-02 provider routing/resilience architecture;
3. inspect TASK-03 Prompt Engine;
4. inspect existing `Asset`, `Generation`, `QualityReview`, `Job`, and `GenerationCost` models;
5. locate existing technical QA;
6. locate generation lifecycle transitions;
7. locate existing Review frontend;
8. reuse/refactor existing abstractions instead of creating parallel systems.

Search for existing concepts such as:

```text
QualityReview
QA_PENDING
APPROVED
REJECTED
AssetStatus
GenerationStatus
technical validation
```

Preserve existing functionality and migrations.

Do not modify already-applied Flyway migrations.

---

# 2. Target Architecture

Implement this logical architecture:

```text
Generation
    ↓
Generated Asset
    ↓
QA Orchestrator
    │
    ├─────────────────────┐
    ↓                     ↓
Technical QA          Visual AI QA
    │                     │
    └──────────┬──────────┘
               ↓
        QA Evaluation
               ↓
          Policy Engine
               ↓
       Final QA Decision
               │
      ┌────────┼─────────┐
      ↓        ↓         ↓
 APPROVED   REVIEW    REJECTED
              ↓
        Human Reviewer
              ↓
       Human Decision
              ↓
     APPROVED / REJECTED
              │
              ↓
         REGENERATE?
```

Separate:

```text
Detection
```

from:

```text
Decision
```

A detector reports observations.

A policy decides what those observations mean for a particular pipeline.

---

# 3. Core Principle: Detection != Policy

Do NOT write logic such as:

```java
if (visionModelScore < 0.8) {
    reject();
}
```

inside provider or detector implementations.

Instead:

```text
Detector
    ↓
Structured Findings
    ↓
QA Policy Engine
    ↓
Decision
```

Example:

```text
Finding:
UNWANTED_TEXT

confidence:
0.94

severity:
MAJOR
```

For a wallpaper pipeline this might produce:

```text
REJECT
```

For another pipeline it might produce:

```text
NEEDS_REVIEW
```

The detection result must remain independent from the policy decision.

---

# 4. QA Levels

Implement at least two QA layers:

```text
TECHNICAL
VISUAL_AI
```

Architecture should allow future layers such as:

```text
SIMILARITY
BRAND_SAFETY
STOCK_COMPLIANCE
COPYRIGHT_RISK
FACE_QUALITY
VIDEO_QA
```

Do not implement all future layers in TASK-04.

---

# 5. QA Orchestrator

Create/refine:

```java
public interface QualityAssuranceService {

    QualityReviewResult review(
        QualityReviewRequest request
    );
}
```

Or equivalent architecture matching the project.

Responsibilities:

```text
load Asset
↓
load Generation context
↓
load prompt snapshot
↓
execute technical QA
↓
execute visual QA
↓
aggregate findings
↓
evaluate policy
↓
persist review
↓
transition Asset/Generation state
```

The orchestrator must not contain provider-specific Vision API logic.

---

# 6. QA Request Context

Visual QA needs more than the image.

Build a context containing:

```text
assetId
generationId

asset metadata

canonical prompt
negative prompt

provider-adapted prompt

prompt variables

pipeline

collection

expected aspect ratio

expected dimensions

expected subject

generation provider/model
```

Reuse TASK-03 `RenderedPromptSnapshot`.

Do not dynamically reconstruct historical prompts.

---

# 7. Technical QA

Extend TASK-01 technical QA.

Technical QA must be deterministic and not require AI.

Checks should include:

```text
FILE_READABLE
SUPPORTED_FORMAT
EXPECTED_MIME_TYPE
MINIMUM_RESOLUTION
EXPECTED_ASPECT_RATIO
FILE_SIZE
EMPTY_IMAGE
CORRUPT_IMAGE
ALPHA_CHANNEL
UNEXPECTED_TRANSPARENCY
BLACK_BORDER
SOLID_OR_NEAR_SOLID_IMAGE
EXTREME_BLUR
EXTREME_UNDEREXPOSURE
EXTREME_OVEREXPOSURE
SHA256_DUPLICATE
```

Only implement checks that can be made reliable enough.

Avoid fragile heuristics presented as certainty.

---

# 8. Technical Finding

Every check produces structured output.

Example:

```json
{
  "code": "RESOLUTION_TOO_LOW",
  "category": "TECHNICAL",
  "severity": "CRITICAL",
  "detected": true,
  "confidence": 1.0,
  "message": "Image resolution is below the pipeline minimum.",
  "metadata": {
    "width": 1024,
    "height": 1792,
    "requiredWidth": 1440,
    "requiredHeight": 2560
  }
}
```

Technical deterministic checks may use:

```text
confidence = 1.0
```

when appropriate.

---

# 9. Vision Provider Abstraction

TASK-02 established provider abstractions.

Create/refine:

```java
public interface VisionQualityProvider {

    String providerId();

    VisionQualityResult analyze(
        VisionQualityRequest request
    );

    VisionQualityCapabilities capabilities();
}
```

Do not expose SDK-specific classes outside the adapter.

Potential implementations:

```text
MockVisionQualityProvider
<FirstRealVisionProvider>
```

Preserve Mock mode for local development and automated tests.

---

# 10. Vision Model Responsibilities

The Vision Model should evaluate structured dimensions rather than answering:

```text
"Is this image good?"
```

Evaluate independently:

```text
prompt compliance

visual artifacts

subject integrity

anatomy

composition

text presence

watermark/logo presence

image coherence

crop/framing

background consistency
```

Only evaluate dimensions relevant to the asset/pipeline.

---

# 11. Structured Vision Output

Do not rely on parsing free-form prose.

Require structured output.

Example:

```json
{
  "dimensions": {
    "promptCompliance": {
      "score": 0.91,
      "confidence": 0.88
    },
    "composition": {
      "score": 0.86,
      "confidence": 0.82
    },
    "subjectIntegrity": {
      "score": 0.95,
      "confidence": 0.91
    }
  },
  "findings": [
    {
      "code": "MINOR_FUR_ARTIFACT",
      "severity": "MINOR",
      "confidence": 0.76,
      "description": "Small inconsistent fur detail near the left ear."
    }
  ]
}
```

Validate the response schema before accepting it.

Invalid Vision Model output must be treated as provider failure, not as an image failure.

---

# 12. QA Dimension Model

Do not use one universal score.

Create dimensions such as:

```text
TECHNICAL_INTEGRITY

PROMPT_COMPLIANCE

SUBJECT_INTEGRITY

ANATOMY

COMPOSITION

VISUAL_COHERENCE

TEXT_FREE

WATERMARK_FREE

CROP_QUALITY
```

Each result may contain:

```text
score
confidence
evidence
```

Use normalized values consistently, preferably:

```text
0.0 → 1.0
```

Document semantics.

---

# 13. Scores vs Confidence

Keep these separate.

Example:

```text
score = 0.35
confidence = 0.97
```

means:

> The model is highly confident that this dimension is poor.

Whereas:

```text
score = 0.35
confidence = 0.42
```

means:

> The model suspects a problem but is uncertain.

Policy must be able to use both values.

---

# 14. Issue Taxonomy

Create stable machine-readable issue codes.

Initial categories:

```text
TECHNICAL
ARTIFACT
ANATOMY
TEXT
WATERMARK
PROMPT_COMPLIANCE
COMPOSITION
SUBJECT
CROP
OTHER
```

Examples:

```text
CORRUPT_FILE
RESOLUTION_TOO_LOW
WRONG_ASPECT_RATIO

BLUR
GENERATIVE_ARTIFACT
REPEATED_PATTERN
BROKEN_GEOMETRY

MALFORMED_FACE
MALFORMED_EYES
EXTRA_LIMB
MISSING_LIMB
ANATOMY_INCONSISTENCY

UNWANTED_TEXT
UNWANTED_LOGO
POSSIBLE_WATERMARK

SUBJECT_MISSING
SUBJECT_PARTIALLY_MISSING
WRONG_SUBJECT

PROMPT_CONFLICT
PROMPT_REQUIREMENT_MISSING

SUBJECT_CROPPED
POOR_COMPOSITION
```

Keep codes stable because future analytics will depend on them.

---

# 15. Severity

Support:

```text
INFO
MINOR
MAJOR
CRITICAL
```

Severity describes impact, not confidence.

Example:

```text
possible tiny artifact

severity = MINOR
confidence = 0.62
```

versus:

```text
clearly malformed face

severity = CRITICAL
confidence = 0.97
```

---

# 16. Anatomy Detection

Anatomy evaluation must be contextual.

Do not require human anatomy rules for:

```text
abstract art
landscapes
objects
architecture
```

Use asset/generation context.

For humans/animals evaluate relevant areas such as:

```text
face
eyes
limbs
body structure
symmetry where expected
```

Do not treat intentional fantasy anatomy as automatically invalid if the prompt explicitly requests it.

Prompt context matters.

---

# 17. Artifact Detection

Detect likely generative problems such as:

```text
merged structures
duplicated features
broken geometry
inconsistent textures
floating objects
impossible connections
repeated patterns
unintended deformation
unnatural edge transitions
```

Findings should include confidence.

Do not claim deterministic certainty from a Vision Model.

---

# 18. Text Detection

For pipelines requiring text-free images, evaluate:

```text
visible text
pseudo-text
letters
numbers
logos
watermarks
signatures
```

Where practical combine:

```text
deterministic/local detection
+
Vision Model
```

Do not introduce OCR merely because it exists.

Use it only if it materially improves reliability and fits the project's available tooling.

---

# 19. Prompt Compliance

TASK-03 gives access to the exact prompt snapshot.

Vision QA must compare the generated image against relevant prompt requirements.

Evaluate:

```text
main subject
subject count
important colors
composition
environment
style constraints
required visual elements
forbidden visual elements
```

Do not require literal matching for subjective stylistic language.

Return structured missing/violated requirements.

Example:

```json
{
  "code": "PROMPT_REQUIREMENT_MISSING",
  "severity": "MAJOR",
  "confidence": 0.92,
  "metadata": {
    "requirement": "orange eyes"
  }
}
```

---

# 20. Negative Prompt Compliance

Where useful, compare the output against negative constraints from TASK-03.

Example:

```text
Negative prompt:

text
watermark
logo
cropped head
```

If the image contains visible text:

```text
NEGATIVE_CONSTRAINT_VIOLATION
```

Preserve the specific constraint in metadata.

---

# 21. Composition Evaluation

Composition is pipeline-dependent.

For Wallpaper:

```text
subject visibility
vertical framing
lock-screen safe composition
head/face not clipped
visual center
background suitability
```

For Stock:

```text
clean composition
usable framing
copy space when requested
subject separation
commercial usability
```

Do not hardcode Wallpaper rules into generic Vision QA.

---

# 22. QA Policy

Create:

```java
public interface QualityPolicy {

    QualityDecision evaluate(
        QualityEvaluationContext context
    );
}
```

Policies should be configurable per pipeline.

Examples:

```text
WallpaperQualityPolicy
StockQualityPolicy
DefaultQualityPolicy
```

Prefer configuration-driven rules over large hardcoded classes.

---

# 23. Policy Configuration

Example:

```yaml
quality:

  policies:

    wallpaper:

      technical:
        minimum-width: 1440
        minimum-height: 2560

      rules:

        critical-finding:
          action: REJECT

        major-finding:
          minimum-confidence: 0.85
          action: REJECT

        uncertain-major-finding:
          minimum-confidence: 0.50
          action: NEEDS_REVIEW

      dimensions:

        prompt-compliance:
          minimum-score: 0.75

        composition:
          minimum-score: 0.70

        subject-integrity:
          minimum-score: 0.80
```

Do not hardcode thresholds throughout services.

---

# 24. QA Decisions

Support:

```text
APPROVED
NEEDS_REVIEW
REJECTED
```

Optionally:

```text
QA_FAILED
```

for cases where QA itself could not execute.

Do not classify a Vision API outage as:

```text
REJECTED
```

The asset did not fail QA; QA execution failed.

---

# 25. QA Execution Status

Keep execution status separate from content decision.

Example:

```text
executionStatus:

PENDING
RUNNING
COMPLETED
FAILED
```

and:

```text
decision:

APPROVED
NEEDS_REVIEW
REJECTED
```

This prevents infrastructure failures from being confused with content failures.

---

# 26. Quality Review Persistence

Refactor/extend existing `QualityReview`.

Suggested structure:

```text
QualityReview

id

assetId
generationId

policyId
policyVersion

executionStatus
decision

startedAt
completedAt

technicalCompleted
visualCompleted

automaticDecision

finalDecision

humanOverride

reviewedBy
reviewedAt

overrideReason

createdAt
```

Preserve historical reviews.

---

# 27. QA Findings

Create:

```text
QualityFinding
```

Suggested fields:

```text
id

qualityReviewId

source

category
code

severity

confidence
score

description

metadata

createdAt
```

Sources:

```text
TECHNICAL
VISION_MODEL
HUMAN
```

Do not put every finding into one unstructured text blob.

---

# 28. Dimension Results

Create structured persistence for dimensions.

Example:

```text
QualityDimensionResult

reviewId

dimension

score
confidence

provider
model

metadata
```

This will later support analytics such as:

```text
average prompt compliance by provider

artifact rate by model

approval rate by prompt version
```

---

# 29. QA Versioning

QA behavior changes over time.

Store:

```text
policyId
policyVersion

visionProvider
visionModel

visionPromptVersion
```

A historical QA result must remain understandable after policy thresholds change.

---

# 30. Vision QA Prompt

Treat the Vision QA system prompt as a versioned internal artifact.

Do not scatter it through Java strings.

Suggested location:

```text
prompts/
    qa/
        visual-quality/
            v1.yaml
```

or integrate with TASK-03 infrastructure if appropriate without conflating user-generation prompts with internal operational prompts.

The QA prompt must request structured output.

---

# 31. Vision QA Prompt Requirements

The system prompt should explicitly tell the model:

```text
evaluate only visible evidence

use provided generation context

do not assume hidden details

distinguish confidence from severity

return structured findings

do not reject solely because the style is unusual

respect intentional fantasy/surreal prompt requirements

evaluate prompt compliance independently from aesthetic preference
```

Avoid asking:

```text
"Do you like this image?"
```

---

# 32. Vision Provider Cost Tracking

Vision QA consumes paid AI resources.

Integrate with TASK-02 cost infrastructure.

Track:

```text
operation = VISUAL_QA

reviewId
assetId

provider
model

input usage
output usage

estimatedCost
actualCost

currency

pricingVersion
```

QA cost must contribute to total asset production cost.

Eventually:

```text
generation
+
visual QA
+
upscale
+
video
=
asset production cost
```

---

# 33. Retry and Fallback

Reuse TASK-02 resilience architecture where appropriate.

Vision provider failures may include:

```text
429
timeout
temporary outage
5xx
```

Support:

```text
retry
backoff
rate limiting
concurrency limits
fallback provider
```

Do not retry:

```text
invalid credentials
invalid request
unsupported media
```

A failed Vision provider must not automatically cause content rejection.

---

# 34. Vision Provider Fallback

Architecture:

```text
Visual QA
   ↓
Vision Provider A
   ↓
temporary failure
   ↓
retry
   ↓
still unavailable
   ↓
Vision Provider B
```

Preserve attempt history similarly to generation provider attempts.

If all Vision providers fail:

```text
QA execution = FAILED
```

or:

```text
NEEDS_REVIEW
```

depending on explicit configured policy.

Never silently approve because AI QA was unavailable.

---

# 35. QA Attempt History

Create/reuse generic provider-attempt infrastructure where appropriate.

Track:

```text
reviewId

provider
model

attempt

status

duration

providerRequestId

errorType
errorCode

retryable

cost
```

Do not duplicate TASK-02 infrastructure unnecessarily if it can be generalized safely.

---

# 36. Automatic Decision

After all required QA stages complete:

```text
Technical Findings
+
Vision Findings
+
Dimension Scores
+
Policy
```

produce:

```text
automaticDecision
```

Example:

```text
APPROVED
```

or:

```text
REJECTED
```

or:

```text
NEEDS_REVIEW
```

Persist the reason for the decision.

---

# 37. Decision Explanation

Policy Engine should return machine-readable reasoning.

Example:

```json
{
  "decision": "REJECTED",
  "rules": [
    {
      "rule": "CRITICAL_FINDING",
      "finding": "MALFORMED_FACE"
    },
    {
      "rule": "PROMPT_COMPLIANCE_MINIMUM",
      "actual": 0.54,
      "required": 0.75
    }
  ]
}
```

Do not rely only on prose explanation.

---

# 38. Human Review

Human reviewer must be able to:

```text
APPROVE

REJECT

OVERRIDE AI APPROVAL

OVERRIDE AI REJECTION

REQUEST REGENERATION
```

Human decision becomes:

```text
finalDecision
```

while preserving:

```text
automaticDecision
```

Never overwrite AI decision history.

---

# 39. Human Override

Example:

```text
automaticDecision:
REJECTED

finalDecision:
APPROVED

humanOverride:
true

reason:
"Intentional asymmetry required by prompt."
```

Or:

```text
automaticDecision:
APPROVED

finalDecision:
REJECTED

reason:
"Visible artifact near the eye missed by AI."
```

Both directions must be supported.

---

# 40. Override Reason

Require a reason when human decision contradicts automatic decision.

Support:

```text
reasonCode
reasonText
```

Suggested reason codes:

```text
AI_FALSE_POSITIVE
AI_FALSE_NEGATIVE
INTENTIONAL_STYLE
PROMPT_CONTEXT
TECHNICAL_EXCEPTION
MANUAL_QUALITY_JUDGMENT
OTHER
```

If `OTHER`, require text.

---

# 41. Human Findings

Reviewer should optionally be able to add a finding.

Example:

```text
Category:
ARTIFACT

Code:
MALFORMED_EYE

Severity:
MAJOR

Comment:
"Visible deformation in right eye."
```

Source:

```text
HUMAN
```

This data becomes valuable later for measuring Vision QA accuracy.

---

# 42. Review Locking

Prevent conflicting review operations.

Example:

```text
Reviewer A opens asset

Reviewer B opens same asset

A approves
B rejects stale state
```

Use optimistic locking or another appropriate mechanism.

Frontend must handle conflict clearly.

---

# 43. Regeneration Workflow

A rejected asset may be regenerated.

Do NOT overwrite or mutate the original Generation.

Flow:

```text
Generation #100
     ↓
Asset #200
     ↓
QA REJECTED
     ↓
REGENERATE
     ↓
Generation #101
```

Link:

```text
Generation #101
parentGenerationId = #100
```

or equivalent explicit lineage.

---

# 44. Regeneration Strategy

Support initial regeneration modes:

```text
SAME_PROMPT
MODIFIED_VARIABLES
MANUAL_OVERRIDE
```

Architecture should later support:

```text
AUTO_CORRECT_PROMPT
ALTERNATE_PROVIDER
ALTERNATE_MODEL
```

Do not implement uncontrolled autonomous prompt rewriting in TASK-04.

---

# 45. Same-Prompt Regeneration

For:

```text
SAME_PROMPT
```

reuse the canonical prompt snapshot from TASK-03.

Create a new Generation.

Do not reuse the previous provider result.

If provider supports seed and the goal is variation, use an explicit seed policy.

Record lineage.

---

# 46. Regeneration From Human Feedback

Allow optional feedback:

```text
"Eyes malformed; preserve orange eyes and improve facial symmetry."
```

Store this as regeneration context.

Do not silently alter the original prompt version.

If applied as an override/suffix, ensure TASK-03 snapshot captures the final prompt used by the new Generation.

---

# 47. QA State Integration

Integrate with existing lifecycle.

Expected successful generation:

```text
GENERATED
↓
QA_PENDING
↓
QA_RUNNING
↓
APPROVED
```

or:

```text
QA_PENDING
↓
QA_RUNNING
↓
NEEDS_REVIEW
```

or:

```text
QA_PENDING
↓
QA_RUNNING
↓
REJECTED
```

Adapt exact state ownership to the existing architecture.

Avoid contradictory status fields between Asset and Generation.

Document which entity owns QA state.

---

# 48. Publishing Gate

Assets must not proceed to publication unless:

```text
finalDecision = APPROVED
```

If human review has not occurred:

```text
finalDecision = automaticDecision
```

when policy allows automatic approval.

Publishing services must enforce this server-side.

Do not rely on frontend button visibility.

---

# 49. Manual QA Mode

Allow a pipeline to require:

```text
ALWAYS_HUMAN_REVIEW
```

Example:

```yaml
quality:
  policies:
    premium-stock:
      human-review:
        required: true
```

Then even an automatic:

```text
APPROVED
```

becomes:

```text
NEEDS_REVIEW
```

until human approval.

---

# 50. Confidence-Based Review

Support rules such as:

```text
high confidence + no issues
→ APPROVED

high confidence critical issue
→ REJECTED

low confidence important issue
→ NEEDS_REVIEW
```

Example configuration:

```yaml
quality:

  decision:

    auto-approve:
      minimum-confidence: 0.85

    auto-reject:
      critical-finding-confidence: 0.90

    review:
      uncertain-finding-confidence:
        min: 0.40
        max: 0.90
```

Exact rule structure may differ.

Keep it understandable and testable.

---

# 51. QA Profiles

Introduce reusable QA profiles.

Examples:

```text
WALLPAPER_STANDARD

WALLPAPER_PREMIUM

STOCK_STANDARD

SOCIAL_MEDIA
```

Profile contains:

```text
technical rules

required AI dimensions

decision thresholds

human-review policy
```

Collections/pipelines should reference profiles rather than duplicate configuration.

---

# 52. Wallpaper QA Profile

Initial wallpaper profile should evaluate:

```text
portrait suitability

resolution

aspect ratio

subject integrity

face/eye quality where applicable

prompt compliance

unwanted text

watermarks/logos

subject clipping

visual artifacts

composition
```

Do not evaluate irrelevant stock-photo requirements.

---

# 53. Stock QA Foundation

Prepare but do not overbuild Stock-specific QA.

Potential future checks:

```text
commercial usability

logos/trademarks

model/property concerns

metadata consistency

duplicate similarity

stock-platform technical requirements
```

TASK-04 only needs enough architecture for future policies.

---

# 54. Review API

Implement endpoints consistent with project conventions.

Examples:

```text
GET /api/v1/reviews

GET /api/v1/reviews/{id}

POST /api/v1/reviews/{id}/approve

POST /api/v1/reviews/{id}/reject

POST /api/v1/reviews/{id}/regenerate
```

Support filters:

```text
decision
executionStatus
collection
pipeline
finding
severity
provider
createdAt
```

Use pagination.

---

# 55. Review Queue API

Create an efficient review queue endpoint.

Example:

```text
GET /api/v1/reviews/queue
```

Default priority could be:

```text
NEEDS_REVIEW
↓
oldest first
```

Support future prioritization.

Do not load full-resolution image binary through JSON.

Return appropriate media URLs/references according to existing storage architecture.

---

# 56. Batch Review API

Support batch operations:

```text
approve selected

reject selected
```

Do not make batch operations all-or-nothing unless the existing architecture requires it.

Return per-item result.

Example:

```json
{
  "successful": 18,
  "failed": 2,
  "results": []
}
```

Each operation must preserve audit history.

---

# 57. Frontend — Review Workspace

Upgrade the existing Review page into a production review workspace.

Main layout:

```text
┌───────────────────────────────────────────────────────────┐
│ REVIEW QUEUE                                              │
├───────────────────────┬───────────────────────────────────┤
│                       │ QA                               │
│                       │                                  │
│                       │ Decision: NEEDS_REVIEW           │
│       IMAGE           │                                  │
│                       │ Prompt compliance   91%           │
│                       │ Composition         84%           │
│                       │ Subject integrity   96%           │
│                       │                                  │
│                       │ Findings                         │
│                       │ ⚠ Minor fur artifact             │
│                       │ ⚠ Possible pseudo-text           │
│                       │                                  │
├───────────────────────┴───────────────────────────────────┤
│ [Approve] [Reject] [Regenerate] [Previous] [Next]        │
└───────────────────────────────────────────────────────────┘
```

Optimize for rapid human review.

---

# 58. Full-Resolution Inspection

Reviewer must be able to:

```text
zoom

pan

fit image

view at 100%

view metadata
```

Do not force review only from a tiny thumbnail.

Where practical provide quick access to:

```text
original
processed preview
```

---

# 59. Finding Visualization

Show findings grouped by severity:

```text
CRITICAL

MAJOR

MINOR

INFO
```

Display:

```text
code
description
confidence
source
```

Example:

```text
MAJOR

MALFORMED_EYE

Vision Model
Confidence: 94%
```

Do not represent confidence as certainty.

---

# 60. Prompt Comparison UI

Reviewer should be able to see:

```text
IMAGE
```

alongside:

```text
CANONICAL PROMPT
NEGATIVE PROMPT
```

and optionally:

```text
PROVIDER-ADAPTED PROMPT
```

This is necessary for judging prompt compliance.

---

# 61. Review Keyboard Shortcuts

For fast production review implement shortcuts such as:

```text
A → Approve

R → Reject

G → Regenerate

← → Previous

→ → Next
```

Do not trigger destructive actions while typing in text fields.

Display shortcut hints in UI.

---

# 62. Grid Review

Provide grid mode:

```text
┌──────┐ ┌──────┐ ┌──────┐
│ IMG  │ │ IMG  │ │ IMG  │
│  ✓ ✕ │ │  ✓ ✕ │ │  ✓ ✕ │
└──────┘ └──────┘ └──────┘
```

Useful information per card:

```text
automatic decision

highest severity finding

prompt compliance

collection

generation provider
```

Avoid clutter.

---

# 63. Batch Selection

Support:

```text
Select all visible

Select approved candidates

Select review candidates

Approve selected

Reject selected
```

Require confirmation for large rejection batches if appropriate.

---

# 64. Human Feedback Analytics Foundation

Store enough information to later calculate:

```text
AI approval overturned by human

AI rejection overturned by human

issue false-positive rate

issue false-negative indicators

Vision provider accuracy

model quality by issue type
```

Do not implement the full analytics dashboard yet.

TASK-10 can consume this data later.

---

# 65. QA Dashboard

Add dashboard metrics:

```text
Pending QA

Needs Human Review

Approved Today

Rejected Today

AI Auto-Approval Rate

Human Override Rate

Average QA Duration

QA Cost Today
```

Do not present an aggregate quality score as objective truth.

---

# 66. Provider Quality Metrics

Prepare metrics allowing later comparison:

```text
Generation provider/model
        ↓
artifact frequency

Generation provider/model
        ↓
human rejection rate

Prompt version
        ↓
prompt compliance

Vision provider/model
        ↓
human override rate
```

This becomes important for future provider routing.

---

# 67. Metrics

Expose metrics compatible with existing observability infrastructure.

At minimum:

```text
media_factory_qa_total

media_factory_qa_approved_total

media_factory_qa_rejected_total

media_factory_qa_needs_review_total

media_factory_qa_execution_failed_total

media_factory_qa_duration

media_factory_qa_findings_total

media_factory_qa_human_override_total

media_factory_vision_requests_total

media_factory_vision_failures_total

media_factory_vision_cost
```

Safe tags:

```text
pipeline
policy
provider
model
decision
finding_code
severity
```

Avoid high-cardinality labels:

```text
assetId
generationId
prompt
reviewId
```

---

# 68. Structured Logging

Log important lifecycle events:

```text
QA started

technical QA completed

visual QA started

visual QA completed

policy evaluated

automatic decision produced

human review completed

human override applied

regeneration requested
```

Include safe identifiers:

```text
assetId
generationId
reviewId
provider
model
decision
duration
```

Do not log:

```text
image binary

API credentials

authorization headers

huge provider responses
```

Avoid logging full prompts at INFO level.

---

# 69. Vision Response Storage

Do not blindly persist huge raw API responses.

Persist normalized structured results.

If raw response storage is useful for debugging:

* make it optional;
* sanitize it;
* apply retention policy;
* avoid secrets;
* document storage implications.

Normalized QA findings remain the authoritative application model.

---

# 70. Cost Protection

QA automation can generate significant cost at scale.

Add configurable limits where compatible with current architecture:

```text
max visual QA calls per generation

max retries

max fallback attempts
```

Avoid accidentally sending the same image repeatedly to Vision providers.

Reuse completed QA results unless a new review is explicitly requested.

---

# 71. Re-Run QA

Support explicit:

```text
RE-RUN QA
```

but create a new `QualityReview`.

Do not overwrite historical review.

Example:

```text
Asset #123

Review #1
Policy v1
Vision Model A

Review #2
Policy v2
Vision Model B
```

Mark which review is current/effective.

---

# 72. QA Cache / Idempotency

If the same QA job is accidentally executed twice:

```text
same asset
same QA profile/version
same Vision model
same prompt snapshot
```

avoid unnecessary duplicate paid calls where reasonably possible.

Use existing job idempotency architecture.

Do not make cache identity so broad that changed policies or models incorrectly reuse old results.

---

# 73. Database

Create Flyway migrations.

Likely additions/refactors:

```text
quality_review

quality_finding

quality_dimension_result

quality_policy / policy metadata if persisted

vision_qa_attempt

human_review_action

generation lineage
```

Potential indexes:

```text
quality_review.asset_id

quality_review.decision

quality_review.execution_status

quality_review.created_at

quality_finding.quality_review_id

quality_finding.code

quality_finding.severity

quality_dimension_result.quality_review_id

generation.parent_generation_id
```

Use project naming conventions.

---

# 74. Human Review Audit

Create immutable review action history.

Example:

```text
HumanReviewAction

id

reviewId

action

previousDecision
newDecision

reasonCode
reasonText

actor

createdAt
```

Actions:

```text
APPROVE
REJECT
OVERRIDE_APPROVAL
OVERRIDE_REJECTION
REQUEST_REGENERATION
ADD_FINDING
```

Never lose prior human actions.

---

# 75. Security

Review endpoints are operationally sensitive.

Reuse existing authentication/authorization.

If roles already exist, consider permissions such as:

```text
QA_VIEW

QA_REVIEW

QA_OVERRIDE
```

Do not invent an entire auth system if none exists.

At minimum keep authorization architecture extensible.

---

# 76. Mock Vision Provider

Implement a powerful deterministic Mock provider.

It must support scenarios such as:

```text
PERFECT

MINOR_ARTIFACT

MAJOR_ARTIFACT

MALFORMED_FACE

UNWANTED_TEXT

WATERMARK

LOW_PROMPT_COMPLIANCE

UNCERTAIN

RATE_LIMIT

TIMEOUT

INVALID_RESPONSE

PROVIDER_ERROR
```

This provider is essential for tests.

No paid API should be required to exercise the entire QA workflow.

---

# 77. Unit Tests

Add tests for:

```text
technical QA

issue classification

severity handling

dimension aggregation

policy thresholds

confidence thresholds

automatic approval

automatic rejection

needs-review decision

QA execution failure

human approval

human rejection

human override

override reason validation

regeneration lineage

publishing gate

QA profile selection

Vision response validation

Vision retry behavior

Vision fallback behavior

QA idempotency
```

---

# 78. Policy Tests

Create explicit tests such as:

```text
CRITICAL + confidence 0.98
→ REJECTED
```

```text
MAJOR + confidence 0.55
→ NEEDS_REVIEW
```

```text
all dimensions above thresholds
+ no blocking findings
→ APPROVED
```

```text
Vision provider unavailable
→ NOT automatically REJECTED
```

Policy tests must be deterministic.

---

# 79. Human Override Tests

Test:

```text
AI REJECTED
↓
Human APPROVED
↓
finalDecision = APPROVED
automaticDecision remains REJECTED
override stored
```

And:

```text
AI APPROVED
↓
Human REJECTED
↓
finalDecision = REJECTED
automaticDecision remains APPROVED
override stored
```

---

# 80. Integration Tests

Test complete pipelines.

### Successful QA

```text
Mock Generation
↓
Asset
↓
Technical QA
↓
Mock Vision QA
↓
APPROVED
```

### Automatic rejection

```text
Asset
↓
Critical finding
↓
REJECTED
```

### Uncertain case

```text
Asset
↓
uncertain major finding
↓
NEEDS_REVIEW
↓
Human APPROVED
```

### Human rejection

```text
AI APPROVED
↓
Human REJECTED
```

### Regeneration

```text
REJECTED
↓
Regenerate
↓
new Generation
↓
parent lineage preserved
```

### Vision retry

```text
429
↓
retry
↓
success
```

### Vision fallback

```text
Provider A fails
↓
Provider B
↓
success
```

### QA infrastructure failure

```text
all Vision providers fail
↓
QA execution failure
↓
asset NOT incorrectly rejected
```

Automated tests must not call paid APIs.

---

# 81. Performance Tests

Create a lightweight test or documented verification for:

```text
100 generated assets
↓
100 QA jobs
```

Verify:

```text
provider concurrency limits respected

no uncontrolled thread creation

no long DB transactions

review queue remains responsive
```

Do not create expensive AI calls.

Use mocks.

---

# 82. Documentation

Create/update:

```text
docs/quality-assurance.md

docs/qa-policies.md

docs/visual-qa.md

docs/human-review.md

docs/regeneration.md
```

---

# 83. Quality Assurance Documentation

Explain:

```text
Asset
↓
Technical QA
↓
Visual QA
↓
Policy
↓
Decision
↓
Human Review
```

Document difference between:

```text
finding
severity
score
confidence
decision
```

This distinction is important.

---

# 84. QA Policy Documentation

Document:

```text
profiles

thresholds

critical findings

confidence rules

auto-approval

auto-rejection

human review
```

Include Wallpaper profile example.

---

# 85. Visual QA Documentation

Document:

```text
Vision provider architecture

structured output schema

prompt compliance

artifact detection

anatomy evaluation

provider fallback

cost tracking

limitations
```

Explicitly state that Vision Model judgments are probabilistic.

---

# 86. Human Review Documentation

Document:

```text
review queue

approve

reject

override

reason codes

audit history

batch review

keyboard shortcuts
```

---

# 87. Regeneration Documentation

Explain lineage:

```text
Generation 100
      ↓
Rejected Asset
      ↓
Regeneration
      ↓
Generation 101
```

Historical generations/assets must remain immutable.

---

# 88. Definition of Done

TASK-04 is complete when this workflow works:

```text
Image Generation
       ↓
Asset
       ↓
Technical QA
       ↓
Visual AI QA
       ↓
Structured Findings
       ↓
QA Policy
       ↓
APPROVED
```

And:

```text
Image Generation
       ↓
Visual artifact
       ↓
Finding
       ↓
REJECTED
       ↓
Regenerate
       ↓
New Generation
```

And:

```text
AI uncertain
       ↓
NEEDS_REVIEW
       ↓
Human reviewer
       ↓
APPROVED
```

And:

```text
AI rejects
       ↓
Human determines intentional style
       ↓
Override
       ↓
APPROVED
```

with complete audit history.

---

# 89. Verification

Before completing TASK-04:

1. run backend unit tests;
2. run integration tests;
3. run frontend tests;
4. run backend production build;
5. run frontend production build;
6. apply Flyway migrations;
7. generate an image using Mock provider;
8. verify automatic QA starts;
9. verify technical findings;
10. verify structured Vision findings;
11. verify dimension scores;
12. verify confidence values;
13. verify policy decision;
14. verify automatic approval;
15. verify automatic rejection;
16. verify `NEEDS_REVIEW`;
17. approve manually;
18. reject manually;
19. override AI rejection;
20. override AI approval;
21. verify audit history;
22. request regeneration;
23. verify new Generation;
24. verify generation lineage;
25. simulate Vision 429;
26. verify retry;
27. simulate Vision timeout;
28. verify fallback;
29. simulate all Vision providers unavailable;
30. verify asset is not falsely rejected;
31. verify publishing gate;
32. verify QA cost tracking;
33. verify no credentials appear in logs/API;
34. verify batch review;
35. verify old QualityReview records remain readable.

---

# 90. Final Codex Report

At completion provide:

## Architecture

Describe:

```text
Asset
→ QA Orchestrator
→ Technical QA
→ Visual QA
→ Findings
→ Policy
→ Decision
→ Human Review
```

## Domain Changes

List new/refactored entities.

## Database

List migrations and indexes.

## Technical QA

List implemented deterministic checks.

## Visual QA

Document:

```text
provider
model
structured schema
dimensions
issue taxonomy
```

## Policy Engine

Explain:

```text
thresholds
severity
confidence
auto-approval
auto-rejection
needs-review
```

## Human Review

Explain:

```text
approve
reject
override
audit
batch operations
```

## Regeneration

Explain generation lineage and prompt reuse.

## Resilience

Report:

```text
rate limiting
concurrency
timeout
retry
fallback
```

## Cost Tracking

Explain Vision QA cost accounting.

## Frontend

List implemented review workflows.

## Tests

Report:

```text
unit tests
integration tests
frontend tests
build status
```

## Real Vision Verification

If credentials are configured, manually test one real Vision review.

If not, explicitly report:

```text
Real Vision provider implementation completed,
but live Vision QA was not executed because
credentials were unavailable.
```

Never claim live verification unless it actually occurred.

## Remaining Limitations

List deferred functionality and known limitations.

---

# Engineering Principles

Throughout TASK-04 follow these rules:

1. Detection and policy are separate concerns.
2. Technical checks should be deterministic whenever possible.
3. Vision Model judgments are probabilistic.
4. Never represent Vision confidence as certainty.
5. Never reduce quality to one arbitrary score.
6. Preserve individual quality dimensions.
7. Preserve every QA finding.
8. Preserve the automatic decision after human override.
9. Human decisions must be auditable.
10. Historical reviews are immutable.
11. Re-running QA creates a new review.
12. Rejected assets are never overwritten.
13. Regeneration creates a new Generation.
14. Preserve generation lineage.
15. Use the exact TASK-03 prompt snapshot for prompt compliance.
16. Do not dynamically reconstruct historical prompts.
17. Vision provider outages are not content failures.
18. Never silently approve because QA infrastructure failed.
19. Never silently reject because QA infrastructure failed.
20. Provider-specific Vision logic belongs in adapters.
21. QA policies belong outside provider implementations.
22. Thresholds must be configurable.
23. Publishing must enforce QA approval server-side.
24. Vision QA costs must be tracked.
25. Automated tests must never consume paid AI APIs.
26. Do not hold DB transactions open during Vision API calls.
27. Avoid unnecessary repeat Vision calls.
28. Do not introduce OCR unless it materially improves the solution.
29. Keep issue codes stable for future analytics.
30. Build TASK-04 so human feedback can later improve provider routing and QA models.

The result of TASK-04 should turn Media Factory QA from a simple pass/fail check into a structured production quality-control system capable of automatically processing large image volumes while escalating ambiguous cases to a human reviewer.
