using System;
using System.Collections.Generic;
using UnityEngine;

namespace MinePeakExporter
{
    /// <summary>
    /// Upright walls the up-and-down scan slides past: the Gloom's temple hall (`Gloom/Gloom Temple/Temple_Model`, a
    /// brick hall round the foot of the tower, its floor at y 868 and its roof at 1246). The scan found its floor and
    /// roof but not its walls, so in Minecraft it was an open shell with the swamp's background rocks showing through
    /// ("the walls are missing and you can see the empty shell"). Rays across it, along x and along z at every height,
    /// both ways: a block wherever one crosses its surface, so the walls stand a block or two thick and the hall stays
    /// hollow (Sideways fills between walls instead: right for the giant redwood's trunk, not for a hall).
    /// </summary>
    internal static class UprightWalls
    {
        static readonly Dictionary<long, Dictionary<int, int>> Cells = new Dictionary<long, Dictionary<int, int>>();
        static long Key(int x, int z) => ((long)x << 32) | (uint)z;

        public static void Clear() => Cells.Clear();

        static bool Wanted(Collider c) => c is MeshCollider && c.enabled && !c.isTrigger
            && MinePeakExport.TPathOf(c.transform).Contains("/Gloom/Gloom Temple/") && c.bounds.size.magnitude > 8f;

        /// <summary>Crosses the wanted walls in a box (Unity coordinates); block gives a hit's block (a palette index).</summary>
        public static void Prepare(Bounds box, Func<RaycastHit, int> block, Action<string> log)
        {
            Cells.Clear();
            int pieces = 0, rays = 0, blocks = 0;
            float t = Time.realtimeSinceStartup;
            foreach (var c in UnityEngine.Object.FindObjectsByType<Collider>(FindObjectsSortMode.None))
            {
                if (c == null || !Wanted(c) || !c.bounds.Intersects(box)) continue;
                pieces++;
                var b = c.bounds;
                int x0 = Mathf.FloorToInt(b.min.x), x1 = Mathf.CeilToInt(b.max.x), z0 = Mathf.FloorToInt(b.min.z), z1 = Mathf.CeilToInt(b.max.z);
                int y0 = Mathf.FloorToInt(b.min.y), y1 = Mathf.CeilToInt(b.max.y);
                for (int y = y0; y <= y1; y++)
                {
                    float fy = y + 0.5f;
                    for (int z = z0; z <= z1; z++)
                    {
                        float fz = z + 0.5f;
                        rays += Cross(c, new Vector3(b.min.x - 1f, fy, fz), Vector3.right, b.size.x + 2f, block, ref blocks);
                        rays += Cross(c, new Vector3(b.max.x + 1f, fy, fz), Vector3.left, b.size.x + 2f, block, ref blocks);
                    }
                    for (int x = x0; x <= x1; x++)
                    {
                        float fx = x + 0.5f;
                        rays += Cross(c, new Vector3(fx, fy, b.min.z - 1f), Vector3.forward, b.size.z + 2f, block, ref blocks);
                        rays += Cross(c, new Vector3(fx, fy, b.max.z + 1f), Vector3.back, b.size.z + 2f, block, ref blocks);
                    }
                }
            }
            if (pieces > 0) log($"  upright walls: {pieces} pieces of the Gloom temple's hall crossed by {rays} rays, {blocks} blocks of wall ({Time.realtimeSinceStartup - t:F1} s)");
        }

        /// <summary>One ray across a piece, on through each of its surfaces: a block where it crosses each. How many rays.</summary>
        static int Cross(Collider c, Vector3 from, Vector3 dir, float length, Func<RaycastHit, int> block, ref int blocks)
        {
            int n = 0;
            for (int k = 0; k < 64 && length > 0f; k++)
            {
                n++;
                if (!c.Raycast(new Ray(from, dir), out var h, length)) break;
                var p = h.point + dir * 0.01f;   // (the block just inside the surface)
                long key = Key(Mathf.FloorToInt(p.x), Mathf.FloorToInt(p.z));
                if (!Cells.TryGetValue(key, out var col)) Cells[key] = col = new Dictionary<int, int>();
                int y = Mathf.FloorToInt(p.y);
                if (!col.ContainsKey(y)) { col[y] = block(h); blocks++; }
                length -= h.distance + 0.05f;
                from = h.point + dir * 0.05f;
            }
            return n;
        }

        /// <summary>The wall blocks in the column at Unity (ux, uz): height (Unity) to palette index, or null.</summary>
        public static Dictionary<int, int> At(float ux, float uz) =>
            Cells.Count > 0 && Cells.TryGetValue(Key(Mathf.FloorToInt(ux), Mathf.FloorToInt(uz)), out var c) ? c : null;
    }
}
