package tr.erdvyn.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers file hashes against the file's size and modification time, so a launch does not re-read a gigabyte of
 * unchanged jars. A stamp is only trusted when the caller asks for it ({@code trusted}); an explicit file audit
 * re-reads every byte. A changed size or timestamp always forces a fresh hash.
 */
final class HashCache {
    private record Stamp(long size, long modified, String sha256, String sha1) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HashCache SHARED = new HashCache(LauncherPaths.appRoot().resolve("hash-cache.json"));
    private final Path file;
    private final Map<String, Stamp> stamps = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    HashCache(Path file) { this.file = file; load(); }
    static HashCache shared() { return SHARED; }

    String sha256(Path path, boolean trusted) throws Exception { return hash(path, "SHA-256", trusted); }
    String sha1(Path path, boolean trusted) throws Exception { return hash(path, "SHA-1", trusted); }

    /** Records a hash computed while the file was written (a verified download), so it is not read back again. */
    void remember(Path path, String algorithm, String hex) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            store(path, attributes, algorithm, hex.toLowerCase(java.util.Locale.ROOT));
        } catch (Exception ignored) {}
    }

    /** Forgets every stamp: the next launch reads all files again (after a crash or an explicit audit). */
    void clear() { stamps.clear(); dirty = true; save(); }

    synchronized void save() {
        if (!dirty) return;
        try {
            ObjectNode root = JSON.createObjectNode();
            ObjectNode entries = root.putObject("files");
            stamps.forEach((key, stamp) -> {
                ObjectNode node = entries.putObject(key);
                node.put("size", stamp.size()).put("modified", stamp.modified());
                if (stamp.sha256() != null) node.put("sha256", stamp.sha256());
                if (stamp.sha1() != null) node.put("sha1", stamp.sha1());
            });
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            JSON.writeValue(temp.toFile(), root);
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.io.IOException atomicMoveUnsupported) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            dirty = false;
        } catch (Exception ignored) {
            // A cache that cannot be written only costs speed on the next launch.
        }
    }

    private String hash(Path path, String algorithm, boolean trusted) throws Exception {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        Stamp stamp = stamps.get(key(path));
        if (trusted && stamp != null && stamp.size() == attributes.size() && stamp.modified() == attributes.lastModifiedTime().toMillis()) {
            String known = "SHA-1".equals(algorithm) ? stamp.sha1() : stamp.sha256();
            if (known != null) return known;
        }
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1 << 17];
            for (int read; (read = input.read(buffer)) >= 0; ) if (read > 0) digest.update(buffer, 0, read);
        }
        String hex = HexFormat.of().formatHex(digest.digest());
        store(path, attributes, algorithm, hex);
        return hex;
    }

    private void store(Path path, BasicFileAttributes attributes, String algorithm, String hex) {
        String key = key(path);
        Stamp old = stamps.get(key);
        boolean same = old != null && old.size() == attributes.size() && old.modified() == attributes.lastModifiedTime().toMillis();
        String sha256 = "SHA-256".equals(algorithm) ? hex : same ? old.sha256() : null;
        String sha1 = "SHA-1".equals(algorithm) ? hex : same ? old.sha1() : null;
        stamps.put(key, new Stamp(attributes.size(), attributes.lastModifiedTime().toMillis(), sha256, sha1));
        dirty = true;
    }

    private static String key(Path path) { return path.toAbsolutePath().normalize().toString(); }

    private void load() {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 32L << 20) return;
            JsonNode entries = JSON.readTree(file.toFile()).path("files");
            entries.fields().forEachRemaining(entry -> {
                JsonNode node = entry.getValue();
                String sha256 = node.path("sha256").asText(null), sha1 = node.path("sha1").asText(null);
                stamps.put(entry.getKey(), new Stamp(node.path("size").asLong(-1), node.path("modified").asLong(-1), sha256, sha1));
            });
        } catch (Exception ignored) {
            stamps.clear(); // a damaged cache is simply rebuilt
        }
    }
}
