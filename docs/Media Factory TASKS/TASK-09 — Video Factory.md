# TASK-09 — Video Factory

## Objective

Build a production-ready **Video Factory** for Media Factory.

The Video Factory must transform approved static assets into high-quality animated video assets using external **image-to-video AI providers**, followed by deterministic local processing with **FFmpeg**.

Primary workflow:

```text
Approved Image
   ↓
Video Concept / Motion Plan
   ↓
Video Prompt Engine
   ↓
Provider Router
   ↓
Image-to-Video Provider
   ↓
Raw Video
   ↓
Technical Validation
   ↓
Video Visual QA
   ↓
FFmpeg Processing
   ├── trim
   ├── crop
   ├── resize
   ├── stabilization
   ├── frame interpolation
   ├── denoise
   ├── color processing
   └── encoding
   ↓
Loop Analyzer
   ↓
Loop Processor
   ↓
MASTER VIDEO
   ↓
Video Variants
   ├── wallpaper loop
   ├── 9:16
   ├── 16:9
   ├── 1:1
   └── preview
   ↓
Human Review
   ↓
READY
```

TASK-09 must support:

* image-to-video provider abstraction;
* multiple providers;
* provider routing;
* provider capabilities;
* configurable models;
* video prompts;
* motion plans;
* negative prompts where supported;
* provider-specific prompt adaptation;
* asynchronous video generation;
* provider polling;
* webhooks where supported;
* timeout/retry/fallback;
* generation cost tracking;
* generation lineage;
* source-image lineage;
* raw-video preservation;
* FFmpeg processing;
* GPU acceleration where available;
* video validation;
* visual/video QA;
* motion-quality checks;
* loop detection;
* seamless loop generation;
* crossfade loops;
* forward/reverse loops;
* loop quality scoring;
* output variants;
* thumbnails/posters;
* preview clips;
* Android live-wallpaper-ready loops;
* social-media-ready variants;
* immutable processing history;
* human override.

Do not hardcode Video Factory around one AI provider.

---

# 1. Inspect Existing System First

Before implementation inspect TASK-01 through TASK-08.

Reuse existing infrastructure for:

```text
Project
Collection
Concept
Generation
Asset
AssetVariant
QualityReview
GenerationCost
MediaStorage
Job
PromptTemplate
PromptVersion
Provider routing
Retry
Rate limiting
Processing lineage
Human review
```

Specifically reuse:

```text
TASK-02
Provider routing
Retry
Rate limiting
Cost tracking
Fallback
Provider health

TASK-03
Prompt Engine
Prompt versions
Variables
A/B prompts
Prompt history

TASK-04
Visual QA architecture
Human override

TASK-05
Similarity concepts where useful

TASK-06
Immutable processing lineage
Media-processing architecture

TASK-07
Wallpaper variants/publication integration

TASK-08
Export/package architecture where reusable
```

Do not duplicate generic infrastructure.

---

# 2. Core Architecture

Target architecture:

```text
                    VIDEO FACTORY

Approved Source Asset
        │
        ▼
Motion Planner
        │
        ▼
Video Prompt Engine ─────────── TASK-03
        │
        ▼
Video Provider Router
        │
        ▼
Image-to-Video Provider
        │
        ▼
RAW VIDEO
        │
        ├──────── Cost / Provider Metadata
        │
        ▼
Technical Validation
        │
        ▼
Video QA
        │
        ▼
FFmpeg Processing
        │
        ▼
Loop Analyzer
        │
        ▼
Loop Processor
        │
        ▼
MASTER VIDEO
        │
        ├── Android Live Wallpaper
        ├── 9:16
        ├── 16:9
        ├── 1:1
        ├── Preview
        └── Poster
```

---

# 3. Video Provider Abstraction

Create provider-neutral interface.

Example:

```java
public interface VideoGenerationProvider {

    String providerId();

    VideoGenerationJob submit(
        VideoGenerationRequest request
    );

    VideoGenerationStatus getStatus(
        String providerJobId
    );

    GeneratedVideoResult getResult(
        String providerJobId
    );

    VideoProviderCapabilities capabilities();
}
```

Adapt to existing provider architecture if appropriate.

Do not force every provider into synchronous generation.

---

# 4. Asynchronous Generation Is First-Class

Most expensive video generation must be treated as asynchronous.

Expected lifecycle:

```text
REQUESTED
↓
SUBMITTED
↓
PROVIDER_QUEUED
↓
PROVIDER_PROCESSING
↓
DOWNLOADING
↓
GENERATED
```

Failures:

```text
PROVIDER_FAILED
PROVIDER_REJECTED
TIMED_OUT
DOWNLOAD_FAILED
CANCELLED
```

Do not hold an HTTP request open waiting for video generation.

---

# 5. Video Generation Request

Create canonical request such as:

```java
public record VideoGenerationRequest(
    UUID generationId,
    UUID sourceAssetId,
    String prompt,
    String negativePrompt,
    VideoAspectRatio aspectRatio,
    Integer durationSeconds,
    VideoQuality quality,
    Integer fps,
    Long seed,
    CameraMotion cameraMotion,
    MotionStrength motionStrength,
    Map<String, Object> metadata
) {}
```

Fields unsupported by a provider must be handled explicitly.

Never silently discard important options.

---

# 6. Provider Capabilities

Create:

```text
VideoProviderCapabilities
```

Possible fields:

```text
supportedAspectRatios

supportedDurations

supportedResolutions

supportsNegativePrompt

supportsSeed

supportsCameraMotion

supportsMotionStrength

supportsFirstFrame

supportsLastFrame

supportsReferenceImages

supportsLoopGeneration

supportsAudio

supportsWebhook

supportsStatusPolling
```

Router must consider capabilities.

---

# 7. First Real Video Provider

Implement at least one real image-to-video provider adapter.

Structure:

```text
provider/video/

  VideoGenerationProvider.java

  mock/
    MockVideoGenerationProvider.java

  <real-provider>/
    RealVideoGenerationProvider.java
    RealVideoProviderClient.java
    RealVideoProviderMapper.java
    RealVideoProviderProperties.java
    RealVideoProviderExceptionMapper.java
```

Do not leak provider DTOs into core domain.

---

# 8. Provider Router

Create/reuse:

```text
VideoProviderRouter
```

Routing modes:

```text
EXPLICIT

DEFAULT

FALLBACK
```

Architecture should later support:

```text
CHEAPEST

FASTEST

BEST_QUALITY

BEST_FOR_LOOP

BEST_FOR_MOTION

WEIGHTED
```

TASK-09 only needs reliable configurable default/fallback routing.

---

# 9. Routing Example

Configuration:

```yaml
media-factory:

  video-generation:

    default-provider: provider-a

    fallback-providers:
      - provider-b

    providers:

      provider-a:
        enabled: true
        model: ${VIDEO_PROVIDER_A_MODEL}

      provider-b:
        enabled: true
        model: ${VIDEO_PROVIDER_B_MODEL}
```

No provider secrets in source control.

---

# 10. Video Provider Errors

Create/map error categories:

```text
Authentication

RateLimit

InvalidRequest

UnsupportedFeature

ContentPolicy

ProviderUnavailable

ProviderTimeout

GenerationFailed

DownloadFailed

Unexpected
```

Reuse generic provider exception infrastructure where possible.

---

# 11. Retry Strategy

Retry transient operations such as:

```text
status polling failures

temporary 5xx

429

network timeout

video download failure
```

Do not automatically submit another expensive generation merely because status polling failed.

This distinction is critical.

---

# 12. Submission Retry Safety

Video generation submission may cost significant money.

If:

```text
submit request
↓
provider accepts
↓
network response lost
```

blind retry may create two paid videos.

Use:

```text
provider idempotency key
```

where supported.

Otherwise persist uncertainty:

```text
SUBMISSION_UNKNOWN
```

and attempt reconciliation.

Never blindly regenerate paid video after ambiguous submission failure.

---

# 13. Provider Polling

Implement configurable polling.

Example:

```yaml
video-generation:

  polling:
    initial-delay: 5s
    interval: 10s
    maximum-duration: 15m
```

Use provider recommendations where applicable.

Do not poll aggressively.

---

# 14. Webhooks

If provider supports callbacks/webhooks, architecture should allow:

```text
provider
↓
webhook
↓
VideoGenerationStatusHandler
```

Validate webhook authenticity where supported.

Polling remains fallback if necessary.

---

# 15. Video Generation Attempt

Persist every provider attempt.

Example:

```text
VideoGenerationAttempt

id
generationId

provider
model

providerJobId

status

submittedAt
startedAt
completedAt

durationMs

requestedDuration

actualDuration

resolution

fps

providerRequestId

retryable

fallback

errorType
errorCode
errorMessage

estimatedCost
actualCost
currency
```

Do not overwrite failed attempts.

---

# 16. Source Image Provenance

Every generated video must point to:

```text
sourceAssetId
```

and ideally:

```text
sourceAssetChecksum
```

Lineage:

```text
Concept
↓
Image Generation
↓
Image Asset
↓
Video Generation
↓
Raw Video
↓
Processed Video
```

must always be recoverable.

---

# 17. Motion Plan

Introduce:

```text
MotionPlan
```

Video generation should not rely only on an arbitrary text prompt.

Motion plan may contain:

```text
subjectMotion

environmentMotion

cameraMotion

motionStrength

motionSpeed

depthMotion

particleMotion

lightingMotion

loopIntent
```

---

# 18. Motion Plan Example

```json
{
  "subjectMotion": "subtle breathing and fur movement",
  "environmentMotion": "slow rain and drifting fog",
  "cameraMotion": "slow push-in",
  "motionStrength": "LOW",
  "motionSpeed": "SLOW",
  "depthMotion": "SUBTLE_PARALLAX",
  "particleMotion": "small neon particles",
  "lightingMotion": "gentle neon pulse",
  "loopIntent": true
}
```

Keep it structured.

---

# 19. Motion Planner

Create:

```text
MotionPlanner
```

Input:

```text
source image

concept

image prompt

visual QA observations

target video profile
```

Output:

```text
MotionPlan
```

Use LLM/Vision where useful.

Do not ask an LLM to calculate deterministic video properties.

---

# 20. Motion Safety

Avoid motion likely to destroy image consistency.

Examples of risky motion:

```text
extreme face movement

large anatomy changes

large camera rotations

rapid zoom

major scene transformation

heavy object deformation
```

For wallpapers prefer:

```text
subtle motion
```

by default.

---

# 21. Video Prompt Engine

Use TASK-03.

Create logical templates such as:

```text
VIDEO_IMAGE_TO_VIDEO

VIDEO_WALLPAPER_LOOP

VIDEO_CINEMATIC

VIDEO_SOCIAL
```

Do not construct provider prompts directly inside adapters.

---

# 22. Prompt Variables

Example:

```text
{{visual_description}}

{{main_subject}}

{{subject_motion}}

{{environment_motion}}

{{camera_motion}}

{{motion_strength}}

{{motion_speed}}

{{loop_intent}}

{{style}}
```

---

# 23. Provider-Specific Prompt Adaptation

TASK-03 may render:

```text
Canonical Motion Prompt
↓
Provider Adapter
↓
Provider-Compatible Prompt
```

Provider adaptation must preserve provenance.

Store both:

```text
canonicalPrompt

providerPrompt
```

---

# 24. Negative Video Prompts

Support when provider allows it.

Possible concepts:

```text
flicker

morphing

warping

face distortion

extra limbs

camera shake

text

watermark

scene cuts

abrupt transitions
```

Do not assume every provider supports negative prompts.

---

# 25. Video Profiles

Create:

```text
VideoProductionProfile
```

Initial profiles:

```text
WALLPAPER_LOOP

CINEMATIC_LOOP

SOCIAL_VERTICAL

SOCIAL_HORIZONTAL

SOCIAL_SQUARE

GENERIC_VIDEO
```

---

# 26. Wallpaper Loop Profile

Default behavior should favor:

```text
5–10 second duration

subtle subject motion

subtle environmental motion

stable camera

no cuts

no abrupt transformations

loop-friendly movement
```

Make values configurable.

---

# 27. Raw Video Preservation

Provider output becomes:

```text
RAW_VIDEO
```

Store immediately.

Never overwrite it.

Example:

```text
RAW_VIDEO
↓
ProcessingRun
↓
MASTER_VIDEO
```

If FFmpeg processing fails, raw provider output remains available.

---

# 28. Raw Video Metadata

Persist:

```text
container

codec

width

height

duration

fps

bitrate

audio

fileSize

checksum
```

Use:

```text
ffprobe
```

for deterministic metadata extraction.

---

# 29. FFmpeg Integration

Create:

```text
FfmpegService
```

and:

```text
FfprobeService
```

Do not scatter shell commands throughout business services.

---

# 30. Process Execution

Use safe process execution.

Avoid:

```text
"ffmpeg " + userInput
```

Use structured command arguments.

Prevent command injection.

Capture:

```text
exit code

stdout

stderr

duration
```

with sensible log limits.

---

# 31. FFmpeg Availability

At application/worker startup detect:

```text
ffmpeg version

ffprobe version

available encoders

available decoders

GPU acceleration capabilities
```

Expose health status.

---

# 32. Video Worker

Heavy FFmpeg work should preferably execute in a dedicated worker.

Architecture:

```text
Backend
↓
Processing Job
↓
Video Worker
↓
FFmpeg
↓
MediaStorage
```

Do not block normal HTTP request threads.

---

# 33. GPU Acceleration

Detect available acceleration.

Potential implementations may include platform-supported hardware encoders.

Create abstraction/configuration such as:

```text
AUTO

CPU

GPU
```

Do not assume one GPU vendor universally.

---

# 34. Hardware Encoding Policy

Hardware encoding can improve throughput but may differ in quality.

Allow profiles to specify:

```text
encodingMode

codec

quality target

bitrate target
```

Do not force hardware encoding for archival/master output if quality is worse.

---

# 35. FFmpeg Processing Pipeline

Typical pipeline:

```text
RAW VIDEO
↓
Trim
↓
Stabilize
↓
Crop
↓
Resize
↓
Frame-rate normalization
↓
Optional interpolation
↓
Denoise
↓
Optional sharpen
↓
Color normalization
↓
Loop processing
↓
Encode
↓
MASTER VIDEO
```

Not every stage must run for every profile.

---

# 36. Processing Graph

Represent processing operations explicitly.

Example:

```text
VideoProcessingRun

RAW_VIDEO
  ↓
TRIM
  ↓
STABILIZE
  ↓
INTERPOLATE
  ↓
LOOP_CROSSFADE
  ↓
ENCODE
  ↓
MASTER_VIDEO
```

Preserve complete lineage.

---

# 37. Immutable Video Processing

Never:

```text
raw.mp4
→ overwrite raw.mp4
```

Instead:

```text
raw.mp4

processed-v1.mp4

processed-v2.mp4
```

Each output references parent input and processing run.

---

# 38. Video Technical Validator

Create:

```text
VideoTechnicalValidator
```

Validate:

```text
file readable

container

codec

dimensions

duration

fps

bitrate

file size

frame count

audio presence

corruption

decode errors
```

---

# 39. Frame Validation

Optionally decode sampled frames:

```text
first

25%

50%

75%

last
```

Ensure video can be decoded throughout.

For suspicious files run deeper validation.

---

# 40. Video QA

Extend TASK-04 concepts for temporal media.

Create:

```text
VideoQualityAnalyzer
```

or appropriate TASK-04 extension.

Evaluate:

```text
flicker

subject deformation

identity drift

geometry instability

background warping

unexpected objects

text appearance

watermark appearance

camera instability

abrupt transitions

motion coherence
```

---

# 41. Temporal Sampling

Do not necessarily send every frame to a Vision model.

Sample intelligently.

Example:

```text
frame 0

frame 25%

frame 50%

frame 75%

final frame
```

plus frames detected around anomalies.

---

# 42. Motion Analysis

Use deterministic computer-vision metrics where practical.

Potential metrics:

```text
frame difference

optical flow

camera motion

scene cut detection

flicker metrics
```

Use Vision AI for semantic problems.

Keep responsibilities separated.

---

# 43. Scene Cut Detection

Wallpaper loops should normally contain:

```text
zero scene cuts
```

Detect abrupt transitions.

Unexpected scene cut:

```text
WARNING
```

or:

```text
FAIL
```

according to profile.

---

# 44. Flicker Detection

Implement approximate temporal flicker detection.

Measure frame-to-frame changes while accounting for expected motion.

Output:

```text
flickerScore
```

and detected suspicious ranges.

---

# 45. Motion Intensity

Calculate:

```text
motionIntensity
```

using deterministic frame/optical-flow analysis.

Classify:

```text
VERY_LOW

LOW

MEDIUM

HIGH

VERY_HIGH
```

Wallpaper profile may prefer:

```text
LOW / MEDIUM
```

---

# 46. Camera Stability

Estimate unwanted global motion.

Possible output:

```text
cameraMotionScore

cameraShakeScore
```

Do not stabilize intentionally cinematic movement blindly.

Use profile/motion-plan context.

---

# 47. Stabilization

FFmpeg or other local processing may stabilize video when enabled.

Create:

```text
STABILIZATION
```

processing stage.

Store parameters.

Do not automatically stabilize every video.

---

# 48. Frame Rate Normalization

Support target FPS such as:

```text
24

30

60
```

according to profile.

Do not convert to 60 FPS unnecessarily.

---

# 49. Frame Interpolation

Create optional:

```text
FRAME_INTERPOLATION
```

stage.

Potential implementation may use:

```text
FFmpeg interpolation
```

or later dedicated local AI interpolation.

TASK-09 requires a working FFmpeg/local baseline.

---

# 50. Interpolation Policy

Only interpolate when useful.

Examples:

```text
source 24 FPS
target 30 FPS
```

may not need expensive AI interpolation.

Where:

```text
source motion is visibly choppy
```

interpolation may be enabled.

Make profile-driven.

---

# 51. Denoise

Optional stage:

```text
DENOISE
```

Use conservative settings.

Avoid destroying fine generated details.

---

# 52. Sharpen

Optional:

```text
SHARPEN
```

after resize/denoise.

Avoid halos and oversharpening.

Store exact parameters.

---

# 53. Color Processing

Optional:

```text
COLOR_NORMALIZATION
```

Do not blindly apply dramatic grading.

Preserve source artistic intent.

---

# 54. Crop and Resize

Reuse concepts from TASK-06.

Video variants may require:

```text
9:16

16:9

1:1
```

Use subject-aware crop metadata where possible.

Do not simply center-crop every video.

---

# 55. Video Master

Define:

```text
MASTER_VIDEO
```

as highest-quality approved processed video used for derivatives.

Lineage:

```text
RAW_VIDEO
↓
Processing
↓
MASTER_VIDEO
↓
Variants
```

---

# 56. Master Video Requirements

Profile defines:

```text
resolution

fps

codec

quality

duration

audio policy
```

Do not hardcode one universal master.

---

# 57. Loop Factory

Create dedicated:

```text
VideoLoopService
```

Responsibilities:

```text
analyze loop compatibility

choose loop strategy

generate loop

validate loop boundary
```

---

# 58. Loop Strategies

Support at least:

```text
DIRECT

CROSSFADE

PING_PONG
```

Future:

```text
AI_GENERATED_LOOP

MOTION_MATCHED
```

---

# 59. DIRECT Loop

Use when:

```text
first frame ≈ last frame
```

and motion continuity is acceptable.

No artificial transition required.

---

# 60. CROSSFADE Loop

Create overlap:

```text
end
↓
crossfade
↓
beginning
```

Configurable duration:

```text
0.25s
0.5s
1.0s
```

Profile-driven.

---

# 61. PING_PONG Loop

Sequence:

```text
A → B → C → D
        ↓
D → C → B → A
```

Useful for some motion patterns.

Avoid duplicate terminal frames that create a visible pause.

---

# 62. Ping-Pong Suitability

Do not use PING_PONG for:

```text
rain falling

smoke flowing one direction

cars moving

walking

camera push-in
```

unless reversal looks intentional.

Prefer it for:

```text
breathing

subtle floating

light pulsing

gentle deformation
```

---

# 63. Loop Boundary Analyzer

Create:

```text
LoopBoundaryAnalyzer
```

Compare beginning and end.

Metrics may include:

```text
pixelDifference

perceptualSimilarity

embeddingSimilarity

colorDifference

edgeDifference

motionDirectionDifference
```

Reuse TASK-05 embeddings if appropriate.

---

# 64. Multi-Frame Boundary

Do not compare only:

```text
frame 0
vs
frame N
```

Also compare small windows:

```text
first K frames

last K frames
```

This better represents motion continuity.

---

# 65. Loop Score

Expose dimensions such as:

```text
visualBoundaryScore

motionContinuityScore

colorContinuityScore

overallLoopScore
```

Do not hide everything behind one unexplained score.

---

# 66. Automatic Loop Strategy

Create:

```text
LoopStrategySelector
```

Example:

```text
boundary already excellent
→ DIRECT

boundary moderate
→ CROSSFADE

reversible motion
→ PING_PONG

otherwise
→ REVIEW
```

Thresholds configurable.

---

# 67. Loop Validation

After creating loop:

```text
processed loop
↓
LoopBoundaryAnalyzer
```

again.

Do not assume processing improved it.

---

# 68. Perfect Loop Target

For wallpaper profile aim for:

```text
no visible jump

no brightness jump

no large subject displacement

no frame freeze

no duplicate-frame pause
```

Do not call something “perfect” solely because first and last frames have high image similarity.

---

# 69. Loop Preview

Generate preview showing:

```text
loop repeated 2–3 times
```

This makes boundary defects easy to review.

Do not make repeated preview the production video.

---

# 70. Loop Human Review

Review UI should allow user to watch:

```text
RAW

MASTER

LOOP × 3
```

Actions:

```text
APPROVE

REJECT

TRY DIRECT

TRY CROSSFADE

TRY PING_PONG

REPROCESS

REGENERATE VIDEO
```

---

# 71. Video Regeneration vs Reprocessing

Keep separate:

```text
REGENERATE
```

means call image-to-video provider again.

```text
REPROCESS
```

means reuse existing raw video and run local processing again.

This distinction is essential for cost control.

---

# 72. Video Variants

Generate from:

```text
MASTER_VIDEO
```

not directly from provider output each time.

Possible variants:

```text
ANDROID_LIVE_WALLPAPER

SOCIAL_VERTICAL

SOCIAL_HORIZONTAL

SOCIAL_SQUARE

PREVIEW

THUMBNAIL
```

---

# 73. Android Live Wallpaper Variant

Create profile for:

```text
ANDROID_LIVE_WALLPAPER
```

Optimize for:

```text
portrait

short loop

reasonable resolution

reasonable bitrate

efficient decoding

no audio

small enough file size

smooth playback
```

Exact parameters must be configurable.

---

# 74. Android Wallpaper Loop

Typical target:

```text
5–10 seconds

portrait

seamless loop

no audio

stable subject

low/moderate motion
```

Do not assume every device requires identical resolution.

Reuse TASK-07 device-family strategy where useful.

---

# 75. Android Video Variants

Possible families:

```text
ANDROID_VIDEO_FHD

ANDROID_VIDEO_QHD

ANDROID_VIDEO_GENERIC
```

Avoid generating hundreds of device-specific videos.

---

# 76. Social Vertical

Create:

```text
SOCIAL_VERTICAL
```

Typical geometry:

```text
9:16
```

Do not hardcode platform branding.

---

# 77. Social Horizontal

Create:

```text
SOCIAL_HORIZONTAL
```

Typical geometry:

```text
16:9
```

---

# 78. Social Square

Create:

```text
SOCIAL_SQUARE
```

Typical geometry:

```text
1:1
```

Use subject-aware framing.

---

# 79. Preview Variant

Create lightweight:

```text
VIDEO_PREVIEW
```

for dashboard browsing.

Properties:

```text
lower resolution

lower bitrate

short duration where useful

fast loading
```

Do not serve master video in grids.

---

# 80. Poster Frame

Generate:

```text
VIDEO_POSTER
```

Select representative frame.

Avoid:

```text
motion-blurred frame

transition frame

distorted frame
```

Use deterministic + QA-assisted selection.

---

# 81. Thumbnail

Generate:

```text
VIDEO_THUMBNAIL
```

from poster frame using TASK-06 image processing where appropriate.

---

# 82. Audio Policy

Image-to-video output may contain audio depending on provider.

Video profile must define:

```text
KEEP

REMOVE

OPTIONAL
```

Wallpaper loops default:

```text
REMOVE
```

unless product requirements explicitly say otherwise.

---

# 83. Codec Profiles

Make configurable.

Possible targets:

```text
H264

H265

AV1
```

Do not assume client support without checking downstream requirements.

---

# 84. Encoding Profiles

Create:

```text
VideoEncodingProfile
```

Fields:

```text
codec

pixelFormat

fps

bitrateMode

targetBitrate

quality

maxFileSize

audioCodec

audioEnabled

hardwareAcceleration
```

---

# 85. File Size Optimization

For mobile delivery optimize:

```text
quality
vs
file size
vs
decode cost
```

Do not optimize purely for minimum bytes.

---

# 86. Video Processing Lineage

Persist:

```text
VideoProcessingRun

id

sourceAssetId

outputAssetId

profileId
profileVersion

ffmpegVersion

operations

startedAt
completedAt

status
```

Each operation stores parameters.

---

# 87. Processing Operation

Example:

```json
{
  "type": "LOOP_CROSSFADE",
  "parameters": {
    "durationMs": 500
  }
}
```

Another:

```json
{
  "type": "ENCODE",
  "parameters": {
    "codec": "H264",
    "fps": 30
  }
}
```

---

# 88. Reproducibility

Store enough information to recreate local processing:

```text
source checksum

FFmpeg version

processing profile version

filter graph

encoding parameters
```

Provider generation itself may not be perfectly reproducible.

Document this limitation.

---

# 89. FFmpeg Filter Graph

Store normalized representation of the executed filter graph.

Do not rely only on logs.

This makes debugging and reproduction easier.

---

# 90. Video Storage Layout

Conceptual:

```text
video/

  raw/
    {generationId}/

  processed/
    {processingRunId}/

  master/
    {assetId}/

  variants/
    {assetId}/

  previews/
```

Use actual MediaStorage abstraction rather than assuming local filesystem.

---

# 91. Download Safety

Provider video download must enforce:

```text
maximum expected file size

HTTP timeout

content-type validation

checksum

safe temporary file handling
```

Do not blindly trust provider download URL responses.

---

# 92. Provider URL Expiration

Provider result URLs may expire.

Download generated media into Media Factory storage immediately after successful generation.

Do not use provider temporary URLs as permanent asset URLs.

---

# 93. Cost Tracking

Reuse TASK-02 cost infrastructure.

Operations:

```text
VIDEO_GENERATION

VIDEO_VISION_QA

VIDEO_EXTERNAL_PROCESSING
```

Local FFmpeg processing should normally record compute metrics rather than fake API cost.

---

# 94. Video Cost

Store:

```text
provider

model

duration

resolution

quantity

estimatedCost

actualCost

currency

pricingVersion
```

If provider pricing is unknown:

```text
pricingStatus = UNKNOWN
```

Never invent cost.

---

# 95. Cost Per Approved Video

Calculate:

```text
total video generation cost
+
video QA cost
+
external processing cost
──────────────────────────
approved videos
```

Failed generations remain part of production cost.

---

# 96. Regeneration Cost Warning

UI should clearly distinguish:

```text
REPROCESS
→ local / cheap

REGENERATE
→ external AI / potentially paid
```

Show estimated generation cost when available.

---

# 97. Video Production Aggregate

Introduce:

```text
VideoProduction
```

Suggested fields:

```text
id

projectId
collectionId
conceptId

sourceAssetId

videoGenerationId

rawVideoAssetId

masterVideoAssetId

profileId
profileVersion

status

loopStrategy

createdAt
updatedAt

approvedAt
approvedBy
```

Reference rather than duplicate existing metadata.

---

# 98. Video Production State Machine

Example:

```text
DRAFT
↓
MOTION_PLAN_READY
↓
GENERATION_QUEUED
↓
GENERATING
↓
RAW_READY
↓
TECHNICAL_VALIDATION
↓
QA_PENDING
↓
PROCESSING
↓
LOOP_ANALYSIS
↓
VARIANTS_GENERATING
↓
REVIEW
↓
APPROVED
↓
READY
```

Failure states:

```text
GENERATION_FAILED

VALIDATION_FAILED

QA_REJECTED

PROCESSING_FAILED

LOOP_FAILED

CANCELLED
```

---

# 99. Pipeline Orchestrator

Create:

```java
public interface VideoProductionOrchestrator {

    VideoProduction start(...);

    void continuePipeline(...);

    void pause(...);

    void resume(...);

    void cancel(...);
}
```

Adapt naming to project conventions.

---

# 100. Event-Driven Progression

Prefer:

```text
VideoGenerationCompleted
↓
RawVideoStored
↓
VideoValidationRequested
↓
VideoQaRequested
↓
VideoProcessingRequested
↓
LoopAnalysisRequested
↓
VariantsRequested
↓
VideoReadyForReview
```

Avoid one huge synchronous transaction.

---

# 101. Idempotency

Every stage must be idempotent.

Duplicate:

```text
VideoGenerationCompleted
```

must not create duplicate raw assets.

Duplicate:

```text
VideoProcessingCompleted
```

must not create uncontrolled variants.

---

# 102. Recovery

After application restart recover:

```text
PROVIDER_PROCESSING

DOWNLOADING

PROCESSING

VARIANTS_GENERATING
```

from durable state.

Do not rely solely on memory.

---

# 103. Stale Job Detection

Detect:

```text
generation stuck for X minutes

processing stuck for X minutes
```

Handle differently.

Provider generation may still be running remotely.

Do not automatically create another paid generation.

---

# 104. Cancellation

If provider supports cancellation:

```text
cancel remote job
```

when safe.

Otherwise:

```text
mark local production cancelled
```

and ignore/prevent future pipeline stages after remote result arrives.

Preserve costs and attempts.

---

# 105. Video Collection Production

Support:

```text
Collection
↓
approved images
↓
video candidates
↓
Video Factory
```

Example:

```text
Cyber Wolves

10 approved images
↓
10 animated wallpaper loops
```

---

# 106. Controlled Batch Generation

Do not submit hundreds of expensive video generations simultaneously.

Support:

```text
batch size

maximum concurrent generations

maximum total attempts

budget
```

---

# 107. Budget Guard

Collection/video campaign may define:

```text
targetApprovedVideos

maxGenerationAttempts

maxGenerationCost
```

Stop/pause when guard is reached.

---

# 108. Provider Concurrency

Reuse TASK-02 concepts.

Limit separately:

```text
API submission rate

active remote jobs

local download concurrency

FFmpeg processing concurrency
```

These are different resources.

---

# 109. GPU Worker Concurrency

FFmpeg GPU operations can exhaust GPU memory.

Configure:

```text
maxConcurrentGpuJobs
```

CPU and GPU queues may have different concurrency.

---

# 110. Resource Awareness

Video processing should track:

```text
CPU duration

GPU duration

processing wall time

input size

output size
```

This prepares future optimization.

---

# 111. Video Dashboard

Create:

```text
VIDEO FACTORY
```

Show:

```text
Generating

Provider Queue

Raw Ready

QA Pending

Processing

Loop Review

Ready

Failed
```

---

# 112. Video KPIs

Show:

```text
videos generated today

approved videos

generation failures

QA failures

loop failures

average provider duration

average processing duration

generation cost

cost per approved video

active provider jobs

active FFmpeg jobs
```

---

# 113. Provider Dashboard

Show:

```text
provider

model

health

active jobs

success rate

failure rate

rate limits

average generation time

generation cost
```

No credentials.

---

# 114. Video Review UI

Show:

```text
SOURCE IMAGE

RAW VIDEO

PROCESSED VIDEO

LOOP PREVIEW
```

Alongside:

```text
Provider

Model

Prompt

Motion Plan

Technical Validation

Video QA

Loop Score

Cost
```

---

# 115. Review Actions

Support:

```text
APPROVE

REJECT

REGENERATE

REPROCESS

CHANGE LOOP STRATEGY

EDIT MOTION PLAN
```

Regeneration creates new generation lineage.

Reprocessing preserves raw video.

---

# 116. Frame Timeline

Where practical show timeline markers for detected issues:

```text
0s ───────────── 8s

     ▲
   flicker

             ▲
        deformation
```

This greatly improves human QA.

---

# 117. Loop Comparison

UI should allow quick comparison:

```text
DIRECT

CROSSFADE

PING_PONG
```

when multiple loop variants exist.

Do not destroy alternatives when user selects one.

---

# 118. API

Possible endpoints:

```text
POST /api/v1/video-productions

GET /api/v1/video-productions

GET /api/v1/video-productions/{id}

POST /api/v1/video-productions/{id}/regenerate

POST /api/v1/video-productions/{id}/reprocess

POST /api/v1/video-productions/{id}/approve

POST /api/v1/video-productions/{id}/reject

POST /api/v1/video-productions/{id}/cancel
```

Follow project conventions.

---

# 119. Motion Plan API

Possible:

```text
GET /api/v1/video-productions/{id}/motion-plan

PUT /api/v1/video-productions/{id}/motion-plan
```

Editing motion plan before regeneration creates new version/history.

---

# 120. Loop API

Possible:

```text
POST /api/v1/video-productions/{id}/loops
```

Request:

```json
{
  "strategy": "CROSSFADE",
  "crossfadeDurationMs": 500
}
```

This should create new processing output.

---

# 121. Variant API

Possible:

```text
POST /api/v1/video-productions/{id}/variants
```

Request:

```json
{
  "profiles": [
    "ANDROID_VIDEO_FHD",
    "SOCIAL_VERTICAL",
    "VIDEO_PREVIEW"
  ]
}
```

---

# 122. Provider API

Expose safe information:

```text
GET /api/v1/providers/video
```

Return:

```text
provider

enabled

health

models

capabilities

limits
```

Never API keys.

---

# 123. Database

Create/refine migrations for:

```text
video_production

video_generation_attempt

motion_plan

motion_plan_version

video_processing_run

video_processing_operation

video_quality_result

video_loop_analysis
```

Reuse generic tables where existing architecture supports it.

---

# 124. Indexes

Consider:

```text
video_production.status

video_production.collection_id

video_production.source_asset_id

video_generation_attempt.generation_id

video_generation_attempt.provider_job_id

video_processing_run.source_asset_id
```

Add uniqueness constraints required for idempotency.

---

# 125. FFmpeg Container / Worker

Docker environment must include known FFmpeg build.

Pin appropriate version.

Do not depend on random host-installed FFmpeg.

Expose:

```text
ffmpeg -version
```

through diagnostics, not arbitrary shell execution.

---

# 126. GPU Runtime

If GPU acceleration is enabled, document required Docker/runtime configuration.

CPU fallback should exist where practical.

Do not make development environment impossible without GPU.

---

# 127. Local Development

Provide mock provider capable of generating deterministic test video fixture or copying bundled sample fixture.

Developers must be able to run:

```text
Image
↓
Mock Video Generation
↓
FFmpeg
↓
Loop
↓
Variant
```

without external credentials.

---

# 128. Unit Tests — Provider

Test:

```text
successful submission

polling

provider success

provider failure

429

timeout

authentication failure

fallback

ambiguous submission
```

No real paid API.

---

# 129. Unit Tests — FFmpeg

Use short deterministic fixtures.

Test:

```text
probe metadata

trim

resize

crop

fps conversion

audio removal

encoding

thumbnail extraction
```

---

# 130. Loop Tests

Create fixtures:

```text
perfect loop

bad boundary

brightness jump

reversible motion

non-reversible motion
```

Test:

```text
DIRECT

CROSSFADE

PING_PONG
```

---

# 131. Loop Boundary Tests

Verify:

```text
first/last frame analysis

multi-frame boundary analysis

loop score

post-processing revalidation
```

Use deterministic local algorithms.

---

# 132. Video QA Tests

Use fixtures containing:

```text
stable video

flicker

scene cut

camera shake

large frame discontinuity
```

Verify deterministic metrics.

Mock semantic Vision QA.

---

# 133. Lineage Test

Verify:

```text
source image
↓
raw video
↓
processed video
↓
master video
↓
Android variant
```

can be traversed in both directions.

---

# 134. Immutability Test

Reprocessing:

```text
MASTER v1
↓
new settings
↓
MASTER v2
```

must not modify:

```text
RAW

MASTER v1
```

---

# 135. Cost Test

Simulate:

```text
generation attempt 1 failed but billed

fallback attempt 2 succeeded
```

Verify total production cost includes both.

---

# 136. Recovery Test

Simulate application restart while provider job is:

```text
PROVIDER_PROCESSING
```

Verify system resumes polling/reconciliation rather than creating another generation.

---

# 137. Concurrency Test

Submit:

```text
20 video productions
```

with:

```text
max provider concurrency = 3
max GPU jobs = 1
```

Verify limits deterministically.

---

# 138. Integration Tests

Use:

```text
PostgreSQL Testcontainers

MinIO

Mock Video Provider

local FFmpeg

mock Vision provider
```

Test full pipeline:

```text
Image
↓
Video Generation
↓
Raw Storage
↓
Validation
↓
QA
↓
FFmpeg
↓
Loop
↓
Master
↓
Variants
↓
Review
```

---

# 139. No Paid APIs in CI

CI must never call production image-to-video providers.

Use:

```text
mock provider

fixture videos

fake polling

fake failures
```

---

# 140. Performance Tests

Measure local processing for representative videos.

Record:

```text
resolution

duration

codec

CPU/GPU

processing duration

output size
```

Do not set unrealistic hard performance requirements without measuring actual hardware.

---

# 141. Documentation

Create:

```text
docs/video-factory.md

docs/video-providers.md

docs/video-prompts.md

docs/video-processing.md

docs/ffmpeg.md

docs/video-looping.md

docs/video-qa.md

docs/android-video-wallpapers.md
```

---

# 142. Provider Documentation

Document:

```text
provider

models

capabilities

limitations

async behavior

polling

webhooks

rate limits configuration

cost configuration

fallback behavior
```

Do not include credentials.

---

# 143. FFmpeg Documentation

Document:

```text
FFmpeg version

Docker setup

CPU processing

GPU processing

codec support

filter graphs

troubleshooting
```

---

# 144. Loop Documentation

Explain:

```text
DIRECT

CROSSFADE

PING_PONG
```

and selection rules.

Document loop metrics and thresholds.

---

# 145. Android Video Documentation

Document:

```text
video profiles

resolution families

codec

fps

bitrate

duration

loop behavior

audio policy

fallback
```

Keep Android compatibility assumptions explicit.

---

# 146. Definition of Done

TASK-09 is complete when this works end-to-end:

```text
Approved Image
↓
Motion Plan
↓
Video Prompt
↓
Real/Mock Image-to-Video Provider
↓
Async Provider Job
↓
Raw Video
↓
Technical Validation
↓
Video QA
↓
FFmpeg Processing
↓
Loop Analysis
↓
Loop Processing
↓
MASTER VIDEO
↓
Video Variants
↓
Human Review
↓
APPROVED
```

And this works:

```text
Wallpaper Image
↓
subtle animation
↓
5–10 sec video
↓
loop analysis
↓
crossfade/direct/ping-pong
↓
Android Live Wallpaper variant
```

And:

```text
RAW VIDEO
↓
Reprocess
↓
MASTER v1

RAW VIDEO
↓
different processing settings
↓
MASTER v2
```

preserves both outputs.

---

# 147. Verification

Before completing TASK-09:

1. run backend tests;
2. run frontend tests;
3. run integration tests;
4. run production builds;
5. apply migrations;
6. verify FFmpeg startup diagnostics;
7. verify ffprobe;
8. verify CPU processing;
9. verify GPU detection if available;
10. run mock image-to-video generation;
11. persist provider job ID;
12. simulate async polling;
13. download/store raw video;
14. verify raw checksum;
15. verify video metadata;
16. validate codec;
17. validate duration;
18. validate FPS;
19. sample frames;
20. run video QA;
21. detect scene cut fixture;
22. detect flicker fixture;
23. calculate motion intensity;
24. trim video;
25. resize video;
26. crop video;
27. remove audio;
28. normalize FPS;
29. run optional interpolation;
30. encode master;
31. verify master lineage;
32. run loop boundary analysis;
33. generate DIRECT loop;
34. generate CROSSFADE loop;
35. generate PING_PONG loop;
36. validate resulting loops;
37. generate repeated loop preview;
38. create poster;
39. create thumbnail;
40. create Android FHD video;
41. create Android generic fallback;
42. create 9:16 variant;
43. create 16:9 variant;
44. create 1:1 variant;
45. verify output checksums;
46. verify immutable raw video;
47. reprocess into v2;
48. verify v1 unchanged;
49. simulate provider 429;
50. simulate provider timeout;
51. simulate provider failure;
52. verify fallback;
53. simulate ambiguous submission;
54. verify no blind duplicate paid generation;
55. simulate application restart during provider processing;
56. verify polling resumes;
57. test provider concurrency;
58. test GPU-worker concurrency;
59. verify cost attribution;
60. verify failed billed attempts count toward total cost;
61. verify regeneration vs reprocessing behavior;
62. verify human approval;
63. verify no provider credentials in logs;
64. verify CI uses no paid provider.

---

# 148. Final Codex Report

At completion provide:

## Architecture

Describe:

```text
Image
→ Motion Plan
→ Provider
→ Raw Video
→ QA
→ FFmpeg
→ Loop
→ Master
→ Variants
```

## Video Providers

Report:

```text
provider

model

capabilities

async mechanism

polling/webhook

rate limiting

fallback

known limitations
```

Do not expose credentials.

## Motion Engine

Report:

```text
MotionPlan structure

Prompt Template

Prompt Version

negative prompt handling

provider adaptation
```

## FFmpeg

Report:

```text
version

processing stages

CPU/GPU support

codecs

filter graphs

encoding profiles
```

## Loop Engine

Report:

```text
DIRECT

CROSSFADE

PING_PONG

boundary analysis

loop metrics

selection policy
```

## Video QA

Report:

```text
technical validation

frame sampling

flicker

scene cuts

motion intensity

camera stability

semantic Vision QA
```

## Variants

Report actual implemented:

```text
Android Live Wallpaper

9:16

16:9

1:1

Preview

Poster

Thumbnail
```

with dimensions/codecs/FPS/quality configuration.

## Cost Tracking

Report:

```text
generation attempts

failed billed attempts

fallback costs

cost per approved video
```

## Tests

Report:

```text
unit

integration

FFmpeg

loop

QA

provider

recovery

concurrency

frontend
```

## Manual Verification

State exactly which provider/manual generations were actually tested.

If real provider credentials were unavailable, say so explicitly.

Never claim a live video generation that was not executed.

## Remaining Limitations

Explicitly document:

```text
provider limitations

video reproducibility limitations

loop-detection limitations

Vision QA limitations

GPU-specific limitations

codec/client compatibility limitations

future AI interpolation opportunities

future AI-native loop-generation opportunities
```

---

# Engineering Principles

Throughout TASK-09:

1. Video Factory orchestrates existing Media Factory infrastructure instead of duplicating it.
2. Image-to-video providers are replaceable adapters.
3. Provider-specific DTOs never enter core domain.
4. Video generation is asynchronous by default.
5. Never keep database transactions open while waiting for provider generation.
6. Never blindly retry an ambiguous paid generation submission.
7. Persist provider job IDs immediately.
8. Polling failures are different from generation failures.
9. Rate limiting, concurrency and active remote-job limits are separate concerns.
10. Preserve every paid generation attempt.
11. Failed billed attempts count toward production cost.
12. Download provider results immediately because temporary URLs may expire.
13. Raw provider video is immutable.
14. Local reprocessing must not require another paid generation.
15. REGENERATE and REPROCESS are different operations.
16. All local processing has immutable lineage.
17. Store FFmpeg version and processing parameters.
18. Never construct unsafe shell commands from user-controlled strings.
19. FFmpeg work runs asynchronously.
20. GPU concurrency must be bounded.
21. CPU fallback should remain available where practical.
22. Do not interpolate frames unless useful.
23. Do not stabilize intentional cinematic motion blindly.
24. Use deterministic CV analysis for deterministic temporal properties.
25. Use Vision AI for semantic visual defects.
26. Do not send every video frame to expensive Vision APIs unnecessarily.
27. Loop quality requires temporal continuity, not merely similar first/last frames.
28. Compare boundary frame windows, not only two frames.
29. Revalidate a loop after processing.
30. Keep DIRECT, CROSSFADE and PING_PONG as explicit strategies.
31. Do not use ping-pong when reversed motion looks physically wrong.
32. Wallpaper motion should default to subtle rather than spectacular deformation.
33. Preserve the source-image identity and composition.
34. Do not generate each output aspect ratio independently with paid AI unless explicitly required.
35. Create one high-quality master video and derive variants.
36. Optimize previews separately from masters.
37. Do not stream huge master videos in dashboard grids.
38. Android wallpaper video should prioritize smooth decoding, reasonable size and seamless playback.
39. Audio policy must be explicit.
40. Codec assumptions must be documented.
41. All generated assets require checksums.
42. Pipeline state must survive application restart.
43. Duplicate events must not create duplicate assets.
44. Cancellation must preserve already-incurred cost and provenance.
45. Automated video generation must have budget and attempt guards.
46. CI must never call paid video-generation APIs.
47. Human review remains available before production use.
48. Architecture must allow future video providers without domain rewrites.
49. Architecture must allow future AI interpolation, optical-flow processing and AI-native loop generation.
50. Preserve enough provenance so future analytics can correlate source image, image prompt, motion prompt, provider, model, processing profile, loop strategy, cost and downstream performance.

The result of TASK-09 should turn Media Factory into a complete **AI Video Factory** capable of taking an approved static image, generating controlled motion through interchangeable image-to-video providers, preserving the raw provider result, performing reproducible FFmpeg processing, detecting temporal defects, constructing and validating seamless loops, generating Android/social variants, and maintaining complete generation, cost and processing lineage.
