package io.github.rtzhang.keypilot.ui;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.text.InputType;
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

import io.github.rtzhang.keypilot.config.ActionProfile;
import io.github.rtzhang.keypilot.config.ActionSpec;
import io.github.rtzhang.keypilot.config.ContextMatcher;
import io.github.rtzhang.keypilot.config.GestureType;
import io.github.rtzhang.keypilot.config.KeyId;
import io.github.rtzhang.keypilot.config.KeyPilotConfig;

/**
 * Material 3 Bottom Sheet editor for creating/editing a per-app ActionProfile.
 * Uses unified M3 inputs (26dp), M3 Switches, M3 dropdown sheets, M3 icon app picker,
 * and icon-badged gesture override rows.
 */
public final class ProfileEditorDialog {

    public interface Callback {
        void onSave(ActionProfile profile, boolean powerLongConfirmed);
        void onDelete(String profileId);
    }

    private ProfileEditorDialog() {
    }

    public static void show(final Activity activity,
                            final ActionProfile existing,
                            final KeyPilotConfig globalConfig,
                            final Callback callback) {
        final UiTheme.Palette p = UiTheme.colors(activity);
        final boolean isNew = (existing == null);
        final ActionProfile draft = isNew
                ? new ActionProfile("prof_" + System.currentTimeMillis(), "新应用场景",
                        true, 150, ContextMatcher.forPackages(new ArrayList<String>()), false)
                : existing.copy();
        final boolean[] powerLongConfirmed = {
                globalConfig != null && globalConfig.powerLongPressExplicitlyBound
        };

        final UiTheme.BottomSheet sheet = UiTheme.createBottomSheet(
                activity,
                isNew ? "新建场景分流" : "编辑场景分流",
                "设置生效应用与手势覆写，未覆写的手势自动继承全局",
                true);

        // ---- Basic Info ----
        sheet.body.addView(UiTheme.makeSectionHeader(activity, "基本信息"));
        sheet.body.addView(fieldLabel(activity, "场景名称"));
        final EditText nameIn = UiTheme.makeInput(activity, "例如：相机快门", draft.name);
        sheet.body.addView(nameIn);

        LinearLayout priRow = UiTheme.makeInnerRow(activity, false);
        LinearLayout priCol = new LinearLayout(activity);
        priCol.setOrientation(LinearLayout.VERTICAL);
        priCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        priCol.addView(UiTheme.makeText(activity, "启用此场景", 14, true, p.onSurface));
        priCol.addView(UiTheme.makeText(activity, "关闭后所有手势自动回退全局", 12, false, p.onSurfaceVariant));
        priRow.addView(priCol);
        final Switch enSw = UiTheme.makeSwitch(activity);
        enSw.setChecked(draft.enabled);
        priRow.addView(enSw);
        sheet.body.addView(priRow);

        sheet.body.addView(fieldLabel(activity, "优先级（数值越大越优先）"));
        final EditText priIn = UiTheme.makeInput(activity, "默认 150",
                String.valueOf(draft.priority));
        priIn.setInputType(InputType.TYPE_CLASS_NUMBER);
        sheet.body.addView(priIn);

        // ---- Context Matcher ----
        sheet.body.addView(UiTheme.makeDivider(activity));
        sheet.body.addView(UiTheme.makeSectionHeader(activity, "生效条件"));

        // Selected target apps preview bar (icons + app names)
        final LinearLayout appsPreviewRow = new LinearLayout(activity);
        appsPreviewRow.setOrientation(LinearLayout.HORIZONTAL);
        appsPreviewRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams apLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        apLp.topMargin = UiTheme.dp(activity, 6);
        appsPreviewRow.setLayoutParams(apLp);
        sheet.body.addView(appsPreviewRow);

        final EditText pkgsIn = UiTheme.makeInput(activity,
                "目标包名（逗号分隔，支持 * 通配，留空=任意应用）",
                joinCsv(draft.match.packages));
        sheet.body.addView(pkgsIn);
        refreshAppsPreview(activity, appsPreviewRow, draft.match.packages);

        TextView pickAppsBtn = UiTheme.makeButton(activity, "+ 从已安装应用多选（带图标）",
                UiTheme.ButtonStyle.TONAL, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ActionPickerDialog.showMultiAppPicker(activity, "选择生效目标应用",
                                parseCsv(pkgsIn.getText().toString()),
                                new ActionPickerDialog.MultiAppCallback() {
                                    @Override
                                    public void onPicked(List<String> selected) {
                                        pkgsIn.setText(joinCsv(selected));
                                        refreshAppsPreview(activity, appsPreviewRow, selected);
                                    }
                                });
                    }
                });
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbLp.topMargin = UiTheme.dp(activity, 8);
        sheet.body.addView(pickAppsBtn, pbLp);

        final EditText exPkgsIn = UiTheme.makeInput(activity, "排除包名（可选，逗号分隔）",
                joinCsv(draft.match.excludePackages));
        sheet.body.addView(exPkgsIn);

        final EditText actRxIn = UiTheme.makeInput(activity, "Activity 正则（可选，留空=全页面）",
                draft.match.activityRegex);
        sheet.body.addView(actRxIn);

        final String[] kgVals = {
                ContextMatcher.KEYGUARD_ANY, ContextMatcher.KEYGUARD_UNLOCKED_ONLY, ContextMatcher.KEYGUARD_LOCKED_ONLY
        };
        final String[] kgLabels = {"不限（含锁屏）", "仅解锁态", "仅锁屏态"};
        final int[] selKg = {indexOf(kgVals, draft.match.keyguard, 0)};
        final LinearLayout kgRow = UiTheme.makeDropdownField(activity, "锁屏条件", kgLabels[selKg[0]], null);
        kgRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiTheme.showOptionSheet(activity, "锁屏条件", null, kgLabels, selKg[0],
                        new UiTheme.OnOptionPicked() {
                            @Override public void onPicked(int idx) {
                                selKg[0] = idx;
                                UiTheme.setDropdownValue(kgRow, kgLabels[idx]);
                            }
                        });
            }
        });
        sheet.body.addView(kgRow);

        final String[] scrVals = {
                ContextMatcher.SCREEN_ANY, ContextMatcher.SCREEN_ON_ONLY, ContextMatcher.SCREEN_OFF_ONLY
        };
        final String[] scrLabels = {"不限（含息屏）", "仅亮屏", "仅息屏"};
        final int[] selScr = {indexOf(scrVals, draft.match.screen, 0)};
        final LinearLayout scrRow = UiTheme.makeDropdownField(activity, "屏幕条件", scrLabels[selScr[0]], null);
        scrRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiTheme.showOptionSheet(activity, "屏幕条件", null, scrLabels, selScr[0],
                        new UiTheme.OnOptionPicked() {
                            @Override public void onPicked(int idx) {
                                selScr[0] = idx;
                                UiTheme.setDropdownValue(scrRow, scrLabels[idx]);
                            }
                        });
            }
        });
        sheet.body.addView(scrRow);

        // ---- 4x3 Gesture Override Matrix ----
        sheet.body.addView(UiTheme.makeDivider(activity));
        sheet.body.addView(UiTheme.makeSectionHeader(activity, "覆写按键手势"));

        for (final KeyId key : KeyId.values()) {
            TextView kTitle = UiTheme.makeText(activity, key.displayName, 13, true, p.primary);
            LinearLayout.LayoutParams ktLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ktLp.topMargin = UiTheme.dp(activity, 12);
            ktLp.bottomMargin = UiTheme.dp(activity, 2);
            sheet.body.addView(kTitle, ktLp);

            for (final GestureType gesture : GestureType.values()) {
                final LinearLayout row = UiTheme.makeInnerRow(activity, true);
                final ImageView iconView = new ImageView(activity);
                LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(
                        UiTheme.dp(activity, 32), UiTheme.dp(activity, 32));
                ivLp.rightMargin = UiTheme.dp(activity, 12);
                row.addView(iconView, ivLp);

                LinearLayout col = new LinearLayout(activity);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                col.addView(UiTheme.makeText(activity, gesture.displayName, 12, false, p.onSurfaceVariant));
                final TextView valTv = UiTheme.makeText(activity, "", 14, true, p.onSurface);
                valTv.setMaxLines(2);
                valTv.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(valTv);
                row.addView(col);

                TextView chev = UiTheme.makeText(activity, "›", 18, true, p.onSurfaceVariant);
                chev.setPadding(UiTheme.dp(activity, 8), 0, 0, 0);
                row.addView(chev);

                refreshOverrideRow(activity, iconView, valTv, draft, globalConfig, key, gesture);

                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ActionSpec cur = draft.getBinding(key, gesture);
                        if (cur == null) cur = ActionSpec.inherit();
                        ActionPickerDialog.show(activity, key, gesture, cur, true,
                                new ActionPickerDialog.OnActionSelected() {
                                    @Override
                                    public void onSelected(final ActionSpec picked) {
                                        if (key == KeyId.POWER && gesture == GestureType.LONG
                                                && !picked.isSystem() && !picked.isInherit()
                                                && !powerLongConfirmed[0]) {
                                            UiTheme.showConfirmSheet(activity,
                                                    "覆盖电源键长按",
                                                    "电源长按默认永久放行原生电源菜单。确认在场景中授权覆写吗？",
                                                    "确认覆盖", true, new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            powerLongConfirmed[0] = true;
                                                            draft.setBinding(key, gesture, picked);
                                                            refreshOverrideRow(activity, iconView, valTv,
                                                                    draft, globalConfig, key, gesture);
                                                        }
                                                    });
                                            return;
                                        }
                                        draft.setBinding(key, gesture, picked);
                                        refreshOverrideRow(activity, iconView, valTv,
                                                draft, globalConfig, key, gesture);
                                    }
                                });
                    }
                });
                sheet.body.addView(row);
            }
        }

        // ---- Footer ----
        if (!isNew) {
            TextView delBtn = UiTheme.makeButton(activity, "删除", UiTheme.ButtonStyle.TEXT_DANGER,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            sheet.dismiss();
                            callback.onDelete(draft.id);
                        }
                    });
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dLp.rightMargin = UiTheme.dp(activity, 8);
            sheet.footer.addView(delBtn, dLp);
        }

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
                        draft.name = nameIn.getText().toString().trim();
                        if (draft.name.isEmpty()) draft.name = "未命名场景";
                        try {
                            draft.priority = Integer.parseInt(priIn.getText().toString().trim());
                        } catch (Throwable ignored) {
                            draft.priority = 100;
                        }
                        draft.enabled = enSw.isChecked();
                        draft.match = new ContextMatcher(
                                parseCsv(pkgsIn.getText().toString()),
                                parseCsv(exPkgsIn.getText().toString()),
                                actRxIn.getText().toString().trim(),
                                kgVals[selKg[0]],
                                scrVals[selScr[0]]);
                        sheet.dismiss();
                        callback.onSave(draft, powerLongConfirmed[0]);
                    }
                }));
        sheet.show();
    }

    private static void refreshAppsPreview(Activity activity, LinearLayout row, List<String> pkgs) {
        row.removeAllViews();
        UiTheme.Palette p = UiTheme.colors(activity);
        if (pkgs == null || pkgs.isEmpty() || pkgs.contains("*")) {
            row.addView(UiTheme.makeChip(activity, "匹配任意前台应用", false));
            return;
        }
        int shownIcons = 0;
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < pkgs.size(); i++) {
            String pkg = pkgs.get(i);
            if (shownIcons < 4) {
                Drawable icon = AppIconCache.getRoundedAppIcon(activity, pkg, 24);
                if (icon != null) {
                    ImageView iv = new ImageView(activity);
                    iv.setImageDrawable(icon);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            UiTheme.dp(activity, 24), UiTheme.dp(activity, 24));
                    lp.rightMargin = UiTheme.dp(activity, 6);
                    row.addView(iv, lp);
                    shownIcons++;
                }
            }
            if (i < 2) {
                if (i > 0) names.append(" · ");
                names.append(AppIconCache.getAppName(activity, pkg));
            }
        }
        if (pkgs.size() > 2) {
            names.append(" 等 ").append(pkgs.size()).append(" 个应用");
        }
        TextView tv = UiTheme.makeText(activity, names.toString(), 12, true, p.onSurface);
        tv.setMaxLines(2);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);
    }

    private static void refreshOverrideRow(Activity activity, ImageView iconView, TextView valTv,
                                           ActionProfile draft, KeyPilotConfig globalCfg,
                                           KeyId key, GestureType gesture) {
        UiTheme.Palette p = UiTheme.colors(activity);
        ActionSpec ov = draft.getBinding(key, gesture);
        if (ov == null) {
            ActionSpec globalSpec = globalCfg != null ? globalCfg.getKey(key).get(gesture) : ActionSpec.system();
            iconView.setImageDrawable(ActionIcons.forActionSpec(activity, globalSpec, 32));
            iconView.setAlpha(0.55f);
            valTv.setText("继承全局 · " + formatConciseValue(activity, globalSpec));
            valTv.setTextColor(p.onSurfaceVariant);
        } else {
            iconView.setImageDrawable(ActionIcons.forActionSpec(activity, ov, 32));
            iconView.setAlpha(1.0f);
            valTv.setText(formatConciseValue(activity, ov));
            valTv.setTextColor(ov.isSystem() ? p.error : p.primary);
        }
    }

    /** Value-only formatter (Rule #9: never repeat redundant labels). */
    public static String formatConciseValue(Activity activity, ActionSpec spec) {
        if (spec == null) return "跟随系统";
        String val;
        switch (spec.type) {
            case SYSTEM:
                val = "跟随系统";
                break;
            case NONE:
                val = "无动作";
                break;
            case LAUNCH_APP:
                val = spec.label != null ? spec.label
                        : (spec.packageName != null ? AppIconCache.getAppName(activity, spec.packageName) : "未指定");
                break;
            case CAMERA:
                val = spec.packageName != null
                        ? (spec.label != null ? spec.label : AppIconCache.getAppName(activity, spec.packageName))
                        : "相机";
                break;
            case CAMERA_SHUTTER:
                val = "相机快门 · " + (spec.shutterKey != null ? spec.shutterKey : "auto");
                break;
            case IN_APP_PRESET:
                val = spec.label != null ? spec.label : String.valueOf(spec.presetId);
                break;
            case DEEP_LINK:
                val = spec.label != null ? spec.label : (spec.dataUri != null ? spec.dataUri : "Deep Link");
                break;
            case MEDIA_TRANSPORT:
                val = localizeMediaCmd(spec.mediaCommand);
                break;
            case KEY_INJECT:
                val = spec.label != null ? spec.label : ("KeyCode " + spec.keyCode);
                break;
            default:
                val = spec.type.displayName;
                break;
        }
        return spec.passthroughDefault ? val + " · 不吞键" : val;
    }

    private static TextView fieldLabel(Activity activity, String text) {
        UiTheme.Palette p = UiTheme.colors(activity);
        TextView tv = UiTheme.makeText(activity, text, 12, true, p.onSurfaceVariant);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiTheme.dp(activity, 8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static String localizeMediaCmd(String cmd) {
        if (ActionSpec.MEDIA_CMD_NEXT.equals(cmd)) return "媒体 · 下一曲";
        if (ActionSpec.MEDIA_CMD_PREV.equals(cmd)) return "媒体 · 上一曲";
        if (ActionSpec.MEDIA_CMD_PLAY.equals(cmd)) return "媒体 · 播放";
        if (ActionSpec.MEDIA_CMD_PAUSE.equals(cmd)) return "媒体 · 暂停";
        if (ActionSpec.MEDIA_CMD_SEEK_FORWARD.equals(cmd)) return "媒体 · 快进";
        if (ActionSpec.MEDIA_CMD_SEEK_BACK.equals(cmd)) return "媒体 · 后退";
        return "媒体 · 播放 / 暂停";
    }

    private static int indexOf(String[] arr, String val, int def) {
        if (val == null) return def;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equalsIgnoreCase(val)) return i;
        }
        return def;
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
