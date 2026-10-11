[CmdletBinding()]
param(
    [string]$MavenPath = 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd',
    [int]$StartupTimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$taskRunId = 'auth-it-' + [Guid]::NewGuid().ToString('N')
$taskMysql = 'learnhub-' + $taskRunId + '-mysql'
$taskRedis = 'learnhub-' + $taskRunId + '-redis'
$taskContainers = @($taskMysql, $taskRedis)
$taskFixtureDirectory = Join-Path $taskRoot ('.local\' + $taskRunId)
$taskFixturePath = Join-Path $taskFixtureDirectory 'schema.sql'
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
    # The repository schema names the business database; rewrite only its two
    # database directives in an ignored copy for this disposable database.
    $taskSchemaContent = Get-Content -LiteralPath (Join-Path $taskRoot 'sql\schema.sql') -Raw
    if (-not $taskSchemaContent.Contains('CREATE DATABASE IF NOT EXISTS learnhub ') -or
        -not $taskSchemaContent.Contains('USE learnhub;')) { throw 'Unexpected schema database directives' }
    $taskSchemaContent = $taskSchemaContent.Replace('CREATE DATABASE IF NOT EXISTS learnhub ',
        'CREATE DATABASE IF NOT EXISTS learnhub_auth_it ').Replace('USE learnhub;', 'USE learnhub_auth_it;')
    New-Item -ItemType Directory -Path $taskFixtureDirectory | Out-Null
    [IO.File]::WriteAllText($taskFixturePath, $taskSchemaContent, [Text.UTF8Encoding]::new($false))
    $taskSchema = $taskFixturePath.Replace('\', '/')
    # Only disposable test containers use these demonstration credentials.
    Invoke-CheckedDocker -DockerArguments @('run', '--detach', '--pull', 'never', '--name', $taskMysql,
        '--label', "learnhub.auth-it-run=$taskRunId", '--publish', '127.0.0.1::3306',
        '--env', 'MYSQL_ROOT_PASSWORD=learnhub-auth-it', '--env', 'MYSQL_DATABASE=learnhub_auth_it',
        '--env', 'MYSQL_USER=learnhub', '--env', 'MYSQL_PASSWORD=learnhub',
        '--mount', "type=bind,source=$taskSchema,target=/docker-entrypoint-initdb.d/01-schema.sql,readonly",
        '--tmpfs', '/var/lib/mysql:rw,nosuid,size=512m', 'mysql:8.4') | Out-Null
    Invoke-CheckedDocker -DockerArguments @('run', '--detach', '--pull', 'never', '--name', $taskRedis,
        '--label', "learnhub.auth-it-run=$taskRunId", '--publish', '127.0.0.1::6379',
        'redis:7.4-alpine', 'redis-server', '--appendonly', 'no') | Out-Null

    $taskDeadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    do {
        & docker exec $taskMysql mysql --user=learnhub --password=learnhub --database=learnhub_auth_it `
            --execute='SELECT 1 FROM user LIMIT 0; SELECT 1 FROM user_role LIMIT 0; SELECT 1 FROM media_asset LIMIT 0;' *> $null
        $taskMysqlReady = $LASTEXITCODE -eq 0
        $taskRedisReply = & docker exec $taskRedis redis-cli ping 2>$null
        $taskRedisReady = $LASTEXITCODE -eq 0 -and $taskRedisReply -eq 'PONG'
        if ($taskMysqlReady -and $taskRedisReady) { break }
        if ((Get-Date) -ge $taskDeadline) { throw 'Isolated MySQL/Redis startup timed out' }
        Start-Sleep -Seconds 2
    } while ($true)

    $taskMysqlPort = Get-PublishedPort $taskMysql '3306/tcp'
    $taskRedisPort = Get-PublishedPort $taskRedis '6379/tcp'
    Write-Host "Isolated test infrastructure ready: MySQL=$taskMysqlPort Redis=$taskRedisPort"
    Push-Location -LiteralPath $taskRoot
    try {
        $taskMavenStartedUtc = [DateTime]::UtcNow
        & $MavenPath '-pl' 'learnhub-server' '-am' '-Dtest=AuthInfrastructureIntegrationTest' `
            '-Dsurefire.failIfNoSpecifiedTests=false' '-Dlearnhub.auth.it.enabled=true' `
            "-Dlearnhub.auth.it.mysql-port=$taskMysqlPort" "-Dlearnhub.auth.it.redis-port=$taskRedisPort" test
        $taskExitCode = $LASTEXITCODE
        if ($taskExitCode -eq 0) {
            $taskReportPath = Join-Path $taskRoot 'learnhub-server\target\surefire-reports\TEST-com.learnhub.auth.security.AuthInfrastructureIntegrationTest.xml'
            $taskReportFile = Get-Item -LiteralPath $taskReportPath
            if ($taskReportFile.LastWriteTimeUtc -lt $taskMavenStartedUtc) { throw 'Integration test report is stale' }
            [xml]$taskReport = Get-Content -LiteralPath $taskReportPath -Raw
            if ([int]$taskReport.testsuite.tests -lt 5 -or [int]$taskReport.testsuite.skipped -ne 0 -or
                [int]$taskReport.testsuite.errors -ne 0 -or [int]$taskReport.testsuite.failures -ne 0) {
                throw 'Integration tests did not execute all required scenarios successfully'
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
                    '{{ index .Config.Labels "learnhub.auth-it-run" }}', $taskContainer)
                if ($taskLabel -ne $taskRunId) { throw 'Refusing to remove container with mismatched ownership label' }
                Invoke-CheckedDocker -DockerArguments @('rm', '--force', '--volumes', $taskContainer) | Out-Null
            } elseif ($taskPresent.Count -ne 0) {
                throw 'Unexpected container lookup result during cleanup'
            }
        } catch { $taskCleanupErrors += $_ }
    }
    if (Test-Path -LiteralPath $taskFixturePath) { Remove-Item -LiteralPath $taskFixturePath }
    if (Test-Path -LiteralPath $taskFixtureDirectory) { Remove-Item -LiteralPath $taskFixtureDirectory }
    if ($taskCleanupErrors.Count -gt 0) { throw ($taskCleanupErrors -join [Environment]::NewLine) }
    Write-Host 'Isolated test containers removed; existing containers and volumes were not modified.'
}
exit $taskExitCode

