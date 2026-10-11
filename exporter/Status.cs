using System;
using System.IO;
using Newtonsoft.Json.Linq;

namespace MinePeakExporter
{
    /// <summary>
    /// The export's progress, for MinePeak (in Minecraft) to show while it waits:
    /// %APPDATA%/MinePeak/export_status.json = {state: starting|exporting|done|error, step, percent (0-100 overall),
    /// message, island (the finished folder), pid, time}.
    /// </summary>
    internal static class Status
    {
        public static string FilePath => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "MinePeak", "export_status.json");

        // the steps and how much of the whole each is (the airport's pictures for the panorama, then the segments of the
        // climb; The Kiln has the summit)
        static readonly (string name, int weight)[] Steps = { ("panorama", 2), ("beach", 18), ("tropics", 14), ("alpine", 26), ("caldera", 25), ("thekiln", 15) };

        public static void Set(string state, string step, int percent, string message, string island = null)
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(FilePath));
                var o = new JObject
                {
                    ["state"] = state, ["step"] = step, ["percent"] = percent, ["message"] = message,
                    ["pid"] = System.Diagnostics.Process.GetCurrentProcess().Id, ["time"] = DateTime.Now.ToString("o"),
                };
                if (island != null) o["island"] = island;
                File.WriteAllText(FilePath + ".tmp", o.ToString());
                if (File.Exists(FilePath)) File.Delete(FilePath);
                File.Move(FilePath + ".tmp", FilePath);
            }
            catch (Exception) { }
        }

        /// <summary>Progress within a step (an area's scan), as the overall percent.</summary>
        public static void Area(string area, int areaPercent)
        {
            int done = 0, total = 0, weight = 0;
            foreach (var s in Steps) total += s.weight;
            foreach (var s in Steps)
            {
                if (s.name == area.ToLowerInvariant()) { weight = s.weight; break; }
                done += s.weight;
            }
            int overall = (int)Math.Round(100.0 * (done + weight * areaPercent / 100.0) / total);
            Set("exporting", area, Math.Min(99, overall), $"Exporting today's PEAK island: {Pretty(area)} {areaPercent}%");
        }

        static string Pretty(string area)
        {
            switch (area.ToLowerInvariant())
            {
                case "thekiln": return "the Kiln";
                case "caldera": return "the volcano";
                case "panorama": return "the airport's pictures";
                default: return char.ToUpperInvariant(area[0]) + area.Substring(1).ToLowerInvariant();
            }
        }
    }
}
