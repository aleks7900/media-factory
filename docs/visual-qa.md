# Visual QA providers

`VisionQualityProvider` accepts image bytes and a neutral immutable context and returns `QualityModels.Evidence`, usage and safe metadata. Domain services do not depend on OpenAI request/response shapes. The earlier `VisionProvider` remains compatible with the foundation's mock port; advanced inspection uses the richer evidence contract.

Context includes asset/generation, checksum/MIME, expected dimensions, collection, pipeline, generation provider/model and the exact stored TASK-03 prompt snapshot: canonical positive/negative, adapted positive/negative, variables, presets, composition and experiment attribution. The successful generation provider's snapshot is selected. QA never calls PromptEngine to rerender historical content. Missing legacy context prevents automatic approval.

The versioned runtime prompt is `backend/src/main/resources/qa/visual-quality-v1.txt`, cataloged by `prompts/qa/visual-quality/v1.yaml`. It treats prompts and image content as untrusted data, demands visible evidence, respects intentional fantasy/stylization, distinguishes confidence from severity, and avoids taste judgments and a single quality number.

## Structured contract

Dimensions: TECHNICAL_INTEGRITY, PROMPT_COMPLIANCE, SUBJECT_INTEGRITY, ANATOMY, COMPOSITION, VISUAL_COHERENCE, TEXT_FREE, WATERMARK_FREE, CROP_QUALITY. Each has score, confidence, applicability and evidence. Only ANATOMY may be inapplicable. Technical integrity is supplied by the deterministic inspector when combining evidence.

Findings have category, stable code, severity, confidence, detected, source, evidence and metadata. Categories: TECHNICAL, ARTIFACT, ANATOMY, TEXT, WATERMARK, PROMPT_COMPLIANCE, COMPOSITION, SUBJECT, CROP, OTHER. Codes cover corrupted/unsupported files, dimensions/aspect/size/duplicates/transparency, blur/exposure/borders, artifacts/repetition/geometry, faces/eyes/limbs/anatomy, unwanted text/logos/watermarks, missing/wrong subjects and requirements, conflicts, negative constraints, crop and composition. Prompt findings preserve requirement/observation evidence. Sources distinguish technical, Vision and human evidence.

Output must contain all dimensions, unique names, finite normalized numbers, correctly typed required fields, allowed enums and bounded evidence. Strict JSON Schema is sent to the provider and validated again locally. Truncated, refused, missing, invalid or unexpected output is an execution failure, never a rejection verdict. Raw responses and image binaries are not stored in review JSON or logs.

## Mock scenarios

`MOCK_VISION_SCENARIO`: PERFECT, MINOR_ARTIFACT, MAJOR_ARTIFACT, MALFORMED_FACE, UNWANTED_TEXT, WATERMARK, LOW_PROMPT_COMPLIANCE, UNCERTAIN, RATE_LIMIT, TIMEOUT, INVALID_RESPONSE, PROVIDER_ERROR. Results are deterministic and explicitly labeled mock evidence. Rerun accepts `mockScenario` for local demonstrations. Mock findings are test fixtures, not an assessment of visible pixels; technical checks still inspect the actual image.

## OpenAI adapter

Enable independently of image generation:

```dotenv
VISION_PROVIDER=openai
OPENAI_VISION_ENABLED=true
OPENAI_API_KEY=<set privately>
OPENAI_VISION_MODEL=gpt-4.1-mini-2025-04-14
```

Recreate backend with `docker compose up -d --build`. Model access depends on the account. Image generation can remain mock while QA uses Vision. The adapter sends a bounded base64 image to Responses with `store:false`, strict structured output, no redirects, a 10-second connect timeout and default 90-second complete-exchange deadline. Output is capped at 1 MiB and 6000 tokens. HTTPS is required except literal loopback addresses used by HTTP contract tests.

The pinned model estimate uses $0.40/million input and $1.60/million output tokens, pricing version `openai-gpt-4.1-mini-2026-09-24`. It conservatively prices all input tokens at the uncached rate; custom model estimates remain unknown. Reported usage includes image input token accounting; the application does not independently guess image tokenization. Sources checked: [model capabilities/pricing](https://developers.openai.com/api/docs/models/gpt-4.1-mini), [image inputs](https://developers.openai.com/api/docs/guides/images-vision), [structured output](https://developers.openai.com/api/docs/guides/structured-outputs).

No OCR, face detector, segmentation model, watermark classifier training or provider-specific fields are embedded in domain services. Visual anatomy and semantic compliance judgments are probabilistic model evidence; use human review for ambiguity. No live accuracy calibration dataset is supplied.
