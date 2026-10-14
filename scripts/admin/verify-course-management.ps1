[CmdletBinding()]
param(
    [string]$MavenPath = 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd',
    [switch]$SkipMaven
)

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
    $taskManagementMigration = Get-Content -LiteralPath (Join-Path $taskRoot 'sql\migrations\V20261012__course_management.sql') -Raw
    # 新安装库使用正式schema；升级库仅移除本次新增列，再执行正式迁移两次。
    $taskDeletedLine = "    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0正常，1已删除',"
    $taskCoverLine = "    cover_media_id BIGINT COMMENT '课程封面媒体资源编号，关联媒体资源表',"
    $taskCoverIndexLine = '    KEY idx_course_cover_media (cover_media_id)'
    $taskCategoryNameIndexLine = '    UNIQUE KEY uk_course_category_name (name)'
    $taskTagUpdatedLine = "    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',"
    $taskBindingCreatedLine = "    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',"
    $taskBindingUpdatedLine = "    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',"
    $taskLessonTable = [regex]::Match($taskSchema, '(?ms)^CREATE TABLE IF NOT EXISTS course_lesson \(.*?^\) COMMENT=[^\r\n]+;')
    if (-not $taskLessonTable.Success -or [regex]::Matches($taskLessonTable.Value, [regex]::Escape($taskDeletedLine)).Count -ne 1) { throw 'Unexpected lesson soft-delete schema definition' }
    $taskCourseTable = [regex]::Match($taskSchema, '(?ms)^CREATE TABLE IF NOT EXISTS course \(.*?^\) COMMENT=[^\r\n]+;')
    $taskCategoryTable = [regex]::Match($taskSchema, '(?ms)^CREATE TABLE IF NOT EXISTS course_category \(.*?^\) COMMENT=[^\r\n]+;')
    $taskTagTable = [regex]::Match($taskSchema, '(?ms)^CREATE TABLE IF NOT EXISTS tag \(.*?^\) COMMENT=[^\r\n]+;')
    $taskBindingTable = [regex]::Match($taskSchema, '(?ms)^CREATE TABLE IF NOT EXISTS course_tag \(.*?^\) COMMENT=[^\r\n]+;')
    if (-not $taskCourseTable.Success -or -not $taskCategoryTable.Success -or -not $taskTagTable.Success -or -not $taskBindingTable.Success) {
        throw 'Unexpected course taxonomy schema definition'
    }
    $taskFreshSchema = $taskSchema.Replace('CREATE DATABASE IF NOT EXISTS learnhub ', 'CREATE DATABASE IF NOT EXISTS learnhub_admin_course_fresh ').Replace('USE learnhub;', 'USE learnhub_admin_course_fresh;')
    $taskLegacySchema = $taskSchema.Replace($taskLessonTable.Value, $taskLessonTable.Value.Replace($taskDeletedLine, ''))
    $taskLegacyCourse = $taskCourseTable.Value.Replace($taskCoverLine + "`r`n", '').Replace($taskCoverLine + "`n", '')
    $taskLegacyCourse = $taskLegacyCourse.Replace("    KEY idx_course_category (category_id),`r`n" + $taskCoverIndexLine,
        '    KEY idx_course_category (category_id)').Replace("    KEY idx_course_category (category_id),`n" + $taskCoverIndexLine,
        '    KEY idx_course_category (category_id)')
    $taskLegacyTag = $taskTagTable.Value.Replace($taskTagUpdatedLine + "`r`n", '').Replace($taskTagUpdatedLine + "`n", '')
    $taskLegacyBinding = $taskBindingTable.Value.Replace($taskBindingCreatedLine + "`r`n", '').Replace($taskBindingCreatedLine + "`n", '')
    $taskLegacyBinding = $taskLegacyBinding.Replace($taskBindingUpdatedLine + "`r`n", '').Replace($taskBindingUpdatedLine + "`n", '')
    $taskLegacySchema = $taskLegacySchema.Replace($taskCourseTable.Value, $taskLegacyCourse)
    $taskLegacyCategory = $taskCategoryTable.Value.Replace(",`r`n" + $taskCategoryNameIndexLine, '').Replace(",`n" + $taskCategoryNameIndexLine, '')
    $taskLegacySchema = $taskLegacySchema.Replace($taskCategoryTable.Value, $taskLegacyCategory)
    $taskLegacySchema = $taskLegacySchema.Replace($taskTagTable.Value, $taskLegacyTag)
    $taskLegacySchema = $taskLegacySchema.Replace($taskBindingTable.Value, $taskLegacyBinding)
    $taskLegacyFixture = @"
INSERT INTO course_category (id, name, sort_order, status) VALUES (91001, '迁移验收分类', 1, 'ENABLED');
INSERT INTO course (id, category_id, title, cover_url, instructor, price, status, like_count)
VALUES (91002, 91001, '迁移验收课程', 'https://legacy.example/cover.png', '验收讲师', 9.90, 'DRAFT', 7);
INSERT INTO tag (id, name) VALUES (91003, '迁移验收标签');
INSERT INTO course_tag (course_id, tag_id) VALUES (91002, 91003);
"@
    $taskSql = ($taskFreshSchema + "`n" + $taskLegacySchema + "`n" + $taskLegacyFixture + "`n" + $taskMigration + "`n" + $taskMigration +
        "`n" + $taskManagementMigration + "`n" + $taskManagementMigration)
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
            --execute='SELECT deleted FROM course_lesson LIMIT 0; SELECT cover_media_id FROM course LIMIT 0; SELECT updated_at FROM tag LIMIT 0; SELECT created_at,updated_at FROM course_tag LIMIT 0; SELECT 1 FROM media_asset LIMIT 0;' *> $null
        if ($LASTEXITCODE -eq 0) { break }
        if ((Get-Date) -ge $taskDeadline) { throw 'Isolated MySQL startup timed out' }
        Start-Sleep -Seconds 2
    } while ($true)
    $taskFreshColumn = Invoke-CheckedDocker -DockerArguments @('exec', $taskContainer,
        'mysql', '--user=root', '--password=learnhub-admin-it', '-N', '-B',
        '--execute', "SELECT CONCAT(COLUMN_TYPE,'|',IS_NULLABLE,'|',COLUMN_DEFAULT,'|',HEX(COLUMN_COMMENT)) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='learnhub_admin_course_fresh' AND TABLE_NAME='course_lesson' AND COLUMN_NAME='deleted';")
    $taskCommentHex = -join ([Text.Encoding]::UTF8.GetBytes('逻辑删除标记：0正常，1已删除') | ForEach-Object { $_.ToString('X2') })
    if (-not (($taskFreshColumn -join "`n").Contains('tinyint|NO|0|' + $taskCommentHex))) { throw "Fresh schema soft-delete column validation failed: $($taskFreshColumn -join ' ')" }
    $taskManagementColumns = Invoke-CheckedDocker -DockerArguments @('exec', $taskContainer,
        'mysql', '--user=root', '--password=learnhub-admin-it', '-N', '-B',
        '--execute', "SELECT CONCAT_WS('|',TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COALESCE(COLUMN_DEFAULT,'NULL'),EXTRA,HEX(COLUMN_COMMENT)) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='learnhub_admin_course_it' AND ((TABLE_NAME='course' AND COLUMN_NAME='cover_media_id') OR (TABLE_NAME='tag' AND COLUMN_NAME='updated_at') OR (TABLE_NAME='course_tag' AND COLUMN_NAME IN ('created_at','updated_at'))) ORDER BY TABLE_NAME,COLUMN_NAME; SELECT CONCAT('cover-index|',COUNT(*)) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='learnhub_admin_course_it' AND TABLE_NAME='course' AND INDEX_NAME='idx_course_cover_media'; SELECT CONCAT('category-name-index|',COUNT(*)) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='learnhub_admin_course_it' AND TABLE_NAME='course_category' AND INDEX_NAME='uk_course_category_name' AND NON_UNIQUE=0; SELECT CONCAT(id,'|',category_id,'|',HEX(title),'|',cover_url,'|',HEX(instructor),'|',price,'|',status,'|',like_count) FROM learnhub_admin_course_it.course WHERE id=91002; SELECT CONCAT(course_id,'|',tag_id) FROM learnhub_admin_course_it.course_tag WHERE course_id=91002 AND tag_id=91003; SELECT HEX(name) FROM learnhub_admin_course_it.tag WHERE id=91003;")
    $taskCoverCommentHex = -join ([Text.Encoding]::UTF8.GetBytes('课程封面媒体资源编号，关联媒体资源表') | ForEach-Object { $_.ToString('X2') })
    $taskCreatedCommentHex = -join ([Text.Encoding]::UTF8.GetBytes('创建时间，精确到毫秒') | ForEach-Object { $_.ToString('X2') })
    $taskUpdatedCommentHex = -join ([Text.Encoding]::UTF8.GetBytes('更新时间，记录修改时自动更新，精确到毫秒') | ForEach-Object { $_.ToString('X2') })
    $taskFixtureTitleHex = -join ([Text.Encoding]::UTF8.GetBytes('迁移验收课程') | ForEach-Object { $_.ToString('X2') })
    $taskFixtureInstructorHex = -join ([Text.Encoding]::UTF8.GetBytes('验收讲师') | ForEach-Object { $_.ToString('X2') })
    $taskFixtureTagHex = -join ([Text.Encoding]::UTF8.GetBytes('迁移验收标签') | ForEach-Object { $_.ToString('X2') })
    $taskManagementText = $taskManagementColumns -join "`n"
    foreach ($taskExpected in @(
        "course|cover_media_id|bigint|YES|NULL||$taskCoverCommentHex",
        "course_tag|created_at|datetime(3)|NO|CURRENT_TIMESTAMP(3)|DEFAULT_GENERATED|$taskCreatedCommentHex",
        "course_tag|updated_at|datetime(3)|NO|CURRENT_TIMESTAMP(3)|DEFAULT_GENERATED on update CURRENT_TIMESTAMP(3)|$taskUpdatedCommentHex",
        "tag|updated_at|datetime(3)|NO|CURRENT_TIMESTAMP(3)|DEFAULT_GENERATED on update CURRENT_TIMESTAMP(3)|$taskUpdatedCommentHex",
        'cover-index|1',
        'category-name-index|1',
        "91002|91001|$taskFixtureTitleHex|https://legacy.example/cover.png|$taskFixtureInstructorHex|9.90|DRAFT|7",
        '91002|91003',
        $taskFixtureTagHex)) {
        if (-not $taskManagementText.Contains($taskExpected)) {
            throw "Course management migration validation failed, missing [$taskExpected]: $taskManagementText"
        }
    }
    # 迁移保真校验完成后移除业务夹具，仅保留启用分类供集成测试创建课程。
    Invoke-CheckedDocker -DockerArguments @('exec', $taskContainer,
        'mysql', '--user=root', '--password=learnhub-admin-it',
        '--execute', "DELETE FROM learnhub_admin_course_it.course_tag WHERE course_id=91002 AND tag_id=91003; DELETE FROM learnhub_admin_course_it.course WHERE id=91002; DELETE FROM learnhub_admin_course_it.tag WHERE id=91003;") | Out-Null
    $taskBinding = Invoke-CheckedDocker -DockerArguments @('port', $taskContainer, '3306/tcp')
    $taskPort = [regex]::Match(($taskBinding -join ''), '^127\.0\.0\.1:(\d+)$')
    if (-not $taskPort.Success) { throw 'Test database must bind only to loopback' }
    if ($SkipMaven) {
        $taskExitCode = 0
    } else {
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
                if ([int]$taskReport.testsuite.tests -lt 4 -or [int]$taskReport.testsuite.skipped -ne 0 -or
                    [int]$taskReport.testsuite.errors -ne 0 -or [int]$taskReport.testsuite.failures -ne 0) { throw 'Required integration scenarios did not all pass' }
            }
        } finally { Pop-Location }
    }
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
