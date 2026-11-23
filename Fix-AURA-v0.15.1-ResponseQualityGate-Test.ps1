param(
    [string]$ProjectRoot = "E:\MY PROJECT FOLDER\AURA PROJECT\AURA-Butler-Android-Studio-v0.4.0-Native-Stable",
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "AURA v0.15.1 test-compile hotfix" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "Project: $ProjectRoot"
Write-Host ""
Write-Host "This hotfix ONLY repairs the v0.15 Kotlin unit-test string." -ForegroundColor Yellow
Write-Host "Cloud v0.15 is already deployed; this script does not redeploy Modal."
Write-Host "No production Kotlin logic, settings, model weights, or secrets are changed."
Write-Host ""

$AndroidRoot = Join-Path $ProjectRoot "android"
$TestFile = Join-Path $AndroidRoot "app\src\test\java\in\diganta\aurabutler\runtime\ResponseQualityGateTest.kt"
$Gradlew = Join-Path $AndroidRoot "gradlew.bat"

if (-not (Test-Path -LiteralPath $TestFile)) {
    throw "Test file not found: $TestFile"
}
if (-not (Test-Path -LiteralPath $Gradlew)) {
    throw "Gradle wrapper not found: $Gradlew"
}

$ExpectedBuggyHash = "31F3EB6E396ED2473D56C12AC32DEAC924726A94204FB2BED7D4469E199E6965"
$TargetHash = "0B78DED489138A58CD26C0C6831D96B949C7F65CB8A21018AEB061FF86166996"
$CurrentHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $TestFile).Hash.ToUpperInvariant()

Write-Host "Current ResponseQualityGateTest.kt SHA256: $CurrentHash"

if ($CurrentHash -eq $TargetHash) {
    Write-Host "CURRENT: corrected test is already installed." -ForegroundColor Green
}
elseif ($CurrentHash -eq $ExpectedBuggyHash) {
    $Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $Backup = "$TestFile.backup-$Stamp"
    Copy-Item -LiteralPath $TestFile -Destination $Backup -Force
    Write-Host "Backup: $Backup"

    $Encoded = @'
cGFja2FnZSBgaW5gLmRpZ2FudGEuYXVyYWJ1dGxlci5ydW50aW1lCgppbXBvcnQgb3JnLmp1bml0LkFzc2VydC5hc3NlcnRFcXVhbHMKaW1wb3J0IG9yZy5qdW5pdC5Bc3NlcnQuYXNzZXJ0RmFsc2UKaW1wb3J0IG9yZy5qdW5pdC5Bc3NlcnQuYXNzZXJ0VHJ1ZQppbXBvcnQgb3JnLmp1bml0LlRlc3QKCmNsYXNzIFJlc3BvbnNlUXVhbGl0eUdhdGVUZXN0IHsKICAgIEBUZXN0CiAgICBmdW4gcHJlc2VydmVzUHJvZ3JhbW1pbmdTeW50YXhJbnNpZGVGZW5jZWRDb2RlKCkgewogICAgICAgIHZhbCByYXcgPSAiIiIKICAgICAgICAgICAgSGVyZSBpcyB0aGUgaW1wbGVtZW50YXRpb246CiAgICAgICAgICAgIGBgYGMKICAgICAgICAgICAgaW50ICpwdHIgPSAmdmFsdWU7CiAgICAgICAgICAgIGludCBteV92YXJpYWJsZSA9IGEqYjsKICAgICAgICAgICAgcHJpbnRmKCIlZFxcbiIsIG15X3ZhcmlhYmxlKTsKICAgICAgICAgICAgYGBgCiAgICAgICAgIiIiLnRyaW1JbmRlbnQoKQoKICAgICAgICB2YWwgdmFsdWUgPSBSZXNwb25zZVF1YWxpdHlHYXRlLmZvckRpc3BsYXkocmF3LCAicHJvZmVzc2lvbmFsIikKICAgICAgICBhc3NlcnRUcnVlKHZhbHVlLmNvbnRhaW5zKCJgYGBjIikpCiAgICAgICAgYXNzZXJ0VHJ1ZSh2YWx1ZS5jb250YWlucygiaW50ICpwdHIgPSAmdmFsdWU7IikpCiAgICAgICAgYXNzZXJ0VHJ1ZSh2YWx1ZS5jb250YWlucygibXlfdmFyaWFibGUgPSBhKmIiKSkKICAgICAgICBhc3NlcnRUcnVlKHZhbHVlLmNvbnRhaW5zKCJgYGAiKSkKICAgIH0KCiAgICBAVGVzdAogICAgZnVuIHJlbW92ZXNIaWRkZW5UaGlua2luZ1dpdGhvdXRDb3JydXB0aW5nUG93ZXJTaGVsbCgpIHsKICAgICAgICB2YWwgcmF3ID0gIjx0aGluaz5wcml2YXRlIHJlYXNvbmluZzwvdGhpbms+YGBgcG93ZXJzaGVsbFxuYCR7JyQnfWVudjpVU0VSUFJPRklMRVxcRG93bmxvYWRzXG5gYGAiCiAgICAgICAgdmFsIHZhbHVlID0gUmVzcG9uc2VRdWFsaXR5R2F0ZS5mb3JEaXNwbGF5KHJhdywgInByb2Zlc3Npb25hbCIpCiAgICAgICAgYXNzZXJ0RmFsc2UodmFsdWUuY29udGFpbnMoInByaXZhdGUgcmVhc29uaW5nIikpCiAgICAgICAgYXNzZXJ0VHJ1ZSh2YWx1ZS5jb250YWlucygiYCR7JyQnfWVudjpVU0VSUFJPRklMRVxcRG93bmxvYWRzIikpCiAgICB9CgogICAgQFRlc3QKICAgIGZ1biBub3JtYWxpemVzUGFpcmVkTWFya2Rvd25JblByb3NlT25seSgpIHsKICAgICAgICB2YWwgdmFsdWUgPSBSZXNwb25zZVF1YWxpdHlHYXRlLmZvckRpc3BsYXkoIioqSW1wb3J0YW50Kio6IHVzZSBgbXlfdmFyaWFibGVgLiIsICJwcm9mZXNzaW9uYWwiKQogICAgICAgIGFzc2VydEVxdWFscygiSW1wb3J0YW50OiB1c2UgbXlfdmFyaWFibGUuIiwgdmFsdWUpCiAgICB9Cn0K
'@
    [System.IO.File]::WriteAllBytes(
        $TestFile,
        [System.Convert]::FromBase64String($Encoded.Trim())
    )

    $AfterHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $TestFile).Hash.ToUpperInvariant()
    if ($AfterHash -ne $TargetHash) {
        throw "Hotfix write verification failed. Expected $TargetHash but got $AfterHash"
    }

    Write-Host "PATCHED: ResponseQualityGateTest.kt" -ForegroundColor Green
}
else {
    throw @"
Refusing to overwrite an unexpected ResponseQualityGateTest.kt.

Expected buggy v0.15 SHA256:
$ExpectedBuggyHash

Corrected SHA256:
$TargetHash

Actual SHA256:
$CurrentHash

Do not use Force. Send the actual hash for review.
"@
}

$Text = [System.IO.File]::ReadAllText($TestFile)
$ExpectedMarker = '`${''$''}env:USERPROFILE\\Downloads'
$OldMarker = '`$env:USERPROFILE\\Downloads'

if (-not $Text.Contains($ExpectedMarker)) {
    throw "Correct Kotlin dollar escaping marker is missing."
}
if ($Text.Contains($OldMarker)) {
    throw "Old invalid Kotlin `$env interpolation is still present."
}

Write-Host "PASS [static]: PowerShell `$env text is escaped correctly for Kotlin" -ForegroundColor Green

if ($SkipBuild) {
    Write-Host ""
    Write-Host "SkipBuild requested. Test source fixed; Gradle not run." -ForegroundColor Yellow
    exit 0
}

$JbrCandidates = @(
    "E:\Program Files\Android\Android Studio\jbr",
    "C:\Program Files\Android\Android Studio\jbr"
)
foreach ($Candidate in $JbrCandidates) {
    if (Test-Path -LiteralPath (Join-Path $Candidate "bin\java.exe")) {
        $env:JAVA_HOME = $Candidate
        break
    }
}

Write-Host ""
Write-Host "==> Running Android verification" -ForegroundColor Cyan
Write-Host "Tasks: testDebugUnitTest lintDebug assembleDebug"
Write-Host ""

Push-Location $AndroidRoot
try {
    & $Gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
    $GradleExit = $LASTEXITCODE
}
finally {
    Pop-Location
}

if ($GradleExit -ne 0) {
    throw "Gradle verification failed with exit code $GradleExit. Send the output from the first 'FAILED' task."
}

Write-Host ""
Write-Host "PASS: JVM unit tests" -ForegroundColor Green
Write-Host "PASS: Android lintDebug" -ForegroundColor Green
Write-Host "PASS: Debug APK build" -ForegroundColor Green

$Apk = Join-Path $AndroidRoot "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path -LiteralPath $Apk)) {
    throw "Gradle succeeded but APK was not found at: $Apk"
}

$FinalApk = Join-Path $ProjectRoot "AURA-v0.15.0-professional-coding-debug-fixed.apk"
Copy-Item -LiteralPath $Apk -Destination $FinalApk -Force

Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
Write-Host "SUCCESS: AURA v0.15 Android verification passed." -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Green
Write-Host "APK: $FinalApk"
Write-Host ""
Write-Host "Install this APK as an UPDATE; do not uninstall the existing AURA app."
Write-Host "After installation, run Test-AURA-AutoQA-v5.ps1 -Full."
