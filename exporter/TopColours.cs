using System;
using System.Collections.Generic;
using UnityEngine;
using UnityEngine.Rendering;

namespace MinePeakExporter
{
    /// <summary>
    /// What colour PEAK draws the top of each column: the area photographed from straight above, 1 pixel a metre, with
    /// the plants (MinePeak builds those) and the scouts hidden, no shadows and no fog. Where it's green, the top is grass
    /// (PEAK's grassy summit, its meadows), and where it's purple, mycelium (the Ivy jungle's floor), whatever the
    /// material rules say.
    /// </summary>
    internal static class TopColours
    {
        static Color32[] _px;
        static readonly string[] FogKeys = { "USEFOG", "EXTRAFOG", "_WeatherBlend", "WindFogDensity", "WindFogTextureDensity" };
        static int _x0, _z0, _w, _d;

        /// <summary>Photographs the area (Minecraft x0, z0, width, depth; Unity heights lo..hi).</summary>
        /// <summary>Is there snow where PEAK's tops are snow-white here? Only on the Alpine (the Blue Beach is as pale).</summary>
        public static bool SnowHere;

        public static void Take(int x0, int z0, int w, int d, float lo, float hi, Action<string> log)
        {
            _px = null; _x0 = x0; _z0 = z0; _w = w; _d = d;
            const int Hidden = 31;
            var moved = new List<(GameObject go, int layer)>();
            var light = Slicer.Lighting.Save();
            var go = new GameObject("MinePeakTopColours");
            var fog = new Dictionary<string, float>();
            try
            {
                foreach (var r in UnityEngine.Object.FindObjectsByType<Renderer>(FindObjectsSortMode.None))
                {
                    if (r == null || r.gameObject.layer == Hidden) continue;
                    string mat = r.sharedMaterial != null ? r.sharedMaterial.name : "";
                    if (r is ParticleSystemRenderer || r.GetComponentInParent<Character>() != null || r.GetComponentInParent<Item>() != null
                        || Plants.KindOf(r.transform, r) != null || mat.IndexOf("water", StringComparison.OrdinalIgnoreCase) >= 0)   // (the swamp's violet water read as purple ground)
                    { moved.Add((r.gameObject, r.gameObject.layer)); r.gameObject.layer = Hidden; }
                }
                light.Apply(Slicer.Lighting.Mode.NoShadows);
                // and no fog: PEAK's shaders read these (the snowy beach's photo was all fog)
                foreach (var k in FogKeys) { fog[k] = Shader.GetGlobalFloat(k); Shader.SetGlobalFloat(k, 0f); }
                var cam = go.AddComponent<Camera>();
                cam.enabled = false;
                cam.orthographic = true;
                cam.orthographicSize = d / 2f;
                cam.aspect = (float)w / d;
                cam.clearFlags = CameraClearFlags.SolidColor;
                cam.backgroundColor = new Color(1f, 0f, 1f, 0f);
                cam.useOcclusionCulling = false;
                cam.nearClipPlane = 0.1f; cam.farClipPlane = hi - lo + 20f;
                var main = Camera.main;
                cam.cullingMask = (main != null ? main.cullingMask : ~0) & ~(1 << Hidden);
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
                        data.renderShadows = false;
                        data.renderPostProcessing = false;
                    }
                }
                catch (Exception) { }
                // centre of the area in Unity coordinates (Minecraft z = -Unity z), looking down with Unity +z up the picture
                float cx = x0 + w / 2f, cz = -(z0 + d / 2f);
                cam.transform.SetPositionAndRotation(new Vector3(cx, hi + 10f, cz), Quaternion.LookRotation(Vector3.down, Vector3.forward));
                int pw = Mathf.Min(w, 4096), ph = Mathf.Min(d, 4096);
                var rt = new RenderTexture(pw, ph, 24, RenderTextureFormat.ARGB32);
                cam.targetTexture = rt;
                var req = new RenderPipeline.StandardRequest { destination = rt };
                if (RenderPipeline.SupportsRenderRequest(cam, req)) RenderPipeline.SubmitRenderRequest(cam, req); else cam.Render();
                var prev = RenderTexture.active;
                RenderTexture.active = rt;
                var tex = new Texture2D(pw, ph, TextureFormat.RGBA32, false);
                tex.ReadPixels(new Rect(0, 0, pw, ph), 0, 0);
                tex.Apply();
                RenderTexture.active = prev;
                // texture rows go up the picture (Unity +z, Minecraft -z); pixels left to right are Minecraft +x
                var raw = tex.GetPixels32();
                _px = new Color32[w * d];
                for (int iz = 0; iz < d; iz++)
                    for (int ix = 0; ix < w; ix++)
                    {
                        int px = ix * pw / w, py = (d - 1 - iz) * ph / d;
                        _px[iz * w + ix] = raw[py * pw + px];
                    }
                UnityEngine.Object.Destroy(tex);
                cam.targetTexture = null;
                rt.Release();
                int green = 0, snow = 0;
                foreach (var c in _px) { if (c.r > 250 && c.b > 250 && c.g < 5) continue; if (Snow(c)) snow++; else if (Green(c)) green++; }
                log($"  top colours: {w}x{d}, {green} green columns, {snow} snowy");
            }
            catch (Exception e) { log("  top colours failed: " + e.Message); _px = null; }
            finally
            {
                foreach (var kv in fog) Shader.SetGlobalFloat(kv.Key, kv.Value);
                light.Restore();
                UnityEngine.Object.Destroy(go);
                foreach (var (g, layer) in moved) if (g != null) g.layer = layer;
            }
        }

        public static void Clear() => _px = null;

        /// <summary>The top block PEAK's colour at Minecraft (x, z) asks for: grass where it's green, mycelium where it's
        /// purple (the Ivy jungle's floor), or null (the material rules decide).</summary>
        public static string TopAt(int x, int z)
        {
            if (_px == null) return null;
            int ix = x - _x0, iz = z - _z0;
            if (ix < 0 || iz < 0 || ix >= _w || iz >= _d) return null;
            var c = _px[iz * _w + ix];
            if (c.r > 250 && c.b > 250 && c.g < 5) return null;
            if (SnowHere && Snow(c)) return "snow_block";
            if (Green(c)) return "grass_block";
            Color.RGBToHSV(c, out float h, out float s, out float v);
            return h * 360f >= 250f && h * 360f <= 335f && s >= 0.12f && v >= 0.15f ? "mycelium" : null;
        }

        /// <summary>Snow: very light, and grey or a little cyan (PEAK lights it so); not sand, which is yellower.</summary>
        static bool Snow(Color32 c)
        {
            Color.RGBToHSV(c, out float h, out float s, out float v);
            float deg = h * 360f;
            return s < 0.12f && v >= 0.62f || deg >= 140f && deg <= 220f && s < 0.32f && v >= 0.45f;
        }

        static bool Green(Color32 c)
        {
            if (c.r > 250 && c.b > 250 && c.g < 5) return false;   // (nothing there)
            Color.RGBToHSV(c, out float h, out float s, out float v);
            return h * 360f >= 80f && h * 360f <= 170f && s >= 0.25f && v >= 0.15f;   // (not the Mesa's sunlit yellow, nor the pale sky)
        }
    }
}
