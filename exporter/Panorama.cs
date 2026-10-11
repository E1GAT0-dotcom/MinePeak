using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using Newtonsoft.Json.Linq;
using UnityEngine;
using UnityEngine.Rendering;

namespace MinePeakExporter
{
    /// <summary>
    /// The menus' panorama for MinePeak (in Minecraft): PEAK's airport from the middle of its hall, six square pictures
    /// at 90 degrees, as Minecraft's own panorama is made (ahead, right, behind, left, up, down; up and down with their tops
    /// away from and towards the way "ahead" faces). Into %APPDATA%/MinePeak/panorama/panorama_0..5.png, with
    /// panorama.json. Taken in the Airport scene, as the game draws it (its renderer, its light and shadows); no scouts.
    /// </summary>
    internal static class Panorama
    {
        public static string Dir => Path.Combine(Path.GetDirectoryName(MinePeakExport.Root), "panorama");

        // the middle of the hall (PEAK's x and z: on the carpet between the walkway and the seats), and the way the first
        // picture faces: Gate 7, the departure boards and the climbing wall
        static readonly Vector2 Spot = new Vector2(-17.8f, 98.8f);
        const float FirstYaw = 90f;
        const int Size = 1024;

        public static IEnumerator Take(Character ch, Action<string> log, bool compare = false)
        {
            float t0 = Time.realtimeSinceStartup;
            float floor = Physics.Raycast(new Vector3(Spot.x, ch.Center.y + 1.5f, Spot.y), Vector3.down, out var hit, 8f, ~0, QueryTriggerInteraction.Ignore)
                ? hit.point.y : ch.Center.y - 1.9f;
            var eye = new Vector3(Spot.x, floor + 1.7f, Spot.y);
            // the scouts and what they hold onto a layer the camera doesn't draw, meanwhile
            const int Hidden = 31;
            var moved = new List<(GameObject go, int layer)>();
            foreach (var r in UnityEngine.Object.FindObjectsByType<Renderer>(FindObjectsSortMode.None))
                if (r != null && r.gameObject.layer != Hidden && (r.GetComponentInParent<Character>() != null || r.GetComponentInParent<Item>() != null))
                { moved.Add((r.gameObject, r.gameObject.layer)); r.gameObject.layer = Hidden; }
            var go = new GameObject("MinePeakPanoramaCamera");
            try
            {
                var cam = go.AddComponent<Camera>();
                cam.enabled = false;
                cam.fieldOfView = 90f; cam.aspect = 1f;
                cam.nearClipPlane = 0.05f; cam.farClipPlane = 3000f;
                cam.clearFlags = CameraClearFlags.Skybox;
                cam.useOcclusionCulling = false;   // (PEAK's baked occlusion is for its own camera)
                var main = Camera.main;
                if (main != null) cam.allowHDR = main.allowHDR;
                cam.cullingMask = (main != null ? main.cullingMask : ~0) & ~(1 << Hidden);
                int character = LayerMask.NameToLayer("Character");
                if (character >= 0) cam.cullingMask &= ~(1 << character);
                // the game camera's renderer (PEAK's plants are drawn by its own passes), with shadows and PEAK's
                // post-processing (its colour grading: there's no vignette to show the cube's seams)
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
                        data.requiresDepthOption = md.requiresDepthOption;
                        data.requiresColorOption = md.requiresColorOption;
                    }
                }
                catch (Exception e) { log("  panorama: couldn't take the game camera's renderer: " + e.Message); }

                Directory.CreateDirectory(Dir);
                var turns = new[] { (0f, 0f), (0f, 90f), (0f, 180f), (0f, -90f), (-90f, 0f), (90f, 0f) };
                for (int i = 0; i < 6; i++)
                {
                    cam.transform.SetPositionAndRotation(eye, Quaternion.Euler(turns[i].Item1, FirstYaw + turns[i].Item2, 0f));
                    Save(cam, Path.Combine(Dir, $"panorama_{i}.png"));
                    yield return null;
                }
                if (compare)
                {
                    // the first side without PEAK's post-processing, to compare
                    var data = UnityEngine.Rendering.Universal.CameraExtensions.GetUniversalAdditionalCameraData(cam);
                    data.renderPostProcessing = false;
                    cam.transform.SetPositionAndRotation(eye, Quaternion.Euler(0f, FirstYaw, 0f));
                    Save(cam, Path.Combine(Safety.DataDir, "probe", "panorama_0_nopost.png"));
                }
                File.WriteAllText(Path.Combine(Dir, "panorama.json"), new JObject
                {
                    ["date"] = DateTime.Now.ToString("yyyy-MM-dd HH:mm"), ["eye"] = new JArray(Math.Round(eye.x, 2), Math.Round(eye.y, 2), Math.Round(eye.z, 2)),
                    ["firstYaw"] = FirstYaw, ["size"] = Size,
                }.ToString());
                log($"panorama: 6 pictures from {eye:F1} in {Time.realtimeSinceStartup - t0:F1} s, into {Dir}");
            }
            finally
            {
                UnityEngine.Object.Destroy(go);
                foreach (var (g, layer) in moved) if (g != null) g.layer = layer;
            }
        }

        /// <summary>Drawn at twice the size and halved (smoother edges), into a PNG (written beside it first, then moved in place).</summary>
        static void Save(Camera cam, string file)
        {
            var big = new RenderTexture(Size * 2, Size * 2, 24, RenderTextureFormat.ARGB32);
            var small = new RenderTexture(Size, Size, 0, RenderTextureFormat.ARGB32);
            cam.targetTexture = big;
            var req = new RenderPipeline.StandardRequest { destination = big };
            if (RenderPipeline.SupportsRenderRequest(cam, req)) RenderPipeline.SubmitRenderRequest(cam, req); else cam.Render();
            cam.targetTexture = null;
            Graphics.Blit(big, small);
            var prev = RenderTexture.active;
            RenderTexture.active = small;
            var tex = new Texture2D(Size, Size, TextureFormat.RGB24, false);
            tex.ReadPixels(new Rect(0, 0, Size, Size), 0, 0);
            tex.Apply();
            RenderTexture.active = prev;
            File.WriteAllBytes(file + ".tmp", tex.EncodeToPNG());
            if (File.Exists(file)) File.Delete(file);
            File.Move(file + ".tmp", file);
            UnityEngine.Object.Destroy(tex);
            big.Release(); small.Release();
        }
    }
}
