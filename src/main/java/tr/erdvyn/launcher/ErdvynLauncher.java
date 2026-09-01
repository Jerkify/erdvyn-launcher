package tr.erdvyn.launcher;

import javax.imageio.ImageIO;
import javax.swing.*;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.SepiaTone;
import javafx.util.Duration;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.List;
import java.util.*;
import java.util.prefs.Preferences;

public final class ErdvynLauncher {
    private ErdvynLauncher() {}

    public static void main(String[] args) {
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        String requestedCapture = null, requestedSelfTest = null, requestedPage = null,requestedWindow=null;boolean requestedBoot=false,requestedLauncherBoot=false,requestedLaunchPreview=false;int requestedCaptureDelay=1800;
        for (int i = 0; i < args.length; i++) {
            if ("--capture".equals(args[i]) && i + 1 < args.length) requestedCapture = args[++i];
            else if ("--self-test".equals(args[i]) && i + 1 < args.length) requestedSelfTest = args[++i];
            else if ("--page".equals(args[i]) && i + 1 < args.length) requestedPage = args[++i];
            else if ("--boot".equals(args[i])) requestedBoot=true;
            else if ("--launcher-boot".equals(args[i])) requestedLauncherBoot=true;
            else if ("--launch-preview".equals(args[i])) requestedLaunchPreview=true;
            else if ("--window".equals(args[i])&&i+1<args.length)requestedWindow=args[++i];
            else if ("--capture-delay".equals(args[i])&&i+1<args.length)try{requestedCaptureDelay=Math.max(250,Integer.parseInt(args[++i]));}catch(NumberFormatException ignored){}
            else if ("--lang".equals(args[i]) && i + 1 < args.length) Preferences.userNodeForPackage(ErdvynLauncher.class).put("language", args[++i].toUpperCase(Locale.ROOT));
        }
        String capturePath = requestedCapture, selfTestPath = requestedSelfTest, initialPage=requestedPage,initialWindow=requestedWindow;boolean initialBoot=requestedBoot,initialLauncherBoot=requestedLauncherBoot,initialLaunchPreview=requestedLaunchPreview;int captureDelayMs=requestedCaptureDelay;
        EventQueue.invokeLater(() -> {
            LauncherFrame frame = new LauncherFrame();
            if(initialWindow!=null)try{String[] size=initialWindow.toLowerCase(Locale.ROOT).split("x");frame.setSize(Math.max(1040,Integer.parseInt(size[0])),Math.max(640,Integer.parseInt(size[1])));frame.setLocationRelativeTo(null);frame.layoutLayers();}catch(Exception ignored){}
            frame.setVisible(true);
            if(initialPage!=null)try{frame.canvas.navigate(Page.valueOf(initialPage.toUpperCase(Locale.ROOT)));}catch(Exception ignored){}
            if(initialBoot)frame.canvas.startBoot();else if(initialLauncherBoot||(capturePath==null&&selfTestPath==null&&initialPage==null))frame.canvas.startLauncherBoot();
            if(initialLaunchPreview)frame.canvas.startLaunchPreview();
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

    enum Language { TR, EN }
    enum Page { HOME, NEWS, PACK, GAME, MAP, SETTINGS, ADMIN }
    enum UpdateStage { IDLE, CHECKING, DOWNLOADING, VERIFYING, READY, LAUNCHING }

    @SuppressWarnings("serial")
    static final class LauncherFrame extends JFrame {
        private Point dragOrigin;
        final LauncherCanvas canvas;
        final VideoBackdrop video;
        final JLayeredPane layers = new JLayeredPane();
        private final AtomicBoolean shuttingDown = new AtomicBoolean();

        LauncherFrame() {
            super("Erdvyn Launcher");
            try { setIconImage(ImageIO.read(Objects.requireNonNull(ErdvynLauncher.class.getResource("/assets/erdvyn-app-icon.png")))); } catch (Exception ignored) {}
            setUndecorated(true);
            setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            setMinimumSize(new Dimension(1040, 640));
            setSize(1242, 768);
            setLocationRelativeTo(null);
            video = new VideoBackdrop();
            canvas = new LauncherCanvas(this,video);
            layers.setLayout(null);layers.add(video.panel,JLayeredPane.DEFAULT_LAYER);layers.add(canvas,JLayeredPane.PALETTE_LAYER);setContentPane(layers);
            addComponentListener(new ComponentAdapter(){@Override public void componentResized(ComponentEvent e){layoutLayers();}@Override public void componentShown(ComponentEvent e){layoutLayers();}});
            layoutLayers();SwingUtilities.invokeLater(this::layoutLayers);
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (e.getY() < 70 && e.getX() > 92 && e.getX() < getWidth() - 120) dragOrigin = e.getPoint();
                }
                @Override public void mouseReleased(MouseEvent e) { dragOrigin = null; }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseDragged(MouseEvent e) {
                    if (dragOrigin != null) {
                        Point screen = e.getLocationOnScreen();
                        setLocation(screen.x - dragOrigin.x, screen.y - dragOrigin.y);
                    }
                }
            });
            addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { shutdownAndExit(); }
            });
        }

        void shutdownAndExit() {
            if (!shuttingDown.compareAndSet(false, true)) return;
            canvas.shutdown();
            video.shutdown();
            setVisible(false);
            dispose();
            Platform.exit();


            System.exit(0);
        }

        void onGameProcessStarted() { shutdownAndExit(); }

        private int videoJitterX,videoJitterY;
        private void layoutLayers(){int w=Math.max(1,getContentPane().getWidth()>0?getContentPane().getWidth():getWidth()),h=Math.max(1,getContentPane().getHeight()>0?getContentPane().getHeight():getHeight());Rectangle feed=LauncherCanvas.cameraBounds(w,h);video.panel.setBounds(feed.x+videoJitterX,feed.y+videoJitterY,feed.width,feed.height);canvas.setBounds(0,0,w,h);video.resize(feed.width,feed.height);layers.revalidate();layers.repaint();}
        private void applyVideoJitter(int dx,int dy){if(videoJitterX==dx&&videoJitterY==dy)return;videoJitterX=dx;videoJitterY=dy;Rectangle feed=LauncherCanvas.cameraBounds(Math.max(1,getContentPane().getWidth()),Math.max(1,getContentPane().getHeight()));video.panel.setBounds(feed.x+dx,feed.y+dy,feed.width,feed.height);}
    }

    static final class VideoBackdrop {
        private static final Path PREPARED=Path.of(System.getProperty("user.home"),"Videos","ErdvynLauncher");
        private static final List<Path> MEDIA=List.of(
                Path.of("C:/Medal/Edits/MedalTVMinecraft20260829134219880-trim-1788000173231.mp4"),
                Path.of("C:/Medal/Clips/Minecraft/MedalTVMinecraft20260829135358462.mp4"),
                Path.of("C:/Medal/Edits/MedalTVMinecraft20260829140347672-trim-1788001487967.mp4"),
                Path.of("C:/Medal/Clips/Minecraft/MedalTVMinecraft20260829142023524.mp4"),
                Path.of("C:/Medal/Edits/MedalTVMinecraft20260829143146521-trim-1788003213422.mp4"),
                Path.of("C:/Medal/Clips/Minecraft/MedalTVMinecraft20260829144620964.mp4"),
                Path.of("C:/Medal/Clips/Minecraft/MedalTVMinecraft20260829145602063.mp4"));
        final JFXPanel panel=new JFXPanel();private final Preferences prefs=Preferences.userNodeForPackage(VideoBackdrop.class);
        private MediaPlayer player;private MediaView view;private AnimationTimer volumeTimer;private volatile boolean ready;private volatile double volume;private volatile boolean muted,uiGate;private int targetW=1440,targetH=900;private Path selected;
        private final Set<Path> attempted=new HashSet<>();

        VideoBackdrop(){panel.setOpaque(true);panel.setBackground(new Color(2,7,13));volume=Math.max(0,Math.min(1,prefs.getDouble("videoVolume",.16)));muted=prefs.getBoolean("videoMuted",false);selected=chooseVideo();Platform.runLater(this::open);}
        boolean isReady(){return ready;}double volume(){return volume;}boolean isMuted(){return muted;}Path selected(){return selected;}
        void setUiGate(boolean active){if(uiGate==active)return;uiGate=active;applyVolume();}
        List<Path> media(){
            LinkedHashSet<Path> roots=new LinkedHashSet<>();String appPath=System.getProperty("jpackage.app-path","");
            if(!appPath.isBlank()){Path parent=Path.of(appPath).toAbsolutePath().getParent();if(parent!=null)roots.add(parent.resolve("videos"));}
            Path runtimeParent=Path.of(System.getProperty("java.home")).toAbsolutePath().getParent();if(runtimeParent!=null)roots.add(runtimeParent.resolve("videos"));
            roots.add(Path.of(System.getProperty("user.dir")).toAbsolutePath().resolve("videos"));roots.add(PREPARED);
            for(Path root:roots){List<Path> found=numberedVideos(root);if(!found.isEmpty())return found;}
            return MEDIA.stream().filter(Files::isRegularFile).toList();
        }
        private static List<Path> numberedVideos(Path root){List<Path> found=new ArrayList<>();for(int i=1;i<=MEDIA.size();i++){Path file=root.resolve(String.format(Locale.ROOT,"erdvyn-%02d.mp4",i));if(Files.isRegularFile(file))found.add(file);}return found;}
        void resize(int w,int h){targetW=Math.max(1,w);targetH=Math.max(1,h);Platform.runLater(this::applyViewport);}
        void toggleMute(){muted=!muted;prefs.putBoolean("videoMuted",muted);applyVolume();}
        void setVolume(double value){volume=Math.max(0,Math.min(1,value));if(volume>.001)muted=false;prefs.putDouble("videoVolume",volume);prefs.putBoolean("videoMuted",muted);applyVolume();}
        void switchToDifferentVideo(){List<Path> available=media();if(available.isEmpty())return;Path current=selected;List<Path> choices=available.stream().filter(path->!path.equals(current)).toList();Path next=(choices.isEmpty()?available:choices).get(new Random().nextInt((choices.isEmpty()?available:choices).size()));selected=next;prefs.put("lastVideo",next.toString());ready=false;Platform.runLater(()->{if(volumeTimer!=null){volumeTimer.stop();volumeTimer=null;}if(player!=null){player.stop();player.dispose();player=null;}open();});}

        private Path chooseVideo(){List<Path> available=media();if(available.isEmpty())return null;String last=prefs.get("lastVideo","");List<Path> choices=available.stream().filter(path->!path.toString().equals(last)).toList();if(choices.isEmpty())choices=available;Path choice=choices.get(new Random().nextInt(choices.size()));prefs.put("lastVideo",choice.toString());return choice;}
        private void open(){
            if(selected==null)return;
            try{
                attempted.add(selected);
                Media media=new Media(selected.toUri().toString());player=new MediaPlayer(media);view=new MediaView(player);view.setSmooth(true);view.setPreserveRatio(false);
                ColorAdjust cameraGrade=new ColorAdjust();cameraGrade.setSaturation(-.19);cameraGrade.setContrast(.06);cameraGrade.setBrightness(.045);cameraGrade.setHue(-.035);cameraGrade.setInput(new SepiaTone(.22));view.setEffect(cameraGrade);
                StackPane root=new StackPane(view);root.setStyle("-fx-background-color: #02070d;");panel.setScene(new Scene(root,javafx.scene.paint.Color.rgb(2,7,13)));
                player.setCycleCount(MediaPlayer.INDEFINITE);player.setOnReady(()->{ready=true;applyViewport();applyVolume();player.play();});player.setOnRepeat(()->{if(player!=null){player.seek(Duration.ZERO);player.play();}});player.setOnStalled(()->{if(player!=null)player.play();});
                player.setOnError(()->{ready=false;System.err.println("Video playback: "+player.getError());tryNextVideo();});
                volumeTimer=new AnimationTimer(){@Override public void handle(long now){if(player==null||!ready)return;Duration duration=player.getTotalDuration(),at=player.getCurrentTime();if(duration==null||duration.isUnknown()||duration.isIndefinite())return;double edge=.38,seconds=at.toSeconds(),remaining=duration.toSeconds()-seconds,fade=Math.min(1,Math.min(seconds/edge,remaining/edge));player.setVolume((muted||!uiGate?0:volume)*Math.max(0,fade));}};volumeTimer.start();
            }catch(Exception ex){ready=false;System.err.println("Video init: "+ex.getMessage());tryNextVideo();}
        }
        private void tryNextVideo(){if(player!=null){player.dispose();player=null;}Path next=media().stream().filter(path->!attempted.contains(path)).findFirst().orElse(null);if(next!=null){selected=next;prefs.put("lastVideo",next.toString());Platform.runLater(this::open);}}
        private void applyVolume(){Platform.runLater(()->{if(player!=null)player.setVolume(muted||!uiGate?0:volume);});}
        private void applyViewport(){if(view==null||view.getMediaPlayer()==null)return;Media media=view.getMediaPlayer().getMedia();double sw=media.getWidth(),sh=media.getHeight();if(sw<=0||sh<=0)return;double target=targetW/(double)targetH,source=sw/sh,x=0,y=0,cw=sw,ch=sh;if(source>target){cw=sh*target;x=(sw-cw)/2;}else{ch=sw/target;y=(sh-ch)/2;}view.setViewport(new Rectangle2D(x,y,cw,ch));view.setFitWidth(targetW);view.setFitHeight(targetH);}

        void shutdown(){
            ready=false;uiGate=false;
            Runnable cleanup=()->{if(volumeTimer!=null){volumeTimer.stop();volumeTimer=null;}if(player!=null){player.stop();player.dispose();player=null;}view=null;panel.setScene(null);};
            if(Platform.isFxApplicationThread())cleanup.run();else Platform.runLater(cleanup);
        }

        void requestThumbnail(int mediaIndex,Consumer<BufferedImage> consumer){List<Path> available=media();if(available.isEmpty())return;Path source=available.get(Math.floorMod(mediaIndex,available.size()));Platform.runLater(()->createThumbnail(source,consumer));}
        private void createThumbnail(Path source,Consumer<BufferedImage> consumer){
            try{Media media=new Media(source.toUri().toString());MediaPlayer thumbPlayer=new MediaPlayer(media);MediaView thumbView=new MediaView(thumbPlayer);thumbView.setFitWidth(560);thumbView.setFitHeight(315);thumbView.setPreserveRatio(false);Group group=new Group(thumbView);new Scene(group,560,315,javafx.scene.paint.Color.BLACK);thumbPlayer.setMute(true);
                thumbPlayer.setOnReady(()->{Duration seek=Duration.seconds(Math.min(6,Math.max(1,thumbPlayer.getTotalDuration().toSeconds()*.18)));thumbPlayer.seek(seek);PauseTransition pause=new PauseTransition(Duration.millis(380));pause.setOnFinished(event->{WritableImage image=new WritableImage(560,315);thumbView.snapshot(null,image);BufferedImage converted=SwingFXUtils.fromFXImage(image,null);thumbPlayer.dispose();SwingUtilities.invokeLater(()->consumer.accept(converted));});pause.play();});
            }catch(Exception ignored){}
        }
    }

    @SuppressWarnings("serial")
    static final class LauncherCanvas extends JPanel implements MouseListener, MouseMotionListener, MouseWheelListener, KeyListener, ActionListener {
        private static final Color INK = new Color(11, 5, 2);
        private static final Color PANEL = new Color(20, 9, 3, 246);
        private static final Color PANEL_SOFT = new Color(27, 12, 4, 232);
        private static final Color LINE = new Color(181, 86, 28, 178);
        private static final Color PAPER = new Color(255, 218, 147);
        private static final Color MUTED = new Color(222, 143, 74);
        private static final Color AMBER = new Color(244, 139, 43);
        private static final Color AMBER_HOT = new Color(255, 174, 66);
        private static final Color RED = new Color(194, 68, 32);
        private static final int SIDEBAR = 82;
        private static final int[] BOOT_STEPS={0,4,11,18,34,52,71,83,99,107,111,112};
        private static final String[] BOOT_LOGS={
                "> INITIALIZING ERDVYN RUNTIME","> JAVA 21 RUNTIME ............... OK","> MINECRAFT 1.21.1 .............. FOUND","> NEOFORGE 21.1.243 ............. READY",
                "> VERIFYING PACKAGE INDEX","[OK] mod manifest","[OK] configs","[OK] resources","> MOUNTING RESOURCE PACKS","> PREPARING JVM ARGUMENTS",
                "> SYNCHRONIZING LOCAL PROFILE","> REGISTERING LAUNCH SERVICES","> PREPARING GAME INSTANCE","[WAIT] game process","> SPAWNING GAME PROCESS","> HANDOFF TO MINECRAFT"};
        private static final String[] LAUNCHER_BOOT_LOGS={
                "> POWERING ERDVYN CONTROL TERMINAL","> MEMORY MAP .................... OK","> CRT PHOSPHOR LAYER ............ READY","> LOADING PIXEL GLYPH ROM",
                "[OK] PxPlus IBM VGA8","[OK] amber color table","> MOUNTING USER PREFERENCES","> INITIALIZING VIDEO BUS","[OK] camera playlist / 07",
                "> INITIALIZING AUDIO DEVICE","[OK] mechanical UI channel","> STARTING NETWORK MONITOR","> REGISTERING PANEL MODULES","> SYNCHRONIZING SYSTEM CLOCK","> UI BUS HANDOFF"};

        private final LauncherFrame frame;
        private final VideoBackdrop video;
        private final Preferences preferences = Preferences.userNodeForPackage(ErdvynLauncher.class);
        private final javax.swing.Timer timer = new javax.swing.Timer(16, this);
        private final Random random = new Random(72491);
        private final List<Dust> dust = new ArrayList<>();
        private final Map<String, String[]> strings = new HashMap<>();
        private final Rectangle[] navBounds = new Rectangle[7];
        private Rectangle playBounds = new Rectangle(), instancePathBounds = new Rectangle(), languageBounds = new Rectangle(), closeBounds = new Rectangle(), minimizeBounds = new Rectangle();
        private Rectangle updateBounds = new Rectangle(), profileBounds = new Rectangle(), notificationBounds = new Rectangle(), notificationPanelBounds = new Rectangle(), articleCloseBounds = new Rectangle();
        private final Rectangle newsComposeBounds=new Rectangle(),newsTitleInputBounds=new Rectangle(),newsBodyInputBounds=new Rectangle(),newsPublishBounds=new Rectangle(),newsCancelBounds=new Rectangle();
        private Rectangle audioBounds = new Rectangle(), volumeBounds = new Rectangle(), installPackBounds = new Rectangle(), verifyBounds = new Rectangle(), folderBounds = new Rectangle();
        private Rectangle chatInputBounds = new Rectangle(), chatSendBounds = new Rectangle(), microsoftBounds = new Rectangle(), erdvynAccountBounds = new Rectangle();
        private Rectangle settingsLanguageBounds = new Rectangle();
        private final Rectangle[] settingBounds = {new Rectangle(),new Rectangle(),new Rectangle()};
        private final Rectangle ramMinusBounds=new Rectangle(),ramPlusBounds=new Rectangle(),renderMinusBounds=new Rectangle(),renderPlusBounds=new Rectangle(),simulationMinusBounds=new Rectangle(),simulationPlusBounds=new Rectangle();
        private final Rectangle fpsMinusBounds=new Rectangle(),fpsPlusBounds=new Rectangle(),guiMinusBounds=new Rectangle(),guiPlusBounds=new Rectangle(),vsyncBounds=new Rectangle();
        private final Rectangle[] settingsFolderBounds={new Rectangle(),new Rectangle(),new Rectangle(),new Rectangle()};
        private final Rectangle optionsFileBounds=new Rectangle(),configFolderBounds=new Rectangle();
        private final Rectangle adminTargetBounds=new Rectangle(),adminCommandBounds=new Rectangle(),adminGrantBounds=new Rectangle(),adminRevokeBounds=new Rectangle(),adminBanBounds=new Rectangle(),adminUnbanBounds=new Rectangle(),adminExecuteBounds=new Rectangle();
        private final Rectangle launchDismissBounds=new Rectangle();
        private final Rectangle[] newsBounds = new Rectangle[12];
        private final HubClient hub = new HubClient(this::onHubEvent);
        private final MinecraftServerStatus serverStatus = new MinecraftServerStatus(this::onServerStatus);
        private final MicrosoftAccountService accountService = new MicrosoftAccountService();
        private final ErdvynApiClient apiClient = new ErdvynApiClient();
        private final MinecraftInstallService installService = new MinecraftInstallService();
        private final MinecraftLaunchService launchService = new MinecraftLaunchService();
        private final PackService packService = new PackService();
        private final MinecraftSkinService skinService = new MinecraftSkinService();
        private final LauncherUpdateService launcherUpdateService = new LauncherUpdateService();
        private final List<ChatLine> chatLines = new ArrayList<>();
        private final List<String> onlinePlayers = new ArrayList<>();
        private final List<String> packLog = new ArrayList<>();
        private final List<String> launchTrace = new ArrayList<>();
        private final List<ErdvynApiClient.NewsPost> newsPosts = new ArrayList<>();
        private final List<ErdvynApiClient.AdminAccount> adminAccounts = new ArrayList<>();
        private final Map<String,BufferedImage> adminHeads = new HashMap<>();
        private GameOptions gameOptions;
        private MicrosoftAccountService.Session accountSession;
        private MinecraftServerStatus.Snapshot serverSnapshot = MinecraftServerStatus.Snapshot.offline();
        private ErdvynApiClient.Status apiStatus = ErdvynApiClient.Status.offline();
        private Page page = Page.HOME;
        private Language language;
        private UpdateStage updateStage = UpdateStage.IDLE;
        private int hoverNav = -1, hoverNews = -1, hoverSetting = -1, selectedNews = -1, stateTicks, resizeMask, newsScroll,newsFirstVisible;
        private boolean hoverPlay, hoverLanguage, hoverUpdate, hoverProfile, profileOpen, autoUpdate = true, autoConnect = true, closeAfterLaunch = true;
        private boolean hoverAudio, volumeDragging, hoverVerify, hoverFolder, packVerifying, chatFocused,bootActive,bootCompleteSound,launcherBootMode,launcherReady=true,cameraVideoSwitched;
        private boolean accountLoginInProgress,launchAfterLogin,pendingGameLaunch,gameLaunching,launchOverlayActive,launchFailed,backendPollInProgress,newsComposeOpen,newsPublishInProgress,notificationsOpen,packInstalled;
        private boolean draggingWindow, resizingWindow;
        private Point windowActionStart;
        private Rectangle windowStartBounds;
        private BufferedImage logo;
        private BufferedImage playerHead;
        private LauncherUpdateService.Update launcherUpdate;
        private Path launcherInstaller;
        private double time, displayedProgress, targetProgress, playPulse, pageTransition = 1, opening, sidebarExpand, volumeReveal,pressDepth,cameraGlitchStart=-10,cameraGlitchEnd=-10,nextCameraGlitchAt=14,launchDisplayedProgress,launchTargetProgress;
        private int bootStepIndex,bootLogCount,bootDelay,bootHold;
        private String pressedControl="";
        private final double[] toggleVisual = {1,1,0};
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
        private String accountNotice = "";
        private String adminTargetDraft="",adminCommandDraft="",adminNotice="";
        private int adminField;
        private boolean adminActionInProgress;
        private final List<String> notifications = new ArrayList<>();
        private Point mouse = new Point();

        LauncherCanvas(LauncherFrame frame,VideoBackdrop video) {
            this.frame = frame;
            this.video=video;setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            setFocusable(true);
            language = "EN".equalsIgnoreCase(preferences.get("language", "TR")) ? Language.EN : Language.TR;
            autoUpdate=preferences.getBoolean("autoUpdate",true);autoConnect=preferences.getBoolean("autoConnect",true);
            try { LauncherPaths.prepareInstance(); } catch (Exception ignored) {}
            gameOptions = new GameOptions(LauncherPaths.gameDirectory());
            accountSession = accountService.cached();
            packInstalled = packService.isInstalled();
            refreshPackSummary();
            try { logo = ImageIO.read(Objects.requireNonNull(ErdvynLauncher.class.getResource("/assets/erdvyn-logo.png"))); }
            catch (Exception ignored) { logo = null; }
            installStrings();
            for(int i=0;i<navBounds.length;i++)navBounds[i]=new Rectangle();
            for(int i=0;i<newsBounds.length;i++)newsBounds[i]=new Rectangle();
            for (int i = 0; i < 58; i++) dust.add(new Dust(random.nextDouble(), random.nextDouble(), .08 + random.nextDouble() * .32, 2 + random.nextInt(3), random.nextDouble() * Math.PI * 2));
            addMouseListener(this);
            addMouseMotionListener(this);
            addMouseWheelListener(this);
            addKeyListener(this);
            chatLines.add(new ChatLine("SYSTEM", language==Language.TR?"Sohbet bağlantısı bekleniyor.":"Waiting for chat connection.", LocalTime.now()));
            if(!packInstalled)notifications.add(l("Mod paketi kuruluma hazır. İlk OYNA basışında otomatik kurulacak.","Modpack is ready to install. It will install automatically on first PLAY."));
            hub.connect();
            serverStatus.start();
            if(accountSession!=null)authenticateCachedAccount();
            checkLauncherUpdateAsync();
            timer.start();
        }

        private void installStrings() {
            put("home", "ANA SAYFA", "HOME"); put("news", "HABERLER", "NEWS"); put("pack", "MOD PAKETİ", "MODPACK"); put("gamePanel", "OYUN PANELİ", "GAME PANEL"); put("worldMap", "DÜNYA HARİTASI", "WORLD MAP"); put("settings", "AYARLAR", "SETTINGS"); put("admin", "YÖNETİCİ", "ADMIN");
            put("online", "SUNUCU ÇEVRİMİÇİ", "SERVER ONLINE"); put("players", "12 / 80 OYUNCU", "12 / 80 PLAYERS");
            put("offline", "BAĞLANTI BEKLENİYOR", "WAITING FOR CONNECTION"); put("loginRequired", "Giriş gerekli", "Sign-in required");
            put("eyebrow", "ERDVYN FRONTIER // SEZON 01", "ERDVYN FRONTIER // SEASON 01");
            put("headline1", "SINIRIN ÖTESİNE", "BEYOND THE"); put("headline2", "GEÇ.", "FRONTIER.");
            put("description", "Mekanik silahlar, fizik tabanlı savaş ve yaşayan bir çöl dünyası.\nPaketin her zaman güncel; tek yapman gereken oynamak.", "Mechanical weapons, physics-driven combat and a living desert world.\nYour pack stays current. All you have to do is play.");
            put("play", "OYNA", "PLAY"); put("checking", "GÜNCELLEME ARANIYOR", "CHECKING FOR UPDATES"); put("downloading", "GÜNCELLENİYOR", "UPDATING");
            put("verifying", "DOSYALAR DOĞRULANIYOR", "VERIFYING FILES"); put("ready", "HAZIR", "READY"); put("launching", "BAŞLATILIYOR", "LAUNCHING");
            put("upToDate", "OYUN GÜNCEL", "GAME IS UP TO DATE"); put("version", "SÜRÜM", "VERSION"); put("ping", "GECİKME", "PING");
            put("latest", "SON GÜNCELLEME", "LATEST UPDATE"); put("latestTitle", "Grappling Bracer: Halat Fiziği", "Grappling Bracer: Rope Physics");
            put("latestText", "Yeni segment çarpışmaları, Create ve Sable bağlantıları ile daha ağır bir hareket sistemi.", "New segment collisions, Create and Sable attachments, and a heavier traversal system.");
            put("read", "DETAYLARI GÖR", "VIEW DETAILS"); put("profile", "HOŞ GELDİN", "WELCOME BACK"); put("account", "Hesap bağlı", "Account connected");
            put("today", "BUGÜN", "TODAY"); put("newsTitle", "SINIRDAN HABERLER", "NEWS FROM THE FRONTIER");
            put("newsLead", "Dünya değişiyor. Son yamaları, etkinlikleri ve topluluk notlarını buradan takip et.", "The world is changing. Follow patches, events and community notes here.");
            put("patch", "YAMA NOTLARI", "PATCH NOTES"); put("patchTitle", "Savaş ve hareket güncellemesi", "Combat & movement update");
            put("event", "ETKİNLİK", "EVENT"); put("eventTitle", "Çöl sınırında yeni hedefler", "New bounties on the desert frontier");
            put("community", "TOPLULUK", "COMMUNITY"); put("communityTitle", "Haftanın yapıları", "Builds of the week");
            put("packTitle", "ERDVYN: THE FRONTIER", "ERDVYN: THE FRONTIER"); put("managed", "LAUNCHER TARAFINDAN YÖNETİLİYOR", "MANAGED BY LAUNCHER");
            put("mods", "MOD", "MODS"); put("configs", "YAPILANDIRMA", "CONFIGS"); put("size", "KURULUM", "INSTALL"); put("integrity", "Tüm dosyalar doğrulandı", "All files verified");
            put("repair", "DOSYALARI DOĞRULA", "VERIFY FILES"); put("openFolder", "KLASÖRÜ AÇ", "OPEN FOLDER");
            put("settingsTitle", "LAUNCHER AYARLARI", "LAUNCHER SETTINGS"); put("settingsLead", "İndirme, başlatma ve arayüz tercihlerini yönet.", "Manage download, launch and interface preferences.");
            put("autoUpdate", "Otomatik güncellemeler", "Automatic updates"); put("autoUpdateDesc", "Oyun açılmadan önce paketi güncel tutar.", "Keeps the pack current before launch.");
            put("autoConnect", "Sunucuya otomatik bağlan", "Connect to server automatically"); put("autoConnectDesc", "Minecraft hazır olduğunda Erdvyn'e katılır.", "Joins Erdvyn when Minecraft is ready.");
            put("closeLauncher", "Oyun açılınca launcher'ı kapat", "Close launcher after game starts"); put("closeLauncherDesc", "Sistem tepsisinde çalışmaya devam etmez.", "Does not continue running in the tray.");
            put("language", "Dil", "Language"); put("languageDesc", "Arayüz dilini anında değiştirir.", "Changes the interface language instantly.");
            put("prototype", "GÖRSEL PROTOTİP", "VISUAL PROTOTYPE");
            put("playersTitle", "SUNUCUDAKİ OYUNCULAR", "PLAYERS ONLINE"); put("playersLead", "Sunucunun canlı oyuncu listesi.", "Live player list from the server.");
            put("chatTitle", "ERDVYN SOHBETİ", "ERDVYN CHAT"); put("chatLead", "Launcher'dan oyun içi sohbete bağlan.", "Connect to in-game chat from the launcher.");
            put("send", "GÖNDER", "SEND"); put("writeMessage", "Mesaj yaz...", "Write a message...");
            put("accountTitle", "HESABINI BAĞLA", "CONNECT YOUR ACCOUNT"); put("microsoftLogin", "MICROSOFT İLE GİRİŞ", "SIGN IN WITH MICROSOFT"); put("erdvynLogin", "ERDVYN HESABI", "ERDVYN ACCOUNT");
            put("mapLead", "Keşfettiğin bölgeler burada görüntülenecek.", "Your discovered regions will appear here."); put("mapOffline", "HARİTA BAĞLANTISI BEKLENİYOR", "WAITING FOR MAP LINK");
        }

        private void put(String key, String tr, String en) { strings.put(key, new String[]{tr, en}); }
        private String t(String key) { String[] pair = strings.get(key); return pair == null ? key : pair[language == Language.TR ? 0 : 1]; }
        private String l(String tr,String en){return language==Language.TR?tr:en;}
        private void refreshPackSummary(){Thread.startVirtualThread(()->{try{PackService.Summary summary=packService.summary();SwingUtilities.invokeLater(()->{packSummary=summary;repaint();});}catch(Exception ignored){}});}
        private static String formatBytes(long bytes){if(bytes<=0)return "--";double value=bytes;String unit="B";if(value>=1024){value/=1024;unit="KB";}if(value>=1024){value/=1024;unit="MB";}if(value>=1024){value/=1024;unit="GB";}return String.format(Locale.ROOT,value>=100?"%.0f %s":"%.1f %s",value,unit);}

        @Override protected void paintComponent(Graphics base) {
            super.paintComponent(base);
            Graphics2D g = (Graphics2D) base.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            paintWorld(g);
            float openingAlpha=(float)Math.max(.02,Math.min(1,opening));
            g.setComposite(AlphaComposite.SrcOver.derive(openingAlpha));
            g.translate(0,(1-openingAlpha)*18);
            paintChrome(g);
            AffineTransform beforeContent = g.getTransform(); Composite beforeComposite = g.getComposite();
            float contentAlpha = (float) Math.max(.01, Math.min(1, pageTransition));
            g.translate((1 - contentAlpha) * 28, 0); g.setComposite(AlphaComposite.SrcOver.derive(contentAlpha));
            switch (page) {
                case HOME -> paintHome(g);
                case NEWS -> paintNews(g);
                case PACK -> paintPack(g);
                case GAME -> paintGamePanel(g);
                case MAP -> paintWorldMap(g);
                case SETTINGS -> paintSettings(g);
                case ADMIN -> paintAdmin(g);
            }
            g.setTransform(beforeContent); g.setComposite(beforeComposite);
            if (selectedNews >= 0) paintArticleOverlay(g);
            if (newsComposeOpen) paintNewsComposer(g);
            if (notificationsOpen) paintNotifications(g);
            if (profileOpen) paintProfileMenu(g);
            if(bootActive)paintBootOverlay(g);else if(launchOverlayActive)paintLaunchOverlay(g);
            paintGlobalCrt(g);
            g.setColor(new Color(255,144,42,72)); g.drawRect(3,3,getWidth()-7,getHeight()-7);
            g.dispose();
        }

        private void paintWorld(Graphics2D g) {
            int w = getWidth(), h = getHeight();
            Rectangle feed=cameraBounds(w,h);Area terminal=new Area(new Rectangle(0,0,w,h));if(page==Page.HOME&&video.isReady())terminal.subtract(new Area(feed));Shape oldClip=g.getClip();g.clip(terminal);
            g.setPaint(new LinearGradientPaint(0,0,w,h,new float[]{0,.55f,1},new Color[]{new Color(8,3,1),new Color(22,9,3),new Color(5,2,1)}));g.fillRect(0,0,w,h);
            g.setColor(new Color(112,48,13,30));for(int y=0;y<h;y+=4)g.fillRect(0,y,w,1);
            Random noise=new Random((long)(time*18));for(int i=0;i<90;i++){int nx=noise.nextInt(Math.max(1,w)),ny=noise.nextInt(Math.max(1,h)),a=5+noise.nextInt(12);g.setColor(new Color(255,137,34,a));g.fillRect(nx,ny,1+noise.nextInt(2),1);}
            g.setClip(oldClip);
            if(page==Page.HOME)paintCameraFeedOverlay(g,feed);
        }

        private void paintCameraFeedOverlay(Graphics2D g,Rectangle feed){
            int left=feed.x,top=feed.y,right=feed.x+feed.width,bottom=feed.y+feed.height;
            Composite oldComposite=g.getComposite();Stroke oldStroke=g.getStroke();
            g.setColor(new Color(120,54,16,38));g.fillRect(left,top,feed.width,feed.height);g.setStroke(new BasicStroke(1));g.setColor(new Color(255,158,47,30));for(int y=top;y<bottom;y+=3)g.drawLine(left,y,right,y);
            int sweepY=top+(int)((time*43)%Math.max(1,feed.height));g.setColor(new Color(255,190,77,18));g.fillRect(left,sweepY,feed.width,2);
            Random grain=new Random((long)(time*14));for(int i=0;i<45;i++){int gx=left+grain.nextInt(Math.max(1,feed.width)),gy=top+grain.nextInt(Math.max(1,feed.height));g.setColor(new Color(255,180,74,10+grain.nextInt(22)));g.fillRect(gx,gy,1+grain.nextInt(3),1);}
            g.setColor(AMBER);g.drawRect(left-1,top-1,feed.width+1,feed.height+1);g.drawRect(left+4,top+4,feed.width-9,feed.height-9);
            g.setFont(cameraFont(13,Font.PLAIN));g.setColor(PAPER);int cameraNo=1+Math.floorMod(video.selected()==null?7:video.selected().hashCode(),12);String cameraLabel=String.format(Locale.ROOT,"OH-CAM %02d",cameraNo);g.drawString(cameraLabel,left+14,top+24);g.fillRect(left+15,top+35,6,6);g.drawString("REC",left+29,top+42);
            int totalFrames=(int)(time*25),frames=totalFrames%25,seconds=(totalFrames/25)%60,minutes=(totalFrames/1500)%60,hours=(totalFrames/90000)%24;
            String code=String.format(Locale.ROOT,"%02d:%02d:%02d:%02d",hours,minutes,seconds,frames);int codeWidth=g.getFontMetrics().stringWidth(code);g.drawString(code,right-codeWidth-10,bottom-12);
            double glitch=cameraGlitchStrength();if(glitch>0){int persistentAlpha=Math.min(178,42+(int)(glitch*126));g.setColor(new Color(0,0,0,persistentAlpha));g.fillRect(left+5,top+5,feed.width-9,feed.height-9);int bands=3+(int)(glitch*8);for(int i=0;i<bands;i++){int gy=top+Math.floorMod((int)(time*128+i*71),Math.max(1,feed.height)),gh=2+Math.floorMod(i*5+(int)(time*19),11);int alpha=(int)(35+glitch*112);g.setColor(new Color(i%2==0?8:255,i%2==0?2:132,i%2==0?1:34,Math.min(178,alpha)));g.fillRect(left+5,gy,feed.width-10,gh);g.setColor(new Color(255,190,80,Math.min(190,58+(int)(glitch*124))));g.drawLine(left+8,gy-1,right-8,gy-1);}if(((int)(time*8)&3)==0){g.setColor(new Color(255,132,34,Math.min(58,12+(int)(glitch*40))));g.fillRect(left+5,top+5,feed.width-9,feed.height-9);}double p=(time-cameraGlitchStart)/Math.max(.01,cameraGlitchEnd-cameraGlitchStart);boolean noSignal=p<.62&&Math.floorMod((int)(time*9),5)!=1;if(noSignal){String noSignalText="SIGNAL LOST";g.setFont(font(23,Font.PLAIN));int tw=g.getFontMetrics().stringWidth(noSignalText),bx=left+(feed.width-tw)/2-18,by=top+feed.height/2-25;int logoAlpha=Math.floorMod((int)(time*18),4)==0?145:230;g.setColor(new Color(5,2,1,logoAlpha));g.fillRect(bx,by,tw+36,48);g.setColor(new Color(255,174,66,logoAlpha));g.drawRect(bx,by,tw+36,48);g.drawString(noSignalText,bx+18,by+32);}}
            g.setComposite(oldComposite);g.setStroke(oldStroke);
        }

        private void paintGlobalCrt(Graphics2D g){int w=getWidth(),h=getHeight();g.setColor(new Color(0,0,0,24));for(int y=1;y<h;y+=4)g.fillRect(0,y,w,1);int flicker=(int)(4+4*Math.sin(time*17));g.setColor(new Color(255,132,26,Math.max(0,flicker)));g.fillRect(0,(int)((time*29)%Math.max(1,h)),w,1);double glitch=cameraGlitchStrength();if(glitch>0){int tears=1+(int)(glitch*3);for(int i=0;i<tears;i++){int gy=Math.floorMod((int)(time*211+i*193),Math.max(1,h));g.setColor(new Color(255,139,43,Math.min(70,12+(int)(glitch*54))));g.fillRect(0,gy,w,1+Math.floorMod(i+(int)(time*20),4));}if(glitch>.72&&((int)(time*17)&1)==0){g.setColor(new Color(0,0,0,38));g.fillRect(0,0,w,h);}}for(int i=0;i<20;i++){int a=4+i*2;g.setColor(new Color(0,0,0,a));g.drawRect(i,i,w-1-i*2,h-1-i*2);}}

        private double cameraGlitchStrength(){if(page!=Page.HOME||time<cameraGlitchStart||time>=cameraGlitchEnd)return 0;double p=(time-cameraGlitchStart)/Math.max(.01,cameraGlitchEnd-cameraGlitchStart),envelope;if(p<.12)envelope=p/.12;else if(p<.58)envelope=1;else{double recovery=(p-.58)/.42;envelope=Math.pow(1-recovery,1.65);}double analog=.92+.08*Math.abs(Math.sin(time*29));return Math.max(0,Math.min(1,envelope*analog));}
        private void triggerCameraGlitch(double duration){cameraGlitchStart=time;cameraGlitchEnd=time+duration;cameraVideoSwitched=false;nextCameraGlitchAt=cameraGlitchEnd+20+random.nextDouble()*18;}

        private void paintProceduralBackdrop(Graphics2D g, int w, int h) {
            g.setPaint(new LinearGradientPaint(0, 0, w, h, new float[]{0f, .52f, 1f}, new Color[]{new Color(2, 5, 9), new Color(7, 17, 30), new Color(12, 30, 51)}));
            g.fillRect(0, 0, w, h);

            float sunX = w * .79f, sunY = h * .22f, radius = Math.max(w, h) * .31f;
            g.setPaint(new RadialGradientPaint(new Point2D.Float(sunX, sunY), radius, new float[]{0f, .12f, .55f, 1f}, new Color[]{new Color(255, 216, 91, 105), new Color(222, 178, 61, 42), new Color(7, 19, 33, 10), new Color(3, 6, 11, 0)}));
            g.fill(new Ellipse2D.Float(sunX - radius, sunY - radius, radius * 2, radius * 2));
            int sunSize = Math.max(34, w / 29); g.setColor(new Color(241, 204, 75, 170)); g.fillRect((int)sunX-sunSize/2,(int)sunY-sunSize/2,sunSize,sunSize);

            paintPixelCloud(g, (int)(w*.53), (int)(h*.19), 22, new Color(219,230,232,18));
            paintPixelCloud(g, (int)(w*.78), (int)(h*.36), 18, new Color(219,230,232,13));
            paintBlockTerrain(g,w,h,(int)(h*.62),34,new Color(13,31,49,235),new Color(39,61,76,80),.12);
            paintBlockTerrain(g,w,h,(int)(h*.73),42,new Color(8,22,37,248),new Color(49,70,83,70),.26);
            paintBlockTerrain(g,w,h,(int)(h*.86),52,new Color(3,11,20,255),new Color(50,73,87,55),.42);
            paintCactus(g,(int)(w*.69),(int)(h*.61),18,72,new Color(20,53,60,180));
            paintCactus(g,(int)(w*.89),(int)(h*.70),15,59,new Color(18,48,56,170));
            paintFloatingBlock(g,(int)(w*.72),(int)(h*.30),25); paintFloatingBlock(g,(int)(w*.93),(int)(h*.23),17);

            if(logo!=null){Composite old=g.getComposite();g.setComposite(AlphaComposite.SrcOver.derive(.055f));int s=Math.min(430,h/2);g.drawImage(logo,w-s-38,(h-s)/2,s,s,null);g.setComposite(old);}
        }

        private void paintPixelCloud(Graphics2D g,int x,int y,int unit,Color color){
            g.setColor(color);g.fillRect(x,y,unit*5,unit);g.fillRect(x+unit,y-unit,unit*3,unit);g.fillRect(x+unit*5,y+unit,unit*2,unit);
        }

        private void paintBlockTerrain(Graphics2D g,int w,int h,int baseline,int block,Color fill,Color edge,double phase){
            Path2D terrain=new Path2D.Double();terrain.moveTo(SIDEBAR,h);int lastY=baseline;
            for(int x=SIDEBAR;x<=w+block;x+=block){
                int raw=(int)(Math.sin(x*.006+time*phase)*block*1.4+Math.sin(x*.017-time*phase*.7)*block*.55);
                int y=baseline+(raw/block)*block;terrain.lineTo(x,lastY);terrain.lineTo(x,y);lastY=y;
            }
            terrain.lineTo(w,h);terrain.closePath();g.setColor(fill);g.fill(terrain);g.setColor(edge);g.setStroke(new BasicStroke(1));
            for(int x=SIDEBAR;x<w;x+=block)g.drawLine(x,baseline-block*3,x,h);
            for(int y=baseline-block*2;y<h;y+=block)g.drawLine(SIDEBAR,y,w,y);
        }

        private void paintCactus(Graphics2D g,int x,int ground,int width,int height,Color color){
            g.setColor(color);g.fillRect(x-width/2,ground-height,width,height);g.fillRect(x-width*2,ground-height*2/3,width*2,width);g.fillRect(x-width*2,ground-height*2/3-width*2,width,width*2);g.fillRect(x+width/2,ground-height/2,width*2,width);g.fillRect(x+width*3/2,ground-height/2-width*2,width,width*2);
        }

        private void paintFloatingBlock(Graphics2D g,int x,int y,int size){
            double hover=Math.sin(time*.7+x)*4;int py=(int)(y+hover);g.setColor(new Color(21,43,61,150));g.fillRect(x,py,size,size);g.setColor(new Color(237,198,74,35));g.drawRect(x,py,size,size);g.drawLine(x+size/2,py,x+size/2,py+size);g.drawLine(x,py+size/2,x+size,py+size/2);
        }

        private void paintChrome(Graphics2D g) {
            int w = getWidth(), h = getHeight();
            if(!notificationsOpen)updateBounds.setBounds(0,0,0,0);
            int expanded=(int)(SIDEBAR+126*sidebarExpand);g.setColor(new Color(7,3,1,248));g.fillRect(0,0,expanded,h);g.setColor(LINE);g.drawLine(expanded-1,0,expanded-1,h);g.drawLine(0,69,w,69);paintLogo(g,21,18);

            String[] labels = {t("home"), t("news"), t("pack"), t("gamePanel"), t("worldMap"), t("settings"), t("admin")};
            for (int i = 0; i < labels.length; i++) {
                boolean adminVisible=apiClient.current()!=null&&apiClient.current().account().admin();
                if(i==6&&!adminVisible){navBounds[i].setBounds(0,0,0,0);continue;}
                int y = 128 + i * 70; navBounds[i] = new Rectangle(0, y - 25, expanded, 56);
                int pressY=pressedControl.equals("nav"+i)?(int)Math.round(2*pressDepth):0;
                boolean active = page.ordinal() == i, hover = hoverNav == i;
                if(hover&&!active){g.setColor(new Color(255,132,35,22));g.fillRect(7,y-24,expanded-14,53);}if(active){g.setColor(AMBER);g.drawLine(9,y-20+pressY,9,y+24);g.drawLine(9,y-20+pressY,20,y-20+pressY);g.drawLine(9,y+24,20,y+24);g.drawLine(expanded-10,y-20+pressY,expanded-10,y+24);}
                paintNavIcon(g,i,39,y+2+pressY,active?AMBER_HOT:hover?PAPER:MUTED);
                if(sidebarExpand>.08){Composite old=g.getComposite();g.setComposite(AlphaComposite.SrcOver.derive((float)sidebarExpand));g.setFont(font(12,Font.PLAIN));g.setColor(active?AMBER_HOT:hover?PAPER:MUTED);g.drawString(labels[i].toUpperCase(Locale.ROOT),70,y+7+pressY);g.setComposite(old);}
            }
            int headerX=expanded+18;g.setFont(font(12,Font.PLAIN));g.setColor(MUTED);String linkLabel=l("BAĞLANTI:","LINK:");g.drawString(linkLabel,headerX,29);g.setColor(serverSnapshot.online()?PAPER:AMBER);g.drawString(serverSnapshot.online()?l("ÇEVRİMİÇİ","ONLINE"):l("BEKLEMEDE","STANDBY"),headerX+g.getFontMetrics().stringWidth(linkLabel)+10,29);g.setColor(MUTED);g.drawString(l("DÜĞÜM: ","NODE: ")+"ERDVYN-FRONTIER",headerX,49);

            audioBounds=new Rectangle((SIDEBAR-38)/2,h-62,38,42);
            int sliderLength=(int)(120*volumeReveal);if(sliderLength>5){volumeBounds=new Rectangle(61,h-55,sliderLength+8,28);g.setColor(LINE);g.drawRect(volumeBounds.x,volumeBounds.y,sliderLength+6,22);int fill=(int)((sliderLength-4)*video.volume());g.setColor(AMBER);for(int sx=0;sx<fill;sx+=7)g.fillRect(volumeBounds.x+4+sx,volumeBounds.y+5,4,12);}else volumeBounds.setBounds(0,0,0,0);paintSpeakerIcon(g,audioBounds.x+19,audioBounds.y+21,video.isMuted(),hoverAudio?AMBER_HOT:PAPER,hoverAudio);

            String profileName=accountSession==null?l("GİRİŞ YOK","SIGNED OUT"):accountSession.name();String authState=accountSession==null?l("GEREKLİ","REQUIRED"):l("BAĞLI","LINKED");
            int profileW=242,profileX=w-454;profileBounds.setBounds(profileX,12,profileW,44);g.setColor(LINE);g.drawRect(profileX,12,profileW,44);if(playerHead!=null){g.drawImage(playerHead,profileX+6,17,34,34,null);g.setColor(AMBER);g.drawRect(profileX+5,16,35,35);}else{g.setColor(new Color(255,145,42,36));g.fillRect(profileX+6,17,34,34);g.setColor(AMBER);g.drawRect(profileX+5,16,35,35);}g.setFont(font(11,Font.PLAIN));g.setColor(MUTED);g.drawString(l("KULL:","USER:"),profileX+49,30);g.setColor(PAPER);g.drawString(profileName,profileX+100,30);g.setColor(MUTED);g.drawString(l("YETKİ:","AUTH:"),profileX+49,47);g.setColor(accountSession==null?AMBER:PAPER);g.drawString(authState,profileX+100,47);

            notificationBounds.setBounds(profileX-50,12,38,44);g.setColor(LINE);g.drawRect(notificationBounds.x,notificationBounds.y,notificationBounds.width,notificationBounds.height);paintBellIcon(g,notificationBounds.x+19,notificationBounds.y+21,notificationsOpen?PAPER:AMBER);if(!notifications.isEmpty()){g.setColor(PAPER);g.fillRect(notificationBounds.x+27,notificationBounds.y+7,5,5);}

            languageBounds=new Rectangle(w-196,12,78,44);g.setColor(LINE);g.drawRect(languageBounds.x,languageBounds.y,languageBounds.width,languageBounds.height);g.setFont(font(11,Font.PLAIN));g.setColor(language==Language.TR?PAPER:MUTED);g.drawString("TR",languageBounds.x+14,39);g.setColor(MUTED);g.drawString("/",languageBounds.x+35,39);g.setColor(language==Language.EN?PAPER:MUTED);g.drawString("EN",languageBounds.x+48,39);

            minimizeBounds = new Rectangle(w - 108, 15, 38, 40); closeBounds = new Rectangle(w - 56, 15, 38, 40);
            g.setColor(MUTED); g.setStroke(new BasicStroke(1)); g.drawLine(w - 99, 36, w - 87, 36);
            g.drawLine(w - 44, 29, w - 33, 40); g.drawLine(w - 33, 29, w - 44, 40);
        }

        private void paintHome(Graphics2D g) {
            int x=contentLeft(),top=108;Rectangle feed=cameraBounds(getWidth(),getHeight());int leftW=Math.max(330,feed.x-x-30);
            paintSectionGlyph(g,0,x,top+5);g.setFont(font(38,Font.PLAIN));g.setColor(PAPER);g.drawString("ERDVYN",x+48,top+39);g.setFont(font(17,Font.PLAIN));g.setColor(AMBER);g.drawString("THE FRONTIER",x+48,top+67);g.setColor(LINE);g.drawLine(x+48,top+82,x+leftW,top+82);
            int py=top+112;paintNavIcon(g,3,x+11,py-4,AMBER);terminalLabel(g,l("SİSTEM PROFİLİ","SYSTEM PROFILE"),x+30,py);terminalPanel(g,x,py+18,leftW,222);String packageData=packSummary.files()==0?l("ÖLÇÜLÜYOR","MEASURING"):String.format(Locale.ROOT,"%d MOD / %d FILE / %s",packSummary.mods(),packSummary.files(),formatBytes(packSummary.bytes()));String[][] rows={{l("MC SÜRÜMÜ","MC VERSION"),LauncherPaths.GAME_VERSION},{l("YÜKLEYİCİ","LOADER"),"NEOFORGE 21.1.243"},{l("PAKET VERİSİ","PACK DATA"),packageData},{l("DOSYA DURUMU","FILE STATUS"),packStatus.isBlank()?l("DENETİM BEKLİYOR","AUDIT PENDING"):l("DENETLENDİ","AUDITED")},{l("KURULUM","INSTANCE"),"THE-FRONTIER / MANAGED"}};for(int i=0;i<rows.length;i++){int ry=py+55+i*32;g.setFont(font(13,Font.PLAIN));g.setColor(MUTED);g.drawString(rows[i][0],x+18,ry);g.setColor(PAPER);g.drawString(rows[i][1],x+154,ry);g.setColor(new Color(255,145,42,28));g.drawLine(x+14,ry+9,x+leftW-14,ry+9);}
            int buttonY=py+270;playBounds.setBounds(x,buttonY,Math.min(280,leftW),54);String playText=gameLaunching?l("BAŞLATILIYOR","STARTING"):accountSession==null?l("GİRİŞ YAP VE OYNA","SIGN IN & PLAY"):t("play");terminalButton(g,playBounds,hoverPlay,"[ "+playText.toUpperCase(Locale.ROOT)+" ]");if(leftW>=460){g.setFont(font(12,Font.PLAIN));g.setColor(MUTED);g.drawString(l("BAŞLATMA HATTI 01","LAUNCH BUS 01"),x+300,buttonY+22);g.setColor(AMBER);g.drawString(accountSession==null?l("HESAP GEREKLİ","ACCOUNT REQUIRED"):l("HAZIR","READY"),x+300,buttonY+44);g.setColor(LINE);g.drawLine(x+405,buttonY+39,x+leftW-8,buttonY+39);}paintClientDiagnostics(g,x,buttonY+76,leftW,getHeight()-buttonY-104);updateBounds.setBounds(0,0,0,0);
            paintServerStatus(g,feed);
        }

        private void paintNews(Graphics2D g) {
            int x=contentLeft(),y=104,w=getWidth();sectionTitle(g,x,y,l("DUYURU TERMİNALİ","DISPATCH"),t("newsTitle"),"");
            for(Rectangle bounds:newsBounds)bounds.setBounds(0,0,0,0);newsComposeBounds.setBounds(0,0,0,0);
            boolean admin=apiClient.current()!=null&&apiClient.current().account().admin();if(admin){newsComposeBounds.setBounds(w-238,y+4,206,38);terminalButton(g,newsComposeBounds,false,"[ "+l("YENİ DUYURU","NEW DISPATCH")+" ]");}
            int panelY=y+72,panelW=w-x-32,panelH=getHeight()-panelY-34;terminalPanel(g,x,panelY,panelW,panelH);g.setFont(font(12,Font.PLAIN));g.setColor(AMBER);g.drawString(l("TARİH","DATE"),x+18,panelY+31);g.drawString("ID",x+154,panelY+31);g.drawString(l("KONU","SUBJECT"),x+252,panelY+31);g.drawString(l("DURUM","STATUS"),x+panelW-118,panelY+31);g.setColor(LINE);g.drawLine(x+12,panelY+44,x+panelW-12,panelY+44);
            if(newsPosts.isEmpty()){g.setFont(font(14,Font.PLAIN));g.setColor(MUTED);centered(g,apiClient.configured()?l("-- DUYURU KAYDI YOK --","-- NO DISPATCH RECORDS --"):l("-- ERDVYN API YAPILANDIRILMADI --","-- ERDVYN API NOT CONFIGURED --"),x+panelW/2,panelY+94);}
            else {int rowH=46,first=Math.max(0,Math.min(newsPosts.size()-1,newsScroll/rowH));newsFirstVisible=first;int visible=Math.min(newsBounds.length,Math.max(0,(panelH-58)/rowH));for(int slot=0;slot<visible&&first+slot<newsPosts.size();slot++){int index=first+slot,rowY=panelY+50+slot*rowH;ErdvynApiClient.NewsPost post=newsPosts.get(index);newsBounds[slot].setBounds(x+10,rowY,panelW-20,rowH);if(hoverNews==slot){g.setColor(new Color(255,139,43,25));g.fillRect(x+11,rowY+1,panelW-22,rowH-1);}g.setColor(new Color(145,65,18,70));g.drawLine(x+12,rowY+rowH,x+panelW-12,rowY+rowH);g.setFont(font(12,Font.PLAIN));g.setColor(MUTED);g.drawString(formatNewsDate(post.publishedAt()),x+18,rowY+28);g.drawString(String.format(Locale.ROOT,"%04d",post.id()),x+154,rowY+28);g.setColor(PAPER);String title=post.title().length()>58?post.title().substring(0,55)+"...":post.title();g.drawString(title,x+252,rowY+28);g.setColor(AMBER);g.drawString(l("YAYINDA","PUBLIC"),x+panelW-118,rowY+28);}}
            if(!newsNotice.isBlank()){g.setFont(font(11,Font.PLAIN));g.setColor(MUTED);g.drawString(newsNotice,x+18,panelY+panelH-14);}
        }

        private void drawCover(Graphics2D g,BufferedImage image,int x,int y,int w,int h){double scale=Math.max(w/(double)image.getWidth(),h/(double)image.getHeight());int dw=(int)(image.getWidth()*scale),dh=(int)(image.getHeight()*scale);g.drawImage(image,x+(w-dw)/2,y+(h-dh)/2,dw,dh,null);}

        private void paintArticleOverlay(Graphics2D g){
            if(selectedNews<0||selectedNews>=newsPosts.size()){selectedNews=-1;return;}ErdvynApiClient.NewsPost post=newsPosts.get(selectedNews);
            int w=getWidth(),h=getHeight(),boxW=Math.min(680,w-180),boxH=300,x=(w-boxW)/2,y=(h-boxH)/2;
            g.setColor(new Color(9,3,0,218));g.fillRect(SIDEBAR,70,w-SIDEBAR,h-70);
            terminalPanel(g,x,y,boxW,boxH);
            g.setFont(font(11,Font.PLAIN));g.setColor(AMBER);g.drawString("DISPATCH / "+t("news").toUpperCase(Locale.ROOT),x+22,y+31);
            g.setColor(LINE);g.drawLine(x+12,y+44,x+boxW-12,y+44);
            g.setFont(font(23,Font.PLAIN));g.setColor(PAPER);String title=post.title().toUpperCase(Locale.ROOT);g.drawString(title.length()>42?title.substring(0,39)+"...":title,x+22,y+82);
            g.setFont(font(12,Font.PLAIN));g.setColor(MUTED);drawWrapped(g,post.body(),x+22,y+122,boxW-44,22,5);
            g.setColor(LINE);g.drawLine(x+12,y+244,x+boxW-12,y+244);
            g.setFont(font(10,Font.PLAIN));g.setColor(MUTED);g.drawString("DATE "+formatNewsDate(post.publishedAt())+"   /   ID "+String.format(Locale.ROOT,"%04d",post.id()),x+22,y+274);
            articleCloseBounds.setBounds(x+boxW-48,y+10,32,26);g.setFont(font(13,Font.PLAIN));g.setColor(AMBER);g.drawString("[ X ]",articleCloseBounds.x,articleCloseBounds.y+18);
        }

        private void paintNewsComposer(Graphics2D g){
            int w=getWidth(),h=getHeight(),boxW=Math.min(720,w-180),boxH=410,x=(w-boxW)/2,y=(h-boxH)/2;g.setColor(new Color(9,3,0,218));g.fillRect(SIDEBAR,70,w-SIDEBAR,h-70);terminalPanel(g,x,y,boxW,boxH);g.setFont(font(13,Font.PLAIN));g.setColor(AMBER);g.drawString(l("YÖNETİCİ DUYURU TERMİNALİ","ADMIN DISPATCH TERMINAL"),x+20,y+30);g.setColor(LINE);g.drawLine(x+12,y+44,x+boxW-12,y+44);
            g.setFont(font(11,Font.PLAIN));g.setColor(MUTED);g.drawString(l("BAŞLIK","TITLE"),x+20,y+70);newsTitleInputBounds.setBounds(x+18,y+80,boxW-36,42);g.setColor(newsField==0?AMBER:LINE);g.drawRect(newsTitleInputBounds.x,newsTitleInputBounds.y,newsTitleInputBounds.width,newsTitleInputBounds.height);g.setFont(font(13,Font.PLAIN));g.setColor(newsTitleDraft.isBlank()?MUTED:PAPER);g.drawString(newsTitleDraft.isBlank()?l("Duyuru başlığı...","Dispatch title..."):newsTitleDraft+(newsField==0&&((int)(time*2)&1)==0?"_":""),x+30,y+107);
            g.setFont(font(11,Font.PLAIN));g.setColor(MUTED);g.drawString(l("İÇERİK","BODY"),x+20,y+151);newsBodyInputBounds.setBounds(x+18,y+162,boxW-36,158);g.setColor(newsField==1?AMBER:LINE);g.drawRect(newsBodyInputBounds.x,newsBodyInputBounds.y,newsBodyInputBounds.width,newsBodyInputBounds.height);g.setFont(font(12,Font.PLAIN));g.setColor(newsBodyDraft.isBlank()?MUTED:PAPER);drawWrapped(g,newsBodyDraft.isBlank()?l("Herkesin göreceği duyuruyu yaz...","Write the dispatch everyone will see..."):newsBodyDraft+(newsField==1&&((int)(time*2)&1)==0?"_":""),x+30,y+188,boxW-60,20,7);
            newsCancelBounds.setBounds(x+18,y+344,180,42);newsPublishBounds.setBounds(x+boxW-238,y+344,220,42);terminalButton(g,newsCancelBounds,false,"[ "+l("İPTAL","CANCEL")+" ]");terminalButton(g,newsPublishBounds,false,"[ "+(newsPublishInProgress?l("YAYINLANIYOR","PUBLISHING"):l("YAYINLA","PUBLISH"))+" ]");
        }

        private static String formatNewsDate(long epoch){return DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epoch));}

        private void paintProfileMenu(Graphics2D g){
            int boxW=354,x=profileBounds.x+profileBounds.width-boxW,y=66;terminalPanel(g,x,y,boxW,234);g.setFont(font(13,Font.PLAIN));g.setColor(AMBER);g.drawString(l("HESAP TERMİNALİ","ACCOUNT TERMINAL"),x+16,y+27);g.setColor(LINE);g.drawLine(x+12,y+38,x+boxW-12,y+38);g.setFont(font(12,Font.PLAIN));g.setColor(MUTED);g.drawString(l("KULLANICI","USER"),x+18,y+67);g.setColor(PAPER);g.drawString(accountSession==null?"--":accountSession.name(),x+138,y+67);g.setColor(MUTED);g.drawString("UUID",x+18,y+91);g.setColor(accountSession==null?AMBER:PAPER);String uuid=accountSession==null?l("BAĞLANTI GEREKLİ","LINK REQUIRED"):accountSession.uuid().toString();g.drawString(uuid.length()>24?uuid.substring(0,24)+"...":uuid,x+138,y+91);microsoftBounds.setBounds(x+18,y+112,boxW-36,38);erdvynAccountBounds.setBounds(x+18,y+160,boxW-36,38);terminalButton(g,microsoftBounds,false,"[ "+(accountLoginInProgress?l("GİRİŞ BEKLENİYOR","WAITING FOR SIGN-IN"):t("microsoftLogin"))+" ]");terminalButton(g,erdvynAccountBounds,false,"[ "+t("erdvynLogin")+" ]");if(!accountNotice.isBlank()){g.setFont(font(10,Font.PLAIN));g.setColor(MUTED);drawWrapped(g,accountNotice,x+18,y+216,boxW-36,14,2);}
        }

        private void paintNotifications(Graphics2D g){
            int boxW=390,x=notificationBounds.x+notificationBounds.width-boxW,y=66,rows=Math.max(1,Math.min(5,notifications.size())),updateH=launcherInstaller==null?0:54,boxH=58+rows*58+updateH;notificationPanelBounds.setBounds(x,y,boxW,boxH);terminalPanel(g,x,y,boxW,boxH);g.setFont(font(13,Font.PLAIN));g.setColor(AMBER);g.drawString(l("BİLDİRİM HATTI","NOTIFICATION BUS"),x+16,y+27);g.setColor(LINE);g.drawLine(x+12,y+39,x+boxW-12,y+39);if(notifications.isEmpty()){g.setFont(font(11,Font.PLAIN));g.setColor(MUTED);g.drawString(l("[ YENİ BİLDİRİM YOK ]","[ NO NEW NOTIFICATIONS ]"),x+18,y+73);}else{int first=Math.max(0,notifications.size()-5);for(int i=first;i<notifications.size();i++){int row=i-first,ry=y+55+row*58;g.setColor(new Color(255,145,42,22));g.fillRect(x+12,ry-6,boxW-24,48);g.setColor(row==rows-1?AMBER:LINE);g.drawLine(x+12,ry+47,x+boxW-12,ry+47);g.setFont(font(11,Font.PLAIN));g.setColor(PAPER);drawWrapped(g,notifications.get(i),x+22,ry+13,boxW-44,15,2);}}if(launcherInstaller!=null){updateBounds.setBounds(x+14,y+boxH-46,boxW-28,34);terminalButton(g,updateBounds,false,"[ "+l("LAUNCHER GÜNCELLEMESİNİ KUR","INSTALL LAUNCHER UPDATE")+" ]");}else updateBounds.setBounds(0,0,0,0);}

        private static void paintBellIcon(Graphics2D g,int cx,int cy,Color color){g.setColor(color);g.drawLine(cx-6,cy+5,cx+6,cy+5);g.drawLine(cx-6,cy+5,cx-4,cy+2);g.drawLine(cx+6,cy+5,cx+4,cy+2);g.drawLine(cx-4,cy+2,cx-4,cy-5);g.drawLine(cx+4,cy+2,cx+4,cy-5);g.drawLine(cx-4,cy-5,cx-2,cy-8);g.drawLine(cx+4,cy-5,cx+2,cy-8);g.drawLine(cx-2,cy-8,cx+2,cy-8);g.fillRect(cx-1,cy+8,3,2);}

        private void paintPack(Graphics2D g) {
            int x=contentLeft(),y=104,w=getWidth();sectionTitle(g,x,y,l("PAKET İZLEYİCİ","PACKAGE MONITOR"),t("packTitle"),"");int panelY=y+72,panelW=w-x-32,panelH=getHeight()-panelY-34;terminalPanel(g,x,panelY,panelW,panelH);g.setFont(font(12,Font.PLAIN));g.setColor(AMBER);g.drawString(l("GERÇEK DOSYA DENETİMİ / SHA-256","REAL FILE AUDIT / SHA-256"),x+18,panelY+29);g.setColor(packVerifying?AMBER:PAPER);String state=packVerifying?l("ÇALIŞIYOR","RUNNING"):l("HAZIR","READY");g.drawString(state,x+panelW-g.getFontMetrics().stringWidth(state)-18,panelY+29);g.setColor(LINE);g.drawLine(x+12,panelY+42,x+panelW-12,panelY+42);
            int progressY=panelY+58;g.setColor(new Color(82,35,9));g.fillRect(x+18,progressY,panelW-36,18);g.setColor(AMBER);g.fillRect(x+18,progressY,(int)((panelW-36)*Math.max(0,Math.min(1,packProgress))),18);g.setFont(font(11,Font.PLAIN));g.setColor(PAPER);centered(g,String.format(Locale.ROOT,"%03d%%  /  %d MOD  /  %d %s  /  %s",(int)(packProgress*100),packSummary.mods(),packSummary.files(),l("DOSYA","FILES"),formatBytes(packSummary.bytes())),x+panelW/2,progressY+14);
            int logY=panelY+92,logH=Math.max(120,panelH-166);terminalPanel(g,x+16,logY,panelW-32,logH);g.setFont(font(11,Font.PLAIN));int maxLines=Math.max(5,(logH-34)/19),start=Math.max(0,packLog.size()-maxLines);if(packLog.isEmpty()){g.setColor(MUTED);g.drawString(l("> DOSYA DENETİMİ BAŞLATILMADI","></ FILE AUDIT HAS NOT STARTED"),x+30,logY+28);}else for(int i=start;i<packLog.size();i++){String line=packLog.get(i);g.setColor(line.contains("[FAIL]")||line.contains("[MISSING]")?RED:line.contains("[GET]")?AMBER:PAPER);g.drawString(line,x+30,logY+27+(i-start)*19);}
            int by=panelY+panelH-58,statusX;if(!packInstalled){installPackBounds.setBounds(x+16,by,230,40);verifyBounds.setBounds(x+260,by,230,40);folderBounds.setBounds(x+504,by,190,40);terminalButton(g,installPackBounds,false,packVerifying?"[ "+l("KURULUYOR","INSTALLING")+" ]":"[ "+l("MOD PAKETİNİ KUR","INSTALL MODPACK")+" ]");statusX=x+710;}else{installPackBounds.setBounds(0,0,0,0);verifyBounds.setBounds(x+16,by,260,40);folderBounds.setBounds(x+290,by,210,40);statusX=x+520;}terminalButton(g,verifyBounds,hoverVerify,packVerifying?"[ "+l("DOĞRULANIYOR","VERIFYING")+" ]":"[ "+t("repair")+" ]");terminalButton(g,folderBounds,hoverFolder,"[ "+t("openFolder")+" ]");if(!packStatus.isBlank()){g.setFont(font(11,Font.PLAIN));g.setColor(MUTED);String status=packStatus.length()>54?packStatus.substring(0,51)+"...":packStatus;g.drawString(status,statusX,by+25);}
        }

        private void paintSettings(Graphics2D g) {
            int x=contentLeft(),y=104,w=getWidth(),h=getHeight();sectionTitle(g,x,y,l("SİSTEM YAPILANDIRMASI","SYSTEM CONFIGURATION"),t("settingsTitle"),"");int panelY=y+72,panelW=w-x-32,panelH=h-panelY-30,gap=14,leftW=(panelW-gap)/2,rightX=x+leftW+gap,rightW=panelW-leftW-gap;terminalPanel(g,x,panelY,leftW,panelH);terminalPanel(g,rightX,panelY,rightW,panelH);
            g.setFont(font(13,Font.PLAIN));g.setColor(AMBER);g.drawString(l("OYUN / BELLEK","GAME / MEMORY"),x+18,panelY+28);g.drawString(l("GÖRÜNTÜ / KLASÖRLER","VIDEO / FOLDERS"),rightX+18,panelY+28);g.setColor(LINE);g.drawLine(x+12,panelY+40,x+leftW-12,panelY+40);g.drawLine(rightX+12,panelY+40,rightX+rightW-12,panelY+40);
            boolean compact=panelH<500;int rowH=compact?42:46,rowGap=compact?6:10,toggleH=compact?46:56,innerTop=panelY+54;
            int ly=innerTop;optionStepper(g,l("AYRILAN RAM","ALLOCATED RAM"),gameOptions.ramGb()+" GB  "+l("(8 GB ÖNERİLEN)","(8 GB RECOMMENDED)"),x+18,ly,leftW-36,rowH,ramMinusBounds,ramPlusBounds,.68);ly+=rowH+rowGap;optionStepper(g,l("GÖRÜŞ MESAFESİ","RENDER DISTANCE"),gameOptions.renderDistance()+" "+l("CHUNK","CHUNKS"),x+18,ly,leftW-36,rowH,renderMinusBounds,renderPlusBounds,.48);ly+=rowH+rowGap;optionStepper(g,l("SİMÜLASYON","SIMULATION"),gameOptions.simulationDistance()+" "+l("CHUNK","CHUNKS"),x+18,ly,leftW-36,rowH,simulationMinusBounds,simulationPlusBounds,.36);ly+=rowH+rowGap+2;
            settingRow(g,x+18,ly,leftW-36,toggleH,l("OTOMATİK GÜNCELLEME","AUTO UPDATE"),autoUpdate,0);ly+=toggleH+rowGap;settingRow(g,x+18,ly,leftW-36,toggleH,l("OTOMATİK BAĞLAN","AUTO CONNECT"),autoConnect,1);ly+=toggleH+(compact?12:24);
            String[] folderLabels={l("INSTANCE KLASÖRÜ","INSTANCE FOLDER"),"MODS","RESOURCEPACKS","SHADERPACKS"};int folderH=compact?32:36,folderGap=compact?7:10,folderW=(leftW-48)/2;for(int i=0;i<settingsFolderBounds.length;i++){int fy=ly+(i/2)*(folderH+folderGap),fx=x+18+(i%2)*(folderW+12);settingsFolderBounds[i].setBounds(fx,fy,folderW,folderH);terminalButton(g,settingsFolderBounds[i],false,"[ "+folderLabels[i]+" ]");}
            int ry=innerTop;settingRow(g,rightX+18,ry,rightW-36,toggleH,l("TAM EKRAN","FULLSCREEN"),gameOptions.fullscreen(),2);ry+=toggleH+rowGap;vsyncBounds.setBounds(rightX+18,ry,rightW-36,toggleH);drawSimpleToggle(g,vsyncBounds,l("DİKEY SENKRONİZASYON","VERTICAL SYNC"),gameOptions.vsync());ry+=toggleH+rowGap+2;optionStepper(g,l("MAKSİMUM FPS","MAXIMUM FPS"),Integer.toString(gameOptions.maxFps()),rightX+18,ry,rightW-36,rowH,fpsMinusBounds,fpsPlusBounds,.78);ry+=rowH+rowGap;optionStepper(g,l("ARAYÜZ ÖLÇEĞİ","GUI SCALE"),gameOptions.guiScale()==0?l("OTOMATİK","AUTO"):Integer.toString(gameOptions.guiScale()),rightX+18,ry,rightW-36,rowH,guiMinusBounds,guiPlusBounds,.42);ry+=rowH+(compact?10:18);
            int langH=compact?34:38;g.setFont(font(11,Font.PLAIN));g.setColor(MUTED);g.drawString(l("ARAYÜZ DİLİ","INTERFACE LANGUAGE"),rightX+24,ry+langH/2+5);settingsLanguageBounds.setBounds(rightX+rightW-190,ry,164,langH);terminalButton(g,settingsLanguageBounds,false,language==Language.TR?"[ TR ]   EN":"TR   [ EN ]");ry+=langH+(compact?9:16);g.setColor(LINE);g.drawLine(rightX+14,ry,rightX+rightW-14,ry);ry+=compact?18:26;
            if(!compact){g.setFont(dataFont(11,Font.PLAIN));g.setColor(PAPER);drawWrapped(g,l("Minecraft açıldıktan sonra launcher tamamen kapanır. Ayarlar options.txt dosyasına anında yazılır.","The launcher exits completely after Minecraft starts. Changes are written to options.txt immediately."),rightX+20,ry,rightW-40,18,3);ry+=58;}
            int specialH=compact?32:36,specialW=(rightW-52)/2,specialY=Math.min(panelY+panelH-specialH-16,ry);optionsFileBounds.setBounds(rightX+18,specialY,specialW,specialH);configFolderBounds.setBounds(rightX+30+specialW,specialY,specialW,specialH);terminalButton(g,optionsFileBounds,false,"[ OPTIONS.TXT ]");terminalButton(g,configFolderBounds,false,"[ CONFIG ]");
        }

        private void paintAdmin(Graphics2D g){
            ErdvynApiClient.Login active=apiClient.current();boolean admin=active!=null&&active.account().admin(),root=admin&&active.account().rootAdmin();
            int x=contentLeft(),y=104,w=getWidth(),panelY=y+72,panelW=w-x-32,panelH=getHeight()-panelY-34;
            sectionTitle(g,x,y,l("GÜVENLİ YÖNETİM HATTI","SECURE CONTROL BUS"),l("YÖNETİCİ TERMİNALİ","ADMIN TERMINAL"),"");terminalPanel(g,x,panelY,panelW,panelH);
            g.setFont(font(12,Font.PLAIN));g.setColor(admin?PAPER:RED);g.drawString(admin?l("DOĞRULANMIŞ MICROSOFT OTURUMU","VERIFIED MICROSOFT SESSION"):l("ERİŞİM REDDEDİLDİ","ACCESS DENIED"),x+18,panelY+30);g.setColor(LINE);g.drawLine(x+12,panelY+43,x+panelW-12,panelY+43);
            if(!admin){g.setFont(font(13,Font.PLAIN));g.setColor(MUTED);g.drawString(l("Bu terminal yalnızca sunucunun doğruladığı yönetici hesaplarına açıktır.","This terminal is available only to server-verified admin accounts."),x+24,panelY+82);return;}
            int half=(panelW-54)/2,left=x+18,right=left+half+18;g.setFont(font(12,Font.PLAIN));g.setColor(AMBER);g.drawString(l("HESAP YETKİSİ / SADECE KÖK YÖNETİCİ","ACCOUNT ACCESS / ROOT ADMIN ONLY"),left,panelY+76);g.drawString(l("MINECRAFT KOMUT KUYRUĞU","MINECRAFT COMMAND QUEUE"),right,panelY+76);
            adminTargetBounds.setBounds(left,panelY+92,half,42);adminCommandBounds.setBounds(right,panelY+92,half,42);paintTerminalInput(g,adminTargetBounds,adminField==0,adminTargetDraft,l("Oyuncu adı veya UUID","Player name or UUID"));paintTerminalInput(g,adminCommandBounds,adminField==1,adminCommandDraft,l("İzinli Minecraft komutu","Allowlisted Minecraft command"));
            int bw=(half-12)/2;if(root){adminGrantBounds.setBounds(left,panelY+150,bw,40);adminRevokeBounds.setBounds(left+bw+12,panelY+150,bw,40);terminalButton(g,adminGrantBounds,false,"[ "+l("YÖNETİCİ YAP","GRANT ADMIN")+" ]");terminalButton(g,adminRevokeBounds,false,"[ "+l("YETKİYİ AL","REVOKE ADMIN")+" ]");}else{adminGrantBounds.setBounds(0,0,0,0);adminRevokeBounds.setBounds(0,0,0,0);g.setColor(LINE);g.drawRect(left,panelY+150,half,40);g.setFont(font(11,Font.PLAIN));g.setColor(MUTED);centered(g,l("YETKİ DEVRİ: KÖK YÖNETİCİYE KİLİTLİ","DELEGATION: LOCKED TO ROOT ADMIN"),left+half/2,panelY+175);}
            adminBanBounds.setBounds(right,panelY+150,bw,40);adminUnbanBounds.setBounds(right+bw+12,panelY+150,bw,40);terminalButton(g,adminBanBounds,false,"[ "+l("OYUNCUYU BANLA","BAN PLAYER")+" ]");terminalButton(g,adminUnbanBounds,false,"[ "+l("BANI KALDIR","UNBAN PLAYER")+" ]");
            adminExecuteBounds.setBounds(right,panelY+206,half,42);terminalButton(g,adminExecuteBounds,false,"[ "+(adminActionInProgress?l("İLETİLİYOR","DISPATCHING"):l("KOMUTU İLET","DISPATCH COMMAND"))+" ]");
            g.setColor(LINE);g.drawLine(x+12,panelY+272,x+panelW-12,panelY+272);g.setFont(font(12,Font.PLAIN));g.setColor(AMBER);g.drawString(l("YÖNETİCİLER / DOĞRULANMIŞ HESAPLAR","ADMINISTRATORS / VERIFIED ACCOUNTS"),x+20,panelY+300);
            int listY=panelY+316,rowH=50;if(adminAccounts.isEmpty()){g.setFont(font(11,Font.PLAIN));g.setColor(MUTED);g.drawString(l("-- YÖNETİCİ LİSTESİ SENKRONİZE EDİLİYOR --","-- SYNCHRONIZING ADMIN LIST --"),x+22,listY+28);}else for(int i=0;i<adminAccounts.size()&&i<4;i++){ErdvynApiClient.AdminAccount item=adminAccounts.get(i);int ry=listY+i*rowH;g.setColor(new Color(255,145,42,18));g.fillRect(x+18,ry,panelW-36,rowH-4);g.setColor(LINE);g.drawRect(x+18,ry,panelW-36,rowH-4);BufferedImage head=adminHeads.get(item.uuid());if(head!=null)g.drawImage(head,x+24,ry+6,34,34,null);else{g.setColor(new Color(255,145,42,30));g.fillRect(x+24,ry+6,34,34);g.setColor(AMBER);g.drawRect(x+24,ry+6,34,34);}g.setFont(font(12,Font.PLAIN));g.setColor(PAPER);g.drawString(item.minecraftName(),x+72,ry+20);g.setFont(font(10,Font.PLAIN));g.setColor(item.root()?AMBER_HOT:MUTED);g.drawString(item.root()?"ROOT ADMIN":"ADMIN",x+72,ry+38);String date=item.grantedAt()<=0?"--":DateTimeFormatter.ofPattern("dd.MM.yyyy  HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(item.grantedAt()));g.setColor(MUTED);g.drawString(l("YETKİ TARİHİ ","GRANTED ")+date,x+panelW-260,ry+29);}
            int footerY=panelY+panelH-56;g.setFont(font(10,Font.PLAIN));g.setColor(root?PAPER:AMBER);g.drawString(root?l("KÖK YÖNETİCİ / UUID KİLİTLİ","ROOT ADMIN / UUID LOCKED"):l("YÖNETİCİ / YETKİ DEVRİ KAPALI","ADMIN / DELEGATION DISABLED"),x+20,footerY);if(!adminNotice.isBlank()){g.setColor(adminNotice.startsWith("ERROR")||adminNotice.startsWith("HATA")?RED:PAPER);String notice=adminNotice.length()>90?adminNotice.substring(0,87)+"...":adminNotice;g.drawString(notice,x+260,footerY);}
        }

        private void paintTerminalInput(Graphics2D g,Rectangle bounds,boolean focused,String value,String placeholder){g.setColor(focused?AMBER:LINE);g.drawRect(bounds.x,bounds.y,bounds.width,bounds.height);g.setFont(font(12,Font.PLAIN));g.setColor(value.isBlank()?MUTED:PAPER);String shown=value.isBlank()?placeholder:value+(focused&&((int)(time*2)&1)==0?"_":"");if(shown.length()>52)shown=shown.substring(shown.length()-52);g.drawString("> "+shown,bounds.x+12,bounds.y+26);}

        private void paintGamePanel(Graphics2D g){
            int x=contentLeft(),y=104,w=getWidth(),h=getHeight();sectionTitle(g,x,y,l("AĞ TERMİNALİ","NETWORK TERMINAL"),t("gamePanel"),"");int panelY=y+72,panelW=w-x-32,panelH=h-panelY-34,leftW=Math.max(275,Math.min(340,panelW/3));terminalPanel(g,x,panelY,leftW,panelH);terminalPanel(g,x+leftW+12,panelY,panelW-leftW-12,panelH);g.setFont(font(12,Font.PLAIN));g.setColor(AMBER);g.drawString(l("OYUNCU / BAĞLANTI","PLAYER / LINK"),x+14,panelY+28);g.setColor(MUTED);g.drawString(String.format(Locale.ROOT,"%02d / %02d",onlinePlayers.size(),Math.max(0,serverSnapshot.maxPlayers())),x+leftW-76,panelY+28);g.setColor(LINE);g.drawLine(x+10,panelY+40,x+leftW-10,panelY+40);
            if(onlinePlayers.isEmpty()){g.setFont(font(11,Font.PLAIN));g.setColor(MUTED);g.drawString(l("-- BAĞLANTI BEKLEMEDE --","-- LINK STANDBY --"),x+16,panelY+72);}else for(int i=0;i<onlinePlayers.size()&&i<8;i++){int py=panelY+60+i*42;g.setColor(LINE);g.drawRect(x+14,py-12,22,22);g.setColor(AMBER);g.fillRect(x+20,py-6,10,10);g.setFont(font(11,Font.PLAIN));g.setColor(PAPER);g.drawString(onlinePlayers.get(i),x+48,py+3);g.setColor(MUTED);g.drawString(l("PING İYİ","PING OK"),x+leftW-78,py+3);}
            int chatX=x+leftW+12,chatW=panelW-leftW-12;g.setFont(font(12,Font.PLAIN));g.setColor(AMBER);g.drawString(l("ERDVYN SOHBET HATTI","ERDVYN CHAT BUS"),chatX+14,panelY+28);g.setColor(LINE);g.drawLine(chatX+10,panelY+40,chatX+chatW-10,panelY+40);int baseline=panelY+70;List<ChatLine> visible=chatLines.size()>10?chatLines.subList(chatLines.size()-10,chatLines.size()):chatLines;for(ChatLine line:visible){g.setFont(font(10,Font.PLAIN));g.setColor(MUTED);g.drawString(line.time.format(DateTimeFormatter.ofPattern("HH:mm")),chatX+14,baseline);g.setColor(AMBER);g.fillRect(chatX+65,baseline-10,12,12);g.setColor(PAPER);g.drawString(line.author,chatX+86,baseline);g.setColor(MUTED);drawWrapped(g,line.message,chatX+164,baseline,chatW-184,16,2);baseline+=34;}
            chatInputBounds.setBounds(chatX+12,panelY+panelH-52,chatW-132,36);chatSendBounds.setBounds(chatX+chatW-110,panelY+panelH-52,98,36);g.setColor(chatFocused?AMBER:LINE);g.drawRect(chatInputBounds.x,chatInputBounds.y,chatInputBounds.width,chatInputBounds.height);g.setFont(font(11,Font.PLAIN));g.setColor(chatDraft.isBlank()?MUTED:PAPER);g.drawString("> "+(chatDraft.isBlank()?t("writeMessage"):chatDraft+((int)(time*2)%2==0&&chatFocused?"_":"")),chatInputBounds.x+10,chatInputBounds.y+23);terminalButton(g,chatSendBounds,false,"[ "+t("send")+" ]");
        }

        private void paintWorldMap(Graphics2D g){
            int x=contentLeft(),y=104,w=getWidth(),h=getHeight();sectionTitle(g,x,y,l("ARAZİ İZLEYİCİ","SURVEY MONITOR"),t("worldMap"),"");
            int mapY=y+72,mapW=w-x-32,mapH=h-mapY-34;terminalPanel(g,x,mapY,mapW,mapH);
            Shape oldClip=g.getClip();g.clipRect(x+1,mapY+1,mapW-2,mapH-2);g.setStroke(new BasicStroke(1));
            g.setColor(new Color(224,115,24,50));for(int gx=x;gx<x+mapW;gx+=24)g.drawLine(gx,mapY,gx,mapY+mapH);for(int gy=mapY;gy<mapY+mapH;gy+=24)g.drawLine(x,gy,x+mapW,gy);
            Path2D contour=new Path2D.Double();contour.moveTo(x-20,mapY+mapH*.72);for(int px=x-20;px<x+mapW+30;px+=18){double py=mapY+mapH*.55+Math.sin(px*.021)*58+Math.sin(px*.049)*21;contour.lineTo(px,py);}g.setColor(new Color(255,174,18,78));g.setStroke(new BasicStroke(1.2f));g.draw(contour);
            for(int ring=0;ring<6;ring++){int rw=130+ring*74,rh=70+ring*38;g.setColor(new Color(224,122,30,30+ring*5));g.drawOval(x+mapW/2-rw/2,mapY+mapH/2-rh/2,rw,rh);}
            g.setColor(new Color(255,174,18,170));int cx=x+mapW/2,cy=mapY+mapH/2;g.fillRect(cx-3,cy-3,7,7);g.drawLine(cx-18,cy,cx-7,cy);g.drawLine(cx+7,cy,cx+18,cy);g.drawLine(cx,cy-18,cx,cy-7);g.drawLine(cx,cy+7,cx,cy+18);
            int scan=mapY+(int)((time*42)%Math.max(1,mapH));g.setColor(new Color(255,160,38,40));g.fillRect(x,scan,mapW,2);g.setClip(oldClip);g.setFont(font(13,Font.PLAIN));g.setColor(PAPER);centered(g,t("mapOffline"),x+mapW/2,mapY+mapH/2+54);g.setFont(font(11,Font.PLAIN));g.setColor(AMBER);g.drawString("X 0000 / Z 0000 / SIGNAL 00",x+14,mapY+mapH-14);g.drawString("N ^",x+mapW-52,mapY+24);
        }

        private void sectionTitle(Graphics2D g, int x, int y, String eyebrow, String title, String lead) {
            paintSectionGlyph(g,page.ordinal(),x,y+9);int tx=x+46;g.setFont(font(12,Font.PLAIN));g.setColor(AMBER);g.drawString(eyebrow,tx,y+17);g.setFont(font(25,Font.PLAIN));g.setColor(PAPER);g.drawString(title.toUpperCase(Locale.ROOT),tx,y+47);g.setColor(LINE);g.drawLine(tx,y+59,getWidth()-30,y+59);if(!lead.isBlank()){g.setFont(font(12,Font.PLAIN));g.setColor(MUTED);g.drawString(lead,tx,y+75);}
        }

        private static void paintTextHalo(Graphics2D g,String text,int x,int baseline,Font font,Color color){
            Shape glyph=font.createGlyphVector(g.getFontRenderContext(),text).getOutline(x,baseline);Stroke oldStroke=g.getStroke();Paint oldPaint=g.getPaint();
            g.setStroke(new BasicStroke(10f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.setColor(new Color(0,0,0,15));g.draw(glyph);
            g.setStroke(new BasicStroke(6f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.setColor(new Color(0,0,0,28));g.draw(glyph);
            g.setStroke(new BasicStroke(3f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.setColor(new Color(0,0,0,52));g.draw(glyph);
            g.setColor(new Color(0,0,0,70));g.fill(glyph);g.setColor(color);g.fill(glyph);g.setStroke(oldStroke);g.setPaint(oldPaint);
        }

        static Rectangle cameraBounds(int w,int h){int feedW=Math.max(380,(int)((w-SIDEBAR)*.43));feedW=Math.min(feedW,w-SIDEBAR-420);int feedH=(int)Math.round(feedW*9.0/16.0);feedH=Math.min(feedH,Math.max(220,h-360));return new Rectangle(w-feedW-28,104,feedW,feedH);}
        private int contentLeft(){return SIDEBAR+32+(int)(126*sidebarExpand);}

        private static void terminalPanel(Graphics2D g,int x,int y,int w,int h){g.setColor(new Color(12,5,2,224));g.fillRect(x,y,w,h);g.setColor(LINE);g.drawRect(x,y,w,h);g.setColor(new Color(255,145,42,35));g.drawRect(x+3,y+3,w-6,h-6);}
        private static void terminalLabel(Graphics2D g,String text,int x,int y){g.setFont(font(11,Font.PLAIN));g.setColor(AMBER);g.drawString(text,x,y);}
        private static void terminalButton(Graphics2D g,Rectangle r,boolean hover,String text){g.setColor(hover?new Color(86,36,8,210):new Color(18,7,2,240));g.fillRect(r.x,r.y,r.width,r.height);g.setColor(hover?AMBER_HOT:LINE);g.drawRect(r.x,r.y,r.width,r.height);g.setFont(font(12,Font.PLAIN));g.setColor(hover?PAPER:AMBER);centered(g,text,r.x+r.width/2,r.y+r.height/2+5);}
        private static void drawSegments(Graphics2D g,int x,int y,int w,int h,int count,double progress){int gap=3,sw=Math.max(3,(w-gap*(count-1))/count),active=(int)Math.round(count*Math.max(0,Math.min(1,progress)));for(int i=0;i<count;i++){g.setColor(i<active?AMBER:new Color(74,31,9));g.fillRect(x+i*(sw+gap),y,sw,h);g.setColor(LINE);g.drawRect(x+i*(sw+gap),y,sw,h);}}
        private void statBlock(Graphics2D g,int x,int y,int w,int h,String label,String value,String register,int icon){terminalPanel(g,x,y,w,h);int pulse=150+(int)(85*(.5+.5*Math.sin(time*2.2+icon)));Color liveAmber=new Color(255,145,42,pulse);paintNavIcon(g,icon,x+w-30,y+27,liveAmber);g.setFont(font(12,Font.PLAIN));g.setColor(MUTED);g.drawString(label,x+14,y+25);g.setFont(font(25,Font.PLAIN));g.setColor(PAPER);g.drawString(value,x+14,y+60);g.setColor(LINE);g.drawLine(x+12,y+72,x+w-12,y+72);g.setFont(font(11,Font.PLAIN));g.setColor(AMBER);g.drawString(register,x+14,y+94);g.setColor(((int)(time*2+icon)&1)==0?MUTED:AMBER);g.drawString(l("DURUM / İYİ","STATUS / OK"),x+w-126,y+94);int cursor=x+12+(int)((time*54+icon*31)%Math.max(1,w-32));g.setColor(liveAmber);g.fillRect(cursor,y+71,12,2);}

        private void paintSectionGlyph(Graphics2D g,int kind,int x,int y){g.setColor(LINE);g.drawRect(x,y,34,34);g.setColor(new Color(255,145,42,30));g.drawRect(x+3,y+3,28,28);paintNavIcon(g,Math.max(0,Math.min(5,kind)),x+17,y+17,AMBER);}

        private void paintServerStatus(Graphics2D g,Rectangle feed){int x=feed.x,y=feed.y+feed.height+16,w=feed.width,h=getHeight()-y-28;if(h<128)return;terminalPanel(g,x,y,w,h);paintNavIcon(g,3,x+28,y+28,AMBER);g.setFont(font(13,Font.PLAIN));g.setColor(AMBER);g.drawString(l("SUNUCU DURUMU / ERDVYN DÜĞÜMÜ","SERVER STATUS / ERDVYN NODE"),x+52,y+32);g.setFont(font(12,Font.PLAIN));g.setColor(serverSnapshot.online()?PAPER:AMBER);String state=serverSnapshot.online()?l("[ ÇEVRİMİÇİ ]","[ ONLINE ]"):l("[ BEKLEMEDE ]","[ STANDBY ]");g.drawString(state,x+w-g.getFontMetrics().stringWidth(state)-18,y+32);g.setColor(LINE);g.drawLine(x+12,y+46,x+w-12,y+46);
            String players=serverSnapshot.online()?String.format(Locale.ROOT,"%02d / %02d",serverSnapshot.players(),serverSnapshot.maxPlayers()):"-- / --";String latency=serverSnapshot.online()?serverSnapshot.latencyMs()+" MS":"--- MS";String tps=apiStatus.tps()==null?"--.-- TPS":String.format(Locale.ROOT,"%.2f TPS",apiStatus.tps());String uptime=apiStatus.uptimeSeconds()==null?"--:--:--":formatUptime(apiStatus.uptimeSeconds());String[][] rows={{l("ADRES","ADDRESS"),LauncherPaths.serverAddress()},{l("OYUNCULAR","PLAYERS"),players},{l("GECİKME","LATENCY"),latency},{"TPS",tps},{l("SOHBET HATTI","CHAT BUS"),hub.connected()?l("BAĞLI","LINKED"):l("BEKLEMEDE","STANDBY")},{l("ÇALIŞMA SÜRESİ","UPTIME"),uptime}};int col=Math.max(170,w/2);for(int i=0;i<rows.length;i++){int cx=x+18+(i%2)*col,cy=y+76+(i/2)*42;g.setFont(font(11,Font.PLAIN));g.setColor(MUTED);g.drawString(rows[i][0],cx,cy);g.setFont(font(13,Font.PLAIN));g.setColor((i==2||i==4)&&!serverSnapshot.online()?AMBER:PAPER);g.drawString(rows[i][1],cx,cy+19);}int traceY=y+h-58;if(traceY>y+184){g.setColor(LINE);g.drawLine(x+12,traceY-12,x+w-12,traceY-12);g.setFont(font(11,Font.PLAIN));g.setColor(MUTED);g.drawString(l("BAĞLANTI ETKİNLİĞİ","LINK ACTIVITY"),x+18,traceY+10);paintSignalTrace(g,x+168,traceY-2,w-188,30,serverSnapshot.online());}}

        private static String formatUptime(long seconds){long hours=seconds/3600,minutes=(seconds%3600)/60,remainder=seconds%60;return String.format(Locale.ROOT,"%02d:%02d:%02d",hours,minutes,remainder);}

        private void paintClientDiagnostics(Graphics2D g,int x,int y,int w,int h){if(h<86)return;terminalPanel(g,x,y,w,h);g.setFont(font(12,Font.PLAIN));g.setColor(AMBER);g.drawString(l("İSTEMCİ TANILAMA / YEREL","CLIENT DIAGNOSTIC / LOCAL"),x+16,y+24);g.setColor(LINE);g.drawLine(x+12,y+36,x+w-12,y+36);String[][] rows={{"JAVA VM",l("21 / HAZIR","21 / READY")},{l("BELLEK","MEMORY"),gameOptions.ramGb()+" GB"},{l("HESAP","ACCOUNT"),accountSession==null?l("GİRİŞ YOK","SIGNED OUT"):accountSession.name()},{l("INSTANCE","INSTANCE"),"THE-FRONTIER"}};for(int i=0;i<rows.length;i++){int cx=x+16+(i%2)*(w/2),cy=y+64+(i/2)*34;g.setFont(font(11,Font.PLAIN));g.setColor(MUTED);g.drawString(rows[i][0],cx,cy);g.setFont(dataFont(11,Font.PLAIN));g.setColor(PAPER);g.drawString(rows[i][1],cx+112,cy);}if(h>=146){instancePathBounds.setBounds(x+14,y+101,w-28,28);g.setColor(new Color(255,145,42,18));g.fillRect(instancePathBounds.x,instancePathBounds.y,instancePathBounds.width,instancePathBounds.height);g.setFont(font(10,Font.PLAIN));g.setColor(MUTED);g.drawString(l("KONUM:","PATH:"),x+18,y+120);g.setColor(PAPER);String path=LauncherPaths.managedInstance().toAbsolutePath().normalize().toString();int max=w-92;while(path.length()>8&&g.getFontMetrics().stringWidth(path)>max)path="..."+path.substring(4);g.drawString(path,x+72,y+120);}else instancePathBounds.setBounds(0,0,0,0);int traceY=y+h-38;if(traceY>y+146)paintSignalTrace(g,x+16,traceY,w-32,24);}

        private void paintPackageBlockMap(Graphics2D g,int x,int y,int w,int h){if(h<24||w<120)return;g.setFont(font(11,Font.PLAIN));g.setColor(MUTED);g.drawString(l("PAKET BLOK HARİTASI","PACKAGE BLOCK MAP"),x,y);int top=y+12,rows=Math.max(1,Math.min(4,(h-12)/16)),cols=Math.max(8,Math.min(28,(w-48)/18)),scan=(int)(time*7)%cols,tick=(int)(time*2.4);for(int row=0;row<rows;row++){g.setColor(MUTED);g.drawString(String.format(Locale.ROOT,"%02X",row*16),x,top+row*16+10);for(int col=0;col<cols;col++){int bx=x+34+col*18,by=top+row*16;boolean packet=Math.floorMod(row*17+col*11+tick,13)<2,scanner=col==scan;g.setColor(scanner?new Color(255,174,65,75):new Color(255,145,42,30));g.fillRect(bx,by,12,10);g.setColor(scanner?PAPER:packet?AMBER_HOT:LINE);g.drawRect(bx,by,12,10);if(packet)g.fillRect(bx+4,by+3,4,4);}}int scanX=x+34+scan*18;g.setColor(new Color(255,190,82,120));g.drawLine(scanX-3,top-3,scanX-3,top+rows*16-5);}

        private void paintSignalTrace(Graphics2D g,int x,int y,int w,int h){paintSignalTrace(g,x,y,w,h,true);}
        private void paintSignalTrace(Graphics2D g,int x,int y,int w,int h,boolean live){if(w<40||h<12)return;Shape oldClip=g.getClip();Stroke oldStroke=g.getStroke();g.clipRect(x,y,w,h);int gridOffset=(int)((time*28)%16);g.setColor(new Color(255,145,42,24));for(int gx=x-gridOffset;gx<x+w;gx+=16)g.drawLine(gx,y,gx,y+h);double phase=time*3.1;Path2D glow=new Path2D.Double(),trace=new Path2D.Double();for(int px=-8;px<=w+8;px+=4){double sample=live?Math.sin((px+phase*22)*.15)*h*.18+Math.sin((px+phase*37)*.047)*h*.12+Math.sin((px+phase*13)*.39)*h*.035:0;double py=y+h/2+sample;if(px==-8){glow.moveTo(x+px,py);trace.moveTo(x+px,py);}else{glow.lineTo(x+px,py);trace.lineTo(x+px,py);}}g.setStroke(new BasicStroke(3f));g.setColor(new Color(255,122,22,live?36:22));g.draw(glow);g.setStroke(new BasicStroke(1f));g.setColor(live?AMBER:new Color(177,82,27));g.draw(trace);if(live){int head=x+(int)((time*64)%w);double hp=y+h/2+Math.sin(((head-x)+phase*22)*.15)*h*.18+Math.sin(((head-x)+phase*37)*.047)*h*.12;g.setColor(PAPER);g.fillRect(head-2,(int)hp-2,5,5);g.setColor(new Color(255,190,80,85));g.drawLine(head,y,head,y+h);}g.setClip(oldClip);g.setStroke(oldStroke);}

        private void settingRow(Graphics2D g, int x, int y, int w,int h, String title, boolean enabled, int index) {
            settingBounds[index].setBounds(x,y,w,h);if(hoverSetting==index){g.setColor(new Color(255,137,32,18));g.fillRect(x,y,w,h);}g.setColor(LINE);g.drawRect(x,y,w,h);g.setFont(font(12,Font.PLAIN));g.setColor(PAPER);g.drawString(title,x+14,y+h/2+5);String state=enabled?l("[ AÇIK ]","[ ENABLED ]"):l("[ KAPALI ]","[ DISABLED ]");g.setColor(enabled?AMBER_HOT:MUTED);g.drawString(state,x+w-154,y+h/2+5);int meterX=x+w-54,meterY=y+8,meterH=Math.max(8,h-16);g.setColor(new Color(255,145,42,24));g.fillRect(meterX,meterY,34,meterH);g.setColor(enabled?AMBER:LINE);int scan=(int)((time*26+index*9)%Math.max(1,meterH));g.drawLine(meterX+4,meterY+meterH-scan,meterX+30,meterY+meterH-scan);
        }

        private void optionStepper(Graphics2D g,String label,String value,int x,int y,int w,int h,Rectangle minus,Rectangle plus,double load){
            g.setColor(LINE);g.drawRect(x,y,w,h);g.setFont(font(11,Font.PLAIN));g.setColor(MUTED);g.drawString(label,x+12,y+17);g.setFont(dataFont(12,Font.PLAIN));g.setColor(PAPER);g.drawString(value,x+12,y+h-8);int buttonH=Math.max(28,h-12);minus.setBounds(x+w-82,y+6,32,buttonH);plus.setBounds(x+w-40,y+6,32,buttonH);int graphX=Math.max(x+154,x+w-182),graphW=Math.max(24,minus.x-graphX-10),mid=y+h/2+4;g.setColor(new Color(255,145,42,22));g.drawLine(graphX,mid,graphX+graphW,mid);g.setColor(new Color(255,145,42,80));for(int gx=0;gx<graphW;gx+=6){double wave=Math.sin(time*2.8+gx*.22+load*5)*load;int gy=(int)(wave*Math.max(2,h*.16));g.drawLine(graphX+gx,mid,graphX+gx,mid-gy);}terminalButton(g,minus,false,"-");terminalButton(g,plus,false,"+");
        }

        private void drawSimpleToggle(Graphics2D g,Rectangle bounds,String title,boolean enabled){
            g.setColor(LINE);g.drawRect(bounds.x,bounds.y,bounds.width,bounds.height);g.setFont(font(13,Font.PLAIN));g.setColor(PAPER);g.drawString(title,bounds.x+16,bounds.y+34);g.setColor(enabled?AMBER_HOT:MUTED);g.drawString(enabled?l("[ AÇIK ]","[ ENABLED ]"):l("[ KAPALI ]","[ DISABLED ]"),bounds.x+bounds.width-160,bounds.y+34);
        }

        private void stat(Graphics2D g, int x, int y, String value, String label) {
            g.setFont(font(28, Font.BOLD)); g.setColor(PAPER); g.drawString(value, x, y + 28);
            g.setFont(font(9, Font.BOLD)); g.setColor(MUTED); g.drawString(label, x, y + 51);
        }

        private void paintLogo(Graphics2D g, int x, int y) {
            if(logo!=null){
                g.setColor(LINE);g.drawRect(x-4,y-4,48,48);Shape oldClip=g.getClip();g.clipRect(x,y,40,40);g.drawImage(logo,x,y,40,40,null);g.setClip(oldClip);return;
            }
            g.setStroke(new BasicStroke(2f)); g.setColor(AMBER);
            Path2D mark = new Path2D.Double(); mark.moveTo(x + 20, y); mark.lineTo(x + 40, y + 12); mark.lineTo(x + 40, y + 36); mark.lineTo(x + 20, y + 49); mark.lineTo(x, y + 36); mark.lineTo(x, y + 12); mark.closePath(); g.draw(mark);
            g.drawLine(x + 9, y + 16, x + 31, y + 16); g.drawLine(x + 9, y + 16, x + 20, y + 36); g.drawLine(x + 31, y + 16, x + 20, y + 36);
            g.setColor(RED); g.fillRect(x + 16, y + 21, 8, 8);
        }

        private void paintNavIcon(Graphics2D g, int icon, int x, int y, Color color) {
            g.setColor(color); g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (icon) {
                case 0 -> { Path2D p = new Path2D.Double(); p.moveTo(x - 9, y); p.lineTo(x, y - 8); p.lineTo(x + 9, y); p.lineTo(x + 7, y + 10); p.lineTo(x - 7, y + 10); p.closePath(); g.draw(p); }
                case 1 -> { g.drawRoundRect(x - 9, y - 9, 18, 18, 3, 3); g.drawLine(x - 5, y - 4, x + 5, y - 4); g.drawLine(x - 5, y + 1, x + 5, y + 1); g.drawLine(x - 5, y + 6, x + 1, y + 6); }
                case 2 -> { g.drawRoundRect(x - 10, y - 7, 20, 15, 3, 3); g.drawLine(x - 4, y - 10, x + 4, y - 10); g.drawLine(x - 4, y - 10, x - 4, y - 7); g.drawLine(x + 4, y - 10, x + 4, y - 7); }
                case 3 -> { g.drawOval(x-10,y-9,7,7);g.drawOval(x+1,y-9,7,7);g.drawRoundRect(x-11,y-1,22,12,4,4);g.drawLine(x-3,y+11,x-7,y+15);g.fillOval(x-4,y+4,3,3);g.fillOval(x+2,y+4,3,3); }
                case 4 -> { Path2D map=new Path2D.Double();map.moveTo(x-11,y-8);map.lineTo(x-4,y-11);map.lineTo(x+4,y-8);map.lineTo(x+11,y-11);map.lineTo(x+11,y+8);map.lineTo(x+4,y+11);map.lineTo(x-4,y+8);map.lineTo(x-11,y+11);map.closePath();g.draw(map);g.drawLine(x-4,y-11,x-4,y+8);g.drawLine(x+4,y-8,x+4,y+11); }
                case 5 -> { g.drawOval(x - 8, y - 8, 16, 16); g.drawOval(x - 3, y - 3, 6, 6); for (int i=0;i<8;i++){double a=i*Math.PI/4;g.drawLine((int)(x+Math.cos(a)*9),(int)(y+Math.sin(a)*9),(int)(x+Math.cos(a)*12),(int)(y+Math.sin(a)*12));} }
            }
        }

        private void paintSpeakerIcon(Graphics2D g,int x,int y,boolean muted,Color color,boolean animate){Stroke old=g.getStroke();g.setStroke(new BasicStroke(2f));g.setColor(color);Path2D speaker=new Path2D.Double();speaker.moveTo(x-11,y-4);speaker.lineTo(x-6,y-4);speaker.lineTo(x,y-10);speaker.lineTo(x,y+10);speaker.lineTo(x-6,y+4);speaker.lineTo(x-11,y+4);speaker.closePath();g.draw(speaker);if(muted){g.setColor(AMBER_HOT);g.drawLine(x+5,y-6,x+13,y+6);g.drawLine(x+13,y-6,x+5,y+6);}else if(animate){int phase=(int)(time*5)%3;for(int i=0;i<3;i++){int alpha=i<=phase?230:70;g.setColor(new Color(color.getRed(),color.getGreen(),color.getBlue(),alpha));int px=x+5+i*4,half=2+i*2;g.drawLine(px,y-half,px,y+half);}}g.setStroke(old);}

        private void paintCardGlyph(Graphics2D g, int kind, int x, int y, Color c) {
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 35)); g.fillOval(x - 58, y - 58, 116, 116);
            g.setColor(c); g.setStroke(new BasicStroke(3f));
            if (kind == 0) { g.drawArc(x - 28, y - 29, 56, 56, 35, 290); g.drawLine(x - 8, y - 12, x + 18, y - 12); g.drawLine(x - 8, y, x + 18, y); g.drawLine(x - 8, y + 12, x + 9, y + 12); }
            else if (kind == 1) { Path2D p=new Path2D.Double();p.moveTo(x,y-31);p.lineTo(x+29,y+23);p.lineTo(x,y+12);p.lineTo(x-29,y+23);p.closePath();g.draw(p);g.fillOval(x-4,y-5,8,8); }
            else { g.drawOval(x - 28, y - 18, 56, 36); g.drawOval(x - 15, y - 8, 11, 11); g.drawOval(x + 5, y - 8, 11, 11); g.drawArc(x - 12, y - 2, 24, 14, 200, 140); }
        }

        private static void paintArrow(Graphics2D g, int x, int y, Color color) { g.setColor(color); g.setStroke(new BasicStroke(2.4f)); g.drawLine(x - 10, y, x + 10, y); g.drawLine(x + 3, y - 7, x + 10, y); g.drawLine(x + 3, y + 7, x + 10, y); }
        private static final Font TERMINAL_FONT=loadBundledFont("/fonts/PxPlus_IBM_VGA8.ttf");
        private static final String INTERFACE_FACE=findInterfaceFace();
        private static Font loadBundledFont(String resource){try(InputStream stream=ErdvynLauncher.class.getResourceAsStream(resource)){if(stream==null)return null;Font loaded=Font.createFont(Font.TRUETYPE_FONT,stream);GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(loaded);return loaded;}catch(Exception ignored){return null;}}
        private static String findInterfaceFace(){Set<String> installed=new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));for(String candidate:List.of("PxPlus IBM VGA8","Consolas"))if(installed.contains(candidate))return candidate;return Font.MONOSPACED;}
        private static float readableSize(float size){return Math.max(13,Math.round(size+2));}
        private static Font font(float size, int style) {return TERMINAL_FONT!=null?TERMINAL_FONT.deriveFont(style,readableSize(size)):new Font(INTERFACE_FACE,style,Math.round(readableSize(size)));}
        private static Font dataFont(float size,int style){return new Font("Consolas",style,Math.round(readableSize(size)));}
        private static Font cameraFont(float size,int style){return font(size,style);}
        private static void centered(Graphics2D g, String text, int cx, int baseline) { g.drawString(text, cx - g.getFontMetrics().stringWidth(text) / 2, baseline); }
        private static void rounded(Graphics2D g, int x, int y, int w, int h, int radius, Color fill, Color border) { int arc=Math.min(radius,3);g.setColor(fill); g.fillRoundRect(x, y, w, h, arc, arc); g.setColor(border); g.setStroke(new BasicStroke(1)); g.drawRoundRect(x, y, w, h, arc, arc); }
        private static void drawMultiline(Graphics2D g, String text, int x, int y, int lineHeight) { for (String line : text.split("\\n")) { g.drawString(line, x, y); y += lineHeight; } }
        private static void drawWrapped(Graphics2D g, String text, int x, int y, int maxWidth, int lineHeight, int maxLines) {
            StringBuilder line = new StringBuilder(); int lines = 0;
            for (String word : text.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (g.getFontMetrics().stringWidth(candidate) > maxWidth && !line.isEmpty()) { g.drawString(line.toString(), x, y); y += lineHeight; lines++; line = new StringBuilder(word); if (lines >= maxLines) return; }
                else line = new StringBuilder(candidate);
            }
            if (!line.isEmpty() && lines < maxLines) g.drawString(line.toString(), x, y);
        }

        private void paintBootOverlay(Graphics2D g){
            int w=getWidth(),h=getHeight();g.setColor(new Color(0,0,0,208));g.fillRect(0,0,w,h);int bw=Math.min(790,w-110),bh=Math.min(540,h-88),x=(w-bw)/2,y=(h-bh)/2;terminalPanel(g,x,y,bw,bh);String[] logs=launcherBootMode?LAUNCHER_BOOT_LOGS:BOOT_LOGS;g.setFont(font(15,Font.PLAIN));g.setColor(PAPER);g.drawString(launcherBootMode?"ERDVYN TERMINAL STARTUP":"ERDVYN BOOT SEQUENCE",x+22,y+34);g.setFont(font(11,Font.PLAIN));g.setColor(MUTED);g.drawString(launcherBootMode?"NODE: LOCAL-CONSOLE / CRT: AMBER / BUS: UI-01":"NODE: ERDVYN-FRONTIER / RUNTIME: JAVA 21",x+22,y+55);g.setColor(LINE);g.drawLine(x+14,y+68,x+bw-14,y+68);
            int value=BOOT_STEPS[Math.max(0,Math.min(bootStepIndex,BOOT_STEPS.length-1))];drawSegments(g,x+22,y+88,bw-44,20,28,value/112.0);g.setFont(font(13,Font.PLAIN));g.setColor(AMBER);g.drawString(String.format(Locale.ROOT,"%03d / 112",value),x+22,y+130);
            int logY=y+166,footerLine=y+bh-58;g.setColor(LINE);g.drawRect(x+18,logY-22,bw-36,footerLine-logY-14);g.setFont(font(12,Font.PLAIN));int visible=Math.min(bootLogCount,logs.length),start=Math.max(0,visible-12);for(int i=start;i<visible;i++){g.setColor(logs[i].startsWith("[OK]")?PAPER:logs[i].startsWith("[WAIT]")?AMBER:MUTED);g.drawString(logs[i],x+32,logY+(i-start)*24);}
            g.setColor(LINE);g.drawLine(x+14,footerLine,x+bw-14,footerLine);int footerBase=y+bh-23;if(value>=112){g.setFont(font(16,Font.PLAIN));g.setColor(PAPER);centered(g,launcherBootMode?"TERMINAL ONLINE":"BOOT COMPLETE",x+bw/4,footerBase);g.setColor(AMBER_HOT);centered(g,launcherBootMode?"OPENING CONTROL UI...":"STARTING MINECRAFT...",x+bw*3/4,footerBase);}else{g.setFont(font(12,Font.PLAIN));g.setColor(MUTED);centered(g,launcherBootMode?"TERMINAL BUS ACTIVE":"SYSTEM BUS ACTIVE",x+bw/2,footerBase);g.drawString(((int)(time*2)%2==0)?"_":" ",x+bw-34,footerBase);}
        }

        private void paintLaunchOverlay(Graphics2D g){
            int w=getWidth(),h=getHeight();g.setColor(new Color(0,0,0,224));g.fillRect(0,0,w,h);int bw=Math.min(920,w-76),bh=Math.min(570,h-54),x=(w-bw)/2,y=(h-bh)/2;terminalPanel(g,x,y,bw,bh);
            g.setFont(font(16,Font.PLAIN));g.setColor(PAPER);g.drawString(l("ERDVYN BAŞLATMA KONTROLÜ","ERDVYN LAUNCH CONTROL"),x+22,y+34);g.setFont(font(11,Font.PLAIN));g.setColor(MUTED);g.drawString("NODE: ERDVYN-FRONTIER / BUS: JVM-01 / CRT: AMBER",x+22,y+55);g.setColor(launchFailed?RED:AMBER);String state=launchFailed?l("[ BAŞLATMA DURDURULDU ]","[ LAUNCH HALTED ]"):l("[ CANLI ]","[ LIVE ]");g.drawString(state,x+bw-g.getFontMetrics().stringWidth(state)-22,y+34);g.setColor(LINE);g.drawLine(x+14,y+68,x+bw-14,y+68);
            drawSegments(g,x+22,y+84,bw-44,18,32,launchDisplayedProgress);g.setFont(font(12,Font.PLAIN));g.setColor(launchFailed?RED:AMBER_HOT);String headline=launchStatus.isBlank()?l("BAŞLATMA HATTI HAZIRLANIYOR","PREPARING LAUNCH BUS"):launchStatus;g.drawString(fitTerminalLine(g,headline.toUpperCase(Locale.ROOT),bw-180),x+22,y+124);g.setColor(MUTED);g.drawString(String.format(Locale.ROOT,"%03d%%",(int)Math.round(launchDisplayedProgress*100)),x+bw-72,y+124);

            int sideW=238,gap=14,contentY=y+142,contentH=bh-212,logW=bw-44-sideW-gap;terminalPanel(g,x+18,contentY,logW,contentH);terminalPanel(g,x+18+logW+gap,contentY,sideW,contentH);g.setFont(font(11,Font.PLAIN));g.setColor(AMBER);g.drawString(l("YÜRÜTME İZİ / CANLI","EXECUTION TRACE / LIVE"),x+32,contentY+26);g.setColor(LINE);g.drawLine(x+28,contentY+38,x+18+logW-10,contentY+38);
            int maxLines=Math.max(5,(contentH-58)/21),start=Math.max(0,launchTrace.size()-maxLines);g.setFont(font(11,Font.PLAIN));for(int i=start;i<launchTrace.size();i++){String line=launchTrace.get(i);g.setColor(line.startsWith("[FAIL]")?RED:line.startsWith("[WAIT]")?AMBER:line.startsWith("[GET]")?AMBER_HOT:PAPER);g.drawString(fitTerminalLine(g,line,logW-34),x+32,contentY+62+(i-start)*21);}if(launchTrace.isEmpty()){g.setColor(MUTED);g.drawString("> "+l("SİNYAL BEKLENİYOR","AWAITING SIGNAL")+(((int)(time*2)&1)==0?" _":""),x+32,contentY+64);}

            int sx=x+18+logW+gap+14;g.setFont(font(11,Font.PLAIN));String elapsed=formatUptime(Math.max(0,(System.currentTimeMillis()-launchStartedAtMillis)/1000));String[][] telemetry={{l("İŞLEM","PROCESS"),launchPid>0?Long.toString(launchPid):l("BEKLİYOR","PENDING")},{l("GEÇEN","ELAPSED"),elapsed},{l("BELLEK","MEMORY"),gameOptions.ramGb()+" GB"},{l("PAKET","PACK"),packSummary.version()},{l("INSTANCE","INSTANCE"),"THE-FRONTIER"},{l("HEDEF","TARGET"),autoConnect?LauncherPaths.serverAddress():l("ANA MENÜ","MAIN MENU")}};for(int i=0;i<telemetry.length;i++){int ty=contentY+29+i*48;g.setColor(MUTED);g.drawString(telemetry[i][0],sx,ty);g.setFont(dataFont(11,Font.PLAIN));g.setColor(i==0&&launchPid==0?AMBER:PAPER);g.drawString(fitTerminalLine(g,telemetry[i][1],sideW-28),sx,ty+19);g.setFont(font(11,Font.PLAIN));g.setColor(new Color(255,145,42,28));g.drawLine(sx,ty+28,x+bw-30,ty+28);}
            int traceY=y+bh-58;g.setColor(LINE);g.drawLine(x+14,traceY-12,x+bw-14,traceY-12);g.setFont(font(11,Font.PLAIN));g.setColor(MUTED);g.drawString(l("JVM ETKİNLİĞİ","JVM ACTIVITY"),x+22,traceY+10);paintSignalTrace(g,x+150,traceY-4,Math.max(160,bw-456),28,!launchFailed);if(launchFailed){launchDismissBounds.setBounds(x+bw-166,traceY-11,142,34);terminalButton(g,launchDismissBounds,false,"[ "+l("GERİ DÖN","RETURN")+" ]");}else{launchDismissBounds.setBounds(0,0,0,0);g.setColor(AMBER);g.drawString(((int)(time*3)&1)==0?l("MINECRAFT BEKLENİYOR _","AWAITING MINECRAFT _"):l("MINECRAFT BEKLENİYOR","AWAITING MINECRAFT"),x+bw-276,traceY+10);}
        }

        private static String fitTerminalLine(Graphics2D g,String text,int maxWidth){String value=text==null?"":text.replace('\t',' ').replaceAll("\\s+"," ").strip();if(g.getFontMetrics().stringWidth(value)<=maxWidth)return value;String suffix="...";while(value.length()>4&&g.getFontMetrics().stringWidth(value+suffix)>maxWidth)value=value.substring(0,value.length()-1);return value+suffix;}
        private void beginLaunchOverlay(){launchOverlayActive=true;launchFailed=false;launchTrace.clear();launchStatus=l("BAŞLATMA HATTI DEVREDE","LAUNCH BUS ENGAGED");launchDisplayedProgress=.02;launchTargetProgress=.04;launchStartedAtMillis=System.currentTimeMillis();launchPid=0;launchLastPackBucket=-1;appendLaunchTrace("[EXEC] "+l("OYNA İSTEĞİ KABUL EDİLDİ","PLAY REQUEST ACCEPTED"));appendLaunchTrace("[WAIT] "+l("ÇALIŞMA ORTAMI DENETLENİYOR","RUNTIME PROBE PENDING"));}
        private void startLaunchPreview(){
            beginLaunchOverlay();gameLaunching=true;launchPid=4217;launchDisplayedProgress=.88;launchTargetProgress=.94;launchStartedAtMillis=System.currentTimeMillis()-43_000;
            launchStatus=l("MINECRAFT BEKLENİYOR / NEOFORGE YÜKLENİYOR","AWAITING MINECRAFT / NEOFORGE LOADING");
            launchTrace.clear();
            appendLaunchTrace("[OK] JAVA 21 RUNTIME / READY");
            appendLaunchTrace("[OK] PACK 2026.08.30.5 / SIGNATURE VERIFIED");
            appendLaunchTrace("[OK] 1187 FILES / SERVER MANIFEST MATCHED");
            appendLaunchTrace("[OK] MICROSOFT SESSION / BOUND");
            appendLaunchTrace("[EXEC] JVM / XMX "+gameOptions.ramGb()+"G / NEOFORGE 21.1.243");
            appendLaunchTrace("[OK] MODLAUNCHER HANDSHAKE / ACCEPTED");
            appendLaunchTrace("[OK] NEOFORGE RUNTIME / ONLINE");
            appendLaunchTrace("[WAIT] "+l("RENDER PENCERESİ HAZIR SİNYALİ","RENDER WINDOW READY SIGNAL"));
            repaint();
        }
        private void appendLaunchTrace(String line){String value=line==null?"":line.replaceAll("\\s+"," ").strip();if(value.isBlank())return;if(value.length()>150)value=value.substring(0,147)+"...";launchTrace.add(value);while(launchTrace.size()>96)launchTrace.remove(0);}
        private void setLaunchStage(String status,double progress,String event){Runnable update=()->{launchStatus=status;launchTargetProgress=Math.max(launchTargetProgress,Math.max(0,Math.min(1,progress)));if(event!=null&&!event.isBlank())appendLaunchTrace(event);repaint();};if(SwingUtilities.isEventDispatchThread())update.run();else SwingUtilities.invokeLater(update);}
        private void onPackLaunchProgress(PackService.Progress progress){LauncherLog.write(progress.line());SwingUtilities.invokeLater(()->{packProgress=progress.value();packStatus=progress.line();packLog.add(progress.line());if(packLog.size()>80)packLog.remove(0);int percent=(int)Math.round(progress.value()*100),bucket=percent/5;boolean notable=progress.line().contains("[GET]")||progress.line().contains("[SAVED]")||progress.line().contains("[FAIL]")||progress.line().startsWith("MANIFEST")||bucket>launchLastPackBucket;if(notable){launchLastPackBucket=Math.max(launchLastPackBucket,bucket);appendLaunchTrace((progress.line().contains("[FAIL]")?"[FAIL] ":progress.line().contains("[GET]")?"[GET] ":"[OK] ")+l("PAKET DENETİMİ ","PACK AUDIT ")+percent+"%");}launchStatus=l("PAKET DOĞRULANIYOR","VERIFYING PACKAGE")+" / "+percent+"%";launchTargetProgress=Math.max(launchTargetProgress,.20+progress.value()*.43);repaint();});}
        private void onMinecraftLaunchSignal(String line){LauncherLog.write(line);SwingUtilities.invokeLater(()->{packStatus=line;packLog.add(line);if(packLog.size()>80)packLog.remove(0);double next=launchTargetProgress;String prefix="[EXEC] ";if(line.startsWith("AWAITING")){next=Math.max(next,.90);prefix="[WAIT] ";}else if(line.startsWith("PROCESS STARTED")){next=Math.max(next,.87);try{launchPid=Long.parseLong(line.replaceAll(".*PID\\s+","").strip());}catch(Exception ignored){}}else if(line.startsWith("MODLAUNCHER"))next=Math.max(next,.91);else if(line.startsWith("NEOFORGE"))next=Math.max(next,.925);else if(line.startsWith("ACCOUNT SESSION"))next=Math.max(next,.94);else if(line.startsWith("RENDER BACKEND"))next=Math.max(next,.955);else if(line.startsWith("RESOURCE BUS"))next=Math.max(next,.97);else if(line.startsWith("AUDIO BUS")||line.startsWith("TEXTURE ATLAS"))next=Math.max(next,.985);else if(line.startsWith("MINECRAFT READY")||line.startsWith("MINECRAFT PROCESS STABLE")){next=1;prefix="[OK] ";}launchTargetProgress=next;launchStatus=line;appendLaunchTrace(prefix+line);repaint();});}

        private void startBoot(){pendingGameLaunch=true;launcherBootMode=false;beginBoot();}
        private void startLauncherBoot(){launcherBootMode=true;launcherReady=false;video.setUiGate(false);beginBoot();}
        private void beginBoot(){bootActive=true;bootCompleteSound=false;bootStepIndex=0;bootLogCount=1;bootDelay=10;bootHold=0;playBootSound(0);}
        private void advanceBoot(){if(!bootActive)return;String[] logs=launcherBootMode?LAUNCHER_BOOT_LOGS:BOOT_LOGS;if(bootStepIndex>=BOOT_STEPS.length-1){bootHold++;if(!bootCompleteSound){bootCompleteSound=true;playBootSound(2);}if(bootHold>(launcherBootMode?58:105)){bootActive=false;if(launcherBootMode){launcherReady=true;triggerCameraGlitch(5.6);}else if(pendingGameLaunch){pendingGameLaunch=false;launchGameAsync();}}return;}if(--bootDelay>0)return;bootStepIndex++;bootLogCount=Math.min(logs.length,1+bootStepIndex+(bootStepIndex>3?bootStepIndex/2:0));bootDelay=3+random.nextInt(11);playBootSound(1);}
        private void playBootSound(int kind){Thread.startVirtualThread(()->{try{float rate=22050;double duration=kind==0?.42:kind==2?.18:.026;int length=(int)(rate*duration);byte[] data=new byte[length*2];Random n=new Random(System.nanoTime());for(int i=0;i<length;i++){double t=i/rate,envelope=Math.pow(1-t/duration,kind==0?1.1:2.8),sample;if(kind==0)sample=Math.sin(2*Math.PI*48*t)*.15+(n.nextDouble()*2-1)*.07+((t<.018||t>.095&&t<.112)?(n.nextDouble()*2-1)*.35:0);else if(kind==2)sample=Math.sin(2*Math.PI*690*t)*.34+(t>.105?(n.nextDouble()*2-1)*.12:0);else sample=(n.nextDouble()*2-1)*.42+Math.sin(2*Math.PI*1850*t)*.12;short pcm=(short)(sample*envelope*420);data[i*2]=(byte)pcm;data[i*2+1]=(byte)(pcm>>8);}javax.sound.sampled.AudioFormat format=new javax.sound.sampled.AudioFormat(rate,16,1,true,false);try(javax.sound.sampled.SourceDataLine line=javax.sound.sampled.AudioSystem.getSourceDataLine(format)){line.open(format);line.start();line.write(data,0,data.length);line.drain();}}catch(Exception ignored){}});}

        @Override public void actionPerformed(ActionEvent e) {
            time += .016; playPulse += .045; pageTransition=Math.min(1,pageTransition+.075);opening=Math.min(1,opening+.032);
            double sideTarget=mouse.x<235?1:0;sidebarExpand+=(sideTarget-sidebarExpand)*.13;volumeReveal+=(sidebarExpand-volumeReveal)*.16;pressDepth+=(pressedControl.isEmpty()? -pressDepth:1-pressDepth)*.24;
            boolean[] values={autoUpdate,autoConnect,gameOptions.fullscreen()};for(int i=0;i<toggleVisual.length;i++)toggleVisual[i]+=(values[i]?1-toggleVisual[i]:-toggleVisual[i])*.18;
            for (Dust p : dust) {
                double sway = Math.sin(time * (.72 + p.speed) + p.phase);
                p.x += p.speed * .00026 + sway * .000075;
                p.y += p.speed * .00018 + Math.cos(time * .55 + p.phase) * .000018;
                p.angle = .55 + sway * .72 + Math.sin(time * .31 + p.phase) * .18;
                if (p.x > 1.04 || p.y > 1.04) { p.y = -.04 - random.nextDouble() * .2; p.x = -.06 + random.nextDouble() * .72; }
            }
            advanceBoot();pollBackendIfDue();if(launcherReady&&!bootActive&&!launchOverlayActive&&page==Page.HOME&&time>=nextCameraGlitchAt)triggerCameraGlitch(4.2+random.nextDouble()*.9);double cameraGlitch=cameraGlitchStrength();double glitchPhase=(time-cameraGlitchStart)/Math.max(.01,cameraGlitchEnd-cameraGlitchStart);if(cameraGlitch>0&&!cameraVideoSwitched&&glitchPhase>=.24){cameraVideoSwitched=true;video.switchToDifferentVideo();}frame.applyVideoJitter(0,0);video.setUiGate(launcherReady&&!bootActive&&!launchOverlayActive&&page==Page.HOME&&cameraGlitch<.035&&video.isReady());advanceUpdate(); displayedProgress += (targetProgress - displayedProgress) * .075;launchDisplayedProgress+=(launchTargetProgress-launchDisplayedProgress)*.085;
            repaint();
        }

        private void advanceUpdate() {
            if (updateStage == UpdateStage.IDLE) { targetProgress = 1; return; }
            stateTicks++;
            switch (updateStage) {
                case CHECKING -> { targetProgress = .12; if (stateTicks > 48) changeStage(UpdateStage.DOWNLOADING); }
                case DOWNLOADING -> { targetProgress = Math.min(.80, .12 + stateTicks / 145.0 * .68); if (stateTicks > 145) changeStage(UpdateStage.VERIFYING); }
                case VERIFYING -> { targetProgress = Math.min(.97, .80 + stateTicks / 48.0 * .17); if (stateTicks > 48) changeStage(UpdateStage.READY); }
                case READY -> { targetProgress = 1; if (stateTicks > 32) changeStage(UpdateStage.LAUNCHING); }
                case LAUNCHING -> { targetProgress = 1; if (stateTicks > 78) changeStage(UpdateStage.IDLE); }
                default -> { }
            }
        }

        private void changeStage(UpdateStage next) { updateStage = next; stateTicks = 0; if (next == UpdateStage.CHECKING) { displayedProgress = 0; targetProgress = 0; } }
        private void toggleLanguage() { language = language == Language.TR ? Language.EN : Language.TR; preferences.put("language", language.name()); repaint(); }
        private void navigate(Page next){if(next==Page.ADMIN&&(apiClient.current()==null||!apiClient.current().account().admin()))return;if(page!=next){page=next;pageTransition=0;}if(next!=Page.SETTINGS)settingsLanguageBounds.setBounds(0,0,0,0);profileOpen=false;selectedNews=-1;newsComposeOpen=false;repaint();}

        @Override public void mouseClicked(MouseEvent e) {
            if (closeBounds.contains(e.getPoint())) { frame.shutdownAndExit(); return; }
            if (minimizeBounds.contains(e.getPoint())) { frame.setState(Frame.ICONIFIED); return; }
            if(launchOverlayActive&&launchFailed&&launchDismissBounds.contains(e.getPoint())){launchOverlayActive=false;launchFailed=false;gameLaunching=false;launchDismissBounds.setBounds(0,0,0,0);repaint();return;}
            if(bootActive||launchOverlayActive)return;
            if(audioBounds.contains(e.getPoint())){video.toggleMute();playUiSound(105);return;}
            if (languageBounds.contains(e.getPoint())) { playUiSound(128);toggleLanguage(); return; }
            if(notificationBounds.contains(e.getPoint())){playUiSound(94);notificationsOpen=!notificationsOpen;profileOpen=false;repaint();return;}
            if(selectedNews>=0){if(articleCloseBounds.contains(e.getPoint()))selectedNews=-1;repaint();return;}
            if(newsComposeOpen){if(newsTitleInputBounds.contains(e.getPoint())){newsField=0;requestFocusInWindow();}else if(newsBodyInputBounds.contains(e.getPoint())){newsField=1;requestFocusInWindow();}else if(newsCancelBounds.contains(e.getPoint())){newsComposeOpen=false;newsTitleDraft="";newsBodyDraft="";}else if(newsPublishBounds.contains(e.getPoint()))publishNews();repaint();return;}
            if(profileBounds.contains(e.getPoint())){playUiSound(101);profileOpen=!profileOpen;notificationsOpen=false;repaint();return;}
            if(profileOpen){if(microsoftBounds.contains(e.getPoint())){beginMicrosoftLogin();return;}if(erdvynAccountBounds.contains(e.getPoint())){beginErdvynLogin();return;}profileOpen=false;repaint();return;}
            for (int i = 0; i < navBounds.length; i++) if (navBounds[i] != null && navBounds[i].contains(e.getPoint())) { playUiSound(92+i*7);navigate(Page.values()[i]); return; }
            if (page == Page.HOME && playBounds.contains(e.getPoint())) { requestGameStart(); return; }
            if (page == Page.HOME && instancePathBounds.contains(e.getPoint())) { openModpackFolder(); return; }
            if(updateBounds.contains(e.getPoint())){launchPreparedUpdate();return;}
            if(page==Page.NEWS&&newsComposeBounds.contains(e.getPoint())){newsComposeOpen=true;newsField=0;requestFocusInWindow();repaint();return;}
            if(page==Page.NEWS)for(int i=0;i<newsBounds.length;i++)if(newsBounds[i].contains(e.getPoint())){selectedNews=newsFirstVisible+i;repaint();return;}
            if(page==Page.ADMIN){if(adminTargetBounds.contains(e.getPoint())){adminField=0;requestFocusInWindow();return;}if(adminCommandBounds.contains(e.getPoint())){adminField=1;requestFocusInWindow();return;}if(adminGrantBounds.contains(e.getPoint())){adminAccessChange(true);return;}if(adminRevokeBounds.contains(e.getPoint())){adminAccessChange(false);return;}if(adminBanBounds.contains(e.getPoint())){queueAdminCommand("ban "+adminTargetDraft.strip());return;}if(adminUnbanBounds.contains(e.getPoint())){queueAdminCommand("pardon "+adminTargetDraft.strip());return;}if(adminExecuteBounds.contains(e.getPoint())){queueAdminCommand(adminCommandDraft);return;}}
            if(page==Page.PACK&&(installPackBounds.contains(e.getPoint())||verifyBounds.contains(e.getPoint()))){playUiSound(88);verifyModpack();return;}
            if(page==Page.PACK&&folderBounds.contains(e.getPoint())){playUiSound(118);openModpackFolder();return;}
            if(page==Page.GAME&&chatInputBounds.contains(e.getPoint())){chatFocused=true;requestFocusInWindow();repaint();return;}
            if(page==Page.GAME&&chatSendBounds.contains(e.getPoint())){sendChat();return;}
            if (page == Page.SETTINGS) {
                for(int i=0;i<settingBounds.length;i++)if(settingBounds[i].contains(e.getPoint())){if(i==0){autoUpdate=!autoUpdate;preferences.putBoolean("autoUpdate",autoUpdate);}else if(i==1){autoConnect=!autoConnect;preferences.putBoolean("autoConnect",autoConnect);}else gameOptions.setFullscreen(!gameOptions.fullscreen());playUiSound(105+i*9);repaint();return;}
                if(vsyncBounds.contains(e.getPoint())){gameOptions.setVsync(!gameOptions.vsync());playUiSound(111);repaint();return;}
                if(ramMinusBounds.contains(e.getPoint())){gameOptions.setRamGb(gameOptions.ramGb()-1);repaint();return;}if(ramPlusBounds.contains(e.getPoint())){gameOptions.setRamGb(gameOptions.ramGb()+1);repaint();return;}
                if(renderMinusBounds.contains(e.getPoint())){gameOptions.setRenderDistance(gameOptions.renderDistance()-2);repaint();return;}if(renderPlusBounds.contains(e.getPoint())){gameOptions.setRenderDistance(gameOptions.renderDistance()+2);repaint();return;}
                if(simulationMinusBounds.contains(e.getPoint())){gameOptions.setSimulationDistance(gameOptions.simulationDistance()-1);repaint();return;}if(simulationPlusBounds.contains(e.getPoint())){gameOptions.setSimulationDistance(gameOptions.simulationDistance()+1);repaint();return;}
                if(fpsMinusBounds.contains(e.getPoint())){gameOptions.setMaxFps(gameOptions.maxFps()-10);repaint();return;}if(fpsPlusBounds.contains(e.getPoint())){gameOptions.setMaxFps(gameOptions.maxFps()+10);repaint();return;}
                if(guiMinusBounds.contains(e.getPoint())){gameOptions.setGuiScale(gameOptions.guiScale()-1);repaint();return;}if(guiPlusBounds.contains(e.getPoint())){gameOptions.setGuiScale(gameOptions.guiScale()+1);repaint();return;}
                for(int i=0;i<settingsFolderBounds.length;i++)if(settingsFolderBounds[i].contains(e.getPoint())){openSettingsFolder(i);return;}
                if(optionsFileBounds.contains(e.getPoint())){openOptionsFile();return;}if(configFolderBounds.contains(e.getPoint())){openFolderKind("config");return;}
                if(settingsLanguageBounds.contains(e.getPoint())){playUiSound(128);toggleLanguage();return;}
            }
            repaint();
        }

        @Override public void mouseMoved(MouseEvent e) {
            mouse = e.getPoint(); hoverNav = -1;if(launchOverlayActive){setCursor(Cursor.getPredefinedCursor(launchFailed&&launchDismissBounds.contains(mouse)?Cursor.HAND_CURSOR:Cursor.DEFAULT_CURSOR));repaint();return;}
            for (int i = 0; i < navBounds.length; i++) if (navBounds[i] != null && navBounds[i].contains(mouse)) hoverNav = i;
            hoverPlay = page == Page.HOME && playBounds.contains(mouse); hoverLanguage = languageBounds.contains(mouse);
            hoverAudio=audioBounds.contains(mouse);hoverVerify=page==Page.PACK&&verifyBounds.contains(mouse);hoverFolder=page==Page.PACK&&folderBounds.contains(mouse);hoverSetting=-1;
            hoverUpdate=page==Page.HOME&&updateBounds.contains(mouse);hoverProfile=profileBounds.contains(mouse);hoverNews=-1;
            if(page==Page.NEWS)for(int i=0;i<newsBounds.length;i++)if(newsBounds[i].contains(mouse))hoverNews=i;
            if(page==Page.SETTINGS)for(int i=0;i<settingBounds.length;i++)if(settingBounds[i].contains(mouse))hoverSetting=i;
            int edge=edgeMask(mouse);if(edge!=0){setCursor(Cursor.getPredefinedCursor(cursorFor(edge)));repaint();return;}
            boolean settingsAction=page==Page.SETTINGS&&(vsyncBounds.contains(mouse)||ramMinusBounds.contains(mouse)||ramPlusBounds.contains(mouse)||renderMinusBounds.contains(mouse)||renderPlusBounds.contains(mouse)||simulationMinusBounds.contains(mouse)||simulationPlusBounds.contains(mouse)||fpsMinusBounds.contains(mouse)||fpsPlusBounds.contains(mouse)||guiMinusBounds.contains(mouse)||guiPlusBounds.contains(mouse)||optionsFileBounds.contains(mouse)||configFolderBounds.contains(mouse)||Arrays.stream(settingsFolderBounds).anyMatch(bounds->bounds.contains(mouse)));
            boolean newsAction=page==Page.NEWS&&(newsComposeBounds.contains(mouse)||newsComposeOpen&&(newsTitleInputBounds.contains(mouse)||newsBodyInputBounds.contains(mouse)||newsPublishBounds.contains(mouse)||newsCancelBounds.contains(mouse)));
            boolean adminAction=page==Page.ADMIN&&(adminTargetBounds.contains(mouse)||adminCommandBounds.contains(mouse)||adminGrantBounds.contains(mouse)||adminRevokeBounds.contains(mouse)||adminBanBounds.contains(mouse)||adminUnbanBounds.contains(mouse)||adminExecuteBounds.contains(mouse));
            boolean clickable = selectedNews>=0&&articleCloseBounds.contains(mouse)||hoverNav >= 0 || hoverPlay || page==Page.HOME&&instancePathBounds.contains(mouse)||hoverLanguage || hoverAudio||hoverUpdate||hoverProfile||notificationBounds.contains(mouse)||hoverNews>=0||hoverVerify||installPackBounds.contains(mouse)||hoverFolder||hoverSetting>=0||settingsAction||newsAction||adminAction||settingsLanguageBounds.contains(mouse)||chatInputBounds.contains(mouse)||chatSendBounds.contains(mouse)|| closeBounds.contains(mouse) || minimizeBounds.contains(mouse);
            setCursor(Cursor.getPredefinedCursor(clickable ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR)); repaint();
        }
        @Override public void mousePressed(MouseEvent e) {
            pressedControl=controlAt(e.getPoint());
            if(volumeBounds.contains(e.getPoint())){volumeDragging=true;setVideoVolumeFromMouse(e.getX());return;}
            windowActionStart=e.getLocationOnScreen();windowStartBounds=frame.getBounds();resizeMask=edgeMask(e.getPoint());
            resizingWindow=resizeMask!=0;draggingWindow=!resizingWindow&&e.getY()<70&&e.getX()>SIDEBAR&&!languageBounds.contains(e.getPoint())&&!closeBounds.contains(e.getPoint())&&!minimizeBounds.contains(e.getPoint());
        }
        @Override public void mouseReleased(MouseEvent e) {pressedControl="";volumeDragging=false;draggingWindow=false;resizingWindow=false;resizeMask=0;windowActionStart=null;windowStartBounds=null;}
        @Override public void mouseEntered(MouseEvent e) { }
        @Override public void mouseExited(MouseEvent e) { hoverNav = -1;hoverNews=-1; hoverPlay = false; hoverLanguage = false;hoverUpdate=false;hoverProfile=false; repaint(); }
        @Override public void mouseDragged(MouseEvent e) {
            if(volumeDragging){setVideoVolumeFromMouse(e.getX());return;}
            if(windowActionStart==null||windowStartBounds==null)return;Point now=e.getLocationOnScreen();int dx=now.x-windowActionStart.x,dy=now.y-windowActionStart.y;
            if(draggingWindow){frame.setLocation(windowStartBounds.x+dx,windowStartBounds.y+dy);return;}
            if(!resizingWindow)return;int x=windowStartBounds.x,y=windowStartBounds.y,w=windowStartBounds.width,h=windowStartBounds.height,minW=1120,minH=700;
            if((resizeMask&1)!=0){x+=dx;w-=dx;}if((resizeMask&2)!=0)w+=dx;if((resizeMask&4)!=0){y+=dy;h-=dy;}if((resizeMask&8)!=0)h+=dy;
            minW=1040;minH=640;if(w<minW){if((resizeMask&1)!=0)x-=minW-w;w=minW;}if(h<minH){if((resizeMask&4)!=0)y-=minH-h;h=minH;}frame.setBounds(x,y,w,h);
        }

        @Override public void mouseWheelMoved(MouseWheelEvent e){if(audioBounds.contains(e.getPoint())||volumeBounds.contains(e.getPoint())){video.setVolume(video.volume()-e.getPreciseWheelRotation()*.06);repaint();return;}if(page==Page.NEWS&&!newsComposeOpen){int visible=Math.max(1,(getHeight()-314)/46),max=Math.max(0,(newsPosts.size()-visible)*46);newsScroll=Math.max(0,Math.min(max,newsScroll+e.getWheelRotation()*46));repaint();}}
        private void setVideoVolumeFromMouse(int x){int start=volumeBounds.x+4,end=start+Math.max(18,volumeBounds.width-10);video.setVolume((x-start)/(double)Math.max(1,end-start));repaint();}
        private String controlAt(Point point){if(playBounds.contains(point))return"play";if(instancePathBounds.contains(point))return"instance";if(audioBounds.contains(point))return"audio";if(notificationBounds.contains(point))return"notification";if(profileBounds.contains(point))return"profile";if(installPackBounds.contains(point))return"install";if(verifyBounds.contains(point))return"verify";if(folderBounds.contains(point))return"folder";if(chatSendBounds.contains(point))return"send";for(int i=0;i<navBounds.length;i++)if(navBounds[i]!=null&&navBounds[i].contains(point))return"nav"+i;for(int i=0;i<settingBounds.length;i++)if(settingBounds[i].contains(point))return"setting"+i;return"";}

        @Override public void keyTyped(KeyEvent e){char c=e.getKeyChar();if(c<32||c==127)return;if(page==Page.ADMIN){if(adminField==0&&adminTargetDraft.length()<36)adminTargetDraft+=c;else if(adminField==1&&adminCommandDraft.length()<180)adminCommandDraft+=c;repaint();return;}if(newsComposeOpen){if(newsField==0&&newsTitleDraft.length()<100)newsTitleDraft+=c;else if(newsField==1&&newsBodyDraft.length()<5000)newsBodyDraft+=c;repaint();return;}if(!chatFocused||page!=Page.GAME)return;if(chatDraft.length()<180){chatDraft+=c;repaint();}}
        @Override public void keyPressed(KeyEvent e){if(page==Page.ADMIN){if(e.getKeyCode()==KeyEvent.VK_TAB)adminField=1-adminField;else if(e.getKeyCode()==KeyEvent.VK_ENTER&&adminField==1)queueAdminCommand(adminCommandDraft);else if(e.getKeyCode()==KeyEvent.VK_BACK_SPACE){if(adminField==0&&!adminTargetDraft.isEmpty())adminTargetDraft=adminTargetDraft.substring(0,adminTargetDraft.length()-1);else if(adminField==1&&!adminCommandDraft.isEmpty())adminCommandDraft=adminCommandDraft.substring(0,adminCommandDraft.length()-1);}repaint();return;}if(newsComposeOpen){if(e.getKeyCode()==KeyEvent.VK_ESCAPE){newsComposeOpen=false;}else if(e.getKeyCode()==KeyEvent.VK_TAB||e.getKeyCode()==KeyEvent.VK_ENTER){newsField=1-newsField;}else if(e.getKeyCode()==KeyEvent.VK_BACK_SPACE){if(newsField==0&&!newsTitleDraft.isEmpty())newsTitleDraft=newsTitleDraft.substring(0,newsTitleDraft.length()-1);else if(newsField==1&&!newsBodyDraft.isEmpty())newsBodyDraft=newsBodyDraft.substring(0,newsBodyDraft.length()-1);}repaint();return;}if(!chatFocused||page!=Page.GAME)return;if(e.getKeyCode()==KeyEvent.VK_BACK_SPACE&&!chatDraft.isEmpty()){chatDraft=chatDraft.substring(0,chatDraft.length()-1);repaint();}else if(e.getKeyCode()==KeyEvent.VK_ENTER)sendChat();else if(e.getKeyCode()==KeyEvent.VK_ESCAPE){chatFocused=false;repaint();}}
        @Override public void keyReleased(KeyEvent e){}

        private void authenticateCachedAccount(){
            if(accountSession==null||backendPollInProgress)return;backendPollInProgress=true;
            Thread.startVirtualThread(()->{try{MicrosoftAccountService.Session fresh=accountService.refresh();if(fresh!=null){accountSession=fresh;loadPlayerHead(fresh);SwingUtilities.invokeLater(this::repaint);if(apiClient.configured()){try{apiClient.authenticate(fresh);String ws=apiClient.webSocketUrl();if(ws!=null)hub.connect(ws);}catch(Exception backendError){SwingUtilities.invokeLater(()->{accountNotice=l("MINECRAFT BAĞLI / ERDVYN SERVİSİ: ","MINECRAFT LINKED / ERDVYN SERVICE: ")+shortError(backendError);repaint();});}}}}catch(Exception ex){SwingUtilities.invokeLater(()->{accountNotice=l("HESAP YENİLEME: ","ACCOUNT REFRESH: ")+loginError(ex);repaint();});}finally{backendPollInProgress=false;lastBackendPollMillis=0;}});
        }

        private void pollBackendIfDue(){
            if(!apiClient.configured()||backendPollInProgress)return;
            long now=System.currentTimeMillis();
            boolean statusDue=now-lastBackendPollMillis>=30_000;
            boolean newsDue=page==Page.NEWS&&now-lastNewsFetchMillis>=300_000;
            boolean adminDue=page==Page.ADMIN&&apiClient.current()!=null&&apiClient.current().account().admin()&&now-lastAdminFetchMillis>=30_000;
            if(!statusDue&&!newsDue&&!adminDue)return;
            if(statusDue)lastBackendPollMillis=now;
            if(newsDue)lastNewsFetchMillis=now;
            if(adminDue)lastAdminFetchMillis=now;
            backendPollInProgress=true;
            Thread.startVirtualThread(()->{
                ErdvynApiClient.Status fetchedStatus=null;
                List<ErdvynApiClient.NewsPost> fetchedPosts=null;
                List<ErdvynApiClient.AdminAccount> fetchedAdmins=null;
                try{if(statusDue)fetchedStatus=apiClient.fetchStatus();}catch(Exception ignored){}
                try{if(newsDue)fetchedPosts=apiClient.fetchNews();}catch(Exception ignored){}
                try{if(adminDue)fetchedAdmins=apiClient.fetchAdmins();}catch(Exception ignored){}
                ErdvynApiClient.Status status=fetchedStatus;
                List<ErdvynApiClient.NewsPost> posts=fetchedPosts;
                List<ErdvynApiClient.AdminAccount> admins=fetchedAdmins;
                SwingUtilities.invokeLater(()->{
                    if(status!=null){apiStatus=status;if(status.online()&&!status.players().isEmpty()){onlinePlayers.clear();onlinePlayers.addAll(status.players());}}
                    if(posts!=null){newsPosts.clear();newsPosts.addAll(posts);}
                    if(admins!=null){adminAccounts.clear();adminAccounts.addAll(admins);loadAdminHeads(admins);}
                    repaint();
                });
                backendPollInProgress=false;
            });
        }

        private void loadAdminHeads(List<ErdvynApiClient.AdminAccount> admins){for(ErdvynApiClient.AdminAccount admin:admins)if(!adminHeads.containsKey(admin.uuid()))Thread.startVirtualThread(()->{try{BufferedImage head=skinService.head(admin.uuid());if(head!=null)SwingUtilities.invokeLater(()->{adminHeads.put(admin.uuid(),head);repaint();});}catch(Exception ignored){}});}

        private void requestGameStart(){
            if(gameLaunching||accountLoginInProgress)return;
            if(accountSession==null){launchAfterLogin=true;profileOpen=true;beginMicrosoftLogin();return;}
            packStatus=l("OYNA İSTEĞİ ALINDI / SENKRONİZASYON BEKLİYOR","PLAY REQUESTED / SYNC PENDING");
            LauncherLog.write("PLAY requested by "+accountSession.name()+"; instance="+LauncherPaths.managedInstance().toAbsolutePath().normalize());
            startBoot();
        }

        private void launchGameAsync(){
            if(gameLaunching)return;gameLaunching=true;beginLaunchOverlay();accountNotice=l("MINECRAFT BAŞLATILIYOR...","STARTING MINECRAFT...");
            Thread.startVirtualThread(()->{try{
                LauncherLog.write("Launch pipeline started");
                setLaunchStage(l("MINECRAFT ÇALIŞMA ORTAMI DENETLENİYOR","CHECKING MINECRAFT RUNTIME"),.08,"[EXEC] "+l("JAVA 21 VE NEOFORGE ARANIYOR","PROBING JAVA 21 AND NEOFORGE"));
                packStatus=l("MINECRAFT ÇALIŞMA ORTAMI DENETLENİYOR","CHECKING MINECRAFT RUNTIME");
                installService.ensureInstalled(line->{LauncherLog.write(line);setLaunchStage(l("ÇALIŞMA ORTAMI HAZIRLANIYOR","PREPARING RUNTIME"),.16,"[OK] "+line);SwingUtilities.invokeLater(()->{packLog.add(line);if(packLog.size()>80)packLog.remove(0);repaint();});});
                setLaunchStage(l("UZAK PAKET MANİFESTİ DENETLENİYOR","CHECKING REMOTE PACK MANIFEST"),.20,"[EXEC] "+l("SUNUCU PAKET İMZASI İSTENDİ","SERVER PACK SIGNATURE REQUESTED"));
                packVerifying=true;packProgress=0;packLog.clear();packStatus=l("UZAK PAKET MANİFESTİ DENETLENİYOR","CHECKING REMOTE PACK MANIFEST");
                PackService.Result verifiedPack=packService.verifyAndRepair(this::onPackLaunchProgress);
                packVerifying=false;if(verifiedPack.failed()>0)throw new IllegalStateException(l("Mod paketi doğrulanamadı; oyun başlatılmadı.","Modpack verification failed; launch was blocked."));packInstalled=true;
                LauncherLog.write("Pack verified version="+verifiedPack.version()+" downloaded="+verifiedPack.downloaded());
                setLaunchStage(l("PAKET İMZASI DOĞRULANDI","PACKAGE SIGNATURE VERIFIED"),.66,"[OK] "+verifiedPack.version()+" / "+verifiedPack.downloaded()+" "+l("DOSYA ALINDI","FILES FETCHED"));
                setLaunchStage(l("MICROSOFT OTURUMU YENİLENİYOR","REFRESHING MICROSOFT SESSION"),.70,"[EXEC] "+l("HESAP BİLETİ DOĞRULANIYOR","VALIDATING ACCOUNT TOKEN"));MicrosoftAccountService.Session fresh=accountService.refresh();if(fresh==null)throw new IllegalStateException(l("Microsoft hesabı bağlı değil.","Microsoft account is not linked."));accountSession=fresh;String ticket=null;
                setLaunchStage(l("ERDVYN OTURUM BİLETİ HAZIRLANIYOR","PREPARING ERDVYN SESSION TICKET"),.74,"[EXEC] "+l("GÜVENLİ BAĞLANTI BİLETİ İSTENDİ","SECURE LINK TICKET REQUESTED"));if(apiClient.configured()){if(apiClient.current()==null)apiClient.authenticate(fresh);String ws=apiClient.webSocketUrl();if(ws!=null)hub.connect(ws);ticket=apiClient.createGameTicket(PackService.activeManifestSha256());}
                setLaunchStage(l("JVM BAŞLATILIYOR","STARTING JVM"),.80,"[EXEC] JVM / XMX "+gameOptions.ramGb()+"G / NEOFORGE 21.1.243");packStatus=l("JVM BAŞLATILIYOR","STARTING JVM");LauncherLog.write("Starting JVM as "+fresh.name());
                Process minecraft=launchService.launch(fresh,gameOptions.ramGb(),autoConnect,ticket,this::onMinecraftLaunchSignal);launchPid=minecraft.pid();setLaunchStage(l("MINECRAFT BEKLENİYOR","AWAITING MINECRAFT"),.88,"[WAIT] "+l("RENDER PENCERESİNDEN HAZIR SİNYALİ BEKLENİYOR","AWAITING READY SIGNAL FROM RENDER WINDOW"));
                launchService.awaitReady(minecraft,this::onMinecraftLaunchSignal);
                LauncherLog.write("Minecraft process reported started");
                setLaunchStage(l("MINECRAFT HAZIR / LAUNCHER KAPATILIYOR","MINECRAFT READY / CLOSING LAUNCHER"),1,"[OK] "+l("RENDER DEVİR TESLİMİ TAMAMLANDI","RENDER HANDOFF COMPLETE"));Thread.sleep(850);SwingUtilities.invokeLater(frame::onGameProcessStarted);
            }catch(Exception ex){LauncherLog.write("LAUNCH ERROR: "+shortError(ex));SwingUtilities.invokeLater(()->{gameLaunching=false;launchFailed=true;launchOverlayActive=true;packVerifying=false;accountNotice=l("BAŞLATMA HATASI: ","LAUNCH ERROR: ")+shortError(ex);packStatus=accountNotice;launchStatus=accountNotice;appendLaunchTrace("[FAIL] "+shortError(ex));repaint();});}});
        }

        private void beginMicrosoftLogin(){
            if(accountLoginInProgress)return;playUiSound(82);accountLoginInProgress=true;accountNotice=l("MICROSOFT GİRİŞİ HAZIRLANIYOR...","PREPARING MICROSOFT SIGN-IN...");repaint();
            Thread.startVirtualThread(()->{try{MicrosoftAccountService.Session session=accountService.login(code->{try{Desktop.getDesktop().browse(URI.create(code.getDirectVerificationUri()));}catch(Exception ignored){}SwingUtilities.invokeLater(()->{accountNotice=l("KOD: ","CODE: ")+code.getUserCode()+" / "+l("TARAYICIDA ONAYLA","CONFIRM IN BROWSER");repaint();});});accountSession=session;loadPlayerHead(session);boolean erdvynLinked=false;String backendFailure="";if(apiClient.configured()){try{apiClient.authenticate(session);String ws=apiClient.webSocketUrl();if(ws!=null)hub.connect(ws);erdvynLinked=true;}catch(Exception backendError){backendFailure=shortError(backendError);}}boolean linked=erdvynLinked;String backendMessage=backendFailure;SwingUtilities.invokeLater(()->{accountLoginInProgress=false;accountNotice=linked?l("ERDVYN HESABI UUID İLE BAĞLANDI","ERDVYN ACCOUNT LINKED TO UUID"):backendMessage.isBlank()?l("MINECRAFT HESABI BAĞLANDI","MINECRAFT ACCOUNT LINKED"):l("MINECRAFT BAĞLI / ERDVYN SERVİSİ: ","MINECRAFT LINKED / ERDVYN SERVICE: ")+backendMessage;notifications.add(l("Minecraft hesabı bağlandı: ","Minecraft account linked: ")+session.name());profileOpen=false;if(launchAfterLogin){launchAfterLogin=false;if(!apiClient.configured()||linked)startBoot();}repaint();});}catch(Exception ex){SwingUtilities.invokeLater(()->{accountLoginInProgress=false;launchAfterLogin=false;accountNotice=l("GİRİŞ HATASI: ","SIGN-IN ERROR: ")+loginError(ex);notifications.add(accountNotice);repaint();});}});
        }
        private void beginErdvynLogin(){openAccountPage("/login?source=launcher",96,language==Language.TR?"Güvenli hesap ekranı açıldı.":"Secure account page opened.");}
        private void openAccountPage(String path,double sound,String success){playUiSound(sound);String accountUrl=LauncherConfig.accountUrl();if(accountUrl.isBlank()){accountNotice=language==Language.TR?"Erdvyn hesap sunucusu adresi gerekli.":"Erdvyn account server URL required.";repaint();return;}try{Desktop.getDesktop().browse(URI.create(accountUrl.replaceAll("/+$","")+path));accountNotice=success;}catch(Exception ex){accountNotice=ex.getMessage();}repaint();}

        private void publishNews(){
            if(newsPublishInProgress)return;String title=newsTitleDraft.strip(),body=newsBodyDraft.strip();if(title.length()<3||body.length()<3){newsNotice=l("Başlık ve içerik en az 3 karakter olmalı.","Title and body must be at least 3 characters.");return;}newsPublishInProgress=true;newsNotice=l("Duyuru yayınlanıyor...","Publishing dispatch...");
            Thread.startVirtualThread(()->{try{apiClient.publishNews(title,body);List<ErdvynApiClient.NewsPost> posts=apiClient.fetchNews();SwingUtilities.invokeLater(()->{newsPosts.clear();newsPosts.addAll(posts);newsTitleDraft="";newsBodyDraft="";newsComposeOpen=false;newsPublishInProgress=false;newsNotice=l("Duyuru yayınlandı.","Dispatch published.");repaint();});}catch(Exception ex){SwingUtilities.invokeLater(()->{newsPublishInProgress=false;newsNotice=l("YAYIN HATASI: ","PUBLISH ERROR: ")+shortError(ex);repaint();});}});
        }

        private void adminAccessChange(boolean grant){
            if(adminActionInProgress)return;String target=adminTargetDraft.strip();if(target.isBlank()){adminNotice=l("HATA: Oyuncu adı veya UUID gerekli.","ERROR: Player name or UUID is required.");repaint();return;}adminActionInProgress=true;adminNotice=l("YETKİ DOĞRULANIYOR...","VERIFYING AUTHORITY...");repaint();
            Thread.startVirtualThread(()->{try{String name=grant?apiClient.grantAdmin(target):apiClient.revokeAdmin(target);SwingUtilities.invokeLater(()->{adminActionInProgress=false;lastBackendPollMillis=0;adminNotice=(grant?l("YÖNETİCİ YETKİSİ VERİLDİ: ","ADMIN GRANTED: "):l("YÖNETİCİ YETKİSİ ALINDI: ","ADMIN REVOKED: "))+name;repaint();});}catch(Exception ex){SwingUtilities.invokeLater(()->{adminActionInProgress=false;adminNotice=l("HATA: ","ERROR: ")+shortError(ex);repaint();});}});
        }

        private void queueAdminCommand(String raw){
            if(adminActionInProgress)return;String command=raw.strip();if(command.isBlank()){adminNotice=l("HATA: Komut veya oyuncu adı gerekli.","ERROR: Command or player name is required.");repaint();return;}adminActionInProgress=true;adminNotice=l("KOMUT GÜVENLİ KUYRUĞA ALINIYOR...","QUEUING SECURE COMMAND...");repaint();
            Thread.startVirtualThread(()->{try{long id=apiClient.queueAdminCommand(command);SwingUtilities.invokeLater(()->{adminActionInProgress=false;adminNotice=l("KOMUT KUYRUKTA / ID ","COMMAND QUEUED / ID ")+id;adminCommandDraft="";repaint();});}catch(Exception ex){SwingUtilities.invokeLater(()->{adminActionInProgress=false;adminNotice=l("HATA: ","ERROR: ")+shortError(ex);repaint();});}});
        }

        private void verifyModpack(){
            if(packVerifying)return;packVerifying=true;packProgress=0;packStatus="";packLog.clear();packLog.add(l("> DOSYA MANİFESTİ HAZIRLANIYOR","> PREPARING FILE MANIFEST"));repaint();
            Thread.startVirtualThread(()->{try{PackService.Result result=packService.verifyAndRepair(progress->SwingUtilities.invokeLater(()->{packProgress=progress.value();packLog.add(progress.line());if(packLog.size()>80)packLog.remove(0);repaint();}));SwingUtilities.invokeLater(()->{packVerifying=false;packProgress=1;packInstalled=result.failed()==0;packStatus=result.failed()==0?String.format(Locale.ROOT,l("%d doğrulandı / %d indirildi / %d oyuncu ayarı korundu","%d verified / %d downloaded / %d player settings kept"),result.verified(),result.downloaded(),result.kept()):String.format(Locale.ROOT,l("%d hata / oyun başlatma engellendi","%d errors / launch blocked"),result.failed());if(result.failed()==0)notifications.add(result.downloaded()>0?String.format(Locale.ROOT,l("Paket hazır: %d dosya indirildi.","Pack ready: %d files downloaded."),result.downloaded()):l("Paket doğrulandı; oyuncu ayarları korundu.","Pack verified; player settings were kept."));refreshPackSummary();repaint();});}catch(Exception ex){SwingUtilities.invokeLater(()->{packVerifying=false;packStatus=l("HATA: ","ERROR: ")+shortError(ex);packLog.add("[FAIL] "+shortError(ex));notifications.add(packStatus);repaint();});}});
        }
        private void openModpackFolder(){openFolderKind("");}
        private void openSettingsFolder(int index){String kind=switch(index){case 1->"mods";case 2->"resourcepacks";case 3->"shaderpacks";default->"";};openFolderKind(kind);}
        private void openOptionsFile(){try{Path options=LauncherPaths.gameDirectory().resolve("options.txt");if(!Files.exists(options))Files.writeString(options,"");Desktop.getDesktop().open(options.toFile());}catch(Exception ex){packStatus=l("AYAR DOSYASI HATASI: ","OPTIONS FILE ERROR: ")+shortError(ex);}repaint();}
        private void openFolderKind(String kind){try{Path root=kind.isBlank()?LauncherPaths.gameDirectory():LauncherPaths.folder(kind);Files.createDirectories(root);Desktop.getDesktop().open(root.toFile());}catch(Exception ex){packStatus=l("KLASÖR HATASI: ","FOLDER ERROR: ")+shortError(ex);}repaint();}
        private static String shortError(Throwable error){Throwable current=error;while(current.getCause()!=null&&current.getCause()!=current)current=current.getCause();String text=current.getMessage();if(text==null||text.isBlank())text=current.getClass().getSimpleName();return text.length()>110?text.substring(0,107)+"...":text;}
        private String loginError(Throwable error){String raw=shortError(error),lower=raw.toLowerCase(Locale.ROOT);if(lower.contains("does not own minecraft")||lower.contains("no java profile")||lower.contains("entitlement")||lower.contains("minecraft profile"))return l("Microsoft girişi tamamlandı fakat bu hesap Minecraft: Java Edition sahibi değil veya Java profili oluşturulmamış.","Microsoft sign-in completed, but this account does not own Minecraft: Java Edition or has no Java profile.");return raw;}
        private void loadPlayerHead(MicrosoftAccountService.Session session){try{BufferedImage loaded=skinService.head(session);if(loaded!=null)SwingUtilities.invokeLater(()->{playerHead=loaded;repaint();});}catch(Exception ignored){}}
        private void checkLauncherUpdateAsync(){if(LauncherConfig.launcherManifestUrl().isBlank())return;Thread.startVirtualThread(()->{try{LauncherUpdateService.Update found=launcherUpdateService.check();if(found==null)return;Path downloaded=launcherUpdateService.download(found);SwingUtilities.invokeLater(()->{launcherUpdate=found;launcherInstaller=downloaded;notifications.add(String.format(Locale.ROOT,l("Launcher %s hazır. Bildirim panelinden kurabilirsin.","Launcher %s is ready. Install it from notifications."),found.version()));repaint();});}catch(Exception ex){SwingUtilities.invokeLater(()->{notifications.add(l("Launcher güncellemesi denetlenemedi: ","Launcher update check failed: ")+shortError(ex));repaint();});}});}
        private void launchPreparedUpdate(){if(launcherInstaller==null||!Files.isRegularFile(launcherInstaller))return;try{Desktop.getDesktop().open(launcherInstaller.toFile());frame.shutdownAndExit();}catch(Exception ex){accountNotice=l("GÜNCELLEME HATASI: ","UPDATE ERROR: ")+shortError(ex);repaint();}}
        private void sendChat(){String message=chatDraft.strip();if(message.isEmpty())return;playUiSound(112);if(hub.connected())hub.sendChat(message);else chatLines.add(new ChatLine("SYSTEM",language==Language.TR?"Sohbet sunucusuna bağlı değilsin.":"Not connected to the chat server.",LocalTime.now()));chatDraft="";repaint();}

        void shutdown(){timer.stop();hub.close();serverStatus.close();video.setUiGate(false);}
        private void onServerStatus(MinecraftServerStatus.Snapshot snapshot){SwingUtilities.invokeLater(()->{serverSnapshot=snapshot;if(!snapshot.sample().isEmpty()&&!hub.connected()){onlinePlayers.clear();onlinePlayers.addAll(snapshot.sample());}repaint();});}
        private void onHubEvent(HubEvent event){SwingUtilities.invokeLater(()->{if(event.kind.equals("chat"))chatLines.add(new ChatLine(event.author,event.value,LocalTime.now()));else if(event.kind.equals("players")){onlinePlayers.clear();if(!event.value.isBlank())onlinePlayers.addAll(Arrays.stream(event.value.split(",")).map(String::strip).filter(s->!s.isEmpty()).toList());}else if(event.kind.equals("status"))chatLines.add(new ChatLine("SYSTEM",event.value,LocalTime.now()));repaint();});}

        private void playUiSound(double pitch){Thread.startVirtualThread(()->{try{
            float rate=22050;int length=(int)(rate*.042);byte[] data=new byte[length*2];Random click=new Random(Double.doubleToLongBits(pitch)^System.nanoTime());
            for(int i=0;i<length;i++){
                double t=i/rate,topNoise=(click.nextDouble()*2-1)*Math.exp(-t*190),switchLeaf=Math.sin(2*Math.PI*(1780+pitch*1.8)*t)*Math.exp(-t*170);
                double delayed=Math.max(0,t-.0095),gate=t>=.0095?1:0,bottomNoise=(click.nextDouble()*2-1)*Math.exp(-delayed*155)*gate;
                double metal=(Math.sin(2*Math.PI*2380*delayed)+.45*Math.sin(2*Math.PI*3260*delayed))*Math.exp(-delayed*125)*gate;
                double body=Math.sin(2*Math.PI*(132+pitch*.22)*t)*Math.exp(-t*70),shaped=topNoise*.28+switchLeaf*.18+bottomNoise*.24+metal*.12+body*.10;
                short sample=(short)(shaped*790);data[i*2]=(byte)sample;data[i*2+1]=(byte)(sample>>8);
            }
            javax.sound.sampled.AudioFormat format=new javax.sound.sampled.AudioFormat(rate,16,1,true,false);try(javax.sound.sampled.SourceDataLine line=javax.sound.sampled.AudioSystem.getSourceDataLine(format)){line.open(format);line.start();line.write(data,0,data.length);line.drain();}
        }catch(Exception ignored){}});}

        private int edgeMask(Point p){int margin=8,mask=0;if(p.x<=margin)mask|=1;if(p.x>=getWidth()-margin)mask|=2;if(p.y<=margin)mask|=4;if(p.y>=getHeight()-margin)mask|=8;return mask;}
        private int cursorFor(int mask){return switch(mask){case 1,2->Cursor.E_RESIZE_CURSOR;case 4,8->Cursor.N_RESIZE_CURSOR;case 5,10->Cursor.NW_RESIZE_CURSOR;case 6,9->Cursor.NE_RESIZE_CURSOR;default->Cursor.DEFAULT_CURSOR;};}

        String runInteractionSelfTest(){
            List<String> failures=new ArrayList<>();Rectangle original=frame.getBounds();
            int sx=original.x+220,sy=original.y+35;
            mousePressed(testEvent(MouseEvent.MOUSE_PRESSED,220,35,sx,sy));mouseDragged(testEvent(MouseEvent.MOUSE_DRAGGED,280,75,sx+60,sy+40));mouseReleased(testEvent(MouseEvent.MOUSE_RELEASED,280,75,sx+60,sy+40));
            if(frame.getX()!=original.x+60||frame.getY()!=original.y+40)failures.add("window-drag");
            frame.validate();Rectangle moved=frame.getBounds();int rx=getWidth()-2,ry=getHeight()/2;
            mousePressed(testEvent(MouseEvent.MOUSE_PRESSED,rx,ry,moved.x+rx,moved.y+ry));mouseDragged(testEvent(MouseEvent.MOUSE_DRAGGED,rx+90,ry,moved.x+rx+90,moved.y+ry));mouseReleased(testEvent(MouseEvent.MOUSE_RELEASED,rx+90,ry,moved.x+rx+90,moved.y+ry));
            if(frame.getWidth()<moved.width+85)failures.add("edge-resize");
            BufferedImage buffer=new BufferedImage(getWidth(),getHeight(),BufferedImage.TYPE_INT_ARGB);Graphics2D graphics=buffer.createGraphics();paint(graphics);graphics.dispose();
            mouseClicked(testEvent(MouseEvent.MOUSE_CLICKED,navBounds[1].x+12,navBounds[1].y+12,frame.getX()+navBounds[1].x+12,frame.getY()+navBounds[1].y+12));
            if(page!=Page.NEWS)failures.add("news-navigation");
            navigate(Page.MAP);paintForTest();if(page!=Page.MAP)failures.add("world-map");
            selectedNews=-1;navigate(Page.HOME);buffer=new BufferedImage(getWidth(),getHeight(),BufferedImage.TYPE_INT_ARGB);graphics=buffer.createGraphics();paint(graphics);graphics.dispose();
            mouseClicked(testEvent(MouseEvent.MOUSE_CLICKED,profileBounds.x+10,profileBounds.y+10,frame.getX()+profileBounds.x+10,frame.getY()+profileBounds.y+10));
            if(!profileOpen)failures.add("profile-card");profileOpen=false;
            navigate(Page.SETTINGS);paintForTest();boolean previous=autoUpdate;mouseClicked(testEvent(MouseEvent.MOUSE_CLICKED,settingBounds[0].x+10,settingBounds[0].y+10,frame.getX()+settingBounds[0].x+10,frame.getY()+settingBounds[0].y+10));if(autoUpdate==previous)failures.add("settings-toggle");autoUpdate=previous;preferences.putBoolean("autoUpdate",previous);Language beforeLanguage=language;mouseClicked(testEvent(MouseEvent.MOUSE_CLICKED,settingsLanguageBounds.x+8,settingsLanguageBounds.y+8,frame.getX()+settingsLanguageBounds.x+8,frame.getY()+settingsLanguageBounds.y+8));if(language==beforeLanguage)failures.add("settings-language");
            language=beforeLanguage;preferences.put("language",language.name());
            navigate(Page.PACK);paintForTest();if(verifyBounds.isEmpty()||folderBounds.isEmpty())failures.add("modpack-actions");
            navigate(Page.GAME);paintForTest();mouseClicked(testEvent(MouseEvent.MOUSE_CLICKED,chatInputBounds.x+8,chatInputBounds.y+8,frame.getX()+chatInputBounds.x+8,frame.getY()+chatInputBounds.y+8));keyTyped(new KeyEvent(this,KeyEvent.KEY_TYPED,System.currentTimeMillis(),0,KeyEvent.VK_UNDEFINED,'x'));if(!chatFocused||!chatDraft.endsWith("x"))failures.add("game-panel-chat");chatDraft="";chatFocused=false;startBoot();if(!bootActive||bootLogCount<1)failures.add("boot-sequence");bootActive=false;startLaunchPreview();paintForTest();if(!launchOverlayActive||launchTrace.size()<6||launchPid<=0)failures.add("launch-terminal");launchOverlayActive=false;gameLaunching=false;
            frame.setBounds(original);
            return failures.isEmpty()?"PASS: drag, resize, dispatch, world-map, profile, settings, language, modpack, game-panel, boot-sequence, launch-terminal":"FAIL: "+String.join(", ",failures);
        }

        private void paintForTest(){BufferedImage buffer=new BufferedImage(Math.max(1,getWidth()),Math.max(1,getHeight()),BufferedImage.TYPE_INT_ARGB);Graphics2D graphics=buffer.createGraphics();paint(graphics);graphics.dispose();}

        private MouseEvent testEvent(int id,int x,int y,int xAbs,int yAbs){return new MouseEvent(this,id,System.currentTimeMillis(),0,x,y,xAbs,yAbs,1,false,MouseEvent.BUTTON1);}

        static final class Dust {
            double x, y, speed, phase, angle; int size;
            Dust(double x, double y, double speed, int size, double phase) { this.x = x; this.y = y; this.speed = speed; this.size = size; this.phase = phase; }
        }
        record ChatLine(String author,String message,LocalTime time){}
    }

    record HubEvent(String kind,String author,String value){}
    static final class HubClient implements WebSocket.Listener {
        private final Consumer<HubEvent> events;private final StringBuilder incoming=new StringBuilder();private volatile WebSocket socket;private volatile boolean connected;
        HubClient(Consumer<HubEvent> events){this.events=events;}
        boolean connected(){return connected;}
        void connect(){String endpoint=System.getenv("ERDVYN_HUB_WS");if(endpoint==null||endpoint.isBlank())return;connect(endpoint);}
        synchronized void connect(String endpoint){if(endpoint==null||endpoint.isBlank()||connected)return;try{var builder=HttpClient.newHttpClient().newWebSocketBuilder();String token=System.getenv("ERDVYN_HUB_TOKEN");if(token!=null&&!token.isBlank()&&!endpoint.contains("token="))builder.header("Authorization","Bearer "+token);builder.buildAsync(URI.create(endpoint),this).exceptionally(error->{events.accept(new HubEvent("status","SYSTEM","Hub: "+error.getMessage()));return null;});}catch(Exception ex){events.accept(new HubEvent("status","SYSTEM","Hub: "+ex.getMessage()));}}
        void sendChat(String message){WebSocket active=socket;if(active!=null&&connected)active.sendText("{\"type\":\"chat\",\"message\":\""+escape(message)+"\"}",true);}
        void close(){WebSocket active=socket;socket=null;connected=false;if(active!=null)try{active.sendClose(WebSocket.NORMAL_CLOSURE,"Launcher closed");}catch(Exception ignored){active.abort();}}
        @Override public void onOpen(WebSocket webSocket){socket=webSocket;connected=true;events.accept(new HubEvent("status","SYSTEM","Erdvyn hub connected."));webSocket.request(1);}
        @Override public CompletionStage<?> onText(WebSocket webSocket,CharSequence data,boolean last){incoming.append(data);if(last){String raw=incoming.toString();incoming.setLength(0);parse(raw);}webSocket.request(1);return null;}
        @Override public CompletionStage<?> onClose(WebSocket webSocket,int statusCode,String reason){connected=false;events.accept(new HubEvent("status","SYSTEM","Hub disconnected."));return WebSocket.Listener.super.onClose(webSocket,statusCode,reason);}
        @Override public void onError(WebSocket webSocket,Throwable error){connected=false;events.accept(new HubEvent("status","SYSTEM","Hub: "+error.getMessage()));}
        private void parse(String raw){String type=json(raw,"type");if("players".equals(type)){events.accept(new HubEvent("players","",json(raw,"players")));return;}if("chat".equals(type))events.accept(new HubEvent("chat",Optional.ofNullable(json(raw,"author")).filter(s->!s.isBlank()).orElse("PLAYER"),json(raw,"message")));}
        private static String json(String raw,String key){java.util.regex.Matcher array=java.util.regex.Pattern.compile("\\\""+java.util.regex.Pattern.quote(key)+"\\\"\\s*:\\s*\\[(.*?)]").matcher(raw);if(array.find())return Arrays.stream(array.group(1).split(",")).map(s->s.strip().replaceAll("^\\\"|\\\"$","")).reduce((a,b)->a+","+b).orElse("");java.util.regex.Matcher value=java.util.regex.Pattern.compile("\\\""+java.util.regex.Pattern.quote(key)+"\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(raw);return value.find()?value.group(1).replace("\\n","\n").replace("\\\"","\""):"";}
        private static String escape(String text){return text.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n");}
    }
}
