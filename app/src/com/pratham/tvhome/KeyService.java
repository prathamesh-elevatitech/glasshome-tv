package com.pratham.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Always-running helper for things only the Google TV launcher used to do:
 * <ul>
 *   <li>the remote's gear button (KEYCODE_NOTIFICATION) opens Control Center, in place on the
 *       home screen or as a frosted overlay on top of any app;</li>
 *   <li>Netflix / Prime / YouTube buttons open their apps;</li>
 *   <li>double-pressing Home opens the App Switcher;</li>
 *   <li>Sleep and the sleep timer;</li>
 *   <li>the profile picker when the TV wakes.</li>
 * </ul>
 * TCL's boot manager blocks this service unless the app holds APP_AUTO_START (install.sh).
 */
public class KeyService extends AccessibilityService implements ControlCenter.Host {
    private static final String TAG = "TvHomeKeys";
    private static final long DOUBLE_PRESS_MS = 320;
    private static final int[] TIMER_STEPS = {0, 15, 30, 60, 90};

    /**
     * TCL vendor keycodes for the remote's app buttons. The disabled Google TV launcher used to
     * handle these; Sony LIV, TCL Channel and Web are handled by TCL itself.
     */
    private static final Map<Integer, String> APP_KEYS = new HashMap<>();

    static {
        APP_KEYS.put(4062, "com.netflix.ninja");
        APP_KEYS.put(4099, "com.amazon.amazonvideo.livingroom");
        APP_KEYS.put(4065, "com.google.android.youtube.tv");
    }

    private static KeyService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private WindowManager wm;
    private SharedPreferences prefs;
    private View overlay;
    private AppSwitcher switcher;
    private int screenW, screenH;
    private Bitmap wallpaper;
    private int wallIndex;
    private boolean homePending;
    private long sleepAt;
    private int timerStep;

    private final Runnable singleHome = () -> {
        homePending = false;
        closeControlCenter();
        performGlobalAction(GLOBAL_ACTION_HOME);
    };

    private final Runnable sleepTimer = () -> {
        sleepAt = 0;
        timerStep = 0;
        sleep();
    };

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Like tvOS: wake to the home screen and ask who's watching.
            if (Profiles.NAMES.length < 2 || !prefs.getBoolean("wake_picker", true)) return;
            startActivity(new Intent(KeyService.this, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_WAKE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Ui.init(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = Profiles.prefs(this);
        Rect bounds = wm.getCurrentWindowMetrics().getBounds();
        screenW = bounds.width();
        screenH = bounds.height();
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        loadWallpaper(null);
        Log.d(TAG, "connected");
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        try {
            unregisterReceiver(screenOn);
        } catch (RuntimeException ignored) {
        }
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean up = e.getAction() == KeyEvent.ACTION_UP;
        String app = APP_KEYS.get(k);
        if (app != null) {
            if (up) openApp(app);
            return true;
        }
        if (k == KeyEvent.KEYCODE_HOME) {
            if (up) onHome();
            return true;
        }
        if (k != KeyEvent.KEYCODE_NOTIFICATION) return false;
        if (up) {
            dismissSwitcher();
            MainActivity home = MainActivity.resumed;
            if (overlay != null) closeControlCenter();
            else if (home != null) home.toggleControlCenter();
            else show();
        }
        return true;
    }

    /** Single press goes home (after a short wait); a second press within the window opens the switcher. */
    private void onHome() {
        if (switcher != null) {
            dismissSwitcher();
            performGlobalAction(GLOBAL_ACTION_HOME);
            return;
        }
        if (homePending) {
            homePending = false;
            handler.removeCallbacks(singleHome);
            showSwitcher();
        } else {
            homePending = true;
            handler.postDelayed(singleHome, DOUBLE_PRESS_MS);
        }
    }

    private WindowManager.LayoutParams overlayParams() {
        return new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
    }

    // ---------------------------------------------------------------- app switcher

    private void showSwitcher() {
        closeControlCenter();
        AppSwitcher s = new AppSwitcher(this, screenW, this::dismissSwitcher);
        try {
            wm.addView(s, overlayParams());
        } catch (RuntimeException ex) {
            Log.w(TAG, "switcher failed", ex);
            return;
        }
        switcher = s;
        s.setAlpha(0f);
        s.setScaleX(1.04f);
        s.setScaleY(1.04f);
        s.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
        s.post(s::focusFirst);
    }

    private void dismissSwitcher() {
        if (switcher == null) return;
        View v = switcher;
        switcher = null;
        v.animate().alpha(0f).setDuration(160).withEndAction(() -> wm.removeView(v)).start();
    }

    // ---------------------------------------------------------------- control center overlay

    private void show() {
        Root root = new Root(this);
        root.setBackgroundColor(0x40000000);
        View panel = ControlCenter.build(this, this, true);
        int w = Ui.dp(ControlCenter.WIDTH);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        lp.rightMargin = Ui.dp(48);
        lp.topMargin = Ui.dp(30 + 48 + 12); // same spot as on the home screen
        root.addView(panel, lp);
        try {
            wm.addView(root, overlayParams());
        } catch (RuntimeException ex) {
            Log.w(TAG, "overlay failed", ex);
            return;
        }
        overlay = root;

        panel.setPivotX(w - Ui.dp(24));
        panel.setPivotY(0f);
        panel.setScaleX(0.6f);
        panel.setScaleY(0.6f);
        panel.setAlpha(0f);
        panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(560)
                .setInterpolator(Ui.spring(7.5f, 10f)).setUpdateListener(a -> panel.invalidate()).start();
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(180).start();
        View first = (View) panel.getTag();
        if (first != null) first.post(first::requestFocus);
    }

    @Override
    public void closeControlCenter() {
        if (overlay == null) return;
        View v = overlay;
        overlay = null;
        v.animate().alpha(0f).setDuration(160).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> wm.removeView(v)).start();
    }

    // ---------------------------------------------------------------- sleep

    /** Puts the TV to sleep (Control Center → Sleep). */
    static void sleepNow(Context c) {
        if (instance == null) {
            Toast.makeText(c, "Press Home once, then try again", Toast.LENGTH_SHORT).show();
            return;
        }
        instance.sleep();
    }

    private void sleep() {
        closeControlCenter();
        dismissSwitcher();
        if (!performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)) performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
    }

    /** "Off", or the minutes left. */
    static String sleepTimerValue() {
        KeyService s = instance;
        if (s == null || s.sleepAt == 0) return "Off";
        long mins = Math.max(1, (s.sleepAt - SystemClock.uptimeMillis() + 59_999) / 60_000);
        return mins + " min left";
    }

    /** Off → 15 → 30 → 60 → 90 → Off. Returns the new status. */
    static String cycleSleepTimer(Context c) {
        KeyService s = instance;
        if (s == null) {
            Toast.makeText(c, "Press Home once, then try again", Toast.LENGTH_SHORT).show();
            return "Off";
        }
        s.timerStep = (s.timerStep + 1) % TIMER_STEPS.length;
        s.handler.removeCallbacks(s.sleepTimer);
        int mins = TIMER_STEPS[s.timerStep];
        if (mins == 0) {
            s.sleepAt = 0;
        } else {
            s.sleepAt = SystemClock.uptimeMillis() + mins * 60_000L;
            s.handler.postAtTime(s.sleepTimer, s.sleepAt);
        }
        return sleepTimerValue();
    }

    // ---------------------------------------------------------------- wallpaper card

    private void loadWallpaper(Runnable done) {
        int raw = prefs.getInt(Profiles.key(prefs, "wall"), Calendar.getInstance().get(Calendar.DAY_OF_YEAR));
        final int i = Math.floorMod(raw, Backdrop.WALLPAPERS.length);
        if (wallpaper != null && i == wallIndex) {
            if (done != null) done.run();
            return;
        }
        worker.execute(() -> {
            try {
                Bitmap[] b = Backdrop.load(this, i, screenW, screenH);
                b[1].recycle();
                handler.post(() -> {
                    wallpaper = b[0];
                    wallIndex = i;
                    if (done != null) done.run();
                });
            } catch (Exception e) {
                Log.w(TAG, "wallpaper load failed", e);
            }
        });
    }

    @Override
    public Bitmap wallpaper() {
        // The profile may have changed since we loaded; refresh for next time.
        loadWallpaper(null);
        return wallpaper;
    }

    @Override
    public int wallpaperIndex() {
        return wallIndex;
    }

    @Override
    public void cycleWallpaper(Runnable done) {
        // The home screen picks this up when it next comes to the front.
        prefs.edit().putInt(Profiles.key(prefs, "wall"), Math.floorMod(wallIndex + 1, Backdrop.WALLPAPERS.length)).apply();
        loadWallpaper(done);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    /** Opens an app from a remote shortcut button, or its Play Store page if it isn't installed. */
    private void openApp(String pkg) {
        closeControlCenter();
        dismissSwitcher();
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (RuntimeException ex) {
            Log.w(TAG, "couldn't open " + pkg, ex);
        }
    }

    /** Closes the overlay on Back. */
    private final class Root extends FrameLayout {
        Root(Context c) {
            super(c);
            setClipChildren(false);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) closeControlCenter();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
    }
}
