[CmdletBinding()]
param(
    [switch]$ApplyMigration,
    [string]$Container = 'deploy-course-vector-pg-1',
    [string]$MavenPath = 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd'
)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
Push-Location -LiteralPath $taskRoot
try {
    if ($ApplyMigration) {
        # 只接受既有课程PG容器，禁止误作用于Mem0库；不重建/重启任何容器。
        $taskDatabase = & docker exec $Container printenv POSTGRES_DB
        if ($LASTEXITCODE -ne 0 -or $taskDatabase -ne 'learnhub_vector') {
            throw 'Expected existing course vector database learnhub_vector'
        }
        Get-Content -LiteralPath 'sql/postgres/002_siliconflow_bge_large_embeddings.sql' -Raw |
            & docker exec -i $Container psql --username postgres --dbname learnhub_vector --set ON_ERROR_STOP=1
        if ($LASTEXITCODE -ne 0) { throw 'Remote vector migration failed' }
        $taskGrant = 'GRANT SELECT, INSERT, UPDATE, DELETE ON learnhub_rag.course_embeddings_sf_bge_large_zh_v1 TO :"runtime_user";'
        $taskGrant | & docker exec -i $Container sh -c 'psql --username postgres --dbname learnhub_vector --set ON_ERROR_STOP=1 --set runtime_user="$LEARNHUB_VECTOR_USERNAME"'
        if ($LASTEXITCODE -ne 0) { throw 'Remote vector DML grant failed' }
    }
    & $MavenPath '-pl' 'learnhub-server' '-am' 'test' '-Dtest=SiliconFlowVectorBackfillIntegrationTest' `
        '-Dsurefire.failIfNoSpecifiedTests=false' '-Dlearnhub.vector.siliconflow.backfill=true'
    $taskResult = $LASTEXITCODE
    if ($taskResult -ne 0) { throw 'Remote vector maintenance failed; inspect redacted report and projection retry state' }
} finally { Pop-Location }
