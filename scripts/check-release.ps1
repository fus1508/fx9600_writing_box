param([Parameter(Mandatory=$true)][string]$Directory, [string]$ShellCommand = 'sh')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $Directory).Path
$required = @('fx9600-writing-box.jar', 'Symbol.RFID.API3.jar', 'station.properties.example',
    'run.sh', 'fx9600-writing-box', 'start_fx9600-writing-box.sh', 'stop_fx9600-writing-box.sh',
    'verify-release.sh', 'release.properties')
$lines = @(Get-Content -LiteralPath (Join-Path $root 'SHA256SUMS'))
if ($lines.Count -ne $required.Count) { throw 'Checksum file count mismatch' }
$seen = New-Object System.Collections.Hashtable ([StringComparer]::Ordinal)
foreach ($line in $lines) {
    if ($line -cnotmatch '^([a-f0-9]{64})  ([a-zA-Z0-9_.-]+)$') { throw 'Invalid checksum entry' }
    $hash = $Matches[1]; $name = $Matches[2]
    if ($required -cnotcontains $name -or $seen.ContainsKey($name)) { throw 'Unknown/duplicate checksum entry' }
    $seen[$name] = $true
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $root $name)).Hash.ToLowerInvariant() -ne $hash) { throw "Checksum mismatch: $name" }
}
$actual = @(Get-ChildItem -LiteralPath $root -Force | Select-Object -ExpandProperty Name)
if ($actual.Count -ne ($required.Count + 1)) { throw 'Unexpected release content' }
foreach ($name in ($required + @('SHA256SUMS'))) {
    if ($actual -cnotcontains $name) { throw 'Release filenames must match Linux case exactly' }
}
foreach ($name in @('run.sh', 'fx9600-writing-box', 'start_fx9600-writing-box.sh', 'stop_fx9600-writing-box.sh', 'verify-release.sh')) {
    $path = Join-Path $root $name
    $bytes = [IO.File]::ReadAllBytes($path)
    if ($bytes.Length -eq 0 -or $bytes -contains 13 -or $bytes[$bytes.Length - 1] -ne 10 -or $bytes[0] -ne 35) { throw "Script must be LF, no BOM: $name" }
    & $ShellCommand -n ($path -replace '\\','/')
    if ($LASTEXITCODE -ne 0) { throw "Shell syntax failed: $name" }
}
$metadata = Get-Content -Raw -LiteralPath (Join-Path $root 'release.properties')
if ($metadata -cnotmatch '(?m)^journal.schema=FXJNL01$' -or $metadata -cnotmatch '(?m)^production.write=BLOCKED$' -or
    $metadata -cnotmatch '(?m)^firmware.sat=BLOCKED$' -or $metadata -cnotmatch '(?m)^runtime.schema=FXRUNTIME02$' -or
    $metadata -cnotmatch '(?m)^rollback.requires=FXJNL01\+FXRUNTIME02$') { throw 'Safety metadata missing' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$jar = [IO.Compression.ZipFile]::OpenRead((Join-Path $root 'fx9600-writing-box.jar'))
try {
    if (-not ($jar.Entries.FullName -ccontains 'org/fus/Main.class') -or -not ($jar.Entries.FullName -ccontains 'org/fus/runtime/ProcessSupervisor.class')) { throw 'Missing application classes' }
    foreach ($entry in $jar.Entries) {
        if ($entry.FullName.EndsWith('.class')) {
            $stream = $entry.Open()
            try {
                $header = New-Object byte[] 8
                if ($stream.Read($header, 0, 8) -ne 8 -or $header[0] -ne 202 -or $header[1] -ne 254 -or $header[6] -ne 0 -or $header[7] -ne 52) { throw 'Non-Java-8 class in release' }
            } finally { $stream.Dispose() }
        }
    }
} finally { $jar.Dispose() }
Write-Host 'Release content/checksums/LF/shell syntax PASS; POSIX archive permissions require archive check.'
