@echo off
setlocal
cd /d "%~dp0"
if not exist "app\src\main\jniLibs\arm64-v8a" mkdir "app\src\main\jniLibs\arm64-v8a"
echo Copying the previously tested ARM64 SpeexDSP binary from the S9+.
echo The S9+ must be connected with ADB and root available.
adb shell su -c "cp /data/local/tmp/libspeexdsp.so /sdcard/Download/libspeexdsp_hfp_tmp.so && chmod 644 /sdcard/Download/libspeexdsp_hfp_tmp.so"
if errorlevel 1 goto failed
adb pull /sdcard/Download/libspeexdsp_hfp_tmp.so app\src\main\jniLibs\arm64-v8a\libspeexdsp.so
if errorlevel 1 goto failed
adb shell rm -f /sdcard/Download/libspeexdsp_hfp_tmp.so
echo SpeexDSP is now packaged for the debug APK. Build in Android Studio.
exit /b 0
:failed
echo Could not stage SpeexDSP. Verify /data/local/tmp/libspeexdsp.so on the S9+.
exit /b 1
