package il.co.embeddit.btviewer;

import java.util.ArrayList;
import java.util.List;

/**
 * An on-screen log.
 *
 * Three fixes in a row produced no observable change, which meant the reports
 * coming back could not distinguish "the fix did not work" from "the build never
 * installed" from "the callback never fired". Guessing again would have been the
 * fourth. This makes the failure state readable without a cable.
 */
public final class Diag {

    public static final String BUILD = "v24";

    private static final int MAX = 120;
    private static final List<String> lines = new ArrayList<String>();
    private static long t0 = System.currentTimeMillis();

    public interface Sink { void onDiag(); }
    private static volatile Sink sink;

    public static void bind(Sink s) { sink = s; }

    private static final android.os.Handler UI =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private static final Runnable NOTIFY = new Runnable() {
        public void run() {
            Sink s = sink;
            if (s != null) s.onDiag();
        }
    };

    /**
     * Callable from any thread.
     *
     * GATT callbacks arrive on a binder thread, and the previous version called
     * the sink straight through to View.setText from there. Once the log panel
     * was on screen that threw CalledFromWrongThreadException inside the receive
     * path, before the incoming line had been queued — so every packet died in
     * the logger and the counter that would have stopped the logging never
     * advanced. Binder swallows the exception, so it failed silently and looked
     * exactly like a peripheral that was sending nothing.
     *
     * Instrumentation must never sit in the path it is instrumenting.
     */
    public static void log(String s) {
        synchronized (Diag.class) {
            long ms = System.currentTimeMillis() - t0;
            lines.add(String.format(java.util.Locale.US, "%5.1fs  %s", ms / 1000f, s));
            while (lines.size() > MAX) lines.remove(0);
        }
        Telemetry.event("diag: " + s);
        UI.removeCallbacks(NOTIFY);
        UI.post(NOTIFY);
    }

    public static synchronized void mark() { t0 = System.currentTimeMillis(); }

    public static synchronized String dump() {
        StringBuilder b = new StringBuilder();
        for (int i = lines.size() - 1; i >= 0; i--) b.append(lines.get(i)).append('\n');
        return b.toString();
    }

    public static synchronized void clear() { lines.clear(); }
}
