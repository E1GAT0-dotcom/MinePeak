using System;
using System.Collections.Generic;
using System.Linq;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace MinePeakExporter
{
    /// <summary>
    /// PEAK's plants, for MinePeak to build as Minecraft trees and plants: palms, bushes, beach weeds, ferns, the
    /// redwoods' crowns, mushroom trees, flowers, small cacti, hanging vines and chains, jungle trees, monsteras,
    /// mangroves, willows, cattails and Venus flytraps. Their colliders aren't scanned into blocks (their
    /// trunks came out as floating log stubs, their crowns as hollow shells or nothing); instead each one's place and
    /// size goes into island.json ("plants" in its area). The giant redwood's rock pillar and crown (bigger than 80 m)
    /// stay blocks from their colliders, and so do the big cacti, the redwoods' trunks and the swamp's huge bare mangroves
    /// (their shapes come out right).
    /// The crashed plane at the spawn is left out altogether.
    /// </summary>
    internal static class Plants
    {
        static readonly Dictionary<int, string> KindCache = new Dictionary<int, string>();

        public static void Clear() => KindCache.Clear();

        static string TPath(Transform t) { var p = t.name; while (t.parent != null) { t = t.parent; p = t.name + "/" + p; } return p; }

        /// <summary>What plant (or the plane) this renderer or collider is part of, or null.</summary>
        public static string KindOf(Transform t, Renderer r)
        {
            int id = t.GetInstanceID();
            if (KindCache.TryGetValue(id, out var k)) return k;
            string path = TPath(t).ToLowerInvariant();
            string mat = r != null && r.sharedMaterial != null ? r.sharedMaterial.name.ToLowerInvariant() : "";
            k = null;
            if (path.Contains("crashed plane")) k = "plane";
            else if (path.Contains("redwood massive")) k = null;                                   // (the giant: blocks)
            else if (path.Contains("mangrovehuge")) k = null;                                      // (the swamp's huge bare mangroves: blocks)
            else if (mat.Contains("pine colony") || (path.Contains("redwood") && path.Contains("foliage"))) k = "redwood";
            else if (path.Contains("ice_pine") && !path.Contains("pinebush") && !path.Contains("pineleaves")) k = "pine";      // (the Alpine's pines)
            else if (path.Contains("ice_deadtree")) k = "deadtree";                                                         // (and its dead trees)
            else if (path.Contains("ice_pinebush") || path.Contains("ice_pineleaves") || path.Contains("ice_deadshrub")) k = "pinebush";
            else if (path.Contains("ice_horsetail")) k = "fern";
            else if (path.Contains("palmtree") || mat.Contains("beach bark") || mat.Contains("palmtree")) k = "palm";
            else if (mat.Contains("foliage_bush") || path.Contains("bushleaves")) k = "bush";
            else if (path.Contains("hangingvine") && mat.Contains("chain")) k = "chain";                // (the temple's chains)
            else if (path.Contains("hangingvine") || mat.Contains("hanging vine") || mat.Contains("swampvine")) k = "vine";
            else if (mat.Contains("foliagepack") && mat.Contains("bark") || mat.Contains("treeleaves")) k = "tree";   // (the jungle's trees)
            else if (mat.Contains("mangrove")) k = "mangrove";
            else if (mat.Contains("willow")) k = "willow";
            else if (mat == "cattail") k = "cattail";
            else if (mat.Contains("venusflytrap")) k = "flytrap";
            else if (path.Contains("monstera")) k = "monstera";
            else if (mat.Contains("generic_red")) k = "redweed";   // (the Red Beach's dry, pale weeds)
            else if (mat.Contains("generic_blue")) k = "blueweed";   // (the Blue Beach's: its own blue grass)
            else if (mat.Contains("generic 3") || path.Contains("weed")) k = "weed";
            else if (mat == "fern") k = "fern";
            else if (path.Contains("mushroom tree") || mat.Contains("mushroombase") || mat.Contains("mushroom skirt")) k = "mushroom";
            else if (mat.Contains("hibiscus") || mat == "m_foliage generic") k = "flower";
            else if (mat == "m_foliage_cactus") k = "cactus";
            KindCache[id] = k;
            return k;
        }

        /// <summary>Is this collider a plant MinePeak builds itself (or the plane), so not to be scanned into blocks?</summary>
        public static bool Skip(Collider c)
        {
            if (c.bounds.size.x > 80f && c.bounds.size.z > 80f) return false;   // (the giant redwood's pillar and crown)
            var r = c.GetComponent<Renderer>() ?? c.GetComponentInParent<Renderer>();
            string k = KindOf(c.transform, r);
            if (k == null && r != null) k = KindOf(r.transform, r);
            return k != null && k != "cactus";
        }

        sealed class Part { public string Kind; public bool Crown; public Bounds B; public Vector3 Pivot; }

        /// <summary>The plants in a box (Unity coordinates), as island.json's "plants" (Minecraft coordinates).</summary>
        public static JArray Collect(Bounds box, HashSet<int> seen, Action<string> log)
        {
            var parts = new List<Part>();
            var lod0 = new HashSet<Renderer>();
            var lodded = new HashSet<Renderer>();
            foreach (var g in UnityEngine.Object.FindObjectsByType<LODGroup>(FindObjectsSortMode.None))
            {
                var lods = g.GetLODs();
                for (int i = 0; i < lods.Length; i++)
                    foreach (var r in lods[i].renderers) if (r != null) { lodded.Add(r); if (i == 0) lod0.Add(r); }
            }
            foreach (var r in UnityEngine.Object.FindObjectsByType<Renderer>(FindObjectsSortMode.None))
            {
                if (r == null || !r.enabled || !r.gameObject.activeInHierarchy || r is ParticleSystemRenderer) continue;
                var b = r.bounds;
                if (b.center.x < box.min.x || b.center.x > box.max.x || b.center.z < box.min.z || b.center.z > box.max.z) continue;
                if (lodded.Contains(r) && !lod0.Contains(r)) continue;   // (the same plant drawn simpler, further away)
                string k = KindOf(r.transform, r);
                if (k == null || k == "plane") continue;
                if (b.size.x > 80f && b.size.z > 80f) continue;           // (the giant redwood's crown: blocks)
                if (k == "fern" && b.size.y > 15f) continue;              // (a few big merged meshes)
                string mat = r.sharedMaterial != null ? r.sharedMaterial.name.ToLowerInvariant() : "";
                if (mat.Contains("skirt")) continue;                      // (the ring round a mushroom tree's stem)
                if (!seen.Add(r.GetInstanceID())) continue;
                bool crown = k == "palm" || k == "tree" ? !mat.Contains("bark") : k == "redwood";
                parts.Add(new Part { Kind = k, Crown = crown, B = b, Pivot = r.transform.position });
            }
            var list = new JArray();
            // palms and jungle trees: each crown on the trunk whose top it sits on
            foreach (var kind in new[] { "palm", "tree" }) Pair(parts, kind, list);
            foreach (var p in parts.Where(p => p.Kind != "palm" && p.Kind != "tree"))
                list.Add(Entry(p.Kind, new Vector3(p.B.center.x, p.B.min.y, p.B.center.z), p.B, 1f));
            log($"  plants: " + string.Join(", ", list.GroupBy(j => (string)j["k"]).Select(g => $"{g.Key} {g.Count()}")));
            return list;
        }

        static void Pair(List<Part> parts, string kind, JArray list)
        {
            var trunks = parts.Where(p => p.Kind == kind && !p.Crown).ToList();
            var used = new HashSet<Part>();
            foreach (var c in parts.Where(p => p.Kind == kind && p.Crown))
            {
                Part best = null; float bd = 6f;
                foreach (var t in trunks)
                {
                    var tb = t.B; tb.Expand(new Vector3(4f, 0f, 4f));
                    if (c.B.center.x < tb.min.x || c.B.center.x > tb.max.x || c.B.center.z < tb.min.z || c.B.center.z > tb.max.z) continue;
                    float d = Mathf.Abs(c.B.center.y - t.B.max.y);
                    if (d < bd) { bd = d; best = t; }
                }
                Vector3 baseP;
                float w = 1f;
                if (best != null)
                {
                    used.Add(best);
                    var tb = best.B;
                    baseP = InXZ(best.Pivot, tb) && Mathf.Abs(best.Pivot.y - tb.min.y) < 3f ? best.Pivot : new Vector3(tb.center.x, tb.min.y, tb.center.z);
                    w = Mathf.Clamp(Mathf.Min(tb.size.x, tb.size.z) * 0.25f, 1f, 2f);
                }
                else baseP = new Vector3(c.B.center.x, c.B.min.y - 6f, c.B.center.z);
                list.Add(Entry(kind, baseP, c.B, w));
            }
            foreach (var t in trunks.Where(t => !used.Contains(t)))   // (no crown found: a crown at the trunk's top)
            {
                var top = new Bounds(new Vector3(t.B.center.x, t.B.max.y, t.B.center.z), new Vector3(9f, 3f, 9f));
                list.Add(Entry(kind, new Vector3(t.B.center.x, t.B.min.y, t.B.center.z), top, 1f));
            }
        }

        static bool InXZ(Vector3 p, Bounds b) => p.x >= b.min.x - 1f && p.x <= b.max.x + 1f && p.z >= b.min.z - 1f && p.z <= b.max.z + 1f;

        /// <summary>k: kind; b: its foot; c: its crown's middle; r: the crown's radius; cb, t: the crown's bottom and top; w: trunk width.</summary>
        static JObject Entry(string kind, Vector3 foot, Bounds crown, float trunk)
        {
            var f = MinePeakExport.ToMc(foot);
            var c = MinePeakExport.ToMc(crown.center);
            return new JObject
            {
                ["k"] = kind,
                ["b"] = new JArray(Math.Round(f.x, 1), Math.Round(f.y, 1), Math.Round(f.z, 1)),
                ["c"] = new JArray(Math.Round(c.x, 1), Math.Round(c.y, 1), Math.Round(c.z, 1)),
                ["r"] = Math.Round(Mathf.Max(crown.extents.x, crown.extents.z), 1),
                ["cb"] = Math.Round(crown.min.y + MinePeakExport.YOffset, 1),
                ["t"] = Math.Round(crown.max.y + MinePeakExport.YOffset, 1),
                ["w"] = Math.Round(trunk, 1),
            };
        }
    }
}
