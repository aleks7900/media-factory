param([string]$RepositoryRoot=(Split-Path $PSScriptRoot -Parent))
$ErrorActionPreference='Stop'
$skillRepo=(Resolve-Path -LiteralPath $RepositoryRoot).Path
$skillSource=Join-Path $skillRepo 'skills'
$skillDestination=Join-Path $skillRepo '.agents/skills'
$skillNames=@('research-trends','create-collection','run-qa','prepare-stock','create-wallpapers','_shared')
New-Item -ItemType Directory -Force -Path $skillDestination | Out-Null
foreach($skillName in $skillNames){
 $skillTarget=Join-Path $skillDestination $skillName
 $skillMarker=Join-Path $skillTarget '.media-factory-owned'
 if(Test-Path -LiteralPath $skillTarget){
  if(!(Test-Path -LiteralPath $skillMarker) -or (Get-Content -LiteralPath $skillMarker -Raw -Encoding utf8).Trim() -ne $skillRepo){throw "Unowned destination: $skillTarget"}
 }
 New-Item -ItemType Directory -Force -Path $skillTarget | Out-Null
 Copy-Item -Path (Join-Path $skillSource "$skillName/*") -Destination $skillTarget -Recurse -Force
 Set-Content -LiteralPath $skillMarker -Value $skillRepo -Encoding utf8
}
Write-Output 'Installed five repository skills and shared resources under .agents/skills.'
