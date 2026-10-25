param(
    [string]$Sdk = (Join-Path $env:TEMP 'rayren-android-sdk'),
    [string]$GoExe = '',
    [string]$SourceRoot = '',
    [string]$Abi = 'arm64-v8a'
)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
if (!$SourceRoot) { $SourceRoot=if(Test-Path (Join-Path $taskRoot 'native\upstreams')){Join-Path $taskRoot 'native\upstreams'}else{Join-Path $taskRoot '.research\veil'} }
if (!(Test-Path (Join-Path $SourceRoot 'AndroidLibXrayLite\go.mod'))) { & (Join-Path $PSScriptRoot 'fetch-upstreams.ps1') }
if (!$GoExe) { $taskGo=Get-Command go -ErrorAction SilentlyContinue; $GoExe=if($taskGo){$taskGo.Source}else{Join-Path (Split-Path $taskRoot -Parent) '.toolchains\go1.26.3\go\bin\go.exe'} }
$taskNative=Join-Path $env:TEMP 'rayren-native-build'
New-Item -ItemType Directory -Force -Path $taskNative,(Join-Path $taskNative 'bridge'),(Join-Path $taskNative 'bin'),(Join-Path $taskRoot 'app\libs'),(Join-Path $taskRoot 'artifacts') | Out-Null
foreach($module in @('AndroidLibXrayLite','OpenFlux','olcrtc')) {
    $dest=Join-Path $taskNative $module
    if (!(Test-Path -LiteralPath $dest)) { Copy-Item -LiteralPath (Join-Path $SourceRoot $module) -Destination $dest -Recurse }
}
Copy-Item -Path (Join-Path $taskRoot 'native\bridge\*.go') -Destination (Join-Path $taskNative 'bridge') -Force
$mod=@'
module rayrencore
go 1.27
require (
 github.com/2dust/AndroidLibXrayLite v0.0.0
 github.com/openlibrecommunity/olcrtc v0.0.0
 openflux-mobile v0.0.0
 golang.org/x/mobile v0.0.0-20260520154334-0e4426e1883d
)
replace github.com/2dust/AndroidLibXrayLite => ../AndroidLibXrayLite
replace github.com/openlibrecommunity/olcrtc => ../olcrtc
replace github.com/p1neappleXpress/OpenFlux => ../OpenFlux
replace openflux-mobile => ../OpenFlux/mobile
replace github.com/pion/webrtc/v4 => github.com/pion/webrtc/v4 v4.2.20
replace github.com/pion/datachannel => github.com/pion/datachannel v1.6.2
replace github.com/pion/dtls/v3 => github.com/pion/dtls/v3 v3.1.8
replace github.com/pion/ice/v4 => github.com/pion/ice/v4 v4.4.2
replace github.com/pion/interceptor => github.com/pion/interceptor v0.1.48
replace github.com/pion/mdns/v2 => github.com/pion/mdns/v2 v2.2.0
replace github.com/pion/rtcp => github.com/pion/rtcp v1.2.17
replace github.com/pion/sctp => github.com/pion/sctp v1.11.1
replace github.com/pion/sdp/v3 => github.com/pion/sdp/v3 v3.0.19
replace github.com/pion/srtp/v3 => github.com/pion/srtp/v3 v3.0.13
replace github.com/pion/stun/v4 => github.com/pion/stun/v4 v4.0.0
replace github.com/pion/turn/v5 => github.com/pion/turn/v5 v5.1.0
replace github.com/pion/transport/v4 => github.com/pion/transport/v4 v4.1.0
'@
$replacements=Get-Content -LiteralPath (Join-Path $SourceRoot 'AndroidLibXrayLite\go.mod') | Where-Object { $_ -match '^replace ' }
[System.IO.File]::WriteAllText((Join-Path $taskNative 'bridge\go.mod'),($mod+"`n"+($replacements -join "`n")),[System.Text.UTF8Encoding]::new($false))
$env:ANDROID_HOME=$Sdk
$env:ANDROID_NDK_HOME=Join-Path $Sdk 'ndk\28.2.13676358'
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:GOBIN=Join-Path $taskNative 'bin'
$env:GOPATH=Join-Path $taskNative 'gopath'
$env:GOCACHE=Join-Path $taskNative 'gocache'
$env:GOFLAGS='-mod=mod'
$env:PATH="$(Split-Path $GoExe -Parent);$env:GOBIN;$env:JAVA_HOME\bin;$env:PATH"
Push-Location (Join-Path $taskNative 'bridge')
try {
    & $GoExe mod tidy
    if ($LASTEXITCODE) { throw 'Go dependency resolution failed' }
    & $GoExe install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
    if ($LASTEXITCODE) { throw 'gomobile setup failed' }
    $bindArgs=@('bind','-target=android/arm64','-androidapi','26','-javapkg=com.rayren.nativebridge','-ldflags=-s -w -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384','-o',(Join-Path $taskNative 'rayrencore.aar'),'.')
    & (Join-Path $env:GOBIN 'gomobile.exe') @bindArgs
    if ($LASTEXITCODE) { throw 'Native bind failed' }
    Copy-Item -LiteralPath (Join-Path $taskNative 'rayrencore.aar') -Destination (Join-Path $taskRoot 'app\libs\rayrencore.aar') -Force
    Copy-Item -LiteralPath go.mod,go.sum -Destination (Join-Path $taskRoot 'native') -Force
} finally { Pop-Location }
