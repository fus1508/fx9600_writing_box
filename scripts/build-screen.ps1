param([string]$Python = 'target/phase4-tools/python/python.exe')
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
$variables = @('PLATFORMIO_CORE_DIR','PLATFORMIO_BUILD_DIR','PLATFORMIO_LIBDEPS_DIR','PLATFORMIO_SETTING_ENABLE_TELEMETRY')
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $pythonPath = (Resolve-Path -LiteralPath $Python).Path
    # Short workspace paths keep GCC's expanded command line below Windows limits.
    $env:PLATFORMIO_CORE_DIR = Join-Path (Get-Location) 'target/p4'
    $env:PLATFORMIO_BUILD_DIR = Join-Path (Get-Location) 'target/f4'
    $env:PLATFORMIO_LIBDEPS_DIR = Join-Path (Get-Location) 'target/l4'
    $env:PLATFORMIO_SETTING_ENABLE_TELEMETRY = 'No'
    & $pythonPath -m platformio run -d screen_writing_box -e esp32-s3-build-check
    if ($LASTEXITCODE -ne 0) { throw 'Firmware compile-only build failed' }
    $output = Join-Path $env:PLATFORMIO_BUILD_DIR 'esp32-s3-build-check'
    $hashes = @(Get-ChildItem -LiteralPath $output -File | Where-Object { $_.Extension -in @('.bin','.elf') } |
        Sort-Object Name | ForEach-Object {
            (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $_.Name
        })
    [IO.File]::WriteAllText((Join-Path $output 'SHA256SUMS'), ($hashes -join "`n") + "`n", (New-Object Text.UTF8Encoding($false)))
    Write-Host "BUILD PASS; placeholder config, NOT FOR DEPLOYMENT; hardware runtime/SAT NOT RUN. Output: $output"
} finally {
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    Pop-Location
}
