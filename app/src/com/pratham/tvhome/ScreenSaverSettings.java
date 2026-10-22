package com.pratham.tvhome;

import android.content.Intent;

/** tvOS-style Screen Saver settings: Preview, and Choose Aerials by category. */
public class ScreenSaverSettings extends SettingsPanel {

    @Override
    protected String title() {
        return "Screen Saver";
    }

    @Override
    protected void build() {
        row(R.drawable.ic_play, 0xFF0A84FF, "Preview", () -> "", () ->
                startActivity(new Intent(this, AerialActivity.class)), true);
        note("Aerials play when the TV is idle. OK shows where it is; Left and Right skip.");

        section("Choose Aerials");
        int[] colors = {0xFF30D158, 0xFFFF9F0A, 0xFF0A84FF, 0xFF5E5CE6};
        for (int i = 0; i < AerialView.CATEGORIES.length; i++) {
            final int bit = 1 << i;
            row(R.drawable.ic_landscape, colors[i % colors.length], AerialView.CATEGORIES[i] + " · " + count(i),
                    () -> (AerialView.offMask(this) & bit) == 0 ? "On" : "Off", () -> {
                        int mask = AerialView.offMask(this) ^ bit;
                        if (mask == (1 << AerialView.CATEGORIES.length) - 1) return; // keep at least one on
                        AerialView.setOffMask(this, mask);
                    }, false);
        }
    }

    private static int count(int category) {
        int n = 0;
        for (String[] c : AerialView.CLIPS) if (Integer.parseInt(c[4]) == category) n++;
        return n;
    }
}
