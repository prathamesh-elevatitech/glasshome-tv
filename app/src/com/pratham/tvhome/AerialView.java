package com.pratham.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * tvOS-style Aerials: slow drone footage streamed from Mixkit (free licence), cross-fading from
 * clip to clip, with the place name shown at the start of each clip and whenever OK is pressed.
 * Left/Right skips. Falls back to a plain clock when there is no network.
 */
final class AerialView extends FrameLayout {
    private static final String TAG = "Aerials";
    private static final String CDN = "https://assets.mixkit.co/";

    static final String[] CATEGORIES = {"Landscapes", "Cities", "Ocean", "Polar & Night Sky"};

    /** {path, place, region, best available resolution, category index}. */
    static final String[][] CLIPS = {
            {"videos/4281/4281", "Matterhorn", "Switzerland", "1080", "0"},
            {"videos/4132/4132", "The Alps", "Europe", "1080", "0"},
            {"active_storage/video_items/100415/1724198576/100415-video", "Misty Highlands", "Green Mountains", "1080", "0"},
            {"videos/52012/52012", "Desert Mountains", "Arid Highlands", "1080", "0"},
            {"active_storage/video_items/100195/1721338072/100195-video", "Forest Waterfall", "Rainforest", "1080", "0"},
            {"videos/39198/39198", "Istanbul", "Türkiye", "720", "1"},
            {"videos/49845/49845", "Austin", "Texas", "1080", "1"},
            {"videos/11038/11038", "Golden Gate Bridge", "San Francisco", "720", "1"},
            {"videos/44868/44868", "Coral Reef", "Underwater", "720", "2"},
            {"videos/5008/5008", "Turquoise Bay", "Coast", "1080", "2"},
            {"videos/2888/2888", "Paradise Island", "Tropics", "1080", "2"},
            {"videos/48402/48402", "Arctic Ice Floes", "Arctic Ocean", "720", "3"},
            {"videos/4148/4148", "Milky Way", "Night Sky", "1080", "3"},
    };

    /** Categories switched off in Screen Saver settings (bit i = CATEGORIES[i] off). */
    static int offMask(Context c) {
        return Profiles.prefs(c).getInt("aerials_off", 0);
    }

    static void setOffMask(Context c, int mask) {
        Profiles.prefs(c).edit().putInt("aerials_off", mask).apply();
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final TextureView[] views = new TextureView[2];
    private final MediaPlayer[] players = new MediaPlayer[2];
    private final boolean[] surfaceReady = new boolean[2];
    private final List<Integer> order = new ArrayList<>();
    private final LinearLayout caption;
    private final TextView place, region, clock;
    private final LinearLayout fallback;
    private final TextView fallbackTime;
    private int cur = -1;
    private int pos;
    private int failures;
    private boolean running;
    /** Set when the TV can't run two video decoders at once: fade through black instead. */
    private boolean singleDecoder;

    private final Runnable advance = this::next;
    private final Runnable hideCaption = () -> getCaption().animate().alpha(0f).setDuration(900).start();
    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            String t = DateFormat.getTimeFormat(getContext()).format(new Date());
            clock.setText(t);
            fallbackTime.setText(t);
            h.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
        }
    };

    AerialView(Context c) {
        super(c);
        Ui.init(c);
        setBackgroundColor(Color.BLACK);
        for (int i = 0; i < 2; i++) {
            final int slot = i;
            TextureView tv = new TextureView(c);
            tv.setAlpha(0f);
            tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int hh) {
                    surfaceReady[slot] = true;
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int hh) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
                    surfaceReady[slot] = false;
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture s) {
                }
            });
            views[i] = tv;
            addView(tv, new LayoutParams(-1, -1));
        }

        View scrim = new View(c);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x26000000, 0x00000000, 0x00000000, 0x80000000}));
        scrim.setTranslationZ(2);
        addView(scrim, new LayoutParams(-1, -1));

        caption = new LinearLayout(c);
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.setAlpha(0f);
        caption.setTranslationZ(3);
        place = Ui.text(c, 34, Ui.semibold, Color.WHITE);
        place.setShadowLayer(Ui.dp(12), 0, Ui.dp(1), 0x99000000);
        region = Ui.text(c, 18, Ui.medium, 0xD9FFFFFF);
        region.setShadowLayer(Ui.dp(10), 0, Ui.dp(1), 0x99000000);
        region.setPadding(Ui.dp(2), Ui.dp(8), 0, 0);
        caption.addView(place);
        caption.addView(region);
        LayoutParams clp = new LayoutParams(-2, -2, Gravity.START | Gravity.BOTTOM);
        clp.leftMargin = Ui.dp(56);
        clp.bottomMargin = Ui.dp(48);
        addView(caption, clp);

        clock = Ui.text(c, 24, Ui.medium, 0xE6FFFFFF);
        clock.setShadowLayer(Ui.dp(10), 0, Ui.dp(1), 0x99000000);
        clock.setTranslationZ(3);
        LayoutParams tlp = new LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        tlp.rightMargin = Ui.dp(56);
        tlp.topMargin = Ui.dp(36);
        addView(clock, tlp);

        fallback = new LinearLayout(c);
        fallback.setOrientation(LinearLayout.VERTICAL);
        fallback.setGravity(Gravity.CENTER_HORIZONTAL);
        fallback.setVisibility(GONE);
        fallback.setTranslationZ(4);
        fallbackTime = Ui.text(c, 120, Ui.light, 0xEEFFFFFF);
        fallback.addView(fallbackTime);
        TextView sub = Ui.text(c, 18, Ui.medium, 0x80FFFFFF);
        sub.setText("Aerials need an internet connection");
        sub.setPadding(0, Ui.dp(12), 0, 0);
        fallback.addView(sub);
        addView(fallback, new LayoutParams(-2, -2, Gravity.CENTER));

        int off = offMask(c);
        for (int i = 0; i < CLIPS.length; i++) {
            if ((off & (1 << Integer.parseInt(CLIPS[i][4]))) == 0) order.add(i);
        }
        if (order.isEmpty()) for (int i = 0; i < CLIPS.length; i++) order.add(i);
        Collections.shuffle(order);
    }

    private View getCaption() {
        return caption;
    }

    void start() {
        if (running) return;
        running = true;
        h.post(clockTick);
        next();
    }

    void stop() {
        running = false;
        h.removeCallbacksAndMessages(null);
        release(0);
        release(1);
        cur = -1;
    }

    void skip() {
        h.removeCallbacks(advance);
        next();
    }

    void showCaption() {
        h.removeCallbacks(hideCaption);
        caption.animate().alpha(1f).setDuration(500).start();
        h.postDelayed(hideCaption, 5000);
    }

    /** D-pad handling shared by the screensaver and the preview screen. Returns false for "wake up". */
    boolean handleKey(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && e.getRepeatCount() == 0) skip();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) showCaption();
                return true;
            default:
                return false;
        }
    }

    private void next() {
        if (!running) return;
        final int clip = order.get(pos++ % order.size());
        if (singleDecoder && cur != -1) {
            final int old = cur;
            views[old].animate().alpha(0f).setDuration(1200).withEndAction(() -> {
                release(old);
                cur = -1;
                prepare(old, clip, false);
            }).start();
            return;
        }
        prepare(cur == -1 ? 0 : 1 - cur, clip, false);
    }

    private void prepare(final int slot, final int clip, final boolean lowRes) {
        if (!running) return;
        release(slot);
        if (!surfaceReady[slot]) {
            h.postDelayed(() -> prepare(slot, clip, lowRes), 150);
            return;
        }
        String[] c = CLIPS[clip];
        String url = CDN + c[0] + "-" + (lowRes ? "720" : c[3]) + ".mp4";
        MediaPlayer mp = new MediaPlayer();
        players[slot] = mp;
        try {
            mp.setSurface(new Surface(views[slot].getSurfaceTexture()));
            mp.setDataSource(url);
            mp.setVolume(0f, 0f);
            mp.setLooping(true);
            mp.setOnVideoSizeChangedListener((p, w, hh) -> fit(views[slot], w, hh));
            mp.setOnPreparedListener(p -> {
                if (players[slot] != p || !running) return;
                failures = 0;
                p.start();
                show(slot, clip, p.getDuration());
            });
            mp.setOnErrorListener((p, what, extra) -> {
                if (players[slot] == p) onError(slot, clip, lowRes, what);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            onError(slot, clip, lowRes, -1);
        }
    }

    private void onError(int slot, int clip, boolean lowRes, int what) {
        Log.w(TAG, "clip " + clip + " failed (" + what + ")");
        release(slot);
        if (!lowRes && !"720".equals(CLIPS[clip][3])) {
            prepare(slot, clip, true);
            return;
        }
        failures++;
        if (cur != -1 && !singleDecoder && failures >= 2) {
            // Probably out of video decoders while the current clip plays.
            singleDecoder = true;
            failures = 0;
        }
        if (cur == -1 && failures >= 4) {
            fallback.setVisibility(VISIBLE);
            failures = 0;
            h.postDelayed(advance, 60_000);
            return;
        }
        h.postDelayed(advance, 1000);
    }

    private void show(final int slot, int clip, int durationMs) {
        final int old = cur;
        cur = slot;
        fallback.setVisibility(GONE);
        views[slot].setTranslationZ(1);
        if (old != -1) views[old].setTranslationZ(0);
        views[slot].animate().alpha(1f).setDuration(old == -1 ? 900 : 1800).withEndAction(() -> {
            if (old != -1 && old != cur) {
                views[old].setAlpha(0f);
                release(old);
            }
        }).start();
        place.setText(CLIPS[clip][1]);
        region.setText(CLIPS[clip][2]);
        showCaption();
        long dwell = durationMs > 0 ? Math.min(durationMs - 2000L, 45_000L) : 30_000L;
        h.removeCallbacks(advance);
        h.postDelayed(advance, Math.max(dwell, 10_000L));
    }

    private void release(int slot) {
        MediaPlayer mp = players[slot];
        players[slot] = null;
        if (mp == null) return;
        try {
            mp.reset();
        } catch (RuntimeException ignored) {
        }
        mp.release();
    }

    /** Centre-crop the video into the full-screen view. */
    private static void fit(TextureView v, int w, int hh) {
        if (w == 0 || hh == 0 || v.getWidth() == 0) return;
        float vw = v.getWidth(), vh = v.getHeight();
        float va = w / (float) hh, ra = vw / vh;
        Matrix m = new Matrix();
        if (va > ra) m.setScale(va / ra, 1f, vw / 2f, vh / 2f);
        else m.setScale(1f, ra / va, vw / 2f, vh / 2f);
        v.setTransform(m);
    }
}
