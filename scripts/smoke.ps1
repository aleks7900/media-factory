param([string]$BaseUrl='http://localhost:3000')
$ErrorActionPreference='Stop'
function Post($Path,$Body,$Key) {
  $headers=@{}
  if($Key) { $headers['Idempotency-Key']=$Key }
  Invoke-RestMethod "$BaseUrl/api$Path" -Method Post -ContentType 'application/json' -Headers $headers -Body ($Body | ConvertTo-Json)
}
$health=Invoke-RestMethod "$BaseUrl/actuator/health"
if($health.status -ne 'UP') { throw 'Backend unhealthy' }
$project=Post '/projects' @{name='Studio launch';description='End-to-end mock pipeline verification'}
$collection=Post '/collections' @{projectId=$project.id;name='Chromatic studies'}
$concept=Post '/concepts' @{collectionId=$collection.id;name='Sculpted light';prompt='Abstract sculptural forms in soft studio light'}
$key=[guid]::NewGuid().ToString()
$body=@{conceptId=$concept.id;prompt=$concept.prompt;width=768;height=768}
$generation=Post '/generations' $body $key
$replay=Post '/generations' $body $key
if($replay.id -ne $generation.id) { throw 'Idempotency failure' }
$deadline=(Get-Date).AddSeconds(60)
do {
  Start-Sleep -Seconds 1
  $result=Invoke-RestMethod "$BaseUrl/api/generations/$($generation.id)"
} while($result.status -notin @('QA_PENDING','FAILED','REJECTED') -and (Get-Date) -lt $deadline)
if($result.status -ne 'QA_PENDING') { throw "Generation did not pass QA: $($result.status)" }
$assets=Invoke-RestMethod "$BaseUrl/api/assets"
$asset=$assets | Where-Object generation_id -eq $generation.id
$media=Invoke-WebRequest "$BaseUrl/api/assets/$($asset.id)/content"
if($media.StatusCode -ne 200) { throw 'Media download failed' }
$review=Post '/reviews' @{assetId=$asset.id;decision='APPROVED';reason='Smoke test approved'}
$regenerated=Post "/assets/$($asset.id)/regenerate" @{} ([guid]::NewGuid().ToString())
if($regenerated.parent_id -ne $generation.id) { throw 'Regeneration lineage failure' }
$ledger=Invoke-RestMethod "$BaseUrl/api/costs"
$cost=$ledger | Where-Object generation_id -eq $generation.id
if(!$cost -or [decimal]$cost.estimated_cost -ne 0) { throw 'Mock cost ledger failure' }
Write-Output "PASS: health, project hierarchy, generation, idempotency, S3 media, QA, approval, regeneration, and cost ledger."
Write-Output "Generation: $($generation.id); asset: $($asset.id)"
