MinePeak Exporter
=================

MinePeak (a Minecraft mod) builds PEAK's island of the day in Minecraft. It gets the island, and PEAK's pictures,
sounds and item data, from your own PEAK: a small BepInEx plugin, the MinePeak Exporter, runs in a copy of PEAK in
the background (no window, no sound) once a day and saves them to %APPDATA%\MinePeak.

Install
-------
1. Double-click "Install MinePeak Exporter.cmd".
   - It finds your Steam PEAK and copies it to %LOCALAPPDATA%\MinePeak\PEAK (about 2 GB). Your Steam PEAK is only read,
     never changed, and any mods you have in it aren't copied.
   - It installs BepInEx 5 (BepInExPack_PEAK) in the copy. It asks before downloading it from Thunderstore, or use a zip
     you already have: Install MinePeak Exporter.cmd -BepInExZip "C:\path\to\BepInExPack_PEAK.zip"
   - It puts MinePeakExporter.dll in the copy and tells MinePeak where the copy is.
2. Start Minecraft with MinePeak (Steam running), then PLAY OFFLINE > Create New World. The first export of a
   day takes a few minutes.

Run the installer again after PEAK updates, to bring the copy up to date (-SkipCopy only reinstalls the exporter).

Uninstall: delete %LOCALAPPDATA%\MinePeak\PEAK (the copy) and %APPDATA%\MinePeak (what it exported).

Logs: the copy's Player-export.log and BepInEx\MinePeakData\export.log.

Nothing of PEAK's ships with MinePeak or this installer: everything comes from your own PEAK, on your PC.
PEAK is by Aggro Crab and Landfall; MinePeak is an unofficial fan project. BepInEx is by the BepInEx team (LGPL-2.1).
