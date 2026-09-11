package tr.erdvyn.launcher;

import java.awt.Point;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class LocalSurveyMapTest {
    @TempDir Path root;
    @Test void exportedXaeroTilesRenderWhenFixtureProvided()throws Exception{
        String fixture=System.getenv("ERDVYN_SURVEY_FIXTURE");
        if(fixture==null)return;
        var map=new LocalSurveyMap(Path.of(fixture));
        var read=LocalSurveyMap.class.getDeclaredMethod("read");read.setAccessible(true);read.invoke(map);
        assertFalse(((java.util.List<?>)field(map,"tiles")).isEmpty(),"No exported Xaero tiles");
        var image=new BufferedImage(1000,700,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        try{map.paint(g,0,0,1000,700);map.wheel(new Point(500,350),-25);map.paint(g,0,0,1000,700);}finally{g.dispose();}
        Path output=Path.of("build","reports","local-survey-preview.png");Files.createDirectories(output.getParent());ImageIO.write(image,"png",output.toFile());
    }
    private static Object field(LocalSurveyMap map,String name)throws Exception{
        var f=LocalSurveyMap.class.getDeclaredField(name);f.setAccessible(true);return f.get(map);
    }
    @Test void zoomAndPanStayBounded()throws Exception{
        var map=new LocalSurveyMap(root);var image=new BufferedImage(900,600,2);var g=image.createGraphics();
        try{map.paint(g,0,0,900,600);}finally{g.dispose();}
        for(int i=0;i<100;i++)map.wheel(new Point(450,300),10);
        assertEquals(1d,(double)field(map,"zoom"));
        map.press(new Point(450,300));map.drag(new Point(-100000,100000));map.release();
        assertTrue(Math.abs((double)field(map,"cx"))<=1400);
        assertTrue(Math.abs((double)field(map,"cz"))<=1400);
        assertFalse(map.wheel(new Point(-1,-1),1));
    }
    @Test void readsOnlyValidInBoundsTilesAndReusesUnchangedImages()throws Exception{
        Path world=Files.createDirectory(root.resolve("world"));
        Files.writeString(world.resolve("metadata.json"),"{}");
        var image=new BufferedImage(16,16,2);
        ImageIO.write(image,"png",world.resolve("0_0.png").toFile());
        ImageIO.write(image,"png",world.resolve("1250_0.png").toFile());
        Files.writeString(world.resolve("1_1.png"),"broken");
        var map=new LocalSurveyMap(root);var read=LocalSurveyMap.class.getDeclaredMethod("read");read.setAccessible(true);read.invoke(map);
        var first=(java.util.List<?>)field(map,"tiles");assertEquals(1,first.size());
        read.invoke(map);assertSame(first.getFirst(),((java.util.List<?>)field(map,"tiles")).getFirst());
        assertEquals("broken",Files.readString(world.resolve("1_1.png")));
    }
}
