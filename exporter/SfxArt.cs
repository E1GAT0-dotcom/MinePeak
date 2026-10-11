using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Newtonsoft.Json.Linq;
using UnityEngine;
using UnityEngine.UI;
using Zorro.Core;

namespace MinePeakExporter
{
    /// <summary>
    /// PEAK's sound effects (its SFX_Instance assets, each a few clips one of which plays), for MinePeak to play them
    /// where PEAK does: each item's own sound when used (ItemUseFeedback.sfxUsed) and when it lands (ItemImpactSFX), the
    /// bugles' notes, the piton being hammered and cracking, the rope paying out, the flare, the dynamite's blast, balloons
    /// tied and popping, a suitcase and a statue opening, the mandrake's scream, the bees' buzz (calm and angry), the
    /// antlion's sand rumbling, cooking, getting hurt (each kind: a knock, cold, heat, poison,
    /// hunger, thorns, spores), running out of stamina, dying and coming back. Saved with the other sounds (SoundArt: by
    /// role, an SFX_Instance's clips as role_0, role_1...).
    /// Also PEAK's badges' pictures and names (ui/badges), and the guidebook's pages as PEAK draws them (items/guidebook).
    /// </summary>
    internal static class SfxArt
    {
        static string Safe(string s)
        {
            var o = new System.Text.StringBuilder();
            foreach (char ch in s ?? "sfx") o.Append(char.IsLetterOrDigit(ch) ? char.ToLowerInvariant(ch) : '_');
            return o.ToString();
        }

        /// <summary>An SFX_Instance's clips under role (role_0, role_1...).</summary>
        static void Inst(string role, SFX_Instance s)
        {
            if (s == null || s.clips == null) return;
            for (int i = 0; i < s.clips.Length; i++) SoundArt.Wants(role + "_" + i, s.clips[i], false);
        }

        static void Named(SFX_Instance s) { if (s != null) Inst("sfx_" + Safe(s.name), s); }

        /// <summary>Every sound in a prefab's hierarchy (its SFX_PlayOneShot and AudioSource clips) under role.</summary>
        static void Tree(string role, GameObject go)
        {
            if (go == null) return;
            int i = 0;
            foreach (var p in go.GetComponentsInChildren<SFX_PlayOneShot>(true))
            {
                if (p.sfx != null && p.sfx.clips != null) foreach (var c in p.sfx.clips) SoundArt.Wants(role + "_" + i++, c, false);
                if (p.sfxs != null) foreach (var s in p.sfxs) if (s != null && s.clips != null) foreach (var c in s.clips) SoundArt.Wants(role + "_" + i++, c, false);
            }
            foreach (var a in go.GetComponentsInChildren<AudioSource>(true)) if (a.clip != null) SoundArt.Wants(role + "_" + i++, a.clip, a.loop);
        }

        static void Each(string role, SFX_Instance[] arr) { if (arr == null) return; int i = 0; foreach (var s in arr) if (s != null && s.clips != null) foreach (var c in s.clips) SoundArt.Wants(role + "_" + i++, c, false); }

        /// <summary>In the airport: the items' sounds and the scout's.</summary>
        public static void TakeAirport(Action<string> log)
        {
            int before = SoundArt.WantedCount;
            try
            {
                foreach (var item in SingletonAsset<ItemDatabase>.Instance.itemLookup.Values)
                {
                    if (item == null) continue;
                    try
                    {
                        var fb = item.GetComponent<ItemUseFeedback>();
                        if (fb != null) Named(fb.sfxUsed);
                        var imp = item.GetComponent<ItemImpactSFX>();
                        if (imp != null && imp.impact != null && imp.impact.Length > 0) Named(imp.impact[0]);
                        var bugle = item.GetComponentInChildren<BugleSFX>(true);
                        if (bugle != null && bugle.bugle != null) for (int i = 0; i < bugle.bugle.Length; i++) SoundArt.Wants("bugle_" + Safe(item.name) + "_" + i, bugle.bugle[i], false);
                        var spike = item.GetComponent<ClimbingSpikeComponent>();
                        if (spike != null && spike.hammeredVersionPrefab != null) Tree("piton_hammer", spike.hammeredVersionPrefab);
                        var rope = item.GetComponentInChildren<RopeAudio>(true);
                        if (rope != null) { if (rope.loop1 != null) SoundArt.Wants("rope_unspool", rope.loop1.clip, true); Each("rope_end", rope.max); }
                        var flare = item.GetComponent<Flare>();
                        if (flare != null && flare.flareVFXPrefab != null) Tree("flare", flare.flareVFXPrefab.gameObject);
                        var dyn = item.GetComponent<Dynamite>();
                        if (dyn != null) Tree("dynamite_explode", dyn.explosionPrefab);
                        var mandrake = item.GetComponent<Mandrake>();
                        if (mandrake != null) Inst("mandrake_scream", mandrake.sfxScream);
                        var hive = item.GetComponent<Beehive>();
                        if (hive != null && hive.beeSwarmPrefab != null)
                        {
                            var bees = hive.beeSwarmPrefab;
                            if (bees.beeIdleLoop != null && bees.beeIdleLoop.clip != null) SoundArt.Wants("bees_idle", bees.beeIdleLoop.clip, true);
                            if (bees.beeAngryLoop != null && bees.beeAngryLoop.clip != null) SoundArt.Wants("bees_angry", bees.beeAngryLoop.clip, true);
                        }
                    }
                    catch (Exception e) { log($"sfx: {item.name}: {e.Message}"); }
                }
                var ch = Character.localCharacter;
                if (ch != null)
                {
                    var af = ch.refs.afflictions;
                    if (af != null)
                    {
                        Inst("injury_small", af.injurySmall); Inst("injury_mid", af.injuryMid); Inst("injury_heavy", af.injuryHeavy);
                        Inst("injury_ice", af.injuryIce); Inst("injury_fire", af.injuryFire); Inst("injury_poison", af.injuryPoison);
                        Inst("injury_hunger", af.injuryHunger); Inst("injury_thorns", af.injuryThorns); Inst("injury_spore", af.injurySpore);
                    }
                    Each("revive", ch.poofSFX);
                    var items = ch.GetComponent<CharacterItems>();
                    if (items != null) Inst("cook", items.cookSfx);
                    var balloons = ch.GetComponent<CharacterBalloons>() ?? ch.GetComponentInChildren<CharacterBalloons>(true);
                    if (balloons != null) { Tree("balloon_pop", balloons.popParticle); Each("balloon_tie", balloons.balloonTie); }
                    var climb = ch.GetComponentInChildren<ClimbSFX>(true);
                    if (climb != null) { Tree("climb_grab", climb.surfaceOn); Tree("climb_let_go", climb.surfaceOff); Tree("rope_grab", climb.ropeOn); }
                }
                var gui = GUIManager.instance;
                if (gui != null && gui.bar != null) Inst("no_stamina", gui.bar.noStaminaSFX);
                Tree("death", Resources.Load<GameObject>("SkeletonExplosion"));
            }
            catch (Exception e) { log("sfx (airport) failed: " + e); }
            log($"sfx: {SoundArt.WantedCount - before} of PEAK's sound effects wanted in the airport");
        }

        /// <summary>On the island: a suitcase's, a statue's, the campfire's, the rusty pitons'.</summary>
        public static void TakeIsland(Action<string> log)
        {
            int before = SoundArt.WantedCount;
            try
            {
                var lug = UnityEngine.Object.FindObjectsByType<Luggage>(FindObjectsInactive.Include, FindObjectsSortMode.None);
                var plain = lug.FirstOrDefault(l => l != null && l.GetType() == typeof(Luggage));
                if (plain != null) Tree("luggage_open", plain.gameObject);
                var statue = lug.FirstOrDefault(l => l is RespawnChest);
                if (statue != null) Tree("statue", statue.gameObject);
                var fire = UnityEngine.Object.FindObjectsByType<Campfire>(FindObjectsInactive.Include, FindObjectsSortMode.None).FirstOrDefault();
                if (fire != null) { Each("campfire_out", fire.extinguish); Each("morale_boost", fire.moraleBoost); }
                var rusty = UnityEngine.Object.FindObjectsByType<ShittyPiton>(FindObjectsInactive.Include, FindObjectsSortMode.None).FirstOrDefault();
                if (rusty != null) { Each("piton_crack", rusty.cracKSFX); Each("piton_break", rusty.breakSFX); }
                var rumble = UnityEngine.Object.FindObjectsByType<AntlionSandRumbleSFX>(FindObjectsInactive.Include, FindObjectsSortMode.None).FirstOrDefault();
                var rumbleSource = rumble != null ? rumble.GetComponent<AudioSource>() : null;
                if (rumbleSource != null && rumbleSource.clip != null) SoundArt.Wants("antlion_rumble", rumbleSource.clip, true);
            }
            catch (Exception e) { log("sfx (island) failed: " + e); }
            log($"sfx: {SoundArt.WantedCount - before} more on the island");
        }

        // ---- the badges ----

        static string UiDir => Path.Combine(Path.GetDirectoryName(MinePeakExport.Root), "ui", "badges");

        public static void TakeBadges(Action<string> log)
        {
            try
            {
                var gui = GUIManager.instance;
                var data = gui != null && gui.mainBadgeManager != null ? gui.mainBadgeManager.badgeData : null;
                if (data == null || data.Length == 0) data = Resources.FindObjectsOfTypeAll<BadgeData>();
                Directory.CreateDirectory(UiDir);
                var o = new JObject();
                foreach (var b in data)
                {
                    if (b == null) continue;
                    string key = b.linkedAchievement.ToString();
                    var j = new JObject();
                    try { j["name"] = LocalizedText.GetText(LocalizedText.GetNameIndex(b.displayName)); } catch (Exception) { j["name"] = b.displayName; }
                    try { j["description"] = LocalizedText.GetText(LocalizedText.GetDescriptionIndex(b.displayName)); } catch (Exception) { j["description"] = b.description; }
                    if (b.icon != null && MenuArt.SavePicture(b.icon, new Rect(0, 0, b.icon.width, b.icon.height), Path.Combine(UiDir, Safe(key) + ".png"))) j["file"] = Safe(key) + ".png";
                    j["secret"] = b.secret;
                    o[key] = j;
                }
                File.WriteAllText(Path.Combine(UiDir, "badges.json"), new JObject { ["badges"] = o }.ToString());
                log($"badges: {o.Count} of PEAK's badges into {UiDir}");
            }
            catch (Exception e) { log("badges failed: " + e); }
        }

        // ---- the guidebook's pages, as PEAK draws them ----

        static string BookDir => Path.Combine(Path.GetDirectoryName(MinePeakExport.Root), "items", "guidebook");

        public static IEnumerator TakeGuidebooks(Action<string> log)
        {
            Directory.CreateDirectory(BookDir);
            var books = SingletonAsset<ItemDatabase>.Instance.itemLookup.Values.Where(i => i != null && i is Guidebook).ToList();
            var index = new JObject();
            foreach (var prefab in books)
            {
                GameObject go = null;
                try
                {
                    go = UnityEngine.Object.Instantiate(prefab.gameObject, new Vector3(0, -800, 0), Quaternion.identity);
                    var rb = go.GetComponent<Rigidbody>();
                    if (rb != null) rb.isKinematic = true;
                }
                catch (Exception e) { log($"guidebook {prefab.name}: {e.Message}"); }
                if (go == null) continue;
                yield return null; yield return null;
                var pages = new JArray();
                try
                {
                    var gb = go.GetComponent<Guidebook>();
                    if (gb != null && gb.renderCamera != null && gb.pageSpreads != null)
                    {
                        if (gb.canvasScaler != null) gb.canvasScaler.scaleFactor = 1f;
                        var rt = new RenderTexture(1800, 900, 24, RenderTextureFormat.ARGB32);
                        var old = gb.renderCamera.targetTexture;
                        gb.renderCamera.targetTexture = rt;
                        for (int i = 0; i < gb.pageSpreads.Count; i++)
                        {
                            for (int j = 0; j < gb.pageSpreads.Count; j++) if (gb.pageSpreads[j] != null) gb.pageSpreads[j].gameObject.SetActive(j == i);
                            Canvas.ForceUpdateCanvases();
                            gb.renderCamera.Render();
                            var prev = RenderTexture.active;
                            RenderTexture.active = rt;
                            var tex = new Texture2D(1800, 900, TextureFormat.RGBA32, false);
                            tex.ReadPixels(new Rect(0, 0, 1800, 900), 0, 0);
                            tex.Apply();
                            RenderTexture.active = prev;
                            string file = Safe(prefab.name) + "_" + i + ".png";
                            File.WriteAllBytes(Path.Combine(BookDir, file), tex.EncodeToPNG());
                            UnityEngine.Object.Destroy(tex);
                            pages.Add(file);
                        }
                        gb.renderCamera.targetTexture = old;
                        rt.Release();
                    }
                }
                catch (Exception e) { log($"guidebook {prefab.name}: {e.Message}"); }
                index[prefab.name] = pages;
                UnityEngine.Object.Destroy(go);
                yield return null;
            }
            File.WriteAllText(Path.Combine(BookDir, "guidebook.json"), new JObject { ["books"] = index }.ToString());
            log($"guidebook: {books.Count} books, {index.Properties().Sum(p => ((JArray)p.Value).Count)} pages into {BookDir}");
        }
    }
}
