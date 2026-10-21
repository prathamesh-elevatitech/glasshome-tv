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
 * tvOS App Switcher (double-press Home): a carousel of recently used apps. OK opens one, Up closes
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
        setBackgroundColor(0xEB0B0B0F);
        setClipChildren(false);
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
        hint.setText("OK to open  ·  ▲ to close an app");
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
            if (launchIntent(pm, pkg) == null) continue;
            out.add(pkg);
            if (out.size() >= MAX) break;
        }
        return out;
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

    /** Swipe-up-to-quit: the card flies up and the app's background process is killed. */
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
