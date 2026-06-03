param(
  [string]$ServiceName = "AgentMonitorDaemon"
)

$ErrorActionPreference = "Stop"
$nssm = Get-Command nssm.exe -ErrorAction SilentlyContinue

if ($nssm -and (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue)) {
  & $nssm.Source stop $ServiceName | Out-Null
  & $nssm.Source remove $ServiceName confirm | Out-Null
  Write-Host "Removed Windows service '$ServiceName'."
  exit 0
}

$task = Get-ScheduledTask -TaskName $ServiceName -ErrorAction SilentlyContinue
if ($task) {
  Stop-ScheduledTask -TaskName $ServiceName -ErrorAction SilentlyContinue
  Unregister-ScheduledTask -TaskName $ServiceName -Confirm:$false
  Write-Host "Removed startup scheduled task '$ServiceName'."
  exit 0
}

Write-Host "No service or scheduled task named '$ServiceName' was found."
