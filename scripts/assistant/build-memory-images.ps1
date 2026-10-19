[CmdletBinding()]
param([string]$JarPath = 'learnhub-server/target/learnhub-server-0.1.0-SNAPSHOT.jar')
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskJar = if ([IO.Path]::IsPathRooted($JarPath)) { $JarPath } else { Join-Path $taskRoot $JarPath }
if (-not (Test-Path -LiteralPath $taskJar -PathType Leaf)) { throw '请先在仓库根执行 Maven package/verify 生成应用 Jar' }
$taskBuild = Join-Path $taskRoot ('.local/memory-build-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($taskBuild) | Out-Null
try {
    # 最小上下文仅复制 Jar 和桥 Dockerfile，不发送 .env 或整个工作区给 Docker。
    Copy-Item -LiteralPath $taskJar -Destination (Join-Path $taskBuild 'app.jar')
    Copy-Item -LiteralPath (Join-Path $taskRoot 'services/mem0/Dockerfile.bridge') -Destination (Join-Path $taskBuild 'Dockerfile')
    & docker build -t 'learnhub-memory-bridge:local' $taskBuild
    if ($LASTEXITCODE -ne 0) { throw 'Embedding 桥镜像构建失败' }
    & docker build -t 'learnhub-mem0:local' (Join-Path $taskRoot 'services/mem0')
    if ($LASTEXITCODE -ne 0) { throw 'Mem0 镜像构建失败' }
    & docker image inspect --format '{{.Id}}' 'learnhub-memory-bridge:local' 'learnhub-mem0:local'
    if ($LASTEXITCODE -ne 0) { throw '构建镜像核验失败' }
} finally {
    $taskResolvedBuild = [IO.Path]::GetFullPath($taskBuild)
    $taskAllowedParent = [IO.Path]::GetFullPath((Join-Path $taskRoot '.local')) + [IO.Path]::DirectorySeparatorChar
    if (-not $taskResolvedBuild.StartsWith($taskAllowedParent, [StringComparison]::OrdinalIgnoreCase)) { throw '构建目录超出本轮工作区，拒绝清理' }
    if (Test-Path -LiteralPath $taskResolvedBuild) { Remove-Item -LiteralPath $taskResolvedBuild -Recurse -Force }
}
