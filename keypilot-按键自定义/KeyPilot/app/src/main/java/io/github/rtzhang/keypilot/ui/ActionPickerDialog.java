package io.github.rtzhang.keypilot.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.rtzhang.keypilot.config.ActionSpec;
import io.github.rtzhang.keypilot.config.ActionType;
import io.github.rtzhang.keypilot.config.GestureType;
import io.github.rtzhang.keypilot.config.KeyId;
import io.github.rtzhang.keypilot.config.SceneTemplates;

/**
 * Material 3 Bottom Sheet pickers & parameter editors for all v1 + v2 P1 actions.
 * Every app list item shows a 40dp rounded app icon + app name + package subtitle with M3 dividers;
 * every action item shows a 36dp semantic vector icon + title + 1-line description.
 */
public final class ActionPickerDialog {

    public interface OnActionSelected {
        void onSelected(ActionSpec spec);
    }

    public interface MultiAppCallback {
        void onPicked(List<String> selected);
    }

    /** Callback carrying the full AppEntry (label + package) for the activity browser. */
    public interface AppPickedCallback {
        void onPicked(AppEntry entry);
    }

    public static final class AppEntry {
        public final String label;
        public final String packageName;
        public final String activityName;

        public AppEntry(String label, String packageName, String activityName) {
            this.label = label;
            this.packageName = packageName;
            this.activityName = activityName;
        }
    }

    private ActionPickerDialog() {
    }

    public static void show(final Activity activity,
                            final KeyId key,
                            final GestureType gesture,
                            final ActionSpec current,
                            final OnActionSelected callback) {
        show(activity, key, gesture, current, false, callback);
    }

    public static void show(final Activity activity,
                            final KeyId key,
                            final GestureType gesture,
                            final ActionSpec current,
                            final boolean allowInherit,
                            final OnActionSelected callback) {
        final UiTheme.Palette p = UiTheme.colors(activity);
        final UiTheme.BottomSheet sheet = UiTheme.createBottomSheet(
                activity,
                key.displayName + " · " + gesture.displayName,
                "选择触发动作（点击即生效或进入参数配置）",
                true);

        if (allowInherit) {
            addActionGroup(activity, sheet, "场景继承",
                    new ActionType[]{ActionType.INHERIT}, current, callback);
        }
        addActionGroup(activity, sheet, "上下文与高频直达",
                new ActionType[]{
                        ActionType.CAMERA_SHUTTER,
                        ActionType.IN_APP_PRESET,
                        ActionType.DEEP_LINK,
                        ActionType.LAUNCH_APP,
                        ActionType.CAMERA,
                        ActionType.MEDIA_TRANSPORT,
                        ActionType.KEY_INJECT
                }, current, callback);
        addActionGroup(activity, sheet, "系统控制与工具",
                new ActionType[]{
                        ActionType.FLASHLIGHT,
                        ActionType.SCREENSHOT,
                        ActionType.PARTIAL_SCREENSHOT,
                        ActionType.RINGER_CYCLE,
                        ActionType.EXPAND_NOTIFICATIONS,
                        ActionType.EXPAND_QS,
                        ActionType.LOCK_SCREEN,
                        ActionType.OPEN_SETTINGS,
                        ActionType.VOICE_ASSISTANT,
                        ActionType.MIC_MUTE_PTT
                }, current, callback);
        addActionGroup(activity, sheet, "媒体、音量与导航",
                new ActionType[]{
                        ActionType.MEDIA_PLAY_PAUSE,
                        ActionType.MEDIA_PREV,
                        ActionType.MEDIA_NEXT,
                        ActionType.VOLUME_UP,
                        ActionType.VOLUME_DOWN,
                        ActionType.NAV_BACK,
                        ActionType.NAV_HOME,
                        ActionType.NAV_RECENTS
                }, current, callback);
        addActionGroup(activity, sheet, "高级与系统默认",
                new ActionType[]{
                        ActionType.LAUNCH_ACTIVITY,
                        ActionType.CUSTOM_INTENT,
                        ActionType.CONTEXT_VAR_BROADCAST,
                        ActionType.SYSTEM,
                        ActionType.NONE
                }, current, callback);

        sheet.footer.addView(UiTheme.makeButton(activity, "取消", UiTheme.ButtonStyle.TEXT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sheet.dismiss();
                    }
                }));
        sheet.show();
    }

    private static void addActionGroup(final Activity activity,
                                       final UiTheme.BottomSheet sheet,
                                       String groupTitle,
                                       ActionType[] types,
                                       final ActionSpec current,
                                       final OnActionSelected callback) {
        UiTheme.Palette p = UiTheme.colors(activity);
        TextView hdr = UiTheme.makeText(activity, groupTitle, 12, true, p.primary);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = UiTheme.dp(activity, 12);
        hLp.bottomMargin = UiTheme.dp(activity, 6);
        sheet.body.addView(hdr, hLp);

        LinearLayout groupBox = new LinearLayout(activity);
        groupBox.setOrientation(LinearLayout.VERTICAL);
        groupBox.setBackground(UiTheme.roundedShape(activity, p.surfaceContainerHigh, 0, 0, UiTheme.RADIUS_CARD));
        groupBox.setPadding(UiTheme.dp(activity, 8), UiTheme.dp(activity, 4),
                UiTheme.dp(activity, 8), UiTheme.dp(activity, 4));
        sheet.body.addView(groupBox);

        for (int i = 0; i < types.length; i++) {
            final ActionType type = types[i];
            if (i > 0) {
                groupBox.addView(UiTheme.makeListDivider(activity));
            }
            boolean selected = (current != null && current.type == type);
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(UiTheme.dp(activity, 56));
            row.setBackground(UiTheme.rippleSurface(
                    activity,
                    selected ? p.primaryContainer : 0x00000000,
                    0, 0, UiTheme.RADIUS_CARD, p.onSurface));
            row.setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 10),
                    UiTheme.dp(activity, 12), UiTheme.dp(activity, 10));

            ImageView iv = new ImageView(activity);
            iv.setImageDrawable(ActionIcons.forActionType(activity, type, 36, selected || type != ActionType.SYSTEM));
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(
                    UiTheme.dp(activity, 36), UiTheme.dp(activity, 36));
            ivLp.rightMargin = UiTheme.dp(activity, 12);
            row.addView(iv, ivLp);

            LinearLayout col = new LinearLayout(activity);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView titleTv = UiTheme.makeText(activity, type.displayName, 14, true,
                    selected ? p.onPrimaryContainer : p.onSurface);
            TextView descTv = UiTheme.makeText(activity, type.description, 12, false,
                    selected ? p.onPrimaryContainer : p.onSurfaceVariant);
            descTv.setMaxLines(2);
            descTv.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(titleTv);
            col.addView(descTv);
            row.addView(col);

            if (selected) {
                TextView chk = UiTheme.makeText(activity, "✓", 16, true, p.onPrimaryContainer);
                chk.setPadding(UiTheme.dp(activity, 8), 0, 0, 0);
                row.addView(chk);
            }

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sheet.dismiss();
                    handlePickedType(activity, type, current, callback);
                }
            });
            groupBox.addView(row);
        }
    }

    private static void handlePickedType(Activity activity,
                                         ActionType picked,
                                         ActionSpec current,
                                         OnActionSelected callback) {
        switch (picked) {
            case INHERIT:
                callback.onSelected(ActionSpec.inherit());
                return;
            case SYSTEM:
                callback.onSelected(ActionSpec.system());
                return;
            case LAUNCH_APP:
                showSingleAppPicker(activity, "选择要启动的应用", callback);
                return;
            case LAUNCH_ACTIVITY:
                ActivityBrowserDialog.showEditor(activity, current, callback);
                return;
            case CUSTOM_INTENT:
                showCustomIntentEditor(activity, current, callback);
                return;
            case CAMERA:
                showCameraOptionSheet(activity, callback);
                return;
            case CAMERA_SHUTTER:
                showCameraShutterEditor(activity, current, callback);
                return;
            case IN_APP_PRESET:
                showInAppPresetPicker(activity, callback);
                return;
            case DEEP_LINK:
                showDeepLinkEditor(activity, current, callback);
                return;
            case KEY_INJECT:
                showKeyInjectEditor(activity, current, callback);
                return;
            case MEDIA_TRANSPORT:
                showMediaTransportEditor(activity, current, callback);
                return;
            case CONTEXT_VAR_BROADCAST:
                showContextVarBroadcastEditor(activity, current, callback);
                return;
            default:
                callback.onSelected(ActionSpec.simple(picked));
        }
    }

    // --------------------------------------------------- M3 App Pickers (40dp Icons)

    public static void showSingleAppPicker(final Activity activity,
                                           final String title,
                                           final OnActionSelected callback) {
        showAppPicker(activity, title, new AppPickedCallback() {
            @Override
            public void onPicked(AppEntry e) {
                callback.onSelected(ActionSpec.launchApp(e.packageName, e.label));
            }
        });
    }

    /** Reused by the Activity 探测绑定器: pick APP -> open the activity browser. */
    public static void showAppPickerForActivity(final Activity activity,
                                                final String title,
                                                final AppPickedCallback callback) {
        showAppPicker(activity, title, callback);
    }

    private static void showAppPicker(final Activity activity,
                                      final String title,
                                      final AppPickedCallback callback) {
        final UiTheme.Palette p = UiTheme.colors(activity);
        final UiTheme.BottomSheet sheet = UiTheme.createBottomSheet(
                activity, title, "按应用名或包名搜索，点击即可选中", false);

        final EditText search = UiTheme.makeInput(activity, "搜索应用名称或包名...", "");
        sheet.body.addView(search);

        final TextView countTv = UiTheme.makeText(activity, "正在加载已安装应用...", 12, false, p.onSurfaceVariant);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = UiTheme.dp(activity, 8);
        cLp.bottomMargin = UiTheme.dp(activity, 4);
        sheet.body.addView(countTv, cLp);

        final List<AppEntry> allApps = new ArrayList<>();
        final List<AppEntry> shown = new ArrayList<>();
        final ListView listView = new ListView(activity);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        int listH = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.48f);
        sheet.body.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listH));

        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int pos) { return shown.get(pos); }
            @Override public long getItemId(int pos) { return pos; }

            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                final AppEntry e = shown.get(pos);
                LinearLayout wrap = new LinearLayout(activity);
                wrap.setOrientation(LinearLayout.VERTICAL);

                LinearLayout row = buildAppRow(activity, e, false, false);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sheet.dismiss();
                        callback.onPicked(e);
                    }
                });
                wrap.addView(row);
                if (pos + 1 < shown.size()) {
                    wrap.addView(UiTheme.makeListDivider(activity));
                }
                return wrap;
            }
        };
        listView.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                filterApps(allApps, shown, s != null ? s.toString() : "");
                adapter.notifyDataSetChanged();
                countTv.setText("共 " + shown.size() + " / " + allApps.size() + " 个应用");
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        sheet.footer.addView(UiTheme.makeButton(activity, "取消", UiTheme.ButtonStyle.TEXT,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { sheet.dismiss(); }
                }));
        sheet.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppEntry> loaded = queryLaunchableApps(activity);
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        allApps.clear();
                        allApps.addAll(loaded);
                        filterApps(allApps, shown, search.getText().toString());
                        adapter.notifyDataSetChanged();
                        countTv.setText("共 " + allApps.size() + " 个可启动应用");
                    }
                });
            }
        }).start();
    }

    /** Multi-select App Picker with 40dp rounded icons, search bar, and M3 dividers. */
    public static void showMultiAppPicker(final Activity activity,
                                          final String title,
                                          final List<String> initialSelected,
                                          final MultiAppCallback cb) {
        final UiTheme.Palette p = UiTheme.colors(activity);
        final List<String> chosen = new ArrayList<>(initialSelected);
        final UiTheme.BottomSheet sheet = UiTheme.createBottomSheet(
                activity, title, "可多选目标应用（已选 " + chosen.size() + " 个）", false);

        final EditText search = UiTheme.makeInput(activity, "搜索应用名称或包名...", "");
        sheet.body.addView(search);

        final TextView countTv = UiTheme.makeText(activity, "正在加载已安装应用...", 12, false, p.onSurfaceVariant);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = UiTheme.dp(activity, 8);
        cLp.bottomMargin = UiTheme.dp(activity, 4);
        sheet.body.addView(countTv, cLp);

        final List<AppEntry> allApps = new ArrayList<>();
        final List<AppEntry> shown = new ArrayList<>();
        final ListView listView = new ListView(activity);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        int listH = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.46f);
        sheet.body.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listH));

        final BaseAdapter[] adapterRef = new BaseAdapter[1];
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int pos) { return shown.get(pos); }
            @Override public long getItemId(int pos) { return pos; }

            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                final AppEntry e = shown.get(pos);
                final boolean isSel = chosen.contains(e.packageName);
                LinearLayout wrap = new LinearLayout(activity);
                wrap.setOrientation(LinearLayout.VERTICAL);

                LinearLayout row = buildAppRow(activity, e, true, isSel);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (chosen.contains(e.packageName)) {
                            chosen.remove(e.packageName);
                        } else {
                            chosen.add(e.packageName);
                        }
                        countTv.setText("已选 " + chosen.size() + " 个 · 显示 " + shown.size() + " / " + allApps.size());
                        adapterRef[0].notifyDataSetChanged();
                    }
                });
                wrap.addView(row);
                if (pos + 1 < shown.size()) {
                    wrap.addView(UiTheme.makeListDivider(activity));
                }
                return wrap;
            }
        };
        adapterRef[0] = adapter;
        listView.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                filterApps(allApps, shown, s != null ? s.toString() : "");
                adapter.notifyDataSetChanged();
                countTv.setText("已选 " + chosen.size() + " 个 · 显示 " + shown.size() + " / " + allApps.size());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        TextView cancelBtn = UiTheme.makeButton(activity, "取消", UiTheme.ButtonStyle.TEXT,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { sheet.dismiss(); }
                });
        LinearLayout.LayoutParams caLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        caLp.rightMargin = UiTheme.dp(activity, 8);
        sheet.footer.addView(cancelBtn, caLp);

        sheet.footer.addView(UiTheme.makeButton(activity, "确定", UiTheme.ButtonStyle.FILLED,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sheet.dismiss();
                        cb.onPicked(chosen);
                    }
                }));
        sheet.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppEntry> loaded = queryLaunchableApps(activity);
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        allApps.clear();
                        allApps.addAll(loaded);
                        filterApps(allApps, shown, search.getText().toString());
                        adapter.notifyDataSetChanged();
                        countTv.setText("已选 " + chosen.size() + " 个 · 共 " + allApps.size() + " 个应用");
                    }
                });
            }
        }).start();
    }

    private static LinearLayout buildAppRow(Activity activity, AppEntry e,
                                            boolean multiSelect, boolean selected) {
        UiTheme.Palette p = UiTheme.colors(activity);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiTheme.dp(activity, 60));
        row.setBackground(UiTheme.rippleSurface(
                activity,
                selected ? p.primaryContainer : 0x00000000,
                0, 0, UiTheme.RADIUS_CARD, p.onSurface));
        row.setPadding(UiTheme.dp(activity, 8), UiTheme.dp(activity, 10),
                UiTheme.dp(activity, 12), UiTheme.dp(activity, 10));

        ImageView iv = new ImageView(activity);
        Drawable icon = AppIconCache.getRoundedAppIcon(activity, e.packageName, 40);
        if (icon != null) {
            iv.setImageDrawable(icon);
        } else {
            iv.setImageDrawable(ActionIcons.forActionType(activity, ActionType.LAUNCH_APP, 40, false));
        }
        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(
                UiTheme.dp(activity, 40), UiTheme.dp(activity, 40));
        ivLp.rightMargin = UiTheme.dp(activity, 14);
        row.addView(iv, ivLp);

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView nameTv = UiTheme.makeText(activity, e.label, 15, true,
                selected ? p.onPrimaryContainer : p.onSurface);
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(TextUtils.TruncateAt.END);
        TextView pkgTv = UiTheme.makeText(activity, e.packageName, 12, false,
                selected ? p.onPrimaryContainer : p.onSurfaceVariant);
        pkgTv.setSingleLine(true);
        pkgTv.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(nameTv);
        col.addView(pkgTv);
        row.addView(col);

        // one-tap package copy (activity binder & every app picker share this row)
        TextView copyBtn = UiTheme.makeText(activity, "复制", 12, true, p.primary);
        copyBtn.setBackground(UiTheme.rippleSurface(activity, Color.TRANSPARENT,
                p.outline, 1, UiTheme.RADIUS_PILL, p.primary));
        copyBtn.setGravity(Gravity.CENTER);
        copyBtn.setSingleLine(true);
        copyBtn.setMinHeight(UiTheme.dp(activity, 30));
        copyBtn.setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 6),
                UiTheme.dp(activity, 10), UiTheme.dp(activity, 6));
        copyBtn.setClickable(true);
        copyBtn.setFocusable(true);
        final String copyPkg = e.packageName;
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyPackageToClipboard(activity, copyPkg);
            }
        });
        LinearLayout.LayoutParams cpLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cpLp.leftMargin = UiTheme.dp(activity, 8);
        row.addView(copyBtn, cpLp);

        if (multiSelect) {
            TextView badge = UiTheme.makeText(activity, selected ? "✓" : "○", 18, true,
                    selected ? p.onPrimaryContainer : p.outline);
            badge.setPadding(UiTheme.dp(activity, 10), 0, 0, 0);
            row.addView(badge);
        }
        return row;
    }

    // --------------------------------------------------- Built-in Preset Picker (with Real App Icons)

    private static void showInAppPresetPicker(final Activity activity, final OnActionSelected callback) {
        final UiTheme.Palette p = UiTheme.colors(activity);
        final UiTheme.BottomSheet sheet = UiTheme.createBottomSheet(
                activity, "中文高频内置功能", "直接绑定支付宝 / 微信 / 相机等常用内嵌入口", true);

        LinearLayout listBox = new LinearLayout(activity);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setBackground(UiTheme.roundedShape(activity, p.surfaceContainerHigh, 0, 0, UiTheme.RADIUS_CARD));
        listBox.setPadding(UiTheme.dp(activity, 8), UiTheme.dp(activity, 4),
                UiTheme.dp(activity, 8), UiTheme.dp(activity, 4));
        sheet.body.addView(listBox);

        List<SceneTemplates.BuiltInPreset> list = SceneTemplates.BUILTIN_PRESETS;
        for (int i = 0; i < list.size(); i++) {
            final SceneTemplates.BuiltInPreset preset = list.get(i);
            if (i > 0) {
                listBox.addView(UiTheme.makeListDivider(activity));
            }
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(UiTheme.dp(activity, 60));
            row.setBackground(UiTheme.rippleSurface(activity, 0x00000000, 0, 0, UiTheme.RADIUS_CARD, p.onSurface));
            row.setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 10),
                    UiTheme.dp(activity, 12), UiTheme.dp(activity, 10));

            String ownerPkg = preset.packageName != null
                    ? preset.packageName
                    : ("camera_shutter".equals(preset.id) ? "com.nothing.camera" : null);
            ImageView iv = new ImageView(activity);
            Drawable appIcon = ownerPkg != null ? AppIconCache.getRoundedAppIcon(activity, ownerPkg, 40) : null;
            iv.setImageDrawable(appIcon != null
                    ? appIcon
                    : ActionIcons.forActionType(activity, ActionType.IN_APP_PRESET, 40, true));
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(
                    UiTheme.dp(activity, 40), UiTheme.dp(activity, 40));
            ivLp.rightMargin = UiTheme.dp(activity, 14);
            row.addView(iv, ivLp);

            LinearLayout col = new LinearLayout(activity);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            String ownerName = ownerPkg != null ? AppIconCache.getAppName(activity, ownerPkg) : "";
            col.addView(UiTheme.makeText(activity, preset.title, 15, true, p.onSurface));
            String sub = (!ownerName.isEmpty() ? ownerName + " · " : "") + preset.description;
            TextView subTv = UiTheme.makeText(activity, sub, 12, false, p.onSurfaceVariant);
            subTv.setMaxLines(2);
            col.addView(subTv);
            row.addView(col);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sheet.dismiss();
                    callback.onSelected(ActionSpec.inAppPreset(preset.id, preset.title));
                }
            });
            listBox.addView(row);
        }

        sheet.footer.addView(UiTheme.makeButton(activity, "取消", UiTheme.ButtonStyle.TEXT,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { sheet.dismiss(); }
                }));
        sheet.show();
    }

    // --------------------------------------------------- Parameter Editors (All M3 Bottom Sheets)

    private static void showCameraOptionSheet(final Activity activity, final OnActionSelected callback) {
        final String[] opts = {
                "系统默认相机（自动适配锁屏安全相机）",
                "Nothing Camera",
                "Google Camera",
                "从已安装应用列表选择相机..."
        };
        UiTheme.showOptionSheet(activity, "选择相机应用", "支持指定第三方或 GCam 移植版相机", opts, 0,
                new UiTheme.OnOptionPicked() {
                    @Override
                    public void onPicked(int which) {
                        if (which == 0) {
                            callback.onSelected(ActionSpec.simple(ActionType.CAMERA));
                        } else if (which == 1) {
                            callback.onSelected(ActionSpec.cameraApp("com.nothing.camera", "Nothing Camera"));
                        } else if (which == 2) {
                            callback.onSelected(ActionSpec.cameraApp("com.google.android.GoogleCamera", "Google Camera"));
                        } else {
                            showSingleAppPicker(activity, "选择相机应用", new OnActionSelected() {
                                @Override
                                public void onSelected(ActionSpec spec) {
                                    callback.onSelected(ActionSpec.cameraApp(spec.packageName, spec.label));
                                }
                            });
                        }
                    }
                });
    }

    private static void showCameraShutterEditor(final Activity activity,
                                                final ActionSpec current,
                                                final OnActionSelected callback) {
        final UiTheme.BottomSheet sheet = UiTheme.createBottomSheet(
                activity, "相机快门配置", "Nothing Camera 默认优先 KEYCODE_CAMERA(27)，GCam 自动走 Bypass 音量键", true);

        final String[] tierVals = {
                ActionSpec.SHUTTER_TIER_AUTO, ActionSpec.SHUTTER_TIER_SYSTEM_KEY,
                ActionSpec.SHUTTER_TIER_A11Y_NODE, ActionSpec.SHUTTER_TIER_A11Y_COORD
        };
        final String[] tierLabels = {
                "自动三档（首选系统键，最稳）",
                "Tier 1：仅系统键注入",
                "Tier 2：无障碍节点点击（P2 启用）",
                "Tier 3：无障碍坐标点击（P2 启用）"
        };
        final int[] selTier = {indexOf(tierVals, current != null ? current.shutterTier : null, 0)};
        final LinearLayout tierRow = UiTheme.makeDropdownField(activity, "快门注入档位", tierLabels[selTier[0]], null);
        tierRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiTheme.showOptionSheet(activity, "选择快门档位", null, tierLabels, selTier[0],
                        new UiTheme.OnOptionPicked() {
                            @Override public void onPicked(int idx) {
                                selTier[0] = idx;
                                UiTheme.setDropdownValue(tierRow, tierLabels[idx]);
                            }
                        });
            }
        });
        sheet.body.addView(tierRow);

        final String[] keyVals = {
                ActionSpec.SHUTTER_KEY_AUTO, ActionSpec.SHUTTER_KEY_CAMERA,
                ActionSpec.SHUTTER_KEY_VOLUME_DOWN, ActionSpec.SHUTTER_KEY_VOLUME_UP
        };
        final String[] keyLabels = {
                "自动适配（Nothing/OpenCamera→27，GCam→音量−）",
                "KEYCODE_CAMERA (27)",
                "音量− (Bypass VOLUME_DOWN)",
                "音量+ (Bypass VOLUME_UP)"
        };
        final int[] selKey = {indexOf(keyVals, current != null ? current.shutterKey : null, 0)};
        final LinearLayout keyRow = UiTheme.makeDropdownField(activity, "快门系统键", keyLabels[selKey[0]], null);
        keyRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiTheme.showOptionSheet(activity, "选择快门键码", null, keyLabels, selKey[0],
                        new UiTheme.OnOptionPicked() {
                            @Override public void onPicked(int idx) {
                                selKey[0] = idx;
                                UiTheme.setDropdownValue(keyRow, keyLabels[idx]);
                            }
                        });
            }
        });
        sheet.body.addView(keyRow);

        final EditText pulseIn = UiTheme.makeInput(activity, "脉冲时长 ms（单拍 35 / 连拍录像 600）",
                String.valueOf(current != null && current.type == ActionType.CAMERA_SHUTTER ? current.pulseMs : 35));
        pulseIn.setInputType(InputType.TYPE_CLASS_NUMBER);
        sheet.body.addView(pulseIn);

        final Switch ptSw = addModalSwitchRow(activity, sheet.body, "保留默认动作（不吞键）",
                "执行快门的同时放行原始物理键事件", current != null && current.passthroughDefault);

        addCancelSaveFooter(activity, sheet, new Runnable() {
            @Override
            public void run() {
                int pulse = parseIntSafe(pulseIn.getText().toString(), 35);
                callback.onSelected(ActionSpec.cameraShutter(
                        tierVals[selTier[0]], keyVals[selKey[0]], pulse, ptSw.isChecked()));
            }
        });
        sheet.show();
    }

    private static void showDeepLinkEditor(final Activity activity,
                                           final ActionSpec current,
                                           final OnActionSelected callback) {
        final UiTheme.BottomSheet sheet = UiTheme.createBottomSheet(
                activity, "Deep Link 直达", "输入 URI Scheme 直接跳转应用内部页面", true);

        final EditText uriIn = UiTheme.makeInput(activity,
                "URI（如 alipays://platformapi/startapp?saId=10000007）",
                current != null ? current.dataUri : "");
        sheet.body.addView(uriIn);

        final EditText pkgIn = UiTheme.makeInput(activity,
                "限定包名（可选，如 com.eg.android.AlipayGphone）",
                current != null ? current.packageName : "");
        sheet.body.addView(pkgIn);

        final EditText lblIn = UiTheme.makeInput(activity,
                "显示名称（可选，如 支付宝扫一扫）",
                current != null ? current.label : "");
        sheet.body.addView(lblIn);

        addCancelSaveFooter(activity, sheet, new Runnable() {
            @Override
            public void run() {
                String uri = uriIn.getText().toString().trim();
                if (uri.isEmpty()) return;
                callback.onSelected(ActionSpec.deepLink(
                        uri, pkgIn.getText().toString().trim(), lblIn.getText().toString().trim()));
            }
        });
        sheet.show();
    }

    private static void showKeyInjectEditor(final Activity activity,
                                            final ActionSpec current,
                                            final OnActionSelected callback) {
        final UiTheme.BottomSheet sheet = UiTheme.createBottomSheet(
                activity, "注入指定按键码", "向当前前台窗口注入标准 Android KeyEvent", true);

        final int[] codes = {93, 92, 27, 66, 62, 168, 169};
        final String[] labels = {
                "下一页 KEYCODE_PAGE_DOWN (93)",
                "上一页 KEYCODE_PAGE_UP (92)",
                "相机快门 KEYCODE_CAMERA (27)",
                "确认 KEYCODE_ENTER (66)",
                "空格 KEYCODE_SPACE (62)",
                "放大 KEYCODE_ZOOM_IN (168 · 实验)",
                "缩小 KEYCODE_ZOOM_OUT (169 · 实验)"
        };
        final EditText codeIn = UiTheme.makeInput(activity, "KeyCode 数值",
                String.valueOf(current != null && current.keyCode > 0 ? current.keyCode : 93));
        codeIn.setInputType(InputType.TYPE_CLASS_NUMBER);

        final int[] selIdx = {0};
        final LinearLayout presetRow = UiTheme.makeDropdownField(activity, "常用键码快选", labels[0], null);
        presetRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiTheme.showOptionSheet(activity, "选择常用键码", null, labels, selIdx[0],
                        new UiTheme.OnOptionPicked() {
                            @Override public void onPicked(int idx) {
                                selIdx[0] = idx;
                                UiTheme.setDropdownValue(presetRow, labels[idx]);
                                codeIn.setText(String.valueOf(codes[idx]));
                            }
                        });
            }
        });
        sheet.body.addView(presetRow);
        sheet.body.addView(codeIn);

        final EditText pulseIn = UiTheme.makeInput(activity, "按压脉冲 ms（默认 35）",
                String.valueOf(current != null ? current.pulseMs : 35));
        pulseIn.setInputType(InputType.TYPE_CLASS_NUMBER);
        sheet.body.addView(pulseIn);

        final Switch ptSw = addModalSwitchRow(activity, sheet.body, "保留默认动作（不吞键）",
                "注入后同时放行原物理按键", current != null && current.passthroughDefault);

        addCancelSaveFooter(activity, sheet, new Runnable() {
            @Override
            public void run() {
                int kc = parseIntSafe(codeIn.getText().toString(), 93);
                int pulse = parseIntSafe(pulseIn.getText().toString(), 35);
                if (kc <= 0) return;
                callback.onSelected(ActionSpec.keyInject(kc, pulse, 1, "KeyCode " + kc, ptSw.isChecked()));
            }
        });
        sheet.show();
    }

    private static void showMediaTransportEditor(final Activity activity,
                                                 final ActionSpec current,
                                                 final OnActionSelected callback) {
        final UiTheme.BottomSheet sheet = UiTheme.createBottomSheet(
                activity, "精准媒体控制", "直控 MediaSession，根治音量到顶失效问题", true);

        final String[] cmds = {
                ActionSpec.MEDIA_CMD_TOGGLE, ActionSpec.MEDIA_CMD_NEXT, ActionSpec.MEDIA_CMD_PREV,
                ActionSpec.MEDIA_CMD_PLAY, ActionSpec.MEDIA_CMD_PAUSE,
                ActionSpec.MEDIA_CMD_SEEK_FORWARD, ActionSpec.MEDIA_CMD_SEEK_BACK
        };
        final String[] labels = {
                "播放 / 暂停切换", "下一曲", "上一曲", "强制播放", "强制暂停", "快进", "后退"
        };
        final int[] selCmd = {indexOf(cmds, current != null ? current.mediaCommand : null, 0)};
        final LinearLayout cmdRow = UiTheme.makeDropdownField(activity, "控制指令", labels[selCmd[0]], null);
        cmdRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiTheme.showOptionSheet(activity, "选择媒体控制指令", null, labels, selCmd[0],
                        new UiTheme.OnOptionPicked() {
                            @Override public void onPicked(int idx) {
                                selCmd[0] = idx;
                                UiTheme.setDropdownValue(cmdRow, labels[idx]);
                            }
                        });
            }
        });
        sheet.body.addView(cmdRow);

        final EditText seekIn = UiTheme.makeInput(activity, "快进/后退步长 ms（默认 15000）",
                String.valueOf(current != null ? current.seekMs : 15000));
        seekIn.setInputType(InputType.TYPE_CLASS_NUMBER);
        sheet.body.addView(seekIn);

        final EditText pkgIn = UiTheme.makeInput(activity, "定向播放器包名（可选，留空控当前活跃播放器）",
                current != null ? current.packageName : "");
        sheet.body.addView(pkgIn);

        final Switch simSw = addModalSwitchRow(activity, sheet.body, "强制模拟媒体按键",
                "Spotify 等不响应标准指令的播放器建议开启", current != null && current.simulateMediaButton);

        addCancelSaveFooter(activity, sheet, new Runnable() {
            @Override
            public void run() {
                int seek = parseIntSafe(seekIn.getText().toString(), 15000);
                callback.onSelected(ActionSpec.mediaTransport(
                        cmds[selCmd[0]], seek, pkgIn.getText().toString().trim(), simSw.isChecked()));
            }
        });
        sheet.show();
    }

    private static void showContextVarBroadcastEditor(final Activity activity,
                                                      final ActionSpec current,
                                                      final OnActionSelected callback) {
        final UiTheme.BottomSheet sheet = UiTheme.createBottomSheet(
                activity, "前台应用变量广播", "支持 ${fg_pkg} / ${fg_app_name} / ${fg_activity} 模板变量", true);

        final EditText actIn = UiTheme.makeInput(activity, "广播 Action",
                current != null && current.intentAction != null
                        ? current.intentAction : "io.github.rtzhang.keypilot.CONTEXT_TRIGGER");
        sheet.body.addView(actIn);

        final EditText exIn = UiTheme.makeInput(activity, "Extras（每行 key=value）",
                "pkg=${fg_pkg}\napp=${fg_app_name}\nactivity=${fg_activity}");
        exIn.setSingleLine(false);
        exIn.setMinLines(3);
        sheet.body.addView(exIn);

        final Switch ptSw = addModalSwitchRow(activity, sheet.body, "保留默认动作（不吞键）",
                "广播变量的同时放行原始按键", current != null && current.passthroughDefault);

        addCancelSaveFooter(activity, sheet, new Runnable() {
            @Override
            public void run() {
                String act = actIn.getText().toString().trim();
                if (act.isEmpty()) act = "io.github.rtzhang.keypilot.CONTEXT_TRIGGER";
                callback.onSelected(ActionSpec.contextVarBroadcast(
                        act, parseExtrasText(exIn.getText().toString()), ptSw.isChecked()));
            }
        });
        sheet.show();
    }

    private static void showCustomIntentEditor(final Activity activity,
                                               final ActionSpec current,
                                               final OnActionSelected callback) {
        final UiTheme.BottomSheet sheet = UiTheme.createBottomSheet(
                activity, "发送自定义 Intent", "支持 Activity / Broadcast / Service 与类型化 Extras", true);

        final String[] modes = {
                ActionSpec.INTENT_TYPE_ACTIVITY, ActionSpec.INTENT_TYPE_BROADCAST, ActionSpec.INTENT_TYPE_SERVICE
        };
        final String[] modeLabels = {"Activity (startActivity)", "Broadcast (sendBroadcast)", "Service (startService)"};
        final int[] selMode = {indexOf(modes, current != null ? current.intentType : null, 0)};
        final LinearLayout modeRow = UiTheme.makeDropdownField(activity, "派发类型", modeLabels[selMode[0]], null);
        modeRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiTheme.showOptionSheet(activity, "选择 Intent 类型", null, modeLabels, selMode[0],
                        new UiTheme.OnOptionPicked() {
                            @Override public void onPicked(int idx) {
                                selMode[0] = idx;
                                UiTheme.setDropdownValue(modeRow, modeLabels[idx]);
                            }
                        });
            }
        });
        sheet.body.addView(modeRow);

        final EditText actIn = UiTheme.makeInput(activity, "Action（如 android.intent.action.VIEW）",
                current != null ? current.intentAction : "");
        final EditText pkgIn = UiTheme.makeInput(activity, "Package（可选）",
                current != null ? current.packageName : "");
        final EditText clsIn = UiTheme.makeInput(activity, "Class（可选）",
                current != null ? current.className : "");
        final EditText uriIn = UiTheme.makeInput(activity, "Data URI（可选）",
                current != null ? current.dataUri : "");

        String exStr = "";
        if (current != null && !current.extras.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Object> e : current.extras.entrySet()) {
                sb.append(e.getKey()).append('=').append(formatExtraValue(e.getValue())).append('\n');
            }
            exStr = sb.toString().trim();
        }
        final EditText exIn = UiTheme.makeInput(activity, "Extras（每行 key=value，支持 bool:/int:/long:）", exStr);
        exIn.setSingleLine(false);
        exIn.setMinLines(3);

        sheet.body.addView(actIn);
        sheet.body.addView(pkgIn);
        sheet.body.addView(clsIn);
        sheet.body.addView(uriIn);
        sheet.body.addView(exIn);

        final Switch ptSw = addModalSwitchRow(activity, sheet.body, "保留默认动作（不吞键）",
                "派发 Intent 同时放行原始按键", current != null && current.passthroughDefault);

        addCancelSaveFooter(activity, sheet, new Runnable() {
            @Override
            public void run() {
                String action = actIn.getText().toString().trim();
                String pkg = pkgIn.getText().toString().trim();
                if (action.isEmpty() && pkg.isEmpty()) return;
                callback.onSelected(ActionSpec.customIntent(
                        modes[selMode[0]], action, pkg,
                        clsIn.getText().toString().trim(),
                        uriIn.getText().toString().trim(),
                        parseExtrasText(exIn.getText().toString()))
                        .withPassthroughDefault(ptSw.isChecked()));
            }
        });
        sheet.show();
    }

    // -------------------------------------------------------------- helpers

    private static Switch addModalSwitchRow(Activity activity, LinearLayout parent,
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

    private static void addCancelSaveFooter(Activity activity, final UiTheme.BottomSheet sheet,
                                            final Runnable onSave) {
        TextView cancelBtn = UiTheme.makeButton(activity, "取消", UiTheme.ButtonStyle.TEXT,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { sheet.dismiss(); }
                });
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.rightMargin = UiTheme.dp(activity, 8);
        sheet.footer.addView(cancelBtn, cLp);

        sheet.footer.addView(UiTheme.makeButton(activity, "保存", UiTheme.ButtonStyle.FILLED,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sheet.dismiss();
                        onSave.run();
                    }
                }));
    }

    /** One-tap copy of the package name shown in every app picker row. */
    private static void copyPackageToClipboard(Activity activity, String packageName) {
        try {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("keypilot_package", packageName));
                android.widget.Toast.makeText(activity, "已复制包名 " + packageName,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    public static List<AppEntry> queryLaunchableApps(Activity activity) {
        List<AppEntry> out = new ArrayList<>();
        try {
            PackageManager pm = activity.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
            Map<String, AppEntry> dedup = new LinkedHashMap<>();
            if (list != null) {
                for (ResolveInfo ri : list) {
                    if (ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    String cls = ri.activityInfo.name;
                    if (pkg == null || dedup.containsKey(pkg)) continue;
                    CharSequence cs = ri.loadLabel(pm);
                    String label = cs != null && cs.length() > 0 ? cs.toString() : pkg;
                    dedup.put(pkg, new AppEntry(label, pkg, cls));
                }
            }
            out.addAll(dedup.values());
            Collections.sort(out, new Comparator<AppEntry>() {
                @Override
                public int compare(AppEntry a, AppEntry b) {
                    return a.label.compareToIgnoreCase(b.label);
                }
            });
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void filterApps(List<AppEntry> all, List<AppEntry> out, String query) {
        out.clear();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.US);
        for (AppEntry e : all) {
            if (q.isEmpty()
                    || e.label.toLowerCase(Locale.US).contains(q)
                    || e.packageName.toLowerCase(Locale.US).contains(q)) {
                out.add(e);
            }
        }
    }

    private static int indexOf(String[] arr, String val, int def) {
        if (val == null) return def;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equalsIgnoreCase(val)) return i;
        }
        return def;
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static String formatExtraValue(Object v) {
        if (v instanceof Boolean) return "bool:" + v;
        if (v instanceof Integer) return "int:" + v;
        if (v instanceof Long) return "long:" + v;
        return String.valueOf(v);
    }

    private static Map<String, Object> parseExtrasText(String raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) return map;
        String[] lines = raw.split("\\r?\\n");
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            if (k.isEmpty()) continue;
            if (v.startsWith("bool:")) {
                map.put(k, Boolean.parseBoolean(v.substring(5).trim()));
            } else if (v.startsWith("int:")) {
                try { map.put(k, Integer.parseInt(v.substring(4).trim())); }
                catch (NumberFormatException ignored) { map.put(k, v); }
            } else if (v.startsWith("long:")) {
                try { map.put(k, Long.parseLong(v.substring(5).trim())); }
                catch (NumberFormatException ignored) { map.put(k, v); }
            } else {
                map.put(k, v);
            }
        }
        return map;
    }
}
