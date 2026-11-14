# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
<#
.SYNOPSIS
  Starts a self-contained Orbit demo: the fictional provider plus an Orbit server with a demo account.

.DESCRIPTION
  1. Starts tools/demo/demo_provider.py (fictional Xtream provider) on 127.0.0.1:<ProviderPort>.
  2. Starts the Orbit server from this repo (web/server/run.py) on 127.0.0.1:<Port> with a scratch data folder
     (default %TEMP%\orbit-demo, wiped on every start unless -Keep), serving the web app from web/frontend.
  3. Through the server's own HTTP API: creates the owner account "demo" with the one-time setup token, adds the
     profiles "Kids" (kids profile) and "Sam", and signs the household in to the demo provider.
  4. Prints the address, the login and how to link a TV over adb reverse.

  The processes keep running in the background after the script returns. Stop them with:
      powershell -File tools\demo\run_demo.ps1 -Stop            (add -Port N for a demo on another port)
  Process ids, data and logs are kept per server port (%TEMP%\orbit-demo-<Port>.pids, orbit-demo-<Port>,
  orbit-demo-<Port>-logs), so -Stop on one port never stops another demo.

  The demo password comes from -Password or is generated and printed here. It is never written to a file.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\demo\run_demo.ps1
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\demo\run_demo.ps1 -Password "pick-a-demo-password"
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\demo\run_demo.ps1 -Stop
#>
[CmdletBinding()]
param(
    [string]$Password = "",
    [int]$Port = 8760,
    [int]$ProviderPort = 8795,
    [string]$DataDir = "",
    [switch]$Keep,
    [switch]$NoVideo,
    [switch]$Stop
)

$ErrorActionPreference = "Stop"
$Repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
# per server port, so several demos can run side by side and -Stop on one port never touches another
if (-not $DataDir) { $DataDir = Join-Path $env:TEMP "orbit-demo-$Port" }
$PidFile = Join-Path $env:TEMP "orbit-demo-$Port.pids"
$LogDir = Join-Path $env:TEMP "orbit-demo-$Port-logs"
$LegacyPidFile = Join-Path $env:TEMP "orbit-demo.pids"     # one shared file before; it belonged to the default port

function Stop-Demo {
    if ($Port -eq 8760 -and (Test-Path $LegacyPidFile) -and -not (Test-Path $PidFile)) { Move-Item $LegacyPidFile $PidFile }
    if (Test-Path $PidFile) {
        foreach ($id in (Get-Content $PidFile)) {
            if ($id -match '^\d+$' -and (Get-Process -Id $id -ErrorAction SilentlyContinue)) {
                & taskkill.exe /T /F /PID $id 2>&1 | Out-Null       # the whole tree: py launcher, python, ffmpeg
            }
        }
        Remove-Item $PidFile -Force
        Write-Host "Orbit demo on port $Port stopped."
    } else {
        Write-Host "No Orbit demo is running on port $Port (no $PidFile)."
    }
}

if ($Stop) { Stop-Demo; return }
if (Test-Path $PidFile) { Write-Host "An Orbit demo seems to be running on port $Port; stopping it first."; Stop-Demo }

# ---- Python
$Py = $null; $PyArgs = @()
if (Get-Command py -ErrorAction SilentlyContinue) { $Py = "py"; $PyArgs = @("-3") }
elseif (Get-Command python3 -ErrorAction SilentlyContinue) { $Py = "python3" }
elseif (Get-Command python -ErrorAction SilentlyContinue) { $Py = "python" }
else { throw "Python 3 is needed (the py launcher, python3 or python on PATH)." }

foreach ($p in @($Port, $ProviderPort)) {
    if (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue) {
        throw "Port $p is already in use. Pick another with -Port / -ProviderPort."
    }
}

$vendor = Join-Path $Repo "web\frontend\vendor"
if (-not (Test-Path (Join-Path $vendor "hls.min.js")) -or -not (Test-Path (Join-Path $vendor "mpegts.min.js"))) {
    Write-Warning ("web\frontend\vendor\hls.min.js / mpegts.min.js are missing (not committed; see web/README.md). " +
                   "The web player cannot play until you copy them there.")
}

# ---- password: from the parameter, else generated (printed below, never stored)
$Generated = $false
if (-not $Password) {
    $chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789".ToCharArray()
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = New-Object byte[] 18
    $rng.GetBytes($bytes)
    $Password = -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
    $Generated = $true
}

# ---- folders
if (-not $Keep -and (Test-Path $DataDir)) { Remove-Item $DataDir -Recurse -Force }
New-Item -ItemType Directory -Force $DataDir | Out-Null
New-Item -ItemType Directory -Force $LogDir | Out-Null

function Start-Bg([string]$name, [string[]]$arguments, [string]$cwd) {
    $out = Join-Path $LogDir "$name.log"
    $err = Join-Path $LogDir "$name.err.log"
    $proc = Start-Process -FilePath $Py -ArgumentList ($PyArgs + $arguments) -WorkingDirectory $cwd -PassThru `
        -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err
    Add-Content -Path $PidFile -Value $proc.Id
    return $proc
}

function Wait-Url([string]$url, [int]$seconds, $proc, [string]$name) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if ($proc.HasExited) { throw "$name stopped; see $LogDir\$name.err.log" }
        try { Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 3 | Out-Null; return } catch { Start-Sleep -Milliseconds 400 }
    }
    throw "$name did not answer at $url within $seconds s; see $LogDir"
}

# ---- 1. the fictional provider
$provArgs = @((Join-Path $Repo "tools\demo\demo_provider.py"), "--port", "$ProviderPort", "--bind", "127.0.0.1")
if ($NoVideo) { $provArgs += "--no-video" }
Write-Host "Starting the demo provider on 127.0.0.1:$ProviderPort (the test video takes a few seconds)..."
$prov = Start-Bg "provider" $provArgs $Repo
Wait-Url "http://127.0.0.1:$ProviderPort/" 180 $prov "provider"

# ---- 2. the Orbit server (environment only for this child)
$envNames = "TVBRO_PORT", "TVBRO_BIND", "TVBRO_DATA", "TVBRO_STATIC", "TVBRO_ALLOW_LOOPBACK_SOURCES", "TVBRO_KEY", "TVBRO_KEY_FILE"
$saved = @{}
foreach ($n in $envNames) { $saved[$n] = [Environment]::GetEnvironmentVariable($n, "Process") }
try {
    $env:TVBRO_PORT = "$Port"
    $env:TVBRO_BIND = "127.0.0.1"
    $env:TVBRO_DATA = $DataDir
    $env:TVBRO_STATIC = (Join-Path $Repo "web\frontend")
    $env:TVBRO_ALLOW_LOOPBACK_SOURCES = "1"      # the demo provider is on loopback
    $env:TVBRO_KEY = $null; $env:TVBRO_KEY_FILE = $null   # a throwaway key.bin in the scratch data folder
    Write-Host "Starting the Orbit server on 127.0.0.1:$Port (data: $DataDir)..."
    $srv = Start-Bg "server" @("run.py") (Join-Path $Repo "web\server")
} finally {
    foreach ($n in $envNames) { [Environment]::SetEnvironmentVariable($n, $saved[$n], "Process") }
}
$Base = "http://127.0.0.1:$Port"
Wait-Url "$Base/api/health" 60 $srv "server"

# ---- 3. accounts, profiles and the playlist, through the server's API
function Call([string]$method, [string]$path, $body, [hashtable]$headers) {
    $h = @{ "X-TvBro" = "1" }
    if ($headers) { foreach ($k in $headers.Keys) { $h[$k] = $headers[$k] } }
    $json = if ($null -ne $body) { $body | ConvertTo-Json -Compress } else { $null }
    $r = Invoke-WebRequest -Uri ($Base + $path) -Method $method -Body $json -ContentType "application/json" `
        -Headers $h -WebSession $script:web -UseBasicParsing -TimeoutSec 120
    if ($r.Content) { return ($r.Content | ConvertFrom-Json) }
}

$script:web = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$status = Call GET "/v1/status" $null $null
if ($status.setup) {
    $tokenFile = Join-Path $DataDir "orbit-setup-token"
    for ($i = 0; $i -lt 50 -and -not (Test-Path $tokenFile); $i++) { Start-Sleep -Milliseconds 200 }
    $token = (Get-Content $tokenFile -Raw).Trim()
    $me = Call POST "/v1/setup" @{ token = $token; username = "demo"; password = $Password; displayName = "Demo" } $null
    $csrf = @{ "X-CSRF-Token" = $me.csrf }
    Call POST "/v1/profiles" @{ name = "Kids"; kids = $true } $csrf | Out-Null
    Call POST "/v1/profiles" @{ name = "Sam" } $csrf | Out-Null
    $pl = Call POST "/api/signin" @{ kind = "xtream"; name = "Demo TV"; server = "http://127.0.0.1:$ProviderPort";
                                     user = "demo"; pass = "demo" } $csrf
    Write-Host "Owner 'demo' created with profiles Demo, Kids and Sam; demo playlist added ($($pl.id))."
    $rows = @()
    for ($i = 0; $i -lt 30 -and -not ($rows | Where-Object { @($_.items).Count -gt 0 }); $i++) {
        try { $rows = @((Call GET "/v1/catalog/home" $null $csrf).rows) } catch { }   # the first sync runs in the background
        if (-not ($rows | Where-Object { @($_.items).Count -gt 0 })) { Start-Sleep -Seconds 1 }
    }
    if ($rows | Where-Object { @($_.items).Count -gt 0 }) {
        Write-Host ("Catalogue home: " + (($rows | ForEach-Object { "$($_.title) ($(@($_.items).Count))" }) -join ", "))
    } else { Write-Host "The catalogue is still syncing; it appears in the app shortly." }
} else {
    Write-Host "This data folder already has accounts (-Keep): nothing set up. Sign in with the earlier password."
    $Generated = $false
}

Write-Host ""
Write-Host "Orbit demo is running."
Write-Host "  Web app:   $Base   (username demo)"
if ($Generated) { Write-Host "  Password:  $Password   (generated for this run; not saved anywhere)" }
elseif ($status.setup) { Write-Host "  Password:  the one you passed with -Password" }
Write-Host "  Provider:  http://127.0.0.1:$ProviderPort  (fictional; Xtream login demo / demo)"
Write-Host "  Logs:      $LogDir"
Write-Host ""
Write-Host "Link a TV over USB/ADB (the TV then reaches this PC's loopback):"
Write-Host "  adb reverse tcp:$Port tcp:$Port"
Write-Host "  adb reverse tcp:$ProviderPort tcp:$ProviderPort   # artwork is loaded from the provider's address"
Write-Host "  On the TV choose the server http://127.0.0.1:$Port, then approve the code in the web app (signed in as demo)."
Write-Host ""
$portArg = if ($Port -ne 8760) { " -Port $Port" } else { "" }
Write-Host "Stop everything:  powershell -File `"$PSCommandPath`" -Stop$portArg"
