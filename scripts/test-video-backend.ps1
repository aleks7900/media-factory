param([switch]$All)
$ErrorActionPreference='Stop'
$repoRoot=Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot
$env:JAVA_HOME=(Resolve-Path '.tools/jdk-21.0.12.1+1').Path
$env:GRADLE_USER_HOME=Join-Path $env:USERPROFILE '.gradle'
$videoBuild=Join-Path $env:TEMP ('video-it-'+[guid]::NewGuid())
New-Item -ItemType Directory -Path $videoBuild | Out-Null
$videoInit=Join-Path $videoBuild 'init.gradle'
"allprojects { layout.buildDirectory.set(file('$(($videoBuild -replace '\\','/') + '/output')')) }" | Set-Content $videoInit
$videoTasks=if($All){@('test','integrationTest','bootJar')}else{@('integrationTest','--tests','*VideoIntegrationTest')}
& .tools/gradle-9.1.0/bin/gradle.bat -p backend --init-script $videoInit --project-cache-dir "$videoBuild/cache" @videoTasks --no-daemon 2>&1 | Tee-Object backend/build/task09-integration.log
$result=$LASTEXITCODE
New-Item -ItemType Directory -Force backend/build/task09-integration-results | Out-Null
Copy-Item "$videoBuild/output/test-results/integrationTest/*.xml" backend/build/task09-integration-results/ -Force -ErrorAction SilentlyContinue
if($All){New-Item -ItemType Directory -Force backend/build/task09-unit-results | Out-Null;Copy-Item "$videoBuild/output/test-results/test/*.xml" backend/build/task09-unit-results/ -Force -ErrorAction SilentlyContinue}
if($All -and $result -eq 0){Copy-Item "$videoBuild/output/libs/*.jar" backend/build/libs/ -Force}
exit $result
