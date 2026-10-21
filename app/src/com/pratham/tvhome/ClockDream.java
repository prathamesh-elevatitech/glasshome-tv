package com.pratham.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;
import java.util.Random;

/** Minimal screensaver: a large clock over slowly drifting colour glows. Moves every minute to avoid burn-in. */
public class ClockDream extends DreamService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private LinearLayout box;
    private TextView time, date;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Date now = new Date();
            time.setText(DateFormat.getTimeFormat(ClockDream.this).format(now));
            date.setText(DateFormat.format("EEEE, d MMMM", now));
            box.animate().alpha(0f).setDuration(800).withEndAction(() -> {
                box.setTranslationX((random.nextFloat() - 0.5f) * Ui.dp(260));
                box.setTranslationY((random.nextFloat() - 0.5f) * Ui.dp(160));
                box.animate().alpha(1f).setDuration(1200).start();
            }).start();
            handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Ui.init(this);
        setInteractive(false);
        setFullscreen(true);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(glow(0x553478F6, -1), new FrameLayout.LayoutParams(Ui.dp(700), Ui.dp(700), Gravity.START | Gravity.TOP));
        root.addView(glow(0x44BF5AF2, 1), new FrameLayout.LayoutParams(Ui.dp(640), Ui.dp(640), Gravity.END | Gravity.BOTTOM));

        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        time = new TextView(this);
        time.setTypeface(Ui.light);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 120);
        time.setTextColor(0xEEFFFFFF);
        time.setLetterSpacing(-0.03f);
        date = new TextView(this);
        date.setTypeface(Ui.medium);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        date.setTextColor(0x99FFFFFF);
        box.addView(time);
        box.addView(date);
        root.addView(box, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(root);
        handler.post(tick);
    }

    private View glow(int color, int dir) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(Ui.dp(340));
        g.setColors(new int[] {color, 0x00000000});
        v.setBackground(g);
        ObjectAnimator a = ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0, dir * Ui.dp(320));
        a.setDuration(45_000);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.start();
        return v;
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }
}
