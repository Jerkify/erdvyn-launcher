package tr.erdvyn.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

final class LauncherUpdateService {
    static final String CURRENT_VERSION = loadCurrentVersion();
    record Update(String version, URI installerUri, String sha256, String notes) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).followRedirects(HttpClient.Redirect.NORMAL).build();

    Update check() throws Exception {
        String repository=LauncherConfig.launcherGithubRepository();
        if(repository!=null&&!repository.isBlank()){
            try{return checkGithub(repository.strip());}
            catch(Exception githubError){
                String manifestUrl=LauncherConfig.launcherManifestUrl();
                if(manifestUrl==null||manifestUrl.isBlank())throw githubError;
            }
        }
        return checkManifest();
    }

    private Update checkGithub(String repository) throws Exception {
        if(!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))throw new IOException("Invalid launcher GitHub repository");
        URI releaseUri=URI.create("https://api.github.com/repos/"+repository+"/releases/latest");
        HttpResponse<String> response=http.send(githubRequest(releaseUri,Duration.ofSeconds(20)).GET().build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if(response.statusCode()/100!=2)throw new IOException("GitHub release HTTP "+response.statusCode());
        JsonNode root=JSON.readTree(response.body());
        String version=root.path("tag_name").asText(root.path("name").asText("")).strip().replaceFirst("^[vV]","");
        if(version.isBlank())throw new IOException("GitHub release version is missing");
        if(compareVersions(version,CURRENT_VERSION)<=0)return null;
        JsonNode installer=null,sums=null;
        String expected="Erdvyn-Launcher-Setup-"+version+".exe";
        for(JsonNode asset:root.path("assets")){
            String name=asset.path("name").asText();
            if(name.equalsIgnoreCase(expected))installer=asset;
            else if(installer==null&&name.toLowerCase(Locale.ROOT).startsWith("erdvyn-launcher-setup-")&&name.toLowerCase(Locale.ROOT).endsWith(".exe"))installer=asset;
            if(name.equalsIgnoreCase("SHA256SUMS.txt"))sums=asset;
        }
        if(installer==null)throw new IOException("GitHub release installer is missing");
        String installerUrl=installer.path("browser_download_url").asText().strip();
        String installerName=installer.path("name").asText();
        String digest=installer.path("digest").asText("").strip();
        String sha=digest.toLowerCase(Locale.ROOT).startsWith("sha256:")?digest.substring(7).strip():"";
        if(!validSha256(sha)&&sums!=null){
            String sumsUrl=sums.path("browser_download_url").asText().strip();
            if(!sumsUrl.isBlank())sha=readChecksum(URI.create(sumsUrl),installerName);
        }
        if(installerUrl.isBlank()||!validSha256(sha))throw new IOException("GitHub release checksum is missing");
        return new Update(version,URI.create(installerUrl),sha.toLowerCase(Locale.ROOT),root.path("body").asText(""));
    }

    private String readChecksum(URI uri,String installerName) throws Exception {
        HttpResponse<String> response=http.send(githubRequest(uri,Duration.ofSeconds(20)).GET().build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if(response.statusCode()/100!=2)throw new IOException("GitHub checksum HTTP "+response.statusCode());
        for(String line:response.body().split("\\R")){
            String clean=line.strip();if(clean.isBlank())continue;String[] parts=clean.split("\\s+",2);
            if(parts.length==2&&validSha256(parts[0])&&parts[1].replaceFirst("^[*]","").strip().equalsIgnoreCase(installerName))return parts[0];
        }
        return "";
    }

    private HttpRequest.Builder githubRequest(URI uri,Duration timeout){return HttpRequest.newBuilder(uri).timeout(timeout).header("Accept","application/vnd.github+json").header("X-GitHub-Api-Version","2022-11-28").header("User-Agent","Erdvyn-Launcher/"+CURRENT_VERSION);}

    private Update checkManifest() throws Exception {
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

    private static boolean validSha256(String value){return value!=null&&value.matches("(?i)[0-9a-f]{64}");}

    private static String loadCurrentVersion(){
        try(InputStream input=LauncherUpdateService.class.getResourceAsStream("/launcher-version.properties")){
            if(input!=null){Properties properties=new Properties();properties.load(input);String value=properties.getProperty("version","").strip();if(!value.isBlank())return value;}
        }catch(Exception ignored){}
        String value=LauncherUpdateService.class.getPackage().getImplementationVersion();
        return value==null||value.isBlank()?"0.0.0":value.strip();
    }
}
