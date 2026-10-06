package tr.erdvyn.launcher;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Synthesised UI sounds on one long-lived audio line fed by one daemon thread. Opening a line per click took
 * 50-100 ms and could fail when clicks overlapped; the line now opens once and stays open until shutdown.
 * Waveforms are rendered once per pitch and reused.
 */
final class UiSound {
    private static final float RATE = 22050;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, false);
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(8);
    private final Map<String, byte[]> rendered = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    UiSound() {
        Thread worker = new Thread(this::run, "erdvyn-ui-sound");
        worker.setDaemon(true);
        worker.start();
    }

    /** A mechanical key click; pitch shifts the switch leaf and body. */
    void click(double pitch) { offer(rendered.computeIfAbsent("c" + Math.round(pitch), k -> renderClick(pitch))); }
    /** 0 = tube power-on thump, 1 = relay tick, 2 = ready chime. */
    void boot(int kind) { offer(rendered.computeIfAbsent("b" + kind, k -> renderBoot(kind))); }

    void shutdown() { running = false; queue.clear(); queue.offer(new byte[0]); }

    private void offer(byte[] pcm) { if (running) queue.offer(pcm); } // a full queue drops the click instead of lagging behind the pointer

    private void run() {
        SourceDataLine line = null;
        try {
            while (running) {
                byte[] pcm = queue.take();
                if (!running || pcm.length == 0) break;
                if (line == null) {
                    line = AudioSystem.getSourceDataLine(FORMAT);
                    line.open(FORMAT, 4096); // ~90 ms buffer: short enough that a click lands with the press
                    line.start();
                }
                line.write(pcm, 0, pcm.length);
            }
        } catch (Exception ignored) {
            // No audio device: the launcher stays silent rather than failing.
        } finally {
            if (line != null) { line.stop(); line.close(); }
        }
    }

    private static byte[] renderClick(double pitch) {
        int length = (int) (RATE * .042);
        byte[] data = new byte[length * 2];
        Random noise = new Random(Double.doubleToLongBits(pitch));
        for (int i = 0; i < length; i++) {
            double t = i / RATE, topNoise = (noise.nextDouble() * 2 - 1) * Math.exp(-t * 190), leaf = Math.sin(2 * Math.PI * (1780 + pitch * 1.8) * t) * Math.exp(-t * 170);
            double delayed = Math.max(0, t - .0095), gate = t >= .0095 ? 1 : 0, bottomNoise = (noise.nextDouble() * 2 - 1) * Math.exp(-delayed * 155) * gate;
            double metal = (Math.sin(2 * Math.PI * 2380 * delayed) + .45 * Math.sin(2 * Math.PI * 3260 * delayed)) * Math.exp(-delayed * 125) * gate;
            double body = Math.sin(2 * Math.PI * (132 + pitch * .22) * t) * Math.exp(-t * 70);
            put(data, i, (topNoise * .28 + leaf * .18 + bottomNoise * .24 + metal * .12 + body * .10) * 790);
        }
        return data;
    }

    private static byte[] renderBoot(int kind) {
        double duration = kind == 0 ? .42 : kind == 2 ? .18 : .026;
        int length = (int) (RATE * duration);
        byte[] data = new byte[length * 2];
        Random noise = new Random(kind * 7919L + 17);
        for (int i = 0; i < length; i++) {
            double t = i / RATE, envelope = Math.pow(1 - t / duration, kind == 0 ? 1.1 : 2.8), sample;
            if (kind == 0) sample = Math.sin(2 * Math.PI * 48 * t) * .15 + (noise.nextDouble() * 2 - 1) * .07 + ((t < .018 || t > .095 && t < .112) ? (noise.nextDouble() * 2 - 1) * .35 : 0);
            else if (kind == 2) sample = Math.sin(2 * Math.PI * 690 * t) * .34 + (t > .105 ? (noise.nextDouble() * 2 - 1) * .12 : 0);
            else sample = (noise.nextDouble() * 2 - 1) * .42 + Math.sin(2 * Math.PI * 1850 * t) * .12;
            put(data, i, sample * envelope * 420);
        }
        return data;
    }

    private static void put(byte[] data, int index, double value) {
        short pcm = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
        data[index * 2] = (byte) pcm;
        data[index * 2 + 1] = (byte) (pcm >> 8);
    }
}
