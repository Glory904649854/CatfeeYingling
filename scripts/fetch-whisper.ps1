param(
    [string]$Dest = ""
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($Dest)) {
    $Dest = Join-Path $root "app\src\main\cpp\whisper.cpp"
}

$url = "https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/refs/heads/master"
$tmp = Join-Path $env:TEMP "catfee-whisper-cpp.tar.gz"
$extract = Join-Path $env:TEMP "catfee-whisper-cpp-extract"

Write-Host "Downloading whisper.cpp ..."
curl.exe -L --fail --retry 3 -o $tmp $url

if (Test-Path $extract) {
    Remove-Item -Recurse -Force $extract
}
New-Item -ItemType Directory -Path $extract | Out-Null

Write-Host "Extracting ..."
tar -xzf $tmp -C $extract

$src = Join-Path $extract "whisper.cpp-master"
if (-not (Test-Path $src)) {
    throw "Unexpected archive layout: whisper.cpp-master not found"
}

if (Test-Path $Dest) {
    Remove-Item -Recurse -Force $Dest
}
Move-Item -Path $src -Destination $Dest

Remove-Item -Recurse -Force $extract
Remove-Item -Force $tmp

Write-Host "whisper.cpp -> $Dest"
Write-Host "Now run: gradlew.bat assembleDebug"
