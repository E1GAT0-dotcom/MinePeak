using System;
using System.Collections.Generic;

namespace MinePeakExporter
{
    /// <summary>
    /// After an area's scan: what single columns got wrong, put right by their neighbours. A rock column standing alone
    /// (at most one of its four neighbours solid there, for 8 blocks or more: a scan ray slipped through a seam between
    /// two rocks and missed an underside) is cut away; an air gap inside rock (three or four neighbours solid all the way
    /// along it: a missed top) is filled with the rock under it. Plants, planks, water and lava are left as they are.
    /// A hole one block wide in the ground (all four neighbours' ground higher: the ray slipped through a seam between two
    /// of PEAK's ground meshes, and under a floating stage you'd fall through it into the sky) is filled up to the lowest
    /// neighbour, with its blocks.
    /// </summary>
    internal static class Clean
    {
        public static int Holes;

        public static (int pillars, int gaps) Area(MinePeakExport.Area a, Func<int, bool> ground, Func<int, bool> liquid)
        {
            int w = a.W, d = a.D;
            // each column's solid intervals (bottom, top, block), from the runs (top y, length, block) going down
            var iv = new List<(int b, int t, int blk)>[w * d];
            for (int c = 0; c < w * d; c++)
            {
                var runs = a.Cols[c];
                var l = new List<(int, int, int)>();
                if (runs != null) for (int i = 0; i + 2 < runs.Length; i += 3) l.Add((runs[i] - runs[i + 1] + 1, runs[i], runs[i + 2]));
                iv[c] = l;
            }
            int pillars = 0, gaps = 0;
            var result = new int[w * d][];
            var k = new List<(int y, int delta)>();
            for (int iz = 0; iz < d; iz++)
                for (int ix = 0; ix < w; ix++)
                {
                    int c = iz * w + ix;
                    var mine = iv[c];
                    if (mine.Count == 0) { result[c] = a.Cols[c]; continue; }
                    // the neighbours (outside the area: unknown, counted as solid, so nothing changes at its rim)
                    var nb = new List<(int b, int t, int blk)>[4];
                    int[] dx = { -1, 1, 0, 0 }, dz = { 0, 0, -1, 1 };
                    bool rim = false;
                    for (int n = 0; n < 4; n++)
                    {
                        int x = ix + dx[n], z = iz + dz[n];
                        if (x < 0 || z < 0 || x >= w || z >= d) { rim = true; break; }
                        nb[n] = iv[z * w + x];
                    }
                    if (rim) { result[c] = a.Cols[c]; continue; }
                    // how many neighbours are solid at each height, as steps: (from y up, count)
                    int Count(int y) { int s = 0; for (int n = 0; n < 4; n++) foreach (var q in nb[n]) if (y >= q.b && y <= q.t && !liquid(q.blk)) { s++; break; } return s; }
                    var keep = new List<(int b, int t, int blk)>();
                    foreach (var r in mine)
                    {
                        if (!ground(r.blk)) { keep.Add(r); continue; }
                        // walk the run in steps where the neighbour count changes
                        k.Clear();
                        for (int n = 0; n < 4; n++) foreach (var q in nb[n]) { int qb = Math.Max(q.b, r.b), qt = Math.Min(q.t, r.t); if (qb <= qt) { k.Add((qb, 1)); k.Add((qt + 1, -1)); } }
                        k.Sort((p, q) => p.y.CompareTo(q.y));
                        var cuts = new List<int>();
                        cuts.Add(r.b); foreach (var e in k) if (e.y > r.b && e.y <= r.t) cuts.Add(e.y); cuts.Add(r.t + 1);
                        cuts.Sort();
                        int from = r.b;
                        for (int s = 0; s + 1 < cuts.Count; s++)
                        {
                            int y0 = cuts[s], y1 = cuts[s + 1] - 1;
                            if (y1 < y0) continue;
                            // (8: not the volcano's 5-thick shell; and only under something: a pillar hangs below a rock whose
                            // underside was missed, while a spire or a ridge's top stands free, and stays)
                            bool lone = y1 - y0 + 1 >= 8 && y1 < r.t && Count(y0) <= 1 && Count(y1 + 1) >= 2;
                            if (!lone) continue;
                            if (y0 > from) keep.Add((from, y0 - 1, r.blk));
                            from = y1 + 1;
                            pillars++;
                        }
                        if (from <= r.t) keep.Add((from, r.t, r.blk));
                    }
                    // gaps between solid intervals, closed in by the neighbours
                    keep.Sort((p, q) => q.t.CompareTo(p.t));
                    var filled = new List<(int b, int t, int blk)>();
                    for (int i = 0; i < keep.Count; i++)
                    {
                        filled.Add(keep[i]);
                        if (i + 1 >= keep.Count) break;
                        var above = keep[i]; var below = keep[i + 1];
                        int gb = below.t + 1, gt = above.b - 1;
                        if (gt < gb || gt - gb > 60 || !ground(above.blk) || !ground(below.blk)) continue;
                        bool closed = true;
                        for (int y = gb; y <= gt && closed; y += Math.Max(1, (gt - gb) / 6)) if (Count(y) < 3) closed = false;
                        if (closed && Count(gt) >= 3) { filled.Add((gb, gt, below.blk)); gaps++; }
                    }
                    // back to runs, top down
                    filled.Sort((p, q) => q.t.CompareTo(p.t));
                    var outRuns = new List<int>();
                    foreach (var f in filled) { outRuns.Add(f.t); outRuns.Add(f.t - f.b + 1); outRuns.Add(f.blk); }
                    result[c] = outRuns.ToArray();
                }
            a.Cols = result;
            Holes = FillHoles(a, ground);
            return (pillars, gaps);
        }

        /// <summary>The highest ground block's y and block in a column's runs (top y, length, block), or (int.MinValue, 0).</summary>
        static (int y, int blk) Top(int[] runs, Func<int, bool> ground)
        {
            var best = (int.MinValue, 0);
            if (runs != null) for (int i = 0; i + 2 < runs.Length; i += 3) if (ground(runs[i + 2]) && runs[i] > best.Item1) best = (runs[i], runs[i + 2]);
            return best;
        }

        static bool SolidAt(int[] runs, int y, Func<int, bool> ground)
        {
            if (runs != null) for (int i = 0; i + 2 < runs.Length; i += 3) if (ground(runs[i + 2]) && y <= runs[i] && y > runs[i] - runs[i + 1]) return true;
            return false;
        }

        static int FillHoles(MinePeakExport.Area a, Func<int, bool> ground)
        {
            int w = a.W, d = a.D, n = 0;
            var fills = new List<(int c, int from, int to, int blk)>();
            int[] dx = { -1, 1, 0, 0 }, dz = { 0, 0, -1, 1 };
            for (int iz = 1; iz < d - 1; iz++)
                for (int ix = 1; ix < w - 1; ix++)
                {
                    int c = iz * w + ix;
                    var (t, _) = Top(a.Cols[c], ground);
                    int h = int.MaxValue, blk = 0;
                    bool ok = true;
                    for (int k = 0; k < 4 && ok; k++)
                    {
                        var (nt, nb) = Top(a.Cols[(iz + dz[k]) * w + ix + dx[k]], ground);
                        if (nt == int.MinValue) ok = false;
                        else if (nt < h) { h = nt; blk = nb; }
                    }
                    if (!ok || t >= h - 2) continue;
                    // the four neighbours solid at the lowest one's top: a hole, not the edge of a drop
                    for (int k = 0; k < 4 && ok; k++) ok = SolidAt(a.Cols[(iz + dz[k]) * w + ix + dx[k]], h, ground);
                    if (!ok) continue;
                    int from = t != int.MinValue && h - t <= 60 ? t + 1 : h - 5;   // (a shaft: filled; over nothing: a plug)
                    fills.Add((c, from, h, blk));
                }
            foreach (var (c, from, to, blk) in fills)
            {
                var l = new List<int> { to, to - from + 1, blk };
                if (a.Cols[c] != null) l.AddRange(a.Cols[c]);
                // keep the runs top down
                var runs = new List<(int t, int len, int b)>();
                for (int i = 0; i + 2 < l.Count; i += 3) runs.Add((l[i], l[i + 1], l[i + 2]));
                runs.Sort((p, q) => q.t.CompareTo(p.t));
                var o = new List<int>();
                foreach (var r in runs) { o.Add(r.t); o.Add(r.len); o.Add(r.b); }
                a.Cols[c] = o.ToArray();
                n++;
            }
            return n;
        }
    }
}
