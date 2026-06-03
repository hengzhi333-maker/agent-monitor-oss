param(
  [string]$Path = ""
)

$ErrorActionPreference = "Stop"

$repo = if ([string]::IsNullOrWhiteSpace($Path)) {
  (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
} else {
  (Resolve-Path -LiteralPath $Path).Path
}

$patterns = @(
  @{ Name = "OpenAI-style API key"; Pattern = "sk-(proj-)?[A-Za-z0-9_-]{20,}" },
  @{ Name = "GitHub token"; Pattern = "(ghp|github_pat)_[A-Za-z0-9_]{20,}" },
  @{ Name = "Bearer token"; Pattern = "Bearer\s+(?!<token>|<redacted>)[A-Za-z0-9._~+/=-]{20,}" },
  @{ Name = "Likely private Tailscale IP"; Pattern = "100\.(?:6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.\d{1,3}\.\d{1,3}"; Allow = @("100.64.0.0", "100.64.0.10") },
  @{ Name = "Likely Samsung device serial"; Pattern = "\bR[0-9A-Z]{10}\b" },
  @{ Name = "Windows user path"; Pattern = "C:\\Users\\(?!you\\)[^\\\s]+" },
  @{ Name = "Windows user path"; Pattern = "C:/Users/(?!you/)[^\s]+" }
)
$blockedExtensions = @(".apk", ".aab", ".jks", ".keystore", ".p12")
$blockedNames = @("config.json", "local.properties", "keystore.properties")

$excluded = @(
  ".git\*",
  "daemon\node_modules\*",
  "android\.gradle\*",
  "android\app\build\*",
  "daemon\package-lock.json",
  "daemon\test\redact.test.js",
  "scripts\scan-open-source-leaks.ps1"
)

$hits = @()
Get-ChildItem -LiteralPath $repo -Recurse -File -Force | ForEach-Object {
  $path = $_.FullName
  $relative = $path.Substring($repo.Length).TrimStart("\", "/")
  foreach ($skip in $excluded) {
    if ($relative -like $skip) { return }
  }
  if ($blockedExtensions -contains $_.Extension.ToLowerInvariant()) {
    $hits += [PSCustomObject]@{
      Rule = "Binary release/signing artifact"
      File = $relative
    }
    return
  }
  if ($blockedNames -contains $_.Name.ToLowerInvariant()) {
    $hits += [PSCustomObject]@{
      Rule = "Private runtime config"
      File = $relative
    }
    return
  }
  $text = Get-Content -LiteralPath $path -Raw -ErrorAction SilentlyContinue
  if ($null -eq $text) { return }
  foreach ($entry in $patterns) {
    $matches = [regex]::Matches($text, $entry.Pattern)
    $blocked = $matches | Where-Object {
      $allow = $entry.Allow
      -not $allow -or $_.Value -notin $allow
    } | Select-Object -First 1
    if ($blocked) {
      $hits += [PSCustomObject]@{
        Rule = $entry.Name
        File = $relative
      }
    }
  }
}

if ($hits.Count -gt 0) {
  $hits | Sort-Object Rule, File | Format-Table -AutoSize | Out-String | Write-Error
  exit 1
}

Write-Host "No high-confidence open-source leak patterns found."
