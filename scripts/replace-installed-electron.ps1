# Replace the installed Logseq electron.js with the freshly built one.
# Run as administrator.

$ErrorActionPreference = 'Stop'

$src     = 'D:\logseq-feng\logseq-feng\static\electron.js'
$dstDir  = 'C:\Program Files (x86)\Logseq\app-2.0.1\resources\app'
$dst     = Join-Path $dstDir 'electron.js'
$backup  = Join-Path $dstDir 'electron.js.bak-mcp-fix'

if (-not (Test-Path $src)) { throw "Source not found: $src" }
if (-not (Test-Path $dst)) { throw "Destination not found: $dst" }

# Re-elevate if not already running as admin.
$me = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $me.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "Re-launching as administrator..." -ForegroundColor Yellow
    Start-Process powershell -Verb RunAs -ArgumentList "-NoProfile","-ExecutionPolicy","Bypass","-File",$PSCommandPath
    exit
}

# Make sure Logseq is fully closed.
$running = Get-Process Logseq -ErrorAction SilentlyContinue
if ($running) {
    Write-Host "Logseq is still running. Stopping..." -ForegroundColor Yellow
    $running | Stop-Process -Force
    Start-Sleep -Seconds 2
}

Write-Host "Backing up: $dst -> $backup"
Copy-Item -Force $dst $backup

Write-Host "Installing new electron.js (size $(Get-Item $src | Select-Object -ExpandProperty Length) bytes)"
Copy-Item -Force $src $dst

Write-Host ""
Write-Host "Done. Now relaunch Logseq from the Start menu." -ForegroundColor Green
Write-Host ""
Write-Host "Press any key to close..."
[void][System.Console]::ReadKey($true)
