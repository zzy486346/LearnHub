[CmdletBinding()]
param(
    [string]$MemoryImage = 'learnhub-mem0-test:20261019',
    [string]$BridgeImage = 'learnhub-memory-bridge-test:20261019',
    [switch]$IncludeJavaClient,
    [string]$MavenPath = 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd'
)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$taskRun = 'memory-it-' + [Guid]::NewGuid().ToString('N')
$taskLabel = 'learnhub.memory-it'
$taskNetwork = 'learnhub-' + $taskRun
$taskIngressNetwork = $taskNetwork + '-ingress'
$taskPg = $taskNetwork + '-pg'
$taskBridge = $taskNetwork + '-bridge'
$taskMemory = $taskNetwork + '-api'
$taskRelay = $taskNetwork + '-relay'
$taskInit = $taskNetwork + '-init'
$taskPgVolume = $taskNetwork + '-pgdata'
$taskStateVolume = $taskNetwork + '-state'
$taskPgImage = 'pgvector/pgvector:0.8.7-pg16-bookworm@sha256:a97d77306ff47cc1d8add4d000cec2207da2e110746318df3137ce53c337ca30'
$taskRelayImage = 'python:3.12.13-slim-bookworm@sha256:6e13e65c55e33adf203d77ee371cf8bf5d81bd4902ef07565721f46bf44917af'
$taskAdminPassword = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$taskRuntimePassword = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$taskServiceToken = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$taskBridgeToken = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$taskOwnerA = 'a' * 64
$taskOwnerB = 'b' * 64
$taskPreferenceA = [Guid]::NewGuid().ToString()
$taskPreferenceB = [Guid]::NewGuid().ToString()
$taskStarted = [DateTime]::UtcNow
$taskStage = 'preconditions'
$taskExit = 1
$taskPreviousItUrl = $env:LEARNHUB_MEMORY_IT_BASE_URL
$taskPreviousItToken = $env:LEARNHUB_MEMORY_IT_TOKEN
$taskClient = [Net.Http.HttpClient]::new()
$taskClient.Timeout = [TimeSpan]::FromSeconds(20)
$taskSummary = [ordered]@{
    run = $taskRun; startedAt = $taskStarted.ToString('o'); passed = $false
    sdkVersion = $null; checks = @(); serviceStatistics = @(); embeddingStatistics = @()
    realSupplierCalls = 0; privateServicesExternalRouting = $false; relayIngressEgressAvailable = $true
    relayTarget = 'memory-api:8000'; relayForwardsExternalRequests = $false; cleanup = $false; failureStage = $null
}

function Invoke-MemoryDocker([string[]]$Arguments) {
    $taskDockerOutput = & docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        $script:taskDockerFailure = ($taskDockerOutput -join "`n")
        throw 'Owned memory test Docker operation failed'
    }
    return $taskDockerOutput
}

function Assert-Memory([bool]$Condition, [string]$Check) {
    if (-not $Condition) { throw ('Memory assertion failed: ' + $Check) }
    $taskSummary.checks += $Check
}

function Invoke-MemoryHttp([string]$Method, [string]$Path, $Body = $null,
                           [int]$ExpectedStatus = 200, [bool]$Authenticated = $true) {
    $taskRequest = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), $script:taskBaseUri + $Path)
    try {
        if ($Authenticated) { $taskRequest.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $taskServiceToken) }
        if ($null -ne $Body) {
            $taskJson = ConvertTo-Json -InputObject $Body -Compress -Depth 6
            $taskRequest.Content = [Net.Http.StringContent]::new($taskJson, [Text.Encoding]::UTF8, 'application/json')
        }
        $taskResponse = $taskClient.SendAsync($taskRequest).GetAwaiter().GetResult()
        try {
            if ([int]$taskResponse.StatusCode -ne $ExpectedStatus) { throw 'Memory HTTP status mismatch' }
            $taskBodyText = $taskResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if ($taskBodyText.Length -gt 65536) { throw 'Memory test HTTP response exceeded limit' }
            if ($taskBodyText.Length -gt 0) { return ConvertFrom-Json -InputObject $taskBodyText }
            return $null
        } finally { $taskResponse.Dispose() }
    } catch { throw 'Memory test HTTP request failed; response and credentials suppressed' }
    finally { $taskRequest.Dispose() }
}

function Wait-MemoryApi {
    $taskDeadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        try { $taskHealth = Invoke-MemoryHttp 'GET' '/health'; if ($taskHealth.status -eq 'UP') { return } }
        catch { }
        if ([DateTime]::UtcNow -gt $taskDeadline) { throw 'Owned memory REST startup timed out' }
        Start-Sleep -Seconds 1
    } while ($true)
}

function Read-MemoryBridge([string]$Path) {
    # 私网访问由已经运行的 Mem0 容器执行；token 从其环境读取，不输出或写入命令。
    $taskPython = @'
import json, os, urllib.request
base = os.environ['LEARNHUB_MEMORY_EMBEDDING_BASE_URL'].removesuffix('/v1')
request = urllib.request.Request(base + '__PATH__', headers={'Authorization': 'Bearer ' + os.environ['LEARNHUB_MEMORY_EMBEDDING_TOKEN']})
try:
    with urllib.request.urlopen(request, timeout=5) as response:
        print(json.dumps(json.load(response)))
except Exception:
    raise SystemExit(1)
'@
    $taskPython = $taskPython.Replace('__PATH__', $Path)
    $taskValue = (Invoke-MemoryDocker @('exec', $taskMemory, 'python', '-c', $taskPython)) -join "`n"
    return ConvertFrom-Json -InputObject $taskValue
}

function Wait-MemoryBridge {
    $taskDeadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        try { $taskHealth = Read-MemoryBridge '/health'; if ($taskHealth.status -eq 'ok') { return } }
        catch { }
        if ([DateTime]::UtcNow -gt $taskDeadline) { throw 'Owned BGE bridge startup timed out' }
        Start-Sleep -Seconds 1
    } while ($true)
}

function Save-MemoryStats {
    $taskStats = Invoke-MemoryHttp 'GET' '/stats'
    Assert-Memory ($taskStats.llmAttempts -eq 0) 'Mem0 LLM attempts remain zero'
    $taskSummary.serviceStatistics += $taskStats
}

try {
    foreach ($taskImage in @($MemoryImage, $BridgeImage, $taskPgImage, $taskRelayImage)) {
        Invoke-MemoryDocker @('image', 'inspect', $taskImage) | Out-Null
    }
    Invoke-MemoryDocker @('network', 'create', '--internal', '--label', "$taskLabel=$taskRun", $taskNetwork) | Out-Null
    Invoke-MemoryDocker @('network', 'create', '--label', "$taskLabel=$taskRun", $taskIngressNetwork) | Out-Null
    foreach ($taskVolume in @($taskPgVolume, $taskStateVolume)) {
        Invoke-MemoryDocker @('volume', 'create', '--label', "$taskLabel=$taskRun", $taskVolume) | Out-Null
    }
    $taskInternal = (Invoke-MemoryDocker @('network', 'inspect', '--format', '{{.Internal}}', $taskNetwork)) -join ''
    Assert-Memory ($taskInternal -eq 'true') 'Mem0/BGE/PG private Docker network is internal'
    $taskIngressInternal = (Invoke-MemoryDocker @('network', 'inspect', '--format', '{{.Internal}}', $taskIngressNetwork)) -join ''
    Assert-Memory ($taskIngressInternal -eq 'false') 'Fixed-target test relay has a separate ingress network with egress capability'
    $taskStage = 'postgres-startup'
    Invoke-MemoryDocker @('run', '--detach', '--pull', 'never', '--name', $taskPg, '--label', "$taskLabel=$taskRun",
        '--network', $taskNetwork, '--network-alias', 'memory-pg',
        '--mount', "type=volume,source=$taskPgVolume,target=/var/lib/postgresql/data",
        '--env', "POSTGRES_PASSWORD=$taskAdminPassword", '--env', 'POSTGRES_DB=learnhub_memory_it', $taskPgImage) | Out-Null
    $taskDeadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        & docker exec $taskPg pg_isready -h 127.0.0.1 -U postgres -d learnhub_memory_it *> $null
        if ($LASTEXITCODE -eq 0) { break }
        if ([DateTime]::UtcNow -gt $taskDeadline) { throw 'Owned PostgreSQL startup timed out' }
        Start-Sleep -Seconds 1
    } while ($true)
    $taskStage = 'postgres-schema-and-permissions'
    $taskSql = Get-Content -LiteralPath (Join-Path $taskRoot 'services/mem0/mem0_collection.sql') -Raw
    $taskSql += "`nREVOKE CREATE ON SCHEMA public FROM PUBLIC;`n"
    $taskSql += "CREATE ROLE memory_runtime LOGIN PASSWORD '$taskRuntimePassword';`n"
    $taskSql += "GRANT CONNECT ON DATABASE learnhub_memory_it TO memory_runtime;`n"
    $taskSql += "GRANT USAGE ON SCHEMA public TO memory_runtime;`n"
    $taskSql += "GRANT SELECT,INSERT,UPDATE,DELETE ON public.learning_preferences_bge512_v1 TO memory_runtime;`n"
    $taskSqlOutput = $taskSql | & docker exec -i $taskPg psql -X -v ON_ERROR_STOP=1 -U postgres -d learnhub_memory_it 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Owned PostgreSQL initialization failed; SQL output suppressed' }
    $taskSql = $null
    $taskSqlOutput = $null
    $taskRuntimeCheck = (Invoke-MemoryDocker @('exec', '--env', "PGPASSWORD=$taskRuntimePassword", $taskPg,
        'psql', '-X', '-tAc', "SELECT current_user,has_schema_privilege(current_user,'public','CREATE')",
        '-h', '127.0.0.1', '-U', 'memory_runtime', '-d', 'learnhub_memory_it')) -join ''
    Assert-Memory ($taskRuntimeCheck.Trim() -eq 'memory_runtime|f') 'Runtime account connects successfully and lacks schema CREATE privilege'
    $taskDdlOutput = & docker exec --env "PGPASSWORD=$taskRuntimePassword" $taskPg psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 `
        -U memory_runtime -d learnhub_memory_it -c 'CREATE TABLE public.memory_it_forbidden(id int)' 2>&1
    $taskDdlDenied = $LASTEXITCODE -ne 0
    $taskDdlOutput = $null
    Assert-Memory $taskDdlDenied 'Runtime database account cannot create tables'

    $taskStage = 'state-volume-ownership'
    Invoke-MemoryDocker @('run', '--rm', '--pull', 'never', '--name', $taskInit, '--label', "$taskLabel=$taskRun",
        '--network', 'none', '--user', '0:0', '--mount', "type=volume,source=$taskStateVolume,target=/data",
        '--entrypoint', 'python', $MemoryImage, '-c', 'import os; os.chown("/data",10001,10001); os.chmod("/data",0o700)') | Out-Null
    $taskStage = 'bridge-and-memory-startup'
    Invoke-MemoryDocker @('run', '--detach', '--pull', 'never', '--name', $taskBridge, '--label', "$taskLabel=$taskRun",
        '--network', $taskNetwork, '--network-alias', 'memory-bridge', '--memory', '1g', '--cpus', '2',
        '--env', 'LEARNHUB_MEMORY_EMBED_HOST=0.0.0.0', '--env', 'LEARNHUB_MEMORY_EMBED_PORT=8081',
        '--env', "LEARNHUB_MEMORY_EMBED_TOKEN=$taskBridgeToken", $BridgeImage) | Out-Null
    Invoke-MemoryDocker @('run', '--detach', '--pull', 'never', '--name', $taskMemory, '--label', "$taskLabel=$taskRun",
        '--network', $taskNetwork, '--network-alias', 'memory-api', '--memory', '512m', '--cpus', '2',
        '--mount', "type=volume,source=$taskStateVolume,target=/data",
        '--env', 'MEM0_DIR=/data/sdk', '--env', 'MEM0_TELEMETRY=false', '--env', 'LEARNHUB_MEMORY_DATA_DIR=/data',
        '--env', "LEARNHUB_MEMORY_SERVICE_TOKEN=$taskServiceToken",
        '--env', 'LEARNHUB_MEMORY_PG_HOST=memory-pg', '--env', 'LEARNHUB_MEMORY_PG_PORT=5432',
        '--env', 'LEARNHUB_MEMORY_PG_DATABASE=learnhub_memory_it', '--env', 'LEARNHUB_MEMORY_PG_USER=memory_runtime',
        '--env', "LEARNHUB_MEMORY_PG_PASSWORD=$taskRuntimePassword",
        '--env', 'LEARNHUB_MEMORY_EMBEDDING_BASE_URL=http://memory-bridge:8081/v1',
        '--env', "LEARNHUB_MEMORY_EMBEDDING_TOKEN=$taskBridgeToken", $MemoryImage) | Out-Null
    # 当前 Docker internal 网络不保留 host-port 映射。仅固定目标测试 relay 双网接入；
    # Mem0/PG/BGE 均保持只有 internal 网络，不向它们添加默认外网路由。
    $taskRelayPath = Join-Path $taskRoot 'services/mem0/test_relay.py'
    Invoke-MemoryDocker @('run', '--detach', '--pull', 'never', '--name', $taskRelay, '--label', "$taskLabel=$taskRun",
        '--network', $taskIngressNetwork, '--publish', '127.0.0.1::8080', '--memory', '128m', '--cpus', '1',
        '--read-only', '--user', '10001:10001', '--security-opt', 'no-new-privileges', '--cap-drop', 'ALL',
        '--mount', "type=bind,source=$taskRelayPath,target=/app/test_relay.py,readonly",
        '--env', 'MEMORY_RELAY_TARGET_HOST=memory-api', '--env', 'MEMORY_RELAY_TARGET_PORT=8000',
        $taskRelayImage, 'python', '-B', '/app/test_relay.py') | Out-Null
    Invoke-MemoryDocker @('network', 'connect', $taskNetwork, $taskRelay) | Out-Null
    $taskBinding = (Invoke-MemoryDocker @('port', $taskRelay, '8080/tcp')) -join ''
    if ($taskBinding -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'Mem0 fixed-target relay endpoint must bind loopback only' }
    $taskSummary.loopbackPort = [int]$Matches[1]
    $script:taskBaseUri = 'http://127.0.0.1:' + $Matches[1]
    foreach ($taskPrivateContainer in @($taskPg, $taskBridge, $taskMemory)) {
        $taskBindings = (Invoke-MemoryDocker @('inspect', '--format', '{{json .HostConfig.PortBindings}}', $taskPrivateContainer)) -join ''
        Assert-Memory ($taskBindings -in @('{}', 'null')) 'Mem0/PG/BGE has no published host ports'
        $taskPrivateNetworks = ConvertFrom-Json -InputObject ((Invoke-MemoryDocker @('inspect', '--format', '{{json .NetworkSettings.Networks}}', $taskPrivateContainer)) -join '')
        $taskPrivateNetworkNames = @($taskPrivateNetworks.PSObject.Properties.Name)
        Assert-Memory ($taskPrivateNetworkNames.Count -eq 1 -and $taskPrivateNetworkNames[0] -eq $taskNetwork) 'Private service is attached exclusively to internal network'
    }
    Wait-MemoryApi
    Wait-MemoryBridge
    $taskVersion = (Invoke-MemoryDocker @('exec', $taskMemory, 'python', '-c',
        'from importlib.metadata import version; print(version("mem0ai"))')) -join ''
    Assert-Memory ($taskVersion -eq '2.2.1') 'Actual installed Mem0 SDK is 2.2.1'
    $taskSummary.sdkVersion = $taskVersion
    Invoke-MemoryHttp 'GET' '/health' $null 401 $false | Out-Null
    Assert-Memory $true 'REST rejects missing service authentication'
    Write-Host ('Owned Mem0/BGE/PG ready; REST loopback port ' + $taskSummary.loopbackPort)

    $taskStage = 'real-crud-and-user-isolation'
    $taskBodyA1 = @{ ownerKey = $taskOwnerA; version = 1; category = 'PACE'; text = '我每晚只有二十分钟，喜欢先看例子' }
    $taskBodyB1 = @{ ownerKey = $taskOwnerB; version = 1; category = 'STYLE'; text = '我喜欢先读原理，再自己练习' }
    Invoke-MemoryHttp 'PUT' "/preferences/$taskPreferenceA" $taskBodyA1 | Out-Null
    Invoke-MemoryHttp 'PUT' "/preferences/$taskPreferenceB" $taskBodyB1 | Out-Null
    $taskAList = Invoke-MemoryHttp 'GET' "/preferences?ownerKey=$taskOwnerA&limit=50"
    $taskBList = Invoke-MemoryHttp 'GET' "/preferences?ownerKey=$taskOwnerB&limit=50"
    Assert-Memory (@($taskAList.items).Count -eq 1 -and $taskAList.items[0].preferenceId -eq $taskPreferenceA) 'A lists only its real SDK preference'
    Assert-Memory (@($taskBList.items).Count -eq 1 -and $taskBList.items[0].preferenceId -eq $taskPreferenceB) 'B lists only its real SDK preference'
    # 服务无 conversation_id：独立查询验证跨会话所需的持久召回；不冒充浏览器会话验收。
    $taskARecall = Invoke-MemoryHttp 'POST' '/preferences/search' @{ ownerKey = $taskOwnerA; query = '我每晚只有二十分钟，喜欢先看例子'; limit = 5 }
    $taskBRecall = Invoke-MemoryHttp 'POST' '/preferences/search' @{ ownerKey = $taskOwnerB; query = '我喜欢先读原理，再自己练习'; limit = 5 }
    Assert-Memory (@($taskARecall.items).Count -eq 1 -and $taskARecall.items[0].preferenceId -eq $taskPreferenceA) 'Independent A query recalls stored learning preference'
    Assert-Memory (@($taskBRecall.items).Count -eq 1 -and $taskBRecall.items[0].preferenceId -eq $taskPreferenceB) 'Search filters isolate A and B'
    Invoke-MemoryHttp 'PUT' "/preferences/$taskPreferenceA" @{ ownerKey = $taskOwnerB; version = 2; category = 'PACE'; text = '禁止跨用户修改' } 404 | Out-Null
    Invoke-MemoryHttp 'DELETE' "/preferences/$taskPreferenceA" @{ ownerKey = $taskOwnerB; version = 2 } 404 | Out-Null
    Assert-Memory $true 'Foreign update and delete are rejected'

    $taskStage = 'version-update-and-idempotency'
    $taskBodyA2 = @{ ownerKey = $taskOwnerA; version = 2; category = 'PACE'; text = '我每晚学习十分钟，先看代码例子' }
    Invoke-MemoryHttp 'PUT' "/preferences/$taskPreferenceA" $taskBodyA2 | Out-Null
    $taskBeforeRepeat = Invoke-MemoryHttp 'GET' '/stats'
    Invoke-MemoryHttp 'PUT' "/preferences/$taskPreferenceA" $taskBodyA2 | Out-Null
    $taskAfterRepeat = Invoke-MemoryHttp 'GET' '/stats'
    Assert-Memory ($taskAfterRepeat.sdkAdds -eq $taskBeforeRepeat.sdkAdds) 'Identical version retry does not add another SDK memory'
    Invoke-MemoryHttp 'PUT' "/preferences/$taskPreferenceA" $taskBodyA1 409 | Out-Null
    Invoke-MemoryHttp 'PUT' "/preferences/$taskPreferenceA" @{ ownerKey = $taskOwnerA; version = 2; category = 'PACE'; text = '同版本的其他正文' } 409 | Out-Null
    $taskUpdated = Invoke-MemoryHttp 'GET' "/preferences?ownerKey=$taskOwnerA&limit=50"
    Assert-Memory (@($taskUpdated.items).Count -eq 1 -and $taskUpdated.items[0].version -eq 2 -and $taskUpdated.items[0].text -eq $taskBodyA2.text) 'New version replaces old preference and stale/conflicting writes fail'
    Save-MemoryStats

    $taskStage = 'memory-restart-and-durable-journal'
    Invoke-MemoryDocker @('restart', '--time', '5', $taskMemory) | Out-Null
    Wait-MemoryApi
    $taskRestarted = Invoke-MemoryHttp 'GET' "/preferences?ownerKey=$taskOwnerA&limit=50"
    Assert-Memory (@($taskRestarted.items).Count -eq 1 -and $taskRestarted.items[0].version -eq 2) 'Mem0 process restart preserves active journal and SDK history'
    Invoke-MemoryHttp 'PUT' "/preferences/$taskPreferenceA" $taskBodyA2 | Out-Null
    $taskRestartStats = Invoke-MemoryHttp 'GET' '/stats'
    Assert-Memory ($taskRestartStats.sdkAdds -eq 0) 'Persistent journal prevents duplicate SDK add after restart'
    Invoke-MemoryHttp 'PUT' "/preferences/$taskPreferenceA" $taskBodyA1 409 | Out-Null

    $taskStage = 'forget-and-tombstone-restart'
    $taskDelete = @{ ownerKey = $taskOwnerA; version = 3 }
    Invoke-MemoryHttp 'DELETE' "/preferences/$taskPreferenceA" $taskDelete | Out-Null
    Invoke-MemoryHttp 'DELETE' "/preferences/$taskPreferenceA" $taskDelete | Out-Null
    $taskForgotten = Invoke-MemoryHttp 'POST' '/preferences/search' @{ ownerKey = $taskOwnerA; query = $taskBodyA2.text; limit = 5 }
    Assert-Memory (@($taskForgotten.items).Count -eq 0) 'Forgotten preference is absent from real vector recall'
    Save-MemoryStats
    Invoke-MemoryDocker @('restart', '--time', '5', $taskMemory) | Out-Null
    Wait-MemoryApi
    Invoke-MemoryHttp 'PUT' "/preferences/$taskPreferenceA" $taskBodyA1 409 | Out-Null
    Invoke-MemoryHttp 'PUT' "/preferences/$taskPreferenceA" @{ ownerKey = $taskOwnerA; version = 4; category = 'PACE'; text = '不得复活已删除标识' } 409 | Out-Null
    $taskAfterForgetRestart = Invoke-MemoryHttp 'GET' "/preferences?ownerKey=$taskOwnerA&limit=50"
    Assert-Memory (@($taskAfterForgetRestart.items).Count -eq 0) 'Tombstone survives restart and rejects late/new writes to deleted UUID'
    $taskStillB = Invoke-MemoryHttp 'GET' "/preferences?ownerKey=$taskOwnerB&limit=50"
    Assert-Memory (@($taskStillB.items).Count -eq 1) 'A forgetting does not delete B preference'

    $taskStage = 'real-pg-vector-and-no-ddl'
    $taskDims = (Invoke-MemoryDocker @('exec', $taskPg, 'psql', '-X', '-tAc',
        'SELECT count(*),min(vector_dims(vector)),max(vector_dims(vector)) FROM public.learning_preferences_bge512_v1',
        '-U', 'postgres', '-d', 'learnhub_memory_it')) -join ''
    Assert-Memory ($taskDims.Trim() -eq '1|512|512') 'Real PostgreSQL contains only surviving B memory with actual 512D vectors'
    $taskForbidden = (Invoke-MemoryDocker @('exec', $taskPg, 'psql', '-X', '-tAc',
        "SELECT count(*) FROM information_schema.tables WHERE table_name='memory_it_forbidden'",
        '-U', 'postgres', '-d', 'learnhub_memory_it')) -join ''
    Assert-Memory ($taskForbidden.Trim() -eq '0') 'Denied DDL did not create any table'
    $taskSummary.embeddingStatistics += Read-MemoryBridge '/internal/stats'
    Assert-Memory ($taskSummary.embeddingStatistics[0].embedded_texts -gt 0 -and $taskSummary.embeddingStatistics[0].embedding_requests -gt 0) 'Linux Java BGE performed real local embedding'

    $taskStage = 'bridge-failure-and-recovery'
    Invoke-MemoryDocker @('stop', '--time', '5', $taskBridge) | Out-Null
    Invoke-MemoryHttp 'POST' '/preferences/search' @{ ownerKey = $taskOwnerB; query = $taskBodyB1.text; limit = 5 } 503 | Out-Null
    Assert-Memory $true 'Embedding outage returns explicit unavailable result without supplier fallback'
    Invoke-MemoryDocker @('start', $taskBridge) | Out-Null
    Wait-MemoryBridge
    $taskRecovered = Invoke-MemoryHttp 'POST' '/preferences/search' @{ ownerKey = $taskOwnerB; query = $taskBodyB1.text; limit = 5 }
    Assert-Memory (@($taskRecovered.items).Count -eq 1 -and $taskRecovered.items[0].preferenceId -eq $taskPreferenceB) 'Real local embedding recovers with B memory intact'
    if ($IncludeJavaClient) {
        $taskStage = 'java-real-service-client'
        $env:LEARNHUB_MEMORY_IT_BASE_URL = $script:taskBaseUri
        $env:LEARNHUB_MEMORY_IT_TOKEN = $taskServiceToken
        $taskJavaStarted = [DateTime]::UtcNow
        $taskJavaLog = Join-Path $taskRoot ('.local/' + $taskRun + '-java.log')
        [IO.Directory]::CreateDirectory((Join-Path $taskRoot '.local')) | Out-Null
        Push-Location -LiteralPath $taskRoot
        try {
            & $MavenPath '-pl' 'learnhub-server' '-am' '-Dtest=Mem0ServiceIntegrationTest' `
                '-Dsurefire.failIfNoSpecifiedTests=false' '-Dlearnhub.memory.service.it.enabled=true' `
                '-DargLine=-Djdk.net.URLClassPath.disableClassPathURLCheck=true' test *> $taskJavaLog
            if ($LASTEXITCODE -ne 0) { throw 'Java Mem0 service integration failed; inspect local test report' }
        } finally {
            Pop-Location
            $env:LEARNHUB_MEMORY_IT_BASE_URL = $taskPreviousItUrl
            $env:LEARNHUB_MEMORY_IT_TOKEN = $taskPreviousItToken
        }
        $taskJavaReports = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'learnhub-server/target/surefire-reports') `
            -Filter 'TEST-*Mem0ServiceIntegrationTest.xml')
        if ($taskJavaReports.Count -ne 1) { throw 'Required Java memory integration report missing or ambiguous' }
        [xml]$taskJavaSuite = Get-Content -LiteralPath $taskJavaReports[0].FullName -Raw
        Assert-Memory ($taskJavaReports[0].LastWriteTimeUtc -ge $taskJavaStarted -and
            [int]$taskJavaSuite.testsuite.tests -eq 1 -and [int]$taskJavaSuite.testsuite.skipped -eq 0 -and
            [int]$taskJavaSuite.testsuite.failures -eq 0 -and [int]$taskJavaSuite.testsuite.errors -eq 0) `
            'Java client real Mem0 integration executed exactly one fresh passing test without skips'
        $taskSummary.javaIntegration = @{ tests = 1; skipped = 0; failures = 0; errors = 0 }
    }
    $taskSummary.embeddingStatistics += Read-MemoryBridge '/internal/stats'
    Save-MemoryStats
    $taskSummary.passed = $true
    $taskExit = 0
} catch {
    $taskSummary.failureStage = $taskStage
    $taskDiagnostics = @($_.Exception.Message, $script:taskDockerFailure)
    foreach ($taskDiagnosticContainer in @($taskMemory, $taskBridge, $taskPg, $taskRelay)) {
        $taskDiagnosticOwner = & docker inspect --format '{{ index .Config.Labels "learnhub.memory-it" }}' $taskDiagnosticContainer 2>$null
        if ($LASTEXITCODE -eq 0 -and ($taskDiagnosticOwner -join '') -eq $taskRun) {
            $taskDiagnostics += (& docker logs --tail 80 $taskDiagnosticContainer 2>&1) -join "`n"
        }
    }
    $taskDiagnosticText = $taskDiagnostics -join "`n"
    foreach ($taskCredential in @($taskAdminPassword,$taskRuntimePassword,$taskServiceToken,$taskBridgeToken)) {
        $taskDiagnosticText = $taskDiagnosticText.Replace($taskCredential, '[REDACTED_SECRET]')
    }
    [IO.Directory]::CreateDirectory((Join-Path $taskRoot '.local')) | Out-Null
    $taskDiagnosticText | Set-Content -LiteralPath (Join-Path $taskRoot ('.local/' + $taskRun + '-diagnostics.log')) -Encoding utf8
    Write-Host ('Memory infrastructure verification failed at stage: ' + $taskStage + '; details suppressed to protect local credentials and payloads.')
} finally {
    $env:LEARNHUB_MEMORY_IT_BASE_URL = $taskPreviousItUrl
    $env:LEARNHUB_MEMORY_IT_TOKEN = $taskPreviousItToken
    $taskClient.Dispose()
    $taskCleanupErrors = @()
    $taskEngineCheck = & docker version --format '{{.Server.Version}}' 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $taskEngineCheck) {
        $taskCleanupErrors += 'Docker engine unavailable; owned resource removal cannot be confirmed'
    }
    foreach ($taskOwned in @(
        @{ Kind = 'container'; Name = $taskInit }, @{ Kind = 'container'; Name = $taskRelay }, @{ Kind = 'container'; Name = $taskMemory },
        @{ Kind = 'container'; Name = $taskBridge }, @{ Kind = 'container'; Name = $taskPg },
        @{ Kind = 'volume'; Name = $taskStateVolume }, @{ Kind = 'volume'; Name = $taskPgVolume },
        @{ Kind = 'network'; Name = $taskNetwork }, @{ Kind = 'network'; Name = $taskIngressNetwork })) {
        $taskKind = $taskOwned.Kind
        $taskName = $taskOwned.Name
        $taskFormat = if ($taskKind -eq 'container') { '{{ index .Config.Labels "learnhub.memory-it" }}' } else { '{{ index .Labels "learnhub.memory-it" }}' }
        $taskOwnership = & docker $taskKind inspect --format $taskFormat $taskName 2>$null
        if ($LASTEXITCODE -ne 0) { continue }
        if (($taskOwnership -join '') -ne $taskRun) { $taskCleanupErrors += ('Ownership mismatch: ' + $taskName); continue }
        if ($taskKind -eq 'container') { & docker container rm --force $taskName *> $null }
        elseif ($taskKind -eq 'volume') { & docker volume rm $taskName *> $null }
        else { & docker network rm $taskName *> $null }
        if ($LASTEXITCODE -ne 0) { $taskCleanupErrors += ('Cleanup failed: ' + $taskName); continue }
        & docker $taskKind inspect $taskName *> $null
        if ($LASTEXITCODE -eq 0) { $taskCleanupErrors += ('Owned resource remains: ' + $taskName) }
    }
    if ($taskSummary.Contains('loopbackPort') -and $taskCleanupErrors.Count -eq 0) {
        $taskProbe = [Net.Sockets.TcpClient]::new()
        try {
            $taskConnect = $taskProbe.ConnectAsync('127.0.0.1', [int]$taskSummary.loopbackPort)
            if ($taskConnect.Wait(1000) -and $taskProbe.Connected) {
                $taskCleanupErrors += 'Previously owned REST loopback port still accepts connections'
            }
        } catch { } # 拒绝连接表示测试监听口已释放。
        finally { $taskProbe.Dispose() }
    }
    $taskSummary.cleanup = $taskCleanupErrors.Count -eq 0
    if (-not $taskSummary.cleanup) {
        $taskSummary.passed = $false
        $taskSummary.cleanupErrors = $taskCleanupErrors
        $taskExit = 1
    }
    $taskSummary.completedAt = [DateTime]::UtcNow.ToString('o')
    $taskSummary.durationSeconds = [Math]::Round(([DateTime]::UtcNow - $taskStarted).TotalSeconds, 2)
    $taskSummaryPath = Join-Path $taskRoot ('.local/' + $taskRun + '.json')
    [IO.Directory]::CreateDirectory((Join-Path $taskRoot '.local')) | Out-Null
    $taskSummary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $taskSummaryPath -Encoding utf8
    $taskAdminPassword = $null; $taskRuntimePassword = $null; $taskServiceToken = $null; $taskBridgeToken = $null
    Write-Host ('Memory verification summary: ' + $taskSummaryPath)
    if ($taskSummary.cleanup) { Write-Host 'Owned Mem0/BGE/PG/relay containers, temporary volumes and both networks removed.' }
    else { Write-Host 'Owned resource cleanup failed; inspect summary ownership checks before retrying.' }
}
exit $taskExit
