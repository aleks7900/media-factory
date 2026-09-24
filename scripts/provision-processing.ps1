$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$modelRoot = Join-Path $projectRoot '.tools/processing-models'
New-Item -ItemType Directory -Force $modelRoot | Out-Null
$models = Get-Content (Join-Path $projectRoot 'workers/image-processing/models.json') -Raw | ConvertFrom-Json
foreach ($model in $models) {
    $destination = Join-Path $modelRoot $model.file
    if ((Test-Path -LiteralPath $destination) -and (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -eq $model.sha256) {
        Write-Output "$($model.id): verified"
        continue
    }
    $temporary = "$destination.download"
    try {
        Invoke-WebRequest -Uri $model.url -OutFile $temporary
        if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash.ToLowerInvariant() -ne $model.sha256) { throw "Model checksum mismatch: $($model.id)" }
        Move-Item -LiteralPath $temporary -Destination $destination -Force
        Write-Output "$($model.id): provisioned"
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
}
