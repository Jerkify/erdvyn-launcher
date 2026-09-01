package tr.erdvyn.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GameOptionsTest {
    @TempDir
    Path gameDirectory;

    @Test
    void readsPlayerValuesWithoutWritingDefaults() throws Exception {
        Path options = gameDirectory.resolve("options.txt");
        String original = "renderDistance:27\nsimulationDistance:13\ncustomPlayerSetting:kept\n";
        Files.writeString(options, original);

        GameOptions values = new GameOptions(gameDirectory);

        assertEquals(27, values.renderDistance());
        assertEquals(13, values.simulationDistance());
        assertEquals(original, Files.readString(options));
    }

    @Test
    void changesOnlyRequestedSettingAndKeepsOtherLines() throws Exception {
        Path options = gameDirectory.resolve("options.txt");
        Files.writeString(options, "renderDistance:29\nmaxFps:90\ncustomPlayerSetting:kept\n");

        GameOptions values = new GameOptions(gameDirectory);
        values.setMaxFps(140);

        String saved = Files.readString(options);
        assertTrue(saved.contains("renderDistance:29"));
        assertTrue(saved.contains("maxFps:140"));
        assertTrue(saved.contains("customPlayerSetting:kept"));
        assertEquals(29, new GameOptions(gameDirectory).renderDistance());
    }
}
