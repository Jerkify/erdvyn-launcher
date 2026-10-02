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

    /** Ed25519 key that signs SHA256SUMS.txt in the release workflow (secret LAUNCHER_SIGNING_KEY). Private half: .local/secrets. */
    static final String RELEASE_PUBLIC_KEY="MCowBQYDK2VwAyEAPtfk/1pBhQdKXB7MvvtKp6y9CsMLz0I3KZjuNlQAxhE=";

    // GitHub releases are the only update source: a second, unsigned manifest path would be a second way in.
    Update check() throws Exception {
        String repository=LauncherConfig.launcherGithubRepository();
        return repository==null||repository.isBlank()?null:checkGithub(repository.strip());
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
        JsonNode installer=null,sums=null,signature=null;
        String expected="Erdvyn-Launcher-Setup-"+version+".exe";
        for(JsonNode asset:root.path("assets")){
            String name=asset.path("name").asText();
            // Only the installer named for this tag: a stray older setup.exe in the release must never be offered as the update.
            if(name.equalsIgnoreCase(expected))installer=asset;
            if(name.equalsIgnoreCase("SHA256SUMS.txt"))sums=asset;
            if(name.equalsIgnoreCase("SHA256SUMS.txt.sig"))signature=asset;
        }
        if(installer==null)throw new IOException("GitHub release installer is missing");
        if(sums==null||signature==null)throw new IOException("Launcher update "+version+" is not signed");
        // The installer hash comes only from the signed checksum file; GitHub's own digest is not covered by the signature.
        byte[] checksums=fetch(sums);
        if(!signed(checksums,new String(fetch(signature),StandardCharsets.US_ASCII).strip(),releaseKey()))throw new IOException("Launcher update "+version+" has an invalid signature");
        String installerUrl=installer.path("browser_download_url").asText().strip();
        String sha=checksum(new String(checksums,StandardCharsets.UTF_8),installer.path("name").asText());
        if(installerUrl.isBlank()||!validSha256(sha))throw new IOException("GitHub release checksum is missing");
        if(!LauncherConfig.secure(URI.create(installerUrl)))throw new IOException("GitHub release installer URL is not https");
        return new Update(version,URI.create(installerUrl),sha.toLowerCase(Locale.ROOT),root.path("body").asText(""));
    }

    private byte[] fetch(JsonNode asset) throws Exception {
        URI uri=URI.create(asset.path("browser_download_url").asText().strip());
        if(!LauncherConfig.secure(uri))throw new IOException("GitHub release asset URL is not https");
        HttpResponse<byte[]> response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("User-Agent","Erdvyn-Launcher/"+CURRENT_VERSION).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        if(response.statusCode()/100!=2)throw new IOException("GitHub release asset HTTP "+response.statusCode());
        if(response.body().length>64*1024)throw new IOException("GitHub release asset is unexpectedly large");
        return response.body();
    }

    static String checksum(String sums,String installerName){
        for(String line:sums.split("\\R")){
            String clean=line.strip();if(clean.isBlank())continue;String[] parts=clean.split("\\s+",2);
            if(parts.length==2&&validSha256(parts[0])&&parts[1].replaceFirst("^[*]","").strip().equalsIgnoreCase(installerName))return parts[0];
        }
        return "";
    }

    static java.security.PublicKey releaseKey() throws Exception {
        return java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(RELEASE_PUBLIC_KEY)));
    }

    /** Base64 Ed25519 signature (openssl pkeyutl -sign -rawin) over the exact checksum file bytes. */
    static boolean signed(byte[] data,String signatureBase64,java.security.PublicKey key){
        try{var verifier=java.security.Signature.getInstance("Ed25519");verifier.initVerify(key);verifier.update(data);return verifier.verify(java.util.Base64.getDecoder().decode(signatureBase64));}
        catch(Exception invalid){return false;}
    }

    private HttpRequest.Builder githubRequest(URI uri,Duration timeout){return HttpRequest.newBuilder(uri).timeout(timeout).header("Accept","application/vnd.github+json").header("X-GitHub-Api-Version","2022-11-28").header("User-Agent","Erdvyn-Launcher/"+CURRENT_VERSION);}
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
