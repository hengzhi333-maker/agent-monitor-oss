param(
  [string]$DaemonDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
)

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$ErrorActionPreference = 'Stop'
$configPath = Join-Path $DaemonDir 'config.json'
$logPath = Join-Path $DaemonDir '.agent-monitor.log.jsonl'

function Read-Config {
  if (Test-Path $configPath) {
    return Get-Content $configPath -Raw | ConvertFrom-Json
  }
  return [pscustomobject]@{ port = 8765; bindHost = '127.0.0.1'; token = '' }
}

function Get-PrimaryToken {
  $cfg = Read-Config
  if ($cfg.token) { return [string]$cfg.token }
  if ($cfg.tokens -and $cfg.tokens.Count -gt 0) {
    $first = $cfg.tokens[0]
    if ($first -is [string]) { return $first }
    if ($first.token) { return [string]$first.token }
  }
  return ''
}

function Get-BaseUrl {
  $cfg = Read-Config
  $host = if ($cfg.bindHost -and $cfg.bindHost -ne '0.0.0.0') { $cfg.bindHost } else { '127.0.0.1' }
  $port = if ($cfg.port) { [int]$cfg.port } else { 8765 }
  return "http://${host}:${port}"
}

function Invoke-Daemon {
  param([string]$Path)
  $token = Get-PrimaryToken
  $headers = @{}
  if ($token) { $headers.Authorization = "Bearer $token" }
  Invoke-RestMethod -Uri "$(Get-BaseUrl)$Path" -Headers $headers -TimeoutSec 5
}

function Get-DaemonStatusText {
  try {
    $ping = Invoke-RestMethod -Uri "$(Get-BaseUrl)/ping" -TimeoutSec 3
    return "Online - $($ping.ts)"
  } catch {
    return "Offline - $($_.Exception.Message)"
  }
}

function Start-Daemon {
  $script = Join-Path $DaemonDir 'start-agent-monitor-daemon.ps1'
  if (Test-Path $script) {
    Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $script) -WorkingDirectory $DaemonDir -WindowStyle Hidden
  } else {
    Start-Process -FilePath 'npm.cmd' -ArgumentList @('start') -WorkingDirectory $DaemonDir -WindowStyle Hidden
  }
}

function Stop-Daemon {
  $cfg = Read-Config
  $port = if ($cfg.port) { [int]$cfg.port } else { 8765 }
  $connections = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
  foreach ($conn in $connections) {
    if ($conn.OwningProcess) {
      Stop-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
    }
  }
}

function Export-Diagnostics {
  $target = Join-Path $DaemonDir ("agent-monitor-diagnostics-{0}.json" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
  $pkg = Invoke-Daemon '/diagnostics/package'
  $pkg | ConvertTo-Json -Depth 20 | Set-Content -Path $target -Encoding UTF8
  [System.Windows.Forms.MessageBox]::Show("Diagnostics exported:`n$target", 'Agent Monitor')
}

$form = New-Object System.Windows.Forms.Form
$form.Text = 'Agent Monitor Control Panel'
$form.Width = 520
$form.Height = 260
$form.StartPosition = 'CenterScreen'

$status = New-Object System.Windows.Forms.Label
$status.Left = 16
$status.Top = 18
$status.Width = 460
$status.Height = 28
$status.Text = Get-DaemonStatusText
$form.Controls.Add($status)

$url = New-Object System.Windows.Forms.TextBox
$url.Left = 16
$url.Top = 52
$url.Width = 460
$url.ReadOnly = $true
$url.Text = Get-BaseUrl
$form.Controls.Add($url)

$buttons = @(
  @{ Text = 'Refresh'; Left = 16; Action = { $status.Text = Get-DaemonStatusText; $url.Text = Get-BaseUrl } },
  @{ Text = 'Start'; Left = 116; Action = { Start-Daemon; Start-Sleep -Seconds 1; $status.Text = Get-DaemonStatusText } },
  @{ Text = 'Stop'; Left = 216; Action = { Stop-Daemon; Start-Sleep -Seconds 1; $status.Text = Get-DaemonStatusText } },
  @{ Text = 'Diagnostics'; Left = 316; Action = { Export-Diagnostics } }
)

foreach ($item in $buttons) {
  $button = New-Object System.Windows.Forms.Button
  $button.Text = $item.Text
  $button.Left = $item.Left
  $button.Top = 92
  $button.Width = 90
  $button.Height = 32
  $handler = $item.Action
  $button.Add_Click($handler)
  $form.Controls.Add($button)
}

$openLogs = New-Object System.Windows.Forms.Button
$openLogs.Text = 'Open Logs'
$openLogs.Left = 16
$openLogs.Top = 138
$openLogs.Width = 120
$openLogs.Height = 32
$openLogs.Add_Click({
  if (Test-Path $logPath) { Start-Process notepad.exe $logPath } else { [System.Windows.Forms.MessageBox]::Show('No structured log file yet.', 'Agent Monitor') }
})
$form.Controls.Add($openLogs)

$notify = New-Object System.Windows.Forms.NotifyIcon
$notify.Icon = [System.Drawing.SystemIcons]::Application
$notify.Text = 'Agent Monitor'
$notify.Visible = $true

$menu = New-Object System.Windows.Forms.ContextMenuStrip
$menu.Items.Add('Open Control Panel').Add_Click({ $form.Show(); $form.WindowState = 'Normal' })
$menu.Items.Add('Refresh Status').Add_Click({ $status.Text = Get-DaemonStatusText })
$menu.Items.Add('Export Diagnostics').Add_Click({ Export-Diagnostics })
$menu.Items.Add('Exit').Add_Click({ $notify.Visible = $false; $form.Close() })
$notify.ContextMenuStrip = $menu
$notify.Add_DoubleClick({ $form.Show(); $form.WindowState = 'Normal' })

$form.Add_Resize({
  if ($form.WindowState -eq 'Minimized') {
    $form.Hide()
  }
})
$form.Add_FormClosed({ $notify.Visible = $false })

[void]$form.ShowDialog()
