package pl.halovisuals;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.*;
import java.util.*;

public final class ModuleManager {
    public static final List<Module> ALL = new ArrayList<>();
    private static final Map<String, Module> MAP = new HashMap<>();
    public static final String[] CATS = {"Combat", "HUD", "Animations", "Sound", "World", "Client"};
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("halovisuals.json");

    /** Discord application (client) id for Rich Presence - create one at discord.com/developers and paste it into the config. */
    public static String discordAppId = "";

    private ModuleManager() {}

    public static Module get(String n) { return MAP.get(n); }

    private static Module add(String cat, String name, boolean def, Setting... s) {
        Module m = new Module(cat, name, true, def);
        m.settings.addAll(Arrays.asList(s));
        ALL.add(m); MAP.put(name, m);
        return m;
    }

    private static Setting style(String n, int idx) {
        return Setting.mode(n, idx, "Angel Dust", "Star Sparks", "Golden Petals", "Crystals", "Snowflakes", "Crimson Sparks", "Mixed");
    }

    static {
        // ---- Combat ----
        add("Combat", "Hit Marker", true, Setting.slider("Size", 3, 12, 6), Setting.slider("Duration", 100, 800, 350));
        add("Combat", "Damage Numbers", false, Setting.slider("Scale", 0.5, 2.0, 1.0));
        add("Combat", "Hit Particles", false, style("Style", 6), Setting.slider("Amount", 1, 80, 28));
        add("Combat", "Hit Bubbles", false, Setting.slider("Amount", 1, 30, 8));
        add("Combat", "Target ESP", false,
                Setting.mode("Style", 0, "Angel Rings", "Soul", "Square", "Crystal Orbit"),
                Setting.mode("Palette", 0, "Golden", "Soul Blue", "Rose"),
                Setting.slider("Rings", 1, 4, 3), Setting.slider("Speed", 0.5, 3.0, 1.5), Setting.toggle("Glow", true));
        add("Combat", "Kill Effect", false);
        add("Combat", "Death Effect", false);
        add("Combat", "Totem Pop", false);
        add("Combat", "Hit Color", false, Setting.mode("Color", 0, "Gold", "White", "Rose"));
        add("Combat", "Hitbox Customizer", false,
                Setting.slider("Range", 8, 64, 32), Setting.slider("Expand", 0, 0.5, 0.0), Setting.toggle("Fill", true));
        // ---- HUD ----
        add("HUD", "Target HUD", true, Setting.toggle("Equipment", true), Setting.slider("Scale", 0.6, 2.0, 1.0),
                Setting.hidden("X", 0.58), Setting.hidden("Y", 0.60));
        add("HUD", "Watermark", true);
        add("HUD", "Dynamic Island", true);
        add("HUD", "Potions", false);
        add("HUD", "Cooldowns", false);
        add("HUD", "Combo Counter", false);
        add("HUD", "Cooldown Ring", false);
        add("HUD", "Crosshair", false,
                Setting.mode("Style", 0, "Cross", "Dot", "Circle"), Setting.slider("Size", 2, 10, 5), Setting.slider("Gap", 0, 8, 3));
        // ---- Animations ----
        add("Animations", "Swing Animations", false,
                Setting.mode("Style", 0, "Smooth Slash", "Orbit", "Float", "Spin"), Setting.slider("Strength", 0.5, 2.0, 1.0));
        add("Animations", "No Hurt Cam", false);
        // ---- Sound ----
        add("Sound", "Hit Sounds", true,
                Setting.mode("Style", 1, "silent", "halo", "bell", "bonk"), Setting.slider("Loudness", 0.1, 3.0, 2.0));
        add("Sound", "Kill Sound", false, Setting.slider("Loudness", 0.1, 3.0, 2.0));
        // ---- World ----
        add("World", "Jump Circles", false);
        add("World", "Jump Particles", false, style("Style", 0), Setting.slider("Amount", 1, 80, 30));
        add("World", "Ambient Particles", false, style("Style", 6), Setting.slider("Amount", 1, 20, 5));
        add("World", "Trails", false, style("Style", 0));
        add("World", "Halo", true, Setting.slider("Radius", 0.2, 0.5, 0.32), Setting.toggle("Glow", true), Setting.toggle("Crystals", true));
        add("World", "China Hat", false, Setting.slider("Radius", 0.3, 0.9, 0.55));
        add("World", "Custom Sky", false,
                Setting.mode("Style", 0, "Dawn Gold", "Heaven White", "Seraph Rose", "Twilight"), Setting.slider("Intensity", 0.0, 1.0, 0.8));
        add("World", "Atmosphere", false,
                Setting.toggle("Lock Time", true), Setting.slider("Time", 0, 24000, 1000), Setting.toggle("Clear Weather", true));
        add("World", "Cinematic Text", false);
        add("World", "Motion Blur", false, Setting.slider("Strength", 0.1, 1.0, 0.5));
        add("World", "Color Saturation", false,
                Setting.mode("Style", 0, "Golden Hour", "Rose Quartz", "Ice"), Setting.slider("Strength", 0.1, 1.0, 0.5));
        add("World", "Aspect Ratio", false, Setting.slider("Ratio", 0.8, 3.0, 1.78));
        add("World", "Fullbright", false);
        // ---- Client ----
        add("Client", "Click GUI", true, Setting.key("Key", 73), Setting.toggle("UI Sounds", true), Setting.slider("UI Volume", 0.1, 1.0, 0.6));
        add("Client", "Performance", false, Setting.slider("Particle Limit", 200, 4000, 500), Setting.toggle("Glow Layers", false));
        add("Client", "Discord Presence", false, Setting.toggle("ShowServer", false));
        add("Client", "Halo Nametag", false);
        add("Client", "Cosmetics", false, Setting.toggle("Wings", true), Setting.slider("Wing Size", 0.6, 1.6, 1.0), Setting.toggle("Orbit Stars", true));
    }

    public static void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("_discordAppId", discordAppId);
            for (Module m : ALL) {
                JsonObject o = new JsonObject();
                o.addProperty("enabled", m.enabled);
                JsonObject st = new JsonObject();
                for (Setting s : m.settings) st.addProperty(s.name, s.value);
                o.add("settings", st);
                root.add(m.name, o);
            }
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(root));
        } catch (Exception e) { e.printStackTrace(); }
    }

    public static void load() {
        try {
            if (!Files.exists(FILE)) { save(); return; }
            JsonObject root = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
            if (root.has("_discordAppId")) discordAppId = root.get("_discordAppId").getAsString();
            for (Module m : ALL) {
                if (!root.has(m.name)) continue;
                JsonObject o = root.getAsJsonObject(m.name);
                if (o.has("enabled")) { m.enabled = o.get("enabled").getAsBoolean(); m.anim = m.enabled ? 1f : 0f; }
                if (o.has("settings")) {
                    JsonObject st = o.getAsJsonObject("settings");
                    for (Setting s : m.settings) if (st.has(s.name)) s.value = st.get(s.name).getAsDouble();
                }
            }
            save(); // writes new keys (e.g. _discordAppId) into old config files
        } catch (Exception e) { e.printStackTrace(); }
    }
}
