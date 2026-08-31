package tr.erdvyn.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class MinecraftInstallService {
    private static final String INSTALLER_URL = "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.243/neoforge-21.1.243-installer.jar";
    private static final String INSTALLER_SHA256 = "29493a719a69c9593040ddbb589488b1231583824018e383f6dcd39b79411980";
    private static final List<URI> VERSION_MANIFESTS = List.of(
            URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"),
            URI.create("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"));
    private static final ObjectMapper JSON = new ObjectMapper();
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
        ensureVanillaFiles(vanilla, client, log);
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
        Files.deleteIfExists(installerLog);
        int exit=-1;
        for(int attempt=1;attempt<=3;attempt++){
            if(attempt>1)log.accept("RETRYING NEOFORGE INSTALLATION "+attempt+"/3");
            Process process = new ProcessBuilder(java.toString(),"-jar",installer.toString(),"--install-client",install.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(installerLog.toFile())).start();
            if(!process.waitFor(12, TimeUnit.MINUTES)){process.destroyForcibly();throw new IOException("NeoForge installation timed out");}
            exit=process.exitValue();
            if(exit==0)break;
            if(attempt<3)Thread.sleep(attempt*1500L);
        }
        if(exit!=0)throw new IOException("NeoForge installation failed after 3 attempts (exit "+exit+"). See "+installerLog);
        if(!Files.isRegularFile(vanilla)||!Files.isRegularFile(neo)||!Files.isRegularFile(client))throw new IOException("NeoForge installer completed without the required version files");
        log.accept("INSTALLATION COMPLETE");
    }

    private void ensureVanillaFiles(Path versionJson, Path clientJar, Consumer<String> log) throws Exception {
        if(!Files.isRegularFile(versionJson)){
            log.accept("DOWNLOADING MINECRAFT METADATA");
            Exception last=null;
            for(URI manifestUri:VERSION_MANIFESTS){
                for(int attempt=1;attempt<=2;attempt++){
                    try{
                        HttpResponse<String> response=http.send(HttpRequest.newBuilder(manifestUri).timeout(Duration.ofSeconds(45)).GET().build(),HttpResponse.BodyHandlers.ofString());
                        if(response.statusCode()/100!=2)throw new IOException("Minecraft manifest HTTP "+response.statusCode());
                        JsonNode match=null;
                        for(JsonNode entry:JSON.readTree(response.body()).path("versions"))if(LauncherPaths.GAME_VERSION.equals(entry.path("id").asText())){match=entry;break;}
                        if(match==null)throw new IOException("Minecraft "+LauncherPaths.GAME_VERSION+" metadata was not found");
                        downloadVerified(URI.create(match.path("url").asText()),match.path("sha1").asText(),versionJson);
                        last=null;
                        break;
                    }catch(Exception ex){last=ex;if(attempt<2)Thread.sleep(1200L);}
                }
                if(last==null)break;
            }
            if(last!=null)throw new IOException("Minecraft metadata download failed",last);
        }
        if(!Files.isRegularFile(clientJar)){
            log.accept("DOWNLOADING MINECRAFT CLIENT");
            JsonNode client=JSON.readTree(Files.readString(versionJson)).path("downloads").path("client");
            String url=client.path("url").asText(),sha1=client.path("sha1").asText();
            if(url.isBlank()||sha1.isBlank())throw new IOException("Minecraft client metadata is incomplete");
            downloadVerified(URI.create(url),sha1,clientJar);
        }
    }

    private void downloadVerified(URI uri, String expectedSha1, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        Exception last=null;
        for(int attempt=1;attempt<=3;attempt++){
            Path temp=target.resolveSibling(target.getFileName()+".download");
            Files.deleteIfExists(temp);
            try{
                HttpResponse<Path> response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(3)).GET().build(),HttpResponse.BodyHandlers.ofFile(temp));
                if(response.statusCode()/100!=2)throw new IOException("Download HTTP "+response.statusCode());
                if(!expectedSha1.equalsIgnoreCase(sha1(temp)))throw new IOException("Download checksum mismatch");
                try{Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
                catch(IOException unsupported){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
                return;
            }catch(Exception ex){last=ex;Files.deleteIfExists(temp);if(attempt<3)Thread.sleep(attempt*1500L);}
        }
        throw new IOException("Download failed: "+uri,last);
    }

    private static String sha1(Path file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-1");
        try(InputStream input=Files.newInputStream(file)){byte[] buffer=new byte[8192];for(int read;(read=input.read(buffer))>=0;)if(read>0)digest.update(buffer,0,read);}
        return HexFormat.of().formatHex(digest.digest());
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
