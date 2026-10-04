# Task: Add Bulk AI Content Generation Pipelines to Media Content Factory

## Goal

Extend **Media Content Factory** with two dedicated bulk-generation sections:

1. **Bulk Gemini Video Generation**
2. **Bulk GPT Image Generation**

Both sections should support uploading a ZIP archive containing many independent generation tasks. The system must extract, validate, queue, process, track, retry, and store every task independently.

---

# 1. Bulk Gemini Video Generation

Create a dedicated section in the application:

**Media Content Factory → Gemini Video → Bulk Generation**

## ZIP Upload

Allow the user to upload a `.zip` archive containing car/video generation tasks.

Example:

```text
cars-video-tasks.zip
├── bmw_m4/
│   ├── task.md
│   ├── front.jpg
│   ├── rear.jpg
│   └── side.jpg
├── audi_rs6/
│   ├── task.md
│   └── reference.jpg
└── porsche_911/
    ├── task.md
    ├── image1.jpg
    └── image2.jpg
```

Each top-level directory represents **one video generation task**.

`task.md` contains the Gemini Video prompt/instructions.

Images in the same directory are reference/input images for that task.

Also support a simpler format where each `.md`/`.txt` file represents one task if no task directories exist.

## Import Flow

After uploading:

1. Validate ZIP.
2. Extract safely.
3. Detect individual tasks.
4. Parse prompts.
5. Detect reference images.
6. Create a database job for the archive.
7. Create one database task per detected item.
8. Display an import summary before or immediately after queueing:

```text
Archive: cars-video-tasks.zip

Tasks detected: 125
Valid: 123
Invalid: 2

References: 278 images
```

Invalid tasks must not block valid tasks.

## Automatic Gemini Video Processing

Imported tasks should automatically enter the Gemini Video generation queue.

Pipeline:

```text
ZIP Upload
    ↓
Archive Parsing
    ↓
Task Creation
    ↓
QUEUED
    ↓
Gemini Video Worker
    ↓
GENERATING
    ↓
Video Generated
    ↓
Store Result
    ↓
COMPLETED
```

The worker must process tasks asynchronously.

Do not keep the HTTP upload request open while generation is happening.

Use configurable concurrency and rate limiting to avoid exceeding Gemini API quotas.

## Task Statuses

At minimum support:

```text
PENDING
QUEUED
GENERATING
COMPLETED
FAILED
RETRYING
CANCELLED
```

Display progress such as:

```text
Cars Video Batch

123 tasks

Completed     74
Generating     5
Queued        39
Failed         5

Progress: 60%
```

## Task Details

Each task should show:

- Task name
- Original prompt
- Reference images
- Gemini model
- Creation time
- Start time
- Completion time
- Current status
- Retry count
- Error message
- Generated video
- Video metadata
- Download action

Provide actions:

**Retry**
**Cancel**
**Regenerate**
**Delete**

Failures must be isolated. One failed generation must never stop the entire batch.

---

# 2. Bulk GPT Image Generation

Create another dedicated section:

**Media Content Factory → GPT Image → Bulk Generation**

The architecture should reuse the generic bulk-job infrastructure where possible.

## ZIP Format

Example:

```text
image-tasks.zip
├── green_dragon/
│   ├── task.md
│   └── reference.jpg
├── cyberpunk_bmw/
│   └── task.md
├── japanese_wolf/
│   ├── task.md
│   ├── reference1.jpg
│   └── reference2.jpg
└── futuristic_city/
    └── task.md
```

Each directory represents one GPT Image generation task.

`task.md` contains the image prompt.

Associated images should automatically become reference images when the selected GPT Image API operation supports them.

---

# 3. GPT Image Queue

Processing:

```text
ZIP
 ↓
Parser
 ↓
Image Tasks
 ↓
Queue
 ↓
GPT Image Worker
 ↓
Generation API
 ↓
Result Storage
 ↓
COMPLETED
```

Support configurable generation options where supported by the existing integration, such as:

- model
- size/aspect ratio
- quality
- output format
- transparent background
- number of outputs

Do not hardcode capabilities that a particular model/API does not support.

---

# 4. Batch Dashboard

Both systems should provide a batch history screen.

Example:

```text
BATCHES

Cars October #1
Gemini Video
123 tasks
74 / 123 completed
RUNNING

Wallpaper Pack #12
GPT Image
250 tasks
250 / 250 completed
COMPLETED

Cars October #2
Gemini Video
80 tasks
12 / 80 completed
RUNNING
```

Clicking a batch opens its individual tasks.

Add filtering by:

```text
All
Running
Queued
Completed
Failed
Cancelled
```

Also provide search by task/batch name.

---

# 5. Batch Controls

Implement:

- Pause batch
- Resume batch
- Cancel remaining tasks
- Retry all failed tasks
- Delete batch
- Download completed outputs

For completed batches provide:

**Download Results ZIP**

Example:

```text
cars-video-results.zip
├── bmw_m4.mp4
├── audi_rs6.mp4
├── porsche_911.mp4
└── manifest.json
```

For image batches:

```text
image-results.zip
├── green_dragon.png
├── cyberpunk_bmw.png
├── japanese_wolf.png
└── manifest.json
```

The manifest should map outputs back to their original task IDs/names and contain generation status/metadata.

---

# 6. Persistence

Generation must survive:

- browser refresh
- user closing the page
- application restart
- worker restart

Do not implement the queue purely in frontend memory.

Persist batches/tasks/statuses in the backend/database.

Suggested entities:

```text
GenerationBatch
- id
- name
- type
- sourceArchive
- status
- totalTasks
- completedTasks
- failedTasks
- createdAt
- startedAt
- completedAt

GenerationTask
- id
- batchId
- name
- prompt
- provider
- model
- configuration
- status
- retryCount
- error
- createdAt
- startedAt
- completedAt

GenerationAsset
- id
- taskId
- type
- path/url
- metadata
```

Adapt this to the existing project's architecture rather than blindly introducing duplicate entities.

---

# 7. Queue Reliability

Inspect the existing Media Content Factory architecture first and reuse its current Gemini/OpenAI clients, authentication, storage, task processing, and configuration mechanisms.

Requirements:

- configurable worker concurrency
- API rate limiting
- exponential retry/backoff
- maximum retry count
- API timeout handling
- restart recovery
- idempotent task processing where practical
- duplicate execution protection
- useful structured logs

The worker must distinguish retryable errors such as temporary API/rate-limit failures from permanent validation errors.

---

# 8. ZIP Security

Treat uploaded archives as untrusted.

Protect against:

- ZIP path traversal / Zip Slip
- decompression bombs
- excessive archive size
- excessive extracted size
- excessive file count
- unsupported files
- deeply nested directories
- malformed archives
- duplicate task names

Limits should be configurable.

---

# 9. UI

Add two clear cards/sections to Media Content Factory:

```text
┌──────────────────────────────┐
│ 🎬 Bulk Gemini Video        │
│                              │
│ Generate hundreds of videos │
│ from a ZIP task archive.    │
│                              │
│ [ Upload ZIP ]              │
└──────────────────────────────┘

┌──────────────────────────────┐
│ 🖼 Bulk GPT Image           │
│                              │
│ Generate images in bulk     │
│ from a ZIP task archive.    │
│                              │
│ [ Upload ZIP ]              │
└──────────────────────────────┘
```

After import, immediately navigate to or show the batch dashboard so generation progress can be monitored.

Progress should update automatically without requiring manual page refresh.

---

# 10. Architecture Requirement

Do not build two unrelated implementations.

Introduce/reuse a generic bulk generation architecture:

```text
BulkGenerationService
        │
        ├── GeminiVideoProcessor
        │
        └── GptImageProcessor
```

Provider-specific processors should handle API differences while batch/task lifecycle management remains shared.

This should make it easy to add future processors such as:

```text
Veo
Sora
Nano Banana
Imagen
Runway
Kling
```

without rebuilding the queue system.

---

# Acceptance Criteria

The implementation is complete when:

1. I can upload a ZIP containing 100+ car tasks.
2. The application detects and creates all valid tasks.
3. Gemini Video generation starts automatically.
4. Each task is processed independently.
5. Generated videos are attached to their corresponding tasks.
6. Failed tasks can be retried individually or in bulk.
7. I can pause/resume/cancel a batch.
8. Progress survives page reload and application restart.
9. I can download completed videos as a ZIP.
10. I can upload a separate ZIP containing 100+ GPT Image tasks.
11. GPT Image tasks are automatically generated through the existing OpenAI image integration.
12. Image outputs are stored against their original tasks.
13. I can download the generated images as a ZIP.
14. Gemini Video and GPT Image share the common batch/task infrastructure.
15. Existing single-generation functionality continues working without regressions.

## Implementation Instruction

Before changing code, inspect the existing Media Content Factory implementation and identify the current:

- Gemini Video integration
- GPT Image integration
- task/job entities
- storage implementation
- async/queue mechanism
- API configuration
- frontend routing/components

Reuse existing infrastructure whenever possible.

Implement this incrementally, add database migrations where required, and add automated tests for ZIP parsing, batch creation, queue processing, retries, failure isolation, restart recovery, and provider processors.

Do not replace working generation integrations merely to accommodate bulk generation.