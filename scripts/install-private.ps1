param(
  [string]$SourceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path,
  [string]$InstallRoot = (Join-Path $env:LOCALAPPDATA 'AgentMonitor'),
  [switch]$InstallDaemonDependencies,
  [switch]$InstallAndroidRelease,
  [string]$AdbPath = 'adb'
)

$ErrorActionPreference = 'Stop'
$daemonSource = Join-Path $SourceRoot 'daemon'
$daemonTarget = Join-Path $InstallRoot 'daemon'
$androidApk = Join-Path $SourceRoot 'android\app\build\outputs\apk\release\app-release.apk'

New-Item -ItemType Directory -Force -Path $daemonTarget | Out-Null

$preserved = @('config.json', '.agent-monitor-history.sqlite', '.agent-monitor-devices.json', '.agent-monitor.log.jsonl')
foreach ($name in $preserved) {
  $target = Join-Path $daemonTarget $name
  if (Test-Path $target) {
    Copy-Item -LiteralPath $target -Destination "$target.bak" -Force
  }
}

Get-ChildItem -LiteralPath $daemonSource -Force | Where-Object {
  $_.Name -notin @('node_modules', 'config.json', '.agent-monitor-history.sqlite', '.agent-monitor-devices.json', '.agent-monitor.log.jsonl')
} | ForEach-Object {
  $dest = Join-Path $daemonTarget $_.Name
  Copy-Item -LiteralPath $_.FullName -Destination $dest -Recurse -Force
}

foreach ($name in $preserved) {
  $backup = Join-Path $daemonTarget "$name.bak"
  $target = Join-Path $daemonTarget $name
  if ((Test-Path $backup) -and -not (Test-Path $target)) {
    Move-Item -LiteralPath $backup -Destination $target -Force
  }
}

if ($InstallDaemonDependencies) {
  Push-Location $daemonTarget
  try { npm ci --omit=dev } finally { Pop-Location }
}

if ($InstallAndroidRelease) {
  if (-not (Test-Path $androidApk)) {
    throw "Release APK not found: $androidApk. Build it with scripts/build-release.ps1 first."
  }
  & $AdbPath install -r $androidApk
}

Write-Host "Agent Monitor private install updated:"
Write-Host "  Daemon: $daemonTarget"
Write-Host "  Control panel: $daemonTarget\windows\control-panel.ps1"
