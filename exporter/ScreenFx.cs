using System;
using System.Collections;
using System.IO;
using System.Linq;
using System.Reflection;
using Newtonsoft.Json.Linq;
using UnityEngine;
using UnityEngine.Rendering;
using UnityEngine.Rendering.Universal;

namespace MinePeakExporter
{
    /// <summary>
    /// PEAK's statuses on the screen (GUIManager's ScreenVFX: the red of a knock, cold's frost, poison's, the spores',
    /// heat's, drowsiness's, the curse's...): each drawn as PEAK draws it at full strength, by a camera of its own that
    /// sees only it, once over black and once over white, so its colour and how much it covers come out as a picture
    /// (ui/screenfx/&lt;name&gt;.png). Also the post-processing PEAK fades in for some (its Volumes: the cold's, the blur's),
    /// as numbers (ui/screenfx/screenfx.json).
    /// </summary>
    internal static class ScreenFx
    {
        static string Dir => Path.Combine(Path.GetDirectoryName(MinePeakExport.Root), "ui", "screenfx");

        const int W = 768, H = 432, Layer = 31;

        public static IEnumerator Take(Action<string> log)
        {
            var gui = GUIManager.instance;
            if (gui == null) { log("screen fx: no GUIManager"); yield break; }
            Directory.CreateDirectory(Dir);
            var index = new JObject();
            var flags = BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic;
            foreach (var f in typeof(GUIManager).GetFields(flags))
            {
                object v;
                try { v = f.GetValue(gui); } catch (Exception) { continue; }
                if (v is ScreenVFX fx && fx != null && fx.renderer != null)
                {
                    JObject j = null;
                    try { j = Shoot(f.Name, fx, log); }
                    catch (Exception e) { log($"screen fx {f.Name}: {e.Message}"); }
                    if (j != null) index[f.Name] = j;
                    yield return null;
                }
                else if (v is Volume vol && vol != null)
                {
                    try { index[f.Name] = VolumeOf(f.Name, vol); }
                    catch (Exception e) { log($"screen fx volume {f.Name}: {e.Message}"); }
                }
            }
            File.WriteAllText(Path.Combine(Dir, "screenfx.json"), new JObject { ["fx"] = index }.ToString());
            log($"screen fx: {index.Count} of PEAK's screen effects into {Dir}");
        }

        /// <summary>One ScreenVFX at full strength, over black and over white, into name.png (colour and cover).</summary>
        static JObject Shoot(string name, ScreenVFX fx, Action<string> log)
        {
            var r = fx.renderer;
            var cam = CameraFor(r);
            if (cam == null) { log($"screen fx {name}: no camera sees it"); return null; }
            var go = fx.gameObject;
            bool wasActive = go.activeSelf;
            int oldLayer = r.gameObject.layer;
            var mat = r.material;
            int intensity = Shader.PropertyToID("_Intensity");
            float oldIntensity = mat.HasProperty(intensity) ? mat.GetFloat(intensity) : 0f;
            var camGo = new GameObject("MinePeak screen fx camera");
            try
            {
                go.SetActive(true);
                r.enabled = true;
                r.gameObject.layer = Layer;
                if (mat.HasProperty(intensity)) mat.SetFloat(intensity, 1f);
                var c = camGo.AddComponent<Camera>();
                c.CopyFrom(cam);
                c.enabled = false;
                c.cullingMask = 1 << Layer;
                c.clearFlags = CameraClearFlags.SolidColor;
                var extra = camGo.AddComponent<UniversalAdditionalCameraData>();
                extra.renderPostProcessing = false;
                extra.renderType = CameraRenderType.Base;
                camGo.transform.SetPositionAndRotation(cam.transform.position, cam.transform.rotation);
                var black = Render(c, Color.clear);
                var white = Render(c, Color.white);
                var o = new Texture2D(W, H, TextureFormat.RGBA32, false);
                var px = new Color32[W * H];
                float cover = 0;
                for (int i = 0; i < px.Length; i++)
                {
                    Color b = black[i], w = white[i];
                    float a = Mathf.Clamp01(1f - ((w.r - b.r) + (w.g - b.g) + (w.b - b.b)) / 3f);
                    Color col = a > 0.002f ? new Color(Mathf.Clamp01(b.r / a), Mathf.Clamp01(b.g / a), Mathf.Clamp01(b.b / a), a) : Color.clear;
                    px[i] = col;
                    cover += a;
                }
                o.SetPixels32(px);
                o.Apply();
                File.WriteAllBytes(Path.Combine(Dir, Safe(name) + ".png"), o.EncodeToPNG());
                UnityEngine.Object.Destroy(o);
                var j = new JObject { ["file"] = Safe(name) + ".png", ["cover"] = Math.Round(cover / px.Length, 4), ["shader"] = mat.shader != null ? mat.shader.name : "" };
                j["duration"] = fx.duration; j["delay"] = fx.delay;
                var props = new JObject();
                var sh = mat.shader;
                if (sh != null)
                    for (int p = 0; p < sh.GetPropertyCount(); p++)
                    {
                        string pn = sh.GetPropertyName(p);
                        switch (sh.GetPropertyType(p))
                        {
                            case ShaderPropertyType.Color: props[pn] = "#" + ColorUtility.ToHtmlStringRGBA(mat.GetColor(pn)); break;
                            case ShaderPropertyType.Float:
                            case ShaderPropertyType.Range: props[pn] = Math.Round(mat.GetFloat(pn), 4); break;
                            case ShaderPropertyType.Texture:
                                var t = mat.GetTexture(pn);
                                if (t != null && t is Texture2D)
                                {
                                    string file = Safe(name) + "_" + Safe(pn) + ".png";
                                    if (MenuArt.SavePicture(t, new Rect(0, 0, t.width, t.height), Path.Combine(Dir, file))) props[pn] = file;
                                }
                                break;
                        }
                    }
                j["material"] = props;
                return j;
            }
            finally
            {
                if (mat.HasProperty(intensity)) mat.SetFloat(intensity, oldIntensity);
                r.gameObject.layer = oldLayer;
                go.SetActive(wasActive);
                UnityEngine.Object.Destroy(camGo);
            }
        }

        /// <summary>The camera that draws r: one it hangs under, or the frontmost that sees its layer.</summary>
        static Camera CameraFor(Renderer r)
        {
            var under = r.GetComponentInParent<Camera>(true);
            if (under != null) return under;
            int layer = r.gameObject.layer;
            var all = UnityEngine.Object.FindObjectsByType<Camera>(FindObjectsInactive.Include, FindObjectsSortMode.None)
                .Where(c => c != null && c.targetTexture == null && (c.cullingMask & (1 << layer)) != 0).OrderByDescending(c => c.depth).ToList();
            if (all.Count > 0) return all[0];
            return MainCamera.instance != null ? MainCamera.instance.cam : Camera.main;
        }

        static Color[] Render(Camera c, Color background)
        {
            c.backgroundColor = background;
            var rt = new RenderTexture(W, H, 24, RenderTextureFormat.ARGB32);
            c.targetTexture = rt;
            var req = new RenderPipeline.StandardRequest { destination = rt };
            if (RenderPipeline.SupportsRenderRequest(c, req)) RenderPipeline.SubmitRenderRequest(c, req); else c.Render();
            c.targetTexture = null;
            var prev = RenderTexture.active;
            RenderTexture.active = rt;
            var tex = new Texture2D(W, H, TextureFormat.RGBA32, false);
            tex.ReadPixels(new Rect(0, 0, W, H), 0, 0);
            tex.Apply();
            RenderTexture.active = prev;
            var px = tex.GetPixels();
            UnityEngine.Object.Destroy(tex);
            rt.Release();
            return px;
        }

        /// <summary>A Volume's post-processing as numbers: each of its effects' settings that it overrides.</summary>
        static JObject VolumeOf(string name, Volume vol)
        {
            var j = new JObject { ["volume"] = true, ["weight"] = Math.Round(vol.weight, 3) };
            var prof = vol.sharedProfile;
            if (prof == null) return j;
            var comps = new JObject();
            foreach (var comp in prof.components)
            {
                if (comp == null) continue;
                var c = new JObject { ["active"] = comp.active };
                foreach (var f in comp.GetType().GetFields(BindingFlags.Instance | BindingFlags.Public))
                {
                    if (!(f.GetValue(comp) is VolumeParameter p) || !p.overrideState) continue;
                    object value = p.GetType().GetProperty("value")?.GetValue(p);
                    if (value is Texture t && t != null)
                    {
                        string file = Safe(name) + "_" + Safe(f.Name) + ".png";
                        if (MenuArt.SavePicture(t, new Rect(0, 0, t.width, t.height), Path.Combine(Dir, file))) c[f.Name] = file;
                    }
                    else
                    {
                        var tok = Reflect.Value(value, 1);
                        if (tok != null) c[f.Name] = tok;
                    }
                }
                comps[comp.GetType().Name] = c;
            }
            j["effects"] = comps;
            return j;
        }

        static string Safe(string s) => ItemArt.Safe(s);
    }
}
