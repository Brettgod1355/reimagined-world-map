# Starts the map server proxy. Usage: .\tools\map-server-proxy\proxy.ps1 [-Port 8099] [-Target https://...]
param(
	[int]$Port = 8099,
	[string]$Target = 'https://osrsmapreforged.online'
)
$ErrorActionPreference = 'Stop'
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
& $java (Join-Path $PSScriptRoot 'MapServerProxy.java') $Port $Target
