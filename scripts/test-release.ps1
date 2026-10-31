param([Parameter(Mandatory=$true)][string]$Directory,
    [string]$ShellCommand = 'sh', [string]$TarCommand = 'tar')
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $Directory).Path
$utf8 = New-Object System.Text.UTF8Encoding($false)
foreach ($fault in @('checksum','crlf','missing','extra','bytecode','mode','path','case')) {
    $root = Join-Path (Split-Path -Parent $PSScriptRoot) ('target/release-fault-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $root | Out-Null
    Copy-Item -Path (Join-Path $source '*') -Destination $root
    $script = Join-Path $root 'run.sh'
    if ($fault -eq 'checksum') { [IO.File]::AppendAllText($script, '#tamper', $utf8) }
    if ($fault -eq 'crlf') { [IO.File]::WriteAllText($script, ([IO.File]::ReadAllText($script) -replace "`n","`r`n"), $utf8) }
    if ($fault -eq 'missing') { Move-Item -LiteralPath $script -Destination (Join-Path $root 'renamed.sh') }
    if ($fault -eq 'extra') { [IO.File]::WriteAllText((Join-Path $root 'unexpected'), 'extra', $utf8) }
    if ($fault -eq 'case') {
        Move-Item -LiteralPath $script -Destination (Join-Path $root 'rename-intermediate')
        Move-Item -LiteralPath (Join-Path $root 'rename-intermediate') -Destination (Join-Path $root 'RUN.SH')
    }
    if ($fault -eq 'bytecode') {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        Add-Type -AssemblyName System.IO.Compression
        $jar = [IO.Compression.ZipFile]::Open((Join-Path $root 'fx9600-writing-box.jar'), [IO.Compression.ZipArchiveMode]::Update)
        try {
            $entry = $jar.GetEntry('org/fus/Main.class'); $stream = $entry.Open(); $buffer = New-Object IO.MemoryStream
            try { $stream.CopyTo($buffer); $bytes = $buffer.ToArray() } finally { $stream.Dispose(); $buffer.Dispose() }
            $entry.Delete(); $bytes[7] = 65
            $stream = $jar.CreateEntry('org/fus/Main.class').Open()
            try { $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
        } finally { $jar.Dispose() }
    }
    if ($fault -in @('crlf','bytecode','case')) {
        $hashes = @(Get-ChildItem -LiteralPath $root -File | Where-Object Name -ne 'SHA256SUMS' | Sort-Object Name | ForEach-Object {
            (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant() + '  ' + $_.Name
        })
        [IO.File]::WriteAllText((Join-Path $root 'SHA256SUMS'), ($hashes -join "`n") + "`n", $utf8)
    }
    $rejected = $false
    if ($fault -in @('mode','path')) {
        $archive = "$root.tar"; $tarRoot = $root -replace '\\','/'; $tarArchive = $archive -replace '\\','/'
        if ($tarRoot -match '^([A-Za-z]):/(.*)$') { $tarRoot = '/' + $Matches[1].ToLowerInvariant() + '/' + $Matches[2] }
        if ($tarArchive -match '^([A-Za-z]):/(.*)$') { $tarArchive = '/' + $Matches[1].ToLowerInvariant() + '/' + $Matches[2] }
        $names = @(Get-ChildItem -LiteralPath $root -File | Select-Object -ExpandProperty Name)
        if ($fault -eq 'mode') {
            & $TarCommand --format=ustar --mode=0644 -cf $tarArchive -C $tarRoot @names
        } else {
            $scriptNames = @('run.sh','fx9600-writing-box','start_fx9600-writing-box.sh','stop_fx9600-writing-box.sh','verify-release.sh')
            $dataNames = @($names | Where-Object { $scriptNames -notcontains $_ })
            & $TarCommand --format=ustar --mode=0644 -cf $tarArchive -C $tarRoot @dataNames
            if ($LASTEXITCODE -ne 0) { throw 'Fault data archive failed' }
            & $TarCommand --format=ustar --mode=0755 '--transform=s|^run.sh$|folder/run.sh|' -rf $tarArchive -C $tarRoot @scriptNames
        }
        if ($LASTEXITCODE -ne 0) { throw 'Fault archive construction failed' }
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant()
        [IO.File]::WriteAllText("$archive.sha256", "$hash  $([IO.Path]::GetFileName($archive))`n", $utf8)
        try { & "$PSScriptRoot/check-archive.ps1" -Archive $archive -ShellCommand $ShellCommand -TarCommand $TarCommand }
        catch { $rejected = $true }
    } else {
        try { & "$PSScriptRoot/check-release.ps1" -Directory $root -ShellCommand $ShellCommand }
        catch { $rejected = $true }
    }
    if (-not $rejected) { throw "Release fault accepted: $fault" }
    Write-Host "Rejected release fault: $fault"
}
Write-Host 'Release fault injection PASS (8 cases)'
