# TASK-03 — Production Prompt Engine

## Objective

Build a production-ready Prompt Engine for Media Factory.

The Prompt Engine must become the single authoritative layer responsible for constructing prompts used by image-generation pipelines.

Implement:

* reusable prompt templates;
* immutable prompt versions;
* typed variables;
* variable validation;
* default values;
* required/optional variables;
* positive prompts;
* negative prompts;
* reusable style presets;
* prompt composition;
* provider-specific adaptation;
* complete prompt history;
* exact generation reproducibility;
* prompt preview/rendering;
* prompt validation;
* A/B prompt experiments;
* weighted variant allocation;
* experiment lifecycle;
* generation-to-variant attribution;
* future analytics integration;
* auditability.

The core principle is:

```text
Concept
   ↓
Prompt Template
   ↓
Prompt Version
   ↓
Variables
   ↓
Style / Presets
   ↓
Experiment Variant
   ↓
Provider Adaptation
   ↓
Rendered Prompt Snapshot
   ↓
Generation
```

A Generation must always preserve the exact final prompt that was actually sent to the provider.

---

# 1. First Inspect Existing Architecture

Before changing code:

1. inspect TASK-01 and TASK-02 implementation;
2. understand existing `Concept`, `Generation`, provider routing and generation request models;
3. locate any existing prompt fields or prompt-building logic;
4. reuse/refactor existing abstractions;
5. do not create a second parallel generation architecture;
6. preserve existing API compatibility where practical;
7. preserve existing tests.

Search specifically for logic such as:

```text
generation.setPrompt(...)
request.prompt(...)
String.format(...)
prompt + "..."
provider-specific prompt manipulation
```

Prompt construction must no longer be scattered across services after this task.

Document important architectural changes.

---

# 2. Target Architecture

Implement this logical flow:

```text
Concept
   │
   ▼
PromptTemplate
   │
   ▼
PromptVersion
   │
   ├── positive template
   ├── negative template
   └── variable schema
   │
   ▼
PromptRenderRequest
   │
   ├── variables
   ├── presets
   └── experiment context
   │
   ▼
Prompt Engine
   │
   ├── validation
   ├── composition
   ├── experiment resolution
   └── rendering
   │
   ▼
Canonical Prompt
   │
   ▼
Provider Prompt Adapter
   │
   ▼
RenderedPromptSnapshot
   │
   ▼
Generation
   │
   ▼
Image Provider
```

Prompt Engine must remain separate from provider execution.

---

# 3. Core Domain Model

Introduce/refine the following concepts:

```text
PromptTemplate
PromptVersion
PromptVariableDefinition
PromptPreset
PromptExperiment
PromptExperimentVariant
RenderedPromptSnapshot
```

Relationships:

```text
PromptTemplate
      │
      ├── Version 1
      ├── Version 2
      └── Version 3

PromptVersion
      │
      ├── Variable Definitions
      └── Presets / compatible presets

PromptExperiment
      │
      ├── Variant A → PromptVersion 2
      └── Variant B → PromptVersion 3

Generation
      │
      ├── PromptVersion
      ├── Experiment
      ├── ExperimentVariant
      └── RenderedPromptSnapshot
```

---

# 4. Prompt Template

`PromptTemplate` represents the logical prompt identity.

Example:

```text
Name:
Premium Animal Wallpaper

Key:
premium-animal-wallpaper

Description:
Creates premium vertical animal wallpapers.

Category:
WALLPAPER
```

Suggested fields:

```text
id
key
name
description
category

status

createdAt
updatedAt
createdBy
```

Possible statuses:

```text
ACTIVE
ARCHIVED
```

Do not store mutable production prompt content directly on `PromptTemplate`.

Actual content belongs to `PromptVersion`.

---

# 5. Immutable Prompt Versions

Every meaningful prompt modification creates a new version.

Example:

```text
Premium Animal Wallpaper

v1
v2
v3
v4
```

Never modify an already-used production version.

Suggested fields:

```text
id

promptTemplateId

version

positiveTemplate
negativeTemplate

variableSchema

changeDescription

createdAt
createdBy

publishedAt

status
```

Possible statuses:

```text
DRAFT
PUBLISHED
DEPRECATED
```

Rules:

```text
DRAFT
→ editable

PUBLISHED
→ immutable

DEPRECATED
→ immutable
```

A published version must never be modified in place.

---

# 6. Version Creation

Editing a published version should create:

```text
v7
   ↓
copy
   ↓
v8 DRAFT
```

Then:

```text
edit
↓
validate
↓
publish
```

Do not silently mutate v7.

Use optimistic locking where appropriate to prevent conflicting edits.

---

# 7. Positive Prompt Template

Example:

```text
Create a premium cinematic portrait of {{subject}}.

The subject is {{pose}}.

Visual style:
{{visualStyle}}

Lighting:
{{lighting}}

Environment:
{{environment}}

Primary colors:
{{primaryColors}}

Composition:
centered composition,
strong visual hierarchy,
premium cinematic aesthetic.

Target medium:
{{targetMedium}}
```

Template syntax should be intentionally limited and safe.

Do not introduce arbitrary executable scripting.

---

# 8. Template Engine

Implement a dedicated abstraction:

```java
public interface PromptTemplateRenderer {

    RenderedTemplate render(
        String template,
        Map<String, Object> variables
    );
}
```

Choose a suitable template implementation.

Requirements:

* deterministic output;
* safe variable substitution;
* no arbitrary code execution;
* clear missing-variable errors;
* testability;
* predictable whitespace behavior.

Do not use raw `String.replace()` scattered through application services.

---

# 9. Typed Variables

Variables require definitions.

Example:

```json
{
  "name": "subject",
  "type": "STRING",
  "required": true
}
```

Supported initial types:

```text
STRING
INTEGER
DECIMAL
BOOLEAN
ENUM
STRING_LIST
```

Architecture should allow additional types later without redesigning the database.

---

# 10. Variable Definition

Suggested model:

```text
name
label
description

type

required
defaultValue

allowedValues

min
max

minLength
maxLength

displayOrder
```

Example:

```json
{
  "name": "lighting",
  "label": "Lighting",
  "type": "ENUM",
  "required": true,
  "defaultValue": "CINEMATIC",
  "allowedValues": [
    "CINEMATIC",
    "SOFT",
    "DRAMATIC",
    "NEON",
    "NATURAL"
  ]
}
```

---

# 11. Variable Validation

Before rendering:

```text
PromptRenderRequest
        ↓
VariableValidator
        ↓
valid?
```

Validate:

* required values;
* type;
* enum membership;
* length;
* numeric bounds;
* unknown variables;
* default values.

Errors should identify the exact variable.

Example API response:

```json
{
  "code": "PROMPT_VARIABLE_INVALID",
  "variable": "lighting",
  "message": "Value 'LASER' is not allowed."
}
```

Do not silently ignore invalid variables.

---

# 12. Variable Snapshot

Generation must preserve the variables actually used.

Store:

```json
{
  "subject": "cybernetic wolf",
  "pose": "looking directly at the viewer",
  "lighting": "NEON",
  "environment": "dark futuristic atmosphere"
}
```

Later changes to defaults must not change historical generations.

---

# 13. Negative Prompts

Support negative prompts as first-class data.

Example:

```text
blurry,
low resolution,
poor anatomy,
duplicate limbs,
unwanted text,
watermark,
logo,
cropped head
```

Negative prompts must:

* be versioned;
* be rendered with variables when needed;
* be included in prompt snapshots;
* support provider adaptation.

Do not assume every provider has a dedicated negative-prompt field.

---

# 14. Canonical Prompt Model

Prompt Engine should produce provider-neutral output first.

Example:

```java
public record CanonicalPrompt(
    String positivePrompt,
    String negativePrompt,
    Map<String, Object> variables,
    List<String> appliedPresetIds
) {}
```

Provider logic comes afterwards.

---

# 15. Provider Prompt Adaptation

TASK-02 established provider abstraction.

Now add:

```java
public interface ProviderPromptAdapter {

    String providerId();

    AdaptedPrompt adapt(
        CanonicalPrompt prompt,
        ProviderCapabilities capabilities
    );
}
```

Example:

```text
Canonical Prompt
      │
      ├── OpenAI adapter
      ├── Gemini adapter
      └── future providers
```

If a provider supports a dedicated negative prompt:

```text
positive → positive field
negative → negative field
```

If it does not:

the adapter may transform the canonical prompt according to an explicit documented strategy.

Do not silently discard negative prompts.

---

# 16. Adaptation Transparency

Store what happened during adaptation.

Example:

```json
{
  "provider": "provider-a",
  "strategy": "NEGATIVE_PROMPT_SEPARATE_FIELD",
  "warnings": []
}
```

Or:

```json
{
  "provider": "provider-b",
  "strategy": "NEGATIVE_CONSTRAINTS_MERGED",
  "warnings": [
    "Provider does not support native negative prompts."
  ]
}
```

This is necessary for reproducibility and debugging.

---

# 17. Rendered Prompt Snapshot

This is one of the most important parts of TASK-03.

Before calling a provider, create an immutable snapshot containing:

```text
templateId
templateVersionId
templateVersion

variables

presets

canonicalPositivePrompt
canonicalNegativePrompt

provider

adaptedPositivePrompt
adaptedNegativePrompt

adaptationStrategy

experimentId
experimentVariantId

renderedAt
```

The snapshot must represent exactly what was used for generation.

---

# 18. Reproducibility

A historical generation must remain understandable even if:

```text
template changed
preset changed
provider adapter changed
defaults changed
experiment ended
```

Therefore never reconstruct historical prompts dynamically.

Historical generation UI/API must read its stored snapshot.

---

# 19. Prompt History

Provide complete history:

```text
Premium Animal Wallpaper

v1  initial version
v2  improved composition
v3  stronger lighting instructions
v4  changed negative prompt
v5  experiment candidate
```

Expose:

```http
GET /api/v1/prompt-templates/{id}/versions
```

and:

```http
GET /api/v1/prompt-versions/{id}
```

---

# 20. Version Comparison

Implement prompt version diff.

Example UI:

```text
VERSION 7                 VERSION 8

cinematic lighting        cinematic rim lighting
                           + volumetric atmosphere

dark background           dark background
```

Compare at minimum:

```text
positive template
negative template
variable definitions
```

Backend may expose structured diff data or frontend may calculate the presentation diff.

Choose the simplest maintainable approach.

---

# 21. Prompt Presets

Introduce reusable presets.

Examples:

```text
AMOLED
CINEMATIC
NEON
MINIMAL
PHOTOREALISTIC
FANTASY
DARK_LUXURY
```

A preset should contain reusable prompt fragments/configuration.

Example:

```text
Preset: AMOLED

Positive:
deep OLED black background,
high subject separation,
controlled highlights

Negative:
gray background,
washed blacks,
overexposure
```

---

# 22. Preset Model

Suggested fields:

```text
id
key
name
description

positiveFragment
negativeFragment

category

status

createdAt
updatedAt
```

Consider versioning presets if they are used in production.

Preferred design:

```text
PromptPreset
    ↓
PromptPresetVersion
```

The generation snapshot must preserve exact applied preset content regardless.

---

# 23. Prompt Composition

Prompt composition must be deterministic.

Example order:

```text
BASE TEMPLATE

↓

STYLE PRESET

↓

PIPELINE CONSTRAINTS

↓

USER OVERRIDES
```

Avoid random string concatenation across services.

Create:

```java
PromptComposer
```

Composition order must be documented and tested.

---

# 24. Pipeline Constraints

Allow pipelines to contribute prompt constraints.

Example Wallpaper pipeline:

```text
vertical composition
subject centered
safe lock-screen composition
no UI
no text
```

Stock pipeline:

```text
commercial composition
clean framing
no trademarks
no visible logos
```

These constraints should not require duplicating complete templates.

---

# 25. Prompt Preview

Implement preview without generating an image.

Endpoint:

```http
POST /api/v1/prompts/render
```

Request:

```json
{
  "promptVersionId": "...",
  "variables": {
    "subject": "wolf",
    "lighting": "NEON"
  },
  "presets": [
    "AMOLED",
    "CINEMATIC"
  ],
  "provider": "provider-a"
}
```

Response:

```json
{
  "canonical": {
    "positivePrompt": "...",
    "negativePrompt": "..."
  },
  "providerAdaptation": {
    "positivePrompt": "...",
    "negativePrompt": "...",
    "warnings": []
  }
}
```

Preview must not create a paid generation.

---

# 26. Prompt Validation

Add explicit validation endpoint or include validation in preview.

Validate:

```text
template syntax
missing variables
unused variable definitions
undefined template variables
invalid defaults
duplicate variable names
invalid presets
provider incompatibilities
```

Publishing an invalid prompt version must be impossible.

---

# 27. Prompt Linting

Implement lightweight prompt linting.

Possible warnings:

```text
extremely long prompt
duplicate phrases
conflicting instructions
undefined variable
defined but unused variable
empty negative prompt
provider length concern
```

Warnings should normally not prevent publishing unless correctness is affected.

Keep linting deterministic.

Do not introduce an LLM dependency merely to lint prompts in TASK-03.

---

# 28. Prompt Experiments

Implement A/B prompt experiments.

Core model:

```text
PromptExperiment
      │
      ├── Variant A
      └── Variant B
```

Support more than two variants architecturally, even though the UI can initially focus on A/B.

---

# 29. Experiment Model

Suggested fields:

```text
id
name
description

status

scope

startedAt
endedAt

createdAt
createdBy
```

Statuses:

```text
DRAFT
RUNNING
PAUSED
COMPLETED
CANCELLED
```

---

# 30. Experiment Variant

Suggested fields:

```text
id

experimentId

key

name

promptVersionId

weight

createdAt
```

Example:

```text
A
Prompt v7
50%

B
Prompt v8
50%
```

Weights must validate to the required total.

Do not use floating-point arithmetic carelessly for allocation percentages.

---

# 31. Experiment Scope

Experiments must be scoped.

Initial supported scopes:

```text
PROMPT_TEMPLATE
COLLECTION
PIPELINE
```

Example:

```text
Experiment:
Cyber Wolf Lighting

Scope:
Collection = Cyber Wolves
```

Only eligible generations participate.

---

# 32. Deterministic Variant Assignment

Do not use naive:

```java
Math.random()
```

for experiment assignment.

Use deterministic assignment based on a stable identifier.

Conceptually:

```text
hash(experimentId + assignmentKey)
        ↓
bucket 0..9999
        ↓
variant
```

Benefits:

* repeatable assignment;
* easier debugging;
* no accidental variant switching.

Choose the appropriate stable assignment key based on the existing generation model.

Document it.

---

# 33. Weighted Experiments

Support:

```text
A = 50%
B = 50%
```

and:

```text
A = 80%
B = 20%
```

Architecture should support:

```text
A = 40%
B = 30%
C = 30%
```

even if initial UI focuses on A/B.

---

# 34. Experiment Attribution

Every participating generation must store:

```text
experimentId
experimentVariantId
```

This attribution must never change after assignment.

Relationship:

```text
Experiment
    ↓
Variant
    ↓
Generation
    ↓
Asset
    ↓
Publication
    ↓
PerformanceMetric
```

This prepares TASK-10 analytics for actual experiment analysis.

---

# 35. Experiment Metrics Foundation

TASK-03 does NOT need to implement full performance analytics.

But create the data relationships needed for future metrics such as:

```text
approval rate
rejection rate
generation cost
downloads
views
likes
CTR
revenue
```

Do not invent an automatic "winner" in TASK-03.

---

# 36. Experiment Integrity

Once an experiment is `RUNNING`:

Do not allow silent modification of:

```text
variant prompt versions
assignment logic
weights
scope
```

Preferred behavior:

```text
RUNNING experiment
→ configuration immutable
```

To change configuration:

```text
stop/cancel
→ create new experiment
```

This preserves statistical integrity.

---

# 37. Experiment Lifecycle

Implement:

```text
DRAFT
  ↓
RUNNING
  ↓
PAUSED
  ↓
RUNNING

RUNNING
  ↓
COMPLETED

DRAFT/RUNNING/PAUSED
  ↓
CANCELLED
```

Validate illegal transitions.

---

# 38. Manual Prompt Override

Allow controlled manual override where required.

Example:

```text
Generation Request

promptVersion = v7

variables = {...}

manualPositiveSuffix = "..."
```

However:

* overrides must be visible;
* overrides must be stored in snapshot;
* overridden generations must remain reproducible;
* experiments should define whether overrides are allowed.

Do not mutate the underlying template.

---

# 39. Prompt Length

Providers may have different prompt limitations.

Prompt Engine should calculate:

```text
character count
```

and where practical:

```text
estimated token count
```

Provider adapter may return warnings/errors when limits are exceeded.

Do not silently truncate prompts unless a provider-specific policy explicitly allows it.

If truncation ever occurs, record it in adaptation metadata.

---

# 40. API — Templates

Implement REST endpoints consistent with existing project conventions.

Required operations:

```text
POST   /api/v1/prompt-templates
GET    /api/v1/prompt-templates
GET    /api/v1/prompt-templates/{id}
PATCH  /api/v1/prompt-templates/{id}
```

Archiving should be preferred over destructive deletion once templates have history.

---

# 41. API — Versions

Implement:

```text
POST /api/v1/prompt-templates/{id}/versions

GET /api/v1/prompt-templates/{id}/versions

GET /api/v1/prompt-versions/{id}

POST /api/v1/prompt-versions/{id}/publish

POST /api/v1/prompt-versions/{id}/deprecate
```

Follow existing REST conventions where they differ.

---

# 42. API — Presets

Implement:

```text
POST /api/v1/prompt-presets

GET /api/v1/prompt-presets

GET /api/v1/prompt-presets/{id}

PATCH /api/v1/prompt-presets/{id}
```

Apply the same history/reproducibility principles to production presets.

---

# 43. API — Experiments

Implement:

```text
POST /api/v1/prompt-experiments

GET /api/v1/prompt-experiments

GET /api/v1/prompt-experiments/{id}

POST /api/v1/prompt-experiments/{id}/start

POST /api/v1/prompt-experiments/{id}/pause

POST /api/v1/prompt-experiments/{id}/complete

POST /api/v1/prompt-experiments/{id}/cancel
```

Expose variants and configuration safely.

---

# 44. Generation API Integration

Extend the existing image-generation request.

Instead of requiring only raw prompt:

```json
{
  "prompt": "..."
}
```

support:

```json
{
  "conceptId": "...",

  "promptVersionId": "...",

  "variables": {
    "subject": "cybernetic wolf",
    "lighting": "NEON"
  },

  "presets": [
    "AMOLED"
  ],

  "provider": null
}
```

Maintain raw prompt support only if required for backwards compatibility or intentional ad-hoc generation.

If raw prompts remain supported, store them as explicit ad-hoc prompt snapshots.

---

# 45. Generation Execution Integration

Update TASK-02 flow.

Old:

```text
Generation
↓
Provider Router
↓
Provider
```

New:

```text
Generation
      ↓
Prompt Resolution
      ↓
Experiment Assignment
      ↓
Variable Validation
      ↓
Prompt Composition
      ↓
Canonical Prompt
      ↓
Provider Router
      ↓
Provider Prompt Adapter
      ↓
RenderedPromptSnapshot
      ↓
Provider
```

Be careful with ordering:

provider routing may affect provider-specific prompt adaptation.

The canonical prompt must exist independently of provider selection.

---

# 46. Retry Behavior

A retry of the same provider attempt must normally reuse the same rendered prompt snapshot.

Do NOT re-render using current template state.

Example:

```text
Generation created
↓
snapshot produced
↓
provider timeout
↓
retry

USE SAME SNAPSHOT
```

This is required for reproducibility.

---

# 47. Fallback Behavior

TASK-02 supports fallback providers.

On fallback:

```text
canonical prompt snapshot
        ↓
new provider adapter
        ↓
provider-specific adapted prompt
```

Preserve:

```text
same canonical prompt
```

but allow different provider adaptation.

Store adaptation per provider attempt if necessary.

This allows debugging:

```text
Attempt 1
Provider A
Adaptation A

Attempt 2
Provider B
Adaptation B
```

---

# 48. Database Design

Create Flyway migrations.

Likely tables:

```text
prompt_template

prompt_version

prompt_variable_definition

prompt_preset

prompt_preset_version

prompt_experiment

prompt_experiment_variant

rendered_prompt_snapshot
```

Potential Generation additions:

```text
prompt_version_id
prompt_snapshot_id
experiment_id
experiment_variant_id
```

Follow existing schema conventions.

Do not edit already-applied Flyway migrations.

Use JSON/JSONB only where flexible structured data is appropriate.

Do not put the entire domain model into JSON merely for convenience.

---

# 49. Indexing

Add useful indexes.

Examples:

```text
prompt_template.key

prompt_version.prompt_template_id

prompt_experiment.status

prompt_experiment_variant.experiment_id

generation.prompt_version_id

generation.experiment_id

generation.experiment_variant_id
```

Use unique constraints where required.

Example:

```text
(prompt_template_id, version)
```

must be unique.

---

# 50. Frontend — Prompt Library

Create a new navigation section:

```text
PROMPTS
```

Pages:

```text
Prompt Library
Prompt Editor
Prompt History
Presets
Experiments
```

Prompt Library should display:

```text
Premium Animal Wallpaper

Current: v8

Category:
Wallpaper

Status:
ACTIVE

Last updated:
...

Experiments:
1 running
```

---

# 51. Frontend — Prompt Editor

Create a proper prompt editor.

Suggested layout:

```text
┌──────────────────────────────────────────────┐
│ Premium Animal Wallpaper             v9 DRAFT│
├───────────────────────┬──────────────────────┤
│                       │ VARIABLES            │
│ POSITIVE TEMPLATE     │                      │
│                       │ subject      STRING  │
│ Create a premium...   │ lighting     ENUM    │
│ {{subject}}            │ environment STRING  │
│                       │                      │
│ {{lighting}}           │ [+ Add variable]    │
│                       │                      │
├───────────────────────┼──────────────────────┤
│ NEGATIVE TEMPLATE     │ PRESETS              │
│                       │                      │
│ blurry, {{...}}       │ AMOLED               │
│                       │ CINEMATIC             │
└───────────────────────┴──────────────────────┘
```

Support clear variable insertion into templates.

---

# 52. Live Preview

Prompt Editor should provide live preview.

Example:

```text
VARIABLES

subject:
Cybernetic wolf

lighting:
Neon

environment:
Dark futuristic city
```

Then:

```text
RENDERED PROMPT

Create a premium cinematic portrait of
a cybernetic wolf...

Lighting:
Neon...

Environment:
Dark futuristic city...
```

Also display:

```text
NEGATIVE PROMPT
```

No image generation is needed for preview.

---

# 53. Provider Preview

Allow selecting:

```text
Canonical
Provider A
Provider B
```

and display differences caused by provider adaptation.

Example:

```text
Canonical

Negative:
watermark, text, blurry
```

versus:

```text
Provider B

Constraint appended:
Avoid watermark, visible text and blurry details.
```

This makes provider behavior transparent.

---

# 54. Frontend — History

Create version timeline:

```text
v9  DRAFT
│
v8  PUBLISHED
│
v7  DEPRECATED
│
v6
│
...
```

Allow:

```text
View
Compare
Create draft from version
```

Never allow editing historical published versions.

---

# 55. Frontend — Diff

Example:

```text
v7 → v8

POSITIVE

- dramatic lighting
+ cinematic rim lighting
+ subtle volumetric atmosphere

NEGATIVE

+ oversaturated colors
+ malformed eyes

VARIABLES

+ eyeColor
```

Use readable semantic presentation where practical.

---

# 56. Frontend — Experiments

Create:

```text
PROMPT EXPERIMENTS
```

Example:

```text
Cyber Wolf Lighting

Status
RUNNING

Scope
Cyber Wolves Collection

Variant A
Prompt v7
50%

Variant B
Prompt v8
50%

Generations

A    483
B    491
```

Do not claim a statistically valid winner in TASK-03.

---

# 57. Auditability

Important prompt operations should be traceable.

At minimum preserve:

```text
createdAt
createdBy

publishedAt
publishedBy

deprecatedAt
deprecatedBy
```

if the existing authentication architecture supports user identity.

Do not invent authentication infrastructure solely for this task if TASK-01 does not have it.

---

# 58. Unit Tests

Add comprehensive tests for:

```text
template rendering

required variables

default variables

enum validation

numeric validation

unknown variables

positive prompt rendering

negative prompt rendering

preset composition

composition order

provider adaptation

version immutability

draft editing

published version protection

prompt snapshots

deterministic experiment assignment

weighted assignment

experiment lifecycle

experiment immutability

manual overrides

retry snapshot reuse

fallback provider adaptation
```

---

# 59. Deterministic Experiment Tests

For a fixed:

```text
experimentId
assignmentKey
weights
```

variant assignment must always produce the same result.

Test distribution over a sufficiently large deterministic input set.

For example:

```text
A = 50%
B = 50%
```

The test should verify reasonable allocation behavior without relying on random execution.

Avoid flaky statistical tests.

---

# 60. Integration Tests

Test complete flows.

### Template flow

```text
create template
↓
create draft version
↓
publish
↓
render
```

### Generation flow

```text
Concept
↓
PromptVersion
↓
variables
↓
render
↓
provider adaptation
↓
snapshot
↓
Mock Provider
↓
Asset
```

### Experiment flow

```text
RUNNING experiment
↓
generation
↓
variant assigned
↓
prompt rendered
↓
generation attributed
```

### Retry flow

```text
render snapshot
↓
provider failure
↓
retry
↓
same snapshot
```

### Fallback flow

```text
canonical prompt
↓
Provider A adaptation
↓
failure
↓
Provider B adaptation
↓
success
```

No integration test should consume paid API resources.

---

# 61. Migration Compatibility

Existing generations created before TASK-03 must remain readable.

Do not break historical records.

If they contain only raw prompts, represent them as:

```text
LEGACY / AD_HOC prompt
```

or equivalent.

Do not invent template/version attribution for old generations.

---

# 62. Documentation

Create:

```text
docs/prompt-engine.md
docs/prompt-versioning.md
docs/prompt-presets.md
docs/prompt-experiments.md
```

Document the full pipeline:

```text
Template
↓
Version
↓
Variables
↓
Preset
↓
Canonical Prompt
↓
Provider Adaptation
↓
Snapshot
↓
Generation
```

Include examples.

---

# 63. Prompt Engine Documentation

`docs/prompt-engine.md` must explain:

* architecture;
* template syntax;
* variable types;
* validation;
* composition order;
* negative prompts;
* provider adaptation;
* snapshots;
* generation integration.

---

# 64. Versioning Documentation

`docs/prompt-versioning.md` must explain:

```text
DRAFT
PUBLISHED
DEPRECATED
```

and why published versions are immutable.

Include:

```text
v1
↓
v2
↓
v3
```

historical generation behavior.

---

# 65. Experiment Documentation

`docs/prompt-experiments.md` must explain:

* experiment scopes;
* deterministic assignment;
* weights;
* lifecycle;
* immutability;
* generation attribution;
* future analytics integration.

Explicitly document that TASK-03 does not attempt to automatically determine a statistically valid experiment winner.

---

# 66. Definition of Done

TASK-03 is complete when this workflow works:

```text
Create Prompt Template
        ↓
Create v1
        ↓
Define variables
        ↓
Define negative prompt
        ↓
Publish
        ↓
Create generation
        ↓
Provide variables
        ↓
Apply presets
        ↓
Render canonical prompt
        ↓
Route provider
        ↓
Adapt prompt
        ↓
Create immutable snapshot
        ↓
Generate image
        ↓
Asset created
```

And this workflow works:

```text
v1
↓
Create v2 draft
↓
modify
↓
publish

Existing v1 generations
remain unchanged.
```

And:

```text
Experiment

A → Prompt v2 → 50%
B → Prompt v3 → 50%

↓

Generation

↓

deterministic variant assignment

↓

snapshot contains attribution
```

---

# 67. Verification

Before completing TASK-03:

1. run backend unit tests;
2. run integration tests;
3. run frontend tests;
4. run backend production build;
5. run frontend production build;
6. apply Flyway migrations;
7. create a template manually;
8. create variables;
9. create positive and negative templates;
10. publish the version;
11. preview canonical prompt;
12. preview provider-adapted prompt;
13. generate an image using Mock provider;
14. verify snapshot persistence;
15. create v2;
16. verify v1 remains immutable;
17. create A/B experiment;
18. generate multiple deterministic assignments;
19. verify experiment attribution;
20. simulate retry;
21. verify prompt snapshot remains unchanged;
22. simulate fallback;
23. verify provider-specific adaptations are preserved;
24. verify legacy generations remain readable.

---

# 68. Final Codex Report

At completion provide:

## Architecture

Describe:

```text
Concept
→ Template
→ Version
→ Variables
→ Presets
→ Experiment
→ Canonical Prompt
→ Provider Adapter
→ Snapshot
→ Generation
```

## Domain Changes

List new entities and relationships.

## Database

List Flyway migrations and indexes.

## Prompt Rendering

Document:

* template implementation;
* variable validation;
* composition rules;
* negative prompts.

## Versioning

Explain immutability guarantees.

## Provider Integration

Explain how TASK-03 integrates with TASK-02 routing/fallback.

## Experiments

Explain:

* deterministic assignment;
* weighting;
* attribution;
* lifecycle.

## Frontend

List implemented screens and workflows.

## Tests

Report:

```text
unit tests
integration tests
frontend tests
build status
```

## Remaining Limitations

Explicitly list anything deferred to later tasks.

---

# Engineering Principles

Throughout TASK-03 follow these rules:

1. A published prompt version is immutable.
2. Every generation preserves the exact prompt it used.
3. Historical prompts are never dynamically reconstructed.
4. Prompt rendering is deterministic.
5. Prompt construction belongs to Prompt Engine, not provider clients.
6. Canonical prompts remain provider-neutral.
7. Provider-specific behavior belongs to adapters.
8. Negative prompts are first-class data.
9. Unsupported provider behavior is never silently ignored.
10. Variables are validated before generation.
11. Prompt presets are reusable rather than copied between templates.
12. Experiment assignment is deterministic.
13. Experiment attribution never changes after generation.
14. Running experiment configuration is immutable.
15. Retries reuse the original prompt snapshot.
16. Fallback providers may adapt the same canonical prompt differently.
17. Prompt previews never invoke paid image generation.
18. Old generations remain readable after migrations.
19. Do not introduce unnecessary LLM calls into deterministic Prompt Engine operations.
20. Do not over-engineer analytics before the dedicated analytics task.

The final result should make prompts a versioned, reproducible, testable domain of Media Factory rather than arbitrary strings passed directly to image-generation APIs.
