package io.github.rtzhang.keypilot.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import io.github.rtzhang.keypilot.config.ActionSpec;
import io.github.rtzhang.keypilot.config.ActionType;
import io.github.rtzhang.keypilot.config.SceneTemplates;

/**
 * Crisp Canvas-drawn Material 3 semantic vector icons inside tonal rounded badges.
 * Eliminates blurry emojis/Unicode inconsistencies and gives every ActionType a clear glyph.
 */
public final class ActionIcons {

    private ActionIcons() {
    }

    /**
     * Resolves the best icon for a bound ActionSpec:
     * - If bound to a concrete app (LAUNCH_APP, CAMERA with package, DEEP_LINK with package,
     *   or IN_APP_PRESET like Alipay/WeChat), returns the real rounded App icon when installed!
     * - Otherwise returns a crisp M3 semantic vector badge.
     */
    public static Drawable forActionSpec(Context ctx, ActionSpec spec, int sizeDp) {
        if (spec == null) {
            return forActionType(ctx, ActionType.SYSTEM, sizeDp, false);
        }
        String pkg = resolveOwnerPackage(spec);
        if (pkg != null) {
            Drawable appIcon = AppIconCache.getRoundedAppIcon(ctx, pkg, sizeDp);
            if (appIcon != null) {
                return appIcon;
            }
        }
        boolean accent = !spec.isSystem() && !spec.isInherit();
        return forActionType(ctx, spec.type, sizeDp, accent);
    }

    /** Resolves owning package name for an ActionSpec (for showing app icon + app name). */
    public static String resolveOwnerPackage(ActionSpec spec) {
        if (spec == null) return null;
        if (spec.packageName != null && !spec.packageName.isEmpty()) {
            return spec.packageName;
        }
        if (spec.type == ActionType.IN_APP_PRESET && spec.presetId != null) {
            SceneTemplates.BuiltInPreset p = SceneTemplates.findPreset(spec.presetId);
            if (p != null && p.packageName != null) return p.packageName;
            if ("camera_shutter".equals(spec.presetId)) return "com.nothing.camera";
        }
        if (spec.type == ActionType.CAMERA) {
            return "com.nothing.camera";
        }
        return null;
    }

    public static Drawable forActionType(Context ctx, ActionType type, int sizeDp, boolean accent) {
        UiTheme.Palette p = UiTheme.colors(ctx);
        int bg = accent ? p.primaryContainer : p.surfaceContainerHigh;
        int fg = accent ? p.onPrimaryContainer : p.onSurfaceVariant;
        return new VectorBadgeDrawable(type != null ? type : ActionType.SYSTEM,
                bg, fg, UiTheme.dp(ctx, sizeDp));
    }

    public static Drawable makeInfoBadge(Context ctx, String glyphType, int bgColor, int fgColor, int sizeDp) {
        ActionType mapped = ActionType.SYSTEM;
        if ("warn".equals(glyphType)) mapped = ActionType.NONE;
        else if ("camera".equals(glyphType)) mapped = ActionType.CAMERA;
        else if ("key".equals(glyphType)) mapped = ActionType.KEY_INJECT;
        return new VectorBadgeDrawable(mapped, bgColor, fgColor, UiTheme.dp(ctx, sizeDp));
    }

    private static final class VectorBadgeDrawable extends Drawable {
        private final ActionType type;
        private final int sizePx;
        private final Paint bgPaint;
        private final Paint strokePaint;
        private final Paint fillPaint;

        VectorBadgeDrawable(ActionType type, int bgColor, int fgColor, int sizePx) {
            this.type = type;
            this.sizePx = sizePx;
            this.bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            this.bgPaint.setColor(bgColor);
            this.bgPaint.setStyle(Paint.Style.FILL);

            this.strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            this.strokePaint.setColor(fgColor);
            this.strokePaint.setStyle(Paint.Style.STROKE);
            this.strokePaint.setStrokeCap(Paint.Cap.ROUND);
            this.strokePaint.setStrokeJoin(Paint.Join.ROUND);

            this.fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            this.fillPaint.setColor(fgColor);
            this.fillPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        public int getIntrinsicWidth() {
            return sizePx;
        }

        @Override
        public int getIntrinsicHeight() {
            return sizePx;
        }

        @Override
        public void draw(Canvas c) {
            RectF b = new RectF(getBounds());
            float s = Math.min(b.width(), b.height());
            float cx = b.centerX();
            float cy = b.centerY();
            float r = s * 0.5f;
            c.drawRoundRect(new RectF(cx - r, cy - r, cx + r, cy + r), s * 0.28f, s * 0.28f, bgPaint);

            strokePaint.setStrokeWidth(Math.max(2f, s * 0.068f));
            float u = s * 0.24f; // icon half-span

            switch (type) {
                case CAMERA:
                    c.drawRoundRect(new RectF(cx - u, cy - u * 0.7f, cx + u, cy + u * 0.8f),
                            u * 0.25f, u * 0.25f, strokePaint);
                    c.drawCircle(cx, cy + u * 0.06f, u * 0.38f, strokePaint);
                    c.drawLine(cx - u * 0.35f, cy - u * 0.7f, cx - u * 0.2f, cy - u * 0.95f, strokePaint);
                    c.drawLine(cx - u * 0.2f, cy - u * 0.95f, cx + u * 0.2f, cy - u * 0.95f, strokePaint);
                    c.drawLine(cx + u * 0.2f, cy - u * 0.95f, cx + u * 0.35f, cy - u * 0.7f, strokePaint);
                    break;
                case CAMERA_SHUTTER:
                    c.drawCircle(cx, cy, u * 0.95f, strokePaint);
                    c.drawCircle(cx, cy, u * 0.55f, fillPaint);
                    break;
                case FLASHLIGHT: {
                    Path p = new Path();
                    p.moveTo(cx + u * 0.1f, cy - u);
                    p.lineTo(cx - u * 0.55f, cy + u * 0.08f);
                    p.lineTo(cx + u * 0.02f, cy + u * 0.08f);
                    p.lineTo(cx - u * 0.12f, cy + u);
                    p.lineTo(cx + u * 0.55f, cy - u * 0.08f);
                    p.lineTo(cx - u * 0.02f, cy - u * 0.08f);
                    p.close();
                    c.drawPath(p, fillPaint);
                    break;
                }
                case SCREENSHOT:
                case PARTIAL_SCREENSHOT: {
                    float d = u * 0.85f;
                    float len = u * 0.45f;
                    // 4 corners
                    c.drawLine(cx - d, cy - d, cx - d + len, cy - d, strokePaint);
                    c.drawLine(cx - d, cy - d, cx - d, cy - d + len, strokePaint);
                    c.drawLine(cx + d, cy - d, cx + d - len, cy - d, strokePaint);
                    c.drawLine(cx + d, cy - d, cx + d, cy - d + len, strokePaint);
                    c.drawLine(cx - d, cy + d, cx - d + len, cy + d, strokePaint);
                    c.drawLine(cx - d, cy + d, cx - d, cy + d - len, strokePaint);
                    c.drawLine(cx + d, cy + d, cx + d - len, cy + d, strokePaint);
                    c.drawLine(cx + d, cy + d, cx + d, cy + d - len, strokePaint);
                    if (type == ActionType.PARTIAL_SCREENSHOT) {
                        c.drawCircle(cx, cy, u * 0.2f, fillPaint);
                    }
                    break;
                }
                case MEDIA_PLAY_PAUSE:
                case MEDIA_TRANSPORT: {
                    Path tri = new Path();
                    tri.moveTo(cx - u * 0.65f, cy - u * 0.7f);
                    tri.lineTo(cx + u * 0.2f, cy);
                    tri.lineTo(cx - u * 0.65f, cy + u * 0.7f);
                    tri.close();
                    c.drawPath(tri, fillPaint);
                    c.drawLine(cx + u * 0.5f, cy - u * 0.65f, cx + u * 0.5f, cy + u * 0.65f, strokePaint);
                    break;
                }
                case MEDIA_NEXT: {
                    Path tri = new Path();
                    tri.moveTo(cx - u * 0.7f, cy - u * 0.65f);
                    tri.lineTo(cx + u * 0.25f, cy);
                    tri.lineTo(cx - u * 0.7f, cy + u * 0.65f);
                    tri.close();
                    c.drawPath(tri, fillPaint);
                    c.drawLine(cx + u * 0.55f, cy - u * 0.65f, cx + u * 0.55f, cy + u * 0.65f, strokePaint);
                    break;
                }
                case MEDIA_PREV: {
                    Path tri = new Path();
                    tri.moveTo(cx + u * 0.7f, cy - u * 0.65f);
                    tri.lineTo(cx - u * 0.25f, cy);
                    tri.lineTo(cx + u * 0.7f, cy + u * 0.65f);
                    tri.close();
                    c.drawPath(tri, fillPaint);
                    c.drawLine(cx - u * 0.55f, cy - u * 0.65f, cx - u * 0.55f, cy + u * 0.65f, strokePaint);
                    break;
                }
                case IN_APP_PRESET: {
                    // QR Scan viewfinder + horizontal scan bar
                    float d = u * 0.85f;
                    float len = u * 0.4f;
                    c.drawLine(cx - d, cy - d, cx - d + len, cy - d, strokePaint);
                    c.drawLine(cx - d, cy - d, cx - d, cy - d + len, strokePaint);
                    c.drawLine(cx + d, cy - d, cx + d - len, cy - d, strokePaint);
                    c.drawLine(cx + d, cy - d, cx + d, cy - d + len, strokePaint);
                    c.drawLine(cx - d, cy + d, cx - d + len, cy + d, strokePaint);
                    c.drawLine(cx - d, cy + d, cx - d, cy + d - len, strokePaint);
                    c.drawLine(cx + d, cy + d, cx + d - len, cy + d, strokePaint);
                    c.drawLine(cx + d, cy + d, cx + d, cy + d - len, strokePaint);
                    c.drawLine(cx - d * 0.85f, cy, cx + d * 0.85f, cy, strokePaint);
                    break;
                }
                case DEEP_LINK:
                    c.drawRoundRect(new RectF(cx - u, cy - u * 0.35f, cx + u * 0.1f, cy + u * 0.35f),
                            u * 0.35f, u * 0.35f, strokePaint);
                    c.drawRoundRect(new RectF(cx - u * 0.1f, cy - u * 0.35f, cx + u, cy + u * 0.35f),
                            u * 0.35f, u * 0.35f, strokePaint);
                    break;
                case LAUNCH_APP:
                case LAUNCH_ACTIVITY: {
                    float g = u * 0.45f;
                    float box = u * 0.34f;
                    c.drawRoundRect(new RectF(cx - g - box, cy - g - box, cx - g + box, cy - g + box), 4, 4, fillPaint);
                    c.drawRoundRect(new RectF(cx + g - box, cy - g - box, cx + g + box, cy - g + box), 4, 4, fillPaint);
                    c.drawRoundRect(new RectF(cx - g - box, cy + g - box, cx - g + box, cy + g + box), 4, 4, fillPaint);
                    c.drawRoundRect(new RectF(cx + g - box, cy + g - box, cx + g + box, cy + g + box), 4, 4, strokePaint);
                    break;
                }
                case NONE:
                    c.drawCircle(cx, cy, u * 0.85f, strokePaint);
                    c.drawLine(cx - u * 0.58f, cy - u * 0.58f, cx + u * 0.58f, cy + u * 0.58f, strokePaint);
                    break;
                case INHERIT:
                    c.drawLine(cx, cy + u * 0.75f, cx, cy - u * 0.65f, strokePaint);
                    c.drawLine(cx - u * 0.5f, cy - u * 0.15f, cx, cy - u * 0.65f, strokePaint);
                    c.drawLine(cx + u * 0.5f, cy - u * 0.15f, cx, cy - u * 0.65f, strokePaint);
                    break;
                case RINGER_CYCLE:
                    c.drawArc(new RectF(cx - u * 0.65f, cy - u * 0.85f, cx + u * 0.65f, cy + u * 0.55f),
                            180, 180, false, strokePaint);
                    c.drawLine(cx - u * 0.65f, cy - u * 0.15f, cx - u * 0.78f, cy + u * 0.55f, strokePaint);
                    c.drawLine(cx + u * 0.65f, cy - u * 0.15f, cx + u * 0.78f, cy + u * 0.55f, strokePaint);
                    c.drawLine(cx - u * 0.85f, cy + u * 0.55f, cx + u * 0.85f, cy + u * 0.55f, strokePaint);
                    c.drawCircle(cx, cy + u * 0.82f, u * 0.14f, fillPaint);
                    break;
                case LOCK_SCREEN:
                    c.drawRoundRect(new RectF(cx - u * 0.7f, cy - u * 0.1f, cx + u * 0.7f, cy + u * 0.9f),
                            u * 0.2f, u * 0.2f, strokePaint);
                    c.drawArc(new RectF(cx - u * 0.42f, cy - u * 0.9f, cx + u * 0.42f, cy + u * 0.1f),
                            180, 180, false, strokePaint);
                    break;
                case KEY_INJECT:
                    c.drawRoundRect(new RectF(cx - u * 0.9f, cy - u * 0.7f, cx + u * 0.9f, cy + u * 0.7f),
                            u * 0.25f, u * 0.25f, strokePaint);
                    c.drawLine(cx - u * 0.4f, cy, cx + u * 0.4f, cy, strokePaint);
                    break;
                case VOLUME_UP:
                case VOLUME_DOWN:
                    c.drawLine(cx - u * 0.65f, cy, cx + u * 0.65f, cy, strokePaint);
                    if (type == ActionType.VOLUME_UP) {
                        c.drawLine(cx, cy - u * 0.65f, cx, cy + u * 0.65f, strokePaint);
                    }
                    break;
                case EXPAND_NOTIFICATIONS:
                case EXPAND_QS:
                    c.drawLine(cx - u * 0.65f, cy - u * 0.25f, cx, cy + u * 0.4f, strokePaint);
                    c.drawLine(cx + u * 0.65f, cy - u * 0.25f, cx, cy + u * 0.4f, strokePaint);
                    if (type == ActionType.EXPAND_QS) {
                        c.drawLine(cx - u * 0.65f, cy - u * 0.7f, cx + u * 0.65f, cy - u * 0.7f, strokePaint);
                    }
                    break;
                case MIC_MUTE_PTT:
                case VOICE_ASSISTANT:
                    c.drawRoundRect(new RectF(cx - u * 0.35f, cy - u * 0.9f, cx + u * 0.35f, cy + u * 0.2f),
                            u * 0.35f, u * 0.35f, strokePaint);
                    c.drawArc(new RectF(cx - u * 0.68f, cy - u * 0.3f, cx + u * 0.68f, cy + u * 0.6f),
                            0, 180, false, strokePaint);
                    c.drawLine(cx, cy + u * 0.6f, cx, cy + u * 0.95f, strokePaint);
                    break;
                case NAV_BACK:
                    c.drawLine(cx + u * 0.6f, cy, cx - u * 0.6f, cy, strokePaint);
                    c.drawLine(cx - u * 0.1f, cy - u * 0.55f, cx - u * 0.6f, cy, strokePaint);
                    c.drawLine(cx - u * 0.1f, cy + u * 0.55f, cx - u * 0.6f, cy, strokePaint);
                    break;
                case NAV_HOME:
                    c.drawCircle(cx, cy, u * 0.65f, strokePaint);
                    break;
                case NAV_RECENTS:
                    c.drawRoundRect(new RectF(cx - u * 0.65f, cy - u * 0.65f, cx + u * 0.65f, cy + u * 0.65f),
                            u * 0.2f, u * 0.2f, strokePaint);
                    break;
                case OPEN_SETTINGS:
                    c.drawCircle(cx, cy, u * 0.78f, strokePaint);
                    c.drawCircle(cx, cy, u * 0.28f, fillPaint);
                    break;
                case CUSTOM_INTENT:
                case CONTEXT_VAR_BROADCAST:
                    c.drawCircle(cx, cy, u * 0.22f, fillPaint);
                    c.drawArc(new RectF(cx - u * 0.6f, cy - u * 0.6f, cx + u * 0.6f, cy + u * 0.6f),
                            -45, 90, false, strokePaint);
                    c.drawArc(new RectF(cx - u * 0.6f, cy - u * 0.6f, cx + u * 0.6f, cy + u * 0.6f),
                            135, 90, false, strokePaint);
                    break;
                case SYSTEM:
                default:
                    c.drawRoundRect(new RectF(cx - u * 0.55f, cy - u * 0.85f, cx + u * 0.55f, cy + u * 0.85f),
                            u * 0.22f, u * 0.22f, strokePaint);
                    c.drawLine(cx - u * 0.18f, cy + u * 0.55f, cx + u * 0.18f, cy + u * 0.55f, strokePaint);
                    break;
            }
        }

        @Override
        public void setAlpha(int alpha) {
            bgPaint.setAlpha(alpha);
            strokePaint.setAlpha(alpha);
            fillPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            bgPaint.setColorFilter(colorFilter);
            strokePaint.setColorFilter(colorFilter);
            fillPaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
