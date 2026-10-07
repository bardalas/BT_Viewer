package il.co.embeddit.btviewer;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * A small synthesiser for the low-altitude alert, offering five conventional
 * warning patterns (steady, fast beep, hi-lo siren, yelp, triple beep).
 *
 * One thread writes PCM without interruption. The selected sound is read fresh
 * every buffer, so a change takes effect within about 20 ms without restarting
 * anything.
 */
public class Beeper {

    private static final int SR = 22050;
    private static final int CHUNK = 512;          // ~23 ms per buffer

    /** The five selectable alert sounds. Ids are persisted in Config. */
    public static final int STEADY = 0, FAST = 1, HILO = 2, YELP = 3, TRIPLE = 4;
    public static final int COUNT = 5;

    /** Output level. High, and driven into soft clipping so it cuts through noise. */
    private static final double LEVEL = 0.95;
    private static final double DRIVE = 2.2;
    private static final double NORM = Math.tanh(DRIVE);

    private volatile boolean running;
    private volatile boolean sounding;             // gate the whole engine
    private volatile int sound = STEADY;

    private AudioTrack track;
    private Thread thread;

    // Carried across buffers so the waveform and the gate stay continuous.
    private double tonePhase, cyclePhase, gain;

    public void start() {
        if (running) return;
        int min = AudioTrack.getMinBufferSize(SR,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int buf = Math.max(min, CHUNK * 8);
        track = new AudioTrack(AudioManager.STREAM_MUSIC, SR,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                buf, AudioTrack.MODE_STREAM);
        if (track.getState() != AudioTrack.STATE_INITIALIZED) { track = null; return; }
        running = true;
        track.play();
        thread = new Thread(new Loop(this), "beeper");
        thread.start();
    }

    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            try { t.join(300); } catch (InterruptedException ignored) { }
        }
        thread = null;
        if (track != null) {
            try { track.stop(); } catch (IllegalStateException ignored) { }
            track.release();
            track = null;
        }
    }

    /** Silence without tearing down the engine, so resuming is instant. */
    public void setSounding(boolean on) { sounding = on; }

    public void setSound(int id) { sound = id < 0 || id >= COUNT ? STEADY : id; }

    private static class Loop implements Runnable {
        private final Beeper b;
        Loop(Beeper beeper) { b = beeper; }

        public void run() {
            short[] out = new short[CHUNK];
            while (b.running) {
                AudioTrack t = b.track;
                if (t == null) return;
                b.fill(out);
                try {
                    t.write(out, 0, out.length);
                } catch (IllegalStateException e) {
                    return;
                }
            }
        }
    }

    /**
     * Each sound is a frequency and an open/closed gate as a function of its
     * position in a repeating cycle. The tone phase is integrated from the
     * instantaneous frequency, so changing pitch never clicks.
     */
    private void fill(short[] out) {
        int snd = sound;
        boolean on = sounding;

        double cycleHz;                 // repetitions per second
        switch (snd) {
            case FAST:   cycleHz = 4.0; break;   // beep-beep-beep
            case HILO:   cycleHz = 1.0; break;   // two-tone, 0.5 s each
            case YELP:   cycleHz = 3.0; break;   // rising sweep, repeated
            case TRIPLE: cycleHz = 1.0; break;   // three short beeps, pause
            default:     cycleHz = 1.0; break;   // steady: cycle is irrelevant
        }
        double step = cycleHz / SR;

        for (int i = 0; i < out.length; i++) {
            cyclePhase += step;
            if (cyclePhase >= 1.0) cyclePhase -= 1.0;
            double c = cyclePhase;

            double f;
            boolean open;
            switch (snd) {
                case FAST:
                    f = 1000; open = c < 0.5; break;
                case HILO:
                    f = c < 0.5 ? 650 : 950; open = true; break;
                case YELP:
                    f = 700 + 900 * c; open = true; break;
                case TRIPLE:
                    f = 1300;
                    open = c < 0.12 || (c >= 0.24 && c < 0.36) || (c >= 0.48 && c < 0.60);
                    break;
                default:
                    f = 1000; open = true;
            }

            // ~1 ms slew keeps edges crisp without clicking.
            double aim = (on && open) ? LEVEL : 0.0;
            gain += (aim - gain) * 0.04;

            tonePhase += 2.0 * Math.PI * f / SR;
            if (tonePhase > 2.0 * Math.PI) tonePhase -= 2.0 * Math.PI;

            double v = Math.tanh(DRIVE * Math.sin(tonePhase)) / NORM;
            out[i] = (short) (v * gain * 32767.0);
        }
    }
}
