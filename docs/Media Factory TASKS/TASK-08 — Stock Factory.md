# TASK-08 — Stock Factory

## Objective

Build a production-ready **Stock Factory** for Media Factory.

The Stock Factory must transform approved AI-generated assets into technically validated, metadata-rich, export-ready stock media packages.

Primary workflow:

```text
Concept
  ↓
Prompt Engine
  ↓
Image Generation
  ↓
Advanced Visual QA
  ↓
Similarity / Duplicate Engine
  ↓
Stock Processing
  ↓
Stock Validation
  ↓
Metadata Generation
  ↓
Metadata QA
  ↓
Human Review
  ↓
Stock Export Package
  ├── Images
  ├── CSV
  ├── Manifest
  └── Validation Report
```

TASK-08 must support:

* stock-specific image variants;
* minimum-resolution / megapixel validation;
* high-quality JPEG export;
* configurable technical requirements;
* AI-generated stock titles;
* descriptions;
* keywords;
* keyword ranking;
* keyword normalization;
* category assignment;
* commercial/editorial classification;
* AI-content disclosure metadata;
* metadata versioning;
* metadata QA;
* duplicate metadata detection;
* export profiles;
* CSV generation;
* stock package generation;
* batch export;
* collection export;
* immutable export manifests;
* human metadata editing;
* publication/export status;
* future multi-stock-platform adapters.

The implementation must remain **platform-neutral at the core**.

Do not hardcode the entire Stock Factory around Adobe Stock or any other single marketplace.

---

# 1. Inspect Existing Architecture First

Before implementation inspect TASK-01 through TASK-07.

Specifically identify:

```text
Project
Collection
Concept
Generation
Asset
AssetVariant
QualityReview
SimilarityResult
ProcessingRun
ProcessingProfile
GenerationCost
Publication
MediaStorage
Job
PromptTemplate
PromptVersion
```

Reuse:

```text
TASK-02 → provider/cost infrastructure
TASK-03 → Prompt Engine
TASK-04 → Visual QA
TASK-05 → Similarity Engine
TASK-06 → Image Processing
```

TASK-08 must orchestrate these capabilities rather than reimplement them.

---

# 2. Core Architecture

Target architecture:

```text
                    STOCK FACTORY

Concept
   │
   ▼
Generation ───────────────── TASK-02
   │
   ▼
Prompt Provenance ────────── TASK-03
   │
   ▼
Visual QA ────────────────── TASK-04
   │
   ▼
Similarity ───────────────── TASK-05
   │
   ▼
Stock Processing ─────────── TASK-06
   │
   ▼
Stock Technical Validator
   │
   ▼
Stock Metadata Engine
   │
   ▼
Metadata QA
   │
   ▼
Human Review
   │
   ▼
Export Builder
   │
   ├── Images
   ├── CSV
   ├── Manifest
   └── Validation Report
```

---

# 3. Stock Production Aggregate

Introduce an orchestration aggregate such as:

```text
StockProduction
```

Suggested fields:

```text
id

projectId
collectionId
conceptId

generationId
sourceAssetId
stockAssetId

profileId
profileVersion

status

metadataVersionId

createdAt
updatedAt

approvedAt
approvedBy

exportedAt
```

Do not duplicate data already available through referenced entities.

---

# 4. Stock Production Lifecycle

Support explicit lifecycle:

```text
DRAFT
↓
SOURCE_READY
↓
QA_PENDING
↓
QA_APPROVED
↓
SIMILARITY_CHECK
↓
PROCESSING
↓
TECHNICAL_VALIDATION
↓
METADATA_GENERATION
↓
METADATA_REVIEW
↓
READY_FOR_EXPORT
↓
EXPORTED
```

Alternative/failure states:

```text
QA_REJECTED

DUPLICATE_REJECTED

PROCESSING_FAILED

VALIDATION_FAILED

METADATA_FAILED

REVIEW_REJECTED

EXPORT_FAILED

CANCELLED
```

---

# 5. Stock Profile

Create:

```text
StockProfile
```

Stock profiles define requirements for a destination or generic stock output.

Initial profiles:

```text
STOCK_GENERIC

STOCK_HIGH_QUALITY

STOCK_ADOBE

STOCK_CUSTOM
```

Platform-specific profiles must be isolated from core stock logic.

---

# 6. Profile Versioning

Profiles must be versioned.

Example:

```text
STOCK_GENERIC

v1
v2
v3
```

A historical export must always point to the exact profile version used.

Published/used profile versions become immutable.

---

# 7. Stock Profile Configuration

Conceptual example:

```yaml
stock:

  profiles:

    generic:

      image:
        minimum-megapixels: 4

        formats:
          - JPEG

        color-space:
          - SRGB

        jpeg-quality: 95

      metadata:
        title:
          required: true

        description:
          required: true

        keywords:
          minimum: 10
          maximum: 49

      export:
        csv: true
        manifest: true
```

Do not hardcode changing marketplace requirements into domain services.

---

# 8. Platform Requirements Registry

Create:

```text
StockPlatformRequirements
```

It may define:

```text
minimumMegapixels

maximumMegapixels

minimumWidth

minimumHeight

maximumWidth

maximumHeight

acceptedFormats

maximumFileSize

colorSpace

titleLength

descriptionLength

minimumKeywords

maximumKeywords

categoryRequirements

aiDisclosureRequirements
```

All values must be configurable/versioned.

---

# 9. Do Not Assume Platform Rules Forever

Marketplace requirements can change.

Therefore:

```text
Adobe requirement
```

must not become:

```java
if (megapixels < 4) ...
```

inside generic stock services.

Use:

```text
StockProfileVersion
```

or equivalent configuration.

---

# 10. Stock Master

Define:

```text
STOCK_MASTER
```

as the highest-quality stock-ready derivative.

Pipeline:

```text
Approved Generated Asset
↓
TASK-06 Processing
↓
STOCK_MASTER
```

The original generated asset remains immutable.

---

# 11. Stock Processing Strategy

Prefer minimal destructive processing.

Stock processing should generally prioritize:

```text
maximum useful resolution

natural detail

clean edges

accurate color

minimal compression artifacts

preserved composition
```

Avoid unnecessary cropping.

---

# 12. Upscale Policy

Use TASK-06.

If source already satisfies stock requirements:

```text
SKIP UPSCALE
```

If not:

```text
Source
↓
GPU Upscale
↓
Validation
```

Use the smallest necessary scale factor.

Do not upscale solely to create enormous files.

---

# 13. Stock Resolution Validation

Calculate:

```text
megapixels =
width × height / 1,000,000
```

Do not validate using rounded display dimensions.

Example:

```text
2400 × 1800
=
4.32 MP
```

---

# 14. Technical Validation

Create:

```text
StockTechnicalValidator
```

Validate:

```text
file readable

supported format

dimensions

megapixels

file size

color space

alpha channel rules

compression

corruption

orientation

checksum
```

Return structured validation results.

---

# 15. Validation Result

Example:

```json
{
  "valid": true,
  "profile": "STOCK_GENERIC",
  "profileVersion": 3,
  "checks": [
    {
      "type": "MINIMUM_MEGAPIXELS",
      "status": "PASS",
      "actual": 11.18,
      "required": 4
    },
    {
      "type": "FORMAT",
      "status": "PASS",
      "actual": "JPEG"
    }
  ],
  "warnings": []
}
```

---

# 16. Validation Severity

Checks should support:

```text
PASS

WARNING

FAIL
```

Example:

```text
quality slightly below preferred
→ WARNING
```

versus:

```text
unsupported file format
→ FAIL
```

---

# 17. Stock Visual QA

Reuse TASK-04.

Stock-specific QA profile should be stricter for defects likely to cause rejection.

Examples:

```text
bad anatomy

extra fingers

distorted faces

broken objects

garbled text

watermark-like artifacts

unwanted signatures

compression artifacts

banding

blur

over-sharpening

obvious generation defects
```

Do not build another independent Vision QA subsystem.

---

# 18. AI Text / Watermark Detection

Stock QA should explicitly inspect for:

```text
accidental text

fake logos

watermark-like patterns

signatures

UI fragments
```

Reuse TASK-04 detections where available.

---

# 19. Intellectual Property Risk Flags

Create a metadata/QA risk flag system.

Possible flags:

```text
VISIBLE_BRAND

VISIBLE_LOGO

TRADEMARK_LIKE_TEXT

KNOWN_CHARACTER_LIKE

CELEBRITY_LIKE

COPYRIGHT_RISK

UNKNOWN_IP_RISK
```

These are review signals.

Do not make unsupported legal conclusions.

---

# 20. Commercial vs Editorial

Represent:

```text
COMMERCIAL

EDITORIAL

UNDETERMINED
```

For AI-generated stock workflow, default classification should follow configured policy.

Do not automatically label risky branded/generated content as commercially safe.

---

# 21. Stock Metadata Engine

Create:

```text
StockMetadataService
```

It must produce:

```text
title

description

keywords

categories

contentType

aiGenerated flag

optional release/risk metadata
```

Use TASK-03 Prompt Engine for LLM prompt/version management.

---

# 22. Metadata Input

Metadata generation should use structured context.

Example:

```text
concept

final stock image

generation prompt

QA results

detected subjects

detected objects

dominant colors

collection

style

stock profile
```

Do not generate metadata from prompt alone.

Final image content is authoritative.

---

# 23. Vision-Assisted Metadata

Use existing TASK-04 Vision infrastructure where appropriate.

Potential structured observations:

```text
main subjects

secondary subjects

setting

activity

visual style

dominant colors

composition

mood

copy space

people count

object list
```

Feed structured observations into metadata generation.

Avoid duplicate Vision calls if equivalent results already exist.

---

# 24. Metadata Prompt

Use TASK-03 versioned templates.

Example logical template:

```text
STOCK_METADATA
```

Variables:

```text
{{concept}}

{{visual_description}}

{{main_subjects}}

{{secondary_subjects}}

{{style}}

{{colors}}

{{copy_space}}

{{stock_profile}}

{{language}}
```

Never hardcode metadata prompt strings inside services.

---

# 25. Metadata Version

Create/refine:

```text
StockMetadataVersion
```

Fields:

```text
id

stockProductionId

version

title

description

keywords

categories

contentType

aiGenerated

sourcePromptVersion

sourceVisionResult

createdAt

createdBy

status
```

---

# 26. Metadata Lifecycle

Support:

```text
GENERATED

EDITED

APPROVED

SUPERSEDED
```

Never silently overwrite metadata used in a previous export.

---

# 27. Title Generation

Generate concise stock-search-oriented titles.

Title should:

```text
describe visible content

identify main subject

include meaningful context

avoid keyword stuffing

avoid unsupported claims

avoid marketing hype

avoid camera/model claims not known to be true
```

---

# 28. Title Constraints

Profile controls:

```text
minimum length

maximum length

forbidden patterns

language
```

Do not truncate blindly after generation.

If title exceeds limits:

```text
regenerate/rewrite
```

or use deterministic safe shortening.

---

# 29. Description Generation

Description should describe:

```text
what is visible

subject

setting

composition

style where appropriate

useful conceptual context
```

Avoid inventing:

```text
location

identity

brand

event

camera

real-world factual context
```

unless known.

---

# 30. Keywords

Keywords are a first-class structured object.

Do not store only:

```text
"wolf, cyberpunk, blue..."
```

Store ranked keywords.

Example:

```text
StockKeyword

value
normalizedValue
rank
source
confidence
```

---

# 31. Keyword Sources

Support:

```text
VISION

LLM

CONCEPT

PROMPT

MANUAL
```

This enables provenance/debugging.

---

# 32. Keyword Ranking

Keywords must be ordered by relevance.

Example:

```text
1 cybernetic wolf
2 wolf
3 cyberpunk
4 neon
5 futuristic
6 animal
...
```

Do not randomly sort keywords alphabetically.

---

# 33. Keyword Normalization

Implement:

```text
trim

case normalization

duplicate removal

whitespace normalization

punctuation cleanup
```

Preserve meaningful multi-word phrases.

Example:

```text
cybernetic wolf
```

must not become:

```text
cybernetic
wolf
```

automatically.

---

# 34. Keyword Deduplication

Detect semantic/lexical duplicates such as:

```text
wolf

wolves

wolf animal
```

Do not fill keyword limits with trivial repetitions.

Implement conservative normalization.

---

# 35. Keyword Limits

Stock profile defines:

```text
minimumKeywords

maximumKeywords
```

If generation returns too many:

```text
rank
↓
select highest relevance
```

Do not truncate before ranking.

---

# 36. Keyword Relevance

Metadata QA should detect potentially irrelevant keywords.

Possible strategy:

```text
keyword
↓
compare with Vision observations / concept / image embedding
↓
relevance score
```

Avoid expensive model calls for every keyword if simpler validation is sufficient.

---

# 37. Keyword Safety

Do not automatically generate names of:

```text
brands

celebrities

artists

characters

trademarks
```

unless explicitly verified/allowed by the configured workflow.

Flag questionable terms for review.

---

# 38. Categories

Create internal normalized stock taxonomy.

Example:

```text
ANIMALS

TECHNOLOGY

NATURE

BUSINESS

PEOPLE

ABSTRACT

FOOD

TRAVEL

SCIENCE
```

Then map:

```text
Internal Category
↓
Platform Category
```

Do not make core domain use platform-specific category IDs.

---

# 39. Category Mapper

Create:

```text
StockCategoryMapper
```

Example:

```text
ANIMALS
↓
Adobe-specific category
```

Platform mapping belongs in adapter/profile layer.

---

# 40. AI Generated Disclosure

Represent explicitly:

```text
aiGenerated = true
```

and optionally:

```text
generationProvider

generationModel
```

internally.

Export only what destination requires.

Do not infer AI status from filename.

---

# 41. Metadata QA

Create:

```text
StockMetadataValidator
```

Validate:

```text
title exists

title length

description exists

description length

keyword count

keyword uniqueness

keyword relevance

category valid

forbidden terms

AI disclosure present where required

no obvious unsupported claims
```

---

# 42. Metadata QA Result

Example:

```json
{
  "status": "WARNING",
  "issues": [
    {
      "field": "keywords",
      "code": "LOW_RELEVANCE_KEYWORD",
      "value": "business",
      "severity": "WARNING"
    }
  ]
}
```

---

# 43. Metadata Quality Score

If useful, calculate dimensions rather than one opaque score:

```text
titleQuality

descriptionQuality

keywordCoverage

keywordRelevance

metadataCompliance
```

Do not hide individual validation issues behind one score.

---

# 44. Human Metadata Review

Before export allow user to edit:

```text
title

description

keywords

category
```

Show:

```text
image preview

QA result

similarity result

technical validation

metadata warnings
```

---

# 45. Keyword Editor

Frontend should support:

```text
drag/reorder

add

remove

edit

duplicate warning

keyword count

relevance warning
```

Preserve keyword ranking.

---

# 46. Metadata Regeneration

Allow:

```text
Regenerate Title

Regenerate Description

Regenerate Keywords

Regenerate All
```

Each creates a new metadata version.

Do not overwrite approved/exported metadata.

---

# 47. Manual Metadata Changes

Human edits must record:

```text
editedBy

editedAt

previousVersion

change source = MANUAL
```

Maintain auditability.

---

# 48. Stock Review UI

Create dedicated:

```text
STOCK REVIEW
```

Example:

```text
┌────────────────────────────────────────────────────────┐
│ STOCK REVIEW                                           │
├───────────────────────┬────────────────────────────────┤
│                       │ Title                          │
│                       │ Cybernetic Wolf in Neon City   │
│       PREVIEW         │                                │
│                       │ Description                    │
│                       │ ...                            │
│                       │                                │
│                       │ Keywords 42/49                 │
│                       │ wolf, cyberpunk, neon...       │
├───────────────────────┴────────────────────────────────┤
│ Technical: PASS   QA: PASS   Similarity: DISTINCT      │
│                                                        │
│ [Reject] [Regenerate Metadata] [Approve]               │
└────────────────────────────────────────────────────────┘
```

---

# 49. Batch Review

Support reviewing many stock assets efficiently.

Modes:

```text
GRID

DETAIL
```

Grid shows:

```text
thumbnail

title

validation status

QA

similarity

metadata warnings
```

---

# 50. Bulk Approval

Allow:

```text
select 25
↓
Approve
```

only when mandatory validation passes.

Do not let frontend bypass server-side export eligibility rules.

---

# 51. Stock Export Eligibility

Create:

```text
StockExportPolicy
```

Required conditions might include:

```text
QA APPROVED

Similarity acceptable

Stock Technical Validation PASS

Metadata Validation PASS

Metadata APPROVED

Required stock variant exists

Checksum exists
```

Server must enforce this.

---

# 52. Export Profiles

Create:

```text
StockExportProfile
```

Initial examples:

```text
GENERIC_CSV

ADOBE_STOCK

CUSTOM
```

Each defines:

```text
file naming

CSV columns

CSV delimiter

encoding

keyword formatting

category mapping

AI disclosure mapping

folder structure
```

---

# 53. Platform Adapter Architecture

Create abstraction such as:

```java
public interface StockExportAdapter {

    String platformId();

    StockExportPackage build(
        StockExportRequest request
    );
}
```

Implement:

```text
GenericStockExportAdapter
```

and optionally:

```text
AdobeStockExportAdapter
```

if requirements are known/configured.

---

# 54. Separate Export From Upload

TASK-08 focuses on:

```text
EXPORT
```

not necessarily direct stock-site submission.

Architecture:

```text
Stock Factory
↓
Export Package
↓
Manual Upload / Future API / FTP / Integration
```

Future upload adapters can be added without changing metadata domain.

---

# 55. CSV Export

Generate standards-compliant CSV.

Support:

```text
UTF-8

configurable delimiter

proper quoting

escaped quotes

commas inside fields

line breaks where permitted
```

Use a mature CSV library.

Do not build CSV by string concatenation.

---

# 56. Generic CSV

Generic schema may include:

```text
filename

title

description

keywords

category

ai_generated
```

Exact platform exports use platform-specific mapping.

---

# 57. CSV Example

Conceptually:

```csv
filename,title,description,keywords,category,ai_generated
wolf-001.jpg,"Cybernetic wolf in neon city","...","cybernetic wolf,wolf,cyberpunk,neon,...","Animals",true
```

Actual output must correctly escape values.

---

# 58. CSV Filename Mapping

Every CSV row must map unambiguously to a file.

Use stable exported filename.

Do not rely on database IDs alone if uploader expects filenames.

---

# 59. Export Filename Strategy

Create:

```text
StockFilenameStrategy
```

Possible output:

```text
cybernetic-wolf-neon-city-8f31a2.jpg
```

Requirements:

```text
safe characters

stable

collision-resistant

reasonable length
```

Do not use title alone as unique key.

---

# 60. Export Package

Create:

```text
StockExportPackage
```

Example:

```text
stock-export-2026-09-24/
│
├── images/
│   ├── cyber-wolf-001.jpg
│   ├── cyber-wolf-002.jpg
│   └── ...
│
├── metadata.csv
│
├── manifest.json
│
└── validation-report.json
```

---

# 61. Export Manifest

Manifest should contain internal provenance.

Example:

```json
{
  "exportId": "...",
  "profile": "GENERIC_CSV",
  "profileVersion": 2,
  "createdAt": "...",
  "assets": [
    {
      "stockProductionId": "...",
      "assetId": "...",
      "filename": "cyber-wolf-001.jpg",
      "checksum": "...",
      "metadataVersion": 3
    }
  ]
}
```

---

# 62. Immutable Export

Once completed successfully:

```text
ExportPackage
```

becomes immutable.

If metadata/images change:

```text
Export v2
```

must be created.

Never silently modify a previously completed export.

---

# 63. Export Entity

Introduce:

```text
StockExport
```

Fields:

```text
id

profileId
profileVersion

status

assetCount

createdAt
completedAt

manifestStorageKey

csvStorageKey

packageStorageKey

checksum
```

---

# 64. Export Item

Create:

```text
StockExportItem
```

Fields:

```text
exportId

stockProductionId

assetId

metadataVersionId

exportFilename

status

validationResult
```

---

# 65. Export Status

Support:

```text
PREPARING

VALIDATING

BUILDING

READY

FAILED

ARCHIVED
```

---

# 66. Batch Export

Allow:

```text
select 100 approved stock assets
↓
Export
↓
ZIP/package
```

Processing must be asynchronous.

---

# 67. Collection Export

Support:

```text
Collection
↓
Stock Export
```

Example:

```text
Cyber Wolves — 50 approved assets
```

Generate one export package containing the selected collection.

---

# 68. Incremental Export

Support querying:

```text
not previously exported
```

so user can prepare only new stock assets.

Do not automatically assume previously exported means successfully uploaded externally.

Track these concepts separately.

---

# 69. Exported vs Published

Keep separate statuses:

```text
READY_FOR_EXPORT

EXPORTED

SUBMITTED

ACCEPTED

REJECTED
```

TASK-08 needs at least:

```text
EXPORTED
```

Future tasks may implement marketplace submission/result tracking.

---

# 70. External Submission Placeholder

Design for future:

```text
StockSubmission
```

but do not overengineer marketplace automation in TASK-08.

The current task ends at reliable export packaging.

---

# 71. Validation Report

Each export should include machine-readable validation report.

Example:

```json
{
  "total": 100,
  "valid": 97,
  "warnings": 3,
  "failed": 0
}
```

Include per-item details.

---

# 72. Fail Fast Before Export

Do not spend time building ZIP/package if required items already fail eligibility.

Run:

```text
pre-export validation
```

first.

---

# 73. Partial Export Policy

Make behavior configurable:

```text
STRICT
```

means:

```text
one invalid item
→ entire export blocked
```

and:

```text
VALID_ONLY
```

means:

```text
export eligible items
skip invalid
report skipped
```

Default to safe/explicit behavior.

---

# 74. Export Storage

Store generated export artifacts through `MediaStorage` or dedicated artifact storage abstraction consistent with project architecture.

Do not scatter export ZIPs across arbitrary local folders.

---

# 75. Temporary Export Workspace

Use:

```text
tmp/stock-export/{exportId}/
```

or equivalent.

After successful storage:

```text
cleanup
```

Retain failed workspace only according to debug/retention policy.

---

# 76. ZIP Packaging

If ZIP export is enabled:

```text
images
metadata
manifest
validation
```

should be packaged deterministically.

Avoid embedding unnecessary source/master files.

---

# 77. Export Checksums

Calculate:

```text
SHA-256
```

for:

```text
each exported image

CSV

manifest

final package
```

where appropriate.

Reuse TASK-05 checksum utilities.

---

# 78. Export Reproducibility

Given:

```text
same asset variant

same metadata version

same export profile version
```

the system should be able to reproduce equivalent export contents.

Timestamps/ZIP metadata may prevent byte-identical archives unless intentionally normalized.

Document this distinction.

---

# 79. Duplicate Protection

Before export use TASK-05.

Prevent obvious:

```text
exact duplicate

near duplicate
```

from entering the same stock batch unless manually overridden.

---

# 80. Collection Similarity Review

Before large export show:

```text
exact duplicates

near duplicates

semantic clusters

largest clusters

repetitive concepts
```

This helps avoid exporting hundreds of almost identical images.

---

# 81. Metadata Similarity

Create lightweight duplicate-title detection.

Detect:

```text
identical titles

nearly identical titles

identical keyword sets
```

Warn when a batch looks mechanically duplicated.

Do not automatically rewrite approved metadata without user action.

---

# 82. Concept Diversity

Expose TASK-05 cluster information in stock review.

Example:

```text
Cyber Wolves

Cluster A: 18
Cluster B: 14
Cluster C: 6
Outliers: 4
```

This helps curate export batches.

---

# 83. Stock Collection Production

Support workflow:

```text
Create Stock Collection
↓
target = 100 approved
↓
controlled generation batches
↓
QA
↓
Similarity
↓
Stock Processing
↓
Metadata
↓
Review
↓
Export
```

Reuse TASK-07 collection orchestration patterns where appropriate.

---

# 84. Target Approved Assets

Use:

```text
targetApprovedAssets
```

rather than target generated count.

Rejected images do not satisfy target.

---

# 85. Budget / Attempt Guard

Collection may define:

```text
targetApproved

maxGenerationAttempts

maxGenerationCost
```

Never generate indefinitely trying to fill a stock collection.

---

# 86. Stock Dashboard

Create:

```text
STOCK FACTORY
```

dashboard.

Show:

```text
Candidates

QA Approved

Duplicate Rejected

Processing

Metadata Review

Ready for Export

Exported

Validation Failed
```

---

# 87. Stock KPIs

Show:

```text
Generated today

Stock-ready today

Metadata pending

Ready for export

Exported today

Technical failures

QA rejection rate

Duplicate rejection rate

Production cost

Cost per stock-ready asset
```

---

# 88. Export Dashboard

Show:

```text
Export ID

Profile

Assets

Status

Created

Completed
```

Actions:

```text
View

Validate

Rebuild as New Version

Download Package
```

Do not mutate completed package.

---

# 89. Stock Asset Detail

Show:

```text
Stock image

dimensions

megapixels

format

file size

QA

similarity

technical validation

title

description

keywords

category

metadata history

export history
```

---

# 90. Metadata History UI

Display:

```text
v1 AI GENERATED

v2 HUMAN EDITED

v3 APPROVED
```

Allow comparison between versions.

---

# 91. Metadata A/B Readiness

TASK-03 already supports A/B prompts.

Stock metadata generation should record:

```text
prompt experiment

variant
```

when metadata comes from an A/B prompt.

Do not implement performance winner selection yet.

TASK-10/11 can later connect marketplace performance.

---

# 92. Localization Readiness

Default stock metadata language:

```text
English
```

but architecture should allow future localization.

Do not build every language in TASK-08.

---

# 93. Metadata Cache

If identical final asset + same metadata prompt version + same context already produced metadata, reuse when appropriate.

Cache identity must include:

```text
asset checksum

prompt version

model/provider where relevant

metadata profile
```

---

# 94. Cost Tracking

Track metadata generation cost using existing cost infrastructure.

Operations may include:

```text
STOCK_METADATA_GENERATION

STOCK_VISION_ANALYSIS
```

Reuse previous Vision results whenever possible.

---

# 95. Cost Attribution

Aggregate:

```text
Generation

Visual QA

Metadata Vision

Metadata LLM

External processing
```

to:

```text
StockProduction
```

and:

```text
StockCollection
```

---

# 96. Cost Per Export-Ready Asset

Calculate:

```text
total production cost
÷
number of export-ready assets
```

Rejected assets remain part of total production cost.

---

# 97. Metrics

Expose:

```text
media_factory_stock_production_total

media_factory_stock_ready_total

media_factory_stock_validation_failed_total

media_factory_stock_metadata_generated_total

media_factory_stock_metadata_failed_total

media_factory_stock_export_total

media_factory_stock_export_assets_total

media_factory_stock_export_failed_total

media_factory_stock_pipeline_duration
```

Use low-cardinality tags.

---

# 98. Structured Logging

Log:

```text
stock production started

technical validation completed

metadata generation started

metadata generated

metadata edited

metadata approved

export validation started

export created

export failed
```

Include:

```text
stockProductionId

assetId

exportId

profile

metadataVersion
```

Do not log image binaries.

---

# 99. Stock Factory API

Possible endpoints:

```text
POST /api/v1/stock-productions

GET /api/v1/stock-productions

GET /api/v1/stock-productions/{id}

POST /api/v1/stock-productions/{id}/process

POST /api/v1/stock-productions/{id}/generate-metadata

POST /api/v1/stock-productions/{id}/approve

POST /api/v1/stock-productions/{id}/reject
```

Follow existing API conventions.

---

# 100. Metadata API

Possible:

```text
GET /api/v1/stock-productions/{id}/metadata

GET /api/v1/stock-productions/{id}/metadata/versions

POST /api/v1/stock-productions/{id}/metadata/regenerate

PUT /api/v1/stock-productions/{id}/metadata
```

Do not overwrite historical versions.

---

# 101. Export API

Possible:

```text
POST /api/v1/stock-exports

GET /api/v1/stock-exports

GET /api/v1/stock-exports/{id}

GET /api/v1/stock-exports/{id}/validation
```

Export creation remains asynchronous.

---

# 102. Export Request

Example:

```json
{
  "profile": "GENERIC_CSV",
  "stockProductionIds": [
    "...",
    "..."
  ],
  "policy": "STRICT"
}
```

Or support:

```text
collectionId
```

instead of explicit IDs.

---

# 103. Database

Create/refine Flyway migrations for:

```text
stock_profile

stock_profile_version

stock_production

stock_metadata_version

stock_keyword

stock_validation_result

stock_export

stock_export_item
```

Reuse generic entities where appropriate.

Do not create unnecessary duplicate tables if existing domain structures already fit.

---

# 104. Indexes

Add indexes for:

```text
stock_production.status

stock_production.collection_id

stock_production.asset_id

stock_metadata_version.stock_production_id

stock_export.status

stock_export.created_at

stock_export_item.export_id
```

Add uniqueness constraints needed for idempotency/versioning.

---

# 105. Technical Validator Tests

Test:

```text
valid JPEG

unsupported PNG profile

below minimum megapixels

exact minimum megapixels

oversized file

wrong color space

corrupt image

unexpected alpha
```

Use deterministic fixtures.

---

# 106. Metadata Tests

Test:

```text
title generation

title max length

description generation

keyword ranking

duplicate keyword removal

keyword count limits

multi-word keywords

category mapping

AI disclosure

manual edit versioning
```

Do not call paid APIs in CI.

---

# 107. CSV Tests

Test:

```text
commas

quotes

UTF-8

special characters

multi-word keywords

empty optional fields

line endings

large batches
```

Use CSV parser round-trip tests.

---

# 108. Export Filename Tests

Test:

```text
duplicate titles

special characters

very long titles

Unicode

filename collisions
```

Ensure stable unique filenames.

---

# 109. Export Package Tests

Build:

```text
10 stock assets
```

Verify:

```text
10 images

10 CSV rows

manifest

validation report

checksums
```

---

# 110. Immutable Export Test

Create export v1.

Modify metadata.

Verify:

```text
v1 unchanged
```

Create:

```text
v2
```

with new metadata.

---

# 111. Duplicate Batch Test

Attempt export containing:

```text
exact duplicate

near duplicate
```

Verify configured policy blocks/warns appropriately.

---

# 112. Metadata History Test

Generate:

```text
v1
```

human edit:

```text
v2
```

regenerate:

```text
v3
```

Verify all remain queryable.

---

# 113. Cost Test

Verify rejected stock candidates still contribute to collection production cost.

Do not calculate cost only from exported assets.

---

# 114. Integration Tests

Use:

```text
PostgreSQL Testcontainers

MinIO

mock image provider

mock Vision provider

mock LLM provider
```

Test:

```text
Generation
↓
QA
↓
Similarity
↓
Processing
↓
Stock Validation
↓
Metadata
↓
Approval
↓
Export
```

---

# 115. No Paid APIs in CI

All automated tests must use:

```text
mock

fake

WireMock

local deterministic fixtures
```

Never call paid production AI APIs from normal CI.

---

# 116. Documentation

Create:

```text
docs/stock-factory.md

docs/stock-profiles.md

docs/stock-validation.md

docs/stock-metadata.md

docs/stock-keywords.md

docs/stock-export.md

docs/stock-csv.md
```

---

# 117. Stock Factory Documentation

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
Metadata
↓
Review
↓
Export
```

Clearly identify which existing TASK owns each stage.

---

# 118. Stock Profile Documentation

Document exact configured requirements.

For each profile list:

```text
resolution

megapixels

formats

file size

color space

metadata limits

export format
```

Do not present configurable assumptions as universal stock-platform rules.

---

# 119. Metadata Documentation

Explain:

```text
Vision observations

Prompt Engine

metadata generation

keyword ranking

metadata validation

human editing

version history
```

---

# 120. CSV Documentation

Document exact columns for every export adapter.

Example:

```text
GENERIC_CSV

ADOBE_STOCK
```

if implemented.

Document:

```text
encoding

delimiter

keyword separator

quoting
```

---

# 121. Export Documentation

Explain:

```text
export eligibility

STRICT vs VALID_ONLY

package structure

manifest

checksums

immutability

re-export/versioning
```

---

# 122. Definition of Done

TASK-08 is complete when:

```text
QA-approved asset
↓
Similarity accepted
↓
Stock processing
↓
Stock technical validation
↓
Metadata generation
↓
Metadata QA
↓
Human approval
↓
Stock export
```

works end-to-end.

And:

```text
50 approved assets
↓
metadata generated
↓
titles
descriptions
ranked keywords
categories
↓
review
↓
CSV
↓
images
↓
manifest
↓
validation report
↓
export package
```

works.

And:

```text
Export v1
↓
metadata changed
↓
Export v2
```

preserves v1 unchanged.

---

# 123. Verification

Before completing TASK-08:

1. run backend unit tests;
2. run backend integration tests;
3. run frontend tests;
4. run production builds;
5. apply Flyway migrations;
6. create stock candidate;
7. run QA;
8. run similarity;
9. create STOCK_MASTER;
10. verify source remains immutable;
11. validate megapixels;
12. validate JPEG;
13. validate color space;
14. validate file size;
15. simulate technical validation failure;
16. generate title;
17. generate description;
18. generate ranked keywords;
19. normalize keywords;
20. detect duplicate keywords;
21. validate keyword count;
22. map category;
23. persist AI disclosure;
24. run metadata validation;
25. manually edit metadata;
26. verify metadata version history;
27. regenerate metadata;
28. verify previous version remains;
29. approve stock asset;
30. verify invalid asset cannot be exported;
31. create Generic CSV export;
32. parse generated CSV back with CSV parser;
33. verify filename mapping;
34. verify commas/quotes/UTF-8;
35. generate manifest;
36. generate validation report;
37. calculate checksums;
38. build export package;
39. verify all expected files exist;
40. create batch export;
41. create collection export;
42. test exact duplicate protection;
43. test near-duplicate protection;
44. test STRICT export;
45. test VALID_ONLY export;
46. create export v1;
47. modify metadata;
48. create export v2;
49. verify v1 unchanged;
50. verify temporary-file cleanup;
51. verify costs;
52. verify no secrets in logs;
53. verify CI uses no paid APIs.

---

# 124. Final Codex Report

At completion provide:

## Architecture

Describe:

```text
Asset
→ Stock Processing
→ Technical Validation
→ Metadata
→ Review
→ Export
```

## Stock Profiles

List implemented profiles and exact configured requirements.

## Processing

Report:

```text
stock variant

format

quality

color space

upscale policy

technical validation
```

## Metadata Engine

Report:

```text
Prompt Template

Prompt Version

Vision inputs

LLM provider/model

title logic

description logic

keyword ranking

keyword normalization

category mapping
```

Do not expose secrets.

## Export

Report:

```text
export adapters

CSV schemas

filename strategy

manifest

validation report

ZIP/package structure

checksum strategy
```

## Tests

Report:

```text
unit tests

integration tests

CSV tests

export tests

frontend tests

production builds
```

## Manual Verification

Report actual manually verified flow.

Never claim live AI/provider execution if credentials were unavailable.

## Remaining Limitations

Explicitly list:

```text
platform-specific limitations

metadata quality limitations

keyword relevance limitations

IP-risk detection limitations

export limitations

future direct-upload opportunities
```

---

# Engineering Principles

Throughout TASK-08 follow these rules:

1. Stock Factory orchestrates TASK-02 — TASK-06 instead of duplicating them.
2. Original generated assets remain immutable.
3. Stock derivatives preserve complete processing lineage.
4. Stock requirements are profile-driven and versioned.
5. Do not hardcode marketplace requirements throughout domain code.
6. Do not claim technical validation guarantees marketplace acceptance.
7. Generate metadata from the final image, not only the generation prompt.
8. Reuse existing Vision results whenever possible.
9. Titles must describe visible content.
10. Do not invent locations, identities, brands, events, or camera information.
11. Keywords are ordered by relevance.
12. Keywords must preserve useful multi-word phrases.
13. Do not fill keyword limits with trivial synonyms.
14. Do not automatically add brands, celebrities, characters, or trademarks.
15. IP detection produces review flags, not unsupported legal conclusions.
16. Metadata is versioned.
17. Human edits never destroy metadata history.
18. Approved/exported metadata is immutable.
19. Categories use an internal taxonomy with platform-specific mapping.
20. AI-generated status is explicit structured metadata.
21. CSV must be generated with a proper CSV library.
22. Every CSV row maps unambiguously to its exported file.
23. Export filenames must be stable and collision-resistant.
24. Export packages are immutable.
25. Changed metadata/assets produce a new export version.
26. Export manifests preserve provenance.
27. Every exported binary has a checksum.
28. TASK-05 must protect stock batches from duplicate and near-duplicate flooding.
29. Similarity clusters should help humans curate diverse batches.
30. Target approved assets, not raw generation count.
31. Generation attempts and budgets must be bounded.
32. Rejected assets remain part of real production cost.
33. Separate EXPORTED from future SUBMITTED/ACCEPTED marketplace states.
34. Direct marketplace upload must remain an adapter/future capability.
35. Platform-specific logic stays outside the core stock domain.
36. CI never consumes paid AI APIs.
37. Failed export must not corrupt previously completed exports.
38. Export building must be resumable/retryable where practical.
39. Metadata QA should expose individual problems instead of hiding everything behind one score.
40. Prefer quality and commercial usefulness over producing maximum volume.
41. Design metadata provenance so TASK-10 Analytics and TASK-11 Feedback Engine can later connect stock performance to prompts, concepts, keywords, visual attributes, providers and processing profiles.

The result of TASK-08 should turn Media Factory into a complete **AI Stock Content Factory** capable of taking approved generated images and producing technically validated, duplicate-controlled, high-resolution stock assets with professional titles, descriptions, ranked keywords and categories, packaged into reproducible CSV-based export batches ready for stock-platform submission.
