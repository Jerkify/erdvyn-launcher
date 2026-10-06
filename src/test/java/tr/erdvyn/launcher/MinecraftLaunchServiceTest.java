package tr.erdvyn.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftLaunchServiceTest {
    @Test
    void ticketMovesToTheEnvironmentOnlyWithErdvynLib028(@TempDir Path game) throws Exception {
        assertFalse(MinecraftLaunchService.ticketViaEnvironment(game));
        Files.createDirectories(game.resolve("mods"));
        Files.writeString(game.resolve("mods/erdvyn_lib-0.2.7.jar"), "");
        assertFalse(MinecraftLaunchService.ticketViaEnvironment(game));
        Files.writeString(game.resolve("mods/erdvyn_lib-0.2.10.jar"), "");
        assertTrue(MinecraftLaunchService.ticketViaEnvironment(game));
    }

    @Test
    void earlyWindowFollowsTheServiceJar(@TempDir Path game) throws Exception {
        Path toml = game.resolve("config/fml.toml");
        Files.createDirectories(game.resolve("mods"));
        MinecraftLaunchService.earlyWindow(game, line -> {});
        assertFalse(Files.exists(toml), "no jar and no config: FML's default stays");
        Files.writeString(game.resolve("mods/erdvyn_earlywindow-0.1.0.jar"), "");
        MinecraftLaunchService.earlyWindow(game, line -> {});
        assertTrue(Files.readString(toml).contains("earlyWindowProvider = \"erdvyn\""));
        Files.writeString(toml, "earlyWindowControl = true\nearlyWindowProvider = \"fmlearlywindow\"\nearlyWindowWidth = 854\n");
        MinecraftLaunchService.earlyWindow(game, line -> {});
        String edited = Files.readString(toml);
        assertTrue(edited.contains("earlyWindowProvider = \"erdvyn\"") && edited.contains("earlyWindowWidth = 854") && edited.contains("earlyWindowControl = true"));
        Files.delete(game.resolve("mods/erdvyn_earlywindow-0.1.0.jar"));
        MinecraftLaunchService.earlyWindow(game, line -> {});
        assertTrue(Files.readString(toml).contains("earlyWindowProvider = \"fmlearlywindow\""), "jar gone: back to FML's window");
    }
}
