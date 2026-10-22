package com.pratham.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Liquid-glass surface: samples the blurred wallpaper behind it, then adds a light tint, a top
 * gloss and a bright rim. Focusable instances fill with white when focused, like tvOS buttons.
 * Without a backdrop (e.g. over another app) it falls back to dark frosted glass.
 */
class GlassView extends FrameLayout {
    static Backdrop backdrop;

    /** Appearance: Frosted (milky, softer) instead of clear Liquid Glass; Dark mode darkens it. */
    static boolean frostedStyle, darkStyle;
    private static final Paint styleWash = new Paint(Paint.ANTI_ALIAS_FLAG);

    static void loadStyle(Context c) {
        frostedStyle = Appearance.frosted(c);
        darkStyle = Appearance.dark(c);
    }

    private final float radius;
    private final RectF rect = new RectF();
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix local = new Matrix();
    private final int[] loc = new int[2];
    private BitmapShader shader;
    private Object shaderSource;
    private int tintColor = 0x2EFFFFFF;
    private int fallbackColor = 0xE0242428;
    private boolean solid;
    private boolean plain;
    private float lit;
    private ValueAnimator litAnim;
    private final List<TextView> texts = new ArrayList<>();
    private final List<ImageView> icons = new ArrayList<>();

    GlassView(Context c, float radiusDp) {
        super(c);
        radius = Ui.dp(radiusDp);
        setWillNotDraw(false);
        setClipChildren(false);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1.5f, Ui.dp(1)));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
    }

    GlassView tint(int color) {
        tintColor = color;
        return this;
    }

    GlassView fallback(int color) {
        fallbackColor = color;
        return this;
    }

    /** Neutral, darker frost: the backdrop loses most of its colour (tvOS menus and popovers). */
    GlassView neutral() {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(0.15f);
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(0.7f, 0.7f, 0.7f, 1f);
        m.postConcat(dim);
        glass.setColorFilter(new ColorMatrixColorFilter(m));
        return this;
    }

    /** A menu row: invisible until focused, then a white highlight (tvOS menus). */
    GlassView plain() {
        plain = true;
        return this;
    }

    /** Ignore the wallpaper and always draw the frosted fallback. */
    GlassView solid(boolean on) {
        solid = on;
        return this;
    }

    /** Makes this glass a focusable button whose given content flips to dark when lit. */
    GlassView button(View.OnClickListener click, TextView[] t, ImageView[] i) {
        setFocusable(true);
        setClickable(true);
        setDefaultFocusHighlightEnabled(false);
        setOnClickListener(click);
        for (TextView v : t) texts.add(v);
        for (ImageView v : i) icons.add(v);
        return this;
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        if (focused) Ui.tick(getContext());
        if (litAnim != null) litAnim.cancel();
        litAnim = ValueAnimator.ofFloat(lit, focused ? 1f : 0f);
        litAnim.setDuration(focused ? 200 : 160);
        litAnim.setInterpolator(new DecelerateInterpolator());
        litAnim.addUpdateListener(a -> setLit((float) a.getAnimatedValue()));
        litAnim.start();
        float s = focused ? (plain ? 1.03f : 1.06f) : 1f;
        animate().scaleX(s).scaleY(s).translationZ(focused && !plain ? Ui.dp(14) : 0)
                .setInterpolator(focused ? Ui.spring(7f, 12f) : new DecelerateInterpolator())
                .setDuration(focused ? 420 : 200).start();
    }

    private void setLit(float v) {
        lit = v;
        int c = blend(Color.WHITE, 0xFF1C1C1E, v);
        for (TextView t : texts) t.setTextColor(t.getTag() instanceof Integer ? blend((Integer) t.getTag(), 0xB31C1C1E, v) : c);
        for (ImageView i : icons) i.setColorFilter(c, PorterDuff.Mode.SRC_IN);
        invalidate();
    }

    private static int blend(int a, int b, float t) {
        return Color.argb(
                Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (backdrop != null) backdrop.watch(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (backdrop != null) backdrop.unwatch(this);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        gloss.setShader(new LinearGradient(0, 0, 0, h * 0.6f, 0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(0, 0, 0, h, new int[] {0x8CFFFFFF, 0x1AFFFFFF, 0x40FFFFFF},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (Ui.off("glass")) return;
        rect.set(0, 0, getWidth(), getHeight());
        if (plain) {
            if (lit > 0f) {
                fill.setColor(Color.argb(Math.round(lit * 240), 255, 255, 255));
                c.drawRoundRect(rect, radius, radius, fill);
            }
            return;
        }
        Backdrop b = backdrop;
        if (!solid && b != null && b.blurred != null) {
            if (shaderSource != b.blurred) {
                shader = new BitmapShader(b.blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                shaderSource = b.blurred;
                glass.setShader(shader);
            }
            getLocationInWindow(loc);
            local.set(b.blurMatrix);
            local.postTranslate(-loc[0], -loc[1]);
            local.postScale(1f / Math.max(0.01f, getScaleX()), 1f / Math.max(0.01f, getScaleY()),
                    0, 0);
            shader.setLocalMatrix(local);
            c.drawRoundRect(rect, radius, radius, glass);
            tint.setColor(tintColor);
        } else {
            tint.setColor(fallbackColor);
        }
        c.drawRoundRect(rect, radius, radius, tint);
        if (frostedStyle || darkStyle) {
            // Frosted: a milky layer that hides more of what's behind. Dark: a smoky layer.
            styleWash.setColor(frostedStyle ? (darkStyle ? 0x8C2C2C30 : 0x4DE5E5EA) : 0x59000000);
            c.drawRoundRect(rect, radius, radius, styleWash);
        }
        if (!(frostedStyle && darkStyle)) c.drawRoundRect(rect, radius, radius, gloss);
        if (lit > 0f) {
            fill.setColor(Color.argb(Math.round(lit * 240), 255, 255, 255));
            c.drawRoundRect(rect, radius, radius, fill);
        }
        float inset = rim.getStrokeWidth() / 2f;
        rect.inset(inset, inset);
        c.drawRoundRect(rect, radius - inset, radius - inset, rim);
    }
}
