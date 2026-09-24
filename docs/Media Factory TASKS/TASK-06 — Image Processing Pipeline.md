# TASK-06 — Image Processing Pipeline

## Objective

Build a production-ready Image Processing Pipeline for Media Factory.

The pipeline must transform QA-approved generated master images into high-quality derivatives suitable for:

* stock platforms;
* Android wallpapers;
* phone wallpapers;
* social media;
* thumbnails;
* previews;
* future video generation.

Implement:

* GPU-accelerated AI upscaling;
* CPU fallback;
* configurable upscale models;
* smart crop;
* subject-aware crop;
* subject-aware resize;
* focal-region detection;
* aspect-ratio conversion;
* deterministic resize;
* optional denoise;
* optional sharpening;
* format conversion;
* compression;
* quality optimization;
* color-profile handling;
* metadata handling;
* stock 4MP+ validation;
* wallpaper variants;
* configurable processing profiles;
* immutable processing lineage;
* processing manifests;
* reproducibility;
* batch processing;
* processing jobs;
* retry/recovery;
* idempotency;
* processing QA;
* processing metrics;
* local GPU utilization;
* frontend processing controls.

Primary pipeline:

```text
QA-APPROVED MASTER
        ↓
Processing Profile
        ↓
GPU Upscale
        ↓
Smart Crop
        ↓
Subject-Aware Resize
        ↓
Optional Denoise
        ↓
Optional Sharpen
        ↓
Color / Format Processing
        ↓
Compression Optimization
        ↓
Output Validation
        ↓
┌─────────────┬──────────────┐
│ STOCK       │ WALLPAPER    │
│ 4MP+        │ VARIANTS     │
└─────────────┴──────────────┘
        ↓
Immutable Asset Variants
```

The original generated asset must NEVER be modified.

---

# 1. Inspect Existing Architecture First

Before implementation:

1. inspect TASK-01 foundation;
2. inspect TASK-02 provider/job architecture;
3. inspect TASK-03 Prompt Engine;
4. inspect TASK-04 QA;
5. inspect TASK-05 Similarity Engine;
6. inspect `Asset` / `AssetVariant`;
7. inspect `MediaStorage`;
8. inspect existing image utilities;
9. inspect Docker/worker architecture;
10. inspect GPU availability/configuration;
11. inspect job retry/idempotency conventions.

Search specifically for:

```text
resize
crop
thumbnail
image conversion
AssetVariant
MediaStorage
ImageIO
Pillow
OpenCV
FFmpeg
upscale
```

Reuse existing infrastructure.

Do not create duplicate media-storage or job systems.

---

# 2. Core Architectural Principle

Separate:

```text
WHAT should be produced
```

from:

```text
HOW it is produced
```

Use:

```text
ProcessingProfile
        ↓
ProcessingPlan
        ↓
ProcessingPipeline
        ↓
ProcessingProvider(s)
        ↓
AssetVariant
```

Example:

```text
STOCK_4K
```

defines desired output.

It must not directly contain implementation-specific code.

---

# 3. Immutable Processing

The most important rule:

```text
ORIGINAL
```

is immutable.

Never:

```text
load original
↓
modify
↓
overwrite original.jpg
```

Instead:

```text
Original Asset #100
       │
       ├── Upscaled Asset #101
       │
       ├── Stock Variant #102
       │
       ├── Wallpaper Variant #103
       │
       └── Thumbnail #104
```

Every derivative must preserve lineage.

---

# 4. Processing Lineage

Implement explicit lineage:

```text
Asset
 │
 └── ProcessingRun
       │
       ├── Step 1 UPSCALE
       │
       ├── Step 2 SMART_CROP
       │
       ├── Step 3 RESIZE
       │
       ├── Step 4 SHARPEN
       │
       └── Step 5 ENCODE
              ↓
          AssetVariant
```

Historical derivatives must remain reproducible.

---

# 5. Domain Model

Introduce/refine:

```text
ProcessingProfile

ProcessingProfileVersion

ProcessingRun

ProcessingStep

ProcessingArtifact

AssetVariant

ProcessingValidationResult
```

Potential relationships:

```text
Asset
 ↓
ProcessingRun
 ↓
ProcessingProfileVersion
 ↓
ProcessingStep[]
 ↓
AssetVariant
```

---

# 6. Processing Profiles

Create reusable profiles.

Initial profiles:

```text
STOCK_STANDARD

STOCK_4K

WALLPAPER_ANDROID

WALLPAPER_PHONE

SOCIAL_PORTRAIT

SOCIAL_SQUARE

THUMBNAIL

PREVIEW
```

Profiles define target requirements rather than hardcoding them into services.

---

# 7. Profile Versioning

Processing profiles must be versioned.

Example:

```text
WALLPAPER_ANDROID

v1
v2
v3
```

Once used in production:

```text
PUBLISHED
```

profile versions become immutable.

Changes create:

```text
v4 DRAFT
```

This follows TASK-03 prompt-versioning principles.

---

# 8. Processing Profile Example

Conceptual configuration:

```yaml
processing:

  profiles:

    stock-4k:

      upscale:
        enabled: true
        minimum-width: 2400
        minimum-height: 2400
        max-scale: 4

      crop:
        mode: PRESERVE

      denoise:
        enabled: false

      sharpen:
        enabled: true
        strength: 0.15

      output:
        format: JPEG
        quality: 95
        color-space: SRGB

      validation:
        minimum-megapixels: 4
```

Actual implementation may use DB-managed profile versions rather than YAML.

---

# 9. Processing Plan

Before processing begins, resolve the profile into an explicit plan.

Example:

```text
ProcessingPlan

Input:
1536 × 1024 PNG

Target:
STOCK_4K

Steps:

1 UPSCALE 2x
2 RESIZE 4096 × 2731
3 SHARPEN 0.15
4 CONVERT JPEG
5 COMPRESS Q95
6 VALIDATE
```

Persist the resolved plan.

Do not reconstruct historical processing runs from current profile configuration.

---

# 10. Processing Step Types

Support:

```text
UPSCALE

SMART_CROP

RESIZE

DENOISE

SHARPEN

COLOR_CONVERT

FORMAT_CONVERT

COMPRESS

METADATA_PROCESS

VALIDATE
```

Architecture should allow future:

```text
BACKGROUND_REMOVE

FACE_ENHANCE

HDR

DEPTH_MAP

OUTPAINT

WATERMARK
```

Do not implement future operations unless needed.

---

# 11. Processing Provider Architecture

Create abstractions where operations depend on external/local engines.

Example:

```java
public interface ImageUpscaleProvider {

    String providerId();

    UpscaleResult upscale(
        UpscaleRequest request
    );

    UpscaleCapabilities capabilities();
}
```

Potential implementations:

```text
LocalGpuUpscaleProvider

CpuUpscaleProvider

FutureCloudUpscaleProvider
```

Domain services must not know Real-ESRGAN-specific command-line arguments.

---

# 12. GPU Upscaling

Implement local GPU-accelerated AI upscaling.

Prefer a mature local super-resolution implementation compatible with the available environment.

Potential technology:

```text
Real-ESRGAN
```

or another suitable maintained model.

Before choosing:

1. inspect runtime environment;
2. inspect GPU support;
3. evaluate Windows/Docker compatibility;
4. evaluate licensing;
5. verify model availability;
6. document the final selection.

Do not silently download arbitrary models at runtime in production.

---

# 13. Upscale Model Registry

Create configuration/model registry.

Example:

```text
UpscaleModel

id

provider

modelName

modelVersion

scaleFactors

supportedContentTypes

enabled
```

Possible content specialization:

```text
GENERAL

PHOTO

ILLUSTRATION

ANIME
```

Do not hardcode one model forever.

---

# 14. Upscale Request

Provider-neutral request:

```text
inputAsset

targetScale

targetMinimumWidth

targetMinimumHeight

contentType

modelPreference
```

Provider determines supported execution.

---

# 15. Scale Selection

Do not upscale blindly.

Example:

```text
Input:
2048 × 3072

Target:
minimum 4MP
```

already satisfies target.

Therefore:

```text
SKIP UPSCALE
```

Processing planner should calculate whether upscale is necessary.

---

# 16. Avoid Unnecessary Upscaling

AI upscale can introduce artifacts.

Rules:

```text
if source already meets target:
    do not upscale
```

and:

```text
do not upscale 4x
then immediately downscale dramatically
```

unless the profile explicitly requires a super-resolution enhancement stage.

Processing Planner should choose the smallest necessary upscale factor.

---

# 17. Upscale Limits

Configure safeguards:

```yaml
upscale:

  max-scale-factor: 4

  max-output-width: 8192

  max-output-height: 8192

  max-output-megapixels: 64
```

Prevent accidental generation of huge images consuming excessive VRAM/RAM/disk.

---

# 18. GPU Detection

Worker should detect:

```text
GPU available?

CUDA available?

VRAM available?

selected execution provider?
```

Expose worker capability.

Example:

```text
Device:
NVIDIA GPU

Backend:
CUDA

Upscale:
AVAILABLE
```

Do not make GPU mandatory.

---

# 19. CPU Fallback

If GPU unavailable:

```text
GPU provider
↓
unavailable
↓
CPU fallback
```

if allowed by configuration.

Example:

```yaml
upscale:

  fallback:

    cpu-enabled: true
```

CPU fallback may be slower.

Do not silently fall back if the profile requires GPU-only processing.

---

# 20. GPU Concurrency

AI upscaling is VRAM-intensive.

Implement worker concurrency limits.

Example:

```yaml
gpu:

  upscale:

    max-concurrent-jobs: 1
```

Do not run arbitrary numbers of upscale jobs simultaneously.

Architecture should later support multiple GPUs.

---

# 21. GPU Memory Failure

Handle:

```text
CUDA OUT OF MEMORY
```

explicitly.

Possible response:

```text
retry with tile processing
```

if supported.

Do not blindly retry the same configuration indefinitely.

Record:

```text
GPU_OOM
```

as structured failure.

---

# 22. Tiled Upscaling

Support tiled inference where the selected upscale implementation supports it.

Example:

```text
large image
↓
tiles
↓
GPU upscale
↓
merge
```

Configure:

```yaml
upscale:

  tile-size: AUTO

  tile-overlap: ...
```

Avoid visible tile seams.

Use provider/model-recommended strategy.

---

# 23. Upscale Validation

After upscale verify:

```text
output readable

expected dimensions

no corrupted output

valid color channels

file exists

non-zero size
```

TASK-04 QA may later inspect visual artifacts.

A failed upscale must not replace the source.

---

# 24. Smart Crop

Implement:

```text
SmartCropService
```

Purpose:

convert aspect ratios without cutting important visual content.

Pipeline:

```text
image
↓
subject/focal detection
↓
safe crop region
↓
crop
```

---

# 25. Focal Regions

Represent important image regions:

```text
FocalRegion

x
y
width
height

type

confidence
```

Types may include:

```text
FACE

PERSON

ANIMAL

PRIMARY_SUBJECT

TEXT

OTHER
```

Coordinates should use a clearly documented coordinate system.

---

# 26. Subject Detection Provider

Create abstraction:

```java
public interface SubjectDetectionProvider {

    SubjectDetectionResult detect(
        SubjectDetectionRequest request
    );
}
```

Potential implementations may use:

```text
local computer vision

object detection

Vision model

hybrid
```

Prefer local deterministic/ML detection where sufficient to avoid unnecessary paid Vision calls.

TASK-04 Vision QA should not automatically be reused for every crop if a cheaper local detector works.

---

# 27. Existing QA Context

Reuse TASK-04 findings when helpful.

If QA already detected:

```text
face location

subject information
```

and structured coordinates are available, reuse them.

Do not call another AI model unnecessarily.

---

# 28. Smart Crop Algorithm

Given:

```text
source aspect ratio

target aspect ratio

focal regions
```

find crop window maximizing preservation of important regions.

Conceptually:

```text
maximize:

subject coverage
+
face coverage
+
saliency

while satisfying target aspect ratio
```

Do not hardcode "always center crop".

---

# 29. Crop Safety

Important regions may have padding requirements.

Example:

```text
face bounding box
+
15% safety margin
```

For wallpapers:

```text
head must not touch top edge
```

For social portraits:

```text
face should remain inside safe zone
```

Make padding/profile-driven.

---

# 30. Multiple Subjects

If multiple important subjects exist:

```text
Subject A
Subject B
```

attempt to preserve both.

If impossible:

```text
SMART_CROP_UNSAFE
```

Do not silently cut an important subject.

Policy may choose:

```text
letterbox

alternate crop

NEEDS_REVIEW

skip variant
```

---

# 31. Smart Crop Confidence

Return:

```text
crop rectangle

confidence

preserved focal regions

excluded focal regions

warnings
```

Example:

```json
{
  "confidence": 0.91,
  "warnings": []
}
```

or:

```json
{
  "confidence": 0.48,
  "warnings": [
    "Unable to preserve all detected subjects."
  ]
}
```

---

# 32. Subject-Aware Resize

Resize must understand whether:

```text
crop
```

or:

```text
fit
```

is appropriate.

Modes:

```text
FIT

FILL

SMART_FILL

EXACT

PRESERVE
```

Definitions must be documented.

---

# 33. FIT

```text
source
↓
fit entirely inside target
↓
preserve aspect ratio
```

May leave unused canvas space.

Do not add background automatically unless profile defines it.

---

# 34. FILL

```text
source
↓
fill target completely
↓
crop overflow
```

Should use focal region when available.

---

# 35. SMART_FILL

```text
subject detection
↓
calculate safe crop
↓
resize
↓
exact target
```

Preferred for wallpaper/social variants.

---

# 36. Resize Quality

Use a high-quality resampling algorithm for deterministic downscaling.

Examples may include:

```text
Lanczos
```

or equivalent.

Document chosen implementation.

Avoid repeated resize chains.

Prefer:

```text
high-quality master
↓
target variant
```

rather than:

```text
master
↓
variant A
↓
variant B
↓
variant C
```

---

# 37. Denoise

Implement optional denoise stage.

Do NOT enable strong denoise globally.

Profiles should control:

```text
enabled

strength

provider/method
```

Denoise must preserve intentional texture.

Potential use:

```text
upscale noise

compression artifacts

minor generation noise
```

---

# 38. Sharpen

Implement optional sharpening.

Use conservative defaults.

Support:

```text
strength
radius
threshold
```

or equivalent parameters supported by selected implementation.

Avoid:

```text
oversharpening

halos

artificial edges
```

---

# 39. Operation Order

Processing order matters.

Recommended conceptual order:

```text
UPSCALE
↓
CROP
↓
RESIZE
↓
DENOISE / SHARPEN
↓
COLOR
↓
FORMAT
↓
COMPRESS
↓
VALIDATE
```

However, evaluate actual image-processing best practices and chosen tools.

Document the final order.

Do not arbitrarily reorder operations per worker.

---

# 40. Avoid Repeated Lossy Encoding

Keep intermediate processing in lossless/high-quality representation where practical.

Bad:

```text
JPEG
↓
JPEG
↓
JPEG
↓
JPEG
```

Preferred:

```text
master
↓
processing
↓
single final JPEG encoding
```

Avoid generation loss.

---

# 41. Color Management

Implement explicit color handling.

At minimum support:

```text
sRGB
```

For web/stock/wallpaper outputs, normalize to expected color profile according to profile.

Do not silently discard ICC profiles without understanding consequences.

Record:

```text
inputColorProfile

outputColorProfile
```

in processing metadata where available.

---

# 42. Alpha Handling

Handle:

```text
RGB

RGBA
```

explicitly.

JPEG does not support transparency.

If converting:

```text
PNG RGBA
↓
JPEG
```

profile must define background behavior.

Example:

```text
flatten-background = BLACK
```

or:

```text
WHITE
```

Do not silently create unexpected backgrounds.

---

# 43. Format Conversion

Support initial output formats:

```text
JPEG

PNG

WEBP
```

Architecture should allow:

```text
AVIF
```

later.

Each format requires its own options.

---

# 44. JPEG

Support:

```text
quality

progressive

chroma subsampling
```

where supported.

Use high-quality defaults appropriate to profile.

---

# 45. PNG

Support:

```text
compression level

alpha preservation
```

Remember:

PNG compression level affects file size/processing time, not visual quality.

---

# 46. WebP

Support:

```text
lossy

lossless

quality
```

Useful for previews/web delivery.

Do not automatically use WebP for stock export unless target platform/profile supports it.

---

# 47. Compression Optimization

Compression should optimize:

```text
quality
↔
file size
```

not simply minimize file size.

Support:

```text
maximum file size
minimum quality
target quality
```

Example:

```yaml
output:

  format: JPEG

  quality:
    target: 95
    minimum: 88

  max-file-size-mb: 20
```

---

# 48. Adaptive Compression

If file exceeds target:

```text
encode
↓
measure
↓
adjust quality
↓
encode
```

Use bounded search rather than decrementing quality one point at a time.

Respect:

```text
minimum quality
```

If impossible:

```text
OUTPUT_TOO_LARGE
```

Do not destroy quality just to satisfy file size.

---

# 49. Metadata Policy

Define whether output preserves/removes:

```text
EXIF

ICC

XMP

generation metadata
```

Public exports may remove unnecessary internal metadata.

Internal provenance must remain in Media Factory DB regardless of file metadata.

Do not rely on EXIF as the only source of lineage.

---

# 50. Immutable Processing Manifest

Every ProcessingRun must preserve an immutable manifest.

Example:

```json
{
  "sourceAssetId": "...",
  "profile": "STOCK_4K",
  "profileVersion": 3,
  "steps": [
    {
      "type": "UPSCALE",
      "provider": "local-gpu",
      "model": "...",
      "modelVersion": "...",
      "scale": 2
    },
    {
      "type": "RESIZE",
      "width": 4096,
      "height": 2731
    },
    {
      "type": "SHARPEN",
      "strength": 0.15
    },
    {
      "type": "FORMAT_CONVERT",
      "format": "JPEG",
      "quality": 95
    }
  ]
}
```

The manifest represents exactly what was executed.

---

# 51. Processing Step Persistence

Suggested:

```text
ProcessingStep

id

processingRunId

order

type

provider

model
modelVersion

parameters

status

startedAt
completedAt

durationMs

inputAssetReference
outputArtifactReference

errorType
errorMessage
```

Statuses:

```text
PENDING

RUNNING

COMPLETED

SKIPPED

FAILED
```

---

# 52. Step Skipping

A step may legitimately be:

```text
SKIPPED
```

Example:

```text
UPSCALE

reason:
SOURCE_ALREADY_MEETS_REQUIREMENTS
```

Store reason.

This is different from failure.

---

# 53. Intermediate Artifacts

Decide which intermediate outputs must be persisted.

Default:

```text
source original
+
final variants
```

should persist.

Large temporary intermediates may be deleted after successful processing.

However, manifest must preserve processing parameters.

Make intermediate retention configurable.

---

# 54. Temporary Storage

Use dedicated temporary workspace:

```text
processing/tmp/{runId}/
```

or equivalent.

On success:

```text
cleanup
```

On failure:

retain according to debug/retention policy.

Do not leak temporary files indefinitely.

---

# 55. Storage Paths

Use deterministic logical organization.

Example:

```text
assets/
  original/
  processed/
      stock/
      wallpaper/
      social/
      thumbnail/
```

Do not derive application identity solely from filenames.

Database IDs remain authoritative.

---

# 56. Checksums

Every final derivative must receive:

```text
SHA-256
```

Reuse TASK-05 fingerprint infrastructure.

Do not duplicate hashing logic.

---

# 57. Similarity Integration

TASK-05 must understand processing lineage.

Derived variants should normally NOT enter master duplicate detection.

Mark:

```text
assetRole = DERIVED
```

or use existing equivalent.

Similarity engine should recognize:

```text
sourceAssetId
```

and lineage.

---

# 58. Processing QA

After processing perform deterministic output validation.

Check:

```text
file readable

format correct

dimensions correct

aspect ratio correct

file size

color space

minimum megapixels

checksum

unexpected alpha

corruption
```

Do not automatically run expensive TASK-04 Vision QA on every resize unless profile requires it.

---

# 59. Post-Upscale Visual QA

AI upscaling can create artifacts.

Profiles may request:

```text
visualQaAfterUpscale = true
```

Then reuse TASK-04.

Example:

```text
Master
↓
Upscale
↓
Visual QA
↓
acceptable?
```

Possible checks:

```text
face distortion

texture hallucination

edge artifacts

oversharpening
```

Avoid duplicate Vision calls when unnecessary.

---

# 60. Stock 4MP+ Validation

Implement a stock-oriented validator.

Calculate:

```text
megapixels =
width × height / 1,000,000
```

Example:

```text
2400 × 1800
=
4.32 MP
```

Validation profile should require:

```text
minimumMegapixels >= configured threshold
```

Use configuration rather than hardcoding assumptions for every stock platform.

---

# 61. Stock Validation

Validate at minimum:

```text
minimum megapixels

maximum dimensions if configured

accepted format

file readability

color space

file size

aspect ratio rules if applicable
```

Do not claim platform compliance beyond checks actually implemented.

---

# 62. Stock Output Profile

Example:

```text
STOCK_STANDARD

Format:
JPEG

Color:
sRGB

Minimum:
4 MP

Quality:
high

Watermark:
none

Internal metadata:
stripped from public file where appropriate
```

Profile should remain configurable.

---

# 63. Stock Master

Avoid destructive crop for generic stock export unless required.

Preferred:

```text
Generated Master
↓
Upscale if necessary
↓
minimal processing
↓
Stock Master
```

Stock master should preserve maximum useful composition.

---

# 64. Wallpaper Variant Pipeline

From one high-quality master produce multiple wallpaper variants.

Example:

```text
MASTER
   │
   ├── 1440 × 3200
   ├── 1290 × 2796
   ├── 1080 × 2400
   └── preview
```

Do not hardcode device models.

Use configurable target definitions.

---

# 65. Wallpaper Target Registry

Create:

```text
WallpaperTarget

key

width

height

aspectRatio

cropMode

quality

format
```

Example:

```text
ANDROID_1440_3200

PHONE_1290_2796
```

Allow adding targets without changing Java code.

---

# 66. Wallpaper Safe Zones

Profiles may define safe zones.

Example:

```text
top:
15%

bottom:
10%
```

Smart crop should attempt to keep important subjects outside unsafe UI areas.

Do not hardcode assumptions into generic crop engine.

---

# 67. Lock-Screen Composition

For wallpaper profiles optionally prioritize:

```text
face below clock area

important subject not under status UI

visual focus centered appropriately
```

This should influence smart crop, not modify the master.

---

# 68. Variant Fan-Out

Processing one master may create:

```text
Stock

Wallpaper 1440×3200

Wallpaper 1290×2796

Instagram

Preview

Thumbnail
```

Avoid repeating expensive upscale for every branch.

Correct:

```text
MASTER
↓
UPSCALE ONCE
↓
HIGH-RES INTERMEDIATE
   ├── Stock
   ├── Wallpaper A
   ├── Wallpaper B
   └── Social
```

Planner should identify reusable stages.

---

# 69. Processing DAG

Do not restrict implementation to a naive linear pipeline.

Represent processing plan so shared operations can form a DAG:

```text
             MASTER
                ↓
             UPSCALE
                ↓
          HIGH-RES MASTER
          /      |       \
         /       |        \
      STOCK   WALLPAPER   SOCIAL
                / \
               /   \
             A       B
```

Avoid duplicate expensive work.

---

# 70. Processing Cache

If an identical operation has already produced a valid artifact:

```text
same source checksum

same operation

same provider/model version

same parameters
```

reuse it where safe.

Create a stable:

```text
processingCacheKey
```

Do not use stale artifacts after model/profile changes.

---

# 71. Idempotency

If the same ProcessingRun job executes twice:

```text
Worker A
Worker B
```

do not create duplicate variants unnecessarily.

Use:

```text
profileVersion
sourceAsset
targetVariant
```

plus operation identity/idempotency constraints.

---

# 72. Processing Job Architecture

Create jobs such as:

```text
PROCESS_ASSET

UPSCALE_ASSET

GENERATE_VARIANTS

VALIDATE_OUTPUT
```

or use one orchestrated processing job if it better fits existing architecture.

Avoid creating excessive micro-jobs without operational benefit.

---

# 73. Job Priorities

Support priority where existing job infrastructure allows it.

Example:

```text
USER_REQUESTED
HIGH

PUBLICATION
NORMAL

BULK_BACKFILL
LOW
```

Interactive processing should not wait behind thousands of backfill jobs.

---

# 74. Retry

Retry only appropriate failures.

Retryable:

```text
worker temporarily unavailable

temporary GPU worker failure

storage timeout

transient IO error
```

Potentially non-retryable:

```text
corrupt source

unsupported format

invalid processing profile

impossible dimensions
```

GPU OOM may be retryable only after changing execution strategy such as tile size.

---

# 75. Failure Isolation

If:

```text
Wallpaper A succeeds

Wallpaper B fails

Stock succeeds
```

do not discard successful outputs.

Persist per-branch status.

Allow retrying only failed variants.

---

# 76. Processing Run Status

Support:

```text
PENDING

RUNNING

PARTIALLY_COMPLETED

COMPLETED

FAILED

CANCELLED
```

If branches fail independently:

```text
PARTIALLY_COMPLETED
```

may be appropriate.

---

# 77. Cancellation

Support safe cancellation of queued/long-running processing.

For GPU worker:

```text
cancel requested
```

should stop at safe boundaries where practical.

Never leave output registered as valid if processing was incomplete.

---

# 78. Processing Resource Limits

Protect local machine.

Configure:

```text
GPU concurrency

CPU worker count

maximum RAM usage where practical

maximum input megapixels

maximum output megapixels

temporary disk budget
```

Avoid a batch job exhausting the host.

---

# 79. Disk Space Protection

Before large processing jobs:

```text
estimate temporary/output storage
```

where practical.

Worker should detect critically low disk space.

Do not begin a huge batch if disk cannot safely hold outputs.

Expose:

```text
INSUFFICIENT_STORAGE
```

as structured failure.

---

# 80. Worker Health

Expose processing worker status:

```text
ONLINE

BUSY

DEGRADED

OFFLINE
```

Capabilities:

```text
GPU available

device

upscale models

supported formats

active jobs
```

Do not expose sensitive host details unnecessarily.

---

# 81. Processing API

Implement endpoints consistent with project conventions.

Example:

```http
POST /api/v1/assets/{id}/process
```

Request:

```json
{
  "profiles": [
    "STOCK_4K",
    "WALLPAPER_ANDROID"
  ]
}
```

Response:

```json
{
  "processingRunId": "...",
  "status": "PENDING"
}
```

Processing must remain asynchronous.

---

# 82. Processing Run API

Implement:

```text
GET /api/v1/processing-runs/{id}

POST /api/v1/processing-runs/{id}/cancel

POST /api/v1/processing-runs/{id}/retry
```

Return:

```text
source

profile

steps

progress

variants

validation

errors

timings
```

---

# 83. Variant API

Extend:

```text
GET /api/v1/assets/{id}/variants
```

Expose:

```text
variant type

dimensions

format

file size

megapixels

processing profile/version

validation status

createdAt
```

---

# 84. Processing Profiles API

Implement read/manage endpoints as appropriate:

```text
GET /api/v1/processing-profiles

GET /api/v1/processing-profiles/{id}

GET /api/v1/processing-profiles/{id}/versions
```

If profiles are DB-managed:

```text
create draft
publish
deprecate
```

following TASK-03 versioning patterns.

---

# 85. Frontend — Processing Workspace

Create:

```text
PROCESSING
```

page.

Display:

```text
Queue

Active

Completed

Failed

GPU Worker

Profiles
```

Example:

```text
IMAGE PROCESSING

GPU
NVIDIA ...
AVAILABLE

Active jobs       1
Queued            24
Completed today   182
Failed            2
```

---

# 86. Asset Processing UI

Asset detail page:

```text
Original
1536 × 1024
PNG
1.57 MP

PROCESS

☑ Stock 4K
☑ Android Wallpaper
☐ Social Portrait

[Start Processing]
```

After processing:

```text
VARIANTS

Stock
4096 × 2731
JPEG
11.18 MP
VALID

Android
1440 × 3200
JPEG
4.61 MP
VALID
```

---

# 87. Before / After Viewer

For upscale and processing inspection provide:

```text
BEFORE ↔ AFTER
```

with:

```text
zoom

pan

100%

fit
```

A split comparison slider is optional.

Focus on practical inspection.

---

# 88. Crop Preview

Before manual processing where useful show:

```text
source image

target crop rectangle

focal regions

safe zones
```

Allow human adjustment:

```text
move crop

resize crop

reset to auto
```

Manual crop override must be persisted in processing manifest.

---

# 89. Manual Crop Override

Support:

```text
AUTO

MANUAL
```

If human modifies crop:

```text
automaticCrop
```

must remain preserved.

Store:

```text
finalCrop

humanOverride = true
```

for auditability.

---

# 90. Batch Processing

Support:

```text
select 100 approved assets

↓

Apply STOCK_4K

↓

queue processing
```

Do not synchronously process from HTTP request.

Provide progress:

```text
73 / 100 completed
```

---

# 91. Automatic Processing

Allow pipeline configuration:

```text
QA APPROVED
↓
automatically process
```

Example:

```yaml
pipeline:

  wallpaper:

    after-qa-approved:

      processing-profiles:
        - WALLPAPER_ANDROID
        - PREVIEW
```

Keep automation configurable.

---

# 92. Similarity Interaction

Processing must not trigger false TASK-05 alerts for derivatives.

Example:

```text
Master
↓
Upscaled
```

will naturally be very similar.

Similarity Engine must understand:

```text
processing lineage
```

and not flag legitimate derivatives as independent duplicate content.

---

# 93. QA Interaction

Processing output may create new defects.

Support profile configuration:

```text
postProcessingQa:

  technical: true

  visual: false
```

For upscale-heavy workflows:

```text
visual: true
```

Reuse TASK-04 rather than building a second QA engine.

---

# 94. Cost Tracking

Track processing operations.

For external processing providers:

```text
actual monetary cost
```

For local operations:

```text
duration

device

GPU execution time

CPU execution time
```

Do not invent dollar cost for local compute unless explicit local-cost configuration exists.

Operation types:

```text
IMAGE_UPSCALE

IMAGE_DENOISE

IMAGE_PROCESSING
```

---

# 95. Processing Metrics

Expose:

```text
media_factory_processing_total

media_factory_processing_failed_total

media_factory_processing_duration

media_factory_processing_queue_size

media_factory_upscale_total

media_factory_upscale_duration

media_factory_gpu_jobs_active

media_factory_gpu_oom_total

media_factory_processing_output_bytes
```

Useful tags:

```text
profile

operation

provider

model

status

device
```

Avoid asset IDs as metric labels.

---

# 96. Structured Logging

Log:

```text
processing requested

processing started

plan resolved

step started

step completed

step skipped

GPU upscale started

GPU upscale completed

crop calculated

variant created

validation completed

processing failed

processing completed
```

Include:

```text
processingRunId

assetId

profile

step

provider

model

duration
```

Do not log image binary data.

---

# 97. Database

Create Flyway migrations.

Likely structures:

```text
processing_profile

processing_profile_version

processing_run

processing_step

processing_artifact

processing_validation_result

wallpaper_target
```

Extend `AssetVariant` rather than duplicating it if appropriate.

---

# 98. Indexes

Add indexes for:

```text
processing_run.asset_id

processing_run.status

processing_run.created_at

processing_step.processing_run_id

processing_step.status

asset_variant.source_asset_id

asset_variant.type
```

Add uniqueness constraints supporting idempotency.

---

# 99. Local Worker Packaging

Package local processing worker cleanly.

Suggested:

```text
workers/
  image-processing/

      upscale/
      crop/
      resize/
      filters/
      encoding/
      validation/

      requirements...
      Dockerfile
      README.md
```

If GPU Docker support is impractical for the target Windows development environment, support native worker execution.

Document both supported modes accurately.

---

# 100. Tool Boundaries

Use the best tool for each operation.

Potential architecture:

```text
AI Upscale
→ specialized super-resolution model

Subject Detection
→ CV/ML detector

Resize/Crop
→ Pillow/OpenCV/libvips

Format/Compression
→ libvips/ImageMagick/Pillow

Video-related future work
→ FFmpeg
```

Do not use AI for operations that deterministic image processing handles better.

---

# 101. Benchmarking

Create a small benchmark command/script for local testing.

Measure:

```text
input dimensions

output dimensions

processing time

peak memory if available

GPU device

upscale model

file size
```

Never invent performance numbers.

Store/report only actually measured results.

---

# 102. Unit Tests

Add tests for:

```text
processing plan resolution

profile versioning

upscale necessity calculation

scale selection

dimension calculations

megapixel calculation

crop geometry

safe zones

subject preservation

resize modes

format conversion configuration

alpha handling

compression bounds

manifest generation

lineage

idempotency

cache key

step skipping

failure classification
```

---

# 103. Smart Crop Tests

Use deterministic fixtures.

Test:

```text
centered subject
→ expected crop
```

```text
subject near left edge
→ crop shifts left
```

```text
face near top
→ safety margin preserved
```

```text
two subjects
→ both preserved when geometrically possible
```

```text
impossible crop
→ warning / unsafe result
```

Do not rely on live AI APIs.

---

# 104. Processing Integration Tests

Test:

```text
Master
↓
Upscale
↓
Resize
↓
JPEG
↓
Validation
↓
AssetVariant
```

Use Mock upscale provider where GPU/model availability cannot be guaranteed in CI.

---

# 105. Real Local Upscale Test

Provide an opt-in integration test/manual verification for the actual local upscale model.

Do not run heavy GPU tests by default in CI.

Example profile:

```text
integration-gpu
```

or equivalent.

---

# 106. Stock Validation Tests

Test:

```text
2000 × 2000
=
4.0 MP
→ valid when minimum is 4 MP
```

```text
1999 × 2000
<
4 MP
→ invalid
```

Test actual multiplication rather than rounded display values.

---

# 107. Compression Tests

Verify:

```text
output readable

quality stays >= minimum

file size constraints honored where possible

failure returned when constraints impossible
```

Avoid asserting exact JPEG byte sizes across different encoder versions unless deterministic.

---

# 108. Lineage Tests

Verify:

```text
Original A

↓

Upscaled B

↓

Wallpaper C
```

produces:

```text
C source lineage
→ B
→ A
```

and original A remains unchanged.

---

# 109. Idempotency Tests

Execute same processing request twice.

Verify:

```text
no unnecessary duplicate expensive upscale
```

and no conflicting duplicate final variants.

---

# 110. Failure Recovery Tests

Test:

```text
upscale succeeds
↓
format conversion fails
↓
run FAILED/PARTIAL
↓
retry
↓
reuse valid upscale artifact
↓
continue
```

Do not repeat expensive completed stages unnecessarily.

---

# 111. GPU Failure Tests

Mock:

```text
GPU unavailable

CUDA OOM

worker crash

model unavailable
```

Verify configured fallback behavior.

Do not require physical GPU in CI.

---

# 112. Documentation

Create:

```text
docs/image-processing.md

docs/upscaling.md

docs/smart-crop.md

docs/processing-profiles.md

docs/processing-lineage.md

docs/stock-processing.md

docs/wallpaper-processing.md
```

---

# 113. Image Processing Documentation

Explain:

```text
Master
↓
Plan
↓
Upscale
↓
Crop
↓
Resize
↓
Enhance
↓
Encode
↓
Validate
↓
Variant
```

Document why originals remain immutable.

---

# 114. Upscaling Documentation

Document exact:

```text
provider

model

model version

license

supported scales

GPU backend

CPU fallback

tile behavior

VRAM considerations
```

Do not write only:

```text
Real-ESRGAN
```

without the actual model/configuration used.

---

# 115. Smart Crop Documentation

Explain:

```text
subject detection

focal regions

safe zones

crop algorithm

confidence

manual override
```

Document known failure cases.

---

# 116. Processing Profile Documentation

Explain:

```text
profile

profile version

processing plan

immutable historical execution
```

Include examples for:

```text
STOCK_4K

WALLPAPER_ANDROID
```

---

# 117. Lineage Documentation

Explain:

```text
ORIGINAL
   ↓
UPSCALED
   ↓
PROCESSED
   ↓
VARIANT
```

and how processing manifests allow reproduction/debugging.

---

# 118. Stock Processing Documentation

Explain:

```text
minimum megapixels

format

color profile

compression

validation
```

Clearly distinguish:

```text
Media Factory technical validation
```

from:

```text
guaranteed acceptance by a stock platform
```

Never claim guaranteed stock acceptance.

---

# 119. Wallpaper Processing Documentation

Explain:

```text
targets

smart crop

safe zones

subject-aware resize

quality

preview generation
```

---

# 120. Definition of Done

TASK-06 is complete when:

```text
QA APPROVED MASTER
        ↓
Processing Profile
        ↓
GPU Upscale if needed
        ↓
Smart Crop
        ↓
Subject-Aware Resize
        ↓
Denoise / Sharpen if configured
        ↓
Format Conversion
        ↓
Compression
        ↓
Validation
        ↓
Immutable Asset Variant
```

works end-to-end.

And:

```text
Master
↓
Stock profile
↓
4MP+ valid stock derivative
```

works.

And:

```text
Master
↓
Wallpaper processing
↓
multiple target resolutions
↓
subject preserved
```

works.

And:

```text
Original
↓
Processing Run #1
↓
Variant

Original remains unchanged.
```

---

# 121. Verification

Before completing TASK-06:

1. run backend unit tests;
2. run integration tests;
3. run frontend tests;
4. run backend production build;
5. run frontend production build;
6. apply Flyway migrations;
7. verify processing worker startup;
8. verify GPU detection;
9. verify CPU fallback;
10. verify upscale model loading;
11. upscale one test image;
12. verify source remains unchanged;
13. verify output dimensions;
14. verify tiled upscale if supported;
15. test GPU OOM handling;
16. test centered smart crop;
17. test off-center subject;
18. test multiple subjects;
19. test unsafe crop;
20. test manual crop override;
21. generate stock derivative;
22. verify actual megapixel calculation;
23. generate Android wallpaper variant;
24. generate multiple wallpaper targets;
25. verify resize quality;
26. verify JPEG conversion;
27. verify PNG conversion;
28. verify WebP conversion;
29. verify alpha handling;
30. verify sRGB output;
31. verify compression constraints;
32. verify SHA-256 creation through TASK-05 infrastructure;
33. verify processing lineage;
34. verify manifest;
35. verify processing profile version;
36. run same request twice and verify idempotency;
37. simulate processing failure;
38. retry and verify completed expensive steps are reused;
39. verify batch processing;
40. verify resource/concurrency limits;
41. verify temporary-file cleanup;
42. verify disk-space protection;
43. verify derived variants do not pollute TASK-05 duplicate detection;
44. verify optional TASK-04 post-processing QA;
45. verify no original/master asset is ever overwritten.

---

# 122. Final Codex Report

At completion provide:

## Architecture

Describe:

```text
Asset
→ Processing Profile
→ Plan
→ DAG
→ Processing Providers
→ Validation
→ Asset Variants
```

## GPU Upscaling

Report exact:

```text
provider

model

model version

license

GPU backend

CPU fallback

tile strategy
```

## Smart Crop

Report:

```text
subject detector

focal-region model

crop algorithm

safe-zone behavior

manual override
```

## Resize / Encoding

Report:

```text
resize implementation

resampling algorithm

JPEG encoder/settings

PNG encoder/settings

WebP encoder/settings

color handling
```

## Processing Profiles

List implemented profiles.

## Stock Processing

Report:

```text
4MP+ validation

format

compression

color profile

known limitations
```

## Wallpaper Processing

List generated target variants and crop behavior.

## Immutable Lineage

Explain how:

```text
original
→ intermediate
→ derivative
```

is persisted.

## GPU Verification

Report actual detected device and measured test result only if verified.

Never invent benchmark numbers.

If GPU execution was unavailable, state:

```text
GPU processing support was implemented,
but live GPU execution was not verified
in the current environment.
```

## Tests

Report:

```text
unit tests

integration tests

GPU opt-in tests

frontend tests

production builds
```

## Remaining Limitations

List:

```text
upscale-model limitations

smart-crop edge cases

format limitations

performance considerations

future improvements
```

---

# Engineering Principles

Throughout TASK-06 follow these rules:

1. Original generated assets are immutable.
2. Never overwrite a master image.
3. Every derivative has explicit lineage.
4. Every production ProcessingRun stores an immutable manifest.
5. Processing profiles are versioned.
6. Do not upscale when the source already satisfies requirements.
7. Use the smallest necessary upscale factor.
8. Avoid unnecessary AI processing.
9. Deterministic operations should remain deterministic.
10. Avoid repeated lossy encoding.
11. Generate variants from the highest-quality suitable ancestor.
12. Smart crop must preserve important subjects.
13. Center crop is not an acceptable universal solution.
14. Unsafe crops must be reported rather than silently accepted.
15. Human crop overrides must be auditable.
16. GPU acceleration is optional, not mandatory.
17. CPU fallback must be configurable.
18. GPU concurrency must be bounded.
19. GPU OOM must not cause infinite retry loops.
20. Large images should support tiled processing where appropriate.
21. Processing operations must not hold long DB transactions.
22. Expensive completed operations should be reusable after downstream failure.
23. Processing must be idempotent.
24. Derived variants must not pollute TASK-05 duplicate analysis.
25. Reuse TASK-04 QA instead of implementing another visual QA system.
26. Reuse TASK-05 fingerprint infrastructure.
27. Local processing does not automatically have a fabricated monetary cost.
28. Output technical validation does not guarantee stock-platform acceptance.
29. Resource limits must protect the host machine.
30. Processing failures must never damage source assets.
31. Every output must be traceable back to its original asset.
32. Processing model/tool versions must be recorded.
33. Pipeline behavior must be reproducible.
34. Prefer local GPU processing for operations where it is practical and cost-effective.
35. Optimize for image quality first, then file size and throughput.

The result of TASK-06 should turn Media Factory into a complete local image post-production system capable of taking a QA-approved AI master and automatically producing high-resolution, subject-aware, optimized, traceable stock and wallpaper assets without destructive processing or unnecessary paid API calls.
