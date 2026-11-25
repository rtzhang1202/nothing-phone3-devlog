package io.github.rtzhang.keypilot.hook;

import android.content.Context;
import android.net.Uri;
import android.os.Binder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XposedBridge;
import io.github.rtzhang.keypilot.StatusProvider;

/**
 * Unified Xposed logger with fixed tag "KeyPilot" and asynchronous best-effort
 * reporting to the module app's StatusProvider. Never throws, never blocks callers.
 */
public final class HookLog {

    public static final String TAG = "KeyPilot";
    private static final Uri STATUS_URI = Uri.parse("content://" + StatusProvider.AUTHORITY);
    private static final ExecutorService REPORTER = Executors.newSingleThreadExecutor();

    private HookLog() {
    }

    public static void i(String msg) {
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
        }
    }

    public static void e(String msg, Throwable t) {
        try {
            XposedBridge.log(TAG + ": [ERR] " + msg + (t != null ? " -> " + t : ""));
        } catch (Throwable ignored) {
        }
    }

    public static void report(final Context ctx, final String msg) {
        i(msg);
        if (ctx == null) {
            return;
        }
        try {
            REPORTER.execute(new Runnable() {
                @Override
                public void run() {
                    long token = Binder.clearCallingIdentity();
                    try {
                        ctx.getContentResolver().call(STATUS_URI, "log", msg, null);
                    } catch (Throwable ignored) {
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public static void heartbeat(final Context ctx, final String summary) {
        if (ctx == null) {
            return;
        }
        try {
            REPORTER.execute(new Runnable() {
                @Override
                public void run() {
                    long token = Binder.clearCallingIdentity();
                    try {
                        ctx.getContentResolver().call(STATUS_URI, "heartbeat", summary, null);
                    } catch (Throwable ignored) {
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
