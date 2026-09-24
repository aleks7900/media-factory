# Smart crop

`SmartCropService` delegates through `SubjectDetectionProvider`. Existing structured TASK-04 `metadata.focalRegion` coordinates are reused when present; no paid Vision call is made for cropping. Otherwise OpenCV's bundled frontal-face Haar cascade runs locally. If no faces are detected, a bounded spectral-residual saliency heuristic supplies candidate regions. This is not a semantic person/animal/text detector; profiles should use review or letterboxing when preservation is uncertain.

Coordinates are normalized to the orientation-corrected source: x/y are top-left, width/height are extents, all within [0,1]. Region types retain the provider's classification and confidence. The crop engine evaluates candidate placements along padded focal boundaries and centers; face coverage has higher weight. Profile-controlled padding and top/bottom safe zones constrain preservation. All significant regions must fit for safe SMART_FILL; incompatible multiple subjects produce `SMART_CROP_UNSAFE`.

`unsafeCrop=LETTERBOX` preserves the entire image on an explicitly BLACK/WHITE background. `NEEDS_REVIEW` or `SKIP` stops that branch with a visible error; neither silently discards subjects. A human can inspect the preview, drag/scale a target-aspect rectangle, or reset to automatic. The request's `manualCrops` map contains profile keys and normalized rectangles. Automatic and final crop, regions, warnings and human override are preserved in the artifact metadata and execution manifest. Current actor is `local-workspace` under the existing local trust model.

Modes:

| Mode | Behavior |
|---|---|
| FIT | Entire image within target bounds, aspect preserved, no automatic canvas |
| FILL | Focal-weighted aspect crop then exact dimensions; excluded regions recorded |
| SMART_FILL | Focal crop with safety policy, then exact dimensions |
| EXACT | Explicit stretch to exact width/height; only use when distortion is intended |
| PRESERVE | Preserve source composition and the selected upscale dimensions |

The normalized crop geometry is deterministic for supplied regions. Default tests cover centered/left/right faces, top-edge safe zones, multiple subjects, impossible preservation and manual validation. Detector confidence is heuristic and is not calibrated as a probability of crop correctness.
