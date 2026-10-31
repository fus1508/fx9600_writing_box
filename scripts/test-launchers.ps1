param([Parameter(Mandatory=$true)][string]$Directory, [string]$ShellCommand = 'sh')
$ErrorActionPreference = 'Stop'
$payload = (Resolve-Path -LiteralPath $Directory).Path -replace '\\','/'
$fixture = Join-Path (Split-Path -Parent $PSScriptRoot) ('target/launcher fixture ' + [guid]::NewGuid().ToString('N'))
$bin = Join-Path $fixture 'bin'; New-Item -ItemType Directory -Path $bin -Force | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
$fakeJava = '#!/bin/sh' + "`n" + 'printf ''%s\n'' "$@" > "$LAUNCH_CAPTURE"' + "`n" + 'exit 20' + "`n"
[IO.File]::WriteAllText((Join-Path $bin 'java'), $fakeJava, $utf8)
$oldJavaHome = $env:JAVA_HOME; $oldConfig = $env:FX9600_CONFIG; $oldCapture = $env:LAUNCH_CAPTURE; $oldState = $env:FX9600_STATE_DIR
try {
    $env:JAVA_HOME = $fixture -replace '\\','/'
    $env:FX9600_STATE_DIR = $null
    $env:FX9600_CONFIG = '/persistent state/station.properties'
    foreach ($name in @('run.sh','start_fx9600-writing-box.sh','stop_fx9600-writing-box.sh')) {
        $capture = Join-Path $fixture ($name + '.args'); $env:LAUNCH_CAPTURE = $capture -replace '\\','/'
        & $ShellCommand ($payload + '/' + $name)
        if ($LASTEXITCODE -ne 20) { throw "Launcher did not propagate child exit: $name" }
        $argv = @(Get-Content -LiteralPath $capture)
        $mode = if ($name -eq 'stop_fx9600-writing-box.sh') { '--stop' } else { '--supervise' }
        if ($argv.Count -ne 5 -or $argv[0] -ne '-cp' -or $argv[2] -ne 'org.fus.Main' -or $argv[3] -ne $mode -or $argv[4] -ne $env:FX9600_CONFIG) { throw "Launcher argument quoting failed: $name" }
    }
    $env:LAUNCH_CAPTURE = (Join-Path $fixture 'relative-must-not-launch.args') -replace '\\','/'
    $env:FX9600_CONFIG = 'relative.properties'
    & $ShellCommand ($payload + '/start_fx9600-writing-box.sh')
    if ($LASTEXITCODE -ne 20 -or (Test-Path -LiteralPath $env:LAUNCH_CAPTURE)) { throw 'Relative config accepted' }
    $broken = Join-Path $fixture 'broken release'; New-Item -ItemType Directory -Path $broken | Out-Null
    Copy-Item -Path ($payload + '/*') -Destination $broken
    [IO.File]::AppendAllText((Join-Path $broken 'station.properties.example'), '#corrupt', $utf8)
    $env:FX9600_CONFIG = '/persistent state/station.properties'
    $env:LAUNCH_CAPTURE = (Join-Path $fixture 'broken-stop.args') -replace '\\','/'
    & $ShellCommand ((Join-Path $broken 'stop_fx9600-writing-box.sh') -replace '\\','/')
    if ($LASTEXITCODE -ne 20 -or -not (Test-Path -LiteralPath $env:LAUNCH_CAPTURE)) { throw 'Unrelated checksum blocked stop' }
    $env:FX9600_STATE_DIR = '/persistent state'
    $env:FX9600_CONFIG = 'broken-relative-config'
    $env:LAUNCH_CAPTURE = (Join-Path $fixture 'state-stop.args') -replace '\\','/'
    & $ShellCommand ($payload + '/stop_fx9600-writing-box.sh')
    $stateArgs = @(Get-Content -LiteralPath $env:LAUNCH_CAPTURE)
    if ($LASTEXITCODE -ne 20 -or $stateArgs[3] -ne '--stop-state' -or $stateArgs[4] -ne $env:FX9600_STATE_DIR) { throw 'State-only stop failed' }
    Write-Host 'Launcher shell integration PASS (fake Java only: start/stop/run arguments, spaces, exit propagation, relative config)'
} finally {
    $env:JAVA_HOME = $oldJavaHome; $env:FX9600_CONFIG = $oldConfig; $env:LAUNCH_CAPTURE = $oldCapture
    $env:FX9600_STATE_DIR = $oldState
}
