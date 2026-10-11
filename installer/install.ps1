<#
  MinePeak Exporter installer.

  MinePeak builds PEAK's island of the day in Minecraft from an export that PEAK itself makes, with the MinePeak
  Exporter (a BepInEx plugin). The exporter runs in a COPY of PEAK, never in your Steam PEAK, so your game stays as it is.
  This script:
    1. finds your Steam PEAK (or asks where it is),
    2. copies it to a folder of its own (default: %LOCALAPPDATA%\MinePeak\PEAK; about 2 GB), leaving out any mods,
    3. installs BepInEx 5 in the copy (BepInExPack_PEAK: from a zip you give it, or downloaded from Thunderstore if you
       say yes),
    4. puts MinePeakExporter.dll in the copy's BepInEx\plugins\MinePeakExporter,
    5. tells MinePeak where the copy is (%APPDATA%\MinePeak\peak_copy.txt).
  Your Steam PEAK is only read, never changed. Run it again after PEAK updates to bring the copy up to date.

  Options:
    -PeakDir <folder>     your PEAK install (found from Steam if left out)
    -Target <folder>      where the copy goes
    -BepInExZip <file>    a BepInExPack_PEAK zip you already have (otherwise it asks to download it)
    -Yes                  don't ask (download BepInEx if needed, copy without asking)
    -SkipCopy             only (re)install BepInEx and the exporter in an existing copy
#>
param(
  [string]$PeakDir = "",
  [string]$Target = (Join-Path $env:LOCALAPPDATA "MinePeak\PEAK"),
  [string]$BepInExZip = "",
  [switch]$Yes,
  [switch]$SkipCopy,
  [string]$MinePeakDir = (Join-Path $env:APPDATA "MinePeak")
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$plugin = Join-Path $here "MinePeakExporter.dll"
$bepinexUrl = "https://thunderstore.io/package/download/BepInEx/BepInExPack_PEAK/5.4.75301/"
$appId = "3527290"

function Say($s) { Write-Host $s }
function Ask($q) { if ($Yes) { return $true }; $a = Read-Host "$q [y/N]"; return $a -match '^(y|yes)$' }

if (-not (Test-Path $plugin)) { throw "MinePeakExporter.dll isn't next to this script ($here)." }

# ---- 1. PEAK ----
function Find-Peak {
  $steam = $null
  try { $steam = (Get-ItemProperty "HKCU:\Software\Valve\Steam" -ErrorAction Stop).SteamPath } catch { }
  if (-not $steam) { try { $steam = (Get-ItemProperty "HKLM:\SOFTWARE\WOW6432Node\Valve\Steam" -ErrorAction Stop).InstallPath } catch { } }
  if (-not $steam) { return $null }
  $libs = @($steam)
  $vdf = Join-Path $steam "steamapps\libraryfolders.vdf"
  if (Test-Path $vdf) {
    foreach ($m in [regex]::Matches((Get-Content $vdf -Raw), '"path"\s+"([^"]+)"')) { $libs += $m.Groups[1].Value -replace '\\\\', '\' }
  }
  foreach ($l in $libs) {
    $p = Join-Path $l "steamapps\common\PEAK"
    if (Test-Path (Join-Path $p "PEAK.exe")) { return (Resolve-Path $p).Path }
  }
  return $null
}

if (-not $SkipCopy) {
  if (-not $PeakDir) { $PeakDir = Find-Peak }
  if (-not $PeakDir) { $PeakDir = Read-Host "Couldn't find PEAK through Steam. Where is it installed (the folder with PEAK.exe)?" }
  if (-not (Test-Path (Join-Path $PeakDir "PEAK.exe"))) { throw "No PEAK.exe in $PeakDir." }
  $PeakDir = (Resolve-Path $PeakDir).Path.TrimEnd('\')
  $full = [IO.Path]::GetFullPath($Target).TrimEnd('\')
  if ($full -eq $PeakDir -or $full.StartsWith($PeakDir + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "The copy can't go inside your PEAK folder ($PeakDir). Choose another -Target." }
  Say "Your PEAK:  $PeakDir"
  Say "The copy:   $full"

  # ---- 2. the copy (game files only; no mods) ----
  $size = (Get-ChildItem $PeakDir -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.FullName -notmatch '\\BepInEx\\' } | Measure-Object Length -Sum).Sum
  Say ("Copying PEAK ({0:N1} GB). Your PEAK isn't changed." -f ($size / 1GB))
  if (-not (Ask "Copy PEAK to $full?")) { Say "Stopped."; exit 1 }
  New-Item -ItemType Directory -Force $full | Out-Null
  robocopy $PeakDir $full /E /NFL /NDL /NJH /NP /R:1 /W:1 /XD BepInEx /XF winhttp.dll doorstop_config.ini .doorstop_version "*.log" | Out-Null
  if ($LASTEXITCODE -ge 8) { throw "Copying PEAK failed (robocopy $LASTEXITCODE)." }
  $Target = $full
} else {
  if (-not (Test-Path (Join-Path $Target "PEAK.exe"))) { throw "No PEAK copy at $Target (no PEAK.exe)." }
  $Target = (Resolve-Path $Target).Path.TrimEnd('\')
}

# ---- 3. BepInEx ----
if (-not (Test-Path (Join-Path $Target "BepInEx\core\BepInEx.dll"))) {
  $tmp = Join-Path ([IO.Path]::GetTempPath()) ("MinePeakBepInEx_" + [Guid]::NewGuid().ToString("N"))
  New-Item -ItemType Directory -Force $tmp | Out-Null
  try {
    if (-not $BepInExZip) {
      Say "BepInEx 5 (BepInExPack_PEAK, by the BepInEx team, from Thunderstore) is needed in the copy."
      if (-not (Ask "Download it from $bepinexUrl ?")) { Say "Stopped. Download it yourself and run again with -BepInExZip <the zip>."; exit 1 }
      $BepInExZip = Join-Path $tmp "BepInExPack_PEAK.zip"
      [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
      Invoke-WebRequest -Uri $bepinexUrl -OutFile $BepInExZip -UseBasicParsing
    }
    $unz = Join-Path $tmp "x"
    Expand-Archive -Path $BepInExZip -DestinationPath $unz -Force
    $root = Get-ChildItem $unz -Recurse -Filter winhttp.dll | Select-Object -First 1
    if (-not $root) { throw "$BepInExZip doesn't look like BepInEx (no winhttp.dll in it)." }
    Copy-Item -Path (Join-Path $root.DirectoryName "*") -Destination $Target -Recurse -Force
    Say "BepInEx installed in the copy."
  } finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
  }
} else { Say "BepInEx is already in the copy." }

# ---- 4. the exporter ----
$pdir = Join-Path $Target "BepInEx\plugins\MinePeakExporter"
New-Item -ItemType Directory -Force $pdir | Out-Null
Copy-Item $plugin $pdir -Force
# (started directly by MinePeak, the copy needs PEAK's Steam app id beside it; Steam must be running)
Set-Content -Path (Join-Path $Target "steam_appid.txt") -Value $appId -NoNewline -Encoding ascii
Say "The MinePeak Exporter is in $pdir"

# ---- 5. tell MinePeak ----
$mp = $MinePeakDir
New-Item -ItemType Directory -Force $mp | Out-Null
Set-Content -Path (Join-Path $mp "peak_copy.txt") -Value (Join-Path $Target "PEAK.exe") -Encoding utf8
Say ""
Say "Done. MinePeak will use $(Join-Path $Target 'PEAK.exe') to export PEAK's island of the day."
Say "Start Minecraft with MinePeak (and Steam running): PLAY OFFLINE, then Create New World, makes a world of today's island."
