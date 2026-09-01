package tr.erdvyn.launcher;

final class PackVerifierCli {
    private PackVerifierCli() {}

    public static void main(String[] args) throws Exception {
        PackService.Result result = new PackService().verifyAndRepair(progress ->
                System.out.printf("%3d%% %s%n", Math.round(progress.value() * 100), progress.line()));
        if (result.failed() > 0) throw new IllegalStateException("Pack verification failed: " + result.failed());
        System.out.printf("VERIFIED=%d DOWNLOADED=%d KEPT=%d VERSION=%s%n", result.verified(), result.downloaded(), result.kept(), result.version());
    }
}
