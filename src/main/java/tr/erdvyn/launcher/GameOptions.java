package tr.erdvyn.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

final class GameOptions {
    private static final Preferences PREFS = Preferences.userNodeForPackage(ErdvynLauncher.class);
    private final Map<String, String> values = new LinkedHashMap<>();
    private final Path file;

    GameOptions(Path gameDirectory) {
        file = gameDirectory.resolve("options.txt");
        load();
    }

    int ramGb() { return Math.max(2, Math.min(16, PREFS.getInt("minecraftRamGb", 8))); }
    void setRamGb(int value) { PREFS.putInt("minecraftRamGb", Math.max(2, Math.min(16, value))); }
    int renderDistance() { return integer("renderDistance", 16, 2, 64); }
    int simulationDistance() { return integer("simulationDistance", 10, 2, 32); }
    int maxFps() { return integer("maxFps", 120, 30, 260); }
    boolean fullscreen() { return bool("fullscreen", true); }
    boolean vsync() { return bool("enableVsync", true); }
    int guiScale() { return integer("guiScale", 2, 0, 6); }

    void setRenderDistance(int value) { set("renderDistance", Integer.toString(clamp(value, 2, 64))); }
    void setSimulationDistance(int value) { set("simulationDistance", Integer.toString(clamp(value, 2, 32))); }
    void setMaxFps(int value) { set("maxFps", Integer.toString(clamp(value, 30, 260))); }
    void setFullscreen(boolean value) { set("fullscreen", Boolean.toString(value)); }
    void setVsync(boolean value) { set("enableVsync", Boolean.toString(value)); }
    void setGuiScale(int value) { set("guiScale", Integer.toString(clamp(value, 0, 6))); }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int split = line.indexOf(':');
                if (split > 0) values.put(line.substring(0, split), line.substring(split + 1));
            }
        } catch (IOException ignored) {}
    }

    private void set(String key, String value) {
        values.put(key, value);
        try { save(); } catch (IOException ignored) {}
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        List<String> lines = new ArrayList<>(values.size());
        values.forEach((key, value) -> lines.add(key + ":" + value));
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private int integer(String key, int fallback, int min, int max) {
        try { return clamp(Integer.parseInt(values.getOrDefault(key, Integer.toString(fallback))), min, max); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private boolean bool(String key, boolean fallback) { return Boolean.parseBoolean(values.getOrDefault(key, Boolean.toString(fallback))); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
