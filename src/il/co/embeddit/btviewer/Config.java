package il.co.embeddit.btviewer;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The configuration.
 *
 * The alert zone is a band: from the lower threshold up to the upper one. The
 * alert starts on entering the band and stops only once the height has left it
 * by the hysteresis margin (above the upper or below the lower threshold), so a
 * reading sitting on either line does not chatter. The upper threshold is
 * always at least MIN_GAP_CM above the lower one.
 */
public class Config {

    private static final String P = "btviewer_v24";

    public static final float MIN_CM = 20f, MAX_CM = 300f;   // sliders: 0.2 .. 3 m
    public static final float MIN_GAP_CM = 5f;                // upper - lower, at least
    public static final float MAX_HYST_CM = 40f;

    public float upperCm = 200f;       // top of the alert zone
    public float lowerCm = MIN_CM;     // bottom of the alert zone
    public float hystCm = 8f;          // leave the zone by this much to silence
    public int sound = 0;              // Beeper.STEADY..TRIPLE

    public String lastAddress = "", lastName = "";
    public boolean demoMode = false;

    /** Tells one phone's field logs from another's: a fixed random id, and an optional name. */
    public String installId = "", deviceName = "";

    public static Config load(Context c) {
        SharedPreferences p = c.getSharedPreferences(P, Context.MODE_PRIVATE);
        Config k = new Config();
        k.installId = p.getString("installId", "");
        if (k.installId.length() == 0) {
            k.installId = String.format(java.util.Locale.US, "%06x",
                    new java.util.Random().nextInt(0x1000000));
            p.edit().putString("installId", k.installId).apply();
        }
        k.deviceName = p.getString("deviceName", "");
        k.upperCm = clamp(p.getFloat("threshold", 200f), MIN_CM, MAX_CM);
        k.lowerCm = clamp(p.getFloat("lower", MIN_CM), MIN_CM, MAX_CM);
        k.hystCm = clamp(p.getFloat("hyst", 8f), 0f, MAX_HYST_CM);
        k.keepOrder(true);
        k.sound = Math.max(0, Math.min(Beeper.COUNT - 1, p.getInt("sound", 0)));
        k.lastAddress = p.getString("lastAddress", "");
        k.lastName = p.getString("lastName", "");
        k.demoMode = p.getBoolean("demoMode", false);
        return k;
    }

    public void save(Context c) {
        c.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
                .putFloat("threshold", upperCm)
                .putFloat("lower", lowerCm)
                .putFloat("hyst", hystCm)
                .putInt("sound", sound)
                .putString("lastAddress", lastAddress)
                .putString("lastName", lastName)
                .putBoolean("demoMode", demoMode)
                .putString("deviceName", deviceName)
                .apply();
    }

    /** Top of the drawn scale. Derived, so it is not one more thing to set. */
    public float scaleCm() { return Math.max(50f, upperCm * 1.6f); }

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    /**
     * Upper stays at least MIN_GAP_CM above lower. The side that was just set
     * wins: moving the upper below the lower pushes the lower down, and moving
     * the lower above the upper pushes the upper up.
     */
    public void keepOrder(boolean upperWins) {
        if (upperCm - lowerCm >= MIN_GAP_CM) return;
        if (upperWins) {
            upperCm = clamp(upperCm, MIN_CM + MIN_GAP_CM, MAX_CM);
            lowerCm = upperCm - MIN_GAP_CM;
        } else {
            lowerCm = clamp(lowerCm, MIN_CM, MAX_CM - MIN_GAP_CM);
            upperCm = lowerCm + MIN_GAP_CM;
        }
    }

    /**
     * Inside the band [lower, upper] the alert sounds. Once sounding, it keeps
     * sounding until the height is more than the hysteresis outside the band.
     */
    public boolean alerting(float cm, boolean currentlyAlerting) {
        if (cm < 0) return false;
        if (cm >= lowerCm && cm <= upperCm) return true;
        return currentlyAlerting && cm >= lowerCm - hystCm && cm <= upperCm + hystCm;
    }
}
