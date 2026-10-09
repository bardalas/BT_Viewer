package il.co.embeddit.btviewer;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * A small synthesiser for the low-altitude alert, offering five conventional
 * warning patterns (steady, fast beep, hi-lo siren, yelp, triple beep).
 *
 * One thread writes PCM without interruption. The selected sound is read fresh
 * every buffer, so a change takes effect without restarting anything.
 *
 * Latency is what matters here: the tone must start the moment the screen
 * turns red. The track runs at the device's native rate (no resampler), in
 * low-latency mode, with only ~20 ms queued ahead. The old 22.05 kHz track kept
 * 190+ ms buffered, all of which played before a change could be heard.
 *
 * It is tagged as an alarm, so it plays at alarm volume (not the media volume
 * the user may have turned down).
 */
public class Beeper {

    private final int SR = nativeRate();
    private final int CHUNK = SR / 200;            // 5 ms per write
    private final int QUEUE = SR / 50;             // ~20 ms ahead of the speaker

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
    private final String tag;

    public Beeper() { this("main"); }

    /** tag names this engine in the field log ("main" alert, "preview" in settings). */
    public Beeper(String t) { tag = t; }

    // Edge measurement: when setSounding flips, find the first frame that
    // actually carries the change, then ask the platform when that frame
    // reaches the speaker. That is the real decision-to-ear delay, including
    // the mixer and driver, which the app-side queue alone cannot show.
    private volatile long edgeDecidedNs;
    private volatile boolean edgePending, edgeOn;
    private long edgeFrame = -1;
    private final android.media.AudioTimestamp ts = new android.media.AudioTimestamp();

    // Carried across buffers so the waveform and the gate stay continuous.
    private double tonePhase, cyclePhase, gain;
    private volatile long written;   // frames handed to the track

    public void start() {
        if (running) return;
        int min = AudioTrack.getMinBufferSize(SR,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SR)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(min)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (Exception e) {
            track = null;
            Telemetry.event("audio[" + tag + "] start FAILED " + e);
            return;
        }
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track = null;
            Telemetry.event("audio[" + tag + "] start FAILED not initialized");
            return;
        }
        // Trim what is queued ahead; capacity stays at min so a hiccup does not
        // underrun. The platform rounds this up to what the device can do.
        track.setBufferSizeInFrames(Math.max(QUEUE, CHUNK * 2));
        running = true;
        track.play();
        track.addOnRoutingChangedListener(new Routing(this), null);
        thread = new Thread(new Loop(this), "beeper");
        thread.start();
        Telemetry.event("audio[" + tag + "] start rate=" + SR
                + " perf=" + (track.getPerformanceMode() == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                        ? "LOW_LATENCY" : String.valueOf(track.getPerformanceMode()))
                + " buf=" + track.getBufferSizeInFrames() + "/" + track.getBufferCapacityInFrames()
                + "fr minBytes=" + min + " route=" + route());
    }

    private static class Routing implements android.media.AudioRouting.OnRoutingChangedListener {
        private final Beeper b;
        Routing(Beeper beeper) { b = beeper; }
        public void onRoutingChanged(android.media.AudioRouting r) {
            Telemetry.event("audio[" + b.tag + "] route -> " + b.route());
        }
    }

    /** Where the sound is going: speaker, bluetooth, wired... */
    public String route() {
        AudioTrack t = track;
        if (t == null) return "none";
        try {
            android.media.AudioDeviceInfo d = t.getRoutedDevice();
            if (d == null) return "unknown";
            switch (d.getType()) {
                case android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "speaker";
                case android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "earpiece";
                case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "bt-a2dp";
                case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "bt-sco";
                case android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET: return "wired-headset";
                case android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "wired-headphones";
                case android.media.AudioDeviceInfo.TYPE_USB_HEADSET: return "usb-headset";
                default: return "type" + d.getType() + ":" + d.getProductName();
            }
        } catch (Exception e) {
            return "err";
        }
    }

    /** One-line health of the engine for the periodic stats. */
    public String stats() {
        AudioTrack t = track;
        if (t == null) return "audio[" + tag + "] none";
        try {
            return "audio[" + tag + "] q=" + queuedMs() + "ms underruns=" + t.getUnderrunCount()
                    + " route=" + route() + " vol=" + streamVolume();
        } catch (Exception e) {
            return "audio[" + tag + "] err " + e;
        }
    }

    private static android.media.AudioManager am;
    static void init(android.content.Context c) {
        am = (android.media.AudioManager) c.getApplicationContext().getSystemService(
                android.content.Context.AUDIO_SERVICE);
    }

    private static String streamVolume() {
        if (am == null) return "?";
        return am.getStreamVolume(AudioManager.STREAM_ALARM) + "/"
                + am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
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
    public void setSounding(boolean on) {
        if (on != sounding) {
            edgeDecidedNs = System.nanoTime();
            edgeOn = on;
            edgePending = true;
        }
        sounding = on;
    }

    /** Audio queued ahead of the speaker, ms: the lag between a decision and hearing it. */
    public int queuedMs() {
        AudioTrack t = track;
        if (t == null) return -1;
        try {
            long played = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
            return (int) ((written - played) * 1000 / SR);
        } catch (Exception e) {
            return -1;
        }
    }

    public int rate() { return SR; }

    private static int nativeRate() {
        int r = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_ALARM);
        return r >= 8000 && r <= 192000 ? r : 48000;
    }

    public void setSound(int id) { sound = id < 0 || id >= COUNT ? STEADY : id; }

    private static class Loop implements Runnable {
        private final Beeper b;
        Loop(Beeper beeper) { b = beeper; }

        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            short[] out = new short[b.CHUNK];
            while (b.running) {
                AudioTrack t = b.track;
                if (t == null) return;
                b.fill(out);
                try {
                    b.written += t.write(out, 0, out.length);
                } catch (IllegalStateException e) {
                    return;
                }
                if (b.edgeFrame >= 0) b.reportEdge(t);
            }
        }
    }

    /**
     * Each sound is a frequency and an open/closed gate as a function of its
     * position in a repeating cycle. The tone phase is integrated from the
     * instantaneous frequency, so changing pitch never clicks.
     */
    /** Called on the audio thread once the edge frame has been written. */
    private void reportEdge(AudioTrack t) {
        long frame = edgeFrame;
        edgeFrame = -1;
        String how;
        long audibleNs;
        if (t.getTimestamp(ts) && ts.framePosition > 0) {
            audibleNs = ts.nanoTime + (frame - ts.framePosition) * 1000000000L / SR;
            how = "ts";
        } else {
            long head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
            audibleNs = System.nanoTime() + (frame - head) * 1000000000L / SR;
            how = "est";
        }
        long ms = (audibleNs - edgeDecidedNs) / 1000000L;
        Telemetry.event("audio[" + tag + "] " + (edgeOn ? "ON" : "off") + " audible +"
                + ms + "ms (" + how + ")");
    }

    private void fill(short[] out) {
        int snd = sound;
        boolean on = sounding;
        boolean watch = edgePending && edgeOn == on;
        if (watch) edgePending = false;

        double cycleHz;                 // repetitions per second
        switch (snd) {
            case FAST:   cycleHz = 4.0; break;   // beep-beep-beep
            case HILO:   cycleHz = 1.0; break;   // two-tone, 0.5 s each
            case YELP:   cycleHz = 3.0; break;   // rising sweep, repeated
            case TRIPLE: cycleHz = 1.0; break;   // three short beeps, pause
            default:     cycleHz = 1.0; break;   // steady: cycle is irrelevant
        }
        double step = cycleHz / SR;
        double slew = 0.04 * 22050.0 / SR;   // ~1 ms whatever the rate

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
            // The first frame that carries the change: for "on" that is the
            // first open gate (a beep pattern may start in a gap), for "off"
            // the first frame after the switch.
            if (watch && (on ? open : true)) {
                edgeFrame = written + i;
                watch = false;
            }
            double aim = (on && open) ? LEVEL : 0.0;
            gain += (aim - gain) * slew;

            tonePhase += 2.0 * Math.PI * f / SR;
            if (tonePhase > 2.0 * Math.PI) tonePhase -= 2.0 * Math.PI;

            double v = Math.tanh(DRIVE * Math.sin(tonePhase)) / NORM;
            out[i] = (short) (v * gain * 32767.0);
        }
    }
}
