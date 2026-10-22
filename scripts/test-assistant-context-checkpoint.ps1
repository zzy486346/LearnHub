param([string]$Maven = 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$label = 'learnhub-context-checkpoint-it'
$name = 'learnhub-context-c-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$password = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$container = $null
$settings = @('LEARNHUB_CHAT_IT_MYSQL_HOST','LEARNHUB_CHAT_IT_MYSQL_PORT','LEARNHUB_CHAT_IT_MYSQL_ADMIN_USERNAME','LEARNHUB_CHAT_IT_MYSQL_ADMIN_PASSWORD')
$previous = @{}
foreach($key in $settings) { $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
try {
    Push-Location $root
    # 仅缓存镜像、随机回环端口、无数据卷；不连接用户数据库。
    $container = (& docker run --detach --rm --pull=never --name $name --label "learnhub.test=$label" `
        --publish '127.0.0.1::3306' --env "MYSQL_ROOT_PASSWORD=$password" --env 'MYSQL_ROOT_HOST=%' mysql:8.4).Trim()
    if ($LASTEXITCODE -ne 0 -or $container -notmatch '^[a-f0-9]{64}$') { throw '隔离 MySQL 容器启动失败' }
    $details = (& docker inspect $container | ConvertFrom-Json)[0]
    $port = $details.NetworkSettings.Ports.'3306/tcp'[0].HostPort
    $ready = $false
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt 60) {
        & docker exec --env "MYSQL_PWD=$password" $container mysqladmin --connect-timeout=1 --user=root --host=127.0.0.1 ping --silent 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (!$ready) { throw '隔离 MySQL 在一分钟内未就绪' }
    $env:LEARNHUB_CHAT_IT_MYSQL_HOST = '127.0.0.1'
    $env:LEARNHUB_CHAT_IT_MYSQL_PORT = $port
    $env:LEARNHUB_CHAT_IT_MYSQL_ADMIN_USERNAME = 'root'
    $env:LEARNHUB_CHAT_IT_MYSQL_ADMIN_PASSWORD = $password
    New-Item -ItemType Directory -Path (Join-Path $root '.local') -Force | Out-Null
    $log = Join-Path $root '.local/context-redesign-c-mysql.log'
    & $Maven -pl learnhub-server -am '-Dtest=ChatPersistenceIntegrationTest' '-Dlearnhub.chat.it.enabled=true' '-Dsurefire.failIfNoSpecifiedTests=false' test *> $log
    $result = $LASTEXITCODE
    Get-Content -LiteralPath $log -Tail 30
    if ($result -ne 0) { throw "隔离检查点测试失败，退出码 $result" }
} finally {
    foreach($key in $settings) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
    if ($container -match '^[a-f0-9]{64}$') {
        $owned = (& docker inspect --format '{{index .Config.Labels "learnhub.test"}}' $container 2>$null)
        if ($LASTEXITCODE -eq 0 -and $owned -eq $label) {
            & docker stop --time 5 $container | Out-Null
            if ($LASTEXITCODE -ne 0) { throw '本次隔离 MySQL 清理失败' }
            Write-Output '本次隔离 MySQL 已停止并自动删除。'
        } else { throw '无法核验本次容器身份，拒绝停止未知容器' }
    }
    Pop-Location
}
