param(
  [string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk",
  [string]$BuildTools = '34.0.0',
  [string]$KeyStore = "$env:USERPROFILE/.android/debug.keystore",
  [string]$Output = 'build/local-tools/wifi-p2p-module'
)
$ErrorActionPreference = 'Stop'
# Requires Java 17 on PATH/JAVA_HOME and an existing Android debug keystore.
$sourceDir = $PSScriptRoot
$binaryDir = Join-Path $Sdk "build-tools/$BuildTools"
New-Item -ItemType Directory -Force "$Output/system/product/overlay" | Out-Null
$outputDir = (Resolve-Path $Output).Path
& "$binaryDir/aapt2.exe" compile --dir "$sourceDir/res" -o "$outputDir/resources.zip"
if ($LASTEXITCODE -ne 0) { throw 'Resource compilation failed' }
& "$binaryDir/aapt2.exe" link -o "$outputDir/unsigned.apk" --manifest "$sourceDir/AndroidManifest.xml" -I "$Sdk/platforms/android-34/android.jar" "$outputDir/resources.zip"
if ($LASTEXITCODE -ne 0) { throw 'Overlay linking failed' }
& "$binaryDir/apksigner.bat" sign --ks $KeyStore --ks-pass pass:android --out "$outputDir/system/product/overlay/S9WifiP2pFix.apk" "$outputDir/unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Overlay signing failed' }
Copy-Item "$sourceDir/module.prop" "$outputDir/module.prop" -Force
Write-Output "Module prepared at $outputDir (install module.prop and system directory only)"
