package tr.erdvyn.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

final class PackService {
    record Progress(double value, String line) {}
    record Result(int verified, int downloaded, int kept, int failed, String version) {}
    record Summary(int files, int mods, int configs, int resourcepacks, int shaderpacks, long bytes, String version) {
        static Summary empty() { return new Summary(0, 0, 0, 0, 0, 0, "--"); }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INSTALL_STATE = ".erdvyn-installed.json";
    private static final String CLEANUP_STATE = ".erdvyn-pack-cleanups.json";
    private static final Set<String> PRESERVED_FILES = Set.of(
            "options.txt", "servers.dat", "servers.dat_old", "command_history.txt",
            "patchouli_data.json", "ponders_watched.json", "trashslotsavestate.json",
            "usernamecache.json", "usercache.json", "vss-lod-presence.dat"
    );
    private static final List<String> PRESERVED_ROOTS = List.of(
            ".sable", ".voxy", "blueprints", "datapacks", "dynamic-data-pack-cache",
            "dynamic-resource-pack-cache", "emotes", "figura", "moddata", "profileimage",
            "schematics", "xaero", "xaerowaypoints_backup240807"
    );
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();

    Result verifyAndRepair(Consumer<Progress> progress) throws Exception {
        LauncherPaths.prepareInstance();
        String manifestUrl = LauncherConfig.packManifestUrl();
        if (manifestUrl == null || manifestUrl.isBlank()) {
            throw new IOException("Pack manifest URL is not configured; a managed installation cannot be downloaded");
        }
        progress.accept(new Progress(.02, "MANIFEST  " + manifestUrl));
        HttpRequest request = freshManifestRequest(manifestUrl, Duration.ofSeconds(30));
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IOException("Manifest HTTP " + response.statusCode());
        Path cachedManifest=LauncherPaths.appRoot().resolve("active-pack-manifest.json");Files.createDirectories(cachedManifest.getParent());Files.writeString(cachedManifest,response.body());
        JsonNode root = JSON.readTree(response.body());
        String version = root.path("version").asText("remote");
        JsonNode files = root.path("files");
        if (!files.isArray()) throw new IOException("Manifest files array is missing");
        Path game = LauncherPaths.gameDirectory().toAbsolutePath().normalize();
        removeRequestedPaths(game, root, LauncherPaths.appRoot().resolve(CLEANUP_STATE), progress);
        int total = files.size(), verified = 0, downloaded = 0, kept = 0, failed = 0, index = 0;
        Set<Path> managedFiles = new HashSet<>();
        for (JsonNode entry : files) {
            index++;
            String relative = entry.path("path").asText(), expected = entry.path("sha256").asText().toLowerCase(Locale.ROOT);
            Path target = game.resolve(relative).normalize();
            if (relative.isBlank() || !target.startsWith(game)) throw new IOException("Unsafe manifest path: " + relative);
            managedFiles.add(target);
            double base = .04 + .94 * (index - 1) / Math.max(1, total);
            if (Files.isRegularFile(target) && shouldPreserveExisting(root, entry, relative)) {
                kept++;
                progress.accept(new Progress(base, "[KEEP] " + relative));
                continue;
            }
            if (Files.isRegularFile(target) && !expected.isBlank() && expected.equals(sha256(target))) {
                verified++;
                progress.accept(new Progress(base, "[OK] " + relative));
                continue;
            }
            String url = entry.path("url").asText();
            if (url.isBlank()) {
                failed++;
                progress.accept(new Progress(base, "[MISSING] " + relative));
                continue;
            }
            progress.accept(new Progress(base, "[GET] " + relative));
            try {
                download(URI.create(manifestUrl).resolve(url).toString(), target, expected);
                downloaded++;
                progress.accept(new Progress(base + .9 / Math.max(1, total), "[SAVED] " + relative));
            } catch (Exception error) {
                failed++;
                progress.accept(new Progress(base, "[FAIL] " + relative + " / " + error.getMessage()));
            }
        }
        JsonNode enforceRoots = root.path("enforce_roots");
        if (enforceRoots.isArray()) for (JsonNode rootName : enforceRoots) {
            Path enforced = game.resolve(rootName.asText()).normalize();
            if (!enforced.startsWith(game) || !Files.isDirectory(enforced)) continue;
            try (var stream = Files.walk(enforced)) {
                for (Path actual : stream.filter(Files::isRegularFile).toList()) {
                    if (actual.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar") && !managedFiles.contains(actual.toAbsolutePath().normalize())) {
                        Path quarantineRoot = LauncherPaths.appRoot().resolve("quarantine").resolve("unmanaged-pack-files").toAbsolutePath().normalize();
                        Path quarantine = quarantineRoot.resolve(game.relativize(actual).toString()).normalize();
                        if (!quarantine.startsWith(quarantineRoot)) throw new IOException("Unsafe quarantine path: " + actual);
                        Files.createDirectories(quarantine.getParent());
                        Files.move(actual, quarantine, StandardCopyOption.REPLACE_EXISTING);
                        progress.accept(new Progress(.98, "[QUARANTINED] " + game.relativize(actual).toString().replace('\\', '/')));
                    }
                }
            }
        }
        progress.accept(new Progress(1, failed == 0 ? "PACKAGE VERIFIED" : "PACKAGE HAS " + failed + " ERRORS"));
        if (failed == 0) writeInstallState(version, cachedManifest, total);
        return new Result(verified, downloaded, kept, failed, version);
    }

    boolean isInstalled() {
        Path state = LauncherPaths.managedInstance().resolve(INSTALL_STATE);
        if (!Files.isRegularFile(state)) return false;
        try {
            JsonNode root = JSON.readTree(state.toFile());
            return root.path("complete").asBoolean(false)
                    && root.path("files").asInt(0) > 0
                    && Files.isDirectory(LauncherPaths.managedInstance().resolve("mods"));
        } catch (Exception ignored) {
            return false;
        }
    }

    boolean hasRemoteManifest() {
        String url = LauncherConfig.packManifestUrl();
        return url != null && !url.isBlank();
    }

    Summary summary() throws Exception {
        String manifestUrl = LauncherConfig.packManifestUrl();
        if (manifestUrl != null && !manifestUrl.isBlank()) {
            try {
                HttpRequest request = freshManifestRequest(manifestUrl, Duration.ofSeconds(20));
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) return summarizeManifest(JSON.readTree(response.body()));
            } catch (Exception ignored) {

            }
        }
        return summarizeLocal();
    }

    private Summary summarizeManifest(JsonNode root) throws IOException {
        JsonNode files = root.path("files");
        if (!files.isArray()) throw new IOException("Manifest files array is missing");
        int count = 0, mods = 0, configs = 0, resourcepacks = 0, shaderpacks = 0;
        long bytes = 0;
        for (JsonNode entry : files) {
            String path = entry.path("path").asText("").replace('\\', '/').toLowerCase(Locale.ROOT);
            count++;
            bytes += Math.max(0, entry.path("size").asLong(0));
            if (path.startsWith("mods/")) mods++;
            else if (path.startsWith("config/") || path.startsWith("defaultconfigs/")) configs++;
            else if (path.startsWith("resourcepacks/")) resourcepacks++;
            else if (path.startsWith("shaderpacks/")) shaderpacks++;
        }
        return new Summary(count, mods, configs, resourcepacks, shaderpacks, bytes, root.path("version").asText("remote"));
    }

    private Summary summarizeLocal() throws IOException {
        Path game = LauncherPaths.gameDirectory();
        String[] roots = {"mods", "config", "defaultconfigs", "resourcepacks", "shaderpacks"};
        int[] counts = new int[roots.length];
        long bytes = 0;
        for (int index = 0; index < roots.length; index++) {
            Path root = game.resolve(roots[index]);
            if (!Files.isDirectory(root)) continue;
            try (var stream = Files.walk(root)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    counts[index]++;
                    bytes += Files.size(file);
                }
            }
        }
        int files = counts[0] + counts[1] + counts[2] + counts[3] + counts[4];
        return new Summary(files, counts[0], counts[1] + counts[2], counts[3], counts[4], bytes, "local");
    }

    private void writeInstallState(String version, Path manifest, int fileCount) throws Exception {
        ObjectNode state = JSON.createObjectNode();
        state.put("complete", true);
        state.put("version", version);
        state.put("files", fileCount);
        state.put("manifest_sha256", sha256(manifest));
        state.put("verified_at", Instant.now().toString());
        Path target = LauncherPaths.managedInstance().resolve(INSTALL_STATE);
        Path temp = target.resolveSibling(INSTALL_STATE + ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), state);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveUnsupported) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }


    private void download(String url, Path target, String expectedSha256) throws Exception {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".erdvyn-download");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3)).GET().build();
        HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(temp));
        if (response.statusCode() / 100 != 2) {
            Files.deleteIfExists(temp);
            throw new IOException("HTTP " + response.statusCode());
        }
        if (!expectedSha256.isBlank() && !expectedSha256.equalsIgnoreCase(sha256(temp))) {
            Files.deleteIfExists(temp);
            throw new IOException("SHA-256 mismatch");
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static HttpRequest freshManifestRequest(String manifestUrl, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(manifestUrl))
                .timeout(timeout)
                .header("Cache-Control", "no-cache, no-store, max-age=0")
                .header("Pragma", "no-cache")
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 128];
            for (int read; (read = input.read(buffer)) >= 0; ) if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String activeManifestSha256() throws Exception {
        Path remote=LauncherPaths.appRoot().resolve("active-pack-manifest.json"),local=LauncherPaths.appRoot().resolve("local-pack-audit.json");
        Path selected=Files.isRegularFile(remote)?remote:local;if(!Files.isRegularFile(selected))throw new IOException("No verified pack manifest is available");return sha256(selected);
    }

    static boolean shouldPreserveExisting(JsonNode manifest, JsonNode entry, String relative) {
        String path = normalizeManifestPath(relative);
        if (path.isBlank()) return false;
        // Player-edited settings are authoritative once they exist. A malformed or
        // outdated manifest must never opt them back into pack management.
        if (isUserOwnedSettingsPath(path)) return true;
        if (entry.path("managed").asBoolean(false) || containsPath(manifest.path("managed_paths"), path)) return false;
        JsonNode preserveRoots = manifest.path("preserve_roots");
        if (preserveRoots.isArray()) for (JsonNode root : preserveRoots) {
            if (isUnderRoot(path, normalizeManifestPath(root.asText()))) return true;
        }
        return false;
    }

    static void removeRequestedPaths(Path game, JsonNode manifest, Consumer<Progress> progress) throws IOException {
        removeRequestedPaths(game, manifest, null, progress);
    }

    static void removeRequestedPaths(Path game, JsonNode manifest, Path cleanupState, Consumer<Progress> progress) throws IOException {
        Path root = game.toAbsolutePath().normalize();
        JsonNode paths = manifest.path("remove_paths");
        if (!paths.isArray()) return;
        String cleanupId = manifest.path("cleanup_id").asText("").strip();
        Set<String> completed = readCompletedCleanups(cleanupState);
        if (!cleanupId.isBlank() && completed.contains(cleanupId)) return;
        for (JsonNode value : paths) {
            String relative = normalizeManifestPath(value.asText());
            if (isUserOwnedSettingsPath(relative)) {
                progress.accept(new Progress(.03, "[KEEP] " + relative));
                continue;
            }
            if (!isAllowedRemovalPath(relative)) throw new IOException("Unsafe pack removal path: " + value.asText());
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root) || target.equals(root)) throw new IOException("Unsafe pack removal path: " + value.asText());
            if (!Files.exists(target)) continue;
            try (var stream = Files.walk(target)) {
                for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
            progress.accept(new Progress(.03, "[REMOVED] " + relative));
        }
        if (!cleanupId.isBlank() && cleanupState != null) {
            completed.add(cleanupId);
            writeCompletedCleanups(cleanupState, completed);
        }
    }

    private static Set<String> readCompletedCleanups(Path state) throws IOException {
        Set<String> completed = new HashSet<>();
        if (state == null || !Files.isRegularFile(state)) return completed;
        JsonNode values = JSON.readTree(state.toFile()).path("completed");
        if (values.isArray()) for (JsonNode value : values) if (!value.asText().isBlank()) completed.add(value.asText());
        return completed;
    }

    private static void writeCompletedCleanups(Path state, Set<String> completed) throws IOException {
        Files.createDirectories(state.getParent());
        ObjectNode root = JSON.createObjectNode();
        var values = root.putArray("completed");
        completed.stream().sorted().forEach(values::add);
        Path temp = state.resolveSibling(state.getFileName() + ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), root);
        try {
            Files.move(temp, state, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveUnsupported) {
            Files.move(temp, state, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isAllowedRemovalPath(String path) {
        if (path.isBlank() || path.contains("../") || path.equals("..")) return false;
        return isUnderRoot(path, "mods") || isUnderRoot(path, "config") || isUnderRoot(path, "defaultconfigs") || isUnderRoot(path, "resourcepacks") || isUnderRoot(path, "shaderpacks") || isUnderRoot(path, "kubejs");
    }

    private static boolean containsPath(JsonNode paths, String expected) {
        if (!paths.isArray()) return false;
        for (JsonNode path : paths) if (expected.equals(normalizeManifestPath(path.asText()))) return true;
        return false;
    }

    private static boolean isUserOwnedSettingsPath(String path) {
        if (PRESERVED_FILES.contains(path)) return true;
        if (isUnderRoot(path, "config") || isUnderRoot(path, "defaultconfigs")) return true;
        for (String root : PRESERVED_ROOTS) if (isUnderRoot(path, root)) return true;
        return false;
    }

    private static boolean isUnderRoot(String path, String root) {
        return !root.isBlank() && (path.equals(root) || path.startsWith(root + "/"));
    }

    private static String normalizeManifestPath(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/').strip().toLowerCase(Locale.ROOT);
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

}
