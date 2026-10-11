using System;
using System.Collections.Generic;
using System.Linq;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace MinePeakExporter
{
    /// <summary>
    /// What else is on PEAK's island for MinePeak to put in its world, by area:
    /// - spawners: the berry bushes, vines, coconut palms, mushroom clusters, nests, guidebook pages, the marshmallows by
    ///   the campfires... (PEAK's Spawner and its kinds, and SingleItemSpawner: where, which pool or item, how many, the spots);
    /// - hazards and fixtures: lava and its heat, the Kiln's rising lava, the Caldera's eruptions, the Mesa's sun and
    ///   tornadoes, the Alpine's wind, geysers and other things that push or hurt (AOE, TimeEvent), places that give a
    ///   status (StatusField), the island's own pitons, bounce shrooms, the summit's flare: each with its settings (Reflect).
    /// </summary>
    internal static class WorldThings
    {
        static JArray J(Vector3 p) { var m = MinePeakExport.ToMc(p); return new JArray(Math.Round(m.x, 2), Math.Round(m.y, 2), Math.Round(m.z, 2)); }

        public static JArray Spawners(Bounds b, Action<string> log)
        {
            var list = new JArray();
            var counts = new Dictionary<string, int>();
            foreach (var s in UnityEngine.Object.FindObjectsByType<Spawner>(FindObjectsInactive.Include, FindObjectsSortMode.None))
            {
                if (s == null || s is Luggage || !b.Contains(s.transform.position)) continue;
                var o = new JObject { ["kind"] = s.GetType().Name + "/" + s.gameObject.name, ["at"] = J(s.transform.position), ["path"] = MinePeakExport.TPathOf(s.transform) };
                o["mode"] = s.spawnMode.ToString();
                SpawnPool pool;
                try { pool = s.GetSpawnPool(); } catch (Exception) { pool = s.spawnPool; }
                o["pool"] = pool.ToString();
                o["chance"] = Math.Round(s.baseSpawnChance, 3);
                o["repeat"] = s.canRepeatSpawns;
                if (s.spawnedObjectPrefab != null) o["item"] = s.spawnedObjectPrefab.name;
                if (s.spawns != null && s.spawns.items != null)
                    o["list"] = Reflect.Value(s.spawns.items, 1);
                Vector2 range = default;
                if (s is BerryBush bush) range = bush.possibleBerries;
                else if (s is BerryVine vine) range = vine.possibleBerries;
                else if (s is GroundPlaceSpawner ground) range = ground.possibleItems;
                if (range != default) o["count"] = new JArray(range.x, range.y);
                var spots = new JArray();
                IEnumerable<Transform> ts = s.spawnSpots ?? new List<Transform>();
                if (s.spawnPointMode == Spawner.SpawnPointMode.WeightedLists && s.weightedSpawnSpots != null)
                    ts = s.weightedSpawnSpots.Where(w => w?.spawnSpots != null).SelectMany(w => w.spawnSpots);
                if (s is BerryVine)
                    ts = s.GetComponentsInChildren<Collider>(true).Select(c => c.transform);
                foreach (var t in ts) if (t != null) spots.Add(J(t.position));
                o["spots"] = spots;
                list.Add(o);
                string k = s.GetType().Name + ":" + pool;
                counts[k] = counts.TryGetValue(k, out int n) ? n + 1 : 1;
            }
            foreach (var s in UnityEngine.Object.FindObjectsByType<SingleItemSpawner>(FindObjectsInactive.Include, FindObjectsSortMode.None))
            {
                if (s == null || s.prefab == null || !b.Contains(s.transform.position)) continue;
                list.Add(new JObject { ["kind"] = s.GetType().Name + "/" + s.gameObject.name, ["at"] = J(s.transform.position), ["mode"] = "SingleItem",
                    ["item"] = s.prefab.name, ["spots"] = new JArray { J(s.transform.position) }, ["path"] = MinePeakExport.TPathOf(s.transform) });
                string k = s.GetType().Name + ":" + s.prefab.name;
                counts[k] = counts.TryGetValue(k, out int n) ? n + 1 : 1;
            }
            if (counts.Count > 0) log("  spawners: " + string.Join(", ", counts.OrderByDescending(kv => kv.Value).Select(kv => $"{kv.Key} {kv.Value}")));
            return list;
        }

        static readonly string[] HazardTypes = {
            "Lava", "LavaRising", "MovingLava", "EruptionSpawner", "TornadoSpawner", "Tornado", "WindChillZone", "HotSun",
            "AOE", "TimeEvent", "StatusField", "StatusEmitter", "CollisionModifier", "ClimbHandle", "ShittyPiton",
            "ProgressPoint", "PeakSequence", "Geyser", "SteamVent", "JungleVine", "Rope", "RopeAnchor", "Bonfire", "Campfire"
        };

        public static JArray Hazards(Bounds b, Action<string> log)
        {
            var list = new JArray();
            var counts = new Dictionary<string, int>();
            foreach (var name in HazardTypes)
            {
                Type t;
                UnityEngine.Object[] found;
                try
                {
                    t = HarmonyLib.AccessTools.TypeByName(name);
                    if (t == null || !typeof(Component).IsAssignableFrom(t)) continue;
                    found = UnityEngine.Object.FindObjectsByType(t, FindObjectsInactive.Include, FindObjectsSortMode.None);
                }
                catch (Exception e) { log($"  hazards: {name}: {e.Message}"); continue; }
                foreach (var obj in found)
                try
                {
                    if (!(obj is Component c) || c == null || c.GetType() != t || !b.Contains(c.transform.position)) continue;
                    var o = new JObject { ["kind"] = name, ["at"] = J(c.transform.position), ["path"] = MinePeakExport.TPathOf(c.transform),
                        ["active"] = c.gameObject.activeInHierarchy, ["yaw"] = Math.Round(c.transform.eulerAngles.y) };
                    var ls = c.transform.lossyScale;
                    o["scale"] = new JArray(Math.Round(ls.x, 3), Math.Round(ls.y, 3), Math.Round(ls.z, 3));
                    var col = c.GetComponent<Collider>();
                    if (col != null)
                    {
                        var cb = col.bounds;
                        var lo = MinePeakExport.ToMc(cb.min); var hi = MinePeakExport.ToMc(cb.max);
                        o["box"] = new JArray(Math.Round(lo.x, 1), Math.Round(lo.y, 1), Math.Round(Math.Min(lo.z, hi.z), 1), Math.Round(hi.x, 1), Math.Round(hi.y, 1), Math.Round(Math.Max(lo.z, hi.z), 1));
                    }
                    // the children that mark where things happen (an eruption's bounds, a tornado's points, the rising lava's top)
                    var kids = new JObject();
                    foreach (Transform k in c.transform) if (kids.Count < 40 && !kids.ContainsKey(k.name)) kids[k.name] = J(k.position);
                    if (kids.Count > 0) o["children"] = kids;
                    try { o["fields"] = Reflect.Fields(c); } catch (Exception) { }
                    // a vine (or a rope bridge): its curve, the points PEAK built it from (colliderRoot's children)
                    if (c is JungleVine vine && vine.colliderRoot != null)
                    {
                        var pts = new JArray();
                        foreach (Transform k in vine.colliderRoot) pts.Add(J(k.position - Vector3.up * vine.colliderOffset));
                        o["points"] = pts;
                        o["bridge"] = vine.GetComponentInChildren<BreakableBridge>(true) != null || vine.colliderType.ToString() == "Box";
                    }
                    list.Add(o);
                    counts[name] = counts.TryGetValue(name, out int n) ? n + 1 : 1;
                }
                catch (Exception e) { log($"  hazards: a {name}: {e.Message}"); }
            }
            if (counts.Count > 0) log("  hazards and fixtures: " + string.Join(", ", counts.Select(kv => $"{kv.Key} {kv.Value}")));
            return list;
        }
    }
}
