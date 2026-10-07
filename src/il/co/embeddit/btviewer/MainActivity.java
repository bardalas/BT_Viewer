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
    private long lastReadingAt;
    private boolean alerting;

    // ------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        cfg = Config.load(this);
        alerter = new Alerter();
        ble = new BleLink(this, this);
        demo = new DemoSource(this);
        setContentView(build());
        Diag.bind(this);
        askPermissions();
        render();
        ui.postDelayed(watchdog, 400);
    }

    @Override protected void onResume() {
        super.onResume();
        cfg = Config.load(this);
        altitude.bind(cfg);
        if (cfg.demoMode && !demo.isRunning()) startDemo();
        if (!cfg.demoMode && demo.isRunning()) stopDemo();
        render();
    }

    @Override protected void onStop() {
        super.onStop();
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
        title = text("חיישנים", 22, Ui.INK, Ui.LABEL);
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

        title.setText(live ? "" : "חיישנים");
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
        lastReadingAt = System.currentTimeMillis();
        if (cm < 0) { reading.setText("–.––"); altitude.clear(); return; }
        boolean was = alerting;
        alerting = alerter.onReading(cm, cfg);
        reading.setText(Ui.metres(cm));
        altitude.set(cm, alerting);
        if (was != alerting) render();
    }

    @Override public void onState(int s) {
        if (demo.isRunning()) return;
        if (s != BleLink.CONNECTED) {
            alerter.reset();
            alerting = false;
            altitude.clear();
            reading.setText("–.––");
            lastReadingAt = 0;
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
                    a.alerter.reset();
                    a.alerting = false;
                    a.render();
                }
                a.reading.setText("–.––");
                a.altitude.clear();
            }
            a.ui.postDelayed(this, 400);
        }
    }

    private void startDemo() {
        ble.stopScan();
        demo.start(cfg.scaleCm());
        render();
    }

    private void stopDemo() {
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
