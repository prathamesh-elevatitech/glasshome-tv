package com.pratham.tvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements ControlCenter.Host, TopShelf.Host {
    /** The home screen while it is in front. */
    static MainActivity resumed;
    /** Set by KeyService when the TV wakes: show "Who's Watching?". */
    static final String EXTRA_WAKE = "wake";

    private static final int COLUMNS = 6;
    private static final String INPUTS = "@inputs";

    /** First-run order; apps not listed follow alphabetically. */
    private static final List<String> DEFAULT_ORDER = Arrays.asList(
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "in.startv.hotstar",
            "com.apple.atve.androidtv.appletv",
            "tv.airtel.xstream.tvapp",
            "org.videolan.vlc",
            "com.tcl.tv",
            INPUTS,
            "com.tcl.ui_mediaCenter",
            "com.android.vending",
            "com.google.android.youtube.tvmusic",
            "com.google.android.videos",
            "com.tcl.t_solo",
            "com.tcl.dashboard",
            "com.android.tv.settings");

    /** Launcher entries that are plumbing, not apps. */
    private static final Set<String> EXCLUDED = new HashSet<>(Arrays.asList(
            "com.tcl.partnercustomizer",
            "com.tcl.tv.tclhome_passive",
            "com.tcl.suspension",
            "com.tcl.factory.view",
            "com.google.android.tungsten.setupwraith",
            "com.google.android.apps.tv.launcherx"));

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Backdrop backdrop = new Backdrop();
    private SharedPreferences prefs;

    // Geometry, in px.
    private int screenW, screenH, margin, dockPad, gap, tileW, tileH, dockTop, rowH;

    private WallpaperView wall, wallBlur;
    private View dim;
    private LinearLayout topBar;
    private TextView clock;
    private GlassView ccButton;
    private TvScrollView scroller;
    private LinearLayout content;
    private FrameLayout ccLayer, menuLayer;
    private GlassView movePill;
    private TopShelf shelf;
    private FrameLayout folderLayer;
    private AppEntry openFolderEntry;
    private final List<TileView> folderTiles = new ArrayList<>();
    private View nightDim;
    private FrameLayout zoom;
    private ImageView zoomIcon;
    private android.graphics.drawable.ColorDrawable zoomFill;
    private View launchDim;
    private Rect launchFrom = new Rect();
    private float launchRadius;
    private long stoppedAt;
    private ValueAnimator zoomAnim;
    private float zoomRadius;
    private String zoomKey;
    private boolean stoppedSinceLaunch;
    private FrameLayout rootView;
    private FrameLayout avatarButton;
    private FrameLayout pickerLayer;

    private List<AppEntry> apps = new ArrayList<>();
    private final List<AppEntry> dockApps = new ArrayList<>();
    private final List<AppEntry> gridApps = new ArrayList<>();
    private int lastDockWidth;
    private final List<TileView> tiles = new ArrayList<>();
    private final List<View> rows = new ArrayList<>();
    private String focusKey;
    private TileView moving;
    private boolean dirty = true;
    private int wallIndex = -1;

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            clock.setText(DateFormat.getTimeFormat(MainActivity.this).format(new Date()));
            // Night mode: the wallpaper dims from 9 pm to 6 am.
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            nightDim.animate().alpha(hour >= 21 || hour < 6 ? 0.22f : 0f).setDuration(1500).start();
            handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
        }
    };

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            dirty = true;
            if (resumed == MainActivity.this) reload();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        Ui.readDebugFlags();
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        margin = Ui.dp(48);
        dockPad = Ui.dp(22);
        gap = Ui.dp(22);
        tileW = (screenW - 2 * margin - 2 * dockPad - (COLUMNS - 1) * gap) / COLUMNS;
        tileH = Math.round(tileW * 0.6f); // tvOS icons are 5:3
        // tvOS 27: the dock sits low with only a sliver of the grid below; Down brings it up.
        dockTop = screenH - Ui.dp(50) - dockHeight();
        rowH = tileH + Ui.dp(58);

        backdrop.setScreen(screenW, screenH);
        getWindow().setBackgroundDrawable(null); // the root view paints everything
        // Opaque window: lets the TV's display hardware scan it out directly instead of
        // blending it with whatever is behind it at 4K.
        getWindow().setFormat(android.graphics.PixelFormat.OPAQUE);
        GlassView.backdrop = backdrop;
        setContentView(buildLayout());
        if (getIntent().getBooleanExtra(EXTRA_WAKE, false)) handler.post(this::showProfilePicker);
        loadWallpaper(prefs.getInt(Profiles.key(prefs, "wall"), Calendar.getInstance().get(Calendar.DAY_OF_YEAR)), false, null);


        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        loader.shutdownNow();
        if (GlassView.backdrop == backdrop) GlassView.backdrop = null;
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = this;
        handler.removeCallbacks(collapseForOverlay);
        if (zoomKey != null && !stoppedSinceLaunch && zoom.getVisibility() == View.VISIBLE) {
            // Came back from a pop-up style app that never fully covered home.
            zoomBack();
        } else if (zoomKey != null && stoppedSinceLaunch) {
            if (android.os.SystemClock.uptimeMillis() - stoppedAt < 900) {
                // Probably the app's splash hand-off (e.g. YouTube): keep the screen dark and wait.
                launchFrame(1f);
                zoom.setVisibility(View.VISIBLE);
                handler.postDelayed(settleReturn, 1200);
            } else {
                zoomBack();
            }
        }
        stoppedSinceLaunch = false;
        handler.post(clockTick);
        if (Ui.off("wall")) wall.setVisibility(View.GONE);
        if (Ui.off("autopilot")) handler.postDelayed(autopilot, 2000);
        if (dirty) reload();
        ensureKeyService();
        // Continue Watching may have changed while an app was open.
        View cur = getCurrentFocus();
        shelf.refresh(cur instanceof TileView && dockApps.contains(((TileView) cur).app) ? (TileView) cur : null);
        // Control Center over another app may have switched the wallpaper.
        int want = Math.floorMod(prefs.getInt(Profiles.key(prefs, "wall"), wallIndex), Backdrop.WALLPAPERS.length);
        if (wallIndex >= 0 && want != wallIndex) loadWallpaper(want, true, null);
    }

    /**
     * Keeps the remote-button service (gear, Netflix, Prime, YouTube) switched on. Android drops it
     * from the enabled list whenever this app is force-stopped or updated.
     */
    private void ensureKeyService() {
        String me = new android.content.ComponentName(this, KeyService.class).flattenToString();
        try {
            String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (on != null && on.contains(me)) return;
            String list = on == null || on.isEmpty() || on.equals("null") ? me : on + ":" + me;
            Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list);
            Settings.Secure.putInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (SecurityException e) {
            // WRITE_SECURE_SETTINGS not granted; install.sh grants it.
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // The app is now in front; reset the launch so home is clean underneath.
        handler.removeCallbacks(settleReturn);
        handler.removeCallbacks(collapseForOverlay);
        resetLaunch();
        if (zoomKey != null) {
            stoppedSinceLaunch = true;
            stoppedAt = android.os.SystemClock.uptimeMillis();
        }
    }

    /** A pop-up app (e.g. Inputs) leaves home visible behind it: shrink the window back into its icon. */
    private final Runnable collapseForOverlay = () -> {
        if (zoomKey != null && !stoppedSinceLaunch && zoom.getVisibility() == View.VISIBLE) {
            runLaunch(1f, 0f, 380, null, this::resetLaunch);
        }
    };

    @Override
    protected void onPause() {
        if (zoomKey != null) handler.postDelayed(collapseForOverlay, 900);
        if (resumed == this) resumed = null;
        handler.removeCallbacks(clockTick);
        if (moving != null) endMove();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // HOME pressed while already home: close overlays and jump back to the dock, like tvOS.
        if (intent.getBooleanExtra(EXTRA_WAKE, false)) {
            hideMenu();
            closeControlCenter();
            showProfilePicker();
            return;
        }
        if (Intent.ACTION_MAIN.equals(intent.getAction())) {
            // Back from an app: the zoom-back animation decides where focus lands.
            if (zoomKey != null && stoppedSinceLaunch) return;
            if (hidePicker()) return;
            hideMenu();
            closeFolder();
            closeControlCenter();
            if (moving != null) endMove();
            goTop();
        }
    }

    /** The dock hugs its apps; when empty it's full width to hold the hint. */
    private int dockWidth(int apps) {
        if (apps == 0) return screenW - 2 * margin;
        return apps * tileW + (apps - 1) * gap + 2 * dockPad;
    }

    private int dockHeight() {
        return tileH + 2 * dockPad;
    }

    // ---------------------------------------------------------------- layout

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        root.setBackgroundColor(Color.BLACK); // until the wallpaper is up
        root.setClipChildren(false);

        wall = new WallpaperView(this, backdrop, false);
        wall.setAlpha(0f);
        root.addView(wall, new FrameLayout.LayoutParams(-1, -1));
        wallBlur = new WallpaperView(this, backdrop, true);
        wallBlur.setAlpha(0f);
        root.addView(wallBlur, new FrameLayout.LayoutParams(-1, -1));
        dim = new View(this);
        dim.setBackgroundColor(Color.BLACK);
        dim.setAlpha(0f);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        nightDim = new View(this);
        nightDim.setBackgroundColor(Color.BLACK);
        nightDim.setAlpha(0f);
        root.addView(nightDim, new FrameLayout.LayoutParams(-1, -1));

        // Top Shelf: full-screen artwork of the focused dock app's content, behind the dock.
        shelf = new TopShelf(this, this, screenW, margin, Ui.dp(118), screenH - dockTop + Ui.dp(16));
        root.addView(shelf, new FrameLayout.LayoutParams(-1, -1));

        scroller = new TvScrollView(this);
        scroller.setClipChildren(false);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setOnScrollChangeListener((v, x, y, ox, oy) -> onScrolled(y));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(0, dockTop, 0, screenH);
        scroller.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroller, new FrameLayout.LayoutParams(-1, -1));


        // Top-right: clock and glass buttons, like tvOS 26.
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setClipChildren(false);
        clock = Ui.text(this, 21, Ui.semibold, Color.WHITE);
        clock.setShadowLayer(Ui.dp(10), 0, Ui.dp(1), 0x80000000);
        // Top-right: the time, a Control Center button and the profile button, each separate.
        clock.setShadowLayer(Ui.dp(10), 0, Ui.dp(1), 0x80000000);
        topBar.addView(clock);
        ccButton = new GlassView(this, 24).tint(0x33FFFFFF);
        if (!Ui.off("layers")) ccButton.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        ccButton.setId(View.generateViewId());
        ImageView ccIcon = new ImageView(this);
        ccIcon.setImageResource(R.drawable.ic_toggles);
        ccButton.addView(ccIcon, new FrameLayout.LayoutParams(Ui.dp(22), Ui.dp(22), Gravity.CENTER));
        ccButton.button(v -> toggleControlCenter(), new TextView[0], new ImageView[] {ccIcon});
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(Ui.dp(48), Ui.dp(48));
        clp.leftMargin = Ui.dp(16);
        topBar.addView(ccButton, clp);
        GlassView profile = new GlassView(this, 24).tint(0x33FFFFFF);
        if (!Ui.off("layers")) profile.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        avatarButton = new FrameLayout(this);
        profile.addView(avatarButton, new FrameLayout.LayoutParams(Ui.dp(40), Ui.dp(40), Gravity.CENTER));
        profile.button(v -> showProfilePicker(), new TextView[0], new ImageView[0]);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(48), Ui.dp(48));
        plp.leftMargin = Ui.dp(12);
        topBar.addView(profile, plp);
        updateAvatar();
        FrameLayout.LayoutParams tbLp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        tbLp.rightMargin = margin;
        tbLp.topMargin = Ui.dp(30);
        root.addView(topBar, tbLp);

        movePill = new GlassView(this, 24).tint(0x3DFFFFFF);
        TextView mt = Ui.text(this, 16, Ui.semibold, Color.WHITE);
        mt.setText("Use the arrows to move  ·  OK when done");
        mt.setPadding(Ui.dp(24), Ui.dp(14), Ui.dp(24), Ui.dp(14));
        movePill.addView(mt);
        movePill.setVisibility(View.GONE);
        FrameLayout.LayoutParams mpLp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        mpLp.topMargin = Ui.dp(30);
        root.addView(movePill, mpLp);

        ccLayer = new FrameLayout(this);
        ccLayer.setBackgroundColor(0x33000000);
        ccLayer.setClipChildren(false);
        ccLayer.setVisibility(View.GONE);
        root.addView(ccLayer, new FrameLayout.LayoutParams(-1, -1));

        folderLayer = new FrameLayout(this);
        folderLayer.setBackgroundColor(0x26000000);
        folderLayer.setClipChildren(false);
        folderLayer.setVisibility(View.GONE);
        root.addView(folderLayer, new FrameLayout.LayoutParams(-1, -1));

        menuLayer = new FrameLayout(this);
        menuLayer.setBackgroundColor(0x80000000);
        menuLayer.setClipChildren(false);
        menuLayer.setVisibility(View.GONE);
        root.addView(menuLayer, new FrameLayout.LayoutParams(-1, -1));

        pickerLayer = new FrameLayout(this);
        pickerLayer.setBackgroundColor(0x8C000000);
        pickerLayer.setClipChildren(false);
        pickerLayer.setVisibility(View.GONE);
        root.addView(pickerLayer, new FrameLayout.LayoutParams(-1, -1));

        launchDim = new View(this);
        launchDim.setBackgroundColor(Color.BLACK);
        launchDim.setAlpha(0f);
        root.addView(launchDim, new FrameLayout.LayoutParams(-1, -1));

        // The dark window that opens out of an icon when an app launches (and closes back into it).
        zoom = new FrameLayout(this);
        zoomFill = new android.graphics.drawable.ColorDrawable(0xFF1C1C1E);
        zoom.setBackground(zoomFill);
        zoomIcon = new ImageView(this);
        zoomIcon.setScaleType(ImageView.ScaleType.FIT_XY);
        zoom.addView(zoomIcon, new FrameLayout.LayoutParams(tileW, tileH));
        zoom.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), zoomRadius);
            }
        });
        zoom.setClipToOutline(true);
        zoom.setVisibility(View.INVISIBLE); // INVISIBLE, not GONE: showing it must not trigger a layout pass
        root.addView(zoom, new FrameLayout.LayoutParams(screenW, screenH));
        return root;
    }

    // ---------------------------------------------------------------- wallpaper

    private void loadWallpaper(int index, boolean fade, Runnable done) {
        int i = ((index % Backdrop.WALLPAPERS.length) + Backdrop.WALLPAPERS.length) % Backdrop.WALLPAPERS.length;
        wallIndex = i;
        loader.execute(() -> {
            try {
                Bitmap[] b = Backdrop.load(this, i, screenW, screenH);
                handler.post(() -> {
                    if (fade && wall.getAlpha() > 0f) {
                        wall.animate().alpha(0f).setDuration(220).withEndAction(() -> showWallpaper(b, done)).start();
                    } else {
                        showWallpaper(b, done);
                    }
                });
            } catch (Exception e) {
                // keep the plain black background
            }
        });
    }

    private void showWallpaper(Bitmap[] b, Runnable done) {
        backdrop.set(b[0], b[1]);
        // Once the wallpaper covers the screen, the black underneath is wasted GPU fill.
        wall.animate().alpha(1f).setDuration(600).withEndAction(() -> rootView.setBackground(null)).start();
        if (done != null) done.run();
    }

    @Override
    public Bitmap wallpaper() {
        return backdrop.sharp;
    }

    @Override
    public int wallpaperIndex() {
        return wallIndex;
    }

    @Override
    public void cycleWallpaper(Runnable done) {
        prefs.edit().putInt(Profiles.key(prefs, "wall"), wallIndex + 1).apply();
        loadWallpaper(wallIndex + 1, true, done);
    }

    // ---------------------------------------------------------------- data

    private void reload() {
        dirty = false;
        loader.execute(() -> {
            List<AppEntry> loaded = loadApps();
            handler.post(() -> {
                apps = loaded;
                splitDock();
                buildGrid();
            });
        });
    }

    private List<AppEntry> loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, AppEntry> found = new LinkedHashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, found);
        collect(pm, Intent.CATEGORY_LAUNCHER, found);

        Intent inputs = new Intent("com.android.tv.action.VIEW_INPUTS");
        if (inputs.resolveActivity(pm) != null) {
            AppEntry e = new AppEntry(INPUTS, "Inputs",
                    inputs.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), true);
            e.glyph = getDrawable(R.drawable.ic_input);
            e.colorTop = 0xFF4A9BFF;
            e.colorBottom = 0xFF1A56D6;
            found.put(INPUTS, e);
        }

        for (Map.Entry<String, List<String>> f : Folders.read(prefs).entrySet()) {
            List<AppEntry> members = new ArrayList<>();
            for (String k : f.getValue()) {
                AppEntry m = found.remove(k);
                if (m != null) members.add(m);
            }
            if (members.isEmpty()) continue;
            AppEntry folder = new AppEntry(Folders.key(f.getKey()), f.getKey(), null, true);
            folder.members = members;
            found.put(folder.key, folder);
        }

        Set<String> hidden = prefs.getStringSet("hidden", new HashSet<>());
        List<String> saved = new ArrayList<>(Arrays.asList(prefs.getString("order", "").split(",")));
        List<AppEntry> out = new ArrayList<>();
        for (List<String> order : Arrays.asList(saved, DEFAULT_ORDER)) {
            for (String key : order) {
                AppEntry e = found.remove(key);
                if (e != null && !hidden.contains(key)) out.add(e);
            }
        }
        List<AppEntry> rest = new ArrayList<>(found.values());
        rest.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        for (AppEntry e : rest) if (!hidden.contains(e.key)) out.add(e);
        for (AppEntry e : out) Ui.bakeTile(e, tileW, tileH, Ui.dp(14)); // off the UI thread
        return out;
    }

    private void collect(PackageManager pm, String category, Map<String, AppEntry> found) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        boolean leanback = Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            String pkg = ai.packageName;
            if (pkg.equals(getPackageName()) || EXCLUDED.contains(pkg) || found.containsKey(pkg)) continue;
            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setClassName(pkg, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            boolean settings = pkg.equals("com.android.tv.settings");
            AppEntry e = new AppEntry(pkg, settings ? "Settings" : String.valueOf(ri.loadLabel(pm)), launch, settings);
            if (settings) {
                e.glyph = getDrawable(R.drawable.ic_gear);
                e.colorTop = 0xFF9A9AA0;
                e.colorBottom = 0xFF545458;
            } else {
                Drawable banner = leanback ? ai.loadBanner(pm) : null;
                if (banner == null && leanback) banner = ai.applicationInfo.loadBanner(pm);
                e.banner = banner;
                if (banner == null) {
                    e.glyph = ai.loadIcon(pm);
                    int c = Ui.averageColor(e.glyph);
                    e.colorTop = Ui.shade(c, 0.9f);
                    e.colorBottom = Ui.shade(c, 0.5f);
                }
            }
            found.put(pkg, e);
        }
    }

    private void saveOrder() {
        apps = new ArrayList<>(dockApps);
        apps.addAll(gridApps);
        prefs.edit()
                .putString("order", join(apps))
                .putString(Profiles.key(prefs, "dock"), join(dockApps))
                .apply();
    }

    private static String join(List<AppEntry> list) {
        StringBuilder sb = new StringBuilder();
        for (AppEntry e : list) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.key);
        }
        return sb.toString();
    }

    /** Splits {@link #apps} into the dock favourites (saved list; first run: the first row) and the rest. */
    private void splitDock() {
        dockApps.clear();
        gridApps.clear();
        String saved = prefs.getString(Profiles.key(prefs, "dock"), null);
        List<String> keys = new ArrayList<>();
        if (saved == null) {
            for (int i = 0; i < Math.min(COLUMNS, apps.size()); i++) keys.add(apps.get(i).key);
        } else if (!saved.isEmpty()) {
            keys.addAll(Arrays.asList(saved.split(",")));
        }
        for (String k : keys) {
            for (AppEntry e : apps) {
                if (e.key.equals(k) && dockApps.size() < COLUMNS) dockApps.add(e);
            }
        }
        for (AppEntry e : apps) if (!dockApps.contains(e)) gridApps.add(e);
    }

    // ---------------------------------------------------------------- grid

    private void buildGrid() {
        content.removeAllViews();
        tiles.clear();
        rows.clear();
        if (dockApps.isEmpty() && gridApps.isEmpty()) splitDock();

        LinearLayout dockRow = newRow(0);
        for (int i = 0; i < dockApps.size(); i++) addTile(dockRow, dockApps.get(i), 0, i);
        if (dockApps.isEmpty()) {
            TextView hint = Ui.text(this, 16, Ui.medium, 0xCCFFFFFF);
            hint.setText("Hold OK on any app and choose Add to Dock");
            hint.setGravity(Gravity.CENTER);
            dockRow.addView(hint, new LinearLayout.LayoutParams(-1, tileH));
        }
        LinearLayout row = null;
        for (int i = 0; i < (Ui.off("grid") ? 0 : gridApps.size()); i++) {
            if (i % COLUMNS == 0) row = newRow(1 + i / COLUMNS);
            addTile(row, gridApps.get(i), 1 + i / COLUMNS, i % COLUMNS);
        }
        if (tiles.isEmpty()) return;
        TileView target = tiles.get(0);
        for (TileView t : tiles) if (t.app.key.equals(focusKey)) target = t;
        if (ccLayer.getVisibility() != View.VISIBLE && menuLayer.getVisibility() != View.VISIBLE) {
            target.requestFocus();
        }
    }

    private void addTile(LinearLayout row, AppEntry app, int r, int col) {
        TileView t = new TileView(this, app, tileW, tileH);
        t.setOnClickListener(v -> {
            if (moving != null) endMove();
            else launch((TileView) v);
        });
        t.setOnLongClickListener(v -> {
            if (moving == null) showAppMenu((TileView) v);
            return true;
        });
        t.setOnFocusChangeListener((v, has) -> {
            if (has) onTileFocused((TileView) v, r);
        });
        if (r == 0) {
            t.setNextFocusUpId(ccButton.getId());
            t.hideLabel();
        }
        // Tall enough to hold the name that appears under the focused icon.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH + Ui.dp(40));
        if (col != 0) lp.leftMargin = gap;
        row.addView(t, lp);
        tiles.add(t);
    }

    private LinearLayout newRow(int r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setGravity(Gravity.TOP);
        if (r == 0) {
            // The dock: the first row sits on a pane of glass.
            // The glass is a childless sibling behind the icons, cached as a GPU layer: it is
            // redrawn only when it moves, not on every frame of an icon animation.
            FrameLayout dock = new FrameLayout(this);
            dock.setClipChildren(false);
            GlassView glass = new GlassView(this, 36).tint(0x29FFFFFF);
            if (!Ui.off("layers")) glass.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            dock.addView(glass, new FrameLayout.LayoutParams(-1, -1));
            row.setPadding(dockPad, dockPad, dockPad, 0);
            row.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            dock.addView(row, new FrameLayout.LayoutParams(-1, -1));
            int w = dockWidth(dockApps.size());
            int from = lastDockWidth > 0 ? lastDockWidth : w;
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(from, dockHeight());
            dlp.leftMargin = (screenW - from) / 2;
            content.addView(dock, dlp);
            rows.add(dock);
            if (from != w) {
                ValueAnimator grow = ValueAnimator.ofInt(from, w);
                grow.setDuration(520);
                grow.setInterpolator(Ui.spring(7.5f, 10f));
                grow.addUpdateListener(a -> {
                    int v = (int) a.getAnimatedValue();
                    dlp.width = v;
                    dlp.leftMargin = (screenW - v) / 2;
                    dock.setLayoutParams(dlp);
                });
                grow.start();
            }
            lastDockWidth = w;
        } else {
            row.setPadding(margin + dockPad, 0, 0, 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, rowH);
            if (r == 1) lp.topMargin = Ui.dp(32);
            content.addView(row, lp);
            rows.add(row);
        }
        return row;
    }

    private void onTileFocused(TileView t, int r) {
        focusKey = t.app.key;
        if (r == 0 && t.app.members == null && !t.app.key.startsWith("@")) shelf.showFor(t);
        else shelf.hide();
        if (r > 0 && r < rows.size() && rows.get(r).getTop() == 0) {
            // Grid just rebuilt and not laid out yet: scroll once it is.
            scroller.post(() -> onTileFocused(t, r));
            return;
        }
        int target = r == 0 ? 0 : rows.get(r).getTop() - Ui.dp(140);
        if (scroller.getScrollY() != target) scroller.glideTo(target);
    }

    private void onScrolled(int y) {
        float p = Math.min(1f, y / (float) Ui.dp(180));
        wallBlur.setAlpha(p);
        dim.setAlpha(0.28f * p);
        topBar.setAlpha(1f - p);
        backdrop.invalidateWatchers();
    }

    private void goTopKeepFocus() {
        scroller.glideTo(0);
    }

    private void goTop() {
        if (!tiles.isEmpty()) tiles.get(0).requestFocus();
        scroller.glideTo(0);
    }

    /**
     * tvOS app launch. The icon itself barely grows: a dark rounded window opens out of the
     * icon's spot to fill the screen, the icon stays near its natural size in the middle and
     * fades away, and the home screen pushes in slightly and dims behind it. The app then fades
     * in from that dark window. Closing plays the same motion in reverse.
     */
    private void launch(TileView t) {
        if (t.app.members != null) {
            openFolder(t.app);
            return;
        }
        if (zoomAnim != null && zoomAnim.isRunning()) return;
        zoomKey = t.app.key;
        launchFrom = cardRect(t);
        launchRadius = Ui.dp(14) * t.card.getScaleX();
        zoomIcon.setImageBitmap(t.app.tile);
        launchFrame(0f);
        zoom.setVisibility(View.VISIBLE);
        final boolean[] started = {false};
        runLaunch(0f, 1f, 520, p -> {
            // Start the app once the window nearly fills the screen; it then appears in place of
            // the launch screen (the system's own transition is off, see install.sh).
            if (!started[0] && p >= 0.85f) {
                started[0] = true;
                try {
                    startActivity(t.app.intent,
                            ActivityOptions.makeCustomAnimation(this, R.anim.app_open_enter, R.anim.hold).toBundle());
                } catch (RuntimeException e) {
                    resetLaunch();
                    zoomKey = null;
                    Toast.makeText(this, "Couldn't open " + t.app.label, Toast.LENGTH_SHORT).show();
                }
            }
        }, null);
    }

    /** The on-screen rectangle of a tile's (possibly scaled) icon. */
    private Rect cardRect(TileView t) {
        int[] loc = new int[2];
        t.card.getLocationInWindow(loc);
        int w = Math.round(t.card.getWidth() * t.card.getScaleX());
        int h = Math.round(t.card.getHeight() * t.card.getScaleY());
        return new Rect(loc[0], loc[1], loc[0] + w, loc[1] + h);
    }

    private interface Progress {
        void at(float p);
    }

    /** Drives {@link #launchFrame} from {@code from} to {@code to} with tvOS's soft ease-out. */
    private void runLaunch(float from, float to, long ms, Progress progress, Runnable end) {
        if (zoomAnim != null) zoomAnim.cancel();
        zoomAnim = ValueAnimator.ofFloat(from, to);
        zoomAnim.setDuration(ms);
        zoomAnim.setInterpolator(new android.view.animation.PathInterpolator(0.32f, 0.72f, 0f, 1f));
        zoomAnim.addUpdateListener(an -> {
            float p = (float) an.getAnimatedValue();
            launchFrame(p);
            if (progress != null) progress.at(p);
        });
        if (end != null) {
            zoomAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator an) {
                    end.run();
                }
            });
        }
        zoomAnim.start();
    }

    /**
     * One frame of the launch at progress {@code p} (0 = the icon on the grid, 1 = full screen).
     * Moves bounds directly (no layout pass) so it stays cheap on this TV's GPU.
     */
    private void launchFrame(float p) {
        Rect a = launchFrom;
        int l = lerp(a.left, 0, p), t = lerp(a.top, 0, p), r = lerp(a.right, screenW, p), b = lerp(a.bottom, screenH, p);
        zoom.setLeftTopRightBottom(l, t, r, b);
        // Stays rounded all the way, like a card opening out of the icon.
        zoomRadius = launchRadius + (Ui.dp(28) - launchRadius) * p;
        zoom.invalidateOutline();
        // The dark window fills in early, so it reads as the app opening, not a stretched icon.
        zoomFill.setAlpha(Math.round(255 * Math.min(1f, p / 0.3f)));
        zoom.invalidate();
        // The icon grows a little and stays centred, like a launch screen, until the app draws.
        float k = 1f + 0.5f * p;
        int iw = Math.round(a.width() * k), ih = Math.round(a.height() * k);
        int il = (r - l - iw) / 2, it = (b - t - ih) / 2;
        zoomIcon.setLeftTopRightBottom(il, it, il + iw, it + ih);
        // The home screen pushes in toward the icon and dims.
        float s = 1f + 0.06f * p;
        for (View v : new View[] {wall, wallBlur, scroller, topBar}) {
            v.setPivotX(a.exactCenterX());
            v.setPivotY(a.exactCenterY());
            v.setScaleX(s);
            v.setScaleY(s);
        }
        launchDim.setAlpha(0.5f * p);
    }

    private static int lerp(int a, int b, float p) {
        return Math.round(a + (b - a) * p);
    }

    private void resetLaunch() {
        if (zoomAnim != null) zoomAnim.cancel();
        zoom.setVisibility(View.INVISIBLE);
        for (View v : new View[] {wall, wallBlur, scroller, topBar}) {
            v.setScaleX(1f);
            v.setScaleY(1f);
        }
        launchDim.setAlpha(0f);
    }

    /** Coming back from an app: the dark window shrinks back into the icon as it reappears. */
    private void zoomBack() {
        TileView target = null;
        for (TileView t : tiles) if (t.app.key.equals(zoomKey)) target = t;
        for (TileView t : folderTiles) if (t.app.key.equals(zoomKey)) target = t;
        zoomKey = null;
        if (target == null || target.app.tile == null) {
            resetLaunch();
            return;
        }
        if (!folderTiles.contains(target)) {
            int r = dockApps.contains(target.app) ? 0 : 1 + gridApps.indexOf(target.app) / COLUMNS;
            scroller.scrollTo(0, r == 0 ? 0 : rows.get(Math.min(r, rows.size() - 1)).getTop() - Ui.dp(140));
        }
        target.requestFocus();
        final TileView t = target;
        zoomIcon.setImageBitmap(t.app.tile);
        // Start fully open (dark screen) before the first frame is drawn, then close.
        launchFrame(1f);
        zoom.setVisibility(View.VISIBLE);
        zoom.post(() -> {
            launchFrom = cardRect(t);
            launchRadius = Ui.dp(14) * TileView.FOCUS_SCALE;
            runLaunch(1f, 0f, 480, null, this::resetLaunch);
        });
    }

    /** If home only flashed up during an app's start-up hand-off, keep the dark window and wait. */
    private final Runnable settleReturn = () -> {
        if (resumed == this && zoomKey != null) zoomBack();
    };

    private void launchIntent(Intent i, String what) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(this, what + " isn't available", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- control center

    void toggleControlCenter() {
        if (ccLayer.getVisibility() == View.VISIBLE) closeControlCenter();
        else openControlCenter();
    }

    private void openControlCenter() {
        hideMenu();
        if (moving != null) endMove();
        ccLayer.removeAllViews();
        View panel = ControlCenter.build(this, this);
        int w = Ui.dp(ControlCenter.WIDTH);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        lp.rightMargin = margin;
        lp.topMargin = Ui.dp(30 + 48 + 12); // just under the time capsule
        ccLayer.addView(panel, lp);
        setBehindFocusable(false);
        ccLayer.setAlpha(0f);
        ccLayer.setVisibility(View.VISIBLE);
        ccLayer.animate().alpha(1f).setDuration(180).start();
        // Grow out of the Control Center button.
        panel.setPivotX(w - Ui.dp(24));
        panel.setPivotY(0f);
        panel.setScaleX(0.6f);
        panel.setScaleY(0.6f);
        panel.setAlpha(0f);
        panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(460).setInterpolator(Ui.spring(9f, 9f))
                .start();
        View first = (View) panel.getTag();
        if (first != null) first.requestFocus();
    }

    @Override
    public void closeControlCenter() {
        if (ccLayer.getVisibility() != View.VISIBLE) return;
        View panel = ccLayer.getChildCount() > 0 ? ccLayer.getChildAt(0) : null;
        if (panel != null) {
            panel.animate().scaleX(0.85f).scaleY(0.85f).alpha(0f).setDuration(200)
                    .setInterpolator(new DecelerateInterpolator()).setUpdateListener(null).start();
        }
        ccLayer.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            ccLayer.setVisibility(View.GONE);
            ccLayer.removeAllViews();
        }).start();
        setBehindFocusable(true);
        refocus();
    }

    private ValueAnimator blurAnim;

    /** tvOS 27: the home screen melts into a blur behind Control Center and folders. */
    private void blurHome(boolean on) {
        if (blurAnim != null) blurAnim.cancel();
        float from = on ? 0f : Ui.dp(18), to = on ? Ui.dp(18) : 0f;
        blurAnim = ValueAnimator.ofFloat(from, to);
        blurAnim.setDuration(260);
        blurAnim.addUpdateListener(a -> {
            float r = (float) a.getAnimatedValue();
            scroller.setRenderEffect(r < 0.5f ? null
                    : android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP));
        });
        blurAnim.start();
        // The wallpaper behind goes to its pre-blurred copy too (free: it's already rendered).
        float rest = Math.min(1f, scroller.getScrollY() / (float) Ui.dp(180));
        wallBlur.animate().alpha(on ? 1f : rest).setDuration(260).start();
        dim.animate().alpha(on ? 0.18f : 0.28f * rest).setDuration(260).start();
    }

    private void setBehindFocusable(boolean on) {
        int f = on ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS;
        scroller.setDescendantFocusability(f);
        shelf.setDescendantFocusability(f);
        topBar.setDescendantFocusability(f);
    }

    private void refocus() {
        for (TileView t : tiles) {
            if (t.app.key.equals(focusKey)) {
                t.requestFocus();
                return;
            }
        }
        if (!tiles.isEmpty()) tiles.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- rearrange

    private void startMove(TileView t) {
        moving = t;
        t.setWiggling(true);
        movePill.setAlpha(0f);
        movePill.setVisibility(View.VISIBLE);
        movePill.animate().alpha(1f).setDuration(200).start();
        topBar.animate().alpha(0f).setDuration(150).start();
    }

    private void endMove() {
        if (moving == null) return;
        moving.setWiggling(false);
        moving = null;
        movePill.setVisibility(View.GONE);
        topBar.animate().alpha(1f - Math.min(1f, scroller.getScrollY() / (float) Ui.dp(180))).setDuration(150).start();
        saveOrder();
    }

    private void moveBy(int delta) {
        List<AppEntry> list = dockApps.contains(moving.app) ? dockApps : gridApps;
        int from = list.indexOf(moving.app);
        int to = Math.max(0, Math.min(list.size() - 1, from + delta));
        if (to == from) return;
        AppEntry a = list.remove(from);
        list.add(to, a);
        focusKey = a.key;
        buildGrid();
        for (TileView t : tiles) {
            if (t.app == a) {
                moving = t;
                t.setWiggling(true);
            }
        }
    }

    // ---------------------------------------------------------------- long-press menu

    private void showAppMenu(TileView t) {
        AppEntry a = t.app;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        boolean inDock = dockApps.contains(a);
        if (inDock) {
            labels.add("Remove from Dock");
            actions.add(() -> {
                dockApps.remove(a);
                gridApps.add(0, a);
                focusKey = a.key;
                saveOrder();
                buildGrid();
            });
        } else {
            labels.add("Add to Dock");
            actions.add(() -> {
                if (dockApps.size() >= COLUMNS) {
                    Toast.makeText(this, "The dock holds " + COLUMNS + " apps. Remove one first.", Toast.LENGTH_SHORT).show();
                    return;
                }
                gridApps.remove(a);
                dockApps.add(a);
                focusKey = a.key;
                saveOrder();
                buildGrid();
                goTopKeepFocus();
            });
        }
        labels.add("Move");
        actions.add(() -> startMove(t));
        if (Folders.isFolder(a.key)) {
            labels.add("Ungroup Folder");
            actions.add(() -> ungroup(a));
        } else if (!a.key.startsWith("@")) {
            labels.add("Move to Folder…");
            actions.add(() -> showFolderPicker(a));
        }
        if (!a.key.equals("com.android.tv.settings") && !Folders.isFolder(a.key)) {
            labels.add("Hide from Home");
            actions.add(() -> {
                Set<String> hidden = new HashSet<>(prefs.getStringSet("hidden", new HashSet<>()));
                hidden.add(a.key);
                List<AppEntry> list = dockApps.contains(a) ? dockApps : gridApps;
                int i = list.indexOf(a);
                focusKey = list.size() > 1 ? list.get(i == list.size() - 1 ? i - 1 : i + 1).key : null;
                list.remove(a);
                prefs.edit().putStringSet("hidden", hidden).apply();
                saveOrder();
                buildGrid();
            });
        }
        if (!a.key.startsWith("@")) {
            labels.add("App Info");
            actions.add(() -> launchIntent(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", a.key, null)), "App info"));
        }
        int hiddenCount = prefs.getStringSet("hidden", new HashSet<>()).size();
        if (hiddenCount > 0) {
            labels.add("Show Hidden Apps (" + hiddenCount + ")");
            actions.add(() -> {
                prefs.edit().remove("hidden").apply();
                reload();
            });
        }
        showMenu(a.label, labels, actions);
    }

    // ---------------------------------------------------------------- folders

    private void showFolderPicker(AppEntry a) {
        List<String> names = new ArrayList<>(Folders.read(prefs).keySet());
        for (String p : Folders.PRESETS) if (!names.contains(p)) names.add(p);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (String n : names) {
            labels.add(n);
            actions.add(() -> moveToFolder(a, n));
        }
        handler.post(() -> showMenu("Move " + a.label + " to", labels, actions));
    }

    private void moveToFolder(AppEntry a, String name) {
        Map<String, List<String>> all = Folders.read(prefs);
        for (List<String> l : all.values()) l.remove(a.key);
        boolean isNew = !all.containsKey(name);
        List<String> list = isNew ? new ArrayList<>() : all.get(name);
        list.add(a.key);
        all.put(name, list);
        Folders.write(prefs, all);
        String fk = Folders.key(name);
        String dockKey = Profiles.key(prefs, "dock");
        // A new folder takes the app's place; otherwise the app just leaves its spot.
        Folders.replaceKey(prefs, "order", a.key, isNew ? fk : null);
        Folders.replaceKey(prefs, dockKey, a.key, isNew ? fk : null);
        focusKey = fk;
        refreshApps();
    }

    private void removeFromFolder(AppEntry folder, AppEntry m) {
        Map<String, List<String>> all = Folders.read(prefs);
        List<String> list = all.get(Folders.name(folder.key));
        if (list != null) list.remove(m.key);
        Folders.write(prefs, all);
        Folders.insertAfter(prefs, "order", folder.key, m.key);
        closeFolder();
        focusKey = m.key;
        refreshApps();
    }

    private void ungroup(AppEntry folder) {
        Map<String, List<String>> all = Folders.read(prefs);
        all.remove(Folders.name(folder.key));
        Folders.write(prefs, all);
        for (int i = folder.members.size() - 1; i >= 0; i--) {
            Folders.insertAfter(prefs, "order", folder.key, folder.members.get(i).key);
        }
        Folders.replaceKey(prefs, Profiles.key(prefs, "dock"), folder.key, null);
        focusKey = folder.members.get(0).key;
        refreshApps();
    }

    /** Re-reads apps and folders from scratch (folder membership changed). */
    private void refreshApps() {
        dockApps.clear();
        gridApps.clear();
        reload();
    }

    /** tvOS folder: the home screen blurs and the folder's apps appear centred, under its name. */
    private void openFolder(AppEntry f) {
        openFolderEntry = f;
        folderLayer.removeAllViews();
        folderTiles.clear();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        TextView title = Ui.text(this, 34, Ui.bold, Color.WHITE);
        title.setText(f.label);
        title.setSingleLine(true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setShadowLayer(Ui.dp(10), 0, Ui.dp(1), 0x66000000);
        col.addView(title, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = null;
        for (int i = 0; i < f.members.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                rlp.topMargin = Ui.dp(i == 0 ? 36 : 14);
                col.addView(row, rlp);
            }
            AppEntry m = f.members.get(i);
            TileView t = new TileView(this, m, tileW, tileH);
            t.setOnClickListener(v -> launch((TileView) v));
            t.setOnLongClickListener(v -> {
                List<String> labels = new ArrayList<>(Arrays.asList("Remove from Folder", "App Info"));
                List<Runnable> actions = new ArrayList<>();
                actions.add(() -> removeFromFolder(f, m));
                actions.add(() -> launchIntent(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", m.key, null)), "App info"));
                showMenu(m.label, labels, actions);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH + Ui.dp(40));
            if (i % COLUMNS != 0) lp.leftMargin = gap;
            row.addView(t, lp);
            folderTiles.add(t);
        }
        folderLayer.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        setBehindFocusable(false);
        blurHome(true);
        topBar.animate().alpha(0f).setDuration(200).start();
        folderLayer.setAlpha(0f);
        folderLayer.setVisibility(View.VISIBLE);
        folderLayer.animate().alpha(1f).setDuration(220).start();
        col.setScaleX(0.9f);
        col.setScaleY(0.9f);
        col.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(Ui.spring(9f, 9f)).start();
        if (!folderTiles.isEmpty()) folderTiles.get(0).requestFocus();
    }

    private boolean closeFolder() {
        if (folderLayer.getVisibility() != View.VISIBLE) return false;
        folderLayer.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            folderLayer.setVisibility(View.GONE);
            folderLayer.removeAllViews();
        }).start();
        folderTiles.clear();
        setBehindFocusable(true);
        blurHome(false);
        topBar.animate().alpha(1f - Math.min(1f, scroller.getScrollY() / (float) Ui.dp(180))).setDuration(200).start();
        if (openFolderEntry != null) focusKey = openFolderEntry.key;
        openFolderEntry = null;
        refocus();
        return true;
    }

    private void showMenu(String title, List<String> labels, List<Runnable> actions) {
        menuLayer.removeAllViews();
        GlassView panel = new GlassView(this, 34).tint(0x33FFFFFF).neutral(); // grey frost, like tvOS menus
        panel.setElevation(Ui.dp(40));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(Ui.dp(16), Ui.dp(20), Ui.dp(16), Ui.dp(16));
        panel.addView(col, new FrameLayout.LayoutParams(-1, -2));
        TextView head = Ui.text(this, 15, Ui.semibold, 0xB3FFFFFF);
        head.setText(title);
        head.setPadding(Ui.dp(14), 0, Ui.dp(14), Ui.dp(12));
        col.addView(head);
        View first = null;
        for (int i = 0; i < labels.size(); i++) {
            Runnable action = actions.get(i);
            GlassView item = new GlassView(this, 22).plain();
            TextView tv = Ui.text(this, 18, Ui.semibold, Color.WHITE);
            tv.setText(labels.get(i));
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setPadding(Ui.dp(18), 0, Ui.dp(18), 0);
            item.addView(tv, new FrameLayout.LayoutParams(-1, -1));
            item.button(v -> {
                hideMenu();
                action.run();
            }, new TextView[] {tv}, new ImageView[0]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(52));
            lp.topMargin = Ui.dp(6);
            col.addView(item, lp);
            if (first == null) first = item;
        }
        menuLayer.addView(panel, new FrameLayout.LayoutParams(Ui.dp(360), -2, Gravity.CENTER));
        setBehindFocusable(false);
        menuLayer.setAlpha(0f);
        menuLayer.setVisibility(View.VISIBLE);
        menuLayer.animate().alpha(1f).setDuration(160).start();
        panel.setScaleX(0.9f);
        panel.setScaleY(0.9f);
        panel.animate().scaleX(1f).scaleY(1f).setDuration(480).setInterpolator(Ui.spring(7f, 11f))
                .setUpdateListener(a -> panel.invalidate()).start();
        if (first != null) first.requestFocus();
    }

    // ---------------------------------------------------------------- profiles

    private void updateAvatar() {
        avatarButton.removeAllViews();
        avatarButton.addView(Profiles.avatar(this, Profiles.current(prefs), 40),
                new FrameLayout.LayoutParams(Ui.dp(40), Ui.dp(40), Gravity.CENTER));
    }

    /** tvOS "Who's Watching?": full-screen over the blurred wallpaper. */
    private void showProfilePicker() {
        if (pickerLayer.getVisibility() == View.VISIBLE) return;
        hideMenu();
        closeControlCenter();
        if (moving != null) endMove();
        pickerLayer.removeAllViews();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        TextView title = Ui.text(this, 40, Ui.bold, Color.WHITE);
        title.setText("Who's Watching?");
        title.setSingleLine(true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setLetterSpacing(-0.01f);
        col.addView(title, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.topMargin = Ui.dp(56);
        col.addView(row, rlp);
        View focus = null;
        for (int i = 0; i < Profiles.NAMES.length; i++) {
            final int idx = i;
            View item = profileItem(i, v -> selectProfile(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = Ui.dp(64);
            row.addView(item, lp);
            if (i == Profiles.current(prefs)) focus = item;
        }
        // Whether this picker appears every time the TV wakes.
        GlassView wake = new GlassView(this, 22).plain();
        TextView wt = Ui.text(this, 15, Ui.medium, 0xB3FFFFFF);
        wt.setTag(0xB3FFFFFF);
        wt.setGravity(Gravity.CENTER);
        wt.setPadding(Ui.dp(20), 0, Ui.dp(20), 0);
        Runnable label = () -> wt.setText("Ask when the TV wakes:  " + (prefs.getBoolean("wake_picker", true) ? "On" : "Off"));
        label.run();
        wake.addView(wt, new FrameLayout.LayoutParams(-2, -1, Gravity.CENTER));
        wake.button(v -> {
            prefs.edit().putBoolean("wake_picker", !prefs.getBoolean("wake_picker", true)).apply();
            label.run();
        }, new TextView[] {wt}, new ImageView[0]);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-2, Ui.dp(44));
        wlp.topMargin = Ui.dp(52);
        col.addView(wake, wlp);
        pickerLayer.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        setBehindFocusable(false);
        // Only the blurred wallpaper stays behind the picker, like tvOS.
        wallBlur.animate().alpha(1f).setDuration(300).start();
        scroller.animate().alpha(0f).setDuration(250).start();
        topBar.animate().alpha(0f).setDuration(250).start();
        pickerLayer.setAlpha(0f);
        pickerLayer.setVisibility(View.VISIBLE);
        pickerLayer.animate().alpha(1f).setDuration(300).start();
        col.setScaleX(1.06f);
        col.setScaleY(1.06f);
        col.animate().scaleX(1f).scaleY(1f).setDuration(500).setInterpolator(Ui.spring(7f, 9f)).start();
        if (focus != null) focus.requestFocus();
    }

    private View profileItem(int i, View.OnClickListener click) {
        final GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(Ui.dp(3), Color.WHITE);
        ring.setAlpha(0);
        final FrameLayout avatar = Profiles.avatar(this, i, 128);
        avatar.setForeground(ring);
        avatar.setElevation(Ui.dp(10));
        final TextView name = Ui.text(this, 20, Ui.semibold, 0xB3FFFFFF);
        name.setText(Profiles.NAMES[i]);
        LinearLayout item = new LinearLayout(this) {
            @Override
            protected void onFocusChanged(boolean f, int dir, Rect prev) {
                super.onFocusChanged(f, dir, prev);
                if (f) Ui.tick(getContext());
                avatar.animate().scaleX(f ? 1.12f : 1f).scaleY(f ? 1.12f : 1f).translationZ(f ? Ui.dp(24) : 0)
                        .setDuration(f ? 480 : 200)
                        .setInterpolator(f ? Ui.spring(6.5f, 12f) : new DecelerateInterpolator()).start();
                ring.setAlpha(f ? 255 : 0);
                name.setTextColor(f ? Color.WHITE : 0xB3FFFFFF);
            }
        };
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setClipChildren(false);
        item.setFocusable(true);
        item.setClickable(true);
        item.setDefaultFocusHighlightEnabled(false);
        item.setOnClickListener(click);
        item.addView(avatar, new LinearLayout.LayoutParams(Ui.dp(128), Ui.dp(128)));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = Ui.dp(22);
        item.addView(name, nlp);
        return item;
    }

    private void selectProfile(int i) {
        if (i != Profiles.current(prefs)) {
            Profiles.set(prefs, i);
            dockApps.clear();
            gridApps.clear();
            splitDock();
            focusKey = null;
            buildGrid();
            loadWallpaper(prefs.getInt(Profiles.key(prefs, "wall"), Calendar.getInstance().get(Calendar.DAY_OF_YEAR)), true, null);
            updateAvatar();
        }
        hidePicker();
    }

    private boolean hidePicker() {
        if (pickerLayer.getVisibility() != View.VISIBLE) return false;
        pickerLayer.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            pickerLayer.setVisibility(View.GONE);
            pickerLayer.removeAllViews();
        }).start();
        setBehindFocusable(true);
        goTop();
        wallBlur.animate().alpha(0f).setDuration(300).start();
        scroller.animate().alpha(1f).setDuration(300).start();
        topBar.animate().alpha(1f).setDuration(300).start();
        return true;
    }

    private boolean hideMenu() {
        if (menuLayer.getVisibility() != View.VISIBLE) return false;
        menuLayer.setVisibility(View.GONE);
        menuLayer.removeAllViews();
        if (folderLayer.getVisibility() == View.VISIBLE) {
            if (!folderTiles.isEmpty()) folderTiles.get(0).requestFocus();
            return true;
        }
        setBehindFocusable(true);
        refocus();
        return true;
    }

    // ---------------------------------------------------------------- keys

    /** Profiling only: moves focus right/left inside the app, so no adb input competes for the CPU. */
    private int autoStep;
    private final Runnable autopilot = new Runnable() {
        @Override
        public void run() {
            View f = getCurrentFocus();
            if (f != null && resumed == MainActivity.this) {
                int dir = (autoStep++ / 4) % 2 == 0 ? View.FOCUS_RIGHT : View.FOCUS_LEFT;
                View n = f.focusSearch(dir);
                if (n != null) n.requestFocus(dir);
            }
            if (autoStep < 24) handler.postDelayed(this, 400);
        }
    };

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (moving != null) {
            if (down) {
                switch (k) {
                    case KeyEvent.KEYCODE_DPAD_LEFT: moveBy(-1); return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT: moveBy(1); return true;
                    case KeyEvent.KEYCODE_DPAD_UP: moveBy(-COLUMNS); return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN: moveBy(COLUMNS); return true;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_BACK:
                        endMove();
                        return true;
                    default:
                        break;
                }
            } else if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BACK) {
                return true;
            }
        }
        // Top Shelf: Up from a dock app goes into its shelf; Down from the shelf goes back.
        if (down && moving == null && menuLayer.getVisibility() != View.VISIBLE
                && folderLayer.getVisibility() != View.VISIBLE && ccLayer.getVisibility() != View.VISIBLE) {
            View f = getCurrentFocus();
            if (k == KeyEvent.KEYCODE_DPAD_UP && f instanceof TileView && dockApps.contains(((TileView) f).app)
                    && shelf.hasContent()) {
                shelf.enter();
                return true;
            }
            if (f != null && isInside(f, shelf)) {
                if (k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_BACK) {
                    TileView o = shelf.owner();
                    shelf.exit();
                    if (o != null) o.requestFocus();
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_UP) return true; // nothing above the buttons
            }
        }
        // The remote's gear button.
        if (k == KeyEvent.KEYCODE_NOTIFICATION) {
            if (!down) toggleControlCenter();
            return true;
        }
        if (k == KeyEvent.KEYCODE_MENU && !down && menuLayer.getVisibility() != View.VISIBLE) {
            View f = getCurrentFocus();
            if (f instanceof TileView) {
                showAppMenu((TileView) f);
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** The Top Shelf carousel took focus: slide the dock and grid away (and back). */
    @Override
    public void onCarousel(boolean active) {
        scroller.animate().translationY(active ? screenH - dockTop + Ui.dp(24) : 0f)
                .setDuration(active ? 420 : 380).setInterpolator(new DecelerateInterpolator(1.8f)).start();
        topBar.animate().alpha(active ? 0f : 1f).setDuration(250).start();
    }

    private static boolean isInside(View v, View parent) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) {
            if (p == parent) return true;
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        if (hidePicker()) return;
        if (hideMenu()) return;
        if (closeFolder()) return;
        if (ccLayer.getVisibility() == View.VISIBLE) {
            closeControlCenter();
            return;
        }
        goTop(); // never leave the home screen
    }

    /** ScrollView that never auto-scrolls on focus and glides with a soft ease instead. */
    static final class TvScrollView extends ScrollView {
        private ValueAnimator glide;

        TvScrollView(Context c) {
            super(c);
        }

        void glideTo(int y) {
            if (glide != null) glide.cancel();
            glide = ValueAnimator.ofInt(getScrollY(), Math.max(0, y));
            glide.setDuration(380);
            glide.setInterpolator(new DecelerateInterpolator(2.2f));
            glide.addUpdateListener(a -> scrollTo(0, (int) a.getAnimatedValue()));
            glide.start();
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            return 0;
        }
    }
}
