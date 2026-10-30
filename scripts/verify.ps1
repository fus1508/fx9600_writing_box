$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $sources = @(Get-ChildItem -Path 'src/main/java' -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
    & javac -encoding UTF-8 -source 8 -target 8 -cp 'library/Symbol.RFID.API3.jar' -d 'target/classes' $sources
    if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
    $tests = @(Get-ChildItem -Path 'src/test/java' -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
    & javac -encoding UTF-8 -source 8 -target 8 -cp 'target/classes' -d 'target/test-classes' $tests
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
    foreach ($check in @('BarcodeFramerCheck', 'WorkflowCheck', 'StatusServerCheck', 'OperationGuardCheck')) {
        & java -cp 'target/classes;target/test-classes' "org.fus.$check"
        if ($LASTEXITCODE -ne 0) { throw "$check failed" }
    }
} finally {
    Pop-Location
}
