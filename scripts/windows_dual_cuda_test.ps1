# Requires PowerShell 7. Copy this file beside KG-next.exe as Test-CUDA.ps1.
# Runs one small benchmark per selected engine; no toolkit or Python is needed.
#Requires -Version 7.0
param(
    [ValidateSet('Both', 'CUDA12', 'CUDA13')]
    [string]$Profile = 'Both',
    [ValidateRange(1, 90)]
    [int]$TimeoutSeconds = 90
)

$ErrorActionPreference = 'Stop'
$installRoot = $PSScriptRoot
$model = Join-Path $installRoot 'app/weights/default.bin.gz'
$config = Join-Path $installRoot 'app/engines/katago/configs/gtp.cfg'
foreach ($path in @($model, $config)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Shared test asset is missing: $path"
    }
}
$resultsRoot = Join-Path $installRoot ('user-data/test-results/' + (Get-Date -Format 'yyyyMMdd-HHmmssfff'))
New-Item -ItemType Directory -Path $resultsRoot -Force | Out-Null
$variants = @(
    [pscustomobject]@{ Name = 'CUDA12'; Directory = 'windows-x64' },
    [pscustomobject]@{ Name = 'CUDA13'; Directory = 'windows-x64-nvidia-cuda13' }
)
$results = foreach ($variant in $variants) {
    if ($Profile -ne 'Both' -and $Profile -ne $variant.Name) { continue }
    $engineDirectory = Join-Path $installRoot ('app/engines/katago/' + $variant.Directory)
    $engine = Join-Path $engineDirectory 'katago.exe'
    $probeHome = Join-Path $installRoot ('user-data/runtime/katago-test/' + $variant.Name)
    $logDirectory = Join-Path $resultsRoot ($variant.Name + '-logs')
    $process = $null
    $started = $false
    $stdoutTask = $null
    $stderrTask = $null
    $exitCode = $null
    $timedOut = $false
    $passed = $false
    $failure = $null
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        if (-not (Test-Path -LiteralPath $engine -PathType Leaf)) {
            throw "Installed engine is missing: $engine"
        }
        # KataGo's comma-separated override syntax cannot encode commas in directory names.
        if ($probeHome.Contains(',') -or $probeHome.Contains("`n") -or $probeHome.Contains("`r")) {
            throw 'The installation path contains a character unsupported by KataGo config overrides.'
        }
        New-Item -ItemType Directory -Path $probeHome -Force | Out-Null
        $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
        $startInfo.FileName = $engine
        $startInfo.WorkingDirectory = $engineDirectory
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        # Change only this child's PATH. Conflicting CUDA/cuDNN DLLs stay in their own folders.
        $startInfo.Environment['PATH'] = "$engineDirectory;$($startInfo.Environment['PATH'])"
        foreach ($argument in @(
            'benchmark', '-config', $config, '-model', $model, '-n', '1', '-v', '32', '-t', '4',
            '-no-server-thread-test', '-no-half-batch-size-test',
            '-override-config', "numSearchThreads=4,homeDataDir=$probeHome,logDir=$logDirectory,logToStderr=false,logAllGTPCommunication=false,logSearchInfo=false"
        )) {
            $startInfo.ArgumentList.Add($argument)
        }
        Write-Host "Testing $($variant.Name) (one position, 32 visits, four search threads)..."
        $process = [System.Diagnostics.Process]::new()
        $process.StartInfo = $startInfo
        [void]$process.Start()
        $started = $true
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $timedOut = $true
            $process.Kill($true)
            $process.WaitForExit()
            throw "Engine exceeded the $TimeoutSeconds second timeout"
        }
        $exitCode = $process.ExitCode
        $text = $stdoutTask.GetAwaiter().GetResult() + "`n" + $stderrTask.GetAwaiter().GetResult()
        if ($exitCode -ne 0) { throw "Engine exited with code $exitCode" }
        if ($text -match 'Got nonfinite for policy sum|Error creating directory|Could not create file|Uncaught exception') {
            throw 'Engine output contains a fatal error; inspect the saved output'
        }
        if ($text -notmatch 'numSearchThreads\s*=\s*4:\s*1\s*/\s*1\s*positions,\s*visits/s\s*=\s*([0-9]+(?:\.[0-9]+)?)' -or
            [double]::Parse($Matches[1], [System.Globalization.CultureInfo]::InvariantCulture) -le 0) {
            throw 'Engine did not finish the requested search with a positive visit rate'
        }
        $passed = $true
    }
    catch {
        $failure = $_.Exception.Message
    }
    finally {
        if ($started -and -not $process.HasExited) {
            $process.Kill($true)
            $process.WaitForExit()
        }
        if ($stdoutTask) {
            $stdoutTask.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $resultsRoot ($variant.Name + '.stdout.txt')) -Encoding utf8
        }
        if ($stderrTask) {
            $stderrTask.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $resultsRoot ($variant.Name + '.stderr.txt')) -Encoding utf8
        }
        if ($process) { $process.Dispose() }
        $timer.Stop()
    }
    $status = if ($passed) { 'PASS' } else { 'FAIL' }
    Write-Host "$($variant.Name): $status $failure"
    [pscustomobject]@{
        Profile = $variant.Name
        Passed = $passed
        TimedOut = $timedOut
        ExitCode = $exitCode
        DurationSeconds = [math]::Round($timer.Elapsed.TotalSeconds, 3)
        Error = $failure
    }
}
@($results) | ConvertTo-Json -Depth 4 -AsArray | Set-Content -LiteralPath (Join-Path $resultsRoot 'summary.json') -Encoding utf8
Write-Host "Saved test results to $resultsRoot"
if (@($results | Where-Object { -not $_.Passed }).Count -gt 0) { exit 1 }
exit 0
