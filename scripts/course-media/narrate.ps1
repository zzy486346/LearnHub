param(
    [Parameter(Mandatory=$true)][string]$OutputDir,
    [int]$Limit = 0
)
$ErrorActionPreference = 'Stop'
$targetRoot = [IO.Path]::GetFullPath($OutputDir)
New-Item -ItemType Directory -Force -Path $targetRoot | Out-Null
$catalogPath = Join-Path $PSScriptRoot 'catalog.json'
$catalog = Get-Content -LiteralPath $catalogPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($Limit -gt 0) { $catalog = @($catalog | Select-Object -First $Limit) }
Add-Type -AssemblyName System.Speech
$speaker = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $voice = $speaker.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -eq 'zh-CN' } | Select-Object -First 1
    if (-not $voice) { throw 'A Chinese speech synthesis voice is required.' }
    $speaker.SelectVoice($voice.VoiceInfo.Name)
    foreach ($course in $catalog) {
        if ($course.slug -notmatch '^[a-z0-9-]+$') { throw 'Invalid course slug' }
        $workDir = Join-Path $targetRoot ('_work/' + $course.slug)
        New-Item -ItemType Directory -Force -Path $workDir | Out-Null
        for ($lessonIndex=0; $lessonIndex -lt $course.lessons.Count; $lessonIndex++) {
            $lesson = $course.lessons[$lessonIndex]
            for ($sceneIndex=0; $sceneIndex -lt $lesson.scenes.Count; $sceneIndex++) {
                $hasher = [Security.Cryptography.SHA256]::Create()
                try { $narrationHash = ([BitConverter]::ToString($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($lesson.scenes[$sceneIndex].narration)))).Replace('-','').ToLowerInvariant().Substring(0,12) }
                finally { $hasher.Dispose() }
                $wavePath = Join-Path $workDir ('lesson-{0:D2}-scene-{1:D2}-{2}.wav' -f ($lessonIndex+1),($sceneIndex+1),$narrationHash)
                if (Test-Path -LiteralPath $wavePath) { continue }
                $text = [Security.SecurityElement]::Escape($lesson.scenes[$sceneIndex].narration)
                $rate = if ($sceneIndex -eq 0) { '-4%' } else { '0%' }
                $ssml = '<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="zh-CN"><break time="180ms"/><prosody rate="' + $rate + '">' + $text + '</prosody><break time="500ms"/></speak>'
                $speaker.SetOutputToWaveFile($wavePath)
                $speaker.SpeakSsml($ssml)
                $speaker.SetOutputToNull()
            }
        }
        Write-Output ('Narrated: ' + $course.slug)
    }
} finally { $speaker.Dispose() }
