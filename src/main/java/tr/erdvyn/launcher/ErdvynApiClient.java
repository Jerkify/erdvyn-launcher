package tr.erdvyn.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class ErdvynApiClient {
    record Account(String uuid, String minecraftName, boolean created, boolean admin, boolean rootAdmin) {}
    record Login(String token, Account account) {}
    record Status(boolean online, Long uptimeSeconds, Double tps, List<String> players) {
        static Status offline() { return new Status(false, null, null, List.of()); }
    }
    record NewsPost(long id, String authorUuid, String title, String body, String imageUrl, long publishedAt) {}
    record AdminAccount(String uuid,String minecraftName,long grantedAt,boolean root) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
    private volatile Login login;

    boolean configured() { return baseUrl() != null; }
    Login current() { return login; }

    Login authenticate(MicrosoftAccountService.Session minecraft) throws Exception {
        String base = baseUrl();if(base==null)throw new IllegalStateException("ERDVYN_API_URL is not configured");
        HttpRequest challengeRequest=HttpRequest.newBuilder(URI.create(base+"/api/auth/minecraft/challenge")).timeout(Duration.ofSeconds(12)).GET().build();
        HttpResponse<String> challengeResponse=http.send(challengeRequest,HttpResponse.BodyHandlers.ofString());
        if(challengeResponse.statusCode()/100!=2)throw new IllegalStateException(responseError("Minecraft login challenge",challengeResponse));
        JsonNode challengeJson=JSON.readTree(challengeResponse.body());
        String challenge=challengeJson.path("challenge").asText(),serverId=challengeJson.path("server_id").asText();
        if(challenge.isBlank()||serverId.isBlank())throw new IllegalStateException("Minecraft login challenge is incomplete");
        String joinBody=JSON.createObjectNode().put("accessToken",minecraft.accessToken()).put("selectedProfile",minecraft.uuidWithoutDashes()).put("serverId",serverId).toString();
        HttpRequest joinRequest=HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/join")).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(joinBody)).build();
        HttpResponse<String> joinResponse=http.send(joinRequest,HttpResponse.BodyHandlers.ofString());
        if(joinResponse.statusCode()/100!=2)throw new IllegalStateException("Minecraft session proof HTTP "+joinResponse.statusCode());
        String body=JSON.createObjectNode().put("challenge",challenge).put("minecraft_name",minecraft.name()).toString();
        HttpRequest request=HttpRequest.newBuilder(URI.create(base+"/api/auth/minecraft")).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()/100!=2)throw new IllegalStateException(responseError("Erdvyn account",response));
        String resultUrl=base+"/api/auth/minecraft/result?challenge="+URLEncoder.encode(challenge,StandardCharsets.UTF_8);
        for(int attempt=0;attempt<120&&response.statusCode()==202;attempt++){
            Thread.sleep(500);
            HttpRequest resultRequest=HttpRequest.newBuilder(URI.create(resultUrl)).timeout(Duration.ofSeconds(12)).GET().build();
            response=http.send(resultRequest,HttpResponse.BodyHandlers.ofString());
        }
        if(response.statusCode()==202)throw new IllegalStateException("Minecraft login verification timed out");
        if(response.statusCode()/100!=2)throw new IllegalStateException(responseError("Erdvyn account",response));
        JsonNode root=JSON.readTree(response.body()),account=root.path("account");String token=root.path("token").asText();if(token.isBlank())throw new IllegalStateException("Erdvyn account token missing");
        login=new Login(token,new Account(account.path("uuid").asText(),account.path("minecraft_name").asText(),account.path("created").asBoolean(),account.path("admin").asBoolean(),account.path("root_admin").asBoolean()));return login;
    }

    String createGameTicket(String manifestSha256) throws Exception {
        Login active=login;if(active==null)throw new IllegalStateException("Erdvyn account is not authenticated");String base=baseUrl();
        String query="?manifest_sha256="+URLEncoder.encode(manifestSha256,StandardCharsets.UTF_8);HttpRequest request=HttpRequest.newBuilder(URI.create(base+"/api/session/ticket"+query)).timeout(Duration.ofSeconds(12)).header("Authorization","Bearer "+active.token()).POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()/100!=2)throw new IllegalStateException(responseError("Session ticket",response));String ticket=JSON.readTree(response.body()).path("ticket").asText();if(ticket.isBlank())throw new IllegalStateException("Session ticket missing");return ticket;
    }

    String webSocketUrl() {
        Login active=login;String base=baseUrl();if(active==null||base==null)return null;String ws=base.replaceFirst("^https://","wss://").replaceFirst("^http://","ws://");return ws+"/ws?token="+URLEncoder.encode(active.token(),StandardCharsets.UTF_8);
    }

    Status fetchStatus() throws Exception {
        String base=baseUrl();if(base==null)return Status.offline();
        HttpRequest request=HttpRequest.newBuilder(URI.create(base+"/api/status")).timeout(Duration.ofSeconds(8)).GET().build();
        HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()/100!=2)throw new IllegalStateException(responseError("Status",response));
        JsonNode root=JSON.readTree(response.body());List<String> players=new ArrayList<>();root.path("players").forEach(node->{String value=node.asText().strip();if(!value.isEmpty())players.add(value);});
        Long uptime=root.path("uptime_seconds").isNumber()?root.path("uptime_seconds").asLong():null;
        Double tps=root.path("tps").isNumber()?root.path("tps").asDouble():null;
        return new Status(root.path("online").asBoolean(false),uptime,tps,List.copyOf(players));
    }

    List<NewsPost> fetchNews() throws Exception {
        String base=baseUrl();if(base==null)return List.of();
        HttpRequest request=HttpRequest.newBuilder(URI.create(base+"/api/news")).timeout(Duration.ofSeconds(8)).GET().build();
        HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()/100!=2)throw new IllegalStateException(responseError("News",response));
        List<NewsPost> posts=new ArrayList<>();for(JsonNode item:JSON.readTree(response.body()))posts.add(new NewsPost(item.path("id").asLong(),item.path("author_uuid").asText(),item.path("title").asText(),item.path("body").asText(),item.path("image_url").isTextual()?item.path("image_url").asText():null,item.path("published_at").asLong()));
        return List.copyOf(posts);
    }

    void publishNews(String title,String body) throws Exception {
        Login active=login;if(active==null||!active.account().admin())throw new IllegalStateException("Admin account required");String base=baseUrl();
        String payload=JSON.createObjectNode().put("title",title.strip()).put("body",body.strip()).toString();
        HttpRequest request=HttpRequest.newBuilder(URI.create(base+"/api/news")).timeout(Duration.ofSeconds(12)).header("Authorization","Bearer "+active.token()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()/100!=2)throw new IllegalStateException(responseError("Publish news",response));
    }

    String grantAdmin(String target) throws Exception { return adminTarget("POST", target); }
    String revokeAdmin(String target) throws Exception { return adminTarget("DELETE", target); }

    private String adminTarget(String method,String target) throws Exception {
        Login active=login;if(active==null||!active.account().rootAdmin())throw new IllegalStateException("Root admin account required");String base=baseUrl();
        String payload=JSON.createObjectNode().put("target",target.strip()).toString();
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(base+"/api/admin/grants")).timeout(Duration.ofSeconds(12)).header("Authorization","Bearer "+active.token()).header("Content-Type","application/json");
        HttpRequest request=("DELETE".equals(method)?builder.method("DELETE",HttpRequest.BodyPublishers.ofString(payload)):builder.POST(HttpRequest.BodyPublishers.ofString(payload))).build();
        HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()/100!=2)throw new IllegalStateException(responseError("Admin access",response));
        JsonNode account=JSON.readTree(response.body()).path("account");return account.path("minecraft_name").asText(target);
    }

    long queueAdminCommand(String command) throws Exception {
        Login active=login;if(active==null||!active.account().admin())throw new IllegalStateException("Admin account required");String base=baseUrl();
        String payload=JSON.createObjectNode().put("command",command.strip()).toString();
        HttpRequest request=HttpRequest.newBuilder(URI.create(base+"/api/admin/commands")).timeout(Duration.ofSeconds(12)).header("Authorization","Bearer "+active.token()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()/100!=2)throw new IllegalStateException(responseError("Admin command",response));return JSON.readTree(response.body()).path("id").asLong();
    }

    List<AdminAccount> fetchAdmins() throws Exception {
        Login active=login;if(active==null||!active.account().admin())return List.of();String base=baseUrl();
        HttpRequest request=HttpRequest.newBuilder(URI.create(base+"/api/admin/activity")).timeout(Duration.ofSeconds(12)).header("Authorization","Bearer "+active.token()).GET().build();
        HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()/100!=2)throw new IllegalStateException(responseError("Admin list",response));
        List<AdminAccount> admins=new ArrayList<>();for(JsonNode item:JSON.readTree(response.body()).path("admins"))admins.add(new AdminAccount(item.path("uuid").asText(),item.path("minecraft_name").asText("--"),item.path("granted_at").asLong(),"root".equals(item.path("role").asText())));return List.copyOf(admins);
    }

    private static String responseError(String operation,HttpResponse<String> response){try{String detail=JSON.readTree(response.body()).path("detail").asText();if(!detail.isBlank())return operation+": "+detail;}catch(Exception ignored){}return operation+" HTTP "+response.statusCode();}

    private static String baseUrl(){String value=LauncherConfig.apiUrl();if(value.isBlank())return null;return value.replaceAll("/+$","");}
}
