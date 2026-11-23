package io.github.rtzhang.nothingfullaod;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

public final class FullAodConfig {
    public static final String TAG = "FullAOD";

    public static final String KEY_ENABLED = "fullaod_enabled";
    public static final String KEY_FRONT_DIM = "fullaod_front_dim";
    public static final String KEY_WALLPAPER_DIM = "fullaod_wallpaper_dim";
    public static final String KEY_BRT_SCALE = "fullaod_brt_scale";
    public static final String KEY_BRT_MIN = "fullaod_brt_min";
    public static final String KEY_BRT_MAX = "fullaod_brt_max";
    public static final String KEY_KEEP_SHORTCUTS = "fullaod_keep_shortcuts";
    public static final String KEY_SHOW_WIDGETS = "fullaod_show_widgets";
    public static final String KEY_SHOW_NOTIFICATIONS = "fullaod_show_notifications";
    public static final String KEY_ADAPTIVE_DIM = "fullaod_adaptive_dim";
    public static final String KEY_DURATION_S = "fullaod_duration_s";
    public static final String KEY_SCHED_RULES = "fullaod_sched_rules";

    /**
     * Resolve the full-AOD timeout for NOW: scheduled rules first (HH:mm-HH:mm=SEC;...,
     * SEC=0 means "never switch", ranges crossing midnight are supported, equal
     * start/end = all day), then the default duration.
     */
    public static int resolveDurationS(Context ctx) {
        try {
            String rules = Settings.Global.getString(ctx.getContentResolver(), KEY_SCHED_RULES);
            if (rules != null && !rules.trim().isEmpty()) {
                java.util.Calendar now = java.util.Calendar.getInstance();
                int mins = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
                for (String part : rules.split(";")) {
                    String p = part.trim();
                    if (p.isEmpty()) continue;
                    int eq = p.indexOf('=');
                    int dash = p.indexOf('-');
                    if (eq < 0 || dash < 0 || dash > eq) continue;
                    String[] a = p.substring(0, dash).trim().split(":");
                    String[] b = p.substring(dash + 1, eq).trim().split(":");
                    int start = Integer.parseInt(a[0]) * 60 + Integer.parseInt(a[1]);
                    int end = Integer.parseInt(b[0]) * 60 + Integer.parseInt(b[1]);
                    boolean match;
                    if (start == end) match = true;
                    else if (start < end) match = (mins >= start && mins < end);
                    else match = (mins >= start || mins < end);
                    if (match) return Integer.parseInt(p.substring(eq + 1).trim());
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), KEY_DURATION_S, 0);
        } catch (Throwable t) {
            return 0;
        }
    }
    public static final String KEY_TIMED_OUT = "fullaod_timedout";

    private static volatile Context sCtx;

    public static boolean isTimedOut() {
        try {
            return sCtx != null && Settings.Global.getInt(sCtx.getContentResolver(), KEY_TIMED_OUT, 0) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setTimedOut(boolean v) {
        try {
            if (sCtx != null) Settings.Global.putInt(sCtx.getContentResolver(), KEY_TIMED_OUT, v ? 1 : 0);
        } catch (Throwable ignored) {
        }
    }

    public static final float DEFAULT_FRONT_DIM = 0.38f;
    public static final float DEFAULT_WALLPAPER_DIM = 0.25f;
    public static final float DEFAULT_BRT_SCALE = 0.40f;
    public static final float DEFAULT_BRT_MIN = 0.03f;
    public static final float DEFAULT_BRT_MAX = 0.36f;

    private static volatile long sLastRefreshMs = 0L;
    private static volatile boolean sEnabled = true;
    private static volatile float sFrontDim = DEFAULT_FRONT_DIM;
    private static volatile float sWallpaperDim = DEFAULT_WALLPAPER_DIM;
    private static volatile float sBrtScale = DEFAULT_BRT_SCALE;
    private static volatile float sBrtMin = DEFAULT_BRT_MIN;
    private static volatile float sBrtMax = DEFAULT_BRT_MAX;
    private static volatile boolean sKeepShortcuts = true;
    private static volatile boolean sShowWidgets = true;
    private static volatile boolean sShowNotifications = true;
    private static volatile boolean sAdaptiveDim = true;

    private FullAodConfig() {}

    public static void refreshIfNeeded(Context context) {
        if (context == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sLastRefreshMs < 1500L) return;
        sLastRefreshMs = now;
        try {
            sCtx = context.getApplicationContext();
            ContentResolver cr = context.getContentResolver();
            if (cr == null) return;
            sEnabled = Settings.Global.getInt(cr, KEY_ENABLED, 1) != 0;
            sFrontDim = clamp(Settings.Global.getFloat(cr, KEY_FRONT_DIM, DEFAULT_FRONT_DIM), 0.0f, 0.85f);
            sWallpaperDim = clamp(Settings.Global.getFloat(cr, KEY_WALLPAPER_DIM, DEFAULT_WALLPAPER_DIM), 0.0f, 0.85f);
            sBrtScale = clamp(Settings.Global.getFloat(cr, KEY_BRT_SCALE, DEFAULT_BRT_SCALE), 0.08f, 0.90f);
            sBrtMin = clamp(Settings.Global.getFloat(cr, KEY_BRT_MIN, DEFAULT_BRT_MIN), 0.01f, 1.0f);
            sBrtMax = clamp(Settings.Global.getFloat(cr, KEY_BRT_MAX, DEFAULT_BRT_MAX), sBrtMin + 0.01f, 2.0f);
            sKeepShortcuts = Settings.Global.getInt(cr, KEY_KEEP_SHORTCUTS, 1) != 0;
            sShowWidgets = Settings.Global.getInt(cr, KEY_SHOW_WIDGETS, 1) != 0;
            sShowNotifications = Settings.Global.getInt(cr, KEY_SHOW_NOTIFICATIONS, 1) != 0;
            sAdaptiveDim = Settings.Global.getInt(cr, KEY_ADAPTIVE_DIM, 1) != 0;
        } catch (Throwable ignored) {
        }
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    public static float getFrontDim() {
        return sFrontDim;
    }

    public static float getWallpaperDim() {
        return sWallpaperDim;
    }

    public static float getBrtScale() {
        return sBrtScale;
    }

    public static float getBrtMin() {
        return sBrtMin;
    }

    public static float getBrtMax() {
        return sBrtMax;
    }

    public static boolean isKeepShortcuts() {
        return sKeepShortcuts;
    }

    public static boolean isShowWidgets() {
        return sShowWidgets;
    }

    public static boolean isShowNotifications() {
        return sShowNotifications;
    }

    public static boolean isAdaptiveDim() {
        return sAdaptiveDim;
    }

    public static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v)) return lo;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
