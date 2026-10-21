package com.pratham.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Household profiles, like tvOS: each has its own dock and wallpaper. Profile 0 keeps the
 * original unsuffixed preference keys so the existing setup carries over.
 */
final class Profiles {
    static final String[] NAMES = {"Me", "Family"}; // edit to your household
    private static final int[][] COLORS = {{0xFF5AC8FA, 0xFF0A60D6}, {0xFFFF8FB1, 0xFFD6336C}};

    private Profiles() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    static int current(SharedPreferences p) {
        return Math.max(0, Math.min(NAMES.length - 1, p.getInt("profile", 0)));
    }

    static void set(SharedPreferences p, int i) {
        p.edit().putInt("profile", i).apply();
    }

    /** Preference key for a per-profile setting such as "dock" or "wall". */
    static String key(SharedPreferences p, String base) {
        int c = current(p);
        return c == 0 ? base : base + "_" + c;
    }

    /** A round avatar with the profile's initial on its colour gradient. */
    static FrameLayout avatar(Context c, int i, float sizeDp) {
        FrameLayout f = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, COLORS[i % COLORS.length]);
        bg.setShape(GradientDrawable.OVAL);
        f.setBackground(bg);
        TextView t = Ui.text(c, sizeDp * 0.42f, Ui.semibold, Color.WHITE);
        t.setText(NAMES[i].substring(0, 1));
        f.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return f;
    }
}
