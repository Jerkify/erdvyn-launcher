package tr.erdvyn.launcher;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;


public final class LauncherDiagnostics {
    private LauncherDiagnostics() {}

    public static void main(String[] args) throws Exception {
        LauncherPaths.prepareInstance();
        if (args.length == 1 && "--verify-pack".equals(args[0])) {
            PackService.Result result = new PackService().verifyAndRepair(progress ->
                    System.out.printf("%3d%% %s%n", (int) (progress.value() * 100), progress.line()));
            if (result.failed() > 0) throw new IllegalStateException(result.failed() + " package files failed");
            System.out.printf("PASS: verified=%d downloaded=%d kept=%d version=%s%n", result.verified(), result.downloaded(), result.kept(), result.version());
            return;
        }
        if (args.length == 1 && "--ping-server".equals(args[0])) {
            ArrayBlockingQueue<MinecraftServerStatus.Snapshot> result = new ArrayBlockingQueue<>(1);
            try (MinecraftServerStatus status = new MinecraftServerStatus(result::offer)) {
                status.start();
                MinecraftServerStatus.Snapshot snapshot = result.poll(6, TimeUnit.SECONDS);
                if (snapshot == null || !snapshot.online()) throw new IllegalStateException("Server did not answer the status ping");
                System.out.printf("PASS: players=%d/%d latency=%dms version=%s%n", snapshot.players(), snapshot.maxPlayers(), snapshot.latencyMs(), snapshot.version());
            }
            return;
        }
        System.out.println("Usage: LauncherDiagnostics --verify-pack | --ping-server");
    }
}
