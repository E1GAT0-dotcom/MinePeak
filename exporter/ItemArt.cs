using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Newtonsoft.Json.Linq;
using UnityEngine;
using Zorro.Core;

namespace MinePeakExporter
{
    /// <summary>
    /// PEAK's items, for MinePeak's: every item in PEAK's ItemDatabase with its icon (items/&lt;name&gt;.png), its name in
    /// the player's language, and what it does (items/items.json: its uses, how long it takes to use, its weight, and the
    /// settings of each of its parts, e.g. Action_RestoreHunger's restorationAmount, Action_ModifyStatus's status and
    /// amount, ItemCooking's, RopeSpool's...). Also PEAK's statuses' own numbers (the scout's CharacterAfflictions: how
    /// fast each wears off). Taken from the player's own PEAK as it exports, into %APPDATA%/MinePeak/items.
    /// </summary>
    internal static class ItemArt
    {
        static string Dir => Path.Combine(Path.GetDirectoryName(MinePeakExport.Root), "items");

        public static void Take(Action<string> log)
        {
            try
            {
                Directory.CreateDirectory(Dir);
                var db = SingletonAsset<ItemDatabase>.Instance;
                var items = new JArray();
                int icons = 0;
                foreach (var item in db.itemLookup.Values.Where(i => i != null).OrderBy(i => i.itemID))
                {
                    var o = new JObject { ["item"] = item.name, ["id"] = item.itemID };
                    try { o["name"] = item.GetName(); } catch (Exception) { o["name"] = item.UIData?.itemName ?? item.name; }
                    o["key"] = item.UIData?.itemName;
                    o["type"] = item.GetType().Name;
                    o["mass"] = Math.Round(item.mass, 3);
                    o["carryWeight"] = item.carryWeight;
                    o["usingTimePrimary"] = Math.Round(item.usingTimePrimary, 3);
                    o["totalUses"] = item.totalUses;
                    o["tags"] = item.itemTags.ToString();
                    o["canUseOnFriend"] = item.canUseOnFriend;
                    if (item.isSecretlyOtherItemPrefab != null) o["secretly"] = item.isSecretlyOtherItemPrefab.name;   // (a mandrake passing for a root)
                    if (item.UIData != null)
                    {
                        var ui = Reflect.Fields(item.UIData);
                        ui.Remove("icon"); ui.Remove("altIcon");
                        o["ui"] = ui;
                        if (item.UIData.icon != null)
                        {
                            string file = Safe(item.name) + ".png";
                            if (MenuArt.SavePicture(item.UIData.icon, new Rect(0, 0, item.UIData.icon.width, item.UIData.icon.height), Path.Combine(Dir, file)))
                            { o["icon"] = file; icons++; }
                        }
                    }
                    try
                    {
                        var loot = item.GetComponent<LootData>();
                        if (loot != null) o["loot"] = Reflect.Fields(loot);
                    }
                    catch (Exception) { }
                    var parts = new JArray();
                    foreach (var c in item.GetComponentsInChildren<MonoBehaviour>(true))
                    {
                        if (c == null || c is Item || c.GetType().Assembly != typeof(Item).Assembly) continue;
                        var p = new JObject { ["type"] = c.GetType().Name };
                        if (c.transform != item.transform) p["child"] = c.gameObject.name;
                        p["fields"] = Reflect.Fields(c);
                        parts.Add(p);
                    }
                    o["parts"] = parts;
                    // the words on it (a guidebook's pages: PEAK's text in the player's language, as its LocalizedText set it)
                    var texts = new JArray();
                    foreach (var tmp in item.GetComponentsInChildren<TMPro.TMP_Text>(true))
                    {
                        if (tmp == null) continue;
                        string text = tmp.text;
                        try
                        {
                            var lt = tmp.GetComponent<LocalizedText>();
                            if (lt != null && !string.IsNullOrEmpty(lt.index)) { var loc = LocalizedText.GetText(lt.index); if (!string.IsNullOrWhiteSpace(loc)) text = loc; }
                        }
                        catch (Exception) { }
                        if (!string.IsNullOrWhiteSpace(text)) texts.Add(new JObject { ["path"] = MinePeakExport.TPathOf(tmp.transform), ["text"] = text });
                    }
                    if (texts.Count > 0) o["texts"] = texts;
                    items.Add(o);
                }
                var root = new JObject { ["items"] = items };
                // PEAK's statuses' numbers (on the scout, not in the code): how fast each wears off, hunger's rate...
                var ch = Character.localCharacter;
                if (ch != null && ch.refs != null && ch.refs.afflictions != null) root["afflictions"] = Reflect.Fields(ch.refs.afflictions);
                // the bees a beehive lets out (PEAK's BeeSwarm: how far they notice you, how hard they chase, their sting)
                try
                {
                    var hive = db.itemLookup.Values.Where(i => i != null).Select(i => i.GetComponent<Beehive>()).FirstOrDefault(b => b != null && b.beeSwarmPrefab != null);
                    if (hive != null)
                    {
                        var bees = Reflect.Fields(hive.beeSwarmPrefab);
                        if (hive.beeSwarmPrefab.stingerField != null) bees["stinger"] = Reflect.Fields(hive.beeSwarmPrefab.stingerField);
                        var rb = hive.beeSwarmPrefab.GetComponent<Rigidbody>();
                        if (rb != null) { bees["drag"] = Math.Round(rb.linearDamping, 4); bees["mass"] = Math.Round(rb.mass, 4); bees["gravity"] = rb.useGravity; }
                        root["bees"] = bees;
                    }
                }
                catch (Exception e) { log("items: bees: " + e.Message); }
                try { root["cookColors"] = new JArray(Enumerable.Range(0, 6).Select(n => "#" + ColorUtility.ToHtmlStringRGB(ItemCooking.GetCookColor(n)))); } catch (Exception) { }
                File.WriteAllText(Path.Combine(Dir, "items.json"), root.ToString());
                log($"items: {items.Count} of PEAK's items, {icons} icons, into {Dir}");
            }
            catch (Exception e) { log("items failed: " + e); }
        }

        public static string Safe(string s)
        {
            var o = new System.Text.StringBuilder();
            foreach (char ch in s ?? "item") o.Append(char.IsLetterOrDigit(ch) ? char.ToLowerInvariant(ch) : '_');
            return o.ToString();
        }
    }
}
