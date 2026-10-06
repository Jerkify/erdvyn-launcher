import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Turns Erdvyn World's planet texture ({@code gradlew renderNationsMap -Pplanet} in erdvyn_world_patch:
 * {@code outputs/geo-preview/planet-nations.png}, once round and pole to pole) into the launcher's planet atlas: a
 * 2048 x 2048 grey PNG whose every byte is {@code class << 6 | relief}, class 0 deep sea, 1 shallow sea, 2 land,
 * 3 ice. The launcher grades it into each page's phosphor ({@code PlanetGlobe}).
 * <p>Usage: {@code java PlanetAtlas <planet-nations.png> <src/main/resources/assets/planet-atlas.png>}
 */
public final class PlanetAtlas {
    static final int SIZE = 2048;

    public static void main(String[] args) throws Exception {
        BufferedImage source = ImageIO.read(new File(args[0]));
        BufferedImage atlas = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_BYTE_GRAY);
        byte[] out = ((java.awt.image.DataBufferByte) atlas.getRaster().getDataBuffer()).getData();
        for (int v = 0; v < SIZE; v++) for (int u = 0; u < SIZE; u++) {
            // a 2 x 2 box from the source (its rows are a little denser than its columns)
            int r = 0, g = 0, b = 0;
            for (int k = 0; k < 4; k++) {
                int sx = Math.min(source.getWidth() - 1, u * source.getWidth() / SIZE + (k & 1));
                int sy = Math.min(source.getHeight() - 1, (int) ((v + .5 * (k >> 1)) * source.getHeight() / SIZE));
                int rgb = source.getRGB(sx, sy);
                r += rgb >> 16 & 255; g += rgb >> 8 & 255; b += rgb & 255;
            }
            out[v * SIZE + u] = (byte) classify(r / 4, g / 4, b / 4);
        }
        ImageIO.write(atlas, "png", new File(args[1]));
    }

    static int classify(int r, int g, int b) {
        int lum = (r * 30 + g * 59 + b * 11) / 100, spread = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
        int kind;
        if (lum >= 150 && spread < 60) kind = 3;               // ice and snow: bright and grey
        else if (b > r + 30 && b >= g) kind = lum < 70 ? 0 : 1; // sea: blue over red, shallows brighter
        else if (b > r + 30 && lum >= 120) kind = 1;           // the pale glow along the coasts
        else kind = 2;
        return kind << 6 | Math.min(63, lum / 4);
    }
}
