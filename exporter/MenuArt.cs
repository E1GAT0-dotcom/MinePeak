using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Newtonsoft.Json.Linq;
using UnityEngine;
using UnityEngine.Rendering;
using UnityEngine.UI;

namespace MinePeakExporter
{
    /// <summary>
    /// PEAK's menus, for MinePeak's to look like them: the main menu's scene (the plane on the beach, drawn without the
    /// menu over it) and the pictures its menus are made of (the PEAK logo, the signpost's planks, the pause menu's
    /// ribbons...), each with where it sits on the screen. Taken from the player's own PEAK as it exports, into
    /// %APPDATA%/MinePeak/ui (menu_background.png, the pictures, ui.json); nothing of PEAK's ships with MinePeak.
    /// </summary>
    internal static class MenuArt
    {
        static string Dir => Path.Combine(Path.GetDirectoryName(MinePeakExport.Root), "ui");
        static readonly Dictionary<int, string> Saved = new Dictionary<int, string>();
        static JArray _items = new JArray();

        /// <summary>On PEAK's title screen: its background scene and its menu's pictures.</summary>
        public static void TakeMainMenu(Action<string> log)
        {
            try
            {
                Directory.CreateDirectory(Dir);
                Saved.Clear();
                _items = new JArray();
                var cam = Camera.main;
                if (cam != null)
                {
                    // the scene alone: every canvas off while it's drawn (in case one draws through the camera)
                    var off = UnityEngine.Object.FindObjectsByType<Canvas>(FindObjectsSortMode.None).Where(c => c.enabled).ToList();
                    foreach (var c in off) c.enabled = false;
                    try { Render(cam, Path.Combine(Dir, "menu_background.png"), 1920, 1080); }
                    finally { foreach (var c in off) if (c != null) c.enabled = true; }
                }
                int n = 0;
                foreach (var canvas in UnityEngine.Object.FindObjectsByType<Canvas>(FindObjectsInactive.Include, FindObjectsSortMode.None))
                    if (canvas.isRootCanvas) n += Dump(canvas.transform, "menu", log);
                Write();
                log($"menu art: the title screen's scene and {n} of its pictures, into {Dir}");
            }
            catch (Exception e) { log("menu art (title screen) failed: " + e.Message); }
        }

        /// <summary>In the airport: the pause menu's pictures (GUIManager's pause menu, open or not).</summary>
        public static void TakePauseMenu(Action<string> log)
        {
            try
            {
                var gui = GUIManager.instance;
                var pause = gui != null ? HarmonyLib.AccessTools.Field(typeof(GUIManager), "pauseMenu")?.GetValue(gui) as GameObject : null;
                if (pause == null) { log("menu art: no pause menu"); return; }
                bool was = pause.activeSelf;
                pause.SetActive(true);   // (its layout, to know where things sit)
                Canvas.ForceUpdateCanvases();
                int n = Dump(pause.transform, "pause", log);
                pause.SetActive(was);
                Write();
                log($"menu art: {n} of the pause menu's pictures");
            }
            catch (Exception e) { log("menu art (pause menu) failed: " + e.Message); }
        }

        /// <summary>
        /// In the airport: PEAK's HUD, its item slots (three pockets, the backpack's, the one for a thing too many) and its
        /// stamina bar with each status's piece, into ui.json (set "hud") and ui/hud.json (what each part is).
        /// </summary>
        public static void TakeHud(Action<string> log)
        {
            try
            {
                var gui = GUIManager.instance;
                if (gui == null || gui.items == null || gui.items.Length == 0) { log("hud art: no HUD"); return; }
                Canvas.ForceUpdateCanvases();
                int n = 0;
                var roots = new List<Transform> { gui.items[0].transform.parent };
                if (gui.backpack != null && !roots.Any(r => gui.backpack.transform.IsChildOf(r))) roots.Add(gui.backpack.transform);
                if (gui.temporaryItem != null && !roots.Any(r => gui.temporaryItem.transform.IsChildOf(r))) roots.Add(gui.temporaryItem.transform);
                if (gui.bar != null) roots.Add(gui.bar.transform.parent);
                foreach (var r in roots) n += Dump(r, "hud", log);
                var hud = new JObject();
                var canvas = gui.hudCanvas != null ? gui.hudCanvas : gui.items[0].GetComponentInParent<Canvas>()?.rootCanvas;
                if (canvas != null) { var rs = ((RectTransform)canvas.transform).rect.size; hud["canvas"] = new JArray(Math.Round(rs.x, 1), Math.Round(rs.y, 1)); }
                var slots = new JArray();
                foreach (var s in gui.items.Concat(new[] { gui.backpack, gui.temporaryItem }))
                    if (s != null) slots.Add(Part(s, canvas));
                hud["slots"] = slots;
                if (gui.bar != null) hud["bar"] = Part(gui.bar, canvas);
                var afflictions = new JArray();
                if (gui.bar != null)
                    foreach (var a in gui.bar.transform.parent.GetComponentsInChildren<BarAffliction>(true))
                    {
                        var o = new JObject { ["type"] = a.afflictionType.ToString(), ["path"] = TPath(a.transform), ["petrify"] = a.isPetrify };
                        if (a.icon != null)
                        {
                            o["color"] = "#" + ColorUtility.ToHtmlStringRGBA(a.icon.color);
                            if (a.icon.sprite != null) o["file"] = SaveSprite(a.icon.sprite);
                        }
                        var img = a.GetComponent<Image>();
                        if (img != null) { o["backColor"] = "#" + ColorUtility.ToHtmlStringRGBA(img.color); if (img.sprite != null) o["backFile"] = SaveSprite(img.sprite); }
                        afflictions.Add(o);
                    }
                hud["afflictions"] = afflictions;
                File.WriteAllText(Path.Combine(Dir, "hud.json"), hud.ToString());
                Write();
                log($"hud art: {n} pictures, {slots.Count} slots, {afflictions.Count} statuses");
            }
            catch (Exception e) { log("hud art failed: " + e); }
        }

        static string SaveSprite(Sprite sp)
        {
            int id = sp.GetInstanceID();
            if (Saved.TryGetValue(id, out var f)) return f;
            f = Safe(sp.name) + "_" + (id & 0xFFFF).ToString("x4") + ".png";
            if (!SavePicture(sp.texture, sp.textureRect, Path.Combine(Dir, f))) return null;
            Saved[id] = f;
            return f;
        }

        static string SaveTexture(Texture t)
        {
            int id = t.GetInstanceID();
            if (Saved.TryGetValue(id, out var f)) return f;
            f = Safe(t.name) + "_" + (id & 0xFFFF).ToString("x4") + ".png";
            if (!SavePicture(t, new Rect(0, 0, t.width, t.height), Path.Combine(Dir, f))) return null;
            Saved[id] = f;
            return f;
        }

        /// <summary>A HUD part (a slot, the bar): each of its fields that's a picture, a shape, a text or a colour, with where it sits.</summary>
        static JObject Part(MonoBehaviour part, Canvas canvas)
        {
            var o = new JObject { ["path"] = TPath(part.transform), ["active"] = part.gameObject.activeInHierarchy };
            if (canvas != null) o["rect"] = RectOf((RectTransform)part.transform, canvas);
            var flags = System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.NonPublic;
            foreach (var f in part.GetType().GetFields(flags))
            {
                object v;
                try { v = f.GetValue(part); } catch (Exception) { continue; }
                JToken j = null;
                switch (v)
                {
                    case Graphic g when g != null: j = Graphic(g, canvas); break;
                    case RectTransform rt when rt != null: { var g2 = rt.GetComponent<Graphic>(); j = g2 != null ? Graphic(g2, canvas) : new JObject { ["rect"] = RectOf(rt, canvas), ["size"] = Size(rt) }; break; }
                    case GameObject go when go != null: { var g3 = go.GetComponentInChildren<Graphic>(true); j = g3 != null ? Graphic(g3, canvas) : null; if (j is JObject jo) jo["object"] = go.name; break; }
                    case Texture t when t != null: { var file = SaveTexture(t); if (file != null) j = new JObject { ["file"] = file }; break; }
                    case Color c: j = "#" + ColorUtility.ToHtmlStringRGBA(c); break;
                    case float fl: j = Math.Round(fl, 3); break;
                }
                if (j != null) o[f.Name] = j;
            }
            return o;
        }

        static JObject Graphic(Graphic g, Canvas canvas)
        {
            var o = new JObject { ["path"] = TPath(g.transform), ["kind"] = g.GetType().Name, ["color"] = "#" + ColorUtility.ToHtmlStringRGBA(g.color),
                ["active"] = g.gameObject.activeInHierarchy, ["size"] = Size(g.rectTransform) };
            if (canvas != null) o["rect"] = RectOf(g.rectTransform, canvas);
            if (g is Image im)
            {
                o["type"] = im.type.ToString();
                if (im.sprite != null) o["file"] = SaveSprite(im.sprite);
                if (im.sprite != null && im.sprite.border.sqrMagnitude > 0) o["border"] = new JArray(im.sprite.border.x, im.sprite.border.y, im.sprite.border.z, im.sprite.border.w);
                if (im.type == Image.Type.Filled) { o["fillMethod"] = im.fillMethod.ToString(); o["fillOrigin"] = im.fillOrigin; }
            }
            else if (g is RawImage raw && raw.texture != null) o["file"] = SaveTexture(raw.texture);
            else if (g is TMPro.TMP_Text text) { o["fontSize"] = Math.Round(text.fontSize, 1); o["text"] = text.text; o["align"] = text.alignment.ToString(); }
            foreach (var m in g.GetComponents<MonoBehaviour>())
                if (m != null && m.GetType().Name.EndsWith("Modifier")) { o["modifier"] = m.GetType().Name; o["modifierFields"] = Reflect.Fields(m); }
            return o;
        }

        static JArray Size(RectTransform rt) => new JArray(Math.Round(rt.rect.width, 1), Math.Round(rt.rect.height, 1));

        static JArray RectOf(RectTransform rt, Canvas canvas)
        {
            var c = new Vector3[4]; var cc = new Vector3[4];
            rt.GetWorldCorners(c);
            ((RectTransform)canvas.transform).GetWorldCorners(cc);
            float W = cc[2].x - cc[0].x, H = cc[2].y - cc[0].y;
            if (W <= 1 || H <= 1) return null;
            return new JArray(Math.Round((c[0].x - cc[0].x) / W, 4), Math.Round(1 - (c[2].y - cc[0].y) / H, 4),
                Math.Round((c[2].x - cc[0].x) / W, 4), Math.Round(1 - (c[0].y - cc[0].y) / H, 4));
        }

        static void Write() => File.WriteAllText(Path.Combine(Dir, "ui.json"), new JObject { ["items"] = _items }.ToString());

        static string TPath(Transform t) { var p = t.name; while (t.parent != null) { t = t.parent; p = t.name + "/" + p; } return p; }

        /// <summary>Every picture under root: saved once each, and where it sits (0..1 of its canvas, from the top left).</summary>
        static int Dump(Transform root, string set, Action<string> log)
        {
            int n = 0;
            foreach (var g in root.GetComponentsInChildren<Graphic>(true))
            {
                Texture tex = null; Rect rect = default; string name = null; int id = 0;
                if (g is Image img && img.sprite != null) { tex = img.sprite.texture; rect = img.sprite.textureRect; name = img.sprite.name; id = img.sprite.GetInstanceID(); }
                else if (g is RawImage raw && raw.texture != null) { tex = raw.texture; rect = new Rect(0, 0, tex.width, tex.height); name = tex.name; id = tex.GetInstanceID(); }
                string file = null;
                if (tex == null || rect.width < 4 || rect.height < 4)
                {
                    if (set != "hud" || !(g is Image)) continue;   // (the HUD's plain shapes too: PEAK draws some of its bars so)
                }
                else if (!Saved.TryGetValue(id, out file))
                {
                    file = Safe(name) + "_" + (id & 0xFFFF).ToString("x4") + ".png";
                    if (SavePicture(tex, rect, Path.Combine(Dir, file))) { Saved[id] = file; n++; }
                    else continue;
                }
                var canvas = g.canvas != null ? g.canvas.rootCanvas : null;
                var item = new JObject { ["set"] = set, ["path"] = TPath(g.transform), ["active"] = g.gameObject.activeInHierarchy,
                    ["color"] = "#" + ColorUtility.ToHtmlStringRGBA(g.color) };
                if (file != null) item["file"] = file;
                if (g is Image im2)
                {
                    item["type"] = im2.type.ToString();
                    if (im2.sprite != null && im2.sprite.border.sqrMagnitude > 0)
                        item["border"] = new JArray(im2.sprite.border.x, im2.sprite.border.y, im2.sprite.border.z, im2.sprite.border.w);
                    if (im2.type == Image.Type.Filled) { item["fillMethod"] = im2.fillMethod.ToString(); item["fillAmount"] = Math.Round(im2.fillAmount, 3); }
                }
                if (set == "hud")
                {
                    item["kind"] = g.GetType().Name;
                    item["size"] = Size(g.rectTransform);
                    // (PEAK's rounded shapes: ProceduralImage's modifier keeps the corners' radius)
                    foreach (var m in g.GetComponents<MonoBehaviour>())
                        if (m != null && m.GetType().Name.EndsWith("Modifier")) { item["modifier"] = m.GetType().Name; item["modifierFields"] = Reflect.Fields(m); }
                }
                if (canvas != null)
                {
                    var c = new Vector3[4]; var cc = new Vector3[4];
                    g.rectTransform.GetWorldCorners(c);
                    ((RectTransform)canvas.transform).GetWorldCorners(cc);
                    float W = cc[2].x - cc[0].x, H = cc[2].y - cc[0].y;
                    if (W > 1 && H > 1)
                        item["rect"] = new JArray(Math.Round((c[0].x - cc[0].x) / W, 4), Math.Round(1 - (c[2].y - cc[0].y) / H, 4),
                            Math.Round((c[2].x - cc[0].x) / W, 4), Math.Round(1 - (c[0].y - cc[0].y) / H, 4));
                }
                var label = g.GetComponentInChildren<TMPro.TMP_Text>(true) ?? g.transform.parent?.GetComponentInChildren<TMPro.TMP_Text>(true);
                if (label != null && !string.IsNullOrWhiteSpace(label.text)) item["text"] = label.text;
                _items.Add(item);
            }
            return n;
        }

        static string Safe(string s)
        {
            var o = new System.Text.StringBuilder();
            foreach (char ch in s ?? "picture") o.Append(char.IsLetterOrDigit(ch) ? char.ToLowerInvariant(ch) : '_');
            return o.Length > 40 ? o.ToString(0, 40) : o.ToString();
        }

        /// <summary>A sprite's part of a texture into a PNG (through the GPU: PEAK's textures aren't readable).</summary>
        internal static bool SavePicture(Texture tex, Rect r, string file)
        {
            try
            {
                var rt = RenderTexture.GetTemporary(tex.width, tex.height, 0, RenderTextureFormat.ARGB32, RenderTextureReadWrite.sRGB);
                Graphics.Blit(tex, rt);
                var prev = RenderTexture.active;
                RenderTexture.active = rt;
                var o = new Texture2D(Mathf.RoundToInt(r.width), Mathf.RoundToInt(r.height), TextureFormat.RGBA32, false);
                o.ReadPixels(new Rect(r.x, r.y, r.width, r.height), 0, 0);
                o.Apply();
                RenderTexture.active = prev;
                RenderTexture.ReleaseTemporary(rt);
                File.WriteAllBytes(file, o.EncodeToPNG());
                UnityEngine.Object.Destroy(o);
                return true;
            }
            catch (Exception) { return false; }
        }

        /// <summary>A camera's view (PEAK's light, its post-processing) into a PNG.</summary>
        static void Render(Camera cam, string file, int w, int h)
        {
            var rt = new RenderTexture(w, h, 24, RenderTextureFormat.ARGB32);
            var old = cam.targetTexture;
            cam.targetTexture = rt;
            var req = new RenderPipeline.StandardRequest { destination = rt };
            if (RenderPipeline.SupportsRenderRequest(cam, req)) RenderPipeline.SubmitRenderRequest(cam, req); else cam.Render();
            cam.targetTexture = old;
            var prev = RenderTexture.active;
            RenderTexture.active = rt;
            var tex = new Texture2D(w, h, TextureFormat.RGB24, false);
            tex.ReadPixels(new Rect(0, 0, w, h), 0, 0);
            tex.Apply();
            RenderTexture.active = prev;
            File.WriteAllBytes(file, tex.EncodeToPNG());
            UnityEngine.Object.Destroy(tex);
            rt.Release();
        }
    }
}
