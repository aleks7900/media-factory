# research-trends input

Required: projectId, topic, mediaType, and sourced directions. Infer market/platform/audience/region/timeRange from the request when clear. Optional: collectionId and internal coverage references.

Wrap input in:

```json
{"skillName":"research-trends","operationId":"a-stable-operation-id","projectId":"project-UUID","input":{}}
```

Use real resolved UUIDs and the fields above; the empty input is illustrative, not executable. Optional collectionId belongs beside projectId. Plan is always dry-run; execution is a separate start action. See [shared API](../../_shared/api-reference.md).
