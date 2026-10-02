package tr.erdvyn.launcher;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LauncherConfigTest {
    @Test
    void tokensAndDownloadsOnlyTravelOverHttpsOrLocalHttp() {
        assertTrue(LauncherConfig.secure(URI.create("https://api.erdvyn.net/api/pack/manifest")));
        assertTrue(LauncherConfig.secure(URI.create("http://localhost:8080/api")));
        assertTrue(LauncherConfig.secure(URI.create("http://127.0.0.1/api")));
        assertFalse(LauncherConfig.secure(URI.create("http://api.erdvyn.net/api")));
        assertFalse(LauncherConfig.secure(URI.create("http://localhost.evil.example/api")));
        assertFalse(LauncherConfig.secure(URI.create("file:///C:/Windows/System32/calc.exe")));
    }
}
