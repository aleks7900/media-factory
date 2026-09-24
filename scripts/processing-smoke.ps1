param([string]$BaseUrl='http://localhost:3000')
$ErrorActionPreference='Stop'
function Send($Path,$Body,$Key) {
 $headers=@{};if($Key){$headers['Idempotency-Key']=$Key}
 Invoke-RestMethod "$BaseUrl/api$Path" -Method Post -ContentType 'application/json' -Headers $headers -Body ($Body|ConvertTo-Json -Depth 30)
}
$providers=Invoke-RestMethod "$BaseUrl/api/v1/providers/image"
if(($providers|Where-Object {$_.default -and $_.id -ne 'mock'})){throw 'Smoke requires the free mock image provider'}
$project=Send '/projects' @{name='Media Factory · processing studio';description='Free local post-production verification'}
$collection=Send '/collections' @{projectId=$project.id;name='Prism / master collection'}
$concept=Send '/concepts' @{collectionId=$collection.id;name='Prismatic light';prompt='A luminous abstract sculpture in a violet atmospheric gradient'}
$generation=Send '/generations' @{conceptId=$concept.id;prompt=$concept.prompt;width=1024;height=1024} ([guid]::NewGuid().ToString())
$deadline=(Get-Date).AddMinutes(10)
do {
 $assets=Invoke-RestMethod "$BaseUrl/api/assets"
 $asset=$assets|Where-Object generation_id -eq $generation.id|Select-Object -First 1
 if($asset.current_review_id){
  $review=Invoke-RestMethod "$BaseUrl/api/v1/reviews/$($asset.current_review_id)"
  if($review.execution_status -eq 'COMPLETED'){break}
 }
 Start-Sleep -Milliseconds 1000
}while((Get-Date)-lt $deadline)
if(!$asset -or $review.execution_status -ne 'COMPLETED'){throw 'Master QA did not complete'}
if($review.final_decision -ne 'APPROVED'){
 $review=Send "/v1/reviews/$($review.id)/approve" @{revision=$review.revision;reason='Approve newly generated mock fixture for TASK-06 technical verification'}
}
$outputRoot=Join-Path $PSScriptRoot '../storage/data/processing-verification'
New-Item -ItemType Directory -Force $outputRoot | Out-Null
$source=Join-Path $outputRoot 'original.png'
Invoke-WebRequest "$BaseUrl/api/assets/$($asset.id)/content" -OutFile $source
$before=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
$body=@{profiles=@('STOCK_STANDARD','WALLPAPER_ANDROID','WALLPAPER_PHONE','SOCIAL_SQUARE','PREVIEW','THUMBNAIL')}
$run=Send "/v1/assets/$($asset.id)/process" $body
$replay=Send "/v1/assets/$($asset.id)/process" $body
if($run.processingRunId -ne $replay.processingRunId){throw 'Idempotency failed'}
$deadline=(Get-Date).AddMinutes(20)
do {
 $detail=Invoke-RestMethod "$BaseUrl/api/v1/processing-runs/$($run.processingRunId)"
 if($detail.status -in @('COMPLETED','FAILED','PARTIALLY_COMPLETED','CANCELLED')){break}
 Start-Sleep -Milliseconds 1000
}while((Get-Date)-lt $deadline)
if($detail.status -ne 'COMPLETED'){$detail|ConvertTo-Json -Depth 40|Set-Content (Join-Path $outputRoot 'failed-run.json');throw "Processing failed: $($detail.status) $($detail.failure_code)"}
$variants=Invoke-RestMethod "$BaseUrl/api/v1/assets/$($asset.id)/variants"
if($variants.Count -ne 6){throw 'Expected six final variants'}
foreach($variant in $variants){
 $file=Join-Path $outputRoot "$($variant.kind).$($variant.format.ToLowerInvariant())"
 Invoke-WebRequest "$BaseUrl/api/v1/variants/$($variant.id)/content" -OutFile $file
 if((Get-FileHash -LiteralPath $file).Hash.ToLowerInvariant() -ne $variant.sha256){throw 'Variant checksum mismatch'}
 if($variant.validation_status -ne 'VALID'){throw 'Variant failed validation'}
}
Invoke-WebRequest "$BaseUrl/api/assets/$($asset.id)/content" -OutFile (Join-Path $outputRoot 'original-after.png')
if((Get-FileHash -LiteralPath (Join-Path $outputRoot 'original-after.png')).Hash -ne $before){throw 'Original changed'}
$worker=Invoke-RestMethod "$BaseUrl/api/v1/processing-worker"
$result=@{assetId=$asset.id;runId=$run.processingRunId;status=$detail.status;originalChecksum=$before;worker=$worker;variants=$variants;run=$detail}
$result|ConvertTo-Json -Depth 60|Set-Content (Join-Path $outputRoot 'report.json')
$variants|Select-Object kind,width,height,format,megapixels,validation_status|Format-Table
Write-Output "Processing verification passed: $($run.processingRunId)"
