package com.pratham.tvhome;

import android.content.SharedPreferences;

/** Appearance: glass style, icon pack, dark mode, blur, Top Shelf style, app colour background. */
public class AppearanceSettings extends SettingsPanel {
    private SharedPreferences prefs;

    @Override
    protected String title() {
        return "Appearance";
    }

    @Override
    protected void build() {
        prefs = Appearance.prefs(this);

        section("Style");
        choice(R.drawable.ic_drop, 0xFF0A84FF, "Glass Style", Appearance.GLASS,
                new String[] {"liquid", "frosted"}, new String[] {"Liquid Glass", "Frosted Glass"});
        choice(R.drawable.ic_grid, 0xFFFF9F0A, "Icon Pack", Appearance.ICONS,
                new String[] {"tvos", "banner"}, new String[] {"tvOS", "Original"});
        choice(R.drawable.ic_moon, 0xFF3A3A3C, "Dark Mode", Appearance.DARK,
                new String[] {"off", "on", "auto"}, new String[] {"Off", "On", "Automatic"});
        note("Dark Mode darkens the glass and the wallpaper. Automatic turns it on from 7 pm to 7 am.");
        String[] blur = {"Low", "Medium", "High"};
        slider(R.drawable.ic_blur, 0xFF5E5CE6, "Overall Blur",
                () -> blur[Appearance.blur(this)], 3, () -> String.valueOf(Appearance.blur(this)),
                d -> prefs.edit().putInt(Appearance.BLUR, Math.floorMod(Appearance.blur(this) + d, 3)).apply());
        note("Blur of the glass across the interface. Changes apply when you go back home.");

        section("Home Screen");
        choice(R.drawable.ic_shelf, 0xFF30D158, "Top Shelf", Appearance.SHELF,
                new String[] {"full", "row"}, new String[] {"Full Screen", "Row"});
        note("Full Screen fills the screen with artwork. Row shows cards above the dock; press Up again for a clean backdrop.");
        row(R.drawable.ic_palette, 0xFFFF375F, "App Colour Background",
                () -> prefs.getBoolean(Appearance.AMBIENT, true) ? "On" : "Off",
                () -> prefs.edit().putBoolean(Appearance.AMBIENT, !prefs.getBoolean(Appearance.AMBIENT, true)).apply(),
                false);
    }

    private void choice(int icon, int color, String label, String key, String[] values, String[] names) {
        row(icon, color, label, () -> {
            String cur = prefs.getString(key, values[0]);
            for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) return names[i];
            return names[0];
        }, () -> {
            String cur = prefs.getString(key, values[0]);
            int i = 0;
            while (i < values.length && !values[i].equals(cur)) i++;
            prefs.edit().putString(key, values[(i + 1) % values.length]).apply();
        }, true);
    }
}
