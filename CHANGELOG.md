# Changelog

## 0.1.1

- Fixed: PEAK's items could all show as purple-and-black squares when the item pack MinePeak makes from your PEAK
  (in `%APPDATA%\MinePeak\pack`) had been left incomplete, for example by an older version. MinePeak now checks the
  pack every time it starts and makes it again when anything is missing. An item whose picture can't be made looks
  like its Minecraft item instead of a purple-and-black square.
- Fixed (MinePeak Exporter): on Gloom days, the temple at the top was an empty shell. Its great brick hall had a
  floor and a roof but no walls (they're upright, and the export only saw what's under its rays straight down), so
  you could see the swamp's huge background rocks through it; and its floating floors went on down to the bottom of
  the world. The hall has its walls now, and the temple's floors stop at their own bottoms. Update the exporter (run
  its installer again with `-SkipCopy`); the fix comes with the next island exported.

## 0.1.0

First release, for Minecraft 26.2 (Fabric).
