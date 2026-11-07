package tr.erdvyn.launcher;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.List;
import java.util.*;
import java.util.prefs.Preferences;

public final class ErdvynLauncher {
    private ErdvynLauncher() {}
    private static boolean uiTest(){return Boolean.getBoolean("erdvyn.uiTest");}
    private static Preferences prefs(Class<?> type){return uiTest()?Preferences.userRoot().node("/erdvyn-ui-language-test/"+type.getSimpleName()):Preferences.userNodeForPackage(type);}

    public static void main(String[] args) {
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        String requestedCapture = null, requestedSelfTest = null, requestedPage = null,requestedWindow=null;boolean requestedBoot=false,requestedLaunchPreview=false,requestedCrash=false,requestedSignIn=false,requestedSample=false;int requestedCaptureDelay=1800;
        for (int i = 0; i < args.length; i++) {
            if ("--capture".equals(args[i]) && i + 1 < args.length) requestedCapture = args[++i];
            else if ("--self-test".equals(args[i]) && i + 1 < args.length) requestedSelfTest = args[++i];
            else if ("--page".equals(args[i]) && i + 1 < args.length) requestedPage = args[++i];
            else if ("--boot".equals(args[i])||"--launcher-boot".equals(args[i])) requestedBoot=true;
            else if ("--launch-preview".equals(args[i])) requestedLaunchPreview=true;
            else if ("--crash-preview".equals(args[i])) requestedCrash=true;
            else if ("--sign-in-preview".equals(args[i])) requestedSignIn=true;
            else if ("--sample-data".equals(args[i])) requestedSample=true;
            else if ("--window".equals(args[i])&&i+1<args.length)requestedWindow=args[++i];
            else if ("--capture-delay".equals(args[i])&&i+1<args.length)try{requestedCaptureDelay=Math.max(250,Integer.parseInt(args[++i]));}catch(NumberFormatException ignored){}
        }
        // Two launchers would audit and download into the same instance folder at once: a second start only brings the first forward.
        if(!uiTest()&&!SingleInstance.acquire())return;
        String capturePath = requestedCapture, selfTestPath = requestedSelfTest, initialPage=requestedPage,initialWindow=requestedWindow;boolean initialBoot=requestedBoot,initialLaunchPreview=requestedLaunchPreview,initialCrash=requestedCrash,initialSignIn=requestedSignIn,initialSample=requestedSample;int captureDelayMs=requestedCaptureDelay;
        EventQueue.invokeLater(() -> {
            LauncherFrame frame = new LauncherFrame();SingleInstance.frame=frame;
            if(initialWindow!=null)try{String[] size=initialWindow.toLowerCase(Locale.ROOT).split("x");frame.setSize(Math.max(1040,Integer.parseInt(size[0])),Math.max(640,Integer.parseInt(size[1])));frame.setLocationRelativeTo(null);frame.validate();}catch(Exception ignored){}
            // UI tests (captures, self-test) paint offscreen buffers: keep the window off-screen, unfocused and out of the taskbar.
            if(uiTest()){frame.setType(Window.Type.UTILITY);frame.setAutoRequestFocus(false);frame.setFocusableWindowState(false);frame.setLocation(-32000,-32000);}
            frame.setVisible(true);
            if(!uiTest())frame.canvas.requestFocusInWindow();
            if(uiTest()&&initialSample)frame.canvas.loadSampleData();
            if(initialPage!=null)try{frame.canvas.showPage(Page.valueOf(initialPage.toUpperCase(Locale.ROOT)));}catch(Exception ignored){}
            if(initialBoot||(capturePath==null&&selfTestPath==null&&initialPage==null))frame.canvas.startLauncherBoot();
            if(initialLaunchPreview)frame.canvas.startLaunchPreview();
            if(uiTest()&&initialCrash){frame.canvas.startLaunchPreview();frame.canvas.showGameCrash(-1);}
            if(uiTest()&&initialSignIn)frame.canvas.previewSignIn();
            if (capturePath != null) {
                javax.swing.Timer capture = new javax.swing.Timer(captureDelayMs, event -> {
                    try {
                        BufferedImage shot = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_ARGB);
                        Graphics2D graphics = shot.createGraphics();
                        frame.getContentPane().paint(graphics);
                        graphics.dispose();
                        ImageIO.write(shot, "png", new File(capturePath));
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    } finally {
                        frame.shutdownAndExit();
                    }
                });
                capture.setRepeats(false);
                capture.start();
            }
            else if(selfTestPath!=null){
                javax.swing.Timer test=new javax.swing.Timer(800,event->{
                    try{Files.writeString(Path.of(selfTestPath),frame.canvas.runInteractionSelfTest());}
                    catch(Exception ex){ex.printStackTrace();}
                    finally{frame.shutdownAndExit();}
                });test.setRepeats(false);test.start();
            }
        });
    }

    enum Page { HOME, NEWS, PACK, GAME, MAP, SETTINGS, ADMIN }

    /**
     * One launcher per Windows user. The first start holds a file lock and listens on a loopback port; a second start
     * sends SHOW there and exits, so a double click brings the open launcher forward instead of starting a rival
     * that would verify the same pack into the same folder. Any failure of the guard itself lets the launcher start.
     */
    static final class SingleInstance {
        static volatile LauncherFrame frame;
        private static FileChannel channel; private static FileLock lock; private static ServerSocket server; // held for the life of the process
        static boolean acquire(){
            try{
                Path root=LauncherPaths.appRoot();Files.createDirectories(root);Path portFile=root.resolve("launcher.port");
                channel=FileChannel.open(root.resolve("launcher.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                lock=channel.tryLock();
                if(lock==null){
                    try(Socket socket=new Socket()){socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),Integer.parseInt(Files.readString(portFile).strip())),800);socket.getOutputStream().write("SHOW\n".getBytes(StandardCharsets.US_ASCII));}catch(Exception ignored){}
                    return false;
                }
                server=new ServerSocket(0,4,InetAddress.getLoopbackAddress());Files.writeString(portFile,Integer.toString(server.getLocalPort()));
                Thread listener=new Thread(()->{while(!server.isClosed()){try(Socket socket=server.accept()){socket.setSoTimeout(1000);if("SHOW\n".equals(new String(socket.getInputStream().readNBytes(5),StandardCharsets.US_ASCII)))EventQueue.invokeLater(()->{LauncherFrame active=frame;if(active!=null)active.bringToFront();});}catch(Exception ignored){}}},"erdvyn-single-instance");
                listener.setDaemon(true);listener.start();
                return true;
            }catch(Exception unavailable){return true;}
        }
    }

    @SuppressWarnings("serial")
    static final class LauncherFrame extends JFrame {
        final LauncherCanvas canvas;
        private final AtomicBoolean shuttingDown = new AtomicBoolean();

        LauncherFrame() {
            super("Erdvyn Launcher");
            try { setIconImages(iconSizes()); } catch (Exception ignored) {}
            setUndecorated(true);
            setBackground(Color.BLACK);
            setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            // A 1366x768 laptop at 125% only has ~1093x570 usable: never open past it, or the close button lands off-screen.
            Rectangle usable=GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();setMaximizedBounds(usable);
            setMinimumSize(new Dimension(Math.min(1040,usable.width),Math.min(640,usable.height)));
            Rectangle saved=savedBounds();
            if(saved!=null)setBounds(saved);else{setSize(Math.min(1242,usable.width),Math.min(768,usable.height));setLocationRelativeTo(null);}
            canvas = new LauncherCanvas(this);
            setContentPane(canvas);
            addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { canvas.powerOffAndExit(); }
            });
        }

        void shutdownAndExit() {
            if (!shuttingDown.compareAndSet(false, true)) return;
            if((getExtendedState()&MAXIMIZED_BOTH)==0){Preferences p=prefs(LauncherFrame.class);Rectangle b=getBounds();p.putInt("windowX",b.x);p.putInt("windowY",b.y);p.putInt("windowW",b.width);p.putInt("windowH",b.height);}
            canvas.shutdown();
            setVisible(false);
            dispose();
            System.exit(0);
        }

        /** Minecraft owns the screen now: hide (not dispose) so a crash can bring the launcher back with its report. */
        void hideWhileGameRuns(){canvas.suspendForGame();setVisible(false);}
        void showAfterGame(){setVisible(true);if((getExtendedState()&ICONIFIED)!=0)setExtendedState(getExtendedState()&~ICONIFIED);toFront();canvas.requestFocusInWindow();}
        void toggleMaximized(){setExtendedState((getExtendedState()&MAXIMIZED_BOTH)!=0?NORMAL:MAXIMIZED_BOTH);}
        /** A second launcher start: come forward, unless Minecraft owns the screen (then the window stays hidden on purpose). */
        void bringToFront(){if(!isVisible())return;if((getExtendedState()&ICONIFIED)!=0)setExtendedState(getExtendedState()&~ICONIFIED);toFront();requestFocus();}

        /** Last window bounds, only while its header is still on a connected screen (monitor unplugged = default placement). */
        private Rectangle savedBounds(){
            Preferences p=prefs(LauncherFrame.class);Dimension min=getMinimumSize();int w=p.getInt("windowW",0),h=p.getInt("windowH",0);if(w<=0||h<=0)return null;
            Rectangle r=new Rectangle(p.getInt("windowX",0),p.getInt("windowY",0),Math.max(min.width,w),Math.max(min.height,h));
            for(GraphicsDevice device:GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())if(device.getDefaultConfiguration().getBounds().contains(r.x+120,r.y+30))return r;
            return null;
        }

        /** Every PNG entry of the app .ico, so Windows picks a hand-sized title bar/taskbar icon instead of shrinking one bitmap. */
        static List<Image> iconSizes() throws Exception {
            byte[] ico;try(InputStream in=Objects.requireNonNull(ErdvynLauncher.class.getResourceAsStream("/assets/erdvyn-app-icon.ico"))){ico=in.readAllBytes();}
            java.nio.ByteBuffer data=java.nio.ByteBuffer.wrap(ico).order(java.nio.ByteOrder.LITTLE_ENDIAN);List<Image> images=new ArrayList<>();
            for(int i=0,count=data.getShort(4);i<count;i++){int size=data.getInt(6+16*i+8),offset=data.getInt(6+16*i+12);images.add(ImageIO.read(new java.io.ByteArrayInputStream(ico,offset,size)));}
            return images;
        }
    }

    @SuppressWarnings("serial")
    static final class LauncherCanvas extends JPanel implements MouseListener, MouseMotionListener, MouseWheelListener, KeyListener, ActionListener {
        private static final int SIDEBAR = 82, HEADER = 70, FOOTER = 34, RAIL_EXPAND = 150;
        private static final Font F16 = Retro.pixel(16), F32 = Retro.pixel(32);
        /** Inactive icons and quiet labels: the same neutral steel on every page, so only live things carry colour. */
        private static final Color STEEL = new Color(104, 120, 132), QUIET = new Color(150, 164, 172);
        private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm"), HH_MM_SS = DateTimeFormatter.ofPattern("HH:mm:ss");
        private static final double BOOT_SECONDS = 1.3;

        private final LauncherFrame frame;
        private final Preferences preferences = prefs(ErdvynLauncher.class);
        private final javax.swing.Timer timer = new javax.swing.Timer(16, this);
        private final Random random = new Random(72491);
        private final UiSound sound = new UiSound();
        private final Map<String, String> strings = new HashMap<>();
        private final Rectangle[] navBounds = new Rectangle[7];
        private final Rectangle playBounds = new Rectangle(), instancePathBounds = new Rectangle(),  closeBounds = new Rectangle(), minimizeBounds = new Rectangle();
        private final Rectangle updateBounds = new Rectangle(), profileBounds = new Rectangle(), notificationBounds = new Rectangle(), notificationPanelBounds = new Rectangle(), articleCloseBounds = new Rectangle(), articleDeleteBounds = new Rectangle();
        /** Admin delete in the open article: the first click arms it for that dispatch, the second deletes. */
        private long newsDeleteArmed=-1;private boolean newsDeleteInProgress;
        private final Rectangle newsComposeBounds=new Rectangle(),newsTitleInputBounds=new Rectangle(),newsBodyInputBounds=new Rectangle(),newsPublishBounds=new Rectangle(),newsCancelBounds=new Rectangle();
        private final Rectangle audioBounds = new Rectangle(), volumeBounds = new Rectangle(), installPackBounds = new Rectangle(), verifyBounds = new Rectangle(), folderBounds = new Rectangle();
        private final Rectangle chatInputBounds = new Rectangle(), chatSendBounds = new Rectangle(), microsoftBounds = new Rectangle(), erdvynAccountBounds = new Rectangle(), cancelLoginBounds = new Rectangle();
        
        private final Rectangle[] settingBounds = {new Rectangle(),new Rectangle(),new Rectangle()};
        private final Rectangle ramMinusBounds=new Rectangle(),ramPlusBounds=new Rectangle(),renderMinusBounds=new Rectangle(),renderPlusBounds=new Rectangle(),simulationMinusBounds=new Rectangle(),simulationPlusBounds=new Rectangle();
        private final Rectangle fpsMinusBounds=new Rectangle(),fpsPlusBounds=new Rectangle(),guiMinusBounds=new Rectangle(),guiPlusBounds=new Rectangle(),vsyncBounds=new Rectangle();
        private final Rectangle[] settingsFolderBounds={new Rectangle(),new Rectangle(),new Rectangle(),new Rectangle()};
        private final Rectangle optionsFileBounds=new Rectangle(),configFolderBounds=new Rectangle();
        private final Rectangle adminTargetBounds=new Rectangle(),adminCommandBounds=new Rectangle(),adminGrantBounds=new Rectangle(),adminRevokeBounds=new Rectangle(),adminBanBounds=new Rectangle(),adminUnbanBounds=new Rectangle(),adminExecuteBounds=new Rectangle();
        private final Rectangle launchDismissBounds=new Rectangle(),launchLogsBounds=new Rectangle(),launchCancelBounds=new Rectangle(),crashReportsBounds=new Rectangle(),crashPlayBounds=new Rectangle(),notificationClearBounds=new Rectangle();
        private final Rectangle[] newsBounds = new Rectangle[12];
        private final Rectangle[] homeNewsBounds = {new Rectangle(), new Rectangle(), new Rectangle(), new Rectangle()};
        private final HubClient hub = new HubClient(this::onHubEvent);
        private MinecraftServerStatus serverStatus = new MinecraftServerStatus(this::onServerStatus); // replaced on resume: a closed one cannot restart
        private final MicrosoftAccountService accountService = new MicrosoftAccountService();
        private final ErdvynApiClient apiClient = new ErdvynApiClient();
        private final MinecraftInstallService installService = new MinecraftInstallService();
        private final MinecraftLaunchService launchService = new MinecraftLaunchService();
        private final PackService packService = new PackService();
        private final MinecraftSkinService skinService = new MinecraftSkinService();
        private final LauncherUpdateService launcherUpdateService = new LauncherUpdateService();
        private final PlanetSurvey survey=new PlanetSurvey();
        /** HOME's hologram turns on its own; the MAP page's globe is the player's to turn. */
        private final PlanetGlobe homeGlobe=new PlanetGlobe(PlanetGlobe.Style.HOLO),mapGlobe=new PlanetGlobe(PlanetGlobe.Style.SURVEY);
        private final Rectangle homeGlobeBounds=new Rectangle(),mapViewBounds=new Rectangle(),surveyWorldBounds=new Rectangle(),mapCentreBounds=new Rectangle(),mapZoomInBounds=new Rectangle(),mapZoomOutBounds=new Rectangle();
        private final Rectangle[] waypointBounds=new Rectangle[12];
        private Point globeDragAt;private int hoverWaypoint=-1,waypointScroll;private String mapCentredOn="";private double mapHoverSince;
        
        private final List<ChatLine> chatLines = new ArrayList<>();
        private final List<String> onlinePlayers = new ArrayList<>();
        private final List<String> packLog = new ArrayList<>();
        private final List<String> launchTrace = new ArrayList<>();
        private final List<ErdvynApiClient.NewsPost> newsPosts = new ArrayList<>();
        private final List<ErdvynApiClient.AdminAccount> adminAccounts = new ArrayList<>();
        private final Map<String,BufferedImage> adminHeads = new HashMap<>();
        private final List<String> notifications = new ArrayList<>();
        /** Eased 0..1 hover per control, keyed by the control's own bounds; [1] is the paint frame that last drew it. */
        private final IdentityHashMap<Rectangle,double[]> hoverAnim = new IdentityHashMap<>(), switchAnim = new IdentityHashMap<>();
        private GameOptions gameOptions;
        private volatile MicrosoftAccountService.Session accountSession; // written by login/launch worker threads
        private MinecraftServerStatus.Snapshot serverSnapshot = MinecraftServerStatus.Snapshot.offline();
        private ErdvynApiClient.Status apiStatus = ErdvynApiClient.Status.offline();
        private Page page = Page.HOME, previousPage = Page.HOME;
        private Retro.Theme chrome = Retro.HOME;
        private int hoverNews = -1, selectedNews = -1, resizeMask, newsScroll,newsFirstVisible,newsVisibleRows=1,seenNotifications,articleScroll,articleMaxScroll,gameExitCode,lastBootBlock,loginGeneration;
        private long paintFrame;
        private boolean gameCrashed,pointerInside;
        private AtomicBoolean packCancel=new AtomicBoolean(); // a fresh token per verify run, so a late click never cancels the next one
        private long packBytesDone,packBytesTotal;private double packBytesRate;
        private String launchAdvice="",launchNotice="";
        private boolean profileOpen, autoUpdate = true, autoConnect = true;
        // Destructive admin buttons need a second click within a few seconds.
        private String pendingConfirm="";private double pendingConfirmUntil;
        private boolean volumeDragging, packVerifying, chatFocused,bootActive,bootCompleteSound,launcherReady=true;
        private boolean accountLoginInProgress,launchAfterLogin,gameLaunching,launchOverlayActive,launchFailed,newsComposeOpen,newsPublishInProgress,notificationsOpen,packInstalled;
        private volatile boolean gameReady; // render handoff done: from here the launch can no longer be cancelled
        private volatile Thread loginThread,launchThread;private volatile Process launchingGame;
        private String deviceCode="",deviceUrl="";
        private final AtomicBoolean accountRefreshInProgress=new AtomicBoolean(),backendPollInProgress=new AtomicBoolean(),launcherUpdateCheckInProgress=new AtomicBoolean();
        private boolean draggingWindow, resizingWindow;
        private Point windowActionStart;
        private Rectangle windowStartBounds;
        private BufferedImage playerHead;
        private LauncherUpdateService.Update launcherUpdate;
        private Path launcherInstaller;
        private final long startNanos=System.nanoTime();private long lastTickNanos;
        private double time, dt=1/60.0, pageTransition = 1, opening, sidebarExpand, volumeReveal,pressDepth,launchDisplayedProgress,launchTargetProgress,homeGlobeOnAt=-10;
        private double bootStartedAt,powerOff=-1,articleOpenedAt,pageSwitchedAt=-10;
        private String pressedControl="";
        private double packProgress;
        private String packStatus = "";
        private String launchStatus = "";
        private long launchStartedAtMillis,launchPid;
        private int launchLastPackBucket=-1;
        private PackService.Summary packSummary = PackService.Summary.empty();
        private String chatDraft = "";
        private String newsTitleDraft = "",newsBodyDraft = "",newsNotice = "";
        private int newsField;
        private long lastBackendPollMillis;
        private long lastNewsFetchMillis;
        private long lastAdminFetchMillis;
        private long lastLauncherUpdateCheckMillis;
        private String lastLauncherUpdateError="";
        private String accountNotice = "";
        private String adminTargetDraft="",adminCommandDraft="",adminNotice="";
        private int adminField;
        private boolean adminActionInProgress;
        private Point mouse = new Point();
        private String wrapKey="";private List<String> wrapLines=List.of(); // the open article's wrapped body, re-wrapped only when it or the width changes
        private String previewKey="";private List<String> previewLines=List.of();

        LauncherCanvas(LauncherFrame frame) {
            this.frame = frame;
            setOpaque(true);
            PlanetGlobe.preload();
            for(int i=0;i<waypointBounds.length;i++)waypointBounds[i]=new Rectangle();
            sound.setVolume(preferences.getBoolean("uiMuted",false)?0:preferences.getDouble("uiVolume",.8));
            setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            setFocusable(true);setFocusTraversalKeysEnabled(false); // Tab must reach keyPressed to switch admin/news fields
            autoUpdate=preferences.getBoolean("autoUpdate",true);autoConnect=preferences.getBoolean("autoConnect",false); // PLAY opens the main menu unless the player turns AUTO CONNECT on
            try { LauncherPaths.prepareInstance(); } catch (Exception ignored) {}
            gameOptions = new GameOptions(LauncherPaths.gameDirectory());
            accountSession = accountService.cached();
            packInstalled = packService.isInstalled();
            refreshPackSummary();
            installStrings();
            for(int i=0;i<navBounds.length;i++)navBounds[i]=new Rectangle();
            for(int i=0;i<newsBounds.length;i++)newsBounds[i]=new Rectangle();
            addMouseListener(this);
            addMouseMotionListener(this);
            addMouseWheelListener(this);
            addKeyListener(this);
            chatLines.add(ChatLine.system("SYSTEM", "Waiting for chat connection.", LocalTime.now()));
            if(!packInstalled)notify("Modpack is ready to install. It will install automatically on first PLAY.");
            if(!uiTest())hub.connect();
            if(!uiTest())serverStatus.start();
            if(!uiTest()&&accountSession!=null)authenticateCachedAccount();
            if(!uiTest()&&autoUpdate)checkLauncherUpdateAsync();
            timer.start();
        }

        private void installStrings() {
            put("home", "HOME"); put("news", "NEWS"); put("pack", "MODPACK"); put("gamePanel", "PLAYERS"); put("worldMap", "WORLD MAP"); put("settings", "SETTINGS"); put("admin", "ADMIN");
            put("play", "PLAY"); put("newsTitle", "NEWS"); put("packTitle", "MODPACK");
            put("repair", "VERIFY FILES"); put("openFolder", "OPEN FOLDER");
            put("send", "SEND"); put("writeMessage", "Write a message...");
            put("microsoftLogin", "SIGN IN WITH MICROSOFT"); put("erdvynLogin", "ERDVYN ACCOUNT");
        }

        private void put(String key, String en) { strings.put(key, en); }
        private String t(String key) { return strings.getOrDefault(key, key); }
        private String localized(String value){return value;}
        private String upper(String value){return value.toUpperCase(Locale.ENGLISH);}
        private void refreshPackSummary(){Thread.startVirtualThread(()->{try{PackService.Summary summary=packService.summary();SwingUtilities.invokeLater(()->{packSummary=summary;repaint();});}catch(Exception ignored){}});}
        private static String formatBytes(long bytes){if(bytes<=0)return "--";double value=bytes;String unit="B";if(value>=1024){value/=1024;unit="KB";}if(value>=1024){value/=1024;unit="MB";}if(value>=1024){value/=1024;unit="GB";}return String.format(Locale.ROOT,value>=100?"%.0f %s":"%.1f %s",value,unit);}
        /** Notifications and chat are bounded: a launcher left open for days must not grow without limit. */
        private void notify(String message){notifications.add(message);while(notifications.size()>40){notifications.remove(0);seenNotifications=Math.max(0,seenNotifications-1);}}
        private void addChat(ChatLine line){chatLines.add(line);while(chatLines.size()>200)chatLines.remove(0);}
        private void addPackLog(String line){packLog.add(line);while(packLog.size()>80)packLog.remove(0);}

        private static Retro.Theme theme(Page page){
            return switch(page){case HOME->Retro.HOME;case NEWS->Retro.NEWS;case PACK->Retro.PACK;case GAME->Retro.GAME;case MAP->Retro.MAP;case SETTINGS->Retro.SETTINGS;case ADMIN->Retro.ADMIN;};
        }

        // ================================================================ frame

        @Override protected void paintComponent(Graphics base) {
            super.paintComponent(base);
            paintFrame++;
            Graphics2D g = (Graphics2D) base.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            int w=getWidth(),h=getHeight();
            chrome=theme(previousPage).blend(theme(page),Retro.easeInOut(pageTransition));
            paintWorld(g,w,h);
            Composite plain=g.getComposite();
            if(opening<1)g.setComposite(AlphaComposite.SrcOver.derive((float)Math.max(.02,Retro.easeOut(opening))));
            paintHeader(g,w);
            switch (page) {
                case HOME -> paintHome(g);
                case NEWS -> paintNews(g);
                case PACK -> paintPack(g);
                case GAME -> paintGamePanel(g);
                case MAP -> paintWorldMap(g);
                case SETTINGS -> paintSettings(g);
                case ADMIN -> paintAdmin(g);
            }
            // Changing page is changing channel: a burst of static, then the channel number on screen.
            paintFooter(g,w,h);
            paintSidebar(g,h);
            g.setComposite(plain);
            if (selectedNews >= 0) paintArticleOverlay(g);
            if (newsComposeOpen) paintNewsComposer(g);
            if (notificationsOpen) paintNotifications(g);
            if (profileOpen) paintProfileMenu(g);
            if (launchOverlayActive) {paintLaunchOverlay(g);paintWindowButtons(g,w,launchFailed||gameCrashed?Retro.HALT:Retro.LAUNCH);}
            if (bootActive) paintBootOverlay(g);
            paintCrt(g,w,h);
            if(powerOff>=0)Retro.tube(g,w,h,1-Retro.clamp01((time-powerOff)/.32));
            g.dispose();
        }

        private void paintWorld(Graphics2D g,int w,int h) {
            g.setColor(Retro.INK);g.fillRect(0,0,w,h);
            double t=Retro.easeInOut(pageTransition);
            if(t<1)Retro.ambient(g,w,h,theme(previousPage).main,1-t);
            Retro.ambient(g,w,h,theme(page).main,t);
        }

        private String pageLabel(Page p){return t(switch(p){case HOME->"home";case NEWS->"news";case PACK->"pack";case GAME->"gamePanel";case MAP->"worldMap";case SETTINGS->"settings";case ADMIN->"admin";});}

        /**
         * The status strip under every page: the server link on the left, a news crawl in the middle (latest dispatches,
         * or the pack facts while there are none) and the clock on the right.
         */
        private void paintFooter(Graphics2D g,int w,int h){
            Retro.Theme t=chrome;int y=h-FOOTER,x=SIDEBAR,base=y+FOOTER/2+6;
            g.setColor(new Color(3,5,8,244));g.fillRect(x,y,w-x,FOOTER);g.setColor(t.line);g.drawLine(x,y,w,y);
            boolean online=serverSnapshot.online();int lx=x+18;
            Retro.led(g,lx+3,base-5,online?Retro.GREEN:Retro.RED,true,time,!online);lx+=14;
            String address=LauncherPaths.serverAddress().toUpperCase(Locale.ROOT);Retro.plain(g,address,F16,lx,base,QUIET);lx+=Retro.width(F16,address)+16;
            String ping=online?String.format(Locale.ROOT,"%03d MS",Math.min(999,serverSnapshot.latencyMs())):"--- MS";Retro.plain(g,ping,F16,lx,base,online?t.data:STEEL);lx+=Retro.width(F16,ping)+16;
            String players=online?String.format(Locale.ROOT,"%02d/%d",serverSnapshot.players(),serverSnapshot.maxPlayers()):"--/--";Retro.plain(g,players,F16,lx,base,online?t.data:STEEL);lx+=Retro.width(F16,players)+22;
            String clock=LocalTime.now().format(HH_MM_SS);int cw=Retro.segWidth(clock,16),cx=w-24-cw;Retro.seg7(g,clock,cx,y+9,16,t.main,time);
            String date=java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));int dx=cx-16-Retro.width(F16,date);Retro.plain(g,date,F16,dx,base,STEEL);
            int tx0=lx,tx1=dx-22;
            // The newest headlines run slowly through the strip; with no news there is nothing to run, so it stays still.
            if(tx1-tx0>160&&!newsPosts.isEmpty()){
                int clipX=tx0,clipW=tx1-clipX;String crawl=tickerText();int crawlW=Retro.width(F16,crawl);
                Shape clip=g.getClip();g.clipRect(clipX,y+1,clipW,FOOTER-1);
                int offset=(int)((time*30)%Math.max(1,crawlW));for(int cx2=clipX-offset;cx2<clipX+clipW;cx2+=crawlW)Retro.plain(g,crawl,F16,cx2,base,QUIET);
                g.setClip(clip);
            }
        }
        private String tickerText(){
            StringBuilder text=new StringBuilder();
            for(int i=0;i<Math.min(5,newsPosts.size());i++){ErdvynApiClient.NewsPost post=newsPosts.get(i);text.append(formatNewsDate(post.publishedAt())).append("  ").append(post.title().replaceAll("\\s+"," ")).append("        ");}
            return text.toString();
        }

        private void paintCrt(Graphics2D g,int w,int h){Retro.crt(g,w,h);}

        // ================================================================ chrome

        private void paintHeader(Graphics2D g,int w){
            Retro.Theme t=chrome;
            if(!notificationsOpen)updateBounds.setBounds(0,0,0,0);
            g.setColor(Retro.alpha(Retro.INK,214));g.fillRect(0,0,w,HEADER);
            g.setColor(t.line);g.drawLine(0,HEADER-1,w,HEADER-1);
            int hx=SIDEBAR+18;boolean online=serverSnapshot.online();
            Retro.led(g,hx+3,35,online?Retro.GREEN:Retro.RED,true,time,false);
            Retro.plain(g,online?"SERVER ONLINE":"SERVER UNREACHABLE",F16,hx+14,40,online?QUIET:Retro.RED);

            int profileW=242,profileX=w-370;profileBounds.setBounds(profileX,12,profileW,44);double hp=hover(profileBounds);
            g.setColor(Retro.alpha(t.main,(int)(10+34*hp)));g.fillRect(profileX,12,profileW,44);g.setColor(hp>.5||profileOpen?t.hot:t.line);g.drawRect(profileX,12,profileW,44);
            if(playerHead!=null)g.drawImage(playerHead,profileX+6,17,34,34,null);else{g.setColor(t.faint);g.fillRect(profileX+6,17,34,34);Retro.centered(g,"?",F16,profileX+23,39,t.main,0);}
            g.setColor(t.main);g.drawRect(profileX+5,16,35,35);
            int valueX=profileX+49;
            Retro.plain(g,Retro.fit(F16,accountSession==null?"Not signed in":accountSession.name(),profileX+profileW-8-valueX),F16,valueX,30,Retro.PAPER);
            String auth=accountLoginInProgress?"waiting for the browser":accountSession==null?"click to sign in":"Microsoft";Color authColor=accountLoginInProgress?Retro.YELLOW:accountSession==null?t.main:STEEL;
            Retro.plain(g,Retro.fit(F16,auth,profileX+profileW-8-valueX),F16,valueX,48,authColor);

            notificationBounds.setBounds(profileX-50,12,38,44);double hb=hover(notificationBounds);
            g.setColor(Retro.alpha(t.main,(int)(10+34*hb)));g.fillRect(notificationBounds.x,12,38,44);g.setColor(hb>.5||notificationsOpen?t.hot:t.line);g.drawRect(notificationBounds.x,12,38,44);
            paintBellIcon(g,notificationBounds.x+19,33,notificationsOpen||hb>.5?t.hot:t.main);
            if(notifications.size()>seenNotifications&&((int)(time*2.2)&1)==0){g.setColor(Retro.RED);g.fillRect(notificationBounds.x+26,17,7,7);g.setColor(Retro.alpha(Color.WHITE,200));g.fillRect(notificationBounds.x+27,18,2,2);}



            paintWindowButtons(g,w,t);
        }

        /** Minimize and close; repainted above the launch overlay so a long download can still be sent to the taskbar. */
        private void paintWindowButtons(Graphics2D g,int w,Retro.Theme t){
            minimizeBounds.setBounds(w-108,15,38,40);closeBounds.setBounds(w-56,15,38,40);
            double hm=hover(minimizeBounds),hc=hover(closeBounds);
            if(hm>.01){g.setColor(Retro.alpha(t.main,(int)(40*hm)));g.fillRect(minimizeBounds.x,15,38,40);}
            if(hc>.01){g.setColor(Retro.alpha(Retro.RED,(int)(150*hc)));g.fillRect(closeBounds.x,15,(int)(38*Retro.easeOut(hc)),40);}
            g.setColor(Retro.mix(QUIET,Retro.PAPER,hm));g.fillRect(w-99,35,12,2);
            g.setColor(Retro.mix(QUIET,Color.WHITE,hc));for(int i=0;i<11;i++){g.fillRect(w-44+i,29+i,2,2);g.fillRect(w-34-i,29+i,2,2);}
        }

        /** Painted after the page so the widened sidebar overlays content instead of sliding it away from the cursor. */
        private void paintSidebar(Graphics2D g,int h){
            int expanded=(int)(SIDEBAR+RAIL_EXPAND*sidebarExpand);
            g.setColor(new Color(3,5,8,250));g.fillRect(0,0,expanded,h);g.setColor(chrome.line);g.drawLine(expanded-1,0,expanded-1,h);g.drawLine(0,HEADER-1,expanded,HEADER-1);
            ErdvynMark.paint(g,(SIDEBAR-64)/2,3,64,time);
            boolean adminVisible=apiClient.current()!=null&&apiClient.current().account().admin();
            Shape clip=g.getClip();g.clipRect(0,0,expanded,h);
            // Each page keeps its own phosphor (F1-F7 or 1-7 switch to it).
            for (int i = 0; i < navBounds.length; i++) {
                if(i==6&&!adminVisible){navBounds[i].setBounds(0,0,0,0);continue;}
                int y=122+i*66;navBounds[i].setBounds(0,y-26,expanded,56);
                Retro.Theme it=theme(Page.values()[i]);double hv=hover(navBounds[i]);boolean active=page.ordinal()==i;
                int press=pressedControl.equals("nav"+i)?(int)Math.round(2*pressDepth):0;
                if(hv>.01&&!active){g.setColor(Retro.alpha(it.main,(int)(20*hv)));g.fillRect(6,y-24,expanded-12,52);}
                if(active){g.setColor(Retro.alpha(it.main,34));g.fillRect(6,y-24,expanded-12,52);}
                paintNavIcon(g,i,SIDEBAR/2,y+press,active?it.hot:Retro.mix(STEEL,it.main,hv));
                if(sidebarExpand>.08){
                    Composite old=g.getComposite();g.setComposite(AlphaComposite.SrcOver.derive((float)Retro.clamp01(sidebarExpand)));
                    Retro.plain(g,upper(pageLabel(Page.values()[i])),F16,84,y+5+press,active?it.hot:Retro.mix(QUIET,it.main,hv));
                    Retro.right(g,"F"+(i+1),F16,expanded-20,y+5+press,STEEL,0);
                    g.setComposite(old);
                }
            }
            g.setClip(clip);
            audioBounds.setBounds((SIDEBAR-38)/2,h-62,38,42);double ha=hover(audioBounds);
            int sliderLength=(int)(120*volumeReveal);
            if(sliderLength>5){volumeBounds.setBounds(61,h-55,sliderLength+8,28);g.setColor(chrome.line);g.drawRect(61,h-55,sliderLength+6,22);Retro.blocks(g,65,h-50,sliderLength-2,12,Math.max(3,sliderLength/9),sound.volume(),chrome.main);}else volumeBounds.setBounds(0,0,0,0);
            paintSpeakerIcon(g,audioBounds.x+19,audioBounds.y+21,sound.volume()<=.001,ha>.5?chrome.hot:QUIET,ha>.5);
            String version=(sidebarExpand>.45?"LAUNCHER ":"v")+LauncherUpdateService.CURRENT_VERSION;
            Retro.plain(g,version,F16,sidebarExpand>.45?14:Math.max(4,(SIDEBAR-Retro.width(F16,version))/2),h-74,STEEL);
        }

        // ================================================================ shared widgets

        /** Eased hover of a control (0..1). Painting a control registers it; only controls drawn in the last frame can be hot. */
        private double hover(Rectangle r){double[] v=hoverAnim.computeIfAbsent(r,k->new double[2]);v[1]=paintFrame;return v[0];}
        private boolean hot(Rectangle r){double[] v=hoverAnim.get(r);return v!=null&&v[1]>=paintFrame-1&&!r.isEmpty()&&pointerInside&&r.contains(mouse)&&interactive(r)&&!(overSidebar(mouse)&&r.x>=SIDEBAR&&!overlayOpen());}
        /** Eased switch position for a toggle, so ON/OFF slides instead of jumping. */
        private double switchPosition(Rectangle r,boolean on){double[] v=switchAnim.computeIfAbsent(r,k->new double[]{on?1:0,on?1:0});v[1]=on?1:0;return v[0];}
        private boolean overlayOpen(){return launchOverlayActive||bootActive||selectedNews>=0||newsComposeOpen||notificationsOpen||profileOpen;}
        /** With an overlay open only its own controls (and the header buttons that toggle it) respond. */
        private boolean interactive(Rectangle r){
            if(bootActive)return false;
            if(launchOverlayActive)return r==launchDismissBounds||r==launchLogsBounds||r==launchCancelBounds||r==crashReportsBounds||r==crashPlayBounds||r==minimizeBounds||r==closeBounds;
            boolean header=r==closeBounds||r==minimizeBounds||r==profileBounds||r==notificationBounds;
            if(selectedNews>=0)return r==articleCloseBounds||r==articleDeleteBounds;
            if(newsComposeOpen)return header||r==newsTitleInputBounds||r==newsBodyInputBounds||r==newsPublishBounds||r==newsCancelBounds;
            if(notificationsOpen)return header||r==notificationClearBounds||r==updateBounds;
            if(profileOpen)return header||r==microsoftBounds||r==erdvynAccountBounds||r==cancelLoginBounds;
            return true;
        }
        private void button(Graphics2D g,Rectangle r,String label,Retro.Theme t){Retro.button(g,r,upper(label),t,hover(r),true);}
        private double reveal(double index){return Retro.clamp01((pageTransition*1.6-index*.1)/.62);}

        private void sectionTitle(Graphics2D g,Retro.Theme t,int x,int y,String title){
            double r=reveal(0);
            Retro.text(g,upper(title),F32,x,y+42,t.hot,.5);
            int end=getWidth()-30,ruleY=y+58,ruleW=(int)((end-x)*Retro.easeOut(r));
            g.setColor(t.line);g.fillRect(x,ruleY,ruleW,1);
        }

        private void paintTerminalInput(Graphics2D g,Rectangle bounds,boolean focused,String value,String placeholder,Retro.Theme t){
            double hv=hover(bounds);
            g.setColor(Retro.alpha(Retro.INK,230));g.fillRect(bounds.x,bounds.y,bounds.width,bounds.height);
            g.setColor(focused?t.main:hv>.5?t.hot:t.line);g.drawRect(bounds.x,bounds.y,bounds.width,bounds.height);
            if(focused){g.setColor(t.faint);g.fillRect(bounds.x+1,bounds.y+1,4,bounds.height-1);}
            String shown=value.isBlank()&&!focused?placeholder:value;
            while(shown.length()>1&&Retro.width(F16,"> "+shown)>bounds.width-36)shown=shown.substring(1);
            int baseline=bounds.y+bounds.height/2+5;
            Retro.plain(g,"> "+shown,F16,bounds.x+12,baseline,value.isBlank()&&!focused?STEEL:Retro.PAPER);
            if(focused&&((int)(time*2.2)&1)==0){int cx=bounds.x+12+Retro.width(F16,"> "+(value.isBlank()?"":shown));g.setColor(t.main);g.fillRect(cx+1,baseline-12,8,14);}
        }

        /** A labelled LED readout: caption, seven-segment value and a small unit. */
        private void readout(Graphics2D g,int x,int y,String label,String digits,String unit,int segH,Retro.Theme t){
            Retro.plain(g,label,F16,x,y+12,QUIET);
            int sw=Retro.seg7(g,digits,x,y+22,segH,t.data,time);
            if(!unit.isBlank())Retro.plain(g,unit,F16,x+sw+7,y+22+segH,t.dataMuted);
        }

        // ================================================================ HOME

        /**
         * HOME is a broadcast desk: brand and system profile on the left, the planet monitor filling the right, and a
         * lower third across the bottom with PLAY (bottom left, where launchers put it) beside the live server readouts.
         */
        private void paintHome(Graphics2D g) {
            Retro.Theme t=Retro.HOME;int w=getWidth(),h=getHeight(),x=contentLeft(),top=100,leftW=homeLeftW(w),band=lowerThirdH(h),bandY=contentBottom()-band,upperBottom=bandY-18;Rectangle feed=monitorBounds(w,h);
            double r0=reveal(0);
            // the logo: the mark beside the name (the mark's square keeps the planet in its middle 0.64)
            ErdvynMark.paint(g,x-6,top-14,76,time);
            int wx=x+80,cap=30,pad=Math.max(2,cap/10);
            ErdvynMark.paintWordmark(g,wx-pad,top+6-pad,cap);
            Retro.plain(g,"THE FRONTIER, ACT ONE",F16,wx+2,top+80,t.data);
            g.setColor(t.line);g.fillRect(x,top+96,(int)(leftW*Retro.easeOut(r0)),1);
            int y=top+128,rowH=Math.max(19,Math.min(24,(upperBottom-y-24-34-26)/8));

            Retro.plain(g,"INSTALLATION",F16,x,y,t.main);
            String packData=packSummary.files()==0?"counting":String.format(Locale.ROOT,"%d mods, %d files, %s",packSummary.mods(),packSummary.files(),formatBytes(packSummary.bytes()));
            String audit=packVerifying?"checking files":packStatus.isBlank()?(packInstalled?"installed, not checked yet":"not installed"):"files match the server";
            String[][] rows={{"Minecraft",LauncherPaths.GAME_VERSION},{"NeoForge","21.1.243"},{"Pack",packSummary.version()},{"",packData},{"",audit}};
            int valueX=x+Math.min(130,leftW/3);
            for(int i=0;i<rows.length;i++){
                int ry=y+24+i*rowH;
                Retro.plain(g,rows[i][0],F16,x,ry,t.muted);
                Retro.plain(g,Retro.fit(F16,rows[i][1],x+leftW-valueX),F16,valueX,ry,Retro.PAPER);
            }
            y+=24+rows.length*rowH+18;

            // The newest posts, a click away; nothing here is invented to fill the column.
            for(Rectangle b:homeNewsBounds)b.setBounds(0,0,0,0);
            int newsFit=Math.min(homeNewsBounds.length,Math.min(newsPosts.size(),(upperBottom-30-(y+16))/(rowH+4)));
            if(newsFit>0){
                Retro.plain(g,"NEWS",F16,x,y,t.main);
                for(int i=0;i<newsFit;i++){
                    ErdvynApiClient.NewsPost post=newsPosts.get(i);int ry=y+24+i*(rowH+4);
                    homeNewsBounds[i].setBounds(x-6,ry-16,leftW+12,rowH+2);double hv=hover(homeNewsBounds[i]);
                    if(hv>.01){g.setColor(Retro.alpha(t.main,(int)(26*hv)));g.fillRect(x-6,ry-16,leftW+12,rowH+2);}
                    String date=formatNewsDate(post.publishedAt());
                    Retro.plain(g,date,F16,x,ry,t.muted);
                    Retro.plain(g,Retro.fit(F16,post.title(),x+leftW-valueX),F16,valueX,ry,hv>.5?t.hot:Retro.PAPER);
                }
                y+=24+newsFit*(rowH+4);
            }
            if(y+26<=upperBottom){
                instancePathBounds.setBounds(x,upperBottom-24,leftW,24);double hp=hover(instancePathBounds);
                if(hp>.01){g.setColor(Retro.alpha(t.main,(int)(30*hp)));g.fillRect(x,upperBottom-24,leftW,24);}
                String label="PATH: ";String path=LauncherPaths.managedInstance().toAbsolutePath().normalize().toString();int max=leftW-Retro.width(F16,label)-24;
                while(path.length()>8&&Retro.width(F16,path)>max)path="..."+path.substring(4);
                Retro.plain(g,label,F16,x+6,upperBottom-7,STEEL);Retro.plain(g,path,F16,x+6+Retro.width(F16,label),upperBottom-7,hp>.5?t.hot:QUIET);
            }else instancePathBounds.setBounds(0,0,0,0);
            paintGlobeMonitor(g,feed,t);
            paintLowerThird(g,t,x,bandY,w-28-x,band);
        }
        private void paintPlayButton(Graphics2D g,Retro.Theme t){
            Rectangle r=playBounds;double hv=hover(r);int press=pressedControl.equals("play")?(int)Math.round(2*pressDepth):0;boolean busy=gameLaunching;
            Color fill=busy?t.dataMuted:Retro.mix(t.data,Color.WHITE,.12*hv);
            // A lit key with a darker lip under it, pressed down by the click.
            g.setColor(Retro.mix(t.data,Retro.INK,.6));g.fillRect(r.x,r.y+3,r.width,r.height);
            g.setColor(fill);g.fillRect(r.x,r.y+press,r.width,r.height-(3-press));
            String label=upper(busy?"STARTING":accountSession==null?"SIGN IN & PLAY":t("play"));
            Font f=Retro.width(F32,label)<=r.width-90?F32:F16;int tw=Retro.width(f,label),plateW=tw+64,plateX=r.x+(r.width-plateW)/2,plateY=r.y+press+9,plateH=r.height-18;
            int tx=plateX+44,ty=plateY+plateH/2+(f==F32?11:6);
            Color ink=Retro.INK;
            g.setColor(ink);int ay=plateY+plateH/2;for(int i=0;i<8;i++)g.fillRect(plateX+18+i,ay-8+i,1,(8-i)*2);
            Retro.plain(g,label,f,tx,ty,ink);
        }

        /**
         * The HOME monitor: the planet as a two-phosphor hologram turning once every ninety seconds. The ground the
         * player has surveyed glows orange over the cyan planet, with their waypoints and the orbital stations; the
         * picture scans in when the channel comes up. A click opens the same view on the MAP channel.
         */
        private void paintGlobeMonitor(Graphics2D g,Rectangle m,Retro.Theme t){
            int left=m.x,top=m.y,right=m.x+m.width,bottom=m.y+m.height;double r=reveal(2),on=Retro.clamp01((time-homeGlobeOnAt)/.9);
            PlanetSurvey.Snapshot s=survey.current();
            g.setColor(new Color(3,9,12));g.fillRect(left,top,m.width,m.height);
            double radius=Math.min(m.width*.39,m.height*.42),cx=m.width*.5,cy=m.height*.52;int gx=left+(int)cx,gy=top+(int)cy;
            homeGlobeBounds.setBounds(gx-(int)radius,gy-(int)radius,(int)(2*radius),(int)(2*radius));double hv=hover(homeGlobeBounds);
            Shape clip=g.getClip();g.clipRect(left,top,m.width,(int)Math.ceil(m.height*Retro.easeOut(on)));
            homeGlobe.paint(g,left,top,m.width,m.height,cx,cy,radius*(.9+.1*Retro.easeOut(on)),s);
            homeGlobe.paintGrid(g,Retro.alpha(t.main,(int)(40+30*hv)));homeGlobe.paintRim(g,Retro.mix(t.main,Color.WHITE,.3*hv));
            double[] p=new double[3];
            for(PlanetSurvey.Waypoint w:s.waypoints())if(homeGlobe.project(w.x(),w.z(),p)&&p[2]>.08){
                int wx=(int)Math.round(p[0]),wy=(int)Math.round(p[1]);boolean blink=((int)(time*2.5+w.x())&1)==0;
                g.setColor(w.death()?Retro.RED:blink?Retro.PAPER:t.data);g.fillRect(wx-1,wy-1,3,3);
            }
            paintStations(g,homeGlobe,t,right);
            g.setClip(clip);
            if(on<1){int scan=top+(int)(m.height*Retro.easeOut(on));g.setColor(Retro.alpha(Color.WHITE,170));g.fillRect(left,scan,m.width,2);g.setColor(Retro.alpha(t.main,60));g.fillRect(left,scan+2,m.width,6);}
            Retro.frame(g,left-1,top-1,m.width+2,m.height+2,t,r,null);
            Retro.plain(g,"ERDVYN",F16,left+14,top+26,Retro.PAPER);
            boolean surveyed=!s.empty();
            Retro.plain(g,surveyed?"orange: ground you have walked":"your map fills in as you travel",F16,left+14,top+46,QUIET);
            Retro.right(g,"SECTOR",F16,right-14,top+26,QUIET,0);
            String sector=PlanetGlobe.sector(homeGlobe.yaw,PlanetSurvey.MIN+(homeGlobe.pitch/Math.PI+.5)*PlanetSurvey.SPAN).replace('B','b').replace('D','d');
            Retro.seg7(g,sector,right-14-Retro.segWidth(sector,22),top+34,22,t.data,time);
            if(m.height>=200){
                Retro.plain(g,"SURVEYED",F16,left+14,bottom-42,QUIET);
                String pct=String.format(Locale.ROOT,"%.2f",s.fraction()*100);int pw=Retro.seg7(g,pct,left+14,bottom-32,18,t.data,time);Retro.plain(g,"%",F16,left+20+pw,bottom-14,t.dataMuted);
                String marks=String.format(Locale.ROOT,"WAYPOINTS %02d",Math.min(99,s.waypoints().size()));
                Retro.right(g,hv>.5?"OPEN MAP":marks,F16,right-14,bottom-14,hv>.5?t.hot:QUIET,0);
            }
        }
        /**
         * Erdvyn World's orbital stations where they really hang: each keeps station over its own ground (the orbit
         * level sits over the same x/z), so it turns with the planet, at its true height, with a thread down to that
         * ground and its name beside it. The globe hides the ones behind it until they clear the limb.
         */
        private void paintStations(Graphics2D g,PlanetGlobe globe,Retro.Theme t,int right){
            double[] p=new double[3],q=new double[3];
            for(OrbitStations.Station s:OrbitStations.current()){
                if(!globe.project(s.x(),s.z(),1+s.lift(),p))continue;
                int px=(int)Math.round(p[0]),py=(int)Math.round(p[1]);Color c=s.active()?t.data:t.dataMuted;
                if(globe.project(s.x(),s.z(),q)){g.setColor(Retro.alpha(c,70));g.drawLine((int)Math.round(q[0]),(int)Math.round(q[1]),px,py);}
                g.setColor(Retro.alpha(c,90));g.drawRect(px-5,py-5,10,10);
                g.setColor(c);g.fillRect(px-2,py-2,5,5);
                String name=upper(s.nameEn());int nw=Retro.width(F16,name);
                Retro.plain(g,name,F16,px+10+nw>right-8?px-10-nw:px+10,py+5,c); // the name turns to the left near the edge
            }
        }

        /** The lower third: PLAY with its status line on the left, then the server's own numbers. */
        private void paintLowerThird(Graphics2D g,Retro.Theme t,int x,int y,int w,int h){
            int playW=Math.min(300,Math.max(240,w/4)),playH=70;
            playBounds.setBounds(x,y+8,playW,playH);paintPlayButton(g,t);
            String bus=!launchNotice.isBlank()?localized(launchNotice):gameLaunching?"The game is running":accountSession==null?"Sign in with Microsoft first":"Enter starts it too";
            boolean ok=accountSession!=null&&launchNotice.isBlank();int sy=y+8+playH+26;
            Retro.plain(g,Retro.fit(F16,bus,playW),F16,x,sy,ok?QUIET:Retro.YELLOW);
            int px=x+playW+22,pw=w-playW-22;boolean online=serverSnapshot.online();
            Retro.panel(g,px,y,pw,h,t,reveal(2),"PLAY.ERDVYN.NET");
            if(!online){Retro.plain(g,"The server is not answering. It may be down for maintenance; check the news.",F16,px+18,y+h/2+5,t.alert);return;}
            int segH=h>=130?36:30,cell=Math.max(118,(pw-36)/4),ry=y+20;Double tps=apiStatus.tps();
            readout(g,px+18,ry,"PLAYERS",Integer.toString(Math.min(999,serverSnapshot.players())),"/"+serverSnapshot.maxPlayers(),segH,t);
            readout(g,px+18+cell,ry,"PING",Long.toString(Math.min(999,serverSnapshot.latencyMs())),"MS",segH,t);
            if(tps!=null)readout(g,px+18+cell*2,ry,"TPS",String.format(Locale.ROOT,"%.1f",Math.min(99.9,tps)),"",segH,t);
            if(apiStatus.uptimeSeconds()!=null&&px+18+cell*4<=px+pw)readout(g,px+18+cell*3,ry,"UP FOR",formatUptime(apiStatus.uptimeSeconds()),"",segH,t);
        }

        private static String formatUptime(long seconds){long hours=seconds/3600,minutes=(seconds%3600)/60,remainder=seconds%60;return String.format(Locale.ROOT,"%02d:%02d:%02d",hours,minutes,remainder);}

        // ================================================================ NEWS

        private void paintNews(Graphics2D g) {
            Retro.Theme t=Retro.NEWS;int x=contentLeft(),y=104,w=getWidth();sectionTitle(g,t,x,y,t("newsTitle"));
            for(Rectangle bounds:newsBounds)bounds.setBounds(0,0,0,0);newsComposeBounds.setBounds(0,0,0,0);
            boolean admin=apiClient.current()!=null&&apiClient.current().account().admin();if(admin){newsComposeBounds.setBounds(w-250,y+4,218,38);button(g,newsComposeBounds,"NEW POST",t);}
            int panelY=y+82,fullW=w-x-32,panelH=contentBottom()-panelY;
            // Wide windows split into the list and the hovered (or newest) post.
            boolean preview=fullW>=900&&!newsPosts.isEmpty();int panelW=preview?(int)(fullW*.58):fullW;
            if(preview)paintNewsPreview(g,t,x+panelW+14,panelY,fullW-panelW-14,panelH);
            Retro.panel(g,x,panelY,panelW,panelH,t,reveal(1),null);
            int dateX=x+20,subjectX=x+150,statusX=x+panelW,hy=panelY+34;
            Retro.plain(g,"DATE",F16,dateX,hy,t.muted);Retro.plain(g,"TITLE",F16,subjectX,hy,t.muted);
            g.setColor(t.line);g.fillRect(x+12,hy+10,panelW-24,1);
            if(newsPosts.isEmpty()){
                int cx=x+panelW/2,cy=panelY+panelH/2;
                Retro.centered(g,apiClient.configured()?"No news yet.":"News is turned off in this build.",F16,cx,cy,QUIET,0);
            } else {
                int rowH=40,first=Math.max(0,Math.min(newsPosts.size()-1,newsScroll/rowH));newsFirstVisible=first;int visible=Math.min(newsBounds.length,Math.max(0,(panelH-62)/rowH));newsVisibleRows=Math.max(1,visible);
                for(int slot=0;slot<visible&&first+slot<newsPosts.size();slot++){
                    int index=first+slot,rowY=hy+16+slot*rowH;ErdvynApiClient.NewsPost post=newsPosts.get(index);
                    newsBounds[slot].setBounds(x+10,rowY,panelW-20,rowH);double hv=hover(newsBounds[slot]);
                    if(hv>.01){g.setColor(Retro.alpha(t.main,(int)(30*hv)));g.fillRect(x+11,rowY+2,panelW-22,rowH-4);}
                    int base=rowY+rowH/2+5;
                    Retro.plain(g,formatNewsDate(post.publishedAt()),F16,dateX,base,t.muted);
                    Retro.plain(g,Retro.fit(F16,post.title(),statusX-subjectX-20),F16,subjectX,base,hv>.5?t.hot:Retro.PAPER);
                    g.setColor(Retro.alpha(t.main,24));g.drawLine(x+12,rowY+rowH,x+panelW-12,rowY+rowH);
                }
                if(newsPosts.size()>visible&&visible>0){int trackY=hy+16,trackH=visible*rowH,thumbH=Math.max(18,trackH*visible/newsPosts.size()),max=Math.max(1,newsPosts.size()-visible),thumbY=trackY+(trackH-thumbH)*Math.min(first,max)/max;g.setColor(t.faint);g.fillRect(x+panelW-9,trackY,4,trackH);g.setColor(t.main);g.fillRect(x+panelW-9,thumbY,4,thumbH);}
            }
            if(!newsNotice.isBlank())Retro.plain(g,Retro.fit(F16,localized(newsNotice),panelW-40),F16,x+20,panelY+panelH-14,t.muted);
        }

        /** The hovered (or newest) post beside the list: title, date and as much of the text as fits. */
        private void paintNewsPreview(Graphics2D g,Retro.Theme t,int x,int y,int w,int h){
            int index=hoverNews>=0&&newsFirstVisible+hoverNews<newsPosts.size()?newsFirstVisible+hoverNews:0;ErdvynApiClient.NewsPost post=newsPosts.get(index);
            double r=reveal(2);Retro.panel(g,x,y,w,h,t,r,null);if(r<1)return;
            Retro.plain(g,formatNewsDate(post.publishedAt()),F16,x+20,y+34,t.muted);
            // Authored text keeps its own case: upper-casing with the UI locale would turn an English "i" into a Turkish "İ".
            List<String> title=wrapLines(F32,post.title(),w-40);Font titleFont=F32;if(title.size()>3){titleFont=F16;title=wrapLines(F16,post.title(),w-40);}
            int ty=y+(titleFont==F32?72:58),lineH=titleFont==F32?32:20;
            for(int i=0;i<Math.min(3,title.size());i++)Retro.plain(g,title.get(i),titleFont,x+20,ty+i*lineH,Retro.PAPER);
            int ruleY=ty+Math.min(3,title.size())*lineH-lineH/2+8;g.setColor(t.line);g.fillRect(x+20,ruleY,w-40,1);
            String key=post.id()+":"+w;if(!key.equals(previewKey)){previewKey=key;previewLines=wrapLines(F16,post.body(),w-40);}
            int by=ruleY+26,maxRows=Math.max(0,(y+h-46-by)/20);
            for(int i=0;i<Math.min(maxRows,previewLines.size());i++)Retro.plain(g,previewLines.get(i),F16,x+20,by+i*20,QUIET);
            if(previewLines.size()>maxRows&&maxRows>0)Retro.plain(g,"...",F16,x+20,by+maxRows*20,QUIET);
            Retro.plain(g,"Click a post to read all of it",F16,x+20,y+h-14,STEEL);
        }

        private static String formatNewsDate(long epoch){return DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epoch));}

        private void paintArticleOverlay(Graphics2D g){
            if(selectedNews<0||selectedNews>=newsPosts.size()){selectedNews=-1;return;}ErdvynApiClient.NewsPost post=newsPosts.get(selectedNews);Retro.Theme t=Retro.NEWS;
            int w=getWidth(),h=getHeight(),boxW=Math.min(720,w-180),boxH=Math.min(470,h-130),x=(w-boxW)/2,y=(h-boxH)/2;
            g.setColor(new Color(0,0,0,206));g.fillRect(0,HEADER,w,h-HEADER);
            double open=Retro.easeOut((time-articleOpenedAt)/.25);int oh=Math.max(2,(int)(boxH*open)),oy=y+(boxH-oh)/2;
            if(open<1){g.setColor(t.panel);g.fillRect(x,oy,boxW,oh);g.setColor(t.main);g.drawRect(x,oy,boxW,oh);return;}
            Retro.panel(g,x,y,boxW,boxH,t,1,null);
            String title=post.title();Font titleFont=Retro.width(F32,title)<=boxW-90?F32:F16;
            Retro.plain(g,Retro.fit(titleFont,title,boxW-90),titleFont,x+24,y+52,t.hot);
            g.setColor(t.line);g.fillRect(x+24,y+66,boxW-48,1);
            // The body scrolls with the wheel inside its own band.
            int bodyTop=y+86,bodyBottom=y+boxH-62,lineH=21,visible=Math.max(1,(bodyBottom-bodyTop)/lineH);
            String key=post.id()+":"+boxW+":"+post.body().hashCode();if(!key.equals(wrapKey)){wrapKey=key;wrapLines=wrapLines(F16,post.body(),boxW-70);}
            List<String> lines=wrapLines;articleMaxScroll=Math.max(0,lines.size()-visible);articleScroll=Math.max(0,Math.min(articleScroll,articleMaxScroll));
            for(int i=0;i<visible&&articleScroll+i<lines.size();i++)Retro.plain(g,lines.get(articleScroll+i),F16,x+24,bodyTop+16+i*lineH,Retro.PAPER);
            if(articleMaxScroll>0){int trackX=x+boxW-24,trackH=visible*lineH,thumbH=Math.max(16,trackH*visible/lines.size()),thumbY=bodyTop+(trackH-thumbH)*articleScroll/articleMaxScroll;g.setColor(t.faint);g.fillRect(trackX,bodyTop,5,trackH);g.setColor(t.main);g.fillRect(trackX,thumbY,5,thumbH);}
            g.setColor(t.line);g.drawLine(x+12,y+boxH-48,x+boxW-12,y+boxH-48);
            Retro.plain(g,formatNewsDate(post.publishedAt()),F16,x+24,y+boxH-20,t.muted);
            if(articleMaxScroll>0){String position=String.format(Locale.ROOT,"%d-%d / %d",articleScroll+1,Math.min(lines.size(),articleScroll+visible),lines.size());Retro.right(g,position,F16,x+boxW-30,y+boxH-20,t.main,0);}
            articleCloseBounds.setBounds(x+boxW-62,y+12,44,28);double hc=hover(articleCloseBounds);
            if(hc>.01){g.setColor(Retro.alpha(t.main,(int)(200*hc)));g.fillRect(articleCloseBounds.x,articleCloseBounds.y,(int)(44*Retro.easeOut(hc)),28);}
            g.setColor(hc>.5?Retro.INK:t.main);for(int i=0;i<10;i++){g.fillRect(articleCloseBounds.x+17+i,articleCloseBounds.y+9+i,2,2);g.fillRect(articleCloseBounds.x+26-i,articleCloseBounds.y+9+i,2,2);}
            // admins can take a dispatch down; the button asks once more before it deletes
            articleDeleteBounds.setBounds(0,0,0,0);
            if(apiClient.current()!=null&&apiClient.current().account().admin()){
                boolean armed=newsDeleteArmed==post.id();
                String label=newsDeleteInProgress?"DELETING":armed?"CONFIRM DELETE":"DELETE";
                int bw=Math.max(110,Retro.width(F16,label)+40);articleDeleteBounds.setBounds(x+boxW/2-bw/2,y+boxH-42,bw,30);
                button(g,articleDeleteBounds,label,armed?Retro.HALT:t);
            }
        }

        private void paintNewsComposer(Graphics2D g){
            Retro.Theme t=Retro.NEWS;int w=getWidth(),h=getHeight(),boxW=Math.min(720,w-180),boxH=420,x=(w-boxW)/2,y=(h-boxH)/2;
            g.setColor(new Color(0,0,0,206));g.fillRect(0,HEADER,w,h-HEADER);
            Retro.panel(g,x,y,boxW,boxH,t,1,"NEW POST");
            Retro.plain(g,"TITLE",F16,x+20,y+44,t.muted);newsTitleInputBounds.setBounds(x+18,y+54,boxW-36,40);paintTerminalInput(g,newsTitleInputBounds,newsField==0,newsTitleDraft,"",t);
            Retro.plain(g,"TEXT",F16,x+20,y+124,t.muted);newsBodyInputBounds.setBounds(x+18,y+134,boxW-36,190);
            double hb=hover(newsBodyInputBounds);g.setColor(Retro.alpha(Retro.INK,230));g.fillRect(newsBodyInputBounds.x,newsBodyInputBounds.y,newsBodyInputBounds.width,newsBodyInputBounds.height);g.setColor(newsField==1?t.main:hb>.5?t.hot:t.line);g.drawRect(newsBodyInputBounds.x,newsBodyInputBounds.y,newsBodyInputBounds.width,newsBodyInputBounds.height);
            List<String> body=wrapLines(F16,newsBodyDraft.isBlank()?"Everyone who opens the launcher sees it.":newsBodyDraft+(newsField==1&&((int)(time*2)&1)==0?"_":""),boxW-64);
            for(int i=Math.max(0,body.size()-8),row=0;i<body.size();i++,row++)Retro.plain(g,body.get(i),F16,x+30,y+160+row*21,newsBodyDraft.isBlank()?STEEL:Retro.PAPER);
            newsCancelBounds.setBounds(x+18,y+boxH-60,180,40);newsPublishBounds.setBounds(x+boxW-238,y+boxH-60,220,40);button(g,newsCancelBounds,"CANCEL",t);button(g,newsPublishBounds,newsPublishInProgress?"PUBLISHING":"PUBLISH",t);
            if(!newsNotice.isBlank())Retro.plain(g,Retro.fit(F16,localized(newsNotice),boxW-460),F16,x+214,y+boxH-34,t.muted);
        }

        // ================================================================ header overlays

        private void paintProfileMenu(Graphics2D g){
            Retro.Theme t=chrome;int boxW=372,x=profileBounds.x+profileBounds.width-boxW,y=68;
            // The card grows with its wrapped notice (sign-in advice runs 2-4 lines) instead of clipping it at the border.
            List<String> noticeLines=accountNotice.isBlank()?List.of():wrapLines(F16,localized(accountNotice),boxW-36);noticeLines=noticeLines.subList(0,Math.min(4,noticeLines.size()));
            boolean waiting=accountLoginInProgress;int codeH=waiting?96:0,boxH=196+codeH+(waiting?0:0)+noticeLines.size()*19+(noticeLines.isEmpty()?0:8);
            Retro.panel(g,x,y,boxW,boxH,t,1,"ACCOUNT");
            Retro.plain(g,"PLAYER",F16,x+18,y+40,QUIET);Retro.plain(g,accountSession==null?"-":accountSession.name(),F16,x+130,y+40,Retro.PAPER);
            Retro.plain(g,"UUID",F16,x+18,y+64,QUIET);String uuid=accountSession==null?"not signed in":accountSession.uuid().toString();Retro.plain(g,Retro.fit(F16,uuid,boxW-148),F16,x+130,y+64,accountSession==null?Retro.YELLOW:Retro.PAPER);
            int by=y+84;
            if(waiting){
                g.setColor(t.faint);g.fillRect(x+14,by,boxW-28,codeH-8);g.setColor(t.line);g.drawRect(x+14,by,boxW-28,codeH-8);
                Retro.plain(g,"DEVICE CODE",F16,x+26,by+22,QUIET);
                String code=deviceCode.isBlank()?"- - - - - - - -":deviceCode;
                Retro.plain(g,code,F32,x+26,by+58,t.data);
                Retro.plain(g,Retro.fit(F16,deviceUrl.isBlank()?"asking for a code...":deviceUrl.replaceFirst("^https?://(www\\.)?",""),boxW-60),F16,x+26,by+80,t.main);
                by+=codeH;
                microsoftBounds.setBounds(x+18,by,boxW-36,36);button(g,microsoftBounds,"OPEN BROWSER AGAIN",t);
                cancelLoginBounds.setBounds(x+18,by+46,boxW-36,36);button(g,cancelLoginBounds,"CANCEL SIGN-IN",Retro.HALT);
                erdvynAccountBounds.setBounds(0,0,0,0);
            }else{
                cancelLoginBounds.setBounds(0,0,0,0);
                microsoftBounds.setBounds(x+18,by,boxW-36,36);erdvynAccountBounds.setBounds(x+18,by+46,boxW-36,36);
                button(g,microsoftBounds,accountSession!=null?"SIGN OUT":t("microsoftLogin"),accountSession!=null?Retro.HALT:t);button(g,erdvynAccountBounds,t("erdvynLogin"),t);
            }
            for(int i=0;i<noticeLines.size();i++)Retro.plain(g,noticeLines.get(i),F16,x+18,y+boxH-14-(noticeLines.size()-1-i)*19,i==0?t.hot:QUIET);
        }

        private void paintNotifications(Graphics2D g){
            Retro.Theme t=chrome;int boxW=410,x=notificationBounds.x+notificationBounds.width-boxW,y=68,rows=Math.max(1,Math.min(5,notifications.size())),updateH=launcherInstaller==null?0:54,boxH=58+rows*58+updateH;
            notificationPanelBounds.setBounds(x,y,boxW,boxH);Retro.panel(g,x,y,boxW,boxH,t,1,"NOTIFICATIONS");
            if(notifications.isEmpty())notificationClearBounds.setBounds(0,0,0,0);else{notificationClearBounds.setBounds(x+boxW-130,y+14,114,26);button(g,notificationClearBounds,"CLEAR",t);}
            if(notifications.isEmpty()){Retro.plain(g,"Nothing new.",F16,x+18,y+70,STEEL);}
            else{int first=Math.max(0,notifications.size()-5);for(int i=first;i<notifications.size();i++){int row=i-first,ry=y+50+row*58;boolean newest=i==notifications.size()-1;if(row>0){g.setColor(t.faint);g.drawLine(x+18,ry-4,x+boxW-18,ry-4);}List<String> text=wrapLines(F16,localized(notifications.get(i)),boxW-40);for(int k=0;k<Math.min(2,text.size());k++)Retro.plain(g,text.get(k),F16,x+18,ry+21+k*19,newest?Retro.PAPER:QUIET);}}
            if(launcherInstaller!=null){updateBounds.setBounds(x+14,y+boxH-46,boxW-28,34);button(g,updateBounds,"INSTALL LAUNCHER UPDATE",Retro.LAUNCH);}else updateBounds.setBounds(0,0,0,0);
        }

        // ================================================================ PACK

        private void paintPack(Graphics2D g) {
            Retro.Theme t=Retro.PACK;int x=contentLeft(),y=104,w=getWidth(),h=getHeight();sectionTitle(g,t,x,y,t("packTitle"));
            int panelY=y+82,panelW=w-x-32,panelH=contentBottom()-panelY;
            Retro.panel(g,x,panelY,panelW,panelH,t,reveal(1),null);
            // What the check found, said in words; the percentage only matters while it runs.
            int gx=x+24,gy=panelY+30;
            Retro.plain(g,packVerifying?"CHECKING FILES":"LAST CHECK",F16,gx,gy+8,QUIET);
            if(packVerifying){int sw=Retro.seg7(g,Integer.toString((int)Math.round(packProgress*100)),gx,gy+20,36,t.main,time);Retro.plain(g,"%",F16,gx+sw+8,gy+56,t.main);}
            else Retro.plain(g,packInstalled?"Every file matches the server.":"The pack is not installed yet.",F32,gx,gy+48,packInstalled?Retro.PAPER:Retro.YELLOW);
            String meter=packVerifying?downloadMeter():"";
            Retro.plain(g,Retro.fit(F16,meter.isBlank()?String.format(Locale.ROOT,"%s, %s",packSummary.version(),formatBytes(packSummary.bytes())):"downloading  "+meter,Math.max(120,panelW/2-60)),F16,gx,gy+84,t.dataMuted);
            int bx=x+Math.max(430,panelW/2+40),bw=x+panelW-24-bx;
            if(bw>=160){
                String[] names={"Mods","Config files","Resource packs","Shader"};int[] counts={packSummary.mods(),packSummary.configs(),packSummary.resourcepacks(),packSummary.shaderpacks()};
                for(int i=0;i<names.length;i++){int ry=gy+8+i*22;Retro.plain(g,names[i],F16,bx,ry,QUIET);Retro.right(g,Integer.toString(counts[i]),F16,bx+bw,ry,Retro.PAPER,0);}
            }
            int barY=panelY+148;
            if(packVerifying)Retro.bar(g,x+22,barY,panelW-44,6,packProgress,t.main);
            // Console.
            int logY=barY+30,logH=Math.max(90,panelH-(logY-panelY)-74),logW=panelW-44;
            g.setColor(Retro.alpha(Retro.INK,220));g.fillRect(x+22,logY,logW,logH);g.setColor(t.line);g.drawRect(x+22,logY,logW,logH);
            int maxLines=Math.max(3,(logH-18)/19),start=Math.max(0,packLog.size()-maxLines);
            if(packLog.isEmpty())Retro.plain(g,"No files have been checked this session.",F16,x+36,logY+24,STEEL);
            else for(int i=start;i<packLog.size();i++){String line=localized(packLog.get(i));Color c=line.contains("[FAIL]")||line.contains("[MISSING]")?t.alert:line.contains("[GET]")||line.contains("[SAVED]")?t.data:line.contains("[KEEP]")||line.contains("[QUARANTINED]")||line.contains("[REMOVED]")||line.contains("[CANCEL]")?Retro.YELLOW:line.startsWith(">")||line.contains("VERIFIED")?t.main:t.muted;Retro.plain(g,Retro.fit(F16,line,logW-30),F16,x+36,logY+24+(i-start)*19,c);}
            if(packVerifying&&((int)(time*3)&1)==0){int cy=logY+24+Math.min(maxLines,packLog.size()-start)*19;if(cy<logY+logH-4){g.setColor(t.main);g.fillRect(x+36,cy-12,8,14);}}
            int by=panelY+panelH-56,statusX;
            if(!packInstalled){installPackBounds.setBounds(x+22,by,230,38);verifyBounds.setBounds(x+266,by,230,38);folderBounds.setBounds(x+510,by,190,38);button(g,installPackBounds,packVerifying?"INSTALLING":"INSTALL MODPACK",t);statusX=x+716;}
            else{installPackBounds.setBounds(0,0,0,0);verifyBounds.setBounds(x+22,by,260,38);folderBounds.setBounds(x+296,by,210,38);statusX=x+524;}
            button(g,verifyBounds,!packVerifying?t("repair"):packCancel.get()?"CANCELLING":"CANCEL",packVerifying?Retro.HALT:t);button(g,folderBounds,t("openFolder"),t);
            if(!packStatus.isBlank())Retro.plain(g,Retro.fit(F16,localized(packStatus),Math.max(80,x+panelW-statusX-18)),F16,statusX,by+24,QUIET);
        }

        // ================================================================ SETTINGS

        private void paintSettings(Graphics2D g) {
            Retro.Theme t=Retro.SETTINGS;int x=contentLeft(),y=104,w=getWidth(),h=getHeight();sectionTitle(g,t,x,y,"SETTINGS");
            int panelY=y+82,panelW=w-x-32,panelH=contentBottom()-panelY,gap=16,leftW=(panelW-gap)/2,rightX=x+leftW+gap,rightW=panelW-leftW-gap;
            Retro.panel(g,x,panelY,leftW,panelH,t,reveal(1),"GAME");Retro.panel(g,rightX,panelY,rightW,panelH,t,reveal(1.4),"DISPLAY");
            boolean compact=panelH<500;int rowH=compact?46:52,rowGap=compact?6:10,toggleH=compact?38:44,innerTop=panelY+24;
            int max=GameOptions.MAX_RAM_GB,ly=innerTop;
            stepper(g,t,"ALLOCATED RAM",Integer.toString(gameOptions.ramGb()),"GB  "+"(8 RECOMMENDED)",x+18,ly,leftW-36,rowH,ramMinusBounds,ramPlusBounds,(gameOptions.ramGb()-2)/(double)Math.max(1,max-2));ly+=rowH+rowGap;
            stepper(g,t,"RENDER DISTANCE",Integer.toString(gameOptions.renderDistance()),"CHUNKS",x+18,ly,leftW-36,rowH,renderMinusBounds,renderPlusBounds,(gameOptions.renderDistance()-2)/62.0);ly+=rowH+rowGap;
            stepper(g,t,"SIMULATION",Integer.toString(gameOptions.simulationDistance()),"CHUNKS",x+18,ly,leftW-36,rowH,simulationMinusBounds,simulationPlusBounds,(gameOptions.simulationDistance()-2)/30.0);ly+=rowH+rowGap+2;
            settingBounds[0].setBounds(x+18,ly,leftW-36,toggleH);toggle(g,t,settingBounds[0],"AUTO UPDATE",autoUpdate);ly+=toggleH+rowGap;
            settingBounds[1].setBounds(x+18,ly,leftW-36,toggleH);toggle(g,t,settingBounds[1],"AUTO CONNECT",autoConnect);ly+=toggleH+(compact?12:22);
            String[] folderLabels={"INSTANCE","MODS","RESOURCE PACKS","SHADER PACKS"};int folderH=compact?32:36,folderGap=compact?7:10,folderW=(leftW-48)/2;
            for(int i=0;i<settingsFolderBounds.length;i++){int fy=ly+(i/2)*(folderH+folderGap),fx=x+18+(i%2)*(folderW+12);if(fy+folderH>panelY+panelH-8){settingsFolderBounds[i].setBounds(0,0,0,0);continue;}settingsFolderBounds[i].setBounds(fx,fy,folderW,folderH);button(g,settingsFolderBounds[i],folderLabels[i],t);}
            int ry=innerTop;
            settingBounds[2].setBounds(rightX+18,ry,rightW-36,toggleH);toggle(g,t,settingBounds[2],"FULLSCREEN",gameOptions.fullscreen());ry+=toggleH+rowGap;
            vsyncBounds.setBounds(rightX+18,ry,rightW-36,toggleH);toggle(g,t,vsyncBounds,"VERTICAL SYNC",gameOptions.vsync());ry+=toggleH+rowGap+2;
            stepper(g,t,"MAXIMUM FPS",Integer.toString(gameOptions.maxFps()),"FPS",rightX+18,ry,rightW-36,rowH,fpsMinusBounds,fpsPlusBounds,(gameOptions.maxFps()-30)/230.0);ry+=rowH+rowGap;
            stepper(g,t,"GUI SCALE",gameOptions.guiScale()==0?"AUto":Integer.toString(gameOptions.guiScale()),gameOptions.guiScale()==0?"":"X",rightX+18,ry,rightW-36,rowH,guiMinusBounds,guiPlusBounds,gameOptions.guiScale()/6.0);ry+=rowH+(compact?10:18);
                        g.setColor(t.line);g.drawLine(rightX+14,ry,rightX+rightW-14,ry);ry+=compact?20:28;
            int specialH=compact?32:36,specialW=(rightW-52)/2,specialY=panelY+panelH-specialH-16;
            // The memory map wins the space; the explanatory text only shows when both fit.
            if(!compact&&ry+60+56+18<specialY-10){List<String> info=wrapLines(F16,"The launcher hides while Minecraft runs, exits when the game closes normally and returns with a crash report if it crashes. Changes are written to options.txt immediately.",rightW-40);int lines=Math.min(3,info.size());for(int i=0;i<lines;i++)Retro.plain(g,info.get(i),F16,rightX+20,ry+i*19,QUIET);ry+=lines*19+18;}
            if(ry+56<specialY-10)memoryMap(g,t,rightX+20,ry+(specialY-10-ry-56)/2,rightW-40);
            optionsFileBounds.setBounds(rightX+18,specialY,specialW,specialH);configFolderBounds.setBounds(rightX+34+specialW,specialY,specialW,specialH);button(g,optionsFileBounds,"OPTIONS.TXT",t);button(g,configFolderBounds,"CONFIG",t);
        }

        /** A hardware-style stepper: caption, LED value, a block meter of where it sits in its range, and - / + keys. */
        private void stepper(Graphics2D g,Retro.Theme t,String label,String digits,String unit,int x,int y,int w,int h,Rectangle minus,Rectangle plus,double fraction){
            g.setColor(Retro.alpha(Retro.INK,200));g.fillRect(x,y,w,h);g.setColor(t.line);g.drawRect(x,y,w,h);
            Retro.plain(g,label,F16,x+12,y+18,QUIET);
            int segH=Math.max(16,Math.min(22,h-28)),sw=Retro.seg7(g,digits,x+12,y+h-segH-6,segH,t.data,time);Retro.plain(g,unit,F16,x+20+sw,y+h-6,t.dataMuted);
            int keyH=Math.max(26,h-14);minus.setBounds(x+w-88,y+(h-keyH)/2,36,keyH);plus.setBounds(x+w-44,y+(h-keyH)/2,36,keyH);
            int meterX=Math.max(x+Retro.width(F16,label)+30,x+w-250),meterW=minus.x-14-meterX;
            if(meterW>40)Retro.blocks(g,meterX,y+h/2-4,meterW,9,Math.max(6,meterW/12),fraction,t.main);
            key(g,t,minus,"-");key(g,t,plus,"+");
        }
        private void key(Graphics2D g,Retro.Theme t,Rectangle r,String label){double hv=hover(r);int press=pressedControl.equals("key"+System.identityHashCode(r))?1:0;g.setColor(Retro.mix(Retro.INK,t.main,.12+.6*hv));g.fillRect(r.x,r.y+press,r.width,r.height);g.setColor(hv>.5?t.hot:t.line);g.drawRect(r.x,r.y+press,r.width,r.height);Retro.centered(g,label,F16,r.x+r.width/2+1,r.y+press+r.height/2+5,hv>.5?Retro.INK:t.main,0);}

        /** A slide switch: the knob travels when the value flips. */
        private void toggle(Graphics2D g,Retro.Theme t,Rectangle r,String title,boolean on){
            double hv=hover(r),s=switchPosition(r,on);
            g.setColor(Retro.alpha(t.main,(int)(8+22*hv)));g.fillRect(r.x,r.y,r.width,r.height);g.setColor(hv>.5?t.hot:t.line);g.drawRect(r.x,r.y,r.width,r.height);
            Retro.plain(g,title,F16,r.x+14,r.y+r.height/2+5,Retro.PAPER);
            // The track is as wide as the longer label plus the knob, so neither language's text ever sits under the knob.
            String state=on?"ON":"OFF";Font f=F16;int knobW=24,labelW=Math.max(Retro.width(f,"ON"),Retro.width(f,"OFF"));
            int trackW=labelW+knobW+20,trackH=Math.min(24,r.height-12),tx=r.x+r.width-trackW-12,ty=r.y+(r.height-trackH)/2;
            g.setColor(Retro.mix(Retro.INK,t.main,.85*s));g.fillRect(tx,ty,trackW,trackH);g.setColor(t.main);g.drawRect(tx,ty,trackW,trackH);
            if(on)Retro.plain(g,state,f,tx+8,ty+trackH/2+5,Retro.INK);else Retro.plain(g,state,f,tx+trackW-8-Retro.width(f,state),ty+trackH/2+5,STEEL);
            int kx=tx+2+(int)Math.round((trackW-knobW-4)*s);
            g.setColor(Retro.PAPER);g.fillRect(kx,ty+2,knobW,trackH-3);g.setColor(Retro.INK);for(int i=0;i<3;i++)g.fillRect(kx+7+i*4,ty+6,1,trackH-11);
        }

        /** System RAM as a strip of blocks: Minecraft's heap, the share left for Windows, and what stays free. */
        private void memoryMap(Graphics2D g,Retro.Theme t,int x,int y,int w){
            int total=Math.max(GameOptions.PHYSICAL_GB,gameOptions.ramGb()+2),ram=gameOptions.ramGb(),system=2,cells=Math.min(32,total);double perCell=total/(double)cells;
            Retro.plain(g,"MEMORY MAP",F16,x,y,QUIET);Retro.right(g,total+" GB",F16,x+w,y,t.data,0);
            int gap=3,cell=Math.max(4,(w-gap*(cells-1))/cells);
            for(int i=0;i<cells;i++){double gb=(i+1)*perCell;Color c=gb<=ram+.01?t.main:gb<=ram+system+.01?Retro.CYAN:Retro.alpha(t.main,30);g.setColor(c);g.fillRect(x+i*(cell+gap),y+10,cell,14);}
            int ly=y+44;g.setColor(t.main);g.fillRect(x,ly-9,8,8);Retro.plain(g,"MINECRAFT "+ram+" GB",F16,x+14,ly,QUIET);
            int lx=x+Math.max(150,w/3);g.setColor(Retro.CYAN);g.fillRect(lx,ly-9,8,8);Retro.plain(g,"WINDOWS "+system+" GB",F16,lx+14,ly,QUIET);
            int fx=x+Math.max(300,w*2/3);if(fx+90<x+w){g.setColor(Retro.alpha(t.main,60));g.fillRect(fx,ly-9,8,8);Retro.plain(g,"FREE "+Math.max(0,total-ram-system)+" GB",F16,fx+14,ly,QUIET);}
        }

        /** Two-way selector (TR | EN). */
        private void segmented(Graphics2D g,Retro.Theme t,Rectangle r,boolean left){
            double hv=hover(r);int half=r.width/2;
            g.setColor(Retro.alpha(Retro.INK,220));g.fillRect(r.x,r.y,r.width,r.height);
            g.setColor(t.main);g.fillRect(left?r.x:r.x+half,r.y,r.width-half,r.height);
            g.setColor(hv>.5?t.hot:t.line);g.drawRect(r.x,r.y,r.width,r.height);
            Retro.centered(g,"TR",F16,r.x+half/2,r.y+r.height/2+5,left?Retro.INK:STEEL,0);Retro.centered(g,"EN",F16,r.x+half+half/2,r.y+r.height/2+5,left?STEEL:Retro.INK,0);
        }

        // ================================================================ ADMIN

        private void paintAdmin(Graphics2D g){
            ErdvynApiClient.Login active=apiClient.current();boolean admin=active!=null&&active.account().admin(),root=admin&&active.account().rootAdmin();Retro.Theme t=Retro.ADMIN;
            int x=contentLeft(),y=104,w=getWidth(),panelY=y+82,panelW=w-x-32,panelH=contentBottom()-panelY;
            sectionTitle(g,t,x,y,"ADMIN");
            Retro.right(g,"Every action here is logged.",F16,w-32,y+42,QUIET,0);
            Retro.panel(g,x,panelY,panelW,panelH,t,reveal(1),null);
            if(!admin){
                Retro.plain(g,"Admins only",F32,x+30,panelY+80,t.main);
                Retro.plain(g,"This page opens for accounts the server lists as admins.",F16,x+30,panelY+112,QUIET);
                return;
            }
            int half=(panelW-54)/2,left=x+18,right=left+half+18;
            Retro.plain(g,"ADMIN RIGHTS (ROOT ADMIN ONLY)",F16,left,panelY+62,t.main);Retro.plain(g,"MINECRAFT COMMAND",F16,right,panelY+62,t.main);
            adminTargetBounds.setBounds(left,panelY+76,half,40);adminCommandBounds.setBounds(right,panelY+76,half,40);paintTerminalInput(g,adminTargetBounds,adminField==0,adminTargetDraft,"Player name or UUID",t);paintTerminalInput(g,adminCommandBounds,adminField==1,adminCommandDraft,"Allowlisted Minecraft command",t);
            int bw=(half-12)/2;
            if(root){adminGrantBounds.setBounds(left,panelY+130,bw,38);adminRevokeBounds.setBounds(left+bw+12,panelY+130,bw,38);button(g,adminGrantBounds,"GRANT ADMIN",t);button(g,adminRevokeBounds,pendingConfirm.equals("revoke")?"CONFIRM: REVOKE":"REVOKE ADMIN",t);}
            else{adminGrantBounds.setBounds(0,0,0,0);adminRevokeBounds.setBounds(0,0,0,0);Retro.plain(g,"Only the root admin can make admins.",F16,left,panelY+154,STEEL);}
            adminBanBounds.setBounds(right,panelY+130,bw,38);adminUnbanBounds.setBounds(right+bw+12,panelY+130,bw,38);button(g,adminBanBounds,pendingConfirm.equals("ban")?"CONFIRM: BAN":"BAN PLAYER",t);button(g,adminUnbanBounds,"UNBAN PLAYER",t);
            adminExecuteBounds.setBounds(right,panelY+180,half,40);button(g,adminExecuteBounds,adminActionInProgress?"SENDING":"SEND COMMAND",t);
            g.setColor(t.line);g.drawLine(x+12,panelY+238,x+panelW-12,panelY+238);Retro.plain(g,"ADMINS",F16,x+20,panelY+264,t.main);
            // Rows stop above the footer line; whatever does not fit is counted as "+N" beside the list heading.
            int listY=panelY+278,rowH=44,footerY=panelY+panelH-18,fit=Math.max(0,(footerY-24-listY)/rowH),shown=Math.min(fit,adminAccounts.size());
            if(adminAccounts.size()>shown)Retro.right(g,"+"+(adminAccounts.size()-shown)+" "+"MORE",F16,x+panelW-20,panelY+264,t.data,0);
            if(adminAccounts.isEmpty())Retro.plain(g,"Loading the list...",F16,x+22,listY+28,STEEL);
            else for(int i=0;i<shown;i++){ErdvynApiClient.AdminAccount item=adminAccounts.get(i);int ry=listY+i*rowH;g.setColor(t.faint);g.fillRect(x+18,ry,panelW-36,rowH-6);g.setColor(t.line);g.drawRect(x+18,ry,panelW-36,rowH-6);BufferedImage head=adminHeads.get(item.uuid());if(head!=null)g.drawImage(head,x+24,ry+3,32,32,null);else{g.setColor(t.faint);g.fillRect(x+24,ry+3,32,32);g.setColor(t.main);g.drawRect(x+24,ry+3,32,32);}Retro.plain(g,item.minecraftName(),F16,x+70,ry+17,Retro.PAPER);Retro.plain(g,item.root()?"ROOT ADMIN":"ADMIN",F16,x+70,ry+34,item.root()?t.data:QUIET);String date="GRANTED "+(item.grantedAt()<=0?"--":DateTimeFormatter.ofPattern("dd.MM.yyyy  HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(item.grantedAt())));Retro.right(g,date,F16,x+panelW-32,ry+25,QUIET,0);}
            Retro.plain(g,root?"You are the root admin.":"You are an admin.",F16,x+20,footerY,STEEL);
            if(!adminNotice.isBlank())Retro.plain(g,Retro.fit(F16,localized(adminNotice),panelW-320),F16,x+300,footerY,adminNotice.startsWith("ERROR")||adminNotice.startsWith("HATA")?t.main:Retro.PAPER);
        }

        // ================================================================ GAME

        private void paintGamePanel(Graphics2D g){
            Retro.Theme t=Retro.GAME;int x=contentLeft(),y=104,w=getWidth(),h=getHeight();sectionTitle(g,t,x,y,t("gamePanel"));
            int panelY=y+82,panelW=w-x-32,panelH=contentBottom()-panelY,leftW=Math.max(275,Math.min(340,panelW/3));
            Retro.panel(g,x,panelY,leftW,panelH,t,reveal(1),"IN GAME");
            int count=onlinePlayers.size(),cap=Math.max(0,serverSnapshot.maxPlayers());
            int sw=Retro.seg7(g,Integer.toString(Math.min(999,count)),x+18,panelY+40,32,t.data,time);Retro.plain(g,(cap>0?"/ "+cap:"")+" players",F16,x+28+sw,panelY+72,t.dataMuted);
            g.setColor(t.line);g.drawLine(x+12,panelY+92,x+leftW-12,panelY+92);
            int rows=Math.max(0,(panelH-176)/26);
            if(onlinePlayers.isEmpty())Retro.plain(g,"Nobody is playing right now.",F16,x+18,panelY+122,STEEL);
            else for(int i=0;i<onlinePlayers.size()&&i<rows;i++){int py=panelY+122+i*26;Retro.plain(g,Retro.fit(F16,onlinePlayers.get(i),leftW-40),F16,x+18,py,Retro.PAPER);}
            if(onlinePlayers.size()>rows&&rows>0)Retro.right(g,"+"+(onlinePlayers.size()-rows),F16,x+leftW-18,panelY+122+(rows-1)*26,t.data,0);
            // Signal strength from the status ping, five bars like a handset.
            int sy=panelY+panelH-26;boolean online=serverSnapshot.online();long ping=serverSnapshot.latencyMs();int bars=!online?0:ping<60?5:ping<100?4:ping<160?3:ping<250?2:1;
            g.setColor(t.line);g.drawLine(x+12,sy-36,x+leftW-12,sy-36);
            Retro.plain(g,"SIGNAL",F16,x+18,sy,QUIET);
            for(int i=0;i<5;i++){int bh=6+i*5,bx=x+96+i*12;g.setColor(i<bars?t.data:Retro.alpha(t.data,30));g.fillRect(bx,sy-bh+2,8,bh);}
            Retro.plain(g,online?String.format(Locale.ROOT,"%03d MS",Math.min(999,ping)):"NONE",F16,x+166,sy,online?Retro.PAPER:t.alert);
            int chatX=x+leftW+14,chatW=panelW-leftW-14;
            Retro.panel(g,chatX,panelY,chatW,panelH,t,reveal(1.5),"CHAT");
            if(!hub.connected()){String bus="connecting";Retro.plain(g,bus,F16,chatX+chatW-18-Retro.width(F16,bus),panelY+28,Retro.YELLOW);}
            // Newest lines at the bottom, as many as fit above the input.
            int msgX=chatX+180,msgW=chatW-200,top=panelY+48,bottom=panelY+panelH-66;List<String[]> layout=new ArrayList<>();
            for(int i=chatLines.size()-1;i>=0&&layout.size()<(bottom-top)/19;i--){ChatLine line=chatLines.get(i);List<String> wrapped=wrapLines(F16,line.owned?localized(line.message):line.message,msgW);for(int k=Math.min(2,wrapped.size())-1;k>=0;k--)layout.add(0,new String[]{k==0?line.time.format(HH_MM):"",k==0?(line.owned?"SYSTEM":line.author):"",wrapped.get(k),line.owned?"1":"0"});}
            int startRow=Math.max(0,layout.size()-(bottom-top)/19);
            for(int i=startRow;i<layout.size();i++){String[] row=layout.get(i);int ly=top+(i-startRow)*19+14;Retro.plain(g,row[0],F16,chatX+16,ly,STEEL);if(!row[1].isBlank()){boolean system="1".equals(row[3]);Retro.plain(g,Retro.fit(F16,row[1],100),F16,chatX+66,ly,system?t.main:t.data);}Retro.plain(g,row[2],F16,msgX,ly,"1".equals(row[3])?QUIET:Retro.PAPER);}
            chatInputBounds.setBounds(chatX+14,panelY+panelH-52,chatW-142,38);chatSendBounds.setBounds(chatX+chatW-118,panelY+panelH-52,104,38);
            paintTerminalInput(g,chatInputBounds,chatFocused,chatDraft,t("writeMessage"),t);button(g,chatSendBounds,t("send"),t);
        }

        // ================================================================ MAP

        /**
         * The planet as an instrument: a globe to grab and turn (flicks keep turning, the wheel zooms toward the
         * pointer down to the survey's own two-block detail, a double click flies there) beside the survey log: the
         * share of the planet seen, the area walked and the waypoints, each a click away. All of it is read from this
         * computer and never leaves it.
         */
        private void paintWorldMap(Graphics2D g){
            Retro.Theme t=Retro.MAP;int x=contentLeft(),y=104;
            sectionTitle(g,t,x,y,t("worldMap"));
            int mapY=y+82,mapW=getWidth()-x-32,mapH=contentBottom()-mapY,sideW=Math.max(232,Math.min(300,mapW*27/100)),viewW=mapW-sideW-14;
            PlanetSurvey.Snapshot s=survey.current();
            mapViewBounds.setBounds(x,mapY,viewW,mapH);
            paintGlobeView(g,t,s,x,mapY,viewW,mapH);
            paintSurveyLog(g,t,s,x+viewW+14,mapY,sideW,mapH);
        }
        private void paintGlobeView(Graphics2D g,Retro.Theme t,PlanetSurvey.Snapshot s,int x,int y,int w,int h){
            g.setColor(new Color(5,4,9));g.fillRect(x,y,w,h);
            Shape clip=g.getClip();g.clipRect(x,y,w,h);
            long seed=0x5EED;for(int i=0;i<110;i++){seed=seed*6364136223846793005L+1442695040888963407L;int sx=x+(int)((seed>>>33)%w),sy=y+(int)((seed>>>13)%h);g.setColor(Retro.alpha(Retro.PAPER,30+(int)((seed>>>50)%3)*30));g.fillRect(sx,sy,1,1);}
            double radius=Math.min(w,h)*.42*mapGlobe.zoom;
            mapGlobe.paint(g,x,y,w,h,w/2.0,h/2.0,radius,s);
            mapGlobe.paintGrid(g,Retro.alpha(t.main,52));
            if(radius<Math.max(w,h))mapGlobe.paintRim(g,t.main);
            double[] p=new double[3];
            // sector names on the cells facing the viewer, as the field terminal labels them
            for(int c=0;c<8;c++)for(int r=0;r<8;r++){
                if(!mapGlobe.project(PlanetSurvey.MIN+(c+.5)*PlanetSurvey.SPAN/8.0,PlanetSurvey.MIN+(r+.5)*PlanetSurvey.SPAN/8.0,p)||p[2]<.45)continue;
                String cell=(char)('A'+r)+Integer.toString(c+1);
                Retro.plain(g,cell,F16,(int)Math.round(p[0])-Retro.width(F16,cell)/2,(int)Math.round(p[1])+5,Retro.alpha(Retro.PAPER,(int)(Math.min(1,(p[2]-.45)*3)*120)));
            }
            List<PlanetSurvey.Waypoint> marks=s.waypoints();int hot=-1;
            for(int i=0;i<marks.size();i++){
                PlanetSurvey.Waypoint m=marks.get(i);if(!mapGlobe.project(m.x(),m.z(),p)||p[2]<.06)continue;
                int mx=(int)Math.round(p[0]),my=(int)Math.round(p[1]);boolean lit=i==hoverWaypoint||Math.abs(mouse.x-mx)<=6&&Math.abs(mouse.y-my)<=6&&globeDragAt==null&&mapViewBounds.contains(mouse);
                if(lit)hot=i;
                paintWaypoint(g,m,mx,my,lit);
            }
            paintStations(g,mapGlobe,t,x+w-52); // clear of the zoom keys
            double[] at=globeDragAt==null&&mapViewBounds.contains(mouse)&&!overlayOpen()?mapGlobe.pick(mouse.x,mouse.y):null;
            if(hot>=0){
                PlanetSurvey.Waypoint m=marks.get(hot);mapGlobe.project(m.x(),m.z(),p);
                String label=waypointName(m)+"  "+PlanetGlobe.sector(m.x(),m.z());int lx=(int)p[0]+12,ly=(int)p[1]-8,lw=Retro.width(F16,label)+12;
                if(lx+lw>x+w-6)lx=(int)p[0]-12-lw;
                g.setColor(Retro.alpha(Retro.INK,230));g.fillRect(lx,ly-12,lw,20);g.setColor(t.line);g.drawRect(lx,ly-12,lw,20);Retro.plain(g,label,F16,lx+6,ly+3,Retro.PAPER);
            }else if(at!=null){
                String cell=PlanetGlobe.sector(at[0],at[1]),seen=s.at(Math.floorMod((int)Math.floor((at[0]-PlanetSurvey.MIN)/PlanetSurvey.PIXEL),PlanetSurvey.PX),Math.max(0,Math.min(PlanetSurvey.PX-1,(int)Math.floor((at[1]-PlanetSurvey.MIN)/PlanetSurvey.PIXEL))))!=0?"  SURVEYED":"";
                String label=cell+seen;int lx=Math.min(mouse.x+10,x+w-Retro.width(F16,label)-18),ly=Math.min(mouse.y+24,y+h-30);
                g.setColor(Retro.alpha(Retro.INK,220));g.fillRect(lx-4,ly-14,Retro.width(F16,label)+8,20);Retro.plain(g,label,F16,lx,ly,seen.isEmpty()?Retro.PAPER:t.data);
            }
            // zoom and keys
            Retro.plain(g,String.format(Locale.ROOT,"%.1fx",mapGlobe.zoom),F16,x+14,y+24,QUIET);
            int kx=x+w-44;
            mapZoomInBounds.setBounds(kx,y+12,32,30);mapZoomOutBounds.setBounds(kx,y+46,32,30);mapCentreBounds.setBounds(kx,y+80,32,30);
            for(Rectangle k:List.of(mapZoomInBounds,mapZoomOutBounds,mapCentreBounds)){
                double hv=hover(k);g.setColor(Retro.alpha(Retro.INK,220));g.fillRect(k.x,k.y,k.width,k.height);g.setColor(hv>.5?t.hot:t.line);g.drawRect(k.x,k.y,k.width,k.height);
                Color ink=Retro.mix(t.main,Color.WHITE,.4*hv);g.setColor(ink);int cx=k.x+16,cy=k.y+15;
                if(k==mapCentreBounds){g.drawOval(cx-6,cy-6,12,12);g.fillRect(cx-1,cy-1,3,3);g.fillRect(cx-10,cy,4,1);g.fillRect(cx+7,cy,4,1);g.fillRect(cx,cy-10,1,4);g.fillRect(cx,cy+7,1,4);}
                else{g.fillRect(cx-6,cy-1,13,3);if(k==mapZoomInBounds)g.fillRect(cx-1,cy-6,3,13);}
            }
            String help="Drag to turn, scroll to zoom, double-click to fly there";
            if(Retro.width(F16,help)<w-28){g.setColor(Retro.alpha(Retro.INK,200));g.fillRect(x+1,y+h-26,w-2,25);Retro.plain(g,help,F16,x+14,y+h-8,QUIET);}
            g.setClip(clip);
            Retro.frame(g,x,y,w,h,t,reveal(1),null);
        }
        private String waypointName(PlanetSurvey.Waypoint m){return m.death()?"DEATHPOINT":m.name();}
        /** Xaero's sixteen waypoint colours, so a marker reads the same here as in game. */
        private static final int[] WAYPOINT_COLORS={0x000000,0x0000AA,0x00AA00,0x00AAAA,0xAA0000,0xAA00AA,0xFFAA00,0xAAAAAA,0x555555,0x5555FF,0x55FF55,0x55FFFF,0xFF5555,0xFF55FF,0xFFFF55,0xFFFFFF};
        private void paintWaypoint(Graphics2D g,PlanetSurvey.Waypoint m,int x,int y,boolean lit){
            if(m.death()){g.setColor(Retro.INK);g.fillRect(x-4,y-4,9,9);g.setColor(Retro.RED);for(int i=-3;i<=3;i++){g.fillRect(x+i,y+i,1,1);g.fillRect(x+i,y-i,1,1);}return;}
            Color fill=new Color(WAYPOINT_COLORS[m.color()]);
            for(int d=-5;d<=5;d++){int half=5-Math.abs(d);g.setColor(Retro.INK);g.fillRect(x-half,y+d,half*2+1,1);}
            for(int d=-4;d<=4;d++){int half=4-Math.abs(d);g.setColor(fill);g.fillRect(x-half,y+d,half*2+1,1);}
            g.setColor(Retro.PAPER);g.fillRect(x,y,1,1);
            if(lit){double pulse=(time*1.6)%1;int rr=(int)(7+pulse*10);g.setColor(Retro.alpha(Retro.PAPER,(int)(200*(1-pulse))));g.drawOval(x-rr,y-rr,rr*2,rr*2);}
        }
        /** The survey log beside the globe: world, share seen, area, sectors and the waypoint list. */
        private void paintSurveyLog(Graphics2D g,Retro.Theme t,PlanetSurvey.Snapshot s,int x,int y,int w,int h){
            Retro.panel(g,x,y,w,h,t,reveal(1.4),"SURVEY");
            int ix=x+16,iw=w-32,cy=y+52;
            List<String> worlds=survey.worlds();String world=s.world().isEmpty()?"NO RECORD":s.world().replaceFirst("^(server|local)-","");
            surveyWorldBounds.setBounds(worlds.size()>1?x+8:0,worlds.size()>1?cy-16:0,worlds.size()>1?w-16:0,worlds.size()>1?24:0);double hw=hover(surveyWorldBounds);
            if(hw>.01){g.setColor(Retro.alpha(t.main,(int)(30*hw)));g.fillRect(surveyWorldBounds.x,surveyWorldBounds.y,surveyWorldBounds.width,surveyWorldBounds.height);}
            String prefix="WORLD: ";Retro.plain(g,prefix,F16,ix,cy,QUIET);
            Retro.plain(g,Retro.fit(F16,world,iw-Retro.width(F16,prefix)-(worlds.size()>1?28:0)),F16,ix+Retro.width(F16,prefix),cy,hw>.5?t.hot:Retro.PAPER);
            if(worlds.size()>1)Retro.right(g,"switch",F16,ix+iw,cy,t.data,0);
            cy+=32;
            Retro.plain(g,"PLANET SURVEYED",F16,ix,cy,QUIET);
            boolean compact=h<470; // a small window keeps room for the waypoint list
            String pct=String.format(Locale.ROOT,"%.2f",s.fraction()*100);int segH=compact?22:30,pw=Retro.seg7(g,pct,ix,cy+10,segH,t.data,time);Retro.plain(g,"%",F16,ix+pw+8,cy+10+segH,t.dataMuted);
            cy+=segH+34;
            if(!compact){Retro.bar(g,ix,cy,iw,6,Math.min(1,Math.max(s.empty()?0:.01,s.fraction()*20)),t.main);Retro.right(g,"scale: 5%",F16,ix+iw,cy+24,STEEL,0);cy+=40;} // the bar is 5% full scale: early travel still shows
            int sectors=0;boolean[] seenSector=new boolean[64];
            for(int i=0;i<s.tiles().length;i++)if(s.tiles()[i]!=null){int c=(i%PlanetSurvey.TILES)*8/PlanetSurvey.TILES,r=(i/PlanetSurvey.TILES)*8/PlanetSurvey.TILES;if(!seenSector[r*8+c]){seenSector[r*8+c]=true;sectors++;}}
            String saved=s.savedAt()<=0?"--":java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(s.savedAt()),ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM  HH:mm"));
            String[][] rows={{"AREA",String.format(Locale.ROOT,"%.1f KM²",s.seenPixels()*4/1e6)},{"SECTORS",sectors+" / 64"},{"WAYPOINTS",Integer.toString(s.waypoints().size())},{"LAST SAVED",saved}};
            for(String[] row:rows){Retro.plain(g,row[0],F16,ix,cy,t.muted);Retro.right(g,row[1],F16,ix+iw,cy,Retro.PAPER,0);cy+=22;}
            cy+=8;g.setColor(t.line);g.drawLine(ix,cy,ix+iw,cy);cy+=24;
            Retro.text(g,"WAYPOINTS",F16,ix,cy,t.main,.5);cy+=10;
            List<PlanetSurvey.Waypoint> marks=s.waypoints();int rowH=26,fitAll=(y+h-34-cy)/rowH;boolean paged=marks.size()>Math.min(waypointBounds.length,fitAll);
            int listBottom=y+h-(paged?54:34),visible=Math.max(0,Math.min(waypointBounds.length,(listBottom-cy)/rowH));
            waypointScroll=Math.max(0,Math.min(Math.max(0,marks.size()-visible),waypointScroll));
            hoverWaypoint=-1;
            for(int i=0;i<waypointBounds.length;i++){
                int index=waypointScroll+i;Rectangle r=waypointBounds[i];
                if(i>=visible||index>=marks.size()){r.setBounds(0,0,0,0);continue;}
                r.setBounds(x+8,cy+i*rowH,w-16,rowH-2);double hv=hover(r);if(hv>.5)hoverWaypoint=index;
                PlanetSurvey.Waypoint m=marks.get(index);
                if(hv>.01){g.setColor(Retro.alpha(t.main,(int)(36*hv)));g.fillRect(r.x,r.y,r.width,r.height);}
                paintWaypoint(g,m,ix+5,r.y+r.height/2,false);
                String sector=PlanetGlobe.sector(m.x(),m.z());
                Retro.plain(g,Retro.fit(F16,waypointName(m),iw-30-Retro.width(F16,sector)-10),F16,ix+18,r.y+r.height/2+5,hv>.5?t.hot:m.death()?Retro.RED:Retro.PAPER);
                Retro.right(g,sector,F16,ix+iw,r.y+r.height/2+5,t.data,0);
            }
            if(marks.isEmpty()){
                List<String> hint=wrapLines(F16,s.empty()?"The ground you see in game appears here as you travel.":"Waypoints you set on the map in game are listed here.",iw);
                for(int i=0;i<Math.min(3,hint.size())&&cy+22+i*20<listBottom;i++)Retro.plain(g,hint.get(i),F16,ix,cy+22+i*20,QUIET);
            }else if(paged&&visible>0)Retro.right(g,(waypointScroll+1)+"-"+Math.min(marks.size(),waypointScroll+visible)+" / "+marks.size(),F16,ix+iw,y+h-36,STEEL,0);
            String foot=!survey.error().isEmpty()?"Could not read: "+survey.error():"Only read from this computer.";
            Retro.plain(g,Retro.fit(F16,foot,iw),F16,ix,y+h-14,survey.error().isEmpty()?STEEL:t.alert);
        }

        // ================================================================ boot and launch

        /** The tube warming up on the mark alone; it claims no checks, the real ones run behind PLAY. */
        private void paintBootOverlay(Graphics2D g){
            Retro.Theme t=Retro.BOOT;int w=getWidth(),h=getHeight();double p=time-bootStartedAt,fade=Retro.clamp01((p-BOOT_SECONDS)/.35);
            Composite old=g.getComposite();if(fade>0)g.setComposite(AlphaComposite.SrcOver.derive((float)(1-fade)));
            g.setColor(Retro.INK);g.fillRect(0,0,w,h);
            // a loading screen: the mark alone, no name
            boolean big=w>=1200&&h>=720;int markSize=big?260:170,wy=h/2+markSize/4;
            ErdvynMark.paint(g,(w-markSize)/2,wy-(int)(markSize*.82),markSize,p);
            Retro.plain(g,LauncherUpdateService.CURRENT_VERSION,F16,28,h-24,STEEL);
            g.setComposite(old);
            Retro.tube(g,w,h,Retro.clamp01(p/.6));
        }

        private void paintLaunchOverlay(Graphics2D g){
            int w=getWidth(),h=getHeight();boolean halted=launchFailed||gameCrashed;Retro.Theme t=halted?Retro.HALT:Retro.LAUNCH;
            double intro=Retro.clamp01((System.currentTimeMillis()-launchStartedAtMillis)/700.0);
            g.setColor(new Color(0,0,0,(int)(226*Retro.easeOut(intro*3))));g.fillRect(0,0,w,h);
            // Below the header strip: minimize and close stay reachable above the box.
            int bw=Math.min(960,w-60),bh=Math.min(610,h-84),x=(w-bw)/2,y=Math.max(62,(h-bh)/2);
            // The box opens like a tube coming on: a line across, then the full height.
            double open=Retro.easeOut((intro-.12)/.5);
            if(open<1){int lineW=(int)(bw*Retro.easeOut(intro/.18)),oh=Math.max(2,(int)(bh*open)),oy=y+(bh-oh)/2;g.setColor(t.panel);g.fillRect(x,oy,bw,oh);g.setColor(t.main);g.drawRect(x,oy,bw,oh);g.setColor(Retro.alpha(Retro.PAPER,(int)(230*(1-open))));g.fillRect(x+(bw-lineW)/2,y+bh/2-1,lineW,3);return;}
            for(Rectangle r:List.of(launchDismissBounds,launchLogsBounds,launchCancelBounds,crashReportsBounds,crashPlayBounds))r.setBounds(0,0,0,0);
            Retro.panel(g,x,y,bw,bh,t,1,null);double r=Retro.clamp01((intro-.6)/.4);
            int ix=x+22,iy=y+20;ErdvynMark.paint(g,ix,iy,64,time);
            Retro.plain(g,(autoConnect?LauncherPaths.serverAddress():"to the main menu")+",  RAM "+gameOptions.ramGb()+" GB,  "+"pack "+packSummary.version(),F16,ix+84,iy+44,t.muted);
            String state=gameCrashed?"THE GAME CRASHED":launchFailed?"COULD NOT START":"STARTING MINECRAFT";
            Retro.plain(g,state,F16,ix+84,iy+22,halted?t.alert:t.main);
            // The status in large type where a logo used to stand: on this screen what is happening is the only news.
            int sy=y+150;String headline=launchStatus.isBlank()?"Getting ready":localized(launchStatus);
            Font headFont=Retro.width(F32,headline)<=bw-140?F32:F16;
            Retro.plain(g,Retro.fit(headFont,headline,bw-140),headFont,x+22,sy,halted?t.alert:Retro.PAPER);
            if(!halted){
                String percent=Integer.toString((int)Math.round(launchDisplayedProgress*100));int pw=Retro.segWidth(percent,18);
                Retro.seg7(g,percent,x+bw-38-pw,sy-16,18,t.main,time);Retro.plain(g,"%",F16,x+bw-34,sy,t.main);
                Retro.bar(g,x+22,sy+14,bw-44,6,launchDisplayedProgress,t.main);
            }
            int cy=sy+40,ch=y+bh-66-cy,sideW=250,gap=14,logW=bw-44-sideW-gap;
            Retro.panel(g,x+22,cy,logW,ch,t,1,"LOG");
            Retro.panel(g,x+22+logW+gap,cy,sideW,ch,t,1,"DETAILS");
            // "What to do" sits under the trace, wrapped, so the advice is never cut off like a one-line headline would be.
            int adviceH=halted&&!launchAdvice.isBlank()?72:0;
            if(adviceH>0){int ay=cy+ch-adviceH;g.setColor(t.line);g.drawLine(x+32,ay,x+22+logW-10,ay);Retro.plain(g,"WHAT TO DO",F16,x+36,ay+22,t.data);List<String> advice=wrapLines(F16,localized(launchAdvice),logW-34);for(int i=0;i<Math.min(2,advice.size());i++)Retro.plain(g,advice.get(i),F16,x+36,ay+44+i*19,Retro.PAPER);}
            int maxLines=Math.max(3,(ch-40-adviceH)/20),start=Math.max(0,launchTrace.size()-maxLines);
            for(int i=start;i<launchTrace.size();i++){String line=localized(launchTrace.get(i));Color c=line.startsWith("[FAIL]")?Retro.RED:line.startsWith("[WAIT]")?Retro.YELLOW:line.startsWith("[GET]")?Retro.CYAN:line.startsWith("[OK]")?Retro.GREEN:line.startsWith("[INFO]")?QUIET:Retro.PAPER;Retro.plain(g,Retro.fit(F16,line,logW-34),F16,x+36,cy+30+(i-start)*20,c);}
            if(launchTrace.isEmpty())Retro.plain(g,"waiting...",F16,x+36,cy+30,STEEL);
            int sx=x+22+logW+gap+16;String elapsed=formatUptime(Math.max(0,(System.currentTimeMillis()-launchStartedAtMillis)/1000));
            Retro.plain(g,"ELAPSED",F16,sx,cy+30,QUIET);Retro.seg7(g,elapsed,sx,cy+38,22,t.main,time);
            String[][] telemetry={{"PROCESS",launchPid>0?"PID "+launchPid:"not yet"},{"MEMORY",gameOptions.ramGb()+" GB"},{"PACK",packSummary.version()},{"GOING TO",autoConnect?LauncherPaths.serverAddress():"main menu"}};
            for(int i=0;i<telemetry.length;i++){int ty=cy+92+i*42;if(ty+20>cy+ch)break;Retro.plain(g,telemetry[i][0],F16,sx,ty,QUIET);Retro.plain(g,Retro.fit(F16,telemetry[i][1],sideW-32),F16,sx,ty+19,i==0&&launchPid==0?Retro.YELLOW:Retro.PAPER);g.setColor(t.faint);g.drawLine(sx,ty+27,x+bw-38,ty+27);}
            int fy=y+bh-50;
            if(gameCrashed){int bx=x+22,bwid=(bw-44-36)/4;Rectangle[] slots={crashReportsBounds,launchLogsBounds,crashPlayBounds,launchDismissBounds};String[] labels={"CRASH REPORTS","OPEN LOGS","PLAY AGAIN","CLOSE"};for(int i=0;i<slots.length;i++){slots[i].setBounds(bx+i*(bwid+12),fy,bwid,34);button(g,slots[i],labels[i],i==2?Retro.LAUNCH:t);}return;}
            boolean cancellable=!launchFailed&&!gameReady&&gameLaunching;
            if(launchFailed){launchDismissBounds.setBounds(x+bw-166,fy,142,34);button(g,launchDismissBounds,"RETURN",t);launchLogsBounds.setBounds(x+bw-330,fy,152,34);button(g,launchLogsBounds,"OPEN LOGS",t);}
            else if(cancellable){launchCancelBounds.setBounds(x+bw-166,fy,142,34);button(g,launchCancelBounds,packCancel.get()?"STOPPING":"CANCEL",Retro.HALT);}
        }

        private void beginLaunchOverlay(){launchOverlayActive=true;launchFailed=false;gameCrashed=false;gameReady=false;launchAdvice="";launchNotice="";launchTrace.clear();launchStatus="Starting";launchDisplayedProgress=.02;launchTargetProgress=.04;launchStartedAtMillis=System.currentTimeMillis();launchPid=0;launchLastPackBucket=-1;appendLaunchTrace("[EXEC] "+"Play pressed");appendLaunchTrace("[WAIT] "+"Java and NeoForge come next");}
        private void startLaunchPreview(){
            beginLaunchOverlay();gameLaunching=true;launchPid=4217;launchDisplayedProgress=.88;launchTargetProgress=.94;launchStartedAtMillis=System.currentTimeMillis()-43_000;
            launchStatus="NeoForge is loading the mods";
            launchTrace.clear();
            appendLaunchTrace("[OK] Java 21 found");
            appendLaunchTrace("[OK] Pack 2026.08.30.5 matches the server");
            appendLaunchTrace("[OK] 1187 files checked");
            appendLaunchTrace("[OK] Signed in with Microsoft");
            appendLaunchTrace("[EXEC] Java with "+gameOptions.ramGb()+" GB, NeoForge 21.1.243");
            appendLaunchTrace("[OK] Mod loader started");
            appendLaunchTrace("[OK] NeoForge is loading the mods");
            appendLaunchTrace("[WAIT] "+"Waiting for the game window");
            repaint();
        }
        private void appendLaunchTrace(String line){String value=line==null?"":line.replaceAll("\\s+"," ").strip();if(value.isBlank())return;if(value.length()>150)value=value.substring(0,147)+"...";launchTrace.add(value);while(launchTrace.size()>96)launchTrace.remove(0);}
        private void setLaunchStage(String status,double progress,String event){Runnable update=()->{launchStatus=status;launchTargetProgress=Math.max(launchTargetProgress,Math.max(0,Math.min(1,progress)));if(event!=null&&!event.isBlank())appendLaunchTrace(event);repaint();};if(SwingUtilities.isEventDispatchThread())update.run();else SwingUtilities.invokeLater(update);}
        private void onPackLaunchProgress(PackService.Progress progress){if(!progress.tick())LauncherLog.write(progress.line());SwingUtilities.invokeLater(()->{packProgress=progress.value();trackPackBytes(progress);int percent=(int)Math.round(progress.value()*100),bucket=percent/5;if(!progress.tick()){packStatus=progress.line();addPackLog(progress.line());boolean notable=progress.line().contains("[GET]")||progress.line().contains("[SAVED]")||progress.line().contains("[FAIL]")||progress.line().startsWith("MANIFEST")||bucket>launchLastPackBucket;if(notable){launchLastPackBucket=Math.max(launchLastPackBucket,bucket);appendLaunchTrace((progress.line().contains("[FAIL]")?"[FAIL] ":progress.line().contains("[GET]")?"[GET] ":"[OK] ")+"PACK AUDIT "+percent+"%");}}String meter=downloadMeter();launchStatus=meter.isBlank()?"VERIFYING PACKAGE"+" / "+percent+"%":"DOWNLOADING PACKAGE"+" / "+meter;launchTargetProgress=Math.max(launchTargetProgress,.20+progress.value()*.43);repaint();});}
        private void trackPackBytes(PackService.Progress progress){packBytesDone=progress.bytesDone();packBytesTotal=progress.bytesTotal();packBytesRate=progress.bytesPerSecond();}
        /** "120 MB / 340 MB / 8.2 MB/S / ETA 00:00:42" while the pack downloads, blank while it only audits. */
        private String downloadMeter(){if(packBytesTotal<=0)return "";String text=(packBytesDone<=0?"0 B":formatBytes(packBytesDone))+" / "+formatBytes(packBytesTotal);if(packBytesRate>1&&packBytesDone<packBytesTotal)text+=" / "+formatBytes((long)packBytesRate)+"/S / "+"ETA "+formatUptime(Math.round((packBytesTotal-packBytesDone)/packBytesRate));return text;}
        /** Stops the running verify at the next file or chunk; the worker reports it through onPackCancelled. */
        private void cancelPackWork(){if(!packVerifying||packCancel.get())return;playUiSound(70);packCancel.set(true);packService.abortDownload();packStatus="CANCELLING...";repaint();}
        /**
         * Stops a launch anywhere before the render handoff: the pack audit at its next file or chunk, a download or the
         * NeoForge installer through the interrupt, and a Minecraft that is still loading by ending its process tree.
         */
        private void cancelLaunch(){
            if(!gameLaunching||gameReady||packCancel.get())return;playUiSound(70);packCancel.set(true);packService.abortDownload();
            Thread worker=launchThread;if(worker!=null)worker.interrupt();
            Process game=launchingGame;if(game!=null&&game.isAlive()){game.descendants().forEach(ProcessHandle::destroy);game.destroy();}
            launchStatus="CANCELLING";appendLaunchTrace("[INFO] "+"Cancel pressed");repaint();
        }
        private void onPackCancelled(){LauncherLog.write("Pack verification cancelled by the user");packVerifying=false;packBytesTotal=0;packInstalled=packService.isInstalled();packStatus="Cancelled: the install was not marked complete. PLAY or VERIFY resumes where it stopped.";addPackLog("[CANCEL] "+packStatus);repaint();}
        private String actionable(Throwable error){String hint=UiMessages.hint(error);return hint==null?shortError(error):hint;}
        private void onMinecraftLaunchSignal(String line){LauncherLog.write(line);SwingUtilities.invokeLater(()->{packStatus=line;addPackLog(line);double next=launchTargetProgress;String prefix="[EXEC] ";if(line.startsWith("Waiting for Minecraft")){next=Math.max(next,.90);prefix="[WAIT] ";}else if(line.startsWith("PROCESS STARTED")){next=Math.max(next,.87);try{launchPid=Long.parseLong(line.replaceAll(".*PID\\s+","").strip());}catch(Exception ignored){}}else if(line.startsWith("Mod loader"))next=Math.max(next,.91);else if(line.startsWith("NeoForge is"))next=Math.max(next,.925);else if(line.startsWith("Signed in to"))next=Math.max(next,.94);else if(line.startsWith("Game window"))next=Math.max(next,.955);else if(line.startsWith("Loading textures"))next=Math.max(next,.97);else if(line.startsWith("Sound started")||line.startsWith("Textures built"))next=Math.max(next,.985);else if(line.startsWith("Minecraft is ready")||line.startsWith("Minecraft is running")){next=1;prefix="[OK] ";}launchTargetProgress=next;launchStatus=line;appendLaunchTrace(prefix+line);repaint();});}

        /** Click/Esc/Space jumps to the end of the decorative boot. */
        private void skipBoot(){if(!bootActive)return;bootStartedAt=Math.min(bootStartedAt,time-BOOT_SECONDS);repaint();}
        private void dismissLaunchFailure(){launchOverlayActive=false;launchFailed=false;gameCrashed=false;launchAdvice="";gameLaunching=false;for(Rectangle r:List.of(launchDismissBounds,launchLogsBounds,launchCancelBounds,crashReportsBounds,crashPlayBounds))r.setBounds(0,0,0,0);repaint();}
        /** The window is hidden while Minecraft runs: no repaint timer, no status pings. */
        void suspendForGame(){timer.stop();serverStatus.close();}
        private void resumeAfterGame(){serverStatus=new MinecraftServerStatus(this::onServerStatus);if(!uiTest())serverStatus.start();lastTickNanos=0;timer.start();}
        /** Exit 0 is a normal quit and closes the launcher as before; anything else brings it back with the crash panel. */
        private void onGameExited(int code){LauncherLog.write("Minecraft exited with code "+code);if(code==0){frame.shutdownAndExit();return;}frame.showAfterGame();resumeAfterGame();showGameCrash(code);}
        private void showGameCrash(int code){
            launchOverlayActive=true;launchFailed=false;gameCrashed=true;gameLaunching=false;gameExitCode=code;launchTargetProgress=launchDisplayedProgress=1;
            launchStatus="Minecraft closed unexpectedly, exit code "+code;appendLaunchTrace("[FAIL] "+"Minecraft exited, exit code "+code);
            launchAdvice="Open the crash report and logs below. If it keeps happening, send the newest report to an admin.";
            try(var reports=Files.list(LauncherPaths.folder("crash-reports"))){reports.filter(Files::isRegularFile).max(Comparator.comparingLong(path->path.toFile().lastModified())).filter(path->path.toFile().lastModified()>=launchStartedAtMillis).ifPresent(path->appendLaunchTrace("[INFO] crash-reports/"+path.getFileName()));}catch(Exception ignored){}
            // A crash may come from a damaged file: the next launch reads every file again instead of trusting the hash stamps.
            if(!uiTest())Thread.startVirtualThread(()->HashCache.shared().clear());
            notify("Minecraft crashed (exit code "+code+").");repaint();
        }
        private boolean confirmed(String action){if(pendingConfirm.equals(action)){pendingConfirm="";pendingConfirmUntil=0;return true;}pendingConfirm=action;pendingConfirmUntil=time+4;playUiSound(70);repaint();return false;}
        private void startLauncherBoot(){launcherReady=false;bootActive=true;bootCompleteSound=false;lastBootBlock=0;bootStartedAt=time;playBootSound(0);}
        private void advanceBoot(){
            if(!bootActive)return;double p=time-bootStartedAt;
            if(p>=BOOT_SECONDS&&!bootCompleteSound){bootCompleteSound=true;playBootSound(2);}
            if(p>=BOOT_SECONDS+.35){bootActive=false;launcherReady=true;homeGlobeOnAt=time;}
        }
        private void playBootSound(int kind){if(!uiTest())sound.boot(kind);}
        private void playUiSound(double pitch){if(!uiTest())sound.click(pitch);}
        /** The close button: the picture collapses into a line and a dot, then the process exits. */
        void powerOffAndExit(){if(powerOff>=0)return;if(uiTest()||!isShowing()){frame.shutdownAndExit();return;}powerOff=time;timer.setDelay(16);}

        @Override public void actionPerformed(ActionEvent e) {
            long now=System.nanoTime();dt=lastTickNanos==0?1/60.0:Math.min(.1,(now-lastTickNanos)/1e9);lastTickNanos=now;time=(now-startNanos)/1e9;
            pageTransition=Math.min(1,pageTransition+dt/.48);opening=Math.min(1,opening+dt/.55);
            // Widen only while the pointer is on the sidebar itself (it overlays the page, so content never moves).
            double sideTarget=pointerInside&&mouse.y>HEADER&&overSidebar(mouse)&&!overlayOpen()?1:0;
            sidebarExpand=Retro.approach(sidebarExpand,sideTarget,11,dt);volumeReveal=Retro.approach(volumeReveal,sidebarExpand,12,dt);pressDepth=Retro.approach(pressDepth,pressedControl.isEmpty()?0:1,22,dt);
            boolean easing=false;
            for(var entry:hoverAnim.entrySet()){double[] v=entry.getValue();double target=hot(entry.getKey())?1:0;if(Math.abs(target-v[0])>.004){v[0]=Retro.approach(v[0],target,16,dt);easing=true;}else v[0]=target;}
            for(double[] v:switchAnim.values()){if(Math.abs(v[1]-v[0])>.004){v[0]=Retro.approach(v[0],v[1],14,dt);easing=true;}else v[0]=v[1];}
            if(pendingConfirmUntil>0&&time>pendingConfirmUntil){pendingConfirm="";pendingConfirmUntil=0;}
            advanceBoot();if(!uiTest()){pollBackendIfDue();pollLauncherUpdateIfDue();}
            boolean minimized=(frame.getExtendedState()&Frame.ICONIFIED)!=0;
            if(page==Page.HOME||page==Page.MAP)survey.refresh(System.currentTimeMillis());
            centreOnSurvey();
            homeGlobe.yaw=PlanetGlobe.wrapX(homeGlobe.yaw+dt*PlanetSurvey.SPAN/90.0); // once round every ninety seconds
            boolean globeMoving=mapGlobe.tick(dt)||globeDragAt!=null||page==Page.HOME&&time-homeGlobeOnAt<1;
            launchDisplayedProgress=Retro.approach(launchDisplayedProgress,launchTargetProgress,5,dt);
            if(powerOff>=0&&time-powerOff>=.34){frame.shutdownAndExit();return;}
            // Adaptive frame rate: 60 fps while something moves, 30 when idle, 20 in the background, 4 when minimized.
            // (the channel display and the typed-in article that kept these windows at 60 fps are gone: only the reveal and the box opening remain)
            boolean animating=easing||bootActive||launchOverlayActive||powerOff>=0||pageTransition<1||opening<1||Math.abs(sidebarExpand-sideTarget)>.004||selectedNews>=0&&time-articleOpenedAt<.3||globeMoving;
            int delay=minimized?250:animating?16:frame.isActive()?33:50;
            if(timer.getDelay()!=delay)timer.setDelay(delay);
            repaint();
        }
        private void navigate(Page next){if(next==Page.ADMIN&&(apiClient.current()==null||!apiClient.current().account().admin()))return;showPage(next);}
        /** Switches pages without the admin check; captures in UI-test mode use it to show the locked admin page. */
        private void showPage(Page next){if(next==Page.ADMIN&&!uiTest()&&(apiClient.current()==null||!apiClient.current().account().admin()))return;if(page!=next){previousPage=page;page=next;pageTransition=0;pageSwitchedAt=time;if(next==Page.HOME)homeGlobeOnAt=time+.12;}profileOpen=false;selectedNews=-1;newsComposeOpen=false;globeDragAt=null;repaint();}
        /** A newly read world: both globes turn to the ground its player knows (the spawn while there is none). */
        private void centreOnSurvey(){
            PlanetSurvey.Snapshot s=survey.current();String key=s.world()+"@"+s.tileCount();
            if(key.equals(mapCentredOn))return;boolean first=mapCentredOn.isEmpty()||!mapCentredOn.startsWith(s.world()+"@");mapCentredOn=key;
            if(!first)return; // the same world grew: keep the player's view
            homeGlobe.pitch=-.3;homeGlobe.yaw=PlanetGlobe.wrapX(s.centreX()-PlanetSurvey.SPAN*.08);
            mapGlobe.stop();mapGlobe.yaw=s.centreX();mapGlobe.pitch=Math.max(-1.2,Math.min(1.2,((s.centreZ()-PlanetSurvey.MIN)/PlanetSurvey.SPAN-.5)*Math.PI));mapGlobe.zoom=s.empty()?1:1.6;
        }

        @Override public void mouseClicked(MouseEvent e) {
            Point p=e.getPoint();
            if(bootActive){skipBoot();return;}
            if (closeBounds.contains(p)) { playUiSound(70); powerOffAndExit(); return; }
            if (minimizeBounds.contains(p)) { frame.setState(Frame.ICONIFIED); return; }
            // Overlay buttons are zero-sized whenever the overlay does not show them, so containment alone decides.
            if(launchOverlayActive){
                if(launchDismissBounds.contains(p)){playUiSound(84);dismissLaunchFailure();}
                else if(launchLogsBounds.contains(p)){playUiSound(118);openFolderKind("logs");}
                else if(crashReportsBounds.contains(p)){playUiSound(118);openFolderKind("crash-reports");}
                else if(crashPlayBounds.contains(p)){dismissLaunchFailure();requestGameStart();}
                else if(launchCancelBounds.contains(p))cancelLaunch();
                return;
            }
            if(e.getClickCount()==2&&e.getY()<HEADER&&e.getX()>SIDEBAR&&!profileBounds.contains(p)&&!notificationBounds.contains(p)){frame.toggleMaximized();return;}
            if(audioBounds.contains(p)){setUiVolume(sound.volume()>.001?0:Math.max(.2,preferences.getDouble("uiVolume",.8)));playUiSound(105);return;}
            
            if(notificationBounds.contains(p)){playUiSound(94);notificationsOpen=!notificationsOpen;seenNotifications=notifications.size();profileOpen=false;repaint();return;}
            // The notification bus is a modal overlay. Handle its update action before
            // the navigation buttons that remain geometrically underneath the panel.
            if(notificationsOpen){
                if(updateBounds.contains(p)){playUiSound(88);launchPreparedUpdate();return;}
                if(notificationClearBounds.contains(p)){playUiSound(76);notifications.clear();seenNotifications=0;repaint();return;}
                if(!notificationPanelBounds.contains(p))notificationsOpen=false;
                repaint();return;
            }
            if(selectedNews>=0){if(articleCloseBounds.contains(p)){playUiSound(84);selectedNews=-1;newsDeleteArmed=-1;}else if(articleDeleteBounds.contains(p)){playUiSound(84);deleteNews(newsPosts.get(selectedNews).id());}else if(time-articleOpenedAt<4)articleOpenedAt=time-4;repaint();return;}
            if(newsComposeOpen){if(newsTitleInputBounds.contains(p)){newsField=0;requestFocusInWindow();}else if(newsBodyInputBounds.contains(p)){newsField=1;requestFocusInWindow();}else if(newsCancelBounds.contains(p)){playUiSound(84);newsComposeOpen=false;newsTitleDraft="";newsBodyDraft="";}else if(newsPublishBounds.contains(p)){playUiSound(96);publishNews();}repaint();return;}
            if(profileBounds.contains(p)){playUiSound(101);profileOpen=!profileOpen;notificationsOpen=false;repaint();return;}
            if(profileOpen){
                if(accountLoginInProgress){if(cancelLoginBounds.contains(p)){cancelMicrosoftLogin();return;}if(microsoftBounds.contains(p)){playUiSound(96);openDeviceUrl();return;}}
                else{if(microsoftBounds.contains(p)){if(accountSession!=null)signOut();else beginMicrosoftLogin();return;}if(erdvynAccountBounds.contains(p)){beginErdvynLogin();return;}}
                profileOpen=false;repaint();return;
            }
            for (int i = 0; i < navBounds.length; i++) if (navBounds[i].contains(p)) { playUiSound(92+i*7);navigate(Page.values()[i]); return; }
            if(overSidebar(p))return; // the widened sidebar covers the page: never click through it
            if (page == Page.HOME && playBounds.contains(p)) { requestGameStart(); return; }
            if(page==Page.HOME&&homeGlobeBounds.contains(p)&&p.distance(homeGlobe.centreX(),homeGlobe.centreY())<=homeGlobe.radius()){playUiSound(99);openMapFromHome();return;}
            if(page==Page.MAP&&mapClick(p,e.getClickCount()))return;
            if (page == Page.HOME && instancePathBounds.contains(p)) { playUiSound(118);openModpackFolder(); return; }
            if(page==Page.HOME)for(int i=0;i<homeNewsBounds.length;i++)if(homeNewsBounds[i].contains(p)&&i<newsPosts.size()){playUiSound(100);selectedNews=i;articleScroll=0;articleOpenedAt=time;repaint();return;}
            if(page==Page.NEWS&&newsComposeBounds.contains(p)){playUiSound(96);newsComposeOpen=true;newsField=0;requestFocusInWindow();repaint();return;}
            if(page==Page.NEWS)for(int i=0;i<newsBounds.length;i++)if(newsBounds[i].contains(p)&&newsFirstVisible+i<newsPosts.size()){playUiSound(100);selectedNews=newsFirstVisible+i;articleScroll=0;articleOpenedAt=time;repaint();return;}
            if(page==Page.ADMIN){if(adminTargetBounds.contains(p)){adminField=0;requestFocusInWindow();return;}if(adminCommandBounds.contains(p)){adminField=1;requestFocusInWindow();return;}if(adminGrantBounds.contains(p)){adminAccessChange(true);return;}if(adminRevokeBounds.contains(p)){if(confirmed("revoke"))adminAccessChange(false);return;}if(adminBanBounds.contains(p)){if(confirmed("ban"))queueAdminCommand("ban "+adminTargetDraft.strip());return;}if(adminUnbanBounds.contains(p)){queueAdminCommand("pardon "+adminTargetDraft.strip());return;}if(adminExecuteBounds.contains(p)){queueAdminCommand(adminCommandDraft);return;}}
            if(page==Page.PACK&&packVerifying&&verifyBounds.contains(p)){cancelPackWork();return;}
            if(page==Page.PACK&&(installPackBounds.contains(p)||verifyBounds.contains(p))){playUiSound(88);verifyModpack();return;}
            if(page==Page.PACK&&folderBounds.contains(p)){playUiSound(118);openModpackFolder();return;}
            if(page==Page.GAME&&chatInputBounds.contains(p)){chatFocused=true;requestFocusInWindow();repaint();return;}
            if(page==Page.GAME&&chatSendBounds.contains(p)){sendChat();return;}
            if (page == Page.SETTINGS) {
                for(int i=0;i<settingBounds.length;i++)if(settingBounds[i].contains(p)){if(i==0){autoUpdate=!autoUpdate;preferences.putBoolean("autoUpdate",autoUpdate);}else if(i==1){autoConnect=!autoConnect;preferences.putBoolean("autoConnect",autoConnect);}else gameOptions.setFullscreen(!gameOptions.fullscreen());playUiSound(105+i*9);repaint();return;}
                if(vsyncBounds.contains(p)){gameOptions.setVsync(!gameOptions.vsync());playUiSound(111);repaint();return;}
                if(ramMinusBounds.contains(p)){step(()->gameOptions.setRamGb(gameOptions.ramGb()-1));return;}if(ramPlusBounds.contains(p)){step(()->gameOptions.setRamGb(gameOptions.ramGb()+1));return;}
                if(renderMinusBounds.contains(p)){step(()->gameOptions.setRenderDistance(gameOptions.renderDistance()-2));return;}if(renderPlusBounds.contains(p)){step(()->gameOptions.setRenderDistance(gameOptions.renderDistance()+2));return;}
                if(simulationMinusBounds.contains(p)){step(()->gameOptions.setSimulationDistance(gameOptions.simulationDistance()-1));return;}if(simulationPlusBounds.contains(p)){step(()->gameOptions.setSimulationDistance(gameOptions.simulationDistance()+1));return;}
                if(fpsMinusBounds.contains(p)){step(()->gameOptions.setMaxFps(gameOptions.maxFps()-10));return;}if(fpsPlusBounds.contains(p)){step(()->gameOptions.setMaxFps(gameOptions.maxFps()+10));return;}
                if(guiMinusBounds.contains(p)){step(()->gameOptions.setGuiScale(gameOptions.guiScale()-1));return;}if(guiPlusBounds.contains(p)){step(()->gameOptions.setGuiScale(gameOptions.guiScale()+1));return;}
                for(int i=0;i<settingsFolderBounds.length;i++)if(settingsFolderBounds[i].contains(p)){playUiSound(118);openSettingsFolder(i);return;}
                if(optionsFileBounds.contains(p)){playUiSound(118);openOptionsFile();return;}if(configFolderBounds.contains(p)){playUiSound(118);openFolderKind("config");return;}
                
            }
            repaint();
        }
        private void step(Runnable change){change.run();playUiSound(120);repaint();}
        /** The HOME hologram opens on the MAP channel facing the same way, then flies to the ground the player knows. */
        private void openMapFromHome(){
            navigate(Page.MAP);mapGlobe.stop();mapGlobe.yaw=homeGlobe.yaw;mapGlobe.pitch=homeGlobe.pitch;mapGlobe.zoom=1;
            PlanetSurvey.Snapshot s=survey.current();if(!s.empty())mapGlobe.flyTo(s.centreX(),s.centreZ(),1.8);
        }
        private void zoomMap(double factor){mapGlobe.flyTo(mapGlobe.yaw,PlanetSurvey.MIN+(mapGlobe.pitch/Math.PI+.5)*PlanetSurvey.SPAN,Math.max(.8,Math.min(14,mapGlobe.zoom*factor)));}
        private void centreMap(){PlanetSurvey.Snapshot s=survey.current();mapGlobe.flyTo(s.centreX(),s.centreZ(),s.empty()?1:1.8);}
        /** Arrows turn the MAP globe, + and - zoom, C or Home flies back to the surveyed ground. */
        private boolean mapKey(int key){
            double turn=PlanetSurvey.SPAN/24.0/mapGlobe.zoom,tilt=.14/mapGlobe.zoom;
            switch(key){
                case KeyEvent.VK_LEFT->mapGlobe.nudge(-turn,0);
                case KeyEvent.VK_RIGHT->mapGlobe.nudge(turn,0);
                case KeyEvent.VK_UP->mapGlobe.nudge(0,-tilt);
                case KeyEvent.VK_DOWN->mapGlobe.nudge(0,tilt);
                case KeyEvent.VK_ADD,KeyEvent.VK_PLUS,KeyEvent.VK_EQUALS->zoomMap(1.6);
                case KeyEvent.VK_SUBTRACT,KeyEvent.VK_MINUS->zoomMap(1/1.6);
                case KeyEvent.VK_C,KeyEvent.VK_HOME->centreMap();
                default->{return false;}
            }
            repaint();return true;
        }
        private boolean mapClick(Point p,int clicks){
            if(mapZoomInBounds.contains(p)||mapZoomOutBounds.contains(p)){playUiSound(mapZoomInBounds.contains(p)?124:108);zoomMap(mapZoomInBounds.contains(p)?1.8:1/1.8);return true;}
            if(mapCentreBounds.contains(p)){playUiSound(116);centreMap();return true;}
            if(surveyWorldBounds.contains(p)){playUiSound(101);survey.next();mapCentredOn="";return true;}
            List<PlanetSurvey.Waypoint> marks=survey.current().waypoints();
            for(int i=0;i<waypointBounds.length;i++)if(waypointBounds[i].contains(p)&&waypointScroll+i<marks.size()){PlanetSurvey.Waypoint m=marks.get(waypointScroll+i);playUiSound(110+i*3);mapGlobe.flyTo(m.x(),m.z(),Math.max(4,mapGlobe.zoom));return true;}
            if(clicks==2&&mapViewBounds.contains(p)){double[] at=mapGlobe.pick(p.x,p.y);if(at!=null){playUiSound(118);mapGlobe.flyTo(at[0],at[1],Math.min(14,mapGlobe.zoom*2.2));}return true;}
            return false;
        }
        private void setUiVolume(double value){double v=Math.max(0,Math.min(1,value));sound.setVolume(v);preferences.putBoolean("uiMuted",v<=.001);if(v>.001)preferences.putDouble("uiVolume",v);repaint();}

        @Override public void mouseMoved(MouseEvent e) {
            mouse=e.getPoint();pointerInside=true;
            hoverNews=-1;if(page==Page.NEWS)for(int i=0;i<newsBounds.length;i++)if(hot(newsBounds[i]))hoverNews=i;
            int edge=launchOverlayActive||bootActive?0:edgeMask(mouse);if(edge!=0){setCursor(Cursor.getPredefinedCursor(cursorFor(edge)));repaint();return;}
            // Every control registers itself when painted, so "is anything clickable under the pointer" is one scan.
            boolean clickable=bootActive||hoverAnim.keySet().stream().anyMatch(this::hot);
            setCursor(Cursor.getPredefinedCursor(clickable?Cursor.HAND_CURSOR:onMapGlobe(mouse)?Cursor.CROSSHAIR_CURSOR:Cursor.DEFAULT_CURSOR));repaint();
        }
        /** On the MAP page's glass, away from its keys and the widened sidebar: there a press grabs the globe. */
        private boolean onMapGlobe(Point p){return page==Page.MAP&&!overlayOpen()&&mapViewBounds.contains(p)&&!overSidebar(p)&&!mapZoomInBounds.contains(p)&&!mapZoomOutBounds.contains(p)&&!mapCentreBounds.contains(p);}
        @Override public void mousePressed(MouseEvent e) {
            requestFocusInWindow(); // keys (channels, Enter, Esc) follow the last click into the window
            if(onMapGlobe(e.getPoint())&&edgeMask(e.getPoint())==0){globeDragAt=e.getPoint();mapGlobe.stop();return;}
            pressedControl=controlAt(e.getPoint());
            if(volumeBounds.contains(e.getPoint())){volumeDragging=true;setUiVolumeFromMouse(e.getX());return;}
            windowActionStart=e.getLocationOnScreen();windowStartBounds=frame.getBounds();resizeMask=launchOverlayActive||bootActive?0:edgeMask(e.getPoint());
            resizingWindow=resizeMask!=0;draggingWindow=!resizingWindow&&e.getY()<HEADER&&e.getX()>SIDEBAR&&!closeBounds.contains(e.getPoint())&&!minimizeBounds.contains(e.getPoint())&&!profileBounds.contains(e.getPoint())&&!notificationBounds.contains(e.getPoint());
        }
        @Override public void mouseReleased(MouseEvent e) {if(globeDragAt!=null){mapGlobe.release();globeDragAt=null;}pressedControl="";volumeDragging=false;draggingWindow=false;resizingWindow=false;resizeMask=0;windowActionStart=null;windowStartBounds=null;}
        @Override public void mouseEntered(MouseEvent e) { pointerInside=true; }
        @Override public void mouseExited(MouseEvent e) { pointerInside=false; hoverNews=-1; repaint(); }
        @Override public void mouseDragged(MouseEvent e) {
            mouse=e.getPoint();
            if(globeDragAt!=null){mapGlobe.drag(e.getX()-globeDragAt.x,e.getY()-globeDragAt.y);globeDragAt=e.getPoint();repaint();return;}
            if(volumeDragging){setUiVolumeFromMouse(e.getX());return;}
            if(windowActionStart==null||windowStartBounds==null)return;Point now=e.getLocationOnScreen();int dx=now.x-windowActionStart.x,dy=now.y-windowActionStart.y;
            if(draggingWindow){frame.setLocation(windowStartBounds.x+dx,windowStartBounds.y+dy);return;}
            if(!resizingWindow)return;int x=windowStartBounds.x,y=windowStartBounds.y,w=windowStartBounds.width,h=windowStartBounds.height,minW=frame.getMinimumSize().width,minH=frame.getMinimumSize().height;
            if((resizeMask&1)!=0){x+=dx;w-=dx;}if((resizeMask&2)!=0)w+=dx;if((resizeMask&4)!=0){y+=dy;h-=dy;}if((resizeMask&8)!=0)h+=dy;
            if(w<minW){if((resizeMask&1)!=0)x-=minW-w;w=minW;}if(h<minH){if((resizeMask&4)!=0)y-=minH-h;h=minH;}frame.setBounds(x,y,w,h);
        }

        @Override public void mouseWheelMoved(MouseWheelEvent e){
            if(onMapGlobe(e.getPoint())){mapGlobe.zoomAt(e.getX(),e.getY(),Math.pow(1.2,-e.getPreciseWheelRotation()),.8,14);repaint();return;}
            if(page==Page.MAP&&!overlayOpen()&&e.getX()>mapViewBounds.x+mapViewBounds.width&&e.getY()>mapViewBounds.y){waypointScroll=Math.max(0,waypointScroll+e.getWheelRotation());repaint();return;}
            if(audioBounds.contains(e.getPoint())||volumeBounds.contains(e.getPoint())){setUiVolume(sound.volume()-e.getPreciseWheelRotation()*.06);return;}if(selectedNews>=0){articleScroll=Math.max(0,Math.min(articleMaxScroll,articleScroll+e.getWheelRotation()*3));articleOpenedAt=Math.min(articleOpenedAt,time-4);repaint();return;}if(page==Page.NEWS&&!newsComposeOpen){int max=Math.max(0,(newsPosts.size()-newsVisibleRows)*40);newsScroll=Math.max(0,Math.min(max,newsScroll+e.getWheelRotation()*40));repaint();}}
        private void setUiVolumeFromMouse(int x){int start=volumeBounds.x+4,end=start+Math.max(18,volumeBounds.width-10);setUiVolume((x-start)/(double)Math.max(1,end-start));}
        private String controlAt(Point point){if(playBounds.contains(point))return"play";for(int i=0;i<navBounds.length;i++)if(navBounds[i].contains(point))return"nav"+i;for(Rectangle key:List.of(ramMinusBounds,ramPlusBounds,renderMinusBounds,renderPlusBounds,simulationMinusBounds,simulationPlusBounds,fpsMinusBounds,fpsPlusBounds,guiMinusBounds,guiPlusBounds))if(page==Page.SETTINGS&&key.contains(point))return"key"+System.identityHashCode(key);return"";}

        @Override public void keyTyped(KeyEvent e){char c=e.getKeyChar();if(c<32||c==127)return;if(page==Page.ADMIN){if(adminField==0&&adminTargetDraft.length()<36)adminTargetDraft+=c;else if(adminField==1&&adminCommandDraft.length()<180)adminCommandDraft+=c;repaint();return;}if(newsComposeOpen){if(newsField==0&&newsTitleDraft.length()<100)newsTitleDraft+=c;else if(newsField==1&&newsBodyDraft.length()<5000)newsBodyDraft+=c;repaint();return;}if(!chatFocused||page!=Page.GAME)return;if(chatDraft.length()<180){chatDraft+=c;repaint();}}
        @Override public void keyPressed(KeyEvent e){
            int key=e.getKeyCode();
            if(bootActive&&(key==KeyEvent.VK_ESCAPE||key==KeyEvent.VK_SPACE||key==KeyEvent.VK_ENTER)){skipBoot();return;}
            if(key==KeyEvent.VK_V&&(e.isControlDown()||e.isMetaDown())){pasteClipboard();return;}
            // Channels: F1-F7 always, 1-7 when no text field takes the digits; Enter on HOME is PLAY.
            if(!launchOverlayActive&&!overlayOpen()){
                boolean typing=page==Page.ADMIN||page==Page.GAME&&chatFocused;int channel=key>=KeyEvent.VK_F1&&key<=KeyEvent.VK_F7?key-KeyEvent.VK_F1:!typing&&key>=KeyEvent.VK_1&&key<=KeyEvent.VK_7?key-KeyEvent.VK_1:-1;
                if(channel>=0){if(!navBounds[channel].isEmpty()&&page.ordinal()!=channel){playUiSound(92+channel*7);navigate(Page.values()[channel]);}return;}
                if(key==KeyEvent.VK_ENTER&&page==Page.HOME){requestGameStart();return;}
                if(page==Page.MAP&&mapKey(key))return;
            }
            if(key==KeyEvent.VK_ESCAPE&&closeTopOverlay())return;
            if(page==Page.ADMIN){if(e.getKeyCode()==KeyEvent.VK_TAB)adminField=1-adminField;else if(e.getKeyCode()==KeyEvent.VK_ENTER&&adminField==1)queueAdminCommand(adminCommandDraft);else if(e.getKeyCode()==KeyEvent.VK_BACK_SPACE){if(adminField==0&&!adminTargetDraft.isEmpty())adminTargetDraft=adminTargetDraft.substring(0,adminTargetDraft.length()-1);else if(adminField==1&&!adminCommandDraft.isEmpty())adminCommandDraft=adminCommandDraft.substring(0,adminCommandDraft.length()-1);}repaint();return;}if(newsComposeOpen){if(e.getKeyCode()==KeyEvent.VK_ESCAPE){newsComposeOpen=false;}else if(e.getKeyCode()==KeyEvent.VK_TAB||e.getKeyCode()==KeyEvent.VK_ENTER){newsField=1-newsField;}else if(e.getKeyCode()==KeyEvent.VK_BACK_SPACE){if(newsField==0&&!newsTitleDraft.isEmpty())newsTitleDraft=newsTitleDraft.substring(0,newsTitleDraft.length()-1);else if(newsField==1&&!newsBodyDraft.isEmpty())newsBodyDraft=newsBodyDraft.substring(0,newsBodyDraft.length()-1);}repaint();return;}if(!chatFocused||page!=Page.GAME)return;if(e.getKeyCode()==KeyEvent.VK_BACK_SPACE&&!chatDraft.isEmpty()){chatDraft=chatDraft.substring(0,chatDraft.length()-1);repaint();}else if(e.getKeyCode()==KeyEvent.VK_ENTER)sendChat();else if(e.getKeyCode()==KeyEvent.VK_ESCAPE){chatFocused=false;repaint();}}
        @Override public void keyReleased(KeyEvent e){}
        private boolean closeTopOverlay(){
            if(launchOverlayActive&&(launchFailed||gameCrashed))dismissLaunchFailure();else if(selectedNews>=0)selectedNews=-1;else if(notificationsOpen)notificationsOpen=false;else if(profileOpen)profileOpen=false;else return false;
            repaint();return true;
        }
        /** Paste goes through keyTyped, so the focused field's length limits and control-character filter still apply. */
        private void pasteClipboard(){
            try{Object data=Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);if(!(data instanceof String text))return;
                for(char c:text.substring(0,Math.min(500,text.length())).toCharArray())keyTyped(new KeyEvent(this,KeyEvent.KEY_TYPED,System.currentTimeMillis(),0,KeyEvent.VK_UNDEFINED,c));
            }catch(Exception ignored){}
        }

        // ================================================================ account and backend

        private void authenticateCachedAccount(){
            if(accountSession==null||!accountRefreshInProgress.compareAndSet(false,true))return;
            Thread.startVirtualThread(()->{try{MicrosoftAccountService.Session fresh=accountService.refresh();if(fresh!=null){accountSession=fresh;loadPlayerHead(fresh);SwingUtilities.invokeLater(this::repaint);if(apiClient.configured()){try{apiClient.authenticate(fresh);String ws=apiClient.webSocketUrl();if(ws!=null)hub.connect(ws);}catch(Exception backendError){SwingUtilities.invokeLater(()->{accountNotice="Signed in with Microsoft, but the Erdvyn service said: "+shortError(backendError);repaint();});}}}}catch(Exception ex){SwingUtilities.invokeLater(()->{accountNotice="ACCOUNT REFRESH: "+loginError(ex);repaint();});}finally{accountRefreshInProgress.set(false);lastBackendPollMillis=0;}});
        }

        private void pollBackendIfDue(){
            if(!apiClient.configured()||backendPollInProgress.get())return;
            long now=System.currentTimeMillis();
            boolean statusDue=now-lastBackendPollMillis>=30_000;
            boolean newsDue=page==Page.NEWS&&now-lastNewsFetchMillis>=300_000;
            boolean adminDue=page==Page.ADMIN&&apiClient.current()!=null&&apiClient.current().account().admin()&&now-lastAdminFetchMillis>=30_000;
            if(!statusDue&&!newsDue&&!adminDue||!backendPollInProgress.compareAndSet(false,true))return;
            if(statusDue)lastBackendPollMillis=now;
            if(newsDue)lastNewsFetchMillis=now;
            if(adminDue)lastAdminFetchMillis=now;
            Thread.startVirtualThread(()->{
                try{
                    ErdvynApiClient.Status fetchedStatus=null;List<ErdvynApiClient.NewsPost> fetchedPosts=null;List<ErdvynApiClient.AdminAccount> fetchedAdmins=null;
                    try{if(statusDue)fetchedStatus=apiClient.fetchStatus();}catch(Exception ignored){}
                    try{if(newsDue)fetchedPosts=apiClient.fetchNews();}catch(Exception ignored){}
                    try{if(adminDue)fetchedAdmins=apiClient.fetchAdmins();}catch(Exception ignored){}
                    // The chat bus drops when the network does: reconnect on the status cadence while signed in.
                    if(statusDue&&apiClient.current()!=null&&!hub.connected())hub.connect(apiClient.webSocketUrl());
                    ErdvynApiClient.Status status=fetchedStatus;List<ErdvynApiClient.NewsPost> posts=fetchedPosts;List<ErdvynApiClient.AdminAccount> admins=fetchedAdmins;
                    SwingUtilities.invokeLater(()->{
                        if(status!=null){apiStatus=status;if(status.online()&&!status.players().isEmpty()){onlinePlayers.clear();onlinePlayers.addAll(status.players());}}
                        if(posts!=null){newsPosts.clear();newsPosts.addAll(posts);}
                        if(admins!=null){adminAccounts.clear();adminAccounts.addAll(admins);loadAdminHeads(admins);}
                        repaint();
                    });
                }finally{backendPollInProgress.set(false);}
            });
        }

        private void loadAdminHeads(List<ErdvynApiClient.AdminAccount> admins){for(ErdvynApiClient.AdminAccount admin:admins)if(!adminHeads.containsKey(admin.uuid()))Thread.startVirtualThread(()->{try{BufferedImage head=skinService.head(admin.uuid());if(head!=null)SwingUtilities.invokeLater(()->{adminHeads.put(admin.uuid(),head);repaint();});}catch(Exception ignored){}});}

        private void requestGameStart(){
            if(gameLaunching||accountLoginInProgress)return;
            // A manual audit is writing the same files: two writers would race on the same temp downloads.
            if(packVerifying){playUiSound(70);launchNotice="FILE AUDIT RUNNING";notify("A file audit is running; press PLAY when it finishes.");repaint();return;}
            if(accountSession==null){launchAfterLogin=true;profileOpen=true;beginMicrosoftLogin();return;}
            playUiSound(64);
            packStatus="Checking the pack before starting";
            LauncherLog.write("PLAY requested by "+accountSession.name()+"; instance="+LauncherPaths.managedInstance().toAbsolutePath().normalize());
            launchGameAsync();
        }

        private void launchGameAsync(){
            if(gameLaunching)return;gameLaunching=true;beginLaunchOverlay();playBootSound(0);accountNotice="STARTING MINECRAFT...";AtomicBoolean cancel=packCancel=new AtomicBoolean();packBytesTotal=0;launchingGame=null;
            launchThread=Thread.startVirtualThread(()->{Process[] game={null};try{
                LauncherLog.write("Launch pipeline started");
                setLaunchStage("Checking Java and NeoForge",.08,"[EXEC] "+"Looking for Java 21 and NeoForge");
                installService.ensureInstalled(line->{LauncherLog.write(line);setLaunchStage("Preparing Minecraft",.16,"[OK] "+line);SwingUtilities.invokeLater(()->{addPackLog(line);repaint();});});
                checkLaunchCancel(cancel);
                setLaunchStage("Comparing the pack with the server",.20,"[EXEC] "+"Asked the server for its file list");
                String checking="Comparing the pack with the server";SwingUtilities.invokeLater(()->{packVerifying=true;packProgress=0;packLog.clear();packStatus=checking;});
                // Launch-time audit trusts the hash recorded for an unchanged file (same size and timestamp); VERIFY FILES re-reads everything.
                PackService.Result verifiedPack=packService.verifyAndRepair(this::onPackLaunchProgress,cancel,true);
                SwingUtilities.invokeLater(()->{packVerifying=false;packBytesTotal=0;});if(verifiedPack.failed()>0)throw new IllegalStateException("Modpack verification failed; launch was blocked.",verifiedPack.firstError());SwingUtilities.invokeLater(()->packInstalled=true);
                LauncherLog.write("Pack verified version="+verifiedPack.version()+" downloaded="+verifiedPack.downloaded());
                setLaunchStage("The pack matches the server",.66,"[OK] "+verifiedPack.version()+" / "+verifiedPack.downloaded()+" "+"files downloaded");
                checkLaunchCancel(cancel);
                setLaunchStage("Refreshing the Microsoft sign-in",.70,"[EXEC] "+"Checking the account");MicrosoftAccountService.Session fresh=accountService.refresh();if(fresh==null)throw new IllegalStateException("Microsoft account is not linked.");accountSession=fresh;String ticket=null;
                setLaunchStage("Signing in to Erdvyn",.74,"[EXEC] "+"Asked Erdvyn for a session key");if(apiClient.configured()){if(!ErdvynApiClient.sameAccount(apiClient.current(),fresh))apiClient.authenticate(fresh);String ws=apiClient.webSocketUrl();if(ws!=null)hub.connect(ws);String manifestSha=PackService.activeManifestSha256();try{ticket=apiClient.createGameTicket(manifestSha);}catch(Exception expired){/* stored Erdvyn token expired or revoked: sign in once more, then retry */apiClient.authenticate(fresh);ticket=apiClient.createGameTicket(manifestSha);}}
                checkLaunchCancel(cancel);
                setLaunchStage("Starting Minecraft",.80,"[EXEC] Java with "+gameOptions.ramGb()+" GB, NeoForge 21.1.243");LauncherLog.write("Starting JVM as "+fresh.name());
                Process minecraft=game[0]=launchService.launch(fresh,gameOptions.ramGb(),autoConnect,ticket,this::onMinecraftLaunchSignal);launchingGame=minecraft;long pid=minecraft.pid();SwingUtilities.invokeLater(()->launchPid=pid);
                checkLaunchCancel(cancel);
                setLaunchStage("Minecraft is opening",.88,"[WAIT] "+"Waiting for the game window");
                launchService.awaitReady(minecraft,this::onMinecraftLaunchSignal);
                checkLaunchCancel(cancel);gameReady=true;
                LauncherLog.write("Minecraft process reported started");
                setLaunchStage("Minecraft is open, the launcher steps aside",1,"[OK] "+"The game window is open");Thread.sleep(850);SwingUtilities.invokeLater(frame::hideWhileGameRuns);
                minecraft.onExit().thenAccept(exited->SwingUtilities.invokeLater(()->onGameExited(exited.exitValue())));
            }catch(Exception ex){
                // A cancel shows up as whatever the interrupted step threw (CancellationException, InterruptedException, a closed channel): the flag decides.
                if(cancel.get()&&!gameReady){LauncherLog.write("Launch cancelled by the user");Process started=game[0];if(started!=null&&started.isAlive()){started.descendants().forEach(ProcessHandle::destroy);started.destroy();}
                    SwingUtilities.invokeLater(()->{packVerifying=false;packBytesTotal=0;packInstalled=packService.isInstalled();gameLaunching=false;launchOverlayActive=false;launchingGame=null;launchNotice="CANCELLED";notify("Launch cancelled. PLAY resumes where it stopped.");repaint();});return;}
                LauncherLog.write("LAUNCH ERROR: "+rawError(ex));
                // A mod-loading crash kills the game before its window is ready: that is a crash with a report, not a launcher error.
                if(game[0]!=null&&!game[0].isAlive()){int code=game[0].exitValue();SwingUtilities.invokeLater(()->{packVerifying=false;launchingGame=null;if(code==0)dismissLaunchFailure();else showGameCrash(code);});return;}
                String hint=UiMessages.hint(ex);SwingUtilities.invokeLater(()->{gameLaunching=false;launchFailed=true;launchOverlayActive=true;packVerifying=false;packBytesTotal=0;launchingGame=null;String advice=hint==null?"":hint;launchAdvice=advice;accountNotice="LAUNCH ERROR: "+(advice.isBlank()?shortError(ex):advice);packStatus=accountNotice;launchStatus=advice.isBlank()?accountNotice:"LAUNCH HALTED";appendLaunchTrace("[FAIL] "+shortError(ex));repaint();});}});
        }
        private static void checkLaunchCancel(AtomicBoolean cancel){if(cancel.get())throw new CancellationException("Launch cancelled");}

        private void beginMicrosoftLogin(){
            if(accountLoginInProgress)return;playUiSound(82);accountLoginInProgress=true;deviceCode="";deviceUrl="";profileOpen=true;accountNotice="PREPARING MICROSOFT SIGN-IN...";repaint();
            int generation=++loginGeneration; // a cancelled sign-in may still finish in the background: its result is ignored
            loginThread=Thread.startVirtualThread(()->{try{
                MicrosoftAccountService.Session session=accountService.login(code->{
                    String user=code.getUserCode(),url=code.getVerificationUri(),direct=code.getDirectVerificationUri();
                    SwingUtilities.invokeLater(()->{if(generation!=loginGeneration)return;deviceCode=user;deviceUrl=url;copyToClipboard(user);accountNotice="CODE COPIED TO CLIPBOARD. CONFIRM IN YOUR BROWSER.";repaint();});
                    try{Desktop.getDesktop().browse(URI.create(direct));}catch(Exception ignored){}
                });
                if(generation!=loginGeneration)return;
                accountSession=session;loadPlayerHead(session);boolean erdvynLinked=false;String backendFailure="";
                if(apiClient.configured()){try{apiClient.authenticate(session);String ws=apiClient.webSocketUrl();if(ws!=null)hub.connect(ws);erdvynLinked=true;}catch(Exception backendError){backendFailure=shortError(backendError);}}
                boolean linked=erdvynLinked;String backendMessage=backendFailure;
                SwingUtilities.invokeLater(()->{if(generation!=loginGeneration)return;accountLoginInProgress=false;deviceCode="";deviceUrl="";accountNotice=linked?"Signed in, Erdvyn account linked":backendMessage.isBlank()?"Signed in with Microsoft":"Signed in with Microsoft, but the Erdvyn service said: "+backendMessage;notify("Minecraft account linked: "+session.name());profileOpen=false;
                    // The launch pipeline signs in to Erdvyn again if needed and reports a failure in its own trace.
                    if(launchAfterLogin){launchAfterLogin=false;requestGameStart();}repaint();});
            }catch(Exception ex){SwingUtilities.invokeLater(()->{if(generation!=loginGeneration)return;accountLoginInProgress=false;launchAfterLogin=false;deviceCode="";deviceUrl="";accountNotice="SIGN-IN ERROR: "+loginError(ex);notify(accountNotice);repaint();});}});
        }
        /** Abandons a waiting device-code sign-in: the worker is interrupted out of its poll and any late result is ignored. */
        private void cancelMicrosoftLogin(){if(!accountLoginInProgress)return;loginGeneration++;Thread worker=loginThread;if(worker!=null)worker.interrupt();accountLoginInProgress=false;launchAfterLogin=false;deviceCode="";deviceUrl="";accountNotice="SIGN-IN CANCELLED";playUiSound(70);repaint();}
        private void openDeviceUrl(){if(deviceUrl.isBlank())return;try{Desktop.getDesktop().browse(URI.create(deviceUrl+(deviceCode.isBlank()?"":"?otc="+deviceCode)));}catch(Exception ignored){}copyToClipboard(deviceCode);}
        private static void copyToClipboard(String text){if(text==null||text.isBlank())return;try{Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text),null);}catch(Exception ignored){}}
        /** Forgets the DPAPI-stored Microsoft tokens and the Erdvyn session; the next PLAY asks for a sign-in again. */
        private void signOut(){playUiSound(90);accountService.logout();apiClient.signOut();hub.close();accountSession=null;playerHead=null;profileOpen=false;accountNotice="SIGNED OUT";if(page==Page.ADMIN)navigate(Page.HOME);repaint();}
        private void beginErdvynLogin(){openAccountPage("/login?source=launcher",96,"Secure account page opened.");}
        private void openAccountPage(String path,double pitch,String success){playUiSound(pitch);String accountUrl=LauncherConfig.accountUrl();if(accountUrl.isBlank()){accountNotice="Erdvyn account server URL required.";repaint();return;}try{Desktop.getDesktop().browse(URI.create(accountUrl.replaceAll("/+$","")+path));accountNotice=success;}catch(Exception ex){accountNotice=ex.getMessage();}repaint();}

        private void deleteNews(long id){
            if(newsDeleteInProgress)return;
            if(newsDeleteArmed!=id){newsDeleteArmed=id;return;}
            newsDeleteInProgress=true;newsNotice="Deleting dispatch...";
            Thread.startVirtualThread(()->{try{apiClient.deleteNews(id);List<ErdvynApiClient.NewsPost> posts=apiClient.fetchNews();SwingUtilities.invokeLater(()->{newsPosts.clear();newsPosts.addAll(posts);selectedNews=-1;newsDeleteArmed=-1;newsDeleteInProgress=false;newsNotice="Dispatch deleted.";repaint();});}catch(Exception ex){SwingUtilities.invokeLater(()->{newsDeleteArmed=-1;newsDeleteInProgress=false;newsNotice="DELETE ERROR: "+shortError(ex);repaint();});}});
        }

        private void publishNews(){
            if(newsPublishInProgress)return;String title=newsTitleDraft.strip(),body=newsBodyDraft.strip();if(title.length()<3||body.length()<3){newsNotice="Title and body must be at least 3 characters.";return;}newsPublishInProgress=true;newsNotice="Publishing dispatch...";
            Thread.startVirtualThread(()->{try{apiClient.publishNews(title,body);List<ErdvynApiClient.NewsPost> posts=apiClient.fetchNews();SwingUtilities.invokeLater(()->{newsPosts.clear();newsPosts.addAll(posts);newsTitleDraft="";newsBodyDraft="";newsComposeOpen=false;newsPublishInProgress=false;newsNotice="Dispatch published.";repaint();});}catch(Exception ex){SwingUtilities.invokeLater(()->{newsPublishInProgress=false;newsNotice="PUBLISH ERROR: "+shortError(ex);repaint();});}});
        }

        private void adminAccessChange(boolean grant){
            if(adminActionInProgress)return;String target=adminTargetDraft.strip();if(target.isBlank()){adminNotice="ERROR: Player name or UUID is required.";repaint();return;}adminActionInProgress=true;adminNotice="VERIFYING AUTHORITY...";repaint();
            Thread.startVirtualThread(()->{try{String name=grant?apiClient.grantAdmin(target):apiClient.revokeAdmin(target);SwingUtilities.invokeLater(()->{adminActionInProgress=false;lastBackendPollMillis=0;lastAdminFetchMillis=0;adminNotice=(grant?"ADMIN GRANTED: ":"ADMIN REVOKED: ")+name;repaint();});}catch(Exception ex){SwingUtilities.invokeLater(()->{adminActionInProgress=false;adminNotice="ERROR: "+shortError(ex);repaint();});}});
        }

        private void queueAdminCommand(String raw){
            if(adminActionInProgress)return;String command=raw.strip();if(command.isBlank()){adminNotice="ERROR: Command or player name is required.";repaint();return;}adminActionInProgress=true;adminNotice="QUEUING SECURE COMMAND...";repaint();
            Thread.startVirtualThread(()->{try{long id=apiClient.queueAdminCommand(command);SwingUtilities.invokeLater(()->{adminActionInProgress=false;adminNotice="Command sent, number "+id;adminCommandDraft="";repaint();});}catch(Exception ex){SwingUtilities.invokeLater(()->{adminActionInProgress=false;adminNotice="ERROR: "+shortError(ex);repaint();});}});
        }

        private void verifyModpack(){
            // The launch pipeline audits the same files: never run a second audit beside it.
            if(packVerifying||gameLaunching)return;packVerifying=true;packProgress=0;packStatus="";packBytesTotal=0;packLog.clear();addPackLog("> PREPARING FILE MANIFEST");AtomicBoolean cancel=packCancel=new AtomicBoolean();repaint();
            Thread.startVirtualThread(()->{try{PackService.Result result=packService.verifyAndRepair(progress->SwingUtilities.invokeLater(()->{packProgress=progress.value();trackPackBytes(progress);if(!progress.tick())addPackLog(progress.line());repaint();}),cancel);String hint=UiMessages.hint(result.firstError());SwingUtilities.invokeLater(()->{packVerifying=false;packBytesTotal=0;packProgress=1;packInstalled=result.failed()==0;packStatus=result.failed()==0?String.format(Locale.ROOT,"%d verified / %d downloaded / %d player settings kept",result.verified(),result.downloaded(),result.kept()):String.format(Locale.ROOT,"%d errors / launch blocked",result.failed());if(result.failed()==0)notify(result.downloaded()>0?String.format(Locale.ROOT,"Pack ready: %d files downloaded.",result.downloaded()):"Pack verified; player settings were kept.");else if(hint!=null){String advice=hint;addPackLog("> "+advice);notify(advice);}refreshPackSummary();repaint();});}
            catch(CancellationException stopped){SwingUtilities.invokeLater(this::onPackCancelled);}
            catch(Exception ex){LauncherLog.write("PACK VERIFY ERROR: "+rawError(ex));String advice=actionable(ex);SwingUtilities.invokeLater(()->{packVerifying=false;packBytesTotal=0;packStatus="ERROR: "+advice;addPackLog("[FAIL] "+shortError(ex));notify(packStatus);repaint();});}});
        }
        private void openModpackFolder(){openFolderKind("");}
        private void openSettingsFolder(int index){String kind=switch(index){case 1->"mods";case 2->"resourcepacks";case 3->"shaderpacks";default->"";};openFolderKind(kind);}
        private void openOptionsFile(){try{Path options=LauncherPaths.gameDirectory().resolve("options.txt");if(!Files.exists(options))Files.writeString(options,"");Desktop.getDesktop().open(options.toFile());}catch(Exception ex){packStatus="OPTIONS FILE ERROR: "+shortError(ex);}repaint();}
        private void openFolderKind(String kind){try{Path root=kind.isBlank()?LauncherPaths.gameDirectory():LauncherPaths.folder(kind);Files.createDirectories(root);Desktop.getDesktop().open(root.toFile());}catch(Exception ex){packStatus="FOLDER ERROR: "+shortError(ex);}repaint();}
        /** Every cause with its type, for LauncherLog: the UI shows the short or actionable form. */
        private static String rawError(Throwable error){StringBuilder text=new StringBuilder();for(Throwable t=error;t!=null;t=t.getCause()==t?null:t.getCause())text.append(text.isEmpty()?"":" <- ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());return text.toString();}
        private static String shortError(Throwable error){Throwable current=error;while(current.getCause()!=null&&current.getCause()!=current)current=current.getCause();String text=current.getMessage();if(text==null||text.isBlank())text=current.getClass().getSimpleName();return text.length()>110?text.substring(0,107)+"...":text;}
        private String loginError(Throwable error){LauncherLog.write("SIGN-IN ERROR: "+rawError(error));return actionable(error);}
        private void loadPlayerHead(MicrosoftAccountService.Session session){try{BufferedImage loaded=skinService.head(session);if(loaded!=null)SwingUtilities.invokeLater(()->{playerHead=loaded;repaint();});}catch(Exception ignored){}}
        private void pollLauncherUpdateIfDue(){long now=System.currentTimeMillis();if(autoUpdate&&launcherInstaller==null&&!launcherUpdateCheckInProgress.get()&&now-lastLauncherUpdateCheckMillis>=300_000L)checkLauncherUpdateAsync();}
        private void checkLauncherUpdateAsync(){if(!launcherUpdateCheckInProgress.compareAndSet(false,true))return;lastLauncherUpdateCheckMillis=System.currentTimeMillis();Thread.startVirtualThread(()->{try{LauncherUpdateService.Update found=launcherUpdateService.check();if(found==null)return;Path downloaded=launcherUpdateService.download(found);SwingUtilities.invokeLater(()->{launcherUpdate=found;launcherInstaller=downloaded;lastLauncherUpdateError="";notify(String.format(Locale.ROOT,"Launcher %s was downloaded from GitHub. Install it from notifications.",found.version()));repaint();});}catch(Exception ex){String error=shortError(ex);SwingUtilities.invokeLater(()->{if(!error.equals(lastLauncherUpdateError)){lastLauncherUpdateError=error;notify("Launcher update check failed: "+error);}repaint();});}finally{launcherUpdateCheckInProgress.set(false);}});}
        private void launchPreparedUpdate(){if(launcherInstaller==null||launcherUpdate==null||!Files.isRegularFile(launcherInstaller))return;try{
                // Re-hash right before running: the cached installer sits in a user-writable folder for minutes or days.
                if(!launcherUpdate.sha256().equalsIgnoreCase(PackService.sha256(launcherInstaller))){Files.deleteIfExists(launcherInstaller);launcherInstaller=null;throw new java.io.IOException("Update file changed on disk; it will be downloaded again.");}
                Desktop.getDesktop().open(launcherInstaller.toFile());frame.shutdownAndExit();}catch(Exception ex){accountNotice="UPDATE ERROR: "+shortError(ex);repaint();}}
        private void sendChat(){String message=chatDraft.strip();if(message.isEmpty())return;playUiSound(112);if(hub.connected())hub.sendChat(message);else addChat(ChatLine.system("SYSTEM","Not connected to the chat server.",LocalTime.now()));chatDraft="";repaint();}

        void shutdown(){timer.stop();hub.close();serverStatus.close();sound.shutdown();}
        private void onServerStatus(MinecraftServerStatus.Snapshot snapshot){SwingUtilities.invokeLater(()->{serverSnapshot=snapshot;if(!snapshot.sample().isEmpty()&&!hub.connected()){onlinePlayers.clear();onlinePlayers.addAll(snapshot.sample());}repaint();});}
        private void onHubEvent(HubEvent event){SwingUtilities.invokeLater(()->{if(event.kind.equals("chat"))addChat(new ChatLine(event.author,event.value,LocalTime.now()));else if(event.kind.equals("players")){onlinePlayers.clear();if(!event.value.isBlank())onlinePlayers.addAll(Arrays.stream(event.value.split(",")).map(String::strip).filter(s->!s.isEmpty()).limit(ErdvynApiClient.MAX_PLAYERS).toList());}else if(event.kind.equals("status"))addChat(ChatLine.system("SYSTEM",event.value,LocalTime.now()));repaint();});}

        // ================================================================ geometry and text helpers

        /** HOME: the left column's width, the lower-third band's height, and the globe monitor that fills the rest. */
        private static int homeLeftW(int w){return Math.min(520,(int)((w-28-(SIDEBAR+32))*.42));}
        private static int lowerThirdH(int h){return h>=700?136:118;}
        private static Rectangle monitorBounds(int w,int h){int x=SIDEBAR+32+homeLeftW(w)+30,top=100,bottom=h-FOOTER-14-lowerThirdH(h)-18;return new Rectangle(x,top,Math.max(160,w-28-x),Math.max(120,bottom-top));}
        private int contentLeft(){return SIDEBAR+32;}
        private int contentBottom(){return getHeight()-FOOTER-14;}
        private boolean overSidebar(Point p){return p.x<SIDEBAR+RAIL_EXPAND*sidebarExpand;}
        private int edgeMask(Point p){int margin=8,mask=0;if(p.x<=margin)mask|=1;if(p.x>=getWidth()-margin)mask|=2;if(p.y<=margin)mask|=4;if(p.y>=getHeight()-margin)mask|=8;return mask;}
        private int cursorFor(int mask){return switch(mask){case 1,2->Cursor.E_RESIZE_CURSOR;case 4,8->Cursor.N_RESIZE_CURSOR;case 5,10->Cursor.NW_RESIZE_CURSOR;case 6,9->Cursor.NE_RESIZE_CURSOR;default->Cursor.DEFAULT_CURSOR;};}

        /** Word wrap by pixel width that keeps paragraph breaks and splits words wider than a line (links). */
        private static List<String> wrapLines(Font font,String text,int maxWidth){
            List<String> lines=new ArrayList<>();
            for(String paragraph:(text==null?"":text).split("\\R",-1)){
                StringBuilder line=new StringBuilder();
                for(String word:paragraph.split(" ")){
                    String candidate=line.isEmpty()?word:line+" "+word;
                    if(Retro.width(font,candidate)<=maxWidth){line=new StringBuilder(candidate);continue;}
                    if(Retro.width(font,word)<=maxWidth){lines.add(line.toString());line=new StringBuilder(word);continue;}
                    // Wider than a whole line: fill the current line, then break by characters.
                    String rest=candidate;while(rest.length()>1&&Retro.width(font,rest)>maxWidth){int cut=1;while(cut<rest.length()-1&&Retro.width(font,rest.substring(0,cut+1))<=maxWidth)cut++;lines.add(rest.substring(0,cut));rest=rest.substring(cut);}
                    line=new StringBuilder(rest);
                }
                lines.add(line.toString());
            }
            while(lines.size()>1&&lines.get(lines.size()-1).isBlank())lines.remove(lines.size()-1);
            return lines;
        }

        private static void paintBellIcon(Graphics2D g,int cx,int cy,Color color){g.setColor(color);g.drawLine(cx-6,cy+5,cx+6,cy+5);g.drawLine(cx-6,cy+5,cx-4,cy+2);g.drawLine(cx+6,cy+5,cx+4,cy+2);g.drawLine(cx-4,cy+2,cx-4,cy-5);g.drawLine(cx+4,cy+2,cx+4,cy-5);g.drawLine(cx-4,cy-5,cx-2,cy-8);g.drawLine(cx+4,cy-5,cx+2,cy-8);g.drawLine(cx-2,cy-8,cx+2,cy-8);g.fillRect(cx-1,cy+8,3,2);}

        private void paintNavIcon(Graphics2D g, int icon, int x, int y, Color color) {
            Stroke previous=g.getStroke();g.setColor(color); g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (icon) {
                case 6 -> { Path2D shield=new Path2D.Double();shield.moveTo(x,y-11);shield.lineTo(x+9,y-7);shield.lineTo(x+8,y+3);shield.quadTo(x+5,y+9,x,y+12);shield.quadTo(x-5,y+9,x-8,y+3);shield.lineTo(x-9,y-7);shield.closePath();g.draw(shield);g.drawLine(x-4,y,x-1,y+4);g.drawLine(x-1,y+4,x+5,y-4); }
                case 0 -> { Path2D p = new Path2D.Double(); p.moveTo(x - 9, y); p.lineTo(x, y - 8); p.lineTo(x + 9, y); p.lineTo(x + 7, y + 10); p.lineTo(x - 7, y + 10); p.closePath(); g.draw(p); }
                case 1 -> { g.drawRoundRect(x - 9, y - 9, 18, 18, 3, 3); g.drawLine(x - 5, y - 4, x + 5, y - 4); g.drawLine(x - 5, y + 1, x + 5, y + 1); g.drawLine(x - 5, y + 6, x + 1, y + 6); }
                case 2 -> { g.drawRoundRect(x - 10, y - 7, 20, 15, 3, 3); g.drawLine(x - 4, y - 10, x + 4, y - 10); g.drawLine(x - 4, y - 10, x - 4, y - 7); g.drawLine(x + 4, y - 10, x + 4, y - 7); }
                case 3 -> { g.drawOval(x-10,y-9,7,7);g.drawOval(x+1,y-9,7,7);g.drawRoundRect(x-11,y-1,22,12,4,4);g.drawLine(x-3,y+11,x-7,y+15);g.fillOval(x-4,y+4,3,3);g.fillOval(x+2,y+4,3,3); }
                case 4 -> { Path2D map=new Path2D.Double();map.moveTo(x-11,y-8);map.lineTo(x-4,y-11);map.lineTo(x+4,y-8);map.lineTo(x+11,y-11);map.lineTo(x+11,y+8);map.lineTo(x+4,y+11);map.lineTo(x-4,y+8);map.lineTo(x-11,y+11);map.closePath();g.draw(map);g.drawLine(x-4,y-11,x-4,y+8);g.drawLine(x+4,y-8,x+4,y+11); }
                case 5 -> { g.drawOval(x - 8, y - 8, 16, 16); g.drawOval(x - 3, y - 3, 6, 6); for (int i=0;i<8;i++){double a=i*Math.PI/4;g.drawLine((int)(x+Math.cos(a)*9),(int)(y+Math.sin(a)*9),(int)(x+Math.cos(a)*12),(int)(y+Math.sin(a)*12));} }
            }
            g.setStroke(previous); // panels drawn after an icon must keep their 1 px border
        }

        private void paintSpeakerIcon(Graphics2D g,int x,int y,boolean muted,Color color,boolean animate){Stroke old=g.getStroke();g.setStroke(new BasicStroke(2f));g.setColor(color);Path2D speaker=new Path2D.Double();speaker.moveTo(x-11,y-4);speaker.lineTo(x-6,y-4);speaker.lineTo(x,y-10);speaker.lineTo(x,y+10);speaker.lineTo(x-6,y+4);speaker.lineTo(x-11,y+4);speaker.closePath();g.draw(speaker);if(muted){g.setColor(Retro.RED);g.drawLine(x+5,y-6,x+13,y+6);g.drawLine(x+13,y-6,x+5,y+6);}else if(animate){int phase=(int)(time*5)%3;for(int i=0;i<3;i++){int alpha=i<=phase?230:70;g.setColor(Retro.alpha(color,alpha));int px=x+5+i*4,half=2+i*2;g.drawLine(px,y-half,px,y+half);}}g.setStroke(old);}

        // ================================================================ UI-test helpers

        /** UI-test captures only: believable content so every page can be reviewed without a live backend. */
        void loadSampleData(){
            if(!uiTest())return;
            long now=Instant.now().getEpochSecond();
            newsPosts.clear();
            newsPosts.add(new ErdvynApiClient.NewsPost(42,"","Act 01: the Highwater falls are open","The gorge below the Astran plateau is now reachable from the river valley.\n\nBring rope: the lip is sixty blocks up.","",now-3600*5));
            newsPosts.add(new ErdvynApiClient.NewsPost(41,"","Vanguard set and the new bevor","Plates now sit on the body and the wound scarf moves with the cuirass.","",now-86400*2));
            newsPosts.add(new ErdvynApiClient.NewsPost(40,"","Bounty board: three warrants in the Carthus steppe","Hunters are paid on custody, not on kills.","",now-86400*4));
            newsPosts.add(new ErdvynApiClient.NewsPost(39,"","Server maintenance window","Thursday 03:00-04:00 UTC.","",now-86400*9));
            onlinePlayers.clear();onlinePlayers.addAll(List.of("Swaggify","Kestrel_09","IronVeil","Morrow","dustrunner"));
            serverSnapshot=new MinecraftServerStatus.Snapshot(true,5,80,34,"1.21.1","Erdvyn",List.copyOf(onlinePlayers));
            apiStatus=new ErdvynApiClient.Status(true,5455L,19.8,List.copyOf(onlinePlayers));
            LocalTime clock=LocalTime.now();
            addChat(new ChatLine("Kestrel_09","anyone near the cataract? need a hand with the warrant",clock.minusMinutes(6)));
            addChat(new ChatLine("IronVeil","on my way, bringing the sledgehammer",clock.minusMinutes(5)));
            addChat(ChatLine.system("SYSTEM","Erdvyn hub connected.",clock.minusMinutes(4)));
            addChat(new ChatLine("Morrow","patch notes are up in DISPATCH",clock.minusMinutes(1)));
            packLog.clear();for(String line:List.of("MANIFEST  https://api.erdvyn.net/api/pack/manifest","[OK] mods/erdvyn_gears-0.6.42.jar","[OK] mods/erdvyn_close_quarter-0.9.1.jar","[KEEP] options.txt","[GET] mods/erdvyn_world-0.14.0.jar","[SAVED] mods/erdvyn_world-0.14.0.jar","PACKAGE VERIFIED"))addPackLog(line);
            packProgress=1;packStatus="1187 verified / 1 downloaded / 4 player settings kept";
            notify("Pack ready: 1 files downloaded.");
            survey.use(PlanetSurvey.sample(PlanetGlobe.atlasNow()));centreOnSurvey();
        }
        void previewSignIn(){if(!uiTest())return;accountLoginInProgress=true;deviceCode="RT7K-2QXM";deviceUrl="https://www.microsoft.com/link";accountNotice="CODE COPIED TO CLIPBOARD. CONFIRM IN YOUR BROWSER.";profileOpen=true;}

        String runInteractionSelfTest(){
            List<String> failures=new ArrayList<>();Rectangle original=frame.getBounds();pointerInside=true;
            int sx=original.x+220,sy=original.y+35;
            mousePressed(testEvent(MouseEvent.MOUSE_PRESSED,220,35,sx,sy));mouseDragged(testEvent(MouseEvent.MOUSE_DRAGGED,280,75,sx+60,sy+40));mouseReleased(testEvent(MouseEvent.MOUSE_RELEASED,280,75,sx+60,sy+40));
            if(frame.getX()!=original.x+60||frame.getY()!=original.y+40)failures.add("window-drag");
            frame.validate();Rectangle moved=frame.getBounds();int rx=getWidth()-2,ry=getHeight()/2;
            mousePressed(testEvent(MouseEvent.MOUSE_PRESSED,rx,ry,moved.x+rx,moved.y+ry));mouseDragged(testEvent(MouseEvent.MOUSE_DRAGGED,rx+90,ry,moved.x+rx+90,moved.y+ry));mouseReleased(testEvent(MouseEvent.MOUSE_RELEASED,rx+90,ry,moved.x+rx+90,moved.y+ry));
            if(frame.getWidth()<moved.width+85)failures.add("edge-resize");
            frame.validate();paintForTest();
            clickForTest(navBounds[1]);
            if(page!=Page.NEWS)failures.add("news-navigation");
            keyPressed(new KeyEvent(this,KeyEvent.KEY_PRESSED,System.currentTimeMillis(),0,KeyEvent.VK_F3,KeyEvent.CHAR_UNDEFINED));if(page!=Page.PACK)failures.add("channel-key");
            navigate(Page.MAP);paintForTest();if(page!=Page.MAP)failures.add("world-map");
            // The globe: a drag turns it, the wheel zooms, a waypoint row flies to it, the HOME hologram opens the map.
            survey.use(PlanetSurvey.sample(PlanetGlobe.atlasNow()));mapCentredOn="";centreOnSurvey();pageTransition=1;paintForTest();
            int gx=mapViewBounds.x+mapViewBounds.width/2,gy=mapViewBounds.y+mapViewBounds.height/2;double yaw0=mapGlobe.yaw,zoom0=mapGlobe.zoom;
            mousePressed(testEvent(MouseEvent.MOUSE_PRESSED,gx,gy,gx,gy));mouseDragged(testEvent(MouseEvent.MOUSE_DRAGGED,gx+80,gy+20,gx+80,gy+20));mouseReleased(testEvent(MouseEvent.MOUSE_RELEASED,gx+80,gy+20,gx+80,gy+20));
            if(Math.abs(mapGlobe.yaw-yaw0)<100)failures.add("globe-drag");mapGlobe.stop();
            mouseWheelMoved(new MouseWheelEvent(this,MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,gx,gy,gx,gy,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,1,-2));if(mapGlobe.zoom<=zoom0)failures.add("globe-zoom");
            paintForTest();if(waypointBounds[0].isEmpty())failures.add("waypoint-list");else{clickForTest(waypointBounds[0]);for(int i=0;i<90;i++)mapGlobe.tick(1/60.0);PlanetSurvey.Waypoint first=survey.current().waypoints().get(0);if(Math.abs(mapGlobe.yaw-first.x())>2)failures.add("waypoint-fly");}
            navigate(Page.HOME);pageTransition=1;paintForTest();clickForTest(new Rectangle((int)homeGlobe.centreX()-8,(int)homeGlobe.centreY()-8,4,4));if(page!=Page.MAP)failures.add("home-globe");
            selectedNews=-1;navigate(Page.HOME);paintForTest();
            clickForTest(profileBounds);
            if(!profileOpen)failures.add("profile-card");profileOpen=false;
            // Sign-in: the waiting card offers a cancel that really ends the wait.
            previewSignIn();paintForTest();if(cancelLoginBounds.isEmpty())failures.add("sign-in-cancel-shown");else{clickForTest(cancelLoginBounds);if(accountLoginInProgress)failures.add("sign-in-cancel");}profileOpen=false;accountNotice="";
            navigate(Page.SETTINGS);paintForTest();boolean previous=autoUpdate;clickForTest(settingBounds[0]);if(autoUpdate==previous)failures.add("settings-toggle");autoUpdate=previous;preferences.putBoolean("autoUpdate",previous);
            navigate(Page.PACK);paintForTest();if(verifyBounds.isEmpty()||folderBounds.isEmpty())failures.add("modpack-actions");
            navigate(Page.GAME);paintForTest();clickForTest(chatInputBounds);keyTyped(new KeyEvent(this,KeyEvent.KEY_TYPED,System.currentTimeMillis(),0,KeyEvent.VK_UNDEFINED,'x'));if(!chatFocused||!chatDraft.endsWith("x"))failures.add("game-panel-chat");chatDraft="";chatFocused=false;
            startLauncherBoot();if(!bootActive)failures.add("boot-sequence");skipBoot();time+=1;advanceBoot();if(bootActive)failures.add("boot-skip");bootActive=false;launcherReady=true;
            startLaunchPreview();launchStartedAtMillis-=2000;paintForTest();if(!launchOverlayActive||launchTrace.size()<6||launchPid<=0)failures.add("launch-terminal");if(launchCancelBounds.isEmpty())failures.add("launch-cancel-shown");
            showGameCrash(1);paintForTest();if(crashReportsBounds.isEmpty()||crashPlayBounds.isEmpty()||launchLogsBounds.isEmpty())failures.add("crash-panel");clickForTest(launchDismissBounds);if(launchOverlayActive||gameCrashed)failures.add("crash-close");launchOverlayActive=false;gameLaunching=false;
            navigate(Page.HOME);notify("self-test");notificationsOpen=true;paintForTest();clickForTest(notificationClearBounds);if(!notifications.isEmpty())failures.add("notification-clear");notificationsOpen=false;
            newsPosts.add(new ErdvynApiClient.NewsPost(1,"","Self test","line ".repeat(500),"",0));navigate(Page.NEWS);selectedNews=newsPosts.size()-1;articleScroll=0;articleOpenedAt=time-5;paintForTest();mouseWheelMoved(new MouseWheelEvent(this,MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,getWidth()/2,getHeight()/2,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,3,2));if(articleMaxScroll==0||articleScroll==0)failures.add("article-scroll");selectedNews=-1;newsPosts.remove(newsPosts.size()-1);
            // Every page paints at the smallest and a large window, with and without content.
            for(Dimension size:List.of(new Dimension(1040,640),new Dimension(1600,900)))for(int pass=0;pass<2;pass++){
                frame.setSize(size);frame.validate();if(pass==1)loadSampleData();
                for(Page each:Page.values()){page=each;previousPage=each;pageTransition=1;try{paintForTest();}catch(Exception paintFailure){failures.add("paint-"+each+"-"+size.width+"x"+size.height+":"+paintFailure);}}
                pageTransition=.4;try{paintForTest();}catch(Exception transitionFailure){failures.add("paint-transition:"+transitionFailure);}pageTransition=1;
            }
            page=Page.HOME;frame.setBounds(original);frame.validate();
            return failures.isEmpty()?"PASS: drag, resize, dispatch, channel-key, world-map, globe-drag, globe-zoom, waypoint-fly, home-globe, profile, sign-in-cancel, settings, modpack, game-panel, boot-sequence, boot-skip, launch-terminal, launch-cancel, crash-panel, notification-clear, article-scroll, page-paint":"FAIL: "+String.join(", ",failures);
        }
        private void clickForTest(Rectangle r){mouseClicked(testEvent(MouseEvent.MOUSE_CLICKED,r.x+8,r.y+8,frame.getX()+r.x+8,frame.getY()+r.y+8));}

        private void paintForTest(){BufferedImage buffer=new BufferedImage(Math.max(1,getWidth()),Math.max(1,getHeight()),BufferedImage.TYPE_INT_ARGB);Graphics2D graphics=buffer.createGraphics();paint(graphics);graphics.dispose();}

        private MouseEvent testEvent(int id,int x,int y,int xAbs,int yAbs){return new MouseEvent(this,id,System.currentTimeMillis(),0,x,y,xAbs,yAbs,1,false,MouseEvent.BUTTON1);}

        record ChatLine(String author,String message,LocalTime time,boolean owned){
            ChatLine(String author,String message,LocalTime time){this(author,message,time,false);}
            static ChatLine system(String author,String message,LocalTime time){return new ChatLine(author,message,time,true);}
        }
    }

    record HubEvent(String kind,String author,String value){}
    static final class HubClient implements WebSocket.Listener {
        private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
        private final HttpClient http=HttpClient.newHttpClient();
        private final Consumer<HubEvent> events;private final StringBuilder incoming=new StringBuilder();private volatile WebSocket socket;private volatile boolean connected,connecting;
        private volatile String lastProblem=""; // the periodic reconnect must not repeat the same failure into the chat every 30 s
        HubClient(Consumer<HubEvent> events){this.events=events;}
        boolean connected(){return connected;}
        void connect(){String endpoint=System.getenv("ERDVYN_HUB_WS");if(endpoint==null||endpoint.isBlank())return;connect(endpoint);}
        // Startup refresh, login, launch and the reconnect can all call this: one socket at a time, or chat lines arrive twice.
        synchronized void connect(String endpoint){if(endpoint==null||endpoint.isBlank()||connected||connecting)return;connecting=true;try{var builder=http.newWebSocketBuilder();String token=System.getenv("ERDVYN_HUB_TOKEN");if(token!=null&&!token.isBlank()&&!endpoint.contains("token="))builder.header("Authorization","Bearer "+token);builder.buildAsync(URI.create(endpoint),this).exceptionally(error->{connecting=false;problem("Hub: "+error.getMessage());return null;});}catch(Exception ex){connecting=false;problem("Hub: "+ex.getMessage());}}
        void sendChat(String message){WebSocket active=socket;if(active!=null&&connected)active.sendText(JSON.createObjectNode().put("type","chat").put("message",message).toString(),true);}
        void close(){WebSocket active=socket;socket=null;connected=false;connecting=false;if(active!=null)try{active.sendClose(WebSocket.NORMAL_CLOSURE,"Launcher closed");}catch(Exception ignored){active.abort();}}
        private void problem(String message){if(message.equals(lastProblem))return;lastProblem=message;events.accept(new HubEvent("status","SYSTEM",message));}
        @Override public void onOpen(WebSocket webSocket){socket=webSocket;connected=true;connecting=false;lastProblem="";events.accept(new HubEvent("status","SYSTEM","Erdvyn hub connected."));webSocket.request(1);}
        @Override public CompletionStage<?> onText(WebSocket webSocket,CharSequence data,boolean last){if(incoming.length()<256_000)incoming.append(data);if(last){String raw=incoming.toString();incoming.setLength(0);parse(raw);}webSocket.request(1);return null;}
        @Override public CompletionStage<?> onClose(WebSocket webSocket,int statusCode,String reason){boolean was=connected;connected=false;connecting=false;if(was)events.accept(new HubEvent("status","SYSTEM","Hub disconnected."));return WebSocket.Listener.super.onClose(webSocket,statusCode,reason);}
        @Override public void onError(WebSocket webSocket,Throwable error){connected=false;connecting=false;problem("Hub: "+error.getMessage());}
        private void parse(String raw){
            try{var root=JSON.readTree(raw);String type=root.path("type").asText();
                if("players".equals(type)){List<String> names=new ArrayList<>();root.path("players").forEach(node->{if(names.size()<ErdvynApiClient.MAX_PLAYERS)names.add(ErdvynApiClient.clip(node.asText().replace(",",""),32));});events.accept(new HubEvent("players","",String.join(",",names)));return;}
                if("chat".equals(type)){String author=ErdvynApiClient.clip(root.path("author").asText(""),32);events.accept(new HubEvent("chat",author.isBlank()?"PLAYER":author,ErdvynApiClient.clip(root.path("message").asText(""),500)));}
            }catch(Exception malformed){/* ignore frames that are not JSON */}
        }
    }
}
