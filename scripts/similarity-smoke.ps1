param([string]$BaseUrl='http://localhost:3000')
$ErrorActionPreference='Stop'
function Send($Path,$Body,$Key) {
 $headers=@{};if($Key){$headers['Idempotency-Key']=$Key}
 Invoke-RestMethod "$BaseUrl/api$Path" -Method Post -ContentType 'application/json' -Headers $headers -Body ($Body|ConvertTo-Json -Depth 30)
}
function Wait-Analysis($Asset) {
 $deadline=(Get-Date).AddMinutes(3)
 do {$result=Invoke-RestMethod "$BaseUrl/api/v1/assets/$Asset/similar?minimumSimilarity=0";if($result.state -eq 'FAILED'){throw "Embedding failed for $Asset"};if($result.state -eq 'READY'){return $result};Start-Sleep -Milliseconds 500}while((Get-Date)-lt $deadline)
 throw "Analysis deadline exceeded for $Asset"
}
$providers=Invoke-RestMethod "$BaseUrl/api/v1/providers/image"
if(($providers|Where-Object {$_.default -and $_.id -ne 'mock'})){throw 'This verification requires the free mock image provider'}
$health=Invoke-RestMethod 'http://localhost:8001/health'
if($health.dimension -ne 512 -or $health.device -ne 'cpu'){throw 'This verification targets the pinned CPU CLIP worker'}
$project=Send '/projects' @{name='Similarity verification';description='Free local CLIP end-to-end checks'}
$collection=Send '/collections' @{projectId=$project.id;name='Prism · similarity laboratory'}
$concept=Send '/concepts' @{collectionId=$collection.id;name='Luminous geometry';prompt='Luminous concentric circles in a soft atmospheric gradient'}
$generation=Send '/generations' @{conceptId=$concept.id;prompt=$concept.prompt;width=768;height=768} ([guid]::NewGuid().ToString())
$deadline=(Get-Date).AddMinutes(2)
do {$detail=Invoke-RestMethod "$BaseUrl/api/v1/generations/$($generation.id)";if($detail.assets.Count){break};Start-Sleep -Milliseconds 500}while((Get-Date)-lt $deadline)
if(!$detail.assets.Count){throw 'Mock image generation failed'}
$source=$detail.assets[0]
$first=Wait-Analysis $source.id
$fixtureRoot=Join-Path $env:TEMP ('media-factory-similarity-'+[guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
Invoke-WebRequest "$BaseUrl/api/assets/$($source.id)/content" -OutFile (Join-Path $fixtureRoot 'source.png')
Add-Type -AssemblyName System.Drawing
$original=[System.Drawing.Image]::FromFile((Join-Path $fixtureRoot 'source.png'))
try {
 $original.Save((Join-Path $fixtureRoot 'recompressed.jpg'),[System.Drawing.Imaging.ImageFormat]::Jpeg)
 $resized=[System.Drawing.Bitmap]::new($original,384,384)
 try{$resized.Save((Join-Path $fixtureRoot 'resized.png'),[System.Drawing.Imaging.ImageFormat]::Png)}finally{$resized.Dispose()}
}finally{$original.Dispose()}
$clones=@()
foreach($item in @(@{file='source.png';mime='image/png';width=768;height=768},@{file='recompressed.jpg';mime='image/jpeg';width=768;height=768},@{file='resized.png';mime='image/png';width=384;height=384})) {
 $asset=[guid]::NewGuid().ToString();$gen=[guid]::NewGuid().ToString();$key="similarity-verification/$asset/$($item.file)"
 $command='mc alias set local http://minio:9000 "$S3_ACCESS_KEY" "$S3_SECRET_KEY" >/dev/null && mc cp /fixtures/'+$item.file+' local/media-factory/'+$key
 docker compose run --rm --no-deps --volume "${fixtureRoot}:/fixtures:ro" --entrypoint /bin/sh minio-init -c $command | Out-Null
 if($LASTEXITCODE -ne 0){throw 'Fixture upload failed'}
 $path=Join-Path $fixtureRoot $item.file;$sha=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant();$length=(Get-Item -LiteralPath $path).Length
 # Only newly allocated fixture IDs are inserted. Existing originals and stored media are immutable.
 $sql="BEGIN; INSERT INTO generations(id,concept_id,status,prompt,width,height) VALUES('$gen','$($concept.id)','QA_PENDING','Local similarity fixture',$($item.width),$($item.height)); INSERT INTO assets(id,generation_id,storage_key,sha256,media_type,size_bytes,width,height) VALUES('$asset','$gen','$key','$sha','$($item.mime)',$length,$($item.width),$($item.height)); COMMIT;"
 $sql | docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U mediafactory -d mediafactory | Out-Null
 if($LASTEXITCODE -ne 0){throw 'Fixture insert failed'}
 $clones+=@{id=$asset;file=$item.file}
}
foreach($clone in $clones){Wait-Analysis $clone.id | Out-Null}
$comparisons=Invoke-RestMethod "$BaseUrl/api/v1/similarity-comparisons?collection=$($collection.id)"
if(!($comparisons|Where-Object automatic_classification -eq 'EXACT_DUPLICATE')){throw 'Exact duplicate not detected'}
if(!($comparisons|Where-Object automatic_classification -eq 'PERCEPTUAL_DUPLICATE')){throw 'Recompressed/resized perceptual duplicate not detected'}
$semantic=Send '/v1/assets/search/semantic' @{query='concentric circles and a colorful gradient';limit=10}
if(!$semantic.Count){throw 'Semantic retrieval produced no results'}
$run=Send '/v1/embedding-jobs' @{type='CLUSTER_COLLECTION';collectionId=$collection.id} ([guid]::NewGuid().ToString())
$deadline=(Get-Date).AddMinutes(2)
do {$diversity=Invoke-RestMethod "$BaseUrl/api/v1/collections/$($collection.id)/diversity";if($diversity.run){break};Start-Sleep -Milliseconds 500}while((Get-Date)-lt $deadline)
if(!$diversity.run){throw 'Collection clustering failed'}
$pair=$comparisons|Where-Object automatic_classification -eq 'PERCEPTUAL_DUPLICATE'|Select-Object -First 1
$distinct=Send "/v1/similarity-comparisons/$($pair.id)/mark-distinct" @{revision=$pair.revision;reason='Smoke test verifies preserved automatic evidence'}
if($distinct.automatic_classification -ne 'PERCEPTUAL_DUPLICATE' -or $distinct.final_classification -ne 'DISTINCT'){throw 'Human override lost evidence'}
$confirmed=Send "/v1/similarity-comparisons/$($pair.id)/confirm" @{revision=$distinct.revision;classification='PERCEPTUAL_DUPLICATE';reason='Restore confirmed fixture relationship'}
$groups=Invoke-RestMethod "$BaseUrl/api/v1/duplicate-groups"
$family=$null
foreach($candidate in $groups){$members=Invoke-RestMethod "$BaseUrl/api/v1/duplicate-groups/$($candidate.id)";if($members.members.asset_id -contains $source.id){$family=$candidate;break}}
if(!$family){throw 'Duplicate family missing'}
Send "/v1/duplicate-groups/$($family.id)/canonical" @{assetId=$source.id;revision=$family.revision;reason='Prefer full-resolution PNG original'} | Out-Null
$report=@{project=$project.id;collection=$collection.id;source=$source.id;clones=$clones;model=$health;comparisons=$comparisons;diversity=$diversity;semanticResults=$semantic.Count;verifiedAt=(Get-Date).ToUniversalTime().ToString('o')}
New-Item -ItemType Directory -Force 'storage/data' | Out-Null
$report | ConvertTo-Json -Depth 30 | Set-Content 'storage/data/similarity-verification.json'
Write-Output "PASS: CPU CLIP, original pipeline, exact/recompressed/resized matches, semantic retrieval, human overrides, duplicate families, canonical selection, clustering. Collection: $($collection.id)"
