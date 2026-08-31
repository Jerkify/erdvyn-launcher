package tr.erdvyn.launcher;


public final class MinecraftLaunchProbe {
    private MinecraftLaunchProbe() {}

    public static void main(String[] args) throws Exception {
        boolean autoConnect = args.length > 0 && "--auto-connect".equals(args[0]);
        MicrosoftAccountService accounts = new MicrosoftAccountService();
        MicrosoftAccountService.Session session = accounts.refresh();
        if (session == null) throw new IllegalStateException("No cached Minecraft account");

        MinecraftInstallService installer = new MinecraftInstallService();
        installer.ensureInstalled(line -> System.out.println("INSTALL " + line));

        ErdvynApiClient api = new ErdvynApiClient();
        api.authenticate(session);
        String ticket = api.createGameTicket(PackService.activeManifestSha256());

        MinecraftLaunchService launcher = new MinecraftLaunchService();
        Process process = launcher.launch(session, 8, autoConnect, ticket, System.out::println);
        System.out.printf("PASS: pid=%d alive=%s game=%s%n", process.pid(), process.isAlive(), LauncherPaths.gameDirectory());
    }
}
