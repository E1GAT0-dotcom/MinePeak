using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using Newtonsoft.Json.Linq;
using UnityEngine;
using UnityEngine.Rendering;
using Zorro.Core;

namespace MinePeakExporter
{
    /// <summary>
    /// -minepeak-diag (with -minepeak-export): no export; on the island, segment by segment, what the export gets wrong
    /// and why, into BepInEx/MinePeakData/diag: the plane wreck at the spawn, the plants (what kinds, how big, solid or
    /// not), what stands under the giant redwood, the volcano's colliders down a few columns, the summit's materials,
    /// and pictures (each segment from above, and the trouble spots).
    /// </summary>
    internal static class Diag
    {
        static string Dir => Path.Combine(Safety.DataDir, "diag");
        static readonly JObject Out = new JObject();

        static string TPath(Transform t) { var p = t.name; while (t.parent != null) { t = t.parent; p = t.name + "/" + p; } return p; }
        static string Kind(string name) => Regex.Replace(name, @"\s*\(\d+\)", "").Trim();
        static JArray V(Vector3 v) => new JArray(Math.Round(v.x, 1), Math.Round(v.y, 1), Math.Round(v.z, 1));
        /// <summary>Minecraft's x, y, z as PEAK's.</summary>
        static Vector3 Peak(float x, float y, float z) => new Vector3(x, y - 63f, -z);

        public static IEnumerator Segment(int index, Action<string> log)
        {
            Directory.CreateDirectory(Dir);
            var mh = Singleton<MapHandler>.Instance;
            var seg = mh.segments[index];
            string name = ((global::Segment)index).ToString();
            var parent = seg.segmentParent;
            var o = new JObject();
            Out[name] = o;
            log($"diag {name}: {(parent != null ? TPath(parent.transform) : "no parent")}");
            o["queriesHitBackfaces"] = Physics.queriesHitBackfaces;
            var rs = parent != null ? parent.GetComponentsInChildren<Renderer>(false).Where(r => r != null && r.enabled && !(r is ParticleSystemRenderer)).ToList() : new List<Renderer>();

            // the plants: by kind (names without their " (n)"), how many, how big, on which layer, solid or not, shader
            var plants = new JArray();
            foreach (var g in rs.Where(r => IsPlant(r)).GroupBy(r => Kind(r.gameObject.name)).OrderByDescending(g => g.Count()).Take(60))
            {
                var first = g.First();
                var cols = first.GetComponentsInChildren<Collider>().Concat(first.GetComponentsInParent<Collider>()).Distinct().ToList();
                plants.Add(new JObject
                {
                    ["kind"] = g.Key, ["count"] = g.Count(), ["path"] = TPath(first.transform),
                    ["size"] = V(new Vector3(g.Average(r => r.bounds.size.x), g.Average(r => r.bounds.size.y), g.Average(r => r.bounds.size.z))),
                    ["maxHeight"] = Math.Round(g.Max(r => r.bounds.size.y), 1), ["layer"] = LayerMask.LayerToName(first.gameObject.layer),
                    ["shader"] = first.sharedMaterial != null ? first.sharedMaterial.shader.name : null,
                    ["materials"] = new JArray(first.sharedMaterials.Where(m => m != null).Select(m => m.name).Distinct()),
                    ["colliders"] = new JArray(cols.Select(c => $"{c.GetType().Name}{(c is MeshCollider mc && mc.convex ? " convex" : "")}{(c.isTrigger ? " trigger" : "")} [{LayerMask.LayerToName(c.gameObject.layer)}]")),
                    ["lodGroup"] = first.GetComponentInParent<LODGroup>() != null,
                });
            }
            o["plants"] = plants;
            log($"  {plants.Count} kinds of plant");
            // every kind of thing drawn, for the record (kind, count, mean size)
            o["kinds"] = new JArray(rs.GroupBy(r => Kind(r.gameObject.name)).OrderByDescending(g => g.Count()).Take(150)
                .Select(g => $"{g.Count()} x {g.Key} ({g.Average(r => r.bounds.size.y):F1} m tall) {g.First().sharedMaterial?.name}"));

            if (index == 0)
            {
                // the plane wreck round the spawn
                var sp = SpawnPoint.allSpawnPoints.FirstOrDefault(s => s != null);
                if (sp != null)
                {
                    var c = sp.transform.position;
                    var near = UnityEngine.Object.FindObjectsByType<Renderer>(FindObjectsSortMode.None).Where(r => r != null && r.enabled && Vector3.Distance(r.bounds.ClosestPoint(c), c) < 25f).ToList();
                    o["spawn"] = V(c);
                    o["nearSpawn"] = new JArray(near.GroupBy(r => TPath(r.transform.parent != null ? r.transform.parent : r.transform)).Take(80)
                        .Select(g => $"{g.Key}: {g.Count()} ({string.Join(",", g.Select(r => r.sharedMaterial?.name).Distinct().Take(3))}) size {g.First().bounds.size:F0}"));
                    o["nearSpawnColliders"] = new JArray(Physics.OverlapSphere(c, 25f, ~0, QueryTriggerInteraction.Ignore).Select(k => $"{TPath(k.transform)} [{LayerMask.LayerToName(k.gameObject.layer)}] {k.bounds.size:F0}").Distinct().Take(80));
                    Shoot("spawn", c + new Vector3(0f, 12f, -30f), 0f, 20f);
                }
            }

            // columns down through the trouble spots: every hit, on any layer (Minecraft x, z as in the export)
            var columns = new List<(string what, float x, float z)>();
            if (index == 0) { columns.Add(("beach spawn", 22f, 360f)); columns.Add(("beach mid", 0f, 200f)); }
            if (index == 1) { columns.Add(("jungle floor", 0f, -400f)); columns.Add(("jungle floor 2", 60f, -450f)); }
            if (index == 2) { columns.Add(("mesa campfire", 0f, -1230f)); columns.Add(("mesa campfire n", 0f, -1240f)); }
            if (index == 3) { columns.Add(("volcano s 20,-1880", 20.5f, -1879.5f)); columns.Add(("volcano s -60,-1850", -59.5f, -1849.5f)); columns.Add(("volcano n 20,-1900", 20.5f, -1899.5f)); columns.Add(("tunnel -3,-1880", -2.5f, -1879.5f)); }
            if (index == 4) for (int bx = -2; bx <= 14; bx += 2) columns.Add(($"bridge x{bx}", bx + 0.5f, -2035.5f));   // (the Peak Bridge, temple to Citadel)
            if (columns.Count > 0)
            {
                var cj = new JObject();
                foreach (var (what, x, z) in columns)
                {
                    var p = Peak(x, 1500f, z);
                    var hits = Physics.RaycastAll(p, Vector3.down, 1700f, ~0, QueryTriggerInteraction.Ignore).OrderByDescending(h => h.point.y).Take(60);
                    var up = Physics.RaycastAll(Peak(x, -80f, z), Vector3.up, 1700f, ~0, QueryTriggerInteraction.Ignore).OrderBy(h => h.point.y).Take(60);
                    cj[what] = new JObject
                    {
                        ["down"] = new JArray(hits.Select(h => Hit(h))), ["up"] = new JArray(up.Select(h => Hit(h))),
                    };
                }
                o["columns"] = cj;
            }

            // the giant redwood and the volcano: their colliders and renderers
            if (index == 1 || index == 3 || index == 4)
            {
                string[] keys = index == 1 ? new[] { "Redwood Massive", "Big Redwood" } : new[] { "VolcanoModel", "Kiln", "Entrance", "Peak" };
                var found = new JArray();
                foreach (var c in UnityEngine.Object.FindObjectsByType<Collider>(FindObjectsSortMode.None))
                {
                    string path = TPath(c.transform);
                    if (!keys.Any(k => path.Contains(k))) continue;
                    found.Add($"{path} [{LayerMask.LayerToName(c.gameObject.layer)}] {c.GetType().Name}{(c is MeshCollider mc && mc.convex ? " CONVEX" : "")}{(c.enabled ? "" : " off")}{(c.isTrigger ? " trigger" : "")} box {c.bounds.min:F0}..{c.bounds.max:F0}");
                    if (found.Count > 120) break;
                }
                o["keyColliders"] = found;
            }

            // pictures: the segment from straight above (as the game draws it), and the trouble spots
            yield return null;
            var area = AreaBox(name);
            if (area != null)
            {
                var b = area.Value;
                ShootOrtho("top_" + name, new Vector3(b.center.x, 1500f, b.center.z), b.size.x, b.size.z);
            }
            if (index == 1)
            {
                // what the view of the giant redwood is made of: rays across it from the camera, every hit's object
                var eye = Peak(19f, 560f, -120f);
                var seen = new Dictionary<string, int>();
                for (int gy = -6; gy <= 6; gy++)
                    for (int gx = -10; gx <= 10; gx++)
                    {
                        var dir = Quaternion.Euler(5f + gy * 4f, gx * 4.5f, 0f) * Vector3.forward;
                        if (!Physics.Raycast(eye, dir, out var h, 2000f, ~0, QueryTriggerInteraction.Ignore)) continue;
                        var hr = h.collider.GetComponent<Renderer>() ?? h.collider.GetComponentInParent<Renderer>();
                        string k = $"{TPath(h.collider.transform)} [{LayerMask.LayerToName(h.collider.gameObject.layer)}] {h.collider.GetType().Name} {hr?.sharedMaterial?.name} size {h.collider.bounds.size:F0}";
                        seen[k] = seen.TryGetValue(k, out int c0) ? c0 + 1 : 1;
                    }
                o["redwoodView"] = new JArray(seen.OrderByDescending(kv => kv.Value).Select(kv => $"{kv.Value} x {kv.Key}"));
                // and what's drawn there, big: renderers over 20 m near the giant redwood
                var bigs = new JArray();
                foreach (var r in UnityEngine.Object.FindObjectsByType<Renderer>(FindObjectsSortMode.None))
                {
                    if (r == null || !r.enabled || r.bounds.size.magnitude < 30f) continue;
                    var cc = r.bounds.center;
                    if (Mathf.Abs(cc.x - 19f) > 150f || Mathf.Abs(cc.z - 300f) > 150f) continue;
                    var col = r.GetComponent<Collider>() ?? r.GetComponentInChildren<Collider>();
                    bigs.Add($"{TPath(r.transform)} {r.sharedMaterial?.name} [{LayerMask.LayerToName(r.gameObject.layer)}] box {r.bounds.min:F0}..{r.bounds.max:F0} collider {(col != null ? col.GetType().Name + (col.enabled ? "" : " off") + " [" + LayerMask.LayerToName(col.gameObject.layer) + "]" : "none")}");
                    if (bigs.Count > 80) break;
                }
                o["redwoodBig"] = bigs;
                Shoot("redwood_side", Peak(19f, 560f, -120f), 0f, 5f);
                Shoot("redwood_down", Peak(19f, 900f, -347f), 0f, 89f);
                Shoot("redwood_under", Peak(19f, 400f, -230f), 0f, -10f);
                Shoot("cliff_front", Peak(0f, 120f, 140f), 0f, -15f);
            }
            if (index == 2) Shoot("mesa_streaks", Peak(-142f, 700f, -1180f), 0f, 10f);
            if (index == 3) { Shoot("volcano_entrance", Peak(5f, 960f, -1900f), 0f, 5f); }
            if (index == 4)
            {
                for (int yaw = 0; yaw < 360; yaw += 90) Shoot($"kiln_inside_{yaw}", Peak(5f, 1000f, -2150f), yaw, 0f);
                Shoot("summit", Peak(5f, 1330f, -2050f), 0f, 35f);
                // what the summit is made of: the highest things
                var top = new JArray();
                foreach (var h in Physics.RaycastAll(Peak(5f, 1500f, -2150f), Vector3.down, 400f, ~0, QueryTriggerInteraction.Ignore).OrderByDescending(h => h.point.y).Take(5)) top.Add(Hit(h));
                o["summitTop"] = top;
            }
            // the dev test's tour spots (DevTest.plan, from the newest export): the beach, each campfire, above each
            try
            {
                var info = LatestInfo();
                if (info != null)
                    foreach (JObject a in info["areas"])
                    {
                        if ((string)a["segment"] != name) continue;
                        string sname = name.ToLowerInvariant();
                        if (index == 0 && a["spawns"] is JArray sp && sp.Count > 0)
                        {
                            float bx = (float)sp[0][0], by = (float)sp[0][1] + 1f, bz = (float)sp[0][2];
                            Shoot("tour_beach", Peak(bx + 0.5f, by + 1.6f, bz + 0.5f), 0f, 0f);
                            Shoot("tour_beach_above", Peak(bx + 0.5f, by + 40f, bz + 40f), 0f, 20f);
                        }
                        if (a["campfire"] is JArray c)
                        {
                            float cx = (float)c[0], cy = (float)c[1] + 1f, cz = (float)c[2];
                            Shoot($"tour_{sname}_campfire", Peak(cx + 0.5f, cy + 2.6f, cz + 4.5f), 0f, 5f);
                            Shoot($"tour_{sname}_above", Peak(cx, cy + 60f, cz + 60f), 0f, 25f);
                        }
                    }
                // DevTest.plan's other spots: over the way between each two campfires; the last segment from the south;
                // the swamp temple's front and the Mesa's side (from the campfires of their segments)
                if (info != null && ((JArray)info["areas"]).FirstOrDefault(x => (string)x["segment"] == name && x["campfire"] is JArray) is JObject own)
                {
                    var c = (JArray)own["campfire"];
                    float cx = (float)c[0], cy = (float)c[1], cz = (float)c[2];
                    if (name == "Caldera") Shoot("tour_temple_front", Peak(cx, cy + 15f, cz + 70f), 0f, 10f);
                    if (name == "Caldera") Shoot("tour_bridge_side", Peak(cx + 60f, cy + 20f, cz - 40f), -90f, 10f);
                    if (name == "Alpine") Shoot("tour_mesa_side", Peak(cx + 320f, cy - 60f, cz + 250f), -90f, 0f);
                    // what could be a door, a gate or something to climb, near this campfire
                    var near = new JArray();
                    var at = Peak(cx, cy, cz);
                    foreach (var col in UnityEngine.Object.FindObjectsByType<Collider>(FindObjectsSortMode.None))
                    {
                        string pth = TPath(col.transform), low = pth.ToLowerInvariant();
                        if (!(low.Contains("door") || low.Contains("gate") || low.Contains("medallion") || low.Contains("entrance") || low.Contains("rope")
                            || low.Contains("ladder") || low.Contains("climb") || low.Contains("vine") || low.Contains("chain"))) continue;
                        if ((col.bounds.center - at).magnitude > 400f) continue;
                        near.Add($"{pth} [{LayerMask.LayerToName(col.gameObject.layer)}] {col.GetType().Name}{(col.isTrigger ? " trigger" : "")}{(col.enabled ? "" : " off")} {col.bounds.min:F0}..{col.bounds.max:F0}");
                        if (near.Count > 60) break;
                    }
                    o["doorsAndClimbs"] = near;
                }
                if (info != null)
                {
                    var areas = ((JArray)info["areas"]).Cast<JObject>().ToList();
                    var fires = areas.Where(x => x["campfire"] is JArray).ToList();
                    for (int i = 1; i < fires.Count; i++)
                    {
                        if ((string)fires[i]["segment"] != name) continue;
                        var p = (JArray)fires[i - 1]["campfire"]; var c = (JArray)fires[i]["campfire"];
                        Shoot($"tour_{name.ToLowerInvariant()}_mid", Peak(((float)p[0] + (float)c[0]) / 2f, Mathf.Max((float)p[1], (float)c[1]) + 25f, ((float)p[2] + (float)c[2]) / 2f), 0f, 20f);
                    }
                    var last = areas.LastOrDefault();
                    if (last != null && (string)last["segment"] == name && last["campfire"] == null && fires.Count > 0)
                    {
                        float fy = (float)fires[fires.Count - 1]["campfire"][1];
                        float cx = (int)last["x0"] + (int)last["width"] / 2f, south = (int)last["z0"] + (int)last["depth"];
                        Shoot("tour_summit_far", Peak(cx, fy + 200f, south + 150f), 0f, 15f);
                        Shoot("tour_summit_over", Peak(cx, fy + 260f, south - (int)last["depth"] / 3f), 0f, 50f);
                    }
                }
            }
            catch (Exception e) { log("  tour pictures: " + e.Message); }
            // helpers for PEAK's light baking: are any of them solid (scanned as rock)?
            var helpers = new JArray();
            foreach (var c in UnityEngine.Object.FindObjectsByType<Collider>(FindObjectsSortMode.None))
            {
                string pth = TPath(c.transform), low = pth.ToLowerInvariant();
                if (!(low.Contains("lightblocker") || low.Contains("shadowblock") || low.Contains("bakedata") || low.Contains("/pit") || low.Contains("darkfade"))) continue;
                helpers.Add($"{pth} [{LayerMask.LayerToName(c.gameObject.layer)}] {c.GetType().Name}{(c.enabled ? "" : " off")}{(c.isTrigger ? " trigger" : "")} box {c.bounds.min:F0}..{c.bounds.max:F0}");
                if (helpers.Count > 60) break;
            }
            o["helperColliders"] = helpers;
            File.WriteAllText(Path.Combine(Dir, "diag.json"), Out.ToString());
        }

        static JObject LatestInfo()
        {
            var dir = new DirectoryInfo(MinePeakExport.Root).GetDirectories().Where(d => File.Exists(Path.Combine(d.FullName, "island.json")) && !d.Name.StartsWith("exporting_"))
                .OrderByDescending(d => d.LastWriteTimeUtc).FirstOrDefault();
            return dir == null ? null : JObject.Parse(File.ReadAllText(Path.Combine(dir.FullName, "island.json")));
        }

        static bool IsPlant(Renderer r)
        {
            string s = ((r.sharedMaterial != null ? r.sharedMaterial.name + " " + r.sharedMaterial.shader.name : "") + " " + TPath(r.transform)).ToLowerInvariant();
            return s.Contains("foliage") || s.Contains("tree") || s.Contains("palm") || s.Contains("redwood") || s.Contains("bush") || s.Contains("leaf") || s.Contains("leaves")
                || s.Contains("cactus") || s.Contains("pine") || s.Contains("fern") || s.Contains("grass") || s.Contains("plant") || s.Contains("flower") || s.Contains("shroom") || s.Contains("mushroom");
        }

        static string Hit(RaycastHit h)
        {
            var c = h.collider;
            var r = c.GetComponent<Renderer>() ?? c.GetComponentInParent<Renderer>();
            return $"{h.point.y + 63f:F1} n{h.normal.y:F2} {TPath(c.transform)} [{LayerMask.LayerToName(c.gameObject.layer)}] {c.GetType().Name}{(c is MeshCollider mc && mc.convex ? " CONVEX" : "")} {r?.sharedMaterial?.name}";
        }

        /// <summary>The export's rectangle for a segment (from the newest island.json), in PEAK's coordinates.</summary>
        static Bounds? AreaBox(string segment)
        {
            try
            {
                var dir = new DirectoryInfo(MinePeakExport.Root).GetDirectories().Where(d => File.Exists(Path.Combine(d.FullName, "island.json")) && !d.Name.StartsWith("exporting_"))
                    .OrderByDescending(d => d.LastWriteTimeUtc).FirstOrDefault();
                if (dir == null) return null;
                var info = JObject.Parse(File.ReadAllText(Path.Combine(dir.FullName, "island.json")));
                foreach (JObject a in info["areas"])
                    if ((string)a["segment"] == segment)
                    {
                        float x0 = (int)a["x0"], z0 = (int)a["z0"], w = (int)a["width"], d = (int)a["depth"];
                        return new Bounds(new Vector3(x0 + w / 2f, 600f, -(z0 + d / 2f)), new Vector3(w, 1400f, d));
                    }
            }
            catch (Exception) { }
            return null;
        }

        static Camera NewCamera()
        {
            var cam = new GameObject("MinePeakDiagCamera").AddComponent<Camera>();
            cam.enabled = false;
            cam.useOcclusionCulling = false;
            cam.nearClipPlane = 0.1f; cam.farClipPlane = 4000f;
            var main = Camera.main;
            cam.cullingMask = (main != null ? main.cullingMask : ~0) & ~(1 << 31);
            int character = LayerMask.NameToLayer("Character");
            if (character >= 0) cam.cullingMask &= ~(1 << character);
            try
            {
                var md = main != null ? main.GetComponent<UnityEngine.Rendering.Universal.UniversalAdditionalCameraData>() : null;
                if (md != null)
                {
                    int index = (int)HarmonyLib.AccessTools.Field(typeof(UnityEngine.Rendering.Universal.UniversalAdditionalCameraData), "m_RendererIndex").GetValue(md);
                    var data = UnityEngine.Rendering.Universal.CameraExtensions.GetUniversalAdditionalCameraData(cam);
                    data.SetRenderer(index);
                    data.renderShadows = true;
                    data.renderPostProcessing = true;
                }
            }
            catch (Exception) { }
            return cam;
        }

        static void Shoot(string name, Vector3 pos, float yaw, float pitch)
        {
            var cam = NewCamera();
            try
            {
                cam.fieldOfView = 70f; cam.clearFlags = CameraClearFlags.Skybox;
                cam.transform.SetPositionAndRotation(pos, Quaternion.Euler(pitch, yaw, 0f));
                Save(cam, name, 1280, 720);
            }
            finally { UnityEngine.Object.Destroy(cam.gameObject); }
        }

        static void ShootOrtho(string name, Vector3 pos, float width, float depth)
        {
            var cam = NewCamera();
            try
            {
                cam.orthographic = true; cam.orthographicSize = depth / 2f; cam.aspect = width / depth;
                cam.clearFlags = CameraClearFlags.SolidColor; cam.backgroundColor = new Color(1f, 0f, 1f);
                cam.farClipPlane = 1700f;
                cam.transform.SetPositionAndRotation(pos, Quaternion.LookRotation(Vector3.down, Vector3.forward));
                Save(cam, name, Mathf.Clamp(Mathf.RoundToInt(width), 16, 4096), Mathf.Clamp(Mathf.RoundToInt(depth), 16, 4096));
            }
            finally { UnityEngine.Object.Destroy(cam.gameObject); }
        }

        static void Save(Camera cam, string name, int w, int h)
        {
            var rt = new RenderTexture(w, h, 24, RenderTextureFormat.ARGB32);
            cam.targetTexture = rt; cam.aspect = (float)w / h;
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
