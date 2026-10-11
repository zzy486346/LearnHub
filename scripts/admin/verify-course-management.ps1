[CmdletBinding()]
param([string]$MavenPath = 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd')

$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$taskRunId = 'admin-course-it-' + [Guid]::NewGuid().ToString('N')
$taskContainer = 'learnhub-' + $taskRunId
$taskFixtureDirectory = Join-Path $taskRoot ('.local\' + $taskRunId)
$taskSchemaPath = Join-Path $taskFixtureDirectory 'schema.sql'
$taskExitCode = 1

function Invoke-CheckedDocker {
    param([string[]]$DockerArguments)
    $taskOutput = & docker @DockerArguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed: $($taskOutput -join [Environment]::NewLine)" }
    return $taskOutput
}

try {
    Invoke-CheckedDocker -DockerArguments @('info', '--format', '{{.ServerVersion}}') | Out-Null
    New-Item -ItemType Directory -Path $taskFixtureDirectory | Out-Null
    $taskSchema = Get-Content -LiteralPath (Join-Path $taskRoot 'sql\schema.sql') -Raw
    $taskMigration = Get-Content -LiteralPath (Join-Path $taskRoot 'sql\migrations\V20261011__course_lesson_soft_delete.sql') -Raw
    # 新安装库使用正式schema；升级库仅移除本次新增列，再执行正式迁移两次。
    $taskDeletedLine = "    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0正常，1已删除',"
    $taskLessonTable = [regex]::Match($taskSchema, '(?ms)^CREATE TABLE IF NOT EXISTS course_lesson \(.*?^\) COMMENT=[^\r\n]+;')
    if (-not $taskLessonTable.Success -or [regex]::Matches($taskLessonTable.Value, [regex]::Escape($taskDeletedLine)).Count -ne 1) { throw 'Unexpected lesson soft-delete schema definition' }
    $taskFreshSchema = $taskSchema.Replace('CREATE DATABASE IF NOT EXISTS learnhub ', 'CREATE DATABASE IF NOT EXISTS learnhub_admin_course_fresh ').Replace('USE learnhub;', 'USE learnhub_admin_course_fresh;')
    $taskLegacySchema = $taskSchema.Replace($taskLessonTable.Value, $taskLessonTable.Value.Replace($taskDeletedLine, ''))
    $taskSql = ($taskFreshSchema + "`n" + $taskLegacySchema + "`n" + $taskMigration + "`n" + $taskMigration)
    $taskSql = $taskSql.Replace('CREATE DATABASE IF NOT EXISTS learnhub ', 'CREATE DATABASE IF NOT EXISTS learnhub_admin_course_it ').Replace('USE learnhub;', 'USE learnhub_admin_course_it;')
    [IO.File]::WriteAllText($taskSchemaPath, $taskSql, [Text.UTF8Encoding]::new($false))
    $taskMountPath = $taskSchemaPath.Replace('\', '/')
    Invoke-CheckedDocker -DockerArguments @('run', '--detach', '--pull', 'never', '--name', $taskContainer,
        '--label', "learnhub.admin-course-it-run=$taskRunId", '--publish', '127.0.0.1::3306',
        '--env', 'MYSQL_ROOT_PASSWORD=learnhub-admin-it', '--env', 'MYSQL_DATABASE=learnhub_admin_course_it',
        '--env', 'MYSQL_USER=learnhub', '--env', 'MYSQL_PASSWORD=learnhub',
        '--tmpfs', '/var/lib/mysql:rw,nosuid,size=512m', '--mount',
        "type=bind,source=$taskMountPath,target=/docker-entrypoint-initdb.d/01-schema.sql,readonly", 'mysql:8.4') | Out-Null
    $taskDeadline = (Get-Date).AddSeconds(120)
    do {
        & docker exec $taskContainer mysql --host=127.0.0.1 --user=learnhub --password=learnhub --database=learnhub_admin_course_it `
            --execute='SELECT deleted FROM course_lesson LIMIT 0; SELECT 1 FROM media_asset LIMIT 0;' *> $null
        if ($LASTEXITCODE -eq 0) { break }
        if ((Get-Date) -ge $taskDeadline) { throw 'Isolated MySQL startup timed out' }
        Start-Sleep -Seconds 2
    } while ($true)
    $taskFreshColumn = Invoke-CheckedDocker -DockerArguments @('exec', $taskContainer,
        'mysql', '--user=root', '--password=learnhub-admin-it', '-N', '-B',
        '--execute', "SELECT CONCAT(COLUMN_TYPE,'|',IS_NULLABLE,'|',COLUMN_DEFAULT,'|',HEX(COLUMN_COMMENT)) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='learnhub_admin_course_fresh' AND TABLE_NAME='course_lesson' AND COLUMN_NAME='deleted';")
    $taskCommentHex = -join ([Text.Encoding]::UTF8.GetBytes('逻辑删除标记：0正常，1已删除') | ForEach-Object { $_.ToString('X2') })
    if (-not (($taskFreshColumn -join "`n").Contains('tinyint|NO|0|' + $taskCommentHex))) { throw "Fresh schema soft-delete column validation failed: $($taskFreshColumn -join ' ')" }
    $taskBinding = Invoke-CheckedDocker -DockerArguments @('port', $taskContainer, '3306/tcp')
    $taskPort = [regex]::Match(($taskBinding -join ''), '^127\.0\.0\.1:(\d+)$')
    if (-not $taskPort.Success) { throw 'Test database must bind only to loopback' }
    Push-Location -LiteralPath $taskRoot
    try {
        $taskStartedUtc = [DateTime]::UtcNow
        & $MavenPath '-pl' 'learnhub-server' '-am' '-Dtest=AdminCourseInfrastructureIntegrationTest' `
            '-Dsurefire.failIfNoSpecifiedTests=false' '-Dlearnhub.admin.course.it.enabled=true' `
            "-Dlearnhub.admin.course.it.mysql-port=$($taskPort.Groups[1].Value)" test
        $taskExitCode = $LASTEXITCODE
        if ($taskExitCode -eq 0) {
            $taskReportPath = Join-Path $taskRoot 'learnhub-server\target\surefire-reports\TEST-com.learnhub.course.service.AdminCourseInfrastructureIntegrationTest.xml'
            if ((Get-Item -LiteralPath $taskReportPath).LastWriteTimeUtc -lt $taskStartedUtc) { throw 'Integration test report is stale' }
            [xml]$taskReport = Get-Content -LiteralPath $taskReportPath -Raw
            if ([int]$taskReport.testsuite.tests -lt 3 -or [int]$taskReport.testsuite.skipped -ne 0 -or
                [int]$taskReport.testsuite.errors -ne 0 -or [int]$taskReport.testsuite.failures -ne 0) { throw 'Required integration scenarios did not all pass' }
        }
    } finally { Pop-Location }
} finally {
    try {
        $taskPresent = @(Invoke-CheckedDocker -DockerArguments @('ps', '--all', '--filter', ('name=^/' + $taskContainer + '$'), '--format', '{{.Names}}'))
        if ($taskPresent -contains $taskContainer) {
            $taskLabel = Invoke-CheckedDocker -DockerArguments @('inspect', '--format', '{{ index .Config.Labels "learnhub.admin-course-it-run" }}', $taskContainer)
            if ($taskLabel -ne $taskRunId) { throw 'Refusing cleanup with mismatched ownership label' }
            Invoke-CheckedDocker -DockerArguments @('rm', '--force', '--volumes', $taskContainer) | Out-Null
        } elseif ($taskPresent.Count -ne 0) { throw 'Unexpected container lookup result' }
    } finally {
        if (Test-Path -LiteralPath $taskSchemaPath) { Remove-Item -LiteralPath $taskSchemaPath }
        if (Test-Path -LiteralPath $taskFixtureDirectory) { Remove-Item -LiteralPath $taskFixtureDirectory }
    }
}
exit $taskExitCode
