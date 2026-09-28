param([string]$BaseUrl='http://localhost:3000')
$ErrorActionPreference='Stop'
function Send-Stock($Path,$Body=@{},$Key,$Method='Post') {
 $headers=@{};if($Key){$headers['Idempotency-Key']=$Key}
 Invoke-RestMethod "$BaseUrl/api$Path" -Method $Method -ContentType 'application/json' -Headers $headers -Body ($Body|ConvertTo-Json -Depth 50)
}
function Get-Stock($Id){Invoke-RestMethod "$BaseUrl/api/v1/stock-productions/$Id"}
function Wait-Stock($Id,$Expected){
 $deadline=(Get-Date).AddMinutes(12);$last=''
 do{$s=Get-Stock $Id
  if($s.status-ne$last){Write-Host "Stock stage: $($s.status)";$last=$s.status}
  if($s.status-eq$Expected){return $s}
  if($s.status-match'FAILED|REJECTED|CANCELLED'){throw "Stock stopped: $($s.status) $($s.failure_code)"}
  if($s.status-eq'QA_PENDING' -and $s.qa.current_review_id){
   $review=Invoke-RestMethod "$BaseUrl/api/v1/reviews/$($s.qa.current_review_id)"
   if($review.execution_status-eq'COMPLETED' -and $review.final_decision-eq'NEEDS_REVIEW'){
    # Only the newly created, free mock fixture is approved by this test harness.
    if($review.vision_provider-ne'mock'){throw 'This smoke script approves only mock QA fixtures'}
    Send-Stock "/v1/reviews/$($review.id)/approve" @{revision=$review.revision;reasonCode='OTHER';reasonText='Explicit local mock fixture approval by TASK-08 test harness'} | Out-Null
   }
  }
  Start-Sleep -Seconds 2
 }while((Get-Date)-lt$deadline)
 throw "Timed out at $($s.status)"
}
function Wait-Export($Id){$deadline=(Get-Date).AddMinutes(5);do{$e=Invoke-RestMethod "$BaseUrl/api/v1/stock-exports/$Id";if($e.status-eq'READY'){return $e};if($e.status-eq'FAILED'){throw "Export failed: $($e.failure_code) $($e.validation|ConvertTo-Json -Depth 20 -Compress)"};Start-Sleep -Seconds 2}while((Get-Date)-lt$deadline);throw 'Export timeout'}
$providers=Invoke-RestMethod "$BaseUrl/api/v1/providers/image"
if($providers|Where-Object{$_.default-and$_.id-ne'mock'}){throw 'Smoke requires the free mock image provider'}
$stamp=[guid]::NewGuid().ToString('N')
$project=Send-Stock '/projects' @{name='Stock Factory verification';description='Free deterministic mock generation; real local processing and MinIO'}
$collection=Send-Stock '/v1/stock-collections' @{projectId=$project.id;title="Abstract Studies $($stamp.Substring(0,8))"}
$concept=Send-Stock '/concepts' @{collectionId=$collection.id;name='Abstract layered forms';prompt='Abstract layered geometric forms, balanced visual rhythm, no lettering or logos'}
$body=@{conceptId=$concept.id;profile='STOCK_GENERIC'}
$created=Send-Stock '/v1/stock-productions' $body "stock-smoke-$stamp"
$replay=Send-Stock '/v1/stock-productions' $body "stock-smoke-$stamp"
if($created.id-ne$replay.id){throw 'Production idempotency failed'}
$ready=Wait-Stock $created.id 'METADATA_REVIEW'
if(!$ready.technical[0].result.valid){throw 'Technical validation failed'}
if($ready.variant.format-ne'JPEG' -or ([long]$ready.variant.width*$ready.variant.height)-lt4000000){throw 'Invalid JPEG/megapixels'}
if(!$ready.metadataVersions[0].data.aiGenerated){throw 'AI disclosure missing'}
$outputRoot=Join-Path $PSScriptRoot '../storage/data/stock-verification'
New-Item -ItemType Directory -Force $outputRoot | Out-Null
$original=Join-Path $outputRoot 'original.png'
Invoke-WebRequest "$BaseUrl/api/assets/$($ready.source_asset_id)/content" -OutFile $original
$sourceHash=(Get-FileHash -LiteralPath $original).Hash
$stockImage=Join-Path $outputRoot 'stock-master.jpg'
Invoke-WebRequest "$BaseUrl/api/v1/variants/$($ready.variant.id)/content" -OutFile $stockImage
if((Get-FileHash -LiteralPath $stockImage).Hash.ToLowerInvariant()-ne$ready.variant.sha256){throw 'Stock checksum mismatch'}
Send-Stock "/v1/stock-productions/$($created.id)/approve" @{revision=$ready.revision;acknowledgeWarnings=$true} | Out-Null
$exportBody=@{profile='GENERIC_CSV';stockProductionIds=@($created.id);policy='STRICT';incremental=$false}
$queued=Send-Stock '/v1/stock-exports' $exportBody "stock-export-$stamp"
$export=Wait-Export $queued.id
$zipFile=Join-Path $outputRoot 'export-v1.zip'
Invoke-WebRequest "$BaseUrl/api/v1/stock-exports/$($export.id)/content" -OutFile $zipFile
$zipHash=(Get-FileHash -LiteralPath $zipFile).Hash
if($zipHash.ToLowerInvariant()-ne$export.sha256){throw 'ZIP checksum mismatch'}
$zip=[IO.Compression.ZipFile]::OpenRead($zipFile)
try{
 $entries=@($zip.Entries.FullName)
 foreach($expected in @('metadata.csv','manifest.json','validation-report.json')){if($entries-notcontains$expected){throw "Missing $expected"}}
 $reader=[IO.StreamReader]::new($zip.GetEntry('metadata.csv').Open(),[Text.Encoding]::UTF8)
 try{$csv=$reader.ReadToEnd()}finally{$reader.Dispose()}
 $rows=@($csv|ConvertFrom-Csv)
 if($rows.Count-ne1-or$entries-notcontains("images/"+$rows[0].filename)){throw 'CSV filename mapping failed'}
}finally{$zip.Dispose()}
$current=Get-Stock $created.id
$edit=$current.metadataVersions[0].data
$edit.title='Abstract, "layered" forms – color study'
Send-Stock "/v1/stock-productions/$($created.id)/metadata" @{revision=$current.revision;data=$edit} $null 'Put' | Out-Null
$current=Get-Stock $created.id
Send-Stock "/v1/stock-productions/$($created.id)/metadata/regenerate" @{revision=$current.revision;scope='KEYWORDS'} | Out-Null
$current=Wait-Stock $created.id 'METADATA_REVIEW'
if($current.metadataVersions[0].data.title-ne$edit.title){throw 'Keyword regeneration overwrote edited title'}
Send-Stock "/v1/stock-productions/$($created.id)/approve" @{revision=$current.revision;acknowledgeWarnings=$true} | Out-Null
$second=Send-Stock "/v1/stock-exports/$($export.id)/rebuild" @{} "stock-export-v2-$stamp"
$v2=Wait-Export $second.id
Invoke-WebRequest "$BaseUrl/api/v1/stock-exports/$($v2.id)/content" -OutFile (Join-Path $outputRoot 'export-v2.zip')
Invoke-WebRequest "$BaseUrl/api/v1/stock-exports/$($export.id)/content" -OutFile (Join-Path $outputRoot 'export-v1-recheck.zip')
if((Get-FileHash -LiteralPath (Join-Path $outputRoot 'export-v1-recheck.zip')).Hash-ne$zipHash){throw 'Earlier export changed'}
Invoke-WebRequest "$BaseUrl/api/assets/$($ready.source_asset_id)/content" -OutFile (Join-Path $outputRoot 'original-recheck.png')
if((Get-FileHash -LiteralPath (Join-Path $outputRoot 'original-recheck.png')).Hash-ne$sourceHash){throw 'Original changed'}
$final=Get-Stock $created.id
$progress=Invoke-RestMethod "$BaseUrl/api/v1/stock-collections/$($collection.id)/production-status"
@{production=$final;exportV1=$export;exportV2=$v2;csvRows=$rows;collection=$progress;verifiedAt=(Get-Date).ToUniversalTime().ToString('o');sourceUnchanged=$true;previousExportUnchanged=$true}|ConvertTo-Json -Depth 80|Set-Content (Join-Path $outputRoot 'report.json') -Encoding utf8
Write-Output "PASS: stock production $($created.id), JPEG $($ready.variant.width)x$($ready.variant.height), metadata history, CSV, checksums and immutable export versions."
