param([string]$BaseUrl='http://localhost:3000')
$ErrorActionPreference='Stop'
function Send($Path,$Body=@{},$Key) {
  $headers=@{}; if($Key){$headers['Idempotency-Key']=$Key}
  Invoke-RestMethod "$BaseUrl/api$Path" -Method Post -ContentType 'application/json' -Headers $headers -Body ($Body|ConvertTo-Json -Depth 40)
}
function Get-Wallpaper($Id) { Invoke-RestMethod "$BaseUrl/api/v1/wallpaper-productions/$Id" }
function Wait-Wallpaper($Id,$Expected) {
  $deadline=(Get-Date).AddMinutes(15)
  do {
    $item=Get-Wallpaper $Id
    if($item.status -eq $Expected){return $item}
    if($item.status -match 'FAILED|REJECTED|PAUSED|CANCELLED'){throw "Wallpaper $Id stopped: $($item.status) $($item.failure_code)"}
    Start-Sleep -Seconds 2
  } while((Get-Date)-lt $deadline)
  throw "Wallpaper $Id timed out at $($item.status)"
}
$providers=Invoke-RestMethod "$BaseUrl/api/v1/providers/image"
if($providers|Where-Object {$_.default -and $_.id -ne 'mock'}){throw 'Smoke requires the free mock image provider'}
$stamp=[guid]::NewGuid().ToString('N').Substring(0,8)
$project=Send '/projects' @{name='Wallpaper Factory verification';description='TASK-07 mock publication and local GPU processing'}
$outputRoot=Join-Path $PSScriptRoot '../storage/data/wallpaper-verification'
New-Item -ItemType Directory -Force $outputRoot | Out-Null
$reports=@()
foreach($amoled in @($false,$true)) {
  $kind=if($amoled){'AMOLED'}else{'Standard'}
  $collection=Send '/v1/wallpaper-collections' @{projectId=$project.id;title="$kind / Nocturne";slug="nocturne-$($kind.ToLowerInvariant())-$stamp";theme='Quiet celestial worlds';style='Minimal cinematic';description='Local pipeline verification';amoled=$amoled}
  $concept=Send '/concepts' @{collectionId=$collection.id;name="$kind celestial study";prompt='A luminous celestial body suspended above an expansive quiet landscape, clearly separated focal detail'}
  $profile=if($amoled){'ANDROID_AMOLED'}else{'ANDROID_STANDARD'}
  $body=@{conceptId=$concept.id;profile=$profile;metadata=@{title="$kind / Celestial study";slug="celestial-$($kind.ToLowerInvariant())-$stamp";description='Free local verification fixture';tags=@('space','abstract');premium=$false;featured=$false;category='Space'}}
  $key="wallpaper-smoke-$kind-$stamp"
  $created=Send '/v1/wallpaper-productions' $body $key
  $replay=Send '/v1/wallpaper-productions' $body $key
  if($created.id -ne $replay.id){throw 'Production idempotency failed'}
  Write-Output "$kind production queued: $($created.id)"
  $ready=Wait-Wallpaper $created.id 'PUBLICATION_REVIEW'
  if($ready.variants.Count -ne 6){throw 'Expected master plus five device/preview variants'}
  if($amoled -and $ready.amoled_result.classification -ne 'AMOLED_SUITABLE'){throw 'AMOLED fixture did not qualify'}
  $source=Join-Path $outputRoot "$kind-original.png"
  Invoke-WebRequest "$BaseUrl/api/assets/$($ready.master_asset_id)/content" -OutFile $source
  $before=(Get-FileHash -LiteralPath $source).Hash
  foreach($variant in $ready.variants){
    $file=Join-Path $outputRoot "$kind-$($variant.kind).$($variant.format.ToLowerInvariant())"
    Invoke-WebRequest "$BaseUrl/api/v1/variants/$($variant.id)/content" -OutFile $file
    if((Get-FileHash -LiteralPath $file).Hash.ToLowerInvariant() -ne $variant.sha256){throw 'Variant checksum mismatch'}
  }
  $dry=Send "/v1/wallpapers/$($created.id)/publication-dry-run" @{target='MOCK'}
  if(!$dry.eligibility.eligible -or $dry.remoteMutation){throw 'Dry-run failed'}
  $package=Send "/v1/wallpapers/$($created.id)/prepare-publication"
  $approval=Send "/v1/wallpapers/$($created.id)/approve-publication" @{packageId=$package.id;revision=(Get-Wallpaper $created.id).revision}
  $delivery=Send "/v1/wallpapers/$($created.id)/publish" @{target='MOCK'}
  $published=Wait-Wallpaper $created.id 'PUBLISHED'
  $again=Send "/v1/wallpapers/$($created.id)/publish" @{target='MOCK'}
  if($delivery.id -ne $again.id){throw 'Publication idempotency failed'}
  $export=Send "/v1/wallpapers/$($created.id)/export"
  $deadline=(Get-Date).AddMinutes(3)
  do {$all=Invoke-RestMethod "$BaseUrl/api/v1/wallpaper-exports";$zip=$all|Where-Object id -eq $export.id;if($zip.status -in @('COMPLETED','FAILED')){break};Start-Sleep -Seconds 1}while((Get-Date)-lt $deadline)
  if($zip.status -ne 'COMPLETED'){throw 'Export failed'}
  $zipFile=Join-Path $outputRoot "$kind-package.zip"
  Invoke-WebRequest "$BaseUrl/api/v1/wallpaper-exports/$($zip.id)/content" -OutFile $zipFile
  if((Get-FileHash -LiteralPath $zipFile).Hash.ToLowerInvariant() -ne $zip.sha256){throw 'Export checksum mismatch'}
  $unpublish=Send "/v1/wallpapers/$($created.id)/unpublish" @{target='MOCK'}
  $unpublished=Wait-Wallpaper $created.id 'UNPUBLISHED'
  $v2=Send "/v1/wallpapers/$($created.id)/prepare-publication"
  if($v2.version -ne 2){throw 'Republish must create version 2'}
  $approval=Send "/v1/wallpapers/$($created.id)/approve-publication" @{packageId=$v2.id;revision=$unpublished.revision}
  $delivery2=Send "/v1/wallpapers/$($created.id)/publish" @{target='MOCK'}
  $final=Wait-Wallpaper $created.id 'PUBLISHED'
  Invoke-WebRequest "$BaseUrl/api/assets/$($ready.master_asset_id)/content" -OutFile $source
  if((Get-FileHash -LiteralPath $source).Hash -ne $before){throw 'Original modified'}
  $reports+=@{kind=$kind;collectionId=$collection.id;production=$final;dryRun=$dry;originalChecksum=$before;export=$zip}
  $reports|ConvertTo-Json -Depth 70|Set-Content (Join-Path $outputRoot 'report.json')
  Write-Output "$kind passed: six variants, mock publication v2, original and export checksums verified"
}
