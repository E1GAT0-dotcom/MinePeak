# Security policy

## Supported versions

Only the [latest release](https://github.com/E1GAT0-dotcom/MinePeak/releases/latest) gets fixes. If you're on an
older one, update first and check whether the problem is still there.

## What counts

MinePeak does a few things outside Minecraft, so these matter most:

- **The export:** the mod starts a copy of PEAK in the background (`PeakExporter.java`) and reads the files it writes
  to `%APPDATA%\MinePeak`.
- **The installer:** `Install MinePeak Exporter.cmd` and `install.ps1` copy PEAK, can download BepInEx from
  Thunderstore, and write files under `%LOCALAPPDATA%\MinePeak`.
- **Multiplayer:** anything a player or a server could send to crash, take over, or read files on someone else's PC.

Ordinary bugs and crashes that only affect your own game are not security problems: please
[report them as bugs](https://github.com/E1GAT0-dotcom/MinePeak/issues/new/choose) instead.

## Reporting a vulnerability

**Please don't open a public issue.** Report it privately:

1. Go to the repository's **[Security](https://github.com/E1GAT0-dotcom/MinePeak/security)** tab.
2. Click **Report a vulnerability**, and say what you found, how to make it happen, and what an attacker could do.

Only the maintainers can see it. MinePeak is a small project made in spare time, so a reply may take a few days, but
every report gets one. Once it's fixed, the fix goes out in a new release, and you'll be credited in it if you'd like
to be.
