param([string]$GoExe='')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskNative=Join-Path $env:TEMP 'rayren-native-build'
if (!$GoExe) {$GoExe=Join-Path (Split-Path $taskRoot -Parent) '.toolchains\go1.26.3\go\bin\go.exe'}
$env:GOPATH=Join-Path $taskNative 'gopath'
$env:GOCACHE=Join-Path $taskNative 'gocache'
$env:GOFLAGS='-mod=mod'
$env:RAYREN_FIXTURES=Join-Path $taskRoot 'domain\build\native-fixtures'
New-Item -ItemType Directory -Force -Path (Join-Path $taskNative 'bridge\validation') | Out-Null
Copy-Item -Path (Join-Path $taskRoot 'native\validation\*.go') -Destination (Join-Path $taskNative 'bridge\validation') -Force
New-Item -ItemType Directory -Force -Path (Join-Path $taskNative 'bridge\dotvalidation') | Out-Null
Copy-Item -LiteralPath (Join-Path $taskRoot 'native\bridge\dot.go') -Destination (Join-Path $taskNative 'bridge\dotvalidation') -Force
Copy-Item -Path (Join-Path $taskRoot 'native\dot-tests\*.go') -Destination (Join-Path $taskNative 'bridge\dotvalidation') -Force
New-Item -ItemType Directory -Force -Path (Join-Path $taskNative 'bridge\probevalidation') | Out-Null
Copy-Item -LiteralPath (Join-Path $taskRoot 'native\bridge\probe.go') -Destination (Join-Path $taskNative 'bridge\probevalidation') -Force
Copy-Item -LiteralPath (Join-Path $taskRoot 'native\bridge\startup.go') -Destination (Join-Path $taskNative 'bridge\probevalidation') -Force
Copy-Item -Path (Join-Path $taskRoot 'native\probe-tests\*.go') -Destination (Join-Path $taskNative 'bridge\probevalidation') -Force
Push-Location (Join-Path $taskNative 'bridge')
try {& $GoExe test -ldflags=-checklinkname=0 ./validation ./dotvalidation ./probevalidation -v -count=1;if($LASTEXITCODE){throw 'Native validation failed'}} finally {Pop-Location}
