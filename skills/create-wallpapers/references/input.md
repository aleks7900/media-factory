# create-wallpapers input

Required: projectId, wallpaper collectionId and profile. Generation: conceptIds (or collection concepts), targetCount, optional provider/model and maxBudget/currency. Reuse: processOnly true with explicit assetIds from that wallpaper collection. Optional: amoled, metadata, exportPackage and publishToBackend. Profiles control dimensions/devices/previews.

Wrap input in:

```json
{"skillName":"create-wallpapers","operationId":"a-stable-operation-id","projectId":"project-UUID","input":{}}
```

Use real resolved UUIDs and the fields above; the empty input is illustrative, not executable. Optional collectionId belongs beside projectId. Plan is always dry-run; execution is a separate start action. See [shared API](../../_shared/api-reference.md).
