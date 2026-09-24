# Versioned QA policies

Policies live in `backend/src/main/resources/qa/policies.json`. The entire selected policy is frozen in each review. Change the version when changing a rule; rebuild/redeploy to load edited policies. Do not silently change a version already used in production.

| Profile | Minimum dimensions | Human review | Intended use |
|---|---|---|---|
| default v1 | 64 × 64 | uncertain cases | General/dev fixtures |
| wallpaper-standard v1 | 1024 × 1024 | uncertain cases | Standard wallpaper |
| wallpaper-premium v1 | 2048 × 2048 | always for automatic approval | Premium wallpaper |
| stock v1 | 1024 × 1024 | always for automatic approval | Stock quality foundation |
| social v1 | 512 × 512 | uncertain cases | Social output, transparency allowed |

Policy fields configure minimum dimensions, exact dimension matching, aspect tolerance, maximum bytes, transparency, confidence thresholds, rejection codes and minimum scores per dimension. Pipeline `wallpaper` selects wallpaper-standard, `stock` selects stock, and other pipelines select default. Spring properties `media.qa.pipeline.<pipeline>` can change this mapping. `PUT /api/v1/collections/{id}/qa-policy` with `{"policyId":"stock"}` sets a collection reference that takes priority. Profiles can also be assigned through the Collections UI. Policies are not duplicated inside providers or pipelines.

The engine first examines detected findings. A CRITICAL finding or a configured rejection code at/above `rejectConfidence` rejects. MAJOR findings without conclusive rejection evidence, or uncertain detected findings, require review. Missing required dimensions, low dimension confidence or scores below a configured threshold require review. Non-applicable anatomy is exempt. Passing checks do not penalize a result. With all required evidence complete and acceptable, automatic approval is possible.

A high-confidence critical watermark can reject. A major artifact at 0.55 confidence requires review. Score and confidence are distinct: a high score with low confidence still needs review. This is not a weighted average or aggregate quality score. `alwaysHumanReview` preserves automatic APPROVED while setting final NEEDS_REVIEW. Conclusive technical corruption can reject without a Vision call; infrastructure failures cannot.

Machine-readable `rules_triggered` includes actual issue codes and dimension identifiers (`REJECT:POSSIBLE_WATERMARK`, `REVIEW:GENERATIVE_ARTIFACT`, `DIMENSION:PROMPT_COMPLIANCE`, `ALWAYS_HUMAN_REVIEW`). Review details expose the frozen policy, so a future policy change does not rewrite history.

Thresholds are operational starting points, not calibrated accuracy guarantees. Use human feedback and representative evaluation images before relying on unattended publishing. Stock policies address visual evidence only; they do not certify licensing, releases or marketplace compliance.
