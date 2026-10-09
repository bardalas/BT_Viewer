package il.co.embeddit.btviewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * Ground fixed, aircraft moving, one dashed threshold line. Nothing else.
 *
 * The aircraft is the only thing that moves, so any motion on screen is the
 * measurement changing.
 */
public class AltitudeView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Config cfg = new Config();
    private float target = -1f, shown = -1f;
    private boolean live, alert;

    public AltitudeView(Context c) { super(c); }

    public void bind(Config k) { cfg = k; invalidate(); }

    // When the alert state last changed, so onDraw can log how long the screen
    // took to show it.
    private long alertChangedNs;
    private boolean alertDrawPending;

    public void set(float cm, boolean alerting) {
        if (alerting != alert) {
            alertChangedNs = System.nanoTime();
            alertDrawPending = true;
        }
        target = cm;
        alert = alerting;
        live = true;
        if (shown < 0) shown = cm;
        invalidate();
    }

    public void clear() {
        live = false; alert = false; target = -1f; shown = -1f;
        invalidate();
    }

    private int dp(float v) { return Ui.dp(getContext(), v); }

    @Override protected void onDraw(Canvas cv) {
        if (alertDrawPending) {
            alertDrawPending = false;
            Telemetry.event("screen " + (alert ? "RED" : "normal") + " drawn +"
                    + (System.nanoTime() - alertChangedNs) / 1000000L + "ms");
        }
        float w = getWidth(), h = getHeight();
        float top = dp(20), groundY = h - dp(34), bottom = h;

        p.setStyle(Paint.Style.FILL);
        p.setColor(alert ? Ui.over(Ui.WHITE, Ui.ALERT, 0.14f) : Ui.SKY);
        cv.drawRoundRect(0, 0, w, h, dp(18), dp(18), p);

        p.setColor(Ui.TERR);
        cv.drawRect(0, groundY, w, bottom, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(3));
        p.setColor(Ui.SURF);
        cv.drawLine(0, groundY, w, groundY, p);

        float scale = cfg.scaleCm();
        float ty = yFor(cfg.thresholdCm, top, groundY, scale);
        p.setStrokeWidth(dp(3));
        p.setColor(alert ? Ui.WHITE : Ui.ALERT);
        for (float x = dp(6); x < w - dp(6); x += dp(15)) {
            cv.drawLine(x, ty, Math.min(x + dp(9), w - dp(6)), ty, p);
        }

        if (!live || shown < 0) return;

        float y = yFor(shown, top, groundY, scale);
        int ac = alert ? Ui.WHITE : Ui.INK;
        p.setStyle(Paint.Style.FILL);
        p.setColor(ac);
        float cx = w / 2f, wing = dp(36), bar = dp(6), body = dp(13);
        cv.drawRect(cx - wing - body / 2f, y - bar / 2f, cx - body / 2f, y + bar / 2f, p);
        cv.drawRect(cx + body / 2f, y - bar / 2f, cx + wing + body / 2f, y + bar / 2f, p);
        cv.drawRect(cx - body / 2f, y - dp(5), cx + body / 2f, y + dp(5), p);

        float d = target - shown;
        if (Math.abs(d) > 0.15f) {
            shown += d * (Math.abs(d) > 20f ? 0.6f : 0.3f);
            postInvalidateOnAnimation();
        } else {
            shown = target;
        }
    }

    private float yFor(float cm, float top, float groundY, float scale) {
        float f = cm / scale;
        if (f < 0f) f = 0f;
        if (f > 1f) f = 1f;
        return groundY - (groundY - top) * f;
    }
}
