package il.co.embeddit.btviewer;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** Alert zone (upper/lower threshold), hysteresis, sound, demo switch, device name. */
public class ConfigActivity extends Activity {

    private Config cfg;
    private Alerter preview;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        cfg = Config.load(this);
        preview = new Alerter("preview");
        setContentView(build());
    }

    @Override protected void onPause() {
        super.onPause();
        preview.silence();
        cfg.save(this);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        preview.release();
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private TextView text(String s, float size, int c, android.graphics.Typeface f) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(c); t.setTypeface(f);
        return t;
    }

    private SeekBar upperBar, lowerBar;

    private SeekBar slider(LinearLayout parent, String name, String suffix,
                        float min, float max, float step, float value, int tag) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.card(this, 18, Ui.CARD, Ui.LINE));
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        card.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = text(name, 16, Ui.INK, Ui.LABEL);
        n.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = text("", 22, Ui.ACC, Ui.DIGITS_BOLD);
        head.addView(n);
        head.addView(val);
        card.addView(head);

        SeekBar bar = new SeekBar(this);
        bar.setMax((int) Math.round((max - min) / step));
        bar.setProgress((int) Math.round((value - min) / step));
        bar.getProgressDrawable().setColorFilter(new android.graphics.PorterDuffColorFilter(
                Ui.ACC, android.graphics.PorterDuff.Mode.SRC_IN));
        bar.getThumb().setColorFilter(new android.graphics.PorterDuffColorFilter(
                Ui.ACC, android.graphics.PorterDuff.Mode.SRC_IN));
        bar.setPadding(dp(4), dp(14), dp(4), dp(4));
        Slide s = new Slide(this, tag, min, step, val, suffix);
        bar.setOnSeekBarChangeListener(s);
        s.onProgressChanged(bar, bar.getProgress(), false);
        card.addView(bar);
        parent.addView(card);
        return bar;
    }

    private static final String[] SOUNDS = {
        "צליל רצוף", "ביפים מהירים", "סירנה דו-גונית", "יילל עולה", "שלושה ביפים"
    };

    private final TextView[] soundRows = new TextView[SOUNDS.length];
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable stopPreview = new Runnable() {
        public void run() { preview.silence(); }
    };

    private View soundCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.card(this, 18, Ui.CARD, Ui.LINE));
        card.setPadding(dp(20), dp(18), dp(20), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        card.setLayoutParams(lp);
        card.addView(text("צליל התראה", 16, Ui.INK, Ui.LABEL));
        for (int i = 0; i < SOUNDS.length; i++) {
            TextView r = text("", 17, Ui.INK, Ui.LABEL);
            r.setPadding(dp(12), dp(12), dp(12), dp(12));
            final int id = i;
            r.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { pick(id); }
            });
            soundRows[i] = r;
            card.addView(r);
        }
        paintSounds();
        return card;
    }

    private void paintSounds() {
        for (int i = 0; i < soundRows.length; i++) {
            boolean sel = i == cfg.sound;
            soundRows[i].setText((sel ? "\u25CF  " : "\u25CB  ") + SOUNDS[i]);
            soundRows[i].setTextColor(sel ? Ui.ACC : Ui.INK);
        }
    }

    /** Select and audition: a name alone does not tell you how loud it is. */
    private void pick(int id) {
        cfg.sound = id;
        cfg.save(this);
        paintSounds();
        preview.preview(id, true);
        ui.removeCallbacks(stopPreview);
        ui.postDelayed(stopPreview, 2500);
    }

    /**
     * Name this phone for the field logs, so two installs (ours and the
     * customer's) are told apart. The id beside it is fixed per install.
     */
    private View nameCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.card(this, 18, Ui.CARD, Ui.LINE));
        card.setPadding(dp(20), dp(18), dp(20), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        card.setLayoutParams(lp);
        card.addView(text("שם המכשיר (ללוגים)", 16, Ui.INK, Ui.LABEL));
        android.widget.EditText e = new android.widget.EditText(this);
        e.setSingleLine(true);
        e.setText(cfg.deviceName);
        e.setHint("למשל: יאיר / לקוח");
        e.setTextColor(Ui.INK);
        e.addTextChangedListener(new NameWatch(this));
        card.addView(e);
        card.addView(text("מזהה: " + cfg.installId + "   ·   v" + Updater.versionName(this),
                12, Ui.MUTED, Ui.DIGITS));
        return card;
    }

    private static class NameWatch implements android.text.TextWatcher {
        private final ConfigActivity a;
        NameWatch(ConfigActivity act) { a = act; }
        public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
        public void onTextChanged(CharSequence s, int st, int b, int c) { }
        public void afterTextChanged(android.text.Editable s) {
            a.cfg.deviceName = s.toString().trim();
            a.cfg.save(a);
        }
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(dp(22), dp(24), dp(22), dp(32));

        TextView h = text("הגדרות", 24, Ui.INK, Ui.LABEL);
        h.setPadding(0, 0, 0, dp(22));
        root.addView(h);

        upperBar = slider(root, "סף עליון", " מ'", Config.MIN_CM + Config.MIN_GAP_CM, Config.MAX_CM,
                5, cfg.upperCm, Slide.UPPER);
        lowerBar = slider(root, "סף תחתון", " מ'", Config.MIN_CM, Config.MAX_CM - Config.MIN_GAP_CM,
                5, cfg.lowerCm, Slide.LOWER);
        slider(root, "היסטרזיס", " ס\"מ", 0, Config.MAX_HYST_CM, 1, cfg.hystCm, Slide.HYST);
        root.addView(soundCard());

        LinearLayout demoCard = new LinearLayout(this);
        demoCard.setOrientation(LinearLayout.HORIZONTAL);
        demoCard.setGravity(Gravity.CENTER_VERTICAL);
        demoCard.setBackground(Ui.card(this, 18, Ui.CARD, Ui.LINE));
        demoCard.setPadding(dp(20), dp(10), dp(20), dp(10));
        Switch sw = new Switch(this);
        sw.setText("מצב הדגמה");
        sw.setTextSize(16);
        sw.setTextColor(Ui.INK);
        sw.setTypeface(Ui.LABEL);
        sw.setChecked(cfg.demoMode);
        sw.setOnCheckedChangeListener(new DemoToggle(this));
        sw.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        demoCard.addView(sw);
        root.addView(demoCard);
        root.addView(nameCard());

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.addView(root);
        return sv;
    }

    /** Move the other threshold's bar when keepOrder pushed it. */
    void sync(SeekBar bar, float cm, float min) {
        if (bar == null) return;
        int p = Math.round((cm - min) / 5f);
        if (bar.getProgress() != p) bar.setProgress(p);
    }

    private static class Slide implements SeekBar.OnSeekBarChangeListener {
        static final int UPPER = 0, LOWER = 1, HYST = 2;
        private final ConfigActivity a;
        private final int tag;
        private final float min, step;
        private final TextView out;
        private final String suffix;
        Slide(ConfigActivity act, int t, float mn, float st, TextView o, String sfx) {
            a = act; tag = t; min = mn; step = st; out = o; suffix = sfx;
        }
        public void onStartTrackingTouch(SeekBar s) { }
        public void onStopTrackingTouch(SeekBar s) {
            a.cfg.save(a);
            a.preview.silence();
        }
        public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
            float v = min + progress * step;
            if (tag == UPPER) {
                a.cfg.upperCm = v;
                a.cfg.keepOrder(true);
                out.setText(Ui.shortMetres(v) + suffix);
                a.sync(a.lowerBar, a.cfg.lowerCm, Config.MIN_CM);
            } else if (tag == LOWER) {
                a.cfg.lowerCm = v;
                a.cfg.keepOrder(false);
                out.setText(Ui.shortMetres(v) + suffix);
                a.sync(a.upperBar, a.cfg.upperCm, Config.MIN_CM + Config.MIN_GAP_CM);
            } else {
                a.cfg.hystCm = v;
                out.setText((int) v + suffix);
            }
        }
    }

    private static class DemoToggle implements android.widget.CompoundButton.OnCheckedChangeListener {
        private final ConfigActivity a;
        DemoToggle(ConfigActivity act) { a = act; }
        public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
            a.cfg.demoMode = on;
            a.cfg.save(a);
        }
    }
}
