package tr.erdvyn.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

final class LauncherPaths {
    static final String GAME_VERSION = "1.21.1";
    static final String NEOFORGE_VERSION = "neoforge-21.1.243";
    static final String DEFAULT_SERVER = "play.erdvyn.net";
    private LauncherPaths() {}

    static Path appRoot() {
        String local = System.getenv("LOCALAPPDATA");
        Path base = local == null || local.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Local") : Path.of(local);
        return base.resolve("Erdvyn Launcher");
    }

    static Path managedInstance() { return appRoot().resolve("instances").resolve("the-frontier"); }
    static Path curseForgeInstall() { return Path.of(System.getProperty("user.home"), "curseforge", "minecraft", "Install"); }
    static Path managedInstall() { return appRoot().resolve("minecraft"); }


    static Path gameDirectory() { return managedInstance(); }

    static Path minecraftInstall() {
        return Files.isDirectory(curseForgeInstall().resolve("versions")) ? curseForgeInstall() : managedInstall();
    }

    static String serverAddress() {
        return LauncherConfig.serverAddress();
    }

    static void prepareInstance() throws IOException {
        Path managed = managedInstance();
        Files.createDirectories(managed);
        for (String folder : List.of("mods", "config", "resourcepacks", "shaderpacks", "screenshots", "logs")) {
            Files.createDirectories(managed.resolve(folder));
        }
        Path marker = managed.resolve("erdvyn-instance.properties");
        if (!Files.exists(marker)) {
            Files.writeString(marker, "profile=the-frontier\nversion=" + GAME_VERSION + "\nloader=" + NEOFORGE_VERSION + "\n");
        }
    }

    static Path folder(String kind) throws IOException {
        Path root = gameDirectory();
        String safe = switch (kind.toLowerCase(Locale.ROOT)) {
            case "mods" -> "mods";
            case "resourcepacks" -> "resourcepacks";
            case "shaderpacks" -> "shaderpacks";
            case "config" -> "config";
            case "screenshots" -> "screenshots";
            case "logs" -> "logs";
            default -> "";
        };
        Path result = safe.isEmpty() ? root : root.resolve(safe);
        Files.createDirectories(result);
        return result;
    }
}
