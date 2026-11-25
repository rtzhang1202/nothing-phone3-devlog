package io.github.rtzhang.keypilot.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.rtzhang.keypilot.config.KeyPilotConfig;

/**
 * Dual-path configuration bridge inside system_server (uid=1000):
 * 1. Cold start / reboot: loads /data/system/keypilot.json into memory.
 * 2. Hot reload: dynamically registers BroadcastReceiver for io.github.rtzhang.keypilot.CONFIG_UPDATE,
 *    updates in-memory config immediately, and atomically persists to /data/system/keypilot.json.
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
        File target = new File(KeyPilotConfig.SYSTEM_CONFIG_PATH);
        File tmp = new File(KeyPilotConfig.SYSTEM_CONFIG_PATH + ".tmp");
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
            HookLog.i("persisted config to " + KeyPilotConfig.SYSTEM_CONFIG_PATH
                    + " (" + json.length() + " bytes)");
        } catch (Throwable t) {
            HookLog.e("persistToDisk failed for " + KeyPilotConfig.SYSTEM_CONFIG_PATH, t);
            try {
                if (tmp.exists()) tmp.delete();
            } catch (Throwable ignored) {
            }
        }
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
