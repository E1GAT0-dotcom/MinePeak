using UnityEngine;

namespace MinePeakExporter
{
    /// <summary>
    /// Which Minecraft textures PEAK's materials stand for: a copy of PEAKCRAFT's rules (its WorldSkinner.Pick and
    /// NetherLook), kept as MinePeak's own so the two mods stay separate. Texture names are mostly block names.
    /// </summary>
    internal static class Looks
    {
        internal sealed class Look { public string Side, Top, TopAlt; public Color? SideTint, TopTint; public bool Animated, Glass; }

        internal static readonly Color GrassTint = new Color32(0x91, 0xBD, 0x59, 0xFF);
        static readonly Color FoliageTint = new Color32(0x77, 0xAB, 0x2F, 0xFF);
        static readonly Color SpruceTint = new Color32(0x61, 0x99, 0x61, 0xFF);

        static Look L(string side, string top = null, Color? sideTint = null, Color? topTint = null) =>
            new Look { Side = side, Top = top ?? side, SideTint = sideTint, TopTint = topTint ?? (top == null ? sideTint : null) };

        public static Look For(Material m, bool nether, bool gloom)
        {
            if (m == null || m.shader == null) return null;
            var look = Pick(m.shader.name, m.name);
            return nether ? NetherLook(look, m.shader.name, gloom) ?? look : look;   // (what the Nether keeps: basalt, lava, water, glass)
        }

        internal static Look Pick(string shader, string name)
        {
            string n = name.Replace(" (Instance)", "");
            bool Has(params string[] keys) { foreach (var k in keys) if (n.IndexOf(k, System.StringComparison.OrdinalIgnoreCase) >= 0) return true; return false; }

            if (Has("GiantTree")) return L("jungle_wood");   // (the jungle's giant trees: bark all round)
            if (Has("MangroveTree")) return L("mangrove_wood");   // (the swamp's huge bare mangroves: bark all round)
            if (Has("RedRock")) return L("tuff", "minepeak:red_beach_sand");   // (the Red Beach: salmon sand, dark grey rock in its cliffs)
            if (Has("Thorns")) return L("mangrove_roots");               // (the jungle's thorny vines: a tangle)
            if (n.Equals("M_Metal") || Has("Fence")) return L("iron_bars");   // (fences, rails, the temple's traps)
            switch (shader)
            {
                case "W/Peak_Rock":
                case "W/Peak_Ice":
                    if (Has("Shroom", "Mushroom", "Fungus", "Thorns", "Yuzu", "Plane", "Coconut", "Apple")) return null;
                    if (Has("Basalt")) return L("basalt_side", "basalt_top");
                    if (Has("Volcano", "Lava"))
                    {
                        var v = L("blackstone");   // (dark rock, glowing in patches)
                        v.TopAlt = "magma";
                        return v;
                    }
                    if (Has("ice")) return L("packed_ice", "snow");
                    if (Has("Snow")) return L("stone", "snow");
                    if (Has("DesertSand", "Desert")) return L("red_sandstone", "sand");   // (the Mesa: pale sand, banded red cliffs)
                    if (Has("HarborSand")) return L("sandstone", "sand");
                    if (Has("SaltRock")) return L("stone", "sand");
                    if (Has("BlueRock")) return L("minepeak:blue_beach_rock", "minepeak:blue_beach_sand");   // (the Blue Beach: pale blue sand on pale blue rock)
                    if (Has("Swamp")) return L("mossy_cobblestone", "moss_block");
                    if (Has("Petrified")) return L("andesite");
                    if (Has("Citadel")) return L("deepslate_bricks");   // (the Citadel's tower and the swamp's temple: dark slate)
                    if (Has("Dagger")) return L("mud_bricks");          // (the Citadel's inner walls: brown brick)
                    if (Has("temple")) return L("stone_bricks");
                    if (Has("RedWood")) return L("spruce_log", "spruce_log_top");
                    if (Has("Rock", "Stone", "Cliff")) return L("stone", "grass_block_top", null, GrassTint);
                    return null;
                case "W/Peak_Standard":
                    if (Has("Palmtree")) return L("jungle_log");
                    if (Has("Driftwood")) return L("pale_oak_wood");   // (the beach's driftwood: pale, bark all round, lying)
                    if (Has("Bark", "M_Wood", "Trunk", "Log")) return L("oak_log");
                    if (Has("RopeBridgeWood")) return L("oak_planks");
                    if (Has("Airport Tiles")) return L("smooth_stone");
                    if (Has("Floor checker")) return L("chiseled_stone_bricks");
                    if (Has("Airport Floor")) return L("polished_andesite");
                    if (Has("Airport Wall")) return L("quartz_block_side");
                    if (Has("carpet")) return L("blue_wool");
                    if (Has("tarmac")) return L("gray_concrete");
                    if (Has("Schaffold")) return L("scaffolding_side");
                    if (Has("shutter", "Elevator")) return L("iron_block");
                    return null;
                case "GD/FoliageGD":
                case "GD/FoliageGD-Opaque":
                    if (Has("Bark")) return L("oak_log");
                    if (Has("Palm")) return L("jungle_leaves", null, FoliageTint);
                    if (Has("Pine")) return L("spruce_leaves", null, SpruceTint);
                    if (Has("Foliage Snow")) return L("snow");
                    if (Has("Ivy", "Vine")) return L("vine", null, FoliageTint);
                    if (Has("Fern")) return L("fern", null, GrassTint);
                    if (Has("moss")) return L("moss_block");
                    if (Has("Cactus")) return L("cactus_side");
                    if (Has("Leaves", "Leaf", "Bush", "Generic", "Foliage")) return L("oak_leaves", null, FoliageTint);
                    return null;
                case "GD/Water-GD":
                    return L("water_still");
                case "Lava":
                case "LavaStream":
                    return new Look { Side = "lava_still", Top = "lava_still", Animated = true };
                case "W/Peak_Glass":
                    if (Has("Plane Glass", "Airport")) return new Look { Side = "glass", Top = "glass", Glass = true };
                    return null;
                case "Universal Render Pipeline/Lit":
                    if (Has("Airport Glass")) return new Look { Side = "glass", Top = "glass", Glass = true };
                    return null;
                case "Peak/Grass":
                    return L("grass_block_top", null, GrassTint, GrassTint);
            }
            return null;
        }

        internal static Look NetherLook(Look o, string shader, bool gloom)
        {
            if (o == null || o.Animated || o.Glass) return null;   // (lava is Minecraft's already; glass stays)
            string s = o.Side;
            if (s == "water_still") return null;
            bool foliage = shader.StartsWith("GD/Foliage") || shader == "Peak/Grass";
            bool wood = s.EndsWith("_log") || s.EndsWith("_planks");
            if (gloom)
            {
                if (foliage) return s == "vine" ? L("twisting_vines") : L("warped_wart_block");
                if (wood) return L("warped_stem");
                if (s.StartsWith("basalt")) return L("basalt_side", "basalt_top");
                if (s == "blackstone") return L("blackstone", "soul_soil");
                return L("soul_soil", "soul_sand");
            }
            if (foliage) return s == "vine" ? L("weeping_vines") : shader == "Peak/Grass" ? L("crimson_nylium") : L("nether_wart_block");
            if (wood) return L("crimson_stem");
            if (s.StartsWith("basalt")) return null;
            // The volcano rock: netherrack, with magma in patches as in Minecraft's Nether (not whole floors): about a
            // third of its tops are magma blocks (MinePeakExport.TopAltAt)
            if (s == "blackstone") { var l = L("netherrack"); l.TopAlt = "magma"; return l; }
            if (s.Contains("sand")) return L("soul_sand");
            if (o.Top == "grass_block_top") return L("netherrack", "crimson_nylium");
            return L("netherrack");
        }
    }
}
