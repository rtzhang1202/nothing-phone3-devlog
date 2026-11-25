package io.github.rtzhang.keypilot.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * KeyPilot Material 3 (M3) Design System — pure Android SDK implementation (zero Material library).
 *
 * Strict M3 token rules:
 * - Dynamic Monet palette (android.R.color.system_accent1_*, system_neutral1_*, system_neutral2_*)
 *   following system day/night mode automatically; hardcoded fallback only for error roles & API<31.
 * - Unified corner radius family: Card/Container = 24dp, Input/Dropdown = 26dp, Pill/Switch/Sheet = 28dp.
 * - Cards: 24dp rounded surfaceContainer + 12% onSurface Ripple, padding 20dp/16dp, gap 8dp.
 * - Pill buttons: 28dp rounded + Ripple + 14sp sans-serif-medium + minHeight 44dp + no caps/elevation.
 * - Custom M3 Switch: StateListDrawable track (ON=primary, OFF=surfaceContainerHigh + 2dp outline)
 *   + thumb (ON=onPrimary, OFF=onSurfaceVariant).
 */
public final class UiTheme {

    // Strict corner-radius family (dp)
    public static final int RADIUS_CARD = 24;
    public static final int RADIUS_INPUT = 26;
    public static final int RADIUS_PILL = 28;

    public static final class Palette {
        public final boolean isDark;
        public final int surface;
        public final int surfaceContainer;
        public final int surfaceContainerHigh;
        public final int primary;
        public final int onPrimary;
        public final int primaryContainer;
        public final int onPrimaryContainer;
        public final int onSurface;
        public final int onSurfaceVariant;
        public final int outline;
        public final int outlineVariant;
        public final int error;
        public final int onError;
        public final int errorContainer;
        public final int onErrorContainer;

        Palette(boolean isDark,
                int surface, int surfaceContainer, int surfaceContainerHigh,
                int primary, int onPrimary, int primaryContainer, int onPrimaryContainer,
                int onSurface, int onSurfaceVariant, int outline, int outlineVariant,
                int error, int onError, int errorContainer, int onErrorContainer) {
            this.isDark = isDark;
            this.surface = surface;
            this.surfaceContainer = surfaceContainer;
            this.surfaceContainerHigh = surfaceContainerHigh;
            this.primary = primary;
            this.onPrimary = onPrimary;
            this.primaryContainer = primaryContainer;
            this.onPrimaryContainer = onPrimaryContainer;
            this.onSurface = onSurface;
            this.onSurfaceVariant = onSurfaceVariant;
            this.outline = outline;
            this.outlineVariant = outlineVariant;
            this.error = error;
            this.onError = onError;
            this.errorContainer = errorContainer;
            this.onErrorContainer = onErrorContainer;
        }
    }

    private UiTheme() {
    }

    public static Palette colors(Context ctx) {
        boolean dark = (ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        if (Build.VERSION.SDK_INT >= 31) {
            try {
                if (dark) {
                    int n1000 = ctx.getColor(android.R.color.system_neutral1_1000);
                    int n900 = ctx.getColor(android.R.color.system_neutral1_900);
                    int n800 = ctx.getColor(android.R.color.system_neutral1_800);
                    int surface = blend(n1000, n900, 0.35f);            // M3 dark surface (~T6)
                    int surfaceContainer = blend(n900, n800, 0.25f);    // M3 surfaceContainer (~T12)
                    int surfaceContainerHigh = blend(n900, n800, 0.65f);// M3 surfaceContainerHigh (~T17)
                    return new Palette(
                            true,
                            surface,
                            surfaceContainer,
                            surfaceContainerHigh,
                            ctx.getColor(android.R.color.system_accent1_200),
                            ctx.getColor(android.R.color.system_accent1_800),
                            ctx.getColor(android.R.color.system_accent1_700),
                            ctx.getColor(android.R.color.system_accent1_100),
                            ctx.getColor(android.R.color.system_neutral1_100),
                            ctx.getColor(android.R.color.system_neutral2_200),
                            ctx.getColor(android.R.color.system_neutral2_400),
                            ctx.getColor(android.R.color.system_neutral2_700),
                            0xFFF2B8B5, 0xFF601410, 0xFF5C1916, 0xFFF9DEDC);
                } else {
                    int n10 = ctx.getColor(android.R.color.system_neutral1_10);
                    int n50 = ctx.getColor(android.R.color.system_neutral1_50);
                    int n100 = ctx.getColor(android.R.color.system_neutral1_100);
                    return new Palette(
                            false,
                            n10,
                            n50,
                            n100,
                            ctx.getColor(android.R.color.system_accent1_600),
                            ctx.getColor(android.R.color.system_accent1_0),
                            ctx.getColor(android.R.color.system_accent1_100),
                            ctx.getColor(android.R.color.system_accent1_900),
                            ctx.getColor(android.R.color.system_neutral1_900),
                            ctx.getColor(android.R.color.system_neutral2_700),
                            ctx.getColor(android.R.color.system_neutral2_500),
                            ctx.getColor(android.R.color.system_neutral2_200),
                            0xFFB3261E, 0xFFFFFFFF, 0xFFF9DEDC, 0xFF410E0B);
                }
            } catch (Throwable ignored) {
            }
        }

        // Fallback M3 baseline palette (API < 31)
        if (dark) {
            return new Palette(true,
                    0xFF121316, 0xFF1E2025, 0xFF282B31,
                    0xFFD0BCFF, 0xFF381E72, 0xFF4F378B, 0xFFEADDFF,
                    0xFFE6E1E5, 0xFFCAC4D0, 0xFF938F99, 0xFF49454F,
                    0xFFF2B8B5, 0xFF601410, 0xFF5C1916, 0xFFF9DEDC);
        } else {
            return new Palette(false,
                    0xFFFEF7FF, 0xFFF3EDF7, 0xFFE6E0E9,
                    0xFF6750A4, 0xFFFFFFFF, 0xFFEADDFF, 0xFF21005D,
                    0xFF1D1B20, 0xFF49454F, 0xFF79747E, 0xFFCAC4D0,
                    0xFFB3261E, 0xFFFFFFFF, 0xFFF9DEDC, 0xFF410E0B);
        }
    }

    public static int dp(Context ctx, int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics());
    }

    public static int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private static int blend(int c1, int c2, float ratio) {
        float inv = 1f - ratio;
        int r = Math.round(Color.red(c1) * inv + Color.red(c2) * ratio);
        int g = Math.round(Color.green(c1) * inv + Color.green(c2) * ratio);
        int b = Math.round(Color.blue(c1) * inv + Color.blue(c2) * ratio);
        return Color.rgb(r, g, b);
    }

    public static Typeface fontMedium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    public static Typeface fontRegular() {
        return Typeface.create("sans-serif", Typeface.NORMAL);
    }

    public static GradientDrawable roundedShape(Context ctx, int fillColor, int strokeColor,
                                                int strokeDp, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(dp(ctx, radiusDp));
        if (strokeColor != 0 && strokeDp > 0) {
            d.setStroke(dp(ctx, strokeDp), strokeColor);
        }
        return d;
    }

    public static Drawable rippleSurface(Context ctx, int fillColor, int strokeColor,
                                         int strokeDp, int radiusDp, int rippleBaseColor) {
        GradientDrawable content = roundedShape(ctx, fillColor, strokeColor, strokeDp, radiusDp);
        GradientDrawable mask = roundedShape(ctx, 0xFFFFFFFF, 0, 0, radiusDp);
        return new RippleDrawable(
                ColorStateList.valueOf(withAlpha(rippleBaseColor, 0.12f)),
                content,
                mask);
    }

    // -------------------------------------------------------------- M3 Cards

    /** M3 SurfaceContainer Card: 24dp radius, 20/16dp padding, 8dp topMargin. */
    public static LinearLayout makeCard(Context ctx) {
        return makeCard(ctx, false);
    }

    public static LinearLayout makeCard(Context ctx, boolean clickable) {
        Palette p = colors(ctx);
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        if (clickable) {
            card.setBackground(rippleSurface(ctx, p.surfaceContainer, 0, 0, RADIUS_CARD, p.onSurface));
            card.setClickable(true);
            card.setFocusable(true);
        } else {
            card.setBackground(roundedShape(ctx, p.surfaceContainer, 0, 0, RADIUS_CARD));
        }
        int ph = dp(ctx, 20);
        int pv = dp(ctx, 16);
        card.setPadding(ph, pv, ph, pv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 8);
        card.setLayoutParams(lp);
        return card;
    }

    /** M3 Elevated/Inner interactive row inside a card: 24dp radius, surfaceContainerHigh + Ripple. */
    public static LinearLayout makeInnerRow(Context ctx, boolean clickable) {
        Palette p = colors(ctx);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ctx, 56));
        if (clickable) {
            row.setBackground(rippleSurface(ctx, p.surfaceContainerHigh, 0, 0, RADIUS_CARD, p.onSurface));
            row.setClickable(true);
            row.setFocusable(true);
        } else {
            row.setBackground(roundedShape(ctx, p.surfaceContainerHigh, 0, 0, RADIUS_CARD));
        }
        int ph = dp(ctx, 16);
        int pv = dp(ctx, 12);
        row.setPadding(ph, pv, ph, pv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 8);
        row.setLayoutParams(lp);
        return row;
    }

    // ------------------------------------------------------------- M3 Typography

    public static TextView makeText(Context ctx, String text, float sp, boolean medium, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setTypeface(medium ? fontMedium() : fontRegular());
        return tv;
    }

    public static TextView makeSectionHeader(Context ctx, String text) {
        Palette p = colors(ctx);
        TextView tv = makeText(ctx, text, 16, true, p.onSurface);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 8);
        lp.bottomMargin = dp(ctx, 4);
        tv.setLayoutParams(lp);
        return tv;
    }

    public static TextView makeChip(Context ctx, String text, boolean accent) {
        Palette p = colors(ctx);
        TextView tv = makeText(ctx, text, 11, true, accent ? p.onPrimaryContainer : p.onSurfaceVariant);
        tv.setBackground(roundedShape(
                ctx,
                accent ? p.primaryContainer : p.surfaceContainerHigh,
                0, 0, RADIUS_PILL));
        tv.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4));
        tv.setSingleLine(true);
        return tv;
    }

    // ------------------------------------------------------------- M3 Buttons

    public enum ButtonStyle {
        FILLED,
        TONAL,
        OUTLINED,
        TEXT,
        TEXT_DANGER
    }

    /** M3 Pill Button: 28dp radius, Ripple, 14sp sans-serif-medium, no caps, minHeight 44dp. */
    public static TextView makeButton(Context ctx, String label, ButtonStyle style,
                                      View.OnClickListener listener) {
        Palette p = colors(ctx);
        TextView btn = new TextView(ctx);
        btn.setText(label);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btn.setTypeface(fontMedium());
        btn.setAllCaps(false);
        btn.setStateListAnimator(null);
        btn.setGravity(Gravity.CENTER);
        btn.setMinHeight(dp(ctx, 44));
        btn.setSingleLine(true);
        btn.setEllipsize(TextUtils.TruncateAt.END);

        int ph = (style == ButtonStyle.TEXT || style == ButtonStyle.TEXT_DANGER)
                ? dp(ctx, 12) : dp(ctx, 18);
        int pv = dp(ctx, 10);
        btn.setPadding(ph, pv, ph, pv);

        switch (style) {
            case FILLED:
                btn.setTextColor(p.onPrimary);
                btn.setBackground(rippleSurface(ctx, p.primary, 0, 0, RADIUS_PILL, p.onPrimary));
                break;
            case TONAL:
                btn.setTextColor(p.onPrimaryContainer);
                btn.setBackground(rippleSurface(ctx, p.primaryContainer, 0, 0, RADIUS_PILL, p.onPrimaryContainer));
                break;
            case OUTLINED:
                btn.setTextColor(p.primary);
                btn.setBackground(rippleSurface(ctx, Color.TRANSPARENT, p.outline, 1, RADIUS_PILL, p.primary));
                break;
            case TEXT_DANGER:
                btn.setTextColor(p.error);
                btn.setBackground(rippleSurface(ctx, Color.TRANSPARENT, 0, 0, RADIUS_PILL, p.error));
                break;
            case TEXT:
            default:
                btn.setTextColor(p.primary);
                btn.setBackground(rippleSurface(ctx, Color.TRANSPARENT, 0, 0, RADIUS_PILL, p.primary));
                break;
        }
        btn.setClickable(true);
        btn.setFocusable(true);
        if (listener != null) {
            btn.setOnClickListener(listener);
        }
        return btn;
    }

    // -------------------------------------------------------------- M3 Switch

    /**
     * Zero-padding circular thumb drawable for Android's {@link Switch}.
     * Avoids InsetDrawable's non-zero getPadding() which Switch misinterprets as optical
     * bounds (shrinking the track height and making the thumb protrude past the track edge).
     * Exact M3 spec: 52dp x 32dp track; 24dp ON thumb centered at right semicircle (16dp from
     * right edge); 16dp OFF thumb centered at left semicircle (16dp from left edge).
     */
    private static final class ThumbCircleDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int boxW;
        private final int boxH;
        private final float radiusPx;
        private final float edgeCenterOffsetPx;
        private final boolean checkedState;

        ThumbCircleDrawable(int boxW, int boxH, float diameterPx, float edgeCenterOffsetPx,
                            boolean checkedState, int color) {
            this.boxW = boxW;
            this.boxH = boxH;
            this.radiusPx = diameterPx * 0.5f;
            this.edgeCenterOffsetPx = edgeCenterOffsetPx;
            this.checkedState = checkedState;
            this.paint.setColor(color);
            this.paint.setStyle(Paint.Style.FILL);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = checkedState
                    ? (b.right - edgeCenterOffsetPx)
                    : (b.left + edgeCenterOffsetPx);
            float cy = b.exactCenterY();
            canvas.drawCircle(cx, cy, radiusPx, paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return boxW;
        }

        @Override
        public int getIntrinsicHeight() {
            return boxH;
        }

        @Override
        public boolean getPadding(Rect padding) {
            padding.set(0, 0, 0, 0);
            return false;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * M3 Switch using StateListDrawable:
     * - Track: 52dp x 32dp, ON = primary, OFF = surfaceContainerHigh + 2dp outline stroke
     * - Thumb: ON = onPrimary (24dp, concentric with right semicircle), OFF = onSurfaceVariant (16dp, concentric with left semicircle)
     * Right padding = 0 so trailing switches align cleanly with card right content edge.
     */
    public static Switch makeSwitch(Context ctx) {
        Palette p = colors(ctx);
        Switch sw = new Switch(ctx);
        sw.setShowText(false);
        sw.setText("");
        sw.setMinHeight(dp(ctx, 44));
        sw.setSwitchMinWidth(dp(ctx, 52));
        sw.setPadding(dp(ctx, 8), 0, 0, 0);

        int trackW = dp(ctx, 52);
        int trackH = dp(ctx, 32);
        int halfTrackH = dp(ctx, 16);

        // Track StateListDrawable (52dp x 32dp)
        GradientDrawable trackOn = new GradientDrawable();
        trackOn.setShape(GradientDrawable.RECTANGLE);
        trackOn.setCornerRadius(halfTrackH);
        trackOn.setColor(p.primary);
        trackOn.setSize(trackW, trackH);

        GradientDrawable trackOff = new GradientDrawable();
        trackOff.setShape(GradientDrawable.RECTANGLE);
        trackOff.setCornerRadius(halfTrackH);
        trackOff.setColor(p.surfaceContainerHigh);
        trackOff.setStroke(dp(ctx, 2), p.outline);
        trackOff.setSize(trackW, trackH);

        StateListDrawable track = new StateListDrawable();
        track.addState(new int[]{android.R.attr.state_checked}, trackOn);
        track.addState(new int[]{}, trackOff);

        // Thumb StateListDrawable: 26dp x 32dp box (2 * 26dp == 52dp track width!), zero padding
        int thumbBoxW = dp(ctx, 26);
        Drawable thumbOn = new ThumbCircleDrawable(
                thumbBoxW, trackH, dp(ctx, 24), halfTrackH, true, p.onPrimary);
        Drawable thumbOff = new ThumbCircleDrawable(
                thumbBoxW, trackH, dp(ctx, 16), halfTrackH, false, p.onSurfaceVariant);

        StateListDrawable thumb = new StateListDrawable();
        thumb.addState(new int[]{android.R.attr.state_checked}, thumbOn);
        thumb.addState(new int[]{}, thumbOff);

        sw.setTrackDrawable(track);
        sw.setThumbDrawable(thumb);
        sw.setThumbTextPadding(0);
        sw.setTrackTintList(null);
        sw.setThumbTintList(null);
        return sw;
    }

    // -------------------------------------------------------------- M3 Inputs

    /** M3 Input field: 26dp corner radius, surfaceContainerHigh fill + 1dp outlineVariant stroke. */
    public static EditText makeInput(Context ctx, String hint, String value) {
        Palette p = colors(ctx);
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setText(value != null ? value : "");
        et.setTextColor(p.onSurface);
        et.setHintTextColor(p.onSurfaceVariant);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        et.setTypeface(fontRegular());
        et.setMinHeight(dp(ctx, 48));
        et.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12));
        et.setBackground(roundedShape(ctx, p.surfaceContainerHigh, p.outlineVariant, 1, RADIUS_INPUT));
        et.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 8);
        et.setLayoutParams(lp);
        return et;
    }

    /** M3 Dropdown selector pill (replaces ugly raw Android Spinner): 26dp radius + trailing chevron. */
    public static LinearLayout makeDropdownField(Context ctx,
                                                 String label,
                                                 String initialValue,
                                                 View.OnClickListener onClick) {
        Palette p = colors(ctx);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ctx, 50));
        row.setBackground(rippleSurface(ctx, p.surfaceContainerHigh, p.outlineVariant, 1, RADIUS_INPUT, p.onSurface));
        row.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 8);
        row.setLayoutParams(lp);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(makeText(ctx, label, 11, false, p.onSurfaceVariant));
        TextView valTv = makeText(ctx, initialValue, 14, true, p.onSurface);
        valTv.setTag("dropdown_value");
        col.addView(valTv);
        row.addView(col);

        TextView chev = makeText(ctx, "▾", 16, true, p.onSurfaceVariant);
        chev.setPadding(dp(ctx, 8), 0, 0, 0);
        row.addView(chev);
        return row;
    }

    public static void setDropdownValue(View dropdownRow, String value) {
        View v = dropdownRow.findViewWithTag("dropdown_value");
        if (v instanceof TextView) {
            ((TextView) v).setText(value);
        }
    }

    public static View makeDivider(Context ctx) {
        Palette p = colors(ctx);
        View v = new View(ctx);
        v.setBackgroundColor(withAlpha(p.outlineVariant, 0.6f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 1)));
        lp.topMargin = dp(ctx, 8);
        lp.bottomMargin = dp(ctx, 8);
        v.setLayoutParams(lp);
        return v;
    }

    public static View makeListDivider(Context ctx) {
        Palette p = colors(ctx);
        View v = new View(ctx);
        v.setBackgroundColor(withAlpha(p.outlineVariant, 0.5f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 1)));
        lp.leftMargin = dp(ctx, 56);
        v.setLayoutParams(lp);
        return v;
    }

    // ------------------------------------------------------- M3 Bottom Sheet

    /** Deterministic max-height ScrollView so BottomSheet footers are NEVER clipped off-screen. */
    private static final class MaxHeightScrollView extends ScrollView {
        private final int maxHeightPx;

        MaxHeightScrollView(Context ctx, int maxHeightPx) {
            super(ctx);
            this.maxHeightPx = maxHeightPx;
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int capped = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST);
            super.onMeasure(widthMeasureSpec, capped);
        }
    }

    public static final class BottomSheet {
        public final Dialog dialog;
        public final LinearLayout body;
        public final LinearLayout footer;

        BottomSheet(Dialog dialog, LinearLayout body, LinearLayout footer) {
            this.dialog = dialog;
            this.body = body;
            this.footer = footer;
        }

        public void show() {
            dialog.show();
            Window w = dialog.getWindow();
            if (w != null) {
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                if (Build.VERSION.SDK_INT >= 30) {
                    w.setDecorFitsSystemWindows(false);
                    WindowInsetsController ctrl = w.getInsetsController();
                    if (ctrl != null) {
                        Palette p = colors(dialog.getContext());
                        int mask = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                        ctrl.setSystemBarsAppearance(p.isDark ? 0 : mask, mask);
                    }
                }
                w.setNavigationBarColor(Color.TRANSPARENT);
                w.setStatusBarColor(Color.TRANSPARENT);
                View decor = w.getDecorView();
                if (decor != null) {
                    decor.setPadding(0, 0, 0, 0);
                }
            }
        }

        public void dismiss() {
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Unified M3 Modal Bottom Sheet:
     * - Top 28dp rounded corners, surfaceContainer background, flush with bottom edge
     * - Top drag handle pill (36dp x 4dp)
     * - Headline + optional supporting text
     * - Deterministic MaxHeightScrollView (60% screen height) so footer buttons are ALWAYS visible
     * - Right-aligned M3 button bar
     */
    public static BottomSheet createBottomSheet(Context ctx, String title, String subtitle,
                                                boolean scrollableBody) {
        Palette p = colors(ctx);
        final Dialog dlg = new Dialog(ctx, android.R.style.Theme_DeviceDefault_NoActionBar);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            if (Build.VERSION.SDK_INT >= 30) {
                w.setDecorFitsSystemWindows(false);
            }
        }

        // Full-screen scrim host so sheet docks 100% flush at screen bottom (behind gesture bar)
        FrameLayout scrim = new FrameLayout(ctx);
        scrim.setBackgroundColor(Color.argb(130, 0, 0, 0));
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
            }
        });

        final LinearLayout sheet = new LinearLayout(ctx);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(p.surfaceContainer);
        float r = dp(ctx, RADIUS_PILL);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(bg);
        final int ph = dp(ctx, 20);
        final int pt = dp(ctx, 12);
        final int pbBase = dp(ctx, 28);
        sheet.setPadding(ph, pt, ph, pbBase);
        scrim.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int navBot = insets.getSystemWindowInsetBottom();
                sheet.setPadding(ph, pt, ph, Math.max(pbBase, navBot + dp(ctx, 16)));
                return insets;
            }
        });

        // Drag handle pill
        View handle = new View(ctx);
        handle.setBackground(roundedShape(ctx, p.outlineVariant, 0, 0, RADIUS_PILL));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 4));
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        hLp.bottomMargin = dp(ctx, 14);
        sheet.addView(handle, hLp);

        // Title & subtitle
        TextView titleTv = makeText(ctx, title, 20, true, p.onSurface);
        sheet.addView(titleTv);
        if (subtitle != null && !subtitle.trim().isEmpty()) {
            TextView subTv = makeText(ctx, subtitle, 13, false, p.onSurfaceVariant);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sLp.topMargin = dp(ctx, 4);
            sheet.addView(subTv, sLp);
        }

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);

        int maxBodyH = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.58f);
        if (scrollableBody) {
            MaxHeightScrollView sv = new MaxHeightScrollView(ctx, maxBodyH);
            sv.addView(body, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            svLp.topMargin = dp(ctx, 12);
            sheet.addView(sv, svLp);
        } else {
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bLp.topMargin = dp(ctx, 12);
            sheet.addView(body, bLp);
        }

        // Right-aligned footer bar (always visible below MaxHeightScrollView)
        LinearLayout footer = new LinearLayout(ctx);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fLp.topMargin = dp(ctx, 14);
        footer.setLayoutParams(fLp);
        sheet.addView(footer);

        scrim.addView(sheet, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        dlg.setContentView(scrim, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return new BottomSheet(dlg, body, footer);
    }

    /** Convenience M3 single-choice option sheet replacing raw AlertDialog/Spinner. */
    public interface OnOptionPicked {
        void onPicked(int index);
    }

    public static void showOptionSheet(Context ctx, String title, String subtitle,
                                       String[] labels, int selectedIndex,
                                       final OnOptionPicked cb) {
        final BottomSheet sheet = createBottomSheet(ctx, title, subtitle, true);
        Palette p = colors(ctx);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean sel = (i == selectedIndex);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(ctx, 50));
            row.setBackground(rippleSurface(
                    ctx,
                    sel ? p.primaryContainer : p.surfaceContainerHigh,
                    0, 0, RADIUS_CARD, p.onSurface));
            row.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(ctx, 8);
            row.setLayoutParams(rlp);

            TextView tv = makeText(ctx, labels[i], 14, sel,
                    sel ? p.onPrimaryContainer : p.onSurface);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tv);
            if (sel) {
                row.addView(makeText(ctx, "✓", 16, true, p.onPrimaryContainer));
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sheet.dismiss();
                    if (cb != null) cb.onPicked(idx);
                }
            });
            sheet.body.addView(row);
        }
        sheet.footer.addView(makeButton(ctx, "取消", ButtonStyle.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sheet.dismiss();
            }
        }));
        sheet.show();
    }

    /** Convenience M3 confirmation sheet replacing raw AlertDialog. */
    public static void showConfirmSheet(Context ctx, String title, String message,
                                        String confirmLabel, boolean danger,
                                        final Runnable onConfirm) {
        final BottomSheet sheet = createBottomSheet(ctx, title, null, true);
        Palette p = colors(ctx);
        TextView msgTv = makeText(ctx, message, 14, false, p.onSurfaceVariant);
        msgTv.setLineSpacing(dp(ctx, 3), 1f);
        sheet.body.addView(msgTv);

        TextView cancelBtn = makeButton(ctx, "取消", ButtonStyle.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sheet.dismiss();
            }
        });
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.rightMargin = dp(ctx, 8);
        sheet.footer.addView(cancelBtn, cLp);

        TextView okBtn = makeButton(ctx, confirmLabel,
                danger ? ButtonStyle.TEXT_DANGER : ButtonStyle.FILLED,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sheet.dismiss();
                        if (onConfirm != null) onConfirm.run();
                    }
                });
        sheet.footer.addView(okBtn);
        sheet.show();
    }
}
