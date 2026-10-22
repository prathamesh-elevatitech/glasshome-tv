package com.pratham.tvhome;

import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * tvOS 27-style Control Center: no surrounding panel; individual glass controls float under the
 * top-right status icons: a large Sleep tile, two-line pills (Wi-Fi with the network name,
 * Bluetooth, Sound, Sleep Timer), a row of round buttons, and Now Playing when media is active.
 */
final class ControlCenter {
    interface Host {
        void closeControlCenter();

        /** Current wallpaper preview, or null when there is none. */
        Bitmap wallpaper();

        int wallpaperIndex();

        /** Switches to the next wallpaper and runs {@code done} once it is showing. */
        void cycleWallpaper(Runnable done);
    }

    // Sizes in dp, matching the compact tvOS 27 layout: tile and pills the same width.
    private static final int GAP = 6;
    private static final int TILE = 104;
    private static final int PILL_W = 104;
    private static final int PILL_H = (TILE - GAP) / 2;
    static final int WIDTH = TILE + GAP + PILL_W;

    private ControlCenter() {}

    static View build(Context c, Host host) {
        return build(c, host, false);
    }

    /** Every control Control Center can show. {id, name, "pill" | "circle" | "card"}. */
    static final String[][] ITEMS = {
            {"wifi", "Wi-Fi", "pill"},
            {"bluetooth", "Bluetooth", "pill"},
            {"sound", "Sound", "pill"},
            {"timer", "Sleep Timer", "pill"},
            {"picture", "Picture", "pill"},
            {"wallpaper", "Wallpaper", "pill"},
            {"inputs", "Inputs", "circle"},
            {"aerials", "Screen Saver", "circle"},
            {"search", "Search", "circle"},
            {"settings", "Settings", "circle"},
            {"appearance", "Appearance", "circle"},
            {"nowplaying", "Now Playing", "card"},
    };
    static final String DEFAULT_ON = "wifi,bluetooth,sound,timer,inputs,aerials,search,settings,nowplaying";

    static java.util.List<String> enabled(Context c) {
        String raw = Profiles.prefs(c).getString("cc_items", DEFAULT_ON);
        return new java.util.ArrayList<>(java.util.Arrays.asList(raw.split(",")));
    }

    static void setEnabled(Context c, java.util.List<String> ids) {
        Profiles.prefs(c).edit().putString("cc_items", String.join(",", ids)).apply();
    }

    /**
     * {@code overApp}: shown over another app. tvOS 27 doesn't blur what's behind Control
     * Center, so the controls are frosted grey glass, a little darker over apps for legibility.
     * Which controls appear is chosen in {@link ControlCenterSettings}; Sleep is always there.
     */
    static View build(Context c, Host host, boolean overApp) {
        int fill = overApp ? 0xD92C2C30 : 0xB3434349;
        java.util.List<String> on = enabled(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);

        // Sleep tile, always first.
        GlassView sleep = new GlassView(c, 26).solid(true).fallback(fill);
        LinearLayout st = new LinearLayout(c);
        st.setOrientation(LinearLayout.VERTICAL);
        st.setGravity(Gravity.CENTER);
        ImageView power = new ImageView(c);
        power.setImageResource(R.drawable.ic_power);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(34), Ui.dp(34));
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        st.addView(power, plp);
        TextView sleepLabel = Ui.text(c, 12, Ui.semibold, Color.WHITE);
        sleepLabel.setText("Sleep");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.gravity = Gravity.CENTER_HORIZONTAL;
        slp.topMargin = Ui.dp(7);
        st.addView(sleepLabel, slp);
        sleep.addView(st, new FrameLayout.LayoutParams(-1, -1));
        sleep.button(v -> {
            host.closeControlCenter();
            KeyService.sleepNow(c);
        }, new TextView[] {sleepLabel}, new ImageView[] {power});

        // Pills, in the order of ITEMS: the first two stack beside Sleep, the rest go two per row.
        java.util.List<GlassView> pills = new java.util.ArrayList<>();
        for (String[] it : ITEMS) {
            if (!"pill".equals(it[2]) || !on.contains(it[0])) continue;
            pills.add(makePill(c, it[0], host, fill));
        }
        LinearLayout row1 = row(c);
        row1.addView(sleep, new LinearLayout.LayoutParams(Ui.dp(TILE), Ui.dp(TILE)));
        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setClipChildren(false);
        for (int i = 0; i < Math.min(2, pills.size()); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(PILL_W), Ui.dp(PILL_H));
            if (i > 0) lp.topMargin = Ui.dp(GAP);
            right.addView(pills.get(i), lp);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.leftMargin = Ui.dp(GAP);
        row1.addView(right, rlp);
        col.addView(row1);
        LinearLayout r = null;
        for (int i = 2; i < pills.size(); i++) {
            if ((i - 2) % 2 == 0) {
                r = row(c);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.topMargin = Ui.dp(GAP);
                col.addView(r, lp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(PILL_W), Ui.dp(PILL_H));
            if ((i - 2) % 2 == 1) lp.leftMargin = Ui.dp(GAP);
            r.addView(pills.get(i), lp);
        }

        // Round buttons, then the Customise button; a caption names the focused one.
        TextView caption = Ui.text(c, 12, Ui.semibold, 0xCCFFFFFF);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        caption.setText(" ");
        caption.setShadowLayer(Ui.dp(6), 0, 0, 0x99000000);
        java.util.List<Object[]> circles = new java.util.ArrayList<>();
        for (String[] it : ITEMS) {
            if (!"circle".equals(it[2]) || !on.contains(it[0])) continue;
            circles.add(circleSpec(c, it[0], it[1]));
        }
        circles.add(new Object[] {R.drawable.ic_edit, "Customise", new Intent(c, ControlCenterSettings.class)});
        int size = 38, perRow = 5;
        int gap = (WIDTH - perRow * size) / (perRow - 1);
        LinearLayout cr = null;
        for (int i = 0; i < circles.size(); i++) {
            if (i % perRow == 0) {
                cr = row(c);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.topMargin = Ui.dp(i == 0 ? 10 : 8);
                col.addView(cr, lp);
            }
            Object[] spec = circles.get(i);
            final String name = (String) spec[1];
            GlassView g = circle(c, (Integer) spec[0], spec[2], true, host, fill);
            g.setOnFocusChangeListener((v, has) -> caption.setText(has ? name : " "));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(size), Ui.dp(size));
            if (i % perRow != 0) lp.leftMargin = Ui.dp(gap);
            cr.addView(g, lp);
        }
        caption.setPadding(0, Ui.dp(7), 0, 0);
        col.addView(caption, new LinearLayout.LayoutParams(Ui.dp(WIDTH), -2));

        if (on.contains("nowplaying")) {
            MediaController media = MediaListener.nowPlaying(c);
            if (media != null) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(WIDTH), Ui.dp(118));
                lp.topMargin = Ui.dp(8);
                col.addView(nowPlayingCard(c, media), lp);
            }
        }

        // First focus: the first pill, never Sleep, so a stray OK can't power off the TV.
        col.setTag(pills.isEmpty() ? sleep : pills.get(0));
        return col;
    }

    private static GlassView makePill(Context c, String id, Host host, int fill) {
        switch (id) {
            case "wifi":
                return pill(c, R.drawable.ic_wifi, 0xFF0A84FF, "Wi-Fi", wifiName(c),
                        new Intent(Settings.ACTION_WIFI_SETTINGS), true, host, fill, null);
            case "bluetooth":
                return pill(c, R.drawable.ic_bluetooth, 0xFF0A84FF, "Bluetooth", bluetoothState(),
                        bluetoothIntent(c), true, host, fill, null);
            case "sound":
                return pill(c, R.drawable.ic_sound, 0xFFFF375F, "Sound", null,
                        new Intent(Settings.ACTION_SOUND_SETTINGS), true, host, fill, null);
            case "picture":
                return pill(c, R.drawable.ic_picture, 0xFF30D158, "Picture", null,
                        new Intent(Settings.ACTION_DISPLAY_SETTINGS), true, host, fill, null);
            case "wallpaper": {
                TextView[] out = new TextView[1];
                return pill(c, R.drawable.ic_landscape, 0xFF5E5CE6, "Wallpaper", "Next",
                        (Runnable) () -> host.cycleWallpaper(null), false, host, fill, out);
            }
            case "timer":
            default: {
                TextView[] timerValue = new TextView[1];
                String t0 = KeyService.sleepTimerValue();
                return pill(c, R.drawable.ic_moon, 0xFFFF9F0A, "Sleep Timer", "Off".equals(t0) ? null : t0,
                        (Runnable) () -> {
                            String v = KeyService.cycleSleepTimer(c);
                            timerValue[0].setText(v);
                            timerValue[0].setVisibility("Off".equals(v) ? View.GONE : View.VISIBLE);
                        }, false, host, fill, timerValue);
            }
        }
    }

    private static Object[] circleSpec(Context c, String id, String name) {
        switch (id) {
            case "inputs":
                return new Object[] {R.drawable.ic_input, name, new Intent("com.android.tv.action.VIEW_INPUTS")};
            case "aerials":
                return new Object[] {R.drawable.ic_flight, name, new Intent(c, ScreenSaverSettings.class)};
            case "search":
                return new Object[] {R.drawable.ic_search, name, new Intent("android.search.action.GLOBAL_SEARCH")};
            case "appearance":
                return new Object[] {R.drawable.ic_palette, name, new Intent(c, AppearanceSettings.class)};
            case "settings":
            default:
                return new Object[] {R.drawable.ic_gear, name, new Intent(Settings.ACTION_SETTINGS)};
        }
    }

    private static LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        return r;
    }

    static String wifiName(Context c) {
        try {
            WifiManager w = c.getApplicationContext().getSystemService(WifiManager.class);
            if (w == null || !w.isWifiEnabled()) return "Off";
            WifiInfo i = w.getConnectionInfo();
            if (i == null || i.getNetworkId() == -1) return "Not Connected";
            String s = i.getSSID();
            if (s == null || s.contains("unknown")) return "Connected";
            return s.replace("\"", "");
        } catch (RuntimeException e) {
            return "Connected";
        }
    }

    private static String bluetoothState() {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            return a != null && a.isEnabled() ? "On" : "Off";
        } catch (RuntimeException e) {
            return "Devices";
        }
    }

    private static Intent bluetoothIntent(Context c) {
        Intent i = new Intent("com.android.tv.settings.ACCESSORIES_CONNECTED_DEVICES");
        if (i.resolveActivity(c.getPackageManager()) == null) {
            i = new Intent().setComponent(new ComponentName("com.android.tv.settings",
                    "com.tcl.settings.accessories.AccessoriesActivity"));
        }
        return i;
    }

    private static void run(Context c, Object action) {
        if (action instanceof Runnable) {
            ((Runnable) action).run();
            return;
        }
        try {
            c.startActivity(new Intent((Intent) action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(c, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    private static GlassView circle(final Context c, int icon, final Object action, final boolean closes,
                                    final Host host, int fill) {
        GlassView g = new GlassView(c, 19).solid(true).fallback(fill);
        ImageView iv = new ImageView(c);
        iv.setImageResource(icon);
        g.addView(iv, new FrameLayout.LayoutParams(Ui.dp(17), Ui.dp(17), Gravity.CENTER));
        ImageView[] tinted = {iv};
        g.button(v -> {
            if (closes) host.closeControlCenter();
            run(c, action);
        }, new TextView[0], tinted);
        return g;
    }

    /**
     * A compact pill: coloured icon disc, title, and a dimmer status line ({@code status} null:
     * the title may wrap onto two lines instead, like tvOS's "Sleep / Timer").
     */
    private static GlassView pill(final Context c, int icon, int iconColor, String title, String status,
                                  final Object action, final boolean closes, final Host host, int fill,
                                  TextView[] statusOut) {
        GlassView g = new GlassView(c, PILL_H / 2f).solid(true).fallback(fill);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(7), 0, Ui.dp(8), 0);

        FrameLayout disc = new FrameLayout(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(iconColor);
        disc.setBackground(d);
        ImageView iv = new ImageView(c);
        iv.setImageResource(icon);
        disc.addView(iv, new FrameLayout.LayoutParams(Ui.dp(14), Ui.dp(14), Gravity.CENTER));
        row.addView(disc, new LinearLayout.LayoutParams(Ui.dp(28), Ui.dp(28)));

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(Ui.dp(7), 0, 0, 0);
        TextView t1 = Ui.text(c, 11.5f, Ui.semibold, Color.WHITE);
        t1.setText(title);
        t1.setMaxLines(2);
        t1.setEllipsize(TextUtils.TruncateAt.END);
        TextView t2 = Ui.text(c, 10.5f, Ui.medium, 0xB3FFFFFF);
        t2.setTag(0xB3FFFFFF);
        t2.setText(status);
        t2.setSingleLine(true);
        t2.setEllipsize(TextUtils.TruncateAt.END);
        t2.setPadding(0, Ui.dp(2), 0, 0);
        t2.setVisibility(status == null ? View.GONE : View.VISIBLE);
        if (status != null) t1.setSingleLine(true);
        text.addView(t1);
        text.addView(t2);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        g.addView(row, new FrameLayout.LayoutParams(-1, -1));
        if (statusOut != null) statusOut[0] = t2;
        g.button(v -> {
            if (closes) host.closeControlCenter();
            run(c, action);
        }, new TextView[] {t1, t2}, new ImageView[0]);
        return g;
    }

    /** A focusable rounded card that lifts and gets a white ring when focused. */
    private static FrameLayout card(Context c) {
        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(Ui.dp(22));
        ring.setStroke(Ui.dp(2), Color.WHITE);
        ring.setAlpha(0);
        FrameLayout card = new FrameLayout(c) {
            @Override
            protected void onFocusChanged(boolean f, int dir, Rect prev) {
                super.onFocusChanged(f, dir, prev);
                if (f) Ui.tick(getContext());
                animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f)
                        .setDuration(f ? 380 : 200)
                        .setInterpolator(f ? Ui.spring(9f, 9f) : new DecelerateInterpolator())
                        .start();
                ring.setAlpha(f ? 255 : 0);
            }
        };
        Ui.round(card, 22);
        card.setFocusable(true);
        card.setClickable(true);
        card.setDefaultFocusHighlightEnabled(false);
        card.setForeground(ring);
        return card;
    }

    private static TextView chip(Context c, FrameLayout card) {
        TextView chip = Ui.text(c, 12, Ui.semibold, Color.WHITE);
        chip.setPadding(Ui.dp(10), Ui.dp(5), Ui.dp(10), Ui.dp(5));
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(0x73000000);
        chipBg.setCornerRadius(Ui.dp(20));
        chip.setBackground(chipBg);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.TOP);
        clp.leftMargin = Ui.dp(10);
        clp.topMargin = Ui.dp(10);
        card.addView(chip, clp);
        return chip;
    }

    /** Now Playing: artwork, title and app. OK plays/pauses; Left/Right skip back/forward. */
    private static View nowPlayingCard(final Context c, final MediaController mc) {
        final FrameLayout card = card(c);
        card.setOnKeyListener((v, keyCode, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                mc.getTransportControls().skipToPrevious();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                mc.getTransportControls().skipToNext();
                return true;
            }
            return false;
        });

        PackageManager pm = c.getPackageManager();
        String pkg = mc.getPackageName();
        CharSequence appName = pkg;
        Drawable appArt = null;
        try {
            appName = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            appArt = pm.getApplicationBanner(pkg);
            if (appArt == null) appArt = pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        MediaMetadata md = mc.getMetadata();
        Bitmap art = null;
        CharSequence title = null, sub = null;
        if (md != null) {
            art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            title = md.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (TextUtils.isEmpty(title)) title = md.getText(MediaMetadata.METADATA_KEY_TITLE);
            sub = md.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            if (TextUtils.isEmpty(sub)) sub = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
        }
        if (TextUtils.isEmpty(title)) title = appName;
        if (TextUtils.isEmpty(sub) || TextUtils.equals(sub, title)) sub = appName;

        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(Color.BLACK);
        if (art != null) img.setImageBitmap(art);
        else if (appArt != null) img.setImageDrawable(appArt);
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(c);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x40000000, 0x10000000, 0xCC000000}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        final TextView chip = chip(c, card);
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = Ui.text(c, 16, Ui.bold, Color.WHITE);
        t1.setText(title);
        t1.setSingleLine(true);
        t1.setEllipsize(TextUtils.TruncateAt.END);
        TextView t2 = Ui.text(c, 13, Ui.medium, 0xCCFFFFFF);
        t2.setText(sub);
        t2.setSingleLine(true);
        t2.setEllipsize(TextUtils.TruncateAt.END);
        t2.setPadding(0, Ui.dp(4), 0, 0);
        text.addView(t1);
        text.addView(t2);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        tlp.leftMargin = Ui.dp(14);
        tlp.rightMargin = Ui.dp(64);
        tlp.bottomMargin = Ui.dp(12);
        card.addView(text, tlp);

        final ImageView state = new ImageView(c);
        GradientDrawable stateBg = new GradientDrawable();
        stateBg.setShape(GradientDrawable.OVAL);
        stateBg.setColor(Color.WHITE);
        state.setBackground(stateBg);
        state.setPadding(Ui.dp(9), Ui.dp(9), Ui.dp(9), Ui.dp(9));
        state.setColorFilter(0xFF1C1C1E, PorterDuff.Mode.SRC_IN);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(Ui.dp(38), Ui.dp(38), Gravity.END | Gravity.BOTTOM);
        slp.rightMargin = Ui.dp(12);
        slp.bottomMargin = Ui.dp(12);
        card.addView(state, slp);

        final Runnable refresh = () -> {
            PlaybackState st = mc.getPlaybackState();
            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            chip.setText(playing ? "▶  Now Playing" : "❚❚  Paused");
            state.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        };
        refresh.run();
        final MediaController.Callback cb = new MediaController.Callback() {
            @Override
            public void onPlaybackStateChanged(PlaybackState s) {
                refresh.run();
            }
        };
        card.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                mc.registerCallback(cb);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                mc.unregisterCallback(cb);
            }
        });
        card.setOnClickListener(v -> {
            PlaybackState st = mc.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) mc.getTransportControls().pause();
            else mc.getTransportControls().play();
        });
        return card;
    }
}
