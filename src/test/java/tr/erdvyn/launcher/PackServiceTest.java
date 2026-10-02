package tr.erdvyn.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PackServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void keepsExistingConfigWithLegacyManifest() throws Exception {
        JsonNode manifest = JSON.readTree("{\"files\":[]}");
        JsonNode entry = JSON.readTree("{\"path\":\"config/sodium-options.json\"}");
        assertTrue(PackService.shouldPreserveExisting(manifest, entry, "config/sodium-options.json"));
    }

    @Test
    void keepsMinecraftOptionsEvenIfAccidentallyListed() throws Exception {
        JsonNode manifest = JSON.readTree("{\"files\":[]}");
        JsonNode entry = JSON.readTree("{\"path\":\"options.txt\"}");
        assertTrue(PackService.shouldPreserveExisting(manifest, entry, "options.txt"));
    }

    @Test
    void keepsAdditionalManifestRoots() throws Exception {
        JsonNode manifest = JSON.readTree("{\"preserve_roots\":[\"custom-settings\"]}");
        JsonNode entry = JSON.readTree("{\"path\":\"custom-settings/player.json\"}");
        assertTrue(PackService.shouldPreserveExisting(manifest, entry, "custom-settings/player.json"));
    }

    @Test
    void stillRepairsMods() throws Exception {
        JsonNode manifest = JSON.readTree("{\"files\":[]}");
        JsonNode entry = JSON.readTree("{\"path\":\"mods/example.jar\"}");
        assertFalse(PackService.shouldPreserveExisting(manifest, entry, "mods/example.jar"));
    }

    @Test
    void neverLetsManifestManagementOverrideExistingPlayerConfig() throws Exception {
        JsonNode manifest = JSON.readTree("{\"managed_paths\":[\"config/required.toml\"]}");
        JsonNode entry = JSON.readTree("{\"path\":\"config/required.toml\",\"managed\":true}");
        assertTrue(PackService.shouldPreserveExisting(manifest, entry, "config/required.toml"));
        assertTrue(PackService.shouldPreserveExisting(manifest, entry, "defaultconfigs/required.toml"));
    }

    @Test
    void removesOnlyExplicitPackArtifacts(@TempDir Path game) throws Exception {
        Path target = game.resolve("mods/retired-mod.jar");
        Path kept = game.resolve("config/sodium-options.json");
        Files.createDirectories(target.getParent());
        Files.createDirectories(kept.getParent());
        Files.writeString(target, "old");
        Files.writeString(kept, "player");
        JsonNode manifest = JSON.readTree("{\"remove_paths\":[\"mods/retired-mod.jar\",\"config/sodium-options.json\"]}");
        var progress = new ArrayList<PackService.Progress>();
        PackService.removeRequestedPaths(game, manifest, progress::add);
        assertFalse(Files.exists(target));
        assertTrue(Files.exists(kept));
        assertTrue(progress.stream().anyMatch(item -> item.line().equals("[REMOVED] mods/retired-mod.jar")));
        assertTrue(progress.stream().anyMatch(item -> item.line().equals("[KEEP] config/sodium-options.json")));
    }

    @Test
    void rejectsRemovalOutsideManagedPackRoots(@TempDir Path game) throws Exception {
        JsonNode manifest = JSON.readTree("{\"remove_paths\":[\"../options.txt\"]}");
        assertThrows(Exception.class, () -> PackService.removeRequestedPaths(game, manifest, ignored -> {}));
    }

    @Test
    void downloadCountsBytesRejectsBadHashAndCancelsAStalledTransfer(@TempDir Path dir) throws Exception {
        byte[] body = new byte[200_000];
        new Random(7).nextBytes(body);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> { exchange.sendResponseHeaders(200, body.length); try (var out = exchange.getResponseBody()) { out.write(body); } });
        server.createContext("/stall", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body, 0, 70_000);
            exchange.getResponseBody().flush();
            try { release.await(); } catch (InterruptedException ignored) {}
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool(task -> { Thread thread = new Thread(task); thread.setDaemon(true); return thread; }));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            PackService pack = new PackService();
            long[] seen = {0};
            Path ok = dir.resolve("mods/ok.jar");
            pack.download(URI.create(base + "/ok"), ok, sha, new AtomicBoolean(), bytes -> seen[0] = bytes);
            assertEquals(body.length, seen[0]);
            assertArrayEquals(body, Files.readAllBytes(ok));

            Path bad = dir.resolve("mods/bad.jar");
            assertThrows(java.io.IOException.class, () -> pack.download(URI.create(base + "/ok"), bad, "0".repeat(64), new AtomicBoolean(), bytes -> {}));
            assertFalse(Files.exists(bad));

            // The server stops sending mid-file: only abortDownload can wake the blocked read.
            AtomicBoolean cancel = new AtomicBoolean(), armed = new AtomicBoolean();
            Path stalled = dir.resolve("mods/stalled.jar");
            long started = System.nanoTime();
            assertThrows(CancellationException.class, () -> pack.download(URI.create(base + "/stall"), stalled, sha, cancel, bytes -> {
                if (armed.compareAndSet(false, true)) Thread.startVirtualThread(() -> {
                    try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                    cancel.set(true);
                    pack.abortDownload();
                });
            }));
            assertTrue(System.nanoTime() - started < 5_000_000_000L, "cancel must not wait for the stalled server");
            assertFalse(Files.exists(stalled));
            assertFalse(Files.exists(stalled.resolveSibling("stalled.jar.erdvyn-download")));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void appliesNamedCleanupOnlyOnce(@TempDir Path root) throws Exception {
        Path game = root.resolve("game");
        Path state = root.resolve("state/cleanups.json");
        Path target = game.resolve("mods/old-mod.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "old");
        JsonNode manifest = JSON.readTree("{\"cleanup_id\":\"remove-old-mod-v1\",\"remove_paths\":[\"mods/old-mod.jar\"]}");
        PackService.removeRequestedPaths(game, manifest, state, ignored -> {});
        Files.writeString(target, "regenerated");
        PackService.removeRequestedPaths(game, manifest, state, ignored -> {});
        assertTrue(Files.exists(target));
    }
}
