package il.co.embeddit.btviewer;

import android.os.Handler;
import android.os.Looper;

/**
 * Simulated sensor, for exercising the app with no hardware attached.
 *
 * It deliberately emits the same ASCII line the firmware does — "312\n" — into
 * the same parse path. A demo that bypassed onLine() would exercise a parallel
 * pipeline and prove nothing about the real one; this way the number formatting,
 * the non-numeric guard, the zone logic and the audio engine are all the code
 * that ships.
 *
 * AUTO flies a profile: descend, hover low enough to reach the continuous tone,
 * climb out, cruise. MANUAL parks at whatever height you set, which is the mode
 * you want when judging the beep rate at one distance.
 */
public class DemoSource {

    public interface Sink {
        void onDemoLine(String line);
    }

    public static final int AUTO = 0, MANUAL = 1;

    private static final int TICK_MS = 100;      // matches the firmware's 10 Hz
    private static final int CYCLE_MS = 24000;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Sink sink;
    private final Pump pump = new Pump(this);

    private boolean running;
    private int mode = AUTO;
    private float manualCm = 250f;
    private float rangeCm = 500f;
    private long t0;
    private long seed = 0x5DEECE66DL;

    public DemoSource(Sink s) { sink = s; }

    public void start(float range) {
        rangeCm = range <= 0 ? 500f : range;
        if (running) return;
        running = true;
        t0 = System.currentTimeMillis();
        h.post(pump);
    }

    public void stop() {
        running = false;
        h.removeCallbacks(pump);
    }

    public boolean isRunning() { return running; }

    public void setMode(int m) { mode = m; }

    public int mode() { return mode; }

    public void setManual(float cm) { manualCm = cm; }

    public float manual() { return manualCm; }

    private static class Pump implements Runnable {
        private final DemoSource d;
        Pump(DemoSource src) { d = src; }
        public void run() {
            if (!d.running) return;
            d.emit();
            d.h.postDelayed(this, TICK_MS);
        }
    }

    private void emit() {
        float cm = mode == MANUAL ? manualCm : profile(System.currentTimeMillis() - t0);
        // A little noise, because a mathematically clean ramp would hide exactly
        // the chatter the hysteresis and the easing exist to absorb.
        cm += noise() * 1.8f;
        if (cm < 3f) cm = 3f;
        sink.onDemoLine(String.valueOf(Math.round(cm)));
    }

    /** Height in cm at time t within the cycle. */
    private float profile(long ms) {
        float p = (ms % CYCLE_MS) / (float) CYCLE_MS;
        float hi = rangeCm * 0.92f;
        float lo = rangeCm * 0.055f;          // low enough for the continuous tone
        if (p < 0.08f) return hi;                             // cruise
        if (p < 0.42f) return lerp(hi, lo, ease((p - 0.08f) / 0.34f));   // descend
        if (p < 0.55f) return lo;                             // hold low
        if (p < 0.85f) return lerp(lo, hi, ease((p - 0.55f) / 0.30f));   // climb out
        return hi;
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** Smoothstep, so the aircraft does not start and stop instantaneously. */
    private static float ease(float t) {
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return t * t * (3f - 2f * t);
    }

    /** Self-contained LCG: reproducible, and no dependency on Random's state. */
    private float noise() {
        seed = (seed * 0x5DEECE66DL + 0xB) & ((1L << 48) - 1);
        return ((int) (seed >>> 22) % 1000) / 500f - 1f;
    }
}
