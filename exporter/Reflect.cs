using System;
using System.Collections;
using System.Reflection;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace MinePeakExporter
{
    /// <summary>
    /// A PEAK object's settings as JSON, by reflection: its serialized fields (public, [SerializeField] and
    /// [SerializeReference]) with numbers, words, switches, vectors, colours, lists of those, and nested settings (with their
    /// type when it's a [SerializeReference]); a Unity object as "@" and its name. For MinePeak to do what PEAK's items
    /// and hazards do with PEAK's own numbers.
    /// </summary>
    internal static class Reflect
    {
        const BindingFlags Flags = BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.DeclaredOnly;

        public static JObject Fields(object o, int depth = 0)
        {
            var j = new JObject();
            if (o == null) return j;
            for (var t = o.GetType(); t != null && t != typeof(object) && t != typeof(MonoBehaviour) && t != typeof(Behaviour) && t != typeof(Component)
                && t != typeof(UnityEngine.Object) && t.Namespace?.StartsWith("Photon") != true; t = t.BaseType)
                foreach (var f in t.GetFields(Flags))
                {
                    if (f.IsNotSerialized || j.ContainsKey(f.Name) || f.Name.Contains("<")) continue;
                    bool reference = f.GetCustomAttribute<SerializeReference>() != null;
                    if (!f.IsPublic && f.GetCustomAttribute<SerializeField>() == null && !reference) continue;
                    try
                    {
                        var v = Value(f.GetValue(o), depth, reference);
                        if (v != null) j[f.Name] = v;
                    }
                    catch (Exception) { }
                }
            return j;
        }

        public static JToken Value(object v, int depth, bool typed = false)
        {
            switch (v)
            {
                case null: return null;
                case string s: return s;
                case bool b: return b;
                case float f: return float.IsNaN(f) || float.IsInfinity(f) ? null : (JToken)Math.Round(f, 5);
                case double d: return double.IsNaN(d) || double.IsInfinity(d) ? null : (JToken)Math.Round(d, 5);
                case int i: return i;
                case uint ui: return ui;
                case short sh: return sh;
                case ushort us: return us;
                case byte by: return by;
                case long l: return l;
                case Enum e: return e.ToString();
                case Vector2 v2: return new JArray(Math.Round(v2.x, 4), Math.Round(v2.y, 4));
                case Vector3 v3: return new JArray(Math.Round(v3.x, 4), Math.Round(v3.y, 4), Math.Round(v3.z, 4));
                case Color c: return "#" + ColorUtility.ToHtmlStringRGBA(c);
                case Gradient g: { var a = new JArray(); foreach (var k in g.colorKeys) a.Add("#" + ColorUtility.ToHtmlStringRGB(k.color)); return a; }
                case AnimationCurve ac: { var a = new JArray(); foreach (var k in ac.keys) a.Add(new JArray(Math.Round(k.time, 4), Math.Round(k.value, 4))); return a; }
                case LayerMask lm: return lm.value;
                case UnityEngine.Object uo: return uo != null ? "@" + uo.name : null;
            }
            if (depth >= 3) return null;
            if (v is IList list)
            {
                var a = new JArray();
                int n = 0;
                foreach (var x in list) { if (n++ >= 48) break; a.Add(Value(x, depth + 1, typed) ?? JValue.CreateNull()); }
                return a;
            }
            var t = v.GetType();
            if (t.IsPrimitive || t.IsPointer || typeof(Delegate).IsAssignableFrom(t) || t.Namespace?.StartsWith("System") == true) return null;
            var o = Fields(v, depth + 1);
            if (typed || o.Count == 0) o["$type"] = t.Name;
            return o;
        }
    }
}
