[CmdletBinding()]
param([string]$MavenPath = 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd')
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$taskRun = 'vector-it-' + [Guid]::NewGuid().ToString('N')
$taskContainer = 'learnhub-' + $taskRun
$taskMysqlContainer = 'learnhub-' + $taskRun + '-mysql'
$taskVolume = 'learnhub-' + $taskRun + '-data'
$taskImage = 'pgvector/pgvector:0.8.7-pg16-bookworm@sha256:a97d77306ff47cc1d8add4d000cec2207da2e110746318df3137ce53c337ca30'
$taskPassword = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$taskOriginalPort = $env:LEARNHUB_VECTOR_IT_PORT
$taskOriginalPassword = $env:LEARNHUB_VECTOR_IT_PASSWORD
$taskOriginalMysqlPort = $env:LEARNHUB_VECTOR_IT_MYSQL_PORT
$taskOriginalMysqlPassword = $env:LEARNHUB_VECTOR_IT_MYSQL_PASSWORD
$taskExitCode = 1
function Invoke-VectorDocker([string[]]$Arguments) {
    $taskOutput = & docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Vector test Docker operation failed; inspect owned container state without printing credentials' }
    return $taskOutput
}
try {
    Invoke-VectorDocker @('image','inspect',$taskImage) | Out-Null
    Invoke-VectorDocker @('volume','create','--label',"learnhub.vector-it=$taskRun",$taskVolume) | Out-Null
    Invoke-VectorDocker @('run','--detach','--pull','never','--name',$taskContainer,'--label',"learnhub.vector-it=$taskRun",
        '--publish','127.0.0.1::5432','--mount',"type=volume,source=$taskVolume,target=/var/lib/postgresql/data",
        '--env',"POSTGRES_PASSWORD=$taskPassword",'--env','POSTGRES_DB=learnhub_vector_it',$taskImage) | Out-Null
    Invoke-VectorDocker @('run','--detach','--pull','never','--name',$taskMysqlContainer,'--label',"learnhub.vector-it=$taskRun",
        '--publish','127.0.0.1::3306','--tmpfs','/var/lib/mysql:rw,nosuid,size=512m',
        '--env',"MYSQL_ROOT_PASSWORD=$taskPassword",'--env','MYSQL_ROOT_HOST=%',
        '--env','MYSQL_DATABASE=learnhub_vector_it','mysql:8.4') | Out-Null
    $taskDeadline = (Get-Date).AddSeconds(90)
    do {
        & docker exec $taskContainer pg_isready -U postgres -d learnhub_vector_it *> $null
        if ($LASTEXITCODE -eq 0) { break }
        if ((Get-Date) -gt $taskDeadline) { throw 'Owned PostgreSQL startup timed out' }
        Start-Sleep -Seconds 1
    } while ($true)
    $taskBinding = (Invoke-VectorDocker @('port',$taskContainer,'5432/tcp')) -join ''
    if ($taskBinding -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'Owned PostgreSQL must bind loopback only' }
    $env:LEARNHUB_VECTOR_IT_PORT = $Matches[1]
    $env:LEARNHUB_VECTOR_IT_PASSWORD = $taskPassword
    $taskDeadline = (Get-Date).AddSeconds(120)
    do {
        & docker exec $taskMysqlContainer mysqladmin --host=127.0.0.1 --user=root "--password=$taskPassword" ping --silent *> $null
        if ($LASTEXITCODE -eq 0) { break }
        if ((Get-Date) -gt $taskDeadline) { throw 'Owned MySQL startup timed out' }
        Start-Sleep -Seconds 1
    } while ($true)
    $taskMysqlBinding = (Invoke-VectorDocker @('port',$taskMysqlContainer,'3306/tcp')) -join ''
    if ($taskMysqlBinding -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'Owned MySQL must bind loopback only' }
    $env:LEARNHUB_VECTOR_IT_MYSQL_PORT = $Matches[1]
    $env:LEARNHUB_VECTOR_IT_MYSQL_PASSWORD = $taskPassword
    Write-Host "Owned pgvector ready on loopback port $env:LEARNHUB_VECTOR_IT_PORT"
    Push-Location -LiteralPath $taskRoot
    try {
        $taskStarted = [DateTime]::UtcNow
        & $MavenPath '-pl' 'learnhub-server' '-am' '-Dtest=CourseVectorInfrastructureIntegrationTest,VectorProjectionInfrastructureIntegrationTest,BgeChineseEmbeddingTest' `
            '-Dsurefire.failIfNoSpecifiedTests=false' '-Dlearnhub.vector.it.enabled=true' `
            '-DargLine=-Djdk.net.URLClassPath.disableClassPathURLCheck=true' test
        $taskExitCode = $LASTEXITCODE
        if ($taskExitCode -eq 0) {
            $taskRequired = @{ CourseVectorInfrastructureIntegrationTest = 4; BgeChineseEmbeddingTest = 3; VectorProjectionInfrastructureIntegrationTest = 8 }
            foreach ($taskClass in $taskRequired.Keys) {
                $taskReport = Get-Item -LiteralPath (Join-Path $taskRoot "learnhub-server/target/surefire-reports/TEST-com.learnhub.assistant.knowledge.vector.$taskClass.xml")
                [xml]$taskSuite = Get-Content -LiteralPath $taskReport.FullName -Raw
                if ($taskReport.LastWriteTimeUtc -lt $taskStarted -or [int]$taskSuite.testsuite.tests -ne $taskRequired[$taskClass] -or
                    [int]$taskSuite.testsuite.skipped -ne 0 -or [int]$taskSuite.testsuite.failures -ne 0 -or
                    [int]$taskSuite.testsuite.errors -ne 0) { throw "Required vector report missing or stale: $taskClass" }
            }
        }
    } finally { Pop-Location }
} finally {
    $env:LEARNHUB_VECTOR_IT_PORT = $taskOriginalPort
    $env:LEARNHUB_VECTOR_IT_PASSWORD = $taskOriginalPassword
    $env:LEARNHUB_VECTOR_IT_MYSQL_PORT = $taskOriginalMysqlPort
    $env:LEARNHUB_VECTOR_IT_MYSQL_PASSWORD = $taskOriginalMysqlPassword
    $taskCleanupErrors = @()
    foreach ($taskOwned in @(
        [pscustomobject]@{Kind='container';Name=$taskContainer},
        [pscustomobject]@{Kind='container';Name=$taskMysqlContainer},
        [pscustomobject]@{Kind='volume';Name=$taskVolume})) {
        $taskKind = $taskOwned.Kind
        $taskName = $taskOwned.Name
        if ($taskKind -eq 'container') { $taskPresent = & docker container inspect --format '{{ index .Config.Labels "learnhub.vector-it" }}' $taskName 2>$null }
        else { $taskPresent = & docker volume inspect --format '{{ index .Labels "learnhub.vector-it" }}' $taskName 2>$null }
        if ($LASTEXITCODE -eq 0) {
            if ($taskPresent -ne $taskRun) { $taskCleanupErrors += "Ownership mismatch: $taskName"; continue }
            if ($taskKind -eq 'container') { & docker rm --force $taskName *> $null }
            else { & docker volume rm $taskName *> $null }
            if ($LASTEXITCODE -ne 0) { $taskCleanupErrors += "Cleanup failed: $taskName" }
        }
    }
    $taskPassword = $null
    if ($taskCleanupErrors.Count -gt 0) { throw ($taskCleanupErrors -join ', ') }
    Write-Host 'Owned PostgreSQL/MySQL containers and temporary volume removed.'
}
exit $taskExitCode
