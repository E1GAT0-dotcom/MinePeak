using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using Newtonsoft.Json.Linq;
using Unity.Collections;
using UnityEngine;
using Zorro.Core;

namespace MinePeakExporter
{
    /// <summary>
    /// The MinePeak export (PEAK inside Minecraft, the Fabric mod): turns the island of the day into Minecraft
    /// blocks on the player's own machine, for MinePeak to build its world from. Nothing of PEAK's ships.
    /// - 1 metre = 1 block. Minecraft's x is PEAK's x and its z is PEAK's -z (Unity is left-handed); PEAK's sea level
    ///   (y 0) is Minecraft's y 63.
    /// - Each area (a segment of the island) is a rectangle of 1 m columns. A column is scanned with rays down (they
    ///   hit the tops of things) and up (the undersides), which gives its solid runs, overhangs and caves included.
    /// - Blocks follow the material rules in Looks (MinePeak's copy of PEAKCRAFT's): flat tops get the material's top
    ///   block (grass, sand, snow; the volcano's magma patches), cliffs its side block, with dirt under grass. The
    ///   volcano (Map/Biome_4) is the Nether, as in PEAKCRAFT.
    /// Files: %APPDATA%/MinePeak/islands/&lt;date&gt;_&lt;scene&gt;/island.json and &lt;area&gt;.bin, each a header
    /// ("MPK1", x0, z0, width, depth as int32) and a gzip of the columns (z-major): a count, then per run its top block's
    /// y - MinY, its length (down from there) and its palette index, as varints.
    /// </summary>
    internal static class MinePeakExport
    {
        static int SolidMask => LayerMask.GetMask("Terrain", "Map", "Default");   // (Default too: hundreds of PEAK's solid things are on it; invisible ones are skipped)
        static int WaterLayer => LayerMask.NameToLayer("Water") >= 0 ? LayerMask.NameToLayer("Water") : 4;

        // The volcano's magma patches (as PEAKCRAFT draws them): smooth noise over 4-block cells, roughened per block.
        const float TopAltCut = 0.86f;
        static uint CellHash(int x, int y, int z)
        {
            unchecked
            {
                uint h = ((uint)x * 73856093u) ^ ((uint)y * 19349663u) ^ ((uint)z * 83492791u);
                h ^= h >> 13; h *= 0x5bd1e995u; h ^= h >> 15;
                return h;
            }
        }
        static float CellRand(int x, int z, int salt) => (CellHash(x, salt, z) % 1000u) / 1000f;
        static bool TopAltAt(float wx, float wz)
        {
            float cx = Mathf.Floor(wx), cz = Mathf.Floor(wz);
            float gx = (cx + 0.5f) / 4f, gz = (cz + 0.5f) / 4f;
            float ix = Mathf.Floor(gx), iz = Mathf.Floor(gz);
            float fx = gx - ix, fz = gz - iz;
            fx = fx * fx * (3f - 2f * fx); fz = fz * fz * (3f - 2f * fz);
            int x0 = (int)ix, z0 = (int)iz;
            float a = CellRand(x0, z0, 911), b = CellRand(x0 + 1, z0, 911), c = CellRand(x0, z0 + 1, 911), d = CellRand(x0 + 1, z0 + 1, 911);
            float ab = a + (b - a) * fx, cd = c + (d - c) * fx;
            return ab + (cd - ab) * fz + (CellRand((int)cx, (int)cz, 37) - 0.5f) * 0.3f > TopAltCut;
        }

        /// <summary>What a nameless collider probably is, from its names (MinePeak's copy of PEAKCRAFT's Surfaces.Classify).</summary>
        static string Classify(RaycastHit hit)
        {
            var c = hit.collider;
            var r = c.GetComponent<Renderer>() ?? c.GetComponentInParent<Renderer>();
            string names = ((r != null && r.sharedMaterial != null ? r.sharedMaterial.name : "") + " " + c.gameObject.name + " " + (c.transform.parent != null ? c.transform.parent.name : "")).ToLowerInvariant();
            bool Has(params string[] keys) { foreach (var k in keys) if (names.Contains(k)) return true; return false; }
            bool steep = Vector3.Angle(hit.normal, Vector3.up) > 50f;
            if (Has("palmtree 1", "trunk", "bark", "driftwood", "log", "wood", "plank", "branch", "stump", "bamboo")) return "oak_log";
            if (Has("leaf", "leaves", "foliage", "bush", "palm", "frond", "canopy", "hedge", "fern")) return "oak_leaves";
            if (Has("snow", "ice", "frost", "glacier")) return "snow_block";
            if (Has("sand", "beach", "dune", "desert", "mesa")) return "sand";
            if (Has("grass", "moss", "ground", "dirt", "soil", "jungle", "meadow", "mud") && !steep) return "grass_block";
            if (Has("grass", "moss", "dirt", "soil", "mud")) return "dirt";
            return "stone";
        }

        public const int YOffset = 63, MinY = -64, MaxY = 1343;
        public static string Root => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "MinePeak", "islands");

        static readonly List<string> Palette = new List<string> { "minecraft:air" };
        static readonly Dictionary<string, int> PaletteIndex = new Dictionary<string, int> { ["minecraft:air"] = 0 };
        static readonly Dictionary<int, (int top, int sub, int side, bool alt)> Cache = new Dictionary<int, (int, int, int, bool)>();
        static readonly List<(float y, bool top, RaycastHit hit)> Events = new List<(float, bool, RaycastHit)>();

        public static Vector3 ToMc(Vector3 p) => new Vector3(p.x, p.y + YOffset, -p.z);
        static JArray J(Vector3 p) { var m = ToMc(p); return new JArray(Mathf.Floor(m.x), Mathf.Floor(m.y), Mathf.Floor(m.z)); }

        static int P(string block)
        {
            string id = block.Contains(":") ? block : "minecraft:" + block;
            if (id.EndsWith("_leaves")) id += "[persistent=true]";   // (or they'd decay, with no logs next to them)
            if (!PaletteIndex.TryGetValue(id, out int i)) { i = Palette.Count; Palette.Add(id); PaletteIndex[id] = i; }
            return i;
        }

        /// <summary>The block for one of PEAKCRAFT's textures (mostly named after their blocks).</summary>
        static string BlockOf(string tex)
        {
            switch (tex)
            {
                case "grass_block_top": return "grass_block";
                case "basalt_side": case "basalt_top": return "basalt";
                case "deepslate_top": return "deepslate";
                case "spruce_log_top": return "spruce_log";
                case "quartz_block_side": return "quartz_block";
                case "scaffolding_side": return "oak_planks";
                case "cactus_side": return "moss_block";
                case "snow": return "snow_block";
                case "water_still": return "water";
                case "lava_still": return "lava";
                case "blackstone_top": return "blackstone";
                case "magma": return "magma_block";
                case "vine": case "fern": return "jungle_leaves";
                case "weeping_vines": return "nether_wart_block";
                case "twisting_vines": return "warped_wart_block";
                default: return tex;
            }
        }

        static (int top, int sub, int side, bool alt) Blocks(string top, string side, bool alt)
        {
            string t = BlockOf(top), s = BlockOf(side);
            string sub = t == "grass_block" ? "dirt" : t.EndsWith("sand") || t == "snow_block" ? t : s;
            return (P(t), P(sub), P(s), alt);
        }

        /// <summary>What a collider is made of (once per collider).</summary>
        static (int top, int sub, int side, bool alt) BlocksFor(RaycastHit h)
        {
            var c = h.collider;
            int id = c.GetInstanceID();
            if (Cache.TryGetValue(id, out var b)) return b;
            b = Decide(h);
            Cache[id] = b;
            return b;
        }

        static (int top, int sub, int side, bool alt) Decide(RaycastHit h)
        {
            var c = h.collider;
            if (CalderaFloor(c)) return Blocks("lava_still", "blackstone", false);
            if (c.GetComponentInParent<Lava>() != null || c.GetComponentInParent<LavaRising>() != null) return Blocks("lava_still", "lava_still", false);
            var r = c.GetComponent<Renderer>();
            if (r == null && c.transform.parent != null) r = c.transform.parent.GetComponent<Renderer>();
            if (r == null) r = c.GetComponentInChildren<Renderer>();
            if (r == null && c.transform.parent != null)
                // a collider of its own beside the rock it stands for: the renderer next to it that holds the point
                foreach (var nr in c.transform.parent.GetComponentsInChildren<Renderer>())
                {
                    var bb = nr.bounds; bb.Expand(0.5f);
                    if (!(nr is ParticleSystemRenderer) && bb.Contains(h.point)) { r = nr; break; }
                }
            bool nether = false, gloom = false;   // (PEAKCRAFT draws the volcano as the Nether; MinePeak looks like PEAK)
            if (r != null)
                foreach (var m in r.sharedMaterials)
                {
                    var look = Looks.For(m, nether, gloom);
                    if (look == null || look.Side == null) continue;
                    if (look.Side == "blackstone" && Summit(c)) return Blocks("grass_block_top", "stone", false);
                    return Blocks(look.Top ?? look.Side, look.Side, look.TopAlt == "magma");
                }
            string id = Classify(h);
            if (id == "grass_block") return (P("grass_block"), P("dirt"), P("stone"), false);
            if (id == "sand") return (P("sand"), P("sand"), P("sandstone"), false);
            if (id == "snow_block") return (P("snow_block"), P("snow_block"), P("stone"), false);
            return (P(id), P(id), P(id), false);
        }

        static readonly HashSet<int> Ground = new HashSet<int>();

        /// <summary>Is this ground (rock, earth, sand, snow), rather than a thing on it (trees, cacti, planks, glass)?</summary>
        static bool IsGround(RaycastHit h) => IsGroundBlock(BlocksFor(h).side);

        static bool IsGroundBlock(int block)
        {
            if (Ground.Contains(block)) return true;
            string b = Palette[block];
            bool thing = b.Contains("leaves") || b.Contains("_log") || b.Contains("_stem") || b.Contains("wart_block") || b.Contains("planks") || b.Contains("moss_block") || b.Contains("mangrove") || b.Contains("pale_oak")
                || b.Contains("glass") || b.Contains("wool") || b.Contains("iron_block") || b.Contains("lava") || b.Contains("water");
            if (!thing) Ground.Add(block);
            return !thing;
        }

        static readonly Dictionary<int, bool> InvisibleCache = new Dictionary<int, bool>();

        /// <summary>
        /// A collider with nothing drawn where it was hit: PEAK's invisible walls and helpers. (Not blocks: the first
        /// export turned the walls along the segments into long solid slabs.) Lava's colliders count as seen.
        /// </summary>
        static readonly Dictionary<int, bool> HazardCache = new Dictionary<int, bool>();

        /// <summary>PEAK's water hazards (the Gloom's poison swamp): a water surface on a solid layer.</summary>
        static bool IsWaterHazard(Collider c)
        {
            int id = c.GetInstanceID();
            if (HazardCache.TryGetValue(id, out bool w)) return w;
            w = false;
            for (var t = c.transform; t != null && !w; t = t.parent) w = t.name.IndexOf("WaterHazard", StringComparison.OrdinalIgnoreCase) >= 0;
            HazardCache[id] = w;
            return w;
        }

        static readonly HashSet<int> Walls = new HashSet<int>();

        /// <summary>PEAK's walls between segments (MapSegment.wallNext / wallPrevious): its fog walls, not ground.</summary>
        static void FindWalls()
        {
            Walls.Clear();
            var mh = Singleton<MapHandler>.Instance;
            if (mh == null) return;
            foreach (var list in new[] { mh.segments, mh.variantSegments })
                if (list != null)
                    foreach (var seg in list)
                        foreach (var w in new[] { seg?.wallNext, seg?.wallPrevious })
                            if (w != null) foreach (var c in w.GetComponentsInChildren<Collider>(true)) Walls.Add(c.GetInstanceID());
        }

        static bool Invisible(RaycastHit h)
        {
            var c = h.collider;
            int id = c.GetInstanceID();
            if (InvisibleCache.TryGetValue(id, out bool inv)) return inv;
            if (Walls.Contains(id)) { InvisibleCache[id] = true; Invisibles.Add("(wall) " + TPath(c.transform)); return true; }
            inv = true;
            if (c.GetComponentInParent<Lava>() != null || c.GetComponentInParent<LavaRising>() != null) inv = false;
            else
            {
                foreach (var r in c.GetComponentsInChildren<Renderer>()) if (r.enabled && !(r is ParticleSystemRenderer)) { inv = false; break; }
                if (inv && c.GetComponentInParent<Renderer>() is Renderer pr && pr.enabled) inv = false;
                var near = c.transform.parent != null && c.transform.parent.parent != null ? c.transform.parent.parent : c.transform.parent;
                if (inv && near != null)
                    foreach (var r in near.GetComponentsInChildren<Renderer>())
                    {
                        if (!r.enabled || r is ParticleSystemRenderer) continue;
                        var b = r.bounds; b.Expand(0.5f);
                        if (b.Contains(h.point)) { inv = false; break; }
                    }
            }
            InvisibleCache[id] = inv;
            if (inv) Invisibles.Add(TPath(c.transform));
            return inv;
        }

        public static readonly HashSet<string> Invisibles = new HashSet<string>();
        static readonly Dictionary<Collider, int> TopCount = new Dictionary<Collider, int>();
        static void Count(Collider c) => TopCount[c] = TopCount.TryGetValue(c, out int n) ? n + 1 : 1;

        static string TPath(Transform t) { var p = t.name; while (t.parent != null) { t = t.parent; p = t.name + "/" + p; } return p; }
        internal static string TPathOf(Transform t) => TPath(t);

        const int MaxHits = 400;   // (surfaces followed down and up a column: the cliffs are hundreds of overlapping rocks)
        const float ShellThickness = 5f;
        static readonly List<(float top, float bottom, RaycastHit hit)> Intervals = new List<(float, float, RaycastHit)>();
        static readonly Dictionary<Collider, (float y, RaycastHit hit)> Open = new Dictionary<Collider, (float, RaycastHit)>();
        static readonly int[] Cells = new int[MaxY - MinY + 1];   // (one column: PEAK's cells MinY - YOffset .. MaxY - YOffset)
        static readonly Dictionary<int, bool> SkipCache = new Dictionary<int, bool>();

        /// <summary>A plant MinePeak builds itself, or the plane: not blocks.</summary>
        static bool Skipped(Collider c)
        {
            int id = c.GetInstanceID();
            if (!SkipCache.TryGetValue(id, out bool k)) SkipCache[id] = k = Plants.Skip(c);
            return k;
        }

        /// <summary>PEAK's volcano is a shell, open underneath, with the Kiln inside it: blocks only a few thick.</summary>
        static bool Shell(Collider c) => c != null && c.name == "VolcanoModel";

        /// <summary>How thick the volcano's skin is down a column at a hit: 5 blocks across it, more where it's steep.</summary>
        static float ShellReach(RaycastHit h, float most = 16f) => Mathf.Min(most, ShellThickness / Mathf.Max(0.08f, Mathf.Abs(h.normal.y)));

        /// <summary>
        /// Each column's outer skin of the volcano (Minecraft x, z packed as Key): its bottom and top (Minecraft y) and
        /// block, for SkinClosure.
        /// </summary>
        static readonly Dictionary<long, (int bottom, int top, int block)> Skins = new Dictionary<long, (int, int, int)>();

        /// <summary>
        /// The Kiln tunnel's side walls (Minecraft x, z, packed as ShellColumns are): blocks to fill, where air, beside a
        /// tunnel in the volcano's skin (rays straight down miss its upright sides, and holes opened into the hollow
        /// volcano); and the tunnel's own air, kept.
        /// </summary>
        static readonly Dictionary<long, List<(int lo, int hi, int block)>> TunnelSides = new Dictionary<long, List<(int, int, int)>>();
        static readonly Dictionary<long, List<(int lo, int hi)>> TunnelAir = new Dictionary<long, List<(int, int)>>();
        static long Key(int x, int z) => ((long)x << 32) | (uint)z;

        /// <summary>The top of the volcano's rising lava (Unity y), or NaN (set for each segment).</summary>
        internal static float LavaTop = float.NaN;
        static (float top, float bottom, RaycastHit hit) LowestInterval;
        /// <summary>Columns the volcano's shell is over (Unity x, z), during an area's scan.</summary>
        static readonly HashSet<long> ShellColumns = new HashSet<long>();

        /// <summary>
        /// Under the volcano: well inside it (8 blocks or more in from its shell's edge, both ways), under whatever is
        /// lowest when that's above the rising lava, the lava's floor with rock under it; nearer its edge, rock under its
        /// shell (PEAK has nothing under the one-sided shell; a hollow, or the lava showing under an overhang, would show).
        /// Outside it, none of the rising lava's plane (wider than the volcano).
        /// </summary>
        static int VolcanoFloor(Area a)
        {
            if (ShellColumns.Count == 0) return 0;
            bool hasLava = !float.IsNaN(LavaTop);
            int lavaTop = hasLava ? Mathf.FloorToInt(LavaTop - 0.5f) + YOffset : int.MinValue, lava = P("lava"), rock = P("blackstone"), n = 0;
            int W = a.W, D = a.D;
            bool Is(int ix, int iz) => ShellColumns.Contains(((long)(a.X0 + ix) << 32) | (uint)Mathf.FloorToInt(-(a.Z0 + iz + 0.5f)));
            var rowLo = new int[D]; var rowHi = new int[D]; var colLo = new int[W]; var colHi = new int[W];
            for (int i = 0; i < D; i++) { rowLo[i] = int.MaxValue; rowHi[i] = int.MinValue; }
            for (int i = 0; i < W; i++) { colLo[i] = int.MaxValue; colHi[i] = int.MinValue; }
            for (int iz = 0; iz < D; iz++)
                for (int ix = 0; ix < W; ix++)
                    if (Is(ix, iz)) { rowLo[iz] = Math.Min(rowLo[iz], ix); rowHi[iz] = Math.Max(rowHi[iz], ix); colLo[ix] = Math.Min(colLo[ix], iz); colHi[ix] = Math.Max(colHi[ix], iz); }
            for (int iz = 0; iz < D; iz++)
                for (int ix = 0; ix < W; ix++)
                {
                    var runs = a.Cols[iz * W + ix];
                    bool within = ix >= rowLo[iz] && ix <= rowHi[iz] && iz >= colLo[ix] && iz <= colHi[ix];
                    if (!within)
                    {
                        // outside: no rising lava's plane
                        if (runs == null || !hasLava) continue;
                        var kept = new List<int>();
                        for (int i = 0; i + 2 < runs.Length; i += 3)
                            if (runs[i + 2] != lava || Math.Abs(runs[i] - lavaTop) > 40) { kept.Add(runs[i]); kept.Add(runs[i + 1]); kept.Add(runs[i + 2]); }
                        if (kept.Count != runs.Length) a.Cols[iz * W + ix] = kept.ToArray();
                        continue;
                    }
                    int bottom = int.MaxValue;
                    if (runs != null) for (int i = 0; i + 2 < runs.Length; i += 3) bottom = Math.Min(bottom, runs[i] - runs[i + 1] + 1);
                    bool deep = ix > rowLo[iz] + 8 && ix < rowHi[iz] - 8 && iz > colLo[ix] + 8 && iz < colHi[ix] - 8;
                    var l = new List<int>(runs ?? new int[0]);
                    if (deep && hasLava)
                    {
                        if (bottom != int.MaxValue && bottom <= lavaTop + 1)
                        {
                            // something reaching down past the lava's top (the Kiln tunnel's floor): rock under it, not a
                            // pit down to the world's bottom
                            if (bottom <= MinY + 1) continue;
                            l.Add(bottom - 1); l.Add(bottom - 1 - MinY); l.Add(rock);
                            a.Cols[iz * W + ix] = l.ToArray();
                            n++;
                            continue;
                        }
                        l.Add(lavaTop); l.Add(3); l.Add(lava);
                        l.Add(lavaTop - 3); l.Add(lavaTop - 3 - MinY); l.Add(rock);
                    }
                    else
                    {
                        if (bottom == int.MaxValue || bottom <= MinY + 1 || !Is(ix, iz)) continue;
                        l.Add(bottom - 1); l.Add(bottom - 1 - MinY); l.Add(rock);
                    }
                    a.Cols[iz * W + ix] = l.ToArray();
                    n++;
                }
            return n;
        }

        /// <summary>
        /// One column's solid runs, as (y, length, block) triples from the top down, from what its rays hit (Events: the
        /// tops down it and the undersides up it, cast by Scan), the poison water's top and the water's top (or -inf).
        /// </summary>
        static int[] Column(float ux, float uz, float yLo, float yHi, float hazardWater, float waterHit)
        {
            var runs = new List<int>();
            float groundTop = float.NegativeInfinity;
            var side = Sideways.At(ux, uz);
            if (Events.Count > 0 || side != null)
            {
                Events.Sort((a, b) => b.y.CompareTo(a.y));
                // Down the column, each collider on its own: solid from its top to its underside. (PEAK's ground is many
                // rocks that overlap; counting them all together let one with no underside swallow the whole column.)
                Intervals.Clear(); Open.Clear();
                float prevY = yHi;
                foreach (var e in Events)
                {
                    var c = e.hit.collider;
                    if (e.top) { if (!Open.ContainsKey(c)) Open[c] = (e.y, e.hit); }
                    else if (Open.TryGetValue(c, out var o))
                    {
                        Open.Remove(c);
                        if (Shell(c) && o.y - e.y > ShellReach(o.hit) + 3f)
                        {
                            // the volcano's skin and an underside of it far below (the Kiln tunnel's roof, 300 m down): two
                            // skins (PEAK has nothing between them; filled, it was a fin of rock from the tunnel to the top)
                            Intervals.Add((o.y, o.y - ShellReach(o.hit), o.hit));
                            Intervals.Add((Mathf.Min(o.y, e.y + ShellReach(e.hit, 12f)), e.y, e.hit));
                        }
                        else Intervals.Add((o.y, e.y, o.hit));
                    }
                    else
                    {
                        // an underside with no top met above it (a ceiling, open on top): solid up from it to its own top
                        float top = Mathf.Min(prevY, c.bounds.max.y);
                        if (top > e.y + 0.5f) Intervals.Add((top, e.y, e.hit));
                    }
                    prevY = e.y;
                }
                Tunnel(ux, uz);
                float bottomOfWorld = MinY - YOffset;
                foreach (var kv in Open)
                {
                    // tops never left (meshes open underneath):
                    // - the volcano's shell: 5 blocks across its surface (more down the column where it's steep, so its
                    //   near-vertical walls have no gaps);
                    // - a piece with an underside a block or two away (the ray here slipped past it, at its rim): down to
                    //   that (these had been thin pillars down to the ground, under ledges and paths);
                    // - ground: the terrain on down to the world's bottom (like a Minecraft mountain); a rock no lower than
                    //   its own bottom;
                    // - anything else (a crown, a plank): a few blocks
                    var (y, h) = kv.Value;
                    var c = kv.Key;
                    float bottom;
                    if (Shell(c)) bottom = y - ShellReach(h);
                    else
                    {
                        float near = UndersideNear(c, ux, uz, y, yLo);
                        bottom = !float.IsNaN(near) ? near : !IsGround(h) ? y - 3f
                            : c.gameObject.layer == TerrainLayer ? bottomOfWorld : Mathf.Max(bottomOfWorld, c.bounds.min.y - 0.5f);
                        if (!float.IsNaN(near)) RimsMended++;
                    }
                    Intervals.Add((y, bottom, h));
                }
                // the lowest closed piece of ground, when it reaches down near the bottom of the area: on to the world's bottom
                int lowest = -1;
                for (int i = 0; i < Intervals.Count; i++) if (lowest < 0 || Intervals[i].bottom < Intervals[lowest].bottom) lowest = i;
                LowestInterval = lowest >= 0 ? Intervals[lowest] : default;
                if (lowest >= 0)
                {
                    var l = Intervals[lowest];
                    if (l.bottom > bottomOfWorld && (l.top - l.bottom >= 4f && l.bottom <= yLo + 30f || CalderaFloor(l.hit.collider)) && IsGround(l.hit) && !Shell(l.hit.collider))
                        Intervals[lowest] = (l.top, bottomOfWorld, l.hit);
                }
                // painted from the lowest top up, so where pieces overlap the higher one's blocks show; the shapes scanned
                // sideways (the giant redwood's roots) first
                Intervals.Sort((a, b) => a.top.CompareTo(b.top));
                int hi = -1, lo = int.MaxValue;
                if (side != null)
                {
                    int wood = P("spruce_wood"), crown = P("spruce_leaves");
                    foreach (var (sb, st, leafy) in side)
                        for (int y = Math.Max(sb, MinY - YOffset); y <= Math.Min(st, MaxY - YOffset); y++)
                        {
                            int i = y - (MinY - YOffset);
                            Cells[i] = leafy ? crown : wood;
                            if (i > hi) hi = i;
                            if (i < lo) lo = i;
                        }
                }
                foreach (var iv in Intervals) Paint(ux, uz, iv.top, iv.bottom, iv.hit, ref lo, ref hi);
                // a lake of lava (the Caldera's, PEAK's a sheet over nothing): rock under it, down to the world's bottom. Only
                // where its lava is (the Caldera river's collider is a box far wider than its lava, reaching under the
                // volcano: there, under nothing drawn, this had filled a quarter of the volcano solid, from its skin down)
                if (lowest >= 0 && hi >= 0 && BlocksFor(LowestInterval.hit).side == P("lava") && lo > 1 && Cells[lo] == P("lava"))
                {
                    int rock = P("blackstone");
                    for (int i = lo - 1; i >= 1; i--) Cells[i] = rock;
                    lo = 1;
                }
                foreach (var iv in Intervals) if (Shell(iv.hit.collider)) { ShellColumns.Add(((long)Mathf.FloorToInt(ux) << 32) | (uint)Mathf.FloorToInt(uz)); break; }
                {
                    // the volcano's outer skin here: its highest piece
                    int s = -1;
                    for (int i = 0; i < Intervals.Count; i++) if (Shell(Intervals[i].hit.collider) && (s < 0 || Intervals[i].top > Intervals[s].top)) s = i;
                    if (s >= 0)
                        Skins[Key(Mathf.FloorToInt(ux), Mathf.FloorToInt(-uz))] = (Mathf.CeilToInt(Intervals[s].bottom - 0.5f) + YOffset,
                            Mathf.FloorToInt(Intervals[s].top - 0.5f) + YOffset, BlocksFor(Intervals[s].hit).side);
                }
                if (hi >= 0)
                {
                    // where PEAK draws the top green, it's grass; purple, mycelium (with dirt under them)
                    int cx = Mathf.FloorToInt(ux), cz = Mathf.FloorToInt(-uz);
                    string want = TopColours.TopAt(cx, cz);
                    if (want == "snow_block" && Palette[Cells[hi]].Contains("beach_sand")) want = null;   // (the Blue Beach's sand is as pale as snow)
                    if (want != null && Cells[hi] != P(want) && Natural(Cells[hi]))
                    {
                        Cells[hi] = P(want);
                        if (want != "snow_block")   // (snow lies on the rock; grass and mycelium have earth under them)
                            for (int i = hi - 1; i >= Math.Max(lo, hi - 3); i--) if (Cells[i] != 0 && Natural(Cells[i])) Cells[i] = P("dirt");
                    }
                    // as runs (y, length, block) from the top down
                    for (int i = hi; i >= lo;)
                    {
                        int blk = Cells[i], j = i;
                        while (j - 1 >= lo && Cells[j - 1] == blk) j--;
                        if (blk != 0) { runs.Add(i + MinY); runs.Add(i - j + 1); runs.Add(blk); }
                        i = j - 1;
                    }
                    for (int i = lo; i <= hi; i++) Cells[i] = 0;
                }
                if (Events.Count > 0) groundTop = Events[0].y;
            }
            // water standing above the ground (the sea round the island, pools, lakes)
            float waterY = Mathf.Max(waterHit, hazardWater);
            bool hazard = hazardWater >= waterY;
            if (waterY > groundTop && waterY > 0.5f && waterY - groundTop <= (hazard ? 1.5f : 12f))
            {
                int from = Mathf.FloorToInt(groundTop - 0.5f) + 1;
                int to = Mathf.Max(from, Mathf.FloorToInt(waterY - 0.5f));   // (at least a block: the swamp's poison water is a film 0.3 m deep)
                if (to >= from) { runs.Insert(0, to + YOffset); runs.Insert(1, to - from + 1); runs.Insert(2, P("water")); }
            }
            return runs.ToArray();
        }

        static int TerrainLayer => LayerMask.NameToLayer("Terrain");
        internal static int RimsMended;
        static readonly float[] ProbeX = { 1f, -1f, 0f, 0f, 2f, -2f, 0f, 0f }, ProbeZ = { 0f, 0f, 1f, -1f, 0f, 0f, 2f, -2f };
        static readonly List<float> Found = new List<float>();
        static readonly Dictionary<int, (int tries, int found)> UnderTries = new Dictionary<int, (int, int)>();

        /// <summary>
        /// The underside of collider c a block or two from (ux, uz), below top (Unity y), or NaN: rays up from below
        /// (they hit only undersides: PEAK's meshes are one-sided), the middle one of those found.
        /// </summary>
        static float UndersideNear(Collider c, float ux, float uz, float top, float yLo)
        {
            int id = c.GetInstanceID();
            UnderTries.TryGetValue(id, out var st);
            if (st.tries >= 400 && st.found == 0) return float.NaN;   // (one with none anywhere: a terrain sheet, open all over)
            Found.Clear();
            for (int k = 0; k < ProbeX.Length; k++)
                if (c.Raycast(new Ray(new Vector3(ux + ProbeX[k], yLo, uz + ProbeZ[k]), Vector3.up), out var hit, top - yLo) && hit.point.y < top - 0.5f)
                    Found.Add(hit.point.y);
            UnderTries[id] = (st.tries + 1, st.found + (Found.Count > 0 ? 1 : 0));
            if (Found.Count == 0) return float.NaN;
            Found.Sort();
            return Found[Found.Count / 2];
        }

        /// <summary>Natural ground (what PEAK's colours may change): not bricks, wood, glass, metal or the volcano.</summary>
        static bool Natural(int block)
        {
            string b = Palette[block];
            return IsGroundBlock(block) && !b.Contains("brick") && !b.Contains("blackstone") && !b.Contains("iron") && !b.Contains("quartz") && !b.Contains("concrete");
        }

        static readonly Dictionary<int, (float lo, float hi)?> Drawn = new Dictionary<int, (float, float)?>();

        /// <summary>
        /// How high what's drawn of a collider goes, when the collider is much taller (PEAK's swamp fences are 18 m to look
        /// at and invisible walls 200 m tall): only what's drawn becomes blocks.
        /// </summary>
        /// <summary>
        /// PEAK's creatures and their spawners in an area (active or not: some wake as you come): kind, place, which way.
        /// </summary>
        static JArray Creatures(Bounds b, Action<string> log)
        {
            var list = new JArray();
            var counts = new Dictionary<string, int>();
            foreach (var name in new[] { "Scorpion", "Beetle", "Beehive", "BeeSwarm", "Capybara", "Antlion", "MushroomZombie", "MushroomZombieSpawner", "Mandrake", "FrogTongue" })
            {
                var t = HarmonyLib.AccessTools.TypeByName(name);
                if (t == null) continue;
                foreach (var o in UnityEngine.Object.FindObjectsByType(t, FindObjectsInactive.Include, FindObjectsSortMode.None))
                {
                    if (!(o is Component c) || c == null || !b.Contains(c.transform.position)) continue;
                    list.Add(new JObject { ["kind"] = name, ["at"] = J(c.transform.position), ["yaw"] = Math.Round(c.transform.eulerAngles.y), ["path"] = TPath(c.transform) });
                    counts[name] = counts.TryGetValue(name, out int n) ? n + 1 : 1;
                }
            }
            if (counts.Count > 0) log("  creatures: " + string.Join(", ", counts.Select(kv => $"{kv.Key} {kv.Value}")));
            return list;
        }

        /// <summary>PEAK's loot pools (LootData): each pool's items (their names on file and in English) and weights.</summary>
        static JObject LootPools(Action<string> log)
        {
            LootData.PopulateLootData();
            var o = new JObject();
            int n = 0;
            foreach (var kv in LootData.AllSpawnWeightData)
            {
                var items = new JArray();
                foreach (var it in kv.Value)
                    if (ItemDatabase.TryGetItem(it.Key, out var item) && item != null)
                    {
                        string english;
                        try { english = item.GetName(); } catch (Exception) { english = item.name; }
                        items.Add(new JObject { ["item"] = item.name, ["name"] = english, ["weight"] = it.Value });
                    }
                o[kv.Key.ToString()] = items;
                n += items.Count;
            }
            log($"  loot pools: {o.Count}, {n} entries");
            return o;
        }

        /// <summary>PEAK's fog (OrbFogHandler): its spheres (where each closes in to, how big it starts, when it moves on), speed, wait.</summary>
        static JObject FogInfo(Action<string> log)
        {
            var h = UnityEngine.Object.FindAnyObjectByType<OrbFogHandler>(FindObjectsInactive.Include);
            var origins = new JArray();
            var root = h != null ? h.transform.root : null;
            var list = root != null ? root.GetComponentsInChildren<FogSphereOrigin>(true) : UnityEngine.Object.FindObjectsByType<FogSphereOrigin>(FindObjectsInactive.Include, FindObjectsSortMode.None);
            foreach (var f in list)
                origins.Add(new JObject { ["at"] = J(f.transform.position), ["size"] = f.size, ["moveOnHeight"] = f.moveOnHeight + YOffset, ["moveOnForward"] = -f.moveOnForward, ["disabled"] = f.disableFog, ["path"] = TPath(f.transform) });
            log($"  fog: {origins.Count} spheres");
            return new JObject { ["speed"] = h != null ? h.speed : 0.3f, ["maxWait"] = h != null ? h.maxWaitTime : 500f, ["origins"] = origins };
        }

        static JObject CampfireInfo()
        {
            var c = UnityEngine.Object.FindAnyObjectByType<Campfire>(FindObjectsInactive.Include);
            if (c == null) return new JObject();
            return new JObject { ["radius"] = c.moraleBoostRadius, ["boost"] = c.moraleBoostBaseline, ["injury"] = c.injuryReduction, ["burnsFor"] = c.burnsFor };
        }

        static JArray LavaInfo()
        {
            var a = new JArray();
            foreach (var l in UnityEngine.Object.FindObjectsByType<LavaRising>(FindObjectsInactive.Include, FindObjectsSortMode.None))
                a.Add(new JObject { ["segment"] = l.requiredSegment.ToString(), ["start"] = l.heightMinimumTransform != null ? l.heightMinimumTransform.position.y + YOffset : 0,
                    ["top"] = l.topTransform != null ? l.topTransform.position.y + YOffset : 0, ["level"] = l.transform.position.y + YOffset, ["wait"] = l.initialWaitTime, ["travel"] = l.travelTime });
            return a;
        }

        static readonly Dictionary<int, bool> FloorCache = new Dictionary<int, bool>();
        static float? PeakZ;

        /// <summary>
        /// A rock of the summit's hill (past PEAK's last progress point, the peak's, and high up): built as the peak's own
        /// rock (stone, grass on top), not the volcano's black. The grassy summit had sat on a ton of black rock.
        /// </summary>
        static bool Summit(Collider c)
        {
            if (Shell(c)) return false;
            if (PeakZ == null)
            {
                PeakZ = float.MaxValue;
                var mph = Singleton<MountainProgressHandler>.Instance;
                if (mph != null && mph.progressPoints != null)
                    foreach (var p in mph.progressPoints)
                        if (p != null && p.transform != null && p.biome == Biome.BiomeType.Peak) PeakZ = p.transform.position.z;
            }
            var b = c.bounds;
            return b.center.z > PeakZ.Value - 10f && b.max.y + YOffset > 1100f;
        }

        static JArray Falls = new JArray();

        /// <summary>
        /// PEAK's waterfalls under a segment (their renderers: a sheet of falling water, no collider, so the scan never
        /// sees them): each Minecraft column the sheet passes through, from its top to its bottom there, as [x, z, top, bottom].
        /// </summary>
        static void Waterfalls(GameObject parent, Action<string> log)
        {
            int n = 0, cols = 0;
            foreach (var r in parent.GetComponentsInChildren<Renderer>(false))
            {
                var m = r.sharedMaterial;
                if (m == null || !m.name.Contains("Waterfall") || m.name.Contains("Splash")) continue;
                var mf = r.GetComponent<MeshFilter>();
                var mesh = mf != null ? mf.sharedMesh : null;
                var span = new Dictionary<long, (int lo, int hi)>();
                void Add(Vector3 w)
                {
                    var p = ToMc(w);
                    int x = Mathf.FloorToInt(p.x), z = Mathf.FloorToInt(p.z), y = Mathf.FloorToInt(p.y);
                    long k = Key(x, z);
                    span[k] = span.TryGetValue(k, out var s) ? (Math.Min(s.lo, y), Math.Max(s.hi, y)) : (y, y);
                }
                if (mesh != null && mesh.isReadable)
                {
                    var vs = mesh.vertices;
                    var tr = r.transform;
                    // (each triangle's edges sampled every half block, so a sheet with few vertices leaves no gaps)
                    var tris = mesh.triangles;
                    for (int t = 0; t + 2 < tris.Length; t += 3)
                    {
                        Vector3 a = tr.TransformPoint(vs[tris[t]]), b = tr.TransformPoint(vs[tris[t + 1]]), c = tr.TransformPoint(vs[tris[t + 2]]);
                        int steps = Mathf.Clamp(Mathf.CeilToInt(Mathf.Max((a - b).magnitude, (b - c).magnitude, (c - a).magnitude) * 2f), 1, 400);
                        for (int i = 0; i <= steps; i++)
                            for (int j = 0; j <= steps - i; j++)
                            {
                                float u = i / (float)steps, v = j / (float)steps;
                                Add(a + (b - a) * u + (c - a) * v);
                            }
                    }
                }
                else log($"  waterfall {TPath(r.transform)}: its mesh isn't readable, left out");
                foreach (var kv in span)
                {
                    Falls.Add(new JArray((int)(kv.Key >> 32), (int)(uint)(kv.Key & 0xFFFFFFFFL), kv.Value.hi, kv.Value.lo));
                    cols++;
                }
                if (span.Count > 0) n++;
            }
            if (n > 0) log($"  waterfalls: {n}, {cols} columns of falling water");
        }

        /// <summary>
        /// The Caldera's river: a box of lava under its whole field of boulders (and on under the volcano), drawn as a flat
        /// sheet of lava between them. In MinePeak it's the field's floor: lava on top, as PEAK's, on rock down to the
        /// world's bottom (cut to its flat sheet, there had been nothing under the boulders but holes to the void).
        /// </summary>
        static bool CalderaFloor(Collider c)
        {
            if (c == null) return false;
            int id = c.GetInstanceID();
            if (!FloorCache.TryGetValue(id, out bool f)) FloorCache[id] = f = c is BoxCollider && TPath(c.transform).Contains("Caldera_Segment/River");
            return f;
        }

        static (float lo, float hi)? DrawnOf(Collider c)
        {
            int id = c.GetInstanceID();
            if (Drawn.TryGetValue(id, out var d)) return d;
            d = null;
            if (CalderaFloor(c)) { Drawn[id] = null; return null; }
            var r = c.GetComponent<Renderer>() ?? c.GetComponentInParent<Renderer>() ?? c.GetComponentInChildren<Renderer>();
            if (r == null && c.transform.parent != null)
                foreach (var nr in c.transform.parent.GetComponentsInChildren<Renderer>()) if (!(nr is ParticleSystemRenderer)) { r = nr; break; }
            if (r != null && !(r is ParticleSystemRenderer))
            {
                float rh = r.bounds.size.y, ch = c.bounds.size.y;
                if (ch > rh * 2f + 6f) d = (r.bounds.min.y - 0.5f, r.bounds.max.y + 0.5f);
            }
            Drawn[id] = d;
            return d;
        }

        /// <summary>
        /// A tunnel through the volcano's skin in this column (its floor a top of the skin, its roof an underside of it just
        /// above): its air, and 2 blocks of wall behind its floor and roof, sideways (away from the way they face: the
        /// tunnel's round sides). Nothing along it, so its ends stay open.
        /// </summary>
        static void Tunnel(float ux, float uz)
        {
            int roof = -1;
            for (int i = 0; i < Events.Count; i++)
            {
                var e = Events[i];
                if (!Shell(e.hit.collider)) continue;
                if (!e.top) { roof = i; continue; }
                if (roof < 0) continue;
                var r = Events[roof];
                roof = -1;
                if (r.y - e.y > 30f || r.y - e.y < 1f) continue;
                int lo = Mathf.FloorToInt(e.y) + YOffset, hi = Mathf.FloorToInt(r.y) + YOffset;
                int mx = Mathf.FloorToInt(ux), mz = Mathf.FloorToInt(-uz);
                if (!TunnelAir.TryGetValue(Key(mx, mz), out var air)) TunnelAir[Key(mx, mz)] = air = new List<(int, int)>();
                air.Add((lo + 1, hi - 1));
                var away = -new Vector2(e.hit.normal.x + r.hit.normal.x, e.hit.normal.z + r.hit.normal.z);
                if (away.magnitude < 0.3f) continue;   // (the middle of the tunnel: floor and roof flat)
                away.Normalize();
                var back = new Vector2(away.x, -away.y);   // (in Minecraft's x, z)
                int blk = BlocksFor(e.hit).side;
                for (int dz = -2; dz <= 2; dz++)
                    for (int dx = -2; dx <= 2; dx++)
                    {
                        var d = new Vector2(dx, dz);
                        if (d.sqrMagnitude == 0 || d.magnitude > 2.6f || Vector2.Dot(d.normalized, back) < 0.35f) continue;
                        long key = Key(mx + dx, mz + dz);
                        if (!TunnelSides.TryGetValue(key, out var l)) TunnelSides[key] = l = new List<(int, int, int)>();
                        l.Add((lo - 1, hi + 1, blk));
                    }
            }
        }

        /// <summary>The Kiln tunnel's side walls into an area's columns (only where air, and not a tunnel's own air).</summary>
        static int TunnelWalls(Area a)
        {
            int n = 0;
            foreach (var kv in TunnelSides)
            {
                int ix = (int)(kv.Key >> 32) - a.X0, iz = (int)(uint)(kv.Key & 0xFFFFFFFFL) - a.Z0;
                if (ix < 0 || iz < 0 || ix >= a.W || iz >= a.D) continue;
                TunnelAir.TryGetValue(kv.Key, out var keep);
                var runs = a.Cols[iz * a.W + ix];
                foreach (var (lo, hi, blk) in kv.Value)
                {
                    // (less the tunnel's own air in this column)
                    var parts = new List<(int lo, int hi)> { (lo, hi) };
                    if (keep != null)
                        foreach (var (klo, khi) in keep)
                        {
                            var next = new List<(int, int)>();
                            foreach (var (plo, phi) in parts)
                            {
                                if (khi < plo || klo > phi) { next.Add((plo, phi)); continue; }
                                if (plo < klo) next.Add((plo, klo - 1));
                                if (phi > khi) next.Add((khi + 1, phi));
                            }
                            parts = next;
                        }
                    foreach (var (plo, phi) in parts)
                    {
                        var filled = FillAir(runs, plo, phi, blk);
                        if (filled != runs) { runs = filled; n++; }
                    }
                }
                a.Cols[iz * a.W + ix] = runs;
            }
            return n;
        }

        /// <summary>
        /// The volcano's skin made whole. PEAK's is a one-sided surface; each column's piece of it is a few blocks thick
        /// where rays met it, and where it's steep, or a ray missed it, there were holes through it into the hollow
        /// volcano. Now each column's skin goes down at least to the top of its lowest neighbour's (no gap between them,
        /// side by side), and a column with none, where 3 or 4 of its neighbours have one, gets one.
        /// </summary>
        static (int closed, int plugged) SkinClosure(Area a)
        {
            int closed = 0, plugged = 0;
            int[] dx = { -1, 1, 0, 0 }, dz = { 0, 0, -1, 1 };
            var fills = new List<(int at, int lo, int hi, int blk)>();
            for (int iz = 0; iz < a.D; iz++)
                for (int ix = 0; ix < a.W; ix++)
                {
                    int x = a.X0 + ix, z = a.Z0 + iz;
                    bool has = Skins.TryGetValue(Key(x, z), out var me);
                    int lowest = int.MaxValue, highest = int.MinValue, n = 0, blk = 0;
                    for (int k = 0; k < 4; k++)
                        if (Skins.TryGetValue(Key(x + dx[k], z + dz[k]), out var nb)) { n++; lowest = Math.Min(lowest, nb.top); highest = Math.Max(highest, nb.top); blk = nb.block; }
                    if (has)
                    {
                        // (a gap of more than 20 down to a neighbour isn't a slit between two pieces of a steep skin but an
                        // opening under an edge: the tunnel's mouth was closed by a curtain from its arch)
                        if (n > 0 && lowest < me.bottom && me.bottom - lowest <= 20) fills.Add((iz * a.W + ix, lowest, me.bottom - 1, me.block));
                    }
                    else if (n >= 3)
                        fills.Add((iz * a.W + ix, lowest - (int)ShellThickness, (lowest + highest) / 2, blk));
                }
            foreach (var (at, lo, hi, blk) in fills)
            {
                // (never in a tunnel's own air)
                if (TunnelAir.TryGetValue(Key(a.X0 + at % a.W, a.Z0 + at / a.W), out var air) && air.Any(t => t.lo <= hi && t.hi >= lo)) continue;
                var runs = FillAir(a.Cols[at], lo, hi, blk);
                if (runs == a.Cols[at]) continue;
                a.Cols[at] = runs;
                if (hi - lo < 64 && !Skins.ContainsKey(Key(a.X0 + at % a.W, a.Z0 + at / a.W))) plugged++; else closed++;
            }
            return (closed, plugged);
        }

        /// <summary>A column's runs (top y, length, block; from the top down) with the air from lo to hi filled with block.</summary>
        static int[] FillAir(int[] runs, int lo, int hi, int block)
        {
            if (hi < lo) return runs;
            var all = new List<(int top, int bottom, int blk)>();
            if (runs != null) for (int i = 0; i + 2 < runs.Length; i += 3) all.Add((runs[i], runs[i] - runs[i + 1] + 1, runs[i + 2]));
            all.Sort((p, q) => q.top.CompareTo(p.top));
            var add = new List<(int top, int bottom, int blk)>();
            int y = hi;
            foreach (var s in all)
            {
                if (s.bottom > y) continue;
                if (s.top < lo) break;
                if (s.top < y) add.Add((y, Math.Max(s.top + 1, lo), block));
                y = Math.Min(y, s.bottom - 1);
                if (y < lo) break;
            }
            if (y >= lo) add.Add((y, lo, block));
            if (add.Count == 0) return runs;
            all.AddRange(add);
            all.Sort((p, q) => q.top.CompareTo(p.top));
            var r = new int[all.Count * 3];
            for (int i = 0; i < all.Count; i++) { r[i * 3] = all[i].top; r[i * 3 + 1] = all[i].top - all[i].bottom + 1; r[i * 3 + 2] = all[i].blk; }
            return r;
        }

        /// <summary>One piece's blocks into the column: its top block, three under it, then its side block.</summary>
        static void Paint(float ux, float uz, float top, float bottom, RaycastHit hit, ref int lo, ref int hi)
        {
            var drawn = DrawnOf(hit.collider);
            if (drawn != null) { top = Mathf.Min(top, drawn.Value.hi); bottom = Mathf.Max(bottom, drawn.Value.lo); if (top <= bottom) return; }
            int c1 = Mathf.FloorToInt(top - 0.5f), c0 = Mathf.CeilToInt(bottom - 0.5f);
            if (c0 > c1) c0 = c1 = Mathf.FloorToInt(top - 0.01f);   // (thin things still get a block)
            c1 = Mathf.Min(c1, MaxY - YOffset); c0 = Mathf.Max(c0, MinY - YOffset);
            if (c0 > c1) return;
            var b = BlocksFor(hit);
            bool flat = hit.normal.y >= 0.7f;
            int top0 = !flat ? b.side : b.alt && TopAltAt(ux, uz) ? P("magma_block") : b.top;
            int i1 = c1 - (MinY - YOffset), i0 = c0 - (MinY - YOffset);
            for (int i = i1; i >= i0; i--)
                Cells[i] = i == i1 ? top0 : i >= i1 - 3 ? (flat ? b.sub : b.side) : b.side;
            if (i1 > hi) hi = i1;
            if (i0 < lo) lo = i0;
        }

        internal sealed class Area { public string Name; public int X0, Z0, W, D; public int[][] Cols; }

        internal static int ColumnErrors;
        const int Slab = 16;

        /// <summary>
        /// The check on the batched scan: some columns scanned again the old way (one ray at a time from here), their
        /// blocks compared with the scan's. Returns how many of them differ.
        /// </summary>
        static int CheckScan(Area a, Bounds box, int mask, int samples)
        {
            float yLo = box.min.y - 2f, yHi = box.max.y + 5f;
            var rnd = new System.Random(12345);
            int differ = 0;
            for (int s = 0; s < samples; s++)
            {
                int ix = rnd.Next(a.W), iz = rnd.Next(a.D);
                float ux = a.X0 + ix + 0.5f, uz = -(a.Z0 + iz + 0.5f);
                Events.Clear();
                var p = new Vector3(ux, yHi, uz);
                float hazardWater = float.NegativeInfinity;
                for (int k = 0; k < MaxHits; k++)
                {
                    if (!Physics.Raycast(p, Vector3.down, out var h, p.y - yLo, mask, QueryTriggerInteraction.Ignore)) break;
                    if (IsWaterHazard(h.collider)) { hazardWater = Mathf.Max(hazardWater, h.point.y); p.y = h.point.y - 0.02f; continue; }
                    if (!Invisible(h) && !Skipped(h.collider)) Events.Add((h.point.y, true, h));
                    p.y = h.point.y - 0.02f;
                }
                p.y = yLo;
                for (int k = 0; k < MaxHits; k++)
                {
                    if (!Physics.Raycast(p, Vector3.up, out var h, yHi - p.y, mask, QueryTriggerInteraction.Ignore)) break;
                    if (!IsWaterHazard(h.collider) && !Invisible(h) && !Skipped(h.collider)) Events.Add((h.point.y, false, h));
                    p.y = h.point.y + 0.02f;
                }
                float waterY = float.NegativeInfinity;
                if (Physics.Raycast(new Vector3(ux, yHi, uz), Vector3.down, out var w, yHi - yLo, 1 << WaterLayer, QueryTriggerInteraction.Collide)) waterY = w.point.y;
                int[] again;
                try { again = Column(ux, uz, yLo, yHi, hazardWater, waterY); } catch (Exception) { again = new int[0]; }
                var had = a.Cols[iz * a.W + ix] ?? new int[0];
                if (!had.SequenceEqual(again)) differ++;
            }
            return differ;
        }

        /// <summary>
        /// Scans the columns over a box of the scene (Unity coordinates), a slab of rows a frame. Each column's rays go
        /// down it hit by hit (each from just past the last), then up it; they're cast in batches over all the CPU's cores
        /// (RaycastCommand): every column of the slab still going gets its next ray in the same batch. (One ray at a time
        /// from the main thread, as before, took 3 minutes an island.) A column that fails is left empty and noted.
        /// </summary>
        static IEnumerator Scan(Area a, Bounds box, Action<string> log, int mask)
        {
            a.X0 = Mathf.FloorToInt(box.min.x); a.W = Mathf.CeilToInt(box.max.x) - a.X0;
            a.Z0 = Mathf.FloorToInt(-box.max.z); a.D = Mathf.CeilToInt(-box.min.z) - a.Z0;
            a.Cols = new int[a.W * a.D][];
            int W = a.W, cap = Slab * W;
            float yLo = box.min.y - 2f, yHi = box.max.y + 5f;
            var solid = new QueryParameters(mask, false, QueryTriggerInteraction.Ignore, false);
            var waterQ = new QueryParameters(1 << WaterLayer, false, QueryTriggerInteraction.Collide, false);
            var events = new List<(float y, bool top, RaycastHit hit)>[cap];
            for (int k = 0; k < cap; k++) events[k] = new List<(float, bool, RaycastHit)>();
            var py = new float[cap]; var count = new int[cap]; var hazard = new float[cap]; var waterY = new float[cap];
            var live = new List<int>(cap);
            var cmds = new NativeArray<RaycastCommand>(cap, Allocator.Persistent);
            var res = new NativeArray<RaycastHit>(cap, Allocator.Persistent);
            var t0 = Time.realtimeSinceStartup;
            int next = 10;
            try
            {
                for (int z0 = 0; z0 < a.D; z0 += Slab)
                {
                    int n = Math.Min(Slab, a.D - z0) * W;
                    float X(int k) => a.X0 + k % W + 0.5f;
                    float Z(int k) => -(a.Z0 + z0 + k / W + 0.5f);
                    for (int k = 0; k < n; k++) { events[k].Clear(); hazard[k] = float.NegativeInfinity; }
                    for (int pass = 0; pass < 2; pass++)
                    {
                        bool down = pass == 0;
                        live.Clear();
                        for (int k = 0; k < n; k++) { live.Add(k); py[k] = down ? yHi : yLo; count[k] = 0; }
                        while (live.Count > 0)
                        {
                            int m = live.Count;
                            for (int j = 0; j < m; j++)
                            {
                                int k = live[j];
                                cmds[j] = new RaycastCommand(new Vector3(X(k), py[k], Z(k)), down ? Vector3.down : Vector3.up, solid, down ? py[k] - yLo : yHi - py[k]);
                            }
                            RaycastCommand.ScheduleBatch(cmds.GetSubArray(0, m), res.GetSubArray(0, m), 32, 1).Complete();
                            int kept = 0;
                            for (int j = 0; j < m; j++)
                            {
                                int k = live[j];
                                var h = res[j];
                                var c = h.collider;
                                if (c == null) continue;   // (nothing more along it)
                                count[k]++;
                                if (down)
                                {
                                    if (IsWaterHazard(c)) hazard[k] = Mathf.Max(hazard[k], h.point.y);
                                    else if (!Invisible(h) && !Skipped(c))
                                    {
                                        var ev = events[k];
                                        if (ev.Count == 0 || !ev[ev.Count - 1].top) Count(c);
                                        ev.Add((h.point.y, true, h));
                                    }
                                    py[k] = h.point.y - 0.02f;
                                    if (count[k] < MaxHits && py[k] > yLo) live[kept++] = k;
                                }
                                else
                                {
                                    if (!IsWaterHazard(c) && !Invisible(h) && !Skipped(c)) events[k].Add((h.point.y, false, h));
                                    py[k] = h.point.y + 0.02f;
                                    if (count[k] < MaxHits && py[k] < yHi) live[kept++] = k;
                                }
                            }
                            live.RemoveRange(kept, live.Count - kept);
                        }
                    }
                    // the water's top over each column (the sea, pools)
                    for (int k = 0; k < n; k++) cmds[k] = new RaycastCommand(new Vector3(X(k), yHi, Z(k)), Vector3.down, waterQ, yHi - yLo);
                    RaycastCommand.ScheduleBatch(cmds.GetSubArray(0, n), res.GetSubArray(0, n), 32, 1).Complete();
                    for (int k = 0; k < n; k++) waterY[k] = res[k].collider != null ? res[k].point.y : float.NegativeInfinity;
                    // each column's blocks
                    for (int k = 0; k < n; k++)
                    {
                        Events.Clear();
                        Events.AddRange(events[k]);
                        int at = (z0 + k / W) * W + k % W;
                        try { a.Cols[at] = Column(X(k), Z(k), yLo, yHi, hazard[k], waterY[k]); }
                        catch (Exception e)
                        {
                            a.Cols[at] = new int[0];
                            if (ColumnErrors++ < 3) log($"  column {X(k):F0}, {-Z(k):F0} failed (left empty): {e}");
                        }
                    }
                    yield return null;
                    int iz = z0 + Slab;
                    while (iz * 100 / a.D >= next && next < 100) { log($"  {a.Name}: {next}% ({Time.realtimeSinceStartup - t0:F0} s)"); Status.Area(a.Name, next); next += 10; }
                }
            }
            finally { cmds.Dispose(); res.Dispose(); }
            if (ColumnErrors > 0) log($"  {ColumnErrors} columns failed so far (left empty)");
        }

        internal static void Write(Area a, string dir)
        {
            using (var f = File.Create(Path.Combine(dir, a.Name + ".bin")))
            {
                var hdr = new BinaryWriter(f);
                hdr.Write(new[] { (byte)'M', (byte)'P', (byte)'K', (byte)'1' });
                hdr.Write(a.X0); hdr.Write(a.Z0); hdr.Write(a.W); hdr.Write(a.D);
                hdr.Flush();
                using (var gz = new GZipStream(f, System.IO.Compression.CompressionLevel.Optimal, true))
                using (var bw = new BinaryWriter(gz))
                {
                    void V(int v) { uint u = (uint)v; while (u >= 0x80) { bw.Write((byte)(u | 0x80)); u >>= 7; } bw.Write((byte)u); }
                    foreach (var col in a.Cols)
                    {
                        int n = col == null ? 0 : col.Length / 3;
                        V(n);
                        for (int i = 0; i < n; i++) { V(col[i * 3] - MinY); V(col[i * 3 + 1]); V(col[i * 3 + 2]); }
                    }
                }
            }
        }

        static string BoxWhy = "";
        static readonly HashSet<int> PlantsSeen = new HashSet<int>();

        /// <summary>PEAK's day: its island changes every 24 hours at 17:00 UTC (NextLevelService), so this is the UTC date
        /// the current island came in on. MinePeak (in Minecraft) checks for an export of today with the same date.</summary>
        public static string PeakDay => DateTime.UtcNow.AddHours(-17).ToString("yyyy-MM-dd");

        /// <summary>The box round a set of colliders (on the layers that are solid), or null.</summary>
        static Bounds? BoxOf(IEnumerable<Collider> cols, int mask)
        {
            Bounds? b = null;
            int off = 0, trig = 0, layer = 0, huge = 0, used = 0;
            foreach (var c in cols)
            {
                if (c == null || !c.enabled || !c.gameObject.activeInHierarchy) { off++; continue; }
                if (c.isTrigger) { trig++; continue; }
                if (((1 << c.gameObject.layer) & mask) == 0) { layer++; continue; }
                var cb = c.bounds;
                if (cb.size.sqrMagnitude > 4e6f) { huge++; continue; }   // (huge helper volumes)
                used++;
                if (b == null) b = cb; else { var x = b.Value; x.Encapsulate(cb); b = x; }
            }
            BoxWhy = $"{used} used, {off} off, {trig} triggers, {layer} on other layers, {huge} huge";
            return b;
        }

        /// <summary>
        /// Exports each segment of the island; Finish writes island.json.
        /// </summary>
        public sealed class Job
        {
            public string Dir;
            public readonly JObject Info = new JObject();
            public readonly JArray Areas = new JArray();
            public float Seconds;

            string _scene = "island";

            /// <summary>Starts an export (in a folder of its own until the island is known).</summary>
            public Job()
            {
                Palette.Clear(); PaletteIndex.Clear(); Cache.Clear(); InvisibleCache.Clear(); Invisibles.Clear(); TopCount.Clear(); HazardCache.Clear(); Ground.Clear();
                SkipCache.Clear(); Plants.Clear(); TopColours.Clear(); PlantsSeen.Clear(); Drawn.Clear();
                Palette.Add("minecraft:air"); PaletteIndex["minecraft:air"] = 0;
                RimsMended = 0; UnderTries.Clear(); ColumnErrors = 0; Falls = new JArray(); FloorCache.Clear(); PeakZ = null;
                Dir = Path.Combine(Root, "exporting_" + DateTime.Now.ToString("yyyyMMdd_HHmmss"));
                Directory.CreateDirectory(Dir);
                Info["format"] = 3;
                Info["date"] = PeakDay;
                Info["yOffset"] = YOffset; Info["minY"] = MinY; Info["maxY"] = MaxY; Info["seaLevel"] = YOffset;
            }

            /// <summary>The island of the day has loaded (its scene, e.g. Level_14).</summary>
            public void Island(string scene) { _scene = scene; Info["scene"] = scene; }

            public IEnumerator Segment(int index, Action<string> log)
            {
                FindWalls();
                var mh = Singleton<MapHandler>.Instance;
                var seg = mh.segments[index];
                var parent = seg.segmentParent;
                string name = ((global::Segment)index).ToString();
                if (parent == null) { log($"{name}: no segment parent"); yield break; }
                var all = parent.GetComponentsInChildren<Collider>(false);
                var layers = new Dictionary<string, int>();
                foreach (var c in all) if (!c.isTrigger) { string ln = LayerMask.LayerToName(c.gameObject.layer); layers[ln] = layers.TryGetValue(ln, out int k) ? k + 1 : 1; }
                log($"{name} colliders by layer: " + string.Join(", ", layers.Select(kv => $"{kv.Key} {kv.Value}")));
                var box = BoxOf(all, SolidMask);
                if (box == null) { log($"{name}: nothing solid ({BoxWhy})"); yield break; }
                var b = box.Value;
                if (name == "TheKiln")
                    foreach (var vc in UnityEngine.Object.FindObjectsByType<Collider>(FindObjectsSortMode.None))
                        if (Shell(vc)) { b.Encapsulate(vc.bounds); log($"{name}: the volcano's shell {vc.bounds.min:F0}..{vc.bounds.max:F0} in its area"); }
                b.Expand(new Vector3(8f, 4f, 8f));
                log($"{name}: box {b.min:F0}..{b.max:F0}");
                var a = new Area { Name = name.ToLowerInvariant() };
                var t = Time.realtimeSinceStartup;
                // its colours from above first (where PEAK's tops are green), then the scan
                int ax0 = Mathf.FloorToInt(b.min.x), az0 = Mathf.FloorToInt(-b.max.z);
                // (not the Kiln's: no grass or snow in a volcano, and its summit's rock had come out green)
                TopColours.SnowHere = name == "Alpine";
                TopColours.Take(ax0, az0, Mathf.CeilToInt(b.max.x) - ax0, Mathf.CeilToInt(-b.min.z) - az0, b.min.y, b.max.y, log);
                yield return null;
                LavaTop = float.NaN;
                foreach (var lr in UnityEngine.Object.FindObjectsByType<LavaRising>(FindObjectsSortMode.None))
                    foreach (var lc in lr.GetComponentsInChildren<Collider>()) LavaTop = float.IsNaN(LavaTop) ? lc.bounds.max.y : Mathf.Max(LavaTop, lc.bounds.max.y);
                Sideways.Prepare(b, log);
                ShellColumns.Clear(); TunnelSides.Clear(); TunnelAir.Clear(); Skins.Clear();
                yield return Scan(a, b, log, SolidMask);
                float tc = Time.realtimeSinceStartup;
                int differ = CheckScan(a, b, SolidMask, 500);
                log($"  scan check: {differ} of 500 columns differ from one ray at a time ({Time.realtimeSinceStartup - tc:F1} s)");
                // under the volcano: only in the area that holds all of it (Level_20's Caldera holds half of it, and its cut
                // looked like the shell's rim: a wall of rock across a quarter of the Kiln, to the top)
                bool wholeVolcano = true;
                foreach (var vc in UnityEngine.Object.FindObjectsByType<Collider>(FindObjectsSortMode.None))
                    if (Shell(vc) && (vc.bounds.min.x < b.min.x || vc.bounds.max.x > b.max.x || vc.bounds.min.z < b.min.z || vc.bounds.max.z > b.max.z)) wholeVolcano = false;
                int tunnel = TunnelWalls(a);
                if (tunnel > 0) log($"  the Kiln tunnel: {tunnel} pieces of its side walls ({TunnelAir.Count} columns of tunnel)");
                int floors = wholeVolcano ? VolcanoFloor(a) : 0;
                if (!wholeVolcano && ShellColumns.Count > 0) log("  part of the volcano: nothing filled under it here (its own area does that)");
                if (floors > 0) log($"  under the volcano: {floors} columns (its lava floor, rock under its shell)");
                TopColours.Clear();
                Sideways.Clear();
                var (pillars, gaps) = Clean.Area(a, IsGroundBlock, blk => Palette[blk].Contains("water") || Palette[blk].Contains("lava"));
                // after the clean-up (it thinned the skin where steep): the tunnel's walls again, and the skin whole
                TunnelWalls(a);
                var (closed, plugged) = SkinClosure(a);
                if (closed + plugged > 0) log($"  the volcano's skin: {closed} columns of it reach down to their neighbours', {plugged} holes in it filled");
                log($"  clean-up: {pillars} lone rock columns cut, {gaps} gaps in rock filled, {Clean.Holes} holes filled{(float.IsNaN(LavaTop) ? "" : $"; lava at y {LavaTop + YOffset:F0}")}");
                Seconds += Time.realtimeSinceStartup - t;
                // the campfire (the volcano's: its spawner at the entrance; PEAK's segmentCampfire is at the origin there);
                // MinePeak opens it to the sky as it builds (IslandData.clearCampfires: the areas overlap)
                var fire = seg.segmentCampfire != null ? seg.segmentCampfire.transform.position : Vector3.zero;
                if (fire.sqrMagnitude < 1f)
                    foreach (var ct in parent.GetComponentsInChildren<Transform>(true))
                        if (ct.name.StartsWith("CampfireSpawner")) { fire = ct.position; break; }
                Write(a, Dir);
                var o = AreaInfo(a);
                o["segment"] = name;
                o["biome"] = seg.biome.ToString();
                if (fire.sqrMagnitude >= 1f) o["campfire"] = J(fire);
                // PEAK's luggage (each with its kind: its class and object, e.g. Luggage, LuggageCursed, "LuggageAncient";
                // and which way it faces) and its creatures and their spawners, for MinePeak's chests and mobs
                var luggage = new JArray();
                foreach (var l in UnityEngine.Object.FindObjectsByType<Luggage>(FindObjectsSortMode.None))
                    if (b.Contains(l.transform.position))
                        luggage.Add(new JObject { ["at"] = J(l.transform.position), ["kind"] = l.GetType().Name + "/" + l.gameObject.name, ["yaw"] = Math.Round(l.transform.eulerAngles.y),
                            ["pool"] = l.spawnPool.ToString(), ["count"] = l.spawnSpots != null && l.spawnSpots.Count > 0 ? l.spawnSpots.Count : 3 });
                o["luggage"] = luggage;
                o["creatures"] = Creatures(b, log);
                o["plants"] = Plants.Collect(b, PlantsSeen, log);
                try { o["spawners"] = WorldThings.Spawners(b, log); } catch (Exception e) { log("  spawners: " + e.Message); }
                try { o["hazards"] = WorldThings.Hazards(b, log); } catch (Exception e) { log("  hazards: " + e.Message); }
                Waterfalls(parent, log);
                if (index == 0)
                {
                    var spawns = new JArray();
                    foreach (var sp in SpawnPoint.allSpawnPoints) if (sp != null) spawns.Add(J(sp.transform.position));
                    o["spawns"] = spawns;
                }
                Areas.Add(o);
                log($"{name}: {a.W} x {a.D} columns, {Time.realtimeSinceStartup - t:F0} s, {Palette.Count} blocks so far");
            }

            /// <summary>
            /// PEAK's invisible walls round the island (Misc/EdgeWalls): each one's footprint (four corners, Minecraft x, z)
            /// and heights, for MinePeak to stand barriers on. Every collider on the InvisWall layer goes into the log.
            /// </summary>
            static JArray Walls(Action<string> log)
            {
                var walls = new JArray();
                int invis = LayerMask.NameToLayer("InvisWall");
                foreach (var c in UnityEngine.Object.FindObjectsByType<Collider>(FindObjectsSortMode.None))
                {
                    if (c == null || !c.enabled || !c.gameObject.activeInHierarchy || c.isTrigger) continue;
                    string path = TPath(c.transform);
                    bool edge = path.IndexOf("EdgeWall", StringComparison.OrdinalIgnoreCase) >= 0;
                    if (c.gameObject.layer == invis || edge) log($"  invisible wall: {path} [{LayerMask.LayerToName(c.gameObject.layer)}] {c.GetType().Name} {c.bounds.min:F0}..{c.bounds.max:F0}");
                    if (!edge) continue;
                    var corners = new JArray();
                    if (c is BoxCollider bc)
                        foreach (var (sx, sz) in new[] { (-1, -1), (1, -1), (1, 1), (-1, 1) })
                        {
                            var p = c.transform.TransformPoint(bc.center + Vector3.Scale(bc.size * 0.5f, new Vector3(sx, -1, sz)));
                            corners.Add(new JArray(Math.Round(p.x, 1), Math.Round(-p.z, 1)));
                        }
                    else
                        foreach (var (px, pz) in new[] { (c.bounds.min.x, c.bounds.min.z), (c.bounds.max.x, c.bounds.min.z), (c.bounds.max.x, c.bounds.max.z), (c.bounds.min.x, c.bounds.max.z) })
                            corners.Add(new JArray(Math.Round(px, 1), Math.Round(-pz, 1)));
                    walls.Add(new JObject { ["path"] = path, ["corners"] = corners, ["y0"] = Math.Round(c.bounds.min.y + YOffset), ["y1"] = Math.Round(c.bounds.max.y + YOffset) });
                }
                return walls;
            }

            /// <summary>
            /// PEAK's progress points (MountainProgressHandler): walking past one up the mountain (its z) shows its title card,
            /// if its biome is on today's island (or it's the peak's). With their titles in English, for MinePeak's cards.
            /// </summary>
            static JArray Progress(Action<string> log)
            {
                var list = new JArray();
                var mph = Singleton<MountainProgressHandler>.Instance;
                var mh = Singleton<MapHandler>.Instance;
                if (mph == null || mph.progressPoints == null) { log("  no progress points"); return list; }
                foreach (var p in mph.progressPoints)
                {
                    if (p == null || p.transform == null) continue;
                    bool present = p.biome == Biome.BiomeType.Peak || (mh != null && mh.BiomeIsPresent(p.biome));
                    string text;
                    try { text = LocalizedText.GetText(p.title, false); } catch (Exception) { text = p.title; }
                    list.Add(new JObject { ["title"] = p.title, ["text"] = text, ["biome"] = p.biome.ToString(), ["present"] = present, ["at"] = J(p.transform.position) });
                    log($"  progress point {p.title} ({p.biome}{(present ? "" : ", not on this island")}) at {ToMc(p.transform.position):F0}: \"{text}\"");
                }
                return list;
            }

            /// <summary>The English of PEAK's words MinePeak shows (the title card's day line, the rising warnings, the campfire's).</summary>
            static JObject Texts()
            {
                var o = new JObject();
                foreach (var k in new[] { "DAY", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "THEFOGRISES", "THELAVARISES",
                    "THEGLOOMRISES", "THEGHOSTSRISE", "MORALEBOOST", "LIGHT", "COOK", "CAMPFIRE", "LOADING" })
                    try { o[k] = LocalizedText.GetText(k, false); } catch (Exception) { }
                return o;
            }

            static JObject AreaInfo(Area a) => new JObject { ["file"] = a.Name + ".bin", ["x0"] = a.X0, ["z0"] = a.Z0, ["width"] = a.W, ["depth"] = a.D };

            public void Finish(Action<string> log)
            {
                Info["areas"] = Areas;
                Info["invisibleColliders"] = new JArray(Invisibles.Take(200).ToArray());
                var top = TopCount.Where(kv => kv.Key != null).OrderByDescending(kv => kv.Value).Take(40)
                    .Select(kv => $"{kv.Value} columns: {TPath(kv.Key.transform)} [{LayerMask.LayerToName(kv.Key.gameObject.layer)}] size {kv.Key.bounds.size:F0}").ToArray();
                Info["topColliders"] = new JArray(top);
                foreach (var t in top.Take(12)) log("  top: " + t);
                Info["palette"] = new JArray(Palette.ToArray());
                Info["walls"] = Walls(log);
                try { Info["lootPools"] = LootPools(log); } catch (Exception e) { log("  loot pools: " + e.Message); }
                try { Info["fog"] = FogInfo(log); } catch (Exception e) { log("  fog: " + e.Message); }
                try { Info["campfire"] = CampfireInfo(); } catch (Exception e) { log("  campfire: " + e.Message); }
                try { Info["lava"] = LavaInfo(); } catch (Exception e) { log("  lava: " + e.Message); }
                var dn = UnityEngine.Object.FindAnyObjectByType<DayNightManager>();
                if (dn != null)
                {
                    Info["time"] = new JObject { ["dayLengthInMinutes"] = dn.dayLengthInMinutes, ["startingTimeOfDay"] = dn.startingTimeOfDay, ["dayStart"] = dn.dayStart, ["dayEnd"] = dn.dayEnd };
                    log($"  PEAK's day: {dn.dayLengthInMinutes} minutes, starting at {dn.startingTimeOfDay}, light from {dn.dayStart} to {dn.dayEnd}");
                }
                Info["waterfalls"] = Falls;
                Info["progress"] = Progress(log);
                Info["texts"] = Texts();
                log($"  {RimsMended} rock rims mended (open pieces given the underside next to them)");
                File.WriteAllText(Path.Combine(Dir, "island.json"), Info.ToString());
                // its name: the day and the island (a new export of the same island replaces the old one)
                string final = Path.Combine(Root, PeakDay + "_" + _scene);
                // (Windows can refuse to delete the old one for a moment: try a few times, then move it aside instead)
                for (int tries = 0; Directory.Exists(final) && tries < 5; tries++)
                {
                    try { Directory.Delete(final, true); }
                    catch (Exception e) { log($"  couldn't delete the old {final} ({e.Message}), trying again"); System.Threading.Thread.Sleep(1000); }
                }
                if (Directory.Exists(final))
                {
                    string aside = Path.Combine(Path.GetDirectoryName(Root), "moved_aside", Path.GetFileName(final) + "_" + DateTime.Now.ToString("HHmmss"));
                    Directory.CreateDirectory(Path.GetDirectoryName(aside));
                    Directory.Move(final, aside);
                    log($"  the old export moved aside to {aside}");
                }
                Directory.Move(Dir, final);
                Dir = final;
                Status.Set("done", "done", 100, "Today's PEAK island is ready", Dir);
                log($"island written to {Dir} ({Seconds:F0} s of scanning, {Palette.Count} kinds of block)");
            }
        }
    }
}
