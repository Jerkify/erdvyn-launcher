package tr.erdvyn.launcher;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void readsTheInstallerHashFromTheSignedFile() {
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", LauncherUpdateService.checksum(SUMS, "Erdvyn-Launcher-Setup-9.9.9.exe"));
        assertEquals("", LauncherUpdateService.checksum(SUMS, "Erdvyn-Launcher-Setup-1.0.0.exe"));
    }
}
