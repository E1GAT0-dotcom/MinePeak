using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using BepInEx;
using BepInEx.Logging;
using HarmonyLib;
using UnityEngine;
using UnityEngine.SceneManagement;
using UnityEngine.UI;
using Zorro.Core;

namespace MinePeakExporter
{
    /// <summary>
    /// MinePeak Exporter: exports PEAK's island of the day as Minecraft blocks for MinePeak, the Fabric mod. Run PEAK
    /// with -minepeak-export (add -minepeak-quit to close it when done): it goes from the title to the airport, takes the
    /// pictures for Minecraft's menu panorama there, boards the plane, then jumps to each segment of the island and
    /// exports it.
    /// </summary>
    [BepInPlugin("gamemashup.minepeak.exporter", "MinePeak Exporter", "0.1.0")]
    public sealed class Plugin : BaseUnityPlugin
    {
        internal static ManualLogSource Log;

        void Awake()
        {
            Log = Logger;
            Safety.Apply(new Harmony("gamemashup.minepeak.exporter"));
            if (Runner.Has("-minepeak-export") || Runner.Has("-minepeak-probe") || Runner.Has("-minepeak-menushot"))
            {
                var host = new GameObject("MinePeakExporter");
                DontDestroyOnLoad(host);
                host.hideFlags = HideFlags.HideAndDontSave;
                host.AddComponent<Runner>();
            }
        }
    }

    internal sealed class Runner : MonoBehaviour
    {
        readonly StringBuilder _log = new StringBuilder();
        bool _protect;

        void Note(string s)
        {
            _log.AppendLine($"[{Time.realtimeSinceStartup:F1}] {s}");
            Plugin.Log.LogInfo("Export: " + s);
            try { Directory.CreateDirectory(Safety.DataDir); File.WriteAllText(Path.Combine(Safety.DataDir, "export.log"), _log.ToString()); } catch (Exception) { }
        }

        void Fail(string why)
        {
            Note("failed: " + why);
            if (!ProbeOnly && !DiagOnly) Status.Set("error", "error", 0, "The PEAK export failed: " + why);
            if (Quit) Application.Quit();
        }

        internal static bool Has(string arg) => Environment.GetCommandLineArgs().Any(a => a.Equals(arg, StringComparison.OrdinalIgnoreCase));
        static bool Quit => Has("-minepeak-quit");
        static bool ProbeOnly => Has("-minepeak-probe");
        /// <summary>-minepeak-menushot (in a window: menus aren't drawn without one): pictures of PEAK's main menu, its
        /// settings and its pause menu, for MinePeak's menus to look like them. Into BepInEx/MinePeakData/menus.</summary>
        static bool MenuShot => Has("-minepeak-menushot");

        IEnumerator MenuShots()
        {
            string dir = Path.Combine(Safety.DataDir, "menus");
            Directory.CreateDirectory(dir);
            yield return new WaitForSecondsRealtime(4f);
            ScreenCapture.CaptureScreenshot(Path.Combine(dir, "main.png"));
            yield return new WaitForSecondsRealtime(1.5f);
            var page = FindAnyObjectByType<MainMenuMainPage>();
            foreach (var (field, file) in new[] { ("m_settingsButton", "settings.png"), ("m_creditsButton", "credits.png") })
            {
                var b = page != null ? AccessTools.Field(typeof(MainMenuMainPage), field)?.GetValue(page) as Button : null;
                if (b == null || !b.isActiveAndEnabled) { Note($"  menu shots: no {field}"); continue; }
                b.onClick.Invoke();
                yield return new WaitForSecondsRealtime(2f);
                ScreenCapture.CaptureScreenshot(Path.Combine(dir, file));
                yield return new WaitForSecondsRealtime(1.5f);
                // back to the main page (PEAK's Back input)
                var handler = FindAnyObjectByType<MainMenuPageHandler>();
                try { handler?.TransistionToPage<MainMenuMainPage>(); } catch (Exception) { }
                yield return new WaitForSecondsRealtime(1.5f);
            }
            ScreenCapture.CaptureScreenshot(Path.Combine(dir, "main_after.png"));
            yield return new WaitForSecondsRealtime(1f);
            // the pause menu, in the airport
            var solo = page != null ? AccessTools.Field(typeof(MainMenuMainPage), "m_soloButton").GetValue(page) as Button : null;
            if (solo != null)
            {
                solo.onClick.Invoke();
                yield return WaitScene("Airport", 120f);
                float t0 = Time.realtimeSinceStartup;
                while (Character.localCharacter == null && Time.realtimeSinceStartup - t0 < 60f) yield return null;
                yield return new WaitForSecondsRealtime(5f);
                ScreenCapture.CaptureScreenshot(Path.Combine(dir, "airport_hud.png"));
                yield return new WaitForSecondsRealtime(1f);
                var gui = GUIManager.instance;
                var pause = gui != null ? AccessTools.Field(typeof(GUIManager), "pauseMenu")?.GetValue(gui) as GameObject : null;
                if (pause != null)
                {
                    pause.SetActive(true);
                    yield return new WaitForSecondsRealtime(2f);
                    ScreenCapture.CaptureScreenshot(Path.Combine(dir, "pause.png"));
                    yield return new WaitForSecondsRealtime(1.5f);
                }
                else Note("  menu shots: no pause menu");
            }
            Note("menu shots taken into " + dir);
        }
        /// <summary>-minepeak-diag (with -minepeak-export): to the island and through its segments, but Diag in place of the export.</summary>
        static bool DiagOnly => Has("-minepeak-diag");

        /// <summary>Which fonts PEAK's text on screen uses (for MinePeak's menus and title cards to match): each font, how many texts, and one.</summary>
        void LogFonts(string where)
        {
            try
            {
                var seen = new Dictionary<string, (int n, string sample)>();
                foreach (var t in FindObjectsByType<TMPro.TMP_Text>(FindObjectsInactive.Include, FindObjectsSortMode.None))
                {
                    if (t == null || t.font == null) continue;
                    string f = t.font.name, sample = (t.text ?? "").Replace('\n', ' ');
                    if (sample.Length > 30) sample = sample.Substring(0, 30);
                    seen[f] = seen.TryGetValue(f, out var v) ? (v.n + 1, string.IsNullOrWhiteSpace(v.sample) ? sample : v.sample) : (1, sample);
                }
                foreach (var kv in seen.OrderByDescending(k => k.Value.n)) Note($"  font in the {where}: {kv.Key} x{kv.Value.n} (\"{kv.Value.sample}\")");
                var gui = GUIManager.instance;
                if (gui != null && gui.heroText != null) Note($"  the title card's font: {((TMPro.TMP_Text)(object)gui.heroText).font?.name}");
            }
            catch (Exception e) { Note("  fonts: " + e.Message); }
        }

        /// <summary>While exporting, nothing hurts the scout (the Kiln's lava, cold, heat), so the run can't end.</summary>
        void Update()
        {
            // silent all the way through (PEAK sets its own volume as it loads, after Start muted it)
            if (AudioListener.volume != 0f) AudioListener.volume = 0f;
            if (!AudioListener.pause) AudioListener.pause = true;
            var ch = Character.localCharacter;
            if (!_protect || ch == null) return;
            foreach (var t in new[] { CharacterAfflictions.STATUSTYPE.Hot, CharacterAfflictions.STATUSTYPE.Cold, CharacterAfflictions.STATUSTYPE.Injury, CharacterAfflictions.STATUSTYPE.Drowsy, CharacterAfflictions.STATUSTYPE.Hunger, CharacterAfflictions.STATUSTYPE.Poison })
                if (ch.refs.afflictions.GetCurrentStatus(t) > 0f) ch.refs.afflictions.SubtractStatus(t, 1f);
        }

        /// <summary>Runs a step, noting (not throwing) what goes wrong in it.</summary>
        internal sealed class Outcome { public bool Failed; }

        /// <summary>
        /// Runs a step, noting (not throwing) what goes wrong in it, in the steps it runs too: it runs them itself, one
        /// inside another (an error deep in a stage's scan had stopped the whole export, and PEAK sat there).
        /// </summary>
        static IEnumerator Guarded(IEnumerator step, Action<string> log, Outcome outcome = null)
        {
            var stack = new Stack<IEnumerator>();
            stack.Push(step);
            while (stack.Count > 0)
            {
                var top = stack.Peek();
                object current;
                try
                {
                    if (!top.MoveNext()) { stack.Pop(); continue; }
                    current = top.Current;
                }
                catch (Exception e)
                {
                    log("failed (carrying on): " + e);
                    if (outcome != null) outcome.Failed = true;
                    yield break;
                }
                if (current is IEnumerator inner) { stack.Push(inner); continue; }   // (a step of its own, or a wait like WaitForSecondsRealtime)
                yield return current;
            }
        }

        IEnumerator WaitScene(string name, float limit)
        {
            float t0 = Time.realtimeSinceStartup;
            while (SceneManager.GetActiveScene().name != name && Time.realtimeSinceStartup - t0 < limit) yield return null;
        }

        IEnumerator WaitGrounded(float limit)
        {
            float t0 = Time.realtimeSinceStartup;
            while ((Character.localCharacter == null || !Character.localCharacter.data.isGrounded) && Time.realtimeSinceStartup - t0 < limit) yield return null;
        }

        /// <summary>The whole run, guarded: whatever goes wrong, Minecraft is told and PEAK quits (it had sat there for ever).</summary>
        IEnumerator Start()
        {
            var outcome = new Outcome();
            yield return Guarded(Run(), Note, outcome);
            if (outcome.Failed) Fail("it stopped on an error (BepInEx/MinePeakData/export.log has it)");
        }

        IEnumerator Run()
        {
            Note($"MinePeak export{(Application.isBatchMode ? " (no window)" : "")}");
            if (!ProbeOnly && !DiagOnly) Status.Set("starting", "starting", 0, "Starting PEAK to export today's island");
            AudioListener.volume = 0f;   // (it works quietly in the background; Update keeps it so)
            AudioListener.pause = true;
            yield return WaitScene("Title", 120f);
            // the menu ready (its Play Offline button there), then a moment
            float tm = Time.realtimeSinceStartup;
            while (Time.realtimeSinceStartup - tm < 20f)
            {
                var p0 = FindAnyObjectByType<MainMenuMainPage>();
                var b0 = p0 != null ? AccessTools.Field(typeof(MainMenuMainPage), "m_soloButton").GetValue(p0) as Button : null;
                if (b0 != null && b0.isActiveAndEnabled && b0.interactable) break;
                yield return null;
            }
            yield return new WaitForSecondsRealtime(2f);
            LogFonts("main menu");
            MenuArt.TakeMainMenu(Note);
            if (!DiagOnly && !ProbeOnly) SoundArt.TakeMenu(Note);
            if (MenuShot)
            {
                yield return MenuShots();
                if (Quit) Application.Quit();
                yield break;
            }
            var page = FindAnyObjectByType<MainMenuMainPage>();
            var solo = page != null ? AccessTools.Field(typeof(MainMenuMainPage), "m_soloButton").GetValue(page) as Button : null;
            if (solo == null) { Fail("no Play Offline button"); yield break; }
            solo.onClick.Invoke();
            yield return WaitScene("Airport", 120f);
            float t0 = Time.realtimeSinceStartup;
            while (Character.localCharacter == null && Time.realtimeSinceStartup - t0 < 60f) yield return null;
            yield return WaitGrounded(20f);
            yield return new WaitForSecondsRealtime(2f);
            MenuArt.TakePauseMenu(Note);
            if (!ProbeOnly)
            {
                MenuArt.TakeHud(Note); ItemArt.Take(Note); SfxArt.TakeBadges(Note); SfxArt.TakeAirport(Note);
                yield return Guarded(SfxArt.TakeGuidebooks(Note), Note);
                yield return Guarded(ScreenFx.Take(Note), Note);
            }
            var ch = Character.localCharacter;
            if (ch == null) { Fail("no scout in the airport"); yield break; }
            _protect = true;
            if (ProbeOnly)
            {
                yield return new WaitForSecondsRealtime(4f);
                // -minepeak-panorama: just the menus' panorama (and its first side without PEAK's post-processing, to compare)
                if (Has("-minepeak-panorama")) yield return Panorama.Take(ch, Note, compare: true);
                else yield return Probe.Run(ch, Note);
                Note("probe done");
                if (Quit) Application.Quit();
                yield break;
            }

            var job = DiagOnly ? null : new MinePeakExport.Job();   // (Diag writes no island)
            var kiosk = FindAnyObjectByType<AirportCheckInKiosk>();
            // the airport isn't built in Minecraft: it's the menus' panorama (a picture going wrong doesn't stop the export)
            if (!DiagOnly)
            {
                Status.Area("panorama", 0);
                yield return Guarded(Panorama.Take(ch, Note), Note);
            }

            if (kiosk == null) { Fail("no boarding kiosk"); yield break; }
            // (2 s after landing was too soon for the kiosk without the panorama's pictures first: the island never came)
            yield return new WaitForSecondsRealtime(DiagOnly ? 5f : 2f);
            for (int tries = 0; tries < 3 && !SceneManager.GetActiveScene().name.StartsWith("Level_"); tries++)
            {
                if (tries > 0) Note($"  the island hasn't loaded: the kiosk again ({tries + 1})");
                kiosk = FindAnyObjectByType<AirportCheckInKiosk>() ?? kiosk;
                kiosk.StartGame(0);
                t0 = Time.realtimeSinceStartup;
                while (!SceneManager.GetActiveScene().name.StartsWith("Level_") && Time.realtimeSinceStartup - t0 < 60f) yield return null;
            }
            string scene = SceneManager.GetActiveScene().name;
            if (!scene.StartsWith("Level_")) { Fail("the island didn't load from the airport"); yield break; }
            Note($"island '{scene}'");
            yield return WaitGrounded(90f);
            yield return new WaitForSecondsRealtime(5f);
            job?.Island(scene);
            // PEAK's sounds (its music, ambience, the campfire's): saved alongside the scan (recording takes as long as they play)
            if (!DiagOnly) { SoundArt.TakeIsland(Note); SfxArt.TakeIsland(Note); StartCoroutine(Guarded(SoundArt.Save(Note), Note)); }

            // the climb: Beach, Tropics, Alpine (or Mesa), Caldera, and The Kiln with the summit (PEAK's Segment.Peak is
            // segments[4] too); segments[5] is the Void, an ending of its own, not on the mountain
            var mh = Singleton<MapHandler>.Instance;
            for (int i = 0; i < Math.Min(mh.segments.Length, (int)Segment.Peak); i++)
            {
                if (i > 0)
                {
                    // (the jump switches the stage on at once; the scout's warp and landing take a moment, and PEAK's
                    // light for that stage comes with them, which the top colours' picture needs)
                    MapHandler.JumpToSegment((Segment)i);
                    yield return new WaitForSecondsRealtime(1.5f);
                    yield return WaitGrounded(30f);
                    yield return new WaitForSecondsRealtime(1.5f);
                }
                if (DiagOnly) yield return Guarded(Diag.Segment(i, Note), Note);
                else yield return Guarded(job.Segment(i, Note), Note);
            }
            LogFonts("island");
            if (!DiagOnly) job.Finish(Note);
            // (PEAK stays till its sounds are saved: up to 6 minutes more on the first export, nothing after)
            float ts = Time.realtimeSinceStartup;
            while (SoundArt.Busy && Time.realtimeSinceStartup - ts < 360f) yield return null;
            _protect = false;
            if (Quit) Application.Quit();
        }
    }
}
