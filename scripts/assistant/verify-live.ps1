[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ApiKeyFile,
    [ValidateSet('Minimal', 'Smoke', 'Holdout')][string]$Phase = 'Minimal',
    [string]$MavenPath = 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd'
)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$taskLogDirectory = Join-Path $taskRoot '.local'
New-Item -ItemType Directory -Path $taskLogDirectory -Force | Out-Null
$taskLog = Join-Path $taskLogDirectory ('assistant-live-' + $Phase.ToLowerInvariant() + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
if (-not (Test-Path -LiteralPath $MavenPath -PathType Leaf)) { throw 'Maven executable not found' }
$taskReader = [IO.File]::OpenText((Resolve-Path -LiteralPath $ApiKeyFile).Path)
try { $taskFirstLine = $taskReader.ReadLine() } finally { $taskReader.Dispose() }
if ($null -eq $taskFirstLine) { throw 'API key file first line is empty' }
$taskMatch = [regex]::Match($taskFirstLine.Trim(), '^(?:ds\s*[:：]\s*)?(sk-[A-Za-z0-9_-]+)$', 'IgnoreCase')
if (-not $taskMatch.Success) { throw 'API key first line format unsupported; credential value expected' }
$taskCredential = $taskMatch.Groups[1].Value
$taskPreviousKey = [Environment]::GetEnvironmentVariable('LEARNHUB_DEEPSEEK_API_KEY', 'Process')
$taskArguments = @('-pl', 'learnhub-server', '-am', '-Dsurefire.failIfNoSpecifiedTests=false')
if ($Phase -eq 'Minimal') {
    $taskArguments += @('-Dtest=DeepSeekLiveSmokeTest', '-Dlearnhub.assistant.deepseek.smoke=true')
} else {
    $taskArguments += @('-Dtest=AssistantLiveAcceptanceIntegrationTest', '-Dlearnhub.assistant.live.acceptance=true')
    if ($Phase -eq 'Smoke') { $taskArguments += '-Dlearnhub.assistant.live.smoke=true' }
    else { $taskArguments += '-Dlearnhub.assistant.live.holdout=true' }
}
$taskArguments += 'test'
$taskExit = 1
Push-Location -LiteralPath $taskRoot
try {
    [Environment]::SetEnvironmentVariable('LEARNHUB_DEEPSEEK_API_KEY', $taskCredential, 'Process')
    & $MavenPath @taskArguments *> $taskLog
    $taskExit = $LASTEXITCODE
} finally {
    [Environment]::SetEnvironmentVariable('LEARNHUB_DEEPSEEK_API_KEY', $taskPreviousKey, 'Process')
    if (Test-Path -LiteralPath $taskLog) {
        $taskText = [IO.File]::ReadAllText($taskLog)
        [IO.File]::WriteAllText($taskLog, $taskText.Replace($taskCredential, '[REDACTED]'))
        $taskText = $null
    }
    $taskCredential = $null; $taskMatch = $null; $taskFirstLine = $null
    Pop-Location
}
Write-Host "Live verification phase=$Phase exit=$taskExit; log=$taskLog"
if ($taskExit -ne 0) { throw 'Live verification failed; inspect sanitized log. No automatic retry was performed.' }
