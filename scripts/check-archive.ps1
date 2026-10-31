param([Parameter(Mandatory=$true)][string]$Archive, [string]$TarCommand = 'tar', [string]$ShellCommand = 'sh')
$ErrorActionPreference = 'Stop'
$archivePath = (Resolve-Path -LiteralPath $Archive).Path
$checksum = (Get-Content -Raw -LiteralPath "$archivePath.sha256").Trim()
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash.ToLowerInvariant()
if ($checksum -ne ($hash + '  ' + [IO.Path]::GetFileName($archivePath))) { throw 'Archive checksum mismatch' }
$scripts = @('run.sh','fx9600-writing-box','start_fx9600-writing-box.sh','stop_fx9600-writing-box.sh','verify-release.sh')
$data = @('fx9600-writing-box.jar','Symbol.RFID.API3.jar','station.properties.example','release.properties','SHA256SUMS')
$tarPath = $archivePath -replace '\\','/'
if ($env:OS -eq 'Windows_NT' -and $tarPath -match '^([A-Za-z]):/(.*)$') { $tarPath = '/' + $Matches[1].ToLowerInvariant() + '/' + $Matches[2] }
$listing = @(& $TarCommand -tvf $tarPath)
if ($LASTEXITCODE -ne 0 -or $listing.Count -ne ($scripts.Count + $data.Count)) { throw 'Archive entry count invalid' }
$names = @(& $TarCommand -tf $tarPath)
if ($LASTEXITCODE -ne 0 -or $names.Count -ne $listing.Count) { throw 'Archive names invalid' }
$seen = New-Object System.Collections.Hashtable ([StringComparer]::Ordinal)
for ($index = 0; $index -lt $listing.Count; $index++) {
    $entry = $listing[$index]; $name = $names[$index]
    if ($seen.ContainsKey($name)) { throw 'Duplicate archive entry' }
    $seen[$name] = $true
    if ($scripts -ccontains $name) { if (-not $entry.StartsWith('-rwxr-xr-x ')) { throw "Script mode invalid: $name" } }
    elseif ($data -ccontains $name) { if (-not $entry.StartsWith('-rw-r--r-- ')) { throw "Data mode invalid: $name" } }
    else { throw 'Unknown/path/link archive entry' }
}
$workspace = Split-Path -Parent $PSScriptRoot
$qa = Join-Path $workspace ('target/archive-qa-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $qa | Out-Null
$tarQa = $qa -replace '\\','/'
if ($env:OS -eq 'Windows_NT' -and $tarQa -match '^([A-Za-z]):/(.*)$') { $tarQa = '/' + $Matches[1].ToLowerInvariant() + '/' + $Matches[2] }
& $TarCommand -xf $tarPath -C $tarQa
if ($LASTEXITCODE -ne 0) { throw 'Archive extraction failed' }
& "$PSScriptRoot/check-release.ps1" -Directory $qa -ShellCommand $ShellCommand
Write-Host "Archive checksum/flat allowlist/POSIX modes/extracted payload PASS: $archivePath"
