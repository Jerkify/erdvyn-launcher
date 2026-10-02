package tr.erdvyn.launcher;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class LauncherConfig {
    private static final Properties VALUES = load();

    private LauncherConfig() {}

    private static final String PRODUCTION_API = "https://api.erdvyn.net";

    static String apiUrl() { return url("ERDVYN_API_URL", "api.url", PRODUCTION_API); }
    static String accountUrl() { return url("ERDVYN_ACCOUNT_URL", "account.url", apiUrl()); }
    static String packManifestUrl() { return url("ERDVYN_PACK_MANIFEST_URL", "pack.manifest.url", PRODUCTION_API + "/api/pack/manifest"); }
    static String launcherManifestUrl() { return url("ERDVYN_LAUNCHER_MANIFEST_URL", "launcher.manifest.url", PRODUCTION_API + "/api/launcher/manifest"); }

    /** Bearer tokens and executable downloads travel over https only; plain http stays usable for a local test server. */
    static boolean secure(URI uri) {
        String scheme = uri.getScheme(), host = uri.getHost();
        return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme) && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host));
    }

    private static String url(String environment, String key, String fallback) {
        String value = value(environment, key, fallback);
        try { if (secure(URI.create(value))) return value; } catch (IllegalArgumentException ignored) {}
        LauncherLog.write("Ignoring insecure " + key + ": " + value);
        return fallback;
    }
    static String launcherGithubRepository() { return value("ERDVYN_LAUNCHER_GITHUB", "launcher.github.repository", "Jerkify/erdvyn-launcher"); }
    static String serverAddress() { return value("ERDVYN_SERVER_ADDRESS", "server.address", LauncherPaths.DEFAULT_SERVER); }
    static String statusAddress() { return value("ERDVYN_STATUS_ADDRESS", "server.status.address", "57.128.231.8:25575"); }

    static Path path() { return LauncherPaths.appRoot().resolve("launcher.properties"); }

    private static String value(String environment, String key, String fallback) {
        String value = System.getenv(environment);
        if (value != null && !value.isBlank()) return value.strip();
        value = VALUES.getProperty(key);
        if (value != null && !value.isBlank()) return value.strip();
        return fallback == null ? "" : fallback.strip();
    }

    private static Properties load() {
        Properties properties = new Properties();Path file = path();
        try {
            Files.createDirectories(file.getParent());
            if (Files.isRegularFile(file)) {
                try (InputStream input = Files.newInputStream(file)) { properties.load(input); }
            } else {
                String defaults = String.join("\n",
                        "server.address=" + LauncherPaths.DEFAULT_SERVER,
                        "server.status.address=57.128.231.8:25575",
                        "api.url=" + PRODUCTION_API,
                        "account.url=https://account.erdvyn.net",
                        "pack.manifest.url=" + PRODUCTION_API + "/api/pack/manifest",
                        "launcher.manifest.url=" + PRODUCTION_API + "/api/launcher/manifest",
                        "launcher.github.repository=Jerkify/erdvyn-launcher",
                        "");
                Files.writeString(file, defaults);
                properties.load(new java.io.StringReader(defaults));
            }
        } catch (Exception ignored) {}
        return properties;
    }
}
