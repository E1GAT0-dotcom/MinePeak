using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using UnityEngine;
using UnityEngine.Rendering;

namespace MinePeakExporter
{
    /// <summary>
    /// Builds blocks from what PEAK draws (not its colliders): a camera of our own photographs a box of the scene in
    /// slices a block thick, from all six sides. Each slice shows what's in that layer and its colour, so whatever is
    /// drawn becomes blocks (roofs, glass, palm fronds, seats, signs), however its meshes are stored.
    /// Three passes:
    /// - as the game lights it (no shadows or fog), each slice twice, on black and on white: what's the same on both is
    ///   solid, what changes is see-through (glass), what is just the background is empty;
    /// - dark (no light at all): only what glows still shows (lamps, screens): those become blocks that give light.
    /// </summary>
    internal sealed class Slicer
    {
        public struct Voxel
        {
            public Vector3 Solid, Glass, Flat, Glow;
            public float NSolid, NGlass, NFlat, NGlow, GlassAlpha;
        }

        enum Pass { Game, Flat, Dark }

        public readonly Dictionary<long, Voxel> Voxels = new Dictionary<long, Voxel>();
        /// <summary>The solid colours as seen from each of the six sides (-x, +x, -y, +y, -z, +z): sum and count.</summary>
        readonly Dictionary<long, (Vector3 sum, float n)>[] _sides = Enumerable.Range(0, 6).Select(_ => new Dictionary<long, (Vector3, float)>()).ToArray();
        /// <summary>How much brighter each side's colours were made, to match what's seen from above.</summary>
        public readonly float[] SideGain = new float[6];
        public int X0, Y0, Z0, X1, Y1, Z1;   // the box in whole blocks (Unity coordinates), inclusive
        public int Shots;
        /// <summary>How see-through what was seen was, in tenths (for tuning).</summary>
        public readonly int[] Glassiness = new int[10];
        /// <summary>Whether the flat-light pass gave colours (PEAK's shaders took the ambient light).</summary>
        public bool FlatWorks;
        // samples a block, each way: every 25 cm for what's there (PEAK's palm leaves are thin strips, missed at 50 cm),
        // every 50 cm for what glows (only what's already there gets a glow)
        static int Samples(Pass pass) => pass == Pass.Game ? 4 : 2;

        public static long Key(int x, int y, int z) => ((long)(x + 0x8000) << 40) | ((long)(y + 0x8000) << 20) | (long)(z + 0x8000);
        public static void Unkey(long k, out int x, out int y, out int z) { x = (int)(k >> 40) - 0x8000; y = (int)((k >> 20) & 0xFFFFF) - 0x8000; z = (int)(k & 0xFFFFF) - 0x8000; }

        /// <summary>Can it be done here: a graphics device (not -nographics), and Minecraft's block colours measured?</summary>
        public static bool Possible(Action<string> log)
        {
            if (SystemInfo.graphicsDeviceType == GraphicsDeviceType.Null) { log("slicer: no graphics device (-nographics): blocks from colliders instead"); return false; }
            return BlockColours.Load(log);
        }

        /// <summary>
        /// The airport, from what's drawn within 200 m of the scout (from 30 m under them up). done gets the slicer and its
        /// blocks, or nothing when there was nothing to photograph.
        /// </summary>
        public static IEnumerator Airport(Vector3 center, Action<string> log, Action<Slicer, Dictionary<long, string>> done)
        {
            var rs = UnityEngine.Object.FindObjectsByType<Renderer>(FindObjectsSortMode.None)
                .Where(r => r != null && r.enabled && r.gameObject.activeInHierarchy && Vector3.Distance(r.bounds.center, center) < 200f).ToList();
            Bounds box = new Bounds(center, Vector3.one);
            foreach (var r in rs) if (!(r is ParticleSystemRenderer) && r.bounds.size.magnitude < 400f) box.Encapsulate(r.bounds);
            box.min = new Vector3(box.min.x, Mathf.Max(box.min.y, center.y - 30f), box.min.z);
            // not to be blocks: smoke, characters, what they hold (items aren't under the scout), anything small right at the scout
            var hide = rs.Where(r => r is ParticleSystemRenderer || r is SkinnedMeshRenderer || r.GetComponentInParent<Character>() != null
                || r.shadowCastingMode == ShadowCastingMode.ShadowsOnly || Helper(r.transform)
                || r.GetComponentInParent<Item>() != null || (r.bounds.size.magnitude < 3f && Vector3.Distance(r.bounds.center, center) < 2.5f)).ToList();
            foreach (var r in rs.Where(r => !(r is ParticleSystemRenderer)).OrderByDescending(r => r.bounds.size.magnitude).Take(12))
                log($"  big: {r.name} [{LayerMask.LayerToName(r.gameObject.layer)}] {r.sharedMaterial?.name} {r.shadowCastingMode} size {r.bounds.size:F0}{(hide.Contains(r) ? " (left out)" : "")}");
            // the plants (PEAK's foliage shader): how many, on which layers, drawn by the game's camera?
            var plants = rs.Where(r => r.sharedMaterial != null && r.sharedMaterial.shader.name.Contains("Foliage")).ToList();
            int mask = Camera.main != null ? Camera.main.cullingMask : ~0;
            log($"  plants: {plants.Count}, " + string.Join(", ", plants.GroupBy(r => LayerMask.LayerToName(r.gameObject.layer)).Select(g => $"{g.Key} {g.Count()}"))
                + $"; in the game camera's layers {plants.Count(r => (mask & (1 << r.gameObject.layer)) != 0)}, hidden by us {plants.Count(r => hide.Contains(r))}, "
                + $"of a type {string.Join("/", plants.Select(r => r.GetType().Name).Distinct())}" + (plants.Count > 0 ? $", e.g. {plants[0].name} at {plants[0].bounds.center:F0}" : ""));
            var slicer = new Slicer();
            float t = Time.realtimeSinceStartup;
            yield return slicer.Capture(box, hide, log);
            log($"  sliced: {slicer.Voxels.Count} blocks from {slicer.Shots} pictures in {Time.realtimeSinceStartup - t:F0} s, box {box.min:F0}..{box.max:F0}");
            if (slicer.Voxels.Count == 0) { done(null, null); yield break; }
            // something solid there in PEAK (a collider, not a scout's or what they hold)
            bool SolidThere(Vector3 p) => Physics.OverlapBox(p, new Vector3(0.45f, 0.45f, 0.45f), Quaternion.identity, ~0, QueryTriggerInteraction.Ignore)
                .Any(c => c.GetComponentInParent<Character>() == null && c.GetComponentInParent<Item>() == null);
            var blocks = slicer.Blocks(SolidThere, Camera.main != null ? Camera.main.transform.position : center + Vector3.up * 0.7f);
            log($"  {slicer.Lights} light blocks under the roofs, {slicer.Panes_} glass panes, {slicer.Barriers} invisible walls (faint glass), {slicer.GlassDropped} faint see-through ones left out; blocks: " + string.Join(", ", blocks.Values.GroupBy(b => b).OrderByDescending(g => g.Count()).Take(25)
                .Select(g => $"{g.Key.Replace("minecraft:", "")} {g.Count()}")));
            done(slicer, blocks);
        }

        /// <summary>Part of PEAK's light baking (light blockers, shadow boxes), not of what's seen?</summary>
        static bool Helper(Transform t)
        {
            for (; t != null; t = t.parent)
            {
                string n = t.name.ToLowerInvariant();
                if (n.Contains("bakedata") || n.Contains("lightblocker") || n.Contains("shadowblock")) return true;
            }
            return false;
        }

        /// <summary>Photographs the box. hide: things not to photograph (the scout, smoke...), hidden meanwhile.</summary>
        public IEnumerator Capture(Bounds box, IList<Renderer> hide, Action<string> log)
        {
            X0 = Mathf.FloorToInt(box.min.x); Y0 = Mathf.FloorToInt(box.min.y); Z0 = Mathf.FloorToInt(box.min.z);
            X1 = Mathf.FloorToInt(box.max.x); Y1 = Mathf.FloorToInt(box.max.y); Z1 = Mathf.FloorToInt(box.max.z);
            var saved = Lighting.Save();
            float lodBias = QualitySettings.lodBias; int maxLod = QualitySettings.maximumLODLevel;
            QualitySettings.lodBias = 1000f; QualitySettings.maximumLODLevel = 0;   // (seen from far away, PEAK would draw its simplest versions)
            // what's not to be photographed goes on a layer the camera doesn't draw (hiding the renderers isn't enough:
            // PEAK shows a held item again every frame)
            const int Hidden = 31;
            var layers = new List<(GameObject go, int layer)>();
            foreach (var r in hide) if (r != null && r.gameObject.layer != Hidden) { layers.Add((r.gameObject, r.gameObject.layer)); r.gameObject.layer = Hidden; }
            _hide = hide;
            var go = new GameObject("MinePeakSlicer");
            var cam = go.AddComponent<Camera>();
            cam.enabled = false;
            cam.orthographic = true;
            cam.clearFlags = CameraClearFlags.SolidColor;
            cam.allowMSAA = false;
            cam.allowHDR = false;
            cam.useOcclusionCulling = false;   // (PEAK's baked occlusion data is for its own camera, not one inside its walls)
            // the game camera's renderer (its own passes: PEAK's plants aren't drawn by the default one)
            try
            {
                var main = Camera.main != null ? Camera.main.GetComponent<UnityEngine.Rendering.Universal.UniversalAdditionalCameraData>() : null;
                if (main != null)
                {
                    int index = (int)HarmonyLib.AccessTools.Field(typeof(UnityEngine.Rendering.Universal.UniversalAdditionalCameraData), "m_RendererIndex").GetValue(main);
                    var data = UnityEngine.Rendering.Universal.CameraExtensions.GetUniversalAdditionalCameraData(cam);
                    data.SetRenderer(index);
                    data.renderShadows = false;
                    data.renderPostProcessing = false;
                    data.requiresDepthOption = main.requiresDepthOption;
                    data.requiresColorOption = main.requiresColorOption;
                    log($"  slicer: drawing with the game camera's renderer ({index})");
                }
            }
            catch (Exception e) { log("  slicer: couldn't take the game camera's renderer: " + e.Message); }
            cam.cullingMask = (Camera.main != null ? Camera.main.cullingMask : ~0) & ~(1 << Hidden);   // (not the layers the game itself never shows)
            int character = LayerMask.NameToLayer("Character");
            if (character >= 0) cam.cullingMask &= ~(1 << character);   // (the scouts, and what they wear)
            float t0 = Time.realtimeSinceStartup;
            try
            {
                // (a flat-light pass, for colours without shading, didn't work: PEAK's shaders barely take ambient light)
                foreach (var pass in new[] { Pass.Game, Pass.Dark })
                {
                    saved.Apply(pass == Pass.Game ? Lighting.Mode.NoShadows : pass == Pass.Flat ? Lighting.Mode.Flat : Lighting.Mode.Dark);
                    for (int axis = 0; axis < 3; axis++)
                        foreach (int sign in new[] { -1, 1 })
                        {
                            var it = Direction(cam, axis, sign, pass);
                            while (true)
                            {
                                bool more;
                                try { more = it.MoveNext(); }
                                catch (Exception e) { log($"  slicer: {pass} {"xyz"[axis]}{(sign < 0 ? "-" : "+")} failed: {e.Message}"); break; }
                                if (!more) break;
                                yield return null;
                            }
                        }
                    if (pass == Pass.Game) { TurnThinSurfaces(log); EvenOutSides(log); }
                    log($"  slicer: {pass} pass done, {Voxels.Count} blocks ({Time.realtimeSinceStartup - t0:F0} s)");
                }
            }
            finally
            {
                UnityEngine.Object.Destroy(go);
                foreach (var (g, layer) in layers) if (g != null) g.layer = layer;
                foreach (var r in hide) if (r != null) r.enabled = true;
                _hide = Array.Empty<Renderer>();
                saved.Restore();
                QualitySettings.lodBias = lodBias; QualitySettings.maximumLODLevel = maxLod;
            }
            log("  slicer: how see-through, in tenths: " + string.Join(" ", Glassiness));
        }

        /// <summary>
        /// A face toward +axis belongs to the block under it, one toward -axis to the block over it: right for anything
        /// thick, but a thin surface (PEAK's ground, a roof, a pane) then gets its top's colour in the lower block and its
        /// underside's in the upper one, inside out (the ground from above showed its underside). A block seen only from
        /// the + side right under one seen only from the - side is such a surface: their colours swap.
        /// </summary>
        void TurnThinSurfaces(Action<string> log)
        {
            int turned = 0;
            for (int axis = 0; axis < 3; axis++)
            {
                var plus = _sides[axis * 2];       // (seen from the + side: faces toward +axis)
                var minus = _sides[axis * 2 + 1];
                long step = (axis == 0 ? Key(1, 0, 0) : axis == 1 ? Key(0, 1, 0) : Key(0, 0, 1)) - Key(0, 0, 0);
                var pairs = new List<long>();
                foreach (var k in plus.Keys)
                    if (minus.ContainsKey(k + step) && !minus.ContainsKey(k) && !plus.ContainsKey(k + step)) pairs.Add(k);
                foreach (var k in pairs)
                {
                    var p = plus[k]; var m = minus[k + step];
                    plus.Remove(k); minus.Remove(k + step);
                    plus[k + step] = p; minus[k] = m;
                }
                turned += pairs.Count;
            }
            log($"  slicer: {turned} thin surfaces turned the right way out");
        }

        /// <summary>
        /// The game's light makes the same white wall grey where the sun doesn't reach it: each side's colours are made
        /// brighter (up to 1.8 times) so the middle of their brightness matches what's seen from above (floors in the sun).
        /// </summary>
        void EvenOutSides(Action<string> log)
        {
            float Median(Dictionary<long, (Vector3 sum, float n)> d)
            {
                var l = d.Values.Take(200000).Select(v => { var c = v.sum / v.n; return 0.2126f * c.x + 0.7152f * c.y + 0.0722f * c.z; }).ToList();
                if (l.Count == 0) return 0f;
                l.Sort();
                return l[l.Count / 2];
            }
            float top = Median(_sides[2]);   // (looking down: -y)
            for (int d = 0; d < 6; d++) { float m = Median(_sides[d]); SideGain[d] = m > 0.02f ? Mathf.Clamp(top / m, 1f, 1.8f) : 1f; }
            var keys = Voxels.Keys.ToList();
            foreach (var k in keys)
            {
                var v = Voxels[k];
                if (v.NSolid <= 0f) continue;
                Vector3 sum = Vector3.zero; float n = 0f;
                for (int d = 0; d < 6; d++) if (_sides[d].TryGetValue(k, out var a)) { sum += a.sum * SideGain[d]; n += a.n; }
                if (n > 0f) { v.Solid = sum; v.NSolid = n; Voxels[k] = v; }
            }
            foreach (var d in _sides) d.Clear();
            log("  slicer: each side made brighter by " + string.Join(" ", SideGain.Select(g => g.ToString("F2"))));
        }

        /// <summary>
        /// One direction: looking along -axis (sign -1, the camera on the + side, seeing faces that face +axis) or +axis.
        /// A face that faces +axis at a whole number h belongs to block h-1 (the block under a floor); one facing -axis to
        /// block h (the block over a ceiling): so the slice for block j is [j+0.5, j+1.5) or [j-0.5, j+0.5).
        /// </summary>
        IEnumerator Direction(Camera cam, int axis, int sign, Pass pass)
        {
            int[] lo = { X0, Y0, Z0 }, hi = { X1, Y1, Z1 };
            int S = Samples(pass);
            int ua = axis == 1 ? 0 : axis == 0 ? 2 : 0;   // image right: x (or z when looking along x)
            int va = axis == 1 ? 2 : 1;                   // image up: z when looking along y, else y
            Vector3 fwd = Vector3.zero; fwd[axis] = sign;
            Vector3 up = Vector3.zero; up[va] = 1f;
            cam.transform.rotation = Quaternion.LookRotation(fwd, up);
            var right = cam.transform.right;
            bool flipU = right[ua] < 0f;   // (image right may run the other way along its axis)
            int nu = hi[ua] - lo[ua] + 1, nv = hi[va] - lo[va] + 1;
            int pw = nu * S, ph = nv * S;
            cam.orthographicSize = nv / 2f;
            cam.aspect = (float)nu / nv;
            float camA = sign < 0 ? hi[axis] + 3f : lo[axis] - 2f;
            Vector3 pos = Vector3.zero;
            pos[ua] = lo[ua] + nu / 2f; pos[va] = lo[va] + nv / 2f; pos[axis] = camA;
            cam.transform.position = pos;
            var rt = new RenderTexture(pw, ph, 24, RenderTextureFormat.ARGB32);
            var black = new Texture2D(pw, ph, TextureFormat.RGBA32, false);
            var white = pass == Pass.Game ? new Texture2D(pw, ph, TextureFormat.RGBA32, false) : null;
            cam.targetTexture = rt;
            var c = new int[3];
            try
            {
                for (int j = lo[axis]; j <= hi[axis]; j++)
                {
                    float a0 = sign < 0 ? j + 0.5f : j - 0.5f, a1 = a0 + 1f;
                    // the camera right at the slice: PEAK fades some things (its plants) with distance from the camera
                    camA = sign < 0 ? a1 + 0.5f : a0 - 0.5f;
                    pos[axis] = camA;
                    cam.transform.position = pos;
                    cam.nearClipPlane = 0.5f;
                    cam.farClipPlane = 1.5f;
                    Rehide();
                    Shoot(cam, rt, black, Color.clear);
                    if (white != null) Shoot(cam, rt, white, Color.white);
                    var pb = black.GetPixelData<Color32>(0);
                    var pwh = white != null ? white.GetPixelData<Color32>(0) : default;
                    for (int py = 0; py < ph; py++)
                        for (int px = 0; px < pw; px++)
                        {
                            int i = py * pw + px;
                            Color32 b = pb[i];
                            int u = lo[ua] + (flipU ? (pw - 1 - px) : px) / S, v = lo[va] + py / S;
                            c[axis] = j; c[ua] = u; c[va] = v;
                            long k = Key(c[0], c[1], c[2]);
                            Voxel vox;
                            if (pass == Pass.Game)
                            {
                                Color32 w = pwh[i];
                                if (b.r + b.g + b.b <= 6 && 765 - (w.r + w.g + w.b) <= 6) continue;   // just the background: empty
                                Voxels.TryGetValue(k, out vox);
                                // the same on black and on white: solid; otherwise see-through, its colour unblended
                                float through = ((w.r - b.r) + (w.g - b.g) + (w.b - b.b)) / 765f;
                                if (through > 0.88f) continue;   // all but invisible (PEAK's helper volumes): empty
                                Glassiness[Mathf.Clamp((int)(through * 10f), 0, 9)]++;
                                // (green and only a bit see-through: a plant's soft edge, solid)
                                bool leafy = false;
                                if (through >= 0.08f && through < 0.5f)
                                {
                                    var u0 = new Color(b.r / 255f / (1f - through), b.g / 255f / (1f - through), b.b / 255f / (1f - through));
                                    Color.RGBToHSV(u0, out float h0, out float s0, out float v0);
                                    leafy = h0 > 0.17f && h0 < 0.45f && s0 > 0.3f;
                                }
                                if (through < 0.08f || leafy)
                                {
                                    var col = new Vector3(b.r, b.g, b.b) / 255f / (1f - through);
                                    vox.Solid += col; vox.NSolid += 1f;
                                    var side = _sides[axis * 2 + (sign < 0 ? 0 : 1)];
                                    side.TryGetValue(k, out var acc);
                                    side[k] = (acc.Item1 + col, acc.Item2 + 1f);
                                }
                                else
                                {
                                    float alpha = Mathf.Clamp(1f - through, 0.05f, 1f);
                                    vox.Glass += new Vector3(b.r, b.g, b.b) / 255f / alpha; vox.NGlass += 1f; vox.GlassAlpha += alpha;
                                }
                            }
                            else
                            {
                                if (!Voxels.TryGetValue(k, out vox) || vox.NSolid <= 0f) continue;   // (only what the first pass found solid)
                                var col = new Vector3(b.r, b.g, b.b) / 255f;
                                if (pass == Pass.Flat) { vox.Flat += col; vox.NFlat += 1f; }
                                else { vox.Glow += col; vox.NGlow += 1f; }
                            }
                            Voxels[k] = vox;
                        }
                    if (((j - lo[axis]) & 3) == 3) yield return null;
                }
            }
            finally
            {
                cam.targetTexture = null;
                rt.Release();
                UnityEngine.Object.Destroy(black);
                if (white != null) UnityEngine.Object.Destroy(white);
            }
        }

        IList<Renderer> _hide = Array.Empty<Renderer>();

        /// <summary>Hides them again (PEAK puts a held item back on its layer and shows it again, every frame).</summary>
        void Rehide()
        {
            foreach (var r in _hide)
                if (r != null) { if (r.gameObject.layer != 31) r.gameObject.layer = 31; if (r.enabled) r.enabled = false; }
        }

        void Shoot(Camera cam, RenderTexture rt, Texture2D into, Color clear)
        {
            cam.backgroundColor = clear;
            var req = new RenderPipeline.StandardRequest { destination = rt };
            if (RenderPipeline.SupportsRenderRequest(cam, req)) RenderPipeline.SubmitRenderRequest(cam, req);
            else cam.Render();
            var prev = RenderTexture.active;
            RenderTexture.active = rt;
            into.ReadPixels(new Rect(0, 0, rt.width, rt.height), 0, 0, false);
            into.Apply(false);
            RenderTexture.active = prev;
            Shots++;
        }

        /// <summary>The scene's lighting, saved so the passes can change it and it can be put back.</summary>
        internal sealed class Lighting
        {
            public enum Mode { NoShadows, Flat, Dark }
            Light[] _lights; LightShadows[] _shadows; bool[] _on;
            Renderer[] _renderers; LightProbeUsage[] _probes;
            bool _fog; AmbientMode _mode; Color _ambient; float _intensity, _reflection; SphericalHarmonicsL2 _probe;

            public static Lighting Save()
            {
                var l = new Lighting();
                l._lights = UnityEngine.Object.FindObjectsByType<Light>(FindObjectsSortMode.None);
                l._shadows = l._lights.Select(x => x.shadows).ToArray();
                l._on = l._lights.Select(x => x.enabled).ToArray();
                l._renderers = UnityEngine.Object.FindObjectsByType<Renderer>(FindObjectsSortMode.None);
                l._probes = l._renderers.Select(r => r.lightProbeUsage).ToArray();
                l._fog = RenderSettings.fog; l._mode = RenderSettings.ambientMode; l._ambient = RenderSettings.ambientLight;
                l._intensity = RenderSettings.ambientIntensity; l._reflection = RenderSettings.reflectionIntensity; l._probe = RenderSettings.ambientProbe;
                return l;
            }

            public void Apply(Mode m)
            {
                Restore();
                RenderSettings.fog = false;
                foreach (var x in _lights) if (x != null) x.shadows = LightShadows.None;
                if (m == Mode.NoShadows) return;
                foreach (var x in _lights) if (x != null) x.enabled = false;
                // everything takes the one ambient light (not light probes baked for the game's lighting)
                foreach (var r in _renderers) if (r != null) r.lightProbeUsage = LightProbeUsage.Off;
                var c = m == Mode.Flat ? Color.white : Color.black;
                RenderSettings.ambientMode = AmbientMode.Flat;
                RenderSettings.ambientLight = c;
                RenderSettings.ambientIntensity = 1f;
                RenderSettings.reflectionIntensity = 0f;
                var sh = new SphericalHarmonicsL2();
                sh.AddAmbientLight(c);
                RenderSettings.ambientProbe = sh;
            }

            public void Restore()
            {
                for (int i = 0; i < _lights.Length; i++) if (_lights[i] != null) { _lights[i].shadows = _shadows[i]; _lights[i].enabled = _on[i]; }
                for (int i = 0; i < _renderers.Length; i++) if (_renderers[i] != null) _renderers[i].lightProbeUsage = _probes[i];
                RenderSettings.fog = _fog; RenderSettings.ambientMode = _mode; RenderSettings.ambientLight = _ambient;
                RenderSettings.ambientIntensity = _intensity; RenderSettings.reflectionIntensity = _reflection; RenderSettings.ambientProbe = _probe;
            }
        }

        /// <summary>The colour a block was seen in, whether it's see-through, and what it glows (null: nothing there).</summary>
        public (Color colour, bool glass, Color glow)? Seen(Voxel v)
        {
            if (v.NSolid >= v.NGlass && v.NSolid > 0f)
            {
                var c = FlatWorks && v.NFlat > 0f ? v.Flat / v.NFlat : v.Solid / v.NSolid;
                var g = v.NGlow > 0f ? v.Glow / v.NGlow : Vector3.zero;
                return (new Color(Mathf.Clamp01(c.x), Mathf.Clamp01(c.y), Mathf.Clamp01(c.z)), false, new Color(g.x, g.y, g.z));
            }
            if (v.NGlass > 0f) { var c = v.Glass / v.NGlass; return (new Color(Mathf.Clamp01(c.x), Mathf.Clamp01(c.y), Mathf.Clamp01(c.z)), true, Color.black); }
            return null;
        }

        /// <summary>
        /// The Minecraft block for one: what glows brightly in the dark gives light; then glass, leaves (greens: PEAK's
        /// plants), or the solid block nearest its colour.
        /// </summary>
        /// <summary>The block for a solid one, from its (smoothed) colour and what it glows.</summary>
        static string SolidBlock(Color c, Color glow)
        {
            // a little more colour: PEAK's own camera grades its colours, ours doesn't (its blue carpet came out mauve)
            Color.RGBToHSV(c, out float h0, out float s0, out float v0);
            if (s0 > 0.15f) c = Color.HSVToRGB(h0, Mathf.Min(1f, s0 * 1.3f), v0);
            if (Mathf.Max(glow.r, Mathf.Max(glow.g, glow.b)) > 0.55f) return BlockColours.Nearest(BlockColours.Kind.Glow, glow);
            Color.RGBToHSV(c, out float h, out float s, out float val);
            if (h > 0.17f && h < 0.45f && s > 0.35f && val > 0.12f) return BlockColours.Nearest(BlockColours.Kind.Leaves, c) + "[persistent=true]";
            return BlockColours.Nearest(BlockColours.Kind.Solid, c);
        }

        static readonly long[] Around = BuildAround();
        static long[] BuildAround()
        {
            var list = new List<long>();
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++)
                if (dx != 0 || dy != 0 || dz != 0) list.Add(Key(dx, dy, dz) - Key(0, 0, 0));
            return list.ToArray();
        }

        public const string LightBlock = "minecraft:light[level=15]";

        bool Opaque(long k) => Voxels.TryGetValue(k, out var v) && v.NSolid > 0f && v.NSolid >= v.NGlass;

        /// <summary>
        /// Every block's Minecraft block:
        /// - colours evened out first where they're nearly the same (PEAK's ground has noise that would otherwise make
        ///   blotches of two or three blocks), but not across edges (lines on the tarmac, stripes);
        /// - see-through things: faint ones become invisible walls (barriers) where PEAK has something solid
        ///   (solidThere), and nothing elsewhere (light shafts); clearly seen ones glass (clear) or stained glass (tinted);
        /// - nothing near the scout's eyes (clear) that isn't solid in PEAK: what the scout holds is drawn there;
        /// - lone specks evened out: a block whose neighbours mostly agree on another of the same sort becomes that one;
        /// - and under roofs, every 4 blocks, Minecraft's invisible light block, so inside is bright as in PEAK.
        /// </summary>
        public Dictionary<long, string> Blocks(Func<Vector3, bool> solidThere, Vector3? clear = null)
        {
            // colours of the solid ones, evened out twice among neighbours less than 8 apart (in Lab)
            var rgb = new Dictionary<long, Vector3>();
            var glows = new Dictionary<long, Color>();
            var raw = new Dictionary<long, string>();
            foreach (var kv in Voxels)
            {
                var seen = Seen(kv.Value);
                if (seen == null) continue;
                var (c, glass, glow) = seen.Value;
                if (clear.HasValue)
                {
                    Unkey(kv.Key, out int cx, out int cy, out int cz);
                    var centre = new Vector3(cx + 0.5f, cy + 0.5f, cz + 0.5f);
                    if ((centre - clear.Value).sqrMagnitude < 1.6f * 1.6f && !solidThere(centre)) continue;
                }
                if (glass)
                {
                    Unkey(kv.Key, out int x, out int y, out int z);
                    float alpha = kv.Value.GlassAlpha / kv.Value.NGlass;
                    // faint (PEAK's glass is all but invisible; Minecraft's draws a frame round every block): where it's
                    // solid in PEAK, an invisible wall (a barrier); where it isn't (light shafts), nothing
                    if (alpha < 0.5f)
                    {
                        if (solidThere(new Vector3(x + 0.5f, y + 0.5f, z + 0.5f))) { raw[kv.Key] = Barrier; Barriers++; }
                        else GlassDropped++;
                        continue;
                    }
                    raw[kv.Key] = alpha < 0.65f ? "minecraft:glass" : BlockColours.Nearest(BlockColours.Kind.Glass, c);
                    continue;
                }
                rgb[kv.Key] = new Vector3(c.r, c.g, c.b);
                glows[kv.Key] = glow;
            }
            rgb = Patches(rgb);
            foreach (var kv in rgb) raw[kv.Key] = SolidBlock(new Color(kv.Value.x, kv.Value.y, kv.Value.z), glows[kv.Key]);
            var result = Despeckle(raw);
            Panes(result);
            AddLights(result);
            return result;
        }

        /// <summary>
        /// Neighbours (sharing a face) whose colours are less than 8 apart (in Lab) make one patch, and every block of a
        /// patch takes the patch's average colour: a surface whose colour slowly drifts (PEAK's ground) becomes one block
        /// instead of blotches of two or three, while edges (lines on the tarmac, stripes) stay where they are.
        /// </summary>
        static Dictionary<long, Vector3> Patches(Dictionary<long, Vector3> rgb)
        {
            var keys = rgb.Keys.ToArray();
            var index = new Dictionary<long, int>(keys.Length);
            for (int i = 0; i < keys.Length; i++) index[keys[i]] = i;
            var lab = keys.Select(k => BlockColours.Lab(new Color(rgb[k].x, rgb[k].y, rgb[k].z))).ToArray();
            var parent = Enumerable.Range(0, keys.Length).ToArray();
            int Find(int i) { while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; } return i; }
            long[] faces = { Key(1, 0, 0) - Key(0, 0, 0), Key(0, 1, 0) - Key(0, 0, 0), Key(0, 0, 1) - Key(0, 0, 0) };
            for (int i = 0; i < keys.Length; i++)
                foreach (var f in faces)
                    if (index.TryGetValue(keys[i] + f, out int j) && (lab[i] - lab[j]).sqrMagnitude < 64f)
                    {
                        int a = Find(i), b = Find(j);
                        if (a != b) parent[a] = b;
                    }
            var sums = new Dictionary<int, (Vector3 sum, int n)>();
            for (int i = 0; i < keys.Length; i++)
            {
                int r = Find(i);
                sums.TryGetValue(r, out var s);
                sums[r] = (s.sum + rgb[keys[i]], s.n + 1);
            }
            var result = new Dictionary<long, Vector3>(keys.Length);
            for (int i = 0; i < keys.Length; i++) { var s = sums[Find(i)]; result[keys[i]] = s.sum / s.n; }
            return result;
        }

        /// <summary>
        /// Glass walls become panes, like PEAK's thin glass (a wall of glass blocks shows every block's frame): joined to
        /// the blocks beside them (Unity's +z is Minecraft's north). Flat glass (skylights: glass beside it both ways,
        /// none over or under) stays glass blocks.
        /// </summary>
        void Panes(Dictionary<long, string> blocks)
        {
            long dx = Key(1, 0, 0) - Key(0, 0, 0), dy = Key(0, 1, 0) - Key(0, 0, 0), dz = Key(0, 0, 1) - Key(0, 0, 0);
            bool Glass(long k) => blocks.TryGetValue(k, out var b) && b.Contains("glass");
            bool Joins(long k) => blocks.TryGetValue(k, out var b) && b != LightBlock;
            var change = new List<(long, string)>();
            foreach (var kv in blocks)
            {
                if (!kv.Value.Contains("glass") || kv.Value.Contains("pane")) continue;
                long k = kv.Key;
                bool flat = (Glass(k + dx) || Glass(k - dx)) && (Glass(k + dz) || Glass(k - dz)) && !Glass(k + dy) && !Glass(k - dy);
                if (flat) continue;
                string pane = kv.Value + "_pane[north=" + B(Joins(k + dz)) + ",south=" + B(Joins(k - dz)) + ",east=" + B(Joins(k + dx)) + ",west=" + B(Joins(k - dx)) + "]";
                change.Add((k, pane));
            }
            foreach (var (k, b) in change) blocks[k] = b;
            Panes_ = change.Count;
        }

        static string B(bool v) => v ? "true" : "false";
        public int Panes_;

        /// <summary>Minecraft's invisible light block under every roof, on a 4-block grid, just under the ceiling.</summary>
        void AddLights(Dictionary<long, string> blocks)
        {
            var columns = new Dictionary<(int, int), List<int>>();
            foreach (var k in blocks.Keys)
            {
                Unkey(k, out int x, out int y, out int z);
                if (((x % 4) + 4) % 4 != 0 || ((z % 4) + 4) % 4 != 0) continue;
                if (!columns.TryGetValue((x, z), out var ys)) columns[(x, z)] = ys = new List<int>();
                ys.Add(y);
            }
            int added = 0;
            foreach (var kv in columns)
            {
                var ys = kv.Value;
                ys.Sort((a, b) => b.CompareTo(a));
                for (int i = 0; i + 1 < ys.Count; i++)
                {
                    int ceiling = ys[i], floor = ys[i + 1];
                    if (ceiling - floor - 1 < 3) continue;   // (room for a scout under it)
                    blocks[Key(kv.Key.Item1, ceiling - 1, kv.Key.Item2)] = LightBlock;
                    added++;
                }
            }
            Lights = added;
        }

        public int Lights, GlassDropped, Barriers;
        public const string Barrier = "minecraft:barrier";

        Dictionary<long, string> Despeckle(Dictionary<long, string> raw)
        {
            string Sort(string b) => b == Barrier ? "glow" : b.Contains("glass") ? "glass" : b.Contains("leaves") ? "leaves" : Array.IndexOf(GlowIds, b) >= 0 ? "glow" : "solid";   // (barriers and light blocks are left as they are)
            var result = new Dictionary<long, string>(raw);
            var counts = new Dictionary<string, int>();
            foreach (var kv in raw)
            {
                string own = kv.Value, sort = Sort(own);
                if (sort == "glow") continue;
                Unkey(kv.Key, out int x, out int y, out int z);
                counts.Clear();
                int same = 0;
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++)
                {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    if (!raw.TryGetValue(Key(x + dx, y + dy, z + dz), out var nb)) continue;
                    if (nb == own) { same++; continue; }
                    if (Sort(nb) != sort) continue;
                    counts[nb] = counts.TryGetValue(nb, out int n) ? n + 1 : 1;
                }
                if (same > 1 || counts.Count == 0) continue;
                var best = counts.OrderByDescending(p => p.Value).First();
                if (best.Value >= 4) result[kv.Key] = best.Key;
            }
            return result;
        }

        static readonly string[] GlowIds = { "minecraft:sea_lantern", "minecraft:glowstone", "minecraft:shroomlight", "minecraft:ochre_froglight",
            "minecraft:verdant_froglight", "minecraft:pearlescent_froglight", "minecraft:redstone_lamp[lit=true]" };

        /// <summary>The blocks as an area's columns in Minecraft's coordinates (x, y + 63, -z - 1), runs from the top down.</summary>
        public MinePeakExport.Area ToArea(string name, Dictionary<long, string> blocks, Func<string, int> palette)
        {
            var a = new MinePeakExport.Area { Name = name, X0 = X0, W = X1 - X0 + 1, Z0 = -Z1 - 1, D = Z1 - Z0 + 1 };
            var cols = new List<(int y, int block)>[a.W * a.D];
            var ids = new Dictionary<string, int>();
            foreach (var kv in blocks)
            {
                if (!ids.TryGetValue(kv.Value, out int id)) ids[kv.Value] = id = palette(kv.Value);
                Unkey(kv.Key, out int x, out int y, out int z);
                int my = y + MinePeakExport.YOffset;
                if (my < MinePeakExport.MinY || my > MinePeakExport.MaxY) continue;
                int i = ((-z - 1) - a.Z0) * a.W + (x - a.X0);
                (cols[i] ?? (cols[i] = new List<(int, int)>())).Add((my, id));
            }
            a.Cols = new int[cols.Length][];
            for (int i = 0; i < cols.Length; i++)
            {
                var list = cols[i];
                if (list == null) { a.Cols[i] = Array.Empty<int>(); continue; }
                list.Sort((p, q) => q.y.CompareTo(p.y));
                var runs = new List<int>();
                foreach (var (y, b) in list)
                {
                    int n = runs.Count;
                    if (n >= 3 && runs[n - 1] == b && runs[n - 3] - runs[n - 2] == y) runs[n - 2]++;   // (right under the run, same block)
                    else { runs.Add(y); runs.Add(1); runs.Add(b); }
                }
                a.Cols[i] = runs.ToArray();
            }
            return a;
        }

        /// <summary>A picture from above of the top block of each column, in its colour (to look at before Minecraft).</summary>
        public void TopView(string file, Func<Voxel, Color?> colourOf)
        {
            int w = X1 - X0 + 1, d = Z1 - Z0 + 1;
            var top = new int[w * d];
            for (int i = 0; i < top.Length; i++) top[i] = int.MinValue;
            var col = new Color[w * d];
            foreach (var kv in Voxels)
            {
                Unkey(kv.Key, out int x, out int y, out int z);
                int i = (z - Z0) * w + (x - X0);
                if (i < 0 || i >= top.Length || y <= top[i]) continue;
                var c = colourOf(kv.Value);
                if (c == null) continue;
                top[i] = y; col[i] = c.Value;
            }
            var t = new Texture2D(w, d, TextureFormat.RGB24, false);
            t.SetPixels(col);
            t.Apply();
            File.WriteAllBytes(file, t.EncodeToPNG());
            UnityEngine.Object.Destroy(t);
        }
    }
}
