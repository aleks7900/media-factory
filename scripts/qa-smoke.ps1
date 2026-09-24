param([string]$BaseUrl='http://localhost:3000')
$ErrorActionPreference='Stop'
function Send($Path,$Body,$Key) {
 $headers=@{};if($Key){$headers['Idempotency-Key']=$Key}
 Invoke-RestMethod "$BaseUrl/api$Path" -Method Post -ContentType 'application/json' -Headers $headers -Body ($Body|ConvertTo-Json -Depth 20)
}
function Wait-Review($Id) {
 $deadline=(Get-Date).AddSeconds(90)
 do {Start-Sleep -Milliseconds 300;$r=Invoke-RestMethod "$BaseUrl/api/v1/reviews/$Id"}while($r.execution_status -in @('PENDING','RUNNING') -and (Get-Date) -lt $deadline)
 if($r.execution_status -in @('PENDING','RUNNING')){throw 'QA deadline exceeded'}
 $r
}
function Expect-Conflict($Path,$Body) {
 try {Send $Path $Body | Out-Null;throw 'Expected HTTP 409'}catch{if([int]$_.Exception.Response.StatusCode -ne 409){throw}}
}
$provider=Invoke-RestMethod "$BaseUrl/api/v1/providers/image"
if(($provider|Where-Object { $_.default -and $_.id -ne 'mock' })){throw 'This smoke test requires default mock image generation'}
$project=Send '/projects' @{name='Visual QA verification';description='Free TASK-04 smoke checks'}
$collection=Send '/collections' @{projectId=$project.id;name='Nocturne · QA laboratory'}
$concept=Send '/concepts' @{collectionId=$collection.id;name='Evening atmosphere';prompt='Abstract luminous forms with an atmospheric violet gradient, no watermarks'}
$generation=Send '/generations' @{conceptId=$concept.id;prompt=$concept.prompt;width=768;height=768} ([guid]::NewGuid().ToString())
$deadline=(Get-Date).AddSeconds(90)
do {Start-Sleep -Milliseconds 300;$detail=Invoke-RestMethod "$BaseUrl/api/v1/generations/$($generation.id)"}while(!$detail.assets.Count -and (Get-Date) -lt $deadline)
if(!$detail.assets.Count){throw 'Image generation did not produce an asset'}
$asset=$detail.assets[0]
$review=Wait-Review $asset.current_review_id
if($review.vision_provider -ne 'mock'){throw 'This smoke script requires mock Vision'}
if($review.final_decision -ne 'APPROVED'){throw "Expected default mock approval, got $($review.final_decision)"}
if($review.dimensions.Count -ne 9){throw 'Missing quality dimensions'}
if($review.context_snapshot.promptSnapshot.canonical_positive_prompt -ne $concept.prompt){throw 'Frozen prompt context mismatch'}
$initialReview=$review.id
# Explicit re-run: new history, same immutable original.
$new=Send "/v1/reviews/$($review.id)/rerun" @{revision=$review.revision;mockScenario='WATERMARK'}
$review=Wait-Review $new.id
if($review.final_decision -ne 'REJECTED'){throw 'Watermark scenario did not reject'}
Expect-Conflict '/v1/publications' @{assetId=$asset.id;channel='smoke'}
$review=Send "/v1/reviews/$($review.id)/approve" @{revision=$review.revision;reasonCode='AI_FALSE_POSITIVE';reasonText='Mock fixture verification'}
if($review.automatic_decision -ne 'REJECTED' -or $review.final_decision -ne 'APPROVED' -or !$review.human_override){throw 'Override corrupted original AI decision'}
$new=Send "/v1/reviews/$($review.id)/rerun" @{revision=$review.revision;mockScenario='UNCERTAIN'}
$review=Wait-Review $new.id
if($review.final_decision -ne 'NEEDS_REVIEW'){throw 'Uncertain output was not escalated'}
$review=Send "/v1/reviews/$($review.id)/approve" @{revision=$review.revision;reasonText='Human confirms visible evidence'}
$key=[guid]::NewGuid().ToString()
$regenerate=@{revision=$review.revision;mode='SAME_PROMPT';feedback='Preserve the original prompt'}
$child=Send "/v1/reviews/$($review.id)/regenerate" $regenerate $key
$replay=Send "/v1/reviews/$($review.id)/regenerate" $regenerate $key
if($child.parent_id -ne $generation.id -or $replay.id -ne $child.id){throw 'Regeneration lineage or replay failed'}
$review=Invoke-RestMethod "$BaseUrl/api/v1/reviews/$($review.id)"
$new=Send "/v1/reviews/$($review.id)/rerun" @{revision=$review.revision;mockScenario='INVALID_RESPONSE'}
$review=Wait-Review $new.id
if($review.execution_status -ne 'FAILED' -or $review.final_decision -ne 'NEEDS_REVIEW' -or $review.automatic_decision){throw 'Invalid response was confused with a content decision'}
$new=Send "/v1/reviews/$($review.id)/rerun" @{revision=$review.revision;mockScenario='PERFECT'}
$review=Wait-Review $new.id
$review=Send "/v1/reviews/$($review.id)/reject" @{revision=$review.revision;reasonCode='AI_FALSE_NEGATIVE';reasonText='Human rejection fixture'}
if($review.automatic_decision -ne 'APPROVED' -or $review.final_decision -ne 'REJECTED'){throw 'Human rejection lost automatic approval'}
$batch=Send '/v1/reviews/batch' @{decision='APPROVED';items=@(@{id=$initialReview;revision=0;reasonCode='MANUAL_QUALITY_JUDGMENT';reasonText='Stale historical review'},@{id=$review.id;revision=$review.revision;reasonCode='MANUAL_QUALITY_JUDGMENT';reasonText='Human confirmation'})}
if(@($batch.results|Where-Object success).Count -ne 1 -or @($batch.results|Where-Object {-not $_.success}).Count -ne 1){throw 'Partial batch results incorrect'}
$publication=Send '/v1/publications' @{assetId=$asset.id;channel='local-smoke';externalId='record-only'}
$queue=Invoke-RestMethod "$BaseUrl/api/v1/reviews/queue?collection=$($collection.id)&size=24"
if($queue.total -lt 1){throw 'Paginated collection filter failed'}
$dashboard=Invoke-RestMethod "$BaseUrl/api/v1/qa/dashboard"
$metrics=Invoke-WebRequest 'http://localhost:8080/actuator/prometheus'
if($metrics.Content -notmatch 'media_factory_qa_' -or $metrics.Content -notmatch 'media_factory_vision_requests'){throw 'QA metrics missing'}
Write-Output 'PASS: automatic approval/rejection, exact prompt context, uncertainty, both override directions, immutable history, regeneration replay/lineage, invalid Vision response, partial batch conflict, publication gate, paginated queue and QA metrics.'
Write-Output "Collection: $($collection.id); original asset: $($asset.id); child generation: $($child.id); publication: $($publication.id)"
