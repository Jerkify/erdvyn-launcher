package tr.erdvyn.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class HashCacheTest {
    @Test
    void trustedStampSkipsOnlyUnchangedFiles(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("mod.jar"), store = dir.resolve("hash-cache.json");
        Files.writeString(jar, "first");
        FileTime stamp = Files.getLastModifiedTime(jar);
        HashCache cache = new HashCache(store);
        String first = cache.sha256(jar, true);
        cache.save();

        // Same size and timestamp, different bytes: a trusted read keeps the stamp, a full audit sees the change.
        Files.writeString(jar, "fir5t");
        Files.setLastModifiedTime(jar, stamp);
        HashCache reloaded = new HashCache(store);
        assertEquals(first, reloaded.sha256(jar, true));
        assertNotEquals(first, reloaded.sha256(jar, false));

        // A new timestamp always forces a fresh hash, trusted or not.
        Files.writeString(jar, "first");
        Files.setLastModifiedTime(jar, FileTime.fromMillis(stamp.toMillis() + 5_000));
        assertEquals(first, reloaded.sha256(jar, true));
        Files.writeString(jar, "other");
        Files.setLastModifiedTime(jar, FileTime.fromMillis(stamp.toMillis() + 9_000));
        assertNotEquals(first, reloaded.sha256(jar, true));
    }

    @Test
    void clearForgetsEveryStamp(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("lib.jar"), store = dir.resolve("hash-cache.json");
        Files.writeString(jar, "abc");
        FileTime stamp = Files.getLastModifiedTime(jar);
        HashCache cache = new HashCache(store);
        String sha1 = cache.sha1(jar, true);
        cache.clear();
        Files.writeString(jar, "abd");
        Files.setLastModifiedTime(jar, stamp);
        assertNotEquals(sha1, new HashCache(store).sha1(jar, true));
    }
}
