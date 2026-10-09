package il.co.embeddit.btviewer;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Remote field log. The app is used where we are not, so what it saw is
 * batched and inserted as a row of the Supabase table "telemetry" every minute
 * and when the app goes to the background.
 *
 * Every reading is one compact line, "t cm alert lag", where t is ms since the
 * session started and lag is how long the reading waited on the main thread
 * after the radio delivered it. Events are lines starting with '#'. Nothing
 * personal is sent: a random session id, phone model, Android and app version.
 *
 * Never blocks the caller and never throws into it. If the post fails the
 * lines stay buffered (capped) and go with the next batch.
 */
final class Telemetry {
    private Telemetry() { }

    /**
     * Supabase project and its public (anon / publishable) key. Public by
     * design: the table's row-level security lets this key INSERT and nothing
     * else, so the APK cannot read or change anyone's logs.
     */
    static final String SUPABASE_URL = "https://gnlrnsvkupifmsjyyual.supabase.co";
    static final String SUPABASE_KEY = "sb_publishable_cAmKGGFKQLCWw4Xw97J9ig_Yhq6v7JP";
    private static final long PERIOD_MS = 60000;
    private static final int MAX_CHARS = 400000;

    private static final StringBuilder buf = new StringBuilder();
    private static final long t0 = SystemClock.elapsedRealtime();
    private static final String sid = Long.toHexString(new java.util.Random().nextLong());
    private static String app = "?";
    private static int seq;
    private static boolean started, sending;
    private static volatile String installId = "", deviceName = "";

    /** Which phone this is, sent with every batch. Name may change in settings. */
    static void setIdentity(String id, String name) {
        installId = id;
        deviceName = name == null ? "" : name.trim();
    }

    private static final android.os.Handler UI =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static volatile Runnable statsHook;
    private static volatile String lastError;

    /** Called on the main thread just before each periodic batch. */
    static void setStatsHook(Runnable r) { statsHook = r; }

    private static final Runnable TICK = new Runnable() {
        public void run() {
            Runnable h = statsHook;
            if (h != null) {
                try { h.run(); } catch (Exception e) { event("stats failed " + e); }
            }
            flush();
            UI.postDelayed(this, PERIOD_MS);
        }
    };

    static void start(Context c) {
        if (started) return;
        started = true;
        app = Updater.versionName(c);
        event("start " + Build.MANUFACTURER + " " + Build.MODEL + " android " + Build.VERSION.RELEASE);
        UI.postDelayed(TICK, PERIOD_MS);
    }

    static long now() { return SystemClock.elapsedRealtime() - t0; }

    static void reading(float cm, boolean alert, long lagMs) {
        add(now() + " " + Math.round(cm) + " " + (alert ? 1 : 0) + " " + lagMs);
    }

    static void event(String s) { add("# " + now() + " " + s); }

    private static synchronized void add(String line) {
        if (buf.length() > MAX_CHARS) buf.delete(0, buf.indexOf("\n", buf.length() / 4) + 1);
        buf.append(line).append('\n');
    }

    /** Post what is buffered, on a background thread. */
    static void flush() {
        final String body;
        final int n;
        final int mySeq;
        if (SUPABASE_URL.length() == 0) return;
        synchronized (Telemetry.class) {
            if (sending || buf.length() == 0) return;
            sending = true;
            n = buf.length();
            body = buf.toString();
            mySeq = ++seq;
        }
        new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                try {
                    JSONObject j = new JSONObject();
                    j.put("sid", sid);
                    j.put("seq", mySeq);
                    j.put("app", app);
                    // "<name> [id] model / Android n": the id is fixed per
                    // install, the name is whatever was typed in settings.
                    j.put("device", (deviceName.length() > 0 ? deviceName + " " : "")
                            + "[" + installId + "] " + Build.MANUFACTURER + " " + Build.MODEL
                            + " / Android " + Build.VERSION.RELEASE);
                    j.put("t_ms", now());
                    j.put("log", body);
                    byte[] bytes = j.toString().getBytes("UTF-8");
                    HttpURLConnection h = (HttpURLConnection)
                            new URL(SUPABASE_URL + "/rest/v1/telemetry").openConnection();
                    h.setConnectTimeout(10000);
                    h.setReadTimeout(15000);
                    h.setDoOutput(true);
                    h.setRequestMethod("POST");
                    h.setRequestProperty("Content-Type", "application/json");
                    h.setRequestProperty("Prefer", "return=minimal");
                    h.setRequestProperty("apikey", SUPABASE_KEY);
                    // Legacy anon keys are JWTs and also go in Authorization;
                    // the newer sb_publishable_ keys must not.
                    if (SUPABASE_KEY.startsWith("eyJ")) {
                        h.setRequestProperty("Authorization", "Bearer " + SUPABASE_KEY);
                    }
                    h.setFixedLengthStreamingMode(bytes.length);
                    OutputStream o = h.getOutputStream();
                    o.write(bytes);
                    o.close();
                    int code = h.getResponseCode();
                    ok = code / 100 == 2;
                    if (!ok) lastError = "http " + code;
                    h.disconnect();
                } catch (Exception e) {
                    // offline: keep the lines, try next time
                    lastError = e.getClass().getSimpleName();
                }
                synchronized (Telemetry.class) {
                    if (ok) buf.delete(0, Math.min(n, buf.length()));
                    sending = false;
                }
                if (!ok) {
                    event("telemetry send failed: " + lastError);
                } else if (lastError != null) {
                    event("telemetry recovered after: " + lastError);
                    lastError = null;
                }
            }
        }, "telemetry").start();
    }
}
