package il.co.embeddit.btviewer;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Self-update from GitHub Releases, run once each time the app opens.
 *
 * Asks the repo for its latest release, and if the tag is newer than the
 * installed versionName, streams the release's .apk straight into a
 * PackageInstaller session and commits it. Nothing is shown or asked.
 *
 * Android only installs without a prompt when this app is the installer of
 * record for itself (API 31+), so the very first update after a manual install
 * may show the system confirmation once; every later one is silent.
 */
final class Updater {
    private Updater() { }

    static final String REPO = "bardalas/BT_Viewer";
    static final String ACTION = "il.co.embeddit.btviewer.UPDATE_RESULT";

    interface Status { void onStatus(String text); }

    private static boolean busy;

    static String versionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    static void checkAndInstall(final Context app, final Status st) {
        synchronized (Updater.class) {
            if (busy) return;
            busy = true;
        }
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() {
                try {
                    Updater.run(app, st, ui);
                } catch (Exception e) {
                    Diag.log("update: " + e);   // offline etc: stay quiet, retry next open
                } finally {
                    synchronized (Updater.class) { busy = false; }
                }
            }
        }, "updater").start();
    }

    private static void say(final Status st, Handler ui, final String s) {
        ui.post(new Runnable() { public void run() { st.onStatus(s); } });
    }

    private static void run(Context c, Status st, Handler ui) throws Exception {
        HttpURLConnection h = open("https://api.github.com/repos/" + REPO + "/releases/latest");
        h.setRequestProperty("Accept", "application/vnd.github+json");
        if (h.getResponseCode() != 200) return;     // 404 == no release yet
        JSONObject rel = new JSONObject(readAll(h.getInputStream()));
        String tag = rel.optString("tag_name", "");
        String cur = versionName(c);
        if (!isNewer(tag, cur)) return;

        String url = null;
        JSONArray assets = rel.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.optString("name").endsWith(".apk")) {
                url = a.getString("browser_download_url");
                break;
            }
        }
        if (url == null) return;
        Telemetry.event("update " + cur + " -> " + tag);

        say(st, ui, "מעדכן ל-" + tag.replaceFirst("^[vV]", "") + "…");
        install(c, url);
    }

    private static void install(Context c, String url) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (Build.VERSION.SDK_INT >= 31) {
            sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(sp);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            HttpURLConnection h = open(url);
            if (h.getResponseCode() != 200) throw new Exception("download http " + h.getResponseCode());
            InputStream in = h.getInputStream();
            OutputStream out = s.openWrite("update", 0, h.getContentLengthLong());
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            s.fsync(out);
            out.close();
            in.close();

            Intent i = new Intent(c, UpdateReceiver.class).setAction(ACTION);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(c, id, i, flags).getIntentSender());
        } catch (Exception e) {
            s.abandon();
            throw e;
        } finally {
            s.close();
        }
    }

    private static HttpURLConnection open(String u) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(30000);
        h.setRequestProperty("User-Agent", "BTViewer");
        return h;
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }

    /** Dotted numeric compare, ignoring a leading "v": 5.10 > 5.9. */
    static boolean isNewer(String tag, String cur) {
        int[] a = parts(tag), b = parts(cur);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parts(String v) {
        String[] p = v.replaceFirst("^[vV]", "").split("\\.");
        int[] r = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            try { r[i] = Integer.parseInt(p[i].replaceAll("\\D.*$", "")); } catch (Exception e) { r[i] = 0; }
        }
        return r;
    }
}
