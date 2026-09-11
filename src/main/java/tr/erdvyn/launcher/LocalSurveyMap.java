package tr.erdvyn.launcher;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Read-only local map viewer. Never uploads exploration or edits Xaero saves. */
final class LocalSurveyMap {
    private final Path surveyRoot;
    LocalSurveyMap(){this(LauncherPaths.managedInstance().resolve("erdvyn-survey"));}
    LocalSurveyMap(Path surveyRoot){this.surveyRoot=surveyRoot;}
    private record Tile(int x,int z,BufferedImage image){}
    private record CachedTile(long modified,long size,Tile tile){}
    // Accessed only by the single refresh worker; unchanged PNGs are not decoded again.
    private final Map<Path,CachedTile> cache=new HashMap<>();
    private volatile java.util.List<Tile> tiles=java.util.List.of();
    private volatile String dimension="",error="";
    private volatile long updatedAt;
    private boolean turkish=true;
    void setTurkish(boolean value){turkish=value;}
    String status(){
        if(!error.isEmpty())return (turkish?"HARİTA EŞİTLEME HATASI: ":"MAP SYNC ERROR: ")+error;
        if(dimension.isEmpty())return turkish?"Yerel eşitleme için oyunda haritayı aç":"Open the map in game to start local synchronization";
        String name=switch(dimension){case "OVERWORLD","SURFACE"->turkish?"YERYÜZÜ":"OVERWORLD";case "THE_NETHER"->"NETHER";case "THE_END"->"END";default->dimension;};
        return name+" / "+tiles.size()+(turkish?" BÖLGE / ":" CHUNKS / ")+(System.currentTimeMillis()-updatedAt<15000?(turkish?"CANLI EŞİTLEME":"LIVE SYNC"):(turkish?"KAYITLI":"SAVED"))+(worlds.size()>1?(turkish?" / SONRAKİ DÜNYA: BAŞLIĞA TIKLA":" / NEXT WORLD: CLICK HEADER"):"");
    }
    private volatile boolean loading;
    private volatile java.util.List<Path> worlds=java.util.List.of();
    private volatile Path selectedWorld;
    private volatile Path displayedWorld;
    private long nextRead;
    private double cx,cz,zoom=1;
    private final Rectangle bounds=new Rectangle();
    private Point drag;
    void paint(Graphics2D g,int x,int y,int w,int h){
        bounds.setBounds(x,y,w,h);
        if(System.currentTimeMillis()>nextRead&&!loading){nextRead=System.currentTimeMillis()+3000;loading=true;Thread.startVirtualThread(this::read);}
        Shape old=g.getClip();g.clip(bounds);g.setColor(new Color(10,14,12));g.fillRect(x,y,w,h);
        double ratio=ratio();double ox=x+w/2.0-cx*ratio,oz=y+h/2.0-cz*ratio;
        g.setColor(new Color(31,35,28));for(int row=y;row<y+h;row+=5)g.drawLine(x,row,x+w,row);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        for(var tile:tiles){int px=(int)Math.floor(ox+tile.x*16.0*ratio),py=(int)Math.floor(oz+tile.z*16.0*ratio),s=Math.max(1,(int)Math.ceil(16*ratio));if(bounds.intersects(px,py,s,s))g.drawImage(tile.image,px,py,s,s,null);}
        g.setColor(new Color(201,147,82));
        for(int i=0;i<=8;i++){int px=(int)(ox+(-20000+i*5000)*ratio),py=(int)(oz+(-20000+i*5000)*ratio);g.drawLine(px,(int)(oz-20000*ratio),px,(int)(oz+20000*ratio));g.drawLine((int)(ox-20000*ratio),py,(int)(ox+20000*ratio),py);}
        for(int i=0;i<8;i++){
            int px=(int)(ox+(-17500+i*5000)*ratio),py=(int)(oz+(-17500+i*5000)*ratio);
            g.drawString(Integer.toString(i+1),px,Math.min(y+h-24,(int)(oz+20000*ratio)-5));
            g.drawString(Character.toString((char)('A'+i)),Math.max(x+5,(int)(ox-20000*ratio)+5),py);
        }
        g.setColor(new Color(9,12,10,235));g.fillRect(x,y,w,25);g.setColor(new Color(227,209,172));
        String caption=status();
        while(caption.length()>3&&g.getFontMetrics().stringWidth(caption)>w-24)caption=caption.substring(0,caption.length()-4)+"...";
        g.drawString(caption,x+10,y+17);
        g.setColor(new Color(9,12,10,235));g.fillRect(x,y+h-24,w,24);g.setColor(new Color(201,147,82));g.drawString(turkish?"TEKERLEK: YAKINLAŞTIR / SÜRÜKLE: KAYDIR / ÇİFT TIKLA: GENEL BAKIŞ":"SCROLL: ZOOM / DRAG: PAN / DOUBLE CLICK: OVERVIEW",x+10,y+h-8);
        g.setClip(old);
    }
    private void read(){try{
        Path root=surveyRoot;
        if(!Files.isDirectory(root)){tiles=java.util.List.of();cache.clear();dimension="";error="";return;}
        Path latest=null;long time=-1;var choices=new ArrayList<Path>();
        try(var dirs=Files.list(root)){for(Path dir:dirs.filter(Files::isDirectory).toList()){
            Path meta=dir.resolve("metadata.json");if(Files.isRegularFile(meta)){choices.add(dir);if(Files.getLastModifiedTime(meta).toMillis()>time){latest=dir;time=Files.getLastModifiedTime(meta).toMillis();}}
        }}
        choices.sort(Comparator.comparing(Path::toString));worlds=java.util.List.copyOf(choices);
        if(selectedWorld!=null&&choices.contains(selectedWorld)){latest=selectedWorld;time=Files.getLastModifiedTime(latest.resolve("metadata.json")).toMillis();}
        if(latest==null){tiles=java.util.List.of();cache.clear();dimension="";error="";return;}
        String dimension="SURFACE";
        Path metadata=latest.resolve("metadata.json");
        if(Files.size(metadata)<65536){
            var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(metadata.toFile());
            dimension=json.path("dimension").asText("SURFACE").replace("minecraft:","").toUpperCase(Locale.ROOT);
        }
        var result=new ArrayList<Tile>();var seen=new HashSet<Path>();
        try(var files=Files.list(latest)){for(Path file:files.filter(p->p.getFileName().toString().matches("-?\\d+_-?\\d+\\.png")).limit(100000).toList()){
            try {
                String[] xy=file.getFileName().toString().replace(".png","").split("_");
                int tx=Integer.parseInt(xy[0]),tz=Integer.parseInt(xy[1]);
                if(tx < -1250 || tx >= 1250 || tz < -1250 || tz >= 1250)continue;
                long modified=Files.getLastModifiedTime(file).toMillis(),size=Files.size(file);
                if(size>65536)continue;
                seen.add(file);var cached=cache.get(file);
                if(cached==null||cached.modified()!=modified||cached.size()!=size){
                    var image=ImageIO.read(file.toFile());
                    if(image==null||image.getWidth()!=16||image.getHeight()!=16)continue;
                    cached=new CachedTile(modified,size,new Tile(tx,tz,image));cache.put(file,cached);
                }
                result.add(cached.tile());
            } catch(java.io.IOException|NumberFormatException ignored) { /* Retry only this tile next refresh. */ }
        }}cache.keySet().retainAll(seen);tiles=java.util.List.copyOf(result);displayedWorld=latest;this.dimension=dimension;updatedAt=time;error="";
    }catch(Exception e){error=e.getClass().getSimpleName();}finally{loading=false;}}
    private double ratio(){return Math.max(1,Math.min(bounds.width-40,bounds.height-40))/40000.0*zoom;}
    boolean press(Point p){if(!bounds.contains(p))return false;
        var available=worlds;
        if(p.y<bounds.y+25&&available.size()>1){
            selectedWorld=available.get(Math.floorMod(available.indexOf(displayedWorld)+1,available.size()));
            reset();nextRead=0;return true;
        }
        drag=p;return true;}
    boolean drag(Point p){if(drag==null)return false;cx-=(p.x-drag.x)/ratio();cz-=(p.y-drag.y)/ratio();drag=p;clamp();return true;}
    void release(){drag=null;}
    boolean wheel(Point p,double amount){if(!bounds.contains(p))return false;zoom=Math.max(1,Math.min(2048,zoom*Math.pow(1.2,-amount)));clamp();return true;}
    void reset(){zoom=1;cx=cz=0;}
    private void clamp(){double lx=Math.max(0,20000-bounds.width/2.0/ratio())+1400,lz=Math.max(0,20000-bounds.height/2.0/ratio())+1400;cx=Math.max(-lx,Math.min(lx,cx));cz=Math.max(-lz,Math.min(lz,cz));}
}
