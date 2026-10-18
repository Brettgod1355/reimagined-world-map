# Sets up the plugin's cached map files for a test. Close RuneLite first: a running client keeps what it already read.
#
#   .\tools\map-server-proxy\map-cache.ps1 show                   the remembered version and its cached files
#   .\tools\map-server-proxy\map-cache.ps1 corrupt                places.json of the remembered version becomes HTML
#   .\tools\map-server-proxy\map-cache.ps1 clear                  deletes every cached map version
#   .\tools\map-server-proxy\map-cache.ps1 remember cache-2727.35 makes the client start on that version
param(
	[Parameter(Mandatory = $true, Position = 0)]
	[ValidateSet('show', 'corrupt', 'clear', 'remember')]
	[string]$Action,
	[Parameter(Position = 1)]
	[string]$Version
)
$ErrorActionPreference = 'Stop'
$data = Join-Path $env:USERPROFILE '.runelite\plugin-data\reimagined-world-map'
$known = Join-Path $data 'server-map-version.txt'
$maps = Join-Path $data 'server-cache\maps'

function Remembered
{
	if (-not (Test-Path $known)) { return $null }
	return (Get-Content $known -TotalCount 1).Trim()
}

$running = Get-CimInstance Win32_Process -Filter "Name LIKE 'java%' OR Name LIKE 'RuneLite%'" |
	Where-Object { $_.Name -like 'RuneLite*' -or $_.CommandLine -match 'DevClient|net\.runelite\.client\.RuneLite|RuneLite\.jar' }
if ($Action -ne 'show' -and $running)
{
	throw 'RuneLite is open. Close it first, or it keeps using the files it already read.'
}

switch ($Action)
{
	'show'
	{
		Write-Host "Remembered version: $(Remembered)"
		if (Test-Path $maps)
		{
			Get-ChildItem $maps -Recurse -File | Format-Table @{ n = 'Version'; e = { $_.Directory.Name } }, Name, Length, LastWriteTime
		}
		else
		{
			Write-Host 'No cached map files.'
		}
	}
	'corrupt'
	{
		$v = if ($Version) { $Version } else { Remembered }
		$file = Join-Path $maps "$v\places.json"
		if (-not (Test-Path $file)) { throw "No cached $file. Start the client once with the server up to fill it." }
		Set-Content $file '<html><body>Not the map</body></html>'
		Write-Host "Corrupted $file"
	}
	'clear'
	{
		if (Test-Path $maps) { Remove-Item $maps -Recurse -Force }
		Write-Host "Cleared $maps"
	}
	'remember'
	{
		if (-not $Version) { throw 'Give a version, e.g. remember cache-2727.35' }
		Set-Content $known "$Version`n$([DateTimeOffset]::Now.ToUnixTimeMilliseconds())"
		Write-Host "The client will start on $Version"
	}
}
