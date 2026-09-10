package tr.erdvyn.launcher;

import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import com.fasterxml.jackson.databind.*;

/** Download-only production probe. Must run with LOCALAPPDATA redirected to a disposable directory. */
public final class MapUpdateProbe {
    public static void main(String[] args) throws Exception {
        if(!LauncherPaths.appRoot().toString().contains("map-rollout-probe"))throw new IllegalStateException("Isolated LOCALAPPDATA required");
        var http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        var json=new ObjectMapper();
        var body=http.send(HttpRequest.newBuilder(URI.create(LauncherConfig.packManifestUrl())).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(body.statusCode()!=200)throw new IllegalStateException("Manifest HTTP "+body.statusCode());
        var manifest=json.readTree(body.body()); int count=0;
        for(var file:manifest.path("files")){
            String name=file.path("path").asText();
            if(!(name.startsWith("mods/erdvyn_ui-")||name.startsWith("mods/xaeroworldmap-")||name.startsWith("mods/xaerominimap-")||name.equals("config/erdvyn-map-bounds.properties")))continue;
            Path target=LauncherPaths.managedInstance().resolve(name); Files.createDirectories(target.getParent());
            var response=http.send(HttpRequest.newBuilder(URI.create(file.path("url").asText())).GET().build(),HttpResponse.BodyHandlers.ofFile(target));
            if(response.statusCode()!=200||!PackService.sha256(target).equals(file.path("sha256").asText()))throw new IllegalStateException("Bad download: "+name);
            if(name.endsWith(".properties")&&PackService.shouldPreserveExisting(manifest,file,name))throw new IllegalStateException("Bounds update would be skipped");
            System.out.println("MAP DOWNLOAD SHA256 PASS: "+name);count++;
        }
        if(count!=4)throw new IllegalStateException("Incomplete map distribution: "+count);
        // Use the actual launcher update checker/downloader, never execute the installer.
        var service=new LauncherUpdateService();var update=service.check();
        if(update==null)throw new IllegalStateException("No newer GitHub release for probe version "+LauncherUpdateService.CURRENT_VERSION);
        if(!update.installerUri().getHost().equals("github.com"))throw new IllegalStateException("Not the GitHub path");
        System.out.println("GITHUB UPDATE DETECTED: "+update.version());
        System.out.println("INSTALLER DOWNLOAD SHA256 PASS: "+service.download(update));
        System.out.println("NO INSTALLER EXECUTED; LOCAL LAUNCHER UNCHANGED");
    }
}
