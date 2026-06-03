param(
  [string]$StoreFile = "release.keystore",
  [string]$KeyAlias = "agent-monitor",
  [string]$DistinguishedName = "CN=Agent Monitor,O=Private Use,C=US",
  [int]$ValidityDays = 9125,
  [switch]$Force
)

$ErrorActionPreference = "Stop"

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$android = Join-Path $root "android"
$propsPath = Join-Path $android "keystore.properties"
$storePath = Join-Path $android $StoreFile

if ((Test-Path -LiteralPath $propsPath) -and -not $Force) {
  throw "android/keystore.properties already exists. Back it up or pass -Force intentionally."
}
if ((Test-Path -LiteralPath $storePath) -and -not $Force) {
  throw "android/$StoreFile already exists. Back it up or pass -Force intentionally."
}

function New-Password([int]$ByteCount = 36) {
  $bytes = New-Object byte[] $ByteCount
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try {
    $rng.GetBytes($bytes)
  } finally {
    $rng.Dispose()
  }
  return [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

$password = New-Password
$keytool = (Get-Command keytool -ErrorAction Stop).Source

& $keytool `
  -genkeypair `
  -v `
  -keystore $storePath `
  -storetype PKCS12 `
  -storepass $password `
  -keypass $password `
  -alias $KeyAlias `
  -keyalg RSA `
  -keysize 4096 `
  -validity $ValidityDays `
  -dname $DistinguishedName

if ($LASTEXITCODE -ne 0) {
  throw "keytool failed with exit code $LASTEXITCODE"
}

$lines = @(
  "storeFile=$StoreFile",
  "storePassword=$password",
  "keyAlias=$KeyAlias",
  "keyPassword=$password"
)
Set-Content -LiteralPath $propsPath -Value $lines -Encoding ASCII

Write-Host "Created Android release signing files:"
Write-Host "  $storePath"
Write-Host "  $propsPath"
Write-Host "Back up both files. Losing them means future APK updates cannot use the same signing identity."
