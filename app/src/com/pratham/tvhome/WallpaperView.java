package com.pratham.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Draws the backdrop full-screen, either sharp or blurred. */
final class WallpaperView extends View {
    private final Backdrop backdrop;
    private final boolean blurred;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

    WallpaperView(Context c, Backdrop backdrop, boolean blurred) {
        super(c);
        this.backdrop = backdrop;
        this.blurred = blurred;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        backdrop.watch(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        backdrop.unwatch(this);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (blurred ? backdrop.blurred == null : backdrop.sharp == null) return;
        if (blurred) c.drawBitmap(backdrop.blurred, backdrop.blurMatrix, paint);
        else c.drawBitmap(backdrop.sharp, backdrop.sharpMatrix, paint);
    }
}
