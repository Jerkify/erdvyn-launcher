package tr.erdvyn.launcher;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Retro CRT drawing kit for the launcher canvas: phosphor themes, cached glow text, seven-segment readouts,
 * hatched meters, ECG traces, striped orbs and the CRT overlay. Everything here runs on the EDT only.
 * Expensive pixels (glow sprites, scanlines, vignette, dot grid) are built once and reused as managed images.
 */
final class Retro {
    private Retro() {}

    static final Color INK = new Color(4, 6, 9);
    static final Color CYAN = new Color(72, 232, 236), ORANGE = new Color(255, 126, 46), GREEN = new Color(88, 255, 146);
    static final Color AMBER = new Color(255, 188, 56), RED = new Color(255, 74, 82), BLUE = new Color(92, 146, 255);
    static final Color YELLOW = new Color(255, 222, 72), VIOLET = new Color(198, 128, 255), MINT = new Color(110, 255, 204);
    static final Color PAPER = new Color(230, 238, 232);

    /** One phosphor set: main draws labels and frames, data draws values, accent draws rules and chroma, alert draws failures. */
    static final class Theme {
        final Color main, data, accent, alert, hot, muted, line, faint, panel, dataMuted;
        Theme(Color main, Color data, Color accent, Color alert) {
            this.main = main; this.data = data; this.accent = accent; this.alert = alert;
            hot = mix(main, Color.WHITE, .35); muted = mix(main, INK, .38); dataMuted = mix(data, INK, .4);
            line = alpha(main, 118); faint = alpha(main, 30); panel = alpha(mix(INK, main, .045), 234);
        }
        Theme blend(Theme to, double t) {
            return t <= 0 ? this : t >= 1 ? to : new Theme(mix(main, to.main, t), mix(data, to.data, t), mix(accent, to.accent, t), mix(alert, to.alert, t));
        }
    }

    static final Theme HOME = new Theme(CYAN, ORANGE, RED, RED);
    static final Theme NEWS = new Theme(GREEN, PAPER, RED, RED);
    static final Theme PACK = new Theme(AMBER, CYAN, ORANGE, RED);
    static final Theme GAME = new Theme(BLUE, YELLOW, RED, RED);
    static final Theme MAP = new Theme(VIOLET, CYAN, YELLOW, RED);
    static final Theme SETTINGS = new Theme(ORANGE, PAPER, CYAN, RED);
    static final Theme ADMIN = new Theme(RED, YELLOW, PAPER, YELLOW);
    static final Theme LAUNCH = new Theme(GREEN, PAPER, RED, RED);
    static final Theme HALT = new Theme(RED, YELLOW, PAPER, YELLOW);
    static final Theme BOOT = new Theme(MINT, PAPER, RED, RED);

    static Color alpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a))); }
    static Color mix(Color a, Color b, double t) {
        double u = Math.max(0, Math.min(1, t));
        return new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * u), (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * u),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * u), (int) Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * u));
    }
    static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }
    static double easeOut(double t) { double u = 1 - clamp01(t); return 1 - u * u * u; }
    static double easeInOut(double t) { double u = clamp01(t); return u < .5 ? 4 * u * u * u : 1 - Math.pow(-2 * u + 2, 3) / 2; }
    /** Frame-rate independent approach: the same feel at 15 or 60 fps. */
    static double approach(double value, double target, double rate, double dt) { return value + (target - value) * (1 - Math.exp(-rate * dt)); }

    // ---------------------------------------------------------------- fonts

    private static final Font PIXEL = load("/fonts/PxPlus_IBM_VGA8.ttf");
    private static final Font BRAND = load("/fonts/JuliusSansOne-Regular.ttf");
    private static final Map<String, Font> FONTS = new HashMap<>();

    private static Font load(String resource) {
        try (InputStream stream = Retro.class.getResourceAsStream(resource)) {
            if (stream == null) return null;
            Font font = Font.createFont(Font.TRUETYPE_FONT, stream);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
            return font;
        } catch (Exception ignored) { return null; }
    }

    /** PxPlus IBM VGA8 sits on a 16 px grid: anything else drops or doubles pixel rows, so sizes snap to 16/32/48/64. */
    static Font pixel(int size) {
        int px = size >= 56 ? 64 : size >= 40 ? 48 : size >= 24 ? 32 : 16;
        return FONTS.computeIfAbsent("p" + px, k -> PIXEL != null ? PIXEL.deriveFont(Font.PLAIN, px) : new Font(Font.MONOSPACED, Font.BOLD, px - 2));
    }
    /** The 16 px glyphs scaled by whole pixels, so a wide wordmark stays as crisp as the body text. */
    static Font wide(int sx, int sy) {
        return FONTS.computeIfAbsent("w" + sx + "x" + sy, k -> {
            Font base = pixel(16);
            return base.deriveFont(AffineTransform.getScaleInstance(sx, sy));
        });
    }
    static Font brand(float size) {
        return FONTS.computeIfAbsent("b" + size, k -> BRAND != null ? BRAND.deriveFont(Font.PLAIN, size) : new Font(Font.SANS_SERIF, Font.PLAIN, Math.round(size)));
    }
    static Font data(int size) { return FONTS.computeIfAbsent("d" + size, k -> new Font("Consolas", Font.PLAIN, size)); }

    // ---------------------------------------------------------------- text

    private static final FontRenderContext FRC = new FontRenderContext(null, false, false);
    private record Sprite(BufferedImage image, int dx, int dy, int pixels) {}
    private static final long SPRITE_BUDGET = 4_000_000; // ~16 MB of glow pixels; oldest sprites go first
    private static long spritePixels;
    private static final LinkedHashMap<String, Sprite> SPRITES = new LinkedHashMap<>(256, .75f, true);

    static int width(Font font, String text) { return text == null || text.isEmpty() ? 0 : (int) Math.ceil(font.getStringBounds(text, FRC).getWidth()); }

    /** Crisp text over its own soft phosphor bloom. Use glow only for text that changes rarely: each new string builds one sprite. */
    static void text(Graphics2D g, String text, Font font, int x, int y, Color color, double glow) {
        if (text == null || text.isEmpty()) return;
        if (glow > 0) {
            Sprite sprite = sprite(text, font, color, glow);
            if (sprite != null) g.drawImage(sprite.image(), x + sprite.dx(), y + sprite.dy(), null);
        }
        g.setFont(font); g.setColor(color); g.drawString(text, x, y);
    }
    static void text(Graphics2D g, String text, Font font, int x, int y, Color color) { text(g, text, font, x, y, color, .55); }
    static void plain(Graphics2D g, String text, Font font, int x, int y, Color color) { text(g, text, font, x, y, color, 0); }
    static void centered(Graphics2D g, String text, Font font, int cx, int y, Color color, double glow) { text(g, text, font, cx - width(font, text) / 2, y, color, glow); }
    static void right(Graphics2D g, String text, Font font, int rx, int y, Color color, double glow) { text(g, text, font, rx - width(font, text), y, color, glow); }

    /** Text cut to a pixel width with an ellipsis, so one long value never runs over its neighbour. */
    static String fit(Font font, String text, int maxWidth) {
        String value = text == null ? "" : text.replace('\t', ' ').replaceAll("\\s+", " ").strip();
        if (width(font, value) <= maxWidth) return value;
        while (value.length() > 1 && width(font, value + "...") > maxWidth) value = value.substring(0, value.length() - 1);
        return value + "...";
    }

    private static Sprite sprite(String text, Font font, Color color, double glow) {
        String key = text + '\u0001' + System.identityHashCode(font) + '\u0001' + color.getRGB() + '\u0001' + Math.round(glow * 100);
        Sprite cached = SPRITES.get(key);
        if (cached != null) return cached;
        int radius = font.getSize2D() * font.getTransform().getScaleY() >= 30 ? 5 : 3, pad = radius * 3;
        var bounds = font.getStringBounds(text, FRC); // logical bounds include a scaled font's transform; y is minus the ascent
        int ascent = (int) Math.ceil(-bounds.getY());
        int w = (int) Math.ceil(bounds.getWidth()) + pad * 2, h = (int) Math.ceil(bounds.getHeight()) + pad * 2;
        if (w <= 0 || h <= 0 || (long) w * h > 400_000) return null;
        BufferedImage mask = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = mask.createGraphics();
        mg.setFont(font); mg.setColor(Color.WHITE); mg.drawString(text, pad, pad + ascent);
        mg.dispose();
        int[] a = mask.getRGB(0, 0, w, h, null, 0, w), b = new int[a.length];
        for (int i = 0; i < a.length; i++) a[i] = a[i] >>> 24;
        for (int pass = 0; pass < 3; pass++) { blur(a, b, w, h, radius); blur(b, a, h, w, radius); }
        int rgb = color.getRGB() & 0xFFFFFF;
        double strength = Math.min(1.6, glow) * (color.getAlpha() / 255.0);
        for (int i = 0; i < a.length; i++) a[i] = (int) Math.min(255, a[i] * strength) << 24 | rgb;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, w, h, a, 0, w); // setRGB keeps the image eligible for acceleration
        Sprite sprite = new Sprite(image, -pad, -pad - ascent, w * h);
        SPRITES.put(key, sprite);
        spritePixels += sprite.pixels();
        var eldest = SPRITES.entrySet().iterator();
        while (spritePixels > SPRITE_BUDGET && eldest.hasNext()) { spritePixels -= eldest.next().getValue().pixels(); eldest.remove(); }
        return sprite;
    }

    /** One box-blur pass along rows, written transposed so the next call blurs the columns. */
    private static void blur(int[] src, int[] dst, int w, int h, int r) {
        int span = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w, sum = 0;
            for (int i = -r; i <= r; i++) sum += src[row + Math.max(0, Math.min(w - 1, i))];
            for (int x = 0; x < w; x++) {
                dst[x * h + y] = sum / span;
                sum += src[row + Math.min(w - 1, x + r + 1)] - src[row + Math.max(0, x - r)];
            }
        }
    }

    /**
     * A headline split into red and blue fringes with the main colour on top; {@code glitch} 0..1 tears horizontal slices
     * sideways. {@code stripes} cuts dark scan bars through the lower half of the letters, like an old broadcast logo.
     */
    static void chroma(Graphics2D g, String text, Font font, int x, int y, Color color, Color fringe, int split, double glitch, double time, boolean stripes) {
        int ascent = (int) Math.ceil(-font.getStringBounds(text, FRC).getY()), w = width(font, text);
        Shape oldClip = g.getClip();
        int slices = glitch > .02 ? 7 : 1, sliceH = Math.max(1, (ascent + 4) / slices);
        for (int s = 0; s < slices; s++) {
            int sy = y - ascent + s * sliceH, shift = 0;
            if (slices > 1) {
                long seed = (long) (time * 23) * 31 + s * 7919L;
                double r = ((seed * 1103515245L + 12345L) >>> 8 & 0xFFFF) / 65535.0;
                shift = r > .55 ? (int) Math.round((r - .55) * 2.2 * 26 * glitch) * (s % 2 == 0 ? 1 : -1) : 0;
            }
            if (slices > 1) { g.setClip(oldClip); g.clipRect(x - 40, sy, w + 80, s == slices - 1 ? ascent + 20 : sliceH); }
            int sx = x + shift, extra = (int) Math.round(glitch * 5);
            g.setFont(font);
            g.setColor(alpha(fringe, 150)); g.drawString(text, sx - split - extra, y);
            g.setColor(alpha(new Color(60, 120, 255), 150)); g.drawString(text, sx + split + extra, y);
            text(g, text, font, sx, y, color, slices > 1 ? 0 : .9);
        }
        g.setClip(oldClip);
        if (stripes) {
            // Cut only through the letters (and their fringes), never across the background beside them.
            var glyphs = font.createGlyphVector(FRC, text);
            java.awt.geom.Area letters = new java.awt.geom.Area(glyphs.getOutline(x, y));
            letters.add(new java.awt.geom.Area(glyphs.getOutline(x - split, y)));
            letters.add(new java.awt.geom.Area(glyphs.getOutline(x + split, y)));
            g.clip(letters);
            g.setColor(INK);
            int top = y - (int) (ascent * .45), step = Math.max(5, ascent / 8);
            for (int sy = top, i = 0; sy < y + 2; sy += step, i++) g.fillRect(x - split - 8, sy, w + split * 2 + 16, Math.min(4, 1 + i / 2));
            g.setClip(oldClip);
        }
    }

    // ---------------------------------------------------------------- seven segment

    private static final Map<Character, Integer> SEGMENTS = new HashMap<>();
    static {
        String[] map = {"0abcdef", "1bc", "2abdeg", "3abcdg", "4bcfg", "5acdfg", "6acdefg", "7abc", "8abcdefg", "9abcdfg", "-g", "Aabcefg", "bcdefg", "Cadef",
                "dbcdeg", "Eadefg", "Faefg", "Gacdef", "Hbcefg", "Ibc", "Jbcde", "Ldef", "nceg", "ocdeg", "Pabefg", "reg", "Sacdfg", "tdefg", "Ubcdef", "ucde", "Ybcdfg", "_d"};
        for (String row : map) {
            int bits = 0;
            for (char c : row.substring(1).toCharArray()) bits |= 1 << (c - 'a');
            SEGMENTS.put(row.charAt(0), bits);
        }
        SEGMENTS.put('O', SEGMENTS.get('0'));
    }

    static int segWidth(String text, int h) {
        int w = 0, digit = Math.round(h * .56f), gap = Math.max(3, h / 7);
        for (char c : text.toCharArray()) w += (c == ':' || c == '.' ? Math.max(4, h / 5) : digit) + gap;
        return Math.max(0, w - gap);
    }

    /** LED digits with ghost (unlit) segments and a bloom on the lit ones; returns the drawn width. Unknown characters are blank cells. */
    static int seg7(Graphics2D g, String text, int x, int y, int h, Color on, double time) {
        int digit = Math.round(h * .56f), gap = Math.max(3, h / 7), t = Math.max(2, Math.round(h / 8.5f)), cx = x;
        Color ghost = alpha(on, 20), bloom = alpha(on, 46);
        Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Stroke oldStroke = g.getStroke(), halo = new BasicStroke(t * 1.6f);
        for (char c : text.toCharArray()) {
            if (c == ':' || c == '.') {
                int cw = Math.max(4, h / 5), d = Math.max(2, t);
                boolean lit = c == '.' || ((int) (time * 2) & 1) == 0;
                g.setColor(lit ? on : ghost);
                if (c == ':') { g.fillRect(cx + (cw - d) / 2, y + h / 3 - d / 2, d, d); g.fillRect(cx + (cw - d) / 2, y + h * 2 / 3 - d / 2, d, d); }
                else g.fillRect(cx + (cw - d) / 2, y + h - d, d, d);
                cx += cw + gap; continue;
            }
            int bits = SEGMENTS.getOrDefault(Character.isLowerCase(c) && !SEGMENTS.containsKey(c) ? Character.toUpperCase(c) : c, 0);
            for (int s = 0; s < 7; s++) {
                Shape segment = segment(s, cx, y, digit, h, t);
                boolean lit = (bits & 1 << s) != 0;
                if (lit) { g.setStroke(halo); g.setColor(bloom); g.draw(segment); }
                g.setColor(lit ? on : ghost); g.fill(segment);
            }
            cx += digit + gap;
        }
        g.setStroke(oldStroke);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
        return cx - gap - x;
    }

    private static Shape segment(int index, int x, int y, int w, int h, int t) {
        double half = t / 2.0, slant = h * .09, left = x + half, right = x + w - half, top = y + half, mid = y + h / 2.0, bottom = y + h - half, gap = 1.2;
        Path2D p = switch (index) {
            case 0 -> horizontal(left + gap, right - gap, top, half);
            case 1 -> vertical(right, top + gap, mid - gap, half);
            case 2 -> vertical(right, mid + gap, bottom - gap, half);
            case 3 -> horizontal(left + gap, right - gap, bottom, half);
            case 4 -> vertical(left, mid + gap, bottom - gap, half);
            case 5 -> vertical(left, top + gap, mid - gap, half);
            default -> horizontal(left + gap, right - gap, mid, half);
        };
        // A slight italic lean, pivoting on the baseline, like a real LED module.
        p.transform(new AffineTransform(1, 0, -slant / h, 1, slant * (y + h) / h, 0));
        return p;
    }
    private static Path2D horizontal(double x0, double x1, double yc, double half) {
        Path2D p = new Path2D.Double();
        p.moveTo(x0, yc); p.lineTo(x0 + half, yc - half); p.lineTo(x1 - half, yc - half); p.lineTo(x1, yc); p.lineTo(x1 - half, yc + half); p.lineTo(x0 + half, yc + half); p.closePath();
        return p;
    }
    private static Path2D vertical(double xc, double y0, double y1, double half) {
        Path2D p = new Path2D.Double();
        p.moveTo(xc, y0); p.lineTo(xc + half, y0 + half); p.lineTo(xc + half, y1 - half); p.lineTo(xc, y1); p.lineTo(xc - half, y1 - half); p.lineTo(xc - half, y0 + half); p.closePath();
        return p;
    }

    // ---------------------------------------------------------------- meters and traces

    private static final Map<Integer, TexturePaint> HATCH = new HashMap<>();

    /** A diagonal-hatched bar (////) that marches with {@code phase}; the unfilled rest is a faint track. */
    static void hatch(Graphics2D g, int x, int y, int w, int h, double fraction, Color color, double phase) {
        if (w <= 0 || h <= 0) return;
        g.setColor(alpha(color, 22)); g.fillRect(x, y, w, h);
        int fill = (int) Math.round(w * clamp01(fraction));
        if (fill <= 0) return;
        TexturePaint base = HATCH.computeIfAbsent(color.getRGB(), rgb -> {
            BufferedImage tile = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            Color dark = mix(color, INK, .62);
            for (int ty = 0; ty < 8; ty++) for (int tx = 0; tx < 8; tx++) tile.setRGB(tx, ty, (tx + ty) % 8 < 3 ? dark.getRGB() : color.getRGB());
            return new TexturePaint(tile, new Rectangle(0, 0, 8, 8));
        });
        int offset = (int) Math.floor(phase) % 8;
        Paint old = g.getPaint();
        g.setPaint(new TexturePaint(base.getImage(), new Rectangle(x + offset, y, 8, 8)));
        g.fillRect(x, y, fill, h);
        g.setPaint(old);
        g.setColor(alpha(Color.WHITE, 60)); g.fillRect(x, y, fill, 1);
    }

    /** Block LED meter: {@code count} cells, the first {@code fraction} lit. */
    static void blocks(Graphics2D g, int x, int y, int w, int h, int count, double fraction, Color on) {
        int gap = 2, cell = Math.max(2, (w - gap * (count - 1)) / count), lit = (int) Math.round(count * clamp01(fraction));
        for (int i = 0; i < count; i++) { g.setColor(i < lit ? on : alpha(on, 26)); g.fillRect(x + i * (cell + gap), y, cell, h); }
    }

    /**
     * A patient-monitor ECG: a sweep head writes the newest trace left to right, a short gap runs ahead of it and the
     * older sweep fades behind. Offline draws a dim flat line with a little noise.
     */
    static void ecg(Graphics2D g, int x, int y, int w, int h, double time, double bpm, Color color, boolean live) {
        if (w < 30 || h < 10) return;
        double window = 3.2, secondsPerPx = window / w, sweep = (time % window) / window;
        int head = (int) (sweep * w), gapPx = Math.max(10, w / 24), base = y + h * 2 / 3;
        Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        Stroke oldStroke = g.getStroke();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int buckets = 5;
        Path2D[] paths = new Path2D[buckets];
        int lastBucket = -1;
        double lastX = 0, lastY = 0;
        for (int px = 0; px < w; px += 2) {
            int behind = px <= head ? head - px : head - px + w;
            if (behind < 0 || w - behind < gapPx) { lastBucket = -1; continue; }
            double sample = live ? beat((time - behind * secondsPerPx) * bpm / 60.0) : Math.sin((time * 9 + px) * 1.7) * .015;
            double py = base - sample * h * .62;
            int bucket = Math.min(buckets - 1, behind * buckets / w);
            if (paths[bucket] == null) paths[bucket] = new Path2D.Double();
            if (lastBucket != bucket) { paths[bucket].moveTo(lastBucket < 0 ? x + px : lastX, lastBucket < 0 ? py : lastY); }
            paths[bucket].lineTo(x + px, py);
            lastBucket = bucket; lastX = x + px; lastY = py;
        }
        Stroke wide = new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND), thin = g.getStroke();
        for (int b = buckets - 1; b >= 0; b--) {
            if (paths[b] == null) continue;
            int a = (int) ((live ? 255 : 120) * (1 - b / (double) buckets * .85));
            g.setColor(alpha(color, a / 4)); g.setStroke(wide); g.draw(paths[b]);
            g.setColor(alpha(color, a)); g.setStroke(thin); g.draw(paths[b]);
        }
        if (live) {
            double sample = beat(time * bpm / 60.0);
            int hy = (int) (base - sample * h * .62);
            g.setColor(alpha(Color.WHITE, 220)); g.fillRect(x + head - 1, hy - 1, 3, 3);
        }
        g.setStroke(oldStroke);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
    }

    /** One heartbeat per unit of phase: P bump, QRS spike, T wave. */
    static double beat(double phase) {
        double u = phase - Math.floor(phase);
        if (u < .08) return 0;
        if (u < .16) return Math.sin((u - .08) / .08 * Math.PI) * .12;
        if (u < .24) return 0;
        if (u < .26) return -(u - .24) / .02 * .14;
        if (u < .29) return -.14 + (u - .26) / .03 * 1.14;
        if (u < .32) return 1 - (u - .29) / .03 * 1.32;
        if (u < .35) return -.32 + (u - .32) / .03 * .32;
        if (u < .46) return 0;
        if (u < .62) return Math.sin((u - .46) / .16 * Math.PI) * .26;
        return 0;
    }

    /** A sphere of horizontal bars (thin at the top, heavy at the bottom) that slowly roll downward, like a broadcast globe. */
    static void orb(Graphics2D g, int cx, int cy, int r, Color color, double phase) {
        if (r < 4) return;
        int bands = Math.max(6, r / 4);
        double step = 2.0 * r / bands, roll = (phase % 1 + 1) % 1;
        Paint old = g.getPaint();
        // brighter on the left, like light falling on a lit sphere
        g.setPaint(new GradientPaint(cx - r, 0, mix(color, Color.WHITE, .25), cx + r, 0, mix(color, INK, .4)));
        for (int i = -1; i <= bands; i++) {
            double top = cy - r + (i + roll) * step, depth = clamp01((top - (cy - r)) / (2.0 * r));
            double thick = Math.max(1, step * (.25 + depth * .62));
            for (double yy = top; yy < top + thick; yy += 1) {
                double dy = yy - cy;
                if (Math.abs(dy) >= r) continue;
                int half = (int) Math.sqrt(r * (double) r - dy * dy);
                g.fillRect(cx - half, (int) yy, half * 2, 1);
            }
        }
        g.setPaint(old);
    }

    // ---------------------------------------------------------------- frames

    /**
     * A terminal panel: dark fill, a thin frame, bright corner brackets and an optional title cut into the top edge.
     * {@code reveal} 0..1 powers it on: the corners land first, the edges grow out of them, then the fill comes up.
     */
    static void panel(Graphics2D g, int x, int y, int w, int h, Theme t, double reveal, String title) {
        if (w <= 0 || h <= 0 || reveal <= 0) return;
        double fill = clamp01((reveal - .25) / .75);
        g.setColor(alpha(t.panel, (int) (t.panel.getAlpha() * fill))); g.fillRect(x, y, w, h);
        frame(g, x, y, w, h, t, reveal, title);
    }

    /** The panel's edges, corners and title without the fill (for content that paints its own background). */
    static void frame(Graphics2D g, int x, int y, int w, int h, Theme t, double reveal, String title) {
        if (w <= 0 || h <= 0 || reveal <= 0) return;
        double grow = easeOut(reveal / .7);
        g.setColor(t.line);
        int gx = (int) (w / 2.0 * grow), gy = (int) (h / 2.0 * grow);
        g.drawLine(x, y, x + gx, y); g.drawLine(x + w, y, x + w - gx, y);
        g.drawLine(x, y + h, x + gx, y + h); g.drawLine(x + w, y + h, x + w - gx, y + h);
        g.drawLine(x, y, x, y + gy); g.drawLine(x, y + h, x, y + h - gy);
        g.drawLine(x + w, y, x + w, y + gy); g.drawLine(x + w, y + h, x + w, y + h - gy);
        int c = Math.min(12, Math.min(w, h) / 4);
        g.setColor(t.main);
        g.fillRect(x - 1, y - 1, c, 2); g.fillRect(x - 1, y - 1, 2, c);
        g.fillRect(x + w - c + 1, y - 1, c, 2); g.fillRect(x + w - 1, y - 1, 2, c);
        g.fillRect(x - 1, y + h - 1, c, 2); g.fillRect(x - 1, y + h - c + 1, 2, c);
        g.fillRect(x + w - c + 1, y + h - 1, c, 2); g.fillRect(x + w - 1, y + h - c + 1, 2, c);
        if (title != null && !title.isBlank() && reveal > .35) {
            Font f = pixel(16);
            int tw = width(f, title);
            g.setColor(INK); g.fillRect(x + 16, y - 9, tw + 16, 18);
            g.setColor(t.main); g.fillRect(x + 16, y - 3, 3, 6); g.fillRect(x + 29 + tw, y - 3, 3, 6);
            text(g, title, f, x + 24, y + 5, alpha(t.main, (int) (255 * clamp01((reveal - .35) / .4))), .5);
        }
    }

    /** A bracketed terminal button; {@code hover} 0..1 sweeps an inverted block across it. */
    static void button(Graphics2D g, Rectangle r, String label, Theme t, double hover, boolean enabled) {
        if (r.isEmpty()) return;
        Color main = enabled ? t.main : t.muted;
        g.setColor(alpha(INK, 230)); g.fillRect(r.x, r.y, r.width, r.height);
        int sweep = (int) Math.round(r.width * easeOut(hover));
        if (sweep > 0) { g.setColor(main); g.fillRect(r.x, r.y, sweep, r.height); }
        g.setColor(hover > .5 ? t.hot : t.line); g.drawRect(r.x, r.y, r.width, r.height);
        Font f = pixel(16);
        int base = r.y + r.height / 2 + 5, spread = (int) Math.round(4 * easeOut(hover));
        String text = fit(f, label, r.width - 44);
        int tw = width(f, text), tx = r.x + (r.width - tw) / 2;
        Shape clip = g.getClip();
        // The label flips to INK where the block has passed: the same text in two clips.
        g.clipRect(r.x + sweep, r.y, r.width - sweep + 1, r.height + 1);
        text(g, text, f, tx, base, main, hover > .05 ? 0 : .45);
        plain(g, "[", f, tx - 16 - spread, base, t.muted); plain(g, "]", f, tx + tw + 8 + spread, base, t.muted);
        g.setClip(clip);
        if (sweep > 0) {
            g.clipRect(r.x, r.y, sweep, r.height + 1);
            plain(g, text, f, tx, base, INK);
            plain(g, "[", f, tx - 16 - spread, base, INK); plain(g, "]", f, tx + tw + 8 + spread, base, INK);
            g.setClip(clip);
        }
    }

    /** Small status LED with a halo; blinks when {@code blink} is set. */
    static void led(Graphics2D g, int cx, int cy, Color color, boolean on, double time, boolean blink) {
        boolean lit = on && (!blink || ((int) (time * 2.4) & 1) == 0);
        if (lit) { g.setColor(alpha(color, 50)); g.fillRect(cx - 5, cy - 5, 11, 11); g.setColor(alpha(color, 90)); g.fillRect(cx - 4, cy - 4, 9, 9); }
        g.setColor(lit ? color : alpha(color, 40)); g.fillRect(cx - 3, cy - 3, 7, 7);
        if (lit) { g.setColor(alpha(Color.WHITE, 170)); g.fillRect(cx - 2, cy - 2, 2, 2); }
    }

    // ---------------------------------------------------------------- full-window layers

    private static BufferedImage dotLayer, crtLayer;
    private static BufferedImage[] noiseFrames;

    /** TV static: four pre-rolled frames at a third of the size, scaled up blocky and cycled per paint. */
    static void noise(Graphics2D g, int x, int y, int w, int h, long frame) {
        int qw = Math.max(1, w / 3), qh = Math.max(1, h / 3);
        if (noiseFrames == null || noiseFrames[0].getWidth() != qw || noiseFrames[0].getHeight() != qh) {
            noiseFrames = new BufferedImage[4];
            java.util.Random random = new java.util.Random(1984);
            int[] px = new int[qw * qh];
            for (int f = 0; f < noiseFrames.length; f++) {
                for (int row = 0; row < qh; row++) {
                    // Some rows tear brighter or tinted, like a set between stations.
                    int bias = random.nextInt(9) == 0 ? 60 : 0, tint = random.nextInt(14);
                    for (int col = 0; col < qw; col++) {
                        int v = Math.min(255, random.nextInt(200) + bias);
                        int r = tint == 0 ? Math.min(255, v + 50) : v, b = tint == 1 ? Math.min(255, v + 60) : v;
                        px[row * qw + col] = 0xFF000000 | r << 16 | v << 8 | b;
                    }
                }
                noiseFrames[f] = new BufferedImage(qw, qh, BufferedImage.TYPE_INT_ARGB);
                noiseFrames[f].setRGB(0, 0, qw, qh, px, 0, qw);
            }
        }
        Object interpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(noiseFrames[(int) Math.floorMod(frame, noiseFrames.length)], x, y, w, h, null);
        if (interpolation != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
    }
    private static final Map<String, BufferedImage> AMBIENT = new HashMap<>();

    /** A soft phosphor glow from the top left in the page colour; built at quarter size and scaled up (it is a gradient anyway). */
    static void ambient(Graphics2D g, int w, int h, Color color, double strength) {
        if (strength <= 0) return;
        int qw = Math.max(1, w / 4), qh = Math.max(1, h / 4);
        if (AMBIENT.size() > 8) AMBIENT.clear();
        BufferedImage glow = AMBIENT.computeIfAbsent(color.getRGB() + ":" + qw + "x" + qh, key -> {
            BufferedImage image = new BufferedImage(qw, qh, BufferedImage.TYPE_INT_ARGB);
            Graphics2D c = image.createGraphics();
            c.setPaint(new RadialGradientPaint(qw * .16f, qh * .02f, Math.max(qw, qh) * .8f, new float[]{0f, .45f, 1f}, new Color[]{alpha(color, 52), alpha(color, 14), alpha(color, 0)}));
            c.fillRect(0, 0, qw, qh);
            c.dispose();
            return image;
        });
        Composite old = g.getComposite();
        Object interpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        if (strength < 1) g.setComposite(AlphaComposite.SrcOver.derive((float) strength));
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(glow, 0, 0, w, h, null);
        g.setComposite(old);
        if (interpolation != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
    }

    /** The holomap dot grid behind every page; rebuilt only when the window size changes. */
    static void dots(Graphics2D g, int w, int h) {
        if (dotLayer == null || dotLayer.getWidth() != w || dotLayer.getHeight() != h) {
            dotLayer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            int[] px = new int[w * h];
            int dot = new Color(96, 120, 132, 54).getRGB(), major = new Color(120, 150, 160, 92).getRGB();
            for (int y = 11; y < h; y += 22) for (int x = 11; x < w; x += 22) px[y * w + x] = (x / 22 + y / 22) % 4 == 0 ? major : dot;
            dotLayer.setRGB(0, 0, w, h, px, 0, w);
        }
        g.drawImage(dotLayer, 0, 0, null);
    }

    /** Scanlines, a soft vignette and the rounded tube corners, baked once per window size into one overlay image. */
    static void crt(Graphics2D g, int w, int h) {
        if (crtLayer == null || crtLayer.getWidth() != w || crtLayer.getHeight() != h) {
            crtLayer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D c = crtLayer.createGraphics();
            c.setColor(new Color(0, 0, 0, 44));
            for (int y = 1; y < h; y += 3) c.fillRect(0, y, w, 1);
            float radius = (float) Math.hypot(w, h) * .62f;
            c.setPaint(new RadialGradientPaint(w / 2f, h / 2f, radius, new float[]{0f, .62f, 1f}, new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 18), new Color(0, 0, 0, 150)}));
            c.fillRect(0, 0, w, h);
            c.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            java.awt.geom.Area corners = new java.awt.geom.Area(new Rectangle(0, 0, w, h));
            corners.subtract(new java.awt.geom.Area(new java.awt.geom.RoundRectangle2D.Float(1, 1, w - 2, h - 2, 26, 26)));
            c.setColor(Color.BLACK); c.fill(corners);
            c.dispose();
        }
        g.drawImage(crtLayer, 0, 0, null);
    }

    /** Old-tube power on (t 0 to 1) or off (1 to 0): a bright line spreads across the middle, then opens into the picture. */
    static void tube(Graphics2D g, int w, int h, double t) {
        if (t >= 1) return;
        double line = easeOut(t / .4), open = easeOut((t - .4) / .6);
        int bandH = (int) Math.round(h * open), top = (h - bandH) / 2, lineW = (int) Math.round(w * line), fade = (int) (255 * (1 - open));
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, top); g.fillRect(0, top + bandH, w, h - top - bandH);
        if (fade > 0 && lineW > 0) {
            g.setColor(alpha(MINT, fade / 3)); g.fillRect((w - lineW) / 2, h / 2 - 6, lineW, 12);
            g.setColor(alpha(PAPER, fade)); g.fillRect((w - lineW) / 2, h / 2 - 1, lineW, 3);
        }
    }
}
