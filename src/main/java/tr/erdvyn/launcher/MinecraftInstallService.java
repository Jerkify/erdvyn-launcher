package tr.erdvyn.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class MinecraftInstallService {
    private static final String INSTALLER_URL = "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.243/neoforge-21.1.243-installer.jar";
    private static final String INSTALLER_SHA256 = "29493a719a69c9593040ddbb589488b1231583824018e383f6dcd39b79411980";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();

    void ensureInstalled(Consumer<String> log) throws Exception {
        Path install = LauncherPaths.minecraftInstall();
        Path vanilla = install.resolve("versions").resolve(LauncherPaths.GAME_VERSION).resolve(LauncherPaths.GAME_VERSION + ".json");
        Path neo = install.resolve("versions").resolve(LauncherPaths.NEOFORGE_VERSION).resolve(LauncherPaths.NEOFORGE_VERSION + ".json");
        Path client = install.resolve("versions").resolve(LauncherPaths.GAME_VERSION).resolve(LauncherPaths.GAME_VERSION + ".jar");
        if (Files.isRegularFile(vanilla) && Files.isRegularFile(neo) && Files.isRegularFile(client)) {
            log.accept("MINECRAFT 1.21.1 / NEOFORGE 21.1.243 READY");
            return;
        }
        Files.createDirectories(install);
        ensureLauncherProfile(install);
        Path cache = LauncherPaths.appRoot().resolve("cache");Files.createDirectories(cache);
        Path installer = cache.resolve("neoforge-21.1.243-installer.jar");
        if (!Files.isRegularFile(installer) || !INSTALLER_SHA256.equals(PackService.sha256(installer))) {
            log.accept("DOWNLOADING NEOFORGE INSTALLER");
            Path temp = installer.resolveSibling(installer.getFileName()+".download");
            HttpRequest request = HttpRequest.newBuilder(URI.create(INSTALLER_URL)).timeout(Duration.ofMinutes(3)).GET().build();
            HttpResponse<Path> response = http.send(request,HttpResponse.BodyHandlers.ofFile(temp));
            if(response.statusCode()/100!=2)throw new IOException("NeoForge installer HTTP "+response.statusCode());
            if(!INSTALLER_SHA256.equals(PackService.sha256(temp))){Files.deleteIfExists(temp);throw new IOException("NeoForge installer hash mismatch");}
            Files.move(temp,installer, StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }
        Path java = findInstallerJava();Path installerLog=LauncherPaths.appRoot().resolve("logs").resolve("neoforge-install.log");Files.createDirectories(installerLog.getParent());
        log.accept("INSTALLING MINECRAFT + NEOFORGE");
        Process process = new ProcessBuilder(java.toString(),"-jar",installer.toString(),"--install-client",install.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.to(installerLog.toFile())).start();
        if(!process.waitFor(12, TimeUnit.MINUTES)){process.destroyForcibly();throw new IOException("NeoForge installation timed out");}
        if(process.exitValue()!=0)throw new IOException("NeoForge installation failed (exit "+process.exitValue()+"). See "+installerLog);
        if(!Files.isRegularFile(vanilla)||!Files.isRegularFile(neo)||!Files.isRegularFile(client))throw new IOException("NeoForge installer completed without the required version files");
        log.accept("INSTALLATION COMPLETE");
    }

    private static void ensureLauncherProfile(Path install) throws IOException {
        Path profile = install.resolve("launcher_profiles.json");
        Path storeProfile = install.resolve("launcher_profiles_microsoft_store.json");
        if (!Files.isRegularFile(profile) && !Files.isRegularFile(storeProfile)) {
            Files.writeString(profile, "{\"profiles\":{}}\n");
        }
    }

    private static Path findInstallerJava() throws IOException {
        List<Path> candidates=List.of(Path.of(System.getProperty("java.home"),"bin","java.exe"),Path.of(System.getProperty("java.home"),"bin","javaw.exe"),Path.of("C:/Program Files/Java/latest/bin/java.exe"));
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElseThrow(()->new IOException("Java 21 is required for NeoForge installation"));
    }
}
