package tv.orchard.home;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Root screen. Manifest contract: PLAN 1.5. onNewIntent + onResume must restore state cheaply
 * (HoF brings the running instance forward). Setup build: shows SafeModeView in both modes until the
 * real home lands in Phase 3; the crash-failsafe wiring (PLAN 2.2) is already live.
 */
public class HomeActivity extends Activity {
    private FireTvBridge bridge;
    private boolean safe;

    // ---- home view tree (null in safe mode) ----
    private Config config;
    private AppCatalog catalog;
    private ArtCache art;
    private Wallpaper wallpaper;
    private FrameLayout root, content, overlay;
    private HomeScroll scroll;
    private TopShelfView topShelf;
    private DockBand dockBand;
    private StatusStrip strip;
    private final List<TileRow> rows = new ArrayList<TileRow>();     // rows.get(0) = dock, k >= 1 = grid rows
    private final Map<String, TileView> tilesByPkg = new HashMap<String, TileView>();
    private boolean labelsAlways;
    /** Key of the focused tile (package, or Ui.SHELF_KEY_FTH) and its row; task 3.4b restores focus from these. */
    String lastFocusedKey;
    int lastFocusedRow;
    private int appliedRow = -1;                                      // row the scroll rule last acted on
    int lastFocusedCx;                                                // centre x of the focused tile, for column fallback

    // ---- input / lifecycle state (task 3.4b) ----
    private QuickPanel panel;                                         // added once to overlay; GONE until opened
    private ConfirmSheet sheet;                                       // null when no sheet is up
    private TileMenu menu;                                            // tile context menu, null when closed
    private TileView menuTile;                                        // the tile the menu was opened on
    private int appInfoAvail = -1;                                    // -1 unknown, 0 no, 1 yes (resolved once per process)
    private boolean selectDown, selectLong;                           // Select press tracking (tile launch vs long-press menu)
    private ClockFace clock;                                          // screensaver preview, null when closed
    private boolean clockArmed;                                       // a key DOWN was eaten while the clock is up
    private int seenEpoch;                                            // SettingsActivity save counter last applied
    private final KeyRules.MenuTap menuTap = new KeyRules.MenuTap(Ui.MENU_MAX_TAP_MS);
    private boolean released;                                         // onStop dropped wallpaper + art
    private String loadedWallpaper;

    // ---- motion state (task 4.2) ----
    private boolean reducedMotion;                                    // evaluated in buildHome + restoreState
    private boolean idle;                                             // tiles are idle-dimmed
    private final Handler idleHandler = new Handler(Looper.getMainLooper());
    private final Runnable idleTick = this::goIdle;                   // one runnable, reused: no allocation per key

    // ---- grid Move mode (task p5-2c) ----
    private String movingPkg;                                         // non-null while Move mode is on
    private TileView movingTile;
    private List<String> moveOriginal, movePreview;                   // grid order at entry / live preview
    private int moveIndex;
    private boolean swallowSelect;                                    // Move mode ended silently: eat the next Select press
    private boolean moveSelDown;                                      // a Select DOWN was seen in this mode (the opening press's UP must not drop)
    private boolean relayouting;                                      // focus callbacks during a relayout are noise

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        bridge = new FireTvBridge(this);
        applyDebugChannel(getIntent());              // before buildHome(): debug_card / debug_slow_anim are read there
        safe = bridge.evaluateLaunch(getIntent());   // records CATEGORY_HOME launches, sets safe_mode on a loop
        if (!safe) debugHooks(getIntent());
        View first;
        if (safe) {
            first = new SafeModeView(this);          // crash safe-mode variant; nothing else is built
        } else {
            first = buildHome();
        }
        setContentView(first);
        FirstFrame.watch(first, ((OrchardApp) getApplication()).state());
    }

    // ---- home assembly (task 3.4a) ---------------------------------------------------------------------

    private static FrameLayout.LayoutParams at(int w, int h, int x, int y) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.START);
        lp.leftMargin = x;
        lp.topMargin = y;
        return lp;
    }

    private static FrameLayout frame(android.content.Context c) {
        FrameLayout f = new FrameLayout(c);
        f.setClipChildren(false);
        f.setClipToPadding(false);
        return f;
    }

    private View buildHome() {
        OrchardApp app = (OrchardApp) getApplication();
        config = app.config();
        art = app.art();
        int slow = config.getInt(Config.Keys.DEBUG_SLOW_ANIM, 1);
        Ui.animScale = slow > 1 ? slow : 1;
        labelsAlways = "always".equals(config.get(Config.Keys.LABELS, "focus"));

        root = frame(this);
        root.setBackgroundColor(Ui.BG);
        scroll = new HomeScroll(this);
        content = frame(this);
        scroll.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
        root.addView(scroll, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        topShelf = new TopShelfView(this);
        topShelf.bind(art, config);                                  // before any row exists: card/hero/debug_card read here
        content.addView(topShelf, at(ViewGroup.LayoutParams.MATCH_PARENT, Ui.px(Ui.HERO_BLEED_H), 0, 0));
        dockBand = new DockBand(this);
        content.addView(dockBand, at(DockBand.viewWidth(), DockBand.viewHeight(),
            Ui.px(Ui.DOCK_BAND_X0) - DockBand.pad(), Ui.px(Ui.DOCK_BAND_Y0) - DockBand.pad()));
        scroll.setOnScrolled(dockBand::onScrolled);
        applyMotion();

        strip = new StatusStrip(this);
        strip.setClockVisible(config.getBool(Config.Keys.CLOCK, true));
        strip.setClock24h(config.getBool(Config.Keys.CLOCK_24H, true));
        root.addView(strip, at(ViewGroup.LayoutParams.MATCH_PARENT, StatusStrip.viewHeight(), 0, 0));
        overlay = frame(this);                                       // Quick Panel, sheets, clock preview
        root.addView(overlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panel = new QuickPanel(this);
        panel.setVisibility(View.GONE);
        panel.setListener(this::onPanelAction);
        overlay.addView(panel, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        seenEpoch = app.settingsEpoch();

        wallpaper = new Wallpaper(this);
        loadWallpaper();

        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> onFocusMoved(newFocus));

        catalog = new AppCatalog(this, config);
        catalog.refresh();
        buildRows();
        catalog.setListener(this::buildRows);                        // package changes; also registers the receiver
        updateChip();
        return root;
    }

    private void loadWallpaper() {
        loadedWallpaper = config.get(Config.Keys.WALLPAPER, Ui.DEFAULT_WALLPAPER);
        wallpaper.load(loadedWallpaper, w -> {
            if (released) return;                                    // decoded after onStop: stay released
            root.setBackground(w.drawable());
            dockBand.setWallpaper(w.blurred());
            if (panel != null) panel.setWallpaper(w.blurred());
            if (sheet != null) sheet.setWallpaper(w.blurred());
            if (menu != null) menu.setWallpaper(w.blurred());
        });
    }

    /** One entry per tile slot; null = the Fire TV Home tile. */
    private List<List<AppEntry>> layoutRows() {
        List<List<AppEntry>> out = new ArrayList<List<AppEntry>>();
        out.add(new ArrayList<AppEntry>(catalog.dock()));
        List<AppEntry> g = catalog.grid();
        for (int i = 0; i < g.size(); i += Ui.COLS) {
            out.add(new ArrayList<AppEntry>(g.subList(i, Math.min(g.size(), i + Ui.COLS))));
        }
        if (config.getBool(Config.Keys.FIRETV_TILE, true)) {
            if (out.size() == 1 && out.get(0).isEmpty()) {
                out.get(0).add(null);                                // nothing at all: dock row
            } else if (out.size() == 1) {
                out.add(new ArrayList<AppEntry>());                  // dock only: Fire TV Home alone on row 1
                out.get(1).add(null);
            } else {
                List<AppEntry> last = out.get(out.size() - 1);
                if (last.size() >= Ui.COLS) {
                    last = new ArrayList<AppEntry>();
                    out.add(last);
                }
                last.add(null);
            }
        }
        return out;
    }

    private static String keyOf(TileView t) {
        return t.isFireTvHome() || t.entry() == null ? Ui.SHELF_KEY_FTH : t.entry().packageName;
    }

    /** (Re)build the dock + grid rows from the catalogue; keeps focus on the same package when possible. */
    private void buildRows() {
        String keepMoving = null;
        if (movingPkg != null) {                                     // rows are about to be replaced: tile refs go stale
            keepMoving = movingPkg;
            exitMoveMode();
            swallowSelect = true;                                    // the Select meant to drop must not launch whatever has focus
        }
        View f = content.findFocus();
        String keep = keepMoving != null ? keepMoving : f instanceof TileView ? keyOf((TileView) f) : lastFocusedKey;
        for (int i = 0; i < rows.size(); i++) content.removeView(rows.get(i));
        rows.clear();
        tilesByPkg.clear();
        appliedRow = -1;

        List<List<AppEntry>> layout = layoutRows();
        int rowW = Ui.px(Ui.COLS * Ui.TILE_W + (Ui.COLS - 1) * Ui.GUTTER);
        TileView target = null, first = null;
        for (int k = 0; k < layout.size(); k++) {
            TileRow row = new TileRow(this);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            row.setLabelsAlways(labelsAlways);
            row.setNavigator(this::navigate);
            List<AppEntry> items = layout.get(k);
            for (int i = 0; i < items.size(); i++) {
                AppEntry e = items.get(i);
                TileView t = new TileView(this);
                if (e == null) {
                    t.bindFireTvHome(getString(R.string.firetv_home));
                } else {
                    t.bind(e);
                    bindArt(t, e);
                    tilesByPkg.put(e.packageName, t);
                }
                t.setOnClickListener(this::onTileClick);
                row.addTile(t);
                if (first == null) first = t;
                if (keep != null && keep.equals(keyOf(t))) target = t;
            }
            rows.add(row);
            content.addView(row, at(rowW, Ui.px(Ui.TILE_H), Ui.px(Ui.SAFE_X),
                Ui.px(Ui.DOCK_TILE_Y + Ui.ROW_PITCH * k)));
        }
        int lastRow = Math.max(0, rows.size() - 1);
        ViewGroup.LayoutParams clp = content.getLayoutParams();
        clp.height = contentHeight(lastRow);
        content.setLayoutParams(clp);
        // ScrollView measures its child UNSPECIFIED and ignores lp.height; FrameLayout honours the minimum height.
        // Without it the content wraps to the last row's bottom and scrollYFor(k >= 2) is clamped (P4 device check).
        content.setMinimumHeight(contentHeight(lastRow));

        if (target == null && lastFocusedKey != null && !rows.isEmpty()) {
            // key gone (app hidden/removed): same row, nearest to the old column
            target = (TileView) rows.get(Math.min(lastFocusedRow, rows.size() - 1)).nearest(lastFocusedCx);
        }
        final TileView focusTarget = target != null ? target : first;
        if (focusTarget != null && !overlayOpen()) root.post(() -> focusTarget.requestFocus());
        wake();                                                      // fresh tiles are undimmed: resync idle state + timer
    }

    // ---- motion + idle dim (task 4.2) -------------------------------------------------------------------

    /**
     * Reduced motion: debug_motion=reduced, or (no debug_motion and motion=reduced), or the system animator
     * duration scale is 0. debug_motion=full forces full motion. Read-only; never writes settings.
     */
    private boolean computeReducedMotion() {
        String dbg = config.get(Config.Keys.DEBUG_MOTION, "");
        if ("full".equals(dbg)) return false;
        if ("reduced".equals(dbg)) return true;
        if ("reduced".equals(config.get(Config.Keys.MOTION, "full"))) return true;
        try {
            return Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void applyMotion() {
        boolean r = computeReducedMotion();
        reducedMotion = r;
        TileView.setReducedMotion(r);
        scroll.setReducedMotion(r);
        topShelf.setReducedMotion(r);
    }

    private void setAllIdle(boolean on) {
        for (int i = 0; i < rows.size(); i++) rows.get(i).setIdle(on);
    }

    /** Any key / resume / rebuild: undim if dimmed, then re-arm the idle timer (not in reduced motion or with an overlay open). */
    private void wake() {
        idleHandler.removeCallbacks(idleTick);
        if (idle) {
            setAllIdle(false);
            idle = false;
        }
        if (!reducedMotion && !overlayOpen() && movingPkg == null) idleHandler.postDelayed(idleTick, Ui.ms(Ui.IDLE_MS));
    }

    private void goIdle() {
        if (overlayOpen() || reducedMotion || movingPkg != null) return;
        setAllIdle(true);
        idle = true;
    }

    /**
     * Scroll rule (PLAN 0.3, P4): row 0 -> 0; row k >= 1 -> its top lands at Ui.GRID_ANCHOR_Y (470), i.e.
     * DOCK_TILE_Y + ROW_PITCH * k - GRID_ANCHOR_Y = 130 + 256k design px. The row above then spans 214..370
     * (fully visible), the row two above ends at 114 under the strip's opaque scrim, the next row peeks at 982.
     */
    static int scrollYFor(int k) {
        if (k <= 0) return 0;
        return Ui.px(Ui.DOCK_TILE_Y + Ui.ROW_PITCH * k - Ui.GRID_ANCHOR_Y);
    }

    /**
     * Content height: dock tile top + pitch * lastRow + one screen. The deepest scroll is scrollYFor(lastRow)
     * = 130 + 256 * lastRow, the content allows 600 + 256 * lastRow, so the last row can always reach the anchor.
     */
    static int contentHeight(int lastRow) {
        return Ui.px(Ui.DOCK_TILE_Y + Ui.ROW_PITCH * lastRow) + Ui.px(Ui.CANVAS_H);
    }

    private void bindArt(TileView t, AppEntry e) {
        if (released) return;                                        // onStart rebinds
        Bitmap b = art.tile(e);
        if (b != null) {
            t.setArt(b, art.glowColor(e), art.glowAlpha(e));
        } else {
            art.request(e, this::onArtReady);
        }
    }

    private void onArtReady(AppEntry e) {
        TileView t = tilesByPkg.get(e.packageName);
        if (t == null || t.entry() == null || !e.packageName.equals(t.entry().packageName)) return;
        Bitmap b = art.tile(t.entry());
        if (b != null) t.setArt(b, art.glowColor(t.entry()), art.glowAlpha(t.entry()));
    }

    /** Row-to-row focus (PLAN 0.3): nearest tile by the focused tile's centre x; null outside the grid. */
    private View navigate(TileRow from, View focused, int direction) {
        int r = rows.indexOf(from);
        if (r < 0) return null;
        int t = direction == View.FOCUS_DOWN ? r + 1 : direction == View.FOCUS_UP ? r - 1 : -1;
        if (t < 0 || t >= rows.size()) return null;
        return rows.get(t).nearest(focused.getLeft() + focused.getWidth() / 2);
    }

    /**
     * Scroll rule (PLAN 0.3, P4): row 0 -> scrollY 0, shelf + dock band visible; row 1 -> scrollY 386 (row top at
     * 470, dock row at 214, band visible), shelf hidden; row k >= 2 -> band hidden too. The entry may be null (Fire
     * TV Home tile): TopShelfView then keeps the current app.
     */
    private void onFocusMoved(View newFocus) {
        if (relayouting) return;
        if (!(newFocus instanceof TileView) || !(newFocus.getParent() instanceof TileRow)) return;
        TileView t = (TileView) newFocus;
        int k = rows.indexOf((TileRow) t.getParent());
        if (k < 0) return;
        lastFocusedKey = keyOf(t);
        lastFocusedRow = k;
        lastFocusedCx = t.getLeft() + t.getWidth() / 2;
        boolean firstPlacement = appliedRow < 0;
        if (firstPlacement) topShelf.showNow(t.entry()); else if (movingPkg == null) topShelf.show(t.entry());
        if (k == appliedRow) return;
        appliedRow = k;
        int y = scrollYFor(k);
        float shelfAlpha = k == 0 ? 1f : 0f;
        float bandAlpha = k <= 1 ? 1f : 0f;
        topShelf.animate().cancel();
        dockBand.animate().cancel();
        if (firstPlacement) {
            scroll.jumpTo(y);
            topShelf.setAlpha(shelfAlpha);
            dockBand.setAlpha(bandAlpha);
        } else {
            scroll.animateTo(y);
            topShelf.animate().alpha(shelfAlpha).setDuration(Ui.ms(Ui.SHELF_HIDE_MS));
            dockBand.animate().alpha(bandAlpha).setDuration(Ui.ms(Ui.SHELF_HIDE_MS));
        }
        strip.setScrim(k > 0);
    }

    private void onTileClick(View v) {
        if (!overlayOpen() && v instanceof TileView) onTileSelected((TileView) v);
    }

    /** Select: press dip, then launch; the Fire TV Home tile opens the reset confirm sheet instead. */
    void onTileSelected(TileView t) {
        if (t.isFireTvHome() || t.entry() == null) {
            t.press(() -> {
                if (overlayOpen()) return;
                sheet = ConfirmSheet.showReset(overlay, bridge, wallpaper.blurred(), this::onSheetClosed);
                overlayChanged();
            });
        } else {
            final AppEntry e = t.entry();
            t.press(() -> launch(t, e));
        }
    }

    /** Scale-up from the tile (unless launch_anim=off); any failure of the animated path retries without it. */
    private void launch(TileView t, AppEntry e) {
        Intent i = catalog.launchIntent(e);
        if (i == null) return;
        boolean started = false;
        if (config.getBool(Config.Keys.LAUNCH_ANIM, true)) {
            try {
                Bundle opts = ActivityOptions.makeScaleUpAnimation(t, 0, 0, t.getWidth(), t.getHeight()).toBundle();
                startActivity(i, opts);
                started = true;
            } catch (RuntimeException ex) {
                started = false;                                     // retry plain below
            }
        }
        if (!started) {
            try {
                startActivity(i);
            } catch (RuntimeException ex) {                          // ActivityNotFound / Security
                return;
            }
        }
        catalog.recordLaunch(e);
    }

    // ---- overlays: Quick Panel, sheets, clock preview (Phase 5) ------------------------------------------

    private boolean overlayOpen() {
        return panel != null && (panel.isOpen() || sheet != null || clock != null || menu != null);
    }

    /** Re-sync home state with the overlay stack: block + suppress while anything is up, restore when the last closes. */
    private void overlayChanged() {
        if (overlayOpen()) {
            scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            topShelf.setSuppressed(true);
            wake();                                                  // overlay open: undims and cancels the timer
        } else {
            scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            topShelf.setSuppressed(false);
            restoreFocus();
            updateChip();
            wake();                                                  // re-arms the timer
        }
    }

    private void refreshPanelState() {
        panel.setResetState(bridge.redirectOn(), bridge.hasGrant(), bridge.hofTargetName());
    }

    private void openPanel() {
        if (panel == null || overlay == null || panel.isOpen() || sheet != null || clock != null || menu != null) return;
        panel.setWallpaper(wallpaper.blurred());
        refreshPanelState();
        panel.setVisibility(View.VISIBLE);
        panel.open();
        overlayChanged();
    }

    /** Closes the panel, then runs after (may be null) and re-syncs the overlay state. */
    private void closePanel(Runnable after) {
        panel.close(() -> {
            if (!panel.isOpen()) panel.setVisibility(View.GONE);
            if (after != null) after.run();
            overlayChanged();
        });
    }

    private void onPanelAction(int tile) {
        switch (tile) {
            case QuickPanel.TILE_SETTINGS:
                closePanel(this::openSettings);
                break;
            case QuickPanel.TILE_RESET:
                closePanel(this::openResetSheet);
                break;
            case QuickPanel.TILE_SCREENSAVER:
                closePanel(this::openClock);                         // G5 (system screensaver) is deferred; this previews it
                break;
            case QuickPanel.TILE_FIRETV_SETTINGS:
                closePanel(this::openFireTvSettings);
                break;
            default:
                closePanel(null);
                break;
        }
    }

    private void openSettings() {
        try {
            startActivity(new Intent(this, SettingsActivity.class));
        } catch (RuntimeException e) {
            // stay on the home screen
        }
    }

    private void openResetSheet() {
        Runnable closed = this::onSheetClosed;
        sheet = bridge.redirectOn()
            ? ConfirmSheet.showReset(overlay, bridge, wallpaper.blurred(), closed)
            : ConfirmSheet.showRedirectOn(overlay, bridge, wallpaper.blurred(), closed);
    }

    private void openFireTvSettings() {
        try {
            startActivity(FireTvBridge.fireTvSettingsIntent());
        } catch (RuntimeException e) {
            sheet = ConfirmSheet.message(overlay, wallpaper.blurred(), "Fire TV Settings",
                "Could not open Fire TV Settings on this Stick.", this::onSheetClosed);
        }
    }

    private void onSheetClosed() {
        View s = sheet;
        sheet = null;
        if (s != null && s.getParent() == overlay) overlay.removeView(s);
        refreshPanelState();
        overlayChanged();                                            // also refreshes the chip (redirect may have changed)
    }

    private void openClock() {
        if (clock != null) return;
        clock = new ClockFace(this);
        clock.setClock24h(config.getBool(Config.Keys.CLOCK_24H, true));
        clock.setReducedMotion(reducedMotion);
        overlay.addView(clock, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        clockArmed = false;
        clock.start();
    }

    /** Stops and removes the clock preview; the caller re-syncs the overlay state. */
    private void dropClock() {
        if (clock == null) return;
        clock.stop();
        overlay.removeView(clock);
        clock = null;
        clockArmed = false;
    }

    /**
     * Removes a sheet at once when the screen is left (e.g. the "Home redirect is back on" message after the user
     * pressed Home). Without this it was still up on return and the first Select went to its OK button.
     */
    private void dropSheet() {
        ConfirmSheet s = sheet;
        if (s == null) return;
        s.dismiss(false);                                            // runs onSheetClosed, which clears the field
        if (sheet == s) {
            sheet = null;
            overlayChanged();
        }
    }

    private void closeClock() {
        dropClock();
        overlayChanged();
    }

    // ---- tile context menu (task p5-3) ---------------------------------------------------------------------

    private static boolean isSelectKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
            || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private TileView focusedTile() {
        View f = root.findFocus();
        return f instanceof TileView && f.getParent() instanceof TileRow ? (TileView) f : null;
    }

    /**
     * Select on a focused tile. DOWN is always consumed (the View never clicks by itself); a repeat DOWN that is a long
     * press opens the context menu; UP launches unless the press already opened the menu. True = consumed.
     */
    private boolean handleTileSelect(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (down && event.getRepeatCount() == 0) {                   // a fresh press resets any stale state
            selectLong = false;
            selectDown = false;
        }
        if (selectLong) {                                            // rest of the press that opened the menu
            if (!down) {
                selectLong = false;
                selectDown = false;
            }
            return true;
        }
        if (overlayOpen()) return false;
        TileView t = focusedTile();
        if (t == null) return false;
        if (down) {
            selectDown = true;
            if (KeyRules.isLongSelect(event.getRepeatCount(), (event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0,
                event.getDownTime(), event.getEventTime(), Ui.LONG_SELECT_MS)) {
                selectLong = true;
                if (!t.isFireTvHome() && t.entry() != null) openTileMenu(t);   // Fire TV Home tile: nothing
            }
            return true;
        }
        boolean was = selectDown;
        selectDown = false;
        if (was && !event.isCanceled()) onTileSelected(t);
        return true;
    }

    private boolean appInfoAvailable() {
        if (appInfoAvail < 0) {
            appInfoAvail = bridge.resolves(FireTvBridge.appInfoIntent(getPackageName())) ? 1 : 0;
        }
        return appInfoAvail == 1;
    }

    private void openTileMenu(TileView t) {
        if (menu != null || overlayOpen() || t.entry() == null || t.isFireTvHome()) return;
        String pkg = t.entry().packageName;
        boolean inDock = rows.indexOf((TileRow) t.getParent()) == 0;
        int[] acts = new int[7];
        String[] labels = new String[7];
        boolean[] on = new boolean[7];
        int n = 0;
        if (inDock) {
            List<AppEntry> dock = catalog.dock();
            int idx = -1;
            for (int i = 0; i < dock.size(); i++) if (pkg.equals(dock.get(i).packageName)) idx = i;
            acts[n] = TileMenu.REMOVE_DOCK; labels[n] = "Remove from dock"; on[n++] = dock.size() > 1;
            if (idx > 0) { acts[n] = TileMenu.MOVE_LEFT; labels[n] = "Move left"; on[n++] = true; }
            if (idx >= 0 && idx < dock.size() - 1) { acts[n] = TileMenu.MOVE_RIGHT; labels[n] = "Move right"; on[n++] = true; }
        } else {
            boolean full = catalog.isDockFull();
            acts[n] = TileMenu.ADD_DOCK; labels[n] = full ? "Add to dock (dock is full)" : "Add to dock"; on[n++] = !full;
            acts[n] = TileMenu.MOVE; labels[n] = "Move"; on[n++] = true;
        }
        acts[n] = TileMenu.HIDE; labels[n] = "Hide"; on[n++] = catalog.canHide(pkg);
        if (appInfoAvailable()) { acts[n] = TileMenu.APP_INFO; labels[n] = "App info"; on[n++] = true; }

        int[] loc = new int[2];
        overlay.getLocationOnScreen(loc);
        int[] tl = new int[2];
        t.getLocationOnScreen(tl);                                   // top-left already includes the focus scale
        int w = Math.round(t.getWidth() * t.getScaleX()), h = Math.round(t.getHeight() * t.getScaleY());
        Rect r = new Rect(tl[0] - loc[0], tl[1] - loc[1], tl[0] - loc[0] + w, tl[1] - loc[1] + h);

        menuTile = t;
        menu = new TileMenu(this);
        menu.setListener(this::onMenuAction);
        menu.setOnClosed(this::onMenuClosed);
        overlay.addView(menu, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        menu.show(t.label(), java.util.Arrays.copyOf(acts, n), java.util.Arrays.copyOf(labels, n),
            java.util.Arrays.copyOf(on, n), r, wallpaper.blurred());
        overlayChanged();
    }

    private Runnable afterMenu;

    private void onMenuAction(int action) {
        TileView t = menuTile;
        if (menu == null || t == null || t.entry() == null) return;
        String pkg = t.entry().packageName;
        switch (action) {
            case TileMenu.ADD_DOCK: catalog.addToDock(pkg); break;
            case TileMenu.REMOVE_DOCK: catalog.removeFromDock(pkg); break;
            case TileMenu.MOVE_LEFT: catalog.moveInDock(pkg, -1); break;
            case TileMenu.MOVE_RIGHT: catalog.moveInDock(pkg, 1); break;
            case TileMenu.HIDE: catalog.hide(pkg); break;
            case TileMenu.MOVE: afterMenu = () -> startMoveMode(t); break;   // after the menu is gone
            case TileMenu.APP_INFO:
                try {
                    startActivity(FireTvBridge.appInfoIntent(pkg));
                } catch (RuntimeException e) {
                    // stay on the home screen
                }
                break;
            default: break;
        }
        menu.dismiss();
    }

    private void onMenuClosed() {
        menu = null;
        menuTile = null;
        Runnable after = afterMenu;
        afterMenu = null;
        overlayChanged();
        if (after != null) after.run();
    }

    /** Immediate removal (leaving the screen): no fade, no after-action. */
    private void dropMenu() {
        if (menu == null) return;
        afterMenu = null;
        menu.setOnClosed(null);
        menu.dismiss(false);
        menu = null;
        menuTile = null;
        selectLong = false;
    }

    // ---- grid Move mode (task p5-2c) ---------------------------------------------------------------------------

    /** Grid "Move" (PLAN 5.2c): D-pad moves the tile, Select drops, Back or Menu cancels. Grid tiles only. */
    void startMoveMode(TileView t) {
        if (movingPkg != null || overlayOpen() || t == null || t.entry() == null || t.isFireTvHome()) return;
        if (!(t.getParent() instanceof TileRow) || rows.indexOf((TileRow) t.getParent()) < 1) return;   // dock: menu Move left/right
        String pkg = t.entry().packageName;
        if (tilesByPkg.get(pkg) != t) return;                        // stale tile (rows were rebuilt)
        List<String> grid = catalog.gridPackages();
        int idx = grid.indexOf(pkg);
        if (idx < 0) return;
        movingPkg = pkg;
        movingTile = t;
        moveOriginal = grid;
        movePreview = new ArrayList<String>(grid);
        moveIndex = idx;
        moveSelDown = false;
        selectDown = false;
        selectLong = false;
        menuTap.reset();
        idleHandler.removeCallbacks(idleTick);
        if (idle) {
            setAllIdle(false);
            idle = false;
        }
        String l = t.label();
        if (l.length() > 18) l = l.substring(0, 17) + "\u2026";
        strip.setChip("Moving " + l + ": D-pad moves \u00b7 Select drops \u00b7 Back cancels");
        t.setMoving(true);
        for (int i = 0; i < rows.size(); i++) rows.get(i).setMoveDim(t, Ui.MOVE_OTHERS_ALPHA, true);
        t.requestFocus();
    }

    /** Leaves Move mode without touching the order: tile back to normal, others undimmed, chip + idle timer restored. */
    private void exitMoveMode() {
        TileView t = movingTile;
        movingPkg = null;
        movingTile = null;
        moveOriginal = null;
        movePreview = null;
        moveSelDown = false;
        for (int i = 0; i < rows.size(); i++) rows.get(i).setMoveDim(null, 1f, false);
        if (t != null) t.setMoving(false);
        updateChip();
        wake();
    }

    /** Back / Menu tap / leaving the screen: show the original order again, write nothing. */
    private void cancelMove() {
        if (movingPkg == null) return;
        TileView t = movingTile;
        if (movePreview != null && moveOriginal != null && !movePreview.equals(moveOriginal)) {
            relayoutGrid(moveOriginal);
            if (t != null) t.requestFocus();
        }
        exitMoveMode();
    }

    /** Select: leave Move mode, then write the order once (the catalog listener rebuilds the rows; focus stays on pkg). */
    private void dropMove() {
        if (movingPkg == null) return;
        String pkg = movingPkg;
        int idx = moveIndex;
        boolean changed = !movePreview.equals(moveOriginal);
        lastFocusedKey = pkg;
        exitMoveMode();
        if (changed) catalog.moveInGrid(pkg, idx);
    }

    /**
     * Re-seats the SAME TileView instances (no new TileView, no rebind of art): every grid tile is detached and
     * re-added COLS per row in {@code order}, the Fire TV Home tile last. Row count never changes because the
     * tile count does not.
     */
    private void relayoutGrid(List<String> order) {
        TileView fth = null;
        for (int k = 1; k < rows.size(); k++) {
            TileRow row = rows.get(k);
            for (int i = 0; i < row.tileCount(); i++) {
                TileView t = row.tileAt(i);
                if (t != null && t.isFireTvHome()) fth = t;
            }
        }
        relayouting = true;
        for (int k = 1; k < rows.size(); k++) rows.get(k).clearTiles();
        int total = order.size() + (fth != null ? 1 : 0), pos = 0;
        for (int i = 0; i < total; i++) {
            TileView t = i < order.size() ? tilesByPkg.get(order.get(i)) : fth;
            if (t == null) continue;
            int k = 1 + pos / Ui.COLS;
            if (k >= rows.size()) break;
            rows.get(k).addTile(t);
            pos++;
        }
        relayouting = false;
        for (int i = 0; i < rows.size(); i++) rows.get(i).setMoveDim(movingTile, Ui.MOVE_OTHERS_ALPHA, false);   // detach reset their alpha
    }

    /** Key handling while Move mode is on (called first). True = consumed. */
    private boolean handleMoveKey(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (movingTile != null && !movingTile.hasFocus()) movingTile.requestFocus();   // real focus stays on the moving tile
        int delta;
        int dir;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: delta = -1; dir = View.FOCUS_LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: delta = 1; dir = View.FOCUS_RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_UP: delta = -Ui.COLS; dir = View.FOCUS_UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: delta = Ui.COLS; dir = View.FOCUS_DOWN; break;
            default: delta = 0; dir = 0; break;
        }
        if (dir != 0) {
            if (down) moveStep(delta, dir);                          // repeats allowed: a held key keeps moving
            return true;
        }
        if (isSelectKey(code)) {
            if (down) {
                moveSelDown = true;
            } else if (moveSelDown && !event.isCanceled()) {
                dropMove();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!down && !event.isCanceled()) cancelMove();
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            if (down) {
                menuTap.onDown(event.getRepeatCount());
            } else if (menuTap.onUp(event.isCanceled(), event.getDownTime(), event.getEventTime())) {
                cancelMove();
            }
            return true;
        }
        return false;
    }

    private void moveStep(int delta, int dir) {
        TileView t = movingTile;
        if (t == null || movePreview == null) return;
        int to = Math.max(0, Math.min(movePreview.size() - 1, moveIndex + delta));
        if (to == moveIndex) {                                       // clamped: no move, bump the edge
            if (t.getParent() instanceof TileRow) ((TileRow) t.getParent()).bumpTile(t, dir);
            return;
        }
        String pkg = movePreview.remove(moveIndex);
        movePreview.add(to, pkg);
        moveIndex = to;
        relayoutGrid(movePreview);
        t.requestFocus();                                            // onFocusMoved's scroll rule follows its row
    }

    private TileView findTile(String key) {
        if (key == null) return null;
        for (int r = 0; r < rows.size(); r++) {
            TileRow row = rows.get(r);
            for (int i = 0; i < row.tileCount(); i++) {
                if (key.equals(keyOf(row.tileAt(i)))) return row.tileAt(i);
            }
        }
        return null;
    }

    /** Focus the tile with lastFocusedKey (else same row nearest the old column, else first dock tile). */
    private void restoreFocus() {
        if (rows.isEmpty()) return;
        View t = findTile(lastFocusedKey);
        if (t == null && lastFocusedKey != null) {
            t = rows.get(Math.min(lastFocusedRow, rows.size() - 1)).nearest(lastFocusedCx);
        }
        if (t == null && rows.get(0).tileCount() > 0) t = rows.get(0).tileAt(0);
        if (t != null) t.requestFocus();
    }

    private void focusDockFirst() {
        if (!rows.isEmpty() && rows.get(0).tileCount() > 0) rows.get(0).tileAt(0).requestFocus();
    }

    // ---- keys ----------------------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (root == null) return super.dispatchKeyEvent(event);      // safe mode: SafeModeView handles keys
        if (event.getAction() == KeyEvent.ACTION_DOWN) wake();       // every key, Menu and Back included
        int code = event.getKeyCode();
        int action = event.getAction();
        if (movingPkg != null && handleMoveKey(event)) return true;  // Move mode sees keys before anything else
        if (swallowSelect) {
            if (isSelectKey(code)) {
                if (action == KeyEvent.ACTION_UP) swallowSelect = false;
                return true;
            }
            if (action == KeyEvent.ACTION_DOWN) swallowSelect = false;
        }
        if (clock != null) {
            // Clock preview: every key is eaten; the UP of a key whose DOWN it saw closes it.
            if (action == KeyEvent.ACTION_DOWN) {
                clockArmed = true;
            } else if (action == KeyEvent.ACTION_UP && clockArmed) {
                closeClock();
            }
            if (code == KeyEvent.KEYCODE_MENU) menuTap.reset();
            return true;
        }
        if (isSelectKey(code) && handleTileSelect(event)) return true;
        if (menu != null) {
            if (code == KeyEvent.KEYCODE_MENU) menuTap.reset();
            if (menu.handleKey(event)) return true;                  // the menu never takes view focus; the tile stays lit
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            // PLAN 0.9: HoF does not consume Menu DOWN and opens its own screen after 500 ms, swallowing repeats
            // and the UP. Only a short, uncancelled, repeat-free press seen through UP is ours.
            if (action == KeyEvent.ACTION_DOWN) {
                menuTap.onDown(event.getRepeatCount());
            } else if (action == KeyEvent.ACTION_UP
                && menuTap.onUp(event.isCanceled(), event.getDownTime(), event.getEventTime())) {
                dismissOrOpenPanel();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_UP && !event.isCanceled()) {
                if (sheet != null || panel.isOpen()) {
                    dismissOrOpenPanel();
                } else {
                    scroll.animateTo(0);
                    focusDockFirst();
                }
            }
            return true;                                             // never finish(): Orchard stays in front
        }
        return super.dispatchKeyEvent(event);
    }

    /** Menu tap: sheet -> cancel it; panel -> close it; otherwise open the panel. Back reuses it while an overlay is up. */
    private void dismissOrOpenPanel() {
        if (sheet != null) {
            sheet.cancel();
        } else if (panel.isOpen()) {
            closePanel(null);
        } else {
            openPanel();
        }
    }

    @Override
    public void onBackPressed() { }                                  // belt and braces: no finish()

    /** Status chip (0.3): the setup state in one phrase. Reads only; never writes secure settings. */
    private void updateChip() {
        if (movingPkg != null) return;                               // the Move hint owns the chip
        String chip = null;
        if (!bridge.hasGrant()) chip = "adb grant missing";
        else if (!bridge.redirectOn()) chip = "Fire TV home active";
        else if (bridge.redirectDoubtful()) chip = "Redirect may be off in Home on Fire";
        strip.setChip(chip);
    }

    /**
     * PLAN 2.2 test hooks: --ez orchard.crash true throws after onCreate, --ez orchard.hang true blocks the
     * main thread before the first frame. Honoured only when orchard.conf has debug_crash=1 (the activity is
     * exported) and never in safe mode, so the loop can be observed ending.
     */
    private void debugHooks(Intent intent) {
        if (intent == null) return;
        boolean crash = intent.getBooleanExtra("orchard.crash", false);
        boolean hang = intent.getBooleanExtra("orchard.hang", false);
        boolean viaHome = intent.hasCategory(Intent.CATEGORY_HOME);
        if (!crash && !hang && !viaHome) return;
        Config c = new Config(FireTvBridge.settingsFile(this));
        if (!c.getBool(Config.Keys.DEBUG_CRASH, false)) return;
        // R6c: crash_on_home=1 (set through DebugReceiver) crashes every launch that arrives through Home.
        if (viaHome && c.getBool(Config.Keys.CRASH_ON_HOME, false)) crash = true;
        if (!crash && !hang) return;
        if (crash) new Handler(Looper.getMainLooper()).post(new TestCrash());
        if (hang) {
            long end = System.currentTimeMillis() + 120000L;
            while (System.currentTimeMillis() < end) {
                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
            }
        }
    }

    /**
     * SDK 30 blocks adb from the app's /sdcard/Android/data dir, so debug flags arrive as intent extras (DebugFlags):
     * --es orchard.debug "debug_card=banner;debug_motion=reduced", --ez orchard.debug_clear true. Only debug_* keys.
     */
    private void applyDebugChannel(Intent intent) {
        if (intent == null) return;
        String spec = intent.getStringExtra(DebugFlags.EXTRA_SET);
        boolean clear = intent.getBooleanExtra(DebugFlags.EXTRA_CLEAR, false);
        if (spec == null && !clear) return;
        try {
            OrchardApp app = (OrchardApp) getApplication();
            // apply() saves, which moves Config's signature, so restoreState()'s refresh() would report "unchanged"
            // on a live instance (onNewIntent) and never rebind. Bump the epoch so the change is rebuilt.
            if (DebugFlags.apply(app.config(), spec, clear) > 0) app.bumpSettingsEpoch();
        } catch (java.io.IOException | RuntimeException e) {
            // flags stay as they were
        }
    }

    private static final class TestCrash implements Runnable {
        @Override
        public void run() { throw new RuntimeException("test crash"); }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyDebugChannel(intent);
        if (bridge.evaluateLaunch(intent) && !safe) {
            recreate();                                           // loop detected while running: show safe mode
            return;
        }
        if (!safe) debugHooks(intent);                            // crash_on_home also covers a Home press on a live process
        if (!safe && root != null) {
            restoreState();
            wake();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!safe && root != null) {
            restoreState();
            wake();                                                  // undims anything left dim by onPause, re-arms timer
        }
    }

    /** Cheap re-sync on resume / new intent: re-read config, rebuild only on change, restore focus + scroll. */
    private void restoreState() {
        boolean changed = config.refresh();
        int ep = ((OrchardApp) getApplication()).settingsEpoch();
        if (ep != seenEpoch) {                                       // SettingsActivity saved through the same Config
            seenEpoch = ep;
            changed = true;
        }
        applyMotion();                                               // config flag or system animator scale may have changed
        if (changed) {
            int slow = config.getInt(Config.Keys.DEBUG_SLOW_ANIM, 1);
            Ui.animScale = slow > 1 ? slow : 1;
            labelsAlways = "always".equals(config.get(Config.Keys.LABELS, "focus"));
            strip.setClockVisible(config.getBool(Config.Keys.CLOCK, true));
            strip.setClock24h(config.getBool(Config.Keys.CLOCK_24H, true));
            if (!released && !config.get(Config.Keys.WALLPAPER, Ui.DEFAULT_WALLPAPER).equals(loadedWallpaper)) {
                loadWallpaper();
            }
            topShelf.bind(art, config);                              // card / hero / debug_card may have changed
            catalog.refresh();                                       // order / hidden / dock live in the config
            buildRows();
        } else if (!overlayOpen() && !(root.findFocus() instanceof TileView)) {
            restoreFocus();
        }
        if (!overlayOpen() && lastFocusedKey != null) {
            scroll.jumpTo(scrollYFor(Math.min(lastFocusedRow, Math.max(0, rows.size() - 1))));
        }
        // Cold start: onResume runs before the window is attached, so restoreFocus() above focuses a tile without a
        // global focus callback (no ViewRootImpl yet) and the first traversal sees hasFocus() and dispatches nothing.
        // Sync the shelf/scroll/band from the focused tile explicitly; idempotent when already applied (P4 device check).
        View focusedNow = root.findFocus();
        if (!overlayOpen() && focusedNow instanceof TileView) onFocusMoved(focusedNow);
        topShelf.refreshText();                                      // 'Opened just now' after returning from an app
        updateChip();
        if (panel.isOpen()) refreshPanelState();
    }

    @Override
    protected void onPause() {
        cancelMove();
        dropSheet();                                                 // a sheet never survives leaving the screen (stale sheet ate the first Select)                                                // before the idle timer is cancelled below
        selectDown = false;
        selectLong = false;
        dropMenu();                                                  // a menu never survives leaving the screen
        dropClock();                                                 // a preview never survives leaving the screen
        if (panel != null && !overlayOpen()) {
            scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            topShelf.setSuppressed(false);
        }
        if (scroll != null) scroll.stopAnimation();
        // PLAN 0.5 / matrix row 12: nothing animates while paused. Sway stopped, idle timer cancelled. Tiles are
        // left dimmed on purpose (no extra un-dim animators while an app is in front); onResume's wake() undims.
        idleHandler.removeCallbacks(idleTick);
        for (int i = 0; i < rows.size(); i++) rows.get(i).stopSway();
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        cancelMove();
        if (root == null || released) return;
        released = true;
        art.trim();
        for (int r = 0; r < rows.size(); r++) {
            TileRow row = rows.get(r);
            for (int i = 0; i < row.tileCount(); i++) {
                TileView t = row.tileAt(i);
                t.setArt(null, t.glowColor(), t.glowAlpha());        // drop bitmap refs so the LRU really frees them
            }
        }
        topShelf.release();                                          // drops card/glow bitmaps; wash + text stay
        wallpaper.release();                                         // sharp + blurred (the blur is cached on disk)
        dockBand.setWallpaper(null);
        if (panel != null) panel.setWallpaper(null);
        if (sheet != null) sheet.setWallpaper(null);
        if (menu != null) menu.setWallpaper(null);
        root.setBackground(new ColorDrawable(Ui.BG));
        strip.pause();
        // Public-API-only memory release: the Stick never sends UI_HIDDEN and the hidden GL trim is blocked on API 30.
        // Freed bitmaps are native memory that only a GC returns, so collect once the views have let go of them.
        idleHandler.removeCallbacks(gcAfterStop);
        idleHandler.postDelayed(gcAfterStop, 1500L);
    }

    private final Runnable gcAfterStop = new GcAfterStop();

    private static final class GcAfterStop implements Runnable {
        @Override
        public void run() { Runtime.getRuntime().gc(); }
    }

    @Override
    protected void onStart() {
        super.onStart();
        idleHandler.removeCallbacks(gcAfterStop);
        if (root == null || !released) return;
        released = false;
        loadWallpaper();
        topShelf.restore();
        for (int r = 0; r < rows.size(); r++) {
            TileRow row = rows.get(r);
            for (int i = 0; i < row.tileCount(); i++) {
                TileView t = row.tileAt(i);
                if (t.entry() != null && !t.isFireTvHome()) bindArt(t, t.entry());
            }
        }
        strip.resume();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (art == null) return;
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            art.trim();
        }
    }

    @Override
    protected void onDestroy() {
        if (catalog != null) catalog.close();
        super.onDestroy();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        bridge.noteHomePressSeen();             // feeds FireTvBridge.redirectDoubtful()
    }

    /**
     * PLAN 2.2: clear boot_attempts after the first real frame. Removing the listener inside onDraw throws,
     * so both the removal and the write are posted. Static nested class: d8 8.2.2 crashes on javac 21
     * anonymous/inner classes in an instance context.
     */
    private static final class FirstFrame implements ViewTreeObserver.OnDrawListener, Runnable {
        private final View view;
        private final State state;
        private boolean done;

        private FirstFrame(View view, State state) {
            this.view = view;
            this.state = state;
        }

        static void watch(View view, State state) {
            view.getViewTreeObserver().addOnDrawListener(new FirstFrame(view, state));
        }

        @Override
        public void onDraw() {
            if (done) return;
            done = true;
            view.post(this);
        }

        @Override
        public void run() {
            ViewTreeObserver vto = view.getViewTreeObserver();
            if (vto.isAlive()) vto.removeOnDrawListener(this);
            state.clearBootAttempts();
        }
    }
}
