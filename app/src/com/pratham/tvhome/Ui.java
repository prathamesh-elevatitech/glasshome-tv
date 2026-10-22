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
    /** Icon pack: "tvos" (logo on its colour, from the app's adaptive icon) or "banner" (the app's own). */
    static String iconPack = "tvos";

    static Bitmap bakeTile(AppEntry app, int w, int h, float r) {
        if (app.tile != null && app.tile.getWidth() == w && app.tile.getHeight() == h) return app.tile;
        if (app.members != null) return bakeFolder(app, w, h, r);
        if ("com.android.tv.settings".equals(app.key) && app.glyph != null) {
            return finishTile(app, drawGear(app.glyph, w, h), w, h, r);
        }
        if ("tvos".equals(iconPack) && !app.special && app.icon != null) {
            Bitmap art = app.icon instanceof android.graphics.drawable.AdaptiveIconDrawable
                    ? drawTvos((android.graphics.drawable.AdaptiveIconDrawable) app.icon, w, h)
                    : drawLegacy(app.icon, w, h);
            return finishTile(app, art, w, h, r);
        }
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
        return finishTile(app, b, w, h, r);
    }

    /**
     * tvOS-style icon from an adaptive icon: its background layer fills the tile, and its logo
     * (foreground) layer sits centred and large, with no wordy banner art.
     */
    private static Bitmap drawTvos(android.graphics.drawable.AdaptiveIconDrawable ai, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Drawable bg = ai.getBackground(), fg = ai.getForeground();
        if (bg != null) {
            // Stretch the (usually flat or gradient) background over the whole 5:3 tile.
            bg.setBounds(0, 0, w, h);
            bg.draw(cv);
        } else {
            cv.drawColor(Color.WHITE);
        }
        if (fg != null) {
            // Adaptive foregrounds are 108 units with the logo inside the central ~66: draw the
            // layer so that safe zone is about 70% of the tile height.
            int s = Math.round(h * 0.80f / 0.66f);
            fg.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
            fg.draw(cv);
        }
        return b;
    }

    /**
     * tvOS-style icon from a plain launcher icon. The logo inside the icon is measured and scaled
     * up until it fills the tile like a tvOS icon (wordmarks ~80% of the width, symbols ~66% of the
     * height). Icons with their own background keep it, extended to the tile edges (gradients
     * included); logo-only icons sit on white.
     */
    private static Bitmap drawLegacy(Drawable icon, int w, int h) {
        int n = 192; // measuring size
        Bitmap probe = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, n, n);
        icon.draw(new Canvas(probe));
        int e = Math.round(n * 0.06f);
        boolean opaque = Color.alpha(probe.getPixel(e, n / 2)) > 220 && Color.alpha(probe.getPixel(n / 2, e)) > 220;
        int bgc = opaque ? probe.getPixel(e, n / 2) : 0;
        // Bounding box of the logo: pixels that differ from the background (or are opaque).
        int l = n, t = n, r = -1, b = -1;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int c = probe.getPixel(x, y);
                boolean ink = opaque ? colorDistance(c, edgeAt(probe, x, y, bgc)) > 60 : Color.alpha(c) > 40;
                if (ink) {
                    if (x < l) l = x;
                    if (x > r) r = x;
                    if (y < t) t = y;
                    if (y > b) b = y;
                }
            }
        }
        probe.recycle();
        if (r < l || b < t) { l = 0; t = 0; r = n - 1; b = n - 1; }
        float cw = (r - l + 1) / (float) n, ch = (b - t + 1) / (float) n; // logo size, fraction of icon
        // Scale the whole icon so the logo fits 80% x 66% of the tile.
        float k = Math.min(w * 0.80f / cw, h * 0.66f / ch); // icon size in px
        int s = Math.round(k);
        float cx = (l + r + 1) / 2f / n, cy = (t + b + 1) / 2f / n; // logo centre, fraction
        float ox = w / 2f - cx * s, oy = h / 2f - cy * s;          // icon origin in the tile

        Bitmap ic = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, s, s);
        icon.draw(new Canvas(ic));
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        if (opaque) {
            // Extend the icon's own background: per tile row, the scaled icon's edge colours.
            Paint p = new Paint();
            int xl = Math.round(s * 0.06f), xr = s - 1 - xl;
            for (int y = 0; y < h; y++) {
                int iy = Math.max(0, Math.min(s - 1, Math.round(y - oy)));
                int cl = ic.getPixel(xl, iy), cr = ic.getPixel(xr, iy);
                if (Color.alpha(cl) < 220) cl = bgc;
                if (Color.alpha(cr) < 220) cr = bgc;
                // Blend left to right across the row, so diagonal gradients stay seamless.
                p.setShader(new LinearGradient(0, 0, w, 0, cl | 0xFF000000, cr | 0xFF000000, Shader.TileMode.CLAMP));
                cv.drawRect(0, y, w, y + 1, p);
            }
            // Draw the icon minus its rounded-corner fringe, so no corner arcs show.
            cv.save();
            cv.clipRect(ox + s * 0.06f, oy + s * 0.06f, ox + s * 0.94f, oy + s * 0.94f);
            cv.drawBitmap(ic, ox, oy, new Paint(Paint.FILTER_BITMAP_FLAG));
            cv.restore();
        } else {
            cv.drawColor(Color.WHITE);
            cv.drawBitmap(ic, ox, oy, new Paint(Paint.FILTER_BITMAP_FLAG));
        }
        ic.recycle();
        return out;
    }

    /** Settings: a brushed-metal gear with a soft shadow on a silver-grey gradient. */
    private static Bitmap drawGear(Drawable gear, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, 0, 0, h, 0xFFB4B4B9, 0xFF6C6C71, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, bg);
        int s = Math.round(h * 0.90f);
        Bitmap mask = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        gear.setBounds(0, 0, s, s);
        gear.draw(new Canvas(mask));
        float x = (w - s) / 2f, y = (h - s) / 2f;
        // Shadow under the gear.
        Paint sh = new Paint(Paint.FILTER_BITMAP_FLAG);
        sh.setColorFilter(new android.graphics.PorterDuffColorFilter(0x66000000, PorterDuff.Mode.SRC_IN));
        sh.setMaskFilter(new BlurMaskFilter(s * 0.05f, BlurMaskFilter.Blur.NORMAL));
        cv.drawBitmap(mask.extractAlpha(), x, y + s * 0.035f, sh);
        // The gear in a light-to-dark metal gradient, with a lighter top edge.
        Bitmap metal = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas mc = new Canvas(metal);
        mc.drawBitmap(mask, 0, 0, null);
        Paint fill = new Paint();
        fill.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        fill.setShader(new LinearGradient(0, 0, 0, s, new int[] {0xFFFFFFFF, 0xFFE3E3E8, 0xFFB9B9BF},
                new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        mc.drawRect(0, 0, s, s, fill);
        cv.drawBitmap(metal, x, y, new Paint(Paint.FILTER_BITMAP_FLAG));
        mask.recycle();
        metal.recycle();
        return out;
    }

    /** The background colour to compare against at (x, y): the icon's edge colour on that row. */
    private static int edgeAt(Bitmap b, int x, int y, int fallback) {
        int e = Math.round(b.getWidth() * 0.06f);
        int c = b.getPixel(x < b.getWidth() / 2 ? e : b.getWidth() - 1 - e, y);
        return Color.alpha(c) > 220 ? c : fallback;
    }

    private static int colorDistance(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b))
                + Math.abs(Color.blue(a) - Color.blue(b));
    }

    /** Gloss, rim and rounded corners on top of the artwork. */
    private static Bitmap finishTile(AppEntry app, Bitmap b, int w, int h, float r) {
        Canvas cv = new Canvas(b);
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
