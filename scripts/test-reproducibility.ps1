param([Parameter(Mandatory=$true)][string]$OutputPrefix,
    [string]$TarCommand = 'tar', [string]$ShellCommand = 'sh')
$ErrorActionPreference = 'Stop'
# Two fresh full builds, including regression and archive validation, must match byte for byte.
foreach ($suffix in @('a', 'b')) {
    & "$PSScriptRoot/stage-release.ps1" -OutputDirectory "$OutputPrefix-$suffix" `
        -TarCommand $TarCommand -ShellCommand $ShellCommand
}
$archives = @("$OutputPrefix-a.tar", "$OutputPrefix-b.tar")
$hashes = @($archives | ForEach-Object { (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash })
if ($hashes[0] -ne $hashes[1]) { throw "Release is not reproducible: $($hashes -join ' != ')" }
Write-Host "Reproducible release PASS: $($hashes[0].ToLowerInvariant())"
