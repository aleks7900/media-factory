# create-collection input

Required: projectId, name, concepts [{name,prompt,variables?}]. Optional: mediaType IMAGE/WALLPAPER/STOCK, description, slug (required for WALLPAPER), theme, style, amoled, targetAssetCount, diversityPlan, trendCandidateId, promptVersionId, presetKeys, experimentId, generate, provider/model, dimensions, maxBudget/currency.

Wrap input in:

```json
{"skillName":"create-collection","operationId":"a-stable-operation-id","projectId":"project-UUID","input":{}}
```

Use real resolved UUIDs and the fields above; the empty input is illustrative, not executable. Optional collectionId belongs beside projectId. Plan is always dry-run; execution is a separate start action. See [shared API](../../_shared/api-reference.md).
