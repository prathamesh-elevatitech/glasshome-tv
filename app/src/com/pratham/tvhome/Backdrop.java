package com.pratham.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The wallpaper shared by everything on the home screen: a sharp copy for the background and a
 * tiny pre-blurred copy that glass surfaces sample from, both positioned by one slow drift.
 */
final class Backdrop {
    static final String[] WALLPAPERS = {"wall/fjord.jpg", "wall/cliffs.jpg", "wall/snow.jpg", "wall/lake.jpg"};

    Bitmap sharp, blurred;
    final Matrix sharpMatrix = new Matrix();
    final Matrix blurMatrix = new Matrix();
    private final List<View> watchers = new ArrayList<>();
    private int screenW, screenH;
    private float drift = 0.5f;

    void setScreen(int w, int h) {
        screenW = w;
        screenH = h;
        update();
    }

    /** Decodes a wallpaper and its blurred twin; call off the UI thread. */
    /** Overscan so the drift never shows an edge. */
    static final float OVERSCAN = 1.075f;

    /**
     * Decodes a wallpaper, pre-scaled to exactly the screen plus overscan (so it is drawn 1:1,
     * with no per-frame resampling), with the shading baked in; plus a tiny blurred twin.
     */
    static Bitmap[] load(Context c, int index, int screenW, int screenH) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap sharp;
        try (InputStream in = c.getAssets().open(WALLPAPERS[index % WALLPAPERS.length])) {
            sharp = BitmapFactory.decodeStream(in, null, o);
        }
        // Appearance → Blur: smaller sample + wider radius = softer glass.
        int level = Appearance.blur(c);
        Bitmap blurred = level == 0 ? Ui.blur(sharp, 256, 144, 2)
                : level == 2 ? Ui.blur(sharp, 128, 72, 4) : Ui.blur(sharp, 192, 108, 3);
        // Bake the top/bottom shading into the wallpaper instead of drawing a full-screen
        // gradient over it every frame.
        Bitmap shaded = sharp.copy(Bitmap.Config.RGB_565, true);
        sharp.recycle();
        android.graphics.Canvas cv = new android.graphics.Canvas(shaded);
        android.graphics.drawable.GradientDrawable scrim = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x40000000, 0x00000000, 0x00000000, 0x73000000});
        scrim.setDither(true);
        scrim.setBounds(0, 0, shaded.getWidth(), shaded.getHeight());
        scrim.draw(cv);
        float s = Math.max(screenW / (float) shaded.getWidth(), screenH / (float) shaded.getHeight()) * OVERSCAN;
        Bitmap fitted = Bitmap.createScaledBitmap(shaded, Math.round(shaded.getWidth() * s),
                Math.round(shaded.getHeight() * s), true);
        if (fitted != shaded) shaded.recycle();
        return new Bitmap[] {fitted, blurred};
    }

    void set(Bitmap sharp, Bitmap blurred) {
        this.sharp = sharp;
        this.blurred = blurred;
        update();
    }

    /** 0..1 position of the slow Ken Burns drift. */
    void setDrift(float t) {
        drift = t;
        update();
    }

    void watch(View v) {
        if (!watchers.contains(v)) watchers.add(v);
    }

    void unwatch(View v) {
        watchers.remove(v);
    }

    void invalidateWatchers() {
        for (int i = 0; i < watchers.size(); i++) watchers.get(i).invalidate();
    }

    private void update() {
        if (sharp == null || screenW == 0) return;
        float bw = sharp.getWidth(), bh = sharp.getHeight();
        float s = Math.max(screenW / bw, screenH / bh) * (1.05f + 0.05f * drift);
        float dx = (screenW - bw * s) / 2f + (drift - 0.5f) * screenW * 0.04f;
        float dy = (screenH - bh * s) / 2f + (0.5f - drift) * screenH * 0.03f;
        if (Math.abs(s - 1f) < 0.01f) {
            // Pre-scaled bitmap: draw it 1:1 on whole pixels, the cheapest possible blit.
            s = 1f;
            dx = Math.round((screenW - bw) / 2f);
            dy = Math.round((screenH - bh) / 2f);
        }
        sharpMatrix.setScale(s, s);
        sharpMatrix.postTranslate(dx, dy);
        float k = bw / blurred.getWidth();
        blurMatrix.setScale(s * k, s * k);
        blurMatrix.postTranslate(dx, dy);
        invalidateWatchers();
    }
}
