package tv.orchard.home;

import android.content.Context;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * Every constant from PLAN section 0 (Rev 3), copied from mockup/tokens.css. All lengths are DESIGN px
 * on the 1920x1080 canvas; convert with px(). Times are ms, factors are plain floats. Dark only.
 * Owned by task 1.2, then 3.5 (on-device tuning).
 */
public final class Ui {
    private Ui() {}

    // ---- scale (0.2): s = screenWidthPx / 1920f, set by init() --------------------------------
    private static float s = 1f;
    public static void init(Context ctx) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        s = dm.widthPixels / (float) CANVAS_W;
        if (s <= 0f) s = 1f;
        if (fRegular == null) {
            fRegular = font(ctx, R.font.inter_regular, Typeface.create("sans-serif", Typeface.NORMAL));
            fMedium = font(ctx, R.font.inter_medium, Typeface.create("sans-serif-medium", Typeface.NORMAL));
            fSemibold = font(ctx, R.font.inter_semibold, Typeface.create("sans-serif-medium", Typeface.BOLD));
        }
    }
    private static Typeface font(Context ctx, int id, Typeface fallback) {
        try {
            Typeface t = ctx.getResources().getFont(id);
            return t != null ? t : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }
    public static float scale() { return s; }
    public static int px(float v) { return Math.round(v * s); }
    public static float pxf(float v) { return v * s; }

    // ---- 0.2 canvas, safe area, grid (6 columns default) --------------------------------------
    public static final int CANVAS_W = 1920, CANVAS_H = 1080;
    public static final int SAFE_X = 80, SAFE_Y = 60;
    public static final int COLS = 6;
    public static final int TILE_W = 260, TILE_H = 156;      // 5:3
    public static final int GUTTER = 40;
    public static final int COL_PITCH = 300;                  // TILE_W + GUTTER
    public static final int ROW_GAP = 100;
    public static final int ROW_PITCH = 256;                  // TILE_H + ROW_GAP
    public static final int TILE_R = 14;
    public static final int TILE_PAD = 40;
    // 5-column alternative (G0 decision): COLS=5, TILE 320x192, COL_PITCH 360, ROW_PITCH 292, TILE_R 17
    public static final int ALT_COLS = 5, ALT_TILE_W = 320, ALT_TILE_H = 192, ALT_COL_PITCH = 360,
            ALT_ROW_PITCH = 292, ALT_TILE_R = 17;
    public static final float TILE_EDGE_ALPHA = 0.07f;        // static 1 px art edge

    // ---- 0.3 home layout bands ----------------------------------------------------------------
    public static final int STRIP_Y0 = 60, STRIP_Y1 = 110;
    public static final int HERO_Y0 = 60, HERO_Y1 = 580;
    public static final int HERO_BLEED_H = 600, HERO_FADE_H = 240;
    public static final int HERO_ART_W = 560, HERO_ART_H = 315, HERO_ART_R = 22;
    public static final int HERO_ART_X = 1280, HERO_ART_Y = 172;
    public static final int HERO_ART_SHADOW_Y = 32, HERO_ART_SHADOW_BLUR = 72;
    public static final float HERO_ART_SHADOW_ALPHA = 0.55f;
    public static final int HERO_ART_CONTACT_Y = 4, HERO_ART_CONTACT_BLUR = 12;
    public static final float HERO_ART_CONTACT_ALPHA = 0.45f;
    public static final float HERO_GLOW_SCALE = 1.12f;
    public static final int HERO_GLOW_Y = 28;
    public static final float HERO_GLOW_ALPHA = 0.50f;
    public static final float HERO_GLOW_FLAT_LUM = 0.50f, HERO_GLOW_FLAT_ALPHA = 0.25f;
    public static final int HERO_GLOW_BLUR = 40;              // ~ stack radius 5 on 240x135
    public static final float HERO_GLOW_SAT = 1.3f;
    public static final float HERO_RIM_TOP = 0.38f, HERO_RIM_BOTTOM = 0.08f;
    public static final int HERO_TITLE_X = 80, HERO_TITLE_BASELINE = 330, HERO_SUB_BASELINE = 380;
    public static final float HERO_SAT_MAX = 0.70f;
    public static final float HERO_LUM = 0.32f;
    public static final float HERO_WASH_X = 0.80f, HERO_WASH_Y = 0.45f, HERO_WASH_RX = 0.62f,
            HERO_WASH_RY = 1.10f, HERO_WASH_MID = 0.38f, HERO_WASH_MID_LUM = 0.14f;
    public static final float HERO_LEAK_ALPHA = 0.06f;
    public static final float HERO_BL_ALPHA = 0.35f, HERO_BL_SAT = 1.35f;
    public static final int HERO_BL_BLUR = 90;                // ~ stack radius 11 on 240x135
    public static final float HERO_DIM_LEFT = 0.55f, HERO_DIM_RIGHT = 0.10f, HERO_DIM_BOTTOM = 0.35f;
    public static final int HERO_FADE_MS = 280, HERO_DEBOUNCE_MS = 120;
    public static final int SHELF_HIDE_MS = 250;              // shelf fades out while a grid row is focused
    public static final int OVERLAY_HIDE_MS = 200;            // shelf hidden under panel / sheet
    public static final int DOCK_BAND_X0 = 40, DOCK_BAND_X1 = 1880, DOCK_BAND_Y0 = 576;
    public static final int DOCK_BAND_PAD = 24, DOCK_BAND_PAD_BOTTOM = 16, DOCK_BAND_R = 32;
    public static final int DOCK_TILE_Y = 600;                // grid row k top = DOCK_TILE_Y + ROW_PITCH * k
    public static final int GRID_ANCHOR_Y = 470;              // P4: a focused grid row's top sits here, the row above it at 214 (no dead band under the strip)
    public static final int SCROLL_MS = 350;
    public static final int LABEL_GAP = 16;                   // below the SCALED tile bottom
    public static final int LABEL_IN_MS = 240, LABEL_RISE = 8;
    public static final float LABEL_ALWAYS_ALPHA = 0.62f;     // labels=always

    // ---- 0.4 tile art ---------------------------------------------------------------------------
    public static final int ART_W = 325, ART_H = 195;         // RGB_565 raster, 1.25x of tile
    public static final int SHELF_ART_MAX_W = 640, SHELF_ART_MAX_H = 360;
    public static final int BLUR_W = 240, BLUR_H = 135;       // pre-blurred copies
    public static final int GLASS_BLUR_RADIUS = 6;            // = 48 design px
    public static final int TILE_FALLBACK_ICON = 96, TILE_FALLBACK_ICON_R = 22;
    public static final float TILE_FALLBACK_LUM_A = 0.26f, TILE_FALLBACK_LUM_B = 0.09f;
    public static final float TILE_FALLBACK_HALO_LUM = 0.42f, TILE_FALLBACK_HALO_ALPHA = 0.55f;
    public static final int FALLBACK_ICON_SHADOW_Y = 10, FALLBACK_ICON_SHADOW_BLUR = 24;
    public static final float FALLBACK_ICON_SHADOW_ALPHA = 0.45f, FALLBACK_ICON_EDGE_ALPHA = 0.35f;
    public static final int ART_WEBP_QUALITY = 85;

    // ---- 0.5 focus effect -----------------------------------------------------------------------
    public static final float FOCUS_SCALE = 1.12f;
    public static final int FOCUS_IN_MS = 240, FOCUS_OUT_MS = 160;
    public static final float FOCUS_EASE_X1 = 0.34f, FOCUS_EASE_Y1 = 1.25f, FOCUS_EASE_X2 = 0.64f, FOCUS_EASE_Y2 = 1f;
    public static final float FOCUS_OUT_ACCEL = 1.2f;
    public static final float PRESS_SCALE = 0.96f;
    public static final int PRESS_MS = 80;
    public static final int SHADOW_Y = 28, SHADOW_BLUR = 56;
    public static final float SHADOW_ALPHA = 0.48f;
    public static final int CONTACT_Y = 3, CONTACT_BLUR = 8;
    public static final float CONTACT_ALPHA = 0.40f;
    public static final int SHADOW_BITMAP_PAD = 160, GLOW_BITMAP_PAD = 200;
    public static final int GLOW_BLUR = 96, GLOW_Y = 12;
    public static final float GLOW_ALPHA = 0.65f, GLOW_LUM = 0.50f;
    public static final float GLOW_DARK_LUM = 0.58f, GLOW_DARK_ALPHA = 0.85f;   // art average below L 0.2
    public static final float GLOW_DARK_THRESHOLD_LUM = 0.2f;
    public static final float FOCUS_BRIGHTNESS = 1.03f;
    public static final float GLOSS_ALPHA = 0.14f;
    public static final float RIM_TOP = 0.32f, RIM_SIDE = 0.10f;
    public static final float SWAY_ROT_Y = 1.5f, SWAY_ROT_X = 1.0f;             // degrees
    public static final int SWAY_PERIOD_MS = 6000, SWAY_START_MS = 400, SWAY_MAX_CYCLES = 3, SWAY_MAX_MS = 20000;
    public static final float SWAY_CAMERA = 8000f;                              // x density
    public static final int IDLE_MS = 3000;
    public static final float IDLE_ALPHA = 0.90f, IDLE_SCALE_ADD = 0.02f;
    public static final int IDLE_FADE_MS = 600;
    public static final int BUMP_PX = 6, BUMP_MS = 120;
    public static final float REDUCED_SCALE = 1.06f;
    public static final int REDUCED_FADE_MS = 150;

    // ---- 0.6 typography (Inter) ------------------------------------------------------------------
    public static final int T_TITLE2 = 57, W_TITLE2 = 500;
    public static final int T_HEADLINE = 38, W_HEADLINE = 500;
    public static final int T_BODY = 29, W_BODY_LABEL = 500, W_BODY_SUB = 400;
    public static final int T_CAPTION1 = 25, W_CAPTION1 = 400;
    public static final int T_CAPTION2 = 23, W_CAPTION2 = 500;                  // nothing smaller
    public static final int T_CLOCK_TITLE1 = 76;                                // ClockDream (0.11)
    public static final float TRACK_LARGE = -0.02f;                             // em, >= 48 px
    public static final float TRACK_MID = -0.01f;                               // em, 29..47 px
    public static final float TRACK_SMALL = 0.005f;                             // em, <= 25 px

    /** Letter-spacing (em) for a design-px text size. */
    public static float tracking(int designPx) {
        if (designPx >= 48) return TRACK_LARGE;
        if (designPx >= 29) return TRACK_MID;
        return TRACK_SMALL;
    }

    // Fonts (0.6): Inter subsets in res/font (task 1.4), loaded once by init(); system sans before init or on failure.
    private static Typeface fRegular, fMedium, fSemibold;
    public static Typeface regular() { return fRegular != null ? fRegular : Typeface.create("sans-serif", Typeface.NORMAL); }
    public static Typeface medium() { return fMedium != null ? fMedium : Typeface.create("sans-serif-medium", Typeface.NORMAL); }
    public static Typeface semibold() { return fSemibold != null ? fSemibold : Typeface.create("sans-serif-medium", Typeface.BOLD); }

    // ---- 0.7 colour tokens (ARGB) -------------------------------------------------------------------
    public static int argb(float a, int rgb) { return (Math.round(a * 255f) << 24) | (rgb & 0xFFFFFF); }
    public static final int BG = 0xFF0B0B10;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT2 = argb(0.62f, 0xFFFFFF);
    public static final int TEXT3 = argb(0.42f, 0xFFFFFF);
    public static final int GLASS_THIN = argb(0.07f, 0xFFFFFF);
    public static final int GLASS = argb(0.12f, 0xFFFFFF);
    public static final int GLASS_HAIRLINE = argb(0.16f, 0xFFFFFF);
    public static final int GLASS_THICK = argb(0.74f, 0x0E0E14);
    public static final int SHADOW = argb(0.48f, 0x000000);
    public static final int ACCENT = 0xFF3D8BFF;                                // toggles only, never focus
    public static final int BTN_FOCUS_FILL = 0xFFF2F2F7;
    public static final int BTN_FOCUS_TEXT = 0xFF111111;
    public static final float STRIP_HINT_ALPHA = 0.55f, CLOCK_ALPHA = 0.92f;
    public static final float TEXT2_ALPHA = 0.62f, TEXT3_ALPHA = 0.42f;

    // ---- 0.8 glass (Liquid Glass stand-in) -------------------------------------------------------------
    public static final float GLASS_RIM_HI = 0.34f, GLASS_RIM_LO = 0.07f, GLASS_RIM_MIDLO_STOP = 0.38f,
            GLASS_RIM_MIDHI_STOP = 0.70f, GLASS_RIM_END = 0.20f;
    public static final int GLASS_RIM_ANGLE = 115;                              // degrees
    public static final float GLASS_TOP_EDGE_ALPHA = 0.28f;
    public static final float GLASS_INNER_ALPHA = 0.10f, GLASS_INNER_H = 0.45f;
    public static final int GLASS_REFRACT_H = 28;
    public static final float GLASS_REFRACT_ALPHA = 0.12f;
    public static final int GLASS_BLUR_DESIGN_PX = 48;
    public static final int GLASS_DROP_Y = 24, GLASS_DROP_BLUR = 60;
    public static final float GLASS_DROP_ALPHA = 0.35f;
    public static final float DOCK_SLICE_ALPHA = 0.90f;                         // blurred wallpaper under the band
    public static final int DOCK_FILL = argb(0.065f, 0xFFFFFF);
    public static final float DOCK_EDGE_TOP = 0.30f, DOCK_EDGE_BOTTOM = 0.06f;
    public static final float WALL_VIGNETTE_ALPHA = 0.40f;
    public static final String DEFAULT_WALLPAPER = "dusk";

    // ---- 0.9 Quick Panel -----------------------------------------------------------------------------------
    public static final int PANEL_X = 460, PANEL_Y = 60, PANEL_W = 1000, PANEL_H = 316, PANEL_R = 32;
    public static final int PANEL_ICON_TOP = 44, PANEL_TILE = 200, PANEL_TILE_R = 28, PANEL_GAP = 32;
    public static final int PANEL_TILE_X = 512, PANEL_TILE_Y = 96;
    public static final int PANEL_HINT_BASELINE = 356;
    public static final int PANEL_IN_MS = 260, PANEL_OUT_MS = 200;
    public static final float PANEL_EASE_X1 = 0.22f, PANEL_EASE_Y1 = 1.1f, PANEL_EASE_X2 = 0.36f, PANEL_EASE_Y2 = 1f;
    public static final int MENU_MAX_TAP_MS = 450, MENU_HOOF_MS = 500;
    public static final float PANEL_FOCUS_SCALE = 1.08f;

    // ---- 0.10 Reset confirm sheet -----------------------------------------------------------------------------
    public static final int SHEET_W = 880, SHEET_H_MIN = 420, SHEET_R = 36, SHEET_PAD = 48;
    public static final int BTN_W = 360, BTN_H = 88, BTN_R = 44, BTN_GAP = 24;
    public static final float BTN_FOCUS_SCALE = 1.04f;
    public static final float SHEET_SCALE_FROM = 0.94f;
    public static final float SHEET_VEIL_ALPHA = 0.72f;
    public static final int SHEET_RESET_CHECK_MS = 2000, SHEET_POLL_MS = 100;

    // ---- settings list (mockup layout) -----------------------------------------------------------------------------
    public static final int LIST_Y = 150, LIST_W = 880, LIST_ROW_H = 76, LIST_ROW_GAP = 10, LIST_ROW_R = 22;
    public static final int TOGGLE_W = 76, TOGGLE_H = 44;

    // ---- 0.11 screensaver -----------------------------------------------------------------------------------------
    public static final int DREAM_MOVE_MS = 60000, DREAM_GLOW_CYCLE_MS = 8000, DREAM_GLOW_COLOURS = 5;

    // ---- interpolators ----------------------------------------------------------------------------------------------
    public static Interpolator focusIn() {
        return new PathInterpolator(FOCUS_EASE_X1, FOCUS_EASE_Y1, FOCUS_EASE_X2, FOCUS_EASE_Y2);
    }
    public static Interpolator focusOut() { return new AccelerateInterpolator(FOCUS_OUT_ACCEL); }
    public static Interpolator panelIn() {
        return new PathInterpolator(PANEL_EASE_X1, PANEL_EASE_Y1, PANEL_EASE_X2, PANEL_EASE_Y2);
    }
    public static Interpolator panelOut() { return new AccelerateInterpolator(); }

    /** Duration multiplier from the debug_slow_anim flag (1 unless the flag is set; read once at start). */
    public static float animScale = 1f;
    public static long ms(long base) { return Math.round(base * animScale); }

    // ---- appended by task 3.4a (home assembly) ------------------------------------------------------------------------
    public static final int STRIP_VIEW_H = 190;               // P4: scrim clear at 190 (tokens --scrim-h)
    public static final int STRIP_SCRIM_SOLID_H = 120;        // P4: scrim opaque to 120 (tokens --scrim-solid-h)
    public static final String SHELF_KEY_FTH = "fth";         // lastFocusedKey of the Fire TV Home tile

    // ---- consolidated by task 3.5 (were '// -> Ui' in the Phase 3 files) -----------------------------------------------
    // 0.4 shadow/glow bitmaps (TileRow)
    public static final float DRAW_MIN_PROGRESS = 0.002f;     // skip shadow/glow below this focus progress
    public static final int FLABEL_SHADOW_BLUR = 6, FLABEL_SHADOW_DY = 1;  // mockup #flabel text-shadow 0 1px 6px
    public static final int FLABEL_SHADOW_COLOR = 0x66000000; // mockup #flabel text-shadow colour
    // 0.5 tile (TileView)
    public static final int PLATE = 0xFF1A1A22;               // placeholder plate fill
    public static final int FTH_GLOW = 0xFF5B6477;            // Fire TV Home glow colour
    public static final int FTH_FILL_ANGLE = 160;             // glass fill gradient angle (deg)
    public static final float FTH_FILL_HI = 0.11f, FTH_FILL_LO = 0.04f;    // glass fill white alphas
    public static final int FTH_GLYPH_BOX = 64, FTH_GLYPH_RAISE = 10, FTH_CAPTION_BOTTOM = 16;
    public static final float FTH_GLYPH_ALPHA = 0.92f;
    public static final float FTH_STROKE_UNITS = 2.6f, FTH_VIEWBOX = 48f;
    public static final float FTH_RIM_MID_STOP = 0.45f;
    // art analysis (ArtCache): "near-white icon" detection
    public static final float NEAR_WHITE_LUM = 0.80f;
    public static final float NEAR_WHITE_SAT = 0.15f;
    // P4 r2: or at least this share of the banner is white (L >= 0.88, S <= 0.25), e.g. Netflix / YouTube banners,
    // so their hero glow is the flat vivid disc (PLAN 0.3) instead of a grey bloom. Device-only (the mockup hand-flags it).
    public static final float NEAR_WHITE_PIX_LUM = 0.88f, NEAR_WHITE_PIX_SAT = 0.25f, NEAR_WHITE_FRACTION = 0.55f;

    // ---- Phase 4 (task p4-shelf) ----------------------------------------------------------------------------------------
    public static final float HERO_EMBLEM_LUM_A = 0.34f, HERO_EMBLEM_LUM_B = 0.12f;   // plate gradient, top / bottom (HSL L)
    public static final float HERO_EMBLEM_HALO_LUM = 0.50f, HERO_EMBLEM_HALO_ALPHA = 0.55f;
    public static final int HERO_EMBLEM_ICON = 168, HERO_EMBLEM_ICON_R = 38;          // on the 560 design-px card
    public static final int HERO_EMBLEM_ICON_SHADOW_Y = 14, HERO_EMBLEM_ICON_SHADOW_BLUR = 36;
    public static final float HERO_EMBLEM_ICON_SHADOW_ALPHA = 0.50f, HERO_EMBLEM_ICON_EDGE_ALPHA = 0.35f;
    public static final float HERO_EMBLEM_ICON_MAX_UPSCALE = 1.5f;
    public static final float HERO_EMBLEM_GLOW_ALPHA = 0.40f;                         // flat vivid glow behind the plate (L 0.50)
    public static final int HERO_CARD_FADE_IN_MS = 150;                               // card appearing when its art arrives late
    public static final int HERO_TITLE_MAX_W = 1100;                                  // title / subtitle ellipsized to this width
    public static final int HERO_TITLE_SHADOW_Y = 2, HERO_TITLE_SHADOW_BLUR = 24;     // mockup .htitle text-shadow
    public static final float HERO_TITLE_SHADOW_ALPHA = 0.35f;

    // ---- Phase 5 (task p5-0) ----------------------------------------------------------------------------------------
    public static final int LONG_SELECT_MS = 600, VEIL_FADE_MS = 220;
    public static final float PANEL_VEIL_ALPHA = 0.50f;                               // mockup #veil.panel (the sheet keeps SHEET_VEIL_ALPHA 0.72)
    public static final int PANEL_SLIDE_FROM = -400;                                  // mockup #panel closed translateY
    public static final int PANEL_ICON = 60;                                          // glyph box; glyphs use a 48 viewBox, stroke 2.6
    public static final int PANEL_LABEL_GAP = 12, PANEL_TILE_PAD_X = 14, PANEL_SUB_GAP = -8;
    public static final float PANEL_SUB_ALPHA = 0.60f, PANEL_LINE_HEIGHT = 1.15f, PANEL_RIM_ALPHA = 0.80f;
    public static final int SHEET_FADE_MS = 200, SHEET_TITLE_GAP = 14, SHEET_KEEPS_GAP = 14, SHEET_BTN_TOP = 28;
    public static final float SHEET_LINE_HEIGHT = 1.30f;
    public static final int BTN_WIDE_W = 520;
    public static final int SETTINGS_TITLE_TOP = 150, SETTINGS_SUB_TOP = 236, SETTINGS_SUB_W = 480;
    public static final float SETTINGS_DIM_ALPHA = 0.55f;
    public static final float LIST_FOCUS_SCALE = 1.02f, LIST_VAL_DARK_ALPHA = 0.60f;
    public static final int LIST_PAD_X = 32, LIST_VAL_GAP = 14, LIST_SHADOW_Y = 12, LIST_SHADOW_BLUR = 32;
    public static final int TOGGLE_KNOB = 36, TOGGLE_INSET = 4, TOGGLE_MS = 160;
    public static final float TOGGLE_OFF_ALPHA = 0.22f;
    public static final int CTX_W = 520, CTX_PAD = 16, CTX_R = 28, CTX_ROW_H = 76, CTX_ROW_GAP = 8, CTX_ROW_R = 22,
        CTX_GAP = 24, CTX_IN_MS = 200;
    public static final float MOVE_SCALE_ADD = 0.06f, MOVE_OTHERS_ALPHA = 0.70f;
    public static final int DREAM_GLOW_R = 520;
    public static final float DREAM_GLOW_ALPHA = 0.30f, DREAM_TEXT_ALPHA = 0.92f;
    public static final int DREAM_FADE_MS = 1200;
    /** PLAN 0.11 screensaver glow colours. */
    public static final int[] DREAM_COLOURS = {0xFF3D8BFF, 0xFF8A5CF6, 0xFFE0567A, 0xFFF0A040, 0xFF3CC6A8};
}
