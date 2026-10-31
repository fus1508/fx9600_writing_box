param([string]$Compiler = 'g++', [switch]$Zig, [Parameter(Mandatory=$true)][string]$JsonInclude)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    New-Item -ItemType Directory -Path target/phase3-evidence -Force | Out-Null
    if (-not (Test-Path target/phase3-evidence/java-status-fixture.json)) { throw 'Run org.fus.HealthLatencyCheck first to generate the actual API fixture' }
    $driver = @()
    if ($Zig) { $driver = @('c++') }
    $env:ZIG_GLOBAL_CACHE_DIR = Join-Path (Get-Location) 'target/phase3-tools/cache'
    foreach ($name in @('health_ui_check', 'status_parser_check')) {
        & $Compiler @driver -std=c++11 -Wall -Wextra -Werror -I screen_writing_box/include -I $JsonInclude "screen_writing_box/test/$name.cpp" -o "target/phase3-evidence/$name.exe"
        if ($LASTEXITCODE -ne 0) { throw "C++ compile failed: $name" }
        & "target/phase3-evidence/$name.exe" target/phase3-evidence/java-status-fixture.json
        if ($LASTEXITCODE -ne 0) { throw "C++ check failed: $name" }
    }
} finally { Pop-Location }
