using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Newtonsoft.Json.Linq;
using Unity.Collections;
using UnityEngine;
using UnityEngine.Rendering;

namespace MinePeakExporter
{
    /// <summary>
    /// -minepeak-probe: what the airport is made of, for building it from what's drawn rather than its colliders.
    /// Its renderers and meshes (readable or not, and whether the other ways of reading them work), its materials
    /// (shaders, see-through, glowing, their textures' colours, if textures can be read at all), and pictures of it
    /// from fixed places (views.json has where, for the same pictures in Minecraft). All in BepInEx/MinePeakData/probe.
    /// </summary>
    internal static class Probe
    {
        static string Dir => Path.Combine(Safety.DataDir, "probe");

        public static IEnumerator Run(Character ch, Action<string> log)
        {
            Directory.CreateDirectory(Dir);
            Vector3 center = ch.Center;
            var rs = UnityEngine.Object.FindObjectsByType<Renderer>(FindObjectsSortMode.None)
                .Where(r => r != null && r.enabled && r.gameObject.activeInHierarchy && Vector3.Distance(r.bounds.center, center) < 200f).ToList();
            log($"probe: {rs.Count} renderers near the scout (graphics: {SystemInfo.graphicsDeviceType}, batch mode {Application.isBatchMode})");
            log("  by type: " + string.Join(", ", rs.GroupBy(r => r.GetType().Name).Select(g => $"{g.Key} {g.Count()}")));
            log($"  LOD groups: {UnityEngine.Object.FindObjectsByType<LODGroup>(FindObjectsSortMode.None).Count(l => Vector3.Distance(l.transform.position, center) < 200f)}");

            // meshes
            int readable = 0, unreadable = 0, batched = 0, noMesh = 0;
            long tris = 0;
            Mesh firstLocked = null;
            foreach (var r in rs.OfType<MeshRenderer>())
            {
                var m = r.GetComponent<MeshFilter>()?.sharedMesh;
                if (m == null) { noMesh++; continue; }
                if (r.isPartOfStaticBatch) batched++;
                if (m.isReadable) { readable++; for (int s = 0; s < m.subMeshCount; s++) tris += m.GetIndexCount(s) / 3; }
                else { unreadable++; if (firstLocked == null) firstLocked = m; }
            }
            log($"  mesh renderers: {readable} readable ({tris} triangles), {unreadable} not readable, {batched} static-batched, {noMesh} without a mesh");
            // not readable: from the GPU (the vertex and index buffers, as raw buffers), checked against the mesh's bounds
            int shown = 0;
            foreach (var r in rs.OfType<MeshRenderer>())
            {
                var m = r.GetComponent<MeshFilter>()?.sharedMesh;
                if (m == null || m.isReadable || shown >= 4) continue;
                shown++;
                log($"  not readable: '{m.name}' ({m.vertexCount} vertices, {m.subMeshCount} submeshes, index {m.indexFormat}), attributes: "
                    + string.Join(", ", m.GetVertexAttributes().Select(a => $"{a.attribute} {a.format}x{a.dimension} s{a.stream} @{m.GetVertexAttributeOffset(a.attribute)}")));
                try
                {
                    m.vertexBufferTarget |= GraphicsBuffer.Target.Raw;
                    m.indexBufferTarget |= GraphicsBuffer.Target.Raw;
                    int stream = m.GetVertexAttributeStream(VertexAttribute.Position), off = m.GetVertexAttributeOffset(VertexAttribute.Position);
                    using (var vb = m.GetVertexBuffer(stream))
                    using (var ib = m.GetIndexBuffer())
                    {
                        var raw = new byte[vb.count * vb.stride];
                        vb.GetData(raw);
                        var b = new Bounds(new Vector3(BitConverter.ToSingle(raw, off), BitConverter.ToSingle(raw, off + 4), BitConverter.ToSingle(raw, off + 8)), Vector3.zero);
                        for (int i = 0; i < vb.count; i++)
                            b.Encapsulate(new Vector3(BitConverter.ToSingle(raw, i * vb.stride + off), BitConverter.ToSingle(raw, i * vb.stride + off + 4), BitConverter.ToSingle(raw, i * vb.stride + off + 8)));
                        var idx = new byte[ib.count * ib.stride];
                        ib.GetData(idx);
                        log($"    GPU: {vb.count} x {vb.stride} B vertices, bounds {b.min:F2}..{b.max:F2} (mesh says {m.bounds.min:F2}..{m.bounds.max:F2}); {ib.count} x {ib.stride} B indices, first {(ib.stride == 2 ? BitConverter.ToUInt16(idx, 0) : BitConverter.ToUInt32(idx, 0))}");
                    }
                }
                catch (Exception e) { log("    GPU read failed: " + e.Message); }
            }
            yield return null;

            // materials: by how many renderers use them
            var mats = new Dictionary<Material, int>();
            foreach (var r in rs) foreach (var m in r.sharedMaterials) if (m != null) mats[m] = mats.TryGetValue(m, out int k) ? k + 1 : 1;
            log($"  materials: {mats.Count}; shaders: " + string.Join(", ", mats.Keys.GroupBy(m => m.shader.name).OrderByDescending(g => g.Count()).Take(12).Select(g => $"{g.Key} {g.Count()}")));
            int see = mats.Keys.Count(m => m.renderQueue >= 3000), glow = mats.Keys.Count(m => m.IsKeywordEnabled("_EMISSION"));
            int texReadable = mats.Keys.Count(m => m.mainTexture is Texture2D t && t.isReadable), tex = mats.Keys.Count(m => m.mainTexture != null);
            log($"  see-through {see}, glowing {glow}; with a texture {tex} ({texReadable} readable)");
            var table = new JArray();
            foreach (var kv in mats.OrderByDescending(kv => kv.Value).Take(60))
            {
                var m = kv.Key;
                Color tint = m.HasProperty("_BaseColor") ? m.GetColor("_BaseColor") : m.HasProperty("_Color") ? m.GetColor("_Color") : Color.white;
                Color? avg = m.mainTexture != null ? Average(m.mainTexture) : null;
                Color c = avg.HasValue ? avg.Value * tint : tint;
                table.Add(new JObject { ["material"] = m.name, ["shader"] = m.shader.name, ["renderers"] = kv.Value, ["queue"] = m.renderQueue,
                    ["texture"] = m.mainTexture != null ? m.mainTexture.name : null, ["tint"] = Hex(tint), ["textureAverage"] = avg.HasValue ? Hex(avg.Value) : null,
                    ["colour"] = Hex(c), ["emission"] = m.IsKeywordEnabled("_EMISSION") });
            }
            File.WriteAllText(Path.Combine(Dir, "materials.json"), table.ToString());
            // the 25 most used in detail: every texture (and its average colour) and colour the shader has
            var detail = new JArray();
            foreach (var kv in mats.OrderByDescending(kv => kv.Value).Take(25))
            {
                var m = kv.Key;
                var o = new JObject { ["material"] = m.name, ["shader"] = m.shader.name, ["keywords"] = string.Join(" ", m.shaderKeywords) };
                for (int i = 0; i < m.shader.GetPropertyCount(); i++)
                {
                    string n = m.shader.GetPropertyName(i);
                    switch (m.shader.GetPropertyType(i))
                    {
                        case ShaderPropertyType.Texture:
                            var t = m.GetTexture(n);
                            if (t != null) { var a = Average(t); o["tex " + n] = t.name + " " + t.width + "x" + t.height + (a.HasValue ? " avg " + Hex(a.Value) : ""); }
                            break;
                        case ShaderPropertyType.Color: o["col " + n] = Hex(m.GetColor(n)) + " a" + m.GetColor(n).a.ToString("F2"); break;
                        case ShaderPropertyType.Float: case ShaderPropertyType.Range: o["f " + n] = m.GetFloat(n); break;
                    }
                }
                detail.Add(o);
            }
            File.WriteAllText(Path.Combine(Dir, "materials_detail.json"), detail.ToString());
            // vertex colours (some games colour with them): the average per material, from readable meshes
            var vcol = new Dictionary<string, (Color sum, int n)>();
            foreach (var r in rs.OfType<MeshRenderer>())
            {
                var mesh = r.GetComponent<MeshFilter>()?.sharedMesh;
                if (mesh == null || !mesh.isReadable || !mesh.HasVertexAttribute(VertexAttribute.Color) || r.sharedMaterial == null) continue;
                var cs = mesh.colors;
                Color s = Color.clear;
                foreach (var c in cs) s += c;
                var key = r.sharedMaterial.name;
                vcol[key] = vcol.TryGetValue(key, out var e) ? (e.sum + s / Math.Max(1, cs.Length), e.n + 1) : (s / Math.Max(1, cs.Length), 1);
            }
            log("  vertex colours (readable meshes): " + string.Join(", ", vcol.OrderByDescending(kv => kv.Value.n).Take(15).Select(kv => $"{kv.Key} {Hex(kv.Value.sum / kv.Value.n)}")));
            log($"  the 60 most used materials and their colours: probe/materials.json");
            yield return null;

            // the airport's box (from what's drawn), then pictures
            Bounds box = new Bounds(center, Vector3.one);
            foreach (var r in rs) if (!(r is ParticleSystemRenderer) && r.bounds.size.magnitude < 400f) box.Encapsulate(r.bounds);
            log($"  drawn box {box.min:F0}..{box.max:F0}");
            var cam = Camera.main;
            float eye = center.y + 0.7f;
            var views = new List<(string name, Vector3 pos, float yaw, float pitch)>();
            if (cam != null) views.Add(("player", cam.transform.position, cam.transform.eulerAngles.y, cam.transform.eulerAngles.x));
            // in the hall itself (the scout starts in an elevator at its end): the middle of the airport's main mesh
            var hall = GameObject.Find("Map/BL_Airport/Main Meshes/Airport")?.GetComponent<Collider>();
            var mid = hall != null ? new Vector3(hall.bounds.center.x, eye, hall.bounds.center.z) : new Vector3(center.x, eye, center.z + 40f);
            views.Add(("out_of_elevator", new Vector3(center.x, eye, center.z + 4f), 0f, 0f));
            for (int i = 0; i < 4; i++) views.Add(($"hall_{i * 90}", mid, i * 90f, 0f));
            views.Add(("hall_roof", mid, 0f, -60f));
            views.Add(("hall_back", new Vector3(mid.x, eye + 6f, mid.z + 35f), 180f, 15f));
            views.Add(("high_corner", new Vector3(box.min.x + 4f, box.max.y - 2f, box.min.z + 4f), 45f, 30f));
            views.Add(("high_corner2", new Vector3(box.max.x - 4f, box.max.y - 2f, box.max.z - 4f), 225f, 30f));
            views.Add(("above", new Vector3(box.center.x, box.max.y + 60f, box.min.z - 40f), 0f, 45f));
            var list = new JArray();
            foreach (var v in views)
            {
                string ok = Shoot(v.name, v.pos, v.yaw, v.pitch);
                log($"  picture {v.name}: {ok}");
                list.Add(new JObject { ["name"] = v.name, ["x"] = v.pos.x, ["y"] = v.pos.y, ["z"] = v.pos.z, ["yaw"] = v.yaw, ["pitch"] = v.pitch, ["fov"] = 70 });
                yield return null;
            }
            File.WriteAllText(Path.Combine(Dir, "views.json"), list.ToString());

            // what's drawn, and what's solid, right in front of the scout (the elevator they start in)
            var ahead = new Bounds(center + (cam != null ? cam.transform.forward : Vector3.forward) * 1.2f + Vector3.up * 0.6f, new Vector3(3f, 2.5f, 1.5f));
            foreach (var r in rs.Where(r => r.bounds.Intersects(ahead)))
                log($"  ahead, drawn: {TPath(r.transform)} [{LayerMask.LayerToName(r.gameObject.layer)}] {r.GetType().Name} {r.sharedMaterial?.name} size {r.bounds.size:F1} at {r.bounds.center:F1}");
            foreach (var c in Physics.OverlapBox(ahead.center, ahead.extents, Quaternion.identity, ~0, QueryTriggerInteraction.Ignore))
                log($"  ahead, solid: {TPath(c.transform)} [{LayerMask.LayerToName(c.gameObject.layer)}] {c.GetType().Name} size {c.bounds.size:F1} at {c.bounds.center:F1}");

            // -minepeak-survey: the airport measured, to build it by hand
            if (Runner.Has("-minepeak-survey")) { yield return Survey.Run(ch, log); yield break; }
            if (Runner.Has("-minepeak-groups")) { yield return Survey.Groups(ch, log); yield break; }

            // which cameras draw PEAK's plants: one palm from above, orthographic and in perspective from near and far
            var palm = rs.FirstOrDefault(r => r.sharedMaterial != null && r.sharedMaterial.name.Contains("Palmtree 5"));
            if (palm != null)
            {
                var p = palm.bounds.center;
                log($"  palm '{TPath(palm.transform)}' at {p:F1}, size {palm.bounds.size:F1}");
                // the slicer's settings one at a time, starting from a camera that draws the palm
                for (int step = 0; step <= 5; step++)
                    log($"    step {step}: {ShootTest("palm_step" + step, p + Vector3.up * 6f, true, 8f, step, p.y)}");
            }

            // -minepeak-slice: the airport from what's drawn, into today's island (in place of the one from colliders)
            if (!Runner.Has("-minepeak-slice")) yield break;
            if (!Slicer.Possible(log)) yield break;
            Slicer slicer = null; Dictionary<long, string> blocks = null;
            yield return Slicer.Airport(center, log, (sl, bl) => { slicer = sl; blocks = bl; });
            if (slicer == null) yield break;
            slicer.TopView(Path.Combine(Dir, "slice_top_lit.png"), v => v.NSolid > 0 ? (Color?)new Color(v.Solid.x / v.NSolid, v.Solid.y / v.NSolid, v.Solid.z / v.NSolid) : null);
            slicer.TopView(Path.Combine(Dir, "slice_top_glow.png"), v => v.NGlow > 0 ? (Color?)new Color(v.Glow.x / v.NGlow, v.Glow.y / v.NGlow, v.Glow.z / v.NGlow) : null);
            WriteAirport(slicer, blocks, log);
        }

        /// <summary>Puts the sliced airport into the newest island export (the old airport.bin kept as airport.colliders.bin).</summary>
        static void WriteAirport(Slicer slicer, Dictionary<long, string> blocks, Action<string> log)
        {
            var dir = new DirectoryInfo(MinePeakExport.Root).GetDirectories().Where(d => File.Exists(Path.Combine(d.FullName, "island.json")) && !d.Name.StartsWith("exporting_"))
                .OrderByDescending(d => d.LastWriteTimeUtc).FirstOrDefault();
            if (dir == null) { log("  no island export to put it in"); return; }
            string json = Path.Combine(dir.FullName, "island.json"), bin = Path.Combine(dir.FullName, "airport.bin"), old = Path.Combine(dir.FullName, "airport.colliders.bin");
            var info = JObject.Parse(File.ReadAllText(json));
            var palette = (JArray)info["palette"];
            var index = new Dictionary<string, int>();
            for (int i = 0; i < palette.Count; i++) index[(string)palette[i]] = i;
            int P(string b) { if (!index.TryGetValue(b, out int i)) { i = palette.Count; palette.Add(b); index[b] = i; } return i; }
            var a = slicer.ToArea("airport", blocks, P);
            if (!File.Exists(old) && File.Exists(bin)) File.Copy(bin, old);
            MinePeakExport.Write(a, dir.FullName);
            var ap = (JObject)info["airport"];
            ap["x0"] = a.X0; ap["z0"] = a.Z0; ap["width"] = a.W; ap["depth"] = a.D;
            ap["from"] = "drawn";
            File.WriteAllText(json, info.ToString());
            log($"  the sliced airport is in {dir.Name}: {a.W} x {a.D} columns from ({a.X0}, {a.Z0})");
        }

        static string TPath(Transform t) { var p = t.name; for (int i = 0; i < 4 && t.parent != null; i++) { t = t.parent; p = t.name + "/" + p; } return p; }

        static string Hex(Color c) => "#" + ColorUtility.ToHtmlStringRGB(c);

        /// <summary>A texture's average colour, read back from the GPU (textures in a build usually can't be read directly).</summary>
        static Color? Average(Texture t)
        {
            try
            {
                var rt = RenderTexture.GetTemporary(32, 32, 0, RenderTextureFormat.ARGB32, RenderTextureReadWrite.sRGB);
                Graphics.Blit(t, rt);
                var prev = RenderTexture.active;
                RenderTexture.active = rt;
                var tex = new Texture2D(32, 32, TextureFormat.RGBA32, false);
                tex.ReadPixels(new Rect(0, 0, 32, 32), 0, 0);
                tex.Apply();
                RenderTexture.active = prev;
                RenderTexture.ReleaseTemporary(rt);
                var px = tex.GetPixels();
                UnityEngine.Object.Destroy(tex);
                Color sum = Color.clear;
                foreach (var p in px) sum += p;
                return sum / px.Length;
            }
            catch (Exception) { return null; }
        }

        /// <summary>A 512x512 picture looking straight down (orthographic: size; perspective: fov), and how green it is.</summary>
        /// <remarks>step: 1 no HDR or MSAA (as the slicer); 2 also its LOD settings; 3 also a frustum one block thick round
        /// the height at; 4 also the game camera's layers less the scouts'; 5 also the clear colour white.</remarks>
        static string ShootTest(string name, Vector3 pos, bool ortho, float sizeOrFov, int step = 0, float at = 0f)
        {
            float lodBias = QualitySettings.lodBias; int maxLod = QualitySettings.maximumLODLevel;
            var go = new GameObject("MinePeakProbeTest");
            try
            {
                var cam = go.AddComponent<Camera>();
                cam.enabled = false;
                cam.orthographic = ortho;
                if (ortho) cam.orthographicSize = sizeOrFov; else cam.fieldOfView = sizeOrFov;
                cam.nearClipPlane = 0.3f;
                cam.farClipPlane = 3000f;
                cam.useOcclusionCulling = false;
                cam.clearFlags = CameraClearFlags.SolidColor;
                cam.backgroundColor = Color.black;
                cam.transform.SetPositionAndRotation(pos, Quaternion.Euler(90f, 0f, 0f));
                if (step >= 1) { cam.allowHDR = false; cam.allowMSAA = false; }
                if (step >= 2) { QualitySettings.lodBias = 1000f; QualitySettings.maximumLODLevel = 0; }
                if (step >= 3) { cam.transform.position = new Vector3(pos.x, at + 1f, pos.z); cam.nearClipPlane = 0.5f; cam.farClipPlane = 1.5f; }
                if (step >= 4) { cam.cullingMask = (Camera.main != null ? Camera.main.cullingMask : ~0) & ~(1 << 31) & ~(1 << LayerMask.NameToLayer("Character")); }
                if (step >= 5) cam.backgroundColor = Color.white;
                var rt = new RenderTexture(512, 512, 24, RenderTextureFormat.ARGB32);
                cam.targetTexture = rt;
                var req = new RenderPipeline.StandardRequest { destination = rt };
                if (RenderPipeline.SupportsRenderRequest(cam, req)) RenderPipeline.SubmitRenderRequest(cam, req); else cam.Render();
                var prev = RenderTexture.active;
                RenderTexture.active = rt;
                var tex = new Texture2D(512, 512, TextureFormat.RGB24, false);
                tex.ReadPixels(new Rect(0, 0, 512, 512), 0, 0);
                tex.Apply();
                RenderTexture.active = prev;
                int green = 0, drawn = 0;
                foreach (var c in tex.GetPixels32()) { if (c.r + c.g + c.b > 6 && c.r + c.g + c.b < 759) drawn++; if (c.g > c.r + 15 && c.g > c.b + 10) green++; }
                File.WriteAllBytes(Path.Combine(Dir, name + ".png"), tex.EncodeToPNG());
                UnityEngine.Object.Destroy(tex);
                cam.targetTexture = null;
                rt.Release();
                return $"{drawn} pixels drawn, {green} green";
            }
            catch (Exception e) { return "failed: " + e.Message; }
            finally { UnityEngine.Object.Destroy(go); QualitySettings.lodBias = lodBias; QualitySettings.maximumLODLevel = maxLod; }
        }

        /// <summary>A 1280x720 picture from a camera of our own (70° high, like Minecraft's).</summary>
        static string Shoot(string name, Vector3 pos, float yaw, float pitch)
        {
            var go = new GameObject("MinePeakProbeCamera");
            try
            {
                var cam = go.AddComponent<Camera>();
                cam.enabled = false;
                cam.fieldOfView = 70f;
                cam.nearClipPlane = 0.05f;
                cam.farClipPlane = 3000f;
                cam.transform.SetPositionAndRotation(pos, Quaternion.Euler(pitch, yaw, 0f));
                var rt = new RenderTexture(1280, 720, 24, RenderTextureFormat.ARGB32);
                cam.targetTexture = rt;
                string how;
                var req = new RenderPipeline.StandardRequest { destination = rt };
                if (RenderPipeline.SupportsRenderRequest(cam, req)) { RenderPipeline.SubmitRenderRequest(cam, req); how = "render request"; }
                else { cam.Render(); how = "Camera.Render"; }
                var prev = RenderTexture.active;
                RenderTexture.active = rt;
                var tex = new Texture2D(1280, 720, TextureFormat.RGB24, false);
                tex.ReadPixels(new Rect(0, 0, 1280, 720), 0, 0);
                tex.Apply();
                RenderTexture.active = prev;
                var px = tex.GetPixels32();
                long sum = 0;
                for (int i = 0; i < px.Length; i += 97) sum += px[i].r + px[i].g + px[i].b;
                File.WriteAllBytes(Path.Combine(Dir, name + ".png"), tex.EncodeToPNG());
                UnityEngine.Object.Destroy(tex);
                cam.targetTexture = null;
                rt.Release();
                return $"{how}, brightness {sum / (px.Length / 97) / 3}";
            }
            catch (Exception e) { return "failed: " + e.Message; }
            finally { UnityEngine.Object.Destroy(go); }
        }
    }
}
