using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace MinePeakExporter
{
    /// <summary>
    /// PEAK's sounds, for MinePeak to play them in Minecraft: each stage's music as you reach it (AmbienceAudio's climb
    /// stingers), its sunrise, sunset and night music, the ambience loops, the campfire's crackle and the main menu's
    /// music. Saved from the player's own PEAK as it exports, as WAV files, into %APPDATA%/MinePeak/sounds with sounds.json
    /// saying what each is; nothing of PEAK's ships with MinePeak. A clip PEAK keeps uncompressed is copied as it is; one it
    /// keeps compressed or streams can't be read, and is recorded as it plays (silently: PEAK is muted while it exports),
    /// several at once. Each is saved once: later exports keep what's there.
    /// </summary>
    internal static class SoundArt
    {
        static string Dir => Path.Combine(Path.GetDirectoryName(MinePeakExport.Root), "sounds");

        /// <summary>What to save: its role (what MinePeak plays it for), the clip, and whether it loops.</summary>
        static readonly List<(string role, AudioClip clip, bool loop)> Wanted = new List<(string, AudioClip, bool)>();
        public static bool Busy;

        static string Safe(string s)
        {
            var o = new System.Text.StringBuilder();
            foreach (char ch in s ?? "sound") o.Append(char.IsLetterOrDigit(ch) ? char.ToLowerInvariant(ch) : '_');
            return o.ToString();
        }

        static void Want(string role, AudioClip clip, bool loop)
        {
            if (clip == null || Wanted.Any(w => w.role == role)) return;
            Wanted.Add((role, clip, loop));
        }

        internal static void Wants(string role, AudioClip clip, bool loop) => Want(role, clip, loop);
        internal static int WantedCount => Wanted.Count;

        /// <summary>On PEAK's title screen: its music (the looping sources playing there).</summary>
        public static void TakeMenu(Action<string> log)
        {
            try
            {
                foreach (var s in UnityEngine.Object.FindObjectsByType<AudioSource>(FindObjectsInactive.Include, FindObjectsSortMode.None))
                    if (s.clip != null && s.loop && s.clip.length > 20f) { Want("menu_music", s.clip, true); log($"sound: the menu's music is {s.clip.name} ({s.clip.length:F0} s)"); break; }
            }
            catch (Exception e) { log("sound (menu) failed: " + e.Message); }
        }

        /// <summary>On the island: the scout's ambience (its stingers and loops) and a campfire's sounds.</summary>
        public static void TakeIsland(Action<string> log)
        {
            try
            {
                var amb = UnityEngine.Object.FindObjectsByType<AmbienceAudio>(FindObjectsInactive.Include, FindObjectsSortMode.None).FirstOrDefault();
                if (amb != null)
                {
                    foreach (var f in typeof(AmbienceAudio).GetFields(BindingFlags.Public | BindingFlags.Instance))
                    {
                        string role = Safe(f.Name);   // (climbStingerBeach: climbstingerbeach; sunRiseStinger: sunrisestinger_0...)
                        if (f.FieldType == typeof(AudioClip)) Want(role, f.GetValue(amb) as AudioClip, false);
                        else if (f.FieldType == typeof(AudioClip[]) && f.GetValue(amb) is AudioClip[] arr)
                            for (int i = 0; i < arr.Length; i++) Want(role + "_" + i, arr[i], false);
                    }
                    // the ambience loops: every looping source under the scout's ambience, by its object's name
                    foreach (var s in amb.GetComponentsInChildren<AudioSource>(true))
                        if (s != amb.stingerSource && s != amb.mainMusic && s.clip != null)
                            Want("ambience_" + Safe(s.gameObject.name), s.clip, true);
                }
                else log("sound: no AmbienceAudio found");
                var fire = UnityEngine.Object.FindObjectsByType<Campfire>(FindObjectsInactive.Include, FindObjectsSortMode.None).FirstOrDefault();
                if (fire != null)
                {
                    if (fire.loop != null) Want("campfire_loop", fire.loop.clip, true);
                    if (fire.fireStart != null && fire.fireStart.Length > 0 && fire.fireStart[0] != null && fire.fireStart[0].clips != null)
                        for (int i = 0; i < fire.fireStart[0].clips.Length; i++) Want("campfire_light_" + i, fire.fireStart[0].clips[i], false);
                }
                log($"sound: {Wanted.Count} of PEAK's sounds wanted: " + string.Join(", ", Wanted.Select(w => $"{w.role}={w.clip.name}")));
            }
            catch (Exception e) { log("sound (island) failed: " + e.Message); }
        }

        /// <summary>Saves what's wanted (in the background, while the export goes on; Busy till done).</summary>
        public static IEnumerator Save(Action<string> log)
        {
            Busy = true;
            Directory.CreateDirectory(Dir);
            var index = Load();
            var record = new List<(string role, AudioClip clip, bool loop)>();
            int copied = 0, kept = 0;
            foreach (var w in Wanted)
            {
                string file = w.role + ".wav";
                var had = index[w.role] as JObject;
                if (had != null && (string)had["clip"] == w.clip.name && File.Exists(Path.Combine(Dir, file))) { kept++; continue; }
                float[] data = null;
                try
                {
                    if (w.clip.loadType == AudioClipLoadType.DecompressOnLoad)
                    {
                        if (w.clip.loadState != AudioDataLoadState.Loaded) w.clip.LoadAudioData();
                        data = new float[w.clip.samples * w.clip.channels];
                        if (!w.clip.GetData(data, 0) || data.All(v => v == 0f)) data = null;
                    }
                }
                catch (Exception) { data = null; }
                if (data != null) { Write(file, data, w.clip.channels, w.clip.frequency); Note(index, w, file, w.clip.channels, w.clip.frequency); copied++; }
                else record.Add(w);
            }
            log($"sound: {copied} copied, {kept} kept from before, {record.Count} to record as they play");
            // the rest recorded, 10 at a time (each its own source and a listener on it), for as long as each lasts
            int recorded = 0;
            for (int at = 0; at < record.Count; at += 16)
            {
                var batch = record.Skip(at).Take(16).ToList();
                var recs = new List<(string role, AudioClip clip, bool loop, Recorder rec, GameObject go)>();
                foreach (var w in batch)
                {
                    var go = new GameObject("MinePeak sound " + w.role);
                    UnityEngine.Object.DontDestroyOnLoad(go);
                    var src = go.AddComponent<AudioSource>();
                    src.clip = w.clip; src.loop = false; src.volume = 1f; src.spatialBlend = 0f; src.priority = 0;
                    src.ignoreListenerPause = true; src.ignoreListenerVolume = false;   // (heard by no one: the listener is at 0; the recorder gets it before that)
                    src.bypassReverbZones = true;   // (not bypassEffects: that would bypass the recorder too)
                    var rec = go.AddComponent<Recorder>();
                    recs.Add((w.role, w.clip, w.loop, rec, go));
                }
                foreach (var r in recs) r.go.GetComponent<AudioSource>().Play();
                float longest = batch.Max(w => Mathf.Min(w.clip.length, 300f)), t0 = Time.realtimeSinceStartup;
                while (Time.realtimeSinceStartup - t0 < longest + 0.5f) yield return null;
                foreach (var r in recs)
                {
                    var src = r.go.GetComponent<AudioSource>();
                    src.Stop();
                    float[] data; int ch;
                    lock (r.rec.Lock) { data = r.rec.Samples.ToArray(); ch = r.rec.Channels; }
                    int rate = AudioSettings.outputSampleRate;
                    // (only as long as the clip: the recorder ran on after it ended)
                    int want = Mathf.Min(data.Length, Mathf.CeilToInt(r.clip.length * rate) * Math.Max(1, ch));
                    if (ch > 0 && want > rate / 10 && data.Take(want).Any(v => v != 0f))
                    {
                        Write(r.role + ".wav", data.Take(want).ToArray(), ch, rate);
                        Note(index, (r.role, r.clip, r.loop), r.role + ".wav", ch, rate);
                        recorded++;
                    }
                    else log($"sound: {r.role} ({r.clip.name}) came out silent");
                    UnityEngine.Object.Destroy(r.go);
                }
            }
            File.WriteAllText(Path.Combine(Dir, "sounds.json"), new JObject { ["sounds"] = index }.ToString());
            log($"sound: {recorded} recorded; {index.Count} in all, into {Dir}");
            Busy = false;
        }

        static JObject Load()
        {
            try
            {
                var p = Path.Combine(Dir, "sounds.json");
                if (File.Exists(p) && JObject.Parse(File.ReadAllText(p))["sounds"] is JObject o) return o;
            }
            catch (Exception) { }
            return new JObject();
        }

        static void Note(JObject index, (string role, AudioClip clip, bool loop) w, string file, int channels, int rate)
        {
            index[w.role] = new JObject { ["file"] = file, ["clip"] = w.clip.name, ["length"] = Math.Round(w.clip.length, 2), ["channels"] = channels, ["rate"] = rate, ["loop"] = w.loop };
        }

        /// <summary>Float samples (interleaved) into a 16-bit PCM WAV.</summary>
        static void Write(string file, float[] data, int channels, int rate)
        {
            using (var w = new BinaryWriter(File.Create(Path.Combine(Dir, file))))
            {
                int bytes = data.Length * 2;
                w.Write(new[] { 'R', 'I', 'F', 'F' }); w.Write(36 + bytes); w.Write(new[] { 'W', 'A', 'V', 'E' });
                w.Write(new[] { 'f', 'm', 't', ' ' }); w.Write(16); w.Write((short)1); w.Write((short)channels); w.Write(rate);
                w.Write(rate * channels * 2); w.Write((short)(channels * 2)); w.Write((short)16);
                w.Write(new[] { 'd', 'a', 't', 'a' }); w.Write(bytes);
                foreach (var v in data) w.Write((short)Mathf.Clamp(Mathf.RoundToInt(v * 32767f), -32768, 32767));
            }
        }

        /// <summary>Keeps what its source plays (on Unity's audio thread).</summary>
        internal sealed class Recorder : MonoBehaviour
        {
            public readonly object Lock = new object();
            public readonly List<float> Samples = new List<float>();
            public int Channels;

            void OnAudioFilterRead(float[] data, int channels)
            {
                lock (Lock) { Channels = channels; Samples.AddRange(data); }
            }
        }
    }
}
