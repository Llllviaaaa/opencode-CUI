$ErrorActionPreference = "Stop"

$PluginName = "@opencode-cui/message-bridge"
$RootDir = Split-Path -Parent $PSScriptRoot

function Get-ExistingPath {
  param([string]$JsoncPath, [string]$JsonPath)
  if (Test-Path $JsoncPath) { return $JsoncPath }
  if (Test-Path $JsonPath) { return $JsonPath }
  return $JsoncPath
}

function Test-JsonishFile {
  param([string]$Path)
  if (-not (Test-Path $Path)) { return $true }
  $trimmed = (Get-Content -Path $Path -Raw).Trim()
  return $trimmed.StartsWith("{") -and $trimmed.EndsWith("}")
}

function Redact-Value {
  param([string]$Value)
  if ([string]::IsNullOrEmpty($Value)) { return "" }
  if ($Value.Length -le 4) { return "****" }
  return "{0}****{1}" -f $Value.Substring(0, 2), $Value.Substring($Value.Length - 2, 2)
}

function Escape-JsonString {
  param([string]$Value)
  return $Value.Replace('\', '\\').Replace('"', '\"')
}

function Read-TextInput {
  param([string]$Label, [string]$CurrentValue)
  if ($null -ne $script:InputEnumerator -and $script:InputEnumerator.MoveNext()) {
    $value = [string]$script:InputEnumerator.Current
  } else {
    if ([string]::IsNullOrEmpty($CurrentValue)) {
      $value = Read-Host $Label
    } else {
      $value = Read-Host "$Label [$([string](Redact-Value $CurrentValue))]"
    }
  }
  if ([string]::IsNullOrWhiteSpace($value)) { return $CurrentValue }
  return $value.Trim()
}

function Read-SecretInput {
  param([string]$Label, [string]$CurrentValue)
  if ($null -ne $script:InputEnumerator -and $script:InputEnumerator.MoveNext()) {
    $value = [string]$script:InputEnumerator.Current
  } else {
    if ([string]::IsNullOrEmpty($CurrentValue)) {
      $secure = Read-Host -AsSecureString $Label
    } else {
      $secure = Read-Host -AsSecureString "$Label [$([string](Redact-Value $CurrentValue))]"
    }
    $ptr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
      $value = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    } finally {
      [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    }
  }
  if ([string]::IsNullOrWhiteSpace($value)) { return $CurrentValue }
  return $value.Trim()
}

function Confirm-Action {
  param([string]$Prompt)
  if ($null -ne $script:InputEnumerator -and $script:InputEnumerator.MoveNext()) {
    $answer = [string]$script:InputEnumerator.Current
  } else {
    $answer = Read-Host "$Prompt [y/N]"
  }
  return $answer -match '^(?i:y|yes)$'
}

function Set-BridgeConfig {
  param([string]$Path, [string]$Ak, [string]$Sk)
  $escapedAk = Escape-JsonString $Ak
  $escapedSk = Escape-JsonString $Sk

  if (-not (Test-Path $Path)) {
@"
{
  "auth": {
    "ak": "$escapedAk",
    "sk": "$escapedSk"
  }
}
"@ | Set-Content -Path $Path -NoNewline
    return
  }

  if (-not (Test-JsonishFile $Path)) {
    throw "无法安全解析现有 bridge 配置：$Path"
  }

  $content = Get-Content -Path $Path -Raw
  if ($content -match '"auth"\s*:') {
    $akUpdated = $false
    $skUpdated = $false
    $content = [regex]::Replace($content, '"ak"\s*:\s*"[^"]*"', { param($m) $script:akUpdated = $true; '"ak": "' + $escapedAk + '"' }, 1)
    $content = [regex]::Replace($content, '"sk"\s*:\s*"[^"]*"', { param($m) $script:skUpdated = $true; '"sk": "' + $escapedSk + '"' }, 1)
    if (-not $akUpdated) {
      $content = [regex]::Replace($content, '("auth"\s*:\s*\{)', '$1' + "`n    `"ak`": `"$escapedAk`",", 1)
    }
    if (-not $skUpdated) {
      $content = [regex]::Replace($content, '("auth"\s*:\s*\{[\s\S]*?)(\n\s*\})', '$1' + "`n    `"sk`": `"$escapedSk`"`$2", 1)
    }
  } else {
    $content = [regex]::Replace($content, '\n\}\s*$', ",`n  `"auth`": {`n    `"ak`": `"$escapedAk`",`n    `"sk`": `"$escapedSk`"`n  }`n}", 1)
  }

  Set-Content -Path $Path -Value $content -NoNewline
}

function Set-OpenCodeConfig {
  param([string]$Path)
  if (-not (Test-Path $Path)) {
@"
{
  "`$schema": "https://opencode.ai/config.json",
  "plugin": ["$PluginName"]
}
"@ | Set-Content -Path $Path -NoNewline
    return
  }

  if (-not (Test-JsonishFile $Path)) {
    throw "无法安全解析现有 OpenCode 配置：$Path"
  }

  $content = Get-Content -Path $Path -Raw
  if ($content -match [regex]::Escape($PluginName)) {
    return
  }

  if ($content -match '"plugin"\s*:\s*\[') {
    if ($content -match '"plugin"\s*:\s*\[\s*\]') {
      $content = [regex]::Replace($content, '"plugin"\s*:\s*\[\s*\]', "`"plugin`": [`"$PluginName`"]", 1)
    } else {
      $content = [regex]::Replace($content, '("plugin"\s*:\s*\[)([\s\S]*?)(\])', {
        param($m)
        $items = $m.Groups[2].Value.TrimEnd()
        $separator = if ([string]::IsNullOrWhiteSpace($items)) { "" } else { ", " }
        return $m.Groups[1].Value + $items + $separator + "`"$PluginName`"" + $m.Groups[3].Value
      }, 1)
    }
  } else {
    $content = [regex]::Replace($content, '\n\}\s*$', ",`n  `"plugin`": [`"$PluginName`"]`n}", 1)
  }

  Set-Content -Path $Path -Value $content -NoNewline
}

$scope = "user"
if ($args.Length -ge 2 -and $args[0] -eq "-Scope") {
  if ($args[1] -ne "user" -and $args[1] -ne "project") {
    throw "-Scope 仅支持 user 或 project"
  }
  $scope = $args[1]
}

$script:InputEnumerator = $null
if ($MyInvocation.ExpectingInput) {
  $script:InputEnumerator = @($input).GetEnumerator()
}

if ($scope -eq "user") {
  $configHome = if ($env:XDG_CONFIG_HOME) { $env:XDG_CONFIG_HOME } else { Join-Path $HOME ".config" }
  $configDir = Join-Path $configHome "opencode"
  $opencodeConfig = Get-ExistingPath (Join-Path $configDir "opencode.jsonc") (Join-Path $configDir "opencode.json")
} else {
  $configDir = Join-Path (Get-Location) ".opencode"
  $opencodeConfig = Get-ExistingPath (Join-Path (Get-Location) "opencode.jsonc") (Join-Path (Get-Location) "opencode.json")
}

$bridgeConfig = Get-ExistingPath (Join-Path $configDir "message-bridge.jsonc") (Join-Path $configDir "message-bridge.json")

$currentAk = ""
$currentSk = ""
if (Test-Path $bridgeConfig) {
  $bridgeContent = Get-Content -Path $bridgeConfig -Raw
  $akMatch = [regex]::Match($bridgeContent, '"ak"\s*:\s*"([^"]*)"')
  $skMatch = [regex]::Match($bridgeContent, '"sk"\s*:\s*"([^"]*)"')
  if ($akMatch.Success) { $currentAk = $akMatch.Groups[1].Value }
  if ($skMatch.Success) { $currentSk = $skMatch.Groups[1].Value }
}

Write-Host "Message Bridge 初始化"
Write-Host "作用域: $scope"
Write-Host "bridge 配置: $bridgeConfig"
Write-Host "OpenCode 配置: $opencodeConfig"
Write-Host ""

$ak = Read-TextInput "请输入 AK" $currentAk
if ([string]::IsNullOrWhiteSpace($ak)) {
  throw "AK 不能为空"
}

$sk = Read-SecretInput "请输入 SK" $currentSk
if ([string]::IsNullOrWhiteSpace($sk)) {
  throw "SK 不能为空"
}

Write-Host ""
Write-Host "将写入以下内容："
Write-Host "- bridge auth.ak: $(Redact-Value $ak)"
Write-Host "- bridge auth.sk: $(Redact-Value $sk)"
Write-Host "- OpenCode plugin: $PluginName"
Write-Host ""

if (-not (Confirm-Action "确认写入以上配置")) {
  Write-Host "已取消，未写入任何文件。"
  exit 0
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $bridgeConfig) | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $opencodeConfig) | Out-Null

Set-BridgeConfig -Path $bridgeConfig -Ak $ak -Sk $sk
Set-OpenCodeConfig -Path $opencodeConfig

Write-Host "配置完成。"
Write-Host "1. 已写入 $bridgeConfig"
Write-Host "2. 已更新 $opencodeConfig"
Write-Host "3. 下次启动 OpenCode 时会自动安装并加载 npm 插件。"
