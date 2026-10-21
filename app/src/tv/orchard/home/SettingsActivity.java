package tv.orchard.home;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Settings (PLAN 0.12 / mockup #settings): blurred wallpaper backdrop + dimmer, title and sub text on the left,
 * a SettingsList on the right. A small screen stack (root list, About, and whatever p5-2b pushes). Back pops;
 * at the root it finishes. Every change is written to orchard.conf and bumps the settings epoch so HomeActivity
 * rebuilds on resume. Menu is not handled here (long Menu belongs to Home on Fire).
 */
public class SettingsActivity extends Activity {
    private static final String ROOT_SUB = "Changes apply when you return to the home screen.";

    private static final class Screen {
        final String title, sub;
        final List<SettingsList.Item> items;     // null for the About screen
        int focus;
        int kind;                                // KIND_*: how to rebuild the rows

        Screen(String title, String sub, List<SettingsList.Item> items, int focus) {
            this.title = title;
            this.sub = sub;
            this.items = items;
            this.focus = focus;
        }
    }

    private static final int KIND_ROOT = 0, KIND_DOCK = 1, KIND_HIDDEN = 2, KIND_CITY = 3;
    private static final String CITY_SUB = "Not listed? On the Mac run scripts/launcher.sh city <lat> <lon> [label]";
    private static final String DOCK_SUB = "Up to 6 apps. Select adds or removes. Left and Right change the order.";
    private static final String HIDDEN_SUB =
        "Hidden apps leave the dock and the grid. System apps and Home on Fire are hidden by default; tick one to hide it, untick to show it.";
    private static final int DOCK_DETAIL_MAX = 30;

    private Config config;
    private AppCatalog catalog;
    private FrameLayout root;
    private Backdrop backdrop;
    private TextView titleView, subView;
    private SettingsList list;
    private AboutView about;
    private AmbientState state = new AmbientState();
    private int backdropGen;
    private boolean started;
    private ClockFace saver;
    private StatusStrip strip;
    private Bitmap blurKept;                  // the 240x135 blur, kept after Wallpaper.release() for backdrop + sheet
    private ConfirmSheet sheet;
    private boolean reduced;
    private final ArrayList<Screen> stack = new ArrayList<Screen>();

    // ---- lifecycle ------------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        config = ((OrchardApp) getApplication()).config();
        config.refresh();
        catalog = new AppCatalog(this, config);
        catalog.refresh();
        reduced = computeReducedMotion();

        root = new FrameLayout(this);
        root.setBackgroundColor(Theme.cur().bg);
        backdrop = new Backdrop(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        strip = new StatusStrip(this);
        strip.setHintVisible(false);
        strip.setClockVisible(config.getBool(Config.Keys.CLOCK, true));
        strip.setClock24h(config.getBool(Config.Keys.CLOCK_24H, true));
        root.addView(strip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, StatusStrip.viewHeight()));

        titleView = text(Ui.T_TITLE2, Ui.medium(), Theme.cur().text);
        titleView.setLetterSpacing(Ui.tracking(Ui.T_TITLE2));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = Ui.px(Ui.SAFE_X);
        tl.topMargin = Ui.px(Ui.SETTINGS_TITLE_TOP);
        root.addView(titleView, tl);

        subView = text(Ui.T_CAPTION1, Ui.regular(), Theme.cur().text3);
        subView.setLetterSpacing(Ui.tracking(Ui.T_CAPTION1));
        subView.setLineSpacing(0f, 1.35f);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(Ui.px(Ui.SETTINGS_SUB_W),
            ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.leftMargin = Ui.px(Ui.SAFE_X);
        sl.topMargin = Ui.px(Ui.SETTINGS_SUB_TOP);
        root.addView(subView, sl);

        int listX = Ui.CANVAS_W - Ui.LIST_W - Ui.SAFE_X;                       // 960
        int listH = Ui.CANVAS_H - Ui.LIST_Y - 60;                              // bottom at 1020
        list = new SettingsList(this);
        list.setReducedMotion(reduced);
        list.setFocusListener((i, it) -> {
            if (stack.isEmpty()) return;
            Screen top = stack.get(stack.size() - 1);
            if (top.items == null) return;
            subView.setText(it.hint != null ? it.hint : top.sub);
        });
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(SettingsList.viewWidth(),
            Ui.px(listH) + 2 * SettingsList.padY());
        ll.leftMargin = Ui.px(listX) - SettingsList.padX();
        ll.topMargin = Ui.px(Ui.LIST_Y) - SettingsList.padY();
        root.addView(list, ll);

        about = new AboutView(this, aboutText(), notices());
        about.setReducedMotion(reduced);
        about.setVisibility(View.GONE);
        FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(Ui.px(Ui.LIST_W), Ui.px(listH));
        al.leftMargin = Ui.px(listX);
        al.topMargin = Ui.px(Ui.LIST_Y);
        root.addView(about, al);

        setContentView(root);

        stack.add(new Screen("Settings", ROOT_SUB, rootItems(), 0));
        showCurrent();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (catalog != null) catalog.close();
    }

    @Override
    protected void onStart() {
        super.onStart();
        config.refresh();
        reduced = computeReducedMotion();
        list.setReducedMotion(reduced);
        about.setReducedMotion(reduced);
        if (!stack.isEmpty() && stack.get(stack.size() - 1).items != null) list.refresh();
        strip.setClockVisible(config.getBool(Config.Keys.CLOCK, true));
        strip.setClock24h(config.getBool(Config.Keys.CLOCK_24H, true));
        strip.resume();
        started = true;
        refreshSky();
        ((OrchardApp) getApplication()).setSettingsScreenListener(this::onSettingsChangedLive);
        tickHandler.removeCallbacks(minuteTick);
        tickHandler.postDelayed(minuteTick, msToNextMinute());
    }

    private final android.os.Handler tickHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable minuteTick = this::onMinuteTick;      // a method ref: d8 8.2.2 crashes on an anonymous Runnable here

    private void onMinuteTick() {
        if (!started) return;
        refreshSky();                                               // clock pin, greeting and sky follow the minute (and a cleared debug_time)
        tickHandler.postDelayed(minuteTick, msToNextMinute());
    }

    private static long msToNextMinute() {
        long now = System.currentTimeMillis();
        return 60000L - (now % 60000L) + 50L;
    }

    /** debug_time (or another flag) changed through the broadcast channel while this screen is open. */
    private void onSettingsChangedLive() {
        if (!started) return;
        config.refresh();
        refreshSky();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        tickHandler.removeCallbacks(minuteTick);
        ((OrchardApp) getApplication()).setSettingsScreenListener(null);
        backdropGen++;
        dismissSaver();
        strip.pause();
        backdrop.set(null);
        blurKept = null;
    }

    // ---- backdrop -----------------------------------------------------------------------------------------------

    /** Re-evaluate the ambient state (theme, sky, city), repaint the colours and rebuild the backdrop slice. */
    private void refreshSky() {
        state = Ambient.get(this).evaluate(System.currentTimeMillis(), false);
        strip.setClockPin(state.clockMs, state.clockPinned);       // debug_time pins this clock like the home strip
        applyColors();
        loadBackdrop();
    }

    private void loadBackdrop() {
        if (!started) return;
        final int gen = ++backdropGen;
        SkyAssets.slice(this, state, b -> {
            if (gen != backdropGen || !started) return;
            blurKept = b;
            backdrop.set(b);
        });
    }

    /** No halo in Settings (the dimmer covers the sky): Theme.set(light, 0, 0), then recolour everything. */
    private void applyColors() {
        Theme.set(state.light, 0f, 0f);
        Theme t = Theme.cur();
        root.setBackgroundColor(t.bg);
        backdrop.setDim(Theme.argb(state.sky ? Ui.SKY_DIMMER : Ui.SETTINGS_DIM_ALPHA, t.bg), t.bg);
        titleView.setTextColor(t.text);
        subView.setTextColor(t.text3);
        about.applyTheme();
        list.applyTheme();
        strip.applyTheme();
    }

    private Bitmap blurredOrNull() { return blurKept; }

    /** The blurred wallpaper stretched full screen by the GPU (never upscaled into a bitmap) plus the dimmer. */
    private static final class Backdrop extends View {
        private int dim = Ui.argb(Ui.SETTINGS_DIM_ALPHA, 0x0B0B10);
        private int base = Ui.BG;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect src = new Rect();
        private final Rect dst = new Rect();
        private Bitmap bmp;

        Backdrop(android.content.Context c) {
            super(c);
            setFocusable(false);
        }

        void set(Bitmap b) {
            bmp = b;
            invalidate();
        }

        void setDim(int dimColor, int baseColor) {
            dim = dimColor;
            base = baseColor;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(base);
            Bitmap b = bmp;
            if (b != null) {
                src.set(0, 0, b.getWidth(), b.getHeight());
                dst.set(0, 0, getWidth(), getHeight());
                c.drawBitmap(b, src, dst, paint);
            }
            c.drawColor(dim);
        }
    }

    // ---- screen stack ---------------------------------------------------------------------------------------------

    /** Push a list screen (p5-2b: Dock apps, Hidden apps). The current screen's focused row is remembered for pop(). */
    void push(String title, String sub, List<SettingsList.Item> items, int focus) {
        saveFocus();
        stack.add(new Screen(title, sub, items, focus));
        showCurrent();
    }

    /** Back one screen; at the root this finishes the activity. */
    void pop() {
        if (stack.size() <= 1) {
            finish();
            return;
        }
        stack.remove(stack.size() - 1);
        if (stack.size() == 1) {                                                   // back at the root: refresh the details
            Screen old = stack.get(0);
            catalog.refresh();
            Screen fresh = new Screen(old.title, old.sub, rootItems(), old.focus);
            stack.set(0, fresh);
        }
        showCurrent();
    }

    SettingsList list() { return list; }

    private void saveFocus() {
        if (stack.isEmpty()) return;
        Screen top = stack.get(stack.size() - 1);
        if (top.items != null) top.focus = list.focusedIndex();
    }

    private void showCurrent() {
        Screen s = stack.get(stack.size() - 1);
        titleView.setText(s.title);
        subView.setText(s.sub);
        if (s.items == null) {
            list.setVisibility(View.INVISIBLE);
            about.setVisibility(View.VISIBLE);
            about.toTop();
            about.requestFocus();
        } else {
            about.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
            list.setItems(s.items, s.focus);
        }
    }

    private void pushAbout() {
        saveFocus();
        stack.add(new Screen("About", "Version, licences and credits.", null, 0));
        showCurrent();
    }

    // ---- root rows --------------------------------------------------------------------------------------------------

    private List<SettingsList.Item> rootItems() {
        ArrayList<SettingsList.Item> out = new ArrayList<SettingsList.Item>();

        out.add(SettingsList.choice("Theme", new String[] {"auto", "light", "dark"},
            new String[] {"Auto (sunset)", "Light", "Dark"},
            () -> config.get(Config.Keys.THEME, "auto"),
            v -> {
                commit(Config.Keys.THEME, v);
                refreshSky();
            }));

        out.add(SettingsList.toggle("Weather", () -> config.getBool(Config.Keys.WEATHER, true),
            v -> commit(Config.Keys.WEATHER, v ? "on" : "off")));

        CityCommand.Resolved city = Cities.current(this, config);
        String cc = city.cc != null ? city.cc : Locale.getDefault().getCountry();
        String auto = RainText.useF("auto", cc) ? "Auto (\u00b0F)" : "Auto (\u00b0C)";
        out.add(SettingsList.choice("Units", new String[] {"auto", "c", "f"},
            new String[] {auto, "\u00b0C", "\u00b0F"},
            () -> config.get(Config.Keys.UNITS, "auto"),
            v -> commit(Config.Keys.UNITS, v)));

        out.add(SettingsList.action("City", city.name, this::openCity));

        out.add(SettingsList.action("Screensaver", "Preview", this::startSaver)
            .hint(getString(R.string.dream_hint)));

        out.add(SettingsList.action("Dock apps…", dockSummary(), () -> openPicker(KIND_DOCK)));
        out.add(SettingsList.action("Hidden apps…", catalog.hiddenPackages().size() + " hidden",
            () -> openPicker(KIND_HIDDEN)));

        out.add(SettingsList.choice("Tile labels", new String[] {"focus", "always"},
            new String[] {"Focused only", "Always"},
            () -> config.get(Config.Keys.LABELS, "focus"),
            v -> commit(Config.Keys.LABELS, v)));

        boolean user = Wallpaper.hasUserFile(this);
        final String[] wallValues = user ? new String[] {"sky", "dusk", "ember", "fog", "black", "user"}
            : new String[] {"sky", "dusk", "ember", "fog", "black"};
        final String[] wallLabels = user ? new String[] {"Sky", "Dusk", "Ember", "Fog", "Black", "Your file"}
            : new String[] {"Sky", "Dusk", "Ember", "Fog", "Black"};
        final boolean userNow = user;
        out.add(SettingsList.choice("Wallpaper", wallValues, wallLabels,
            () -> {
                String w = config.get(Config.Keys.WALLPAPER, Ui.DEFAULT_WALLPAPER);
                if ("user".equals(w) && !userNow) return Ui.DEFAULT_WALLPAPER;   // the file is gone: Wallpaper falls back too
                return w;
            },
            v -> {
                commit(Config.Keys.WALLPAPER, v);
                refreshSky();
            }));

        out.add(SettingsList.toggle("Reduced motion",
            () -> "reduced".equals(config.get(Config.Keys.MOTION, "full")),
            v -> {
                commit(Config.Keys.MOTION, v ? "reduced" : "full");
                reduced = computeReducedMotion();
                list.setReducedMotion(reduced);
                about.setReducedMotion(reduced);
            }));

        out.add(onOff("Clock", Config.Keys.CLOCK));
        out.add(onOff("24-hour clock", Config.Keys.CLOCK_24H));

        out.add(SettingsList.choice("Shelf card", new String[] {"emblem", "banner", "off"},
            new String[] {"Emblem", "Banner", "Off"},
            () -> {
                String c = config.get(Config.Keys.CARD, "emblem");
                return "on".equals(c) ? "banner" : c;                              // legacy 'on' = banner
            },
            v -> commit(Config.Keys.CARD, v)));

        out.add(SettingsList.choice("Hero style", new String[] {"wash", "blur"},
            new String[] {"Wash", "Blur"},
            () -> config.get(Config.Keys.HERO, "wash"),
            v -> commit(Config.Keys.HERO, v)));

        out.add(onOff("Fire TV Home tile", Config.Keys.FIRETV_TILE));

        out.add(SettingsList.action("Return to Fire TV…", "", () -> {
            final int idx = list.focusedIndex();
            sheet = ConfirmSheet.showReset(root, new FireTvBridge(this), blurredOrNull(), () -> {
                sheet = null;
                list.focusIndex(idx);
            });
        }));

        out.add(SettingsList.action("About", "", this::pushAbout));
        return out;
    }

    // ---- City screen ------------------------------------------------------------------------------------------------

    private void openCity() {
        Screen s = new Screen("City", CITY_SUB, cityItems(), 0);
        s.kind = KIND_CITY;
        CityCommand.Resolved cur = Cities.current(this, config);
        if (cur.id != null) {
            List<CityCommand.City> all = Cities.all(this);
            for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(cur.id)) s.focus = i;
        }
        saveFocus();
        stack.add(s);
        showCurrent();
    }

    /** 'Sunset HH:MM' for the city today in its own zone; 'Polar' when the sun does not set (or rise). */
    private String sunsetDetail(double lat, double lon, String tzId) {
        TimeZone tz = tzId != null ? TimeZone.getTimeZone(tzId) : TimeZone.getDefault();
        Calendar cal = Calendar.getInstance(tz);
        cal.setTimeInMillis(TimeSource.now(System.currentTimeMillis(), config.get(DebugFlags.KEY_TIME, null), tz));
        int y = cal.get(Calendar.YEAR), m = cal.get(Calendar.MONTH) + 1, d = cal.get(Calendar.DAY_OF_MONTH);
        Calendar noon = Calendar.getInstance(tz);
        noon.clear();
        noon.set(y, m - 1, d, 12, 0, 0);
        double tzH = tz.getOffset(noon.getTimeInMillis()) / 3.6e6;
        Solar.Times t = Solar.times(lat, lon, y, m, d, tzH);
        if (Double.isNaN(t.set)) return "Polar";
        return "Sunset " + RainText.hhmm(t.set);
    }

    private List<SettingsList.Item> cityItems() {
        ArrayList<SettingsList.Item> out = new ArrayList<SettingsList.Item>();
        final CityCommand.Resolved cur = Cities.current(this, config);
        if (cur.id == null) {                                                       // a custom city: first, checked, Select = back
            final String detail = sunsetDetail(cur.lat, cur.lon, cur.tz);
            out.add(SettingsList.check(cur.name + " (custom)", () -> true, this::pop, () -> detail));
        }
        final List<CityCommand.City> all = Cities.all(this);
        for (final CityCommand.City c : all) {
            final boolean here = cur.id != null && cur.id.equals(c.id);
            final String detail = sunsetDetail(c.lat, c.lon, c.tz);
            out.add(SettingsList.check(c.name, () -> here, () -> pickCity(all, c), () -> detail));
        }
        return out;
    }

    private void pickCity(List<CityCommand.City> all, CityCommand.City c) {
        Map<String, String> m = config.snapshot();
        int r = CityCommand.apply(m, all, c.id, null, null, null);
        if (r == 1) {
            config.set(Config.Keys.CITY_ID, c.id);
            config.remove(Config.Keys.CITY_LAT);
            config.remove(Config.Keys.CITY_LON);
            config.remove(Config.Keys.CITY_NAME);
            config.remove(Config.Keys.CITY_TZ);
            commit(Config.Keys.CITY_ID, c.id);
            Ambient.get(this).settingsChanged();                                    // drops the old city's weather cache
            refreshSky();
        } else if (r < 0) {
            list.bumpFocused();
            return;
        }
        pop();
    }

    // ---- Screensaver preview ------------------------------------------------------------------------------------

    private void startSaver() {
        if (saver != null) return;
        saver = new ClockFace(this);
        saver.setReducedMotion(reduced);
        root.addView(saver, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        saver.start();
    }

    private void dismissSaver() {
        if (saver == null) return;
        saver.stop();
        root.removeView(saver);
        saver = null;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (saver != null) {                                                        // any key stops the preview and is consumed
            if (event.getAction() == KeyEvent.ACTION_DOWN) dismissSaver();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ---- pickers (p5-2b) ------------------------------------------------------------------------------------------

    private String dockSummary() {
        StringBuilder sb = new StringBuilder();
        for (AppEntry e : catalog.dock()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.label);
        }
        if (sb.length() > DOCK_DETAIL_MAX) return sb.substring(0, DOCK_DETAIL_MAX - 1).trim() + "…";
        return sb.toString();
    }

    private void openPicker(int kind) {
        catalog.refresh();
        Screen s = new Screen(kind == KIND_DOCK ? "Dock apps" : "Hidden apps", kind == KIND_DOCK ? DOCK_SUB : HIDDEN_SUB,
            kind == KIND_DOCK ? dockItems() : hiddenItems(), 0);
        s.kind = kind;
        saveFocus();
        stack.add(s);
        showCurrent();
    }

    /** Re-read the catalog, rebuild the top picker's rows and keep focus on the same package (or the same index). */
    private void rebuildPicker(String focusPkg) {
        Screen old = stack.get(stack.size() - 1);
        catalog.refresh();
        List<SettingsList.Item> items = old.kind == KIND_DOCK ? dockItems() : hiddenItems();
        List<String> order = old.kind == KIND_DOCK ? dockOrder() : hiddenOrder();
        int idx = Math.max(0, order.indexOf(focusPkg));
        Screen fresh = new Screen(old.title, old.sub, items, idx);
        fresh.kind = old.kind;
        stack.set(stack.size() - 1, fresh);
        list.setItems(items, idx);
        ((OrchardApp) getApplication()).bumpSettingsEpoch();
    }

    private void bumpFocused() {
        list.bumpFocused();
    }

    /** Visible apps: dock apps first in dock order, then the rest in grid order. */
    private List<String> dockOrder() {
        ArrayList<String> out = new ArrayList<String>(catalog.dockPackages());
        Set<String> seen = new HashSet<String>(out);
        for (String p : catalog.gridPackages()) if (seen.add(p)) out.add(p);
        Set<String> hid = catalog.hiddenPackages();
        for (int i = out.size() - 1; i >= 0; i--) if (hid.contains(out.get(i)) || catalog.find(out.get(i)) == null) out.remove(i);
        return out;
    }

    private List<SettingsList.Item> dockItems() {
        List<String> order = dockOrder();
        final List<String> dockNow = catalog.dockPackages();
        boolean full = catalog.isDockFull();
        ArrayList<SettingsList.Item> out = new ArrayList<SettingsList.Item>();
        for (final String pkg : order) {
            final int pos = dockNow.indexOf(pkg);
            final boolean in = pos >= 0;
            final String detail = in ? String.valueOf(pos + 1) : (full ? "Dock full" : "");
            SettingsList.Item it = SettingsList.check(catalog.find(pkg).label, () -> in, () -> {
                boolean ok = in ? catalog.removeFromDock(pkg) : catalog.addToDock(pkg);
                if (ok) rebuildPicker(pkg);
                else bumpFocused();
            }, () -> detail);
            if (!in && full) it.disabled(true);
            if (in) {
                it.onHorizontal(dir -> {
                    if (catalog.moveInDock(pkg, dir)) rebuildPicker(pkg);
                    else bumpFocused();
                });
            }
            out.add(it);
        }
        return out;
    }

    private static final Comparator<AppEntry> BY_LABEL = (a, b) -> {
        String x = a.label == null ? "" : a.label.toLowerCase(Locale.ROOT);
        String y = b.label == null ? "" : b.label.toLowerCase(Locale.ROOT);
        int c = x.compareTo(y);
        return c != 0 ? c : a.packageName.compareTo(b.packageName);
    };

    private List<String> hiddenOrder() {
        ArrayList<AppEntry> sorted = new ArrayList<AppEntry>(catalog.all());
        Collections.sort(sorted, BY_LABEL);
        ArrayList<String> out = new ArrayList<String>();
        for (AppEntry e : sorted) out.add(e.packageName);
        return out;
    }

    private List<SettingsList.Item> hiddenItems() {
        ArrayList<SettingsList.Item> out = new ArrayList<SettingsList.Item>();
        for (final String pkg : hiddenOrder()) {
            final boolean hid = catalog.hiddenPackages().contains(pkg);
            out.add(SettingsList.check(catalog.find(pkg).label, () -> hid, () -> {
                boolean ok = hid ? catalog.unhide(pkg) : catalog.hide(pkg);
                if (ok) rebuildPicker(pkg);
                else bumpFocused();
            }, () -> hid ? "Hidden" : ""));
        }
        return out;
    }

    private SettingsList.Item onOff(String label, final String key) {
        return SettingsList.toggle(label, () -> config.getBool(key, true),
            v -> commit(key, v ? "on" : "off"));
    }

    /**
     * Every setting change goes through here: set, save (a failed save keeps the in-memory value), then bump the
     * settings epoch so HomeActivity rebuilds when it resumes.
     */
    private void commit(String key, String value) {
        config.set(key, value);
        try {
            config.save();
        } catch (IOException | RuntimeException e) {
            // keep the in-memory value; the next successful save writes it
        }
        ((OrchardApp) getApplication()).bumpSettingsEpoch();
    }

    /** Same rule as HomeActivity.computeReducedMotion; read-only. */
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

    // ---- keys -----------------------------------------------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;                         // acted on at UP
        return super.onKeyDown(keyCode, event);                                    // Menu etc. pass through untouched
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && !event.isCanceled()) {
            if (sheet != null && sheet.isShowing()) {
                sheet.cancel();
                return true;
            }
            pop();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ---- About ----------------------------------------------------------------------------------------------------------------

    private String aboutText() {
        String name = "?";
        long code = 0;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (pi.versionName != null) name = pi.versionName;
            code = pi.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            // keep the placeholders
        }
        return "Orchard " + name + " (" + code + ")";
    }

    private String notices() {
        try (InputStream in = getAssets().open("NOTICES.txt")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            String s = new String(bo.toByteArray(), Charset.forName("UTF-8")).trim();
            return s.isEmpty() ? "Notices file missing." : s;
        } catch (IOException | RuntimeException e) {
            return "Notices file missing.";
        }
    }

    /** Scrollable text; UP / DOWN scroll 3/4 of a page per press (smooth unless reduced motion). */
    private static final class AboutView extends FrameLayout {
        private final ScrollView scroll;
        private final TextView[] views = new TextView[4];
        private boolean reduced;

        AboutView(android.content.Context c, String version, String notices) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(false);
            setDefaultFocusHighlightEnabled(false);
            scroll = new ScrollView(c);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.setFocusable(false);
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(0, 0, 0, Ui.px(120));
            TextView v = styled(c, Ui.T_BODY, Ui.medium(), Theme.cur().text);
            views[0] = v;
            v.setText(version);
            col.addView(v, lp(0));
            TextView lic = styled(c, Ui.T_CAPTION1, Ui.regular(), Theme.cur().text2);
            views[1] = lic;
            lic.setText("Orchard is MIT licensed. Inter: SIL Open Font License 1.1.\n" + c.getString(R.string.weather_attribution));
            col.addView(lic, lp(28));
            TextView cr = styled(c, Ui.T_CAPTION1, Ui.regular(), Theme.cur().text2);
            views[2] = cr;
            cr.setText("Credits: ideas and code patterns from tv-vibehack (MIT, Gathin) and dumbtv (MIT, dumbtv contributors). "
                + "Works with Home on Fire (GPLv3); no code shared.");
            col.addView(cr, lp(20));
            TextView nt = styled(c, Ui.T_CAPTION1, Ui.regular(), Theme.cur().text2);
            views[3] = nt;
            nt.setText(notices);
            col.addView(nt, lp(40));
            scroll.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        }

        private static LinearLayout.LayoutParams lp(int topDesignPx) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
            p.topMargin = Ui.px(topDesignPx);
            return p;
        }

        private static TextView styled(android.content.Context c, int designPx, android.graphics.Typeface tf, int color) {
            TextView t = new TextView(c);
            t.setTypeface(tf);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.pxf(designPx));
            t.setTextColor(color);
            t.setLetterSpacing(Ui.tracking(designPx));
            t.setIncludeFontPadding(false);
            t.setLineSpacing(0f, 1.35f);
            t.setGravity(Gravity.START);
            return t;
        }

        void applyTheme() {
            Theme t = Theme.cur();
            views[0].setTextColor(t.text);
            for (int i = 1; i < views.length; i++) views[i].setTextColor(t.text2);
        }

        void setReducedMotion(boolean r) { reduced = r; }

        void toTop() { scroll.scrollTo(0, 0); }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent e) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                int dy = Math.max(1, getHeight() * 3 / 4) * (keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                if (reduced) scroll.scrollBy(0, dy);
                else scroll.smoothScrollBy(0, dy);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
            return super.onKeyDown(keyCode, e);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------------------

    private TextView text(int designPx, android.graphics.Typeface tf, int color) {
        TextView t = new TextView(this);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.pxf(designPx));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }
}
