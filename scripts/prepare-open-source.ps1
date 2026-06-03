param(
  [string]$OutputDir = "",
  [switch]$Force,
  [switch]$SkipLeakScan
)

$ErrorActionPreference = "Stop"

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
  $OutputDir = Join-Path (Split-Path $root -Parent) "agent-monitor-oss-open-source"
}

$rootFull = [System.IO.Path]::GetFullPath($root).TrimEnd("\", "/")
$outFull = [System.IO.Path]::GetFullPath($OutputDir).TrimEnd("\", "/")
if ($outFull -eq $rootFull) {
  throw "OutputDir must not be the source repository."
}
if ($outFull.StartsWith($rootFull + [System.IO.Path]::DirectorySeparatorChar)) {
  throw "OutputDir must be outside the source repository so it cannot copy itself."
}

if (Test-Path -LiteralPath $outFull) {
  if (-not $Force) {
    throw "OutputDir already exists: $outFull. Pass -Force to replace it."
  }
  Remove-Item -LiteralPath $outFull -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $outFull | Out-Null

$includes = @(
  ".github",
  "android",
  "daemon",
  "docs",
  "scripts",
  ".gitignore",
  "CHANGELOG.md",
  "CONTRIBUTING.md",
  "LICENSE",
  "README.md",
  "SECURITY.md"
)

$excludedDirPrefixes = @(
  ".git",
  ".gradle",
  ".kotlin",
  "build",
  "dist",
  "docs\superpowers",
  "android\.gradle",
  "android\.kotlin",
  "android\app\build",
  "daemon\node_modules",
  "daemon\.workbench-uploads"
)

$excludedExact = @(
  "android\local.properties",
  "android\keystore.properties",
  "android\release.keystore",
  "daemon\config.json",
  "daemon\.agent-monitor-audit.jsonl",
  "daemon\.agent-monitor-devices.json",
  "daemon\.agent-monitor-history.sqlite",
  "daemon\.workbench-sessions.json"
)

$excludedExtensions = @(".apk", ".aab", ".jks", ".keystore", ".p12")
$excludedNamePatterns = @(
  "*.log",
  "*.sqlite",
  "*.sqlite-*"
)

function Normalize-Rel([string]$Path) {
  return $Path.TrimStart("\", "/").Replace("/", "\")
}

function Test-Excluded([string]$RelativePath, [System.IO.FileSystemInfo]$Item) {
  $rel = Normalize-Rel $RelativePath
  foreach ($prefix in $excludedDirPrefixes) {
    if ($rel -eq $prefix -or $rel.StartsWith($prefix + "\")) { return $true }
  }
  if ($excludedExact -contains $rel) { return $true }
  if ($Item -is [System.IO.FileInfo]) {
    if ($excludedExtensions -contains $Item.Extension.ToLowerInvariant()) { return $true }
    foreach ($pattern in $excludedNamePatterns) {
      if ($Item.Name -like $pattern) { return $true }
    }
  }
  return $false
}

foreach ($include in $includes) {
  $source = Join-Path $root $include
  if (-not (Test-Path -LiteralPath $source)) { continue }
  $sourceItem = Get-Item -LiteralPath $source -Force
  if ($sourceItem -is [System.IO.FileInfo]) {
    if (-not (Test-Excluded $include $sourceItem)) {
      Copy-Item -LiteralPath $sourceItem.FullName -Destination (Join-Path $outFull $include) -Force
    }
    continue
  }

  Get-ChildItem -LiteralPath $sourceItem.FullName -Recurse -Force | ForEach-Object {
    $relative = Normalize-Rel ($_.FullName.Substring($root.Length))
    if (Test-Excluded $relative $_) { return }
    $destination = Join-Path $outFull $relative
    if ($_ -is [System.IO.DirectoryInfo]) {
      New-Item -ItemType Directory -Force -Path $destination | Out-Null
    } else {
      $parent = Split-Path -Parent $destination
      New-Item -ItemType Directory -Force -Path $parent | Out-Null
      Copy-Item -LiteralPath $_.FullName -Destination $destination -Force
    }
  }
}

$gitRevision = ""
try {
  $gitRevision = (git -C $root rev-parse --short HEAD).Trim()
} catch {
  $gitRevision = ""
}

$manifest = [ordered]@{
  name = "agent-monitor-oss-source"
  generatedAt = (Get-Date).ToUniversalTime().ToString("o")
  gitRevision = $gitRevision
  note = "Sanitized source tree for public repository preparation. Private runtime configs, logs, build outputs, keystores, APKs, and dependency folders are excluded."
  requiredBeforePublish = @(
    "Run scripts/scan-open-source-leaks.ps1 from this folder.",
    "Review docs/OPEN_SOURCE_RELEASE_CHECKLIST.md.",
    "Generate fresh daemon tokens after cloning; never publish daemon/config.json."
  )
}
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $outFull "open-source-manifest.json") -Encoding UTF8

if (-not $SkipLeakScan) {
  & (Join-Path $root "scripts\scan-open-source-leaks.ps1") -Path $outFull
}

Write-Host "Open-source source tree prepared:"
Write-Host "  $outFull"
