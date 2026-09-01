package tr.erdvyn.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
