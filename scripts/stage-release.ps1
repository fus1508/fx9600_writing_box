param([string]$OutputDirectory = 'target/release-staging')
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $destination = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputDirectory))
    if (Test-Path -LiteralPath $destination) {
        throw "Staging directory already exists: $destination"
    }
    & "$PSScriptRoot/verify.ps1"
    $classes = Join-Path $destination 'classes'
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    $sources = @(Get-ChildItem -Path 'src/main/java' -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
    & javac -encoding UTF-8 -source 8 -target 8 -cp 'library/Symbol.RFID.API3.jar' -d $classes $sources
    if ($LASTEXITCODE -ne 0) { throw 'Release compilation failed' }
    & jar cf (Join-Path $destination 'fx9600-writing-box.jar') -C $classes .
    if ($LASTEXITCODE -ne 0) { throw 'JAR creation failed' }
    Copy-Item -LiteralPath 'library/Symbol.RFID.API3.jar' -Destination $destination
    Copy-Item -LiteralPath 'station.properties.example' -Destination $destination
    Copy-Item -LiteralPath 'deploy/run.sh' -Destination $destination
    Write-Host "Staged files at $destination; this is not a .deb package."
} finally { Pop-Location }
