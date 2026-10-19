[CmdletBinding()]
param(
    [string]$MavenPath = 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd',
    [switch]$HistoryFixture
)

$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$taskOutput = Join-Path $taskRoot '.local\langchain-phase3-live.log'
$taskPreviousKey = [Environment]::GetEnvironmentVariable('LEARNHUB_LANGCHAIN_LIVE_KEY', 'Process')
$taskKey = [Environment]::GetEnvironmentVariable('LEARNHUB_DEEPSEEK_API_KEY', 'Process')
if ([string]::IsNullOrWhiteSpace($taskKey)) {
    $taskKey = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'Process')
}
if ([string]::IsNullOrWhiteSpace($taskKey)) {
    $taskConfigPath = Join-Path $taskRoot '.env'
    if (Test-Path -LiteralPath $taskConfigPath -PathType Leaf) {
        foreach ($taskLine in [IO.File]::ReadLines($taskConfigPath)) {
            if ($taskLine -match '^\s*(?:LEARNHUB_DEEPSEEK_API_KEY|DEEPSEEK_API_KEY)\s*=(.*)$') {
                $taskCandidate = $Matches[1].Trim().Trim([char[]]@('"', "'"))
                if (-not [string]::IsNullOrWhiteSpace($taskCandidate)) { $taskKey = $taskCandidate; break }
            }
        }
    }
}
if ([string]::IsNullOrWhiteSpace($taskKey) -or $taskKey -notmatch '^[A-Za-z0-9_-]{8,256}$') {
    throw '未找到格式有效的本地凭据；继续使用 HTTP Mock，不执行真实模型请求。'
}
if (-not (Test-Path -LiteralPath $MavenPath -PathType Leaf)) { throw 'Maven executable not found' }
[IO.Directory]::CreateDirectory((Split-Path -Parent $taskOutput)) | Out-Null

try {
    [Environment]::SetEnvironmentVariable('LEARNHUB_LANGCHAIN_LIVE_KEY', $taskKey, 'Process')
    Push-Location -LiteralPath $taskRoot
    try {
        # Java测试持久台账先写预留再调用：累计最多3次、12000预留令牌，单次输出256（授权上限512）。
        # 不删除/重置台账，不自动重试；课程、授权和历史为受控fixture。
        & $MavenPath '-pl' 'learnhub-server' '-am' `
            '-Dtest=LangChainLiveSmokeIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' `
            '-Dlearnhub.langchain.live.enabled=true' `
            "-Dlearnhub.langchain.live.historyFixture=$($HistoryFixture.IsPresent.ToString().ToLowerInvariant())" `
            '-DargLine=-Djdk.net.URLClassPath.disableClassPathURLCheck=true' test *> $taskOutput
        $taskExit = $LASTEXITCODE
    } finally { Pop-Location }
    Write-Host "LangChain live smoke exit: $taskExit"
    $taskLedgerPath = Join-Path $taskRoot '.local\langchain-phase3-live-ledger.json'
    if (Test-Path -LiteralPath $taskLedgerPath -PathType Leaf) {
        $taskLedger = Get-Content -LiteralPath $taskLedgerPath -Raw | ConvertFrom-Json
        Write-Host "Cumulative reserved: $($taskLedger.calls) calls / $($taskLedger.reservedTokens) tokens"
        foreach ($taskOutcome in $taskLedger.outcomes) {
            Write-Host "Step $($taskOutcome.step): $($taskOutcome.status), first delta $($taskOutcome.firstDeltaMillis) ms, batches $($taskOutcome.deltaBatches), usage unknown $($taskOutcome.usageUnknown)"
            if (-not $taskOutcome.usageUnknown -and $null -ne $taskOutcome.usage) {
                Write-Host "Actual usage: prompt $($taskOutcome.usage.actualPromptTokens), completion $($taskOutcome.usage.actualCompletionTokens), total $($taskOutcome.usage.actualTotalTokens)"
            }
        }
    }
    exit $taskExit
} finally {
    [Environment]::SetEnvironmentVariable('LEARNHUB_LANGCHAIN_LIVE_KEY', $taskPreviousKey, 'Process')
    $taskKey = $null
    $taskCandidate = $null
}
