package tr.erdvyn.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;


public final class ApiTicketProbe {
    private ApiTicketProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: ApiTicketProbe <manifest-sha256> <ticket-output>");
        MicrosoftAccountService accounts = new MicrosoftAccountService();
        MicrosoftAccountService.Session session = accounts.refresh();
        HttpResponse<Void> profileCheck = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/minecraft/profile"))
                        .header("Authorization", "Bearer " + session.accessToken())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        System.out.printf("PROFILE_HTTP=%d%n", profileCheck.statusCode());
        ErdvynApiClient api = new ErdvynApiClient();
        ErdvynApiClient.Login login = api.authenticate(session);
        String ticket = api.createGameTicket(args[0]);
        Files.writeString(Path.of(args[1]), ticket, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.printf("PASS: account=%s admin=%s ticket-created=true%n", login.account().minecraftName(), login.account().admin());
    }
}
