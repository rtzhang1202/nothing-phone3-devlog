package io.github.rtzhang.keypilot.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;


/**
 * 活动命名管理 (activity label manager) — settings entry of the Activity 探测绑定器.
 *
 * - local list: view / edit / delete user labels,
 * - export: SAVE (SAF ACTION_CREATE_DOCUMENT) or share as text,
 * - import: SAF ACTION_OPEN_DOCUMENT -> merge (fill gaps first, conflicts prompt:
 *   keep local or overwrite with the imported values).
 */
public final class LabelManagerDialog {

    public static final int REQ_EXPORT_FILE = 6401;
    public static final int REQ_IMPORT_FILE = 6402;

    /** Set while a SAF round-trip is in flight so onActivityResult can resume the flow. */
    private static Runnable sRefresh;

    private LabelManagerDialog() {
    }

    public static void show(final Activity activity) {
        final UiTheme.Palette p = UiTheme.colors(activity);
        final UiTheme.BottomSheet sheet = UiTheme.createBottomSheet(
                activity,
                "活动命名管理",
                "本地命名查看 / 编辑 / 删除，JSON 导出分享与导入合并",
                true);

        // ---- my labels ----
        sheet.body.addView(UiTheme.makeSectionHeader(activity, "我的命名"));
        final TextView myCount = UiTheme.makeText(activity, "", 12, false, p.onSurfaceVariant);
        LinearLayout.LayoutParams mcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mcLp.topMargin = UiTheme.dp(activity, 4);
        sheet.body.addView(myCount, mcLp);

        final LinearLayout myList = new LinearLayout(activity);
        myList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlLp.topMargin = UiTheme.dp(activity, 8);
        sheet.body.addView(myList, mlLp);

        // ---- community / built-in list (collapsible) ----
        sheet.body.addView(UiTheme.makeDivider(activity));
        sheet.body.addView(UiTheme.makeSectionHeader(activity, "社区 / 内置名单"));
        final TextView commCount = UiTheme.makeText(activity, "", 12, false, p.onSurfaceVariant);
        LinearLayout.LayoutParams ccLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ccLp.topMargin = UiTheme.dp(activity, 4);
        sheet.body.addView(commCount, ccLp);

        final LinearLayout commList = new LinearLayout(activity);
        commList.setOrientation(LinearLayout.VERTICAL);
        commList.setVisibility(View.GONE);
        LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clLp.topMargin = UiTheme.dp(activity, 8);
        sheet.body.addView(commList, clLp);

        TextView toggle = UiTheme.makeButton(activity, "展开内置名单",
                UiTheme.ButtonStyle.OUTLINED, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean open = commList.getVisibility() != View.VISIBLE;
                        commList.setVisibility(open ? View.VISIBLE : View.GONE);
                        ((TextView) v).setText(open ? "收起内置名单" : "展开内置名单");
                    }
                });
        LinearLayout.LayoutParams tgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tgLp.topMargin = UiTheme.dp(activity, 8);
        sheet.body.addView(toggle, tgLp);

        // ---- import / export ----
        sheet.body.addView(UiTheme.makeDivider(activity));
        sheet.body.addView(UiTheme.makeSectionHeader(activity, "导入 / 导出"));

        LinearLayout ioRow = new LinearLayout(activity);
        ioRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ioLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ioLp.topMargin = UiTheme.dp(activity, 8);
        ioRow.setLayoutParams(ioLp);

        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.rightMargin = UiTheme.dp(activity, 8);

        ioRow.addView(UiTheme.makeButton(activity, "导出 JSON", UiTheme.ButtonStyle.TONAL,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showExportOptions(activity);
                    }
                }), gap);
        ioRow.addView(UiTheme.makeButton(activity, "导入 JSON", UiTheme.ButtonStyle.OUTLINED,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openImportPicker(activity);
                    }
                }), new LinearLayout.LayoutParams(gap));
        sheet.body.addView(ioRow);

        TextView fmtHint = UiTheme.makeText(activity,
                "格式 {\"组件名\": {\"label\":\"显示名\", \"source\":\"user|community\", \"updatedAt\":毫秒}}；"
                        + "导入先补空缺，冲突再提示（可选覆盖）。",
                11, false, p.onSurfaceVariant);
        fmtHint.setMaxLines(3);
        LinearLayout.LayoutParams fhLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fhLp.topMargin = UiTheme.dp(activity, 8);
        sheet.body.addView(fmtHint, fhLp);

        sRefresh = new Runnable() {
            @Override
            public void run() {
                rebuildLists(activity, myList, commList, myCount, commCount);
            }
        };
        sRefresh.run();

        sheet.footer.addView(UiTheme.makeButton(activity, "关闭", UiTheme.ButtonStyle.FILLED,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sheet.dismiss();
                    }
                }));
        sheet.dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface d) {
                sRefresh = null;
            }
        });
        sheet.show();
    }

    // ------------------------------------------------------------- rendering

    private static void rebuildLists(Activity activity, LinearLayout myList,
                                     LinearLayout commList, TextView myCount,
                                     TextView commCount) {
        UiTheme.Palette p = UiTheme.colors(activity);

        List<Map.Entry<String, LabelStore.Entry>> mine = LabelStore.userEntries(activity);
        myCount.setText("共 " + mine.size() + " 条 · 命名后列表 / 矩阵 / 场景卡即时生效");
        myList.removeAllViews();
        if (mine.isEmpty()) {
            TextView empty = UiTheme.makeText(activity,
                    "还没有本地命名：到「启动指定 Activity → 活动浏览器」给活动起个名字吧",
                    12, false, p.onSurfaceVariant);
            empty.setMaxLines(3);
            LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            eLp.topMargin = UiTheme.dp(activity, 4);
            myList.addView(empty, eLp);
        }
        for (int i = 0; i < mine.size(); i++) {
            final Map.Entry<String, LabelStore.Entry> entry = mine.get(i);
            if (i > 0) myList.addView(UiTheme.makeListDivider(activity));
            myList.addView(buildLabelRow(activity, entry, true));
        }

        List<Map.Entry<String, LabelStore.Entry>> comm = LabelStore.communityEntries(activity);
        commCount.setText("共 " + comm.size() + " 条 · 随包内置，只读（可在管理页之上用导入覆盖）");
        commList.removeAllViews();
        for (int i = 0; i < comm.size(); i++) {
            Map.Entry<String, LabelStore.Entry> entry = comm.get(i);
            if (i > 0) commList.addView(UiTheme.makeListDivider(activity));
            commList.addView(buildLabelRow(activity, entry, false));
        }
    }

    private static View buildLabelRow(final Activity activity,
                                      final Map.Entry<String, LabelStore.Entry> entry,
                                      final boolean editable) {
        UiTheme.Palette p = UiTheme.colors(activity);
        String component = entry.getKey();
        String pkg = component.contains("/")
                ? component.substring(0, component.indexOf('/')) : component;

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(UiTheme.rippleSurface(activity, 0x00000000, 0, 0,
                UiTheme.RADIUS_CARD, p.onSurface));
        row.setPadding(UiTheme.dp(activity, 8), UiTheme.dp(activity, 8),
                UiTheme.dp(activity, 8), UiTheme.dp(activity, 8));

        ImageView iv = new ImageView(activity);
        Drawable icon = AppIconCache.getRoundedAppIcon(activity, pkg, 32);
        iv.setImageDrawable(icon != null ? icon
                : ActionIcons.forActionType(activity,
                io.github.rtzhang.keypilot.config.ActionType.LAUNCH_ACTIVITY, 32, editable));
        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(
                UiTheme.dp(activity, 32), UiTheme.dp(activity, 32));
        ivLp.rightMargin = UiTheme.dp(activity, 10);
        row.addView(iv, ivLp);

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView nameTv = UiTheme.makeText(activity, entry.getValue().label, 14, true,
                p.onSurface);
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(nameTv);
        TextView compTv = UiTheme.makeText(activity, component, 10, false, p.onSurfaceVariant);
        compTv.setMaxLines(2);
        compTv.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(compTv);
        TextView srcTv = UiTheme.makeText(activity,
                ("user".equals(entry.getValue().source) ? "我的命名" : "内置名单"), 10, true,
                editable ? p.primary : p.onSurfaceVariant);
        col.addView(srcTv);
        row.addView(col);

        if (editable) {
            LinearLayout btnCol = new LinearLayout(activity);
            btnCol.setOrientation(LinearLayout.VERTICAL);
            btnCol.setGravity(Gravity.END);
            LinearLayout.LayoutParams bcLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bcLp.leftMargin = UiTheme.dp(activity, 6);
            row.addView(btnCol, bcLp);

            TextView editBtn = makeMiniPill(activity, "编辑", false);
            btnCol.addView(editBtn);
            LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            delLp.topMargin = UiTheme.dp(activity, 6);
            TextView delBtn = makeMiniPill(activity, "删除", false);
            delBtn.setTextColor(p.error);
            btnCol.addView(delBtn, delLp);

            editBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showEditSheet(activity, entry.getKey(), entry.getValue().label);
                }
            });
            delBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    UiTheme.showConfirmSheet(activity, "删除命名",
                            "删除「" + entry.getValue().label + "」？该活动将回退到内置名单或短类名。",
                            "删除", true, new Runnable() {
                                @Override
                                public void run() {
                                    LabelStore.removeUserLabel(activity, entry.getKey());
                                    Toast.makeText(activity, "已删除命名", Toast.LENGTH_SHORT).show();
                                    if (sRefresh != null) sRefresh.run();
                                }
                            });
                }
            });
        }
        return row;
    }

    private static void showEditSheet(final Activity activity, final String component,
                                      String currentLabel) {
        UiTheme.Palette p = UiTheme.colors(activity);
        final UiTheme.BottomSheet es = UiTheme.createBottomSheet(activity, "编辑命名",
                component, true);
        final EditText input = UiTheme.makeInput(activity, "显示名称", currentLabel);
        es.body.addView(input);

        TextView hint = UiTheme.makeText(activity,
                "命名只影响 KeyPilot 内的显示，不会改动目标应用本身。", 12, false, p.onSurfaceVariant);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = UiTheme.dp(activity, 8);
        es.body.addView(hint, hLp);

        TextView cancelBtn = UiTheme.makeButton(activity, "取消", UiTheme.ButtonStyle.TEXT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        es.dismiss();
                    }
                });
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.rightMargin = UiTheme.dp(activity, 8);
        es.footer.addView(cancelBtn, cLp);

        es.footer.addView(UiTheme.makeButton(activity, "保存", UiTheme.ButtonStyle.FILLED,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String value = input.getText().toString().trim();
                        if (value.isEmpty()) {
                            Toast.makeText(activity, "名称不可为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        LabelStore.putUserLabel(activity, component, value);
                        es.dismiss();
                        if (sRefresh != null) sRefresh.run();
                        Toast.makeText(activity, "已保存命名：" + value, Toast.LENGTH_SHORT).show();
                    }
                }));
        es.show();
    }

    // ------------------------------------------------------------- import / export

    private static void showExportOptions(final Activity activity) {
        UiTheme.showOptionSheet(activity, "导出活动命名 JSON",
                "共 " + LabelStore.userCount(activity) + " 条本地命名",
                new String[]{"保存为文件（JSON）", "分享到其它应用"},
                -1, new UiTheme.OnOptionPicked() {
                    @Override
                    public void onPicked(int which) {
                        if (which == 0) {
                            openExportPicker(activity);
                        } else {
                            shareLabels(activity);
                        }
                    }
                });
    }

    private static void openExportPicker(Activity activity) {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "keypilot_labels.json");
            activity.startActivityForResult(intent, REQ_EXPORT_FILE);
        } catch (Throwable t) {
            Toast.makeText(activity, "无法打开文件保存器：" + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private static void openImportPicker(Activity activity) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            // application/json only: a */* filter makes DocumentsUI offer an
            // "open with" chooser instead of returning the picked URI directly.
            intent.setType("application/json");
            activity.startActivityForResult(intent, REQ_IMPORT_FILE);
        } catch (Throwable t) {
            Toast.makeText(activity, "无法打开文件选择器：" + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private static void shareLabels(Activity activity) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "keypilot_labels.json");
            send.putExtra(Intent.EXTRA_TEXT, LabelStore.exportJson());
            activity.startActivity(Intent.createChooser(send, "分享活动命名 JSON"));
        } catch (Throwable t) {
            Toast.makeText(activity, "分享失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Routed from MainActivity.onActivityResult; returns true when the request was ours.
     */
    public static boolean handleActivityResult(Activity activity, int requestCode,
                                               int resultCode, Intent data) {
        if (requestCode != REQ_EXPORT_FILE && requestCode != REQ_IMPORT_FILE) return false;
        try {
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                Toast.makeText(activity, "已取消", Toast.LENGTH_SHORT).show();
                return true;
            }
            android.net.Uri uri = data.getData();
            if (requestCode == REQ_EXPORT_FILE) {
                OutputStream os = activity.getContentResolver().openOutputStream(uri);
                if (os == null) throw new IllegalStateException("无法打开输出流");
                try {
                    os.write(LabelStore.exportJson().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } finally {
                    os.close();
                }
                Toast.makeText(activity,
                        "已导出 " + LabelStore.userCount(activity) + " 条命名", Toast.LENGTH_SHORT).show();
            } else {
                InputStream is = activity.getContentResolver().openInputStream(uri);
                if (is == null) throw new IllegalStateException("无法打开输入流");
                String raw;
                try {
                    raw = readAll(is);
                } finally {
                    is.close();
                }
                applyImport(activity, raw);
            }
        } catch (Throwable t) {
            Toast.makeText(activity, "导入 / 导出失败：" + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
        return true;
    }

    /** Merge pass 1: fill gaps; conflicts are reported and can be applied afterwards. */
    private static void applyImport(final Activity activity, String raw) {
        LabelStore.ImportResult res = LabelStore.importJson(activity, raw, false);
        if (res.total == 0) {
            Toast.makeText(activity, "没有可识别的命名条目（格式？）", Toast.LENGTH_LONG).show();
            return;
        }
        if (sRefresh != null) sRefresh.run();
        if (res.conflicts == 0) {
            Toast.makeText(activity, "导入完成：新增 " + res.added + " 条",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        UiTheme.showConfirmSheet(activity, "导入冲突提示",
                "共识别 " + res.total + " 条：新增 " + res.added + " 条，"
                        + "与本地命名冲突 " + res.conflicts + " 条。\n\n"
                        + "「覆盖」= 用导入文件覆盖这 " + res.conflicts + " 条；"
                        + "「取消」= 保留本地命名（空缺已补齐）。",
                "覆盖冲突项", true, new Runnable() {
                    @Override
                    public void run() {
                        LabelStore.ImportResult over = LabelStore.importJson(activity, raw, true);
                        if (sRefresh != null) sRefresh.run();
                        Toast.makeText(activity,
                                "已覆盖 " + over.updated + " 条冲突命名",
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static TextView makeMiniPill(Activity activity, String text, boolean tonal) {
        UiTheme.Palette p = UiTheme.colors(activity);
        TextView tv = UiTheme.makeText(activity, text, 12, true,
                tonal ? p.onPrimaryContainer : p.primary);
        tv.setBackground(UiTheme.rippleSurface(activity,
                tonal ? p.primaryContainer : android.graphics.Color.TRANSPARENT,
                tonal ? 0 : p.outline, tonal ? 0 : 1, UiTheme.RADIUS_PILL, p.primary));
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setMinHeight(UiTheme.dp(activity, 30));
        tv.setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 6),
                UiTheme.dp(activity, 12), UiTheme.dp(activity, 6));
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }
}
