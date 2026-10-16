package il.co.embeddit.btviewer;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The configuration.
 *
 * One threshold: at or below it the alert sounds, above it there is silence.
 * Nothing above the threshold is of interest (we are flying), so there is no
 * hysteresis band above the line: the alert never sounds for a height above
 * the threshold.
 */
public class Config {

    private static final String P = "btviewer_v24";

    public static final float MIN_CM = 10f, MAX_CM = 200f;   // 0.1 .. 2 m

    public float thresholdCm = 200f;   // cross it and the alert starts
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
        k.thresholdCm = Math.max(MIN_CM, Math.min(MAX_CM, p.getFloat("threshold", 200f)));
        k.sound = Math.max(0, Math.min(Beeper.COUNT - 1, p.getInt("sound", 0)));
        k.lastAddress = p.getString("lastAddress", "");
        k.lastName = p.getString("lastName", "");
        k.demoMode = p.getBoolean("demoMode", false);
        return k;
    }

    public void save(Context c) {
        c.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
                .putFloat("threshold", thresholdCm)
                .putInt("sound", sound)
                .putString("lastAddress", lastAddress)
                .putString("lastName", lastName)
                .putBoolean("demoMode", demoMode)
                .putString("deviceName", deviceName)
                .apply();
    }

    /** Top of the drawn scale. Derived, so it is not one more thing to set. */
    public float scaleCm() { return Math.max(50f, thresholdCm * 2f); }

    /** The alert zone: at or below the threshold. Strictly nothing above it. */
    public boolean alerting(float cm) {
        return cm >= 0 && cm <= thresholdCm;
    }
}
