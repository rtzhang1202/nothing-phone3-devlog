package io.github.rtzhang.nothingfullaod;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

/**
 * Material Design 3 (Material You) settings screen.
 * - Dynamic color: system_accent / system_neutral palette (wallpaper-derived on Android 12+)
 * - Localization: strings resources, follows system language (en default / zh-CN)
 */
public class MainActivity extends Activity {

    private static final String[] KEYS = {
            FullAodConfig.KEY_FRONT_DIM, FullAodConfig.KEY_WALLPAPER_DIM,
            FullAodConfig.KEY_BRT_SCALE, FullAodConfig.KEY_BRT_MIN, FullAodConfig.KEY_BRT_MAX
    };
    private static final int[] NAME_RES = {
            R.string.dim_foreground, R.string.dim_wallpaper,
            R.string.brt_scale, R.string.brt_min, R.string.brt_max
    };
    private static final int[] MAX_PCT = {85, 85, 90, 100, 200};
    private static final int[] MIN_PCT = {0, 0, 8, 1, 10};

    // presets: front, wallpaper, scale, min, max
    private static final float[][] PRESETS = {
            {0.55f, 0.42f, 0.52f, 0.04f, 0.38f}, // Night
            {0.42f, 0.32f, 0.75f, 0.05f, 0.55f}, // Standard
            {0.30f, 0.20f, 0.95f, 0.07f, 0.85f}  // Bright
    };
    private static final int[] PRESET_RES = {
            R.string.preset_night, R.string.preset_standard, R.string.preset_bright
    };

    // ---- M3 color roles ----
    private int mPrimary, mOnPrimary, mPrimaryContainer, mOnPrimaryContainer;
    private int mSurface, mSurfaceContainer, mSurfaceContainerHigh;
    private int mOnSurface, mOnSurfaceVariant, mOutline, mError;

    private final TextView[] mValueViews = new TextView[5];
    private final SeekBar[] mSeeks = new SeekBar[5];
    private final Button[] mPresetButtons = new Button[3];
    private int mSelectedPreset = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        resolveM3Colors();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(mSurface);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(40));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- Top app bar (large) ----
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(mOnSurface);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        title.setLetterSpacing(-0.01f);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.app_subtitle);
        subtitle.setTextColor(mOnSurfaceVariant);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setPadding(0, dp(4), 0, dp(20));
        root.addView(subtitle);

        ContentResolver cr = getContentResolver();
        mSelectedPreset = matchPreset(cr);

        // ---- Master switch card ----
        LinearLayout masterCard = card();
        LinearLayout masterRow = row();
        TextView masterLabel = bodyLarge(getString(R.string.master_label));
        masterRow.addView(masterLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch masterSw = new Switch(this);
        styleSwitch(masterSw);
        masterSw.setChecked(Settings.Global.getInt(cr, FullAodConfig.KEY_ENABLED, 1) != 0);
        masterSw.setContentDescription(getString(R.string.master_label));
        masterSw.setOnCheckedChangeListener((btn, isChecked) ->
                putGlobalAsync(FullAodConfig.KEY_ENABLED, isChecked ? "1" : "0"));
        masterRow.addView(masterSw);
        masterCard.addView(masterRow);
        root.addView(masterCard, cardLp(dp(8)));

        // ---- Presets card ----
        LinearLayout presetCard = card();
        presetCard.addView(cardHeader(getString(R.string.section_presets)));
        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(PRESET_RES[idx]);
            b.setTextColor(mOnPrimaryContainer);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            b.setAllCaps(false);
            b.setStateListAnimator(null);
            mPresetButtons[idx] = b;
            stylePreset(idx, idx == mSelectedPreset);
            b.setOnClickListener(v -> selectPreset(idx));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    0, dp(48), 1f);
            blp.setMargins(0, 0, dp(8), 0);
            if (i == 2) blp.rightMargin = 0;
            presetRow.addView(b, blp);
        }
        presetCard.addView(presetRow);
        root.addView(presetCard, cardLp(dp(8)));

        // ---- Brightness card ----
        LinearLayout brtCard = card();
        brtCard.addView(cardHeader(getString(R.string.section_brightness)));
        addSlider(brtCard, 2, Settings.Global.getFloat(cr, FullAodConfig.KEY_BRT_SCALE, FullAodConfig.DEFAULT_BRT_SCALE));
        addSlider(brtCard, 3, Settings.Global.getFloat(cr, FullAodConfig.KEY_BRT_MIN, FullAodConfig.DEFAULT_BRT_MIN));
        addSlider(brtCard, 4, Settings.Global.getFloat(cr, FullAodConfig.KEY_BRT_MAX, FullAodConfig.DEFAULT_BRT_MAX));
        addDurationRow(brtCard);
        addSchedRow(brtCard);
        root.addView(brtCard, cardLp(dp(8)));

        // ---- Dimming card ----
        LinearLayout dimCard = card();
        dimCard.addView(cardHeader(getString(R.string.section_dimming)));
        addSlider(dimCard, 0, Settings.Global.getFloat(cr, FullAodConfig.KEY_FRONT_DIM, FullAodConfig.DEFAULT_FRONT_DIM));
        addSlider(dimCard, 1, Settings.Global.getFloat(cr, FullAodConfig.KEY_WALLPAPER_DIM, FullAodConfig.DEFAULT_WALLPAPER_DIM));
        root.addView(dimCard, cardLp(dp(8)));

        // ---- Elements card ----
        LinearLayout elCard = card();
        elCard.addView(cardHeader(getString(R.string.section_elements)));
        addSwitchRow(elCard, getString(R.string.sw_adaptive), FullAodConfig.KEY_ADAPTIVE_DIM);
        addSwitchRow(elCard, getString(R.string.sw_widgets), FullAodConfig.KEY_SHOW_WIDGETS);
        addSwitchRow(elCard, getString(R.string.sw_notifications), FullAodConfig.KEY_SHOW_NOTIFICATIONS);
        addSwitchRow(elCard, getString(R.string.sw_shortcuts), FullAodConfig.KEY_KEEP_SHORTCUTS);
        root.addView(elCard, cardLp(dp(8)));

        // ---- Reset (text button, error color) ----
        Button reset = new Button(this);
        reset.setText(R.string.reset);
        reset.setTextColor(mError);
        reset.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        reset.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        reset.setAllCaps(false);
        reset.setBackground(ripple(pill(Color.TRANSPARENT)));
        reset.setStateListAnimator(null);
        reset.setOnClickListener(v -> {
            mSelectedPreset = -1;
            updatePresetStyles();
            applyValues(new float[]{
                FullAodConfig.DEFAULT_FRONT_DIM, FullAodConfig.DEFAULT_WALLPAPER_DIM,
                FullAodConfig.DEFAULT_BRT_SCALE, FullAodConfig.DEFAULT_BRT_MIN, FullAodConfig.DEFAULT_BRT_MAX});
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        rlp.topMargin = dp(16);
        root.addView(reset, rlp);

        // ---- Status card ----
        LinearLayout statusCard = card();
        statusCard.setBackground(ripple(cardBg(mSurfaceContainerHigh)));
        TextView status = bodySmall(getString(R.string.status_scope) + "\n"
                + getString(R.string.status_pocket) + "\n"
                + getString(R.string.status_burnin) + "\n"
                + getString(R.string.status_hot) + "\n"
                + getString(R.string.status_cli));
        statusCard.addView(status);
        root.addView(statusCard, cardLp(dp(8)));

        setContentView(scroll);
    }

    // ================= M3 palette =================
    private void resolveM3Colors() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            mPrimary = getColor(android.R.color.system_accent1_200);
            mOnPrimary = getColor(android.R.color.system_accent1_800);
            mPrimaryContainer = getColor(android.R.color.system_accent1_700);
            mOnPrimaryContainer = getColor(android.R.color.system_accent1_100);
            mSurface = getColor(android.R.color.system_neutral1_900);
            mSurfaceContainer = getColor(android.R.color.system_neutral1_800);
            mSurfaceContainerHigh = getColor(android.R.color.system_neutral1_700);
            mOnSurface = getColor(android.R.color.system_neutral1_100);
            mOnSurfaceVariant = getColor(android.R.color.system_neutral2_200);
            mOutline = getColor(android.R.color.system_neutral2_500);
            mError = 0xFFF2B8B5;
        } else {
            mPrimary = getColor(android.R.color.system_accent1_600);
            mOnPrimary = getColor(android.R.color.system_accent1_0);
            mPrimaryContainer = getColor(android.R.color.system_accent1_100);
            mOnPrimaryContainer = getColor(android.R.color.system_accent1_900);
            mSurface = getColor(android.R.color.system_neutral1_10);
            mSurfaceContainer = getColor(android.R.color.system_neutral1_50);
            mSurfaceContainerHigh = getColor(android.R.color.system_neutral1_100);
            mOnSurface = getColor(android.R.color.system_neutral1_900);
            mOnSurfaceVariant = getColor(android.R.color.system_neutral2_700);
            mOutline = getColor(android.R.color.system_neutral2_500);
            mError = 0xFFB3261E;
        }
    }

    // ================= M3 widgets =================
    private LinearLayout card() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackground(ripple(cardBg(mSurfaceContainer)));
        ll.setPadding(dp(20), dp(16), dp(20), dp(16));
        return ll;
    }

    private GradientDrawable cardBg(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(24));
        return d;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(28));
        return d;
    }

    private android.widget.EditText m3Field(String hint) {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setSingleLine(true);
        et.setTextColor(mOnSurface);
        et.setHintTextColor(mOnSurfaceVariant);
        et.setHint(hint);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(mSurfaceContainerHigh);
        bg.setCornerRadius(dp(26));
        bg.setStroke(dp(1), (mOutline & 0x00FFFFFF) | 0x33000000);
        et.setBackground(ripple(bg));
        et.setPadding(dp(16), 0, dp(16), 0);
        et.setMinimumHeight(dp(52));
        et.setGravity(Gravity.CENTER_VERTICAL);
        return et;
    }

    private Button tonalBtn(String text) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(mOnPrimaryContainer);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(ripple(pill(mPrimaryContainer)));
        b.setStateListAnimator(null);
        b.setMinimumHeight(dp(44));
        b.setPadding(dp(16), 0, dp(16), 0);
        return b;
    }

    private Button textBtn(String text, int color) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(color);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(ripple(pill(Color.TRANSPARENT)));
        b.setStateListAnimator(null);
        b.setMinimumHeight(dp(44));
        return b;
    }

    private Drawable ripple(Drawable bg) {
        int rc = (mOnSurface & 0x00FFFFFF) | 0x1F000000;
        return new RippleDrawable(ColorStateList.valueOf(rc), bg, null);
    }

    private LinearLayout.LayoutParams cardLp(int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = margin;
        return lp;
    }

    private TextView cardHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(mOnSurfaceVariant);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setLetterSpacing(0.02f);
        tv.setPadding(0, dp(4), 0, dp(12));
        return tv;
    }

    private TextView bodyLarge(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(mOnSurface);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        return tv;
    }

    private TextView bodySmall(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(mOnSurfaceVariant);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setLineSpacing(dp(4), 1f);
        return tv;
    }

    private LinearLayout row() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER_VERTICAL);
        ll.setMinimumHeight(dp(48));
        return ll;
    }

    private void styleSwitch(Switch sw) {
        GradientDrawable trackOn = new GradientDrawable();
        trackOn.setColor(mPrimary);
        trackOn.setCornerRadius(dp(16));
        GradientDrawable trackOff = new GradientDrawable();
        trackOff.setColor(mSurfaceContainerHigh);
        trackOff.setCornerRadius(dp(16));
        trackOff.setStroke(dp(2), mOutline);
        StateListDrawable track = new StateListDrawable();
        track.addState(new int[]{android.R.attr.state_checked}, trackOn);
        track.addState(new int[]{}, trackOff);
        GradientDrawable thumbOn = new GradientDrawable();
        thumbOn.setColor(mOnPrimary);
        thumbOn.setCornerRadius(dp(12));
        thumbOn.setSize(dp(24), dp(24));
        GradientDrawable thumbOff = new GradientDrawable();
        thumbOff.setColor(mOnSurfaceVariant);
        thumbOff.setCornerRadius(dp(12));
        thumbOff.setSize(dp(24), dp(24));
        StateListDrawable thumb = new StateListDrawable();
        thumb.addState(new int[]{android.R.attr.state_checked}, thumbOn);
        thumb.addState(new int[]{}, thumbOff);
        sw.setTrackDrawable(track);
        sw.setThumbDrawable(thumb);
        sw.setMinimumHeight(dp(48));
    }

    private void stylePreset(int idx, boolean selected) {
        Button b = mPresetButtons[idx];
        if (b == null) return;
        if (selected) {
            b.setBackground(ripple(pill(mPrimary)));
            b.setTextColor(mOnPrimary);
        } else {
            b.setBackground(ripple(pill(mPrimaryContainer)));
            b.setTextColor(mOnPrimaryContainer);
        }
    }

    private void updatePresetStyles() {
        for (int i = 0; i < 3; i++) stylePreset(i, i == mSelectedPreset);
    }

    private void selectPreset(int idx) {
        mSelectedPreset = idx;
        updatePresetStyles();
        applyValues(PRESETS[idx]);
    }

    private int matchPreset(ContentResolver cr) {
        float[] cur = {
                Settings.Global.getFloat(cr, FullAodConfig.KEY_FRONT_DIM, FullAodConfig.DEFAULT_FRONT_DIM),
                Settings.Global.getFloat(cr, FullAodConfig.KEY_WALLPAPER_DIM, FullAodConfig.DEFAULT_WALLPAPER_DIM),
                Settings.Global.getFloat(cr, FullAodConfig.KEY_BRT_SCALE, FullAodConfig.DEFAULT_BRT_SCALE),
                Settings.Global.getFloat(cr, FullAodConfig.KEY_BRT_MIN, FullAodConfig.DEFAULT_BRT_MIN),
                Settings.Global.getFloat(cr, FullAodConfig.KEY_BRT_MAX, FullAodConfig.DEFAULT_BRT_MAX)};
        for (int p = 0; p < PRESETS.length; p++) {
            boolean match = true;
            for (int i = 0; i < 5; i++) {
                if (Math.abs(cur[i] - PRESETS[p][i]) > 0.005f) {
                    match = false;
                    break;
                }
            }
            if (match) return p;
        }
        return -1;
    }

    // ================= timed stock-AOD fallback =================
    private void addDurationRow(LinearLayout card) {
        LinearLayout head = row();
        TextView label = bodyLarge("全屏AOD时长（超时转普通AOD）");
        head.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView val = new TextView(this);
        val.setTextColor(mPrimary);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        val.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        int cur = 0;
        try {
            cur = Settings.Global.getInt(getContentResolver(), FullAodConfig.KEY_DURATION_S, 0);
        } catch (Throwable ignored) {
        }
        final int curSec = cur;
        val.setText(formatDur(curSec));
        head.addView(val);
        card.addView(head);

        // Numeric input with unit toggle (M3): [field] [unit tonal pill]
        LinearLayout inputRow = row();
        final boolean[] unitMin = {curSec >= 60 && curSec % 60 == 0};
        final android.widget.EditText et = m3Field(unitMin[0] ? "分钟" : "秒");
        et.setText(String.valueOf(unitMin[0] ? curSec / 60 : curSec));
        et.setContentDescription("全屏AOD时长数值（0=一直全屏）");
        inputRow.addView(et, new LinearLayout.LayoutParams(0, dp(52), 0.55f));

        final android.widget.Button unitBtn = tonalBtn(unitMin[0] ? "分钟" : "秒");
        unitBtn.setContentDescription("切换单位");
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(0, dp(52), 0.45f);
        ulp.leftMargin = dp(8);
        inputRow.addView(unitBtn, ulp);
        LinearLayout.LayoutParams irlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        irlp.topMargin = dp(8);
        card.addView(inputRow, irlp);

        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                saveDuration(et, unitMin, val);
            }
        });
        unitBtn.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                unitMin[0] = !unitMin[0];
                unitBtn.setText(unitMin[0] ? "分钟" : "秒");
                et.setHint(unitMin[0] ? "分钟" : "秒");
                saveDuration(et, unitMin, val);
            }
        });
    }

    private final java.util.List<int[]> mRules = new java.util.ArrayList<int[]>();

    private void addSchedRow(LinearLayout card) {
        LinearLayout head = row();
        TextView label = bodyLarge("定时规则（优先于默认时长）");
        head.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(head);

        String cur = "";
        try {
            cur = Settings.Global.getString(getContentResolver(), FullAodConfig.KEY_SCHED_RULES);
        } catch (Throwable ignored) {
        }
        mRules.clear();
        mRules.addAll(parseRules(cur == null ? "" : cur));

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        card.addView(list);
        renderRules(list);

        Button addBtn = tonalBtn("＋ 添加时段规则");
        addBtn.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                mRules.add(new int[]{22 * 60, 9 * 60, 10});
                renderRules(list);
                saveRules();
            }
        });
        card.addView(addBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
    }

    private void renderRules(final LinearLayout list) {
        list.removeAllViews();
        for (int i = 0; i < mRules.size(); i++) {
            list.addView(buildRuleRow(list, i));
        }
    }

    private LinearLayout buildRuleRow(final LinearLayout list, final int idx) {
        final int[] rule = mRules.get(idx);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackground(ripple(cardBg(mSurfaceContainerHigh)));
        wrap.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.bottomMargin = dp(8);
        wrap.setLayoutParams(wlp);

        // line 1: [开始 时间选择器] 至 [结束 时间选择器]  [删除]
        LinearLayout line1 = row();
        final Button startBtn = tonalBtn(formatHm(rule[0]));
        startBtn.setContentDescription("开始时间");
        startBtn.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                new android.app.TimePickerDialog(MainActivity.this,
                        new android.app.TimePickerDialog.OnTimeSetListener() {
                            public void onTimeSet(android.widget.TimePicker tp, int h, int m) {
                                rule[0] = h * 60 + m;
                                startBtn.setText(formatHm(rule[0]));
                                saveRules();
                            }
                        }, rule[0] / 60, rule[0] % 60, true).show();
            }
        });
        line1.addView(startBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));
        TextView mid = bodyLarge("至");
        mid.setPadding(dp(8), 0, dp(8), 0);
        line1.addView(mid);
        final Button endBtn = tonalBtn(formatHm(rule[1]));
        endBtn.setContentDescription("结束时间");
        endBtn.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                new android.app.TimePickerDialog(MainActivity.this,
                        new android.app.TimePickerDialog.OnTimeSetListener() {
                            public void onTimeSet(android.widget.TimePicker tp, int h, int m) {
                                rule[1] = h * 60 + m;
                                endBtn.setText(formatHm(rule[1]));
                                saveRules();
                            }
                        }, rule[1] / 60, rule[1] % 60, true).show();
            }
        });
        line1.addView(endBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button delBtn = textBtn("删除", mError);
        delBtn.setPadding(dp(4), 0, dp(0), 0);
        delBtn.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                mRules.remove(idx);
                renderRules(list);
                saveRules();
            }
        });
        line1.addView(delBtn);
        wrap.addView(line1);

        // line 2: 超时时长 [field] [unit tonal]
        LinearLayout line2 = row();
        TextView lbl = bodyLarge("超时时长");
        line2.addView(lbl, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final boolean[] unitMin = {rule[2] >= 60 && rule[2] % 60 == 0 && rule[2] > 0};
        final android.widget.EditText et = m3Field(unitMin[0] ? "分钟" : "秒");
        et.setText(String.valueOf(rule[2] == 0 ? 10 : (unitMin[0] ? rule[2] / 60 : rule[2])));
        et.setEnabled(rule[2] != 0);
        LinearLayout.LayoutParams etlp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        etlp.leftMargin = dp(8);
        line2.addView(et, etlp);
        final Button unitBtn = tonalBtn(unitMin[0] ? "分钟" : "秒");
        unitBtn.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                unitMin[0] = !unitMin[0];
                unitBtn.setText(unitMin[0] ? "分钟" : "秒");
                saveRuleValue(rule, et, unitMin);
            }
        });
        LinearLayout.LayoutParams ublp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
        ublp.leftMargin = dp(8);
        line2.addView(unitBtn, ublp);
        LinearLayout.LayoutParams l2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l2lp.topMargin = dp(8);
        wrap.addView(line2, l2lp);

        // line 3: 不转普通AOD switch (M3 switch row, same as element rows)
        LinearLayout line3 = row();
        TextView nlbl = bodyLarge("不转普通AOD");
        line3.addView(nlbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final android.widget.Switch noSwitch = new android.widget.Switch(this);
        styleSwitch(noSwitch);
        noSwitch.setChecked(rule[2] == 0);
        noSwitch.setContentDescription("不转普通AOD");
        noSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton cb, boolean on) {
                if (on) {
                    rule[2] = 0;
                } else {
                    saveRuleValue(rule, et, unitMin);
                }
                et.setEnabled(!on);
                saveRules();
            }
        });
        line3.addView(noSwitch);
        LinearLayout.LayoutParams l3lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l3lp.topMargin = dp(8);
        wrap.addView(line3, l3lp);

        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                saveRuleValue(rule, et, unitMin);
                saveRules();
            }
        });
        return wrap;
    }

    private void saveRuleValue(int[] rule, android.widget.EditText et, boolean[] unitMin) {
        if (rule[2] == 0) return; // 不转状态由复选框管理
        int n = 10;
        try {
            n = Integer.parseInt(et.getText().toString().trim());
        } catch (Throwable ignored) {
        }
        if (n < 1) n = 1;
        rule[2] = unitMin[0] ? n * 60 : n;
    }

    private void saveRules() {
        StringBuilder sb = new StringBuilder();
        for (int[] r : mRules) {
            if (sb.length() > 0) sb.append(";");
            sb.append(formatHm(r[0])).append("-").append(formatHm(r[1])).append("=").append(r[2]);
        }
        putGlobalAsync(FullAodConfig.KEY_SCHED_RULES, sb.toString());
    }

    private static String formatHm(int mins) {
        return String.format(Locale.US, "%02d:%02d", (mins / 60) % 24, mins % 60);
    }

    private static java.util.List<int[]> parseRules(String rules) {
        java.util.List<int[]> out = new java.util.ArrayList<int[]>();
        for (String part : rules.split(";")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            try {
                int eq = p.indexOf('=');
                int dash = p.indexOf('-');
                if (eq < 0 || dash < 0 || dash > eq) continue;
                String[] a = p.substring(0, dash).trim().split(":");
                String[] b = p.substring(dash + 1, eq).trim().split(":");
                out.add(new int[]{
                        Integer.parseInt(a[0]) * 60 + Integer.parseInt(a[1]),
                        Integer.parseInt(b[0]) * 60 + Integer.parseInt(b[1]),
                        Integer.parseInt(p.substring(eq + 1).trim())});
            } catch (Throwable ignored) {
            }
        }
        return out;
    }


    private void saveDuration(android.widget.EditText et, boolean[] unitMin, TextView val) {
        int n = 0;
        try {
            n = Integer.parseInt(et.getText().toString().trim());
        } catch (Throwable ignored) {
        }
        if (n < 0) n = 0;
        int sec = unitMin[0] ? n * 60 : n;
        val.setText(formatDur(sec));
        putGlobalAsync(FullAodConfig.KEY_DURATION_S, String.valueOf(sec));
    }

    private static int durToProgress(int sec) {
        if (sec <= 0) return 0;
        return Math.max(1, Math.min(100, Math.round((sec - 10) / 17.9f)));
    }

    private static String formatDur(int sec) {
        if (sec <= 0) return "一直全屏";
        if (sec < 60) return sec + " 秒";
        int m = Math.round(sec / 60f);
        return m + " 分钟";
    }

    // ================= sliders =================
    private void addSlider(LinearLayout card, final int idx, float value) {
        LinearLayout head = row();
        TextView label = bodyLarge(getString(NAME_RES[idx]));
        head.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = new TextView(this);
        val.setTextColor(mPrimary);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        val.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        int pct0 = Math.round(value * 100f);
        val.setText(getString(R.string.value_format, getString(NAME_RES[idx]), pct0));
        head.addView(val);
        card.addView(head);

        final TextView valView = val;
        final String name = getString(NAME_RES[idx]);
        SeekBar sb = new SeekBar(this);
        sb.setMax(MAX_PCT[idx]);
        sb.setProgress(Math.max(0, Math.min(MAX_PCT[idx], pct0)));
        sb.setProgressTintList(ColorStateList.valueOf(mPrimary));
        GradientDrawable sbThumb = new GradientDrawable();
        sbThumb.setColor(mPrimary);
        sbThumb.setCornerRadius(dp(10));
        sbThumb.setSize(dp(20), dp(20));
        sbThumb.setStroke(dp(2), mSurface);
        sb.setThumb(sbThumb);
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(mSurfaceContainerHigh));
        sb.setMinimumHeight(dp(48));
        sb.setContentDescription(name);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                if (fromUser) {
                    mSelectedPreset = -1;
                    updatePresetStyles();
                    int clamped = Math.max(MIN_PCT[idx], p);
                    valView.setText(getString(R.string.value_format, name, clamped));
                    putGlobalAsync(KEYS[idx], String.format(Locale.US, "%.2f", clamped / 100f));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        card.addView(sb);
        mValueViews[idx] = val;
        mSeeks[idx] = sb;
    }

    private void applyValues(float[] vals) {
        for (int i = 0; i < 5; i++) {
            int pct = Math.round(vals[i] * 100f);
            if (mSeeks[i] != null) mSeeks[i].setProgress(pct);
            if (mValueViews[i] != null) {
                mValueViews[i].setText(getString(R.string.value_format, getString(NAME_RES[i]), pct));
            }
            putGlobalAsync(KEYS[i], String.format(Locale.US, "%.2f", vals[i]));
        }
    }

    // ================= switch rows =================
    private void addSwitchRow(LinearLayout card, String label, String key) {
        LinearLayout r = row();
        TextView tv = bodyLarge(label);
        r.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(this);
        styleSwitch(sw);
        sw.setChecked(Settings.Global.getInt(getContentResolver(), key, 1) != 0);
        sw.setContentDescription(label);
        sw.setOnCheckedChangeListener((btn, isChecked) -> putGlobalAsync(key, isChecked ? "1" : "0"));
        r.addView(sw);
        card.addView(r);
    }

    // ================= write path =================
    private void putGlobalAsync(String key, String value) {
        new Thread(() -> {
            try {
                Settings.Global.putString(getContentResolver(), key, value);
                android.util.Log.i("FullAOD", "UI write OK: " + key + "=" + value);
                return;
            } catch (Throwable t) {
                android.util.Log.w("FullAOD", "UI write putString failed: " + key + " -> " + t);
            }
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "settings put global " + key + " " + value});
                int rc = p.waitFor();
                android.util.Log.i("FullAOD", "UI write su rc=" + rc + ": " + key + "=" + value);
            } catch (Throwable t) {
                android.util.Log.e("FullAOD", "UI write su failed: " + key + " -> " + t);
            }
        }).start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
