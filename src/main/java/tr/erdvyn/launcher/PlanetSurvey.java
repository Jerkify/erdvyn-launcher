package tr.erdvyn.launcher;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;

/**
 * What the player has seen of the planet, read-only: erdvyn_ui's survey ({@code erdvyn_survey/<world>/minecraft_overworld.bin},
 * written by its {@code SurveyStore}) and Xaero's waypoints for the same world. Nothing here is ever written or sent.
 * <p>
 * The planet is Erdvyn World's Nations layout: x runs once round it (-20000..20000, wrapping), z from the north pole
 * (-20000) to the south pole (20000). The survey keeps one byte per 2 x 2 blocks ({@code mapColorId << 2 | shade},
 * 0 = never seen) in tiles of 320 blocks counted from the north-west corner, so the planet is 125 x 125 tiles.
 */
final class PlanetSurvey {
    static final int SPAN = 40000, MIN = -20000, PIXEL = 2, PX = SPAN / PIXEL, TILE = 320, SIDE = TILE / PIXEL, TILES = SPAN / TILE;
    private static final int MAGIC = 0x45535631, MAX_TILES = 4096, MAX_WAYPOINTS = 400;
    private static final String FILE = "minecraft_overworld.bin";

    record Waypoint(String name, int x, int z, int color, boolean death) {}

    /**
     * One read of a world. {@code tiles} is indexed {@code tz * TILES + tx} (null = nothing seen there); {@code coarse}
     * is the same survey at the planet atlas' resolution, for the whole-planet view; {@code fraction} is the share of
     * the planet's surface seen (area on the sphere, so the poles do not count extra).
     */
    record Snapshot(String world, byte[][] tiles, byte[] coarse, int tileCount, long seenPixels, double fraction, double centreX, double centreZ,
                    List<Waypoint> waypoints, long stamp, long savedAt) {
        boolean empty() { return tileCount == 0; }
        /** Packed survey byte at survey pixel (su, sv), 0 when unseen. */
        int at(int su, int sv) {
            byte[] tile = tiles[(sv / SIDE) * TILES + su / SIDE];
            return tile == null ? 0 : tile[(sv % SIDE) * SIDE + su % SIDE] & 255;
        }
    }
    static final Snapshot EMPTY = new Snapshot("", new byte[TILES * TILES][], new byte[PlanetGlobe.ATLAS * PlanetGlobe.ATLAS], 0, 0, 0, 0, 0, List.of(), 0, 0);

    private final Path root, xaero;
    private volatile Snapshot current = EMPTY;
    private volatile List<String> worlds = List.of();
    private volatile String selected;
    private volatile boolean loading;
    private volatile String error = "";
    private long nextCheck;

    PlanetSurvey() { this(LauncherPaths.managedInstance()); }
    PlanetSurvey(Path instance) { root = instance.resolve("erdvyn_survey"); xaero = instance.resolve("xaero").resolve("minimap"); }

    Snapshot current() { return current; }
    List<String> worlds() { return worlds; }
    String error() { return error; }
    /** Shows the next surveyed world (servers and single-player saves each keep their own). */
    void next() {
        List<String> all = worlds;
        if (all.size() < 2) return;
        selected = all.get(Math.floorMod(all.indexOf(current.world()) + 1, all.size()));
        nextCheck = 0;
    }
    /** For UI tests: a fixed snapshot, never replaced by disk reads. */
    void use(Snapshot snapshot) { current = snapshot; worlds = List.of(snapshot.world()); nextCheck = Long.MAX_VALUE; }

    /** Looks for a changed survey every few seconds and reads it on a virtual thread; the painter never waits. */
    void refresh(long now) {
        if (now < nextCheck || loading) return;
        nextCheck = now + 4000;
        loading = true;
        Thread.startVirtualThread(() -> {
            try {
                List<String> found = new ArrayList<>();
                String latest = null; long latestTime = -1;
                if (Files.isDirectory(root)) try (var dirs = Files.list(root)) {
                    for (Path dir : dirs.toList()) {
                        Path file = dir.resolve(FILE);
                        if (!Files.isRegularFile(file)) continue;
                        found.add(dir.getFileName().toString());
                        long time = Files.getLastModifiedTime(file).toMillis();
                        if (time > latestTime) { latestTime = time; latest = dir.getFileName().toString(); }
                    }
                }
                found.sort(Comparator.naturalOrder());
                worlds = List.copyOf(found);
                String world = selected != null && found.contains(selected) ? selected : latest;
                if (world == null) { current = EMPTY; error = ""; return; }
                Path file = root.resolve(world).resolve(FILE), marks = waypointDir(world);
                long stamp = Files.getLastModifiedTime(file).toMillis() * 31 + Files.size(file) + lastModified(marks);
                if (world.equals(current.world()) && stamp == current.stamp()) return;
                current = read(file, world, marks, stamp);
                error = "";
            } catch (IOException | RuntimeException failed) {
                error = failed.getClass().getSimpleName();
                LauncherLog.write("Survey read failed: " + failed);
            } finally { loading = false; }
        });
    }

    /** Xaero keeps a server's waypoints under "Multiplayer_<address>" and a save's under its folder name; the overworld is dim%0. */
    private Path waypointDir(String world) {
        String name = world.startsWith("server-") ? "Multiplayer_" + world.substring(7) : world.startsWith("local-") ? world.substring(6) : world;
        return xaero.resolve(name).resolve("dim%0");
    }
    private static long lastModified(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return 0;
        long latest = 0;
        try (var files = Files.list(dir)) { for (Path file : files.toList()) latest = Math.max(latest, Files.getLastModifiedTime(file).toMillis()); }
        return latest;
    }

    static Snapshot read(Path file, String world, Path marks, long stamp) throws IOException {
        byte[][] tiles = new byte[TILES * TILES][];
        try (var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != MAGIC || in.readInt() != TILE) throw new IOException("not a planet survey");
            int count = in.readInt();
            if (count < 0) throw new IOException("bad tile count");
            for (int i = 0; i < Math.min(count, MAX_TILES); i++) {
                long key = in.readLong();
                byte[] px = new byte[SIDE * SIDE];
                in.readFully(px);
                int tx = (int) (key >> 32), tz = (int) key;
                if (tx >= 0 && tx < TILES && tz >= 0 && tz < TILES) tiles[tz * TILES + tx] = px; // the planet frame; anything else is not this world's ground
            }
        }
        return snapshot(world, tiles, waypoints(marks), stamp, Files.getLastModifiedTime(file).toMillis());
    }

    static Snapshot snapshot(String world, byte[][] tiles, List<Waypoint> waypoints, long stamp, long savedAt) {
        int a = PlanetGlobe.ATLAS, count = 0;
        long pixels = 0;
        byte[] coarse = new byte[a * a];
        double seen = 0, total = 0, sx = 0, sz = 0, weight = 0;
        for (int sv = 0; sv < PX; sv++) total += Math.cos(latitude(sv));
        total *= PX;
        for (int t = 0; t < tiles.length; t++) {
            byte[] tile = tiles[t];
            if (tile == null) continue;
            int tx = t % TILES, tz = t / TILES, n = 0;
            for (int i = 0; i < tile.length; i++) {
                if (tile[i] == 0) continue;
                int su = tx * SIDE + i % SIDE, sv = tz * SIDE + i / SIDE;
                coarse[(int) ((long) sv * a / PX) * a + (int) ((long) su * a / PX)] = tile[i];
                n++;
            }
            if (n == 0) continue;
            count++;
            pixels += n;
            double area = n * Math.cos(latitude(tz * SIDE + SIDE / 2)), angle = (tx + .5) / TILES * 2 * Math.PI;
            seen += area; sx += Math.cos(angle) * area; sz += Math.sin(angle) * area; weight += (tz + .5) * TILE * area;
        }
        double centreX = count == 0 ? -3960 : MIN + (Math.atan2(sz, sx) / (2 * Math.PI) + 1) % 1 * SPAN; // empty: the planet's spawn
        double centreZ = count == 0 ? 2347 : MIN + weight / seen;
        return new Snapshot(world, tiles, coarse, count, pixels, total == 0 ? 0 : seen / total, centreX, centreZ, List.copyOf(waypoints), stamp, savedAt);
    }
    static double latitude(int sv) { return ((sv + .5) / PX - .5) * Math.PI; }

    /** Xaero's waypoint files: "waypoint:name:initials:x:y:z:color:disabled:type:set:...", with ':' in a name written as §§. */
    static List<Waypoint> waypoints(Path dir) {
        List<Waypoint> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".txt")).sorted().toList()) {
                if (Files.size(file) > 1 << 20) continue;
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.startsWith("waypoint:") || out.size() >= MAX_WAYPOINTS) continue;
                    String[] f = line.split(":");
                    if (f.length < 8 || "true".equals(f[7])) continue;
                    try {
                        String name = ErdvynApiClient.clip(f[1].replace("§§", ":"), 40);
                        int x = Integer.parseInt(f[3]), z = Integer.parseInt(f[5]), color = Math.floorMod(Integer.parseInt(f[6]), 16);
                        x = MIN + Math.floorMod(x - MIN, SPAN); // the same ground one period round
                        z = Math.max(MIN, Math.min(MIN + SPAN - 1, z));
                        out.add(new Waypoint(name, x, z, color, name.startsWith("gui.xaero_deathpoint")));
                    } catch (NumberFormatException ignored) { /* a hand-edited line: skip it */ }
                }
            }
        } catch (IOException ignored) { /* no waypoints is fine */ }
        return out;
    }

    /** UI tests and captures: a few days of travel from the spawn over real land, and four waypoints. */
    static Snapshot sample(byte[] atlas) {
        byte[][] tiles = new byte[TILES * TILES][];
        Random random = new Random(7);
        double x = -3960, z = 2347, heading = .4;
        for (int step = 0; step < 150; step++) {
            heading += (random.nextDouble() - .5) * .9;
            x += Math.cos(heading) * 150; z += Math.sin(heading) * 110;
            for (int dz = -200; dz <= 200; dz += PIXEL) for (int dx = -200; dx <= 200; dx += PIXEL) {
                if (dx * dx + dz * dz > 200 * 200) continue;
                int su = Math.floorMod((int) Math.floor((x + dx - MIN) / PIXEL), PX), sv = (int) Math.floor((z + dz - MIN) / PIXEL);
                if (sv < 0 || sv >= PX) continue;
                int a = PlanetGlobe.ATLAS, ground = atlas[(sv * a / PX) * a + su * a / PX] & 255, kind = ground >> 6, relief = ground & 63;
                int color = kind <= 1 ? 12 : kind == 3 ? 8 : relief < 22 ? 7 : relief < 40 ? 1 : relief < 52 ? 27 : 2; // water, snow, forest, grass, scrub, sand
                int t = (sv / SIDE) * TILES + su / SIDE;
                if (tiles[t] == null) tiles[t] = new byte[SIDE * SIDE];
                tiles[t][(sv % SIDE) * SIDE + su % SIDE] = (byte) (color << 2 | (random.nextInt(9) == 0 ? random.nextInt(3) : 1));
            }
        }
        List<Waypoint> marks = List.of(new Waypoint("Base", -3900, 2300, 14, false), new Waypoint("Iron ridge", -2600, 3100, 11, false),
                new Waypoint("Harbour", -1800, 1500, 9, false), new Waypoint("gui.xaero_deathpoint", -2200, 2900, 0, true));
        return snapshot("server-play.erdvyn.net", tiles, marks, 1, System.currentTimeMillis() - 2 * 3600_000L);
    }
}
