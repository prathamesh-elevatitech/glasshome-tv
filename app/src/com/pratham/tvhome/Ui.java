package com.pratham.tvhome;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.BlurMaskFilter;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** Shared sizing, fonts, motion and bitmap helpers. */
final class Ui {
    static float density = 2f;
    static Typeface light, regular, medium, semibold, bold;

    private Ui() {}

    static void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
        if (regular != null) return;
        light = font(c, 300);
        regular = font(c, 400);
        medium = font(c, 500);
        semibold = font(c, 600);
        bold = font(c, 700);
    }

    private static Typeface font(Context c, int weight) {
        try {
            Typeface t = new Typeface.Builder(c.getAssets(), "Inter.ttf")
                    .setFontVariationSettings("'wght' " + weight + ", 'opsz' 28")
                    .setWeight(weight)
                    .build();
            if (t != null) return t;
        } catch (RuntimeException ignored) {
        }
        return Typeface.create(Typeface.SANS_SERIF, weight, false);
    }

    private static android.media.SoundPool sounds;
    private static int tickId;
    private static long lastTick;

    /** The soft tvOS-style click played as focus moves. */
    static void tick(Context c) {
        if (sounds == null) {
            sounds = new android.media.SoundPool.Builder()
                    .setMaxStreams(2)
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_GAME) // follows the TV's main volume
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .build();
            tickId = sounds.load(c.getApplicationContext(), R.raw.tick, 1);
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTick < 45) return; // held arrow keys: don't machine-gun
        lastTick = now;
        sounds.play(tickId, 1f, 1f, 1, 0, 1f);
    }

    /** Debug: comma list from `adb shell setprop debug.tvhome.off ...` to switch effects off when profiling. */
    static String off = "";

    static boolean off(String what) {
        return off.contains(what);
    }

    static void readDebugFlags() {
        try {
            off = (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class)
                    .invoke(null, "debug.tvhome.off");
        } catch (Exception e) {
            off = "";
        }
        if (off == null) off = "";
    }

    static int dp(float v) {
        return Math.round(v * density);
    }

    static TextView text(Context c, float sp, Typeface tf, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Damped spring: overshoots once and settles, like tvOS focus motion. */
    static TimeInterpolator spring(final float damping, final float frequency) {
        return t -> (float) (1 - Math.exp(-damping * t) * Math.cos(frequency * t));
    }

    static void round(View v, final float radiusDp) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radiusDp));
            }
        });
        v.setClipToOutline(true);
    }

    /** Renders the app's artwork (banner, or glyph on a gradient) into a rounded card. */
    static FrameLayout art(Context c, AppEntry app, int w, int h, float radiusDp) {
        FrameLayout card = new FrameLayout(c);
        round(card, radiusDp);
        if (app.banner != null) {
            card.setBackgroundColor(Color.BLACK);
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(app.banner);
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            card.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {app.colorTop, app.colorBottom}));
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageDrawable(app.glyph);
            int s = Math.round(h * (app.special ? 0.48f : 0.56f));
            card.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        }
        return card;
    }

    /**
     * Bakes an app icon into one bitmap: artwork (cropped banner, or glyph on a gradient), top
     * gloss, bright rim and anti-aliased rounded corners. One bitmap draw per frame is far cheaper
     * on this TV's GPU than clipped child views.
     */
    static Bitmap bakeTile(AppEntry app, int w, int h, float r) {
        if (app.tile != null && app.tile.getWidth() == w && app.tile.getHeight() == h) return app.tile;
        if (app.members != null) return bakeFolder(app, w, h, r);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        if (app.banner != null) {
            cv.drawColor(Color.BLACK);
            drawCropped(cv, app.banner, w, h);
        } else {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {app.colorTop, app.colorBottom});
            g.setBounds(0, 0, w, h);
            g.draw(cv);
            if (app.glyph != null) {
                int s = Math.round(h * (app.special ? 0.48f : 0.56f));
                app.glyph.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
                app.glyph.draw(cv);
            }
        }
        Paint gloss = new Paint();
        gloss.setShader(new LinearGradient(0, 0, 0, h * 0.5f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h * 0.5f, gloss);

        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        float sw = Math.max(1.5f, dp(0.9f));
        rim.setStrokeWidth(sw);
        rim.setShader(new LinearGradient(0, 0, 0, h, new int[] {0x80FFFFFF, 0x14FFFFFF, 0x2EFFFFFF},
                new float[] {0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRoundRect(new RectF(sw / 2, sw / 2, w - sw / 2, h - sw / 2), r - sw / 2, r - sw / 2, rim);

        // Cut the corners away (anti-aliased).
        Path outside = new Path();
        outside.addRoundRect(new RectF(0, 0, w, h), r, r, Path.Direction.CW);
        outside.setFillType(Path.FillType.INVERSE_WINDING);
        Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
        cut.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        cv.drawPath(outside, cut);
        app.tile = b;
        return b;
    }

    /** A folder: frosted tile with a 3×2 preview of the apps inside, like tvOS. */
    private static Bitmap bakeFolder(AppEntry f, int w, int h, float r) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xFF8E8E94, 0xFF5E5E64});
        bg.setCornerRadius(r);
        bg.setBounds(0, 0, w, h);
        bg.draw(cv);
        float pad = w * 0.075f, gap = w * 0.04f;
        float mw = (w - 2 * pad - 2 * gap) / 3f, mh = mw * 0.6f;
        float top = (h - (2 * mh + gap)) / 2f;
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < Math.min(6, f.members.size()); i++) {
            AppEntry m = f.members.get(i);
            Bitmap mini = bakeTile(m, w, h, r);
            float x = pad + (i % 3) * (mw + gap), y = top + (i / 3) * (mh + gap);
            cv.drawBitmap(mini, null, new RectF(x, y, x + mw, y + mh), p);
        }
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        float sw = Math.max(1.5f, dp(0.9f));
        rim.setStrokeWidth(sw);
        rim.setColor(0x59FFFFFF);
        cv.drawRoundRect(new RectF(sw / 2, sw / 2, w - sw / 2, h - sw / 2), r - sw / 2, r - sw / 2, rim);
        f.tile = b;
        return b;
    }

    private static void drawCropped(Canvas cv, Drawable d, int w, int h) {
        int iw = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : w;
        int ih = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : h;
        float scale = Math.max(w / (float) iw, h / (float) ih);
        int dw = Math.round(iw * scale), dh = Math.round(ih * scale);
        int l = (w - dw) / 2, t = (h - dh) / 2;
        d.setBounds(l, t, l + dw, t + dh);
        d.draw(cv);
    }

    private static Bitmap shadow;

    static int shadowPad(int tileW) {
        return dp(20);
    }

    /** A soft rounded-rect shadow, rendered once and shared by every tile. */
    static Bitmap tileShadow(int w, int h) {
        int pad = shadowPad(w);
        if (shadow != null && shadow.getWidth() == w + 2 * pad) return shadow;
        Bitmap b = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xB3000000);
        p.setMaskFilter(new BlurMaskFilter(pad * 0.75f, BlurMaskFilter.Blur.NORMAL));
        cv.drawRoundRect(new RectF(pad, pad, pad + w, pad + h), dp(14), dp(14), p);
        shadow = b;
        return b;
    }

    /** Box-blurs (3 passes ≈ gaussian) a small bitmap in place. */
    static Bitmap blur(Bitmap src, int w, int h, int radius) {
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < 3; i++) {
            blurPass(px, w, h, radius, true);
            blurPass(px, w, h, radius, false);
        }
        small.setPixels(px, 0, w, 0, 0, w, h);
        return small;
    }

    private static void blurPass(int[] px, int w, int h, int r, boolean horizontal) {
        int[] out = new int[px.length];
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        int n = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < len; i++) {
                int rr = 0, gg = 0, bb = 0;
                for (int k = -r; k <= r; k++) {
                    int j = Math.min(len - 1, Math.max(0, i + k));
                    int c = px[horizontal ? line * w + j : j * w + line];
                    rr += (c >> 16) & 255;
                    gg += (c >> 8) & 255;
                    bb += c & 255;
                }
                out[horizontal ? line * w + i : i * w + line] =
                        0xFF000000 | ((rr / n) << 16) | ((gg / n) << 8) | (bb / n);
            }
        }
        System.arraycopy(out, 0, px, 0, px.length);
    }

    static int averageColor(Drawable d) {
        if (d == null) return 0xFF3A3A3C;
        Bitmap b = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        d.setBounds(0, 0, 32, 32);
        d.draw(cv);
        long r = 0, g = 0, bl = 0, n = 0;
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                int c = b.getPixel(x, y);
                if ((c >>> 24) < 128) continue;
                int cr = (c >> 16) & 255, cg = (c >> 8) & 255, cb = c & 255;
                // skip greys so the logo's brand colour wins
                int max = Math.max(cr, Math.max(cg, cb)), min = Math.min(cr, Math.min(cg, cb));
                if (max - min < 24) continue;
                r += cr; g += cg; bl += cb; n++;
            }
        }
        b.recycle();
        if (n == 0) return 0xFF3A3A3C;
        return Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
    }

    static int shade(int color, float f) {
        return Color.rgb(Math.min(255, Math.round(Color.red(color) * f)),
                Math.min(255, Math.round(Color.green(color) * f)),
                Math.min(255, Math.round(Color.blue(color) * f)));
    }
}
