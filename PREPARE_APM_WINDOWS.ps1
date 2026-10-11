# Run from PowerShell inside project root (Android Studio project).
# Pin version AND sha256. No ROM/root modifications are required.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$version = "m155.8059.4.0"
$url = "https://github.com/Sqhh99/webrtc-audio-processing/releases/download/$version/webrtc-audio-processing.android.tar.gz"
$expected = "0d9d3d9d801e2cfcd3b73a7296352037ac6c0b41a2632bee675c0162913e253b"
$archive = Join-Path $env:TEMP "webrtc-audio-processing-$version.android.tar.gz"
$stage = Join-Path $env:TEMP "hfp-webrtc-apm-$version"
Write-Host "Downloading pinned WebRTC APM Android release $version"
Invoke-WebRequest -Uri $url -OutFile $archive
$hash = (Get-FileHash -Algorithm SHA256 $archive).Hash.ToLowerInvariant()
if ($hash -ne $expected) { throw "WebRTC APM SHA256 mismatch: $hash" }
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force -Path $stage | Out-Null
tar -xzf $archive -C $stage
$include = Join-Path $stage "include"
$lib = Join-Path $stage "lib/arm64-v8a/libwebrtc-audio-processing-3.so"
if (!(Test-Path (Join-Path $include "webrtc-audio-processing-3/api/audio/audio_processing.h"))) {
    throw "The archive did not contain the expected WebRTC APM headers"
}
if (!(Test-Path $lib)) { throw "The archive did not contain an arm64-v8a WebRTC library" }
$dstInclude = "app/src/main/apm/include"
$dstLib = "app/src/main/jniLibs/arm64-v8a"
if (Test-Path $dstInclude) { Remove-Item -Recurse -Force $dstInclude }
New-Item -ItemType Directory -Force -Path $dstInclude,$dstLib | Out-Null
Copy-Item -Recurse -Force (Join-Path $include "*") $dstInclude
Copy-Item -Force $lib $dstLib
New-Item -ItemType Directory -Force -Path "app/src/main/assets/third_party_licenses" | Out-Null
if (Test-Path (Join-Path $stage "licenses")) {
    Copy-Item -Recurse -Force (Join-Path $stage "licenses/*") "app/src/main/assets/third_party_licenses"
}
Write-Host "Installed full WebRTC APM headers + arm64 .so in Android project."
Write-Host "Optional Speex baseline: run PREPARE_SPEEX_OPTIONAL_WINDOWS.ps1"
Write-Host "Open Android Studio and Build > Build APK(s)."
