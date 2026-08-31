package tr.erdvyn.launcher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

final class LauncherLog {
    private LauncherLog() {}

    static synchronized void write(String message) {
        try {
            Path file = LauncherPaths.appRoot().resolve("logs").resolve("launcher.log");
            Files.createDirectories(file.getParent());
            Files.writeString(file, Instant.now() + "  " + message + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {

        }
    }
}
