# prepare-stock input

Required: projectId, explicit asset/collection scope, profile. Optional: maxAssets, exportPackage and exportProfile. Resolve actual profile identifiers from /stock-profiles and /stock-export-profiles. Unsupported target/output choices require an existing configured profile, not guessed marketplace rules.

Wrap input in:

```json
{"skillName":"prepare-stock","operationId":"a-stable-operation-id","projectId":"project-UUID","input":{}}
```

Use real resolved UUIDs and the fields above; the empty input is illustrative, not executable. Optional collectionId belongs beside projectId. Plan is always dry-run; execution is a separate start action. See [shared API](../../_shared/api-reference.md).
