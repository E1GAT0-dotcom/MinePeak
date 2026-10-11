using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Newtonsoft.Json.Linq;
using UnityEngine;
using UnityEngine.Rendering;

namespace MinePeakExporter
{
    /// <summary>
    /// -minepeak-survey (with -minepeak-probe): PEAK's airport measured, to build it by hand in Minecraft. Into
    /// BepInEx/MinePeakData/survey:
    /// - objects.json: every drawn thing (where, how big, turned how, its mesh and materials and colour, solid or not);
    /// - plan_*.png: from straight above, 4 pixels a metre, in height bands (the floor and what's on it, the middle, the
    ///   roof, all); side_*.png from each side, section_*.png cut through the middle;
    /// - views/*.png and views.json: pictures from a grid of places in the hall, four ways each.
    /// Every picture's box is in survey.json, to turn its pixels back into places.
    /// </summary>
    internal static class Survey
    {
        static string Dir => Path.Combine(Safety.DataDir, "survey");
        const int PxPerMetre = 4;

        public static IEnumerator Run(Character ch, Action<string> log)
        {
            Directory.CreateDirectory(Path.Combine(Dir, "views"));
            var info = new JObject();
            Vector3 c = ch.Center;
            float floor = Physics.Raycast(c, Vector3.down, out var hit, 5f, ~0, QueryTriggerInteraction.Ignore) ? hit.point.y : c.y - 1f;
            info["scoutFloor"] = floor;
            var all = UnityEngine.Object.FindObjectsByType<Renderer>(FindObjectsSortMode.None)
                .Where(r => r != null && r.enabled && r.gameObject.activeInHierarchy && Vector3.Distance(r.bounds.center, c) < 250f).ToList();
            bool Skip(Renderer r) => r is ParticleSystemRenderer || r is SkinnedMeshRenderer || r.GetComponentInParent<Character>() != null
                || r.GetComponentInParent<Item>() != null || r.shadowCastingMode == ShadowCastingMode.ShadowsOnly || TPath(r.transform).Contains("BakeData");
            var rs = all.Where(r => !Skip(r)).ToList();
            // the building: what's under Map/BL_Airport and not the size of the ground
            Bounds building = new Bounds(c, Vector3.one);
            foreach (var r in rs) if (TPath(r.transform).StartsWith("Map/BL_Airport") && r.bounds.size.magnitude < 150f) building.Encapsulate(r.bounds);
            info["building"] = Box(building);
            log($"survey: {rs.Count} things drawn; the building {building.min:F0}..{building.max:F0}; the scout's floor at {floor:F2}");

            // the things
            var objects = new JArray();
            foreach (var r in rs)
            {
                var mf = r.GetComponent<MeshFilter>();
                var lod = r.GetComponentInParent<LODGroup>();
                int lodLevel = -1;
                if (lod != null) { var lods = lod.GetLODs(); for (int i = 0; i < lods.Length; i++) if (lods[i].renderers.Contains(r)) { lodLevel = i; break; } }
                var t = r.transform;
                objects.Add(new JObject
                {
                    ["path"] = TPath(t), ["mesh"] = mf != null && mf.sharedMesh != null ? mf.sharedMesh.name : null,
                    ["materials"] = new JArray(r.sharedMaterials.Where(m => m != null).Select(m => m.name)),
                    ["colour"] = Colour(r.sharedMaterial),
                    ["see"] = r.sharedMaterial != null && r.sharedMaterial.renderQueue >= 3000,
                    ["glow"] = r.sharedMaterial != null && r.sharedMaterial.IsKeywordEnabled("_EMISSION"),
                    ["center"] = V(r.bounds.center), ["size"] = V(r.bounds.size),
                    ["position"] = V(t.position), ["rotation"] = V(t.eulerAngles), ["scale"] = V(t.lossyScale),
                    ["solid"] = r.GetComponent<Collider>() != null && !r.GetComponent<Collider>().isTrigger,
                    ["layer"] = LayerMask.LayerToName(r.gameObject.layer), ["lod"] = lodLevel,
                });
            }
            File.WriteAllText(Path.Combine(Dir, "objects.json"), objects.ToString());
            log($"  objects.json: {objects.Count} things");
            yield return null;

            // pictures, lit as the game is but without shadows or fog; no scouts
            var light = Slicer.Lighting.Save();
            light.Apply(Slicer.Lighting.Mode.NoShadows);
            float lodBias = QualitySettings.lodBias; int maxLod = QualitySettings.maximumLODLevel;
            QualitySettings.lodBias = 1000f; QualitySettings.maximumLODLevel = 0;
            var pics = new JObject();
            try
            {
                var b = building; b.Expand(new Vector3(10f, 2f, 10f));
                float top = b.max.y;
                // plans: from above, in height bands
                foreach (var (name, lo, hi) in new[] { ("floor", floor - 0.6f, floor + 2.6f), ("mid", floor + 2.6f, floor + 7f), ("high", floor + 7f, top), ("all", b.min.y, top) })
                {
                    pics[ "plan_" + name] = Ortho("plan_" + name, new Vector3(b.center.x, hi + 0.05f, b.center.z), Quaternion.LookRotation(Vector3.down, Vector3.forward),
                        b.size.x, b.size.z, 0.05f, hi - lo + 0.05f);
                    yield return null;
                }
                // sides, and sections through the middle (2 m thick)
                foreach (var (name, fwd, width, depth) in new[] { ("side_north", Vector3.back, b.size.x, b.size.z), ("side_south", Vector3.forward, b.size.x, b.size.z),
                    ("side_east", Vector3.left, b.size.z, b.size.x), ("side_west", Vector3.right, b.size.z, b.size.x) })
                {
                    var pos = b.center - fwd * (depth / 2f + 1f);
                    pics[name] = Ortho(name, pos, Quaternion.LookRotation(fwd, Vector3.up), width, b.size.y, 0.5f, depth + 2f);
                    yield return null;
                }
                foreach (var (name, fwd, width) in new[] { ("section_x", Vector3.right, b.size.z), ("section_z", Vector3.forward, b.size.x) })
                {
                    var pos = b.center - fwd * 1f;
                    pics[name] = Ortho(name, pos, Quaternion.LookRotation(fwd, Vector3.up), width, b.size.y, 0.05f, 2f);
                    yield return null;
                }
                // a grid of places in the hall, eye height, four ways each (not inside anything)
                var views = new JArray();
                var hall = GameObject.Find("Map/BL_Airport/Main Meshes/Airport")?.GetComponent<Collider>()?.bounds ?? building;
                int n = 0;
                for (float x = hall.min.x + 8f; x < hall.max.x - 4f; x += 14f)
                    for (float z = hall.min.z + 8f; z < hall.max.z - 4f; z += 14f)
                    {
                        // standing on whatever floor is there under the scout's height and up
                        if (!Physics.Raycast(new Vector3(x, floor + 1.5f, z), Vector3.down, out var f, 4f, ~0, QueryTriggerInteraction.Ignore)) continue;
                        var eye = f.point + Vector3.up * 1.7f;
                        if (Physics.CheckSphere(eye, 0.3f, ~0, QueryTriggerInteraction.Ignore)) continue;
                        for (int yaw = 0; yaw < 360; yaw += 90)
                        {
                            string name = $"v{n:00}_{yaw}";
                            Persp(name, eye, yaw);
                            views.Add(new JObject { ["name"] = name, ["x"] = eye.x, ["y"] = eye.y, ["z"] = eye.z, ["yaw"] = yaw, ["pitch"] = 0, ["fov"] = 70 });
                        }
                        n++;
                        yield return null;
                    }
                File.WriteAllText(Path.Combine(Dir, "views.json"), views.ToString());
                log($"  {n} places in the hall, {views.Count} pictures");
            }
            finally
            {
                light.Restore();
                QualitySettings.lodBias = lodBias; QualitySettings.maximumLODLevel = maxLod;
            }
            info["pictures"] = pics;
            info["pxPerMetre"] = PxPerMetre;
            File.WriteAllText(Path.Combine(Dir, "survey.json"), info.ToString());
            log("  survey done");
        }

        /// <summary>
        /// The airport for MinePeak's builder, group by group, into %APPDATA%/MinePeak/airport: each group sliced on its own
        /// (everything else hidden), so every block knows what it is. Groups: the building's walls and floors, its roof,
        /// the scaffold, the windows, the carpet, the props (seats, plants, signs...: Minecraft models stand in for them,
        /// these are for reference and for any without one), and the rest (the tarmac, the ground, the planes).
        /// group.vox.gz: "MPV1", count, then per block x, y, z (short, Unity), r, g, b, flags (1 glass, 2 glows), alpha.
        /// </summary>
        public static IEnumerator Groups(Character ch, Action<string> log)
        {
            string dir = Path.Combine(Path.GetDirectoryName(MinePeakExport.Root), "airport");
            Directory.CreateDirectory(dir);
            if (!BlockColours.Load(log)) yield break;
            Vector3 c = ch.Center;
            var all = UnityEngine.Object.FindObjectsByType<Renderer>(FindObjectsSortMode.None)
                .Where(r => r != null && r.enabled && r.gameObject.activeInHierarchy && Vector3.Distance(r.bounds.center, c) < 250f).ToList();
            bool Never(Renderer r) => r is ParticleSystemRenderer || r is SkinnedMeshRenderer || r.GetComponentInParent<Character>() != null
                || r.GetComponentInParent<Item>() != null || r.shadowCastingMode == ShadowCastingMode.ShadowsOnly || TPath(r.transform).Contains("BakeData");
            string GroupOf(Renderer r)
            {
                string p = TPath(r.transform);
                if (p.Contains("Main Meshes/Airport (1)Roof")) return "roof";
                if (p.Contains("Main Meshes/Airport/")) return "walls";
                if (p.Contains("Main Meshes/Schaffold") || p.Contains("Outside grid")) return "scaffold";
                if (p.Contains("Main Meshes/Windows")) return "windows";
                if (p.Contains("Main Meshes/Carpet")) return "carpet";
                if (p.Contains("Main Meshes/")) return "walls";
                string[] props = { "Vegetation", "Seats", "Fences", "Lights", "Luggage", "Misc/", "stanchion", "Displays", "Flight Board", "Elevator",
                    "Conveyor", "Desert_Rock", "Photobooth", "Climbing Wall", "Mirror", "Boarding Sign", "scoutmaster", "Kiosk", "Chess" };
                return props.Any(k => p.Contains(k)) ? "props" : "rest";
            }
            var groups = all.Where(r => !Never(r)).GroupBy(GroupOf).ToDictionary(g => g.Key, g => g.ToList());
            log("airport groups: " + string.Join(", ", groups.Select(g => $"{g.Key} {g.Value.Count}")));
            foreach (var kv in groups)
            {
                var only = new HashSet<Renderer>(kv.Value);
                var hide = all.Where(r => !only.Contains(r)).ToList();
                Bounds box = kv.Value[0].bounds;
                foreach (var r in kv.Value) if (r.bounds.size.magnitude < 600f) box.Encapsulate(r.bounds);
                box.min = new Vector3(box.min.x, Mathf.Max(box.min.y, c.y - 30f), box.min.z);
                box.Expand(1f);
                var slicer = new Slicer();
                float t = Time.realtimeSinceStartup;
                yield return slicer.Capture(box, hide, log);
                // what's at the scout's eyes isn't part of anything (what they hold)
                var eye = Camera.main != null ? Camera.main.transform.position : c;
                int n = 0;
                using (var f = File.Create(Path.Combine(dir, kv.Key + ".vox.gz")))
                using (var gz = new System.IO.Compression.GZipStream(f, System.IO.Compression.CompressionLevel.Optimal))
                using (var w = new BinaryWriter(gz))
                {
                    w.Write(new[] { (byte)'M', (byte)'P', (byte)'V', (byte)'1' });
                    var list = new List<(int x, int y, int z, Color col, bool glass, bool glow, float alpha)>();
                    foreach (var v in slicer.Voxels)
                    {
                        var seen = slicer.Seen(v.Value);
                        if (seen == null) continue;
                        Slicer.Unkey(v.Key, out int x, out int y, out int z);
                        if ((new Vector3(x + 0.5f, y + 0.5f, z + 0.5f) - eye).sqrMagnitude < 1.6f * 1.6f) continue;
                        var (col, glass, glow) = seen.Value;
                        float alpha = glass ? v.Value.GlassAlpha / v.Value.NGlass : 1f;
                        list.Add((x, y, z, col, glass, Mathf.Max(glow.r, Mathf.Max(glow.g, glow.b)) > 0.55f, alpha));
                    }
                    w.Write(list.Count);
                    foreach (var e in list)
                    {
                        w.Write((short)e.x); w.Write((short)e.y); w.Write((short)e.z);
                        w.Write((byte)Mathf.RoundToInt(e.col.r * 255f)); w.Write((byte)Mathf.RoundToInt(e.col.g * 255f)); w.Write((byte)Mathf.RoundToInt(e.col.b * 255f));
                        w.Write((byte)((e.glass ? 1 : 0) | (e.glow ? 2 : 0)));
                        w.Write((byte)Mathf.RoundToInt(Mathf.Clamp01(e.alpha) * 255f));
                    }
                    n = list.Count;
                }
                log($"  {kv.Key}: {kv.Value.Count} renderers, {n} blocks, box {box.min:F0}..{box.max:F0}, {Time.realtimeSinceStartup - t:F0} s");
            }
            File.Copy(Path.Combine(Dir, "objects.json"), Path.Combine(dir, "objects.json"), true);
            var info = new JObject { ["date"] = DateTime.Now.ToString("yyyy-MM-dd HH:mm"), ["spawn"] = V(c + Vector3.down * 0.9f),
                ["eye"] = V(Camera.main != null ? Camera.main.transform.position : c) };
            var kiosk = UnityEngine.Object.FindAnyObjectByType<AirportCheckInKiosk>();
            if (kiosk != null) info["kiosk"] = V(kiosk.transform.position);
            File.WriteAllText(Path.Combine(dir, "airport.json"), info.ToString());
            log($"  the airport's groups are in {dir}");
        }

        static string TPath(Transform t) { var p = t.name; while (t.parent != null) { t = t.parent; p = t.name + "/" + p; } return p; }
        static JArray V(Vector3 v) => new JArray(Math.Round(v.x, 2), Math.Round(v.y, 2), Math.Round(v.z, 2));
        static JObject Box(Bounds b) => new JObject { ["min"] = V(b.min), ["max"] = V(b.max) };

        static string Colour(Material m)
        {
            if (m == null) return null;
            Color tint = m.HasProperty("_BaseColor") ? m.GetColor("_BaseColor") : m.HasProperty("_Color") ? m.GetColor("_Color") : Color.white;
            return "#" + ColorUtility.ToHtmlStringRGB(tint);
        }

        static Camera NewCamera()
        {
            var cam = new GameObject("MinePeakSurveyCamera").AddComponent<Camera>();
            cam.enabled = false;
            cam.useOcclusionCulling = false;
            cam.allowMSAA = false;
            cam.clearFlags = CameraClearFlags.SolidColor;
            cam.backgroundColor = new Color(1f, 0f, 1f);   // (magenta: nothing there)
            int character = LayerMask.NameToLayer("Character");
            cam.cullingMask = (Camera.main != null ? Camera.main.cullingMask : ~0) & (character >= 0 ? ~(1 << character) : ~0);
            return cam;
        }

        /// <summary>An orthographic picture, width x height metres at 4 pixels a metre, from pos looking along rot.</summary>
        static JObject Ortho(string name, Vector3 pos, Quaternion rot, float width, float height, float near, float far)
        {
            var cam = NewCamera();
            try
            {
                cam.orthographic = true;
                cam.orthographicSize = height / 2f;
                cam.aspect = width / height;
                cam.nearClipPlane = near; cam.farClipPlane = far;
                cam.transform.SetPositionAndRotation(pos, rot);
                Save(cam, name, Mathf.Clamp(Mathf.RoundToInt(width * PxPerMetre), 16, 4096), Mathf.Clamp(Mathf.RoundToInt(height * PxPerMetre), 16, 4096));
                return new JObject { ["position"] = V(pos), ["forward"] = V(cam.transform.forward), ["right"] = V(cam.transform.right), ["up"] = V(cam.transform.up),
                    ["width"] = width, ["height"] = height, ["near"] = near, ["far"] = far };
            }
            finally { UnityEngine.Object.Destroy(cam.gameObject); }
        }

        static void Persp(string name, Vector3 eye, float yaw)
        {
            var cam = NewCamera();
            try
            {
                cam.fieldOfView = 70f; cam.nearClipPlane = 0.05f; cam.farClipPlane = 2000f;
                cam.clearFlags = CameraClearFlags.Skybox;
                cam.transform.SetPositionAndRotation(eye, Quaternion.Euler(0f, yaw, 0f));
                Save(cam, "views/" + name, 960, 540);
            }
            finally { UnityEngine.Object.Destroy(cam.gameObject); }
        }

        static void Save(Camera cam, string name, int w, int h)
        {
            var rt = new RenderTexture(w, h, 24, RenderTextureFormat.ARGB32);
            cam.targetTexture = rt;
            var req = new RenderPipeline.StandardRequest { destination = rt };
            if (RenderPipeline.SupportsRenderRequest(cam, req)) RenderPipeline.SubmitRenderRequest(cam, req); else cam.Render();
            var prev = RenderTexture.active;
            RenderTexture.active = rt;
            var tex = new Texture2D(w, h, TextureFormat.RGB24, false);
            tex.ReadPixels(new Rect(0, 0, w, h), 0, 0);
            tex.Apply();
            RenderTexture.active = prev;
            File.WriteAllBytes(Path.Combine(Dir, name + ".png"), tex.EncodeToPNG());
            UnityEngine.Object.Destroy(tex);
            cam.targetTexture = null;
            rt.Release();
        }
    }
}
