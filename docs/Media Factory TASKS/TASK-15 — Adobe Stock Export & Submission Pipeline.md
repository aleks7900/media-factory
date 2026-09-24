# TASK-15 — Adobe Stock Export & Submission Pipeline

## Objective

Build a production-ready **Adobe Stock Export & Submission Pipeline** for Media Factory.

The pipeline must transform approved stock assets into validated, reproducible and trackable Adobe Stock submission batches:

```text
Stock-Ready Asset
↓
Adobe Eligibility Check
↓
JPEG Validation
↓
Resolution / 4MP+ Validation
↓
Metadata Generation
↓
Metadata Validation
↓
Submission Candidate
↓
Batch
↓
CSV Manifest
↓
Export Package
↓
SFTP / Supported Transport
↓
Upload Verification
↓
Submission Tracking
↓
Adobe Review Result Import
↓
TASK-14 Profitability / Feedback
```

Primary goal:

> Media Factory should be able to take hundreds or thousands of approved stock assets and reliably prepare, export, upload and track them without manually renaming files, copying metadata or reconstructing submission history.

This task is specifically about the **Adobe Stock delivery boundary**.

It must integrate existing:

```text
TASK-04  QA
TASK-05  Similarity
TASK-06  Processing
TASK-08  Stock Factory
TASK-10  Analytics
TASK-11  Feedback
TASK-13  Automation
TASK-14  Stock Intelligence
```

Do not rebuild those systems.

---

# 1. Inspect Existing Architecture First

Before writing code inspect the repository and TASK-01 → TASK-14 implementations.

Identify existing:

```text
Asset

AssetVariant

ProcessingRun

QualityReview

SimilarityFinding

StockMetadata

StockExport

StockSubmission

GenerationCost

AutomationRun
```

Reuse or extend existing entities.

Do not create parallel representations unnecessarily.

---

# 2. Verify Current Adobe Requirements

Before implementing Adobe-specific rules, inspect current official Adobe Stock Contributor documentation.

Document:

```text
supported image formats

minimum resolution

maximum resolution

file-size limits

JPEG requirements

color-space requirements

metadata rules

title rules

keyword rules

keyword limits

AI-content requirements

upload mechanisms

FTP/SFTP availability

CSV requirements

submission workflow
```

Do not hardcode assumptions from this task if current Adobe requirements differ.

Create:

```text
docs/adobe-stock-requirements.md
```

Include:

```text
verified date

official source references

implemented constraints

unsupported capabilities

manual steps still required
```

Adobe-specific rules must be configurable/versioned where appropriate.

---

# 3. Architecture

Target:

```text
                STOCK-READY ASSETS
                       │
                       ▼
              AdobeEligibilityService
                       │
          ┌────────────┴────────────┐
          ▼                         ▼
      ELIGIBLE                 NOT ELIGIBLE
          │
          ▼
     Adobe Validator
          │
          ▼
     Metadata Engine
          │
          ▼
   Submission Candidate
          │
          ▼
      Batch Manager
          │
          ▼
    Export Package Builder
          │
      ┌───┴────┐
      ▼        ▼
    JPEGs     CSV
      │        │
      └───┬────┘
          ▼
    Upload Transport
          │
          ▼
      Adobe Stock
          │
          ▼
   Submission Tracking
          │
          ▼
 Acceptance / Rejection
          │
          ▼
        TASK-14
```

---

# 4. Core Principle

Adobe export must operate only on:

```text
immutable publishable AssetVariant
```

Never modify:

```text
original generation
```

to satisfy marketplace requirements.

Use:

```text
Original
↓
TASK-06 Processing
↓
STOCK_MASTER
↓
ADOBE_STOCK_JPEG
↓
TASK-15 Submission
```

---

# 5. Adobe Stock Profile

Create a versioned:

```text
AdobeStockProfile
```

Suggested fields:

```text
id

name

version

enabled

imageRules

metadataRules

exportRules

transportRules

createdAt
```

Example:

```text
ADOBE_STOCK_STANDARD_V1
```

Rules must be based on verified current Adobe requirements.

---

# 6. Adobe Eligibility Service

Create:

```text
AdobeStockEligibilityService
```

Input:

```text
Asset
AssetVariant
QualityReview
Similarity status
Stock metadata
AdobeStockProfile
```

Output:

```text
ELIGIBLE

NEEDS_PROCESSING

NEEDS_METADATA

NEEDS_REVIEW

BLOCKED
```

with structured reasons.

---

# 7. Eligibility Must Be Explainable

Example:

```text
Asset:
AST-001827

Adobe eligibility:
BLOCKED

Reasons:

RESOLUTION_TOO_LOW
METADATA_MISSING
QA_NOT_APPROVED
```

Never return only:

```text
eligible = false
```

without reason codes.

---

# 8. Eligibility Requirements

At minimum evaluate:

```text
final QA approval

publication eligibility

duplicate policy

correct asset type

correct processing variant

Adobe technical requirements

required metadata

AI-content metadata if applicable
```

Reuse TASK-04 and TASK-05 decisions.

Do not independently reinterpret QA.

---

# 9. JPEG Validation

Create:

```text
AdobeImageValidator
```

Validate actual decoded image, not only filename extension.

Check:

```text
file readable

JPEG encoding

MIME

dimensions

megapixels

aspect ratio where relevant

file size

color mode

color profile

alpha absence

corruption

orientation

metadata consistency
```

---

# 10. 4MP+ Validation

Where current Adobe rules require the applicable minimum megapixel threshold, calculate:

```text
megapixels =
width × height / 1_000_000
```

Do not confuse:

```text
MP
```

with:

```text
MB
```

Persist:

```text
width

height

pixelCount

megapixels
```

---

# 11. Resolution Example

For:

```text
3000 × 2000
```

calculate:

```text
6,000,000 pixels
≈ 6 MP
```

Do not infer resolution from file size.

---

# 12. Upscale Integration

If an asset does not meet Adobe dimensions:

```text
Asset
↓
TASK-06 Upscale
↓
ADOBE_STOCK_JPEG
↓
Validation
```

TASK-15 must not implement another upscale engine.

Call TASK-06.

---

# 13. Upscale Safety

Do not automatically assume:

```text
upscaled to 4MP+
=
stock quality
```

After upscale:

```text
Technical QA
↓
Visual QA if configured
↓
Adobe Validation
```

must still pass.

---

# 14. Upscale Lineage

Persist lineage:

```text
Original Asset
↓
ProcessingRun
↓
Upscale
↓
Resize
↓
Sharpen / Denoise
↓
JPEG Encode
↓
Adobe Variant
```

with:

```text
input SHA

output SHA

processor

model

model version

settings

timestamps
```

Reuse TASK-06.

---

# 15. Adobe Variant

Use a dedicated variant type such as:

```text
ADOBE_STOCK_JPEG
```

if compatible with current AssetVariant architecture.

Never overwrite:

```text
STOCK_MASTER
```

---

# 16. JPEG Encoding

Use TASK-06 format/quality pipeline.

Configuration example:

```yaml
stock:
  adobe:
    jpeg:
      quality: 95
```

Treat value only as configurable example.

Do not repeatedly decode/re-encode JPEG through multiple stages.

Prefer:

```text
master
↓
one final Adobe encode
```

---

# 17. Color Handling

Inspect actual pipeline.

Ensure predictable:

```text
RGB

ICC profile

color space
```

according to verified Adobe requirements.

Do not silently produce:

```text
CMYK
```

or unsupported formats.

---

# 18. EXIF / Metadata

Define explicit policy:

```text
PRESERVE

STRIP

SELECTIVE
```

for embedded metadata.

Do not accidentally expose:

```text
local paths

machine usernames

internal prompts

provider request IDs

private metadata
```

inside exported files.

---

# 19. Filename Strategy

Generate deterministic safe filenames.

Example:

```text
mf_00001827_cybersecurity_network_security.jpg
```

Requirements:

```text
unique

filesystem safe

transport safe

stable

traceable
```

Do not expose internal secrets or full prompts.

---

# 20. Filename Mapping

Persist:

```text
assetId

variantId

submissionCandidateId

exportFilename
```

Never rely on filename parsing as the only mapping mechanism.

---

# 21. Metadata Engine

Reuse TASK-08 metadata generation.

TASK-15 should orchestrate Adobe-specific metadata preparation.

Required concepts:

```text
title

description where applicable

keywords

category where applicable

AI/generated-content declaration

release information where applicable
```

Only implement fields actually supported/required by Adobe workflow.

---

# 22. Adobe Metadata Snapshot

Create immutable:

```text
AdobeMetadataSnapshot
```

Suggested:

```text
id

assetId

stockMetadataId

profileVersion

title

description

keywords

category

aiGenerated

releaseReferences

createdAt
```

Once attached to an exported submission batch:

```text
immutable
```

---

# 23. Metadata Reproducibility

If metadata changes later:

```text
Metadata V1
→ Batch A

Metadata V2
→ Batch B
```

Batch A must still show exactly what was exported.

---

# 24. Metadata Generation

Flow:

```text
Asset
↓
Concept
↓
Prompt Snapshot
↓
Visual Attributes
↓
TASK-08 Metadata Generator
↓
Adobe Adapter
↓
Adobe Metadata Snapshot
```

---

# 25. Metadata Must Describe Image

Do not create metadata solely from original prompt.

Use available:

```text
actual asset

QA findings

vision description

concept

prompt
```

where existing architecture supports it.

The final image may differ from the prompt.

---

# 26. Title Generation

Title should be:

```text
accurate

commercially useful

natural

descriptive

not keyword spam
```

Implement deterministic validation after AI generation.

---

# 27. Keyword Generation

Generate ordered keywords.

Preserve:

```text
keyword

position

source
```

if TASK-08 supports it.

Keyword order may matter operationally.

---

# 28. Keyword Sources

Possible:

```text
VISION

CONCEPT

PROMPT

NICHE

MANUAL

LLM
```

Final output should be deduplicated and validated.

---

# 29. Keyword Normalization

Normalize:

```text
trim

case

duplicates

empty values

invalid separators

excess keywords

unsupported characters
```

Do not blindly stem words if that changes meaning.

---

# 30. Keyword Limits

Use verified Adobe limits.

Configuration:

```yaml
stock:
  adobe:
    metadata:
      max-keywords: <verified-value>
```

Do not hardcode an unverified number from memory.

---

# 31. Keyword Quality

Avoid:

```text
irrelevant keywords

hallucinated objects

trademark stuffing

duplicate singular/plural spam

trend keyword injection
```

---

# 32. Trademark / Brand Guard

Integrate existing policy/QA architecture where possible.

Flag suspicious:

```text
brand names

company names

logos

protected product names
```

for human review.

Do not claim exhaustive trademark detection.

---

# 33. Metadata Validation

Create:

```text
AdobeMetadataValidator
```

Output:

```text
VALID

WARNING

INVALID
```

with findings.

---

# 34. Metadata Findings

Examples:

```text
TITLE_MISSING

TITLE_TOO_LONG

KEYWORDS_MISSING

TOO_MANY_KEYWORDS

DUPLICATE_KEYWORD

INVALID_CHARACTER

POSSIBLE_TRADEMARK

DESCRIPTION_INVALID

AI_DECLARATION_MISSING
```

Use rules based on current Adobe requirements.

---

# 35. Human Metadata Override

Allow user to edit:

```text
title

description

keywords

category
```

before batch freeze.

Persist:

```text
generated value

final value

override author

override timestamp
```

where existing audit model supports it.

---

# 36. Submission Candidate

Create:

```text
AdobeSubmissionCandidate
```

Suggested:

```text
id

assetId

variantId

metadataSnapshotId

eligibilityStatus

validationStatus

batchId

createdAt
```

---

# 37. Candidate Lifecycle

Use:

```text
CREATED

VALIDATING

READY

BLOCKED

BATCHED

EXPORTED

UPLOADING

UPLOADED

SUBMITTED

IN_REVIEW

ACCEPTED

REJECTED

FAILED
```

Adapt to actual Adobe workflow.

---

# 38. Separate Upload From Submission

Important:

```text
UPLOADED
```

does not necessarily mean:

```text
SUBMITTED
```

and:

```text
SUBMITTED
```

does not mean:

```text
ACCEPTED
```

Model these states separately.

---

# 39. Batch Manager

Create:

```text
AdobeSubmissionBatchService
```

Responsibilities:

```text
select ready candidates

validate

freeze metadata

assign filenames

create manifest

create export package

upload

track
```

---

# 40. AdobeSubmissionBatch

Suggested:

```text
id

name

status

profileId

profileVersion

createdAt

frozenAt

exportedAt

uploadStartedAt

uploadCompletedAt

itemCount

totalBytes

createdBy
```

---

# 41. Batch Lifecycle

Use:

```text
DRAFT

VALIDATING

READY

FROZEN

EXPORTING

EXPORTED

UPLOADING

UPLOADED

PARTIALLY_FAILED

FAILED

COMPLETED

ARCHIVED
```

---

# 42. Draft Batch

While:

```text
DRAFT
```

allow:

```text
add assets

remove assets

edit metadata

rerun validation
```

---

# 43. Frozen Batch

Once:

```text
FROZEN
```

the following become immutable for that batch:

```text
asset variant

filename

metadata snapshot

profile version

manifest
```

Changes require a new batch/version.

---

# 44. Batch Size

Support configurable limits:

```yaml
stock:
  adobe:
    batch:
      max-items: 100
      max-total-bytes: ...
```

Values must be configuration, not assumptions.

---

# 45. Automatic Batch Splitting

If 1,000 candidates are selected and configured max is 100:

```text
Batch 001
100

Batch 002
100

...

Batch 010
100
```

Preserve parent export job if useful.

---

# 46. Batch Naming

Example:

```text
adobe_2026-09-24_ai-cybersecurity_001
```

Ensure uniqueness.

---

# 47. Preflight

Before freezing:

```text
ALL files
↓
ALL metadata
↓
ALL mappings
↓
ALL checks
```

Run full batch preflight.

---

# 48. Preflight Result

Create:

```text
AdobeBatchPreflightResult
```

Summary:

```text
READY:
94

WARNINGS:
4

BLOCKED:
2
```

Do not fail 98 valid assets merely because 2 are invalid unless strict mode is configured.

---

# 49. Strict vs Partial Mode

Support:

```text
STRICT
```

and:

```text
ALLOW_VALID_ITEMS
```

In strict mode:

```text
any blocked item
→ batch cannot freeze
```

In partial mode:

```text
valid items proceed
blocked items remain outside batch
```

---

# 50. Export Package

Create:

```text
AdobeExportPackage
```

Package structure:

```text
adobe-batch-2026-09-24-001/
│
├── images/
│   ├── mf_000001.jpg
│   ├── mf_000002.jpg
│   └── ...
│
├── metadata/
│   └── adobe.csv
│
├── manifest.json
└── checksums.sha256
```

Only include files appropriate for the external package.

---

# 51. Internal Manifest

`manifest.json` is primarily for Media Factory reproducibility.

Example structure:

```text
batchId

profileVersion

createdAt

items:
  assetId
  variantId
  filename
  sha256
  metadataSnapshotId
```

Do not put secrets into it.

---

# 52. Checksums

Create:

```text
checksums.sha256
```

for exported image files.

Persist checksums in DB as well.

---

# 53. CSV Exporter

Create:

```text
AdobeCsvExporter
```

Use exact current Adobe-compatible schema.

Do not invent columns.

Document:

```text
column

meaning

source field

required/optional

format
```

---

# 54. CSV Encoding

Use:

```text
UTF-8
```

unless Adobe explicitly requires something else.

Correctly escape:

```text
commas

quotes

newlines
```

Use a proper CSV library.

Do not manually concatenate strings.

---

# 55. CSV Determinism

Given same frozen batch:

```text
export
↓
delete local package
↓
export again
```

must produce semantically identical metadata.

Prefer byte-identical output where practical.

---

# 56. CSV Mapping

Persist:

```text
batchId

candidateId

rowNumber

filename
```

to support reconciliation.

---

# 57. CSV Validation

After writing CSV:

```text
parse it again
```

with the same schema.

Verify:

```text
row count

required fields

filenames

keywords

encoding
```

before package is marked EXPORTED.

---

# 58. Export Storage

Store immutable export artifacts using existing MediaStorage.

Suggested logical path:

```text
exports/
  adobe/
    <batch-id>/
```

Do not rely only on temporary local disk.

---

# 59. Local Download

Allow user to download:

```text
ZIP export package
```

for manual upload.

Manual workflow must remain first-class even when automated transport exists.

---

# 60. Transport Abstraction

Create:

```java
public interface StockSubmissionTransport {

    String transportId();

    TransportCapabilities capabilities();

    UploadResult upload(SubmissionPackage package);

}
```

Implement transport according to actual supported Adobe mechanisms.

---

# 61. Transport Types

Architecture may support:

```text
MANUAL_EXPORT

SFTP

FTP
```

and future:

```text
API
```

only if an official supported API exists.

Do not fake API submission through browser automation.

---

# 62. SFTP Implementation

If current Adobe Contributor workflow officially supports SFTP for the required upload:

create:

```text
AdobeSftpSubmissionTransport
```

Otherwise implement the actual supported transport and document why.

Do not force SFTP simply because TASK title mentions it if Adobe does not support it for the current contributor workflow.

---

# 63. SFTP Configuration

Configuration example:

```yaml
stock:
  adobe:
    transport:
      type: SFTP
      host: ${ADOBE_STOCK_SFTP_HOST}
      port: 22
      username: ${ADOBE_STOCK_SFTP_USERNAME}
      password: ${ADOBE_STOCK_SFTP_PASSWORD}
```

Adapt authentication to actual Adobe requirements.

---

# 64. Secrets

Never store credentials in:

```text
database plaintext

Git

manifest

CSV

logs

frontend
```

Use environment/secrets infrastructure.

---

# 65. Credential Redaction

Logs:

```text
host:
visible if safe

username:
redacted where appropriate

password:
NEVER

private key:
NEVER
```

---

# 66. Host Key Verification

For SFTP:

```text
host key verification
```

must be enabled.

Do not use:

```text
StrictHostKeyChecking=no
```

equivalent in production.

---

# 67. SFTP Upload Strategy

Prefer:

```text
temporary remote filename
↓
upload complete
↓
verify
↓
atomic rename
```

if supported by server.

This prevents partial files appearing as completed uploads.

---

# 68. Streaming

Do not load entire large JPEG batches into JVM memory.

Use streaming:

```text
MediaStorage
↓
InputStream
↓
SFTP
```

---

# 69. Upload Concurrency

Configurable:

```yaml
stock:
  adobe:
    upload:
      concurrency: 3
```

Do not assume more concurrency is always better.

---

# 70. Retry

Retry transient:

```text
connection reset

timeout

temporary server failure
```

with:

```text
bounded exponential backoff
+
jitter
```

Do not retry authentication failures indefinitely.

---

# 71. Upload Attempt

Create:

```text
AdobeUploadAttempt
```

Suggested:

```text
id

batchId

candidateId

transport

attempt

startedAt

completedAt

status

bytesTransferred

errorCode

errorMessage

retryable
```

---

# 72. Upload Status

Use:

```text
STARTED

SUCCEEDED

FAILED

TIMED_OUT

AUTHENTICATION_FAILED

REMOTE_REJECTED
```

---

# 73. Resume

If transport supports reliable resume:

```text
resume partial transfer
```

Otherwise:

```text
restart individual file
```

Do not re-upload the entire batch because one file failed.

---

# 74. Idempotency

Before upload:

```text
remote filename
+
size/checksum if available
```

should be used to avoid accidental duplicate transfers where possible.

---

# 75. Partial Upload

Example:

```text
100 files

97 succeeded
3 failed
```

Batch:

```text
PARTIALLY_FAILED
```

Retry only the failed three.

---

# 76. Upload Verification

After transfer verify where protocol/server supports:

```text
remote existence

remote size

checksum
```

Do not claim checksum verification if server cannot provide it.

Persist verification level:

```text
NONE

EXISTS

SIZE

CHECKSUM
```

---

# 77. CSV Upload

If Adobe workflow requires CSV to be transferred with images:

upload according to verified ordering requirements.

If not required by transport:

keep CSV as export artifact.

Do not invent workflow behavior.

---

# 78. Submission Tracking

Extend/create:

```text
AdobeStockSubmission
```

Suggested:

```text
id

candidateId

batchId

assetId

variantId

metadataSnapshotId

externalAssetId

transport

uploadedAt

submittedAt

reviewedAt

acceptedAt

rejectedAt

status

externalStatus

lastSyncedAt
```

---

# 79. Tracking State

Separate:

```text
transport state
```

from:

```text
marketplace review state
```

Example:

```text
transport:
UPLOADED

marketplace:
UNKNOWN
```

is valid.

---

# 80. External Asset ID

When Adobe exposes an identifier:

```text
externalAssetId
```

must become the primary reconciliation key.

Do not use title as identity.

---

# 81. Reconciliation

Create:

```text
AdobeSubmissionReconciliationService
```

Potential mapping inputs:

```text
externalAssetId

filename

batch

submission date

checksum where available
```

---

# 82. Ambiguous Match

If external record could map to multiple assets:

```text
AMBIGUOUS
```

Do not guess.

Send to reconciliation queue.

---

# 83. Tracking Capabilities

Use the TASK-14 marketplace capability model.

Possible:

```text
STATUS_SYNC

ACCEPTANCE_SYNC

REJECTION_SYNC

SALES_SYNC
```

Only run supported operations.

---

# 84. No Fake Status Polling

If Adobe does not expose automated review status:

do not scrape contributor UI.

Support:

```text
manual status import

CSV/report import
```

and document limitation.

---

# 85. Acceptance Import

When an asset becomes:

```text
ACCEPTED
```

persist:

```text
acceptedAt

externalAssetId

source
```

and emit domain event.

---

# 86. Rejection Import

When:

```text
REJECTED
```

persist:

```text
rejectedAt

originalReason

normalizedReason

source
```

---

# 87. Rejection Normalization

Reuse TASK-14 categories.

Examples:

```text
QUALITY

TECHNICAL

SIMILAR_CONTENT

INTELLECTUAL_PROPERTY

METADATA

COMMERCIAL_VALUE

CONTENT_POLICY

UNKNOWN
```

Do not discard Adobe's original reason.

---

# 88. Domain Events

Emit:

```text
ADOBE_BATCH_CREATED

ADOBE_BATCH_EXPORTED

ADOBE_UPLOAD_STARTED

ADOBE_UPLOAD_COMPLETED

ADOBE_UPLOAD_FAILED

ADOBE_SUBMISSION_ACCEPTED

ADOBE_SUBMISSION_REJECTED
```

Use existing event architecture.

---

# 89. TASK-14 Integration

Flow:

```text
Adobe Acceptance / Rejection
↓
TASK-14 StockSubmission
↓
Stock Economics
↓
TASK-11 Feedback
```

TASK-15 should not calculate ROI itself.

---

# 90. TASK-11 Integration

Rejections should feed Feedback Engine.

Example:

```text
Adobe:
SIMILAR_CONTENT
↓
TASK-11
↓
TASK-05 analysis
```

or:

```text
Adobe:
QUALITY
↓
TASK-11
↓
TASK-04 / TASK-06 analysis
```

---

# 91. TASK-13 Integration

Night automation may:

```text
generate
↓
QA
↓
similarity
↓
process
↓
generate metadata
↓
prepare Adobe batch
```

But external upload/submission must follow configured automation policy.

---

# 92. Automatic Upload Policy

Create:

```text
AdobeSubmissionAutomationPolicy
```

Options:

```text
MANUAL_ONLY

AUTO_PREPARE

AUTO_EXPORT

AUTO_UPLOAD
```

Do not automatically enable the highest automation level.

---

# 93. Default Automation

Safe default:

```text
AUTO_PREPARE
```

Meaning:

```text
validate
+
metadata
+
batch preparation
```

but user controls external transfer.

Make actual default configurable if project conventions dictate otherwise.

---

# 94. Auto Upload Requirements

If:

```text
AUTO_UPLOAD
```

require:

```text
QA approved

similarity accepted

Adobe validation passed

metadata valid

batch frozen

credentials valid

upload budget/capacity valid
```

---

# 95. Human Gate

Support optional:

```text
REQUIRE_BATCH_APPROVAL_BEFORE_UPLOAD
```

Recommended for initial rollout.

---

# 96. Batch Approval

Store:

```text
approvedBy

approvedAt
```

before external transfer when policy requires.

---

# 97. Batch Review UI

Add:

```text
Stock
→ Adobe Submission
```

Pages:

```text
Candidates

Batches

Uploads

Submissions

Rejections

Reconciliation

Settings
```

---

# 98. Candidates Page

Grid/table showing:

```text
preview

asset

niche

dimensions

MP

QA

similarity

metadata

Adobe eligibility

status
```

---

# 99. Candidate Filters

Support:

```text
READY

NEEDS_PROCESSING

NEEDS_METADATA

BLOCKED

BATCHED
```

plus:

```text
niche

collection

date

automation run
```

---

# 100. Candidate Bulk Actions

Support:

```text
generate metadata

validate

process for Adobe

add to batch

exclude
```

---

# 101. Metadata Editor

Side panel:

```text
Preview

Title

Description

Keywords

Category

AI declaration

Validation findings
```

Allow keyboard-efficient review.

---

# 102. Keyword UI

Display ordered keywords as chips.

Support:

```text
drag reorder

remove

add

regenerate
```

Preserve manual overrides.

---

# 103. Batch Page

Show:

```text
batch status

item count

total bytes

profile version

preflight

warnings

blocked items

export package

upload status
```

---

# 104. Batch Item Table

Columns:

```text
preview

filename

dimensions

MP

JPEG validation

metadata validation

upload status

Adobe status
```

---

# 105. Upload Progress

Show:

```text
97 / 100

2.1 GB / 2.2 GB

active:
3

failed:
3
```

Use actual values.

---

# 106. Retry UI

Allow:

```text
Retry failed
```

not:

```text
Upload everything again
```

---

# 107. Submission Dashboard

Summary:

```text
Prepared

Exported

Uploaded

In Review

Accepted

Rejected

Unknown
```

---

# 108. Acceptance Rate

Display via TASK-14/TASK-10 where appropriate.

Do not implement competing analytics.

---

# 109. Rejections Page

Show:

```text
preview

asset

Adobe reason

normalized reason

internal QA

similarity

provider

prompt version

processing profile
```

This makes rejection feedback actionable.

---

# 110. Reconciliation Page

Show:

```text
external record

possible internal asset

confidence/evidence

status
```

Actions:

```text
LINK

IGNORE

MARK UNKNOWN
```

Do not auto-link ambiguous records.

---

# 111. Settings

Expose:

```text
Adobe profile

transport

batch limits

JPEG profile

automation policy

credential status
```

Never return credential values to frontend.

---

# 112. Credential Test

Provide:

```text
Test Connection
```

backend operation.

Result:

```text
SUCCESS

AUTH_FAILED

HOST_UNREACHABLE

HOST_KEY_FAILED

TIMEOUT
```

No secrets in response.

---

# 113. Health

Create health indicators:

```text
Adobe Transport:
HEALTHY

DEGRADED

UNAVAILABLE

NOT_CONFIGURED
```

Do not perform aggressive repeated login attempts.

---

# 114. Background Jobs

Use existing job architecture.

Potential jobs:

```text
PREPARE_ADOBE_VARIANT

GENERATE_ADOBE_METADATA

VALIDATE_ADOBE_CANDIDATE

BUILD_ADOBE_BATCH

EXPORT_ADOBE_BATCH

UPLOAD_ADOBE_BATCH

RETRY_ADOBE_UPLOAD

SYNC_ADOBE_SUBMISSIONS

IMPORT_ADOBE_RESULTS

RECONCILE_ADOBE_SUBMISSIONS
```

---

# 115. Job Idempotency

Every job must be safe to retry.

Examples:

```text
PREPARE_ADOBE_VARIANT
```

identity:

```text
assetId
+
processingProfileVersion
```

and:

```text
EXPORT_ADOBE_BATCH
```

identity:

```text
frozenBatchId
+
exportVersion
```

---

# 116. Processing Failure

If Adobe variant creation fails:

```text
candidate
→ BLOCKED
```

with:

```text
PROCESSING_FAILED
```

Do not silently substitute the original image.

---

# 117. Metadata Failure

If LLM metadata generation fails:

```text
NEEDS_METADATA
```

not entire pipeline failure.

Allow manual metadata.

---

# 118. Upload Failure

Transport failure must not change:

```text
Asset

Variant

Metadata Snapshot
```

Retry transfer only.

---

# 119. Storage Failure

If exported package cannot be persisted:

```text
EXPORT_FAILED
```

Do not mark batch EXPORTED.

---

# 120. Database Transaction Boundaries

Do not keep DB transactions open during:

```text
image processing

large file copying

SFTP upload

network calls
```

Use state transitions around external operations.

---

# 121. Observability

Metrics:

```text
media_factory_adobe_candidates_total

media_factory_adobe_validation_failures_total

media_factory_adobe_batches_total

media_factory_adobe_export_duration_seconds

media_factory_adobe_upload_bytes_total

media_factory_adobe_upload_duration_seconds

media_factory_adobe_upload_failures_total

media_factory_adobe_submissions_accepted_total

media_factory_adobe_submissions_rejected_total
```

Avoid high-cardinality labels.

---

# 122. Structured Logs

Include:

```text
batchId

candidateId

assetId

variantId

uploadAttemptId

transport

stage

status
```

Never:

```text
password

private key

raw credentials
```

---

# 123. Audit

Record:

```text
ADOBE_CANDIDATE_CREATED

ADOBE_METADATA_GENERATED

ADOBE_METADATA_OVERRIDDEN

ADOBE_BATCH_CREATED

ADOBE_BATCH_FROZEN

ADOBE_BATCH_APPROVED

ADOBE_BATCH_EXPORTED

ADOBE_UPLOAD_STARTED

ADOBE_UPLOAD_RETRIED

ADOBE_UPLOAD_COMPLETED

ADOBE_STATUS_IMPORTED
```

---

# 124. Database

Create/refine tables:

```text
adobe_stock_profile

adobe_submission_candidate

adobe_metadata_snapshot

adobe_submission_batch

adobe_submission_batch_item

adobe_export_package

adobe_upload_attempt

adobe_stock_submission
```

Reuse TASK-14 marketplace tables where practical.

Do not duplicate `StockSubmission` if it already models this correctly.

---

# 125. Indexes

Consider:

```text
candidate.asset_id

candidate.status

batch.status

batch.created_at

batch_item.batch_id

batch_item.candidate_id

submission.asset_id

submission.external_asset_id

submission.status

upload_attempt.batch_id

upload_attempt.status
```

---

# 126. Unique Constraints

Examples:

```text
batch_item(batch_id, candidate_id)

submission(platform, external_asset_id)
```

where semantically valid.

---

# 127. Tests — Image Validation

Fixtures:

```text
valid JPEG

corrupt JPEG

PNG renamed .jpg

too-small image

valid 4MP+

CMYK image

wrong MIME

truncated JPEG

huge file

rotated EXIF image
```

---

# 128. Tests — Megapixels

Verify exact boundary around current configured Adobe minimum.

Do not use rounded display value for validation.

Use exact pixel count.

---

# 129. Tests — Upscale

Input below minimum:

```text
Asset
↓
TASK-06
↓
Adobe Variant
↓
Validation
```

Verify immutable lineage.

---

# 130. Tests — Metadata

Test:

```text
valid title

missing title

too-long title

duplicate keywords

keyword overflow

empty keyword

possible trademark

manual override
```

---

# 131. Tests — Metadata Snapshot

After batch freeze:

```text
edit original StockMetadata
```

must NOT alter frozen AdobeMetadataSnapshot.

---

# 132. Tests — CSV

Verify:

```text
UTF-8

quotes

commas

newlines

keyword serialization

filename mapping

row count
```

Then parse exported CSV again.

---

# 133. Tests — Deterministic Export

Same frozen batch exported twice must produce equivalent package contents.

---

# 134. Tests — Checksums

Modify exported JPEG after package creation.

Verification must detect mismatch.

---

# 135. Tests — Batch

Test:

```text
draft

validation

freeze

export

partial validation

strict validation

split batches
```

---

# 136. Tests — SFTP

Use local/mock SFTP server.

Test:

```text
connection

authentication failure

host-key failure

successful upload

timeout

connection reset

partial transfer

retry

duplicate retry
```

No real Adobe connection in CI.

---

# 137. Tests — Partial Upload

Given:

```text
100 items
```

simulate:

```text
97 success
3 failure
```

Verify retry only targets failed items.

---

# 138. Tests — Restart

Scenario:

```text
upload 50/100
↓
backend crash
↓
restart
```

Verify reconciliation and safe continuation.

Do not blindly upload all 100 again.

---

# 139. Tests — Submission Tracking

Test:

```text
UPLOADED

SUBMITTED

IN_REVIEW

ACCEPTED

REJECTED

UNKNOWN
```

according to supported integration.

---

# 140. Tests — Reconciliation

Test:

```text
external ID exact match

filename match

ambiguous filename

unknown external record
```

Never auto-link ambiguous records.

---

# 141. Tests — Rejection Feedback

```text
Adobe rejected:
SIMILAR_CONTENT
```

Verify:

```text
StockSubmission
↓
TASK-14
↓
TASK-11
```

receives normalized + original reason.

---

# 142. Tests — Security

Verify:

```text
credentials not returned by API

credentials not logged

credentials not written into export

host key verification enabled
```

---

# 143. Integration Test — Manual Export

```text
10 Stock-Ready Assets
↓
Adobe Validation
↓
Metadata
↓
Batch
↓
Freeze
↓
CSV
↓
JPEG Package
↓
ZIP
```

Verify user can manually submit the resulting package.

---

# 144. Integration Test — Automated Upload

Using mock/local SFTP:

```text
100 Stock-Ready Assets
↓
Validation
↓
Metadata
↓
Batch
↓
Freeze
↓
Export
↓
SFTP
↓
Verification
↓
UPLOADED
```

---

# 145. Integration Test — Mixed Quality

Input:

```text
100 assets

90 valid
5 below resolution
3 missing metadata
2 QA blocked
```

In partial mode:

```text
90
→ batch

10
→ actionable blocked states
```

---

# 146. Integration Test — Full Adobe Feedback Loop

```text
Generation
↓
TASK-04 QA
↓
TASK-05 Similarity
↓
TASK-06 Processing
↓
TASK-08 Stock Factory
↓
TASK-15 Adobe Batch
↓
Upload
↓
Adobe Result Import
↓
Acceptance / Rejection
↓
TASK-14 Economics
↓
TASK-11 Feedback
```

---

# 147. CI

CI must use:

```text
mock Adobe marketplace

local/mock SFTP

local object storage

mock metadata provider
```

No:

```text
real Adobe credentials

real Adobe uploads

paid LLM

paid image generation
```

---

# 148. Documentation

Create:

```text
docs/adobe-stock-requirements.md

docs/adobe-stock-export.md

docs/adobe-stock-metadata.md

docs/adobe-stock-csv.md

docs/adobe-stock-upload.md

docs/adobe-stock-submission-tracking.md

docs/adobe-stock-reconciliation.md

docs/adobe-stock-security.md
```

---

# 149. Operations Runbook

Create:

```text
docs/runbooks/adobe-stock-submission.md
```

Include:

```text
SFTP connection fails

credentials rejected

host key changed

batch partially uploaded

CSV invalid

file rejected

metadata invalid

status cannot sync

submission cannot reconcile

restart during upload

safe retry

manual export fallback
```

---

# 150. Definition of Done

TASK-15 is complete when the user can select:

```text
500 stock-ready assets
```

and Media Factory can:

```text
Select Assets
↓
Validate QA / Similarity
↓
Validate Adobe Eligibility
↓
Create Missing Adobe Variants
↓
Validate JPEG
↓
Validate 4MP+
↓
Generate Metadata
↓
Validate Metadata
↓
Create Submission Candidates
↓
Split Into Batches
↓
Freeze Batch
↓
Generate Adobe-Compatible CSV
↓
Generate JPEG Export Package
↓
Generate Manifest
↓
Generate Checksums
↓
Export ZIP
```

and, when configured/supported:

```text
Upload Batch
↓
Verify Transfer
↓
Track Submission
↓
Import Acceptance / Rejection
↓
Feed TASK-14 / TASK-11
```

---

# 151. Acceptance Scenario — 1,000 Assets

Input:

```text
1,000 stock-ready assets
```

Configured:

```text
max batch:
100
```

Expected:

```text
Validation
↓
Metadata
↓
10 Adobe Batches
↓
10 CSV Manifests
↓
10 Immutable Export Packages
```

Each asset must retain:

```text
Asset
↓
Variant
↓
Metadata Snapshot
↓
Batch
↓
Filename
↓
Upload Attempt
↓
Adobe Submission
```

---

# 152. Acceptance Scenario — Invalid Assets

Given:

```text
Asset A
3.2 MP

Asset B
valid resolution
missing metadata

Asset C
QA rejected

Asset D
valid
```

Expected:

```text
A
→ NEEDS_PROCESSING

B
→ NEEDS_METADATA

C
→ BLOCKED

D
→ READY
```

No invalid asset silently enters the Adobe batch.

---

# 153. Acceptance Scenario — Upload Failure

Given:

```text
Batch:
100 assets
```

During upload:

```text
97 succeed
3 timeout
```

Expected:

```text
Batch:
PARTIALLY_FAILED
```

Retry:

```text
3 failed files only
```

Then:

```text
100 / 100 verified
↓
UPLOADED
```

---

# 154. Acceptance Scenario — Adobe Rejection

Adobe result:

```text
Asset:
AST-1938

Result:
REJECTED

Reason:
similar content
```

Expected:

```text
AdobeStockSubmission
↓
REJECTED
↓
original reason preserved
↓
normalized:
SIMILAR_CONTENT
↓
TASK-14
↓
TASK-11
↓
TASK-05 analysis available
```

---

# 155. Verification Checklist

Before completing TASK-15:

1. inspect TASK-01 → TASK-14;
2. verify current official Adobe contributor requirements;
3. document verified requirements;
4. inspect actual Adobe upload capabilities;
5. create versioned Adobe profile;
6. implement eligibility service;
7. integrate TASK-04 QA;
8. integrate TASK-05 similarity;
9. integrate TASK-06 processing;
10. implement JPEG validator;
11. implement exact megapixel validation;
12. implement Adobe variant;
13. preserve processing lineage;
14. implement safe JPEG encoding;
15. implement color handling;
16. implement EXIF policy;
17. implement deterministic filenames;
18. persist filename mapping;
19. integrate TASK-08 metadata;
20. implement Adobe metadata snapshot;
21. implement metadata validation;
22. implement keyword normalization;
23. implement trademark warnings;
24. implement manual metadata override;
25. implement submission candidate;
26. implement candidate lifecycle;
27. implement batch manager;
28. implement batch lifecycle;
29. implement preflight;
30. implement strict mode;
31. implement partial mode;
32. implement batch splitting;
33. implement freeze semantics;
34. implement export package;
35. implement manifest;
36. implement SHA-256 checksums;
37. implement Adobe-compatible CSV;
38. validate CSV by re-parsing;
39. implement immutable export storage;
40. implement ZIP/manual export;
41. implement transport abstraction;
42. verify whether SFTP is supported;
43. implement supported Adobe transport;
44. implement secure credentials;
45. implement host-key verification;
46. implement streaming upload;
47. implement bounded upload concurrency;
48. implement retry/backoff;
49. implement upload attempts;
50. implement partial upload recovery;
51. implement upload verification;
52. implement restart recovery;
53. implement submission tracking;
54. separate transport/review states;
55. implement external-ID mapping;
56. implement reconciliation;
57. implement ambiguous reconciliation state;
58. implement acceptance import where supported;
59. implement rejection import where supported;
60. preserve original rejection reason;
61. normalize rejection reason;
62. integrate TASK-14;
63. integrate TASK-11;
64. integrate TASK-13;
65. implement automation policy;
66. implement human batch approval gate;
67. implement Candidates UI;
68. implement Metadata Editor;
69. implement Batches UI;
70. implement Uploads UI;
71. implement Submissions UI;
72. implement Rejections UI;
73. implement Reconciliation UI;
74. implement Settings;
75. implement credential connection test;
76. implement health state;
77. implement background jobs;
78. implement job idempotency;
79. implement metrics;
80. implement structured logs;
81. implement audit events;
82. test JPEG validation;
83. test exact 4MP boundary;
84. test upscale integration;
85. test metadata;
86. test metadata immutability;
87. test CSV escaping;
88. test CSV schema;
89. test deterministic export;
90. test checksums;
91. test batch splitting;
92. test strict/partial preflight;
93. test SFTP/mock transport;
94. test authentication failure;
95. test host-key failure;
96. test timeout;
97. test partial transfer;
98. test retries;
99. test restart recovery;
100. test reconciliation;
101. test rejection feedback;
102. test credential leakage;
103. run manual-export integration;
104. run automated-upload integration;
105. run mixed-quality integration;
106. run full Adobe-feedback loop;
107. verify CI uses no real Adobe credentials;
108. verify CI performs no external uploads;
109. run backend tests;
110. run frontend tests;
111. run production builds;
112. document limitations.

---

# 156. Final Codex Report

At completion provide:

## Verified Adobe Requirements

Report:

```text
documentation checked

verification date

image requirements

metadata requirements

CSV format

supported transport

manual steps
```

Do not report assumptions as facts.

## Validation

Report:

```text
JPEG validation

megapixel calculation

color handling

processing integration

Adobe eligibility
```

## Metadata

Report:

```text
title

description

keywords

AI declaration

validation

manual overrides

snapshot immutability
```

## Batch Management

Report:

```text
candidate lifecycle

batch lifecycle

preflight

batch splitting

freeze semantics
```

## Export

Report:

```text
JPEG package

CSV

manifest

checksums

ZIP

storage
```

## Upload

Report:

```text
implemented transport

authentication

host verification

streaming

concurrency

retry

partial failure

verification
```

## Tracking

Report:

```text
upload status

submission status

external ID

acceptance

rejection

reconciliation
```

## Integrations

Explain integration with:

```text
TASK-04 QA

TASK-05 Similarity

TASK-06 Processing

TASK-08 Stock Factory

TASK-10 Analytics

TASK-11 Feedback

TASK-13 Automation

TASK-14 Stock Intelligence
```

## Security

Report:

```text
secret storage

credential redaction

host verification

frontend credential protection
```

## Tests

Report all:

```text
validation

metadata

CSV

batching

export

SFTP/transport

recovery

tracking

reconciliation

security

full pipeline
```

## Remaining Limitations

Explicitly document:

```text
Adobe API limitations

upload transport limitations

manual contributor actions

review-status synchronization limitations

sales-data synchronization limitations

CSV limitations

metadata limitations
```

---

# Engineering Principles

1. Adobe export is a delivery layer, not a second Stock Factory.
2. Reuse TASK-08 metadata.
3. Reuse TASK-06 processing.
4. Reuse TASK-04 QA.
5. Reuse TASK-05 similarity.
6. Reuse TASK-14 submission/economic feedback.
7. Never modify originals.
8. Every Adobe file is an immutable derived variant.
9. Every derived variant has processing lineage.
10. Validate actual image bytes, not extension.
11. MP is not MB.
12. Calculate megapixels from exact dimensions.
13. Upscaling does not guarantee stock quality.
14. Final Adobe variant must pass validation.
15. Avoid unnecessary JPEG re-encoding.
16. Use explicit color-management policy.
17. Do not leak private EXIF/internal metadata.
18. Export filenames must be deterministic.
19. Filenames are not database identity.
20. Metadata must describe the actual image.
21. Prompt alone is insufficient evidence for metadata.
22. Keywords must be relevant.
23. Do not keyword-spam.
24. Do not fabricate visible objects in metadata.
25. Metadata snapshots become immutable after batch freeze.
26. Historical batches must remain reproducible.
27. Use actual current Adobe requirements.
28. Version Adobe-specific rules.
29. Never silently change a frozen batch.
30. Batch preflight happens before upload.
31. Invalid assets do not silently enter batches.
32. Support strict and partial batch modes.
33. Large exports must be automatically batchable.
34. CSV must use a proper CSV serializer.
35. CSV must be validated after generation.
36. Export packages must contain checksums.
37. Export packages must be reproducible.
38. Manual export remains a first-class fallback.
39. External transport must be abstracted.
40. Do not assume Adobe provides an API that does not exist.
41. Do not scrape Adobe contributor UI as a hidden API.
42. Verify current transport support before implementation.
43. SFTP credentials are secrets.
44. Never store secrets in Git.
45. Never return secrets to frontend.
46. Never log secrets.
47. Verify SFTP host keys.
48. Stream large files.
49. Bound upload concurrency.
50. Retry only transient failures.
51. Authentication failures are not endlessly retryable.
52. Partial batch failure must not restart successful transfers.
53. Persist every upload attempt.
54. Upload verification level must be explicit.
55. `UPLOADED != SUBMITTED`.
56. `SUBMITTED != ACCEPTED`.
57. Transport status and marketplace status are separate.
58. External asset ID is preferred reconciliation identity.
59. Never guess ambiguous external mappings.
60. Adobe original rejection reason must be preserved.
61. Normalized rejection reason is additional metadata.
62. Rejections are learning signals.
63. Acceptance is not sales.
64. Sales are not profit without costs.
65. TASK-15 must not calculate competing ROI.
66. TASK-14 remains responsible for profitability.
67. TASK-11 remains responsible for feedback/experiments.
68. TASK-13 remains responsible for scheduling.
69. Automatic external upload requires explicit policy.
70. Human approval gate must be supported.
71. New installations should favor safe automation.
72. Jobs must be idempotent.
73. Restart must not duplicate entire uploads.
74. Network calls must not hold DB transactions.
75. Every exported asset must be traceable:

```text
Concept
↓
Generation
↓
Asset
↓
QA
↓
Similarity
↓
ProcessingRun
↓
ADOBE_STOCK_JPEG
↓
AdobeMetadataSnapshot
↓
SubmissionCandidate
↓
SubmissionBatch
↓
ExportPackage
↓
UploadAttempt
↓
AdobeStockSubmission
↓
Acceptance / Rejection
```

76. Every Adobe batch must be auditable.
77. Every metadata override must be auditable.
78. Every external transfer must be auditable.
79. CI must never upload to Adobe.
80. CI must never contain real Adobe credentials.
81. The final operational workflow should be:

```text
STOCK INTELLIGENCE
↓
CONCEPT
↓
GENERATION
↓
QA
↓
SIMILARITY
↓
PROCESSING
↓
STOCK READY
↓
ADOBE ELIGIBILITY
↓
METADATA
↓
BATCH
↓
PREFLIGHT
↓
FREEZE
↓
CSV + JPEG PACKAGE
↓
UPLOAD / MANUAL EXPORT
↓
TRACK
↓
ACCEPT / REJECT
↓
TASK-14 ECONOMICS
↓
TASK-11 LEARNING
↺
```

The result of TASK-15 should make Media Factory capable of reliably moving from **hundreds of internally approved stock assets to reproducible Adobe Stock submission batches**, while preserving validation, metadata quality, upload safety, immutable lineage, external-status tracking and the profitability feedback loop required by TASK-14.
