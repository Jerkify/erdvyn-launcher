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
            // One previous file is kept: the log never grows without bound on a launcher that stays installed for years.
            if (Files.isRegularFile(file) && Files.size(file) > 2L << 20) Files.move(file, file.resolveSibling("launcher.1.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(file, Instant.now() + "  " + message + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {

        }
    }
}
