param([string]$BaseUrl='http://localhost:3000',[string]$SourceAssetId='cb63ee47-6edb-4d72-88a5-07af4d2154eb')
$ErrorActionPreference='Stop'
function Send-Video($Path,$Body,$Key){Invoke-RestMethod "$BaseUrl/api/v1/video$Path" -Method Post -ContentType 'application/json' -Headers @{'Idempotency-Key'=$Key} -Body ($Body|ConvertTo-Json -Depth 30)}
function Get-Video($Id){Invoke-RestMethod "$BaseUrl/api/v1/video/productions/$Id"}
function Wait-Video($Id){$deadline=(Get-Date).AddMinutes(15);$last='';do{$v=Get-Video $Id;if($v.status-ne$last){Write-Host "Video stage: $($v.status)";$last=$v.status};if($v.status-eq'REVIEW'){return $v};if($v.status-match'FAILED|REJECTED|CANCELLED|UNKNOWN'){throw "Video stopped: $($v.status) $($v.failure_code)"};Start-Sleep -Seconds 2}while((Get-Date)-lt$deadline);throw 'Video pipeline timeout'}
$stamp=[guid]::NewGuid().ToString('N')
$body=@{sourceAssetId=$SourceAssetId;profile='WALLPAPER_LOOP';provider='mock-video';allowFallback=$false;motion=@{subjectMotion='Subtle ambient motion preserving the original abstract forms';cameraMotion='STATIC';reversible=$true};budget=0;maxAttempts=3}
$v=Send-Video '/productions' $body "video-smoke-$stamp"
$again=Send-Video '/productions' $body "video-smoke-$stamp"
if($v.id-ne$again.id){throw 'Video idempotency failed'}
$v=Wait-Video $v.id
if($v.attempts.Count-ne1 -or $v.attempts[0].provider-ne'mock-video' -or !$v.attempts[0].provider_job_id){throw 'Unexpected provider history'}
$output=Join-Path $PSScriptRoot '../storage/data/video-verification'
New-Item -ItemType Directory -Force $output | Out-Null
Invoke-WebRequest "$BaseUrl/api/assets/$($v.raw_asset_id)/content" -OutFile (Join-Path $output 'raw.mp4')
$rawHash=(Get-FileHash (Join-Path $output 'raw.mp4')).Hash
$firstMaster=$v.master_variant_id
$expected=@('ANDROID_VIDEO_FHD','ANDROID_VIDEO_GENERIC','SOCIAL_VERTICAL','SOCIAL_HORIZONTAL','SOCIAL_SQUARE','VIDEO_PREVIEW','VIDEO_POSTER','VIDEO_THUMBNAIL','LOOP_PREVIEW','MASTER_VIDEO','PROCESSED_VIDEO')
foreach($kind in $expected){$variant=$v.variants|Where-Object{$_.kind-eq$kind -and $_.video_processing_run_id-eq$v.current_run_id};if(!$variant){throw "Missing $kind"};$ext=if($variant.format-eq'JPEG'){'jpg'}else{'mp4'};$path=Join-Path $output "$kind.$ext";Invoke-WebRequest "$BaseUrl/api/v1/video/variants/$($variant.id)/content" -OutFile $path;if((Get-FileHash $path).Hash.ToLowerInvariant()-ne$variant.sha256){throw "Checksum mismatch $kind"}}
$firstHash=(Get-FileHash (Join-Path $output 'MASTER_VIDEO.mp4')).Hash
# Approval applies only to this explicitly-created free mock verification fixture.
Send-Video "/productions/$($v.id)/approve" @{revision=$v.revision;acknowledgeWarnings=$true;reason='TASK-09 free mock integration fixture: validated files and temporal evidence'} "approve-$stamp" | Out-Null
$v=Get-Video $v.id
Send-Video "/productions/$($v.id)/reprocess" @{revision=$v.revision;settings=@{quality=21;loopStrategy='CROSSFADE'};variants=@{VIDEO_PREVIEW=@{width=360;height=640;fps=24}}} "reprocess-$stamp" | Out-Null
$v=Wait-Video $v.id
if($v.master_variant_id-eq$firstMaster -or $v.runs.Count-ne2 -or $v.attempts.Count-ne1){throw 'Reprocess lineage failed'}
Invoke-WebRequest "$BaseUrl/api/assets/$($v.raw_asset_id)/content" -OutFile (Join-Path $output 'raw-after.mp4')
Invoke-WebRequest "$BaseUrl/api/v1/video/variants/$firstMaster/content" -OutFile (Join-Path $output 'master-v1-after.mp4')
if((Get-FileHash (Join-Path $output 'raw-after.mp4')).Hash-ne$rawHash -or (Get-FileHash (Join-Path $output 'master-v1-after.mp4')).Hash-ne$firstHash){throw 'Original media changed'}
if(($v.costs|Where-Object{[decimal]$_.total-ne0}).Count){throw 'Mock pipeline incurred nonzero cost'}
$range=Invoke-WebRequest "$BaseUrl/api/v1/video/variants/$($v.master_variant_id)/content" -Headers @{Range='bytes=0-99'}
if($range.StatusCode-ne206){throw "Range playback unsupported: $($range.StatusCode)"}
Send-Video "/productions/$($v.id)/approve" @{revision=$v.revision;acknowledgeWarnings=$true;reason='TASK-09 free mock fixture: second immutable master and range playback verified'} "approve-v2-$stamp" | Out-Null
$v=Get-Video $v.id
if($v.status-ne'READY'){throw 'Final approval failed'}
$v|ConvertTo-Json -Depth 70|Set-Content (Join-Path $output 'report.json')
Invoke-RestMethod 'http://localhost:8003/health'|ConvertTo-Json -Depth 8|Set-Content (Join-Path $output 'worker-health.json')
Write-Host "PASS: $($v.id), 11 delivery artifacts, original checksums preserved, two processing versions, one provider attempt, zero cost, HTTP range 206"
