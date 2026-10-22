package com.pratham.tvhome;

import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * tvOS App Switcher (double-press Home): a carousel of recently used apps. OK opens one, Up removes
 * it, Back dismisses. Needs the GET_USAGE_STATS app-op (granted by install.sh).
 */
final class AppSwitcher extends FrameLayout {
    private static final int MAX = 8;

    private final Runnable dismiss;
    private final HorizontalScrollView scroller;
    private final LinearLayout row;
    private final int cardW, cardH;

    AppSwitcher(Context c, int screenW, Runnable dismiss) {
        super(c);
        this.dismiss = dismiss;
        setBackgroundColor(0xF00B0B0F);
        setClipChildren(false);

        // A soft glow in the focused app's colour, behind the cards.
        glowFill = new android.graphics.drawable.GradientDrawable();
        glowFill.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        glowFill.setGradientRadius(Ui.dp(260));
        glowFill.setColors(new int[] {0x00000000, 0x00000000});
        View glow = new View(c);
        glow.setBackground(glowFill);
        LayoutParams glp = new LayoutParams(Ui.dp(560), Ui.dp(560), Gravity.CENTER);
        glp.rightMargin = Ui.dp(260);
        addView(glow, glp);

        TextView title = Ui.text(c, 20, Ui.semibold, Color.WHITE);
        title.setText("Recent Apps");
        LayoutParams tlp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tlp.leftMargin = Ui.dp(48);
        tlp.topMargin = Ui.dp(36);
        addView(title, tlp);
        addView(ramMeter(c), ramParams());
        cardW = Ui.dp(440);
        cardH = Math.round(cardW * 9 / 16f);

        scroller = new HorizontalScrollView(c);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setOverScrollMode(OVER_SCROLL_NEVER);
        row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        int side = (screenW - cardW) / 2;
        row.setPadding(side, 0, side, 0);
        row.setGravity(Gravity.CENTER_VERTICAL);
        scroller.addView(row, new HorizontalScrollView.LayoutParams(-2, -1));
        addView(scroller, new LayoutParams(-1, cardH + Ui.dp(120), Gravity.CENTER_VERTICAL));

        TextView hint = Ui.text(c, 15, Ui.medium, 0x99FFFFFF);
        hint.setText("Press ▲ on an app to remove it from Recents");
        LayoutParams hlp = new LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        hlp.bottomMargin = Ui.dp(48);
        addView(hint, hlp);

        List<String> apps = recents(c);
        if (apps.isEmpty()) {
            TextView none = Ui.text(c, 20, Ui.semibold, 0xCCFFFFFF);
            none.setText("No recent apps");
            addView(none, new LayoutParams(-2, -2, Gravity.CENTER));
        }
        for (int i = 0; i < apps.size(); i++) row.addView(item(c, apps.get(i)), itemParams(i));
    }

    private LinearLayout.LayoutParams itemParams(int i) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, -2);
        if (i > 0) lp.leftMargin = Ui.dp(36);
        return lp;
    }

    private final android.graphics.drawable.GradientDrawable glowFill;
    private int glowColor;
    private android.animation.ValueAnimator glowAnim;

    /** Blends the background glow to {@code c}. */
    private void glowTo(int c) {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = android.animation.ValueAnimator.ofObject(new android.animation.ArgbEvaluator(), glowColor, c);
        glowAnim.setDuration(420);
        glowAnim.addUpdateListener(a -> {
            glowColor = (int) a.getAnimatedValue();
            int rgb = glowColor & 0x00FFFFFF;
            glowFill.setColors(new int[] {0xB3000000 | rgb, 0x00000000 | rgb});
        });
        glowAnim.start();
    }

    private LayoutParams ramParams() {
        LayoutParams lp = new LayoutParams(Ui.dp(250), Ui.dp(40), Gravity.TOP | Gravity.END);
        lp.rightMargin = Ui.dp(48);
        lp.topMargin = Ui.dp(30);
        return lp;
    }

    /** "RAM 1.1 GB used of 1.8 GB" with a bar, refreshed while the switcher is open. */
    private View ramMeter(Context c) {
        GlassView pill = new GlassView(c, 20).solid(true).fallback(0x2EFFFFFF);
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
        final TextView t = Ui.text(c, 12, Ui.semibold, Color.WHITE);
        r.addView(t);
        final FrameLayout track = new FrameLayout(c);
        android.graphics.drawable.GradientDrawable tb = new android.graphics.drawable.GradientDrawable();
        tb.setColor(0x40FFFFFF);
        tb.setCornerRadius(Ui.dp(3));
        track.setBackground(tb);
        final View bar = new View(c);
        android.graphics.drawable.GradientDrawable bb = new android.graphics.drawable.GradientDrawable();
        bb.setColor(Color.WHITE);
        bb.setCornerRadius(Ui.dp(3));
        bar.setBackground(bb);
        track.addView(bar, new FrameLayout.LayoutParams(0, -1));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, Ui.dp(5), 1f);
        tl.leftMargin = Ui.dp(12);
        r.addView(track, tl);
        pill.addView(r, new FrameLayout.LayoutParams(-1, -1));
        final ActivityManager am = c.getSystemService(ActivityManager.class);
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                float total = mi.totalMem / 1073741824f, used = (mi.totalMem - mi.availMem) / 1073741824f;
                t.setText(String.format(java.util.Locale.US, "RAM %.1f GB used of %.1f GB", used, total));
                int w = track.getWidth();
                if (w > 0) {
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
                    lp.width = Math.round(w * used / total);
                    bar.setLayoutParams(lp);
                }
                if (isAttachedToWindow()) postDelayed(this, 1500);
            }
        };
        pill.post(tick);
        return pill;
    }

    void focusFirst() {
        if (row.getChildCount() > 0) row.getChildAt(0).requestFocus();
    }

    /** Most recently used launchable apps, newest first (excluding the home screen itself). */
    static List<String> recents(Context c) {
        List<String> out = new ArrayList<>();
        UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 7L * 86_400_000L, now);
        if (stats == null) return out;
        Map<String, Long> last = new HashMap<>();
        for (UsageStats s : stats) {
            if (s.getLastTimeUsed() > 0 && s.getTotalTimeInForeground() > 0) {
                Long prev = last.get(s.getPackageName());
                if (prev == null || s.getLastTimeUsed() > prev) last.put(s.getPackageName(), s.getLastTimeUsed());
            }
        }
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(last.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        PackageManager pm = c.getPackageManager();
        for (Map.Entry<String, Long> e : sorted) {
            String pkg = e.getKey();
            if (pkg.equals(c.getPackageName())) continue;
            // Closed from the switcher, and not opened again since.
            if (e.getValue() <= closedAt(c, pkg)) continue;
            if (launchIntent(pm, pkg) == null) continue;
            out.add(pkg);
            if (out.size() >= MAX) break;
        }
        return out;
    }

    private static long closedAt(Context c, String pkg) {
        return Profiles.prefs(c).getLong("closed:" + pkg, 0);
    }

    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    private View item(final Context c, final String pkg) {
        PackageManager pm = c.getPackageManager();
        CharSequence label = pkg;
        Drawable banner = null, icon = null;
        try {
            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            banner = pm.getApplicationBanner(pkg);
            icon = pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        AppEntry app = new AppEntry(pkg, label.toString(), launchIntent(pm, pkg), false);
        final int color = Ui.averageColor(banner != null ? banner : icon);
        app.banner = banner;
        if (banner == null) {
            app.glyph = icon;
            int col = Ui.averageColor(icon);
            app.colorTop = Ui.shade(col, 0.9f);
            app.colorBottom = Ui.shade(col, 0.5f);
        }

        final LinearLayout item = new LinearLayout(c) {
            @Override
            protected void onFocusChanged(boolean f, int dir, Rect prev) {
                super.onFocusChanged(f, dir, prev);
                if (f) {
                    Ui.tick(getContext());
                    scroller.smoothScrollTo(getLeft() + getWidth() / 2 - scroller.getWidth() / 2, 0);
                    glowTo(color);
                }
                animate().scaleX(f ? 1f : 0.86f).scaleY(f ? 1f : 0.86f).alpha(f ? 1f : 0.55f)
                        .setDuration(f ? 420 : 220)
                        .setInterpolator(f ? Ui.spring(7f, 11f) : new DecelerateInterpolator()).start();
            }
        };
        item.setOrientation(LinearLayout.VERTICAL);
        item.setFocusable(true);
        item.setClickable(true);
        item.setDefaultFocusHighlightEnabled(false);
        item.setClipChildren(false);
        item.setScaleX(0.86f);
        item.setScaleY(0.86f);
        item.setAlpha(0.55f);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        if (icon != null) {
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(icon);
            head.addView(iv, new LinearLayout.LayoutParams(Ui.dp(26), Ui.dp(26)));
        }
        TextView name = Ui.text(c, 17, Ui.semibold, Color.WHITE);
        name.setText(label);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(Ui.dp(10), 0, 0, 0);
        head.addView(name);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.bottomMargin = Ui.dp(14);
        item.addView(head, hlp);

        View art = Ui.art(c, app, cardW, cardH, 22);
        art.setElevation(Ui.dp(16));
        item.addView(art, new LinearLayout.LayoutParams(cardW, cardH));

        item.setOnClickListener(v -> {
            dismiss.run();
            try {
                c.startActivity(new Intent(app.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            } catch (RuntimeException ignored) {
            }
        });
        item.setOnKeyListener((v, keyCode, e) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP) return false;
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) close(item, pkg);
            return true;
        });
        return item;
    }

    /**
     * Remove from Recents: the card flies up and the app leaves the list. Android only lets a
     * launcher stop processes that are already idle, so busy apps keep running until Android
     * reclaims their memory itself; a real force-stop would need system or adb privileges.
     */
    private void close(final View item, final String pkg) {
        int i = row.indexOfChild(item);
        item.animate().translationY(-Ui.dp(500)).alpha(0f).setDuration(320)
                .setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
                    row.removeView(item);
                    for (int k = 0; k < row.getChildCount(); k++) {
                        row.getChildAt(k).setLayoutParams(itemParams(k));
                    }
                    if (row.getChildCount() == 0) {
                        dismiss.run();
                        return;
                    }
                    row.getChildAt(Math.min(i, row.getChildCount() - 1)).requestFocus();
                }).start();
        ActivityManager am = getContext().getSystemService(ActivityManager.class);
        am.killBackgroundProcesses(pkg);
        // Android keeps its "last used" record, so remember the close ourselves.
        Profiles.prefs(getContext()).edit().putLong("closed:" + pkg, System.currentTimeMillis()).apply();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) dismiss.run();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
