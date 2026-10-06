package tr.erdvyn.launcher;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.io.InputStream;
import java.util.Arrays;

import static tr.erdvyn.launcher.PlanetSurvey.*;

/**
 * Erdvyn's planet as a phosphor globe, drawn in software. Every pixel of the disc remembers where on the planet it
 * looks (a lookup rebuilt only when the size, tilt or zoom change), so turning the globe costs one addition per pixel.
 * The ground is the planet atlas ({@code tools/PlanetAtlas}: sea, shallows, land and ice with their relief) under the
 * player's survey ({@link PlanetSurvey}). The projection is the field terminal's globe in erdvyn_ui
 * ({@code SurveyMapPanel}): orthographic, longitude along x, latitude along z, north at the top.
 * <p>{@code HOLO} is the HOME monitor's two-phosphor hologram (seen ground in the accent colour); {@code SURVEY} is
 * the MAP page's instrument (seen ground in its own map colours, full survey detail when zoomed in).
 */
final class PlanetGlobe {
    static final int ATLAS = 2048, LEVELS = 16;
    enum Style { HOLO, SURVEY }

    private static volatile byte[] atlas;
    private static final int[] U_ATLAS = new int[PX], U_TILE = new int[PX], U_IN = new int[PX];
    static {
        for (int su = 0; su < PX; su++) { U_ATLAS[su] = (int) ((long) su * ATLAS / PX); U_TILE[su] = su / SIDE; U_IN[su] = su % SIDE; }
    }

    /** Starts decoding the atlas in the background; the globe stays dark until it is there. */
    static void preload() { if (atlas == null) Thread.startVirtualThread(PlanetGlobe::atlasNow); }
    static synchronized byte[] atlasNow() {
        if (atlas != null) return atlas;
        byte[] data = new byte[ATLAS * ATLAS];
        try (InputStream in = PlanetGlobe.class.getResourceAsStream("/assets/planet-atlas.png")) {
            BufferedImage image = in == null ? null : ImageIO.read(in);
            if (image != null && image.getWidth() == ATLAS && image.getHeight() == ATLAS && image.getType() == BufferedImage.TYPE_BYTE_GRAY)
                data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
            else Arrays.fill(data, (byte) 4); // a plain dark sea rather than nothing
        } catch (Exception e) { LauncherLog.write("Planet atlas: " + e); }
        atlas = data;
        return data;
    }

    final Style style;
    /** World x at the centre of the view (blocks), the latitude facing the viewer (radians, + is south) and the zoom. */
    double yaw = -3960, pitch, zoom = 1;
    private final int[][] ground = new int[LEVELS][256], seen = new int[LEVELS][256];
    // this frame's sphere, in window pixels
    private double fcx, fcy, fr, cp = 1, sp;
    // the lookup: disc pixels, their survey column at yaw 0, atlas row offset, tile row + row in tile, and shade
    private int lw, lh, count;
    private double lcx = Double.NaN, lcy, lr, lpitch;
    private int[] index = new int[0], column = new int[0], atlasRow = new int[0], tileRow = new int[0];
    private byte[] level = new byte[0];
    private BufferedImage image;
    private int[] pixels;
    // the view's motion: drag inertia and flights
    private double vyaw, vpitch, flyFrom, flyTo, flyPitch0, flyPitch1, flyZoom0, flyZoom1, flyT = 1;
    private long lastDrag;

    PlanetGlobe(Style style) {
        this.style = style;
        for (int lv = 0; lv < LEVELS; lv++) {
            double k = lv / (LEVELS - 1.0);
            for (int a = 0; a < 256; a++) {
                ground[lv][a] = scale(style == Style.HOLO ? holo(a, Retro.CYAN, 0) : land(a), k);
                // the hologram's surveyed ground glows by itself, so it reads on the night side too
                seen[lv][a] = style == Style.HOLO ? scale(holo(a, Retro.mix(Retro.ORANGE, Retro.YELLOW, .2), .28), .55 + .45 * k) : scale(mapColor(a), k);
            }
        }
    }

    double radius() { return fr; }
    double centreX() { return fcx; }
    double centreY() { return fcy; }

    /**
     * Paints the globe into the viewport (window pixels), centred at (cx, cy) inside it with radius r. Pixels off the
     * disc stay transparent.
     */
    void paint(Graphics2D g, int vx, int vy, int vw, int vh, double cx, double cy, double r, Snapshot survey) {
        fcx = vx + cx; fcy = vy + cy; fr = r; cp = Math.cos(pitch); sp = Math.sin(pitch);
        byte[] planet = atlas;
        if (planet == null || vw <= 0 || vh <= 0) return;
        if (vw != lw || vh != lh || cx != lcx || cy != lcy || r != lr || pitch != lpitch) rebuild(vw, vh, cx, cy, r);
        int turn = Math.floorMod((int) Math.round((yaw - MIN) / PIXEL), PX);
        boolean fine = style == Style.SURVEY && r * 2 * Math.PI / ATLAS > 1.2;
        byte[] coarse = survey.coarse();
        byte[][] tiles = survey.tiles();
        int[][] low = this.ground, high = this.seen;
        boolean holo = style == Style.HOLO;
        for (int i = 0; i < count; i++) {
            int su = column[i] + turn;
            if (su >= PX) su -= PX;
            int at = atlasRow[i] + U_ATLAS[su], a = planet[at] & 255, p;
            if (fine) {
                byte[] tile = tiles[(tileRow[i] >>> 16) + U_TILE[su]];
                p = tile == null ? 0 : tile[(tileRow[i] & 0xFFFF) + U_IN[su]] & 255;
            } else p = coarse[at] & 255;
            int lv = level[i];
            pixels[index[i]] = p == 0 ? low[lv][a] : high[lv][holo ? a : p];
        }
        g.drawImage(image, vx, vy, null);
    }

    private void rebuild(int w, int h, double cx, double cy, double r) {
        if (image == null || image.getWidth() != w || image.getHeight() != h) {
            image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        } else Arrays.fill(pixels, 0);
        int capacity = (int) Math.min((long) w * h, (long) Math.ceil(Math.PI * (r + 2) * (r + 2)));
        if (index.length < capacity) { index = new int[capacity]; column = new int[capacity]; atlasRow = new int[capacity]; tileRow = new int[capacity]; level = new byte[capacity]; }
        double lx = -.5, ly = -.56, lz = .66; // HOLO's light, up and to the left of the viewer
        count = 0;
        for (int y = Math.max(0, (int) Math.floor(cy - r)); y <= Math.min(h - 1, (int) Math.ceil(cy + r)); y++) {
            double yy = (y + .5 - cy) / r;
            if (yy <= -1 || yy >= 1) continue;
            double half = Math.sqrt(1 - yy * yy) * r;
            for (int x = Math.max(0, (int) Math.floor(cx - half)); x <= Math.min(w - 1, (int) Math.ceil(cx + half)); x++) {
                double xx = (x + .5 - cx) / r, q = 1 - xx * xx - yy * yy;
                if (q <= 0) continue;
                double d = Math.sqrt(q), py = yy * cp + d * sp, pz = -yy * sp + d * cp;
                double lon = atan2(xx, pz), lat = atan2(py, Math.sqrt(Math.max(0, 1 - py * py)));
                int su = Math.floorMod((int) Math.floor(lon / (2 * Math.PI) * PX), PX), sv = Math.max(0, Math.min(PX - 1, (int) Math.floor((lat / Math.PI + .5) * PX)));
                double shade;
                if (style == Style.HOLO) {
                    double light = Math.max(0, xx * lx + yy * ly + d * lz);
                    shade = (.2 + .8 * (.6 * light + .4 * d)) * ((y & 1) == 1 ? .74 : 1); // raster lines
                } else shade = .42 + .58 * Math.pow(d, .45);
                index[count] = y * w + x;
                column[count] = su;
                atlasRow[count] = (int) ((long) sv * ATLAS / PX) * ATLAS;
                tileRow[count] = (sv / SIDE) * TILES << 16 | (sv % SIDE) * SIDE;
                level[count] = (byte) Math.max(1, Math.min(LEVELS - 1, Math.round(shade * (LEVELS - 1))));
                count++;
            }
        }
        lw = w; lh = h; lcx = cx; lcy = cy; lr = r; lpitch = pitch;
    }

    // ------------------------------------------------------------------ projection

    /** Window position of world (x, z) into {@code out} [x, y, depth]; true when it faces the viewer. */
    boolean project(double x, double z, double[] out) {
        double lon = (x - yaw) / SPAN * 2 * Math.PI, lat = ((Math.max(MIN, Math.min(MIN + SPAN, z)) - MIN) / SPAN - .5) * Math.PI;
        lon -= 2 * Math.PI * Math.floor((lon + Math.PI) / (2 * Math.PI));
        double cl = Math.cos(lat), sl = Math.sin(lat), px = cl * Math.sin(lon), pz = cl * Math.cos(lon);
        out[0] = fcx + fr * px; out[1] = fcy + fr * (sl * cp - pz * sp); out[2] = sl * sp + pz * cp;
        return out[2] > 0;
    }
    /** World (x, z) under a window point, or null off the disc. */
    double[] pick(double mx, double my) {
        if (fr <= 0) return null;
        double x = (mx - fcx) / fr, y = (my - fcy) / fr, q = 1 - x * x - y * y;
        if (q <= 0) return null;
        double d = Math.sqrt(q), py = y * cp + d * sp, pz = -y * sp + d * cp;
        double lon = Math.atan2(x, pz), lat = Math.asin(Math.max(-1, Math.min(1, py)));
        return new double[]{wrapX(yaw + lon / (2 * Math.PI) * SPAN), MIN + (lat / Math.PI + .5) * SPAN};
    }
    static double wrapX(double x) { return MIN + Math.floorMod((long) Math.floor((x - MIN) * 16), (long) SPAN * 16) / 16.0; }
    /** The sector (A1..H8, the bounty and registry maps' grid) of a world point. */
    static String sector(double x, double z) {
        int column = Math.max(0, Math.min(7, (int) ((wrapX(x) - MIN) / (SPAN / 8)))), row = Math.max(0, Math.min(7, (int) ((z - MIN) / (SPAN / 8))));
        return (char) ('A' + row) + Integer.toString(column + 1);
    }

    /** The sector grid (8 x 8, as the field terminal draws it): meridians and parallels on the visible side. */
    void paintGrid(Graphics2D g, Color color) {
        g.setColor(color);
        double[] p = new double[3], q = new double[3];
        for (int k = 0; k < 8; k++) {
            double x = MIN + k * SPAN / 8.0;
            for (int m = 0; m < 48; m++) { project(x, MIN + SPAN * m / 48.0, p); project(x, MIN + SPAN * (m + 1) / 48.0, q); segment(g, p, q); }
        }
        for (int k = 1; k < 8; k++) {
            double z = MIN + k * SPAN / 8.0;
            for (int m = 0; m < 96; m++) { project(MIN + SPAN * m / 96.0, z, p); project(MIN + SPAN * (m + 1) / 96.0, z, q); segment(g, p, q); }
        }
    }
    private void segment(Graphics2D g, double[] p, double[] q) {
        if (p[2] > .03 && q[2] > .03) g.drawLine((int) Math.round(p[0]), (int) Math.round(p[1]), (int) Math.round(q[0]), (int) Math.round(q[1]));
    }
    /** The limb and a soft atmosphere round it. */
    void paintRim(Graphics2D g, Color color) {
        if (fr <= 2) return;
        Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Stroke old = g.getStroke();
        for (int i = 0; i < 4; i++) {
            g.setStroke(new BasicStroke(2f + i * 3)); g.setColor(Retro.alpha(color, 34 - i * 8));
            g.draw(new Ellipse2D.Double(fcx - fr - 1 - i, fcy - fr - 1 - i, 2 * (fr + 1 + i), 2 * (fr + 1 + i)));
        }
        g.setStroke(new BasicStroke(1.2f)); g.setColor(Retro.alpha(color, 170));
        g.draw(new Ellipse2D.Double(fcx - fr, fcy - fr, 2 * fr, 2 * fr));
        g.setStroke(old);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
    }

    // ------------------------------------------------------------------ motion

    /** Grab and turn: the ground under the pointer follows it. */
    void drag(double dx, double dy) {
        if (fr <= 0) return;
        flyT = 1;
        double dyaw = -dx / fr / (2 * Math.PI) * SPAN, dpitch = -dy / fr;
        yaw = wrapX(yaw + dyaw);
        pitch = Math.max(-1.45, Math.min(1.45, pitch + dpitch));
        long now = System.nanoTime();
        double seconds = lastDrag == 0 ? 1 / 60.0 : Math.max(1 / 240.0, (now - lastDrag) / 1e9);
        vyaw = vyaw * .4 + dyaw / seconds * .6; vpitch = vpitch * .4 + dpitch / seconds * .6;
        lastDrag = now;
    }
    /** A flick keeps it turning for a moment; a drag that stopped before the release does not. */
    void release() {
        if (System.nanoTime() - lastDrag > 90_000_000L) { vyaw = 0; vpitch = 0; }
        lastDrag = 0;
    }
    void stop() { vyaw = vpitch = 0; flyT = 1; }
    /** A glide that covers about {@code dyaw} blocks and {@code dpitch} radians as it slows (the arrow keys). */
    void nudge(double dyaw, double dpitch) { flyT = 1; vyaw += dyaw * 3.2; vpitch += dpitch * 3.2; }
    /** Zooms by {@code factor}, turning toward the pointer so the ground under it stays close to it. */
    void zoomAt(double mx, double my, double factor, double min, double max) {
        double next = Math.max(min, Math.min(max, zoom * factor)), f = next / zoom;
        double[] at = pick(mx, my);
        if (at != null && f > 1) {
            double share = 1 - 1 / f, lat = ((at[1] - MIN) / SPAN - .5) * Math.PI;
            yaw = wrapX(yaw + nearest(at[0] - yaw) * share);
            pitch = Math.max(-1.45, Math.min(1.45, pitch + (lat - pitch) * share));
        }
        zoom = next; flyT = 1; vyaw = vpitch = 0;
    }
    /** An eased flight to face world (x, z) at the given zoom. */
    void flyTo(double x, double z, double toZoom) {
        flyFrom = yaw; flyTo = yaw + nearest(x - yaw);
        flyPitch0 = pitch; flyPitch1 = Math.max(-1.3, Math.min(1.3, ((Math.max(MIN, Math.min(MIN + SPAN, z)) - MIN) / SPAN - .5) * Math.PI));
        flyZoom0 = zoom; flyZoom1 = toZoom; flyT = 0; vyaw = vpitch = 0;
    }
    private static double nearest(double dx) { return dx - SPAN * Math.round(dx / SPAN); }
    /** Advances inertia and flights; true while the view still moves. */
    boolean tick(double dt) {
        if (flyT < 1) {
            flyT = Math.min(1, flyT + dt / .85);
            double e = Retro.easeInOut(flyT);
            yaw = wrapX(flyFrom + (flyTo - flyFrom) * e);
            pitch = flyPitch0 + (flyPitch1 - flyPitch0) * e;
            // out and back in, so a long flight shows where it goes
            double hop = Math.sin(flyT * Math.PI) * Math.min(.5, Math.abs(flyTo - flyFrom) / SPAN * 2);
            zoom = Math.exp(Math.log(flyZoom0) + (Math.log(flyZoom1) - Math.log(flyZoom0)) * e) * (1 - hop);
            return true;
        }
        if (lastDrag != 0 || Math.abs(vyaw) < 5 && Math.abs(vpitch) < .002) { if (lastDrag == 0) vyaw = vpitch = 0; return false; }
        yaw = wrapX(yaw + vyaw * dt);
        pitch = Math.max(-1.45, Math.min(1.45, pitch + vpitch * dt));
        double decay = Math.exp(-3.2 * dt);
        vyaw *= decay; vpitch *= decay;
        return true;
    }

    // ------------------------------------------------------------------ colour

    /** Fast atan2 (|error| < 1e-5 rad), enough for a pixel at any zoom; the lookup calls it twice per pixel. */
    static double atan2(double y, double x) {
        double ax = Math.abs(x), ay = Math.abs(y);
        if (ax == 0 && ay == 0) return 0;
        double a = Math.min(ax, ay) / Math.max(ax, ay), s = a * a;
        double r = (((((-0.01172120 * s + 0.05265332) * s - 0.11643287) * s + 0.19354346) * s - 0.33262347) * s + 0.99997726) * a;
        if (ay > ax) r = Math.PI / 2 - r;
        if (x < 0) r = Math.PI - r;
        return y < 0 ? -r : r;
    }
    private static int scale(Color c, double k) {
        return 0xFF000000 | (int) Math.round(c.getRed() * k) << 16 | (int) Math.round(c.getGreen() * k) << 8 | (int) Math.round(c.getBlue() * k);
    }
    /** The hologram: one phosphor, its relief in brightness, ice paler; {@code lift} brightens surveyed ground so it reads first. */
    private static Color holo(int a, Color phosphor, double lift) {
        double i = (a & 63) / 63.0;
        return switch (a >> 6) {
            case 0 -> Retro.mix(Retro.INK, phosphor, .07 + .05 * i + lift * .6);
            case 1 -> Retro.mix(Retro.INK, phosphor, .26 + .3 * i + lift);
            case 2 -> Retro.mix(Retro.INK, phosphor, .36 + lift + (.64 - lift) * i);
            default -> Retro.mix(Retro.INK, Retro.mix(phosphor, Retro.PAPER, .5), .5 + lift * .5 + .5 * i);
        };
    }
    /** Unseen ground on the MAP page: dark sea and land in the page's violet, as the field terminal shows it. */
    private static Color land(int a) {
        double i = (a & 63) / 63.0;
        return switch (a >> 6) {
            case 0 -> Retro.mix(Retro.INK, Retro.VIOLET, .05 + .03 * i);
            case 1 -> Retro.mix(Retro.INK, Retro.VIOLET, .11 + .08 * i);
            case 2 -> Retro.mix(Retro.INK, Retro.VIOLET, .17 + .3 * i);
            default -> Retro.mix(Retro.INK, Retro.PAPER, .2 + .22 * i);
        };
    }
    /** Vanilla map colours (MapColor 0..61, 1.21.1) and their four shades, a little less saturated. */
    private static final int[] MAP = {0x000000, 0x7FB238, 0xF7E9A3, 0xC7C7C7, 0xFF0000, 0xA0A0FF, 0xA7A7A7, 0x007C00, 0xFFFFFF, 0xA4A8B8,
            0x976D4D, 0x707070, 0x4040FF, 0x8F7748, 0xFFFCF5, 0xD87F33, 0xB24CD8, 0x6699D8, 0xE5E533, 0x7FCC19, 0xF27FA5, 0x4C4C4C, 0x999999,
            0x4C7F99, 0x7F3FB2, 0x334CB2, 0x664C33, 0x667F33, 0x993333, 0x191919, 0xFAEE4D, 0x5CDBD5, 0x4A80FF, 0x00D93A, 0x815631, 0x700200,
            0xD1B1A1, 0x9F5224, 0x95576C, 0x706C8A, 0xBA8524, 0x677535, 0xA04D4E, 0x392923, 0x876B62, 0x575C5C, 0x7A4958, 0x4C3E5C, 0x4C3223,
            0x4C522A, 0x8E3C2E, 0x251610, 0xBD3031, 0x943F61, 0x5C191D, 0x167E86, 0x3A8E8C, 0x562C3E, 0x14B485, 0x646464, 0xD8AF93, 0x7FA796};
    private static Color mapColor(int packed) {
        int id = packed >> 2;
        if (id <= 0 || id >= MAP.length) return Retro.INK;
        int rgb = id == 12 ? 0x2F6680 : MAP[id]; // vanilla's water is a harsh violet-blue on the glass: steel blue, as in game
        double f = new double[]{.71, .86, 1, .53}[packed & 3];
        double r = (rgb >> 16 & 255) * f, g = (rgb >> 8 & 255) * f, b = (rgb & 255) * f, lum = .3 * r + .59 * g + .11 * b;
        return new Color(grade(r, lum), grade(g, lum), grade(b, lum));
    }
    private static int grade(double channel, double lum) { return (int) Math.min(255, Math.round((channel + (lum - channel) * .25) * .92)); }
}
