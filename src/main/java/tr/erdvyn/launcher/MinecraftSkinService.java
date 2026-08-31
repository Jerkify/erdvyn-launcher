package tr.erdvyn.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

final class MinecraftSkinService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    BufferedImage head(MicrosoftAccountService.Session session) throws Exception {
        Path cache = LauncherPaths.appRoot().resolve("skins").resolve(session.uuidWithoutDashes() + ".png");
        if (Files.isRegularFile(cache)) {
            BufferedImage saved = ImageIO.read(cache.toFile());
            if (saved != null) return saved;
        }
        HttpRequest profileRequest = HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/minecraft/profile"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + session.accessToken())
                .header("User-Agent", "Erdvyn-Launcher/2.1.9")
                .GET().build();
        HttpResponse<String> profileResponse = http.send(profileRequest, HttpResponse.BodyHandlers.ofString());
        if (profileResponse.statusCode() / 100 != 2) throw new IllegalStateException("Minecraft profile HTTP " + profileResponse.statusCode());
        JsonNode skins = JSON.readTree(profileResponse.body()).path("skins");
        if (!skins.isArray() || skins.isEmpty()) return null;
        String skinUrl = skins.get(0).path("url").asText();
        if (skinUrl.isBlank()) return null;
        return downloadHead(cache, skinUrl);
    }

    BufferedImage head(String uuid) throws Exception {
        String compact=uuid.replace("-","").toLowerCase();
        if(!compact.matches("[0-9a-f]{32}"))return null;
        Path cache=LauncherPaths.appRoot().resolve("skins").resolve(compact+".png");
        if(Files.isRegularFile(cache)){BufferedImage saved=ImageIO.read(cache.toFile());if(saved!=null)return saved;}
        HttpRequest request=HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/"+compact+"?unsigned=false")).timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()/100!=2)return null;
        String encoded="";for(JsonNode property:JSON.readTree(response.body()).path("properties"))if("textures".equals(property.path("name").asText())){encoded=property.path("value").asText();break;}
        if(encoded.isBlank())return null;JsonNode textures=JSON.readTree(Base64.getDecoder().decode(encoded));String skinUrl=textures.path("textures").path("SKIN").path("url").asText();if(skinUrl.isBlank())return null;
        return downloadHead(cache,skinUrl);
    }

    private BufferedImage downloadHead(Path cache,String skinUrl) throws Exception {
        HttpResponse<byte[]> skinResponse = http.send(HttpRequest.newBuilder(URI.create(skinUrl)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (skinResponse.statusCode() / 100 != 2) throw new IllegalStateException("Skin HTTP " + skinResponse.statusCode());
        BufferedImage skin = ImageIO.read(new ByteArrayInputStream(skinResponse.body()));
        if (skin == null || skin.getWidth() < 48 || skin.getHeight() < 16) return null;
        BufferedImage head = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = head.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(skin, 0, 0, 64, 64, 8, 8, 16, 16, null);
        g.drawImage(skin, 0, 0, 64, 64, 40, 8, 48, 16, null);
        g.dispose();
        Files.createDirectories(cache.getParent());
        ImageIO.write(head, "png", cache.toFile());
        return head;
    }
}
