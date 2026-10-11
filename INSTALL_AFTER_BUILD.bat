@echo off
set APK=app\build\outputs\apk\debug\app-debug.apk
if not exist "%APK%" (
  echo No APK found. Build the project using Android Studio first.
  exit /b 1
)
adb install -r "%APK%"
if errorlevel 1 exit /b 1
adb shell am start -n com.hfpengine.s9/.MainActivity
