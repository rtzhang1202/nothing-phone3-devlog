package io.github.rtzhang.keypilot.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import io.github.rtzhang.keypilot.StatusProvider;
import io.github.rtzhang.keypilot.config.ActionSpec;
import io.github.rtzhang.keypilot.config.ActionType;
import io.github.rtzhang.keypilot.config.KeyPilotConfig;

/**
 * Activity 探测绑定器 (Activity discovery binder).
 *
 * Main path for binding launch_activity: pick APP (reuses ActionPickerDialog's M3 app
 * selector) -> activity browser (PackageManager GET_ACTIVITIES auto-discovery, search,
 * paged/lazy rendering, try-launch, rename) -> tap a row to bind.
 * The legacy hand-written package/class form is kept, demoted into a collapsible
 * "高级" section of the editor sheet.
 *
 * Try-launch goes through the CONFIG_UPDATE bridge (command=TRY_LAUNCH) so system_server
 * can start non-exported activities; the result comes back via StatusProvider.call().
 */
public final class ActivityBrowserDialog {

    /** Rows rendered per page (lazy window over the filtered list). */
    private static final int PAGE_SIZE = 60;
    private static final long TRY_LAUNCH_TIMEOUT_MS = 6000L;
    private static final long HIGHLIGHT_MS = 1500L;

    private static final int HL_NONE = 0;
    private static final int HL_OK = 1;
    private static final int HL_ERR = 2;

    private ActivityBrowserDialog() {
    }

    private static final class ActivityEntry {
        final String pkg;
        final String cls;
        final boolean exported;

        ActivityEntry(String pkg, String cls, boolean exported) {
            this.pkg = pkg;
            this.cls = cls;
            this.exported = exported;
        }

        String component() {
            return pkg + "/" + cls;
        }

        String shortName() {
            return LabelStore.shortClass(pkg, cls);
        }
    }

    /** Per-browser-sheet state for the single in-flight TRY_LAUNCH request. */
    private static final class TrySession {
        final Handler handler = new Handler(Looper.getMainLooper());
        String token;
        Runnable timeout;
    }

    // ------------------------------------------------------- editor entry point

    /**
     * "启动指定 Activity" editor: primary path = choose APP -> activity browser ->
     * tap to bind; legacy hand-fill lives in a collapsible 高级 section.
     */
    public static void showEditor(final Activity activity,
                                  final ActionSpec current,
                                  final ActionPickerDialog.OnActionSelected callback) {
        final UiTheme.Palette p = UiTheme.colors(activity);
        final boolean hasCurrent = current != null
                && current.type == ActionType.LAUNCH_ACTIVITY
                && current.packageName != null && current.className != null;
        final UiTheme.BottomSheet sheet = UiTheme.createBottomSheet(
                activity,
                "启动指定 Activity",
                hasCurrent ? "重新选择：选 APP → 活动浏览器 → 点选绑定"
                        : "选 APP → 活动浏览器 → 点选绑定，无需手填类名",
                true);

        // ---- current binding preview card (tap = reopen the browser for this APP) ----
        if (hasCurrent) {
            LinearLayout card = UiTheme.makeCard(activity);
            card.setBackground(UiTheme.rippleSurface(activity, p.primaryContainer,
                    0, 0, UiTheme.RADIUS_CARD, p.onPrimaryContainer));
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            ImageView iv = new ImageView(activity);
            Drawable icon = AppIconCache.getRoundedAppIcon(activity, current.packageName, 40);
            iv.setImageDrawable(icon != null ? icon
                    : ActionIcons.forActionType(activity,
                    ActionType.LAUNCH_ACTIVITY, 40, true));
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(
                    UiTheme.dp(activity, 40), UiTheme.dp(activity, 40));
            ivLp.rightMargin = UiTheme.dp(activity, 12);
            row.addView(iv, ivLp);

            LinearLayout col = new LinearLayout(activity);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            String named = LabelStore.getLabel(activity,
                    LabelStore.normalizeComponent(current.packageName, current.className));
            String title = named != null ? named
                    : (current.label != null ? current.label
                    : LabelStore.shortClass(current.packageName, current.className));
            TextView tTv = UiTheme.makeText(activity, title, 15, true, p.onPrimaryContainer);
            tTv.setSingleLine(true);
            tTv.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(tTv);
            TextView cTv = UiTheme.makeText(activity,
                    current.packageName + "/" + LabelStore.shortClass(
                            current.packageName, current.className),
                    11, false, p.onPrimaryContainer);
            cTv.setMaxLines(2);
            cTv.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(cTv);
            row.addView(col);

            TextView browse = smallPill(activity, "浏览该 APP", true);
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bLp.leftMargin = UiTheme.dp(activity, 8);
            row.addView(browse, bLp);
            card.addView(row);
            sheet.body.addView(card);

            final String curPkg = current.packageName;
            browse.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sheet.dismiss();
                    showBrowser(activity, new ActionPickerDialog.AppEntry(
                            AppIconCache.getAppName(activity, curPkg), curPkg, null), callback);
                }
            });
        }

        // ---- primary path: pick APP ----
        TextView pickBtn = UiTheme.makeButton(activity, "选择 APP → 活动浏览器",
                UiTheme.ButtonStyle.FILLED, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sheet.dismiss();
                        ActionPickerDialog.showAppPickerForActivity(activity,
                                "选择要绑定的应用", new ActionPickerDialog.AppPickedCallback() {
                                    @Override
                                    public void onPicked(ActionPickerDialog.AppEntry e) {
                                        showBrowser(activity, e, callback);
                                    }
                                });
                    }
                });
        LinearLayout.LayoutParams pkLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pkLp.topMargin = UiTheme.dp(activity, 8);
        sheet.body.addView(pickBtn, pkLp);

        TextView hint = UiTheme.makeText(activity,
                "探测由软件完成：自动列出该 APP 全部 Activity（含未导出），可试开启、重命名后绑定。",
                12, false, p.onSurfaceVariant);
        hint.setMaxLines(3);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = UiTheme.dp(activity, 8);
        sheet.body.addView(hint, hLp);

        // ---- advanced (legacy hand-fill) collapsible section ----
        sheet.body.addView(UiTheme.makeDivider(activity));
        sheet.body.addView(UiTheme.makeSectionHeader(activity, "高级"));

        final LinearLayout advBox = new LinearLayout(activity);
        advBox.setOrientation(LinearLayout.VERTICAL);
        advBox.setVisibility(View.GONE);

        final EditText pkgIn = UiTheme.makeInput(activity, "包名（如 com.nothing.ntessentialspace）",
                hasCurrent ? current.packageName : "");
        final EditText clsIn = UiTheme.makeInput(activity, "完整类名（如 .MainActivity）",
                hasCurrent ? current.className : "");
        advBox.addView(pkgIn);
        advBox.addView(clsIn);

        TextView advHint = UiTheme.makeText(activity,
                "内置名单未覆盖的自定义入口才需要手填；类名可写 . 开头的相对形式。",
                11, false, p.onSurfaceVariant);
        advHint.setMaxLines(2);
        LinearLayout.LayoutParams ahLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ahLp.topMargin = UiTheme.dp(activity, 4);
        advBox.addView(advHint, ahLp);

        final TextView[] saveHolder = new TextView[1];

        final LinearLayout toggleRow = UiTheme.makeInnerRow(activity, true);
        TextView toggleTv = UiTheme.makeText(activity, "手填包名 / 类名", 14, true, p.onSurface);
        toggleRow.addView(toggleTv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView chev = UiTheme.makeText(activity, "▸", 16, true, p.onSurfaceVariant);
        toggleRow.addView(chev);
        toggleRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean open = advBox.getVisibility() != View.VISIBLE;
                advBox.setVisibility(open ? View.VISIBLE : View.GONE);
                chev.setText(open ? "▾" : "▸");
                if (saveHolder[0] != null) {
                    saveHolder[0].setVisibility(open ? View.VISIBLE : View.GONE);
                }
            }
        });
        // toggle sits above the inputs it opens
        sheet.body.addView(toggleRow);
        sheet.body.addView(advBox);

        // ---- footer ----
        TextView cancelBtn = UiTheme.makeButton(activity, "取消", UiTheme.ButtonStyle.TEXT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sheet.dismiss();
                    }
                });
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.rightMargin = UiTheme.dp(activity, 8);
        sheet.footer.addView(cancelBtn, cLp);

        TextView saveBtn = UiTheme.makeButton(activity, "保存手填", UiTheme.ButtonStyle.FILLED,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String pkg = pkgIn.getText().toString().trim();
                        String cls = clsIn.getText().toString().trim();
                        if (pkg.isEmpty() || cls.isEmpty()) {
                            Toast.makeText(activity, "包名与类名均不可为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (cls.startsWith(".")) cls = pkg + cls;
                        sheet.dismiss();
                        callback.onSelected(ActionSpec.launchActivity(pkg, cls, null));
                    }
                });
        saveBtn.setVisibility(View.GONE);
        saveHolder[0] = saveBtn;
        sheet.footer.addView(saveBtn);

        sheet.show();
    }

    // ------------------------------------------------------------- browser

    public static void showBrowser(final Activity activity,
                                   final ActionPickerDialog.AppEntry app,
                                   final ActionPickerDialog.OnActionSelected callback) {
        final UiTheme.Palette p = UiTheme.colors(activity);
        final String pkg = app.packageName;
        final UiTheme.BottomSheet sheet = UiTheme.createBottomSheet(
                activity,
                app.label,
                "自动探测 " + pkg + " 的全部 Activity · 点选行即绑定",
                false);

        final EditText search = UiTheme.makeInput(activity, "搜索活动名或类名...", "");
        sheet.body.addView(search);

        final TextView countTv = UiTheme.makeText(activity, "正在探测 Activity ...",
                12, false, p.onSurfaceVariant);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = UiTheme.dp(activity, 8);
        cLp.bottomMargin = UiTheme.dp(activity, 4);
        sheet.body.addView(countTv, cLp);

        final List<ActivityEntry> all = new ArrayList<>();
        final List<ActivityEntry> filtered = new ArrayList<>();
        final List<ActivityEntry> shown = new ArrayList<>();
        final int[] pageEnd = {PAGE_SIZE};
        final Map<String, Integer> highlight = new HashMap<>();
        final TrySession session = new TrySession();

        final ListView listView = new ListView(activity);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        int listH = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.50f);
        sheet.body.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listH));

        final BaseAdapter[] adapterRef = new BaseAdapter[1];

        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int pos) { return shown.get(pos); }
            @Override public long getItemId(int pos) { return pos; }

            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                final ActivityEntry e = shown.get(pos);
                LinearLayout wrap = (LinearLayout) buildActivityRow(activity, app, e,
                        highlight, session, callback, sheet, all, adapterRef);
                if (pos + 1 < shown.size()) {
                    wrap.addView(UiTheme.makeListDivider(activity));
                }
                return wrap;
            }
        };
        adapterRef[0] = adapter;
        listView.setAdapter(adapter);

        // lazy window: append the next page when the user scrolls near the end
        listView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int firstVisible, int visibleCount,
                                 int totalCount) {
                if (totalCount > 0 && firstVisible + visibleCount >= totalCount - 2
                        && pageEnd[0] < filtered.size()) {
                    pageEnd[0] = Math.min(filtered.size(), pageEnd[0] + PAGE_SIZE);
                    rebuildShown(filtered, shown, pageEnd[0]);
                    adapter.notifyDataSetChanged();
                    countTv.setText(countText(filtered.size(), shown.size()));
                }
            }
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                applyFilter(all, filtered, s != null ? s.toString() : "");
                pageEnd[0] = PAGE_SIZE;
                rebuildShown(filtered, shown, pageEnd[0]);
                adapter.notifyDataSetChanged();
                countTv.setText(countText(filtered.size(), shown.size()));
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        sheet.footer.addView(UiTheme.makeButton(activity, "取消", UiTheme.ButtonStyle.TEXT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sheet.dismiss();
                    }
                }));

        // ---- TRY_LAUNCH result listener (single in-flight request, 6s watchdog) ----
        StatusProvider.setTryLaunchListener(new StatusProvider.TryLaunchListener() {
            @Override
            public void onTryLaunchResult(final String token, final boolean ok,
                                          final String error, final String component) {
                if (session.token == null || !session.token.equals(token)) return;
                session.token = null;
                if (session.timeout != null) {
                    session.handler.removeCallbacks(session.timeout);
                    session.timeout = null;
                }
                highlight.put(component, ok ? HL_OK : HL_ERR);
                adapter.notifyDataSetChanged();
                session.handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        highlight.clear();
                        adapter.notifyDataSetChanged();
                    }
                }, HIGHLIGHT_MS);
                String name = findName(activity, all, component);
                if (ok) {
                    Toast.makeText(activity, "已拉起 " + name, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(activity, "拉起失败：" + (error != null ? error : "未知原因"),
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        sheet.dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                StatusProvider.setTryLaunchListener(null);
                session.handler.removeCallbacksAndMessages(null);
            }
        });

        sheet.show();

        // ---- background discovery: PackageManager.getPackageInfo(GET_ACTIVITIES) ----
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<ActivityEntry> loaded = new ArrayList<>();
                final String[] failure = {null};
                try {
                    PackageManager pm = activity.getPackageManager();
                    PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
                    ActivityInfo[] acts = pi.activities;
                    if (acts != null) {
                        for (ActivityInfo ai : acts) {
                            if (ai == null || ai.name == null || ai.name.isEmpty()) continue;
                            loaded.add(new ActivityEntry(pkg, ai.name, ai.exported));
                        }
                    }
                    Collections.sort(loaded, new Comparator<ActivityEntry>() {
                        @Override
                        public int compare(ActivityEntry a, ActivityEntry b) {
                            return a.shortName().compareToIgnoreCase(b.shortName());
                        }
                    });
                } catch (Throwable t) {
                    failure[0] = t.getClass().getSimpleName() + ": "
                            + (t.getMessage() != null ? t.getMessage() : "");
                }
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (failure[0] != null) {
                            countTv.setText("探测失败：" + failure[0]);
                            return;
                        }
                        all.clear();
                        all.addAll(loaded);
                        applyFilter(all, filtered, search.getText().toString());
                        pageEnd[0] = PAGE_SIZE;
                        rebuildShown(filtered, shown, pageEnd[0]);
                        adapter.notifyDataSetChanged();
                        countTv.setText(countText(filtered.size(), shown.size()));
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------- row UI

    private static View buildActivityRow(final Activity activity,
                                         final ActionPickerDialog.AppEntry app,
                                         final ActivityEntry e,
                                         final Map<String, Integer> highlight,
                                         final TrySession session,
                                         final ActionPickerDialog.OnActionSelected callback,
                                         final UiTheme.BottomSheet sheet,
                                         final List<ActivityEntry> all,
                                         final BaseAdapter[] adapterRef) {
        UiTheme.Palette p = UiTheme.colors(activity);
        Integer hlObj = highlight.get(e.component());
        int hl = hlObj != null ? hlObj : HL_NONE;

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(UiTheme.rippleSurface(activity,
                hl == HL_OK ? p.primaryContainer : (hl == HL_ERR ? p.errorContainer : 0x00000000),
                0, 0, UiTheme.RADIUS_CARD, p.onSurface));
        row.setPadding(UiTheme.dp(activity, 8), UiTheme.dp(activity, 10),
                UiTheme.dp(activity, 10), UiTheme.dp(activity, 10));

        ImageView iv = new ImageView(activity);
        Drawable icon = AppIconCache.getRoundedAppIcon(activity, app.packageName, 40);
        iv.setImageDrawable(icon != null ? icon
                : ActionIcons.forActionType(activity,
                ActionType.LAUNCH_ACTIVITY, 40, true));
        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(
                UiTheme.dp(activity, 40), UiTheme.dp(activity, 40));
        ivLp.rightMargin = UiTheme.dp(activity, 10);
        row.addView(iv, ivLp);

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout nameLine = new LinearLayout(activity);
        nameLine.setOrientation(LinearLayout.HORIZONTAL);
        nameLine.setGravity(Gravity.CENTER_VERTICAL);

        String named = LabelStore.getLabel(activity, e.component());
        String display = named != null ? named : e.shortName();
        TextView nameTv = UiTheme.makeText(activity, display, 14, true,
                hl == HL_ERR ? p.onErrorContainer : p.onSurface);
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(TextUtils.TruncateAt.END);
        nameTv.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nameLine.addView(nameTv);

        TextView expChip = UiTheme.makeChip(activity, e.exported ? "导出" : "非导出",
                e.exported);
        LinearLayout.LayoutParams ecLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ecLp.leftMargin = UiTheme.dp(activity, 6);
        nameLine.addView(expChip, ecLp);
        col.addView(nameLine);

        TextView compTv = UiTheme.makeText(activity, e.component(), 10, false,
                hl == HL_ERR ? p.onErrorContainer : p.onSurfaceVariant);
        compTv.setMaxLines(2);
        compTv.setEllipsize(TextUtils.TruncateAt.END);
        compTv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToClipboard(activity, e.component());
            }
        });
        col.addView(compTv);
        row.addView(col);

        LinearLayout btnCol = new LinearLayout(activity);
        btnCol.setOrientation(LinearLayout.VERTICAL);
        btnCol.setGravity(Gravity.END);
        LinearLayout.LayoutParams bcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bcLp.leftMargin = UiTheme.dp(activity, 6);
        row.addView(btnCol, bcLp);

        TextView tryBtn = smallPill(activity, "试开启", true);
        btnCol.addView(tryBtn);
        LinearLayout.LayoutParams rnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rnLp.topMargin = UiTheme.dp(activity, 6);
        TextView renameBtn = smallPill(activity, "重命名", false);
        btnCol.addView(renameBtn, rnLp);

        tryBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tryLaunch(activity, e, session);
            }
        });
        renameBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRenameDialog(activity, e, adapterRef[0]);
            }
        });

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String namedNow = LabelStore.getLabel(activity, e.component());
                String title = namedNow != null ? namedNow
                        : (LabelStore.shortClass(e.pkg, e.cls));
                sheet.dismiss();
                callback.onSelected(ActionSpec.launchActivity(e.pkg, e.cls, title));
            }
        });

        // outer wrapper carries the M3 list divider (mirrors the app picker rows)
        LinearLayout wrap = new LinearLayout(activity);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        return wrap;
    }

    // ------------------------------------------------------------- rename

    private static void showRenameDialog(final Activity activity,
                                         final ActivityEntry e,
                                         final BaseAdapter adapter) {
        UiTheme.Palette p = UiTheme.colors(activity);
        final UiTheme.BottomSheet rs = UiTheme.createBottomSheet(
                activity, "重命名活动", e.component(), true);
        final String current = LabelStore.getLabel(activity, e.component());

        final EditText input = UiTheme.makeInput(activity, "显示名称（如：扫一扫）",
                current != null ? current : "");
        rs.body.addView(input);

        TextView hint = UiTheme.makeText(activity,
                "命名只影响 KeyPilot 内的显示（列表 / 矩阵 / 场景卡），不会改动目标应用本身。",
                12, false, p.onSurfaceVariant);
        hint.setMaxLines(3);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = UiTheme.dp(activity, 8);
        rs.body.addView(hint, hLp);

        if (current != null) {
            TextView clearBtn = UiTheme.makeButton(activity, "清除命名",
                    UiTheme.ButtonStyle.TEXT_DANGER, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            LabelStore.removeUserLabel(activity, e.component());
                            rs.dismiss();
                            if (adapter != null) adapter.notifyDataSetChanged();
                            Toast.makeText(activity, "已清除命名", Toast.LENGTH_SHORT).show();
                        }
                    });
            LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clLp.rightMargin = UiTheme.dp(activity, 8);
            rs.footer.addView(clearBtn, clLp);
        }

        TextView cancelBtn = UiTheme.makeButton(activity, "取消", UiTheme.ButtonStyle.TEXT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        rs.dismiss();
                    }
                });
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.rightMargin = UiTheme.dp(activity, 8);
        rs.footer.addView(cancelBtn, cLp);

        rs.footer.addView(UiTheme.makeButton(activity, "保存", UiTheme.ButtonStyle.FILLED,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String value = input.getText().toString().trim();
                        if (value.isEmpty()) {
                            Toast.makeText(activity, "名称不可为空",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        LabelStore.putUserLabel(activity, e.component(), value);
                        rs.dismiss();
                        if (adapter != null) adapter.notifyDataSetChanged();
                        Toast.makeText(activity, "已保存命名：" + value,
                                Toast.LENGTH_SHORT).show();
                    }
                }));
        rs.show();
    }

    // ------------------------------------------------------------- try launch

    private static void tryLaunch(final Activity activity, final ActivityEntry e,
                                  final TrySession session) {
        if (session.token != null) {
            Toast.makeText(activity, "上一个试开启还在进行中", Toast.LENGTH_SHORT).show();
            return;
        }
        final String token = UUID.randomUUID().toString();
        session.token = token;
        try {
            Intent intent = new Intent(KeyPilotConfig.ACTION_CONFIG_UPDATE);
            intent.putExtra(KeyPilotConfig.EXTRA_COMMAND, KeyPilotConfig.CMD_TRY_LAUNCH);
            intent.putExtra(KeyPilotConfig.EXTRA_TRY_PACKAGE, e.pkg);
            intent.putExtra(KeyPilotConfig.EXTRA_TRY_CLASS, e.cls);
            intent.putExtra(KeyPilotConfig.EXTRA_TRY_TOKEN, token);
            activity.sendBroadcast(intent);
        } catch (Throwable t) {
            session.token = null;
            Toast.makeText(activity, "发送试开启请求失败：" + t.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }
        // watchdog: the hook must answer through StatusProvider within the timeout
        final Runnable timeout = new Runnable() {
            @Override
            public void run() {
                if (token.equals(session.token)) {
                    session.token = null;
                    Toast.makeText(activity, "钩子未响应（请确认模块已启用并重启）",
                            Toast.LENGTH_LONG).show();
                }
            }
        };
        session.timeout = timeout;
        session.handler.postDelayed(timeout, TRY_LAUNCH_TIMEOUT_MS);
    }

    // ------------------------------------------------------------- helpers

    private static void applyFilter(List<ActivityEntry> all, List<ActivityEntry> out,
                                    String query) {
        out.clear();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.US);
        for (ActivityEntry e : all) {
            if (q.isEmpty()
                    || e.shortName().toLowerCase(Locale.US).contains(q)
                    || e.cls.toLowerCase(Locale.US).contains(q)
                    || e.component().toLowerCase(Locale.US).contains(q)) {
                out.add(e);
            }
        }
    }

    private static void rebuildShown(List<ActivityEntry> filtered, List<ActivityEntry> shown,
                                     int pageEnd) {
        shown.clear();
        int end = Math.min(pageEnd, filtered.size());
        for (int i = 0; i < end; i++) {
            shown.add(filtered.get(i));
        }
    }

    private static String countText(int total, int shown) {
        if (total <= 0) return "未探测到 Activity（该应用未声明可启动活动）";
        return "共 " + total + " 个活动 · 已显示 " + shown + " 个（滚动加载更多）";
    }

    private static String findName(Context ctx, List<ActivityEntry> all, String component) {
        if (component == null) return "";
        for (ActivityEntry e : all) {
            if (e.component().equals(component)) {
                String named = null;
                try {
                    named = LabelStore.getLabel(ctx, component);
                } catch (Throwable ignored) {
                }
                return named != null ? named : e.shortName();
            }
        }
        int slash = component.indexOf('/');
        return slash > 0 ? component.substring(slash + 1) : component;
    }

    /** Small M3 pill built from the UiTheme vocabulary (no raw Button). */
    private static TextView smallPill(Activity activity, String text, boolean tonal) {
        UiTheme.Palette p = UiTheme.colors(activity);
        TextView tv = UiTheme.makeText(activity, text, 12, true,
                tonal ? p.onPrimaryContainer : p.primary);
        tv.setBackground(tonal
                ? UiTheme.rippleSurface(activity, p.primaryContainer, 0, 0,
                UiTheme.RADIUS_PILL, p.onPrimaryContainer)
                : UiTheme.rippleSurface(activity, Color.TRANSPARENT, p.outline, 1,
                UiTheme.RADIUS_PILL, p.primary));
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setMinHeight(UiTheme.dp(activity, 32));
        tv.setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 6),
                UiTheme.dp(activity, 12), UiTheme.dp(activity, 6));
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private static void copyToClipboard(Context ctx, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("keypilot_component", text));
                Toast.makeText(ctx, "已复制组件名", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }
}
