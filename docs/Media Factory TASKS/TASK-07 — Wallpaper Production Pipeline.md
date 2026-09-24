# TASK-07 — Wallpaper Production Pipeline

## Objective

Build a production-ready **Wallpaper Production Pipeline** on top of Media Factory.

TASK-07 must integrate the systems created in TASK-01 — TASK-06 into one complete workflow:

```text
Concept
   ↓
Prompt Engine
   ↓
Image Generation
   ↓
Advanced Visual QA
   ↓
Similarity & Duplicate Engine
   ↓
Image Processing
   ↓
Wallpaper Master
   ↓
Android Device Variants
   ↓
Preview / Thumbnail
   ↓
Wallpaper Collection
   ↓
Publication Package
   ↓
Wallpaper Backend
   ↓
Android Application
```

The pipeline must support:

* Android-specific wallpaper variants;
* portrait/mobile-first generation;
* device/aspect-ratio families;
* smart crop and safe zones;
* lock-screen-aware composition;
* AMOLED wallpapers;
* AMOLED quality validation;
* wallpaper collections;
* collection covers;
* thumbnails/previews;
* publication metadata;
* backend export;
* idempotent publication;
* publication state tracking;
* rollback/unpublish support;
* immutable publication manifests;
* human approval before publication;
* batch collection production;
* scheduled production readiness;
* integration with existing Android wallpaper backend.

TASK-07 is primarily an **orchestration and product pipeline task**.

Do not rebuild functionality already implemented by TASK-02 — TASK-06.

---

# 1. Inspect Existing System First

Before writing code inspect:

```text
TASK-01 Foundation
TASK-02 Real Image Providers
TASK-03 Prompt Engine
TASK-04 Advanced Visual QA
TASK-05 Similarity Engine
TASK-06 Image Processing Pipeline
```

Identify existing implementations for:

```text
Project
Collection
Concept
Generation
Asset
AssetVariant
QualityReview
SimilarityFinding
ProcessingRun
ProcessingProfile
Publication
GenerationCost
MediaStorage
Job
```

Also inspect the existing Android wallpaper backend.

Determine:

```text
wallpaper entity/model
categories
collections
REST endpoints
storage
image URLs
thumbnail URLs
metadata
publication mechanism
authentication
existing Android API contract
```

Do not assume the backend schema.

Adapt the Media Factory exporter to the real backend contract.

---

# 2. Core Principle

TASK-07 must NOT become another implementation of:

```text
generation
QA
similarity
resize
upscale
storage
```

Instead:

```text
WallpaperProductionPipeline
```

orchestrates existing capabilities.

Target architecture:

```text
                  WALLPAPER PIPELINE

Concept
   │
   ▼
Prompt Engine ───────────── TASK-03
   │
   ▼
Generation ──────────────── TASK-02
   │
   ▼
Visual QA ───────────────── TASK-04
   │
   ▼
Similarity ──────────────── TASK-05
   │
   ▼
Processing ──────────────── TASK-06
   │
   ▼
Wallpaper Packaging
   │
   ▼
Publication
   │
   ▼
Wallpaper Backend
```

---

# 3. Wallpaper Production State Machine

Introduce an explicit production lifecycle.

Example:

```text
DRAFT
↓
CONCEPT_READY
↓
GENERATING
↓
GENERATED
↓
QA_PENDING
↓
QA_APPROVED
↓
SIMILARITY_CHECK
↓
PROCESSING
↓
VARIANTS_READY
↓
PUBLICATION_REVIEW
↓
APPROVED_FOR_PUBLICATION
↓
PUBLISHING
↓
PUBLISHED
```

Failure/alternative states:

```text
QA_REJECTED

DUPLICATE_REJECTED

PROCESSING_FAILED

PUBLICATION_FAILED

PAUSED

CANCELLED

UNPUBLISHED
```

Use existing state abstractions where possible.

Do not duplicate state already represented elsewhere unless pipeline-level state is necessary.

---

# 4. Wallpaper Production Entity

Introduce an orchestration aggregate such as:

```text
WallpaperProduction
```

Suggested fields:

```text
id

projectId
collectionId
conceptId

generationId
masterAssetId

status

wallpaperProfile

amoled

publicationTarget

createdAt
updatedAt

approvedBy
approvedAt

publishedAt
```

Do not store duplicated asset metadata unnecessarily.

Reference existing entities.

---

# 5. Wallpaper Profile

Create wallpaper-specific profiles.

Initial examples:

```text
ANDROID_STANDARD

ANDROID_PREMIUM

ANDROID_AMOLED

ANDROID_LOCK_SCREEN

ANDROID_HOME_SCREEN
```

A profile defines:

```text
generation expectations

QA policy

similarity profile

processing profile

device variants

preview strategy

publication strategy
```

---

# 6. Pipeline Definition

The pipeline should be configurable.

Conceptual example:

```yaml
wallpaper:

  profiles:

    android-premium:

      generation:
        prompt-profile: WALLPAPER_ANDROID

      qa:
        profile: WALLPAPER_STRICT

      similarity:
        profile: WALLPAPER

      processing:
        profile: WALLPAPER_ANDROID

      variants:
        - ANDROID_QHD_PORTRAIT
        - ANDROID_FHD_PORTRAIT
        - ANDROID_GENERIC_PORTRAIT

      preview:
        enabled: true

      publication:
        human-approval-required: true
```

Do not hardcode workflow decisions throughout services.

---

# 7. Android-First Composition

Wallpaper generation must be mobile-first.

Prompt context should support requirements such as:

```text
portrait composition

clear focal subject

vertical negative space

safe top area

safe bottom area

subject not excessively close to edges

minimal accidental text

high detail

wallpaper-friendly composition
```

TASK-07 should pass these requirements into TASK-03.

Do not concatenate random strings directly onto prompts.

Use Prompt Engine variables/presets.

---

# 8. Wallpaper Prompt Context

Example variables:

```text
wallpaper.orientation

wallpaper.aspectRatio

wallpaper.safeZoneTop

wallpaper.safeZoneBottom

wallpaper.subjectPlacement

wallpaper.amoled

wallpaper.style

wallpaper.collectionTheme
```

Example:

```text
orientation = PORTRAIT
aspectRatio = 9:20
subjectPlacement = CENTER_LOWER
amoled = true
```

Prompt Engine remains responsible for rendering.

---

# 9. Wallpaper Master

Define:

```text
WALLPAPER_MASTER
```

as the highest-quality approved wallpaper source used for derivative creation.

Preferred flow:

```text
Generated Asset
↓
QA
↓
Similarity
↓
TASK-06 Processing
↓
WALLPAPER_MASTER
```

Do not generate each phone resolution separately using the image-generation provider.

Generate one strong master and derive variants.

---

# 10. Master Resolution Strategy

Do not assume one fixed master resolution forever.

Create configuration such as:

```yaml
wallpaper:

  master:

    orientation: PORTRAIT

    minimum-width: 2160

    minimum-height: 4800
```

TASK-06 may upscale when necessary.

The actual master should preserve the best available quality.

---

# 11. Android Device Variant Registry

Create:

```text
WallpaperDeviceProfile
```

Fields:

```text
key

width

height

aspectRatio

format

quality

cropMode

safeZoneProfile

enabled
```

Initial logical profiles might include:

```text
ANDROID_FHD_PORTRAIT

ANDROID_QHD_PORTRAIT

ANDROID_TALL_PORTRAIT

ANDROID_GENERIC_PORTRAIT

ANDROID_PREVIEW

ANDROID_THUMBNAIL
```

Do not tie the system to specific phone models.

---

# 12. Avoid Device Explosion

Do NOT create variants for hundreds of Android devices.

Group devices by useful resolution/aspect-ratio families.

For example:

```text
FHD portrait family

QHD portrait family

tall-screen family

generic fallback
```

The Android client/backend can choose the closest appropriate variant.

---

# 13. Variant Selection Strategy

Prepare backend metadata allowing the Android application to choose the best asset based on:

```text
screenWidth

screenHeight

aspectRatio

density

requested quality
```

Do not force the app to download the largest master unnecessarily.

---

# 14. Variant Descriptor

Each published variant should expose:

```text
id

type

width

height

aspectRatio

format

fileSize

checksum

qualityTier

url / storage reference
```

Reuse TASK-06 `AssetVariant`.

Do not create another binary representation.

---

# 15. Subject-Aware Variants

TASK-06 Smart Crop must be used for device variants.

Example:

```text
Wallpaper Master
↓
QHD target
↓
Smart Crop
↓
subject safe?
↓
resize
```

Do not use naive center crop.

---

# 16. Android Safe Zones

Introduce wallpaper-specific safe-zone profiles.

Example:

```text
ANDROID_HOME

ANDROID_LOCK
```

Potential conceptual regions:

```text
TOP_UI_ZONE

CENTER_FOCUS_ZONE

BOTTOM_UI_ZONE
```

Do not assume every Android launcher has identical UI.

Safe zones should be heuristics/configuration.

---

# 17. Lock Screen Variant

Support optional:

```text
LOCK_SCREEN
```

variant.

The crop/composition should preserve useful empty space around common clock/notification areas.

Do not bake clock graphics into the image.

---

# 18. Home Screen Variant

Support optional:

```text
HOME_SCREEN
```

variant.

Consider:

```text
icons

widgets

visual readability

subject placement
```

Again, these are composition heuristics.

Do not render launcher UI into final wallpaper.

---

# 19. AMOLED Pipeline

Implement explicit:

```text
ANDROID_AMOLED
```

production profile.

AMOLED should be a real technical/content profile, not merely a tag.

Pipeline:

```text
AMOLED Concept
↓
AMOLED Prompt Profile
↓
Generation
↓
QA
↓
AMOLED Analysis
↓
Similarity
↓
Processing
↓
AMOLED Variants
```

---

# 20. AMOLED Definition

Create configurable criteria.

Example:

```text
dominant dark/black background

limited bright area

strong focal highlights

good subject separation

no unintended gray haze

no crushed important details
```

Do not define AMOLED quality purely as:

```text
average brightness < X
```

Use multiple measurements.

---

# 21. AMOLED Analyzer

Create:

```text
AmoledAnalyzer
```

It should calculate objective image statistics.

Possible measurements:

```text
blackPixelRatio

nearBlackPixelRatio

meanLuminance

medianLuminance

brightPixelRatio

highlightCoverage

luminanceHistogram
```

Use deterministic image analysis.

Do not use an LLM for simple pixel statistics.

---

# 22. Black Pixel Definition

Make threshold configurable.

Example:

```yaml
amoled:

  black:
    maximum-luminance: ...
```

Also calculate:

```text
near-black
```

separately.

Do not hardcode one threshold without documentation.

---

# 23. AMOLED Validation Result

Example:

```json
{
  "blackPixelRatio": 0.68,
  "nearBlackPixelRatio": 0.79,
  "meanLuminance": 0.14,
  "brightPixelRatio": 0.07,
  "classification": "AMOLED_SUITABLE",
  "warnings": []
}
```

Classification:

```text
AMOLED_SUITABLE

AMOLED_BORDERLINE

NOT_AMOLED
```

Thresholds must be configurable.

---

# 24. AMOLED QA

AMOLED technical analysis should complement TASK-04 Visual QA.

TASK-04 can detect:

```text
artifacts

bad anatomy

unwanted text

composition problems
```

AMOLED Analyzer detects:

```text
pixel/luminance properties
```

Keep responsibilities separate.

---

# 25. AMOLED Processing

Do NOT automatically crush blacks aggressively just to pass AMOLED validation.

Allow optional conservative processing profile:

```text
black-point adjustment

contrast adjustment
```

only if explicitly configured.

Preserve image quality.

---

# 26. AMOLED Collection

Support dedicated collections such as:

```text
AMOLED Wolves

AMOLED Space

AMOLED Cyberpunk

AMOLED Cars

AMOLED Abstract

AMOLED Nature
```

AMOLED remains structured metadata:

```text
amoled = true
```

rather than relying only on collection name.

---

# 27. Wallpaper Collections

Extend existing `Collection`.

Wallpaper collection should support:

```text
title

slug

description

theme

style

amoled

status

coverAssetId

sortOrder

featured

publication metadata
```

Reuse existing collection entity if possible.

Do not create an unrelated second collection model.

---

# 28. Collection Lifecycle

Support:

```text
DRAFT

GENERATING

REVIEW

READY

PUBLISHED

ARCHIVED
```

A collection may contain:

```text
10
20
50
100+
```

wallpapers.

---

# 29. Collection Production

Support:

```text
Create Collection
↓
Generate Concepts
↓
Generate Wallpapers
↓
QA
↓
Similarity
↓
Processing
↓
Review
↓
Publish Collection
```

Example:

```text
Cyber Wolves
target = 30 wallpapers
```

The pipeline should manage progress toward the target.

---

# 30. Collection Production Target

Store:

```text
targetApprovedAssets
```

not simply:

```text
targetGeneratedAssets
```

Example:

```text
target approved = 30
```

If QA rejects 8:

```text
generate replacements
```

until:

```text
30 approved
```

subject to budget and generation limits.

---

# 31. Collection Budget Guard

Integrate TASK-02 cost tracking.

Collection may define:

```text
generationBudget

maximumGenerationAttempts

maximumCost
```

Example:

```text
target = 30 approved

maximum attempts = 60
```

Do not generate indefinitely trying to fill the collection.

---

# 32. Similarity Guard

TASK-05 must protect collection diversity.

Before accepting wallpaper:

```text
QA Approved
↓
Similarity
↓
near duplicate?
```

Policy:

```text
DISTINCT
→ continue

SIMILAR
→ continue/warn

NEAR_DUPLICATE
→ review/reject

DUPLICATE
→ reject
```

Use configured wallpaper similarity profile.

---

# 33. Batch Diversity Protection

For automated collection generation:

```text
Generate 10
↓
QA
↓
Similarity
↓
Diversity check
↓
next 10
```

Do not queue 500 generations blindly.

Reuse TASK-05 batch diversity guard.

---

# 34. Collection Diversity

Before publication calculate:

```text
approved count

duplicate count

near-duplicate count

cluster distribution

largest cluster share

outliers
```

Warn when collection is excessively repetitive.

Do not automatically reject collection solely from one aggregate metric unless policy explicitly requires it.

---

# 35. Collection Cover

Automatically propose a cover from approved assets.

Candidate ranking may consider:

```text
QA status

resolution

composition

representativeness

similarity cluster

manual favorite
```

Do not use revenue/performance data before it exists.

Human should be able to override the cover.

---

# 36. Cover Derivatives

Generate:

```text
COLLECTION_COVER

COLLECTION_CARD

COLLECTION_THUMBNAIL
```

through TASK-06.

Do not modify the selected wallpaper itself.

---

# 37. Wallpaper Preview

Generate lightweight previews for UI browsing.

Example:

```text
PREVIEW_SMALL

PREVIEW_MEDIUM
```

Properties:

```text
lower resolution

optimized format

small file size
```

Do not serve full 4K masters in collection grids.

---

# 38. Thumbnail

Generate dedicated thumbnail.

Do not merely rely on browser/client resizing of a huge source.

Use TASK-06 processing.

---

# 39. Preview Strategy

Example:

```text
MASTER
  │
  ├── QHD wallpaper
  ├── FHD wallpaper
  ├── preview
  └── thumbnail
```

Reuse shared processing stages where possible.

---

# 40. Preview Metadata

Store:

```text
width

height

format

fileSize

checksum
```

as standard `AssetVariant` metadata.

---

# 41. Android Backend Adapter

Create a publication abstraction:

```java
public interface WallpaperPublicationTarget {

    PublicationResult publish(
        WallpaperPublicationRequest request
    );

    PublicationResult update(
        WallpaperPublicationRequest request
    );

    PublicationResult unpublish(
        WallpaperPublicationReference reference
    );
}
```

First implementation:

```text
AndroidWallpaperBackendPublicationTarget
```

Do not embed HTTP calls directly into pipeline orchestration.

---

# 42. Publication Target Registry

Architecture should allow future targets:

```text
Android Backend

Web Gallery

External CDN

Future iOS Backend
```

TASK-07 only needs the Android wallpaper backend implementation.

---

# 43. Existing Backend Integration

Inspect the actual backend before implementing the adapter.

Determine whether publication requires:

```text
REST API

direct DB

S3/MinIO upload

manifest import

filesystem export
```

Prefer a stable API/integration boundary.

Avoid direct cross-application DB writes unless the existing architecture explicitly requires them.

---

# 44. Publication Package

Before publishing create immutable:

```text
WallpaperPublicationPackage
```

containing:

```text
wallpaper metadata

collection metadata

master reference

variant references

preview

thumbnail

AMOLED metadata

checksums

generation provenance

processing lineage

publication version
```

This package represents exactly what is being published.

---

# 45. Publication Manifest

Example:

```json
{
  "wallpaperId": "...",
  "collection": "cyber-wolves",
  "title": "Neon Sentinel",
  "amoled": true,
  "masterAssetId": "...",
  "variants": [
    {
      "type": "ANDROID_QHD_PORTRAIT",
      "width": 1440,
      "height": 3200,
      "checksum": "..."
    },
    {
      "type": "ANDROID_FHD_PORTRAIT",
      "width": 1080,
      "height": 2400,
      "checksum": "..."
    }
  ],
  "previewAssetId": "...",
  "thumbnailAssetId": "...",
  "publicationVersion": 1
}
```

Persist immutable historical publication manifests.

---

# 46. Publication Versioning

If wallpaper changes after publication:

```text
Publication v1
↓
new processing/profile
↓
Publication v2
```

Do not overwrite historical publication metadata.

Track:

```text
ACTIVE publication version
```

---

# 47. Publication Metadata

Generate/store:

```text
title

description

slug

tags

category

collection

amoled

featured

premium/free

sort order
```

TASK-03 may generate text metadata.

Do not couple metadata generation directly to backend HTTP DTOs.

---

# 48. Wallpaper Tags

Use structured tags where possible.

Examples:

```text
wolf

cyberpunk

neon

dark

amoled

animal

blue

fantasy
```

Separate internal taxonomy from display text.

---

# 49. Categories

Support configurable categories:

```text
AMOLED

Animals

Nature

Space

Cyberpunk

Abstract

Cars

Fantasy
```

Do not hardcode these throughout Java enums if backend taxonomy is dynamic.

---

# 50. Publication Eligibility

Create:

```text
WallpaperPublicationPolicy
```

Wallpaper may be published only when required conditions are met.

Example:

```text
QA = APPROVED

Similarity = acceptable

required variants = READY

preview = READY

thumbnail = READY

processing validation = PASSED

human publication approval = YES
```

Server-side enforcement is required.

---

# 51. Human Publication Review

Before publication show:

```text
Wallpaper

Master preview

Device variants

AMOLED analysis

QA

Similarity

Metadata

Collection
```

Actions:

```text
APPROVE

REJECT

REGENERATE

REPROCESS

EDIT METADATA
```

Do not auto-publish by default during TASK-07.

---

# 52. Review UI

Example:

```text
┌─────────────────────────────────────────────────────┐
│ WALLPAPER REVIEW                                    │
├──────────────────────┬──────────────────────────────┤
│                      │ Cyber Wolves                 │
│                      │ Neon Sentinel                │
│       PREVIEW        │                              │
│                      │ QA: PASS                     │
│                      │ Similarity: DISTINCT         │
│                      │ AMOLED: SUITABLE             │
│                      │                              │
│                      │ QHD ✓ FHD ✓ Preview ✓       │
├──────────────────────┴──────────────────────────────┤
│ [Reject] [Reprocess] [Approve for Publication]      │
└─────────────────────────────────────────────────────┘
```

---

# 53. Publication Queue

Create:

```text
PUBLICATION QUEUE
```

Statuses:

```text
WAITING_APPROVAL

READY

PUBLISHING

PUBLISHED

FAILED

UNPUBLISHED
```

Support batch publication.

---

# 54. Idempotent Publication

Publication must be idempotent.

If network timeout occurs after backend accepted wallpaper:

```text
Media Factory
does not know response
```

retry must not create duplicate wallpapers.

Use:

```text
externalId

idempotencyKey

publicationVersion
```

depending on backend capabilities.

---

# 55. External Mapping

Persist:

```text
PublicationExternalReference

publicationId

target

externalWallpaperId

externalCollectionId

externalVersion

publishedAt
```

Never depend solely on matching titles/slugs.

---

# 56. Publication Retry

Retry:

```text
timeout

temporary 5xx

temporary storage error
```

Do not blindly retry:

```text
authentication failure

invalid payload

validation failure

unsupported backend version
```

Classify failures.

---

# 57. Publication Rollback

Support:

```text
UNPUBLISH
```

when backend supports it.

Do not delete Media Factory assets.

Flow:

```text
Published
↓
Unpublish
↓
backend content disabled/removed
↓
Media Factory provenance retained
```

---

# 58. Republishing

Allow:

```text
UNPUBLISHED
↓
new publication version
↓
PUBLISHED
```

without rerunning generation unless needed.

---

# 59. Collection Publication

Support:

```text
publish collection metadata
↓
publish wallpapers
↓
associate wallpapers with collection
↓
publish/activate collection
```

Avoid exposing an empty collection prematurely if backend supports staged publication.

---

# 60. Atomicity Strategy

Cross-system publication cannot rely on one database transaction.

Implement orchestration/saga-style behavior.

Example:

```text
create remote collection
↓
upload/publish assets
↓
associate wallpapers
↓
activate collection
```

Persist each completed external step.

Allow resume after failure.

---

# 61. Do Not Hold Transactions During HTTP

Never:

```text
BEGIN DB TRANSACTION

↓
upload large image
↓
HTTP backend call
↓
wait
↓
COMMIT
```

Use short DB transactions around state transitions.

---

# 62. Storage Strategy

Determine whether Android backend consumes:

```text
Media Factory object URLs
```

or requires:

```text
copy/upload into backend storage
```

Implement according to actual architecture.

Do not assume shared storage.

---

# 63. Asset Delivery

Prefer publication references to immutable/versioned objects.

Avoid URLs that can silently change contents.

Published binary should correspond to stored checksum.

---

# 64. Checksum Verification

Before publication verify:

```text
variant checksum

storage object checksum where available
```

After upload/copy, verify integrity when backend/storage supports it.

---

# 65. Backend DTO Isolation

Create adapter DTOs inside integration layer.

Do not annotate core Media Factory domain entities with Android backend serialization concerns.

Architecture:

```text
Wallpaper Domain
↓
Publication Package
↓
Android Backend Mapper
↓
Android Backend DTO
↓
HTTP Client
```

---

# 66. API Version Compatibility

If wallpaper backend exposes versioned API:

```text
/api/v1
```

record/support its contract.

Fail clearly on incompatible versions.

Do not silently ignore unknown required fields.

---

# 67. Authentication

Reuse existing secret/configuration infrastructure.

Support backend authentication as required:

```text
API key

Bearer token

service credentials
```

Never store credentials in source control.

Never log them.

---

# 68. Publication Dry Run

Implement:

```text
DRY_RUN
```

mode.

It should:

```text
build package

validate eligibility

map backend payload

validate files

show planned operations
```

but perform no remote mutation.

This is important before first production publication.

---

# 69. Export Mode

Besides direct publication support:

```text
EXPORT PACKAGE
```

for manual/backend import.

Example:

```text
wallpaper-export/
  manifest.json
  master/
  variants/
  previews/
  thumbnails/
```

This provides a fallback if backend API is unavailable.

---

# 70. Export ZIP

Optionally support packaging:

```text
collection-cyber-wolves-v1.zip
```

containing the publication package.

Do not make ZIP the internal source of truth.

Database + storage remain authoritative.

---

# 71. Android Client Contract

Document how Android should select a wallpaper variant.

Conceptually:

```text
device screen metrics
↓
available variants
↓
best match
```

Prefer:

```text
smallest variant meeting display requirements
```

rather than always downloading the largest image.

---

# 72. Fallback Variant

Every published wallpaper should have a generic fallback where required.

Example:

```text
ANDROID_GENERIC_PORTRAIT
```

If exact family is unavailable:

```text
fallback
```

Android app must still display/apply wallpaper.

---

# 73. Wallpaper API Response

Backend integration should support data conceptually like:

```json
{
  "id": "...",
  "title": "Neon Sentinel",
  "amoled": true,
  "collection": {
    "id": "...",
    "slug": "cyber-wolves"
  },
  "thumbnail": "...",
  "preview": "...",
  "variants": [
    {
      "type": "QHD",
      "width": 1440,
      "height": 3200
    },
    {
      "type": "FHD",
      "width": 1080,
      "height": 2400
    }
  ]
}
```

Adapt to the actual backend instead of replacing its contract unnecessarily.

---

# 74. Publication Metrics

Track:

```text
wallpapers prepared

wallpapers approved

wallpapers published

publication failures

collections published

AMOLED wallpapers

average pipeline duration
```

Later TASK-10 can add:

```text
views

downloads

favorites

applications

revenue
```

---

# 75. Pipeline Timing

Persist stage timings:

```text
generationDuration

qaDuration

similarityDuration

processingDuration

publicationDuration

totalProductionDuration
```

Do not assume faster always means better.

These metrics are operational.

---

# 76. Cost Attribution

Aggregate TASK-02 costs to:

```text
WallpaperProduction
```

and:

```text
Collection
```

Example:

```text
Generation       $X
Vision QA        $Y
External upscale $Z
-------------------
Total            $N
```

Local operations remain local compute unless configured otherwise.

---

# 77. Cost Per Approved Wallpaper

Calculate:

```text
total collection generation/QA costs
÷
approved wallpaper count
```

Expose as operational metric.

Do not count rejected generation costs as zero.

They are part of production cost.

---

# 78. Collection Cost

Expose:

```text
Generated: 42
Approved: 30
Rejected: 12

Generation cost
QA cost
Processing external cost
Total production cost
Cost / approved wallpaper
```

This becomes important for future monetization analysis.

---

# 79. Wallpaper Dashboard

Create dedicated dashboard area:

```text
WALLPAPER FACTORY
```

Show:

```text
Collections

Production Queue

Review

Publication Queue

Published

AMOLED

Failures
```

---

# 80. Dashboard KPIs

Show:

```text
Generated today

QA approved

Similarity rejected

Processing ready

Waiting publication review

Published today

AMOLED ready

Pipeline failures

Production cost
```

Keep operational metrics actionable.

---

# 81. Collection Detail UI

Example:

```text
CYBER WOLVES

Target approved       30
Generated             38
QA approved           31
Duplicates             3
Ready                  30
Published               0

Budget used           ...
```

Grid:

```text
[IMG] [IMG] [IMG] [IMG]
[IMG] [IMG] [IMG] [IMG]
```

Filters:

```text
Generated

QA Failed

Duplicate

Ready

Published
```

---

# 82. AMOLED Dashboard

Provide:

```text
AMOLED Collections

AMOLED Suitable

Borderline

Failed AMOLED validation
```

Allow inspection of luminance statistics.

---

# 83. Production Actions

UI actions:

```text
Generate More

Run QA

Run Similarity

Process Variants

Prepare Publication

Approve Selected

Publish Selected
```

Actions should invoke existing pipeline capabilities.

Do not implement duplicate business logic in frontend.

---

# 84. Bulk Actions

Support:

```text
select 20 wallpapers
↓
Approve
```

or:

```text
Process
Publish
Reject
```

Bulk operations remain asynchronous where expensive.

---

# 85. Regeneration

If wallpaper fails:

```text
QA
```

user may select:

```text
REGENERATE
```

Reuse TASK-03/TASK-04 lineage.

Do not replace the failed generation.

Create new generation descendant.

---

# 86. Reprocessing

If image is good but crop is bad:

```text
REPROCESS
```

not:

```text
REGENERATE
```

Reuse TASK-06.

Preserve distinction between:

```text
content problem
```

and:

```text
processing problem
```

---

# 87. Manual Crop Fix

Publication Review should allow opening TASK-06 crop editor.

Flow:

```text
variant bad
↓
Edit Crop
↓
new ProcessingRun
↓
new AssetVariant
↓
publication package updated
```

Do not manually overwrite old variant.

---

# 88. Collection Replacement

If collection needs 30 wallpapers and one is rejected:

```text
approved = 29
↓
replacement generation requested
```

Generation should preserve collection diversity constraints.

---

# 89. Production Orchestrator

Implement service such as:

```java
public interface WallpaperProductionOrchestrator {

    WallpaperProduction start(...);

    void continuePipeline(...);

    void pause(...);

    void resume(...);

    void cancel(...);
}
```

Actual implementation should follow existing architecture conventions.

---

# 90. Event-Driven Progression

Where existing architecture supports events/jobs, prefer stage transitions such as:

```text
GenerationCompleted
↓
QA requested

QAApproved
↓
Similarity requested

SimilarityAccepted
↓
Processing requested

ProcessingCompleted
↓
Publication review
```

Avoid one giant synchronous method.

---

# 91. Idempotent Stage Transitions

Every transition must be idempotent.

Receiving:

```text
ProcessingCompleted
```

twice must not publish/create variants twice.

Use durable state + constraints.

---

# 92. Pipeline Recovery

After service restart:

```text
RUNNING wallpaper production
```

must be recoverable.

Do not rely solely on in-memory orchestration.

Persist pipeline state.

---

# 93. Pipeline Pause

Allow:

```text
PAUSE COLLECTION
```

This should prevent new generation/processing jobs from being dispatched while allowing safe handling of already-running work according to existing job semantics.

---

# 94. Pipeline Cancellation

Cancellation should stop future stages.

Do not delete completed assets.

Preserve cost/provenance.

---

# 95. Automated Collection Mode

Support optional:

```text
AUTO_PRODUCTION
```

Example:

```text
target approved = 30

↓

generate controlled batches

↓

QA

↓

similarity

↓

process accepted assets

↓

stop at 30 ready
```

Still require publication approval by default.

---

# 96. Human-Gated Mode

Default production mode:

```text
AUTO GENERATION
AUTO QA
AUTO SIMILARITY
AUTO PROCESSING

↓

HUMAN PUBLICATION REVIEW

↓

PUBLISH
```

This should be the safe default.

---

# 97. Future Fully Automated Mode

Design for future:

```text
AUTO_PUBLISH
```

but do not enable it by default in TASK-07.

Require explicit configuration.

---

# 98. Scheduling Readiness

TASK-07 should be compatible with future TASK-13 automation.

Example future schedule:

```text
02:00
Generate collection candidates

↓

QA

↓

Similarity

↓

Processing

↓

Morning:
publication review queue
```

Do not implement scheduling twice if existing automation/job infrastructure already handles it.

---

# 99. API Endpoints

Implement REST endpoints consistent with existing conventions.

Possible:

```text
POST /api/v1/wallpaper-productions

GET /api/v1/wallpaper-productions

GET /api/v1/wallpaper-productions/{id}

POST /api/v1/wallpaper-productions/{id}/pause

POST /api/v1/wallpaper-productions/{id}/resume

POST /api/v1/wallpaper-productions/{id}/cancel
```

---

# 100. Collection Production API

Possible:

```text
POST /api/v1/wallpaper-collections/{id}/produce

GET /api/v1/wallpaper-collections/{id}/production-status
```

Request:

```json
{
  "targetApproved": 30,
  "batchSize": 5,
  "wallpaperProfile": "ANDROID_AMOLED",
  "maxAttempts": 60
}
```

---

# 101. Publication API

Possible:

```text
POST /api/v1/wallpapers/{id}/prepare-publication

POST /api/v1/wallpapers/{id}/approve-publication

POST /api/v1/wallpapers/{id}/publish

POST /api/v1/wallpapers/{id}/unpublish
```

Use existing authorization conventions.

---

# 102. Collection Publication API

Possible:

```text
POST /api/v1/wallpaper-collections/{id}/prepare-publication

POST /api/v1/wallpaper-collections/{id}/publish

POST /api/v1/wallpaper-collections/{id}/unpublish
```

Collection publishing must account for partial failures.

---

# 103. Dry Run API

Support:

```text
POST /api/v1/wallpapers/{id}/publication-dry-run
```

Return:

```text
eligibility

planned backend operations

payload summary

variant summary

warnings
```

without remote mutation.

---

# 104. Export API

Support asynchronous export:

```text
POST /api/v1/wallpapers/{id}/export
```

and:

```text
POST /api/v1/wallpaper-collections/{id}/export
```

Generate immutable export package.

---

# 105. Validation Before Publication

Validate:

```text
master exists

required variants exist

variants readable

checksums present

preview exists

thumbnail exists

QA approved

similarity acceptable

processing validation passed

metadata valid

collection valid

backend mapping valid
```

Fail before remote calls where possible.

---

# 106. Publication Race Conditions

Prevent:

```text
User A publishes
User B publishes simultaneously
```

from creating duplicate external resources.

Use locking/versioning/idempotency appropriately.

---

# 107. Optimistic Locking

Consider optimistic locking on:

```text
WallpaperProduction

Publication

Collection
```

where concurrent UI operations are possible.

Follow existing project conventions.

---

# 108. Security

Protect:

```text
publication

unpublish

bulk publish

profile configuration
```

with appropriate authorization.

Do not expose backend publication credentials to frontend.

---

# 109. Audit Trail

Record:

```text
collection created

production started

generation accepted/rejected

wallpaper approved

wallpaper rejected

metadata edited

crop overridden

publication approved

published

unpublished
```

Reuse existing audit infrastructure if present.

---

# 110. Tests — Pipeline

Test complete flow with mocks:

```text
Concept
↓
Mock Generation
↓
QA APPROVED
↓
Similarity DISTINCT
↓
Processing
↓
Variants
↓
Publication Review
↓
Publish
```

Verify final:

```text
PUBLISHED
```

---

# 111. Tests — QA Rejection

```text
Generation
↓
QA REJECTED
```

Verify:

```text
no processing

no publication
```

and replacement can be requested.

---

# 112. Tests — Duplicate Rejection

```text
QA approved
↓
Similarity DUPLICATE
```

Verify:

```text
publication blocked
```

according to wallpaper policy.

---

# 113. Tests — Reprocessing

```text
Master approved
↓
variant crop bad
↓
manual crop override
↓
new ProcessingRun
↓
new variant
```

Verify old variant remains immutable.

---

# 114. Tests — AMOLED

Use deterministic image fixtures:

```text
mostly black
```

```text
dark gray
```

```text
bright image
```

Verify:

```text
black pixel ratio

near-black ratio

luminance

classification
```

Do not rely on Vision API.

---

# 115. Tests — Device Variants

Given master:

```text
2160 × 4800
```

generate configured variants.

Verify:

```text
dimensions

aspect ratio

subject-aware crop integration

format

checksum

lineage
```

---

# 116. Tests — Collection Target

Example:

```text
targetApproved = 10

generated = 12

rejected = 2

approved = 10
```

Verify pipeline stops.

---

# 117. Tests — Budget Guard

Example:

```text
target = 30

approved = 20

max attempts reached
```

Verify:

```text
pipeline pauses/stops
```

rather than generating indefinitely.

---

# 118. Tests — Diversity Guard

Simulate repetitive generations.

Verify TASK-05 can cause:

```text
PAUSED_DIVERSITY
```

and no new generation batches are dispatched.

---

# 119. Tests — Publication Idempotency

Simulate:

```text
backend accepts request
↓
client timeout
↓
retry
```

Verify one remote wallpaper exists.

Use mock/fake backend.

---

# 120. Tests — Partial Collection Publication

Simulate:

```text
wallpaper 1 success

wallpaper 2 success

wallpaper 3 failure
```

Verify:

```text
state persisted

retry continues safely

1 and 2 are not duplicated
```

---

# 121. Tests — Unpublish

Verify:

```text
PUBLISHED
↓
UNPUBLISH
↓
UNPUBLISHED
```

while:

```text
assets

lineage

publication history

costs
```

remain intact.

---

# 122. Tests — Export Package

Verify export contains:

```text
manifest

required variants

preview

thumbnail
```

and checksums match.

---

# 123. Integration Tests

Use:

```text
Testcontainers PostgreSQL

MinIO

mock image provider

mock Vision provider

mock Android wallpaper backend
```

Do not consume paid AI APIs in CI.

---

# 124. Android Backend Contract Tests

Create adapter/contract tests against a fake server matching the real wallpaper backend API.

Test:

```text
authentication

payload mapping

successful publication

duplicate/idempotent request

validation failure

401/403

404

409

429

5xx

timeout
```

---

# 125. Frontend Tests

Cover:

```text
collection production

review workflow

AMOLED statistics

variant viewer

publication approval

publication queue

error/retry states

bulk actions
```

---

# 126. Observability

Expose metrics such as:

```text
media_factory_wallpaper_production_total

media_factory_wallpaper_ready_total

media_factory_wallpaper_published_total

media_factory_wallpaper_rejected_total

media_factory_wallpaper_publication_failed_total

media_factory_wallpaper_pipeline_duration

media_factory_amoled_suitable_total

media_factory_wallpaper_collection_ready_total
```

Useful low-cardinality tags:

```text
profile

status

amoled

publication_target
```

---

# 127. Structured Logging

Log:

```text
wallpaper production started

stage transition

AMOLED analysis completed

variants generated

publication package prepared

publication approved

publication started

publication completed

publication failed

unpublished
```

Include safe IDs.

Do not log credentials or binary images.

---

# 128. Documentation

Create:

```text
docs/wallpaper-pipeline.md

docs/android-wallpaper-variants.md

docs/amoled-pipeline.md

docs/wallpaper-collections.md

docs/wallpaper-publication.md

docs/wallpaper-backend-integration.md
```

---

# 129. Wallpaper Pipeline Documentation

Document:

```text
Concept
↓
Generation
↓
QA
↓
Similarity
↓
Processing
↓
Variants
↓
Review
↓
Publication
```

Explain which TASK owns each stage.

---

# 130. Android Variant Documentation

Document:

```text
device families

target resolutions

selection algorithm

fallback

safe zones

lock/home differences
```

Do not claim universal Android launcher behavior.

---

# 131. AMOLED Documentation

Document exact definitions for:

```text
black

near-black

luminance

bright pixels

AMOLED_SUITABLE

AMOLED_BORDERLINE
```

Include configured thresholds.

---

# 132. Backend Integration Documentation

Document:

```text
endpoint

authentication

payload mapping

storage strategy

idempotency

external IDs

retry

unpublish
```

Never include production secrets.

---

# 133. Definition of Done

TASK-07 is complete when this works end-to-end:

```text
Concept
↓
Prompt
↓
Generation
↓
Visual QA
↓
Similarity
↓
Processing
↓
Wallpaper Master
↓
Android Variants
↓
Preview
↓
Thumbnail
↓
Publication Review
↓
Android Backend
↓
PUBLISHED
```

And this works:

```text
AMOLED Concept
↓
Generation
↓
AMOLED Analyzer
↓
AMOLED validation
↓
Android AMOLED variants
↓
AMOLED Collection
↓
Publication
```

And this works:

```text
Collection
target = 30 approved
↓
controlled generation batches
↓
QA
↓
duplicate/diversity protection
↓
replacement generation
↓
30 READY wallpapers
↓
human publication review
```

---

# 134. Verification

Before completing TASK-07:

1. run backend tests;
2. run frontend tests;
3. run integration tests;
4. run production builds;
5. apply Flyway migrations;
6. create wallpaper collection;
7. create standard wallpaper production;
8. generate through mock/real configured provider;
9. run TASK-04 QA;
10. run TASK-05 similarity;
11. run TASK-06 processing;
12. verify wallpaper master;
13. generate FHD variant;
14. generate QHD variant;
15. generate generic fallback;
16. generate preview;
17. generate thumbnail;
18. verify subject-aware crops;
19. verify lineage;
20. create AMOLED wallpaper;
21. calculate AMOLED statistics;
22. verify AMOLED classification;
23. create AMOLED collection;
24. verify collection target logic;
25. verify rejected generation replacement;
26. verify maximum-attempt guard;
27. verify diversity pause;
28. prepare publication package;
29. verify publication eligibility;
30. execute dry run;
31. publish to mock backend;
32. simulate timeout and retry;
33. verify idempotency;
34. simulate partial collection failure;
35. resume publication;
36. unpublish;
37. republish new version;
38. export wallpaper package;
39. export collection package;
40. verify checksums;
41. verify immutable publication manifest;
42. verify original/master assets remain unchanged;
43. verify no paid APIs are used by CI;
44. verify Android backend credentials never reach frontend;
45. document actual backend integration.

---

# 135. Final Codex Report

At completion provide:

## Pipeline Architecture

Describe:

```text
Concept
→ Generation
→ QA
→ Similarity
→ Processing
→ Variants
→ Publication
```

## Android Variants

Report actual implemented profiles:

```text
name

resolution

aspect ratio

format

quality

crop strategy
```

## AMOLED

Report:

```text
analyzer implementation

black threshold

near-black threshold

luminance calculations

classification rules
```

## Collections

Report:

```text
target-approved logic

batch generation

replacement behavior

diversity protection

budget/attempt limits
```

## Publication

Report:

```text
Android backend integration

authentication mechanism

storage strategy

idempotency

retry

rollback/unpublish

publication versioning
```

Do not expose credentials.

## Frontend

List implemented:

```text
Wallpaper Factory Dashboard

Collections

Production Queue

Wallpaper Review

AMOLED Review

Publication Queue
```

## Tests

Report:

```text
unit tests

integration tests

backend contract tests

frontend tests

production builds
```

## Remaining Limitations

Explicitly list:

```text
device-specific limitations

launcher safe-zone assumptions

AMOLED threshold calibration

backend limitations

publication limitations

future improvements
```

---

# Engineering Principles

Throughout TASK-07:

1. TASK-07 orchestrates existing subsystems instead of reimplementing them.
2. Generate one strong wallpaper master and derive device variants from it.
3. Do not generate one AI image per phone resolution.
4. Do not create variants for hundreds of individual Android devices.
5. Use device/aspect-ratio families.
6. Always provide an appropriate fallback variant.
7. Use subject-aware cropping.
8. Android safe zones are configurable heuristics, not universal truths.
9. AMOLED is a structured production profile, not merely a tag.
10. AMOLED validation should use deterministic pixel analysis where possible.
11. Do not destroy image quality merely to increase black-pixel ratio.
12. QA approval is required before publication.
13. Similarity protection is required before publication.
14. Derived variants must preserve TASK-06 lineage.
15. Wallpaper collections should target approved assets, not generated count.
16. Rejected assets still count toward production cost.
17. Automated generation must have attempt/budget limits.
18. Batch generation must respect TASK-05 diversity protection.
19. Reprocessing and regeneration are different operations.
20. Human crop corrections create new processing history.
21. Publication must be idempotent.
22. Remote publication calls must not run inside long DB transactions.
23. Cross-system publication must be resumable after partial failure.
24. Published versions are immutable historical records.
25. Unpublishing must not delete Media Factory provenance.
26. Backend-specific DTOs stay outside the core domain.
27. Credentials never reach frontend or logs.
28. Preview and thumbnail assets should be optimized separately from full wallpaper assets.
29. Do not serve huge master images where lightweight previews are sufficient.
30. Every published binary must be traceable to its source master.
31. Every published wallpaper must have a reproducible generation and processing history.
32. CI must use mocks/fakes rather than paid AI APIs.
33. Direct publication must have a dry-run mode.
34. Manual export must remain available as a fallback.
35. Publication approval should remain human-gated by default.
36. Design the pipeline so TASK-10 Analytics can later connect downloads, favorites, usage, and revenue back to generation parameters.
37. Optimize for a high-quality, diverse wallpaper catalog rather than maximum generation volume.

The result of TASK-07 should turn Media Factory into an end-to-end **Android Wallpaper Factory** capable of taking a concept and producing a QA-approved, duplicate-checked, GPU-processed, Android-optimized, AMOLED-aware, collection-organized and fully traceable wallpaper ready for publication in the production wallpaper backend.
