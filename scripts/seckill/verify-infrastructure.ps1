[CmdletBinding()]
param(
    [string]$MavenPath = 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd',
    [int]$StartupTimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$taskRunId = 'seckill-it-' + [Guid]::NewGuid().ToString('N')
$taskMysql = 'learnhub-' + $taskRunId + '-mysql'
$taskRedis = 'learnhub-' + $taskRunId + '-redis'
$taskRabbit = 'learnhub-' + $taskRunId + '-rabbit'
$taskContainers = @($taskRabbit, $taskRedis, $taskMysql)
$taskFixtureDirectory = Join-Path $taskRoot ('.local\' + $taskRunId)
$taskSchemaPath = Join-Path $taskFixtureDirectory 'schema.sql'
$taskDatabase = 'learnhub_seckill_it'
$taskExitCode = 1

function Invoke-CheckedDocker {
    param([string[]]$DockerArguments)
    $taskOutput = & docker @DockerArguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed: $($taskOutput -join [Environment]::NewLine)" }
    return $taskOutput
}

function Get-PublishedPort {
    param([string]$Container, [string]$ContainerPort)
    $taskBinding = Invoke-CheckedDocker -DockerArguments @('port', $Container, $ContainerPort)
    $taskMatch = [regex]::Match(($taskBinding -join ''), '^127\.0\.0\.1:(\d+)$')
    if (-not $taskMatch.Success) { throw 'Test container must bind only to loopback' }
    return [int]$taskMatch.Groups[1].Value
}

try {
    if (-not (Test-Path -LiteralPath $MavenPath -PathType Leaf)) { throw 'Maven executable not found' }
    Invoke-CheckedDocker -DockerArguments @('info', '--format', '{{.ServerVersion}}') | Out-Null

    $taskSchema = Get-Content -LiteralPath (Join-Path $taskRoot 'sql\schema.sql') -Raw
    if (-not $taskSchema.Contains('CREATE DATABASE IF NOT EXISTS learnhub ') -or
        -not $taskSchema.Contains('USE learnhub;')) { throw 'Unexpected schema database directives' }
    $taskSchema = $taskSchema.Replace('CREATE DATABASE IF NOT EXISTS learnhub ',
        "CREATE DATABASE IF NOT EXISTS $taskDatabase ").Replace('USE learnhub;', "USE $taskDatabase;")
    New-Item -ItemType Directory -Path $taskFixtureDirectory | Out-Null
    [IO.File]::WriteAllText($taskSchemaPath, $taskSchema, [Text.UTF8Encoding]::new($false))
    $taskSchemaMount = $taskSchemaPath.Replace('\', '/')

    Invoke-CheckedDocker -DockerArguments @('run', '--detach', '--pull', 'never', '--name', $taskMysql,
        '--label', "learnhub.seckill-it-run=$taskRunId", '--publish', '127.0.0.1::3306',
        '--env', 'MYSQL_ROOT_PASSWORD=learnhub-seckill-it', '--env', "MYSQL_DATABASE=$taskDatabase",
        '--env', 'MYSQL_USER=learnhub', '--env', 'MYSQL_PASSWORD=learnhub',
        '--mount', "type=bind,source=$taskSchemaMount,target=/docker-entrypoint-initdb.d/01-schema.sql,readonly",
        '--tmpfs', '/var/lib/mysql:rw,nosuid,size=512m', 'mysql:8.4') | Out-Null
    Invoke-CheckedDocker -DockerArguments @('run', '--detach', '--pull', 'never', '--name', $taskRedis,
        '--label', "learnhub.seckill-it-run=$taskRunId", '--publish', '127.0.0.1::6379',
        'redis:7.4-alpine', 'redis-server', '--appendonly', 'no') | Out-Null
    Invoke-CheckedDocker -DockerArguments @('run', '--detach', '--pull', 'never', '--name', $taskRabbit,
        '--label', "learnhub.seckill-it-run=$taskRunId", '--publish', '127.0.0.1::5672',
        '--env', 'RABBITMQ_DEFAULT_USER=learnhub', '--env', 'RABBITMQ_DEFAULT_PASS=learnhub',
        '--env', 'RABBITMQ_ERLANG_COOKIE=learnhub-seckill-it-cookie',
        '--env', 'HOME=/tmp/rabbitmq', '--env', 'RABBITMQ_MNESIA_BASE=/tmp/rabbitmq/mnesia',
        '--env', 'RABBITMQ_LOG_BASE=/tmp/rabbitmq/log', '--user', '0',
        '--entrypoint', 'sh', 'rabbitmq:3-management', '-c',
        'mkdir -p /tmp/rabbitmq && chown rabbitmq:rabbitmq /tmp/rabbitmq && exec gosu rabbitmq /usr/local/bin/docker-entrypoint.sh rabbitmq-server') | Out-Null

    $taskDeadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    do {
        & docker exec $taskMysql mysql --user=learnhub --password=learnhub --database=$taskDatabase `
            --execute='SELECT 1 FROM coupon LIMIT 0; SELECT 1 FROM seckill_order LIMIT 0; SELECT 1 FROM mq_consume_log LIMIT 0;' *> $null
        $taskMysqlReady = $LASTEXITCODE -eq 0
        $taskRedisReply = & docker exec $taskRedis redis-cli ping 2>$null
        $taskRedisReady = $LASTEXITCODE -eq 0 -and $taskRedisReply -eq 'PONG'
        & docker exec $taskRabbit rabbitmq-diagnostics -q ping *> $null
        $taskRabbitReady = $LASTEXITCODE -eq 0
        if ($taskMysqlReady -and $taskRedisReady -and $taskRabbitReady) { break }
        $taskRabbitState = (Invoke-CheckedDocker -DockerArguments @(
            'inspect', '--format', '{{.State.Status}}', $taskRabbit) | Select-Object -First 1)
        if ($taskRabbitState -in @('exited', 'dead')) {
            $taskRabbitLogs = & docker logs $taskRabbit --tail 30 2>&1
            throw "Isolated RabbitMQ exited during startup: $($taskRabbitLogs -join [Environment]::NewLine)"
        }
        if ((Get-Date) -ge $taskDeadline) { throw 'Isolated MySQL/Redis/RabbitMQ startup timed out' }
        Start-Sleep -Seconds 2
    } while ($true)

    $taskMysqlPort = Get-PublishedPort $taskMysql '3306/tcp'
    $taskRedisPort = Get-PublishedPort $taskRedis '6379/tcp'
    $taskRabbitPort = Get-PublishedPort $taskRabbit '5672/tcp'
    Write-Host "Isolated seckill infrastructure ready: MySQL=$taskMysqlPort Redis=$taskRedisPort RabbitMQ=$taskRabbitPort"

    Push-Location -LiteralPath $taskRoot
    try {
        $taskMavenStartedUtc = [DateTime]::UtcNow
        & $MavenPath '-pl' 'learnhub-server' '-am' '-Dtest=SeckillInfrastructureIntegrationTest' `
            '-Dsurefire.failIfNoSpecifiedTests=false' '-Dlearnhub.it.enabled=true' `
            "-Dlearnhub.seckill.it.mysql-port=$taskMysqlPort" `
            "-Dlearnhub.seckill.it.mysql-database=$taskDatabase" `
            "-Dlearnhub.seckill.it.redis-port=$taskRedisPort" `
            "-Dlearnhub.seckill.it.rabbit-port=$taskRabbitPort" test
        $taskExitCode = $LASTEXITCODE
        if ($taskExitCode -eq 0) {
            $taskReportPath = Join-Path $taskRoot 'learnhub-server\target\surefire-reports\TEST-com.learnhub.marketing.coupon.SeckillInfrastructureIntegrationTest.xml'
            $taskReportFile = Get-Item -LiteralPath $taskReportPath
            if ($taskReportFile.LastWriteTimeUtc -lt $taskMavenStartedUtc) { throw 'Integration test report is stale' }
            [xml]$taskReport = Get-Content -LiteralPath $taskReportPath -Raw
            if ([int]$taskReport.testsuite.tests -lt 2 -or [int]$taskReport.testsuite.skipped -ne 0 -or
                [int]$taskReport.testsuite.errors -ne 0 -or [int]$taskReport.testsuite.failures -ne 0) {
                throw 'Seckill integration tests did not execute all required scenarios successfully'
            }
        }
    } finally { Pop-Location }
} finally {
    $taskCleanupErrors = @()
    foreach ($taskContainer in $taskContainers) {
        try {
            $taskPresent = @(Invoke-CheckedDocker -DockerArguments @('ps', '--all', '--filter',
                ('name=^/' + $taskContainer + '$'), '--format', '{{.Names}}'))
            if ($taskPresent -contains $taskContainer) {
                $taskLabel = Invoke-CheckedDocker -DockerArguments @('inspect', '--format',
                    '{{ index .Config.Labels "learnhub.seckill-it-run" }}', $taskContainer)
                if ($taskLabel -ne $taskRunId) { throw 'Refusing to remove container with mismatched ownership label' }
                Invoke-CheckedDocker -DockerArguments @('rm', '--force', '--volumes', $taskContainer) | Out-Null
            } elseif ($taskPresent.Count -ne 0) {
                throw 'Unexpected container lookup result during cleanup'
            }
        } catch { $taskCleanupErrors += $_ }
    }
    if (Test-Path -LiteralPath $taskSchemaPath) { Remove-Item -LiteralPath $taskSchemaPath }
    if (Test-Path -LiteralPath $taskFixtureDirectory) { Remove-Item -LiteralPath $taskFixtureDirectory }
    if ($taskCleanupErrors.Count -gt 0) { throw ($taskCleanupErrors -join [Environment]::NewLine) }
    Write-Host 'Isolated seckill test containers removed; existing containers and volumes were not modified.'
}
exit $taskExitCode
