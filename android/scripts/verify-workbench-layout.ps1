param(
  [string]$Path = (Join-Path $PSScriptRoot '..\app\src\main\java\com\agentmonitor\app\ui\screens\WorkbenchScreen.kt')
)
$src = Get-Content -Raw -Encoding UTF8 -LiteralPath $Path
$checks = @(
  @{ Name = 'message content region is explicitly weighted'; Pattern = 'Box\(\s*modifier\s*=\s*Modifier\s*\r?\n\s*\.weight\(1f\)' },
  @{ Name = 'empty state is rendered inside the weighted content region'; Pattern = 'messages\.isEmpty\(\)\s*->\s*WorkbenchInlineState' },
  @{ Name = 'composer row fills width'; Pattern = 'Row\(\s*verticalAlignment\s*=\s*Alignment\.CenterVertically,\s*\r?\n\s*modifier\s*=\s*Modifier\s*\r?\n\s*\.fillMaxWidth\(\)' },
  @{ Name = 'composer row reserves keyboard and navigation space'; Pattern = '\.imePadding\(\)\s*\r?\n\s*\.navigationBarsPadding\(\)' },
  @{ Name = 'text input has a stable minimum height'; Pattern = 'Modifier\s*\r?\n\s*\.weight\(1f\)\s*\r?\n\s*\.heightIn\(min\s*=\s*56\.dp\)' },
  @{ Name = 'send path refreshes messages after the REST accept'; Pattern = 'repo\.sendWorkbenchMessage\(currentHost,\s*sessionId,\s*text\)\s*\r?\n\s*refreshWorkbenchMessages\(showLoading\s*=\s*false\)' },
  @{ Name = 'send path polls while the agent turn is running'; Pattern = 'while \(session\.state == "running" && attempts < 30\)' }
)
$failed = @()
foreach ($check in $checks) {
  if ($src -notmatch $check.Pattern) { $failed += $check.Name }
}
if ($failed.Count -gt 0) {
  Write-Error ("Workbench layout constraints failed: " + ($failed -join '; '))
  exit 1
}
Write-Host 'Workbench layout constraints passed.'
