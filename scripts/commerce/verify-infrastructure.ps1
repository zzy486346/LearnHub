[CmdletBinding()]
param([string]$MavenPath = 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd')

$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$taskRunId = 'commerce-it-' + [Guid]::NewGuid().ToString('N')
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
    $taskMigration = Get-Content -LiteralPath (Join-Path $taskRoot 'sql\migrations\V20261012__commerce_closure.sql') -Raw
    $taskFreshSchema = $taskSchema.Replace('CREATE DATABASE IF NOT EXISTS learnhub ', 'CREATE DATABASE IF NOT EXISTS learnhub_commerce_it ').Replace('USE learnhub;', 'USE learnhub_commerce_it;')
    $taskFreshMigration = $taskMigration.Replace('USE learnhub;', 'USE learnhub_commerce_it;')
    $taskLegacyMigration = $taskMigration.Replace('USE learnhub;', 'USE learnhub_commerce_legacy;')
    $taskLegacySchema = @"
CREATE DATABASE IF NOT EXISTS learnhub_commerce_legacy CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE learnhub_commerce_legacy;
CREATE TABLE course_order (
    id BIGINT PRIMARY KEY COMMENT '主键编号', order_no VARCHAR(64) NOT NULL COMMENT '课程订单业务编号，全局唯一',
    user_id BIGINT NOT NULL COMMENT '用户编号，关联用户表', course_id BIGINT NOT NULL COMMENT '课程编号，关联课程表',
    course_title VARCHAR(160) NOT NULL COMMENT '下单时的课程标题快照',
    original_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '订单原始金额，单位为人民币元',
    paid_amount DECIMAL(12,2) NULL COMMENT '订单实付金额，单位为人民币元；未支付时为空',
    status VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'PAID' COMMENT '课程订单状态：PENDING待支付，PAID已支付',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    paid_at DATETIME(3) NULL COMMENT '订单支付完成时间，精确到毫秒',
    UNIQUE KEY uk_course_order_no (order_no), UNIQUE KEY uk_course_order_user_course (user_id, course_id),
    KEY idx_course_order_course_status (course_id, status)
) COMMENT='课程购买订单表';
CREATE TABLE coupon_claim (
    id BIGINT PRIMARY KEY COMMENT '主键编号', coupon_id BIGINT NOT NULL COMMENT '优惠券活动编号，关联优惠券表',
    user_id BIGINT NOT NULL COMMENT '用户编号，关联用户表',
    status VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'USED' COMMENT '领取券状态：AVAILABLE可用、USED已使用、EXPIRED已过期；到期状态可按有效期计算',
    claimed_at DATETIME(3) NOT NULL COMMENT '优惠券领取时间，精确到毫秒',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_claim_coupon_user (coupon_id, user_id)
) COMMENT='用户优惠券领取记录表';
INSERT INTO course_order (id, order_no, user_id, course_id, course_title, original_amount, paid_amount, status, created_at, updated_at, paid_at) VALUES
 (91001, 'LEGACY-PENDING', 1, 1, '历史待支付课程', 100.00, NULL, 'PENDING', '2020-01-01 01:00:00.000', '2020-01-02 01:00:00.000', NULL),
 (91002, 'LEGACY-PAID', 2, 2, '历史已支付课程', 100.00, 0.00, 'PAID', '2021-01-01 01:00:00.000', '2021-01-02 01:00:00.000', '2021-01-02 00:00:00.000');
INSERT INTO coupon_claim (id, coupon_id, user_id, status, claimed_at) VALUES (92001, 1, 1, 'AVAILABLE', NOW(3));
"@
    $taskSql = $taskFreshSchema + "`n" + $taskFreshMigration + "`n" + $taskFreshMigration + "`n" +
        $taskLegacySchema + "`n" + $taskLegacyMigration + "`n" + $taskLegacyMigration
    [IO.File]::WriteAllText($taskSchemaPath, $taskSql, [Text.UTF8Encoding]::new($false))
    $taskMountPath = $taskSchemaPath.Replace('\', '/')
    Invoke-CheckedDocker -DockerArguments @('run', '--detach', '--pull', 'never', '--name', $taskContainer,
        '--label', "learnhub.commerce-it-run=$taskRunId", '--publish', '127.0.0.1::3306',
        '--env', 'MYSQL_ROOT_PASSWORD=learnhub-commerce-it', '--env', 'MYSQL_DATABASE=learnhub_commerce_it',
        '--env', 'MYSQL_USER=learnhub', '--env', 'MYSQL_PASSWORD=learnhub',
        '--tmpfs', '/var/lib/mysql:rw,nosuid,size=512m', '--mount',
        "type=bind,source=$taskMountPath,target=/docker-entrypoint-initdb.d/01-schema.sql,readonly", 'mysql:8.4') | Out-Null
    $taskDeadline = (Get-Date).AddSeconds(120)
    do {
        & docker exec $taskContainer mysql --user=learnhub --password=learnhub --database=learnhub_commerce_it `
            --execute='SELECT coupon_claim_id,payable_amount,payment_mode,expires_at FROM course_order LIMIT 0; SELECT reserved_order_id,used_order_id FROM coupon_claim LIMIT 0;' *> $null
        if ($LASTEXITCODE -eq 0) { break }
        if ((Get-Date) -ge $taskDeadline) { throw 'Isolated MySQL startup timed out' }
        Start-Sleep -Seconds 2
    } while ($true)

    $taskLegacy = Invoke-CheckedDocker -DockerArguments @('exec', $taskContainer, 'mysql', '--user=root',
        '--password=learnhub-commerce-it', '-N', '-B', '--database=learnhub_commerce_legacy', '--execute',
        "SELECT CONCAT(id,'|',status,'|',payable_amount,'|',payment_mode,'|',IF(expires_at IS NULL,'NULL','SET'),'|',DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f')) FROM course_order ORDER BY id; SELECT CONCAT(status,'|',IFNULL(reserved_order_id,'NULL'),'|',IFNULL(used_order_id,'NULL')) FROM coupon_claim WHERE id=92001; SELECT CONCAT(TABLE_NAME,'|',COLUMN_TYPE,'|',IS_NULLABLE,'|',COLUMN_DEFAULT,'|',CHARACTER_SET_NAME,'|',COLLATION_NAME,'|',EXTRA) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='learnhub_commerce_legacy' AND COLUMN_NAME='status' AND TABLE_NAME IN ('course_order','coupon_claim') ORDER BY TABLE_NAME; SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='learnhub_commerce_legacy' AND TABLE_NAME='course_order' AND INDEX_NAME='idx_course_order_coupon_claim';")
    $taskLegacyText = $taskLegacy -join "`n"
    foreach ($taskExpected in @('91001|PENDING|100.00|DISABLED|SET|2020-01-02 01:00:00.000000',
            '91002|PAID|100.00|LEGACY_DEMO|NULL|2021-01-02 01:00:00.000000',
            'AVAILABLE|NULL|NULL', 'coupon_claim|varchar(20)|NO|USED|utf8mb4|utf8mb4_unicode_ci|',
            'course_order|varchar(20)|NO|PAID|utf8mb4|utf8mb4_bin|', '1')) {
        if (-not $taskLegacyText.Contains($taskExpected)) {
            throw "Commerce migration validation failed, missing [$taskExpected]: $taskLegacyText"
        }
    }

    $taskBinding = Invoke-CheckedDocker -DockerArguments @('port', $taskContainer, '3306/tcp')
    $taskPort = [regex]::Match(($taskBinding -join ''), '^127\.0\.0\.1:(\d+)$')
    if (-not $taskPort.Success) { throw 'Test database must bind only to loopback' }
    Push-Location -LiteralPath $taskRoot
    try {
        $taskStartedUtc = [DateTime]::UtcNow
        & $MavenPath '-pl' 'learnhub-server' '-am' '-Dtest=CommerceInfrastructureIntegrationTest' `
            '-Dsurefire.failIfNoSpecifiedTests=false' '-Dlearnhub.commerce.it.enabled=true' `
            "-Dlearnhub.commerce.it.mysql-port=$($taskPort.Groups[1].Value)" test
        $taskExitCode = $LASTEXITCODE
        if ($taskExitCode -eq 0) {
            $taskReportPath = Join-Path $taskRoot 'learnhub-server\target\surefire-reports\TEST-com.learnhub.order.CommerceInfrastructureIntegrationTest.xml'
            if ((Get-Item -LiteralPath $taskReportPath).LastWriteTimeUtc -lt $taskStartedUtc) { throw 'Integration test report is stale' }
            [xml]$taskReport = Get-Content -LiteralPath $taskReportPath -Raw
            if ([int]$taskReport.testsuite.tests -ne 9 -or [int]$taskReport.testsuite.skipped -ne 0 -or
                [int]$taskReport.testsuite.errors -ne 0 -or [int]$taskReport.testsuite.failures -ne 0) {
                throw 'Required commerce integration scenarios did not all pass'
            }
        }
    } finally { Pop-Location }
} finally {
    try {
        $taskPresent = @(Invoke-CheckedDocker -DockerArguments @('ps', '--all', '--filter', ('name=^/' + $taskContainer + '$'), '--format', '{{.Names}}'))
        if ($taskPresent -contains $taskContainer) {
            $taskLabel = Invoke-CheckedDocker -DockerArguments @('inspect', '--format', '{{ index .Config.Labels "learnhub.commerce-it-run" }}', $taskContainer)
            if ($taskLabel -ne $taskRunId) { throw 'Refusing cleanup with mismatched ownership label' }
            Invoke-CheckedDocker -DockerArguments @('rm', '--force', '--volumes', $taskContainer) | Out-Null
        }
    } finally {
        if (Test-Path -LiteralPath $taskSchemaPath) { Remove-Item -LiteralPath $taskSchemaPath }
        if (Test-Path -LiteralPath $taskFixtureDirectory) { Remove-Item -LiteralPath $taskFixtureDirectory }
    }
}
exit $taskExitCode
