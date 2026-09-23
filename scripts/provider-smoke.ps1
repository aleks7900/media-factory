param([string]$BaseUrl='http://localhost:3000')
$ErrorActionPreference='Stop'
function Post($Path,$Body,$Key) {
  $headers=@{}
  if($Key) { $headers['Idempotency-Key']=$Key }
  Invoke-RestMethod "$BaseUrl/api$Path" -Method Post -ContentType 'application/json' -Headers $headers -Body ($Body | ConvertTo-Json)
}
$providers=Invoke-RestMethod "$BaseUrl/api/v1/providers/image"
if(!($providers | Where-Object id -eq 'mock')) { throw 'Mock provider missing' }
$project=Post '/projects' @{name='Provider verification';description='Free mock-only smoke test'}
$collection=Post '/collections' @{projectId=$project.id;name='Routing checks'}
$concept=Post '/concepts' @{collectionId=$collection.id;name='Provider smoke';prompt='Colorful studio sculpture'}
$key=[guid]::NewGuid().ToString()
$body=@{conceptId=$concept.id;prompt=$concept.prompt;provider='mock';aspectRatio='SQUARE';format='JPEG';quality='AUTO'}
$created=Post '/v1/generations/images' $body $key
$replay=Post '/v1/generations/images' $body $key
if($created.generationId -ne $replay.generationId) { throw 'Replay created another generation' }
$deadline=(Get-Date).AddSeconds(60)
do {
  Start-Sleep -Seconds 1
  $detail=Invoke-RestMethod "$BaseUrl/api/v1/generations/$($created.generationId)"
} while($detail.status -notin @('QA_PENDING','FAILED','REJECTED') -and (Get-Date) -lt $deadline)
if($detail.status -ne 'QA_PENDING') { throw "Unexpected status: $($detail.status)" }
if($detail.attempts.Count -ne 1 -or $detail.attempts[0].status -ne 'SUCCEEDED') { throw 'Attempt ledger mismatch' }
if($detail.final_provider -ne 'mock' -or $detail.costs[0].estimated_total -ne 0) { throw 'Provider or cost mismatch' }
$asset=$detail.assets[0]
if($asset.sha256.Length -ne 64) { throw 'Checksum missing' }
$media=Invoke-WebRequest "$BaseUrl/api/assets/$($asset.id)/content"
if($media.Headers['Content-Type'] -notlike 'image/jpeg*') { throw 'Requested JPEG format was not preserved' }
$metrics=Invoke-WebRequest 'http://localhost:8080/actuator/prometheus'
if($metrics.Content -notmatch 'media_factory_') { throw 'Provider metrics absent' }
Write-Output "PASS: provider discovery, explicit mock routing, v1 asynchronous API, idempotency, attempt ledger, cost, JPEG storage, SHA-256, QA and Prometheus."
Write-Output "Generation: $($created.generationId)"
