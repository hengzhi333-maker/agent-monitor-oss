param(
  [string]$ServiceName = "AgentMonitorDaemon",
  [string]$DisplayName = "Agent Monitor Daemon",
  [string]$NodePath = "",
  [switch]$UseScheduledTaskFallback
)

$ErrorActionPreference = "Stop"
$daemonDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$scriptPath = Join-Path $daemonDir "src\index.js"
$logDir = $daemonDir

if (-not $NodePath) {
  $cmd = Get-Command node -ErrorAction SilentlyContinue
  if (-not $cmd) { throw "node.exe was not found in PATH. Install Node.js or pass -NodePath." }
  $NodePath = $cmd.Source
}

function Install-ScheduledTaskFallback {
  $action = New-ScheduledTaskAction -Execute $NodePath -Argument "`"$scriptPath`"" -WorkingDirectory $daemonDir
  $trigger = New-ScheduledTaskTrigger -AtStartup
  $principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -RunLevel Highest
  $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)
  Register-ScheduledTask -TaskName $ServiceName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
  Start-ScheduledTask -TaskName $ServiceName
  Write-Host "Installed startup scheduled task '$ServiceName'."
}

$nssm = Get-Command nssm.exe -ErrorAction SilentlyContinue
if ($UseScheduledTaskFallback -or -not $nssm) {
  Write-Warning "nssm.exe was not found. Installing the supported startup scheduled-task fallback."
  Install-ScheduledTaskFallback
  exit 0
}

& $nssm.Source install $ServiceName $NodePath $scriptPath | Out-Null
& $nssm.Source set $ServiceName AppDirectory $daemonDir | Out-Null
& $nssm.Source set $ServiceName DisplayName $DisplayName | Out-Null
& $nssm.Source set $ServiceName Description "Local Agent Monitor collector and phone API daemon." | Out-Null
& $nssm.Source set $ServiceName Start SERVICE_AUTO_START | Out-Null
& $nssm.Source set $ServiceName AppStdout (Join-Path $logDir "agent-monitor.out.log") | Out-Null
& $nssm.Source set $ServiceName AppStderr (Join-Path $logDir "agent-monitor.err.log") | Out-Null
& $nssm.Source set $ServiceName AppRotateFiles 1 | Out-Null
& $nssm.Source set $ServiceName AppRotateBytes 10485760 | Out-Null
& $nssm.Source start $ServiceName | Out-Null

Write-Host "Installed and started Windows service '$ServiceName'."
