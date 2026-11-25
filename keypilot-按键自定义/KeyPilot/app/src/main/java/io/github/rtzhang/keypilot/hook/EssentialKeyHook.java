package io.github.rtzhang.keypilot.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.rtzhang.keypilot.config.ActionSpec;
import io.github.rtzhang.keypilot.config.ActionType;
import io.github.rtzhang.keypilot.config.GestureType;
import io.github.rtzhang.keypilot.config.KeyId;
import io.github.rtzhang.keypilot.config.KeyPilotConfig;

/**
 * Essential Key hook (scanCode=250, event0) inside system_server.
 *
 * Primary target (verified by P4 bytecode inspection):
 *   Class:  com.android.server.wm.NtEssentialKeyImpl (in /system/framework/nt-services.jar)
 *   Method: public void onRecord(android.view.KeyEvent)  [un-obfuscated INtEssentialKey contract]
 *
 * v2 P1 upgrades:
 * - Resolves actions via ProfileResolver(ForegroundTracker.snapshot) with fallback to v1 global keys.
 * - Supports blacklist bypass, screen-off/lockscreen gates, passthroughDefault, B3 PTT release,
 *   and Nothing OS 5.1 officialRemapPolicy (takeover / yield_single_click / yield_all).
 */
public final class EssentialKeyHook {

    private static final String CLS_NT_ESSENTIAL_KEY_IMPL = "com.android.server.wm.NtEssentialKeyImpl";
    private static final String CLS_NT_EXT_FACTORY = "com.nothing.server.ext.NtExtServiceFactory";
    private static final String CLS_INT_ESSENTIAL_KEY = "com.nothing.server.ext.INtEssentialKey";
    private static final String CLS_INT_ESSENTIAL_VOICE = "com.nothing.server.ext.INtEssentialVoice";

    private static volatile boolean sDirectHookInstalled = false;
    private static volatile Object sLastTargetObj;
    private static volatile Member sLastRecordMethod;
    private static volatile boolean sNativeLongActive = false;
    private static volatile boolean sPttLongActive = false;
    private static volatile Handler sReplayHandler;
    private static volatile ContextSnapshot sActiveSnapshot = ContextSnapshot.EMPTY;

    private static final ThreadLocal<Boolean> sBypassing = new ThreadLocal<>();

    private static final GestureStateMachine sFsm = new GestureStateMachine(
            KeyId.ESSENTIAL,
            new GestureStateMachine.Callback() {
                @Override
                public void onShortPress(KeyId key, KeyEvent down1, KeyEvent up1) {
                    KeyPilotConfig cfg = ConfigBridge.getConfig();
                    ContextSnapshot snap = sActiveSnapshot;
                    ProfileResolver.Resolution res =
                            ProfileResolver.resolve(cfg, KeyId.ESSENTIAL, GestureType.SHORT, snap);
                    if (!res.action.isSystem()) {
                        ActionExecutor.execute(KeyId.ESSENTIAL, GestureType.SHORT,
                                res.action, snap, res.source);
                        if (res.action.passthroughDefault) {
                            HookLog.i("EssentialKeyHook: passthroughDefault=true -> replaying native SHORT");
                            replayNativeShortPress();
                        }
                    } else {
                        HookLog.i("EssentialKeyHook: SHORT -> native (" + res.source + ")");
                        replayNativeShortPress();
                    }
                }

                @Override
                public void onLongPress(KeyId key, KeyEvent down1) {
                    KeyPilotConfig cfg = ConfigBridge.getConfig();
                    ContextSnapshot snap = sActiveSnapshot;
                    ProfileResolver.Resolution res =
                            ProfileResolver.resolve(cfg, KeyId.ESSENTIAL, GestureType.LONG, snap);
                    if (!res.action.isSystem()) {
                        if (res.action.type == ActionType.MIC_MUTE_PTT) {
                            sPttLongActive = true;
                        }
                        ActionExecutor.execute(KeyId.ESSENTIAL, GestureType.LONG,
                                res.action, snap, res.source);
                        if (res.action.passthroughDefault) {
                            sNativeLongActive = true;
                            replayNativeSingleEvent(KeyEvent.ACTION_DOWN);
                        }
                    } else {
                        HookLog.i("EssentialKeyHook: LONG -> native (" + res.source + ")");
                        sNativeLongActive = true;
                        replayNativeSingleEvent(KeyEvent.ACTION_DOWN);
                    }
                }

                @Override
                public void onLongPressUp(KeyId key, KeyEvent up1) {
                    if (sPttLongActive) {
                        sPttLongActive = false;
                        ActionExecutor.releasePttMicMute();
                    }
                    if (sNativeLongActive) {
                        sNativeLongActive = false;
                        replayNativeSingleEvent(KeyEvent.ACTION_UP);
                    }
                }

                @Override
                public void onDoublePress(KeyId key, KeyEvent down1, KeyEvent down2) {
                    KeyPilotConfig cfg = ConfigBridge.getConfig();
                    ContextSnapshot snap = sActiveSnapshot;
                    ProfileResolver.Resolution res =
                            ProfileResolver.resolve(cfg, KeyId.ESSENTIAL, GestureType.DOUBLE, snap);
                    if (!res.action.isSystem()) {
                        ActionExecutor.execute(KeyId.ESSENTIAL, GestureType.DOUBLE,
                                res.action, snap, res.source);
                        if (res.action.passthroughDefault) {
                            launchNativeEssentialSpace();
                        }
                    } else {
                        HookLog.i("EssentialKeyHook: DOUBLE -> native (" + res.source + ")");
                        launchNativeEssentialSpace();
                    }
                }
            });

    private EssentialKeyHook() {
    }

    public static boolean isDirectHookInstalled() {
        return sDirectHookInstalled;
    }

    public static void install(ClassLoader cl) {
        try {
            Class<?> implCls = XposedHelpers.findClassIfExists(CLS_NT_ESSENTIAL_KEY_IMPL, cl);
            if (implCls != null) {
                if (hookOnRecordAllOverloads(implCls)) {
                    sDirectHookInstalled = true;
                    HookLog.i("EssentialKeyHook installed on " + implCls.getName() + "#onRecord");
                    return;
                }
            } else {
                HookLog.i("EssentialKeyHook: " + CLS_NT_ESSENTIAL_KEY_IMPL
                        + " not directly found, installing NtExtServiceFactory runtime probe");
            }
        } catch (Throwable t) {
            HookLog.e("EssentialKeyHook direct install error", t);
        }

        installFactoryProbe(cl);
    }

    private static boolean hookOnRecordAllOverloads(Class<?> cls) {
        try {
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    cls, "onRecord", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                handleOnRecordBefore(param);
                            } catch (Throwable t) {
                                HookLog.e("EssentialKeyHook beforeHookedMethod fail-safe passthrough", t);
                            }
                        }
                    });
            return hooks != null && !hooks.isEmpty();
        } catch (Throwable t) {
            HookLog.e("hookOnRecordAllOverloads failed on " + cls.getName(), t);
            return false;
        }
    }

    private static void installFactoryProbe(final ClassLoader cl) {
        try {
            Class<?> factoryCls = XposedHelpers.findClassIfExists(CLS_NT_EXT_FACTORY, cl);
            if (factoryCls == null) {
                HookLog.i("EssentialKeyHook: NtExtServiceFactory not found; StandardKeysHook will handle scanCode=250");
                return;
            }
            final Class<?> ifaceCls = XposedHelpers.findClassIfExists(CLS_INT_ESSENTIAL_KEY, cl);
            XposedBridge.hookAllMethods(factoryCls, "getOrCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (sDirectHookInstalled) {
                            return;
                        }
                        Object res = param.getResult();
                        if (res == null) {
                            return;
                        }
                        boolean match = (ifaceCls != null && ifaceCls.isInstance(res))
                                || res.getClass().getName().toLowerCase().contains("essentialkey");
                        if (match && hookOnRecordAllOverloads(res.getClass())) {
                            sDirectHookInstalled = true;
                            HookLog.i("EssentialKeyHook dynamically hooked via factory probe: "
                                    + res.getClass().getName());
                        }
                    } catch (Throwable t) {
                        HookLog.e("EssentialKeyHook factory probe error", t);
                    }
                }
            });
            HookLog.i("EssentialKeyHook factory probe active on " + CLS_NT_EXT_FACTORY);
        } catch (Throwable t) {
            HookLog.e("installFactoryProbe error", t);
        }
    }

    private static void handleOnRecordBefore(XC_MethodHook.MethodHookParam param) {
        if (Boolean.TRUE.equals(sBypassing.get())) {
            return;
        }
        KeyEvent event = extractKeyEvent(param.args);
        if (event == null) {
            return;
        }
        sLastTargetObj = param.thisObject;
        sLastRecordMethod = param.method;
        Context probedCtx = probeContextField(param.thisObject);
        ConfigBridge.ensureInitialized(probedCtx);
        Context useCtx = probedCtx != null ? probedCtx : ConfigBridge.getSystemContext();
        OfficialRemapDetector.probeOnce(useCtx);

        KeyPilotConfig cfg = ConfigBridge.getConfig();
        ContextSnapshot snap = ForegroundTracker.snapshot(useCtx, event);

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            sActiveSnapshot = snap;
        }

        if (!ProfileResolver.shouldInterceptKey(cfg, KeyId.ESSENTIAL, sActiveSnapshot)) {
            // Blacklisted, yield_all, disabled, or all 3 gestures native -> 0ms passthrough
            return;
        }
        if (cfg.preserveImeVoice && isEssentialVoiceImeVisible(param.thisObject)) {
            HookLog.i("EssentialKeyHook: IME visible & preserveImeVoice=true -> passthrough");
            return;
        }

        param.setResult(null);
        sFsm.onKeyEvent(event);
    }

    /** Fallback entry called by StandardKeysHook ONLY if direct NtEssentialKeyImpl hook failed. */
    public static boolean handleFallbackFromPwm(KeyEvent event, ContextSnapshot snap) {
        if (sDirectHookInstalled || event == null) {
            return false;
        }
        KeyPilotConfig cfg = ConfigBridge.getConfig();
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && snap != null) {
            sActiveSnapshot = snap;
        }
        if (!ProfileResolver.shouldInterceptKey(cfg, KeyId.ESSENTIAL, sActiveSnapshot)) {
            return false;
        }
        sFsm.onKeyEvent(event);
        return true;
    }

    // --------------------------------------------------- mixed-mode native replay

    private static Handler getReplayHandler() {
        Handler h = sReplayHandler;
        if (h == null) {
            synchronized (EssentialKeyHook.class) {
                h = sReplayHandler;
                if (h == null) {
                    HandlerThread ht = new HandlerThread("KeyPilot-EKReplay");
                    ht.start();
                    h = new Handler(ht.getLooper());
                    sReplayHandler = h;
                }
            }
        }
        return h;
    }

    private static void replayNativeShortPress() {
        HookLog.i("EssentialKeyHook: SHORT unbound -> replaying native DOWN+UP to NtEssentialKeyImpl");
        getReplayHandler().post(new Runnable() {
            @Override
            public void run() {
                long downTime = SystemClock.uptimeMillis();
                invokeOriginalOnRecord(buildEssentialEvent(KeyEvent.ACTION_DOWN, downTime, downTime));
                getReplayHandler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        long upTime = SystemClock.uptimeMillis();
                        invokeOriginalOnRecord(buildEssentialEvent(KeyEvent.ACTION_UP, downTime, upTime));
                    }
                }, 65L);
            }
        });
    }

    private static void replayNativeSingleEvent(final int action) {
        HookLog.i("EssentialKeyHook: LONG unbound -> replaying native action=" + action);
        getReplayHandler().post(new Runnable() {
            @Override
            public void run() {
                long now = SystemClock.uptimeMillis();
                invokeOriginalOnRecord(buildEssentialEvent(action, now, now));
            }
        });
    }

    private static void invokeOriginalOnRecord(KeyEvent ev) {
        Member method = sLastRecordMethod;
        Object target = sLastTargetObj;
        if (method == null || target == null) {
            return;
        }
        sBypassing.set(Boolean.TRUE);
        try {
            XposedBridge.invokeOriginalMethod(method, target, new Object[]{ev});
        } catch (Throwable t) {
            HookLog.e("EssentialKeyHook native replay failed", t);
        } finally {
            sBypassing.set(Boolean.FALSE);
        }
    }

    private static void launchNativeEssentialSpace() {
        Context ctx = ConfigBridge.getSystemContext();
        if (ctx == null) {
            return;
        }
        HookLog.i("EssentialKeyHook: DOUBLE unbound -> launching native Essential Space");
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    "com.nothing.ntessentialspace",
                    "com.nothing.ntessentialspace.MainActivity"));
            intent.putExtra("FROM_ESSENTIAL_KEY", true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActionExecutor.startActivityAsCurrentUser(ctx, intent);
        } catch (Throwable t) {
            HookLog.e("launchNativeEssentialSpace failed", t);
        }
    }

    private static KeyEvent buildEssentialEvent(int action, long downTime, long eventTime) {
        return new KeyEvent(
                downTime,
                eventTime,
                action,
                KeyEvent.KEYCODE_UNKNOWN,
                0,
                0,
                2,
                KeyId.ESSENTIAL.scanCode,
                0,
                InputDevice.SOURCE_KEYBOARD);
    }

    // -------------------------------------------------------------- reflection

    private static KeyEvent extractKeyEvent(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof KeyEvent) {
                return (KeyEvent) a;
            }
        }
        return null;
    }

    private static Context probeContextField(Object obj) {
        if (obj == null) return null;
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Context.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v instanceof Context) {
                            return (Context) v;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isEssentialVoiceImeVisible(Object target) {
        if (target == null) return false;
        try {
            ClassLoader cl = target.getClass().getClassLoader();
            Class<?> factoryCls = XposedHelpers.findClassIfExists(CLS_NT_EXT_FACTORY, cl);
            Class<?> voiceIface = XposedHelpers.findClassIfExists(CLS_INT_ESSENTIAL_VOICE, cl);
            if (factoryCls != null && voiceIface != null) {
                for (Method m : factoryCls.getDeclaredMethods()) {
                    if ("getOrCreate".equals(m.getName()) && m.getParameterTypes().length == 1) {
                        for (Field f : factoryCls.getDeclaredFields()) {
                            if (f.getName().contains("ESSENTIAL_VOICE")) {
                                f.setAccessible(true);
                                Object voiceSvc = m.invoke(null, f.get(null));
                                if (voiceSvc != null) {
                                    Method isVis = voiceSvc.getClass().getMethod("isImeVisible");
                                    Object r = isVis.invoke(voiceSvc);
                                    return Boolean.TRUE.equals(r);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
