package com.pratham.tvhome;

import android.content.Intent;

import java.util.List;

/** Customise Control Center: switch each control on or off. Sleep is always shown. */
public class ControlCenterSettings extends SettingsPanel {

    @Override
    protected String title() {
        return "Customise";
    }

    @Override
    protected void build() {
        // Grouped by where each control sits in Control Center.
        String[][] groups = {{"pill", "Tiles"}, {"circle", "Buttons"}, {"card", "Cards"}};
        for (String[] g : groups) {
            section(g[1]);
            for (String[] it : ControlCenter.ITEMS) {
                if (!g[0].equals(it[2])) continue;
                final String id = it[0];
                int[] look = look(id);
                row(look[0], look[1], it[1], () -> ControlCenter.enabled(this).contains(id) ? "On" : "Off", () -> {
                    List<String> on = ControlCenter.enabled(this);
                    if (on.contains(id)) on.remove(id);
                    else on.add(id);
                    ControlCenter.setEnabled(this, on);
                }, false);
            }
        }
        note("Sleep is always there. Tiles sit beside it, Buttons in the row below, Cards at the bottom.");

        section("Settings");
        row(R.drawable.ic_palette, 0xFFBF5AF2, "Appearance Settings", () -> "", () ->
                startActivity(new Intent(this, AppearanceSettings.class)), true);
        row(R.drawable.ic_flight, 0xFF64D2FF, "Screen Saver Settings", () -> "", () ->
                startActivity(new Intent(this, ScreenSaverSettings.class)), true);
    }

    /** {icon, colour} for each Control Center item. */
    private static int[] look(String id) {
        switch (id) {
            case "wifi": return new int[] {R.drawable.ic_wifi, 0xFF0A84FF};
            case "bluetooth": return new int[] {R.drawable.ic_bluetooth, 0xFF0A84FF};
            case "sound": return new int[] {R.drawable.ic_sound, 0xFFFF375F};
            case "timer": return new int[] {R.drawable.ic_moon, 0xFFFF9F0A};
            case "picture": return new int[] {R.drawable.ic_picture, 0xFF30D158};
            case "wallpaper": return new int[] {R.drawable.ic_landscape, 0xFF5E5CE6};
            case "inputs": return new int[] {R.drawable.ic_input, 0xFF636366};
            case "aerials": return new int[] {R.drawable.ic_flight, 0xFF64D2FF};
            case "search": return new int[] {R.drawable.ic_search, 0xFF636366};
            case "appearance": return new int[] {R.drawable.ic_palette, 0xFFBF5AF2};
            case "nowplaying": return new int[] {R.drawable.ic_play, 0xFFFF375F};
            case "settings":
            default: return new int[] {R.drawable.ic_gear, 0xFF8E8E93};
        }
    }
}
