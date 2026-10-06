package tr.erdvyn.launcher;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LauncherUpdateServiceTest {
    // Signed with the real release key by the same command the release workflow runs (openssl pkeyutl -sign -rawin).
    private static final String SUMS = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef  Erdvyn-Launcher-Setup-9.9.9.exe\n";
    private static final String SIGNATURE = "BcRV6AO/l5N4Fg7Y8N3pTYY2hwo2w14OWREChquMJXMsqakGq33rxbjGMSZ9mwXF3JHOG2Mkgh0QYKpmlS9ZCg==";

    @Test
    void acceptsOnlyChecksumsSignedByTheReleaseKey() throws Exception {
        var key = LauncherUpdateService.releaseKey();
        assertTrue(LauncherUpdateService.signed(SUMS.getBytes(StandardCharsets.UTF_8), SIGNATURE, key));
        assertFalse(LauncherUpdateService.signed(SUMS.replace("0123", "f123").getBytes(StandardCharsets.UTF_8), SIGNATURE, key));
        assertFalse(LauncherUpdateService.signed(SUMS.getBytes(StandardCharsets.UTF_8), "not base64!", key));
    }

    @Test
    void siteFeedOffersOnlyWhatTheSignedChecksumsName() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        java.util.Map<String, String> files = new java.util.HashMap<>(java.util.Map.of("/signed/SHA256SUMS.txt", SUMS, "/signed/SHA256SUMS.txt.sig", SIGNATURE,
                "/forged/SHA256SUMS.txt", SUMS.replace("9.9.9", "9.9.8"), "/forged/SHA256SUMS.txt.sig", SIGNATURE));
        server.createContext("/", exchange -> {
            String body = files.get(exchange.getRequestURI().getPath());
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : bytes.length);
            if (body != null) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var update = new LauncherUpdateService().checkFeed(base + "/signed");
            assertEquals("9.9.9", update.version());
            assertEquals(base + "/signed/Erdvyn-Launcher-Setup-9.9.9.exe", update.installerUri().toString());
            assertEquals(LauncherUpdateService.checksum(SUMS, "Erdvyn-Launcher-Setup-9.9.9.exe"), update.sha256());
            assertNull(new LauncherUpdateService().checkFeed(base + "/missing/")); // not published there yet: silently nothing
            assertThrows(java.io.IOException.class, () -> new LauncherUpdateService().checkFeed(base + "/forged"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readsTheInstallerHashFromTheSignedFile() {
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", LauncherUpdateService.checksum(SUMS, "Erdvyn-Launcher-Setup-9.9.9.exe"));
        assertEquals("", LauncherUpdateService.checksum(SUMS, "Erdvyn-Launcher-Setup-1.0.0.exe"));
    }
}
