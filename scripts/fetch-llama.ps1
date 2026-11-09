param(
    [string]$Dest = ""
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($Dest)) {
    $Dest = Join-Path $root "app\src\main\cpp\llama.cpp"
}

if (Test-Path (Join-Path $Dest "CMakeLists.txt")) {
    Write-Host "llama.cpp already present: $Dest"
    exit 0
}

Write-Host "Cloning llama.cpp (GitCode mirror) ..."
git clone --depth 1 https://gitcode.com/gh_mirrors/ll/llama.cpp $Dest

if (-not (Test-Path (Join-Path $Dest "CMakeLists.txt"))) {
    throw "llama.cpp clone failed"
}

Write-Host "llama.cpp -> $Dest"
Write-Host "Now run: gradlew.bat assembleDebug"
