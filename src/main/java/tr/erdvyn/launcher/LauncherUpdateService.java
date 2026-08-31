package tr.erdvyn.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class LauncherUpdateService {
    static final String CURRENT_VERSION = "2.1.11";
    record Update(String version, URI installerUri, String sha256, String notes) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).followRedirects(HttpClient.Redirect.NORMAL).build();

    Update check() throws Exception {
        String manifestUrl = LauncherConfig.launcherManifestUrl();
        if (manifestUrl == null || manifestUrl.isBlank()) return null;
        URI manifestUri = URI.create(manifestUrl);
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(manifestUri).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IOException("Launcher manifest HTTP " + response.statusCode());
        JsonNode root = JSON.readTree(response.body());
        String version = root.path("version").asText().strip();
        String url = root.path("installer_url").asText().strip();
        String sha256 = root.path("sha256").asText().strip().toLowerCase();
        if (version.isBlank() || url.isBlank() || sha256.length() != 64) throw new IOException("Launcher update manifest is incomplete");
        if (compareVersions(version, CURRENT_VERSION) <= 0) return null;
        return new Update(version, manifestUri.resolve(url), sha256, root.path("release_notes").asText(""));
    }

    Path download(Update update) throws Exception {
        Path directory = LauncherPaths.appRoot().resolve("updates");
        Files.createDirectories(directory);
        Path target = directory.resolve("Erdvyn-Launcher-Setup-" + safeVersion(update.version()) + ".exe");
        if (Files.isRegularFile(target) && update.sha256().equalsIgnoreCase(PackService.sha256(target))) return target;
        Path temp = target.resolveSibling(target.getFileName() + ".download");
        Files.deleteIfExists(temp);
        HttpResponse<Path> response = http.send(HttpRequest.newBuilder(update.installerUri()).timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofFile(temp));
        if (response.statusCode() / 100 != 2) { Files.deleteIfExists(temp); throw new IOException("Launcher update HTTP " + response.statusCode()); }
        if (!update.sha256().equalsIgnoreCase(PackService.sha256(temp))) { Files.deleteIfExists(temp); throw new IOException("Launcher update SHA-256 mismatch"); }
        try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (IOException atomicMoveUnsupported) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        return target;
    }

    private static String safeVersion(String value) { return value.replaceAll("[^0-9A-Za-z._-]", "_"); }

    static int compareVersions(String left, String right) {
        List<Integer> a = numbers(left), b = numbers(right);int size=Math.max(a.size(),b.size());
        for(int i=0;i<size;i++){int av=i<a.size()?a.get(i):0,bv=i<b.size()?b.get(i):0;if(av!=bv)return Integer.compare(av,bv);}
        return 0;
    }

    private static List<Integer> numbers(String value) {
        List<Integer> result=new ArrayList<>();for(String part:value.split("[^0-9]+")){if(part.isBlank())continue;try{result.add(Integer.parseInt(part));}catch(NumberFormatException ignored){result.add(0);}}return result;
    }
}
