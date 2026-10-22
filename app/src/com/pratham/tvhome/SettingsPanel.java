package com.pratham.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * A settings screen in the tvOS style: a frosted glass panel floating on the right over the
 * home screen, with a centred title, small section labels, rows with coloured icon squares,
 * values with a chevron, notes under rows, and sliders.
 */
abstract class SettingsPanel extends Activity {
    interface Value {
        String get();
    }

    /** Left/Right on a slider row: -1 or +1. */
    interface Step {
        void by(int delta);
    }

    protected LinearLayout col;
    private GlassView panel;
    private View firstRow;

    protected abstract String title();

    /** Adds the rows; called once from onCreate. */
    protected abstract void build();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        GlassView.loadStyle(this);
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);

        panel = new GlassView(this, 30).solid(true).fallback(0xF0262629);
        panel.setElevation(Ui.dp(30));
        // The list scrolls inside the panel: clip it to the rounded shape.
        panel.setClipChildren(true);
        panel.setClipToOutline(true);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(true);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(Ui.dp(12), Ui.dp(20), Ui.dp(12), Ui.dp(20));
        TextView t = Ui.text(this, 17, Ui.semibold, Color.WHITE);
        t.setText(title());
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(0, 0, 0, Ui.dp(10));
        col.addView(t, new LinearLayout.LayoutParams(-1, -2));
        build();
        scroll.addView(col, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(340), -1, Gravity.END);
        lp.rightMargin = Ui.dp(24);
        lp.topMargin = Ui.dp(24);
        lp.bottomMargin = Ui.dp(24);
        root.addView(panel, lp);
        setContentView(root);

        panel.setTranslationX(Ui.dp(380));
        panel.animate().translationX(0f).setDuration(480).setInterpolator(Ui.spring(9f, 9f)).start();
        if (firstRow != null) firstRow.requestFocus();
    }

    // A child panel (e.g. Appearance opened from Customise) replaces this one on screen; this one
    // comes back when the child closes.
    @Override
    protected void onPause() {
        super.onPause();
        if (!isFinishing()) panel.animate().alpha(0f).setDuration(150).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (panel.getAlpha() < 1f) panel.animate().alpha(1f).setDuration(220).start();
    }

    @Override
    public void onBackPressed() {
        panel.animate().translationX(Ui.dp(380)).setDuration(220).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    finish();
                    overridePendingTransition(0, 0);
                }).start();
    }

    protected void section(String name) {
        TextView s = Ui.text(this, 11, Ui.semibold, 0x8CFFFFFF);
        s.setText(name);
        s.setPadding(Ui.dp(10), Ui.dp(14), 0, Ui.dp(4));
        col.addView(s);
    }

    protected void note(String text) {
        TextView n = Ui.text(this, 11, Ui.regular, 0x99FFFFFF);
        n.setText(text);
        n.setLineSpacing(Ui.dp(1), 1f);
        n.setPadding(Ui.dp(10), Ui.dp(4), Ui.dp(10), Ui.dp(6));
        col.addView(n);
    }

    /** A row: coloured icon square, label, value and chevron. OK runs {@code click} then refreshes. */
    protected View row(int icon, int iconColor, String label, Value value, Runnable click, boolean chevron) {
        GlassView item = new GlassView(this, 14).plain();
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(8), 0, Ui.dp(12), 0);

        FrameLayout sq = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(iconColor);
        bg.setCornerRadius(Ui.dp(6));
        sq.setBackground(bg);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        sq.addView(iv, new FrameLayout.LayoutParams(Ui.dp(14), Ui.dp(14), Gravity.CENTER));
        r.addView(sq, new LinearLayout.LayoutParams(Ui.dp(24), Ui.dp(24)));

        TextView t = Ui.text(this, 13, Ui.semibold, Color.WHITE);
        t.setText(label);
        t.setSingleLine(true);
        t.setPadding(Ui.dp(10), 0, 0, 0);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = Ui.text(this, 12, Ui.medium, 0x99FFFFFF);
        v.setTag(0x99FFFFFF);
        v.setText(value == null ? "" : value.get() + (chevron ? "  ›" : ""));
        r.addView(v);
        item.addView(r, new FrameLayout.LayoutParams(-1, -1));
        Runnable refresh = () -> {
            if (value != null) v.setText(value.get() + (chevron ? "  ›" : ""));
        };
        item.setTag(refresh);
        item.button(x -> {
            if (click != null) click.run();
            refresh.run();
        }, new TextView[] {t, v}, new ImageView[0]);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(40));
        lp.topMargin = Ui.dp(3);
        col.addView(item, lp);
        if (firstRow == null) firstRow = item;
        return item;
    }

    /** A row with a bar under it; Left/Right step the value (OK also steps up, wrapping). */
    protected View slider(int icon, int iconColor, String label, Value value, int steps, Value position,
                          Step step) {
        final View[] bar = new View[1];
        final FrameLayout track = new FrameLayout(this);
        View row = row(icon, iconColor, label, value, () -> {
            step.by(+1);
            updateBar(track, bar[0], position, steps);
        }, false);
        row.setOnKeyListener((v, k, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (k != KeyEvent.KEYCODE_DPAD_LEFT && k != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            step.by(k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            Ui.tick(this);
            ((Runnable) v.getTag()).run();
            updateBar(track, bar[0], position, steps);
            return true;
        });
        GradientDrawable tb = new GradientDrawable();
        tb.setColor(0x40FFFFFF);
        tb.setCornerRadius(Ui.dp(2));
        track.setBackground(tb);
        bar[0] = new View(this);
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(Color.WHITE);
        bb.setCornerRadius(Ui.dp(2));
        bar[0].setBackground(bb);
        track.addView(bar[0], new FrameLayout.LayoutParams(0, -1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(4));
        lp.leftMargin = Ui.dp(10);
        lp.rightMargin = Ui.dp(10);
        lp.topMargin = Ui.dp(8);
        col.addView(track, lp);
        track.post(() -> updateBar(track, bar[0], position, steps));
        return row;
    }

    private static void updateBar(FrameLayout track, View bar, Value position, int steps) {
        int i = Integer.parseInt(position.get());
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
        lp.width = Math.round(track.getWidth() * (i + 1) / (float) steps);
        bar.setLayoutParams(lp);
    }
}
