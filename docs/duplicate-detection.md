# Duplicate detection and review

SHA-256 uses Java `MessageDigest` over original stored bytes. Storage checksum disagreement fails extraction. SHA equality is authoritative for byte identity; recompression, resizing, and metadata changes can produce different checksums.

pHash uses a 32 × 32 RGB image resized with bicubic interpolation, BT.601 luminance, a two-dimensional DCT-II, and the low-frequency 8 × 8 coefficient block. The median excludes DC; the DC output bit is fixed to zero, yielding a stored 64-bit value with 63 informative bits. The version is `dct32-low8-acmedian-v1`. Hamming distance counts differing bits. Low luminance variance (<25 at the reduced resolution) marks an ambiguous low-information fingerprint and suppresses pHash duplicate inference. PNG/JPEG and resized deterministic image fixtures calibrate basic invariance; this is not a photographic production calibration set.

## Initial profiles

| Profile | pHash duplicate / near | Embedding corroboration / related | Block duplicate / near publication | Prompt guard | Saturation pause |
|---|---|---|---|---|---|
| WALLPAPER | 4 / 10 | .94 / .86 | yes / no | WARN | .80 |
| STOCK_STRICT | 4 / 10 | .94 / .86 | yes / yes | BLOCK | .65 |
| SOCIAL | 3 / 8 | .96 / .88 | no / no | WARN | .90 |
| GENERATION_DIVERSITY | 4 / 10 | .94 / .86 | yes / no | WARN | .75 |

These values are tunable starting points, not universal truths. Update profiles with their current revision through the REST API. Each comparison keeps a policy snapshot. Production tuning should use labeled originals, recompressions, crops, lighting changes, distinct images of the same subject, and human override history. Measure false-positive/negative rates by collection type before tightening automatic publication blocks.

Rules run in this order:

1. Matching SHA → EXACT_DUPLICATE, regardless of lineage.
2. Informative pHash within duplicate threshold → PERCEPTUAL_DUPLICATE, subject to human review.
3. Informative pHash within near threshold **and** sufficient embedding similarity → NEAR_DUPLICATE. Same regeneration family downgrades this to VISUALLY_SIMILAR.
4. High embedding similarity without corroboration → SEMANTICALLY_SIMILAR.
5. Nearby pHash without corroboration → VISUALLY_SIMILAR, with an ambiguity explanation for low-information images.
6. Otherwise → DISTINCT for that evaluated candidate. Low-value distinct pairs are not persisted.

Embedding similarity alone never proves a duplicate. Same collection, concept, prompt version, and generation family are recorded independently. Parent ancestry is bounded at 100 generations to protect malformed/deep histories.

## Families and human decisions

EXACT, PERCEPTUAL, and NEAR_DUPLICATE families are connected components of recognized relationships, merged as new edges arrive. Members retain their originals and cost provenance. A human explicitly selects a canonical member with a reason and revision. Selecting a non-member is rejected. Human MARK_DISTINCT / CONFIRM_NEAR_DUPLICATE / CONFIRM_DUPLICATE changes the final classification, leaves automatic evidence intact, and creates an immutable action containing actor, reason, timestamp, and before/after state. The private local deployment uses the existing `ReviewActor` boundary (`local-workspace`); production authentication must replace that boundary.

Changing a relationship supersedes affected families and rebuilds them from effective edges. Historical family records remain, while canonical selection may need to be repeated after a split/merge. A family can contain indirectly related members; do not infer every pair is equally similar.

QA receives SIMILARITY-category findings with comparison/target IDs, pHash and embedding values. Existing QA policy determines APPROVED/NEEDS_REVIEW/REJECTED. Human pair review does not rewrite an already completed QA review: rerun QA or review it explicitly to preserve its original snapshot.

Publication is protected in the Java workflow and PostgreSQL triggers, including direct changes to generation status. Strict policy requires complete active-model analysis. Current policy thresholds are evaluated against persisted measurements and human overrides; selecting an explicit canonical original allows that original through its family's duplicate block, while other family members remain blocked. A QA approval alone cannot bypass the gate.
