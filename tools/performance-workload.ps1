param(
    [ValidatePattern('^\d+(,\d+)*$')][string]$Sessions = '100,1000',
    [ValidateRange(10,10000)][int]$EventsPerSession = 1000,
    [ValidateRange(5,100)][int]$Samples = 20,
    [ValidateRange(1,5)][int]$Imports = 3
)
$ErrorActionPreference = 'Stop'
$benchmarkNames = @('CODEX_BENCHMARK_SESSIONS', 'CODEX_BENCHMARK_EVENTS', 'CODEX_BENCHMARK_SAMPLES', 'CODEX_BENCHMARK_IMPORTS')
$previousValues = @{}
foreach ($benchmarkName in $benchmarkNames) {
    $previousValues[$benchmarkName] = [Environment]::GetEnvironmentVariable($benchmarkName, 'Process')
}
try {
    $env:CODEX_BENCHMARK_SESSIONS = $Sessions
    $env:CODEX_BENCHMARK_EVENTS = "$EventsPerSession"
    $env:CODEX_BENCHMARK_SAMPLES = "$Samples"
    $env:CODEX_BENCHMARK_IMPORTS = "$Imports"
    Push-Location (Split-Path -Parent $PSScriptRoot)
    try {
        & .\gradlew.bat performanceBaseline
        if ($LASTEXITCODE -ne 0) { throw "Performance baseline failed (exit $LASTEXITCODE)." }
    } finally { Pop-Location }
} finally {
    foreach ($benchmarkName in $benchmarkNames) {
        [Environment]::SetEnvironmentVariable($benchmarkName, $previousValues[$benchmarkName], 'Process')
    }
}
