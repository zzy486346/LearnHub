[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskCompose = Join-Path $taskRoot 'services/mem0/compose.yml'
$taskProject = 'learnhub-memory-smoke-' + [Guid]::NewGuid().ToString('N')
$taskNames = @('LEARNHUB_MEMORY_PG_ADMIN_PASSWORD','LEARNHUB_MEMORY_PG_PASSWORD',
    'LEARNHUB_MEMORY_EMBEDDING_TOKEN','LEARNHUB_ASSISTANT_MEMORY_SERVICE_TOKEN','LEARNHUB_MEMORY_LOCAL_PORT')
$taskPrevious = @{}
$taskHeaders = @{}
$taskStarted = $false
$taskExit = 1
try {
    foreach ($taskName in $taskNames) {
        $taskPrevious[$taskName] = [Environment]::GetEnvironmentVariable($taskName,'Process')
        $taskValue = if ($taskName -eq 'LEARNHUB_MEMORY_LOCAL_PORT') { '0' } else { [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N') }
        [Environment]::SetEnvironmentVariable($taskName,$taskValue,'Process')
    }
    $taskStarted = $true
    & docker compose -p $taskProject -f $taskCompose up -d --wait --wait-timeout 120
    if ($LASTEXITCODE -ne 0) { throw 'Owned Compose startup failed' }
    $taskBinding = (& docker compose -p $taskProject -f $taskCompose port memory-loopback 8080) -join ''
    if ($LASTEXITCODE -ne 0 -or $taskBinding -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'Compose endpoint must be loopback only' }
    $taskPort = [int]$Matches[1]
    $taskBase = 'http://' + $taskBinding
    $taskHeaders = @{Authorization='Bearer '+$env:LEARNHUB_ASSISTANT_MEMORY_SERVICE_TOKEN}
    $taskHealth = Invoke-RestMethod -Uri ($taskBase+'/health') -Headers $taskHeaders -TimeoutSec 10
    if ($taskHealth.status -ne 'UP') { throw 'Compose service is not ready' }
    $taskId = [Guid]::NewGuid().ToString()
    $taskPut = @{ownerKey=('a'*64);version=1;category='STYLE';text='喜欢先看Java例子'} | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Put -Uri ($taskBase+'/preferences/'+$taskId) -Headers $taskHeaders -ContentType 'application/json' -Body $taskPut -TimeoutSec 15 | Out-Null
    $taskSearch = @{ownerKey=('a'*64);query='我喜欢怎样的讲解方式';limit=3} | ConvertTo-Json -Compress
    $taskHits = Invoke-RestMethod -Method Post -Uri ($taskBase+'/preferences/search') -Headers $taskHeaders -ContentType 'application/json' -Body $taskSearch -TimeoutSec 15
    if (@($taskHits.items).Count -ne 1 -or $taskHits.items[0].preferenceId -ne $taskId) { throw 'Compose real preference recall failed' }
    $taskDelete = @{ownerKey=('a'*64);version=2} | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Delete -Uri ($taskBase+'/preferences/'+$taskId) -Headers $taskHeaders -ContentType 'application/json' -Body $taskDelete -TimeoutSec 10 | Out-Null
    $taskStats = Invoke-RestMethod -Uri ($taskBase+'/stats') -Headers $taskHeaders -TimeoutSec 10
    if ($taskStats.llmAttempts -ne 0) { throw 'Compose must never call an LLM' }
    Write-Host 'Actual Compose initialization, DML account, read-only containers, persistent state permissions, Linux embedding and preference recall passed; LLM attempts 0.'
    $taskExit = 0
} catch {
    Write-Host 'Owned Compose smoke failed; credentials and HTTP payloads suppressed.'
} finally {
  try {
    if ($taskStarted) {
        foreach ($taskKind in @('container','volume','network')) {
            $taskListArgs = @($taskKind,'ls')
            if ($taskKind -eq 'container') { $taskListArgs += '-a' }
            $taskListFormat = if ($taskKind -eq 'volume') { '{{.Name}}' } else { '{{.ID}}' }
            $taskResourceNames = & docker @taskListArgs --filter "label=com.docker.compose.project=$taskProject" --format $taskListFormat
            if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect owned Compose resources before cleanup' }
            foreach ($taskResource in $taskResourceNames) {
                $taskFormat = if ($taskKind -eq 'container') { '{{ index .Config.Labels "com.docker.compose.project" }}' } else { '{{ index .Labels "com.docker.compose.project" }}' }
                $taskOwner = (& docker $taskKind inspect --format $taskFormat $taskResource) -join ''
                if ($LASTEXITCODE -ne 0 -or $taskOwner -ne $taskProject) { throw 'Compose resource ownership mismatch; cleanup refused' }
            }
        }
        & docker compose -p $taskProject -f $taskCompose down --volumes --remove-orphans
        if ($LASTEXITCODE -ne 0) { $taskExit=1; Write-Host 'Owned Compose cleanup failed' }
        foreach ($taskKind in @('container','volume','network')) {
            $taskListArgs = @($taskKind,'ls')
            if ($taskKind -eq 'container') { $taskListArgs += '-a' }
            $taskRemaining = & docker @taskListArgs --filter "label=com.docker.compose.project=$taskProject" --quiet
            if ($LASTEXITCODE -ne 0 -or @($taskRemaining).Count -gt 0) { $taskExit=1; Write-Host 'Owned Compose resource removal cannot be confirmed' }
        }
        if ($taskPort) {
            $taskProbe = [Net.Sockets.TcpClient]::new()
            try {
                if ($taskProbe.ConnectAsync('127.0.0.1',$taskPort).Wait(1000) -and $taskProbe.Connected) { $taskExit=1; Write-Host 'Owned Compose port still accepts connections' }
            } catch { } finally { $taskProbe.Dispose() }
        }
    }
  } finally {
    foreach ($taskName in $taskPrevious.Keys) { [Environment]::SetEnvironmentVariable($taskName,$taskPrevious[$taskName],'Process') }
    $taskHeaders.Clear()
  }
}
exit $taskExit
