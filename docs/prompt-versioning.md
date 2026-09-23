# Prompt versioning

Templates hold stable identity (`key`, name, category, description, ACTIVE/ARCHIVED), never production prompt content. Versions hold positive/negative templates and relational variable definitions. `(prompt_template_id, version)` is unique; new version numbers are allocated while locking the parent template.

```text
v1 DRAFT → PUBLISHED → DEPRECATED
             ↓ copy
          v2 DRAFT → PUBLISHED
                        ↓ copy
                     v3 DRAFT
```

DRAFT content is editable using `PATCH /api/v1/prompt-versions/{id}` with its current `revision`. Missing/stale revisions return 409. A published or deprecated version cannot be edited. Create a new draft using `POST /api/v1/prompt-templates/{id}/versions` with `copyFromVersionId` and `changeDescription` instead. Original content/schema is copied; later changes affect only the new draft.

Publish using `POST /api/v1/prompt-versions/{id}/publish` with `{revision: n}`. Invalid syntax, undefined variables or invalid variable/default schemas block publication. Required values are supplied at render time, so a required variable need not have a default. Unused definitions are warnings. Publishing records time and the local workspace actor. Deprecating uses the corresponding `/deprecate` endpoint and records its time/actor. Generation requires a published version and active template; existing snapshots/retries remain usable after deprecation or archival.

Database triggers protect published/deprecated content and their variable rows independently of the REST checks. They permit only the published-to-deprecated metadata transition. Snapshots and generation attribution are also protected against mutation. Transactions keep catalogue edits, version allocation, and generation acceptance atomic.

`GET /api/v1/prompt-templates/{id}/versions` returns the complete version timeline. `GET /api/v1/prompt-versions/{id}` includes variable definitions and audit metadata. `GET /api/v1/prompt-versions` provides a bounded published-version selector. Prompt History offers View, Create draft copy, and a side-by-side comparison of positive template, negative template and variable definitions. Differences are shown by section, without an additional diff-library dependency.

Archiving templates is preferred over deleting history. No destructive delete API is provided. `PATCH /api/v1/prompt-templates/{id}` changes metadata/status with optimistic `revision`; stable keys cannot be renamed. Draft editing keeps a revision counter but is not an append-only record of every keystroke. Published versions and generation snapshots are the durable production history.

Example initial version:

```json
{
  "positiveTemplate": "A premium portrait of {{subject}} under {{lighting}} light.",
  "negativeTemplate": "blurry {{subject}}, watermark",
  "variables": [
    {"name":"subject","label":"Subject","type":"STRING","required":true,"maxLength":100},
    {"name":"lighting","type":"ENUM","required":true,"defaultValue":"NEON","allowedValues":["NEON","SOFT"]}
  ],
  "changeDescription": "Initial cinematic direction"
}
```

Historical v1 generations read stored v1 snapshots. Publishing v2, editing presets, finishing an experiment or changing adapter implementation does not reconstruct those snapshots.
