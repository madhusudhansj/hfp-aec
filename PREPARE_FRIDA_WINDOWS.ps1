param([string]$PythonExe='python')
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
$version='17.2.3'
$archive=Join-Path $env:TEMP 'hfp-frida-inject-17.2.3-arm64.xz'
Invoke-WebRequest "https://github.com/frida/frida/releases/download/$version/frida-inject-$version-android-arm64.xz" -OutFile $archive
if((Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'f74ce5b1c7a587d4e19e9541d2c0c5131e0e645b0bcd9c4c32f208a9c500656c'){throw 'Frida archive hash mismatch'}
$destination=Join-Path $PSScriptRoot 'app/src/main/assets/bridge/frida-inject'
& $PythonExe -c 'import lzma,pathlib,sys; pathlib.Path(sys.argv[2]).write_bytes(lzma.decompress(pathlib.Path(sys.argv[1]).read_bytes()))' $archive $destination
if($LASTEXITCODE -ne 0){throw 'Frida decompression failed; Python 3 with lzma is required'}
if((Get-FileHash $destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne '795e05b765a68c9272e5abc0bac9536a4ee74799aff2f893fa69dad2a580b8e7'){throw 'Frida executable hash mismatch'}
Write-Host 'Official Frida ARM64 injector staged; source and license links are in docs/V030_CALL_BRIDGE.md.'
