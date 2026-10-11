# Packages the MinePeak Exporter for release: dist\MinePeakExporter-<version>.zip with the installer (install.ps1,
# "Install MinePeak Exporter.cmd", README.txt) and MinePeakExporter.dll (built from ..\exporter, Release).
# Nothing of PEAK's or BepInEx's goes in it: the installer copies the player's own PEAK and fetches BepInEx itself.
param([string]$Version = "0.1.0")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$dll = Join-Path $root "exporter\bin\Release\netstandard2.1\MinePeakExporter.dll"
if (-not (Test-Path $dll)) { throw "Build the exporter first (dotnet build -c Release in exporter)." }
$dist = Join-Path $root "dist"
$stage = Join-Path $dist "MinePeakExporter"
Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item (Join-Path $root "installer\*") $stage
Copy-Item $dll $stage
$zip = Join-Path $dist "MinePeakExporter-$Version.zip"
Remove-Item -Force $zip -ErrorAction SilentlyContinue
Compress-Archive -Path (Join-Path $stage "*") -DestinationPath $zip
Remove-Item -Recurse -Force $stage
"packaged $zip"
