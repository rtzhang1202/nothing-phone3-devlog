package io.github.rtzhang.nothingfullaod;

import android.content.Context;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class FrameworkBrightnessHook {
    private static final String TAG = FullAodConfig.TAG;
    private static final int POLICY_DOZE = 1;

    private static volatile long sLastLogMs = 0L;
    private static volatile float sLastLoggedScaled = -1f;
    private static volatile Context sCtx;
    private static volatile boolean sObserverSet;

    private FrameworkBrightnessHook() {}

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        hookAutomaticBrightnessStrategy(cl);
        hookStrategySelectorLogging(cl);
        hookDozePowerMode(cl);
        hookDozeScreenRequest(cl);
    }

    /**
     * Plan C (flicker root fix): while our full-AOD is active, translate the panel
     * power mode DOZE/DOZE_SUSPEND -> ON so the LTPS panel keeps its normal
     * 60-120Hz drive (flicker-free) at doze brightness. Logical doze state is
     * untouched. On timed-out (stock AOD), the panel is handed back to real DOZE
     * so the stock power saving still works.
     */
    private static void hookDozePowerMode(ClassLoader cl) {
        try {
            final Class<?> sc = XposedHelpers.findClass("android.view.SurfaceControl", cl);
            java.util.Set<XC_MethodHook.Unhook> matched =
                    XposedBridge.hookAllMethods(sc, "setDisplayPowerMode", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 2) return;
                        if (!(param.args[1] instanceof Integer)) return;
                        int mode = (Integer) param.args[1];
                        if (mode != 1 && mode != 3) return; // only DOZE / DOZE_SUSPEND
                        Context ctx = sCtx != null ? sCtx : systemContext();
                        if (ctx == null) return;
                        ensureTimeoutObserver(ctx);
                        FullAodConfig.refreshIfNeeded(ctx);
                        if (!FullAodConfig.isEnabled() || FullAodConfig.isTimedOut()) return;
                        param.args[1] = 2; // -> ON: normal drive, only brightness stays low
                        Log.i(TAG, "Doze panel power mode " + mode + " -> ON (normal drive, no flicker)");
                    } catch (Throwable t) {
                        Log.e(TAG, "setDisplayPowerMode hook error", t);
                    }
                }
            });
            Log.i(TAG, "Doze power-mode translation hooked (plan C), matchedMethods="
                    + matched.size());
        } catch (Throwable t) {
            Log.e(TAG, "hookDozePowerMode failed", t);
        }
    }

    /**
     * Plan C core: the doze power request flips dozeScreenState to DOZE/DOZE_SUSPEND,
     * which puts the LTPS panel into its low-frequency self-refresh scan (the flicker
     * source). Translate the requested state to ON while our full-AOD is active: the
     * panel then keeps its normal 60-120Hz drive and only the doze brightness applies
     * (dozeScreenBrightness is left untouched) = "normal state, dimmed". When the timed
     * fallback fires (isTimedOut), translation stops and real DOZE resumes on the next
     * request, so the stock-AOD power saving still works.
     */
    private static void hookDozeScreenRequest(ClassLoader cl) {
        try {
            Class<?> dpc = XposedHelpers.findClass(
                    "com.android.server.display.DisplayPowerController", cl);
            XposedBridge.hookAllMethods(dpc, "requestPowerState", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 1 || param.args[0] == null) return;
                        Object req = param.args[0];
                        int state;
                        try {
                            state = XposedHelpers.getIntField(req, "dozeScreenState");
                        } catch (Throwable t) {
                            return; // not a DisplayPowerRequest
                        }
                        if (state != 3 && state != 4) return; // only DOZE / DOZE_SUSPEND
                        Context ctx = sCtx != null ? sCtx : systemContext();
                        if (ctx == null) return;
                        FullAodConfig.refreshIfNeeded(ctx);
                        if (!FullAodConfig.isEnabled() || FullAodConfig.isTimedOut()) return;
                        XposedHelpers.setIntField(req, "dozeScreenState", 2); // -> STATE_ON
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now - sLastLogMs > 3000L) {
                            sLastLogMs = now;
                            Log.i(TAG, "Doze screen request: state=" + state
                                    + " -> ON (panel keeps normal drive; brightness untouched)");
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "requestPowerState hook error", t);
                    }
                }
            });
            Log.i(TAG, "Doze screen-state translation hooked (plan C core)");
        } catch (Throwable t) {
            Log.e(TAG, "hookDozeScreenRequest failed", t);
        }
    }

    /** Diagnostic: list power/state methods of a class and log live calls. */
    private static void probe(ClassLoader cl, String className) {
        try {
            Class<?> c = XposedHelpers.findClass(className, cl);
            StringBuilder sb = new StringBuilder("PROBE " + className + ":");
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                String n = m.getName().toLowerCase();
                if (n.contains("state") || n.contains("power")) {
                    sb.append(" ").append(m.getName()).append(m.getParameterTypes().length);
                }
            }
            Log.i(TAG, sb.toString());
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                String n = m.getName().toLowerCase();
                if ((n.contains("setdisplaystate") || n.contains("requestdisplaystate")
                        || n.contains("setpowermode") || n.contains("setdisplaypower")
                        || n.contains("requestpowerstate"))) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) {
                                Log.i(TAG, "PROBE CALL " + className + "#" + p.method.getName()
                                        + " args=" + java.util.Arrays.toString(p.args));
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "PROBE " + className + " not found: " + t.getMessage());
        }
    }

    private static Context systemContext() {
        try {
            Object at = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null), "currentActivityThread");
            if (at != null) {
                Object ctx = XposedHelpers.callMethod(at, "getSystemContext");
                if (ctx instanceof Context) return (Context) ctx;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** When fullaod_timedout flips to 1 mid-doze, hand the panel back to real DOZE. */
    private static void ensureTimeoutObserver(final Context ctx) {
        if (sObserverSet || ctx == null) return;
        sObserverSet = true;
        try {
            ctx.getContentResolver().registerContentObserver(
                    android.provider.Settings.Global.getUriFor(FullAodConfig.KEY_TIMED_OUT),
                    false,
                    new android.database.ContentObserver(
                            new android.os.Handler(android.os.Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            try {
                                if (!FullAodConfig.isTimedOut()) return;
                                Class<?> sc = XposedHelpers.findClass("android.view.SurfaceControl", null);
                                Object token = XposedHelpers.callStaticMethod(sc, "getInternalDisplayToken");
                                if (token != null) {
                                    XposedHelpers.callStaticMethod(sc, "setDisplayPowerMode", token, 1);
                                    Log.i(TAG, "Timed out -> panel handed back to real DOZE");
                                } else {
                                    Log.w(TAG, "Timed out but no internal display token; panel stays ON until next transition");
                                }
                            } catch (Throwable t) {
                                Log.e(TAG, "timeout DOZE handoff error", t);
                            }
                        }
                    });
            Log.i(TAG, "Timed-out DOZE handoff observer registered");
        } catch (Throwable t) {
            Log.e(TAG, "ensureTimeoutObserver failed", t);
        }
    }

    private static void hookAutomaticBrightnessStrategy(ClassLoader cl) {
        try {
            Class<?> absCls = XposedHelpers.findClass(
                    "com.android.server.display.brightness.strategy.AutomaticBrightnessStrategy", cl);
            Class<?> builderCls = XposedHelpers.findClass(
                    "com.android.server.display.DisplayBrightnessState$Builder", cl);

            XposedBridge.hookAllMethods(absCls, "updateBrightness", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 1 || param.args[0] == null) return;
                        Object execReq = param.args[0];
                        Object dpr = XposedHelpers.callMethod(execReq, "getDisplayPowerRequest");
                        if (dpr == null) return;
                        int policy = XposedHelpers.getIntField(dpr, "policy");
                        if (policy != POLICY_DOZE) return;

                        try {
                            Context ctx = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                            sCtx = ctx;
                            ensureTimeoutObserver(ctx);
                            FullAodConfig.refreshIfNeeded(ctx);
                        } catch (Throwable ignored) {
                        }
                        if (!FullAodConfig.isEnabled() || FullAodConfig.isTimedOut()) return;

                        Object state = param.getResult();
                        if (state == null) return;

                        float orig = XposedHelpers.getFloatField(state, "mBrightness");
                        if (Float.isNaN(orig) || orig <= 0f) return;

                        float scale = FullAodConfig.getBrtScale();
                        float minBrt = FullAodConfig.getBrtMin();
                        float maxBrt = FullAodConfig.getBrtMax();
                        float scaled = FullAodConfig.clamp(orig * scale, minBrt, maxBrt);

                        Object builder = XposedHelpers.callStaticMethod(builderCls, "from", state);
                        XposedHelpers.callMethod(builder, "setBrightness", scaled);
                        XposedHelpers.callMethod(builder, "setShouldUpdateScreenBrightnessSetting", false);
                        Object newState = XposedHelpers.callMethod(builder, "build");
                        param.setResult(newState);

                        long now = android.os.SystemClock.uptimeMillis();
                        if (Math.abs(scaled - sLastLoggedScaled) > 0.005f || now - sLastLogMs > 5000L) {
                            sLastLogMs = now;
                            sLastLoggedScaled = scaled;
                            Log.i(TAG, "FW DOZE ABC scaled: orig=" + orig
                                    + " -> scaled=" + scaled
                                    + " (scale=" + scale + ", range=[" + minBrt + "," + maxBrt + "])");
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "AutomaticBrightnessStrategy.updateBrightness hook error", t);
                    }
                }
            });
            Log.i(TAG, "Hooked AutomaticBrightnessStrategy.updateBrightness");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook AutomaticBrightnessStrategy", t);
        }
    }

    private static void hookStrategySelectorLogging(ClassLoader cl) {
        try {
            Class<?> selCls = XposedHelpers.findClass(
                    "com.android.server.display.brightness.DisplayBrightnessStrategySelector", cl);
            XposedBridge.hookAllMethods(selCls, "shouldUseDozeBrightnessStrategy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 1 || param.args[0] == null) return;
                        Object dpr = XposedHelpers.callMethod(param.args[0], "getDisplayPowerRequest");
                        if (dpr != null && XposedHelpers.getIntField(dpr, "policy") == POLICY_DOZE) {
                            boolean allowCfg = XposedHelpers.getBooleanField(param.thisObject, "mAllowAutoBrightnessWhileDozingConfig");
                            boolean result = Boolean.TRUE.equals(param.getResult());
                            long now = android.os.SystemClock.uptimeMillis();
                            if (now - sLastLogMs > 4000L) {
                                Log.i(TAG, "FW StrategySelector DOZE: allowABCConfig=" + allowCfg
                                        + ", useDozeStrategy=" + result);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            Log.i(TAG, "Hooked DisplayBrightnessStrategySelector.shouldUseDozeBrightnessStrategy");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook DisplayBrightnessStrategySelector", t);
        }
    }
}
