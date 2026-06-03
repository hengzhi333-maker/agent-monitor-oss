$ErrorActionPreference = 'Stop'

$DaemonDir = Split-Path -Parent $PSCommandPath
$ConfigPath = Join-Path $DaemonDir 'config.json'
$Port = 8765
$OutLog = Join-Path $DaemonDir 'agent-monitor.out.log'
$ErrLog = Join-Path $DaemonDir 'agent-monitor.err.log'
$LaunchLog = Join-Path $DaemonDir 'agent-monitor.launch.log'

if (-not [string]::IsNullOrWhiteSpace($env:AM_PORT)) {
    $Port = [int]$env:AM_PORT
} elseif (Test-Path -LiteralPath $ConfigPath) {
    try {
        $cfg = Get-Content -LiteralPath $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($cfg.port) { $Port = [int]$cfg.port }
    } catch {
        Write-LaunchLog "config port read failed, using default $Port`: $($_.Exception.Message)"
    }
}

function Write-LaunchLog {
    param([string]$Message)
    $line = "[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    Add-Content -LiteralPath $LaunchLog -Value $line -Encoding UTF8
}

try {
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) {
        $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" -ErrorAction SilentlyContinue
        Write-LaunchLog "daemon already listening on port $Port, pid=$($listener.OwningProcess), command=$($proc.CommandLine)"
        exit 0
    }

    $node = $env:AGENT_MONITOR_NODE
    if ([string]::IsNullOrWhiteSpace($node) -or -not (Test-Path -LiteralPath $node)) {
        $nodeCommand = Get-Command node.exe -ErrorAction SilentlyContinue
        if (-not $nodeCommand) {
            Write-LaunchLog 'node.exe was not found. Set AGENT_MONITOR_NODE or add Node.js to PATH.'
            exit 1
        }
        $node = $nodeCommand.Source
    }

    $process = Start-Process `
        -FilePath $node `
        -ArgumentList 'src/index.js' `
        -WorkingDirectory $DaemonDir `
        -WindowStyle Hidden `
        -RedirectStandardOutput $OutLog `
        -RedirectStandardError $ErrLog `
        -PassThru

    Start-Sleep -Seconds 2
    $started = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($started) {
        Write-LaunchLog "daemon started, pid=$($process.Id), listening on port $Port"
        exit 0
    }

    Write-LaunchLog "daemon process launched pid=$($process.Id), but port $Port is not listening yet"
    exit 2
} catch {
    Write-LaunchLog "failed to start daemon: $($_.Exception.Message)"
    exit 1
}
