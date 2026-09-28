# Loop analysis and construction

The analyzer compares head/tail windows of up to five frames, pixel/luminance/edge differences, incoming/outgoing frame-change velocities and seam-motion error. It reports visual boundary, motion continuity, color continuity and weighted overall score (45%, 35%, 20%). Values are deterministic heuristics, not a perceptual guarantee; color continuity currently measures luminance rather than a full chroma-space distribution.

`DIRECT` preserves a naturally continuous sequence. `CROSSFADE` blends overlapping head/tail segments and shortens the sequence by the fade duration. `PING_PONG` appends reversed motion while omitting duplicate terminal frames. It requires explicit reversible/static-camera eligibility; directional or physically irreversible motion should use another strategy or regeneration.

AUTO selects DIRECT at overall score ≥0.90, CROSSFADE at ≥0.45, otherwise PING_PONG only when eligible; all other cases produce a DIRECT review candidate with a warning. A result below 0.80 remains flagged for human loop review. Every constructed master is decoded and analyzed again. None of these thresholds automatically approves a production.

The preview repeats the sequence three times so a reviewer can inspect the seam. Earlier strategy outputs remain in their processing versions and are selectable for comparison. Changing the strategy invokes local reprocessing of the same raw asset. Explicit audio KEEP is incompatible with chronology-changing loop strategies; silent wallpaper output is the default.

Limitations: crossfades may ghost moving subjects; ping-pong may reverse implausibly; matching boundaries cannot prove a perfectly seamless loop. Future optical-flow blending or provider-native loop generation can be added behind processing/provider ports without changing the production aggregate.
