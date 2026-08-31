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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

final class MinecraftInstallService {
    private static final String INSTALLER_URL = "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.243/neoforge-21.1.243-installer.jar";
    private static final String INSTALLER_SHA256 = "29493a719a69c9593040ddbb589488b1231583824018e383f6dcd39b79411980";
    private static final String NEOFORGE_CLIENT_SHA256 = "8e3563a078289f0f07ee6f87f1c8651294387639c8355983ee207cb753f08b7f";
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
        Files.createDirectories(install);
        ensureLauncherProfile(install);
        ensureVanillaFiles(install, vanilla, client, log);
        Path neoClient=install.resolve("libraries").resolve("net/neoforged/neoforge/21.1.243/neoforge-21.1.243-client.jar");
        if (neoForgeReady(neo,neoClient)) {
            ensureLibraries(install,JSON.readTree(Files.readString(neo)),"NEOFORGE LIBRARIES",log);
            log.accept("MINECRAFT 1.21.1 / NEOFORGE 21.1.243 READY");
            return;
        }
        Path cache = LauncherPaths.appRoot().resolve("cache");Files.createDirectories(cache);
        Path installer = cache.resolve("neoforge-21.1.243-installer.jar");
        ensureNeoForgeInstaller(installer,log);
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
        if(!Files.isRegularFile(vanilla)||!Files.isRegularFile(client)||!neoForgeReady(neo,neoClient))throw new IOException("NeoForge installer completed without the required version files");
        ensureLibraries(install,JSON.readTree(Files.readString(neo)),"NEOFORGE LIBRARIES",log);
        log.accept("INSTALLATION COMPLETE");
    }

    private void ensureNeoForgeInstaller(Path installer, Consumer<String> log) throws Exception {
        if(Files.isRegularFile(installer)&&INSTALLER_SHA256.equals(PackService.sha256(installer)))return;
        log.accept("DOWNLOADING NEOFORGE INSTALLER");
        Exception last=null;
        for(int attempt=1;attempt<=3;attempt++){
            Path temp=installer.resolveSibling(installer.getFileName()+".download");
            Files.deleteIfExists(temp);
            try{
                HttpRequest request=HttpRequest.newBuilder(URI.create(INSTALLER_URL)).timeout(Duration.ofMinutes(3)).GET().build();
                HttpResponse<Path> response=http.send(request,HttpResponse.BodyHandlers.ofFile(temp));
                if(response.statusCode()/100!=2)throw new IOException("NeoForge installer HTTP "+response.statusCode());
                if(!INSTALLER_SHA256.equals(PackService.sha256(temp)))throw new IOException("NeoForge installer hash mismatch");
                try{Files.move(temp,installer,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
                catch(IOException unsupported){Files.move(temp,installer,StandardCopyOption.REPLACE_EXISTING);}
                return;
            }catch(Exception ex){last=ex;Files.deleteIfExists(temp);if(attempt<3)Thread.sleep(attempt*1500L);}
        }
        throw new IOException("NeoForge installer download failed",last);
    }

    private void ensureVanillaFiles(Path install, Path versionJson, Path clientJar, Consumer<String> log) throws Exception {
        if(!Files.isRegularFile(versionJson))downloadVanillaMetadata(versionJson,log);
        JsonNode profile;
        try{profile=readVanillaProfile(versionJson);}
        catch(Exception invalid){Files.deleteIfExists(versionJson);log.accept("REPAIRING MINECRAFT METADATA");downloadVanillaMetadata(versionJson,log);profile=readVanillaProfile(versionJson);}
        JsonNode client=profile.path("downloads").path("client");
        String clientUrl=client.path("url").asText(),clientSha1=client.path("sha1").asText();
        if(clientUrl.isBlank()||clientSha1.isBlank())throw new IOException("Minecraft client metadata is incomplete");
        Download clientDownload=new Download(URI.create(clientUrl),clientSha1,client.path("size").asLong(-1),clientJar);
        if(!validDownload(clientDownload,true)){
            log.accept("DOWNLOADING MINECRAFT CLIENT");
            downloadVerified(clientDownload.uri(),clientDownload.sha1(),clientDownload.size(),clientDownload.target());
        }
        ensureLibraries(install,profile,"MINECRAFT LIBRARIES",log);
        ensureAssets(install,profile,log);
        ensureLoggingConfig(install,profile,log);
    }

    private void downloadVanillaMetadata(Path versionJson, Consumer<String> log) throws Exception {
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
                    downloadVerified(URI.create(match.path("url").asText()),match.path("sha1").asText(),-1,versionJson);
                    return;
                }catch(Exception ex){last=ex;if(attempt<2)Thread.sleep(1200L);}
            }
        }
        throw new IOException("Minecraft metadata download failed",last);
    }

    private static JsonNode readVanillaProfile(Path versionJson) throws Exception {
        JsonNode profile=JSON.readTree(Files.readString(versionJson));
        if(!LauncherPaths.GAME_VERSION.equals(profile.path("id").asText())||profile.path("downloads").path("client").path("url").asText().isBlank())throw new IOException("Minecraft version metadata is invalid");
        return profile;
    }

    private void ensureLibraries(Path install, JsonNode profile, String label, Consumer<String> log) throws Exception {
        Path root=install.resolve("libraries");
        List<Download> downloads=new ArrayList<>();
        for(JsonNode library:profile.path("libraries")){
            if(!allowedOnWindows(library.path("rules")))continue;
            addDownload(downloads,root,library.path("downloads").path("artifact"));
            String classifier=library.path("natives").path("windows").asText().replace("${arch}",System.getProperty("os.arch").contains("64")?"64":"32");
            if(!classifier.isBlank())addDownload(downloads,root,library.path("downloads").path("classifiers").path(classifier));
        }
        downloadBatch(downloads,label,log,true);
    }

    private void ensureAssets(Path install, JsonNode profile, Consumer<String> log) throws Exception {
        JsonNode metadata=profile.path("assetIndex");
        String id=metadata.path("id").asText(),url=metadata.path("url").asText(),sha1=metadata.path("sha1").asText();
        if(id.isBlank()||url.isBlank()||sha1.isBlank())throw new IOException("Minecraft asset index metadata is incomplete");
        Path index=install.resolve("assets").resolve("indexes").resolve(id+".json");
        Download indexDownload=new Download(URI.create(url),sha1,metadata.path("size").asLong(-1),index);
        if(!validDownload(indexDownload,true))downloadVerified(indexDownload.uri(),indexDownload.sha1(),indexDownload.size(),indexDownload.target());
        List<Download> downloads=new ArrayList<>();
        JsonNode objects=JSON.readTree(Files.readString(index)).path("objects");
        var fields=objects.fields();
        while(fields.hasNext()){
            JsonNode object=fields.next().getValue();
            String hash=object.path("hash").asText();
            if(hash.length()<2)throw new IOException("Minecraft asset hash is invalid");
            Path target=install.resolve("assets").resolve("objects").resolve(hash.substring(0,2)).resolve(hash);
            downloads.add(new Download(URI.create("https://resources.download.minecraft.net/"+hash.substring(0,2)+"/"+hash),hash,object.path("size").asLong(-1),target));
        }
        downloadBatch(downloads,"MINECRAFT ASSETS",log,false);
    }

    private void ensureLoggingConfig(Path install, JsonNode profile, Consumer<String> log) throws Exception {
        JsonNode file=profile.path("logging").path("client").path("file");
        String id=file.path("id").asText(),url=file.path("url").asText(),sha1=file.path("sha1").asText();
        if(id.isBlank()||url.isBlank()||sha1.isBlank())return;
        Download logging=new Download(URI.create(url),sha1,file.path("size").asLong(-1),install.resolve("assets").resolve("log_configs").resolve(id));
        if(!validDownload(logging,true))downloadVerified(logging.uri(),logging.sha1(),logging.size(),logging.target());
    }

    private static void addDownload(List<Download> downloads, Path root, JsonNode metadata) throws IOException {
        String path=metadata.path("path").asText(),url=metadata.path("url").asText(),sha1=metadata.path("sha1").asText();
        if(path.isBlank())return;
        if(url.isBlank()||sha1.isBlank())throw new IOException("Minecraft library metadata is incomplete: "+path);
        downloads.add(new Download(URI.create(url),sha1,metadata.path("size").asLong(-1),root.resolve(path)));
    }

    private void downloadBatch(List<Download> downloads, String label, Consumer<String> log, boolean verifyExistingHash) throws Exception {
        LinkedHashMap<Path,Download> unique=new LinkedHashMap<>();
        for(Download download:downloads)unique.putIfAbsent(download.target().normalize(),download);
        List<Download> missing=unique.values().stream().filter(download->!validDownload(download,verifyExistingHash)).toList();
        if(missing.isEmpty())return;
        log.accept("DOWNLOADING "+label+" / "+missing.size());
        ExecutorService pool=Executors.newFixedThreadPool(Math.min(12,missing.size()));
        AtomicInteger completed=new AtomicInteger();
        List<Future<?>> futures=new ArrayList<>();
        try{
            for(Download download:missing)futures.add(pool.submit(()->{
                try{downloadVerified(download.uri(),download.sha1(),download.size(),download.target());}
                catch(Exception ex){throw new RuntimeException(ex);}
                int current=completed.incrementAndGet();
                if(current==missing.size()||current%100==0)log.accept(label+" "+current+"/"+missing.size());
            }));
            for(Future<?> future:futures)future.get();
        }catch(ExecutionException ex){
            futures.forEach(future->future.cancel(true));
            Throwable cause=ex.getCause();
            if(cause instanceof RuntimeException runtime&&runtime.getCause()!=null)cause=runtime.getCause();
            if(cause instanceof Exception exception)throw exception;
            throw new IOException(label+" download failed",cause);
        }finally{
            pool.shutdownNow();
        }
    }

    private void downloadVerified(URI uri, String expectedSha1, long expectedSize, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        Exception last=null;
        for(int attempt=1;attempt<=3;attempt++){
            Path temp=target.resolveSibling(target.getFileName()+".download");
            Files.deleteIfExists(temp);
            try{
                HttpResponse<Path> response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(3)).GET().build(),HttpResponse.BodyHandlers.ofFile(temp));
                if(response.statusCode()/100!=2)throw new IOException("Download HTTP "+response.statusCode());
                if(expectedSize>=0&&Files.size(temp)!=expectedSize)throw new IOException("Download size mismatch");
                if(!expectedSha1.equalsIgnoreCase(sha1(temp)))throw new IOException("Download checksum mismatch");
                try{Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
                catch(IOException unsupported){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
                return;
            }catch(Exception ex){last=ex;Files.deleteIfExists(temp);if(attempt<3)Thread.sleep(attempt*1500L);}
        }
        throw new IOException("Download failed: "+uri,last);
    }

    private static boolean validSize(Path target, long expectedSize) {
        try{return Files.isRegularFile(target)&&(expectedSize<0||Files.size(target)==expectedSize);}
        catch(IOException ignored){return false;}
    }

    private static boolean validDownload(Download download, boolean verifyHash) {
        if(!validSize(download.target(),download.size()))return false;
        if(!verifyHash)return true;
        try{return download.sha1().equalsIgnoreCase(sha1(download.target()));}
        catch(Exception ignored){return false;}
    }

    private static boolean allowedOnWindows(JsonNode rules) {
        if(!rules.isArray()||rules.isEmpty())return true;
        boolean allowed=false;
        for(JsonNode rule:rules){
            JsonNode os=rule.path("os");
            String name=os.path("name").asText(),arch=os.path("arch").asText(),version=os.path("version").asText();
            if(!name.isBlank()&&!"windows".equals(name))continue;
            if(!arch.isBlank()&&!(arch.contains("64")&&System.getProperty("os.arch").contains("64")))continue;
            if(!version.isBlank()&&!System.getProperty("os.version").matches(version))continue;
            allowed="allow".equals(rule.path("action").asText());
        }
        return allowed;
    }

    private static boolean neoForgeReady(Path profile, Path client) {
        try{return Files.isRegularFile(profile)&&Files.isRegularFile(client)&&!JSON.readTree(Files.readString(profile)).path("mainClass").asText().isBlank()&&NEOFORGE_CLIENT_SHA256.equalsIgnoreCase(PackService.sha256(client));}
        catch(Exception ignored){return false;}
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

    private record Download(URI uri,String sha1,long size,Path target){}
}
