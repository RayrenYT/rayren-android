param([string]$Sdk=(Join-Path $env:TEMP 'rayren-android-sdk'),[string]$JavaHome='C:\Program Files\Java\jdk-21')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskLink=Join-Path $env:TEMP 'rayren-android-project'
if(!(Test-Path -LiteralPath $taskLink)){New-Item -ItemType Junction -Path $taskLink -Target $taskRoot | Out-Null}
$taskItem=Get-Item -LiteralPath $taskLink
if($taskItem.LinkType -ne 'Junction' -or $taskItem.Target -ne $taskRoot){throw 'ASCII build path already belongs to another checkout; choose a different taskLink'}
$env:JAVA_HOME=$JavaHome
$env:GRADLE_USER_HOME=Join-Path $env:TEMP 'rayren-gradle'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NUL'
$taskSdkPath=$Sdk.Replace('\','/').Replace(':','\:')
[IO.File]::WriteAllText((Join-Path $taskRoot 'local.properties'),"sdk.dir=$taskSdkPath`n",[Text.UTF8Encoding]::new($false))
Push-Location $taskLink
try{& .\gradlew.bat :app:assembleDebug :app:lintDebug :domain:test :app:testDebugUnitTest --console=plain;if($LASTEXITCODE){throw 'Build or checks failed'}}finally{Pop-Location}
