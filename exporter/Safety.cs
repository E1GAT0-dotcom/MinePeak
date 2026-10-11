using System;
using System.IO;
using System.Linq;
using System.Reflection;
using HarmonyLib;

namespace MinePeakExporter
{
    /// <summary>
    /// Keeps MINEPEAK's PEAK test copy from touching the player's real PEAK progress and files (MinePeak's own copy of
    /// PEAKCRAFT's guards):
    /// - no Steam achievement/stat writes (PEAK's custom-run gate, PEAK's Steam wrapper and Steamworks.NET itself).
    ///   Reads stay untouched; PEAK spin-waits on them.
    /// - quicksave.peak goes to the test copy's BepInEx folder instead of the shared LocalLow folder.
    /// - run settings use their own PlayerPrefs keys.
    /// </summary>
    internal static class Safety
    {
        static int _blockedCalls;

        public static string DataDir => Path.Combine(BepInEx.Paths.BepInExRootPath, "MinePeakData");

        public static void Apply(Harmony h)
        {
            int n = 0;
            h.Patch(AccessTools.PropertyGetter(typeof(RunSettings), nameof(RunSettings.blockingAchievements)),
                prefix: new HarmonyMethod(typeof(Safety), nameof(AlwaysTrue)));
            n++;
            n += BlockWrites(h, AccessTools.TypeByName("SteamRuntimeManager"),
                "UnlockAchievement", "SetStat", "ProgressAchievement", "StoreStats", "SetProgress");
            n += BlockWrites(h, AccessTools.TypeByName("Steamworks.SteamUserStats"),
                "SetAchievement", "ClearAchievement", "SetStat", "UpdateAvgRateStat", "StoreStats",
                "IndicateAchievementProgress", "ResetAllStats");
            h.Patch(AccessTools.PropertyGetter(typeof(Peak.Quicksave), "SavePath"),
                prefix: new HarmonyMethod(typeof(Safety), nameof(SavePath)));
            h.Patch(AccessTools.Method(typeof(RunSettings), "GetPlayerPrefsKey"),
                prefix: new HarmonyMethod(typeof(Safety), nameof(RunSettingKey)));
            n += 2;
            Plugin.Log.LogInfo($"Safety: {n} guards on (no Steam progress writes; quicksave -> {DataDir})");
        }

        static int BlockWrites(Harmony h, Type t, params string[] names)
        {
            if (t == null) { Plugin.Log.LogWarning($"Safety: type not found for {string.Join(",", names)}"); return 0; }
            int n = 0;
            foreach (var m in AccessTools.GetDeclaredMethods(t).Where(m => names.Contains(m.Name)))
            {
                string prefix = m.ReturnType == typeof(bool) ? nameof(BlockBool) : m.ReturnType == typeof(void) ? nameof(BlockVoid) : null;
                if (prefix == null) { Plugin.Log.LogWarning($"Safety: skipped {t.Name}.{m.Name} (returns {m.ReturnType.Name})"); continue; }
                h.Patch(m, prefix: new HarmonyMethod(typeof(Safety), prefix));
                n++;
            }
            return n;
        }

        static bool AlwaysTrue(ref bool __result) { __result = true; return false; }
        static bool BlockBool(MethodBase __originalMethod, ref bool __result) { Note(__originalMethod); __result = true; return false; }
        static bool BlockVoid(MethodBase __originalMethod) { Note(__originalMethod); return false; }
        static void Note(MethodBase m) { if (++_blockedCalls <= 20) Plugin.Log.LogInfo($"Safety: blocked {m.DeclaringType?.Name}.{m.Name}"); }

        static bool SavePath(ref string __result)
        {
            Directory.CreateDirectory(DataDir);
            __result = Path.Combine(DataDir, "quicksave.peak");
            return false;
        }

        static bool RunSettingKey(string id, ref string __result) { __result = "MinePeak_RunSetting_" + id; return false; }
    }
}
