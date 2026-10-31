param([string]$OutputDirectory = 'target/release-staging', [string]$Version = '4.0.0-local',
    [string]$TarCommand = 'tar', [string]$ShellCommand = 'sh')
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $destination = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputDirectory))
    $workspace = (Get-Location).Path.TrimEnd('\') + '\'
    if (-not $destination.StartsWith($workspace, [StringComparison]::OrdinalIgnoreCase)) { throw 'Output must remain in workspace' }
    if ($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+-[a-z0-9.-]+$') { throw 'Invalid local release version' }
    $archive = "$destination.tar"
    if ((Test-Path -LiteralPath $archive) -or (Test-Path -LiteralPath "$archive.sha256") -or (Test-Path -LiteralPath "$archive.work")) { throw 'Archive already exists' }
    if (Test-Path -LiteralPath $destination) {
        throw "Staging directory already exists: $destination"
    }
    & "$PSScriptRoot/verify.ps1"
    $classes = Join-Path $destination 'build/classes'
    $payload = Join-Path $destination 'payload'
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    New-Item -ItemType Directory -Path $payload | Out-Null
    $sources = @(Get-ChildItem -Path 'src/main/java' -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
    & javac -encoding UTF-8 --release 8 -cp 'library/Symbol.RFID.API3.jar' -d $classes $sources
    if ($LASTEXITCODE -ne 0) { throw 'Release compilation failed' }
    # A class-path JAR is a ZIP; no manifest entry point is needed (Main remains explicit).
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    Add-Type -AssemblyName System.IO.Compression
    $zip = [IO.Compression.ZipFile]::Open((Join-Path $payload 'fx9600-writing-box.jar'), [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($file in (Get-ChildItem -LiteralPath $classes -Recurse -File | Sort-Object FullName)) {
            $entryName = $file.FullName.Substring($classes.Length + 1) -replace '\\','/'
            $entry = $zip.CreateEntry($entryName)
            $entry.LastWriteTime = [DateTimeOffset]::new(2000, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
            $inputStream = $file.OpenRead(); $outputStream = $entry.Open()
            try { $inputStream.CopyTo($outputStream) } finally { $inputStream.Dispose(); $outputStream.Dispose() }
        }
    } finally { $zip.Dispose() }
    Copy-Item -LiteralPath 'library/Symbol.RFID.API3.jar' -Destination $payload
    Copy-Item -LiteralPath 'station.properties.example' -Destination $payload
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $scripts = @('run.sh', 'fx9600-writing-box', 'start_fx9600-writing-box.sh', 'stop_fx9600-writing-box.sh', 'verify-release.sh')
    foreach ($name in $scripts) {
        $text = [IO.File]::ReadAllText((Join-Path 'deploy' $name)) -replace "`r`n", "`n"
        [IO.File]::WriteAllText((Join-Path $payload $name), $text, $utf8)
    }
    $baseline = (& git rev-parse HEAD | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot identify source baseline' }
    # Record exact checkout bytes, including uncommitted code, tests and build scripts.
    # This manifest stays beside build output; its digest is inside the checksummed payload.
    $inputs = @(Get-ChildItem src,deploy,scripts -Recurse -File | Where-Object { $_.FullName -notmatch '[\\/]__pycache__[\\/]' })
    $inputs += Get-Item library/Symbol.RFID.API3.jar,station.properties.example
    $inputLines = @($inputs | Sort-Object FullName | ForEach-Object {
        (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' +
            ($_.FullName.Substring($workspace.Length) -replace '\\','/')
    })
    $inputManifest = ($inputLines -join "`n") + "`n"
    [IO.File]::WriteAllText((Join-Path $destination 'source-inputs.sha256'), $inputManifest, $utf8)
    $inputHash = (Get-FileHash -LiteralPath (Join-Path $destination 'source-inputs.sha256') -Algorithm SHA256).Hash.ToLowerInvariant()
    $compiler = (& javac -version 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot identify compiler' }
    $metadata = "version=$Version`nsource.baseline=$baseline`nsource.state=uncommitted-phase4`nsource.inputs.sha256=$inputHash`nbuild.javac=$compiler`nbuild.epoch=946684800`nstatus.schema=2`njournal.schema=FXJNL01`nruntime.schema=FXRUNTIME02`nrollback.requires=FXJNL01+FXRUNTIME02`nproduction.write=BLOCKED`nfirmware.sat=BLOCKED`npackage.type=local-tar-not-zebra-installer`n"
    [IO.File]::WriteAllText((Join-Path $payload 'release.properties'), $metadata, $utf8)
    $hashes = @(Get-ChildItem -LiteralPath $payload -File | Sort-Object Name | ForEach-Object {
        (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant() + '  ' + $_.Name
    })
    [IO.File]::WriteAllText((Join-Path $payload 'SHA256SUMS'), ($hashes -join "`n") + "`n", $utf8)
    & "$PSScriptRoot/check-release.ps1" -Directory $payload -ShellCommand $ShellCommand
    $tarVersion = (& $TarCommand --version | Out-String)
    if ($LASTEXITCODE -ne 0 -or $tarVersion -notmatch 'GNU tar') { throw 'GNU tar required' }
    # GNU tar sets modes in the archive even when staging on NTFS. No device changes.
    $tarArchive = $archive -replace '\\','/'
    $tarPayload = $payload -replace '\\','/'
    if ($env:OS -eq 'Windows_NT' -and $tarArchive -match '^([A-Za-z]):/(.*)$') { $tarArchive = '/' + $Matches[1].ToLowerInvariant() + '/' + $Matches[2] }
    if ($env:OS -eq 'Windows_NT' -and $tarPayload -match '^([A-Za-z]):/(.*)$') { $tarPayload = '/' + $Matches[1].ToLowerInvariant() + '/' + $Matches[2] }
    & $TarCommand --format=ustar --mtime=@946684800 --mode=0644 --owner=0 --group=0 --numeric-owner -cf "$tarArchive.work" -C $tarPayload @('fx9600-writing-box.jar','Symbol.RFID.API3.jar','station.properties.example','release.properties','SHA256SUMS')
    if ($LASTEXITCODE -ne 0) { throw 'Data archive failed' }
    & $TarCommand --format=ustar --mtime=@946684800 --mode=0755 --owner=0 --group=0 --numeric-owner -rf "$tarArchive.work" -C $tarPayload @scripts
    if ($LASTEXITCODE -ne 0) { throw 'Executable archive failed' }
    Move-Item -LiteralPath "$archive.work" -Destination $archive
    $archiveHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText("$archive.sha256", "$archiveHash  $([IO.Path]::GetFileName($archive))`n", $utf8)
    & "$PSScriptRoot/check-archive.ps1" -Archive $archive -TarCommand $TarCommand -ShellCommand $ShellCommand
    & java -cp "$payload/fx9600-writing-box.jar;target/test-classes;$payload/Symbol.RFID.API3.jar" org.fus.StartupCheck
    if ($LASTEXITCODE -ne 0) { throw 'Packaged JAR startup checks failed' }
    Write-Host "Local tar release: $archive; NOT a Zebra .deb installer."
} finally { Pop-Location }
