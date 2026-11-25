package io.github.rtzhang.keypilot.hook;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.rtzhang.keypilot.config.ActionType;
import io.github.rtzhang.keypilot.config.GestureType;
import io.github.rtzhang.keypilot.config.KeyId;
import io.github.rtzhang.keypilot.config.KeyPilotConfig;

/**
 * Hooks PhoneWindowManager.interceptKeyBeforeQueueing inside system_server for:
 * - Power       (scancode 116 / KEYCODE_POWER=26)
 * - Volume Up   (scancode 115 / KEYCODE_VOLUME_UP=24)
 * - Volume Down (scancode 114 / KEYCODE_VOLUME_DOWN=25)
 * - Fallback for Essential Key (scancode 250) only if EssentialKeyHook direct hook failed.
 *
 * v2 P1 upgrades:
 * - Context-aware ProfileResolver (per-app overrides, blacklist 0ms bypass, screen/keyguard gates,
 *   passthroughDefault, external Bluetooth shutter guard).
 * - All v1 safety redlines (Power Long Press, Hardware Chord Shield, fail-safe fallback) preserved 100%.
 */
public final class StandardKeysHook {

    private static final String CLS_PHONE_WINDOW_MANAGER =
            "com.android.server.policy.PhoneWindowManager";

    private static volatile Object sPhoneWindowManager;
    private static volatile Handler sHelperHandler;

    // Bypass registry for KeyPilot's own replayed synthetic KeyEvents (key: keyCode + downTime)
    private static final ConcurrentHashMap<String, Long> sBypassTokens = new ConcurrentHashMap<>();

    // Hardware press tracking for real-time chord shield (synchronized on sChordLock)
    private static final Object sChordLock = new Object();
    private static final EnumSet<KeyId> sPhysicallyDown = EnumSet.noneOf(KeyId.class);
    private static final EnumSet<KeyId> sSwallowedOnDown = EnumSet.noneOf(KeyId.class);
    private static final EnumSet<KeyId> sChordActive = EnumSet.noneOf(KeyId.class);
    private static final EnumSet<KeyId> sChordInjectedDown = EnumSet.noneOf(KeyId.class);

    // Per-key captured ContextSnapshot at DOWN time for consistent gesture resolution
    private static final EnumMap<KeyId, ContextSnapshot> sPressSnapshots = new EnumMap<>(KeyId.class);
    private static final EnumSet<KeyId> sPttLongActive = EnumSet.noneOf(KeyId.class);

    // Native volume ramp runnables while holding unbound Volume+/Volume- long press
    private static final EnumMap<KeyId, Runnable> sVolumeRampTasks = new EnumMap<>(KeyId.class);
    private static final EnumMap<KeyId, GestureStateMachine> sFsms = new EnumMap<>(KeyId.class);

    static {
        GestureStateMachine.Callback cb = new GestureStateMachine.Callback() {
            @Override
            public void onShortPress(KeyId key, KeyEvent down1, KeyEvent up1) {
                handleGestureResult(key, GestureType.SHORT, down1, up1);
            }

            @Override
            public void onLongPress(KeyId key, KeyEvent down1) {
                handleGestureResult(key, GestureType.LONG, down1, null);
            }

            @Override
            public void onLongPressUp(KeyId key, KeyEvent up1) {
                stopVolumeRamp(key);
                boolean releasePtt;
                synchronized (sChordLock) {
                    releasePtt = sPttLongActive.remove(key);
                }
                if (releasePtt) {
                    ActionExecutor.releasePttMicMute();
                }
            }

            @Override
            public void onDoublePress(KeyId key, KeyEvent down1, KeyEvent down2) {
                handleGestureResult(key, GestureType.DOUBLE, down1, down2);
            }
        };
        sFsms.put(KeyId.POWER, new GestureStateMachine(KeyId.POWER, cb));
        sFsms.put(KeyId.VOLUME_UP, new GestureStateMachine(KeyId.VOLUME_UP, cb));
        sFsms.put(KeyId.VOLUME_DOWN, new GestureStateMachine(KeyId.VOLUME_DOWN, cb));
    }

    private StandardKeysHook() {
    }

    public static Object getPhoneWindowManager() {
        return sPhoneWindowManager;
    }

    private static Handler getHelperHandler() {
        Handler h = sHelperHandler;
        if (h == null) {
            synchronized (StandardKeysHook.class) {
                h = sHelperHandler;
                if (h == null) {
                    HandlerThread ht = new HandlerThread("KeyPilot-StdHelper");
                    ht.start();
                    h = new Handler(ht.getLooper());
                    sHelperHandler = h;
                }
            }
        }
        return h;
    }

    public static void install(ClassLoader cl) {
        try {
            Class<?> pwmCls = XposedHelpers.findClassIfExists(CLS_PHONE_WINDOW_MANAGER, cl);
            if (pwmCls == null) {
                HookLog.i("StandardKeysHook: PhoneWindowManager not found");
                return;
            }

            XC_MethodHook lifecycleHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        capturePwmAndInit(param.thisObject);
                    } catch (Throwable t) {
                        HookLog.e("PhoneWindowManager lifecycle hook error", t);
                    }
                }
            };
            XposedBridge.hookAllMethods(pwmCls, "init", lifecycleHook);
            XposedBridge.hookAllMethods(pwmCls, "systemReady", lifecycleHook);

            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    pwmCls, "interceptKeyBeforeQueueing", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                handleInterceptBeforeQueueing(param);
                            } catch (Throwable t) {
                                // Safety redline #3: fail-safe fallback to native Android policy
                                HookLog.e("interceptKeyBeforeQueueing fail-safe passthrough", t);
                            }
                        }
                    });
            HookLog.i("StandardKeysHook installed on PhoneWindowManager#interceptKeyBeforeQueueing ("
                    + (hooks != null ? hooks.size() : 0) + " overloads)");
        } catch (Throwable t) {
            HookLog.e("StandardKeysHook install failed", t);
        }
    }

    private static void capturePwmAndInit(Object pwm) {
        if (pwm == null) return;
        sPhoneWindowManager = pwm;
        Context ctx = extractContextFromPwm(pwm);
        ConfigBridge.ensureInitialized(ctx);
    }

    private static void handleInterceptBeforeQueueing(XC_MethodHook.MethodHookParam param) {
        if (sPhoneWindowManager == null && param.thisObject != null) {
            capturePwmAndInit(param.thisObject);
        } else {
            ConfigBridge.ensureInitialized(null);
        }

        KeyEvent event = extractKeyEvent(param.args);
        if (event == null) {
            return;
        }

        // Bypass KeyPilot's own synthetic replayed events (prevents infinite recursion)
        if (isAndConsumeBypass(event)) {
            return;
        }

        KeyId key = KeyId.fromKeyEvent(event);
        if (key == null) {
            return;
        }

        Context sysCtx = ConfigBridge.getSystemContext();
        ContextSnapshot snap = ForegroundTracker.snapshot(sysCtx, event);

        // Essential Key is normally handled in EssentialKeyHook; only handle here as fallback
        if (key == KeyId.ESSENTIAL) {
            if (!EssentialKeyHook.isDirectHookInstalled()
                    && EssentialKeyHook.handleFallbackFromPwm(event, snap)) {
                param.setResult(0);
            }
            return;
        }

        final int action = event.getAction();
        final boolean isDown = (action == KeyEvent.ACTION_DOWN);
        final boolean isUp = (action == KeyEvent.ACTION_UP);
        final boolean isRepeat = isDown && event.getRepeatCount() > 0;

        if (!isDown && !isUp) {
            return;
        }

        KeyPilotConfig cfg = ConfigBridge.getConfig();
        boolean shouldIntercept = ProfileResolver.shouldInterceptKey(cfg, key, snap);

        synchronized (sChordLock) {
            if (isDown && !isRepeat) {
                sPhysicallyDown.add(key);
                sPressSnapshots.put(key, snap);

                // Safety redline #2: detect hardware chords (POWER+VOL_DOWN, POWER+VOL_UP, VOL_UP+VOL_DOWN)
                KeyId partner = detectActiveChordPartnerLocked(key);
                if (partner != null) {
                    enterChordModeLocked(partner, key);
                    return;
                }
                if (sChordActive.contains(key)) {
                    return;
                }
            } else if (isRepeat) {
                if (sChordActive.contains(key)) {
                    return;
                }
                if (sSwallowedOnDown.contains(key)) {
                    param.setResult(0);
                    return;
                }
                return;
            } else if (isUp) {
                sPhysicallyDown.remove(key);
                if (sChordActive.remove(key)) {
                    boolean hadInjectedDown = sChordInjectedDown.remove(key);
                    sSwallowedOnDown.remove(key);
                    if (hadInjectedDown) {
                        return;
                    }
                    return;
                }
                if (sSwallowedOnDown.remove(key)) {
                    param.setResult(0);
                    GestureStateMachine fsm = sFsms.get(key);
                    if (fsm != null) {
                        fsm.onKeyEvent(event);
                    }
                    return;
                }
                return;
            }

            if (!shouldIntercept) {
                return;
            }

            sSwallowedOnDown.add(key);
        }

        param.setResult(0);
        GestureStateMachine fsm = sFsms.get(key);
        if (fsm != null) {
            fsm.onKeyEvent(event);
        }
    }

    private static KeyId detectActiveChordPartnerLocked(KeyId incoming) {
        if (incoming == KeyId.POWER) {
            if (sPhysicallyDown.contains(KeyId.VOLUME_DOWN)) return KeyId.VOLUME_DOWN;
            if (sPhysicallyDown.contains(KeyId.VOLUME_UP)) return KeyId.VOLUME_UP;
        } else if (incoming == KeyId.VOLUME_DOWN) {
            if (sPhysicallyDown.contains(KeyId.POWER)) return KeyId.POWER;
            if (sPhysicallyDown.contains(KeyId.VOLUME_UP)) return KeyId.VOLUME_UP;
        } else if (incoming == KeyId.VOLUME_UP) {
            if (sPhysicallyDown.contains(KeyId.POWER)) return KeyId.POWER;
            if (sPhysicallyDown.contains(KeyId.VOLUME_DOWN)) return KeyId.VOLUME_DOWN;
        }
        return null;
    }

    private static void enterChordModeLocked(final KeyId firstKey, final KeyId secondKey) {
        HookLog.i("CHORD SHIELD activated: " + firstKey.id + " + " + secondKey.id + " -> native passthrough");
        GestureStateMachine fsm1 = sFsms.get(firstKey);
        GestureStateMachine fsm2 = sFsms.get(secondKey);
        if (fsm1 != null) fsm1.cancel();
        if (fsm2 != null) fsm2.cancel();
        stopVolumeRamp(firstKey);
        stopVolumeRamp(secondKey);

        sChordActive.add(firstKey);
        sChordActive.add(secondKey);

        if (sSwallowedOnDown.remove(firstKey)) {
            sChordInjectedDown.add(firstKey);
            final Context ctx = ConfigBridge.getSystemContext();
            getHelperHandler().post(new Runnable() {
                @Override
                public void run() {
                    long now = SystemClock.uptimeMillis();
                    registerBypassDownTime(firstKey.keyCode, now);
                    ActionExecutor.injectKeyEvent(ctx, firstKey.keyCode, firstKey.scanCode,
                            KeyEvent.ACTION_DOWN, now, now);
                }
            });
        }
        sSwallowedOnDown.remove(secondKey);
    }

    // --------------------------------------------------- gesture outcome router

    private static void handleGestureResult(KeyId key, GestureType gesture,
                                            KeyEvent ev1, KeyEvent ev2) {
        KeyPilotConfig cfg = ConfigBridge.getConfig();
        ContextSnapshot snap;
        synchronized (sChordLock) {
            snap = sPressSnapshots.get(key);
        }
        if (snap == null) {
            snap = ForegroundTracker.snapshot(ConfigBridge.getSystemContext(), ev1);
        }

        ProfileResolver.Resolution res = ProfileResolver.resolve(cfg, key, gesture, snap);

        if (!res.action.isSystem()) {
            if (gesture == GestureType.LONG && res.action.type == ActionType.MIC_MUTE_PTT) {
                synchronized (sChordLock) {
                    sPttLongActive.add(key);
                }
            }
            ActionExecutor.execute(key, gesture, res.action, snap, res.source);
            if (!res.action.passthroughDefault) {
                return;
            }
            HookLog.i("StandardKeysHook: passthroughDefault=true -> also replaying native "
                    + key.id + "." + gesture.id);
        }

        // Unbound gesture (or passthroughDefault=true) -> execute native system behavior faithfully
        Context ctx = ConfigBridge.getSystemContext();
        if (ctx == null) {
            return;
        }

        if (gesture == GestureType.SHORT) {
            HookLog.i("StandardKeysHook: " + key.id + ".short unbound -> native bypass tap (" + res.source + ")");
            injectBypassTap(ctx, key.keyCode, key.scanCode);
        } else if (gesture == GestureType.LONG) {
            if (key == KeyId.POWER) {
                HookLog.i("StandardKeysHook: power.long unbound -> invoking native GlobalActions");
                invokeNativePowerLongPress(ev1 != null ? ev1.getEventTime() : SystemClock.uptimeMillis());
            } else if (key == KeyId.VOLUME_UP || key == KeyId.VOLUME_DOWN) {
                HookLog.i("StandardKeysHook: " + key.id + ".long unbound -> native volume ramp");
                startVolumeRamp(ctx, key);
            }
        } else if (gesture == GestureType.DOUBLE) {
            HookLog.i("StandardKeysHook: " + key.id + ".double unbound -> native double tap replay");
            injectBypassTap(ctx, key.keyCode, key.scanCode);
            getHelperHandler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    injectBypassTap(ctx, key.keyCode, key.scanCode);
                }
            }, 90L);
        }
    }

    private static void invokeNativePowerLongPress(final long eventTime) {
        final Object pwm = sPhoneWindowManager;
        if (pwm == null) {
            return;
        }
        getHelperHandler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Method m = findDeclaredMethodInHierarchy(pwm.getClass(), "powerLongPress", long.class);
                    if (m != null) {
                        m.setAccessible(true);
                        m.invoke(pwm, eventTime);
                        return;
                    }
                } catch (Throwable t) {
                    HookLog.i("powerLongPress(long) skipped: " + t);
                }
                for (String name : new String[]{"showGlobalActions", "showGlobalActionsInternal"}) {
                    try {
                        Method m = findDeclaredMethodInHierarchy(pwm.getClass(), name);
                        if (m != null) {
                            m.setAccessible(true);
                            m.invoke(pwm);
                            return;
                        }
                    } catch (Throwable t) {
                        HookLog.i(name + "() skipped: " + t);
                    }
                }
                HookLog.i("native powerLongPress fallback exhausted");
            }
        });
    }

    private static void startVolumeRamp(final Context ctx, final KeyId key) {
        stopVolumeRamp(key);
        final int dir = (key == KeyId.VOLUME_UP)
                ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
        final Runnable task = new Runnable() {
            @Override
            public void run() {
                boolean stillHeld;
                synchronized (sChordLock) {
                    stillHeld = sPhysicallyDown.contains(key);
                }
                if (!stillHeld) {
                    stopVolumeRamp(key);
                    return;
                }
                try {
                    AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        am.adjustSuggestedStreamVolume(
                                dir,
                                AudioManager.USE_DEFAULT_STREAM_TYPE,
                                AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_VIBRATE);
                    }
                } catch (Throwable ignored) {
                }
                getHelperHandler().postDelayed(this, 100L);
            }
        };
        synchronized (sVolumeRampTasks) {
            sVolumeRampTasks.put(key, task);
        }
        getHelperHandler().post(task);
    }

    private static void stopVolumeRamp(KeyId key) {
        Runnable task;
        synchronized (sVolumeRampTasks) {
            task = sVolumeRampTasks.remove(key);
        }
        if (task != null) {
            getHelperHandler().removeCallbacks(task);
        }
    }

    // --------------------------------------------------- bypass event helpers

    public static void registerBypassDownTime(int keyCode, long downTime) {
        long now = SystemClock.uptimeMillis();
        if (sBypassTokens.size() > 32) {
            Iterator<Map.Entry<String, Long>> it = sBypassTokens.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > 3000L) {
                    it.remove();
                }
            }
        }
        sBypassTokens.put(keyCode + ":" + downTime, now);
    }

    private static boolean isAndConsumeBypass(KeyEvent event) {
        if (sBypassTokens.isEmpty()) {
            return false;
        }
        int kc = event.getKeyCode();
        long dt = event.getDownTime();
        String exactKey = kc + ":" + dt;
        Long ts = sBypassTokens.get(exactKey);
        if (ts == null) {
            for (long delta = -2; delta <= 2; delta++) {
                if (delta == 0) continue;
                String altKey = kc + ":" + (dt + delta);
                ts = sBypassTokens.get(altKey);
                if (ts != null) {
                    exactKey = altKey;
                    break;
                }
            }
        }
        if (ts == null) {
            return false;
        }
        long age = SystemClock.uptimeMillis() - ts;
        if (age > 3000L) {
            sBypassTokens.remove(exactKey);
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            sBypassTokens.remove(exactKey);
        }
        return true;
    }

    public static void injectBypassTap(final Context ctx, final int keyCode, final int scanCode) {
        getHelperHandler().post(new Runnable() {
            @Override
            public void run() {
                ActionExecutor.injectKeyTap(ctx, keyCode, scanCode, true);
            }
        });
    }

    public static void injectBypassChord(final Context ctx,
                                         final int keyCode1, final int scanCode1,
                                         final int keyCode2, final int scanCode2,
                                         final long holdMs) {
        getHelperHandler().post(new Runnable() {
            @Override
            public void run() {
                long down1 = SystemClock.uptimeMillis();
                long down2 = down1 + 4L;
                registerBypassDownTime(keyCode1, down1);
                registerBypassDownTime(keyCode2, down2);
                ActionExecutor.injectKeyEvent(ctx, keyCode1, scanCode1, KeyEvent.ACTION_DOWN, down1, down1);
                ActionExecutor.injectKeyEvent(ctx, keyCode2, scanCode2, KeyEvent.ACTION_DOWN, down2, down2);
                getHelperHandler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        long up = SystemClock.uptimeMillis();
                        ActionExecutor.injectKeyEvent(ctx, keyCode2, scanCode2, KeyEvent.ACTION_UP, down2, up);
                        ActionExecutor.injectKeyEvent(ctx, keyCode1, scanCode1, KeyEvent.ACTION_UP, down1, up + 4L);
                    }
                }, holdMs);
            }
        });
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

    private static Context extractContextFromPwm(Object pwm) {
        if (pwm == null) return null;
        try {
            Object c = XposedHelpers.getObjectField(pwm, "mContext");
            if (c instanceof Context) {
                return (Context) c;
            }
        } catch (Throwable ignored) {
        }
        try {
            for (Class<?> cls = pwm.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
                for (Field f : cls.getDeclaredFields()) {
                    if (Context.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object v = f.get(pwm);
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

    private static Method findDeclaredMethodInHierarchy(Class<?> cls, String name, Class<?>... params) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }
}
