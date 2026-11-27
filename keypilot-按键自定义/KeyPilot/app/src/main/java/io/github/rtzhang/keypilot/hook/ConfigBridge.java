package io.github.rtzhang.keypilot.hook;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.rtzhang.keypilot.StatusProvider;
import io.github.rtzhang.keypilot.config.KeyPilotConfig;

/**
 * Dual-path configuration bridge inside system_server (uid=1000):
 * 1. Cold start / reboot: loads /data/system/keypilot.json into memory.
 * 2. Hot reload: dynamically registers BroadcastReceiver for io.github.rtzhang.keypilot.CONFIG_UPDATE,
 *    updates in-memory config immediately, and atomically persists to /data/system/keypilot.json.
 *
 * Since v2.1 the same broadcast also carries two commands (docs/DESIGN.md §3.5):
 *   - TRY_LAUNCH      : startActivity (system uid, may open non-exported activities) with the
 *                       result returned to the app through StatusProvider.call().
 *   - LABELS_UPDATE   : mirror the activity label map to /data/system/keypilot_labels.json.
 */
public final class ConfigBridge {

    private static final int RECEIVER_EXPORTED_FLAG = 0x2; // Context.RECEIVER_EXPORTED (API 33+)

    private static volatile KeyPilotConfig sConfig = KeyPilotConfig.createDefault();
    private static volatile Context sSystemContext;
    private static final AtomicBoolean sLoadedFromDisk = new AtomicBoolean(false);
    private static final AtomicBoolean sReceiverRegistered = new AtomicBoolean(false);

    private static volatile Handler sIoHandler;

    private ConfigBridge() {
    }

    public static KeyPilotConfig getConfig() {
        if (!sLoadedFromDisk.get()) {
            loadFromDiskOnce();
        }
        return sConfig;
    }

    public static Context getSystemContext() {
        Context ctx = sSystemContext;
        if (ctx != null) {
            return ctx;
        }
        ctx = resolveSystemContext();
        if (ctx != null) {
            sSystemContext = ctx;
        }
        return ctx;
    }

    /**
     * Safe to invoke multiple times from PhoneWindowManager.init, systemReady, or key hooks.
     * Retries BroadcastReceiver registration cleanly if called before AMS is ready during early boot.
     */
    public static void ensureInitialized(Context ctx) {
        try {
            if (ctx != null && sSystemContext == null) {
                sSystemContext = ctx;
            }
            Context useCtx = sSystemContext != null ? sSystemContext : resolveSystemContext();
            if (useCtx != null) {
                sSystemContext = useCtx;
            }
            loadFromDiskOnce();
            if (useCtx != null && !sReceiverRegistered.get()) {
                tryRegisterReceiver(useCtx);
            }
        } catch (Throwable t) {
            HookLog.e("ConfigBridge.ensureInitialized error", t);
        }
    }

    private static Handler getIoHandler() {
        Handler h = sIoHandler;
        if (h == null) {
            synchronized (ConfigBridge.class) {
                h = sIoHandler;
                if (h == null) {
                    HandlerThread ht = new HandlerThread("KeyPilot-ConfigIO");
                    ht.start();
                    h = new Handler(ht.getLooper());
                    sIoHandler = h;
                }
            }
        }
        return h;
    }

    private static void loadFromDiskOnce() {
        if (!sLoadedFromDisk.compareAndSet(false, true)) {
            return;
        }
        try {
            File f = new File(KeyPilotConfig.SYSTEM_CONFIG_PATH);
            if (!f.exists() || !f.isFile() || f.length() <= 0) {
                HookLog.i("cold start: " + KeyPilotConfig.SYSTEM_CONFIG_PATH
                        + " not found, using default passthrough config");
                return;
            }
            String json = readFileUtf8(f);
            KeyPilotConfig parsed = KeyPilotConfig.fromJsonString(json);
            sConfig = parsed;
            HookLog.i("cold start: loaded " + KeyPilotConfig.SYSTEM_CONFIG_PATH
                    + " (" + parsed.summaryStats() + ")");
        } catch (Throwable t) {
            HookLog.e("cold start: failed reading " + KeyPilotConfig.SYSTEM_CONFIG_PATH
                    + ", falling back to default", t);
            sConfig = KeyPilotConfig.createDefault();
        }
    }

    private static synchronized void tryRegisterReceiver(final Context ctx) {
        if (sReceiverRegistered.get()) {
            return;
        }
        try {
            IntentFilter filter = new IntentFilter(KeyPilotConfig.ACTION_CONFIG_UPDATE);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        if (intent == null || !KeyPilotConfig.ACTION_CONFIG_UPDATE.equals(intent.getAction())) {
                            return;
                        }
                        final String command = intent.getStringExtra(KeyPilotConfig.EXTRA_COMMAND);
                        if (command != null && !command.trim().isEmpty()) {
                            handleCommand(context, command.trim(), intent);
                            return;
                        }
                        final String json = intent.getStringExtra(KeyPilotConfig.EXTRA_CONFIG_JSON);
                        if (json == null || json.trim().isEmpty()) {
                            HookLog.i("CONFIG_UPDATE ignored: empty config_json");
                            return;
                        }
                        final KeyPilotConfig parsed = KeyPilotConfig.fromJsonString(json);
                        sConfig = parsed;
                        HookLog.report(context, "CONFIG_UPDATE applied: " + parsed.summaryStats());
                        HookLog.heartbeat(context, "synced (" + parsed.summaryStats() + ")");

                        // Persist asynchronously to /data/system/keypilot.json
                        getIoHandler().post(new Runnable() {
                            @Override
                            public void run() {
                                persistToDisk(parsed.toJsonString(2));
                            }
                        });
                    } catch (Throwable t) {
                        HookLog.e("CONFIG_UPDATE onReceive error", t);
                    }
                }
            };

            if (Build.VERSION.SDK_INT >= 33) {
                Method m = Context.class.getMethod("registerReceiver",
                        BroadcastReceiver.class, IntentFilter.class, int.class);
                m.invoke(ctx, receiver, filter, RECEIVER_EXPORTED_FLAG);
            } else {
                ctx.registerReceiver(receiver, filter);
            }
            sReceiverRegistered.set(true);
            HookLog.report(ctx, "ConfigBridge ready: registered " + KeyPilotConfig.ACTION_CONFIG_UPDATE);
            HookLog.heartbeat(ctx, "active (" + sConfig.summaryStats() + ")");
        } catch (Throwable t) {
            // Expected during very early boot before ActivityManagerService is ready; will retry later.
            HookLog.i("ConfigBridge receiver deferred (AMS not ready yet): " + t.getClass().getSimpleName());
        }
    }

    private static synchronized void persistToDisk(String json) {
        atomicWrite(new File(KeyPilotConfig.SYSTEM_CONFIG_PATH), json,
                KeyPilotConfig.SYSTEM_CONFIG_PATH);
    }

    private static synchronized void persistLabelsToDisk(String json) {
        atomicWrite(new File(KeyPilotConfig.SYSTEM_LABELS_PATH), json,
                KeyPilotConfig.SYSTEM_LABELS_PATH);
    }

    /** Write .tmp -> fsync -> rename (atomic) with direct-write fallback. */
    private static void atomicWrite(File target, String json, String logName) {
        File tmp = new File(target.getPath() + ".tmp");
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(tmp, false)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                try {
                    fos.getFD().sync();
                } catch (Throwable ignored) {
                }
            }
            if (!tmp.renameTo(target)) {
                // Fallback direct write if rename fails
                try (FileOutputStream fos = new FileOutputStream(target, false)) {
                    fos.write(json.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
                tmp.delete();
            }
            HookLog.i("persisted config to " + logName
                    + " (json " + json.length() + " bytes)");
        } catch (Throwable t) {
            HookLog.e("persistToDisk failed for " + logName, t);
            try {
                if (tmp.exists()) tmp.delete();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------- bridge commands (§3.5)

    private static void handleCommand(Context ctx, String command, Intent intent) {
        if (KeyPilotConfig.CMD_TRY_LAUNCH.equals(command)) {
            handleTryLaunch(ctx, intent);
        } else if (KeyPilotConfig.CMD_LABELS_UPDATE.equals(command)) {
            handleLabelsUpdate(ctx, intent);
        } else {
            HookLog.i("CONFIG_UPDATE unknown command ignored: " + command);
        }
    }

    /**
     * TRY_LAUNCH: start an activity with system_server privileges (works for
     * non-exported activities too) and report ok/error back to the app UI through
     * StatusProvider.call("try_launch_result"). Everything runs on the ConfigIO
     * thread inside try/catch(Throwable) — system_server stability red line.
     */
    private static void handleTryLaunch(final Context ctx, final Intent intent) {
        final String pkg = intent.getStringExtra(KeyPilotConfig.EXTRA_TRY_PACKAGE);
        final String cls = intent.getStringExtra(KeyPilotConfig.EXTRA_TRY_CLASS);
        final String token = intent.getStringExtra(KeyPilotConfig.EXTRA_TRY_TOKEN);
        getIoHandler().post(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String error = null;
                long binderToken = Binder.clearCallingIdentity();
                try {
                    if (pkg == null || pkg.trim().isEmpty()
                            || cls == null || cls.trim().isEmpty()) {
                        error = "参数缺失（package / class）";
                    } else {
                        String full = cls.trim();
                        if (full.startsWith(".")) full = pkg.trim() + full;
                        ComponentName cn = new ComponentName(pkg.trim(), full);
                        // Pre-validate: startActivity() only throws for permission problems,
                        // a missing component silently returns a start result code, so check
                        // the manifest first and never report "ok" for a dead component.
                        try {
                            ctx.getPackageManager().getActivityInfo(cn, 0);
                        } catch (Throwable t) {
                            error = "组件不存在: " + cn.flattenToString()
                                    + " (" + t.getClass().getSimpleName() + ")";
                        }
                        if (error == null) {
                            Intent launch = new Intent();
                            launch.setComponent(cn);
                            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            ActionExecutor.startActivityStrict(ctx, launch);
                            ok = true;
                        }
                    }
                } catch (Throwable t) {
                    error = describeError(t);
                } finally {
                    Binder.restoreCallingIdentity(binderToken);
                }
                HookLog.report(ctx, "TRY_LAUNCH " + (ok ? "ok" : "FAILED")
                        + " " + pkg + "/" + cls + (error != null ? " -> " + error : ""));
                sendTryLaunchResult(ctx, token, ok, error, pkg, cls);
            }
        });
    }

    private static String describeError(Throwable t) {
        if (t == null) return "未知错误";
        String msg = t.getMessage();
        String name = t.getClass().getSimpleName();
        if (msg == null || msg.trim().isEmpty()) return name;
        return name + ": " + msg.trim();
    }

    private static void sendTryLaunchResult(Context ctx, String token, boolean ok,
                                            String error, String pkg, String cls) {
        if (ctx == null) return;
        try {
            Bundle out = new Bundle();
            out.putBoolean("ok", ok);
            out.putString("error", error);
            out.putString("token", token);
            out.putString("component", pkg != null ? pkg + "/" + cls : cls);
            ctx.getContentResolver().call(
                    Uri.parse("content://" + StatusProvider.AUTHORITY),
                    KeyPilotConfig.METHOD_TRY_LAUNCH_RESULT, token, out);
        } catch (Throwable t) {
            HookLog.e("TRY_LAUNCH result delivery failed", t);
        }
    }

    /** LABELS_UPDATE: keep an in-memory copy + atomically mirror the label map to disk. */
    private static void handleLabelsUpdate(final Context ctx, final Intent intent) {
        final String labels = intent.getStringExtra(KeyPilotConfig.EXTRA_LABELS_JSON);
        if (labels == null || labels.trim().isEmpty()) {
            HookLog.i("LABELS_UPDATE ignored: empty labels_json");
            return;
        }
        int count = 0;
        try {
            JSONObject parsed = new JSONObject(labels);
            sLabels = parsed;
            sLabelsLoaded.set(true);
            java.util.Iterator<String> keys = parsed.keys();
            while (keys.hasNext()) {
                if (!keys.next().startsWith("_")) count++;
            }
        } catch (Throwable t) {
            HookLog.e("LABELS_UPDATE parse failed", t);
            return;
        }
        final int total = count;
        HookLog.report(ctx, "LABELS_UPDATE applied: " + total + " labels");
        getIoHandler().post(new Runnable() {
            @Override
            public void run() {
                persistLabelsToDisk(labels);
            }
        });
    }

    // ------------------------------------------------- activity label lookup

    private static volatile JSONObject sLabels;
    private static final AtomicBoolean sLabelsLoaded = new AtomicBoolean(false);

    /** Resolves a user/community label for a bound activity (used by toasts & logs). */
    public static String getActivityLabel(String pkg, String cls) {
        JSONObject o = sLabels;
        if (o == null && sLabelsLoaded.compareAndSet(false, true)) {
            try {
                File f = new File(KeyPilotConfig.SYSTEM_LABELS_PATH);
                if (f.exists() && f.length() > 0) {
                    o = new JSONObject(readFileUtf8(f));
                    sLabels = o;
                }
            } catch (Throwable t) {
                HookLog.i("activity labels not readable yet: " + t.getClass().getSimpleName());
            }
        }
        if (o == null || pkg == null || cls == null) return null;
        try {
            String full = cls.startsWith(".") ? pkg + cls : cls;
            JSONObject entry = o.optJSONObject(pkg + "/" + full);
            if (entry != null) {
                String label = entry.optString("label", null);
                if (label != null && !label.trim().isEmpty()) return label.trim();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String readFileUtf8(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static Context resolveSystemContext() {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Method currentAt = atCls.getDeclaredMethod("currentActivityThread");
            currentAt.setAccessible(true);
            Object at = currentAt.invoke(null);
            if (at != null) {
                Method getSysCtx = atCls.getDeclaredMethod("getSystemContext");
                getSysCtx.setAccessible(true);
                Object ctx = getSysCtx.invoke(at);
                if (ctx instanceof Context) {
                    return (Context) ctx;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
