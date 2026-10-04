# run-qa input

Required: projectId plus explicit assetIds or collection/date scope. Optional: collectionId, generationBatchId, status, from/to UTC instants, profile, forceRerun and maxAssets. Resolve today using the user timezone into an explicit half-open UTC interval.

Wrap input in:

```json
{"skillName":"run-qa","operationId":"a-stable-operation-id","projectId":"project-UUID","input":{}}
```

Use real resolved UUIDs and the fields above; the empty input is illustrative, not executable. Optional collectionId belongs beside projectId. Plan is always dry-run; execution is a separate start action. See [shared API](../../_shared/api-reference.md).
