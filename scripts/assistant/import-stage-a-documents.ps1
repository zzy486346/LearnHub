param(
    [string]$BaseUrl = 'http://127.0.0.1:18081',
    [string]$MappingPath = 'scripts/assistant/stage-a-mapping.json',
    [string]$CatalogPath = 'scripts/course-media/catalog.json',
    [int]$IndexWaitSeconds = 60
)

$ErrorActionPreference = 'Stop'
$username = $env:LEARNHUB_STAGE_A_ADMIN_USERNAME
$password = $env:LEARNHUB_STAGE_A_ADMIN_PASSWORD
if ([string]::IsNullOrWhiteSpace($username) -or [string]::IsNullOrWhiteSpace($password)) {
    throw '必须通过 LEARNHUB_STAGE_A_ADMIN_USERNAME 和 LEARNHUB_STAGE_A_ADMIN_PASSWORD 提供本地管理员凭据'
}

$payloadText = (& node scripts/assistant/prepare-documents.mjs --catalog $CatalogPath --mapping $MappingPath | Out-String)
if ($LASTEXITCODE -ne 0) { throw '讲解稿转换失败' }
$payloads = @($payloadText | ConvertFrom-Json)

$loginBody = @{ username = $username; password = $password } | ConvertTo-Json -Compress
$login = Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body $loginBody
$token = $login.data.accessToken
if ([string]::IsNullOrWhiteSpace($token)) { throw '管理员登录未返回 accessToken' }
$headers = @{ Authorization = "Bearer $token" }

$created = 0
$unchanged = 0
$chunks = 0
foreach ($payload in $payloads) {
    $body = $payload | ConvertTo-Json -Depth 8 -Compress
    $first = Invoke-RestMethod -Uri "$BaseUrl/api/admin/knowledge/documents" -Method Post -Headers $headers -ContentType 'application/json' -Body $body
    if ($first.data.unchanged) { $unchanged++ } else { $created++ }
    $chunks += [int]$first.data.chunkCount

    $second = Invoke-RestMethod -Uri "$BaseUrl/api/admin/knowledge/documents" -Method Post -Headers $headers -ContentType 'application/json' -Body $body
    if (-not $second.data.unchanged -or $second.data.sourceVersion -ne $first.data.sourceVersion) {
        throw "重复导入未保持幂等：$($payload.sourceKey)"
    }
}

$smokes = @(
    @{ courseId = '12002'; question = '工作线程为什么不能越多越好' },
    @{ courseId = '12007'; question = '热点键过期后怎样避免集中回源' },
    @{ courseId = '12015'; question = '倒排索引怎样定位包含关键词的课程' }
)
$deadline = [DateTimeOffset]::UtcNow.AddSeconds($IndexWaitSeconds)
$matched = 0
do {
    $matched = 0
    foreach ($smoke in $smokes) {
        $requestBody = @{ courseId = $smoke.courseId; question = $smoke.question; limit = 5 } | ConvertTo-Json -Compress
        $response = Invoke-RestMethod -Uri "$BaseUrl/api/assistant/retrieve" -Method Post -Headers $headers -ContentType 'application/json' -Body $requestBody
        if (@($response.data.sources).Count -gt 0) { $matched++ }
    }
    if ($matched -lt $smokes.Count) { Start-Sleep -Seconds 2 }
} while ($matched -lt $smokes.Count -and [DateTimeOffset]::UtcNow -lt $deadline)

if ($matched -ne $smokes.Count) { throw "索引等待超时：仅 $matched/$($smokes.Count) 个查询返回来源" }
Write-Output "STAGE_A_IMPORT=OK documents=$($payloads.Count) created=$created unchanged=$unchanged chunks=$chunks"
Write-Output "STAGE_A_BM25=OK queries=$($smokes.Count) matched=$matched"
