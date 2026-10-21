package com.pratham.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * A tvOS-style app icon: springs up, tilts toward where focus came from, catches a light sweep,
 * and settles.
 *
 * Built for a weak TV GPU: the artwork, rounded corners, gloss and rim are baked into one bitmap
 * (see {@link Ui#bakeTile}), and the shadow is a shared pre-blurred image rather than a live
 * elevation shadow, so a focus move costs a few bitmap draws per frame.
 */
final class TileView extends FrameLayout {
    static final float FOCUS_SCALE = 1.15f;

    final AppEntry app;
    final Card card;
    private final ImageView shadow;
    private final TextView label;
    private final int cardW, cardH;
    private ValueAnimator sheenAnim;
    private ObjectAnimator wiggle;

    TileView(Context c, AppEntry app, int w, int h) {
        super(c);
        this.app = app;
        this.cardW = w;
        this.cardH = h;
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        setDefaultFocusHighlightEnabled(false);

        int pad = Ui.shadowPad(w);
        shadow = new ImageView(c);
        shadow.setImageBitmap(Ui.tileShadow(w, h));
        shadow.setScaleType(ImageView.ScaleType.FIT_XY);
        shadow.setAlpha(0f); // only the focused icon casts a shadow (saves ~30 blended quads a frame)
        LayoutParams slp = new LayoutParams(w + 2 * pad, h + 2 * pad);
        slp.leftMargin = -pad;
        slp.topMargin = -pad + Ui.dp(5);
        addView(shadow, slp);

        card = new Card(c, Ui.bakeTile(app, w, h, Ui.dp(14)), Ui.dp(14));
        card.setCameraDistance(Ui.dp(900) * 10);
        addView(card, new LayoutParams(w, h));

        label = Ui.text(c, 14, Ui.semibold, Color.WHITE);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setShadowLayer(Ui.dp(8), 0, Ui.dp(1), 0xB3000000);
        label.setAlpha(0f);
        LayoutParams lp = new LayoutParams(w + Ui.dp(50), LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        lp.topMargin = h + Ui.dp(12);
        addView(label, lp);
    }

    // Physics springs: a new focus change picks up from the current position AND velocity, so
    // quick presses blend into one fluid motion instead of restarting the animation.
    private final Spring scale = new Spring(1f, 420f, 0.72f);
    private final Spring tiltX = new Spring(0f, 260f, 0.5f);
    private final Spring tiltY = new Spring(0f, 260f, 0.5f);
    private long lastFrame;
    private boolean ticking;
    private final android.view.Choreographer.FrameCallback frame = new android.view.Choreographer.FrameCallback() {
        @Override
        public void doFrame(long now) {
            float dt = lastFrame == 0 ? 1 / 60f : Math.min(0.05f, (now - lastFrame) / 1e9f);
            lastFrame = now;
            boolean moving = scale.step(dt) | tiltX.step(dt) | tiltY.step(dt);
            apply();
            if (moving) {
                android.view.Choreographer.getInstance().postFrameCallback(this);
            } else {
                ticking = false;
                lastFrame = 0;
            }
        }
    };

    private void kick() {
        if (ticking) return;
        ticking = true;
        lastFrame = 0;
        android.view.Choreographer.getInstance().postFrameCallback(frame);
    }

    private void apply() {
        float s = scale.value;
        card.setScaleX(s);
        card.setScaleY(s);
        if (wiggle == null) {
            card.setRotationX(tiltX.value);
            card.setRotationY(tiltY.value);
        }
        float p = Math.max(0f, Math.min(1f, (s - 1f) / (FOCUS_SCALE - 1f)));
        shadow.setAlpha(0.9f * p);
        shadow.setScaleX(1f + (FOCUS_SCALE + 0.04f - 1f) * p);
        shadow.setScaleY(1f + (FOCUS_SCALE + 0.08f - 1f) * p);
        shadow.setTranslationY(Ui.dp(12) * p);
        label.setTranslationY(cardH * (s - 1f) / 2f + Ui.dp(2) * p);
    }

    @Override
    protected void onDetachedFromWindow() {
        android.view.Choreographer.getInstance().removeFrameCallback(frame);
        ticking = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        if (focused) {
            Ui.tick(getContext());
            scale.target = FOCUS_SCALE;
            // Lean in the direction of travel via a velocity kick (no jump), then spring flat.
            float v = 120f;
            if (direction == View.FOCUS_RIGHT) tiltY.velocity -= v;
            else if (direction == View.FOCUS_LEFT) tiltY.velocity += v;
            else if (direction == View.FOCUS_DOWN) tiltX.velocity += v;
            else if (direction == View.FOCUS_UP) tiltX.velocity -= v;
            sweep();
            label.animate().alpha(1f).setDuration(180).setStartDelay(0).start();
        } else {
            scale.target = 1f;
            if (sheenAnim != null) sheenAnim.cancel();
            card.setSheen(-1f);
            label.animate().alpha(0f).setDuration(150).setStartDelay(0).start();
            setWiggling(false);
        }
        kick();
    }

    /** A damped harmonic spring (semi-implicit Euler). */
    private static final class Spring {
        float value, velocity, target;
        final float stiffness, damping;

        Spring(float v, float stiffness, float dampingRatio) {
            value = target = v;
            this.stiffness = stiffness;
            damping = 2f * dampingRatio * (float) Math.sqrt(stiffness);
        }

        /** Advances by {@code dt} seconds; returns whether it's still moving. */
        boolean step(float dt) {
            for (int i = 0; i < 2; i++) { // two half-steps for stability at low frame rates
                float h = dt / 2f;
                velocity += (-stiffness * (value - target) - damping * velocity) * h;
                value += velocity * h;
            }
            if (Math.abs(value - target) < 0.0005f && Math.abs(velocity) < 0.01f) {
                value = target;
                velocity = 0f;
                return false;
            }
            return true;
        }
    }

    /** One diagonal light sweep across the icon. */
    private void sweep() {
        if (sheenAnim != null) sheenAnim.cancel();
        if (Ui.off("sheen")) return;
        sheenAnim = ValueAnimator.ofFloat(0f, 1f);
        sheenAnim.setDuration(600);
        sheenAnim.setStartDelay(60);
        sheenAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        sheenAnim.addUpdateListener(a -> card.setSheen((float) a.getAnimatedValue()));
        sheenAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                card.setSheen(-1f);
            }
        });
        sheenAnim.start();
    }

    /** Icon only, no name under it when focused (used in the dock). */
    void hideLabel() {
        label.setVisibility(View.GONE);
    }

    void setWiggling(boolean on) {
        if (on && wiggle == null) {
            card.setRotationX(0f);
            wiggle = ObjectAnimator.ofFloat(card, View.ROTATION, -1.6f, 1.6f);
            wiggle.setDuration(130);
            wiggle.setRepeatMode(ValueAnimator.REVERSE);
            wiggle.setRepeatCount(ValueAnimator.INFINITE);
            wiggle.start();
        } else if (!on && wiggle != null) {
            wiggle.cancel();
            wiggle = null;
            card.setRotation(0f);
        }
    }

    /** The baked icon, plus the light sweep drawn on top while it runs. */
    static final class Card extends View {
        private final Bitmap art;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF bounds = new RectF();
        private final float radius;
        private float sheen = -1f;

        Card(Context c, Bitmap art, float radius) {
            super(c);
            this.art = art;
            this.radius = radius;
        }

        void setSheen(float t) {
            sheen = t;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            bounds.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
            float band = w * 0.35f;
            sheenPaint.setShader(new LinearGradient(0, 0, band, band * 0.45f,
                    new int[] {0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawBitmap(art, null, bounds, paint);
            if (sheen >= 0f) {
                float w = getWidth(), band = w * 0.35f;
                c.save();
                c.clipPath(clip);
                c.translate(-band + sheen * (w + band * 2), 0);
                c.drawRect(0, 0, band, getHeight(), sheenPaint);
                c.restore();
            }
        }
    }
}
