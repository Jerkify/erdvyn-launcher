package tr.erdvyn.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PackServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void keepsExistingConfigWithLegacyManifest() throws Exception {
        JsonNode manifest = JSON.readTree("{\"files\":[]}");
        JsonNode entry = JSON.readTree("{\"path\":\"config/sodium-options.json\"}");
        assertTrue(PackService.shouldPreserveExisting(manifest, entry, "config/sodium-options.json"));
    }

    @Test
    void keepsMinecraftOptionsEvenIfAccidentallyListed() throws Exception {
        JsonNode manifest = JSON.readTree("{\"files\":[]}");
        JsonNode entry = JSON.readTree("{\"path\":\"options.txt\"}");
        assertTrue(PackService.shouldPreserveExisting(manifest, entry, "options.txt"));
    }

    @Test
    void keepsAdditionalManifestRoots() throws Exception {
        JsonNode manifest = JSON.readTree("{\"preserve_roots\":[\"custom-settings\"]}");
        JsonNode entry = JSON.readTree("{\"path\":\"custom-settings/player.json\"}");
        assertTrue(PackService.shouldPreserveExisting(manifest, entry, "custom-settings/player.json"));
    }

    @Test
    void stillRepairsMods() throws Exception {
        JsonNode manifest = JSON.readTree("{\"files\":[]}");
        JsonNode entry = JSON.readTree("{\"path\":\"mods/example.jar\"}");
        assertFalse(PackService.shouldPreserveExisting(manifest, entry, "mods/example.jar"));
    }

    @Test
    void allowsExplicitlyManagedConfigFiles() throws Exception {
        JsonNode manifest = JSON.readTree("{\"managed_paths\":[\"config/required.toml\"]}");
        JsonNode entry = JSON.readTree("{\"path\":\"config/required.toml\"}");
        assertFalse(PackService.shouldPreserveExisting(manifest, entry, "config/required.toml"));
    }

    @Test
    void removesOnlyExplicitPackArtifacts(@TempDir Path game) throws Exception {
        Path target = game.resolve("config/spell_engine/server.json5");
        Path kept = game.resolve("config/sodium-options.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "old");
        Files.writeString(kept, "player");
        JsonNode manifest = JSON.readTree("{\"remove_paths\":[\"config/spell_engine\"]}");
        var progress = new ArrayList<PackService.Progress>();
        PackService.removeRequestedPaths(game, manifest, progress::add);
        assertFalse(Files.exists(target));
        assertTrue(Files.exists(kept));
        assertTrue(progress.stream().anyMatch(item -> item.line().equals("[REMOVED] config/spell_engine")));
    }

    @Test
    void rejectsRemovalOutsideManagedPackRoots(@TempDir Path game) throws Exception {
        JsonNode manifest = JSON.readTree("{\"remove_paths\":[\"../options.txt\"]}");
        assertThrows(Exception.class, () -> PackService.removeRequestedPaths(game, manifest, ignored -> {}));
    }

    @Test
    void appliesNamedCleanupOnlyOnce(@TempDir Path root) throws Exception {
        Path game = root.resolve("game");
        Path state = root.resolve("state/cleanups.json");
        Path target = game.resolve("config/old-mod.toml");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "old");
        JsonNode manifest = JSON.readTree("{\"cleanup_id\":\"remove-old-mod-v1\",\"remove_paths\":[\"config/old-mod.toml\"]}");
        PackService.removeRequestedPaths(game, manifest, state, ignored -> {});
        Files.writeString(target, "regenerated");
        PackService.removeRequestedPaths(game, manifest, state, ignored -> {});
        assertTrue(Files.exists(target));
    }
}
