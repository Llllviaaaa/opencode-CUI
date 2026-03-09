param(
    [string]$RedisRoot = "D:\010-apps\redis-5.0-cluster",
    [string]$Distro = "ubuntu2204",
    [int]$Port = 6379
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (Get-Variable PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

function Convert-ToWslPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$WindowsPath
    )

    $resolvedPath = (Resolve-Path $WindowsPath).Path
    $normalized = $resolvedPath -replace "\\", "/"

    if ($normalized -match "^([A-Za-z]):/(.*)$") {
        return "/mnt/$($matches[1].ToLower())/$($matches[2])"
    }

    throw "Unsupported Windows path: $WindowsPath"
}

function Test-TcpPort {
    param(
        [Parameter(Mandatory = $true)]
        [string]$HostName,

        [Parameter(Mandatory = $true)]
        [int]$Port
    )

    $client = [System.Net.Sockets.TcpClient]::new()

    try {
        $result = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $result.AsyncWaitHandle.WaitOne(1000, $false)) {
            return $false
        }

        $client.EndConnect($result)
        return $true
    }
    catch {
        return $false
    }
    finally {
        $client.Dispose()
    }
}

function Test-RedisPing {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Distro,

        [Parameter(Mandatory = $true)]
        [string]$RedisCli,

        [Parameter(Mandatory = $true)]
        [int]$Port
    )

    $output = & wsl.exe -d $Distro -- bash -lc "'$RedisCli' -p $Port ping 2>/dev/null" 2>$null
    return $LASTEXITCODE -eq 0 -and (($output | Out-String) -match "(^|\\s)PONG(\\s|$)")
}

function Resolve-WslDistroName {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RequestedName
    )

    $availableDistros = @(
        & wsl.exe -l -q 2>$null |
            ForEach-Object { (($_ | Out-String) -replace "`0", "").Trim() } |
            Where-Object { $_ }
    )

    if ($availableDistros -contains $RequestedName) {
        return $RequestedName
    }

    $knownAliases = @{
        "ubuntu2204" = "Ubuntu-22.04"
        "ubuntu2404" = "Ubuntu-24.04"
    }

    if ($knownAliases.ContainsKey($RequestedName) -and ($availableDistros -contains $knownAliases[$RequestedName])) {
        return $knownAliases[$RequestedName]
    }

    throw "WSL distro '$RequestedName' was not found. Available distros: $($availableDistros -join ', ')"
}

$redisServerWindows = Join-Path $RedisRoot "src\redis-5.0.14\src\redis-server"
$redisCliWindows = Join-Path $RedisRoot "src\redis-5.0.14\src\redis-cli"

if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
    throw "wsl.exe is not available. Install WSL or update the script to use another launcher."
}

if (-not (Test-Path $RedisRoot -PathType Container)) {
    throw "Redis root does not exist: $RedisRoot"
}

if (-not (Test-Path $redisServerWindows -PathType Leaf)) {
    throw "redis-server was not found: $redisServerWindows"
}

if (-not (Test-Path $redisCliWindows -PathType Leaf)) {
    throw "redis-cli was not found: $redisCliWindows"
}

$redisRootWsl = Convert-ToWslPath -WindowsPath $RedisRoot
$resolvedDistro = Resolve-WslDistroName -RequestedName $Distro
$redisServerWsl = "$redisRootWsl/src/redis-5.0.14/src/redis-server"
$redisCliWsl = "$redisRootWsl/src/redis-5.0.14/src/redis-cli"
$redisNodeDirWindows = Join-Path $RedisRoot "nodes\$Port"
$redisNodeDirWsl = "$redisRootWsl/nodes/$Port"
$redisLogWindows = Join-Path $redisNodeDirWindows "redis.log"
$redisLogWsl = "$redisNodeDirWsl/redis.log"
$redisPidWsl = "$redisNodeDirWsl/redis.pid"

if (Test-RedisPing -Distro $resolvedDistro -RedisCli $redisCliWsl -Port $Port) {
    Write-Host "Redis is already running on 127.0.0.1:$Port" -ForegroundColor Green
    Write-Host "Log file: $redisLogWindows" -ForegroundColor DarkGray
    exit 0
}

if (Test-TcpPort -HostName "127.0.0.1" -Port $Port) {
    throw "127.0.0.1:$Port is already in use, but redis-cli ping failed. Check the port owner before starting Redis."
}

Write-Host "Starting local Redis on 127.0.0.1:$Port via WSL distro '$resolvedDistro'..." -ForegroundColor Cyan

$startScript = @"
set -euo pipefail
mkdir -p '$redisNodeDirWsl'
'$redisServerWsl' \
  --port $Port \
  --bind 127.0.0.1 \
  --dir '$redisNodeDirWsl' \
  --appendonly yes \
  --daemonize yes \
  --protected-mode no \
  --logfile '$redisLogWsl' \
  --pidfile '$redisPidWsl'
"@

& wsl.exe -d $resolvedDistro -- bash -lc $startScript 2>$null

if ($LASTEXITCODE -ne 0) {
    throw "Redis start command failed with exit code $LASTEXITCODE."
}

for ($attempt = 1; $attempt -le 10; $attempt++) {
    Start-Sleep -Milliseconds 500

    if (Test-RedisPing -Distro $resolvedDistro -RedisCli $redisCliWsl -Port $Port) {
        Write-Host "Redis started successfully on 127.0.0.1:$Port" -ForegroundColor Green
        Write-Host "Log file: $redisLogWindows" -ForegroundColor DarkGray
        Write-Host "Try: redis-cli -p $Port ping" -ForegroundColor DarkGray
        exit 0
    }
}

throw "Redis did not become ready on 127.0.0.1:$Port. Check the log: $redisLogWindows"
