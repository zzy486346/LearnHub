[CmdletBinding()]
param(
    [string]$MavenPath = 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd',
    [int]$StartupTimeoutSeconds = 120,
    [switch]$SkipMaven
)

$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$taskRunId = 'permission-holdout-' + [Guid]::NewGuid().ToString('N')
$taskContainer = 'learnhub-' + $taskRunId + '-mysql'
$taskRootPassword = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$taskLabelName = 'learnhub.permission-holdout-run'
$taskExitCode = 1
$taskEnvironmentNames = @(
    'LEARNHUB_PERMISSION_HOLDOUT_MYSQL_ADMIN_USERNAME',
    'LEARNHUB_PERMISSION_HOLDOUT_MYSQL_ADMIN_PASSWORD',
    'LEARNHUB_PERMISSION_HOLDOUT_MYSQL_HOST',
    'LEARNHUB_PERMISSION_HOLDOUT_MYSQL_PORT'
)
$taskOriginalEnvironment = @{}

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
    if (-not $taskMatch.Success) { throw 'Permission holdout MySQL must bind only to loopback' }
    return [int]$taskMatch.Groups[1].Value
}

foreach ($taskName in $taskEnvironmentNames) {
    $taskOriginalEnvironment[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process')
}

try {
    if (-not $SkipMaven -and -not (Test-Path -LiteralPath $MavenPath -PathType Leaf)) {
        throw 'Maven executable not found'
    }
    Invoke-CheckedDocker -DockerArguments @('info', '--format', '{{.ServerVersion}}') | Out-Null
    Invoke-CheckedDocker -DockerArguments @(
        'run', '--detach', '--pull', 'never', '--name', $taskContainer,
        '--label', "$taskLabelName=$taskRunId", '--publish', '127.0.0.1::3306',
        '--env', "MYSQL_ROOT_PASSWORD=$taskRootPassword", '--env', 'MYSQL_ROOT_HOST=%',
        '--tmpfs', '/var/lib/mysql:rw,nosuid,size=512m', 'mysql:8.4'
    ) | Out-Null

    $taskDeadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    do {
        & docker exec $taskContainer mysqladmin --host=127.0.0.1 --user=root `
            "--password=$taskRootPassword" ping --silent *> $null
        if ($LASTEXITCODE -eq 0) { break }
        if ((Get-Date) -ge $taskDeadline) { throw 'Isolated permission-holdout MySQL startup timed out' }
        Start-Sleep -Seconds 2
    } while ($true)

    $taskMysqlPort = Get-PublishedPort $taskContainer '3306/tcp'
    [Environment]::SetEnvironmentVariable('LEARNHUB_PERMISSION_HOLDOUT_MYSQL_ADMIN_USERNAME', 'root', 'Process')
    [Environment]::SetEnvironmentVariable('LEARNHUB_PERMISSION_HOLDOUT_MYSQL_ADMIN_PASSWORD', $taskRootPassword, 'Process')
    [Environment]::SetEnvironmentVariable('LEARNHUB_PERMISSION_HOLDOUT_MYSQL_HOST', '127.0.0.1', 'Process')
    [Environment]::SetEnvironmentVariable('LEARNHUB_PERMISSION_HOLDOUT_MYSQL_PORT', $taskMysqlPort.ToString(), 'Process')

    Write-Host "Permission holdout infrastructure ready: isolated MySQL port $taskMysqlPort; using existing Elasticsearch"
    if ($SkipMaven) {
        $taskExitCode = 0
    } else {
        Push-Location -LiteralPath $taskRoot
        try {
            $taskStartedUtc = [DateTime]::UtcNow
            & $MavenPath '-pl' 'learnhub-server' '-am' '-Dtest=AssistantPermissionHoldoutIntegrationTest' `
                '-Dsurefire.failIfNoSpecifiedTests=false' `
                '-Dlearnhub.assistant.permission-holdout.it.enabled=true' test
            $taskExitCode = $LASTEXITCODE
            if ($taskExitCode -eq 0) {
                $taskReportPath = Join-Path $taskRoot `
                    'learnhub-server\target\surefire-reports\TEST-com.learnhub.assistant.runtime.AssistantPermissionHoldoutIntegrationTest.xml'
                $taskReport = Get-Item -LiteralPath $taskReportPath
                if ($taskReport.LastWriteTimeUtc -lt $taskStartedUtc) { throw 'Permission holdout test report is stale' }
                [xml]$taskSuite = Get-Content -LiteralPath $taskReportPath -Raw
                if ([int]$taskSuite.testsuite.tests -ne 4 -or [int]$taskSuite.testsuite.skipped -ne 0 -or
                    [int]$taskSuite.testsuite.errors -ne 0 -or [int]$taskSuite.testsuite.failures -ne 0) {
                    throw 'RET-013~016 permission holdout scenarios did not all pass'
                }
                $taskEvidence = Join-Path $taskRoot '.local\assistant-permission-holdout.json'
                if (-not (Test-Path -LiteralPath $taskEvidence -PathType Leaf)) {
                    throw 'Permission holdout evidence JSON was not produced'
                }
                $taskJson = Get-Content -LiteralPath $taskEvidence -Raw | ConvertFrom-Json
                if ($taskJson.executed -ne 4 -or $taskJson.passed -ne 4 -or $taskJson.modelCalls -ne 0) {
                    throw 'Permission holdout evidence counts are invalid'
                }
            }
        } finally {
            Pop-Location
        }
    }
} finally {
    $taskCleanupErrors = @()
    foreach ($taskName in $taskEnvironmentNames) {
        try { [Environment]::SetEnvironmentVariable($taskName, $taskOriginalEnvironment[$taskName], 'Process') }
        catch { $taskCleanupErrors += $_ }
    }
    try {
        $taskPresent = @(Invoke-CheckedDocker -DockerArguments @(
            'ps', '--all', '--filter', ('name=^/' + $taskContainer + '$'), '--format', '{{.Names}}'))
        if ($taskPresent -contains $taskContainer) {
            $taskLabel = Invoke-CheckedDocker -DockerArguments @(
                'inspect', '--format', ('{{ index .Config.Labels "' + $taskLabelName + '" }}'), $taskContainer)
            if ($taskLabel -ne $taskRunId) { throw 'Refusing to remove MySQL container with mismatched ownership label' }
            Invoke-CheckedDocker -DockerArguments @('rm', '--force', '--volumes', $taskContainer) | Out-Null
        } elseif ($taskPresent.Count -ne 0) {
            throw 'Unexpected permission-holdout container lookup result during cleanup'
        }
    } catch { $taskCleanupErrors += $_ }
    $taskRootPassword = $null
    if ($taskCleanupErrors.Count -gt 0) { throw ($taskCleanupErrors -join [Environment]::NewLine) }
    Write-Host 'Isolated permission-holdout MySQL removed; existing Elasticsearch and other containers were not modified.'
}
exit $taskExitCode
