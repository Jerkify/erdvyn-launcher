package tr.erdvyn.launcher;

import com.google.gson.JsonParser;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.java.model.MinecraftProfile;
import net.raphimc.minecraftauth.java.model.MinecraftToken;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;

final class MicrosoftAccountService {
    record Session(String name, UUID uuid, String accessToken) {
        String uuidWithoutDashes() { return uuid.toString().replace("-", ""); }
    }

    private final Path tokenFile = LauncherPaths.appRoot().resolve("account.dat");
    private JavaAuthManager manager;

    MicrosoftAccountService() { restore(); }

    synchronized Session cached() {
        if (manager == null || !manager.getMinecraftProfile().hasValue() || !manager.getMinecraftToken().hasValue()) return null;
        MinecraftProfile profile = manager.getMinecraftProfile().getCached();
        MinecraftToken token = manager.getMinecraftToken().getCached();
        return profile == null || token == null ? null : new Session(profile.getName(), profile.getId(), token.getToken());
    }

    /** Microsoft refused the stored grant (expired or revoked); it has been deleted and only a new sign-in helps. */
    static final class SignInExpired extends IOException {
        SignInExpired(Throwable cause) { super("Your Microsoft sign-in has expired", cause); }
    }

    synchronized Session refresh() throws IOException {
        if (manager == null) return null;
        try {
            MinecraftProfile profile = manager.getMinecraftProfile().getUpToDate();
            MinecraftToken token = manager.getMinecraftToken().getUpToDate();
            save();
            return new Session(profile.getName(), profile.getId(), token.getToken());
        } catch (Exception error) {
            if (!grantExpired(error)) throw error;
            logout(); // a dead grant kept on disk fails every later PLAY the same way
            throw new SignInExpired(error);
        }
    }

    /** True when Microsoft answered invalid_grant: the stored refresh grant is expired or revoked. */
    static boolean grantExpired(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SignInExpired) return true;
            String message = t.getMessage();
            if (message != null && message.contains("invalid_grant")) return true;
        }
        return false;
    }

    synchronized Session login(Consumer<MsaDeviceCode> codeConsumer) throws Exception {
        manager = JavaAuthManager.create(MinecraftAuth.createHttpClient("Erdvyn-Launcher/" + LauncherUpdateService.CURRENT_VERSION))
                .login(DeviceCodeMsaAuthService::new, codeConsumer);
        Session result;
        try {
            result = refresh();
        } catch (Exception error) {
            manager = null;
            throw new IOException("Microsoft sign-in succeeded, but this account does not own Minecraft: Java Edition or has no Java profile", error);
        }
        save();
        return result;
    }

    synchronized void logout() {
        manager = null;
        try { Files.deleteIfExists(tokenFile); } catch (IOException ignored) {}
    }

    private void restore() {
        if (!Files.isRegularFile(tokenFile) || !Platform.isWindows()) return;
        try {
            byte[] encrypted = Files.readAllBytes(tokenFile);
            byte[] json = Crypt32Util.cryptUnprotectData(encrypted);
            manager = JavaAuthManager.fromJson(MinecraftAuth.createHttpClient("Erdvyn-Launcher/" + LauncherUpdateService.CURRENT_VERSION),
                    JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (Exception ignored) { manager = null; }
    }

    private void save() throws IOException {
        if (manager == null || !Platform.isWindows()) return;
        Files.createDirectories(tokenFile.getParent());
        byte[] clear = JavaAuthManager.toJson(manager).toString().getBytes(StandardCharsets.UTF_8);
        Files.write(tokenFile, Crypt32Util.cryptProtectData(clear));
    }
}
