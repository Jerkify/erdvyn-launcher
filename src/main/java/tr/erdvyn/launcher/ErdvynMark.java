package tr.erdvyn.launcher;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * The Erdvyn mark and wordmark, the same geometry as brand/erdvyn-logo.mjs (the SVG master): a planet rising over a
 * teal horizon, cut into scan bands that thin out towards the line, its reflection under it and the orange orbital
 * station; the wordmark is ERDVYN in a heavy extended italic cut by the same kind of bands (the lowest two teal).
 * Hand-built geometry, not a bitmap.
 * <p>
 * The mark is 1 : 0.64 and sits centred in a square, so every square slot (icons, the sidebar) keeps working.
 * Below 40 px and in {@code flat} mode the planet has three heavy bands and the reflection two lines, so nothing
 * aliases away when an engine shrinks it. Public because tools/IconGenerator renders the icon files from it.
 * Copies: erdvyn_earlywindow and erdvyn_lib keep the same drawing; change all three together.
 */
public final class ErdvynMark {
    private ErdvynMark() {}

    public static final Color PAPER = new Color(241, 234, 216), TEAL = new Color(95, 242, 220), ORANGE = new Color(255, 126, 46);
    private static final Color FRINGE_RED = new Color(255, 74, 82, 150), FRINGE_BLUE = new Color(92, 146, 255, 150);
    private static final Map<String, BufferedImage> CACHE = new HashMap<>();

    // ---------------------------------------------------------------- the mark

    /** A still mark on a transparent square, without glow, for icon files. */
    public static BufferedImage render(int size) { return render(size, false); }

    /** {@code flat}: three heavy bands and two reflection lines, for textures an engine shrinks without filtering. */
    public static BufferedImage render(int size, boolean flat) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        draw(g, size, flat || size < 40);
        g.dispose();
        return image;
    }

    /** Paints the in-app mark into a {@code size} square at x,y: its phosphor glow, then the mark. Cached per size. */
    public static void paint(Graphics2D g, int x, int y, int size, double time) {
        if (CACHE.size() > 48) CACHE.clear();
        BufferedImage glow = CACHE.computeIfAbsent("glow:" + size, key -> glow(size));
        BufferedImage mark = CACHE.computeIfAbsent("mark:" + size, key -> render(size));
        if (glow != null) g.drawImage(glow, x - size / 8, y - size / 8, null);
        g.drawImage(mark, x, y, null);
    }

    private static final double[][] BANDS = {{0, .5}, {.555, .67}, {.72, .8}, {.845, .905}, {.945, 1}};
    private static final double[][] BANDS_FLAT = {{0, .52}, {.64, .8}, {.9, 1}};
    private static final double[][] REFLECTION = {{.07, .62, .016}, {.105, .42, .012}, {.135, .22, .009}}; // dy, width, thickness in S
    private static final double[][] REFLECTION_FLAT = {{.075, .6, .03}, {.13, .32, .024}};

    /** Mark geometry in a square of {@code size}: box width S = size, its 0.64 S height centred. */
    private record Geo(double s, double cx, double base, double r) {
        static Geo of(int size) { double s = size, y0 = (size - .64 * s) / 2; return new Geo(s, s / 2, y0 + .48 * s, .42 * s); }
    }

    private static Area planet(Geo m, boolean flat) {
        Area disc = new Area(new Arc2D.Double(m.cx() - m.r(), m.base() - m.r(), 2 * m.r(), 2 * m.r(), 0, 180, Arc2D.CHORD)), bands = new Area();
        for (double[] b : flat ? BANDS_FLAT : BANDS)
            bands.add(new Area(new Rectangle2D.Double(0, m.base() - m.r() + b[0] * m.r(), m.s(), (b[1] - b[0]) * m.r())));
        disc.intersect(bands);
        return disc;
    }

    private static Area teal(Geo m, boolean flat) {
        double s = m.s(), ra = m.r() * 1.07, line = Math.max(1, (flat ? .022 : .012) * s);
        Area out = new Area(new BasicStroke((float) line).createStrokedShape(new Arc2D.Double(m.cx() - ra, m.base() - ra, 2 * ra, 2 * ra, 0, 180, Arc2D.OPEN)));
        out.add(new Area(new Rectangle2D.Double(0, m.base() + .018 * s, s, Math.max(1, (flat ? .034 : .022) * s))));
        for (double[] l : flat ? REFLECTION_FLAT : REFLECTION)
            out.add(new Area(new Rectangle2D.Double(m.cx() - l[1] * s / 2, m.base() + l[0] * s, l[1] * s, Math.max(1, l[2] * s))));
        return out;
    }

    private static Shape station(Geo m, boolean flat) {
        double a = Math.toRadians(-40), so = m.r() * 1.2, sx = m.cx() + so * Math.cos(a), sy = m.base() + so * Math.sin(a) - m.r() * .12;
        double r = Math.max(1, (flat ? .04 : .026) * m.s());
        return new Ellipse2D.Double(sx - r, sy - r, 2 * r, 2 * r);
    }

    private static void draw(Graphics2D g, int size, boolean flat) {
        hints(g);
        Geo m = Geo.of(size);
        Area teal = teal(m, flat);
        // the planet's limb arc is softer than the horizon
        g.setColor(new Color(TEAL.getRed(), TEAL.getGreen(), TEAL.getBlue(), flat ? 200 : 140));
        Area arc = new Area(new Rectangle2D.Double(0, 0, size, m.base()));
        arc.intersect(teal);
        g.fill(arc);
        g.setColor(TEAL);
        Area rest = new Area(teal);
        rest.subtract(arc);
        g.fill(rest);
        g.setColor(PAPER);
        g.fill(planet(m, flat));
        g.setColor(ORANGE);
        g.fill(station(m, flat));
    }

    /** Soft phosphor bloom: warm from the planet, teal from the horizon, padded by size/8. Null below 48 px. */
    private static BufferedImage glow(int markSize) {
        if (markSize < 48) return null;
        int pad = markSize / 8, size = markSize + pad * 2;
        Geo m = Geo.of(markSize);
        AffineTransform shift = AffineTransform.getTranslateInstance(pad, pad);
        int[] warm = mask(size, shift.createTransformedShape(planet(m, false))), cold = mask(size, shift.createTransformedShape(teal(m, false)));
        int radius = Math.max(2, markSize / 16);
        blur(warm, size, size, radius);
        blur(cold, size, size, radius);
        return tint(warm, cold, size, size, .45, .6);
    }

    // ---------------------------------------------------------------- the wordmark

    // Cap height 100, stems 28, bars 24: [width, path]. Same strings as brand/erdvyn-logo.mjs.
    private static final String[][] GLYPHS = {
        {"100", "M0,0H100V24H28V38H88V62H28V76H100V100H0Z"},
        {"104", "M0,0H76A28,28 0 0 1 104,28V34A26,26 0 0 1 86,58L104,100H74L58,62H28V100H0Z M28,24V40H72A6,6 0 0 0 78,34V30A6,6 0 0 0 72,24Z"},
        {"108", "M0,0H80A28,28 0 0 1 108,28V72A28,28 0 0 1 80,100H0Z M28,24V76H72A8,8 0 0 0 80,68V32A8,8 0 0 0 72,24Z"},
        {"116", "M0,0H30L58,66.7L86,0H116L74,100H42Z"},
        {"112", "M0,0H31L56,40L81,0H112L70,62V100H42V62Z"},
        {"108", "M0,0H30L80,62V0H108V100H78L28,38V100H0Z"},
    };
    private static final double TRACK = 2, SKEW = Math.tan(Math.toRadians(12));
    // Scan bands through the name, in cap units; the lowest two are teal.
    private static final double[][] NAME_BANDS = {{0, 56}, {60, 70}, {74, 82}, {86, 92.5}, {96.5, 100}};
    private static final double[][] NAME_BANDS_FLAT = {{0, 58}, {66, 80}, {88, 100}};

    /** Width of ERDVYN at cap height 100, including the italic overhang. */
    private static double wordUnits() {
        double w = -TRACK;
        for (String[] gl : GLYPHS) w += Double.parseDouble(gl[0]) + TRACK;
        return w + 100 * SKEW;
    }

    /** ERDVYN's outline, cap height {@code h}, top-left of the cap box at 0,0 (before the italic lean, which leans right). */
    private static Area letters(double h) {
        Path2D all = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        double x = 0;
        for (String[] gl : GLYPHS) {
            Path2D p = SvgPath.parse(gl[1]);
            p.transform(AffineTransform.getTranslateInstance(x, 0));
            all.append(p, false);
            x += Double.parseDouble(gl[0]) + TRACK;
        }
        // leans right around the baseline: x' = x + tan(12) * (100 - y)
        AffineTransform t = new AffineTransform(1, 0, -SKEW, 1, 100 * SKEW, 0);
        t.preConcatenate(AffineTransform.getScaleInstance(h / 100, h / 100));
        return new Area(t.createTransformedShape(all));
    }

    /**
     * The wordmark on a transparent image: ERDVYN at cap height {@code capH}, cut into bands, with a red/blue chroma
     * fringe (skipped when {@code flat}). The image is padded by capH/10 on every side.
     */
    public static BufferedImage wordmark(int capH, boolean flat) {
        return CACHE.computeIfAbsent("word:" + capH + ":" + flat, key -> drawWordmark(capH, flat));
    }

    /** Paints the wordmark with its top-left at x,y (the image's padding included, see {@link #wordmarkSize}). */
    public static void paintWordmark(Graphics2D g, int x, int y, int capH) {
        if (CACHE.size() > 48) CACHE.clear();
        g.drawImage(wordmark(capH, false), x, y, null);
    }

    /** Width of ERDVYN itself (no padding) at cap height {@code capH}, italic overhang included. */
    public static double wordmarkWidth(double capH) { return wordUnits() * capH / 100; }

    /** Wordmark image size for {@link #wordmark}, so callers can centre it before drawing. */
    public static Dimension wordmarkSize(int capH) {
        int pad = Math.max(2, capH / 10);
        return new Dimension((int) Math.ceil(wordUnits() * capH / 100) + 2 * pad, capH + 2 * pad);
    }

    private static BufferedImage drawWordmark(int capH, boolean flat) {
        Dimension d = wordmarkSize(capH);
        int pad = Math.max(2, capH / 10);
        BufferedImage image = new BufferedImage(d.width, d.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        hints(g);
        g.translate(pad, pad);
        Area word = letters(capH);
        double[][] bands = flat ? NAME_BANDS_FLAT : NAME_BANDS;
        if (!flat) {
            double f = Math.max(1, capH / 40.0);
            Area cut = banded(word, bands, capH, 0, bands.length);
            g.setColor(FRINGE_RED); g.fill(cut.createTransformedArea(AffineTransform.getTranslateInstance(-f, 0)));
            g.setColor(FRINGE_BLUE); g.fill(cut.createTransformedArea(AffineTransform.getTranslateInstance(f, 0)));
        }
        int tealFrom = bands.length - (flat ? 1 : 2);
        g.setColor(PAPER); g.fill(banded(word, bands, capH, 0, tealFrom));
        g.setColor(TEAL); g.fill(banded(word, bands, capH, tealFrom, bands.length));
        g.dispose();
        return image;
    }

    private static Area banded(Area word, double[][] bands, double capH, int from, int to) {
        Area clip = new Area();
        for (int i = from; i < to; i++)
            clip.add(new Area(new Rectangle2D.Double(-capH, bands[i][0] * capH / 100, wordUnits() * capH / 100 + 2 * capH, (bands[i][1] - bands[i][0]) * capH / 100)));
        Area out = new Area(word);
        out.intersect(clip);
        return out;
    }

    // ---------------------------------------------------------------- shared

    private static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    private static BufferedImage tint(int[] warm, int[] cold, int w, int h, double warmA, double coldA) {
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            double a = warm[i] * warmA / 255, b = cold[i] * coldA / 255, alpha = Math.min(1, a + b);
            if (alpha <= 0) continue;
            double share = b / Math.max(1e-6, a + b);
            int r = (int) (PAPER.getRed() * (1 - share) + TEAL.getRed() * share), gr = (int) (PAPER.getGreen() * (1 - share) + TEAL.getGreen() * share), bl = (int) (PAPER.getBlue() * (1 - share) + TEAL.getBlue() * share);
            out[i] = (int) (alpha * 255) << 24 | r << 16 | gr << 8 | bl;
        }
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, w, h, out, 0, w);
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
    private static void blur(int[] a, int w, int h, int r) {
        int[] b = new int[a.length];
        for (int pass = 0; pass < 3; pass++) { boxBlur(a, b, w, h, r); boxBlur(b, a, h, w, r); }
    }
    private static void boxBlur(int[] src, int[] dst, int w, int h, int r) {
        int span = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w, sum = 0;
            for (int i = -r; i <= r; i++) sum += src[row + Math.max(0, Math.min(w - 1, i))];
            for (int x = 0; x < w; x++) { dst[x * h + y] = sum / span; sum += src[row + Math.min(w - 1, x + r + 1)] - src[row + Math.max(0, x - r)]; }
        }
    }

    /** The subset of SVG path syntax the glyphs use: absolute M, H, V, L, A, Z. */
    private static final class SvgPath {
        static Path2D parse(String d) {
            Path2D p = new Path2D.Double(Path2D.WIND_EVEN_ODD);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("[MHVLAZ]|-?[0-9.]+").matcher(d);
            java.util.List<String> tok = new java.util.ArrayList<>();
            while (m.find()) tok.add(m.group());
            double x = 0, y = 0, sx = 0, sy = 0;
            char cmd = 'M';
            for (int i = 0; i < tok.size();) {
                String t = tok.get(i);
                if (Character.isLetter(t.charAt(0))) { cmd = t.charAt(0); i++; if (cmd == 'Z') { p.closePath(); x = sx; y = sy; } continue; }
                switch (cmd) {
                    case 'M' -> { x = num(tok, i++); y = num(tok, i++); p.moveTo(x, y); sx = x; sy = y; cmd = 'L'; }
                    case 'L' -> { x = num(tok, i++); y = num(tok, i++); p.lineTo(x, y); }
                    case 'H' -> { x = num(tok, i++); p.lineTo(x, y); }
                    case 'V' -> { y = num(tok, i++); p.lineTo(x, y); }
                    case 'A' -> {
                        double rx = num(tok, i++), ry = num(tok, i++); i++; // x-axis rotation is always 0 here
                        boolean large = num(tok, i++) != 0, sweep = num(tok, i++) != 0;
                        double x2 = num(tok, i++), y2 = num(tok, i++);
                        arc(p, x, y, rx, ry, large, sweep, x2, y2);
                        x = x2; y = y2;
                    }
                    default -> i++;
                }
            }
            return p;
        }

        private static double num(java.util.List<String> tok, int i) { return Double.parseDouble(tok.get(i)); }

        /** SVG endpoint arc to a centre arc (SVG 1.1 F.6.5), rotation 0. */
        private static void arc(Path2D p, double x1, double y1, double rx, double ry, boolean large, boolean sweep, double x2, double y2) {
            double dx = (x1 - x2) / 2, dy = (y1 - y2) / 2;
            double lambda = dx * dx / (rx * rx) + dy * dy / (ry * ry);
            if (lambda > 1) { rx *= Math.sqrt(lambda); ry *= Math.sqrt(lambda); }
            double num = rx * rx * ry * ry - rx * rx * dy * dy - ry * ry * dx * dx, den = rx * rx * dy * dy + ry * ry * dx * dx;
            double co = Math.sqrt(Math.max(0, num / den)) * (large == sweep ? -1 : 1);
            double cxp = co * rx * dy / ry, cyp = -co * ry * dx / rx;
            double cx = cxp + (x1 + x2) / 2, cy = cyp + (y1 + y2) / 2;
            double t1 = Math.atan2((dy - cyp) / ry, (dx - cxp) / rx), t2 = Math.atan2((-dy - cyp) / ry, (-dx - cxp) / rx);
            double dt = t2 - t1;
            if (sweep && dt < 0) dt += 2 * Math.PI;
            if (!sweep && dt > 0) dt -= 2 * Math.PI;
            // y points down: an SVG angle is the negative of an Arc2D angle
            p.append(new Arc2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry, -Math.toDegrees(t1), -Math.toDegrees(dt), Arc2D.OPEN), true);
        }
    }
}
