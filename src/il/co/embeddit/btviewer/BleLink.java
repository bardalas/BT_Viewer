package il.co.embeddit.btviewer;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** GATT client for an HM-10 style serial peripheral (service FFE0, char FFE1). */
@SuppressWarnings("MissingPermission")
public class BleLink {

    public static final UUID SERVICE_UUID =
            UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_UUID =
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final int IDLE = 0, SCANNING = 1, CONNECTING = 2, CONNECTED = 3, FAILED = 4;

    /** How long a connect attempt may hang before we call it a failure. */
    private static final long CONNECT_TIMEOUT_MS = 12000;
    /** Scanning forever drains the battery and grows a list nobody reads. */
    private static final long SCAN_TIMEOUT_MS = 20000;

    public static class Found {
        public final String name, address;
        public final boolean likely;   // advertises FFE0, or is named like a bridge
        public final int rssi;
        Found(String n, String a, boolean l, int r) { name = n; address = a; likely = l; rssi = r; }
    }

    /** HC-08, HM-10, AT-09, JDY and friends. Name matching is a hint, not a gate. */
    private static boolean looksLikeBridge(String name, ScanResult r) {
        if (r.getScanRecord() != null && r.getScanRecord().getServiceUuids() != null) {
            for (android.os.ParcelUuid u : r.getScanRecord().getServiceUuids()) {
                String s = u.getUuid().toString().toLowerCase(java.util.Locale.US);
                if (s.startsWith("0000ffe0")) return true;
            }
        }
        if (name == null) return false;
        String n = name.toUpperCase(java.util.Locale.US);
        // Prefixes only. Matching anything containing "BT" or "BLE" swept in
        // unrelated devices and made the filter useless.
        return n.startsWith("HC-") || n.startsWith("HM-") || n.startsWith("HMSOFT")
                || n.startsWith("AT-") || n.startsWith("JDY") || n.startsWith("BT0")
                || n.startsWith("SPP");
    }

    public interface Listener {
        void onState(int state);
        void onDevices(List<Found> devices);
        void onLine(String line);
        void onScanProblem(String message);
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Found> found = new ArrayList<>();
    private final StringBuilder rx = new StringBuilder();

    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private boolean scanning;
    private boolean userInitiatedDisconnect;
    private boolean retried;
    private String targetName = "", targetAddress = "";
    private String failure = "";
    private final Runnable connectTimeout = new ConnectTimeout(this);
    private final Runnable scanTimeout = new ScanTimeout(this);
    private java.util.UUID dataChar = CHAR_UUID;
    private int seenTotal;
    private int rxCount;
    /** When the newest line arrived from the radio (elapsedRealtime ms). */
    volatile long lineAt;
    // Since the last stats(): lines received (before newest-wins coalescing),
    // notifications, and the longest silence between two lines.
    private int statLines, statPackets;
    private long statMaxGap;

    synchronized String stats() {
        String r = "ble lines=" + statLines + " packets=" + statPackets
                + " maxGap=" + statMaxGap + "ms";
        statLines = 0; statPackets = 0; statMaxGap = 0;
        return r;
    }

    private synchronized void countLine(long now) {
        if (lineAt > 0) statMaxGap = Math.max(statMaxGap, now - lineAt);
        statLines++;
    }
    private final java.util.concurrent.atomic.AtomicReference<String> latestLine =
            new java.util.concurrent.atomic.AtomicReference<String>();
    private Runnable deliver;
    private final java.util.List<BluetoothGattCharacteristic> candidates =
            new ArrayList<BluetoothGattCharacteristic>();
    private int candidateIndex = -1;
    private final Runnable probeNext = new ProbeNext(this);
    private int state = IDLE;

    public BleLink(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) adapter = bm.getAdapter();
        deliver = new PostLine(listener, latestLine);
    }

    public boolean bluetoothReady() { return adapter != null && adapter.isEnabled(); }

    public int state() { return state; }

    /** Name of whatever we are connecting to or connected to, for the UI. */
    public String targetName() {
        if (targetName != null && !targetName.trim().isEmpty()) return targetName;
        return targetAddress;
    }

    public String targetAddress() { return targetAddress; }

    public String failure() { return failure; }

    private static class Reconnect implements Runnable {
        private final BleLink b;
        Reconnect(BleLink link) { b = link; }
        public void run() { b.connect(b.targetAddress, b.targetName); }
    }

    private static class ConnectTimeout implements Runnable {
        private final BleLink b;
        ConnectTimeout(BleLink link) { b = link; }
        public void run() {
            if (b.state != CONNECTING) return;
            Diag.log("connect timed out after " + CONNECT_TIMEOUT_MS + "ms");
            b.fail("המכשיר לא הגיב");
        }
    }

    private static class ScanTimeout implements Runnable {
        private final BleLink b;
        ScanTimeout(BleLink link) { b = link; }
        public void run() {
            if (b.state != SCANNING) return;
            Diag.log("scan window elapsed");
            b.stopScan();
        }
    }

    /** Tear down and report why, instead of sliding quietly back to idle. */
    private void fail(String why) {
        failure = why;
        Diag.log("FAILED: " + why);
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (RuntimeException ignored) { }
            gatt = null;
        }
        rx.setLength(0);
        rxCount = 0;
        ui.removeCallbacks(connectTimeout);
        setState(FAILED);
    }

    private static class PostState implements Runnable {
        private final Listener target;
        private final int s;
        PostState(Listener t, int v) { target = t; s = v; }
        public void run() { target.onState(s); }
    }

    // Static on purpose. This d8 build mis-parses a non-static inner class that
    // carries a generic Signature attribute, so the ones holding typed payloads
    // take their dependencies explicitly instead of capturing the outer scope.
    private static class PostDevices implements Runnable {
        private final Listener target;
        private final List<Found> list;
        PostDevices(Listener t, List<Found> l) { target = t; list = l; }
        public void run() { target.onDevices(list); }
    }

    private static class PostProblem implements Runnable {
        private final Listener target;
        private final String msg;
        PostProblem(Listener t, String m) { target = t; msg = m; }
        public void run() { target.onScanProblem(msg); }
    }

    /**
     * One pending post that always carries the newest reading.
     *
     * Posting every line put 100 messages a second on the main looper, each one
     * setting a 92sp TextView and redrawing the scene. The queue grew faster
     * than it drained, so the delay increased the longer it ran - and two of
     * every three readings were overwritten in the same frame without ever
     * being seen. Superseding a value that has not been drawn yet costs nothing.
     */
    private static class PostLine implements Runnable {
        private final Listener target;
        private final java.util.concurrent.atomic.AtomicReference<String> latest;
        PostLine(Listener t, java.util.concurrent.atomic.AtomicReference<String> ref) {
            target = t; latest = ref;
        }
        public void run() {
            String v = latest.getAndSet(null);
            if (v != null) target.onLine(v);
        }
    }

    private void setState(int s) {
        state = s;
        ui.post(new PostState(listener, s));
    }

    // Named rather than anonymous: an anonymous class declared in a field
    // Every nested class here is static and takes its outer object explicitly.
    // The d8 build available in this environment NPEs on a non-static inner
    // class that implements an interface, so this is a workaround, not taste.
    private static class Scan extends ScanCallback {
        private final BleLink b;
        Scan(BleLink link) { b = link; }

        /**
         * Previously unimplemented, which meant every scan failure was silent
         * and the screen went on claiming it was scanning. Android's throttle is
         * the common one: more than five scans in thirty seconds and the rest
         * are simply dropped.
         */
        @Override public void onBatchScanResults(List<ScanResult> results) {
            if (results == null) return;
            for (int i = 0; i < results.size(); i++) onScanResult(0, results.get(i));
        }

        @Override public void onScanFailed(int errorCode) {
            String why;
            switch (errorCode) {
                case ScanCallback.SCAN_FAILED_ALREADY_STARTED:
                    why = "סריקה כבר פעילה"; break;
                case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                    why = "רישום הסריקה נכשל — הפעל מחדש Bluetooth"; break;
                case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:
                    why = "המכשיר לא תומך בסריקת BLE"; break;
                case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:
                    why = "שגיאה פנימית במחסנית Bluetooth"; break;
                case 5: why = "אין משאבי חומרה פנויים"; break;
                case 6: why = "סריקות תכופות מדי — אנדרואיד חוסם. המתן 30 שניות"; break;
                default: why = "הסריקה נכשלה (קוד " + errorCode + ")";
            }
            b.scanning = false;
            Diag.log("onScanFailed code=" + errorCode + " : " + why);
            b.ui.post(new PostProblem(b.listener, why));
        }

        @Override public void onScanResult(int type, ScanResult r) {
            String name;
            try {
                name = r.getDevice().getName();
            } catch (SecurityException e) {
                // Reading the name needs BLUETOOTH_CONNECT on API 31+. Thrown
                // from a binder thread this would take the whole app down.
                Diag.log("getName SecurityException - missing BLUETOOTH_CONNECT");
                name = null;
            }
            if (name == null && r.getScanRecord() != null) name = r.getScanRecord().getDeviceName();
            // Do NOT drop nameless devices. A peripheral whose advertising packet
            // overflowed is still there, just without a name — hiding it makes a
            // firmware problem look like a radio problem.
            if (name == null || name.trim().isEmpty()) name = "";
            b.seenTotal++;
            if (b.seenTotal <= 8) {
                Diag.log("adv #" + b.seenTotal + "  " + r.getDevice().getAddress()
                        + "  rssi " + r.getRssi() + "  name=" + (name == null ? "-" : name));
            }
            String addr = r.getDevice().getAddress();
            for (int i = 0; i < b.found.size(); i++) {
                if (b.found.get(i).address.equals(addr)) return;
            }
            b.found.add(new Found(name, addr, looksLikeBridge(name, r), r.getRssi()));
            b.ui.post(new PostDevices(b.listener, new ArrayList<Found>(b.found)));
        }
    }

    private final ScanCallback scanCallback = new Scan(this);

    public int seenTotal() { return seenTotal; }

    /** Everything the radio saw, unfiltered. */
    public java.util.List<Found> devicesSeen() {
        return new ArrayList<Found>(found);
    }

    public void startScan() {
        if (adapter == null) { listener.onScanProblem("אין מתאם Bluetooth"); return; }
        if (scanning) return;
        seenTotal = 0;
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { listener.onScanProblem("Bluetooth כבוי"); return; }
        found.clear();
        listener.onDevices(new ArrayList<Found>());
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(0)
                .build();
        // Unfiltered: some HM-10 clones do not advertise the FFE0 service, so
        // filtering on it would hide exactly the devices we care about.
        //
        // null, NOT an empty list. Android documents null as "no filtering"; an
        // empty List<ScanFilter> registers a filter set that matches nothing,
        // and on several stacks that returns zero results in complete silence.
        // This is what made the scan come back empty with every check green.
        List<ScanFilter> noFilter = null;
        try {
            scanner.startScan(noFilter, settings, scanCallback);
            Diag.log("startScan issued, no filter");
        } catch (Throwable t) {
            // An exception here used to propagate off a binder thread and look
            // like anything except what it was.
            Diag.log("startScan THREW " + t.getClass().getSimpleName() + ": " + t.getMessage());
            listener.onScanProblem("startScan נכשל: " + t.getClass().getSimpleName());
            return;
        }
        scanning = true;
        failure = "";
        ui.removeCallbacks(scanTimeout);
        ui.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
        setState(SCANNING);
    }

    public void stopScan() {
        ui.removeCallbacks(scanTimeout);
        if (!scanning || adapter == null) return;
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner != null) scanner.stopScan(scanCallback);
        scanning = false;
        if (state == SCANNING) setState(IDLE);
    }

    public void connect(String address, String name) {
        stopScan();
        if (adapter == null) return;
        BluetoothDevice dev = adapter.getRemoteDevice(address);
        if (dev == null) return;
        targetAddress = address;
        targetName = name == null ? "" : name;
        failure = "";
        userInitiatedDisconnect = false;
        Diag.log("connecting to " + targetName() + " " + address);
        setState(CONNECTING);
        gatt = dev.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        ui.removeCallbacks(connectTimeout);
        ui.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    /** Explicit user action. Separate from a link that fell over by itself. */
    public void disconnect() {
        userInitiatedDisconnect = true;
        failure = "";
        ui.removeCallbacks(connectTimeout);
        ui.removeCallbacks(probeNext);
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        rx.setLength(0);
        rxCount = 0;
        setState(IDLE);
    }

    private static class Gatt extends BluetoothGattCallback {
        private final BleLink b;
        Gatt(BleLink link) { b = link; }


        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            Diag.log("conn state=" + newState + " status=" + status);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                boolean ok = g.discoverServices();
                Diag.log("discoverServices=" + ok);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                b.rx.setLength(0);
                b.setState(IDLE);
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            Diag.log("servicesDiscovered status=" + status
                    + " count=" + g.getServices().size());
            BluetoothGattCharacteristic ch = null;
            if (g.getService(SERVICE_UUID) != null) {
                ch = g.getService(SERVICE_UUID).getCharacteristic(CHAR_UUID);
            }
            // Fall back to any notifying characteristic. HM-10, HC-08, AT-09 and
            // the JDY parts mostly agree on FFE0/FFE1, but not all of them, and
            // hard-coding one pair turns a working module into a dead connection
            // with no explanation. A serial bridge has exactly one notify
            // characteristic, so taking the first is unambiguous in practice.
            if (ch == null) ch = findNotifying(g);
            for (android.bluetooth.BluetoothGattService svc : g.getServices()) {
                Diag.log("svc " + shortUuid(svc.getUuid()));
                for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                    Diag.log("   chr " + shortUuid(c.getUuid()) + " props=0x"
                            + Integer.toHexString(c.getProperties()));
                }
            }
            b.candidates.clear();
            if (ch != null) b.candidates.add(ch);
            for (android.bluetooth.BluetoothGattService svc : g.getServices()) {
                for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                    if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) continue;
                    if (ch != null && c.getUuid().equals(ch.getUuid())) continue;
                    b.candidates.add(c);
                }
            }
            if (b.candidates.isEmpty()) {
                b.fail("לא נמצא מאפיין ששולח נתונים");
                return;
            }
            StringBuilder list = new StringBuilder();
            for (BluetoothGattCharacteristic c : b.candidates) {
                list.append(shortUuid(c.getUuid())).append(' ');
            }
            Diag.log("notify candidates: " + list.toString().trim());
            b.candidateIndex = -1;
            b.subscribeNext(g);
            g.setCharacteristicNotification(ch, true);
            // Local notification flag is not enough. Without writing the CCCD
            // on the peripheral, nothing is ever delivered and the app just sits
            // there looking connected.
            b.ui.removeCallbacks(b.connectTimeout);
            // Ask for the fast interval: ~11-15 ms instead of the ~30 ms Android
            // settles on. It is a request; the peripheral may refuse.
            try {
                boolean hi = g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                Diag.log("requestConnectionPriority(HIGH)=" + hi);
            } catch (RuntimeException e) {
                Diag.log("connection priority request rejected");
            }
            b.retried = false;   // a healthy link earns a fresh retry budget
            b.setState(CONNECTED);
        }

        @Override public void onDescriptorWrite(BluetoothGatt g,
                                                BluetoothGattDescriptor d, int status) {
            // Queued is not accepted. Only status 0 means the peripheral agreed
            // to notify, and without this the result of subscribing was invisible.
            Diag.log("cccd result " + shortUuid(d.getCharacteristic().getUuid())
                    + " status=" + status + (status == 0 ? " OK" : " FAILED"));
        }

        // Log at the boundary, before any check can discard the event. The old
        // version only logged inside handle(), so a callback that fired and was
        // dropped here looked identical to one that never fired at all.
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            byte[] v = ch.getValue();
            if (b.rxCount < 6) Diag.log("CB-2arg " + shortUuid(ch.getUuid())
                    + (v == null ? " value=NULL (deprecated getValue)" : " " + v.length + "B"));
            if (v != null) b.handle(ch, v);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g,
                                                      BluetoothGattCharacteristic ch, byte[] value) {
            if (b.rxCount < 6) Diag.log("CB-3arg " + shortUuid(ch.getUuid())
                    + (value == null ? " value=NULL" : " " + value.length + "B"));
            b.handle(ch, value);
        }
    }

    private final BluetoothGattCallback gattCallback = new Gatt(this);

    /** Any characteristic that can push data at us, whatever the vendor called it. */
    /**
     * Subscribe to the next notify-capable characteristic and give it a few
     * seconds to produce something. HC-08 clones expose several — FFE1, FFF4,
     * FFF5 here — and which one actually carries the UART stream varies by
     * firmware. Guessing once and waiting forever is what produced a connected
     * screen with no data and no explanation.
     */
    private void subscribeNext(BluetoothGatt g) {
        ui.removeCallbacks(probeNext);
        candidateIndex++;
        if (candidateIndex >= candidates.size()) {
            Diag.log("every notify characteristic tried, none delivered data");
            listener.onScanProblem("אף מאפיין לא שידר נתונים — בדוק את החיישן וה-baud");
            return;
        }
        BluetoothGattCharacteristic c = candidates.get(candidateIndex);
        dataChar = c.getUuid();
        boolean local = g.setCharacteristicNotification(c, true);
        BluetoothGattDescriptor d = c.getDescriptor(CCCD_UUID);
        if (d == null) {
            Diag.log("try " + shortUuid(c.getUuid()) + ": no 0x2902 descriptor");
        } else {
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean w = g.writeDescriptor(d);
            Diag.log("try " + shortUuid(c.getUuid()) + ": local=" + local + " queued=" + w);
        }
        ui.postDelayed(probeNext, 3500);
    }

    private static class ProbeNext implements Runnable {
        private final BleLink b;
        ProbeNext(BleLink link) { b = link; }
        public void run() {
            if (b.gatt == null || b.rxCount > 0) return;
            Diag.log("no data on " + shortUuid(b.dataChar) + " after 3.5s, trying next");
            b.subscribeNext(b.gatt);
        }
    }

    /** Digits, with an optional leading minus. Nothing else. */
    private static boolean isNumeric(String s) {
        if (s.length() == 0 || s.length() > 8) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i == 0 && c == '-' && s.length() > 1) continue;
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static String shortUuid(java.util.UUID u) {
        String s = u.toString();
        return s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")
                ? s.substring(4, 8).toUpperCase(java.util.Locale.US) : s;
    }

    private static BluetoothGattCharacteristic findNotifying(BluetoothGatt g) {
        for (android.bluetooth.BluetoothGattService svc : g.getServices()) {
            for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) return c;
            }
        }
        return null;
    }

    /**
     * Notifications arrive as arbitrary chunks, not lines. Buffer until a
     * terminator so a reading split across two packets ("8" then "7\n") is read
     * as 87 rather than as two bad values.
     */
    private void handle(BluetoothGattCharacteristic ch, byte[] value) {
        // While probing, take data from whichever characteristic speaks first
        // and lock to it. Filtering on a guess would discard the answer.
        if (rxCount > 0 && !ch.getUuid().equals(dataChar)) {
            Diag.log("dropped: " + shortUuid(ch.getUuid()) + " != locked " + shortUuid(dataChar));
            return;
        }
        String text = new String(value, java.nio.charset.StandardCharsets.US_ASCII);

        // Consume the bytes FIRST. Anything optional - logging, bookkeeping -
        // happens after the data is safely queued, so a fault in the diagnostics
        // can never again cost a reading.
        synchronized (this) { statPackets++; }
        boolean first = rxCount == 0;
        if (first) dataChar = ch.getUuid();
        rxCount++;

        // A BLE notification is a discrete message: its boundaries are preserved
        // on the wire. So a packet that is nothing but a number is already a
        // complete reading, terminator or not. Waiting for a newline that the
        // firmware never sends left the buffer filling with "210210210..." and
        // no line ever completing - data arriving, nothing displayed.
        String whole = text.trim();
        if (isNumeric(whole)) {
            rx.setLength(0);
            long now = android.os.SystemClock.elapsedRealtime();
            countLine(now);
            lineAt = now;
            latestLine.set(whole);
            ui.removeCallbacks(deliver);
            ui.post(deliver);
        } else {
            rx.append(text);
            while (true) {
                int idx = -1;
                for (int i = 0; i < rx.length(); i++) {
                    char c = rx.charAt(i);
                    if (c == '\n' || c == '\r' || c == '\0') { idx = i; break; }
                }
                if (idx < 0) break;
                String line = rx.substring(0, idx).trim();
                rx.delete(0, idx + 1);
                if (line.length() > 0) {
                    long now = android.os.SystemClock.elapsedRealtime();
                    countLine(now);
                    lineAt = now;
                    latestLine.set(line);
                    ui.removeCallbacks(deliver);
                    ui.post(deliver);
                }
            }
        }
        if (rx.length() > 256) rx.setLength(0);

        if (first) {
            ui.removeCallbacks(probeNext);
            Diag.log("DATA on " + shortUuid(ch.getUuid()) + " - locking to it");
        }
        if (rxCount <= 6) {
            Diag.log("rx " + value.length + "B on " + shortUuid(ch.getUuid()) + ": "
                    + text.replace("\n", "\\n").replace("\r", "\\r"));
        }
    }
}
