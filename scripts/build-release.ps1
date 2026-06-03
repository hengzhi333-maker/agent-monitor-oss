param(
  [ValidateSet("debug", "release")]
  [string]$AndroidVariant = "debug",
  [string]$GradleCommand = "",
  [string]$OutputDir = "",
  [switch]$SkipTests,
  [switch]$SkipAudit
)

$ErrorActionPreference = "Stop"

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$daemon = Join-Path $root "daemon"
$android = Join-Path $root "android"
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
  $OutputDir = Join-Path $root "dist"
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$releaseDir = Join-Path $OutputDir "agent-monitor-$stamp"
$daemonStage = Join-Path $releaseDir "daemon"
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
New-Item -ItemType Directory -Force -Path $daemonStage | Out-Null

if ($AndroidVariant -eq "release") {
  $keystoreConfig = Join-Path $android "keystore.properties"
  if (-not (Test-Path -LiteralPath $keystoreConfig)) {
    throw "Release build requires android/keystore.properties. Run .\scripts\create-android-keystore.ps1 first."
  }
}

if (-not $SkipTests) {
  Push-Location $daemon
  try {
    npm.cmd test
    if (-not $SkipAudit) {
      npm.cmd --registry=https://registry.npmjs.org/ audit --omit=dev --audit-level=high
    }
  } finally {
    Pop-Location
  }
}

Push-Location $android
try {
  $task = if ($AndroidVariant -eq "release") { "assembleRelease" } else { "assembleDebug" }
  if ([string]::IsNullOrWhiteSpace($GradleCommand)) {
    .\gradlew.bat $task
  } else {
    & $GradleCommand $task
  }
} finally {
  Pop-Location
}

$variantDir = Join-Path $android "app\build\outputs\apk\$AndroidVariant"
$apk = Get-ChildItem -LiteralPath $variantDir -Filter "*.apk" -File |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1
if (-not $apk) {
  throw "Android APK was not found under $variantDir"
}

$apkName = "agent-monitor-$AndroidVariant.apk"
Copy-Item -LiteralPath $apk.FullName -Destination (Join-Path $releaseDir $apkName) -Force

$daemonItems = @(
  "package.json",
  "package-lock.json",
  "config.example.json",
  ".dockerignore",
  "Dockerfile",
  "docker-compose.example.yml",
  "ecosystem.config.cjs",
  "start-agent-monitor-daemon.ps1",
  "src",
  "systemd"
)
foreach ($item in $daemonItems) {
  $source = Join-Path $daemon $item
  if (Test-Path -LiteralPath $source) {
    Copy-Item -LiteralPath $source -Destination $daemonStage -Recurse -Force
  }
}

Copy-Item -LiteralPath (Join-Path $root "README.md") -Destination $releaseDir -Force
Copy-Item -LiteralPath (Join-Path $root "SECURITY.md") -Destination $releaseDir -Force
Copy-Item -LiteralPath (Join-Path $root "LICENSE") -Destination $releaseDir -Force
Copy-Item -LiteralPath (Join-Path $root "docs") -Destination (Join-Path $releaseDir "docs") -Recurse -Force

$daemonZip = Join-Path $releaseDir "agent-monitor-daemon.zip"
Compress-Archive -Path (Join-Path $daemonStage "*") -DestinationPath $daemonZip -Force
Remove-Item -LiteralPath $daemonStage -Recurse -Force

$apkPath = Join-Path $releaseDir $apkName
$apkSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $apkPath).Hash
$daemonSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $daemonZip).Hash

$gitRevision = ""
try {
  $gitRevision = (git -C $root rev-parse --short HEAD).Trim()
} catch {
  $gitRevision = ""
}

$manifest = [ordered]@{
  name = "agent-monitor"
  generatedAt = (Get-Date).ToUniversalTime().ToString("o")
  gitRevision = $gitRevision
  androidVariant = $AndroidVariant
  androidApk = $apkName
  androidApkSha256 = $apkSha256
  daemonPackage = Split-Path -Leaf $daemonZip
  daemonPackageSha256 = $daemonSha256
  testsRun = -not $SkipTests
  auditRun = (-not $SkipTests) -and (-not $SkipAudit)
}
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $releaseDir "release-manifest.json") -Encoding UTF8

Write-Host "Release package created:"
Write-Host "  $releaseDir"
