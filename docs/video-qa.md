# Video quality assurance

Technical checks cover container, video codec, stream count, dimensions/pixel area, duration, FPS, bitrate, file size, frame count, audio policy and full decode. Invalid raw media is preserved and stops at VALIDATION_FAILED. All derived videos are decoded and validated before a completed run can be reviewed.

Deterministic CV analyzes downsampled frames for abrupt scene changes, alternating brightness flicker, motion intensity, phase-correlation camera movement/shake, excessive motion and frozen-frame intervals. Findings carry start/end timestamps. These thresholds are diagnostic: intended transitions or static shots may be flagged. The timeline evidence remains available in the review panel.

Five JPEG samples cover start, quarter, midpoint, three-quarter and final frames. `VideoSemanticQa` reuses TASK-04 `VisionQualityProvider` evidence for anatomy, identity, composition, artifacts, text, watermarks and prompt compliance. It records per-frame usage/cost before calls and persists completed evidence. Interrupted STARTED or failed paid calls are not blindly replayed after restart; incomplete evidence is shown for human review.

`VIDEO_VISION_PROVIDER=mock` is the default. Mock evidence is synthetic, never proof of semantic quality. Real sampled Vision requires an existing enabled TASK-04 adapter plus `VIDEO_VISION_MAX_FRAME_COST` > 0 and sufficient production budget. That value reserves a conservative maximum per sample; verify pricing before enabling. Image-level Vision cannot reliably detect all temporal morphing, and five frames may miss brief defects. Full human playback review remains mandatory even with real Vision.

Human approval requires acknowledgement of temporal and loop evidence. It records the existing shared quality-review decision and video production approval. Reprocessing resets the video production to review; old output files and QA history remain available.
