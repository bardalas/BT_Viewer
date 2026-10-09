package il.co.embeddit.btviewer;

/**
 * One alert: a continuous tone while below the threshold, silence above it.
 *
 * No rate ladder, no speech, no vibration. A steady tone that is either present
 * or absent is the least ambiguous signal available, and it needs no
 * interpretation while something else has your attention.
 */
public class Alerter {

    private final Beeper beeper = new Beeper();
    private boolean alerting;

    public Alerter() { beeper.start(); }

    public void reset() {
        alerting = false;
        beeper.setSounding(false);
    }

    /** Feed every accepted reading. Returns true while the alert is sounding. */
    public boolean onReading(float cm, Config cfg) {
        alerting = cfg.alerting(cm, alerting);
        if (alerting) {
            beeper.setSound(cfg.sound);
            beeper.setSounding(true);
        } else {
            beeper.setSounding(false);
        }
        return alerting;
    }

    public boolean alerting() { return alerting; }

    public int queuedMs() { return beeper.queuedMs(); }

    public int rate() { return beeper.rate(); }

    /** Used by the settings screen to audition the tone. */
    public void preview(int sound, boolean on) {
        beeper.setSound(sound);
        beeper.setSounding(on);
    }

    public void silence() { beeper.setSounding(false); }

    public void release() { beeper.stop(); }
}
