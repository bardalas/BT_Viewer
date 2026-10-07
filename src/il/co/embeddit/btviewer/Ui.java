package il.co.embeddit.btviewer;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

final class Ui {
    private Ui() { }

    static final int BG    = 0xFFF4F6F9;
    static final int CARD  = 0xFFFFFFFF;
    static final int LINE  = 0xFFE4E8EE;
    static final int INK   = 0xFF0D1116;
    static final int MUTED = 0xFF8A929D;
    static final int SKY   = 0xFFE9F2F9;
    static final int TERR  = 0xFFC4CAD2;
    static final int SURF  = 0xFF78808C;
    static final int ACC   = 0xFF175A8A;
    static final int ALERT = 0xFFD83628;
    static final int WHITE = 0xFFFFFFFF;

    static final Typeface LABEL = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    static final Typeface DIGITS = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL);
    static final Typeface DIGITS_BOLD = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    static int fade(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    static int over(int color, int bg, float a) {
        int r = (int) (((color >> 16) & 255) * a + ((bg >> 16) & 255) * (1 - a));
        int g = (int) (((color >> 8) & 255) * a + ((bg >> 8) & 255) * (1 - a));
        int b = (int) ((color & 255) * a + (bg & 255) * (1 - a));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static GradientDrawable card(Context c, float radius, int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radius));
        g.setStroke(dp(c, 1), stroke);
        return g;
    }

    static String metres(float cm) {
        return String.format(java.util.Locale.US, "%.2f", cm / 100f);
    }

    static String shortMetres(float cm) {
        float m = cm / 100f;
        if (Math.abs(m - Math.round(m)) < 0.005f) return String.valueOf(Math.round(m));
        if (Math.abs(m * 10f - Math.round(m * 10f)) < 0.05f)
            return String.format(java.util.Locale.US, "%.1f", m);
        return String.format(java.util.Locale.US, "%.2f", m);
    }
}
