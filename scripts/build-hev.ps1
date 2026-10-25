param([string]$Sdk=(Join-Path $env:TEMP 'rayren-android-sdk'),[string]$SourceRoot='')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskHev=Join-Path $env:TEMP 'rayren-hev-build'
New-Item -ItemType Directory -Force -Path $taskHev | Out-Null
if(!$SourceRoot){$SourceRoot=if(Test-Path (Join-Path $taskRoot 'native\upstreams\hev-socks5-tunnel')){Join-Path $taskRoot 'native\upstreams\hev-socks5-tunnel'}else{Join-Path $taskRoot '.research\veil\hev-socks5-tunnel'}}
& python (Join-Path $PSScriptRoot 'stage-hev.py') $SourceRoot (Join-Path $taskHev 'jni')
if ($LASTEXITCODE) { throw 'HEV staging failed' }
Push-Location $taskHev
try {
    & (Join-Path $Sdk 'ndk\28.2.13676358\ndk-build.cmd') 'NDK_PROJECT_PATH=.' 'APP_BUILD_SCRIPT=jni/Android.mk' 'APP_ABI=arm64-v8a' 'APP_PLATFORM=android-26' 'APP_CFLAGS=-O3 -DPKGNAME=com/rayren/android/vpn' 'APP_LDFLAGS=-Wl,-z,max-page-size=16384' '-j4'
    if ($LASTEXITCODE) { throw 'HEV build failed' }
    $dest=Join-Path $taskRoot 'app\src\main\jniLibs\arm64-v8a'
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Copy-Item -LiteralPath 'libs\arm64-v8a\libhev-socks5-tunnel.so' -Destination $dest -Force
} finally { Pop-Location }
