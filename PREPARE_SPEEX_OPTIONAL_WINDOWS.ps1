# Optional: stage the known-good ARM64 SpeexDSP library from the rooted S9+.
# WebRTC AEC3 works without Speex. Speex is only an A/B benchmark.
$ErrorActionPreference="Stop"
Set-Location $PSScriptRoot
$dest="app/src/main/jniLibs/arm64-v8a"
New-Item -ItemType Directory -Force -Path $dest | Out-Null
adb shell su -c "cp /data/local/tmp/libspeexdsp.so /sdcard/Download/libspeex_hfp_test.so && chmod 644 /sdcard/Download/libspeex_hfp_test.so"
if($LASTEXITCODE -ne 0){throw "Could not copy the existing SpeexDSP .so on phone"}
adb pull /sdcard/Download/libspeex_hfp_test.so "$dest/libspeexdsp.so"
if($LASTEXITCODE -ne 0){throw "ADB pull failed"}
adb shell rm -f /sdcard/Download/libspeex_hfp_test.so
Write-Host "Optional SpeexDSP native library packaged. Rebuild debug APK."
