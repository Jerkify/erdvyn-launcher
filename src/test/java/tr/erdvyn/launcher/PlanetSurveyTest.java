package tr.erdvyn.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

final class PlanetSurveyTest {
    @TempDir Path instance;

    /** The file erdvyn_ui's SurveyStore writes: magic, tile size, count, then (key, 160 x 160 bytes) per tile. */
    @Test
    void readsTheGameSurveyAndXaeroWaypoints() throws Exception {
        Path file = instance.resolve("erdvyn_survey/server-play.erdvyn.net/minecraft_overworld.bin");
        Files.createDirectories(file.getParent());
        byte[] tile = new byte[PlanetSurvey.SIDE * PlanetSurvey.SIDE];
        for (int i = 0; i < 100; i++) tile[i] = (byte) (1 << 2 | 2); // a strip of grass along the tile's north edge
        try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(0x45535631); out.writeInt(320); out.writeInt(2);
            out.writeLong(62L << 32 | 62); out.write(tile);
            out.writeLong(-1L << 32 | 3); out.write(tile); // outside the planet frame: ignored
        }
        Path marks = instance.resolve("xaero/minimap/Multiplayer_play.erdvyn.net/dim%0/mw$default_1.txt");
        Files.createDirectories(marks.getParent());
        Files.writeString(marks, String.join("\n",
                "#waypoint:name:initials:x:y:z:color:disabled:type:set:rotate_on_tp:tp_yaw:visibility_type:destination",
                "waypoint:Camp§§North:C:100:64:-200:11:false:0:gui.xaero_default:false:0:0:false",
                "waypoint:Hidden:H:0:64:0:2:true:0:gui.xaero_default:false:0:0:false",
                "waypoint:gui.xaero_deathpoint:D:20500:~:900:0:false:1:gui.xaero_default:false:0:0:false",
                "waypoint:Broken:B:x:64:0:2:false:0:gui.xaero_default:false:0:0:false"), StandardCharsets.UTF_8);

        var s = PlanetSurvey.read(file, "server-play.erdvyn.net", marks.getParent(), 1);
        assertEquals(1, s.tileCount());
        assertEquals(100, s.seenPixels());
        assertTrue(s.fraction() > 0 && s.fraction() < 1e-4);
        int su = 62 * PlanetSurvey.SIDE, sv = 62 * PlanetSurvey.SIDE;
        assertEquals(1 << 2 | 2, s.at(su + 5, sv));
        assertEquals(0, s.at(su + 5, sv + 1));
        assertEquals(2, s.waypoints().size()); // the disabled and the malformed line are skipped
        assertEquals("Camp:North", s.waypoints().get(0).name());
        var death = s.waypoints().get(1);
        assertTrue(death.death());
        assertEquals(-19500, death.x()); // past the east-west seam: the same ground one period round
        assertFalse(s.empty());
    }

    @Test
    void refusesAnotherFormat() throws Exception {
        Path file = instance.resolve("other.bin");
        try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) { out.writeInt(42); out.writeInt(320); }
        assertThrows(java.io.IOException.class, () -> PlanetSurvey.read(file, "x", instance, 1));
    }

    /** Projection and picking are inverses, so a click lands on the ground drawn under it. */
    @Test
    void pickFindsTheProjectedPoint() {
        var globe = new PlanetGlobe(PlanetGlobe.Style.SURVEY);
        globe.yaw = 1200; globe.pitch = .4;
        var g = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB).createGraphics();
        globe.paint(g, 0, 0, 400, 400, 200, 200, 180, PlanetSurvey.EMPTY);
        g.dispose();
        double[] p = new double[3];
        for (double[] at : new double[][]{{1200, 4000}, {5000, 9000}, {-2500, 2000}}) {
            assertTrue(globe.project(at[0], at[1], p));
            double[] back = globe.pick(p[0], p[1]);
            assertNotNull(back);
            assertEquals(at[0], back[0], 2);
            assertEquals(at[1], back[1], 2);
        }
        assertNull(globe.pick(5, 5));
        assertEquals("A1", PlanetGlobe.sector(-20000, -20000));
        assertEquals("H8", PlanetGlobe.sector(19999, 19999));
    }

    @Test
    void fastAtan2IsCloseEverywhere() {
        for (double a = -Math.PI; a <= Math.PI; a += .001) {
            double y = Math.sin(a) * 3, x = Math.cos(a) * 3;
            assertEquals(Math.atan2(y, x), PlanetGlobe.atan2(y, x), 2e-5);
        }
    }
}
