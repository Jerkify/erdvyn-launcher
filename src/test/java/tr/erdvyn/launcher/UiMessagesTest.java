package tr.erdvyn.launcher;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UiMessagesTest {
    @Test void actionableHints(){
        assertTrue(UiMessages.hint(new java.io.IOException("Java 21 runtime was not found")).startsWith("Java 21 was not found"));
        assertTrue(UiMessages.hint(new IllegalStateException("Modpack verification failed",new java.net.UnknownHostException("api.erdvyn.net"))).startsWith("Could not reach"));
        assertTrue(UiMessages.hint(new java.io.IOException("Manifest HTTP 503")).contains("HTTP 5xx"));
        assertTrue(UiMessages.hint(new java.io.IOException("SHA-256 mismatch")).contains("SHA-256"));
        assertTrue(UiMessages.hint(new java.io.IOException("There is not enough space on the disk")).contains("disk is full"));
        assertTrue(UiMessages.hint(new java.nio.file.AccessDeniedException("mods/a.jar")).contains("could not be accessed"));
        assertTrue(UiMessages.hint(new java.io.IOException("Microsoft sign-in succeeded, but this account does not own Minecraft: Java Edition or has no Java profile")).contains("no Minecraft: Java Edition profile"));
        assertNull(UiMessages.hint(new java.io.IOException("HTTP 404")));
        assertNull(UiMessages.hint(null));
    }
}
