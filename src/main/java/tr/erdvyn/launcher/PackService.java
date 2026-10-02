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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

final class PackService {
    /** A blank line marks a byte tick of the running download: update the meters, do not log it. */
    record Progress(double value, String line, long bytesDone, long bytesTotal, double bytesPerSecond) {
        Progress(double value, String line) { this(value, line, 0, 0, 0); }
        boolean tick() { return line.isEmpty(); }
    }
    /** firstError keeps the original exception so the UI can turn it into an actionable message. */
    record Result(int verified, int downloaded, int kept, int failed, String version, Throwable firstError) {}
    private record Pending(String relative, Path target, String expected, String url, long size) {}
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
    private volatile InputStream activeDownload;

    /** CLI entry points: no cancel button, and they print every event, so byte ticks are dropped. */
    Result verifyAndRepair(Consumer<Progress> progress) throws Exception {
        return verifyAndRepair(event -> { if (!event.tick()) progress.accept(event); }, new AtomicBoolean());
    }

    /** Closes the in-flight download so a stalled read returns at once; the loop then sees the cancel flag. */
    void abortDownload() {
        InputStream active = activeDownload;
        if (active != null) try { active.close(); } catch (IOException ignored) {}
    }

    /**
     * Audits every file first (hash pass), then downloads what is missing or wrong (byte pass), so the
     * download meter knows its total up front. Setting {@code cancel} stops at the next file or chunk with a
     * CancellationException: partial temp files are deleted and the install state is never written.
     */
    Result verifyAndRepair(Consumer<Progress> progress, AtomicBoolean cancel) throws Exception {
        LauncherPaths.prepareInstance();
        String manifestUrl = LauncherConfig.packManifestUrl();
        if (manifestUrl == null || manifestUrl.isBlank()) {
            throw new IOException("Pack manifest URL is not configured; a managed installation cannot be downloaded");
        }
        progress.accept(new Progress(.02, "MANIFEST  " + manifestUrl));
        HttpRequest request = freshManifestRequest(manifestUrl, Duration.ofSeconds(30));
        HttpResponse<String> response = await(http.sendAsync(request, HttpResponse.BodyHandlers.ofString()), cancel);
        if (response.statusCode() / 100 != 2) throw new IOException("Manifest HTTP " + response.statusCode());
        Path cachedManifest=LauncherPaths.appRoot().resolve("active-pack-manifest.json");Files.createDirectories(cachedManifest.getParent());Files.writeString(cachedManifest,response.body());
        JsonNode root = JSON.readTree(response.body());
        String version = root.path("version").asText("remote");
        JsonNode files = root.path("files");
        if (!files.isArray()) throw new IOException("Manifest files array is missing");
        Path game = LauncherPaths.gameDirectory().toAbsolutePath().normalize();
        removeRequestedPaths(game, root, LauncherPaths.appRoot().resolve(CLEANUP_STATE), progress);
        int total = files.size(), verified = 0, downloaded = 0, kept = 0, failed = 0, index = 0;
        Throwable firstError = null;
        Set<Path> managedFiles = new HashSet<>();
        List<Pending> pending = new ArrayList<>();
        // A cheap size probe decides the bar split: hashing is fast, so an install that will download gets most of the bar for bytes.
        double auditEnd = .98;
        for (JsonNode entry : files) {
            Path probe = game.resolve(entry.path("path").asText()).normalize();long size = entry.path("size").asLong(0);
            if (!Files.isRegularFile(probe) || size > 0 && Files.size(probe) != size) { auditEnd = .30; break; }
        }
        for (JsonNode entry : files) {
            checkCancel(cancel);
            index++;
            String relative = entry.path("path").asText(), expected = entry.path("sha256").asText().toLowerCase(Locale.ROOT);
            Path target = game.resolve(relative).normalize();
            if (relative.isBlank() || !target.startsWith(game)) throw new IOException("Unsafe manifest path: " + relative);
            managedFiles.add(target);
            double at = .04 + (auditEnd - .04) * index / Math.max(1, total);
            if (Files.isRegularFile(target) && shouldPreserveExisting(root, entry, relative)) {
                kept++;
                progress.accept(new Progress(at, "[KEEP] " + relative));
                continue;
            }
            if (Files.isRegularFile(target) && !expected.isBlank() && expected.equals(sha256(target))) {
                verified++;
                progress.accept(new Progress(at, "[OK] " + relative));
                continue;
            }
            String url = entry.path("url").asText();
            if (url.isBlank()) {
                failed++;
                if (firstError == null) firstError = new IOException("Manifest entry has no download URL: " + relative);
                progress.accept(new Progress(at, "[MISSING] " + relative));
                continue;
            }
            pending.add(new Pending(relative, target, expected, url, Math.max(0, entry.path("size").asLong(0))));
        }
        Meter meter = new Meter(pending.stream().mapToLong(Pending::size).sum());
        double downloadStart = auditEnd;
        for (Pending file : pending) {
            checkCancel(cancel);
            long before = meter.done;
            progress.accept(meter.progress(downloadStart, "[GET] " + file.relative()));
            try {
                // Fail closed: pack files run inside the game, so an unhashed or plain-http entry is never installed.
                if (!file.expected().matches("[0-9a-f]{64}")) throw new IOException("manifest entry has no SHA-256");
                URI source = URI.create(manifestUrl).resolve(file.url());
                if (!LauncherConfig.secure(source)) throw new IOException("insecure download URL");
                download(source, file.target(), file.expected(), cancel, bytes -> {
                    meter.done = before + bytes;
                    if (meter.sample()) progress.accept(meter.progress(downloadStart, ""));
                });
                downloaded++;
                meter.done = before + Math.max(file.size(), meter.done - before);
                progress.accept(meter.progress(downloadStart, "[SAVED] " + file.relative()));
            } catch (CancellationException stop) {
                throw stop;
            } catch (Exception error) {
                failed++;
                if (firstError == null) firstError = error;
                meter.done = before + file.size(); // a failed file must not leave the ETA counting its bytes as still to come
                String message = error.getMessage() == null ? "" : error.getMessage();
                progress.accept(meter.progress(downloadStart, "[FAIL] " + file.relative() + " / " + (error.getClass() == IOException.class ? message : error.getClass().getSimpleName() + (message.isBlank() ? "" : ": " + message))));
            }
        }
        JsonNode enforceRoots = root.path("enforce_roots");
        // One folder per run so a later quarantine never overwrites an earlier copy of the same jar.
        Path quarantineRoot = LauncherPaths.appRoot().resolve("quarantine").resolve("unmanaged-pack-files").resolve(String.valueOf(Instant.now().getEpochSecond())).toAbsolutePath().normalize();
        if (enforceRoots.isArray()) for (JsonNode rootName : enforceRoots) {
            // Only the mods tree is enforced; a manifest naming "." must not sweep jars from the whole instance.
            if (!isUnderRoot(normalizeManifestPath(rootName.asText()), "mods")) continue;
            Path enforced = game.resolve(rootName.asText()).normalize();
            if (!enforced.startsWith(game) || !Files.isDirectory(enforced)) continue;
            try (var stream = Files.walk(enforced)) {
                for (Path actual : stream.filter(Files::isRegularFile).toList()) {
                    if (actual.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar") && !managedFiles.contains(actual.toAbsolutePath().normalize())) {
                        Path quarantine = quarantineRoot.resolve(game.relativize(actual).toString()).normalize();
                        if (!quarantine.startsWith(quarantineRoot)) throw new IOException("Unsafe quarantine path: " + actual);
                        Files.createDirectories(quarantine.getParent());
                        Files.move(actual, quarantine, StandardCopyOption.REPLACE_EXISTING);
                        progress.accept(new Progress(.98, "[QUARANTINED] " + game.relativize(actual).toString().replace('\\', '/')));
                    }
                }
            }
        }
        checkCancel(cancel);
        progress.accept(new Progress(1, failed == 0 ? "PACKAGE VERIFIED" : "PACKAGE HAS " + failed + " ERRORS"));
        if (failed == 0) writeInstallState(version, cachedManifest, total);
        return new Result(verified, downloaded, kept, failed, version, firstError);
    }

    private static void checkCancel(AtomicBoolean cancel) { if (cancel.get()) throw new CancellationException("Pack verification cancelled"); }

    /** Waits for an HTTP exchange but gives up within 250 ms of a cancel, aborting the request. */
    private static <T> T await(CompletableFuture<T> future, AtomicBoolean cancel) throws Exception {
        while (true) {
            try { return future.get(250, TimeUnit.MILLISECONDS); }
            catch (TimeoutException waiting) { if (cancel.get()) { future.cancel(true); throw new CancellationException("Pack verification cancelled"); } }
            catch (ExecutionException failed) { throw failed.getCause() instanceof Exception cause ? cause : failed; }
        }
    }

    /** Download bytes for the progress events: total is known after the audit; the rate is a moving average sampled every 200 ms. */
    private static final class Meter {
        final long total; long done; double rate; private long sampledAt = System.nanoTime(), sampledBytes;
        Meter(long total) { this.total = total; }
        boolean sample() {
            long now = System.nanoTime();
            if (now - sampledAt < 200_000_000L) return false;
            double instant = (done - sampledBytes) * 1e9 / (now - sampledAt);
            rate = rate == 0 ? instant : rate * .7 + instant * .3;
            sampledAt = now; sampledBytes = done;
            return true;
        }
        Progress progress(double start, String line) {
            long shownTotal = Math.max(total, done);
            return new Progress(start + (.98 - start) * (shownTotal == 0 ? 1 : done / (double) shownTotal), line, done, shownTotal, rate);
        }
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


    /** Streams into a temp file while hashing, reporting bytes received per chunk; the temp file never survives a failure or cancel. */
    void download(URI url, Path target, String expectedSha256, AtomicBoolean cancel, LongConsumer received) throws Exception {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".erdvyn-download");
        HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofMinutes(3)).GET().build();
        try {
            HttpResponse<InputStream> response = await(http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()), cancel);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // newOutputStream truncates, so a stale longer temp file cannot leave trailing bytes.
            try (InputStream input = response.body(); var output = Files.newOutputStream(temp)) {
                activeDownload = input;
                checkCancel(cancel); // a cancel that ran before activeDownload was set could not close this stream
                if (response.statusCode() / 100 != 2) throw new IOException("HTTP " + response.statusCode());
                byte[] buffer = new byte[1 << 16];
                long total = 0;
                for (int read; (read = input.read(buffer)) >= 0 && !cancel.get(); ) {
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    received.accept(total += read);
                }
            } catch (IOException readFailed) {
                checkCancel(cancel); // a stream closed by abortDownload fails its read: report the cancel, not an I/O error
                throw readFailed;
            } finally {
                activeDownload = null;
            }
            checkCancel(cancel); // abortDownload ends the stream early; that is a cancel, not a short file
            if (!expectedSha256.equalsIgnoreCase(HexFormat.of().formatHex(digest.digest()))) throw new IOException("SHA-256 mismatch");
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
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
