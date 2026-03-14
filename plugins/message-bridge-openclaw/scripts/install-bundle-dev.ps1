$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$BundleDir = Join-Path $RootDir "bundle"
$TargetDir = Join-Path $env:USERPROFILE ".openclaw-dev\extensions\message-bridge"

function Require-File {
  param([string]$Path)
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    Write-Error "Missing bundle artifact: $Path`nRun: npm run build:bundle"
  }
}

Require-File (Join-Path $BundleDir "index.js")
Require-File (Join-Path $BundleDir "package.json")
Require-File (Join-Path $BundleDir "openclaw.plugin.json")

if ((Test-Path -LiteralPath (Join-Path $TargetDir "dist")) -or (Test-Path -LiteralPath (Join-Path $TargetDir "node_modules"))) {
  Write-Warning "Detected legacy install layout in $TargetDir"
  Write-Host "Continuing with minimal bundle install."
}

New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null

$FilesToRemove = @("index.js", "package.json", "openclaw.plugin.json", "README.md", "package-lock.json", "tsconfig.json")
foreach ($File in $FilesToRemove) {
  $Path = Join-Path $TargetDir $File
  if (Test-Path -LiteralPath $Path) {
    Remove-Item -LiteralPath $Path -Force
  }
}

$DirsToRemove = @("dist", "src", "tests", "node_modules")
foreach ($Dir in $DirsToRemove) {
  $Path = Join-Path $TargetDir $Dir
  if (Test-Path -LiteralPath $Path) {
    Remove-Item -LiteralPath $Path -Recurse -Force
  }
}

Copy-Item (Join-Path $BundleDir "index.js") (Join-Path $TargetDir "index.js") -Force
Copy-Item (Join-Path $BundleDir "package.json") (Join-Path $TargetDir "package.json") -Force
Copy-Item (Join-Path $BundleDir "openclaw.plugin.json") (Join-Path $TargetDir "openclaw.plugin.json") -Force

$ReadmePath = Join-Path $BundleDir "README.md"
if (Test-Path -LiteralPath $ReadmePath -PathType Leaf) {
  Copy-Item $ReadmePath (Join-Path $TargetDir "README.md") -Force
}

Write-Host "Installed message-bridge bundle to:"
Write-Host "  $TargetDir"
Write-Host "Installed files:"
Get-ChildItem -LiteralPath $TargetDir | Select-Object -ExpandProperty Name | ForEach-Object {
  Write-Host "  - $_"
}
Write-Host "Next:"
Write-Host "  openclaw --dev gateway run --allow-unconfigured --verbose"
