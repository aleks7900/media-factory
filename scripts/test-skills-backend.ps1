param([switch]$All,[switch]$Compile,[string[]]$CustomTasks)
$ErrorActionPreference='Stop'
$repoRoot=Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot
$env:JAVA_HOME=(Resolve-Path '.tools/jdk-21.0.12.1+1').Path
$env:GRADLE_USER_HOME=Join-Path $env:USERPROFILE '.gradle'
$skillsBuild=Join-Path $env:TEMP ('skills-it-'+[guid]::NewGuid())
New-Item -ItemType Directory -Path $skillsBuild | Out-Null
$skillsInit=Join-Path $skillsBuild 'init.gradle'
"allprojects { layout.buildDirectory.set(file('$(($skillsBuild -replace '\\','/') + '/output')')) }" | Set-Content $skillsInit
$skillsTasks=@(if($CustomTasks){$CustomTasks}elseif($All){@('test','integrationTest','bootJar')}elseif($Compile){@('compileJava')}else{@('integrationTest','--tests','*SkillIntegrationTest','bootJar')})
$ErrorActionPreference='Continue' # Windows PowerShell treats normal javac stderr warnings as errors.
& .tools/gradle-9.1.0/bin/gradle.bat -p backend --init-script $skillsInit --project-cache-dir "$skillsBuild/cache" @skillsTasks --no-daemon 2>&1 | Tee-Object backend/build/task12-integration.log
$result=$LASTEXITCODE
$ErrorActionPreference='Continue'
New-Item -ItemType Directory -Force backend/build/task12-integration-results | Out-Null
Copy-Item "$skillsBuild/output/test-results/integrationTest/*.xml" backend/build/task12-integration-results/ -Force -ErrorAction SilentlyContinue
if($All){New-Item -ItemType Directory -Force backend/build/task12-unit-results | Out-Null;Copy-Item "$skillsBuild/output/test-results/test/*.xml" backend/build/task12-unit-results/ -Force -ErrorAction SilentlyContinue}
if(!$Compile -and $result -eq 0 -and (Test-Path "$skillsBuild/output/libs")){Copy-Item "$skillsBuild/output/libs/*.jar" backend/build/libs/ -Force}
exit $result





