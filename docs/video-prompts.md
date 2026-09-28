# Video motion and prompts

Four published TASK-03 templates cover image-to-video, wallpaper loops, cinematic motion and social motion. Profiles select their immutable prompt version. The production freezes the rendered canonical prompt; each attempt stores the request and provider-facing text.

Motion plans preserve subject, environment, camera, strength, speed, depth, particles, lighting, loop intent and style. Depth, particle and lighting descriptions are included in the environment prompt variable. The source asset ID/checksum and original image prompt are retained; its generation links back to image prompt provenance. Source images must have a current approved review.

Default motion is subtle with a static camera, low intensity, slow speed and no automatic reversal. ORBIT, HIGH strength and FAST speed require explicit risk acknowledgement. Reversible motion requires a static camera; the operator must decide whether physical reversal is plausible. Walking, rain, fire and directional action should not be marked reversible.

Editing creates an append-only motion version. Existing generation attempts keep the original plan and prompt. A regeneration creates a new production using the edited plan. Reprocessing can use the latest reversal eligibility without another provider charge.

Runway does not receive unsupported negative-prompt or FPS fields. Its prompt length and source-image constraints are validated before submission. Reproducible local processing does not imply reproducible paid AI generation: identical prompts/seeds may still vary with provider models and service versions.
