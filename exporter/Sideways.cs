using System;
using System.Collections.Generic;
using UnityEngine;

namespace MinePeakExporter
{
    /// <summary>
    /// Shapes the up-and-down scan can't see: walls near vertical all round and open at the top and bottom, which rays
    /// going straight down slide past (the giant redwood's root pillar, `Redwood Massive/Redwood/Roots`, ~190 m across).
    /// Scanned sideways instead: at each height, along every row and every column across it, from either side to its
    /// outer walls; a block is inside where it's between the walls both ways.
    /// </summary>
    internal static class Sideways
    {
        static readonly Dictionary<long, List<(int b, int t, bool leaves)>> Fill = new Dictionary<long, List<(int, int, bool)>>();
        /// <summary>How thick the crown's shell of leaves is (inside it, air: a solid crown 250 m across was too many blocks).</summary>
        const int CrownShell = 4;

        static long Key(int x, int z) => ((long)x << 32) | (uint)z;

        public static void Clear() => Fill.Clear();

        static bool Wanted(Collider c)
        {
            if (!(c is MeshCollider)) return false;
            bool inGiant = false;
            for (var t = c.transform; t != null && !inGiant; t = t.parent) inGiant = t.name == "Redwood Massive";
            return inGiant && (c.name == "Roots" || Leafy(c));
        }

        /// <summary>A collider of leaves: its name, or its renderer's material (the crown's is "Pine colony").</summary>
        static bool Leafy(Collider c)
        {
            string n = c.name.ToLowerInvariant();
            if (n.Contains("foliage") || n.Contains("leaves") || n.Contains("crown")) return true;
            var r = c.GetComponent<Renderer>() ?? c.GetComponentInParent<Renderer>();
            string m = r != null && r.sharedMaterial != null ? r.sharedMaterial.name.ToLowerInvariant() : "";
            return m.Contains("pine colony") || m.Contains("foliage");
        }

        /// <summary>Scans the shapes in a box (Unity coordinates).</summary>
        public static void Prepare(Bounds box, Action<string> log)
        {
            Fill.Clear();
            foreach (var c in UnityEngine.Object.FindObjectsByType<Collider>(FindObjectsSortMode.None))
            {
                if (c != null && c.enabled && c.bounds.Intersects(box))
                    for (var t = c.transform; t != null; t = t.parent)
                        if (t.name == "Redwood Massive") { log($"  the giant redwood's collider: {MinePeakExport.TPathOf(c.transform)} {c.GetType().Name} {c.bounds.size:F0}{(Wanted(c) ? (Leafy(c) ? " (leaves)" : " (wood)") : "")}"); break; }
                if (c == null || !c.enabled || !Wanted(c) || !c.bounds.Intersects(box)) continue;
                var b = c.bounds;
                int x0 = Mathf.FloorToInt(b.min.x), x1 = Mathf.CeilToInt(b.max.x), z0 = Mathf.FloorToInt(b.min.z), z1 = Mathf.CeilToInt(b.max.z);
                int y0 = Mathf.FloorToInt(b.min.y), y1 = Mathf.CeilToInt(b.max.y);
                int w = x1 - x0, d = z1 - z0;
                bool leaves = c.name != "Roots";
                var masks = new List<bool[]>();
                int cells = 0;
                var open = new Dictionary<long, int>();   // (column: the height its current run started)
                // every height's inside first (the crown's shell needs the heights round each)
                for (int y = y0; y <= y1 + 1; y++)
                {
                    var inX = new bool[w * d];
                    masks.Add(inX);
                    float fy = y + 0.5f;
                    if (y <= y1)
                    {
                        // along each row (x): from the left to the first wall, from the right to the first wall
                        for (int iz = 0; iz < d; iz++)
                        {
                            float fz = z0 + iz + 0.5f;
                            if (!c.Raycast(new Ray(new Vector3(b.min.x - 1f, fy, fz), Vector3.right), out var l, b.size.x + 2f)) continue;
                            if (!c.Raycast(new Ray(new Vector3(b.max.x + 1f, fy, fz), Vector3.left), out var r, b.size.x + 2f)) continue;
                            for (int ix = Mathf.Max(0, Mathf.CeilToInt(l.point.x - 0.5f) - x0); ix <= Mathf.Min(w - 1, Mathf.FloorToInt(r.point.x - 0.5f) - x0); ix++) inX[iz * w + ix] = true;
                        }
                        // along each column (z), keeping the blocks between both pairs of walls
                        for (int ix = 0; ix < w; ix++)
                        {
                            float fx = x0 + ix + 0.5f;
                            bool hit = c.Raycast(new Ray(new Vector3(fx, fy, b.min.z - 1f), Vector3.forward), out var n, b.size.z + 2f)
                                & c.Raycast(new Ray(new Vector3(fx, fy, b.max.z + 1f), Vector3.back), out var s, b.size.z + 2f);
                            int za = hit ? Mathf.CeilToInt(n.point.z - 0.5f) - z0 : int.MaxValue, zb = hit ? Mathf.FloorToInt(s.point.z - 0.5f) - z0 : int.MinValue;
                            for (int iz = 0; iz < d; iz++) if (iz < za || iz > zb) inX[iz * w + ix] = false;
                        }
                    }
                }
                bool In(int ly, int ix, int iz) => ly >= 0 && ly < masks.Count && ix >= 0 && iz >= 0 && ix < w && iz < d && masks[ly][iz * w + ix];
                for (int ly = 0; ly < masks.Count; ly++)
                {
                    int y = y0 + ly;
                    var inX = masks[ly];
                    // runs of inside heights per column (the crown: only its outer few blocks)
                    for (int iz = 0; iz < d; iz++)
                        for (int ix = 0; ix < w; ix++)
                        {
                            long k = Key(x0 + ix, z0 + iz);
                            bool inside = y <= y1 && inX[iz * w + ix];
                            if (inside && leaves && In(ly + CrownShell, ix, iz) && In(ly - CrownShell, ix, iz) && In(ly, ix + CrownShell, iz)
                                && In(ly, ix - CrownShell, iz) && In(ly, ix, iz + CrownShell) && In(ly, ix, iz - CrownShell)) inside = false;
                            if (inside) { cells++; if (!open.ContainsKey(k)) open[k] = y; }
                            else if (open.TryGetValue(k, out int from))
                            {
                                if (!Fill.TryGetValue(k, out var list)) Fill[k] = list = new List<(int, int, bool)>();
                                list.Add((from, y - 1, leaves));
                                open.Remove(k);
                            }
                        }
                }
                log($"  sideways: {MinePeakExport.TPathOf(c.transform)} {w}x{d}x{y1 - y0 + 1}, {cells} blocks inside");
            }
        }

        /// <summary>The inside heights (Unity cells, bottom..top) of the column at Unity (ux, uz).</summary>
        public static List<(int b, int t, bool leaves)> At(float ux, float uz) =>
            Fill.Count > 0 && Fill.TryGetValue(Key(Mathf.FloorToInt(ux), Mathf.FloorToInt(uz)), out var l) ? l : null;
    }
}
