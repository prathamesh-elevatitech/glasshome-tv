package com.pratham.tvhome;

import android.content.Intent;
import android.graphics.drawable.Drawable;

/** One tile on the home screen. {@code key} is the package name, or "@inputs" for the input picker. */
final class AppEntry {
    final String key;
    final String label;
    final Intent intent;
    final boolean special;

    /** 16:9 TV banner, when the app ships one. Otherwise the tile shows {@link #glyph} on a gradient. */
    Drawable banner;
    Drawable glyph;
    int colorTop;
    int colorBottom;

    /** The baked icon (see Ui.bakeTile), reused when the grid is rebuilt. */
    android.graphics.Bitmap tile;

    /** Set for a folder: the apps inside it. */
    java.util.List<AppEntry> members;

    AppEntry(String key, String label, Intent intent, boolean special) {
        this.key = key;
        this.label = label;
        this.intent = intent;
        this.special = special;
    }
}
