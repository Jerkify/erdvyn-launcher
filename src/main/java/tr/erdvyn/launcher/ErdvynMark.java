package tr.erdvyn.launcher;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * The Erdvyn mark: an "E" built from horizontal scan lines standing in front of a striped frontier sun, with a
 * red/blue chromatic split on the letter, in the launcher's own language (broadcast globe stripes, misconverged
 * tube, phosphor glow). Hand-built geometry, not a bitmap: the in-app logo (its sun bands roll slowly), the
 * window/taskbar icon and the installer icon all come from this one drawing.
 * <p>
 * Below 32 px the letter is solid and both parts grow to fill the square; from 40 px the letter is scan lines with
 * the chroma split. Icon files are drawn without glow so they sit cleanly on any background; the in-app logo adds it.
 * Public because tools/IconGenerator renders the icon files from it.
 */
public final class ErdvynMark {
    private ErdvynMark() {}

    public static final Color SUN_TOP = new Color(255, 222, 72), SUN_MID = new Color(255, 126, 46), SUN_BOTTOM = new Color(255, 74, 82);
    public static final Color LETTER_TOP = new Color(240, 246, 240), LETTER_BOTTOM = new Color(72, 232, 236);
    private static final Color FRINGE_RED = new Color(255, 74, 82, 210), FRINGE_BLUE = new Color(92, 146, 255, 210);
    private static final int ROLL_FRAMES = 24; // one full band step per 24 cached frames
    private static final Map<String, BufferedImage> FRAMES = new HashMap<>();

    /** A still mark on a transparent square, without glow, for icon files. */
    public static BufferedImage render(int size) { return render(size, false); }

    /**
     * {@code flat}: a solid letter and fewer, heavier sun bands, for a texture that an engine shrinks without filtering
     * (Minecraft GUI blits): thin scan lines would alias away there.
     */
    public static BufferedImage render(int size, boolean flat) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        draw(g, size, 0, flat);
        g.dispose();
        return image;
    }

    /**
     * Paints the in-app logo into a {@code size} square at x,y: glow, then the mark with its sun bands rolled by
     * {@code time} (seconds). Frames are cached per size, so this costs one or two image draws.
     */
    public static void paint(Graphics2D g, int x, int y, int size, double time) {
        int frame = (int) Math.floorMod((long) Math.floor(time * 10), ROLL_FRAMES);
        if (FRAMES.size() > 6 * ROLL_FRAMES) FRAMES.clear();
        BufferedImage glow = FRAMES.computeIfAbsent(size + ":glow", key -> glow(size));
        BufferedImage mark = FRAMES.computeIfAbsent(size + ":" + frame, key -> {
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D m = image.createGraphics();
            draw(m, size, frame / (double) ROLL_FRAMES, false);
            m.dispose();
            return image;
        });
        if (glow != null) g.drawImage(glow, x - size / 8, y - size / 8, null);
        g.drawImage(mark, x, y, null);
    }

    private record Layout(double sunX, double sunY, double sunR, int bands, double ex, double ey, double ew, double eh, double spine, double gap, boolean lines) {}

    private static Layout layout(int size, boolean flat) {
        double u = size / 64.0;
        if (flat) return new Layout(39 * u, 30 * u, 22 * u, 6, 7 * u, 15 * u, 32 * u, 38 * u, 10 * u, Math.max(1, 2.8 * u), false);
        if (size < 32) return new Layout(42 * u, 29 * u, 21 * u, 5, 3 * u, 9 * u, 36 * u, 46 * u, 12 * u, Math.max(1, 2.6 * u), false);
        return new Layout(39 * u, 30 * u, 22 * u, size < 40 ? 7 : 9, 7 * u, 15 * u, 32 * u, 38 * u, 9 * u, Math.max(1, 2.4 * u), size >= 40);
    }

    private static void draw(Graphics2D g, int size, double roll, boolean flat) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        Layout l = layout(size, flat);
        Area letter = letter(l, false), lines = l.lines() ? letter(l, true) : letter;
        Area sun = sunBands(l, roll);
        sun.subtract(dilate(letter, l.gap()));
        g.setPaint(new LinearGradientPaint(0f, (float) (l.sunY() - l.sunR()), 0f, (float) (l.sunY() + l.sunR()), new float[]{0f, .5f, 1f}, new Color[]{SUN_TOP, SUN_MID, SUN_BOTTOM}));
        g.fill(sun);
        if (l.lines()) {
            double d = Math.max(1, 1.5 * size / 64.0);
            g.setColor(FRINGE_RED); g.fill(lines.createTransformedArea(AffineTransform.getTranslateInstance(-d, 0)));
            g.setColor(FRINGE_BLUE); g.fill(lines.createTransformedArea(AffineTransform.getTranslateInstance(d, 0)));
        }
        g.setPaint(new GradientPaint(0f, (float) l.ey(), LETTER_TOP, 0f, (float) (l.ey() + l.eh()), LETTER_BOTTOM));
        g.fill(lines);
    }

    /** The E: a spine and three arms; as scan lines every row runs the spine and the rows inside an arm run its full width. */
    private static Area letter(Layout l, boolean asLines) {
        double arm = l.eh() * .2, mid = l.eh() * .18;
        Area solid = new Area(new Rectangle2D.Double(l.ex(), l.ey(), l.spine(), l.eh()));
        solid.add(new Area(new Rectangle2D.Double(l.ex(), l.ey(), l.ew(), arm)));
        solid.add(new Area(new Rectangle2D.Double(l.ex(), l.ey() + (l.eh() - mid) / 2, l.ew() * .84, mid)));
        solid.add(new Area(new Rectangle2D.Double(l.ex(), l.ey() + l.eh() - arm, l.ew(), arm)));
        if (!asLines) return solid;
        int rows = 14;
        double pitch = l.eh() / rows;
        for (int i = 0; i < rows; i++) {
            double depth = i / (double) (rows - 1), gap = pitch * (.42 - depth * .22); // open at the top, denser toward the bottom
            solid.subtract(new Area(new Rectangle2D.Double(l.ex() - 1, l.ey() + (i + 1) * pitch - gap, l.ew() + 2, gap)));
        }
        return solid;
    }

    /** The sun as horizontal bands, thin at the top and heavy at the bottom; {@code roll} 0..1 moves them down one step. */
    private static Area sunBands(Layout l, double roll) {
        Area disc = new Area(new Ellipse2D.Double(l.sunX() - l.sunR(), l.sunY() - l.sunR(), l.sunR() * 2, l.sunR() * 2)), out = new Area();
        double step = 2 * l.sunR() / l.bands(), top = l.sunY() - l.sunR();
        for (int i = -1; i <= l.bands(); i++) {
            double y = top + (i + roll) * step, depth = Math.max(0, Math.min(1, (y - top + step / 2) / (2 * l.sunR())));
            double gap = step * (.5 - depth * .38);
            out.add(new Area(new Rectangle2D.Double(l.sunX() - l.sunR() - 1, y, l.sunR() * 2 + 2, Math.max(.5, step - gap))));
        }
        out.intersect(disc);
        return out;
    }

    /** The shape grown by {@code distance} with square, mitred corners (the letter is all right angles). */
    private static Area dilate(Area shape, double distance) {
        Area out = new Area(new BasicStroke((float) (distance * 2), BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER, 10f).createStrokedShape(shape));
        out.add(shape);
        return out;
    }

    /** Soft phosphor bloom for the in-app logo: a warm halo from the sun and a cyan one from the letter, padded by size/8 so it never ends in a hard square edge. */
    private static BufferedImage glow(int markSize) {
        if (markSize < 48) return null;
        int pad = markSize / 8, size = markSize + pad * 2;
        Layout l = layout(markSize, false);
        AffineTransform shift = AffineTransform.getTranslateInstance(pad, pad);
        int[] sun = mask(size, shift.createTransformedShape(new Ellipse2D.Double(l.sunX() - l.sunR(), l.sunY() - l.sunR(), l.sunR() * 2, l.sunR() * 2))), letter = mask(size, shift.createTransformedShape(letter(l, false)));
        int radius = Math.max(2, markSize / 14);
        blur(sun, size, radius);
        blur(letter, size, radius);
        int[] out = new int[size * size];
        for (int i = 0; i < out.length; i++) {
            double a = sun[i] * .5 / 255, b = letter[i] * .55 / 255, alpha = Math.min(1, a + b);
            if (alpha <= 0) continue;
            double share = b / Math.max(1e-6, a + b);
            int r = (int) (255 * (1 - share) + 72 * share), gr = (int) (100 * (1 - share) + 232 * share), bl = (int) (64 * (1 - share) + 236 * share);
            out[i] = (int) (alpha * 255) << 24 | r << 16 | gr << 8 | bl;
        }
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, size, size, out, 0, size);
        return image;
    }

    private static int[] mask(int size, Shape shape) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE); g.fill(shape); g.dispose();
        int[] pixels = image.getRGB(0, 0, size, size, null, 0, size);
        for (int i = 0; i < pixels.length; i++) pixels[i] >>>= 24;
        return pixels;
    }

    /** Three box-blur passes each way (a cheap gaussian), in place. */
    private static void blur(int[] a, int size, int r) {
        int[] b = new int[a.length];
        for (int pass = 0; pass < 3; pass++) { boxBlur(a, b, size, size, r); boxBlur(b, a, size, size, r); }
    }
    private static void boxBlur(int[] src, int[] dst, int w, int h, int r) {
        int span = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w, sum = 0;
            for (int i = -r; i <= r; i++) sum += src[row + Math.max(0, Math.min(w - 1, i))];
            for (int x = 0; x < w; x++) { dst[x * h + y] = sum / span; sum += src[row + Math.min(w - 1, x + r + 1)] - src[row + Math.max(0, x - r)]; }
        }
    }
}
