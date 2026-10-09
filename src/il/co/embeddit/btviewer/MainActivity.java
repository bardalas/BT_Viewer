package il.co.embeddit.btviewer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements BleLink.Listener, DemoSource.Sink, Diag.Sink {

    private static final long STALE_MS = 1500;
    /** Below this the ultrasonic sensor goes blind and reports -1 (no echo). */
    private static final float BLIND_CM = 30f;

    private BleLink ble;
    private Alerter alerter;
    private Config cfg;
    private DemoSource demo;

    private LinearLayout root, listScreen, deviceBox, liveScreen;
    private TextView title, reading, unit, deviceLabel, action, emptyNote;
    private AltitudeView altitude;
    private TextView logPanel;
    private final List<BleLink.Found> devices = new ArrayList<BleLink.Found>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Watchdog watchdog = new Watchdog(this);
    private TextView version;
    private long lastReadingAt;      // any line, -1 included: the link is alive
    private long lastEchoAt;         // last real distance
    private float lastEchoCm = -1f;
    private boolean blind;           // -1 right after a reading below BLIND_CM
    private boolean alerting;
    private int shownCount;          // readings processed since the last stats
    private final FrameWatch frames = new FrameWatch();

    /**
     * Logs any frame that took over 100 ms: the main thread was stuck, so the
     * screen (and the alert decision, which runs here too) was late.
     */
    private static class FrameWatch implements android.view.Choreographer.FrameCallback {
        private long last;
        private boolean on;
        public void doFrame(long ns) {
            if (on && last > 0 && ns - last > 100000000L) {
                Telemetry.event("ui stall " + (ns - last) / 1000000L + "ms");
            }
            last = ns;
            on = true;
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
        void stop() {
            on = false;
            last = 0;
            android.view.Choreographer.getInstance().removeFrameCallback(this);
        }
    }

    /** Periodic health line: link, display and audio side by side. */
    private static class Stats implements Runnable {
        private final MainActivity a;
        Stats(MainActivity act) { a = act; }
        public void run() {
            Telemetry.event("stats " + a.ble.stats() + " shown=" + a.shownCount
                    + " demo=" + a.demo.isRunning() + " " + a.alerter.stats()
                    + " powerSave=" + a.powerSave());
            a.shownCount = 0;
        }
    }

    private boolean powerSave() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isPowerSaveMode();
    }

    /** Long-press anywhere on the live screen: "it just happened". */
    private static class Mark implements View.OnLongClickListener {
        private final MainActivity a;
        Mark(MainActivity act) { a = act; }
        public boolean onLongClick(View v) {
            Telemetry.event("USER MARK cm=" + Math.round(a.lastEchoCm) + " alert=" + a.alerting
                    + " " + a.alerter.stats());
            try {
                android.os.Vibrator vib = (android.os.Vibrator) a.getSystemService(VIBRATOR_SERVICE);
                if (vib != null) vib.vibrate(android.os.VibrationEffect.createOneShot(80,
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } catch (Exception ignored) { }
            Toast.makeText(a, "\u05e1\u05d5\u05de\u05df \u2713", Toast.LENGTH_SHORT).show();
            // Send soon, with the seconds after the mark included.
            a.ui.postDelayed(new Runnable() { public void run() { Telemetry.flush(); } }, 10000);
            return true;
        }
    }

    /** Shows the installed version, and "updating to x" while a release installs. */
    private static class VersionStatus implements Updater.Status {
        private final MainActivity a;
        VersionStatus(MainActivity act) { a = act; }
        public void onStatus(String s) {
            a.version.setText("v" + Updater.versionName(a) + "  ·  " + s);
        }
    }

    // ------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        cfg = Config.load(this);
        Beeper.init(this);
        alerter = new Alerter();
        ble = new BleLink(this, this);
        demo = new DemoSource(this);
        setContentView(build());
        Diag.bind(this);
        askPermissions();
        render();
        Updater.checkAndInstall(getApplicationContext(), new VersionStatus(this));
        Telemetry.setIdentity(cfg.installId, cfg.deviceName);
        Telemetry.start(getApplicationContext());
        Telemetry.setStatsHook(new Stats(this));
        ui.postDelayed(watchdog, 400);
    }

    @Override protected void onResume() {
        super.onResume();
        cfg = Config.load(this);
        Telemetry.setIdentity(cfg.installId, cfg.deviceName);
        Telemetry.event("resume cfg thr=" + cfg.thresholdCm + " hyst=" + cfg.hystCm
                + " sound=" + cfg.sound + " demo=" + cfg.demoMode + " powerSave=" + powerSave()
                + " " + alerter.stats());
        android.view.Choreographer.getInstance().postFrameCallback(frames);
        altitude.bind(cfg);
        if (cfg.demoMode && !demo.isRunning()) startDemo();
        if (!cfg.demoMode && demo.isRunning()) stopDemo();
        render();
    }

    @Override protected void onPause() {
        super.onPause();
        Telemetry.event("pause");
        frames.stop();
    }

    @Override protected void onStop() {
        super.onStop();
        Telemetry.event("stop");
        Telemetry.flush();
        ble.stopScan();
        alerter.silence();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(watchdog);
        ble.disconnect();
        demo.stop();
        alerter.release();
    }

    private String[] perms() {
        return Build.VERSION.SDK_INT >= 31
                ? new String[]{"android.permission.BLUETOOTH_SCAN",
                               "android.permission.BLUETOOTH_CONNECT",
                               "android.permission.ACCESS_FINE_LOCATION"}
                : new String[]{"android.permission.ACCESS_FINE_LOCATION"};
    }

    private boolean hasPerms() {
        for (String p : perms()) {
            if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void askPermissions() { requestPermissions(perms(), 1); }

    @Override public void onRequestPermissionsResult(int c, String[] p, int[] g) {
        super.onRequestPermissionsResult(c, p, g);
        if (pendingScan && hasPerms()) { pendingScan = false; ble.startScan(); render(); }
    }

    private boolean pendingScan;
    private boolean showAll;
    private int hidden;
    private int dp(float v) { return Ui.dp(this, v); }

    // --------------------------------------------------------------- layout

    private TextView text(String s, float size, int color, android.graphics.Typeface f) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setTypeface(f);
        return t;
    }

    private View build() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(dp(22), dp(20), dp(22), dp(24));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        title = text("חיישנים · BT", 22, Ui.INK, Ui.LABEL);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView gear = text("\u2699", 15, Ui.MUTED, Ui.LABEL);
        gear.setGravity(Gravity.CENTER);
        gear.setWidth(dp(40)); gear.setHeight(dp(40));
        gear.setBackground(Ui.card(this, 20, Ui.BG, Ui.LINE));
        gear.setClickable(true);
        gear.setOnClickListener(new Tap(this, Tap.CONFIG));
        head.addView(title);
        head.addView(gear);
        root.addView(head);
        version = text("v" + Updater.versionName(this), 11, Ui.MUTED, Ui.DIGITS);
        root.addView(version);

        // --- device list
        listScreen = new LinearLayout(this);
        listScreen.setOrientation(LinearLayout.VERTICAL);
        listScreen.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        deviceBox = new LinearLayout(this);
        deviceBox.setOrientation(LinearLayout.VERTICAL);
        deviceBox.setPadding(0, dp(22), 0, 0);
        emptyNote = text("", 14, Ui.MUTED, Ui.LABEL);
        emptyNote.setGravity(Gravity.CENTER);
        emptyNote.setPadding(0, dp(50), 0, 0);
        listScreen.addView(deviceBox);
        root.addView(listScreen);

        // --- live readout
        liveScreen = new LinearLayout(this);
        liveScreen.setOrientation(LinearLayout.VERTICAL);
        liveScreen.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        deviceLabel = text("", 13, Ui.MUTED, Ui.LABEL);
        reading = text("–.––", 92, Ui.INK, Ui.DIGITS_BOLD);
        reading.setGravity(Gravity.CENTER);
        reading.setIncludeFontPadding(false);
        reading.setPadding(0, dp(18), 0, 0);
        reading.setClickable(true);
        reading.setOnClickListener(new Tap(this, Tap.LOG));
        unit = text("מטר", 16, Ui.MUTED, Ui.LABEL);
        unit.setGravity(Gravity.CENTER);
        unit.setPadding(0, dp(6), 0, dp(20));
        altitude = new AltitudeView(this);
        altitude.bind(cfg);
        altitude.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        Mark mark = new Mark(this);
        altitude.setOnLongClickListener(mark);
        reading.setOnLongClickListener(mark);
        liveScreen.setOnLongClickListener(mark);
        liveScreen.addView(deviceLabel);
        liveScreen.addView(reading);
        liveScreen.addView(unit);
        logPanel = text("", 10, Ui.MUTED, Ui.DIGITS);
        logPanel.setBackground(Ui.card(this, 12, Ui.CARD, Ui.LINE));
        logPanel.setPadding(dp(10), dp(8), dp(10), dp(8));
        logPanel.setVisibility(View.GONE);
        liveScreen.addView(logPanel);
        liveScreen.addView(altitude);
        root.addView(liveScreen);

        action = text("סרוק", 17, Ui.ACC, Ui.LABEL);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(16), dp(20), dp(16), dp(20));
        action.setClickable(true);
        action.setOnClickListener(new Tap(this, Tap.ACTION));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = dp(18);
        action.setLayoutParams(ap);
        root.addView(action);
        return root;
    }

    // --------------------------------------------------------------- render

    @Override public void onDiag() {
        if (logPanel != null && logPanel.getVisibility() == View.VISIBLE) {
            logPanel.setText(Diag.dump());
        }
    }

    private boolean liveMode() {
        return demo.isRunning() || ble.state() == BleLink.CONNECTED;
    }

    private void render() {
        boolean live = liveMode();
        listScreen.setVisibility(live ? View.GONE : View.VISIBLE);
        liveScreen.setVisibility(live ? View.VISIBLE : View.GONE);

        int bg = alerting ? Ui.ALERT : Ui.BG;
        int ink = alerting ? Ui.WHITE : Ui.INK;
        root.setBackgroundColor(bg);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(alerting ? 0
                : (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));

        title.setText(live ? "" : "חיישנים · BT");
        title.setVisibility(live ? View.GONE : View.VISIBLE);
        deviceLabel.setText(demo.isRunning() ? "הדגמה" : ble.targetName());
        deviceLabel.setTextColor(Ui.fade(ink, 140));
        reading.setTextColor(ink);
        unit.setTextColor(Ui.fade(ink, 160));

        if (live) {
            action.setText(demo.isRunning() ? "צא מהדגמה" : "נתק");
            action.setTextColor(ink);
            action.setBackground(Ui.card(this, 18, 0x00000000, Ui.fade(ink, 90)));
        } else if (ble.state() == BleLink.SCANNING) {
            action.setText("עצור");
            action.setTextColor(Ui.ACC);
            action.setBackground(Ui.card(this, 18, Ui.over(Ui.ACC, Ui.BG, 0.10f), Ui.ACC));
        } else if (ble.state() == BleLink.CONNECTING) {
            action.setText("בטל");
            action.setTextColor(Ui.ALERT);
            action.setBackground(Ui.card(this, 18, Ui.over(Ui.ALERT, Ui.BG, 0.10f), Ui.ALERT));
        } else {
            action.setText("סרוק");
            action.setTextColor(Ui.ACC);
            action.setBackground(Ui.card(this, 18, Ui.over(Ui.ACC, Ui.BG, 0.10f), Ui.ACC));
        }
        if (!live) renderDevices();
    }

    private void renderDevices() {
        deviceBox.removeAllViews();
        boolean connecting = ble.state() == BleLink.CONNECTING;
        if (devices.isEmpty()) {
            emptyNote.setText(ble.state() == BleLink.SCANNING ? "מחפש\u2026" : "");
            deviceBox.addView(emptyNote);
            addShowAll();
            return;
        }
        for (int i = 0; i < devices.size(); i++) {
            BleLink.Found f = devices.get(i);
            boolean isTarget = f.address.equals(ble.targetAddress());
            boolean sel = connecting && isTarget;
            boolean dim = connecting && !isTarget;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(20), dp(18), dp(20), dp(18));
            row.setBackground(Ui.card(this, 18,
                    sel ? Ui.ACC : Ui.CARD, sel ? Ui.ACC : Ui.LINE));
            row.setAlpha(dim ? 0.4f : 1f);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.bottomMargin = dp(12);
            row.setLayoutParams(rp);
            if (!connecting) {
                row.setClickable(true);
                row.setOnTouchListener(new Press(row));
                row.setOnClickListener(new Pick(this, i));
            }

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            boolean noName = f.name == null || f.name.trim().isEmpty();
            col.addView(text(noName ? "ללא שם" : f.name, 20,
                    sel ? Ui.WHITE : Ui.INK, Ui.LABEL));
            TextView ad = text(f.address, 12,
                    sel ? Ui.over(Ui.WHITE, Ui.ACC, 0.7f) : Ui.MUTED, Ui.DIGITS);
            ad.setPadding(0, dp(4), 0, 0);
            col.addView(ad);
            row.addView(col);

            if (sel) {
                row.addView(text("מתחבר\u2026", 13, Ui.WHITE, Ui.LABEL));
            } else {
                SignalBars bars = new SignalBars(this, f.rssi, dim);
                bars.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(22)));
                row.addView(bars);
            }
            deviceBox.addView(row);
        }
        addShowAll();
    }

    /**
     * An escape hatch, and only when it is needed. A bridge that advertises
     * neither FFE0 nor a known name would otherwise be invisible, which is the
     * failure that cost hours earlier - but it stays one quiet line, not a list
     * of everything in the building.
     */
    private void addShowAll() {
        if (showAll || hidden == 0 || ble.state() == BleLink.CONNECTING) return;
        TextView t = text("הצג את כל המכשירים (" + hidden + ")", 13, Ui.MUTED, Ui.LABEL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(18), dp(12), dp(12));
        t.setClickable(true);
        t.setOnClickListener(new Tap(this, Tap.SHOW_ALL));
        deviceBox.addView(t);
    }

    // ---------------------------------------------------------------- data

    @Override public void onDemoLine(String line) { onLine(line); }

    @Override public void onLine(String line) {
        float cm;
        try {
            cm = Float.parseFloat(line.trim());
        } catch (NumberFormatException e) {
            // Visible again. The minimal rewrite dropped this, so a line in the
            // wrong format looked exactly like no data at all - and the two
            // need opposite fixes.
            Diag.log("unparsable: \"" + line + "\"");
            reading.setText("?");
            deviceLabel.setText("התקבל: " + line);
            return;
        }
        long now = System.currentTimeMillis();
        lastReadingAt = now;
        long lag = demo.isRunning() ? 0
                : Math.max(0, android.os.SystemClock.elapsedRealtime() - ble.lineAt);
        if (cm < 0) {
            // No echo. The old code cleared the picture here but left the tone
            // running, so screen and sound disagreed for as long as the echo was
            // missing - reported as a 1-2 s lag that demo mode (which never
            // sends -1) could not reproduce. Sound and picture now always move
            // together.
            if (blind || (lastEchoCm >= 0 && lastEchoCm < BLIND_CM)) {
                // Just came from very low: the sensor is blind, not the sky empty.
                blind = true;
                cm = 0f;
            } else {
                // Hold the last state, both of them; the watchdog clears it if
                // the echo stays away.
                reading.setText("–.––");
                Telemetry.reading(-1, alerting, lag);
                return;
            }
        } else {
            blind = false;
            lastEchoAt = now;
            lastEchoCm = cm;
        }
        boolean was = alerting;
        alerting = alerter.onReading(cm, cfg);
        reading.setText(blind ? "<" + Ui.shortMetres(BLIND_CM) : Ui.metres(cm));
        altitude.set(cm, alerting);
        Telemetry.reading(blind ? -1 : cm, alerting, lag);
        shownCount++;
        if (was != alerting) {
            Telemetry.event((alerting ? "ALERT ON" : "alert off") + " cm=" + Math.round(cm)
                    + (blind ? " blind" : "") + " audioQ=" + alerter.queuedMs() + "ms");
            render();
        }
    }

    @Override public void onState(int s) {
        if (demo.isRunning()) return;
        Telemetry.event("ble state=" + s + " " + ble.targetName());
        if (s != BleLink.CONNECTED) {
            alerter.reset();
            alerting = false;
            altitude.clear();
            reading.setText("–.––");
            lastReadingAt = 0;
            lastEchoAt = 0;
            lastEchoCm = -1f;
            blind = false;
        }
        render();
    }

    @Override public void onDevices(List<BleLink.Found> list) {
        // Filter always, not only once a sensor turns up. The old fallback
        // showed everything until the first match arrived, so the list was full
        // of neighbours and then snapped to one entry.
        devices.clear();
        hidden = 0;
        for (BleLink.Found f : list) {
            if (f.likely || showAll) devices.add(f); else hidden++;
        }
        render();
    }

    @Override public void onScanProblem(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        render();
    }

    /** Silence the alert if the readings stop; a held tone must mean live data. */
    private static class Watchdog implements Runnable {
        private final MainActivity a;
        Watchdog(MainActivity act) { a = act; }
        public void run() {
            if (a.liveMode() && a.lastReadingAt > 0
                    && System.currentTimeMillis() - a.lastReadingAt > STALE_MS) {
                if (a.alerting) {
                    Telemetry.event("watchdog: no data, alert off");
                    a.alerter.reset();
                    a.alerting = false;
                    a.render();
                }
                a.reading.setText("–.––");
                a.altitude.clear();
            } else if (a.liveMode() && !a.blind && a.lastEchoAt > 0
                    && System.currentTimeMillis() - a.lastEchoAt > STALE_MS) {
                // Link alive but no echo for a while, and not because we are on
                // the ground: no distance to show, so no alert either.
                Telemetry.event("watchdog: no echo 1.5s" + (a.alerting ? ", alert off" : ""));
                if (a.alerting) {
                    a.alerter.reset();
                    a.alerting = false;
                    a.render();
                }
                a.reading.setText("–.––");
                a.altitude.clear();
                a.lastEchoAt = 0;
            }
            a.ui.postDelayed(this, 400);
        }
    }

    private void startDemo() {
        ble.stopScan();
        Telemetry.event("demo start");
        demo.start(cfg.scaleCm());
        render();
    }

    private void stopDemo() {
        Telemetry.event("demo stop");
        demo.stop();
        cfg.demoMode = false;
        alerter.reset();
        alerting = false;
        altitude.clear();
        render();
    }

    // ----------------------------------------------------------- listeners

    private static class SignalBars extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int rssi;
        private final boolean dim;
        SignalBars(android.content.Context c, int r, boolean d) { super(c); rssi = r; dim = d; }
        @Override protected void onDraw(Canvas cv) {
            int lvl = rssi > -55 ? 4 : rssi > -70 ? 3 : rssi > -85 ? 2 : 1;
            float bw = getWidth() / 7f;
            for (int k = 0; k < 4; k++) {
                float h = getHeight() * (0.28f + 0.24f * k);
                p.setColor(k < lvl ? (dim ? Ui.MUTED : Ui.INK) : Ui.LINE);
                cv.drawRoundRect(k * bw * 1.75f, getHeight() - h,
                        k * bw * 1.75f + bw, getHeight(), bw / 2f, bw / 2f, p);
            }
        }
    }

    private static class Press implements View.OnTouchListener {
        private final View v;
        Press(View target) { v = target; }
        public boolean onTouch(View view, MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) v.setAlpha(0.6f);
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) v.setAlpha(1f);
            return false;
        }
    }

    private static class Pick implements View.OnClickListener {
        private final MainActivity a;
        private final int index;
        Pick(MainActivity act, int i) { a = act; index = i; }
        public void onClick(View v) {
            if (index >= a.devices.size()) return;
            BleLink.Found f = a.devices.get(index);
            a.cfg.lastAddress = f.address;
            a.cfg.lastName = f.name == null || f.name.trim().isEmpty() ? f.address : f.name;
            a.cfg.save(a);
            a.ble.connect(f.address, a.cfg.lastName);
            a.render();
        }
    }

    private static class Tap implements View.OnClickListener {
        static final int ACTION = 0, CONFIG = 1, SHOW_ALL = 2, LOG = 3;
        private final MainActivity a;
        private final int what;
        Tap(MainActivity act, int w) { a = act; what = w; }
        public void onClick(View v) {
            if (what == SHOW_ALL) {
                a.showAll = true;
                a.onDevices(new java.util.ArrayList<BleLink.Found>(a.ble.devicesSeen()));
                return;
            }
            if (what == LOG) {
                boolean show = a.logPanel.getVisibility() != View.VISIBLE;
                a.logPanel.setVisibility(show ? View.VISIBLE : View.GONE);
                if (show) a.logPanel.setText(Diag.dump());
                return;
            }
            if (what == CONFIG) {
                a.startActivity(new Intent(a, ConfigActivity.class));
                return;
            }
            if (a.demo.isRunning()) { a.stopDemo(); return; }
            int st = a.ble.state();
            if (st == BleLink.CONNECTED || st == BleLink.CONNECTING) { a.ble.disconnect(); a.render(); return; }
            if (st == BleLink.SCANNING) { a.ble.stopScan(); a.render(); return; }
            if (!a.ble.bluetoothReady()) {
                Toast.makeText(a, "הפעל Bluetooth", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!a.hasPerms()) { a.pendingScan = true; a.askPermissions(); return; }
            a.showAll = false;
            a.ble.startScan();
            a.render();
        }
    }
}
