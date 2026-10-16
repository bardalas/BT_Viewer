package il.co.embeddit.btviewer;

/**
 * Plain-JVM check of the decision behind both the sound and the red screen:
 * EchoFilter + Config.alerting, fed like MainActivity.onLine. Run ./test.sh.
 *
 * The rule under test: the alarm sounds only in the zone of interest - at or
 * below the threshold. Above it, never, whatever the sensor does.
 */
public class EchoFilterTest {

    static int failures;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    /** Filter + alert, exactly as onLine uses them. */
    static final class Run {
        final EchoFilter f = new EchoFilter();
        final Config cfg = new Config();
        boolean alerting;
        int alertTicks;

        Run(float thr) { cfg.thresholdCm = thr; }

        void feed(float raw) {
            float cm = f.accept(raw);
            if (Float.isNaN(cm)) return;           // hold
            alerting = cfg.alerting(cm);
            if (alerting) alertTicks++;
        }
    }

    /** What the sensor reports for a true height: noise, false short echoes, lost echoes. */
    static float sensor(float h, java.util.Random rnd, double falseRate, double lostRate) {
        if (h < 22) return -1;                                   // blind zone
        if (rnd.nextDouble() < falseRate) return 20 + rnd.nextInt(11);   // false 20-30 cm
        if (rnd.nextDouble() < lostRate) return -1;              // lost echo
        return Math.round(h + (rnd.nextFloat() * 4 - 2));        // +-2 cm noise
    }

    public static void main(String[] a) {
        java.util.Random rnd = new java.util.Random(7);

        // 1. The field report: hovering at 2.5 m, threshold 2 m, with false
        // short echoes (2% of readings) and 15% lost echoes. Never an alarm.
        Run r = new Run(200);
        for (int i = 0; i < 20000; i++) r.feed(sensor(250, rnd, 0.02, 0.15));
        check(r.alertTicks == 0, "hover 2.5 m, thr 2 m, 2% false echoes, 20000 readings: alarm never (ticks="
                + r.alertTicks + ")");
        // Harsher: 5% false echoes. Only three false echoes in a row can pass.
        r = new Run(200);
        for (int i = 0; i < 20000; i++) r.feed(sensor(250, rnd, 0.05, 0.15));
        System.out.println("INFO  hover with 5% false echoes: alarm ticks " + r.alertTicks + " / 20000");

        // 2. The 5.7.3-5.7.6 logic on the same input: reproduces the report.
        check(oldLogic(new java.util.Random(7)) > 0, "old logic reproduces the report");

        // 3. Random flights. The logic invariant: whenever the alarm sounds,
        // the height it is based on is inside the zone. And against the TRUE
        // height: no alarm more than 10 cm above the zone.
        for (float thr : new float[]{30, 50, 100, 150, 200}) {
            int invariant = 0, farOutside = 0, ticks = 0;
            for (int flight = 0; flight < 200; flight++) {
                Run fr = new Run(thr);
                float h = 50 + rnd.nextInt(400);
                for (int i = 0; i < 600; i++) {
                    h += (rnd.nextFloat() * 2 - 1) * 4;          // <= 4 cm per tick
                    if (h < 0) h = 0;
                    if (h > 500) h = 500;
                    fr.feed(sensor(h, rnd, 0.02, 0.10));
                    if (fr.alerting) {
                        ticks++;
                        if (fr.f.last() > thr) invariant++;
                        if (h > thr + 10) farOutside++;
                    }
                }
            }
            check(invariant == 0 && farOutside == 0, "random flights, thr " + (int) thr
                    + " cm: alarm on a height above the zone = " + invariant
                    + ", true height >10 cm above = " + farOutside + " (alarm ticks " + ticks + ")");
        }

        // 3b. No added delay: in continuous flight every reading is used as is,
        // on the reading it arrives (display and alarm).
        EchoFilter f = new EchoFilter();
        int delayed = 0;
        float h = 300;
        f.accept(300); f.accept(300); f.accept(300);
        for (int i = 0; i < 5000; i++) {
            h += (rnd.nextFloat() * 2 - 1) * 15;                 // up to 3 m/s
            if (h < 35) h = 35;
            if (h > 450) h = 450;
            float raw = Math.round(h);
            if (f.accept(raw) != raw) delayed++;
        }
        check(delayed == 0, "continuous flight up to 3 m/s: every reading shown and judged immediately (late="
                + delayed + ")");

        // 4. Exact edges: on at the threshold, off one cm above it.
        r = new Run(200);
        for (int cm = 260; cm >= 199; cm--) r.feed(cm);
        check(r.alerting, "descent: on at 1.99 m");
        r = new Run(200);
        for (int cm = 260; cm >= 200; cm--) r.feed(cm);
        check(r.alerting, "descent: on exactly at 2.00 m");
        for (int cm = 200; cm <= 201; cm++) r.feed(cm);
        check(!r.alerting, "climb: off at 2.01 m (no band above the line)");

        // 5. Spikes and sudden drops.
        r = new Run(200);
        for (int i = 0; i < 10; i++) r.feed(250);
        r.feed(24); r.feed(24);
        check(!r.alerting, "two false 24 cm echoes at 2.5 m: no alarm");
        r.feed(150); r.feed(151);
        check(!r.alerting, "sudden real drop to 1.5 m, 2 echoes: not yet");
        r.feed(150);
        check(r.alerting, "sudden real drop to 1.5 m, 3rd echo: alarm (~100 ms)");

        // 6. First echo after connecting is not trusted alone.
        r = new Run(200);
        r.feed(24);
        check(!r.alerting, "first echo after connect is a false 24 cm: no alarm");
        r.feed(250); r.feed(251); r.feed(250);
        check(!r.alerting && r.f.last() == 250, "then real 2.5 m confirmed: no alarm");

        // 7. Landing: alarm on through the blind zone; lost echo away from the
        // ground does not invent a height.
        r = new Run(200);
        for (int cm = 300; cm >= 22; cm -= 3) r.feed(cm);
        for (int i = 0; i < 100; i++) r.feed(-1);
        check(r.alerting && r.f.blind(), "landing: alarm held on the ground (-1 stream)");
        r = new Run(200);
        for (int i = 0; i < 10; i++) r.feed(250);
        for (int i = 0; i < 50; i++) r.feed(-1);
        check(!r.alerting && !r.f.blind(), "-1 stream at 2.5 m: no alarm");

        // 8. Threshold below the sensor's blind limit (~25 cm) cannot be
        // detected; the alarm stays strictly off rather than guess.
        r = new Run(15);
        for (int cm = 100; cm >= 22; cm -= 2) r.feed(cm);
        for (int i = 0; i < 50; i++) r.feed(-1);
        check(!r.alerting, "thr 0.15 m (below sensor range): no alarm at 22 cm or in blind zone");

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** The 5.7.3-5.7.6 decision (hysteresis 8 cm, latch), kept only to show the bug. */
    static int oldLogic(java.util.Random rnd) {
        float thr = 200, hyst = 8, last = -1;
        boolean alerting = false, blind = false;
        int ticks = 0;
        for (int i = 0; i < 5000; i++) {
            float raw = sensor(250, rnd, 0.05, 0.15), cm = raw;
            if (raw < 0) {
                if (blind || (last >= 0 && last < 30)) { blind = true; cm = 0; }
                else continue;
            } else { blind = false; last = raw; }
            alerting = cm <= thr || (alerting && cm <= thr + hyst);
            if (alerting) ticks++;
        }
        return ticks;
    }
}
