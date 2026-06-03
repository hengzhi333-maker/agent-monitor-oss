param(
  [string]$SourceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path,
  [string]$InstallRoot = (Join-Path $env:LOCALAPPDATA 'AgentMonitor'),
  [switch]$InstallDaemonDependencies,
  [switch]$InstallAndroidRelease,
  [string]$AdbPath = 'adb'
)

$installer = Join-Path $PSScriptRoot 'install-private.ps1'
& $installer `
  -SourceRoot $SourceRoot `
  -InstallRoot $InstallRoot `
  -AdbPath $AdbPath `
  -InstallDaemonDependencies:$InstallDaemonDependencies `
  -InstallAndroidRelease:$InstallAndroidRelease
