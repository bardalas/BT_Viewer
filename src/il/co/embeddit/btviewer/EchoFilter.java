package il.co.embeddit.btviewer;

/**
 * Turns raw sensor lines into the height the alert and the screen use.
 *
 * Plain Java with no Android types, so it is tested on a PC
 * (test/EchoFilterTest.java, ./test.sh).
 *
 * Two jobs:
 *  - Reject false echoes. The JSN-SR04T sometimes returns a false short
 *    reading (~20-30 cm) when the real echo is weak, typically higher up.
 *    Unfiltered, one such reading sounded the alarm and, followed by -1,
 *    latched the "blind near the ground" state below, so the alarm stayed on
 *    far above the threshold (reported on 5.7.3-5.7.6). Real motion is
 *    continuous: between two echoes (~50 ms) the height changes by a few cm.
 *    A change faster than STEP_CM per line is taken only once CONFIRM
 *    consecutive echoes agree on the new height; until then the last height
 *    stands. Normal readings (up to 4 m/s) pass with no delay; a genuine
 *    sudden jump (flying over an edge) costs two extra echoes, ~100 ms.
 *  - No echo (-1). Right after a height below BLIND_CM the sensor is blind near
 *    the ground: the last height stands for as long as -1 continues (the
 *    alert decision on it is unchanged - in the zone only if that height was).
 *    Otherwise -1 carries no height and the caller holds its last state for a
 *    short while.
 */
final class EchoFilter {

    /** Below this the ultrasonic sensor goes blind and reports -1 (no echo). */
    static final float BLIND_CM = 30f;
    /**
     * The most the height can change per line (~50 ms) in real flight: 20 cm =
     * 4 m/s. After lost echoes the allowance grows with the lines missed, so a
     * real change across a gap passes at once. Anything faster needs
     * confirming.
     */
    static final float STEP_CM = 20f;
    /** False short echoes land below this (~20-30 cm, sensor ringing). */
    static final float FALSE_BAND_CM = 35f;
    /** Inside that band, the step that passes without confirming (2 m/s). */
    static final float BAND_STEP_CM = 10f;
    /** How close confirming echoes must be to each other. */
    static final float AGREE_CM = 6f;
    /** Consecutive echoes needed to accept a jump. */
    static final int CONFIRM = 3;
    /**
     * ... and a jump straight into the false-echo band, which is what a run of
     * false echoes looks like (~200 ms). A real continuous descent into the
     * band is not a jump and passes at once.
     */
    static final int CONFIRM_BAND = 5;

    /**
     * -1 means "blind near the ground" only this many lines (~600 ms at 20 Hz)
     * after a low echo. Later than that the low echo is stale - or was false.
     */
    static final int BLIND_WINDOW_LINES = 12;

    /** Returned by accept() for a -1 that is not the blind zone, or before the first height is confirmed: hold. */
    static final float HOLD = Float.NaN;

    private float last = -1f;
    private boolean blind;
    private float candidate;
    private int agreeing;
    private int sinceEcho;          // lines since the last accepted echo

    /** Height to use: the last accepted cm (kept through the blind zone), or HOLD. */
    float accept(float raw) {
        sinceEcho++;
        if (raw < 0) {
            if (blind || (last >= 0 && last < BLIND_CM && sinceEcho <= BLIND_WINDOW_LINES)) {
                blind = true;
                return last;
            }
            return HOLD;
        }
        // The band just above the blind limit is where the false echoes land,
        // so a reading there gets no allowance for missed lines: it must come
        // from one step of continuous descent, or be confirmed.
        float allowed = raw < FALSE_BAND_CM ? BAND_STEP_CM : STEP_CM * sinceEcho;
        if (last >= 0 && Math.abs(raw - last) <= allowed) {
            last = raw;
            agreeing = 0;
            blind = false;
            sinceEcho = 0;
            return last;
        }
        // A jump - or the first echo after connecting, which has nothing to be
        // compared with and could itself be false: count it only if it repeats.
        if (agreeing > 0 && Math.abs(raw - candidate) <= AGREE_CM) {
            agreeing++;
            candidate = raw;
        } else {
            candidate = raw;
            agreeing = 1;
        }
        if (agreeing >= (raw < FALSE_BAND_CM ? CONFIRM_BAND : CONFIRM)) {
            last = raw;
            agreeing = 0;
            blind = false;
            sinceEcho = 0;
        }
        // While unconfirmed, the blind state (if any) and the height stand.
        return last >= 0 ? last : HOLD;
    }

    boolean blind() { return blind; }

    /** Last accepted height, -1 before the first echo. */
    float last() { return last; }

    void reset() {
        last = -1f;
        blind = false;
        agreeing = 0;
        sinceEcho = 0;
    }
}
