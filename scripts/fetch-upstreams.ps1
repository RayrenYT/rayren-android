$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskSource=Join-Path $taskRoot '.research\veil'
if(!(Test-Path (Join-Path $taskSource '.git'))){
    New-Item -ItemType Directory -Force -Path (Join-Path $taskRoot '.research') | Out-Null
    & git clone --filter=blob:none --no-checkout https://github.com/venterum/veil.git $taskSource
    if($LASTEXITCODE){throw 'Clone failed'}
    & git -C $taskSource checkout --detach a5af92169126b8d9b79c674c37f2bc127b339601
    if($LASTEXITCODE){throw 'Pinned checkout failed'}
}
$taskRevision=& git -C $taskSource rev-parse HEAD
if($taskRevision -ne 'a5af92169126b8d9b79c674c37f2bc127b339601'){throw 'Existing upstream checkout has a different revision; do not overwrite it'}
& git -C $taskSource config submodule.olcrtc.url https://github.com/openlibrecommunity/olcrtc.git
& git -C $taskSource submodule update --init --recursive
if($LASTEXITCODE){throw 'Submodule fetch failed'}
Write-Output 'Pinned source tree ready'
