param([string]$BaseUrl='http://localhost:3000')
$ErrorActionPreference='Stop'
function Send($Path,$Body,$Key,$Method='POST') {
 $headers=@{};if($Key){$headers['Idempotency-Key']=$Key}
 Invoke-RestMethod "$BaseUrl/api$Path" -Method $Method -Headers $headers -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 20)
}
$suffix=[guid]::NewGuid().ToString().Substring(0,8)
$project=Send '/projects' @{name="Prompt verification $suffix";description='Free mock verification'}
$collection=Send '/collections' @{projectId=$project.id;name='Prompt engine studies'}
$concept=Send '/concepts' @{collectionId=$collection.id;name='Cybernetic wolf';prompt='Legacy concept direction'}
$template=Send '/v1/prompt-templates' @{key="wolf-$suffix";name='Premium Animal Wallpaper';category='WALLPAPER';description='Versioned studio direction'}
$variables=@(@{name='subject';label='Subject';type='STRING';required=$true;minLength=1;maxLength=100},@{name='lighting';label='Lighting';type='ENUM';required=$true;defaultValue='NEON';allowedValues=@('NEON','SOFT')})
$draft=@{positiveTemplate='Create a premium cinematic portrait of {{subject}}. Lighting: {{lighting}}.';negativeTemplate='blurry {{subject}}, watermark, unwanted text';variables=$variables;changeDescription='Initial version'}
$version=Send "/v1/prompt-templates/$($template.id)/versions" $draft
$published=Send "/v1/prompt-versions/$($version.id)/publish" @{revision=$version.revision}
$preset=Send '/v1/prompt-presets' @{key="AMOLED_$suffix";name='AMOLED';category='STYLE';positiveFragment='deep OLED black background, high subject separation';negativeFragment='gray background, washed blacks'}
$render=@{promptVersionId=$version.id;variables=@{subject='cybernetic wolf'};presets=@($preset.key);provider='mock';pipeline='wallpaper'}
$preview=Send '/v1/prompts/render' $render
if($preview.canonical.negativePrompt -notmatch 'watermark' -or $preview.providerAdaptation.positivePrompt -notmatch 'Negative constraints') {throw 'Prompt adaptation failed'}
$render.provider='openai';$openai=Send '/v1/prompts/render' $render
if($openai.providerAdaptation.positivePrompt -notmatch 'Avoid the following unwanted elements') {throw 'OpenAI preview failed'}
$body=@{conceptId=$concept.id;promptVersionId=$version.id;variables=@{subject='cybernetic wolf'};presets=@($preset.key);provider='mock';pipeline='wallpaper'}
$key=[guid]::NewGuid().ToString();$generation=Send '/v1/generations/images' $body $key
$deadline=(Get-Date).AddSeconds(60)
do {Start-Sleep -Seconds 1;$detail=Invoke-RestMethod "$BaseUrl/api/v1/generations/$($generation.generationId)"} while($detail.status -notin @('QA_PENDING','FAILED','REJECTED') -and (Get-Date) -lt $deadline)
if($detail.status -ne 'QA_PENDING'){throw "Generation failed: $($detail.status)"}
$snapshot=$detail.promptSnapshots[0]
if($snapshot.prompt_version_id -ne $version.id -or $snapshot.variables.lighting -ne 'NEON' -or $detail.attempts[0].prompt_snapshot_id -ne $snapshot.id){throw 'Snapshot attribution failed'}
$frozen=$snapshot | ConvertTo-Json -Depth 20 -Compress
$v2=Send "/v1/prompt-templates/$($template.id)/versions" @{copyFromVersionId=$version.id;changeDescription='Stronger lighting'}
$draft.positiveTemplate='Create an elegant portrait of {{subject}}. Cinematic rim lighting: {{lighting}}.';$draft.revision=0
$v2=Send "/v1/prompt-versions/$($v2.id)" $draft $null 'PATCH'
$v2=Send "/v1/prompt-versions/$($v2.id)/publish" @{revision=$v2.revision}
$protected=$false
try {Send "/v1/prompt-versions/$($version.id)" $draft $null 'PATCH' | Out-Null} catch {if([int]$_.Exception.Response.StatusCode -in @(400,409)){$protected=$true}else{throw}}
if(!$protected){throw 'Published version was editable'}
$after=Invoke-RestMethod "$BaseUrl/api/v1/generations/$($generation.generationId)"
if(($after.promptSnapshots[0] | ConvertTo-Json -Depth 20 -Compress) -ne $frozen){throw 'Historical prompt changed'}
$experiment=Send '/v1/prompt-experiments' @{name="Wolf lighting $suffix";scope='PROMPT_TEMPLATE';promptTemplateId=$template.id;variants=@(@{key='A';name='Initial';promptVersionId=$version.id;weight=5000},@{key='B';name='Rim lighting';promptVersionId=$v2.id;weight=5000})}
$experiment=Send "/v1/prompt-experiments/$($experiment.id)/start" @{revision=$experiment.revision}
for($i=0;$i -lt 4;$i++){
 $assignment="prompt-smoke-$suffix-$i";$g=Send '/v1/generations/images' $body $assignment;$replay=Send '/v1/generations/images' $body $assignment
 if($g.generationId -ne $replay.generationId){throw 'Experiment idempotency failed'}
 $d=Invoke-RestMethod "$BaseUrl/api/v1/generations/$($g.generationId)"
 if($d.experiment_id -ne $experiment.id -or !$d.experiment_variant_id -or $d.promptSnapshots[0].experiment_variant_id -ne $d.experiment_variant_id){throw 'Experiment attribution failed'}
}
$legacy=Invoke-RestMethod "$BaseUrl/api/generations"
if(!$legacy.Count){throw 'Legacy generations not readable'}
Write-Output 'PASS: template/variables/negative prompt, publish, canonical and provider previews, preset composition, mock generation, immutable snapshot, v2 history, A/B attribution and deterministic replay.'
Write-Output "Template: $($template.id); generation: $($generation.generationId); experiment: $($experiment.id)"
