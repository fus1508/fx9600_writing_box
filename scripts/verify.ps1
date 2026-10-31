$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $compilerVersion = (& javac -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw 'javac not available' }
    $compatibility = if ($compilerVersion -match 'javac 1\.8\.') { @('-source', '8', '-target', '8') } else { @('--release', '8') }
    $sources = @(Get-ChildItem -Path 'src/main/java' -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
    & javac -encoding UTF-8 @compatibility -cp 'library/Symbol.RFID.API3.jar' -d 'target/classes' $sources
    if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
    $tests = @(Get-ChildItem -Path 'src/test/java' -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
    & javac -encoding UTF-8 @compatibility -cp 'target/classes;library/Symbol.RFID.API3.jar' -d 'target/test-classes' $tests
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
    foreach ($check in @('BarcodeFramerCheck', 'WorkflowCheck', 'StatusServerCheck', 'OperationGuardCheck', 'Phase1SafetyCheck', 'TransactionJournalCheck', 'IdentityCheck', 'ProcessSupervisorCheck', 'StartupCheck', 'StopControlCheck', 'DeviceHealthCheck', 'HealthApiCheck', 'ScannerHealthCheck')) {
        & java -cp 'target/classes;target/test-classes;library/Symbol.RFID.API3.jar' "org.fus.$check"
        if ($LASTEXITCODE -ne 0) { throw "$check failed" }
    }
} finally {
    Pop-Location
}
