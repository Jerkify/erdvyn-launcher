package tr.erdvyn.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class MinecraftLaunchService {
    private static final ObjectMapper JSON = new ObjectMapper();

    Process launch(MicrosoftAccountService.Session account, int ramGb, boolean autoConnect, String erdvynTicket, Consumer<String> log) throws Exception {
        if (account == null) throw new IllegalStateException("Microsoft Minecraft account is required");
        LauncherPaths.prepareInstance();
        Path install = LauncherPaths.minecraftInstall();
        Path game = LauncherPaths.gameDirectory();
        Path vanillaJson = install.resolve("versions").resolve(LauncherPaths.GAME_VERSION).resolve(LauncherPaths.GAME_VERSION + ".json");
        Path neoJson = install.resolve("versions").resolve(LauncherPaths.NEOFORGE_VERSION).resolve(LauncherPaths.NEOFORGE_VERSION + ".json");
        if (!Files.isRegularFile(vanillaJson) || !Files.isRegularFile(neoJson)) {
            throw new IOException("Minecraft/NeoForge installation is missing: " + install);
        }

        JsonNode vanilla = JSON.readTree(vanillaJson.toFile()), neo = JSON.readTree(neoJson.toFile());
        Path libraries = install.resolve("libraries"), assets = install.resolve("assets");
        Path natives = LauncherPaths.appRoot().resolve("natives").resolve(LauncherPaths.GAME_VERSION);
        Files.createDirectories(natives);
        extractNatives(vanilla.path("libraries"), libraries, natives, autoConnect, log);

        LinkedHashSet<Path> classpathFiles = new LinkedHashSet<>();
        collectLibraries(vanilla.path("libraries"), libraries, classpathFiles, autoConnect);
        collectLibraries(neo.path("libraries"), libraries, classpathFiles, autoConnect);






        List<Path> missing = classpathFiles.stream().filter(path -> !Files.isRegularFile(path)).toList();
        if (!missing.isEmpty()) throw new IOException("Missing Minecraft library: " + missing.get(0) + " (" + missing.size() + " total)");
        String classpath = String.join(System.getProperty("path.separator"), classpathFiles.stream().map(Path::toString).toList());

        Map<String, String> variables = variables(account, install, game, libraries, assets, natives, classpath);
        List<String> command = new ArrayList<>();
        Path java = findJava(install);
        command.add(java.toString());
        command.add("-Xms1024M");
        command.add("-Xmx" + Math.max(2, Math.min(16, ramGb)) + "G");
        command.add("-XX:+UseG1GC");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Duser.language=en");
        command.add("-Duser.country=US");
        command.add("-Duser.variant=");
        if(erdvynTicket!=null&&!erdvynTicket.isBlank())command.add("-Derdvyn.sessionTicket="+erdvynTicket);
        appendArguments(command, vanilla.path("arguments").path("jvm"), variables, autoConnect);
        appendArguments(command, neo.path("arguments").path("jvm"), variables, autoConnect);
        addLoggingArgument(command, vanilla, install, variables);
        command.add(neo.path("mainClass").asText(vanilla.path("mainClass").asText()));
        appendArguments(command, vanilla.path("arguments").path("game"), variables, autoConnect);
        appendArguments(command, neo.path("arguments").path("game"), variables, autoConnect);

        Path logs = game.resolve("logs");
        Files.createDirectories(logs);
        Path processLog = logs.resolve("erdvyn-launcher-game.log");
        Files.writeString(processLog, "# Erdvyn launch " + Instant.now() + System.lineSeparator());
        log.accept("JAVA  " + java);
        log.accept("GAME  " + game);
        log.accept("RAM   " + ramGb + " GB");
        log.accept("AUTH  " + account.name() + " / " + account.uuid());
        log.accept("CP    " + classpathFiles.size() + " libraries");
        if (autoConnect) log.accept("JOIN  " + LauncherPaths.serverAddress());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(game.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(processLog.toFile()));
        Process process = builder.start();
        Thread.sleep(900);
        if (!process.isAlive()) throw new IOException("Minecraft exited during startup. See " + processLog);
        log.accept("PROCESS STARTED / PID " + process.pid());
        return process;
    }

    void awaitReady(Process process, Consumer<String> log) throws Exception {
        if (process == null) throw new IllegalArgumentException("Minecraft process is required");
        Path processLog = LauncherPaths.gameDirectory().resolve("logs").resolve("erdvyn-launcher-game.log");
        long started = System.currentTimeMillis(), deadline = started + 6 * 60_000L, lastHeartbeat = 0;
        Set<String> milestones = new LinkedHashSet<>();
        try (RandomAccessFile tail = new RandomAccessFile(processLog.toFile(), "r")) {
            long position = 0;
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive()) {
                    throw new IOException("Minecraft exited before its window became ready. See " + processLog);
                }
                long length = tail.length();
                if (length < position) position = 0;
                if (length > position) {
                    tail.seek(position);
                    String raw;
                    while ((raw = tail.readLine()) != null) {
                        String line = new String(raw.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), java.nio.charset.StandardCharsets.UTF_8);
                        if (line.contains("ModLauncher running") && milestones.add("modlauncher")) log.accept("MODLAUNCHER HANDSHAKE / ACCEPTED");
                        if (line.contains("NeoForge mod loading") && milestones.add("neoforge")) log.accept("NEOFORGE RUNTIME / ONLINE");
                        if (line.contains("Setting user:") && milestones.add("account")) log.accept("ACCOUNT SESSION / BOUND");
                        if (line.contains("Backend library: LWJGL") && milestones.add("render")) log.accept("RENDER BACKEND / LINKED");
                        if (line.contains("Reloading ResourceManager") && milestones.add("resources")) log.accept("RESOURCE BUS / COMPILING ASSETS");
                        if (line.contains("Sound engine started") && milestones.add("audio")) log.accept("AUDIO BUS / ONLINE");
                        if (line.contains("Created:") && line.contains("textures/atlas") && milestones.add("atlas")) log.accept("TEXTURE ATLAS / COMPILED");
                        boolean connected = line.contains("Connecting to " + LauncherPaths.serverAddress());
                        boolean startupComplete = line.contains("Game took ") && line.contains(" to start");
                        if (connected || startupComplete) {
                            log.accept("MINECRAFT READY / RENDER HANDOFF");
                            return;
                        }
                    }
                    position = tail.getFilePointer();
                }
                long now = System.currentTimeMillis();
                if (now - lastHeartbeat >= 7_000L) {
                    lastHeartbeat = now;
                    log.accept("AWAITING MINECRAFT / PID " + process.pid() + " / " + Math.max(0, (now - started) / 1000) + "S");
                }
                Thread.sleep(350);
            }
        }
        if (!process.isAlive()) throw new IOException("Minecraft stopped while waiting for its window");
        log.accept("MINECRAFT PROCESS STABLE / HANDOFF");
    }

    private static Map<String, String> variables(MicrosoftAccountService.Session account, Path install, Path game,
                                                  Path libraries, Path assets, Path natives, String classpath) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("${auth_player_name}", account.name());
        values.put("${version_name}", LauncherPaths.NEOFORGE_VERSION);
        values.put("${game_directory}", game.toString());
        values.put("${assets_root}", assets.toString());
        values.put("${game_assets}", assets.resolve("virtual").resolve("legacy").toString());
        values.put("${assets_index_name}", "17");
        values.put("${auth_uuid}", account.uuidWithoutDashes());
        values.put("${auth_access_token}", account.accessToken());
        values.put("${auth_session}", "token:" + account.accessToken() + ":" + account.uuidWithoutDashes());
        values.put("${clientid}", "");
        values.put("${auth_xuid}", "");
        values.put("${user_type}", "msa");
        values.put("${version_type}", "release");
        values.put("${natives_directory}", natives.toString());
        values.put("${launcher_name}", "ErdvynLauncher");
        values.put("${launcher_version}", LauncherUpdateService.CURRENT_VERSION);
        values.put("${classpath}", classpath);
        values.put("${classpath_separator}", System.getProperty("path.separator"));
        values.put("${library_directory}", libraries.toString());
        values.put("${resolution_width}", "1280");
        values.put("${resolution_height}", "720");
        values.put("${quickPlayPath}", game.resolve("quickPlay").toString());





        values.put("${quickPlayMultiplayer}", LauncherPaths.serverAddress());
        return values;
    }

    private static Path findJava(Path install) throws IOException {
        List<Path> candidates = List.of(
                install.resolve("java/Jre_21/bin/javaw.exe"),
                install.resolve("java/java-runtime-delta/bin/javaw.exe"),
                Path.of(System.getProperty("java.home"), "bin", "javaw.exe"),
                Path.of(System.getProperty("java.home"), "bin", "java.exe"));
        return candidates.stream().filter(Files::isRegularFile).findFirst()
                .orElseThrow(() -> new IOException("Java 21 runtime was not found"));
    }

    private static void collectLibraries(JsonNode nodes, Path root, Set<Path> output, boolean autoConnect) {
        if (!nodes.isArray()) return;
        for (JsonNode library : nodes) {
            if (!allowed(library.path("rules"), autoConnect)) continue;
            JsonNode artifact = library.path("downloads").path("artifact");
            String path = artifact.path("path").asText();
            if (!path.isBlank()) output.add(root.resolve(path));
        }
    }

    private static void extractNatives(JsonNode nodes, Path libraries, Path natives, boolean autoConnect, Consumer<String> log) throws IOException {
        if (!nodes.isArray()) return;
        for (JsonNode library : nodes) {
            if (!allowed(library.path("rules"), autoConnect)) continue;
            String classifier = library.path("natives").path("windows").asText().replace("${arch}", "64");
            if (classifier.isBlank()) continue;
            JsonNode download = library.path("downloads").path("classifiers").path(classifier);
            String relative = download.path("path").asText();
            if (relative.isBlank()) continue;
            Path archive = libraries.resolve(relative);
            if (!Files.isRegularFile(archive)) throw new IOException("Missing native library: " + archive);
            List<String> excludes = new ArrayList<>();
            JsonNode excluded = library.path("extract").path("exclude");
            if (excluded.isArray()) excluded.forEach(value -> excludes.add(value.asText()));
            try (FileSystem zip = FileSystems.newFileSystem(archive)) {
                Path zipRoot = zip.getPath("/");
                try (var entries = Files.walk(zipRoot)) {
                    for (Path entry : entries.filter(Files::isRegularFile).toList()) {
                        String relativeName = zipRoot.relativize(entry).toString().replace('\\', '/');
                        if (relativeName.startsWith("META-INF/") || excludes.stream().anyMatch(relativeName::startsWith)) continue;
                        Path target = natives.resolve(relativeName).normalize();
                        if (!target.startsWith(natives)) throw new IOException("Unsafe native path: " + relativeName);
                        Files.createDirectories(target.getParent());
                        try (InputStream input = Files.newInputStream(entry)) {
                            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
        }
        log.accept("NATIVES READY");
    }

    private static void appendArguments(List<String> output, JsonNode arguments, Map<String, String> variables, boolean autoConnect) {
        if (!arguments.isArray()) return;
        for (JsonNode argument : arguments) {
            if (argument.isTextual()) output.add(replace(argument.asText(), variables));
            else if (argument.isObject() && allowed(argument.path("rules"), autoConnect)) {
                JsonNode value = argument.path("value");
                if (value.isArray()) value.forEach(item -> output.add(replace(item.asText(), variables)));
                else if (value.isTextual()) output.add(replace(value.asText(), variables));
            }
        }
    }

    private static boolean allowed(JsonNode rules, boolean autoConnect) {
        if (!rules.isArray() || rules.isEmpty()) return true;
        boolean result = false;
        for (JsonNode rule : rules) {
            if (!ruleMatches(rule, autoConnect)) continue;
            result = "allow".equals(rule.path("action").asText());
        }
        return result;
    }

    private static boolean ruleMatches(JsonNode rule, boolean autoConnect) {
        JsonNode os = rule.path("os");
        if (!os.isMissingNode()) {
            String name = os.path("name").asText();
            if (!name.isBlank() && !"windows".equals(name)) return false;
            String arch = os.path("arch").asText();
            if (!arch.isBlank() && !(arch.contains("64") && System.getProperty("os.arch").contains("64"))) return false;
            String version = os.path("version").asText();
            if (!version.isBlank() && !System.getProperty("os.version").matches(version)) return false;
        }
        JsonNode features = rule.path("features");
        if (features.isObject()) {
            var names = features.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                boolean expected = features.path(name).asBoolean();
                boolean actual = switch (name) {
                    case "has_quick_plays_support", "is_quick_play_multiplayer" -> autoConnect;
                    default -> false;
                };
                if (expected != actual) return false;
            }
        }
        return true;
    }

    private static String replace(String input, Map<String, String> variables) {
        String result = input;
        for (Map.Entry<String, String> entry : variables.entrySet()) result = result.replace(entry.getKey(), entry.getValue());
        return result;
    }

    private static void addLoggingArgument(List<String> command, JsonNode vanilla, Path install, Map<String, String> variables) {
        JsonNode client = vanilla.path("logging").path("client");
        String argument = client.path("argument").asText(), id = client.path("file").path("id").asText();
        if (argument.isBlank() || id.isBlank()) return;
        Path file = install.resolve("assets").resolve("log_configs").resolve(id);
        if (Files.isRegularFile(file)) command.add(replace(argument, Map.of("${path}", file.toString())));
    }
}
