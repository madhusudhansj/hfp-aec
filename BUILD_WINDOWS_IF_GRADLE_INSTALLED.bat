@echo off
where gradle >nul 2>nul
if errorlevel 1 (
  echo Gradle command not installed. Open this project in Android Studio and use Build APK(s).
  exit /b 1
)
gradle --version
gradle :app:assembleDebug
if errorlevel 1 exit /b 1
echo APK: app\build\outputs\apk\debug\app-debug.apk
