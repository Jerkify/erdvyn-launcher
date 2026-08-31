package tr.erdvyn.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

public final class MinecraftInstallProbe {
    private static final ObjectMapper JSON = new ObjectMapper();

    private MinecraftInstallProbe() {}

    public static void main(String[] args) throws Exception {
        MinecraftInstallService installer = new MinecraftInstallService();
        installer.ensureInstalled(System.out::println);

        Path install = LauncherPaths.minecraftInstall();
        Path vanillaPath = install.resolve("versions/1.21.1/1.21.1.json");
        Path neoPath = install.resolve("versions/neoforge-21.1.243/neoforge-21.1.243.json");
        require(vanillaPath);
        require(neoPath);
        require(install.resolve("versions/1.21.1/1.21.1.jar"));
        require(install.resolve("libraries/net/neoforged/neoforge/21.1.243/neoforge-21.1.243-client.jar"));
        require(install.resolve("libraries/com/github/oshi/oshi-core/6.4.10/oshi-core-6.4.10.jar"));

        JsonNode vanilla = JSON.readTree(vanillaPath.toFile());
        JsonNode assets = JSON.readTree(install.resolve("assets/indexes/" + vanilla.path("assetIndex").path("id").asText() + ".json").toFile());
        int assetCount = 0;
        var fields = assets.path("objects").fields();
        while (fields.hasNext()) {
            JsonNode object = fields.next().getValue();
            String hash = object.path("hash").asText();
            Path target = install.resolve("assets/objects").resolve(hash.substring(0, 2)).resolve(hash);
            require(target);
            if (Files.size(target) != object.path("size").asLong()) throw new IllegalStateException("Wrong asset size: " + target);
            assetCount++;
        }

        JsonNode neo = JSON.readTree(neoPath.toFile());
        int neoLibraries = 0;
        for (JsonNode library : neo.path("libraries")) {
            String relative = library.path("downloads").path("artifact").path("path").asText();
            if (!relative.isBlank()) {
                require(install.resolve("libraries").resolve(relative));
                neoLibraries++;
            }
        }
        System.out.printf("PASS install=%s assets=%d neoforgeLibraries=%d%n", install, assetCount, neoLibraries);
    }

    private static void require(Path file) {
        if (!Files.isRegularFile(file)) throw new IllegalStateException("Missing: " + file);
    }
}
