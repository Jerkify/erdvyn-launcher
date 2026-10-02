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
}
