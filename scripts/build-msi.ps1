# scripts/build-msi.ps1
#
# One-shot Windows packaging script for logseq-feng.
# Produces: Squirrel installer (.exe + .nupkg), WiX installer (.msi),
#           portable zip — all under static/out/make/.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/build-msi.ps1
#
# Optional switches:
#   -SkipInstall     Skip the top-level `yarn install` (use when deps are warm).
#   -SkipSubInstall  Skip `yarn install` inside static/ (use when it's warm).
#   -SkipClean       Don't wipe static/out/make before building.
#   -OutDir <path>   Directory to copy final artifacts into.
#                    Default: static/out/make (the electron-forge default).
#
# Prerequisites:
#   - Node >= 22.20
#   - Yarn (classic) 1.22+
#   - Clojure CLI (tools.deps)
#   - WiX Toolset v3 on PATH (candle.exe / light.exe). electron-forge's
#     maker-wix needs this; WiX v4/v5 does NOT work here.
#     Install via: choco install wixtoolset, then add
#     "C:\Program Files (x86)\WiX Toolset v3.11\bin" to PATH.

[CmdletBinding()]
param(
    [switch]$SkipInstall,
    [switch]$SkipSubInstall,
    [switch]$SkipClean,
    [string]$OutDir
)

$ErrorActionPreference = 'Stop'
$InformationPreference  = 'Continue'

# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------

function Write-Step([string]$msg) {
    Write-Host ""
    Write-Host ">>> $msg" -ForegroundColor Cyan
}

function Assert-Tool([string]$name, [string]$hint) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if (-not $cmd) {
        throw "Required tool '$name' not found on PATH. $hint"
    }
    Write-Host "  $name -> $($cmd.Source)"
}

function Invoke-Checked([string]$label, [scriptblock]$block) {
    Write-Step $label
    & $block
    if ($LASTEXITCODE -ne 0) {
        throw "$label failed with exit code $LASTEXITCODE"
    }
}

# --------------------------------------------------------------------------
# locate project root (one level up from this script)
# --------------------------------------------------------------------------

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$projRoot  = Split-Path -Parent $scriptDir
Set-Location $projRoot
Write-Host "Project root: $projRoot"

# --------------------------------------------------------------------------
# prerequisite checks
# --------------------------------------------------------------------------

Write-Step "Checking prerequisites"
Assert-Tool 'node'    'Install Node 22 LTS from https://nodejs.org/.'
Assert-Tool 'yarn'    'Install Yarn classic via `npm install -g yarn@1.22.22`.'
Assert-Tool 'clojure' 'Install Clojure CLI: https://clojure.org/guides/install_clojure.'

# WiX is required for the .msi artifact. Try common install paths if not on PATH.
$hasWix = Get-Command 'candle.exe' -ErrorAction SilentlyContinue
if (-not $hasWix) {
    $wixCandidates = @(
        'C:\wix311',
        'C:\Program Files (x86)\WiX Toolset v3.11\bin',
        'C:\Program Files\WiX Toolset v3.11\bin'
    )
    foreach ($p in $wixCandidates) {
        if (Test-Path (Join-Path $p 'candle.exe')) {
            Write-Host "  Found WiX at $p — prepending to PATH for this build"
            $env:PATH = "$p;$env:PATH"
            $hasWix = Get-Command 'candle.exe' -ErrorAction SilentlyContinue
            break
        }
    }
}
if (-not $hasWix) {
    Write-Warning "WiX Toolset v3 (candle.exe) not on PATH; the .msi artifact will fail. Install via ``choco install wixtoolset`` or extract https://github.com/wixtoolset/wix3/releases/download/wix3112rtm/wix311-binaries.zip to C:\wix311."
} else {
    Write-Host "  candle.exe -> $($hasWix.Source)"
}

# --------------------------------------------------------------------------
# yarn installs
# --------------------------------------------------------------------------

if (-not $SkipInstall) {
    Invoke-Checked "yarn install (root)" {
        yarn install --frozen-lockfile
    }
} else {
    Write-Host "Skipping root yarn install (-SkipInstall)"
}

$staticDir = Join-Path $projRoot 'static'
if (-not (Test-Path $staticDir)) {
    New-Item -ItemType Directory -Path $staticDir | Out-Null
}

$staticPkg = Join-Path $staticDir 'package.json'
$staticSrc = Join-Path $projRoot 'resources/package.json'
if (-not (Test-Path $staticPkg)) {
    # gulp:build copies this, but copy it eagerly so sub-install works even
    # if someone runs this script before gulp has ever run.
    Copy-Item $staticSrc $staticPkg
}

if (-not $SkipSubInstall) {
    if (-not (Test-Path (Join-Path $staticDir 'node_modules'))) {
        Invoke-Checked "yarn install (static/)" {
            Push-Location $staticDir
            try { yarn install }
            finally { Pop-Location }
        }
    } else {
        Write-Host "static/node_modules exists; skipping sub-install"
    }
} else {
    Write-Host "Skipping static/ yarn install (-SkipSubInstall)"
}

# --------------------------------------------------------------------------
# clean prior artifacts
# --------------------------------------------------------------------------

$defaultOutDir = Join-Path $staticDir 'out/make'
if (-not $OutDir) { $OutDir = $defaultOutDir }

if (-not $SkipClean) {
    if (Test-Path $defaultOutDir) {
        Write-Step "Cleaning $defaultOutDir"
        Remove-Item -Recurse -Force $defaultOutDir
    }
}

# --------------------------------------------------------------------------
# run the full release pipeline
# --------------------------------------------------------------------------
# `yarn release-electron` chains:
#   1. gulp:build        — static assets + resources/package.json copy
#   2. cljs:release-electron — shadow-cljs release (app + db-worker + electron + publishing)
#   3. webpack-app-build — js bundles for app/excalidraw/mind-map/univer-sheet
#   4. gulp electronMaker — stamps version + runs `electron-forge make`

Invoke-Checked "yarn release-electron" {
    yarn release-electron
}

# --------------------------------------------------------------------------
# list final artifacts
# --------------------------------------------------------------------------

Write-Step "Build finished — artifacts under $defaultOutDir"

if (Test-Path $defaultOutDir) {
    Get-ChildItem -Recurse -File $defaultOutDir |
        Where-Object { $_.Extension -in '.exe','.msi','.nupkg','.zip','.deb','.rpm','.AppImage' } |
        ForEach-Object {
            $sizeMb = [math]::Round($_.Length / 1MB, 1)
            $rel    = $_.FullName.Substring($projRoot.Length + 1)
            Write-Host ("  {0,8:N1} MB  {1}" -f $sizeMb, $rel)
        }
}

if ($OutDir -ne $defaultOutDir) {
    Write-Step "Copying artifacts to $OutDir"
    if (-not (Test-Path $OutDir)) {
        New-Item -ItemType Directory -Path $OutDir | Out-Null
    }
    Copy-Item -Recurse -Force -Path (Join-Path $defaultOutDir '*') -Destination $OutDir
}

Write-Host ""
Write-Host "Done." -ForegroundColor Green
