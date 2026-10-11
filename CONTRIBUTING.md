# Contributing to MinePeak

Thanks for wanting to help! There are lots of ways to, and most need no code.

## Report a bug

[Open a bug report](https://github.com/E1GAT0-dotcom/MinePeak/issues/new/choose) and fill in the form. The most
useful things to include:

- your MinePeak version and other mods;
- how to make it happen, step by step;
- the logs: Minecraft's `logs/latest.log` (and the crash report, if it crashed); for export problems, the PEAK copy's
  `Player-export.log` and `BepInEx/MinePeakData/export.log` (in `%LOCALAPPDATA%\MinePeak\PEAK`).

Search the [open issues](https://github.com/E1GAT0-dotcom/MinePeak/issues) first: if it's already there, add what you
know to it instead.

## Suggest an idea

Use the **Idea or missing PEAK feature** form. If it's something from PEAK, say how it works there (a clip or a wiki
link helps a lot). MinePeak aims to be as close to PEAK as it can be, so ideas that bring in more of PEAK come first.

## Ask a question

Questions are quicker on the [MinePeak Discord](https://discord.gg/URP6bwCxvv), in the questions forum.

## Change the code

1. **Talk first** for anything bigger than a small fix: open an issue (or post on the Discord) so we can agree on it
   before you spend time on it.
2. **Build it** as the README's [Building](README.md#building) section says: the mod needs JDK 25; the exporter needs
   the .NET SDK and a copy of PEAK with BepInEx 5.
3. **Test it** in a dev run (`gradlew runClient` in `mod`), never in your real Minecraft. Say in the pull request what
   you tried and what happened.
4. **Match PEAK.** When the mod copies something from PEAK (a number, a timing, a rule), it should be PEAK's, from the
   game itself, not a guess.
5. **Match the code around it:** its naming, its comments, its style.

### Never commit PEAK's or Mojang's files

MinePeak ships **nothing** of PEAK's or Minecraft's. Everything from PEAK (the island, pictures, sounds, item data) is
made on each player's PC from their own PEAK. So never add to the repository:

- PEAK's or Minecraft's files, or anything taken out of them (textures, sounds, models, decompiled code);
- anything the exporter made (`%APPDATA%\MinePeak`, `BepInEx/MinePeakData`);
- game DLLs (PEAK's, Unity's, BepInEx's): the exporter's build finds them in your own PEAK copy.

Pull requests with any of these can't be merged.

## Licence

MinePeak's code is under the [MIT licence](LICENSE). By sending a pull request, you agree that your changes are
under it too.

Everyone taking part follows the [code of conduct](CODE_OF_CONDUCT.md).
