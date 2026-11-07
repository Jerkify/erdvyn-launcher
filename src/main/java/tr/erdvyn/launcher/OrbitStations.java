package tr.erdvyn.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Erdvyn World's orbital stations as the installed pack defines them: {@code data/<ns>/orbit_station/*.json} inside the
 * instance's {@code erdvyn_world-*.jar} (the overworld x/z each one hangs over, its y in the orbit level, active or
 * abandoned), named from the same jar's en_us lang file. Read-only; empty when the pack is not installed.
 */
final class OrbitStations {
    /** One station over world (x, z); {@code lift} is its height above the ground in planet radii. */
    record Station(String nameEn, int x, int z, double lift, boolean active) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static volatile List<Station> stations = List.of();
    private static volatile String source = "";
    private static volatile boolean loading;
    private static long nextCheck;

    private OrbitStations() {}

    /**
     * Height above the ground in planet radii for a station at orbit-level {@code y}: players fall out of the orbit level
     * at y -1000 (Orbit.FALL_Y) into the overworld at y 400 (Orbit.ENTRY_Y), 337 above the sea at 63, so the
     * station stands y + 1337 blocks up; the planet's radius is its 40000-block circumference over 2 pi.
     */
    static double lift(int y) { return (y + 1337) / (PlanetSurvey.SPAN / (2 * Math.PI)); }

    /** The stations, rechecking the mods folder every ten seconds on a virtual thread (the painter never waits). */
    static List<Station> current() {
        long now = System.currentTimeMillis();
        if (now >= nextCheck && !loading) {
            nextCheck = now + 10_000;
            loading = true;
            Thread.startVirtualThread(() -> {
                try { reload(LauncherPaths.managedInstance().resolve("mods")); }
                catch (IOException | RuntimeException failed) { LauncherLog.write("Orbit stations not read: " + failed); }
                finally { loading = false; }
            });
        }
        return stations;
    }

    private static void reload(Path mods) throws IOException {
        Path jar = null;
        if (Files.isDirectory(mods)) try (var files = Files.list(mods)) {
            jar = files.filter(p -> p.getFileName().toString().matches("erdvyn_world-.*\\.jar")).findFirst().orElse(null);
        }
        String stamp = jar == null ? "" : jar + "|" + Files.getLastModifiedTime(jar).toMillis();
        if (stamp.equals(source)) return;
        stations = jar == null ? List.of() : read(jar);
        source = stamp;
    }

    static List<Station> read(Path jar) throws IOException {
        List<Station> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            JsonNode en = json(zip, "assets/erdvyn_world/lang/en_us.json");
            for (ZipEntry entry : zip.stream().toList()) {
                if (!entry.getName().matches("data/[^/]+/orbit_station/[^/]+\\.json")) continue;
                JsonNode station = json(zip, entry.getName()), center = station.path("center");
                if (center.size() != 3) continue;
                String name = station.path("name").asText(), english = en.path(name).asText(name);
                out.add(new Station(english, center.get(0).asInt(), center.get(2).asInt(),
                    lift(center.get(1).asInt()), "active".equals(station.path("state").asText())));
            }
        }
        return List.copyOf(out);
    }

    private static JsonNode json(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return JSON.missingNode();
        try (InputStream in = zip.getInputStream(entry)) { return JSON.readTree(in); }
    }
}
