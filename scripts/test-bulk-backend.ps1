param([switch]$All,[switch]$Compile,[switch]$Unit)
$ErrorActionPreference='Stop'
$repoRoot=Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot
$env:JAVA_HOME=(Resolve-Path '.tools/jdk-21.0.12.1+1').Path
$env:GRADLE_USER_HOME=Join-Path $env:USERPROFILE '.gradle'
$skillsBuild=Join-Path $env:TEMP ('bulk-it-'+[guid]::NewGuid())
New-Item -ItemType Directory -Path $skillsBuild | Out-Null
$skillsInit=Join-Path $skillsBuild 'init.gradle'
"allprojects { layout.buildDirectory.set(file('$(($skillsBuild -replace '\\','/') + '/output')')) }" | Set-Content $skillsInit
$skillsTasks=@(if($All){@('test','integrationTest','bootJar')}elseif($Unit){@('test','--tests','*Bulk*Test')}elseif($Compile){@('compileJava')}else{@('integrationTest','--tests','*BulkIntegrationTest','bootJar')})
$ErrorActionPreference='Continue' # Windows PowerShell treats normal javac stderr warnings as errors.
& .tools/gradle-9.1.0/bin/gradle.bat -p backend --init-script $skillsInit --project-cache-dir "$skillsBuild/cache" @skillsTasks --no-daemon 2>&1 | Tee-Object backend/build/bulk-integration.log
$result=$LASTEXITCODE
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force backend/build/bulk-integration-results | Out-Null
Copy-Item "$skillsBuild/output/test-results/integrationTest/*.xml" backend/build/bulk-integration-results/ -Force -ErrorAction SilentlyContinue
if($All -or $Unit){New-Item -ItemType Directory -Force backend/build/bulk-unit-results | Out-Null;Copy-Item "$skillsBuild/output/test-results/test/*.xml" backend/build/bulk-unit-results/ -Force -ErrorAction SilentlyContinue}
if(!$Compile -and !$Unit -and $result -eq 0){Copy-Item "$skillsBuild/output/libs/*.jar" backend/build/libs/ -Force}
exit $result





