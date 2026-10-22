package com.pratham.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/** Appearance preferences (Appearance screen) and what they resolve to right now. */
final class Appearance {
    static final String GLASS = "ap_glass";       // "liquid" | "frosted"
    static final String DARK = "ap_dark";         // "off" | "on" | "auto"
    static final String BLUR = "ap_blur";         // 0 low, 1 medium, 2 high
    static final String SHELF = TopShelf.STYLE;   // "full" | "row"
    static final String AMBIENT = "ambient";      // boolean
    static final String ICONS = "ap_icons";       // "tvos" | "banner"

    private Appearance() {}

    static SharedPreferences prefs(Context c) {
        return Profiles.prefs(c);
    }

    static boolean frosted(Context c) {
        return "frosted".equals(prefs(c).getString(GLASS, "liquid"));
    }

    /** Dark mode now: on, or automatic between 7 pm and 7 am. */
    static boolean dark(Context c) {
        String m = prefs(c).getString(DARK, "off");
        if ("on".equals(m)) return true;
        if (!"auto".equals(m)) return false;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 19 || h < 7;
    }

    static String iconPack(Context c) {
        return prefs(c).getString(ICONS, "tvos");
    }

    static int blur(Context c) {
        return Math.max(0, Math.min(2, prefs(c).getInt(BLUR, 1)));
    }

    /** A string that changes whenever anything visible here changes (to know when to redraw). */
    static String signature(Context c) {
        return frosted(c) + "|" + dark(c) + "|" + blur(c) + "|" + iconPack(c) + "|" + prefs(c).getString(SHELF, "full")
                + "|" + prefs(c).getBoolean(AMBIENT, true);
    }
}
