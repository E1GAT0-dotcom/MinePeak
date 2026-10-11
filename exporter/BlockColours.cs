using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using UnityEngine;

namespace MinePeakExporter
{
    /// <summary>
    /// The colours of Minecraft's building blocks, measured from the player's own Minecraft (its 26.2 jar, read when the
    /// export runs; nothing of Minecraft's is shipped), so a colour of PEAK's can become the block that looks most like it.
    /// Four sets: solid blocks, see-through ones (glass), ones that give light, and leaves.
    /// </summary>
    internal static class BlockColours
    {
        public enum Kind { Solid, Glass, Glow, Leaves }

        // block id, texture (when not named after the block), and for leaves the tint Minecraft gives them
        static readonly string[] Dyes = { "white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow", "lime", "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink" };
        static readonly string[] Woods = { "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo", "pale_oak" };

        static IEnumerable<(string block, string tex)> SolidBlocks()
        {
            foreach (var d in Dyes) { yield return (d + "_concrete", null); yield return (d + "_wool", null); yield return (d + "_terracotta", null); }
            yield return ("terracotta", null);
            foreach (var w in Woods) yield return (w + "_planks", null);
            yield return ("quartz_block", "quartz_block_side");
            yield return ("smooth_quartz", "quartz_block_bottom");
            foreach (var b in new[] { "calcite", "smooth_stone", "stone", "andesite", "polished_andesite", "diorite", "polished_diorite", "granite", "polished_granite",
                "tuff", "polished_tuff", "stone_bricks", "bricks", "mud_bricks", "packed_mud", "dark_prismarine", "iron_block", "lapis_block", "packed_ice",
                "polished_blackstone", "nether_bricks", "deepslate_tiles", "polished_deepslate", "snow_block", "white_glazed_terracotta" })
                if (b != "white_glazed_terracotta") yield return (b, b == "snow_block" ? "snow" : null);
            yield return ("smooth_sandstone", "sandstone_top");
            yield return ("smooth_red_sandstone", "red_sandstone_top");
        }

        static IEnumerable<(string block, string tex)> GlassBlocks()
        {
            yield return ("glass", null);
            foreach (var d in Dyes) yield return (d + "_stained_glass", null);
        }

        static IEnumerable<(string block, string tex)> GlowBlocks()
        {
            yield return ("sea_lantern", null);
            yield return ("glowstone", null);
            yield return ("shroomlight", null);
            yield return ("ochre_froglight", "ochre_froglight_top");
            yield return ("verdant_froglight", "verdant_froglight_top");
            yield return ("pearlescent_froglight", "pearlescent_froglight_top");
            yield return ("redstone_lamp[lit=true]", "redstone_lamp_on");
        }

        static IEnumerable<(string block, string tex, Color tint)> LeafBlocks()
        {
            var foliage = new Color32(0x77, 0xAB, 0x2F, 0xFF);
            yield return ("oak_leaves", null, foliage);
            yield return ("jungle_leaves", null, foliage);
            yield return ("acacia_leaves", null, foliage);
            yield return ("dark_oak_leaves", null, foliage);
            yield return ("mangrove_leaves", null, new Color32(0x92, 0xC2, 0x35, 0xFF));
            yield return ("spruce_leaves", null, new Color32(0x61, 0x99, 0x61, 0xFF));
            yield return ("birch_leaves", null, new Color32(0x80, 0xA7, 0x55, 0xFF));
            yield return ("azalea_leaves", null, Color.white);
            yield return ("cherry_leaves", null, Color.white);
        }

        static readonly Dictionary<Kind, List<(string block, Vector3 lab)>> Sets = new Dictionary<Kind, List<(string, Vector3)>>();
        public static string Jar { get; private set; }
        public static bool Ready => Sets.Count > 0;

        /// <summary>Measures the blocks (once). False when there's no Minecraft 26.2 to measure them from.</summary>
        public static bool Load(Action<string> log)
        {
            if (Ready) return true;
            Jar = FindJar();
            if (Jar == null) { log("block colours: no Minecraft 26.2 jar found (in .minecraft\\versions): the airport keeps PEAKCRAFT's blocks"); return false; }
            try
            {
                using (var zip = new ZipArchive(File.OpenRead(Jar), ZipArchiveMode.Read))
                {
                    Color? Avg(string tex, Color tint)
                    {
                        var e = zip.GetEntry($"assets/minecraft/textures/block/{tex}.png");
                        if (e == null) return null;
                        using (var s = e.Open()) using (var ms = new MemoryStream())
                        {
                            s.CopyTo(ms);
                            var t = new Texture2D(2, 2, TextureFormat.RGBA32, false);
                            if (!t.LoadImage(ms.ToArray())) return null;
                            int h = Math.Min(t.height, t.width);   // (animated textures are frames stacked: the first)
                            var px = t.GetPixels(0, t.height - h, t.width, h);
                            UnityEngine.Object.Destroy(t);
                            Color sum = Color.clear; float a = 0f;
                            foreach (var p in px) { sum += p * p.a; a += p.a; }
                            if (a <= 0f) return null;
                            var c = sum / a * tint;
                            c.a = a / px.Length;
                            return c;
                        }
                    }
                    void Add(Kind k, string block, string tex, Color tint)
                    {
                        var c = Avg(tex ?? block.Split('[')[0], tint);
                        if (c == null) return;
                        if (!Sets.TryGetValue(k, out var list)) Sets[k] = list = new List<(string, Vector3)>();
                        list.Add(("minecraft:" + block, Lab(c.Value)));
                    }
                    foreach (var b in SolidBlocks()) Add(Kind.Solid, b.block, b.tex, Color.white);
                    foreach (var b in GlassBlocks()) Add(Kind.Glass, b.block, b.tex, Color.white);
                    foreach (var b in GlowBlocks()) Add(Kind.Glow, b.block, b.tex, Color.white);
                    foreach (var b in LeafBlocks()) Add(Kind.Leaves, b.block, b.tex, b.tint);
                }
                log($"block colours from {Jar}: " + string.Join(", ", Sets.Select(kv => $"{kv.Value.Count} {kv.Key}")));
                return Ready;
            }
            catch (Exception e) { Sets.Clear(); log("block colours: couldn't read " + Jar + ": " + e.Message); return false; }
        }

        static string FindJar()
        {
            string versions = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), ".minecraft", "versions");
            string p = Path.Combine(versions, "26.2", "26.2.jar");
            return File.Exists(p) ? p : null;
        }

        /// <summary>The block of this kind that looks most like the colour (in Lab, which follows how we see colour).</summary>
        /// <remarks>
        /// A colour with almost no colour in it (white, greys) only gets blocks with almost none either: PEAK's near-white
        /// surfaces drift slightly in tint, and the nearest coloured block (beige terracotta, pink planks) makes far more of
        /// that tint than there is.
        /// </remarks>
        public static string Nearest(Kind k, Color c)
        {
            if (!Sets.TryGetValue(k, out var list) || list.Count == 0) list = Sets[Kind.Solid];
            var lab = Lab(c);
            bool neutral = Chroma(lab) < 10f;
            string best = null; float bd = float.MaxValue;
            for (int pass = 0; pass < 2 && best == null; pass++)
                foreach (var (block, l) in list)
                {
                    if (pass == 0 && neutral && Chroma(l) >= 14f) continue;
                    float d = (l - lab).sqrMagnitude;
                    if (d < bd) { bd = d; best = block; }
                }
            return best;
        }

        static float Chroma(Vector3 lab) => Mathf.Sqrt(lab.y * lab.y + lab.z * lab.z);

        internal static Vector3 Lab(Color c)
        {
            float Lin(float v) => v <= 0.04045f ? v / 12.92f : Mathf.Pow((v + 0.055f) / 1.055f, 2.4f);
            float r = Lin(c.r), g = Lin(c.g), b = Lin(c.b);
            float x = (r * 0.4124f + g * 0.3576f + b * 0.1805f) / 0.95047f;
            float y = r * 0.2126f + g * 0.7152f + b * 0.0722f;
            float z = (r * 0.0193f + g * 0.1192f + b * 0.9505f) / 1.08883f;
            float F(float t) => t > 0.008856f ? Mathf.Pow(t, 1f / 3f) : 7.787f * t + 16f / 116f;
            return new Vector3(116f * F(y) - 16f, 500f * (F(x) - F(y)), 200f * (F(y) - F(z)));
        }
    }
}
