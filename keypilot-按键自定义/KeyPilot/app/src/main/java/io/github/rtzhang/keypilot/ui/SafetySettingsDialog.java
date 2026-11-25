package io.github.rtzhang.keypilot.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import io.github.rtzhang.keypilot.config.KeyPilotConfig;
import io.github.rtzhang.keypilot.config.SceneTemplates;

/**
 * Material 3 Bottom Sheet for the 6 P1 Safety Switches (docs/V2-PLAN.md §1.4).
 * Uses unified M3 switches, M3 dropdown sheet, M3 icon-based app picker for blacklist,
 * and M3 errorContainer conflict cards.
 */
public final class SafetySettingsDialog {

    public interface OnSaved {
        void onSaved();
    }

    private SafetySettingsDialog() {
    }

    public static void show(final Activity activity,
                            final KeyPilotConfig config,
                            final OnSaved onSaved) {
        final UiTheme.Palette p = UiTheme.colors(activity);
        final UiTheme.BottomSheet sheet = UiTheme.createBottomSheet(
                activity,
                "防坑开关与共存策略",
                "保护支付/游戏零干扰，配置息锁屏权限与 OS 5.1 共存规则",
                true);

        // ---- 1. Screen-off & Lockscreen ----
        sheet.body.addView(UiTheme.makeSectionHeader(activity, "息屏与锁屏控制"));
        final Switch screenOffSw = addSwitchRow(activity, sheet.body,
                "息屏时允许全局自定义动作",
                "关闭后息屏按压回归系统原生",
                config.allowWhenScreenOff);
        final Switch keyguardSw = addSwitchRow(activity, sheet.body,
                "锁屏时允许全局自定义动作",
                "关闭后锁屏态仅允许原生及明确设锁屏可用的场景",
                config.allowWhenKeyguardLocked);

        // ---- 2. Nothing OS 5.1 Official Remap Policy ----
        sheet.body.addView(UiTheme.makeDivider(activity));
        sheet.body.addView(UiTheme.makeSectionHeader(activity, "Nothing OS 5.1 官方重映射共存"));

        final String[] policyVals = {
                KeyPilotConfig.POLICY_TAKEOVER,
                KeyPilotConfig.POLICY_YIELD_SINGLE_CLICK,
                KeyPilotConfig.POLICY_YIELD_ALL
        };
        final String[] policyLabels = {
                "KeyPilot 独占接管（默认）",
                "常规单击让位官方，场景与双/长按归 KeyPilot",
                "Essential Key 全手势让位官方"
        };
        final int[] selPolicy = {0};
        for (int i = 0; i < policyVals.length; i++) {
            if (policyVals[i].equals(config.officialRemapPolicy)) selPolicy[0] = i;
        }
        final LinearLayout policyRow = UiTheme.makeDropdownField(
                activity, "共存模式", policyLabels[selPolicy[0]], null);
        policyRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiTheme.showOptionSheet(activity, "OS 5.1 共存模式",
                        "若官方重映射开启，推荐「常规单击让位官方」以保留相机内快门等场景覆写",
                        policyLabels, selPolicy[0], new UiTheme.OnOptionPicked() {
                            @Override public void onPicked(int idx) {
                                selPolicy[0] = idx;
                                UiTheme.setDropdownValue(policyRow, policyLabels[idx]);
                            }
                        });
            }
        });
        sheet.body.addView(policyRow);

        // ---- 3. Global App Blacklist ----
        sheet.body.addView(UiTheme.makeDivider(activity));
        sheet.body.addView(UiTheme.makeSectionHeader(activity, "全局 App 黑名单（0ms 原生旁路）"));

        final LinearLayout blPreview = new LinearLayout(activity);
        blPreview.setOrientation(LinearLayout.HORIZONTAL);
        blPreview.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bpLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bpLp.topMargin = UiTheme.dp(activity, 4);
        blPreview.setLayoutParams(bpLp);
        sheet.body.addView(blPreview);

        final EditText blIn = UiTheme.makeInput(activity, "逗号分隔包名（如 com.icbc, com.unionpay）",
                joinCsv(config.blacklistPackages));
        sheet.body.addView(blIn);
        refreshBlacklistPreview(activity, blPreview, config.blacklistPackages);

        LinearLayout blBtnRow = new LinearLayout(activity);
        blBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bbLp.topMargin = UiTheme.dp(activity, 8);
        blBtnRow.setLayoutParams(bbLp);

        TextView pickBlBtn = UiTheme.makeButton(activity, "选择应用", UiTheme.ButtonStyle.TONAL,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ActionPickerDialog.showMultiAppPicker(activity, "选择黑名单应用",
                                parseCsv(blIn.getText().toString()),
                                new ActionPickerDialog.MultiAppCallback() {
                                    @Override
                                    public void onPicked(List<String> selected) {
                                        blIn.setText(joinCsv(selected));
                                        refreshBlacklistPreview(activity, blPreview, selected);
                                    }
                                });
                    }
                });
        LinearLayout.LayoutParams b1Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b1Lp.rightMargin = UiTheme.dp(activity, 8);
        blBtnRow.addView(pickBlBtn, b1Lp);

        TextView addBanksBtn = UiTheme.makeButton(activity, "+ 银行/支付", UiTheme.ButtonStyle.OUTLINED,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        List<String> cur = parseCsv(blIn.getText().toString());
                        for (String s : SceneTemplates.SUGGESTED_BLACKLIST) {
                            if (!cur.contains(s)) cur.add(s);
                        }
                        blIn.setText(joinCsv(cur));
                        refreshBlacklistPreview(activity, blPreview, cur);
                    }
                });
        LinearLayout.LayoutParams b2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b2Lp.rightMargin = UiTheme.dp(activity, 8);
        blBtnRow.addView(addBanksBtn, b2Lp);

        blBtnRow.addView(UiTheme.makeButton(activity, "清空", UiTheme.ButtonStyle.TEXT_DANGER,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        blIn.setText("");
                        refreshBlacklistPreview(activity, blPreview, new ArrayList<String>());
                    }
                }));
        sheet.body.addView(blBtnRow);

        // ---- 4. Conflict Detector ----
        sheet.body.addView(UiTheme.makeDivider(activity));
        sheet.body.addView(UiTheme.makeSectionHeader(activity, "无障碍冲突检测"));
        List<ConflictDetector.Warning> warnings = ConflictDetector.scan(activity);
        if (warnings.isEmpty()) {
            LinearLayout okBox = UiTheme.makeInnerRow(activity, false);
            okBox.addView(UiTheme.makeText(activity,
                    "未检测到 Select to Speak 或其它无障碍按键服务冲突",
                    13, true, p.primary));
            sheet.body.addView(okBox);
        } else {
            for (ConflictDetector.Warning w : warnings) {
                LinearLayout errCard = new LinearLayout(activity);
                errCard.setOrientation(LinearLayout.VERTICAL);
                errCard.setBackground(UiTheme.roundedShape(activity, p.errorContainer, 0, 0, UiTheme.RADIUS_CARD));
                errCard.setPadding(UiTheme.dp(activity, 16), UiTheme.dp(activity, 12),
                        UiTheme.dp(activity, 16), UiTheme.dp(activity, 12));
                LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                eLp.topMargin = UiTheme.dp(activity, 8);
                errCard.setLayoutParams(eLp);
                errCard.addView(UiTheme.makeText(activity, w.title, 14, true, p.onErrorContainer));
                TextView dTv = UiTheme.makeText(activity, w.detail, 12, false, p.onErrorContainer);
                LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dLp.topMargin = UiTheme.dp(activity, 4);
                errCard.addView(dTv, dLp);
                sheet.body.addView(errCard);
            }
            TextView openA11y = UiTheme.makeButton(activity, "前往系统无障碍设置", UiTheme.ButtonStyle.OUTLINED,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            try {
                                activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            LinearLayout.LayoutParams oLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            oLp.topMargin = UiTheme.dp(activity, 8);
            sheet.body.addView(openA11y, oLp);
        }

        // ---- Footer ----
        TextView cancelBtn = UiTheme.makeButton(activity, "取消", UiTheme.ButtonStyle.TEXT,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { sheet.dismiss(); }
                });
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.rightMargin = UiTheme.dp(activity, 8);
        sheet.footer.addView(cancelBtn, cLp);

        sheet.footer.addView(UiTheme.makeButton(activity, "保存并同步", UiTheme.ButtonStyle.FILLED,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        config.allowWhenScreenOff = screenOffSw.isChecked();
                        config.allowWhenKeyguardLocked = keyguardSw.isChecked();
                        config.officialRemapPolicy = policyVals[selPolicy[0]];
                        config.blacklistPackages.clear();
                        config.blacklistPackages.addAll(parseCsv(blIn.getText().toString()));
                        config.updatedAt = System.currentTimeMillis();
                        sheet.dismiss();
                        onSaved.onSaved();
                    }
                }));
        sheet.show();
    }

    private static Switch addSwitchRow(Activity activity, LinearLayout parent,
                                       String title, String sub, boolean checked) {
        UiTheme.Palette p = UiTheme.colors(activity);
        LinearLayout row = UiTheme.makeInnerRow(activity, false);
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(UiTheme.makeText(activity, title, 14, true, p.onSurface));
        col.addView(UiTheme.makeText(activity, sub, 12, false, p.onSurfaceVariant));
        row.addView(col);
        Switch sw = UiTheme.makeSwitch(activity);
        sw.setChecked(checked);
        row.addView(sw);
        parent.addView(row);
        return sw;
    }

    private static void refreshBlacklistPreview(Activity activity, LinearLayout row, List<String> pkgs) {
        row.removeAllViews();
        UiTheme.Palette p = UiTheme.colors(activity);
        if (pkgs == null || pkgs.isEmpty()) {
            row.addView(UiTheme.makeText(activity, "当前无黑名单应用", 12, false, p.onSurfaceVariant));
            return;
        }
        int shown = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pkgs.size(); i++) {
            String pkg = pkgs.get(i);
            if (shown < 4) {
                Drawable icon = AppIconCache.getRoundedAppIcon(activity, pkg, 24);
                if (icon != null) {
                    ImageView iv = new ImageView(activity);
                    iv.setImageDrawable(icon);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            UiTheme.dp(activity, 24), UiTheme.dp(activity, 24));
                    lp.rightMargin = UiTheme.dp(activity, 6);
                    row.addView(iv, lp);
                    shown++;
                }
            }
            if (i < 2) {
                if (i > 0) sb.append(" · ");
                sb.append(AppIconCache.getAppName(activity, pkg));
            }
        }
        if (pkgs.size() > 2) {
            sb.append(" 等 ").append(pkgs.size()).append(" 个应用");
        }
        TextView tv = UiTheme.makeText(activity, sb.toString(), 12, true, p.onSurface);
        tv.setMaxLines(2);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);
    }

    private static String joinCsv(List<String> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static List<String> parseCsv(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String part : raw.split("[,;\\n]")) {
            String t = part.trim();
            if (!t.isEmpty() && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }
}
