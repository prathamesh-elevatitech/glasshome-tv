package com.pratham.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * tvOS full-screen Top Shelf ("carousel details" layout from Apple's HIG). When an app in the
 * dock is focused, its featured artwork fills the screen with an attribution line, title,
 * subtitle and description at the top-left, and page dots; it advances on a timer. Up from the
 * dock enters the carousel: the dock slides away and Play/Resume and More Info buttons appear;
 * Left/Right page through items.
 *
 * Content comes from the TV's own database (TvContract watch_next_program, else
 * preview_program; needs READ_TV_LISTINGS, granted by install.sh). The provider rejects WHERE
 * clauses from non-system apps, so rows are filtered in code.
 */
final class TopShelf extends FrameLayout {
    static final class Item {
        String title, subtitle, description, art, intent;
        boolean resume; // from Continue Watching, not a recommendation
        float progress = -1f;
    }

    interface Host {
        /** The carousel took or gave up focus: slide the dock away / back. */
        void onCarousel(boolean active);

        /** Content appeared or went away (e.g. to show the "press Up" hint). */
        void onShelfContent(boolean shown);
    }

    /** Settings key for the Top Shelf style: "full" (default, full-screen carousel) or "row". */
    static final String STYLE = "shelf_style";

    private static final long TTL = 60_000;
    private static final long PAGE_MS = 7000;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Map<String, List<Item>> cache = new HashMap<>();
    private final Map<String, Long> cachedAt = new HashMap<>();
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(28 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    private final Host host;
    private final int screenW;
    private final ImageView[] hero = new ImageView[2];
    private int heroFront;
    private final LinearLayout info;
    private final TextView attribution, title, subtitle, description;
    private final FrameLayout progressTrack;
    private final View progressBar;
    private final LinearLayout buttons, dots;
    private final GlassView play, more;
    private final TextView playLabel;
    private final int progressW;
    private String shownPkg, wantedPkg, pendingPkg, appName;
    private List<Item> items;
    private int page;
    private boolean active;
    private TileView owner;
    // Row style (optional): a labelled row of 16:9 cards above the dock; Up again shows a clean
    // full-screen backdrop with the app's logo over its colour.
    private final List<View> fullParts = new ArrayList<>();
    private FrameLayout rowLayer;
    private TextView rowHeading;
    private android.widget.HorizontalScrollView rowScroller;
    private LinearLayout rowCards;
    private ImageView logo;
    private View backdropFocus;
    private int cardW, cardH, margin;
    private boolean rowMode, backdrop;

    private final Runnable pending = () -> load(pendingPkg);
    private final Runnable advance = new Runnable() {
        @Override
        public void run() {
            if (items != null && items.size() > 1) show((page + 1) % items.size(), true);
            h.postDelayed(this, PAGE_MS);
        }
    };

    TopShelf(Context c, Host host, int screenW, int margin, int infoTop, int dotsBottom, int dockTop) {
        super(c);
        this.host = host;
        this.screenW = screenW;
        this.margin = margin;
        setClipChildren(false);
        setAlpha(0f);
        setVisibility(INVISIBLE);

        // Two artwork layers cross-fade under fixed scrims, so the text always stays legible.
        for (int i = 0; i < 2; i++) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setAlpha(0f);
            hero[i] = iv;
            addView(iv, new LayoutParams(-1, -1));
            fullParts.add(iv);
        }
        View left = new View(c);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xCC000000, 0x66000000, 0x00000000}));
        addView(left, new LayoutParams(Math.round(screenW * 0.62f), -1));
        fullParts.add(left);
        View bottom = new View(c);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xE6000000, 0x80000000, 0x00000000}));
        addView(bottom, new LayoutParams(-1, Ui.dp(300), Gravity.BOTTOM));
        fullParts.add(bottom);
        View top = new View(c);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x73000000, 0x00000000}));
        addView(top, new LayoutParams(-1, Ui.dp(140), Gravity.TOP));
        fullParts.add(top);

        info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setClipChildren(false);
        attribution = Ui.text(c, 13, Ui.semibold, 0xB3FFFFFF);
        attribution.setLetterSpacing(0.06f);
        info.addView(attribution);
        title = Ui.text(c, 40, Ui.bold, Color.WHITE);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLetterSpacing(-0.01f);
        title.setShadowLayer(Ui.dp(14), 0, Ui.dp(2), 0x80000000);
        title.setPadding(0, Ui.dp(8), 0, 0);
        info.addView(title);
        subtitle = Ui.text(c, 16, Ui.semibold, 0xE6FFFFFF);
        subtitle.setPadding(0, Ui.dp(8), 0, 0);
        info.addView(subtitle);

        progressW = Ui.dp(180);
        progressTrack = new FrameLayout(c);
        GradientDrawable tb = new GradientDrawable();
        tb.setColor(0x59FFFFFF);
        tb.setCornerRadius(Ui.dp(3));
        progressTrack.setBackground(tb);
        progressBar = new View(c);
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(Color.WHITE);
        bb.setCornerRadius(Ui.dp(3));
        progressBar.setBackground(bb);
        progressTrack.addView(progressBar, new LayoutParams(0, -1));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(progressW, Ui.dp(5));
        plp.topMargin = Ui.dp(12);
        info.addView(progressTrack, plp);

        description = Ui.text(c, 15, Ui.regular, 0xCCFFFFFF);
        description.setMaxLines(3);
        description.setEllipsize(TextUtils.TruncateAt.END);
        description.setLineSpacing(Ui.dp(3), 1f);
        description.setPadding(0, Ui.dp(12), 0, 0);
        info.addView(description);

        buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setClipChildren(false);
        buttons.setAlpha(0f);
        buttons.setVisibility(GONE);
        play = new GlassView(c, 22).solid(true).fallback(0x40FFFFFF);
        LinearLayout pr = new LinearLayout(c);
        pr.setOrientation(LinearLayout.HORIZONTAL);
        pr.setGravity(Gravity.CENTER);
        ImageView pi = new ImageView(c);
        pi.setImageResource(R.drawable.ic_play);
        pr.addView(pi, new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)));
        playLabel = Ui.text(c, 16, Ui.semibold, Color.WHITE);
        playLabel.setPadding(Ui.dp(8), 0, 0, 0);
        pr.addView(playLabel);
        play.addView(pr, new LayoutParams(-1, -1));
        play.button(v -> openCurrent(), new TextView[] {playLabel}, new ImageView[] {pi});
        buttons.addView(play, new LinearLayout.LayoutParams(Ui.dp(150), Ui.dp(44)));
        more = new GlassView(c, 22).solid(true).fallback(0x40FFFFFF);
        TextView ml = Ui.text(c, 16, Ui.semibold, Color.WHITE);
        ml.setText("More Info");
        ml.setGravity(Gravity.CENTER);
        more.addView(ml, new LayoutParams(-1, -1));
        more.button(v -> openApp(), new TextView[] {ml}, new ImageView[0]);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(Ui.dp(150), Ui.dp(44));
        mlp.leftMargin = Ui.dp(14);
        buttons.addView(more, mlp);
        // Left/Right past the buttons pages through the carousel, like swiping on the Siri Remote.
        play.setOnKeyListener((v, k, e) -> {
            if (k == KeyEvent.KEYCODE_DPAD_LEFT && e.getAction() == KeyEvent.ACTION_DOWN) {
                page(-1);
                return true;
            }
            return false;
        });
        more.setOnKeyListener((v, k, e) -> {
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT && e.getAction() == KeyEvent.ACTION_DOWN) {
                page(1);
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = Ui.dp(22);
        info.addView(buttons, blp);

        LayoutParams ilp = new LayoutParams(Math.round(screenW * 0.46f), -2, Gravity.TOP | Gravity.START);
        ilp.leftMargin = margin;
        ilp.topMargin = infoTop;
        addView(info, ilp);
        fullParts.add(info);

        dots = new LinearLayout(c);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        LayoutParams dlp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dlp.bottomMargin = dotsBottom;
        addView(dots, dlp);
        fullParts.add(dots);
        buildRowLayer(c, dockTop);
    }

    // ---------------------------------------------------------------- state

    boolean hasContent() {
        return getVisibility() == VISIBLE && items != null && !items.isEmpty() && wantedPkg != null;
    }

    boolean isActive() {
        return active;
    }

    TileView owner() {
        return owner;
    }

    /** Show the focused dock app's shelf (after a short pause, so scrubbing along the dock stays light). */
    void showFor(TileView tile) {
        owner = tile;
        String pkg = tile.app.key;
        appName = tile.app.label;
        wantedPkg = pkg;
        h.removeCallbacks(pending);
        if (pkg.equals(shownPkg) && getVisibility() == VISIBLE) return;
        pendingPkg = pkg;
        h.postDelayed(pending, 260);
    }

    void hide() {
        wantedPkg = null;
        h.removeCallbacks(pending);
        h.removeCallbacks(advance);
        if (active) exit();
        if (getVisibility() != VISIBLE) return;
        host.onShelfContent(false);
        animate().alpha(0f).setDuration(260).withEndAction(() -> {
            if (wantedPkg != null) return;
            setVisibility(INVISIBLE);
            shownPkg = null;
            items = null;
        }).start();
    }

    /** Drops cached rows (Continue Watching may have changed) and reloads the focused app's shelf. */
    void refresh(TileView focused) {
        cachedAt.clear();
        shownPkg = null;
        if (focused != null) showFor(focused);
    }

    /** Up from the dock: the dock slides away and the Play / More Info buttons take focus. */
    void enter() {
        if (!hasContent()) return;
        active = true;
        if (rowMode) {
            if (rowCards.getChildCount() > 0) rowCards.getChildAt(0).requestFocus();
            return;
        }
        h.removeCallbacks(advance);
        playLabel.setText(items.get(page).resume ? "Resume" : "Play");
        buttons.setVisibility(VISIBLE);
        buttons.setTranslationY(Ui.dp(12));
        buttons.animate().alpha(1f).translationY(0).setDuration(320).setInterpolator(new DecelerateInterpolator()).start();
        play.requestFocus();
        host.onCarousel(true);
    }

    /** Back down to the dock. */
    void exit() {
        if (!active) return;
        active = false;
        if (rowMode) {
            if (backdrop) showBackdrop(false);
            return;
        }
        buttons.animate().alpha(0f).setDuration(180).withEndAction(() -> buttons.setVisibility(GONE)).start();
        host.onCarousel(false);
        h.removeCallbacks(advance);
        h.postDelayed(advance, PAGE_MS);
    }

    private void page(int delta) {
        if (items == null || items.size() < 2) return;
        Ui.tick(getContext());
        show((page + delta + items.size()) % items.size(), true);
    }

    // ---------------------------------------------------------------- loading

    private void load(final String pkg) {
        Long at = cachedAt.get(pkg);
        if (at != null && System.currentTimeMillis() - at < TTL) {
            present(pkg, cache.get(pkg));
            return;
        }
        io.execute(() -> {
            List<Item> found = query(getContext(), pkg);
            // Have the first picture ready before showing anything.
            if (!found.isEmpty()) fetch(found.get(0).art);
            h.post(() -> {
                cache.put(pkg, found);
                cachedAt.put(pkg, System.currentTimeMillis());
                present(pkg, found);
            });
        });
    }

    private void present(String pkg, List<Item> found) {
        if (!pkg.equals(wantedPkg)) return;
        if (found == null || found.isEmpty()) {
            hide();
            return;
        }
        boolean same = pkg.equals(shownPkg);
        shownPkg = pkg;
        items = found;
        host.onShelfContent(true);
        rowMode = "row".equals(Profiles.prefs(getContext()).getString(STYLE, "full"));
        for (View v : fullParts) v.setVisibility(rowMode ? GONE : VISIBLE);
        rowLayer.setVisibility(rowMode ? VISIBLE : GONE);
        if (rowMode) {
            buildRow(found);
            h.removeCallbacks(advance);
            if (getVisibility() != VISIBLE || getAlpha() < 1f) {
                setVisibility(VISIBLE);
                animate().alpha(1f).setDuration(320).setInterpolator(new DecelerateInterpolator()).start();
            }
            return;
        }
        dots.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            View d = new View(getContext());
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Color.WHITE);
            d.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(7), Ui.dp(7));
            if (i > 0) lp.leftMargin = Ui.dp(8);
            dots.addView(d, lp);
        }
        dots.setVisibility(items.size() > 1 ? VISIBLE : INVISIBLE);
        show(same ? Math.min(page, items.size() - 1) : 0, getVisibility() == VISIBLE);
        if (getVisibility() != VISIBLE || getAlpha() < 1f) {
            setVisibility(VISIBLE);
            animate().alpha(1f).setDuration(420).setInterpolator(new DecelerateInterpolator()).start();
        }
        h.removeCallbacks(advance);
        if (!active) h.postDelayed(advance, PAGE_MS);
    }

    // ---------------------------------------------------------------- row style (optional)

    /** Up inside the shelf. Returns true if handled (Row style: cards → clean backdrop). */
    boolean onUp() {
        if (rowMode && active && !backdrop) {
            showBackdrop(true);
            return true;
        }
        return false;
    }

    /** Down inside the shelf. Returns true if handled here (backdrop → back to the cards). */
    boolean onDown() {
        if (rowMode && backdrop) {
            showBackdrop(false);
            if (rowCards.getChildCount() > 0) rowCards.getChildAt(Math.min(page, rowCards.getChildCount() - 1)).requestFocus();
            return true;
        }
        return false;
    }

    /** The clean full-screen backdrop: just the app's logo over its colour; the dock slides away. */
    private void showBackdrop(boolean on) {
        backdrop = on;
        if (on) {
            logo.setImageBitmap(owner != null ? owner.app.tile : null);
            logo.setScaleX(0.9f);
            logo.setScaleY(0.9f);
            logo.setVisibility(VISIBLE);
            logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420)
                    .setInterpolator(new DecelerateInterpolator()).start();
            rowLayer.animate().alpha(0f).setDuration(220).start();
            backdropFocus.requestFocus();
        } else {
            logo.animate().alpha(0f).setDuration(200).withEndAction(() -> logo.setVisibility(INVISIBLE)).start();
            rowLayer.animate().alpha(1f).setDuration(260).start();
        }
        host.onCarousel(on);
    }

    private void buildRowLayer(Context c, int dockTop) {
        cardH = Ui.dp(150);
        cardW = Math.round(cardH * 16 / 9f);
        rowLayer = new FrameLayout(c);
        rowLayer.setClipChildren(false);
        rowLayer.setVisibility(GONE);
        rowHeading = Ui.text(c, 17, Ui.semibold, Color.WHITE);
        rowHeading.setShadowLayer(Ui.dp(10), 0, Ui.dp(1), 0x80000000);
        LayoutParams hlp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        hlp.leftMargin = margin + Ui.dp(4);
        rowLayer.addView(rowHeading, hlp);
        rowScroller = new android.widget.HorizontalScrollView(c);
        rowScroller.setHorizontalScrollBarEnabled(false);
        rowScroller.setClipChildren(false);
        rowScroller.setOverScrollMode(OVER_SCROLL_NEVER);
        rowCards = new LinearLayout(c);
        rowCards.setOrientation(LinearLayout.HORIZONTAL);
        rowCards.setClipChildren(false);
        rowCards.setClipToPadding(false);
        rowCards.setPadding(margin, Ui.dp(12), margin, Ui.dp(12));
        rowScroller.addView(rowCards, new android.widget.HorizontalScrollView.LayoutParams(-2, -2));
        LayoutParams slp = new LayoutParams(-1, -2);
        slp.topMargin = Ui.dp(28);
        rowLayer.addView(rowScroller, slp);
        LayoutParams rlp = new LayoutParams(-1, -2, Gravity.TOP);
        rlp.topMargin = dockTop - cardH - Ui.dp(28 + 12 + 30);
        addView(rowLayer, rlp);

        logo = new ImageView(c);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setAlpha(0f);
        logo.setVisibility(INVISIBLE);
        logo.setElevation(Ui.dp(20));
        Ui.round(logo, 28);
        int lw = Math.round(screenW * 0.34f);
        addView(logo, new LayoutParams(lw, Math.round(lw * 0.6f), Gravity.CENTER));
        // Holds focus while the backdrop is shown (nothing on screen is selectable then).
        backdropFocus = new View(c);
        backdropFocus.setFocusable(true);
        backdropFocus.setDefaultFocusHighlightEnabled(false);
        addView(backdropFocus, new LayoutParams(1, 1));
    }

    private void buildRow(List<Item> found) {
        rowHeading.setText(found.get(0).resume ? "Continue Watching" : "Recommended for you");
        rowCards.removeAllViews();
        for (int i = 0; i < found.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, cardH);
            if (i > 0) lp.leftMargin = Ui.dp(24);
            rowCards.addView(rowCard(found.get(i), i), lp);
        }
        rowScroller.scrollTo(0, 0);
        rowLayer.setAlpha(1f);
    }

    /** A 16:9 card with the title and subtitle over a bottom gradient. */
    private View rowCard(final Item it, final int index) {
        final Context c = getContext();
        final FrameLayout art = new FrameLayout(c) {
            @Override
            protected void onFocusChanged(boolean f, int dir, android.graphics.Rect prev) {
                super.onFocusChanged(f, dir, prev);
                if (f) {
                    Ui.tick(getContext());
                    page = index;
                    rowScroller.smoothScrollTo(Math.max(0, getLeft() - margin - Ui.dp(40)), 0);
                }
                animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).translationZ(f ? Ui.dp(18) : 0)
                        .setDuration(f ? 380 : 200)
                        .setInterpolator(f ? Ui.spring(9f, 9f) : new DecelerateInterpolator()).start();
            }
        };
        Ui.round(art, 16);
        art.setBackgroundColor(0xFF1C1C1E);
        art.setFocusable(true);
        art.setClickable(true);
        art.setDefaultFocusHighlightEnabled(false);
        final ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(img, new LayoutParams(-1, -1));
        View shade = new View(c);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xCC000000, 0x33000000, 0x00000000}));
        art.addView(shade, new LayoutParams(-1, Math.round(cardH * 0.6f), Gravity.BOTTOM));
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = Ui.text(c, 14, Ui.semibold, Color.WHITE);
        t1.setText(it.title);
        t1.setSingleLine(true);
        t1.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(t1);
        String sub = !TextUtils.isEmpty(it.subtitle) ? it.subtitle : it.description;
        if (!TextUtils.isEmpty(sub)) {
            TextView t2 = Ui.text(c, 11, Ui.medium, 0xCCFFFFFF);
            t2.setText(sub);
            t2.setSingleLine(true);
            t2.setEllipsize(TextUtils.TruncateAt.END);
            t2.setPadding(0, Ui.dp(3), 0, 0);
            text.addView(t2);
        }
        if (it.progress >= 0f) {
            FrameLayout track = new FrameLayout(c);
            GradientDrawable tb = new GradientDrawable();
            tb.setColor(0x59FFFFFF);
            tb.setCornerRadius(Ui.dp(2));
            track.setBackground(tb);
            View bar = new View(c);
            GradientDrawable bb = new GradientDrawable();
            bb.setColor(Color.WHITE);
            bb.setCornerRadius(Ui.dp(2));
            bar.setBackground(bb);
            int tw = cardW - Ui.dp(28);
            track.addView(bar, new LayoutParams(Math.max(Ui.dp(4), Math.round(tw * it.progress)), -1));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(tw, Ui.dp(4));
            plp.topMargin = Ui.dp(7);
            text.addView(track, plp);
        }
        LayoutParams tlp = new LayoutParams(-1, -2, Gravity.BOTTOM);
        tlp.leftMargin = Ui.dp(14);
        tlp.rightMargin = Ui.dp(14);
        tlp.bottomMargin = Ui.dp(11);
        art.addView(text, tlp);

        img.setTag(it.art);
        Bitmap b = images.get(it.art);
        if (b != null) img.setImageBitmap(b);
        else io.execute(() -> {
            Bitmap got = fetch(it.art);
            h.post(() -> {
                if (got != null && it.art.equals(img.getTag())) {
                    img.setAlpha(0f);
                    img.setImageBitmap(got);
                    img.animate().alpha(1f).setDuration(220).start();
                }
            });
        });
        art.setOnClickListener(v -> {
            page = index;
            openCurrent();
        });
        return art;
    }

    /** Cross-fades to item {@code i}: artwork, then text. */
    private void show(int i, boolean animate) {
        page = i;
        final Item it = items.get(i);
        for (int d = 0; d < dots.getChildCount(); d++) dots.getChildAt(d).setAlpha(d == i ? 1f : 0.35f);

        final ImageView next = hero[1 - heroFront], cur = hero[heroFront];
        heroFront = 1 - heroFront;
        next.setTag(it.art);
        next.animate().cancel();
        cur.animate().cancel();
        Bitmap b = images.get(it.art);
        next.setImageBitmap(b);
        next.setAlpha(0f);
        long fade = animate ? 700 : 0;
        if (b != null) next.animate().alpha(1f).setDuration(fade).start();
        else io.execute(() -> {
            Bitmap got = fetch(it.art);
            h.post(() -> {
                if (got != null && it.art.equals(next.getTag())) {
                    next.setImageBitmap(got);
                    next.animate().alpha(1f).setDuration(500).start();
                }
            });
        });
        cur.animate().alpha(0f).setDuration(fade).start();

        Runnable text = () -> {
            attribution.setText((it.resume ? "CONTINUE WATCHING ON " : "FEATURED ON ")
                    + (appName == null ? "" : appName.toUpperCase()));
            title.setText(it.title);
            subtitle.setText(it.subtitle);
            subtitle.setVisibility(TextUtils.isEmpty(it.subtitle) ? GONE : VISIBLE);
            description.setText(it.description);
            description.setVisibility(TextUtils.isEmpty(it.description) ? GONE : VISIBLE);
            progressTrack.setVisibility(it.progress >= 0f ? VISIBLE : GONE);
            if (it.progress >= 0f) {
                LayoutParams lp = (LayoutParams) progressBar.getLayoutParams();
                lp.width = Math.max(Ui.dp(6), Math.round(progressW * it.progress));
                progressBar.setLayoutParams(lp);
            }
            playLabel.setText(it.resume ? "Resume" : "Play");
        };
        if (animate) {
            info.animate().alpha(0f).translationX(-Ui.dp(10)).setDuration(160).withEndAction(() -> {
                text.run();
                info.setTranslationX(Ui.dp(14));
                info.animate().alpha(1f).translationX(0).setDuration(380)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }).start();
        } else {
            text.run();
            info.setAlpha(1f);
            info.setTranslationX(0);
        }
        // Prefetch the next picture.
        if (items.size() > 1) {
            final String nextArt = items.get((i + 1) % items.size()).art;
            if (images.get(nextArt) == null) io.execute(() -> fetch(nextArt));
        }
    }

    /** Continue Watching rows (newest first); otherwise the app's recommendations. */
    private List<Item> query(Context c, String pkg) {
        List<Item> out = new ArrayList<>();
        List<long[]> order = new ArrayList<>();
        List<Item> wn = new ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(Uri.parse("content://android.media.tv/watch_next_program"),
                null, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                if (!pkg.equals(getStr(cur, "package_name"))) continue;
                Item it = item(cur);
                if (it.art == null) continue;
                long pos = getLong(cur, "last_playback_position_millis"), dur = getLong(cur, "duration_millis");
                if (pos > 0 && dur > 0) it.progress = Math.min(1f, pos / (float) dur);
                it.resume = true;
                order.add(new long[] {getLong(cur, "last_engagement_time_utc_millis"), wn.size()});
                wn.add(it);
            }
        } catch (RuntimeException ignored) {
        }
        order.sort((a, b) -> Long.compare(b[0], a[0]));
        for (long[] o : order) if (out.size() < 10) out.add(wn.get((int) o[1]));
        if (out.isEmpty()) {
            try (Cursor cur = c.getContentResolver().query(Uri.parse("content://android.media.tv/preview_program"),
                    null, null, null, null)) {
                while (cur != null && cur.moveToNext() && out.size() < 10) {
                    if (!pkg.equals(getStr(cur, "package_name"))) continue;
                    if ("0".equals(getStr(cur, "browsable"))) continue;
                    Item it = item(cur);
                    if (it.art != null) out.add(it);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return out;
    }

    private static Item item(Cursor c) {
        Item it = new Item();
        it.title = getStr(c, "title");
        String ep = getStr(c, "episode_title");
        String s = getStr(c, "season_display_number"), e = getStr(c, "episode_display_number");
        if (!TextUtils.isEmpty(s) && !TextUtils.isEmpty(e)) {
            it.subtitle = "Season " + s + ", Episode " + e + (TextUtils.isEmpty(ep) ? "" : " · " + ep);
        } else if (!TextUtils.isEmpty(ep)) {
            it.subtitle = ep;
        }
        it.description = getStr(c, "short_description");
        if (TextUtils.isEmpty(it.description)) it.description = getStr(c, "long_description");
        it.art = getStr(c, "poster_art_uri");
        if (TextUtils.isEmpty(it.art)) it.art = getStr(c, "thumbnail_uri");
        it.intent = getStr(c, "intent_uri");
        return it;
    }

    private static String getStr(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i >= 0 ? c.getString(i) : null;
    }

    private static long getLong(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i >= 0 && !c.isNull(i) ? c.getLong(i) : 0;
    }

    // ---------------------------------------------------------------- actions

    private void openCurrent() {
        if (items == null) return;
        Item it = items.get(page);
        try {
            Intent i = Intent.parseUri(it.intent, Intent.URI_INTENT_SCHEME);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Exception e) {
            openApp();
        }
        if (shownPkg != null) cachedAt.remove(shownPkg);
    }

    private void openApp() {
        if (shownPkg == null) return;
        PackageManager pm = getContext().getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(shownPkg);
        if (i == null) i = pm.getLaunchIntentForPackage(shownPkg);
        if (i == null) {
            Toast.makeText(getContext(), "Couldn't open this", Toast.LENGTH_SHORT).show();
            return;
        }
        getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // ---------------------------------------------------------------- images

    /** Downloads and decodes (about screen width, RGB_565) into the cache; call off the UI thread. */
    private Bitmap fetch(String uri) {
        if (uri == null) return null;
        Bitmap hit = images.get(uri);
        if (hit != null) return hit;
        try {
            byte[] data;
            if (uri.startsWith("http")) {
                HttpURLConnection con = (HttpURLConnection) new URL(uri).openConnection();
                con.setConnectTimeout(5000);
                con.setReadTimeout(8000);
                try (InputStream in = con.getInputStream()) {
                    data = readAll(in);
                }
            } else {
                try (InputStream in = getContext().getContentResolver().openInputStream(Uri.parse(uri))) {
                    data = readAll(in);
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= screenW) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b != null) images.put(uri, b);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
