param(
  [string]$DaemonDir = (Resolve-Path (Join-Path $PSScriptRoot '..\daemon')).Path,
  [string]$OutputPath = ''
)

$ErrorActionPreference = 'Stop'
$configPath = Join-Path $DaemonDir 'config.json'
if (-not (Test-Path $configPath)) {
  throw "Missing daemon config: $configPath"
}

$config = Get-Content $configPath -Raw | ConvertFrom-Json
$hostName = if ($config.bindHost -and $config.bindHost -ne '0.0.0.0') { $config.bindHost } else { '127.0.0.1' }
$port = if ($config.port) { [int]$config.port } else { 8765 }
$token = ''
if ($config.token) {
  $token = [string]$config.token
} elseif ($config.tokens -and $config.tokens.Count -gt 0) {
  $first = $config.tokens[0]
  if ($first -is [string]) { $token = $first } elseif ($first.token) { $token = [string]$first.token }
}
if (-not $token) {
  throw 'No daemon token found in config.json.'
}

if (-not $OutputPath) {
  $OutputPath = Join-Path $DaemonDir ("agent-monitor-diagnostics-{0}.json" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
}

$headers = @{ Authorization = "Bearer $token" }
$payload = Invoke-RestMethod -Uri "http://${hostName}:${port}/diagnostics/package" -Headers $headers -TimeoutSec 10
$payload | ConvertTo-Json -Depth 30 | Set-Content -Path $OutputPath -Encoding UTF8
Write-Host "Diagnostics exported to $OutputPath"
