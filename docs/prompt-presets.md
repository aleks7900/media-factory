# Prompt presets

Presets are reusable literal positive/negative fragments, with stable key/name/category/status metadata and append-only content revisions. `prompt_presets` holds identity; `prompt_preset_versions` holds immutable fragments and revision number. Database triggers reject updates/deletes to content revisions.

```json
{
  "key":"AMOLED",
  "name":"AMOLED",
  "category":"STYLE",
  "description":"Controlled contrast for OLED wallpaper",
  "positiveFragment":"deep OLED black background, high subject separation, controlled highlights",
  "negativeFragment":"gray background, washed blacks, overexposure"
}
```

Create with `POST /api/v1/prompt-presets`; list with GET on the same path. `GET /api/v1/prompt-presets/{id}` includes all revisions. `PATCH` requires current metadata `revision`; supplied content creates the next immutable revision. Metadata-only archival does not rewrite content. No deletion endpoint exists.

Render/generation requests supply an ordered list of preset keys, e.g. `["AMOLED","CINEMATIC"]`. The engine selects each current active revision once and stores its ID, version, name/key, and exact fragments in the generation snapshot. Duplicated, missing or archived selections fail explicitly. There is no inferred compatibility policy based on category; all active literal presets are composable, subject to final length limits and deterministic lint warnings.

Preset fragments deliberately do not support variables; parameter definitions belong to the versioned base template. This avoids implicit schema merging and ambiguous defaults. Preset order is request order, not alphabetical order. Each channel composes base, presets, pipeline constraints and visible manual suffixes.

The Presets page creates fragments and edits them as new revisions. Updating AMOLED after a generation cannot change that generation's history, retry, fallback or regeneration input. Full catalogue revision history remains available through the detail API; the initial grid shows current revisions.
