package io.github.rtzhang.keypilot.ui;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

/**
 * Cached app label + crisp rounded-clipped icon loader using PackageManager.
 * Ensures every app icon in lists, profile cards, and gesture rows has uniform
 * Material rounded corners and real app names.
 */
public final class AppIconCache {

    private static final LruCache<String, Bitmap> sIconCache = new LruCache<>(96);
    private static final LruCache<String, String> sLabelCache = new LruCache<>(160);

    private AppIconCache() {
    }

    /** Returns localized human app name for packageName, or fallback cleanly if uninstalled/wildcard. */
    public static String getAppName(Context ctx, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return "";
        }
        String pkg = packageName.trim();
        if ("*".equals(pkg)) {
            return "任意应用";
        }
        String cleanPkg = pkg.endsWith("*") ? pkg.substring(0, pkg.length() - 1) : pkg;
        String cached = sLabelCache.get(cleanPkg);
        if (cached != null) {
            return pkg.endsWith("*") ? cached + "*" : cached;
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(cleanPkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null && cs.length() > 0) {
                String lbl = cs.toString();
                sLabelCache.put(cleanPkg, lbl);
                return pkg.endsWith("*") ? lbl + "*" : lbl;
            }
        } catch (Throwable ignored) {
        }
        // Friendly fallback names for common packages if not installed
        if (cleanPkg.contains("GoogleCamera")) return "Google Camera";
        if (cleanPkg.contains("AlipayGphone")) return "支付宝";
        if (cleanPkg.equals("com.tencent.mm")) return "微信";
        if (cleanPkg.contains("nothing.camera")) return "Nothing Camera";
        if (cleanPkg.contains("opencamera")) return "Open Camera";
        if (cleanPkg.contains("weread")) return "微信读书";
        if (cleanPkg.contains("koreader")) return "KOReader";
        if (cleanPkg.contains("cloudmusic")) return "网易云音乐";
        if (cleanPkg.contains("qqmusic")) return "QQ 音乐";
        if (cleanPkg.contains("spotify")) return "Spotify";
        if (cleanPkg.contains("Yuanshen")) return "原神";
        if (cleanPkg.contains("sgame")) return "王者荣耀";
        if (cleanPkg.contains("pubgmhd")) return "和平精英";
        return pkg;
    }

    /** Returns a rounded-corner BitmapDrawable of sizeDp x sizeDp, or null if app not installed. */
    public static Drawable getRoundedAppIcon(Context ctx, String packageName, int sizeDp) {
        if (ctx == null || packageName == null || packageName.trim().isEmpty()) {
            return null;
        }
        String pkg = packageName.trim();
        if ("*".equals(pkg)) return null;
        if (pkg.endsWith("*")) pkg = pkg.substring(0, pkg.length() - 1);

        String cacheKey = pkg + "@" + sizeDp;
        Bitmap cached = sIconCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            return new BitmapDrawable(ctx.getResources(), cached);
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            Drawable raw = pm.getApplicationIcon(pkg);
            if (raw == null) return null;
            Bitmap bmp = renderRounded(raw, UiTheme.dp(ctx, sizeDp));
            sIconCache.put(cacheKey, bmp);
            return new BitmapDrawable(ctx.getResources(), bmp);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Bitmap renderRounded(Drawable src, int sizePx) {
        int s = Math.max(24, sizePx);
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Path clip = new Path();
        float r = s * 0.24f; // Clean squircle-like rounded corner
        clip.addRoundRect(new RectF(0, 0, s, s), r, r, Path.Direction.CW);
        c.clipPath(clip);
        src.setBounds(0, 0, s, s);
        src.draw(c);
        return out;
    }
}
