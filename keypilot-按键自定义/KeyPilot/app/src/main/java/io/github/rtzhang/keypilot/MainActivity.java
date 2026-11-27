package io.github.rtzhang.keypilot;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

import io.github.rtzhang.keypilot.config.ActionProfile;
import io.github.rtzhang.keypilot.config.ActionSpec;
import io.github.rtzhang.keypilot.config.ActionType;
import io.github.rtzhang.keypilot.config.GestureType;
import io.github.rtzhang.keypilot.config.KeyId;
import io.github.rtzhang.keypilot.config.KeyPilotConfig;
import io.github.rtzhang.keypilot.config.SceneTemplates;
import io.github.rtzhang.keypilot.ui.ActionIcons;
import io.github.rtzhang.keypilot.ui.ActionPickerDialog;
import io.github.rtzhang.keypilot.ui.AppIconCache;
import io.github.rtzhang.keypilot.ui.ConflictDetector;
import io.github.rtzhang.keypilot.ui.LabelManagerDialog;
import io.github.rtzhang.keypilot.ui.LabelStore;
import io.github.rtzhang.keypilot.ui.ProfileEditorDialog;
import io.github.rtzhang.keypilot.ui.SafetySettingsDialog;
import io.github.rtzhang.keypilot.ui.UiTheme;

/**
 * KeyPilot Material 3 (M3) Main Activity — dynamic Monet colors (day/night auto),
 * 24/26/28dp corner family, pill tabs, real app icons & names on gesture rows & profile cards,
 * M3 custom switches, dismissible conflict banner, and M3 bottom sheets.
 */
public class MainActivity extends Activity {

    private static final int TAB_GLOBAL = 0;
    private static final int TAB_PROFILES = 1;

    private KeyPilotConfig config;
    private boolean bindingUi = false;
    private boolean conflictDismissed = false;
    private int activeTab = TAB_GLOBAL;

    private UiTheme.Palette palette;
    private View statusDot;
    private TextView heartbeatView;
    private TextView tabGlobalBtn;
    private TextView tabProfilesBtn;
    private LinearLayout conflictBanner;
    private LinearLayout globalContainer;
    private LinearLayout profilesContainer;
    private LinearLayout profilesListContainer;
    private TextView safetySummaryView;
    private TextView logView;

    private Switch masterSwitch;
    private Switch toastSwitch;
    private Switch hapticSwitch;
    private Switch imeVoiceSwitch;

    private final EnumMap<KeyId, Switch> keySwitches = new EnumMap<>(KeyId.class);
    private final EnumMap<KeyId, EnumMap<GestureType, GestureRowHolder>> cellHolders =
            new EnumMap<>(KeyId.class);

    private static final class GestureRowHolder {
        final ImageView iconView;
        final TextView valueView;
        final TextView subView;

        GestureRowHolder(ImageView iconView, TextView valueView, TextView subView) {
            this.iconView = iconView;
            this.valueView = valueView;
            this.subView = subView;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        palette = UiTheme.colors(this);
        applySystemBars();

        config = loadLocalConfig();
        setContentView(buildRootView());
        renderConfigToUi();
        refreshStatus();
        refreshConflictBanner();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshConflictBanner();
    }

    /** SAF round-trips for the 活动命名管理 (export / import JSON). */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (LabelManagerDialog.handleActivityResult(this, requestCode, resultCode, data)) {
            // labels changed -> matrix / profile cards must re-resolve display names
            renderConfigToUi();
        }
    }

    /** Small component line ("pkg/class") for launch_activity bindings on a profile card. */
    private static String collectActivityComponents(ActionProfile prof) {
        StringBuilder sb = new StringBuilder();
        for (KeyId k : KeyId.values()) {
            for (GestureType g : GestureType.values()) {
                ActionSpec s = prof.getBinding(k, g);
                if (s == null || s.type != ActionType.LAUNCH_ACTIVITY
                        || s.packageName == null || s.className == null) {
                    continue;
                }
                if (sb.length() > 0) sb.append(" · ");
                sb.append(s.packageName).append("/")
                        .append(LabelStore.shortClass(s.packageName, s.className));
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private void applySystemBars() {
        Window w = getWindow();
        w.getDecorView().setBackgroundColor(palette.surface);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController ctrl = w.getInsetsController();
            if (ctrl != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                ctrl.setSystemBarsAppearance(palette.isDark ? 0 : mask, mask);
            }
        }
    }

    private View buildRootView() {
        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(palette.surface);
        scroll.setClipToPadding(true);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int padH = UiTheme.dp(this, 16);
        final int statusTopBase = UiTheme.dp(this, 40);
        final int topInner = UiTheme.dp(this, 8);
        final int botBase = UiTheme.dp(this, 36);
        scroll.setPadding(0, statusTopBase, 0, 0);
        root.setPadding(padH, topInner, padH, botBase);
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                int statusTop = Math.max(statusTopBase, insets.getSystemWindowInsetTop());
                int bot = Math.max(botBase, insets.getSystemWindowInsetBottom() + UiTheme.dp(MainActivity.this, 24));
                scroll.setPadding(0, statusTop, 0, 0);
                root.setPadding(padH, topInner, padH, bot);
                return insets;
            }
        });
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- M3 Top Header ----
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = UiTheme.makeText(this, "KeyPilot", 26, true, palette.onSurface);
        headerRow.addView(title);

        TextView verChip = UiTheme.makeChip(this, "v2 P1", true);
        LinearLayout.LayoutParams vcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vcLp.leftMargin = UiTheme.dp(this, 10);
        headerRow.addView(verChip, vcLp);

        View spacer = new View(this);
        headerRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        // Top-right safety shortcut button (0 right padding aligned with card edge!)
        TextView safetyBtn = UiTheme.makeButton(this, "防坑开关", UiTheme.ButtonStyle.TEXT,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { openSafetySettings(); }
                });
        safetyBtn.setPadding(UiTheme.dp(this, 12), UiTheme.dp(this, 10), 0, UiTheme.dp(this, 10));
        headerRow.addView(safetyBtn);
        root.addView(headerRow);

        // ---- Live Connection Status Pill ----
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setBackground(UiTheme.roundedShape(this, palette.surfaceContainer, 0, 0, UiTheme.RADIUS_PILL));
        statusRow.setPadding(UiTheme.dp(this, 14), UiTheme.dp(this, 8),
                UiTheme.dp(this, 14), UiTheme.dp(this, 8));
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = UiTheme.dp(this, 8);
        statusRow.setLayoutParams(stLp);

        statusDot = new View(this);
        statusDot.setBackground(UiTheme.roundedShape(this, palette.primary, 0, 0, UiTheme.RADIUS_PILL));
        LinearLayout.LayoutParams sdLp = new LinearLayout.LayoutParams(
                UiTheme.dp(this, 8), UiTheme.dp(this, 8));
        sdLp.rightMargin = UiTheme.dp(this, 10);
        statusRow.addView(statusDot, sdLp);

        heartbeatView = UiTheme.makeText(this, "等待 system_server 连接", 12, true, palette.onSurfaceVariant);
        heartbeatView.setMaxLines(2);
        heartbeatView.setEllipsize(TextUtils.TruncateAt.END);
        heartbeatView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statusRow.addView(heartbeatView);
        root.addView(statusRow);

        // ---- Dismissible M3 Error Conflict Banner ----
        conflictBanner = new LinearLayout(this);
        conflictBanner.setOrientation(LinearLayout.VERTICAL);
        conflictBanner.setVisibility(View.GONE);
        root.addView(conflictBanner);

        // ---- M3 Segmented Pill Tab Bar (selected = primaryContainer) ----
        LinearLayout tabShell = new LinearLayout(this);
        tabShell.setOrientation(LinearLayout.HORIZONTAL);
        tabShell.setBackground(UiTheme.roundedShape(this, palette.surfaceContainer, 0, 0, UiTheme.RADIUS_PILL));
        tabShell.setPadding(UiTheme.dp(this, 4), UiTheme.dp(this, 4),
                UiTheme.dp(this, 4), UiTheme.dp(this, 4));
        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tsLp.topMargin = UiTheme.dp(this, 12);
        tabShell.setLayoutParams(tsLp);

        tabGlobalBtn = makeTabPill("全局默认", TAB_GLOBAL);
        tabProfilesBtn = makeTabPill("场景分流", TAB_PROFILES);
        tabShell.addView(tabGlobalBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View tabGap = new View(this);
        tabShell.addView(tabGap, new LinearLayout.LayoutParams(UiTheme.dp(this, 4), 1));
        tabShell.addView(tabProfilesBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(tabShell);

        // ---- Tab 1: Global Control + 4x3 Matrix ----
        globalContainer = new LinearLayout(this);
        globalContainer.setOrientation(LinearLayout.VERTICAL);
        globalContainer.addView(buildGlobalControlCard());
        for (KeyId key : KeyId.values()) {
            globalContainer.addView(buildKeyCard(key));
        }
        root.addView(globalContainer);

        // ---- Tab 2: Per-App Profiles ----
        profilesContainer = buildProfilesContainer();
        profilesContainer.setVisibility(View.GONE);
        root.addView(profilesContainer);

        // ---- Diagnostics / Logs Card ----
        root.addView(buildDiagnosticsCard());

        return scroll;
    }

    private TextView makeTabPill(String label, final int tabIndex) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(14);
        tv.setTypeface(UiTheme.fontMedium());
        tv.setAllCaps(false);
        tv.setGravity(Gravity.CENTER);
        tv.setMinHeight(UiTheme.dp(this, 44));
        tv.setPadding(UiTheme.dp(this, 12), UiTheme.dp(this, 10),
                UiTheme.dp(this, 12), UiTheme.dp(this, 10));
        tv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchTab(tabIndex); }
        });
        return tv;
    }

    private void switchTab(int tabIndex) {
        activeTab = tabIndex;
        globalContainer.setVisibility(tabIndex == TAB_GLOBAL ? View.VISIBLE : View.GONE);
        profilesContainer.setVisibility(tabIndex == TAB_PROFILES ? View.VISIBLE : View.GONE);
        updateTabStyles();
    }

    private void updateTabStyles() {
        boolean isGlobal = (activeTab == TAB_GLOBAL);
        tabGlobalBtn.setBackground(UiTheme.rippleSurface(
                this, isGlobal ? palette.primaryContainer : Color.TRANSPARENT,
                0, 0, UiTheme.RADIUS_PILL, palette.onPrimaryContainer));
        tabGlobalBtn.setTextColor(isGlobal ? palette.onPrimaryContainer : palette.onSurfaceVariant);

        tabProfilesBtn.setText("场景分流 (" + config.profiles.size() + ")");
        tabProfilesBtn.setBackground(UiTheme.rippleSurface(
                this, !isGlobal ? palette.primaryContainer : Color.TRANSPARENT,
                0, 0, UiTheme.RADIUS_PILL, palette.onPrimaryContainer));
        tabProfilesBtn.setTextColor(!isGlobal ? palette.onPrimaryContainer : palette.onSurfaceVariant);
    }

    private View buildGlobalControlCard() {
        LinearLayout card = UiTheme.makeCard(this);
        card.addView(UiTheme.makeText(this, "全局控制", 16, true, palette.onSurface));

        masterSwitch = addSwitchRow(card, "启用 KeyPilot 映射",
                "关闭后四颗物理按键立即回归系统原生", new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        if (bindingUi) return;
                        config.masterEnabled = isChecked;
                        renderConfigToUi();
                        saveAndBroadcast("总开关：" + (isChecked ? "开启" : "关闭"));
                    }
                });

        toastSwitch = addSwitchRow(card, "动作执行 Toast 提示",
                "触发手电筒 / 媒体 / 静音等动作时给予轻提示", new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        if (bindingUi) return;
                        config.showToast = isChecked;
                        saveAndBroadcast("Toast 反馈已更新");
                    }
                });

        hapticSwitch = addSwitchRow(card, "触觉振动反馈",
                "触发自定义手势时给予线性马达反馈", new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        if (bindingUi) return;
                        config.hapticFeedback = isChecked;
                        saveAndBroadcast("触觉反馈已更新");
                    }
                });

        imeVoiceSwitch = addSwitchRow(card, "保留软键盘 Essential Voice",
                "输入法弹出时放行 Essential Key 原生听写", new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        if (bindingUi) return;
                        config.preserveImeVoice = isChecked;
                        saveAndBroadcast("IME 语音策略已更新");
                    }
                });

        card.addView(UiTheme.makeDivider(this));

        // Primary action row
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r1Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r1Lp.topMargin = UiTheme.dp(this, 4);
        row1.setLayoutParams(r1Lp);

        TextView syncBtn = UiTheme.makeButton(this, "立即同步", UiTheme.ButtonStyle.FILLED,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { saveAndBroadcast("已同步配置至 system_server"); }
                });
        LinearLayout.LayoutParams bGap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bGap.rightMargin = UiTheme.dp(this, 8);
        row1.addView(syncBtn, bGap);

        TextView v2Btn = UiTheme.makeButton(this, "v2 相机预设", UiTheme.ButtonStyle.TONAL,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { applyV2PresetWithConfirm(); }
                });
        row1.addView(v2Btn, new LinearLayout.LayoutParams(bGap));
        card.addView(row1);

        // Secondary action row
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2Lp.topMargin = UiTheme.dp(this, 8);
        row2.setLayoutParams(r2Lp);

        TextView v1Btn = UiTheme.makeButton(this, "v1 测试预设", UiTheme.ButtonStyle.OUTLINED,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { applyV1PresetWithConfirm(); }
                });
        row2.addView(v1Btn, new LinearLayout.LayoutParams(bGap));

        row2.addView(UiTheme.makeButton(this, "恢复原生", UiTheme.ButtonStyle.TEXT_DANGER,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { resetAllToNativeWithConfirm(); }
                }));
        card.addView(row2);
        return card;
    }

    private LinearLayout buildProfilesContainer() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout topCard = UiTheme.makeCard(this);
        topCard.addView(UiTheme.makeText(this, "场景分流 Profiles", 16, true, palette.onSurface));
        TextView desc = UiTheme.makeText(this,
                "按当前前台应用自动切换手势动作，未覆写的手势自动继承全局。",
                13, false, palette.onSurfaceVariant);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dLp.topMargin = UiTheme.dp(this, 4);
        topCard.addView(desc, dLp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bLp.topMargin = UiTheme.dp(this, 12);
        btnRow.setLayoutParams(bLp);

        LinearLayout.LayoutParams gapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gapLp.rightMargin = UiTheme.dp(this, 8);

        btnRow.addView(UiTheme.makeButton(this, "+ 新建场景", UiTheme.ButtonStyle.FILLED,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { openProfileEditor(null); }
                }), gapLp);
        btnRow.addView(UiTheme.makeButton(this, "+ 模板库", UiTheme.ButtonStyle.TONAL,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showTemplateGallerySheet(); }
                }), new LinearLayout.LayoutParams(gapLp));
        topCard.addView(btnRow);

        safetySummaryView = UiTheme.makeText(this, "", 12, false, palette.onSurfaceVariant);
        LinearLayout.LayoutParams ssLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ssLp.topMargin = UiTheme.dp(this, 10);
        topCard.addView(safetySummaryView, ssLp);
        wrap.addView(topCard);

        profilesListContainer = new LinearLayout(this);
        profilesListContainer.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(profilesListContainer);

        return wrap;
    }

    private View buildKeyCard(final KeyId key) {
        LinearLayout card = UiTheme.makeCard(this);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleCol.addView(UiTheme.makeText(this, key.displayName, 17, true, palette.onSurface));
        titleCol.addView(UiTheme.makeText(this, key.hardwareSummary, 11, false, palette.onSurfaceVariant));
        top.addView(titleCol);

        Switch sw = UiTheme.makeSwitch(this);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (bindingUi) return;
                config.setKeyEnabled(key, isChecked);
                renderConfigToUi();
                saveAndBroadcast(key.displayName + "：" + (isChecked ? "已启用" : "已回归原生"));
            }
        });
        top.addView(sw);
        keySwitches.put(key, sw);
        card.addView(top);

        if (key == KeyId.POWER) {
            LinearLayout warnBox = new LinearLayout(this);
            warnBox.setOrientation(LinearLayout.VERTICAL);
            warnBox.setBackground(UiTheme.roundedShape(this, palette.errorContainer, 0, 0, UiTheme.RADIUS_CARD));
            warnBox.setPadding(UiTheme.dp(this, 14), UiTheme.dp(this, 10),
                    UiTheme.dp(this, 14), UiTheme.dp(this, 10));
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wlp.topMargin = UiTheme.dp(this, 10);
            warnBox.setLayoutParams(wlp);
            warnBox.addView(UiTheme.makeText(this,
                    "安全红线：电源长按建议保持「跟随系统」以保留关机/重启菜单；POWER+音量± 组合键永久放行原生。",
                    12, false, palette.onErrorContainer));
            card.addView(warnBox);
        }

        EnumMap<GestureType, GestureRowHolder> rowMap = new EnumMap<>(GestureType.class);
        for (final GestureType gesture : GestureType.values()) {
            LinearLayout row = UiTheme.makeInnerRow(this, true);

            ImageView iv = new ImageView(this);
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(
                    UiTheme.dp(this, 36), UiTheme.dp(this, 36));
            ivLp.rightMargin = UiTheme.dp(this, 14);
            row.addView(iv, ivLp);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            String gLbl = gesture.displayName;
            if (key == KeyId.POWER && gesture == GestureType.LONG) {
                gLbl += " · 建议原生";
            }
            col.addView(UiTheme.makeText(this, gLbl, 12, false, palette.onSurfaceVariant));

            TextView valTv = UiTheme.makeText(this, "", 15, true, palette.onSurface);
            valTv.setMaxLines(2);
            valTv.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(valTv);

            TextView subTv = UiTheme.makeText(this, "", 11, false, palette.onSurfaceVariant);
            subTv.setMaxLines(2);
            subTv.setEllipsize(TextUtils.TruncateAt.END);
            subTv.setVisibility(View.GONE);
            col.addView(subTv);

            row.addView(col);

            TextView chev = UiTheme.makeText(this, "›", 18, true, palette.onSurfaceVariant);
            chev.setPadding(UiTheme.dp(this, 8), 0, 0, 0);
            row.addView(chev);

            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onGestureCellClicked(key, gesture); }
            });

            rowMap.put(gesture, new GestureRowHolder(iv, valTv, subTv));
            card.addView(row);
        }
        cellHolders.put(key, rowMap);
        return card;
    }

    private View buildDiagnosticsCard() {
        LinearLayout card = UiTheme.makeCard(this);
        card.addView(UiTheme.makeText(this, "运行日志与协议", 16, true, palette.onSurface));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = UiTheme.dp(this, 10);
        btnRow.setLayoutParams(blp);

        LinearLayout.LayoutParams gapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gapLp.rightMargin = UiTheme.dp(this, 8);

        btnRow.addView(UiTheme.makeButton(this, "刷新", UiTheme.ButtonStyle.TONAL,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        refreshStatus();
                        refreshConflictBanner();
                    }
                }), gapLp);
        btnRow.addView(UiTheme.makeButton(this, "查看 JSON", UiTheme.ButtonStyle.OUTLINED,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showJsonPreviewSheet(); }
                }), new LinearLayout.LayoutParams(gapLp));
        btnRow.addView(UiTheme.makeButton(this, "清空", UiTheme.ButtonStyle.TEXT,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { deleteFile(StatusProvider.LOG_FILENAME); } catch (Throwable ignored) {}
                        refreshStatus();
                    }
                }));
        card.addView(btnRow);

        // Styled M3 inner log card
        LinearLayout logBox = new LinearLayout(this);
        logBox.setOrientation(LinearLayout.VERTICAL);
        logBox.setBackground(UiTheme.roundedShape(this, palette.surfaceContainerHigh, 0, 0, UiTheme.RADIUS_CARD));
        logBox.setPadding(UiTheme.dp(this, 14), UiTheme.dp(this, 12),
                UiTheme.dp(this, 14), UiTheme.dp(this, 12));
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lbLp.topMargin = UiTheme.dp(this, 10);
        logBox.setLayoutParams(lbLp);

        logView = UiTheme.makeText(this, "暂无钩子运行日志", 11, false, palette.onSurfaceVariant);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setLineSpacing(UiTheme.dp(this, 2), 1f);
        logBox.addView(logView);
        card.addView(logBox);

        return card;
    }

    private Switch addSwitchRow(LinearLayout parent, String title, String subtitle,
                                CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiTheme.dp(this, 52));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = UiTheme.dp(this, 8);
        row.setLayoutParams(rlp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(UiTheme.makeText(this, title, 14, true, palette.onSurface));
        col.addView(UiTheme.makeText(this, subtitle, 12, false, palette.onSurfaceVariant));
        row.addView(col);

        Switch sw = UiTheme.makeSwitch(this);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        parent.addView(row);
        return sw;
    }

    // --------------------------------------------------- Profiles & Templates

    private void openProfileEditor(ActionProfile existing) {
        ProfileEditorDialog.show(this, existing, config, new ProfileEditorDialog.Callback() {
            @Override
            public void onSave(ActionProfile profile, boolean powerLongConfirmed) {
                if (powerLongConfirmed) config.powerLongPressExplicitlyBound = true;
                int idx = -1;
                for (int i = 0; i < config.profiles.size(); i++) {
                    if (config.profiles.get(i).id.equals(profile.id)) {
                        idx = i;
                        break;
                    }
                }
                if (idx >= 0) config.profiles.set(idx, profile);
                else config.profiles.add(profile);
                config.sortProfilesByPriority();
                config.updatedAt = System.currentTimeMillis();
                renderConfigToUi();
                saveAndBroadcast("已保存场景：" + profile.name);
            }

            @Override
            public void onDelete(String profileId) {
                for (int i = 0; i < config.profiles.size(); i++) {
                    if (config.profiles.get(i).id.equals(profileId)) {
                        config.profiles.remove(i);
                        break;
                    }
                }
                config.updatedAt = System.currentTimeMillis();
                renderConfigToUi();
                saveAndBroadcast("已删除场景");
            }
        });
    }

    private void showTemplateGallerySheet() {
        final UiTheme.BottomSheet sheet = UiTheme.createBottomSheet(
                this, "社区场景模板 (B1–B8)", "选择一个模板并按需微调生效应用与手势", true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(UiTheme.roundedShape(this, palette.surfaceContainerHigh, 0, 0, UiTheme.RADIUS_CARD));
        box.setPadding(UiTheme.dp(this, 8), UiTheme.dp(this, 4),
                UiTheme.dp(this, 8), UiTheme.dp(this, 4));
        sheet.body.addView(box);

        List<SceneTemplates.TemplateMeta> list = SceneTemplates.SCENE_TEMPLATES;
        for (int i = 0; i < list.size(); i++) {
            final SceneTemplates.TemplateMeta m = list.get(i);
            if (i > 0) box.addView(UiTheme.makeListDivider(this));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(UiTheme.dp(this, 60));
            row.setBackground(UiTheme.rippleSurface(this, Color.TRANSPARENT, 0, 0, UiTheme.RADIUS_CARD, palette.onSurface));
            row.setPadding(UiTheme.dp(this, 10), UiTheme.dp(this, 10),
                    UiTheme.dp(this, 12), UiTheme.dp(this, 10));

            TextView badge = UiTheme.makeChip(this, m.code, !m.experimental);
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bLp.rightMargin = UiTheme.dp(this, 12);
            row.addView(badge, bLp);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            col.addView(UiTheme.makeText(this, m.title, 14, true, palette.onSurface));
            TextView desc = UiTheme.makeText(this, m.description, 12, false, palette.onSurfaceVariant);
            desc.setMaxLines(2);
            col.addView(desc);
            row.addView(col);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sheet.dismiss();
                    openProfileEditor(SceneTemplates.createFromTemplate(m.code));
                }
            });
            box.addView(row);
        }

        sheet.footer.addView(UiTheme.makeButton(this, "取消", UiTheme.ButtonStyle.TEXT,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { sheet.dismiss(); }
                }));
        sheet.show();
    }

    private void openSafetySettings() {
        SafetySettingsDialog.show(this, config, new SafetySettingsDialog.OnSaved() {
            @Override
            public void onSaved() {
                renderConfigToUi();
                saveAndBroadcast("防坑开关与共存策略已同步");
            }
        });
    }

    private void renderProfilesList() {
        profilesListContainer.removeAllViews();
        safetySummaryView.setText("黑名单 " + config.blacklistPackages.size()
                + " 项 · 息屏 " + (config.allowWhenScreenOff ? "开" : "关")
                + " · 锁屏 " + (config.allowWhenKeyguardLocked ? "开" : "关")
                + " · OS 5.1 " + config.officialRemapPolicy);

        if (config.profiles.isEmpty()) {
            LinearLayout empty = UiTheme.makeCard(this);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.setPadding(UiTheme.dp(this, 24), UiTheme.dp(this, 28),
                    UiTheme.dp(this, 24), UiTheme.dp(this, 28));

            ImageView iv = new ImageView(this);
            iv.setImageDrawable(ActionIcons.forActionType(this, ActionType.LAUNCH_APP, 48, true));
            empty.addView(iv, new LinearLayout.LayoutParams(UiTheme.dp(this, 48), UiTheme.dp(this, 48)));

            TextView t1 = UiTheme.makeText(this, "还没有场景分流", 16, true, palette.onSurface);
            LinearLayout.LayoutParams t1Lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            t1Lp.topMargin = UiTheme.dp(this, 12);
            empty.addView(t1, t1Lp);

            TextView t2 = UiTheme.makeText(this,
                    "点「+ 新建场景」或「+ 模板库」创建，例如在相机内短按即拍快门",
                    13, false, palette.onSurfaceVariant);
            t2.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams t2Lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            t2Lp.topMargin = UiTheme.dp(this, 6);
            empty.addView(t2, t2Lp);
            profilesListContainer.addView(empty);
            return;
        }

        for (final ActionProfile prof : config.profiles) {
            LinearLayout card = UiTheme.makeCard(this, true);

            // Row 1: Title + Priority chip + Switch
            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout titleWrap = new LinearLayout(this);
            titleWrap.setOrientation(LinearLayout.VERTICAL);
            titleWrap.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout nameLine = new LinearLayout(this);
            nameLine.setOrientation(LinearLayout.HORIZONTAL);
            nameLine.setGravity(Gravity.CENTER_VERTICAL);

            TextView nameTv = UiTheme.makeText(this, prof.name, 15, true,
                    prof.enabled ? palette.onSurface : palette.onSurfaceVariant);
            nameTv.setMaxLines(2);
            nameTv.setEllipsize(TextUtils.TruncateAt.END);
            nameTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            nameLine.addView(nameTv);

            TextView pChip = UiTheme.makeChip(this, "P" + prof.priority, prof.enabled);
            LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pcLp.leftMargin = UiTheme.dp(this, 6);
            nameLine.addView(pChip, pcLp);

            if (prof.experimental) {
                TextView expChip = UiTheme.makeText(this, "实验", 11, true, palette.onErrorContainer);
                expChip.setBackground(UiTheme.roundedShape(this, palette.errorContainer, 0, 0, UiTheme.RADIUS_PILL));
                expChip.setPadding(UiTheme.dp(this, 8), UiTheme.dp(this, 3),
                        UiTheme.dp(this, 8), UiTheme.dp(this, 3));
                LinearLayout.LayoutParams ecLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ecLp.leftMargin = UiTheme.dp(this, 6);
                nameLine.addView(expChip, ecLp);
            }
            titleWrap.addView(nameLine);
            top.addView(titleWrap);

            Switch sw = UiTheme.makeSwitch(this);
            sw.setChecked(prof.enabled);
            sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    prof.enabled = isChecked;
                    config.updatedAt = System.currentTimeMillis();
                    renderConfigToUi();
                    saveAndBroadcast("场景「" + prof.name + "」" + (isChecked ? "已启用" : "已停用"));
                }
            });
            top.addView(sw);
            card.addView(top);

            // Row 2: Target Apps row (real 24dp rounded app icons + app names!)
            LinearLayout appsRow = new LinearLayout(this);
            appsRow.setOrientation(LinearLayout.HORIZONTAL);
            appsRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            arLp.topMargin = UiTheme.dp(this, 10);
            appsRow.setLayoutParams(arLp);
            populateProfileAppsRow(appsRow, prof);
            card.addView(appsRow);

            // Subtle separator between target apps & overrides
            card.addView(UiTheme.makeDivider(this));

            // Row 3: Concise override summary (activity labels resolved live from LabelStore)
            TextView ovTv = UiTheme.makeText(this, prof.overrideSummary(
                    new ActionProfile.SpecNamer() {
                        @Override
                        public String nameFor(ActionSpec spec) {
                            if (spec == null || spec.type != ActionType.LAUNCH_ACTIVITY) {
                                return null;
                            }
                            return LabelStore.displayLabel(MainActivity.this, spec);
                        }
                    }), 13, true,
                    prof.enabled ? palette.primary : palette.onSurfaceVariant);
            ovTv.setMaxLines(3);
            ovTv.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(ovTv);

            // Row 3b: small component line for bound activities
            String actComponents = collectActivityComponents(prof);
            if (actComponents != null) {
                TextView compTv = UiTheme.makeText(this, actComponents, 10, false,
                        palette.onSurfaceVariant);
                compTv.setMaxLines(2);
                compTv.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams compLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                compLp.topMargin = UiTheme.dp(this, 4);
                card.addView(compTv, compLp);
            }

            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openProfileEditor(prof); }
            });
            profilesListContainer.addView(card);
        }
    }

    private void populateProfileAppsRow(LinearLayout row, ActionProfile prof) {
        List<String> pkgs = prof.match.packages;
        if (pkgs == null || pkgs.isEmpty() || pkgs.contains("*")) {
            row.addView(UiTheme.makeChip(this, "任意应用", false));
            return;
        }
        int shownIcons = 0;
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < pkgs.size(); i++) {
            String pkg = pkgs.get(i);
            if (shownIcons < 3) {
                Drawable icon = AppIconCache.getRoundedAppIcon(this, pkg, 24);
                if (icon != null) {
                    ImageView iv = new ImageView(this);
                    iv.setImageDrawable(icon);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            UiTheme.dp(this, 24), UiTheme.dp(this, 24));
                    lp.rightMargin = UiTheme.dp(this, 6);
                    row.addView(iv, lp);
                    shownIcons++;
                }
            }
            if (i < 2) {
                if (i > 0) names.append(" · ");
                names.append(AppIconCache.getAppName(this, pkg));
            }
        }
        if (pkgs.size() > 2) {
            names.append(" 等 ").append(pkgs.size()).append(" 个应用");
        }
        TextView tv = UiTheme.makeText(this, names.toString(), 13, false, palette.onSurface);
        tv.setMaxLines(2);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);
    }

    private void refreshConflictBanner() {
        conflictBanner.removeAllViews();
        if (conflictDismissed) {
            conflictBanner.setVisibility(View.GONE);
            return;
        }
        List<ConflictDetector.Warning> warnings = ConflictDetector.scan(this);
        for (ConflictDetector.Warning w : warnings) {
            if ("INFO".equals(w.level)) continue;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(UiTheme.roundedShape(this, palette.errorContainer, 0, 0, UiTheme.RADIUS_CARD));
            card.setPadding(UiTheme.dp(this, 16), UiTheme.dp(this, 14),
                    UiTheme.dp(this, 16), UiTheme.dp(this, 10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = UiTheme.dp(this, 8);
            card.setLayoutParams(lp);

            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            ImageView warnIv = new ImageView(this);
            warnIv.setImageDrawable(ActionIcons.makeInfoBadge(
                    this, "warn", palette.error, palette.onError, 28));
            LinearLayout.LayoutParams wiLp = new LinearLayout.LayoutParams(
                    UiTheme.dp(this, 28), UiTheme.dp(this, 28));
            wiLp.rightMargin = UiTheme.dp(this, 10);
            head.addView(warnIv, wiLp);

            TextView titleTv = UiTheme.makeText(this, w.title, 14, true, palette.onErrorContainer);
            titleTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(titleTv);

            TextView closeBtn = UiTheme.makeButton(this, "✕", UiTheme.ButtonStyle.TEXT,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            conflictDismissed = true;
                            conflictBanner.setVisibility(View.GONE);
                        }
                    });
            closeBtn.setTextColor(palette.onErrorContainer);
            closeBtn.setPadding(UiTheme.dp(this, 10), UiTheme.dp(this, 6), 0, UiTheme.dp(this, 6));
            head.addView(closeBtn);
            card.addView(head);

            TextView descTv = UiTheme.makeText(this, w.detail, 12, false, palette.onErrorContainer);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dLp.topMargin = UiTheme.dp(this, 6);
            card.addView(descTv, dLp);

            LinearLayout actRow = new LinearLayout(this);
            actRow.setGravity(Gravity.END);
            TextView goBtn = UiTheme.makeButton(this, "前往无障碍设置", UiTheme.ButtonStyle.TEXT,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                            catch (Throwable ignored) {}
                        }
                    });
            goBtn.setTextColor(palette.onErrorContainer);
            goBtn.setPadding(UiTheme.dp(this, 12), UiTheme.dp(this, 8), 0, UiTheme.dp(this, 4));
            actRow.addView(goBtn);
            card.addView(actRow);
            conflictBanner.addView(card);
        }
        conflictBanner.setVisibility(conflictBanner.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    // -------------------------------------------------------- cell interaction

    private void onGestureCellClicked(final KeyId key, final GestureType gesture) {
        ActionSpec current = config.getKey(key).get(gesture);
        ActionPickerDialog.show(this, key, gesture, current, false,
                new ActionPickerDialog.OnActionSelected() {
                    @Override
                    public void onSelected(final ActionSpec picked) {
                        if (key == KeyId.POWER && gesture == GestureType.LONG && !picked.isSystem()) {
                            UiTheme.showConfirmSheet(MainActivity.this,
                                    "覆盖电源键长按",
                                    "电源键长按原生用于打开全局电源菜单（关机/重启/Recovery）。确定覆盖为「"
                                            + ProfileEditorDialog.formatConciseValue(MainActivity.this, picked) + "」吗？\n\n"
                                            + "提示：POWER+音量+ 组合键仍将永久放行原生电源菜单。",
                                    "确认覆盖", true, new Runnable() {
                                        @Override
                                        public void run() {
                                            config.powerLongPressExplicitlyBound = true;
                                            config.setAction(key, gesture, picked);
                                            renderConfigToUi();
                                            saveAndBroadcast("已覆盖电源长按");
                                        }
                                    });
                            return;
                        }
                        config.setAction(key, gesture, picked);
                        renderConfigToUi();
                        saveAndBroadcast(key.displayName + " " + gesture.displayName + "已更新");
                    }
                });
    }

    private void applyV2PresetWithConfirm() {
        UiTheme.showConfirmSheet(this,
                "套用 v2 相机快门预设",
                "• 非相机 App 短按 EK → 打开相机\n"
                        + "• 进入相机 App 后短按 EK → 相机快门 (auto)\n"
                        + "• 双按 EK → 支付宝扫一扫\n"
                        + "• 长按 EK → 手电筒开关\n"
                        + "• 附带阅读翻页 / 音乐切歌 / 游戏防误触场景",
                "立即套用", false, new Runnable() {
                    @Override
                    public void run() {
                        config = KeyPilotConfig.createV2ContextPreset();
                        renderConfigToUi();
                        saveAndBroadcast("已套用 v2 相机快门预设");
                    }
                });
    }

    private void applyV1PresetWithConfirm() {
        UiTheme.showConfirmSheet(this,
                "套用 v1 测试预设",
                "按 v1 测试矩阵配置全局 6 项映射并清空场景分流。",
                "立即套用", false, new Runnable() {
                    @Override
                    public void run() {
                        config = KeyPilotConfig.createTestPlanPreset();
                        renderConfigToUi();
                        saveAndBroadcast("已套用 v1 测试预设");
                    }
                });
    }

    private void resetAllToNativeWithConfirm() {
        UiTheme.showConfirmSheet(this,
                "全部恢复原生",
                "将四颗按键全部手势及场景分流重置为「跟随系统」。",
                "重置", true, new Runnable() {
                    @Override
                    public void run() {
                        config = KeyPilotConfig.createDefault();
                        renderConfigToUi();
                        saveAndBroadcast("已全部重置为原生");
                    }
                });
    }

    private void showJsonPreviewSheet() {
        final UiTheme.BottomSheet sheet = UiTheme.createBottomSheet(
                this, "JSON 协议 (v2)", KeyPilotConfig.SYSTEM_CONFIG_PATH, true);
        LinearLayout codeCard = new LinearLayout(this);
        codeCard.setBackground(UiTheme.roundedShape(this, palette.surfaceContainerHigh, 0, 0, UiTheme.RADIUS_CARD));
        codeCard.setPadding(UiTheme.dp(this, 14), UiTheme.dp(this, 12),
                UiTheme.dp(this, 14), UiTheme.dp(this, 12));
        TextView tv = UiTheme.makeText(this, config.toJsonString(2), 11, false, palette.onSurface);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        codeCard.addView(tv);
        sheet.body.addView(codeCard);

        sheet.footer.addView(UiTheme.makeButton(this, "关闭", UiTheme.ButtonStyle.FILLED,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { sheet.dismiss(); }
                }));
        sheet.show();
    }

    // --------------------------------------------------- persistence & render

    private void renderConfigToUi() {
        bindingUi = true;
        try {
            masterSwitch.setChecked(config.masterEnabled);
            toastSwitch.setChecked(config.showToast);
            hapticSwitch.setChecked(config.hapticFeedback);
            imeVoiceSwitch.setChecked(config.preserveImeVoice);

            for (KeyId k : KeyId.values()) {
                KeyPilotConfig.KeyBindings kb = config.getKey(k);
                Switch sw = keySwitches.get(k);
                if (sw != null) sw.setChecked(kb.enabled);

                EnumMap<GestureType, GestureRowHolder> map = cellHolders.get(k);
                if (map == null) continue;
                for (GestureType g : GestureType.values()) {
                    GestureRowHolder h = map.get(g);
                    if (h == null) continue;
                    ActionSpec eff = config.getEffectiveAction(k, g);
                    ActionSpec raw = kb.get(g);

                    h.iconView.setImageDrawable(ActionIcons.forActionSpec(this, raw, 36));
                    h.valueView.setText(ProfileEditorDialog.formatConciseValue(this, raw));

                    // Show owning app name / subtitle only when informative
                    String ownerPkg = ActionIcons.resolveOwnerPackage(raw);
                    String sub = null;
                    if (raw.type == ActionType.LAUNCH_ACTIVITY && raw.className != null) {
                        // named activity: small component line under the label
                        sub = raw.packageName + "/"
                                + LabelStore.shortClass(raw.packageName, raw.className);
                    } else if (ownerPkg != null && !raw.isSystem()) {
                        String ownerName = AppIconCache.getAppName(this, ownerPkg);
                        if (!ownerName.equals(h.valueView.getText().toString())) {
                            sub = ownerName + " · " + ownerPkg;
                        } else {
                            sub = ownerPkg;
                        }
                    } else if (raw.dataUri != null) {
                        sub = raw.dataUri;
                    }
                    if (sub != null && !sub.isEmpty()) {
                        h.subView.setText(sub);
                        h.subView.setVisibility(View.VISIBLE);
                    } else {
                        h.subView.setVisibility(View.GONE);
                    }

                    boolean active = config.masterEnabled && kb.enabled;
                    h.iconView.setAlpha(active ? 1.0f : 0.45f);
                    if (!active) {
                        h.valueView.setTextColor(palette.onSurfaceVariant);
                    } else if (!eff.isSystem()) {
                        h.valueView.setTextColor(palette.primary);
                    } else {
                        h.valueView.setTextColor(palette.onSurface);
                    }
                }
            }
            updateTabStyles();
            renderProfilesList();
        } finally {
            bindingUi = false;
        }
    }

    private KeyPilotConfig loadLocalConfig() {
        File f = new File(getFilesDir(), KeyPilotConfig.APP_CONFIG_FILENAME);
        if (!f.exists()) return KeyPilotConfig.createDefault();
        try (FileInputStream fis = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            return KeyPilotConfig.fromJsonString(new String(bos.toByteArray(), StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            return KeyPilotConfig.createDefault();
        }
    }

    private void saveAndBroadcast(String toastMsg) {
        String json = config.toJsonString(2);
        try {
            File f = new File(getFilesDir(), KeyPilotConfig.APP_CONFIG_FILENAME);
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
        } catch (Throwable ignored) {
        }
        try {
            Intent intent = new Intent(KeyPilotConfig.ACTION_CONFIG_UPDATE);
            intent.putExtra(KeyPilotConfig.EXTRA_CONFIG_JSON, json);
            intent.putExtra(KeyPilotConfig.EXTRA_VERSION, KeyPilotConfig.SCHEMA_VERSION);
            intent.putExtra(KeyPilotConfig.EXTRA_TIMESTAMP, System.currentTimeMillis());
            sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
        if (toastMsg != null && !toastMsg.isEmpty()) {
            Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show();
        }
        try {
            getWindow().getDecorView().postDelayed(new Runnable() {
                @Override public void run() { refreshStatus(); }
            }, 600L);
        } catch (Throwable ignored) {
        }
    }

    private void refreshStatus() {
        File hb = new File(getFilesDir(), StatusProvider.HEARTBEAT_FILENAME);
        if (hb.exists()) {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(hb), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    heartbeatView.setText(formatHeartbeatText(line.trim()));
                    heartbeatView.setTextColor(palette.onSurface);
                    statusDot.setBackground(UiTheme.roundedShape(this, palette.primary, 0, 0, UiTheme.RADIUS_PILL));
                }
            } catch (Throwable ignored) {
            }
        } else {
            heartbeatView.setText("未连接 system_server（请在 LSPosed/Vector 启用并重启）");
            heartbeatView.setTextColor(palette.error);
            statusDot.setBackground(UiTheme.roundedShape(this, palette.error, 0, 0, UiTheme.RADIUS_PILL));
        }

        List<String> lines = new ArrayList<>();
        File f = new File(getFilesDir(), StatusProvider.LOG_FILENAME);
        if (f.exists()) {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) lines.add(line);
            } catch (Throwable ignored) {
            }
        }
        if (lines.isEmpty()) {
            logView.setText("暂无钩子运行日志");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.size() - 25); i < lines.size(); i++) {
            sb.append(lines.get(i)).append('\n');
        }
        logView.setText(sb.toString().trim());
    }

    /** Formats raw heartbeat ("2026-11-25 14:17:49 | synced (v2 master=true ...)") into concise complete text. */
    private static String formatHeartbeatText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "已连接 system_server";
        }
        String timeStr = "";
        int pipe = raw.indexOf('|');
        String head = (pipe >= 0 ? raw.substring(0, pipe) : raw).trim();
        String tail = (pipe >= 0 ? raw.substring(pipe + 1) : "").trim();

        // Extract HH:mm:ss from "YYYY-MM-DD HH:mm:ss"
        int sp = head.indexOf(' ');
        if (sp >= 0 && sp + 1 < head.length()) {
            timeStr = head.substring(sp + 1).trim();
        } else if (head.contains(":")) {
            timeStr = head;
        }

        String lower = tail.toLowerCase(java.util.Locale.US);
        String state;
        if (lower.contains("master=false")) {
            state = "已同步（总开关关闭）";
        } else if (lower.contains("synced") || lower.contains("reloaded")) {
            state = "已同步";
        } else if (lower.contains("boot") || lower.contains("init") || lower.contains("loaded")) {
            state = "钩子就绪";
        } else if (!tail.isEmpty()) {
            state = tail;
        } else {
            state = "已就绪";
        }

        if (!timeStr.isEmpty()) {
            return "已连接 system_server · " + timeStr + " " + state;
        }
        return "已连接 system_server · " + state;
    }
}
