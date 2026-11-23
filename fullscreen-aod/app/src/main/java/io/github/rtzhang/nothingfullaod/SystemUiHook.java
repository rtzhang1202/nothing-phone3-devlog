package io.github.rtzhang.nothingfullaod;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class SystemUiHook {
    private static final String TAG = FullAodConfig.TAG;

    private static volatile Context sAppContext;
    private static volatile String sDozeState = "UNINITIALIZED";
    private static volatile boolean sFullAodActive = false;
    private static volatile int sLastLightSceneValue = 30;
    private static volatile WeakReference<View> sLightRevealScrimRef = new WeakReference<>(null);
    private static volatile WeakReference<Object> sAodDimRepoRef = new WeakReference<>(null);
    private static volatile WeakReference<View> sNsslRef = new WeakReference<>(null);
    private static volatile WeakReference<Object> sKgVisControllerRef = new WeakReference<>(null);
    private static volatile WeakReference<Object> sClockCoverRef = new WeakReference<>(null);
    private static volatile long sLastSnapLogMs = 0L;
    private static volatile boolean sClockReleased = false;
    private static final java.util.List<WeakReference<Object>> sClockChain =
            java.util.Collections.synchronizedList(new java.util.ArrayList<WeakReference<Object>>());
    // Entrance modes (Xiaomi-style): 0=NATURAL (from lockscreen), 1=BLACK_REVEAL (busy SoC),
    // 2=WALLPAPER_FIRST (spare capacity). See H12.
    private static volatile boolean sFromLockscreen = false;
    private static volatile int sEntranceMode = 0;
    private static volatile boolean sFading = false;
    // Dark-field cover: set at the transition observer (before any window draws). While held,
    // every dim request is coerced to 1.0 so the composition draws dark from its very first
    // frame — the display-mode-switch blank then merges into the natural "screen off" look.
    private static volatile boolean sDarkFieldHold = false;
    // Timed full-AOD (user option): after the configured duration the presentation falls
    // back to the stock AOD (small clock, stock brightness) to save power.
    private static volatile boolean sTimedOut = false;
    private static void restoreWidgetsAtWake() {
        // Restore the global wallpaper dim first: the timed-out blackout pinned
        // WallpaperManager dim to 1.0 (fully black) and nothing reset it on wake,
        // which made BOTH the desktop and lockscreen wallpapers disappear.
        try {
            Object repo = sAodDimRepoRef.get();
            if (repo != null) {
                XposedHelpers.callMethod(repo, "setWallpaperDimAmount", 0f);
            }
            if (sAppContext != null) {
                Object wm = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.WallpaperManager", null),
                        "getInstance", sAppContext);
                if (wm != null) {
                    XposedHelpers.callMethod(wm, "setWallpaperDimAmount", 0.0f);
                }
            }
            android.util.Log.i(FullAodConfig.TAG,
                    "restoreWidgetsAtWake: wallpaper dim restored to 0");
        } catch (Throwable t) {
            android.util.Log.e(FullAodConfig.TAG, "wallpaper dim restore error", t);
        }
        try {
            java.util.List<View> views = new java.util.ArrayList<View>();
            synchronized (sCardViews) {
                for (WeakReference<Object> wr : sCardViews) {
                    if (wr.get() instanceof View) views.add((View) wr.get());
                }
            }
            synchronized (sClockViews) {
                for (WeakReference<Object> wr : sClockViews) {
                    if (wr.get() instanceof View) views.add((View) wr.get());
                }
            }
            for (View v : views) {
                View cur = v;
                for (int depth = 0; cur != null && depth < 10; depth++) {
                    if (cur.getVisibility() != View.VISIBLE) cur.setVisibility(View.VISIBLE);
                    Object p = cur.getParent();
                    if (!(p instanceof View)) break;
                    cur = (View) p;
                }
            }
            Log.i(TAG, "restoreWidgetsAtWake: restored " + views.size() + " tracked view chains");
        } catch (Throwable t) {
            Log.i(TAG, "restoreWidgetsAtWake error: " + t);
        }
    }

    private static void hideBigContentNow() {
        try {
            java.util.List<View> starts = new java.util.ArrayList<View>();
            View n = sNsslRef.get();
            if (n != null) starts.add(n);
            synchronized (sClockViews) {
                for (WeakReference<Object> wr : sClockViews) {
                    if (wr.get() instanceof View) starts.add((View) wr.get());
                }
            }
            synchronized (sCardViews) {
                for (WeakReference<Object> wr : sCardViews) {
                    if (wr.get() instanceof View) starts.add((View) wr.get());
                }
            }
            java.util.List<Object> hiddenTops = new java.util.ArrayList<Object>();
            for (View start : starts) {
                View cur = start;
                View top = start;
                for (int depth = 0; cur != null && depth < 40; depth++) {
                    Object p = cur.getParent();
                    if (!(p instanceof View)) break;
                    top = (View) p;
                    cur = top;
                }
                if (top == null || hiddenTops.contains(top)) continue;
                hiddenTops.add(top);
                top.setVisibility(View.GONE);
            }
            StringBuilder sb = new StringBuilder();
            for (Object t : hiddenTops) {
                View v = (View) t;
                sb.append(v.getClass().getSimpleName()).append("[vis=").append(v.getVisibility())
                        .append(",shown=").append(v.isShown()).append("] ");
            }
            Log.i(TAG, "hideBigContentNow: hid " + hiddenTops.size() + " root(s): " + sb);
        } catch (Throwable t) {
            Log.i(TAG, "hideBigContentNow error: " + t);
        }
    }

    private static final Runnable sTimeoutRunnable = new Runnable() {
        public void run() {
            sTimedOut = true;
            sDarkFieldHold = false;
            FullAodConfig.setTimedOut(true);
            // 1) bring the stock clock cover up FIRST (its flow may re-show content)
            try {
                Object cover = sClockCoverRef.get();
                if (cover != null) {
                    // real signatures: createSurface(Z)V / createSurface(FZ)V — bare calls throw
                    // try (FZ) first: the float+bool overload is the full-screen variant
                    try {
                        XposedHelpers.callMethod(cover, "createSurface", 0f, true);
                    } catch (Throwable t1) {
                        try {
                            XposedHelpers.callMethod(cover, "createSurface", true);
                        } catch (Throwable ignored) {
                        }
                    }
                    for (String m : new String[]{"createFullScreenSurface", "show", "showSurface"}) {
                        try {
                            XposedHelpers.callMethod(cover, m);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            // 2) THEN assert the black stock look (so late cover flow can't undo it)
            applyCurrentStateOnMainThread();
            hideBigContentNow();
            try {
                Object kvc = sKgVisControllerRef.get();
                if (kvc != null) {
                    XposedHelpers.callMethod(kvc, "setVisibility", android.view.View.GONE);
                }
            } catch (Throwable ignored) {
            }
            try {
                if (sAppContext != null) {
                    Object wm = XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("android.app.WallpaperManager", null),
                            "getInstance", sAppContext);
                    if (wm != null) {
                        XposedHelpers.callMethod(wm, "setWallpaperDimAmount", 1.0f);
                    }
                }
            } catch (Throwable ignored) {
            }
            // 3) re-assert once more: the stock cover flow has delayed "hold until
            //    content hidden" callbacks that can re-show the big content.
            sMainHandler.postDelayed(new Runnable() {
                public void run() {
                    if (!sTimedOut) return;
                    hideBigContentNow();
                    synchronized (sClockViews) {
                        for (WeakReference<Object> wr : sClockViews) {
                            Object o = wr.get();
                            if (o instanceof View) {
                                View vv = (View) o;
                                Log.i(TAG, "post-assert view " + vv.getClass().getSimpleName()
                                        + " vis=" + vv.getVisibility() + " alpha=" + vv.getAlpha()
                                        + " shown=" + vv.isShown());
                            }
                        }
                    }
                    try {
                        Object kvc = sKgVisControllerRef.get();
                        if (kvc != null) {
                            XposedHelpers.callMethod(kvc, "setVisibility", android.view.View.GONE);
                        }
                    } catch (Throwable ignored) {
                    }
                    applyCurrentStateOnMainThread();
                }
            }, 500);
            Log.i(TAG, "Full-AOD timed out -> stock AOD (presentation driven)");
        }
    };
    private static volatile float sLastLoadAvg = 0f;
    private static volatile int sLastThermal = 0;
    private static volatile long sLastCurrentMa = 0;
    private static volatile long sBenchBestNs = 0;

    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private SystemUiHook() {}

    private static void hookSleepSignal(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> wakeCls = XposedHelpers.findClass("com.android.systemui.keyguard.WakefulnessLifecycle", lpparam.classLoader);
            String[] cands = {"onStartedGoingToSleep", "dispatchStartedGoingToSleep", "startedGoingToSleep"};
            for (String m : cands) {
                try {
                    XposedHelpers.findAndHookMethod(wakeCls, m, int.class, new XC_MethodHook() {
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!FullAodConfig.isEnabled()) return;
                            try {
                                Object repo = sAodDimRepoRef.get();
                                if (repo != null) {
                                    XposedHelpers.callMethod(repo, "setDimAmount", computeAdaptiveFrontDim());
                                    XposedHelpers.callMethod(repo, "setWallpaperDimAmount", computeAdaptiveWallpaperDim());
                                }
                                // Bypass the Compose-collect -> IPC chain: set the wallpaper
                                // layer dim directly on WallpaperManager so the wallpaper's very
                                // first doze frame is already dimmed (kills the bright pop frame).
                                if (sAppContext != null) {
                                    android.app.WallpaperManager wm =
                                            android.app.WallpaperManager.getInstance(sAppContext);
                                    if (wm != null) {
                                        XposedHelpers.callMethod(wm, "setWallpaperDimAmount", computeAdaptiveWallpaperDim());
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    Log.i(TAG, "Sleep signal hooked: " + m);
                    return;
                } catch (Throwable ignored) {
                }
            }
            // no-arg fallbacks
            for (String m : cands) {
                try {
                    XposedHelpers.findAndHookMethod(wakeCls, m, new XC_MethodHook() {
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!FullAodConfig.isEnabled()) return;
                            try {
                                Object repo = sAodDimRepoRef.get();
                                if (repo != null) {
                                    XposedHelpers.callMethod(repo, "setDimAmount", computeAdaptiveFrontDim());
                                    XposedHelpers.callMethod(repo, "setWallpaperDimAmount", computeAdaptiveWallpaperDim());
                                }
                                // Bypass the Compose-collect -> IPC chain: set the wallpaper
                                // layer dim directly on WallpaperManager so the wallpaper's very
                                // first doze frame is already dimmed (kills the bright pop frame).
                                if (sAppContext != null) {
                                    android.app.WallpaperManager wm =
                                            android.app.WallpaperManager.getInstance(sAppContext);
                                    if (wm != null) {
                                        XposedHelpers.callMethod(wm, "setWallpaperDimAmount", computeAdaptiveWallpaperDim());
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    Log.i(TAG, "Sleep signal hooked (no-arg): " + m);
                    return;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "SleepSignal skipped: " + t);
        }
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        hookDozeMachine(cl);
        hookAodClockCover(cl);
        hookSleepSignal(lpparam);
        hookLightRevealScrim(cl);
        hookDimAndBrightness(cl);
        hookNothingWallpapers(cl);
        hookLockscreenElements(cl);
        // H5/H6 (TransitionStep value snap) REVERTED: it destroyed the lockscreen->AOD
        // animation without fixing the staged entrance. Staging is fixed at the presentation
        // layer (H9) instead of fighting the transition values.
        hookCardEarlyResume(cl);
        hookPresentationClamp();
        hookTransitionObserver(cl);
        hookTransitionStepSnap(cl);
        hookClockVisibility(cl);
    }

    private static boolean isActiveAodState(String stateName) {
        return "DOZE_AOD".equals(stateName)
                || "DOZE_AOD_DOCKED".equals(stateName)
                || "DOZE_PULSING".equals(stateName);
    }

    private static float computeAdaptiveFrontDim() {
        float base = FullAodConfig.getFrontDim();
        if (!FullAodConfig.isAdaptiveDim()) return base;
        int scene = sLastLightSceneValue;
        float offset = 0f;
        if (scene <= 0) {
            offset = 0.08f; // darker room -> slightly more dim
        } else if (scene >= 1000) {
            offset = -0.12f; // bright sunlight -> lighter soft dim for readability
        }
        return FullAodConfig.clamp(base + offset, 0.10f, 0.80f);
    }

    private static float computeAdaptiveWallpaperDim() {
        float base = FullAodConfig.getWallpaperDim();
        if (!FullAodConfig.isAdaptiveDim()) return base;
        int scene = sLastLightSceneValue;
        float offset = 0f;
        if (scene <= 0) {
            offset = 0.06f;
        } else if (scene >= 1000) {
            offset = -0.10f;
        }
        return FullAodConfig.clamp(base + offset, 0.05f, 0.80f);
    }

    private static void applyCurrentStateOnMainThread() {
        sMainHandler.post(() -> {
            try {
                boolean active = sFullAodActive;
                if (!active) {
                    sDarkFieldHold = false;
                    boolean wasTimedOut = sTimedOut || FullAodConfig.isTimedOut();
                    sTimedOut = false;
                    FullAodConfig.setTimedOut(false);
                    sMainHandler.removeCallbacks(sTimeoutRunnable);
                    if (wasTimedOut) {
                        // the timeout's visibility pin left the widget cards GONE; the
                        // wake flow re-shows the containers but never the cards themselves
                        restoreWidgetsAtWake();
                        sMainHandler.postDelayed(new Runnable() {
                            public void run() {
                                if (!sFullAodActive) restoreWidgetsAtWake();
                            }
                        }, 300);
                    }
                }
                Object repo = sAodDimRepoRef.get();
                if (repo != null) {
                    float fDim = active ? computeAdaptiveFrontDim() : 0f;
                    float wDim = active ? computeAdaptiveWallpaperDim() : 0f;
                    XposedHelpers.callMethod(repo, "setDimAmount", fDim);
                    XposedHelpers.callMethod(repo, "setWallpaperDimAmount", wDim);
                }
                View scrim = sLightRevealScrimRef.get();
                if (scrim != null) {
                    if (active && !sTimedOut) {
                        try {
                            XposedHelpers.callMethod(scrim, "setScrimOpaque", false);
                        } catch (Throwable ignored) {}
                    } else if (active) {
                        try {
                            XposedHelpers.callMethod(scrim, "setScrimOpaque", true);
                        } catch (Throwable ignored) {}
                    } else {
                        try {
                            XposedHelpers.callMethod(scrim, "updateScrimOpaque");
                        } catch (Throwable ignored) {}
                    }
                    scrim.invalidate();
                }
                View nssl = sNsslRef.get();
                if (nssl != null && active && FullAodConfig.isShowNotifications()) {
                    if (nssl.getAlpha() < 0.99f) {
                        nssl.setAlpha(1.0f);
                    }
                    if (nssl.getVisibility() != View.VISIBLE) {
                        nssl.setVisibility(View.VISIBLE);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "applyCurrentStateOnMainThread error", t);
            }
        });
    }

    // ==================== H5: Instant keyguard entrance -> AOD ====================
    // Root cause of staged entrance (dim -> widgets/bg -> clock pop):
    // KeyguardTransitionRepositoryImpl runs a ValueAnimator pushing transition value 0->1
    // (~1.3s for UNDEFINED->AOD) and per-element flows map that value with staggered
    // delays. When entering AOD we snap value to 1.0 so every element lands instantly.
    // Wake direction (->LOCKSCREEN) is left untouched.
    private static void hookKeyguardTransitionSnap(ClassLoader cl) {
        try {
            Class<?> repoCls = XposedHelpers.findClass(
                    "com.android.systemui.keyguard.data.repository.KeyguardTransitionRepositoryImpl", cl);
            XC_MethodHook snap = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        FullAodConfig.refreshIfNeeded(sAppContext);
                        if (!FullAodConfig.isEnabled()) return;
                        if (param.args == null || param.args.length < 2) return;
                        if (!(param.args[1] instanceof Float)) return;
                        if (((Float) param.args[1]) >= 1.0f) return;
                        boolean toAod = false;
                        try {
                            Object info = XposedHelpers.callMethod(param.thisObject, "getCurrentTransitionInfo");
                            if (info != null) {
                                Object to = XposedHelpers.callMethod(info, "getTo");
                                toAod = to != null && "AOD".equals(String.valueOf(to));
                            }
                        } catch (Throwable ignored) {
                        }
                        if (toAod) {
                            param.args[1] = 1.0f;
                            long now = android.os.SystemClock.uptimeMillis();
                            if (now - sLastSnapLogMs > 3000L) {
                                sLastSnapLogMs = now;
                                Log.i(TAG, "Snapped keyguard transition -> AOD to value 1.0 (instant entrance)");
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            XposedBridge.hookAllMethods(repoCls, "updateTransitionInternal", snap);
            XposedBridge.hookAllMethods(repoCls, "updateTransition", snap);
            Log.i(TAG, "Hooked KeyguardTransitionRepositoryImpl (instant AOD entrance)");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook KeyguardTransitionRepositoryImpl", t);
        }
    }

    // ==================== H6: Snap TransitionStep value (instant AOD entrance) ====================
    // The per-frame transition value bypasses KeyguardTransitionRepositoryImpl.updateTransition*
    // and flows through TransitionStep construction (animatorListener -> emitTransition). Clamping
    // the value at every TransitionStep creation point lands the ->AOD entrance at end state at
    // once (clock included), killing the staged 1.3s fade (dim -> widgets/bg -> clock pop).
    // 'to' resolution: arg before the float (KeyguardState / TransitionInfo.getTo);
    // copy$default passes nulls for "use default" -> fall back to the source step instance.
    // ==================== H12a: Transition direction observer ====================
    // Records whether the current entrance comes from the lockscreen (keep the natural
    // transition animation) or from other screens (instant swap + staged reveal).
    private static void hookTransitionObserver(ClassLoader cl) {
        try {
            Class<?> repoCls = XposedHelpers.findClass(
                    "com.android.systemui.keyguard.data.repository.KeyguardTransitionRepositoryImpl", cl);
            XposedBridge.hookAllMethods(repoCls, "startTransition", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 1 || param.args[0] == null) return;
                        Object from = XposedHelpers.callMethod(param.args[0], "getFrom");
                        sFromLockscreen = "LOCKSCREEN".equals(String.valueOf(from));
                        if (sFromLockscreen) {
                            sEntranceMode = 0;
                            sDarkFieldHold = false;
                        } else {
                            sDarkFieldHold = false;
                            // Pre-land the SAME target dims the native flow would reach, at the
                            // transition start (before the first doze frame). Pure pre-warm: no
                            // coercion, no holds — kills the brief "wallpaper at original
                            // brightness" window caused by the dim chains landing late.
                            try {
                                Object repo = sAodDimRepoRef.get();
                                if (repo != null) {
                                    XposedHelpers.callMethod(repo, "setDimAmount", computeAdaptiveFrontDim());
                                }
                                if (sAppContext != null) {
                                    Object wm = XposedHelpers.callStaticMethod(
                                            XposedHelpers.findClass("android.app.WallpaperManager", null),
                                            "getInstance", sAppContext);
                                    if (wm != null) {
                                        XposedHelpers.callMethod(wm, "setWallpaperDimAmount",
                                                computeAdaptiveWallpaperDim());
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        Log.i(TAG, "Transition start from=" + from
                                + " (fromLockscreen=" + sFromLockscreen + ")");
                    } catch (Throwable ignored) {
                    }
                }
            });
            Log.i(TAG, "Hooked transition observer");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook transition observer", t);
        }
    }

    private static void hookTransitionStepSnap(ClassLoader cl) {
        try {
            final Class<?> stepCls = XposedHelpers.findClass(
                    "com.android.systemui.keyguard.shared.model.TransitionStep", cl);
            XC_MethodHook snap = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        FullAodConfig.refreshIfNeeded(sAppContext);
                        if (!FullAodConfig.isEnabled()) return;
                        Object[] args = param.args;
                        if (args == null || args.length == 0) return;
                        int valueIdx = -1;
                        for (int i = 0; i < args.length; i++) {
                            if (args[i] instanceof Float) {
                                valueIdx = i;
                                break;
                            }
                        }
                        if (valueIdx < 1) return;
                        if (((Float) args[valueIdx]) >= 1.0f) return;
                        Object toSource = null;
                        Object beforeFloat = args[valueIdx - 1];
                        if (beforeFloat != null
                                && beforeFloat.getClass().getName().endsWith("TransitionInfo")) {
                            toSource = XposedHelpers.callMethod(beforeFloat, "getTo");
                        } else if (beforeFloat instanceof Enum) {
                            toSource = beforeFloat;
                        } else if (args[0] != null && args[0].getClass() == stepCls) {
                            toSource = XposedHelpers.callMethod(args[0], "getTo");
                        } else if (param.thisObject != null && param.thisObject.getClass() == stepCls
                                && "copy".equals(param.method.getName())) {
                            toSource = XposedHelpers.callMethod(param.thisObject, "getTo");
                        }
                        if ("AOD".equals(String.valueOf(toSource)) && !sFromLockscreen) {
                            args[valueIdx] = 1.0f;
                            long now = android.os.SystemClock.uptimeMillis();
                            if (now - sLastSnapLogMs > 3000L) {
                                sLastSnapLogMs = now;
                                Log.i(TAG, "Snapped TransitionStep -> AOD value to 1.0 (instant entrance)");
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            XposedBridge.hookAllConstructors(stepCls, snap);
            XposedBridge.hookAllMethods(stepCls, "copy", snap);
            XposedBridge.hookAllMethods(stepCls, "copy$default", snap);
            Log.i(TAG, "Hooked TransitionStep creation (instant AOD entrance)");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook TransitionStep", t);
        }
    }

    // ==================== H8: Force clock visible at doze entry ====================
    // KeyguardClockViewModel.isClockVisible / isLargeClockVisible are StateFlows whose value
    // only flips true after the doze auto-brightness settles (~5.5s, SCREEN_BRIGHTNESS_INITIALIZED).
    // The big clock (LondonUGClockView) is therefore invisible mid-entrance. We register the
    // ViewModels and set both flows' value to true at doze entry so the clock shows at once.
    private static final java.util.List<WeakReference<Object>> sClockVms =
            java.util.Collections.synchronizedList(new java.util.ArrayList<WeakReference<Object>>());
    private static final java.util.List<WeakReference<Object>> sClockViews =
            java.util.Collections.synchronizedList(new java.util.ArrayList<WeakReference<Object>>());

    private static void hookClockVisibility(ClassLoader cl) {
        try {
            Class<?> vmCls = XposedHelpers.findClass(
                    "com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel", cl);
            XposedBridge.hookAllConstructors(vmCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        synchronized (sClockVms) {
                            if (sClockVms.size() > 10) sClockVms.clear();
                            sClockVms.add(new WeakReference<Object>(param.thisObject));
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            Class<?> clockCls = XposedHelpers.findClass(
                    "com.nothing.systemui.shared.clocks.view.LondonUGClockView", cl);
            XposedBridge.hookAllConstructors(clockCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        synchronized (sClockViews) {
                            if (sClockViews.size() > 10) sClockViews.clear();
                            sClockViews.add(new WeakReference<Object>(param.thisObject));
                        }
                        try {
                            View nv = (View) param.thisObject;
                            if (sFullAodActive) {
                                if (nv.getVisibility() != View.VISIBLE) nv.setVisibility(View.VISIBLE);
                                if (sClockReleased) {
                                    if (nv.getAlpha() < 0.999f) nv.setAlpha(1.0f);
                                } else {
                                    if (nv.getAlpha() > 0.0f) nv.setAlpha(0f);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            Log.i(TAG, "Hooked KeyguardClockViewModel + LondonUGClockView (clock visibility)");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook KeyguardClockViewModel", t);
        }
    }

    private static void forceClockVisible() {
        if (sFromLockscreen || sEntranceMode == 0 || sFading || !sFullAodActive || sTimedOut) return;
        String[] fields = {"isClockVisible", "isLargeClockVisible"};
        java.util.List<WeakReference<Object>> snapshot;
        synchronized (sClockVms) {
            snapshot = new java.util.ArrayList<WeakReference<Object>>(sClockVms);
        }
        int ok = 0;
        for (WeakReference<Object> ref : snapshot) {
            Object vm = ref.get();
            if (vm == null) continue;
            for (String fname : fields) {
                try {
                    java.lang.reflect.Field f = null;
                    for (Class<?> c = vm.getClass(); c != null && f == null; c = c.getSuperclass()) {
                        try {
                            f = c.getDeclaredField(fname);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (f == null) continue;
                    f.setAccessible(true);
                    Object flow = f.get(vm);
                    if (flow == null) continue;
                    if (sClockReleased && flipFlow(flow, 0)) ok++;
                } catch (Throwable ignored) {
                }
            }
        }
        Log.i(TAG, "forceClockVisible: flipped " + ok + " flows on " + snapshot.size() + " vms");
        // Tree-walk force: locate the clock view by class name under the keyguard root
        int forced = 0;
        try {
            Object ctrl = sKgVisControllerRef.get();
            View root = findViewField(ctrl);
            if (root != null) {
                java.util.List<View> found = new java.util.ArrayList<View>();
                collectClockViews(root, found, 0);
                for (View clock : found) {
                    View cur = clock;
                    for (int depth = 0; cur != null && depth < 6; depth++) {
                        String cn = cur.getClass().getName();
                        if (cn.contains("KeyguardRoot") || cn.contains("NotificationShade")) break;
                        if (cur.getVisibility() != View.VISIBLE) cur.setVisibility(View.VISIBLE);
                        if (cur.getAlpha() < 0.999f) cur.setAlpha(1.0f);
                        forced++;
                        Object p = cur.getParent();
                        if (!(p instanceof View)) break;
                        cur = (View) p;
                    }
                }
                Log.i(TAG, "forceClockVisible: root=" + root.getClass().getSimpleName()
                        + " clockViews=" + found.size() + " forced=" + forced);
            } else {
                Log.i(TAG, "forceClockVisible: no root view found");
            }
        } catch (Throwable t) {
            Log.i(TAG, "forceClockVisible tree error: " + t);
        }
        // Direct force: registered clock views + tree search from the NSSL anchor
        int forced2 = 0;
        java.util.List<View> found2 = new java.util.ArrayList<View>();
        synchronized (sClockViews) {
            for (WeakReference<Object> r : sClockViews) {
                if (r.get() instanceof View) found2.add((View) r.get());
            }
        }
        try {
            View a = sNsslRef.get();
            if (a != null) {
                View root2 = a;
                while (root2.getParent() instanceof View) root2 = (View) root2.getParent();
                collectClockViews(root2, found2, 0);
            }
        } catch (Throwable ignored) {
        }
        if (!sClockReleased) {
            if (sEntranceMode == 1) {
                // Mode B: the full-black scrim holds the reveal; elements stay ready beneath.
                Log.i(TAG, "forceClockVisible: mode B held by scrim, views=" + found2.size());
                return;
            }
            // Mode W: pre-warm hold at alpha 0 (clock + widget cards).
            java.util.List<View> hold = new java.util.ArrayList<View>(found2);
            synchronized (sCardViews) {
                for (WeakReference<Object> wr : sCardViews) {
                    if (wr.get() instanceof View) hold.add((View) wr.get());
                }
            }
            for (View v : hold) {
                try {
                    if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
                    if (v.getAlpha() > 0.0f) v.setAlpha(0.0f);
                } catch (Throwable ignored) {
                }
            }
            Log.i(TAG, "forceClockVisible: holding alpha 0 (pre-warm), views=" + hold.size());
            return;
        }
        for (View clock : found2) {
            View cur = clock;
            for (int depth = 0; cur != null && depth < 8; depth++) {
                String cn = cur.getClass().getName();
                if (cn.contains("KeyguardRootView") || cn.contains("NotificationShadeWindow")) break;
                if (cur.getVisibility() != View.VISIBLE) cur.setVisibility(View.VISIBLE);
                if (cur.getAlpha() < 0.999f) cur.setAlpha(1.0f);
                try {
                    cur.invalidate();
                } catch (Throwable ignored) {
                }
                forced2++;
                synchronized (sClockChain) {
                    boolean known = false;
                    for (WeakReference<Object> wr : sClockChain) {
                        if (wr.get() == (Object) cur) {
                            known = true;
                            break;
                        }
                    }
                    if (!known) sClockChain.add(new WeakReference<Object>(cur));
                }
                Object p = cur.getParent();
                if (!(p instanceof View)) break;
                cur = (View) p;
            }
        }
        Log.i(TAG, "forceClockVisible: direct views=" + found2.size() + " forced=" + forced2);
    }

    private static boolean flipFlow(Object flow, int depth) {
        if (flow == null || depth > 3) return false;
        for (String m : new String[]{"setValue", "tryEmit"}) {
            try {
                java.lang.reflect.Method mm = flow.getClass().getMethod(m, Object.class);
                Object r = mm.invoke(flow, Boolean.TRUE);
                if (!(r instanceof Boolean) || ((Boolean) r)) return true;
            } catch (Throwable ignored) {
            }
        }
        // ReadonlyStateFlow wraps the real MutableStateFlow in a private field — unwrap it.
        try {
            for (Class<?> c = flow.getClass(); c != null; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType().isPrimitive()) continue;
                    if (!f.getType().getName().contains("Flow")) continue;
                    f.setAccessible(true);
                    Object inner = f.get(flow);
                    if (inner != null && inner != flow && flipFlow(inner, depth + 1)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ==================== H9: Presentation clamp (kill staged entrance) ====================
    // Clock/widget visibility+alpha flows are gated on the doze brightness settle (up to ~5s),
    // producing the staged entrance (blank -> widgets -> clock). Pin presentation to VISIBLE and
    // opaque during active full AOD instead of fighting the flows. sFullAodActive drops false at
    // the start of the wake transition, so the unlock animation is never touched.
    private static boolean isClockElement(String n) {
        return n.contains("Clock") || n.contains("DateView") || n.contains("KeyguardSliceView");
    }

    // SoC-busy probe. /proc/loadavg is EACCES for SystemUI (SELinux) and the fuel gauge
    // current reads 0 on this device, so we use a self-calibrating micro-benchmark: a fixed
    // math workload runs slower under CPU contention. Best-ever time = baseline; >=3x means
    // the SoC is busy. Thermal >= MODERATE is OR-ed in as a sustained-load backstop.
    private static long benchOnce() {
        long t0 = System.nanoTime();
        double x = 1.0;
        for (int i = 0; i < 120000; i++) {
            x = x * 1.0000001 + Math.sqrt(i);
        }
        if (x == 42.0) Log.i(TAG, "bench " + x);
        return System.nanoTime() - t0;
    }

    private static boolean readSocBusy() {
        try {
            benchOnce(); // JIT warm-up, untimed
            long dt = Math.min(benchOnce(), benchOnce());
            if (sBenchBestNs == 0 || dt < sBenchBestNs) sBenchBestNs = dt;
            sLastCurrentMa = dt / 1000; // bench microseconds (reported in log)
            sLastThermal = 0;
            try {
                android.os.PowerManager pm = sAppContext != null ? (android.os.PowerManager)
                        sAppContext.getSystemService(android.content.Context.POWER_SERVICE) : null;
                if (pm != null) sLastThermal = pm.getCurrentThermalStatus();
            } catch (Throwable ignored) {
            }
            boolean contended = dt >= sBenchBestNs * 2;
            boolean hot = sLastThermal >= 3; // SEVERE only: warm-residue must never fake busy
            return contended || hot;
        } catch (Throwable t) {
            Log.i(TAG, "readSocBusy error: " + t);
            return false;
        }
    }

    private static void fadeDimsToTarget(final long durMs) {
        try {
            sDarkFieldHold = false;
            final Object repo = sAodDimRepoRef.get();
            if (repo == null) return;
            final float fT = computeAdaptiveFrontDim();
            final float wT = computeAdaptiveWallpaperDim();
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(durMs);
            va.setInterpolator(new android.view.animation.DecelerateInterpolator());
            va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    float t = (Float) a.getAnimatedValue();
                    try {
                        XposedHelpers.callMethod(repo, "setDimAmount", 1.0f + (fT - 1.0f) * t);
                        XposedHelpers.callMethod(repo, "setWallpaperDimAmount", 1.0f + (wT - 1.0f) * t);
                    } catch (Throwable ignored) {
                    }
                }
            });
            sMainHandler.post(new Runnable() {
                public void run() {
                    va.start();
                }
            });
            Log.i(TAG, "Dim fade to target: " + durMs + "ms");
        } catch (Throwable ignored) {
        }
    }

    private static void releaseEntrance() {
        sDarkFieldHold = false;
        if (sClockReleased) return;
        sClockReleased = true;
        runEntranceFade();
    }

    // Mode B: fade the full-black scrim out (reveal). Mode W: fade elements in.
    private static void runEntranceFade() {
        try {
            {
                final java.util.List<View> targets = new java.util.ArrayList<View>();
                synchronized (sClockViews) {
                    for (WeakReference<Object> wr : sClockViews) {
                        if (wr.get() instanceof View) targets.add((View) wr.get());
                    }
                }
                synchronized (sCardViews) {
                    for (WeakReference<Object> wr : sCardViews) {
                        if (wr.get() instanceof View) targets.add((View) wr.get());
                    }
                }
                // Force parent chains ready so the fade of the leaves is what the eye sees.
                for (View clock : targets) {
                    try {
                        Object p = clock.getParent();
                        for (int depth = 0; p instanceof View && depth < 8; depth++) {
                            View cur = (View) p;
                            String cn = cur.getClass().getName();
                            if (cn.contains("KeyguardRootView") || cn.contains("NotificationShadeWindow")) break;
                            if (cur.getVisibility() != View.VISIBLE) cur.setVisibility(View.VISIBLE);
                            if (cur.getAlpha() < 0.999f) cur.setAlpha(1.0f);
                            synchronized (sClockChain) {
                                sClockChain.add(new WeakReference<Object>(cur));
                            }
                            p = cur.getParent();
                        }
                    } catch (Throwable ignored) {
                    }
                }
                sFading = true;
                android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
                va.setDuration(400);
                va.setInterpolator(new android.view.animation.DecelerateInterpolator());
                va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                    public void onAnimationUpdate(android.animation.ValueAnimator a) {
                        float v = (Float) a.getAnimatedValue();
                        for (View view : targets) {
                            try {
                                if (view.getVisibility() != View.VISIBLE) view.setVisibility(View.VISIBLE);
                                view.setAlpha(v);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                });
                va.addListener(new android.animation.AnimatorListenerAdapter() {
                    public void onAnimationEnd(android.animation.Animator a) {
                        sFading = false;
                        forceClockVisible();
                    }
                });
                sMainHandler.post(new Runnable() {
                    public void run() {
                        va.start();
                    }
                });
                Log.i(TAG, "Entrance fade: mode W element fade-in 400ms, targets=" + targets.size());
            }
        } catch (Throwable t) {
            Log.i(TAG, "Entrance fade error: " + t);
            sFading = false;
        }
    }

    private static boolean isChainMember(Object v) {
        synchronized (sClockChain) {
            for (WeakReference<Object> wr : sClockChain) {
                if (wr.get() == v) return true;
            }
        }
        return false;
    }

    private static boolean isWidgetElement(String n) {
        return n.contains("Widget") || n.contains("CardHost");
    }

    private static void hookPresentationClamp() {
        try {
            XC_MethodHook clamp = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (sTimedOut) {
                            String nn = param.thisObject.getClass().getName();
                            if (nn.contains("KeyguardRootView") || nn.contains("SceneWindow")
                                    || nn.contains("SharedNotificationContainer")
                                    || isClockElement(nn) || isWidgetElement(nn)) {
                                if (param.args != null && param.args.length >= 1
                                        && param.args[0] instanceof Integer) {
                                    param.args[0] = View.GONE;
                                }
                            }
                            return;
                        }
                        if (!sFullAodActive || sFromLockscreen || sFading) return;
                        String n = param.thisObject.getClass().getName();
                        boolean clock = isClockElement(n) || isChainMember(param.thisObject);
                        boolean widget = isWidgetElement(n);
                        if (!clock && !widget) return;
                        if (widget) {
                            FullAodConfig.refreshIfNeeded(sAppContext);
                            if (!FullAodConfig.isShowWidgets()) return;
                        }
                        if (param.args == null || param.args.length < 1) return;
                        Object arg = param.args[0];
                        if (sEntranceMode == 2 && !sClockReleased) {
                            if (isClockElement(n) || widget) {
                                // Mode W hold: alpha 0 but KEEP VISIBLE (pre-warmed draws).
                                if (arg instanceof Integer && (Integer) arg != 0) {
                                    param.args[0] = 0;
                                } else if (arg instanceof Float && (Float) arg > 0.0f) {
                                    param.args[0] = 0.0f;
                                }
                            }
                            return;
                        }
                        if (arg instanceof Integer && (Integer) arg != 0) {
                            param.args[0] = 0;
                        } else if (arg instanceof Float && (Float) arg < 0.999f) {
                            param.args[0] = 1.0f;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, clamp);
            XposedHelpers.findAndHookMethod(View.class, "setAlpha", float.class, clamp);
            Log.i(TAG, "Hooked View presentation clamp (instant AOD entrance)");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook presentation clamp", t);
        }
    }

    private static View findViewField(Object ctrl) {
        if (ctrl == null) return null;
        try {
            for (Class<?> c = ctrl.getClass(); c != null; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (!View.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object v = f.get(ctrl);
                    if (v instanceof View) return (View) v;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void collectClockViews(View v, java.util.List<View> out, int depth) {
        if (v == null || depth > 30 || out.size() > 20) return;
        String cn = v.getClass().getName();
        if (cn.contains("LondonUG") || cn.contains("ClockView") || cn.contains("ClockCover")) {
            out.add(v);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            for (int i = 0; i < vg.getChildCount() && out.size() <= 20; i++) {
                collectClockViews(vg.getChildAt(i), out, depth + 1);
            }
        }
    }

    // ==================== H7: Early card resume (clock renders sooner) ====================
    // The NTWidget cards (clock, buttons) only refresh their content (actionRefresh ->
    // onHostViewResumed) when the scene transition (gone->lockscreen) FINISHES (~1.2s after
    // lock). We register every CardWidgetHostView and force applyOnViewResumeIfNeed() at doze
    // entry so the card providers re-render immediately, making the clock appear within ~0.3s.
    private static final java.util.List<WeakReference<Object>> sCardViews =
            java.util.Collections.synchronizedList(new java.util.ArrayList<WeakReference<Object>>());

    private static void hookCardEarlyResume(ClassLoader cl) {
        try {
            Class<?> cardCls = XposedHelpers.findClass("com.nothing.cardhost.CardWidgetHostView", cl);
            XposedBridge.hookAllConstructors(cardCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        synchronized (sCardViews) {
                            if (sCardViews.size() > 40) sCardViews.clear();
                            sCardViews.add(new WeakReference<Object>(param.thisObject));
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            Log.i(TAG, "Hooked CardWidgetHostView (early card resume)");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook CardWidgetHostView", t);
        }
    }

    private static void forceEarlyCardResume() {
        try {
            java.util.List<WeakReference<Object>> snapshot;
            synchronized (sCardViews) {
                snapshot = new java.util.ArrayList<WeakReference<Object>>(sCardViews);
            }
            int ok = 0;
            for (WeakReference<Object> ref : snapshot) {
                Object v = ref.get();
                if (v == null) continue;
                boolean done = false;
                // controlLifeCycle(true) is the real resume dispatch (applyOnViewResumeIfNeed is
                // gated on pendingOnResumeStatus which is only set by the late scene-finish path)
                for (Class<?> c = v.getClass(); c != null && !done; c = c.getSuperclass()) {
                    try {
                        java.lang.reflect.Method m = c.getDeclaredMethod("controlLifeCycle", boolean.class);
                        m.setAccessible(true);
                        m.invoke(v, true);
                        ok++;
                        done = true;
                        if (ok == 1) Log.i(TAG, "Early card resume via controlLifeCycle(true)");
                    } catch (Throwable ignored) {
                    }
                }
                if (!done && ok == 0) {
                    Log.i(TAG, "Early card resume: controlLifeCycle missing on " + v.getClass().getSimpleName());
                }
            }
            Log.i(TAG, "Early card resume: applied to " + ok + "/" + snapshot.size() + " card views");
        } catch (Throwable ignored) {
        }
    }

    // ==================== H_STATE: DozeMachine ====================
    private static void hookDozeMachine(ClassLoader cl) {
        try {
            Class<?> dmCls = XposedHelpers.findClass("com.android.systemui.doze.DozeMachine", cl);
            XposedBridge.hookAllMethods(dmCls, "transitionTo", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 1 || param.args[0] == null) return;
                        sKgVisControllerRef = new WeakReference<Object>(param.thisObject);
                        FullAodConfig.refreshIfNeeded(sAppContext);
                        String nextState = param.args[0].toString();
                        String prev = sDozeState;
                        sDozeState = nextState;
                        boolean active = FullAodConfig.isEnabled() && isActiveAodState(nextState);
                        sFullAodActive = active;
                        Log.i(TAG, "DozeMachine.transitionTo: " + prev + " -> " + nextState
                                + " (fullAodActive=" + active + ", enabled=" + FullAodConfig.isEnabled() + ")");
                        applyCurrentStateOnMainThread();
                        if (active && !nextState.equals(prev)) {
                            sTimedOut = false;
                            FullAodConfig.setTimedOut(false);
                            sMainHandler.removeCallbacks(sTimeoutRunnable);
                            try {
                                if (!sTimedOut && FullAodConfig.isTimedOut()) {
                                    // Self-heal: a stale persisted flag from a previous
                                    // SystemUI life gates this whole module off forever
                                    // (the wake path reads the in-memory flag which resets
                                    // on restart). Clear the divergence at session start.
                                    FullAodConfig.setTimedOut(false);
                                    Log.i(TAG, "Cleared stale timed-out flag at doze entry");
                                }
                                int dur = FullAodConfig.resolveDurationS(sAppContext);
                                if (dur > 0) {
                                    sMainHandler.postDelayed(sTimeoutRunnable, dur * 1000L);
                                    Log.i(TAG, "Full-AOD duration: " + dur + "s -> stock AOD after");
                                }
                            } catch (Throwable ignored) {
                            }
                            if (sFromLockscreen) {
                                sEntranceMode = 0;
                                sClockReleased = true;
                                Log.i(TAG, "Entrance mode=NATURAL (from lockscreen)");
                            } else {
                                // Simplified entrance (user decision): no mode probing at all.
                                // The lockscreen wallpaper is on screen instantly at doze
                                // brightness; the widget/clock release logic below is unchanged.
                                sEntranceMode = 2;
                                sClockReleased = false;
                                Log.i(TAG, "Entrance: instant wallpaper + element release");
                            }
                            // 50ms poll: release the clock in the same breath as the widget cards'
                            // actual on-screen render (whichever signal comes first).
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                                    new Runnable() {
                                        private int tries = 0;

                                        public void run() {
                                            try {
                                                if (sClockReleased) return;
                                                synchronized (sCardViews) {
                                                    for (WeakReference<Object> wr : sCardViews) {
                                                        Object o = wr.get();
                                                        if (o instanceof View) {
                                                            View v = (View) o;
                                                            if (v.isShown()) {
                                                                Log.i(TAG, "Clock released: widget card on screen (poll)");
                                                                releaseEntrance();
                                                                return;
                                                            }
                                                        }
                                                    }
                                                }
                                                if (++tries < 60) {
                                                    new android.os.Handler(android.os.Looper.getMainLooper())
                                                            .postDelayed(this, 50);
                                                } else {
                                                    releaseEntrance();
                                                }
                                            } catch (Throwable ignored) {
                                            }
                                        }
                                    }, 100);
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                                    new Runnable() {
                                        public void run() {
                                            releaseEntrance(); // fallback release
                                        }
                                    }, 1500);
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                                    new Runnable() {
                                        public void run() {
                                            forceEarlyCardResume();
                                            forceClockVisible();
                                        }
                                    }, 150);
                            for (int ms : new int[]{700, 1800, 3200}) {
                                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                                        new Runnable() {
                                            public void run() {
                                                forceClockVisible();
                                            }
                                        }, ms);
                            }
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "DozeMachine.transitionTo hook error", t);
                    }
                }
            });
            XposedBridge.hookAllMethods(dmCls, "onScreenState", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args != null && param.args.length > 0) {
                            Log.i(TAG, "DozeMachine.onScreenState: state=" + param.args[0]
                                    + " dozeState=" + sDozeState + " fullAodActive=" + sFullAodActive);
                        }
                    } catch (Throwable ignored) {}
                }
            });
            Log.i(TAG, "Hooked DozeMachine");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook DozeMachine", t);
        }
    }

    // ==================== H0: AODClockCoverController ====================
    private static void hookAodClockCover(ClassLoader cl) {
        try {
            Class<?> coverCls = XposedHelpers.findClass("com.nothing.systemui.doze.AODClockCoverController", cl);
            XposedBridge.hookAllConstructors(coverCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sClockCoverRef = new WeakReference<>(param.thisObject);
                    try {
                        Context ctx = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                        if (ctx != null) sAppContext = ctx.getApplicationContext();
                    } catch (Throwable ignored) {}
                }
            });

            XC_MethodHook suppressVoid = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    FullAodConfig.refreshIfNeeded(sAppContext);
                    if (FullAodConfig.isEnabled() && !sTimedOut) {
                        Log.i(TAG, "Suppressed AODClockCoverController." + param.method.getName()
                                + " (dozeState=" + sDozeState + ")");
                        param.setResult(null);
                    }
                }
            };
            XposedBridge.hookAllMethods(coverCls, "createSurface", suppressVoid);
            XposedBridge.hookAllMethods(coverCls, "createFullScreenSurface", suppressVoid);
            XposedBridge.hookAllMethods(coverCls, "show", suppressVoid);
            XposedBridge.hookAllMethods(coverCls, "holdCoverUntilContentHidden", suppressVoid);
            XposedBridge.hookAllMethods(coverCls, "showSurface", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    FullAodConfig.refreshIfNeeded(sAppContext);
                    if (FullAodConfig.isEnabled() && !sTimedOut) {
                        Log.i(TAG, "Suppressed AODClockCoverController.showSurface");
                        param.setResult(Boolean.FALSE);
                    }
                }
            });
            Log.i(TAG, "Hooked AODClockCoverController");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook AODClockCoverController", t);
        }
    }

    // ==================== H1: LightRevealScrim ====================
    private static void hookLightRevealScrim(ClassLoader cl) {
        try {
            Class<?> lrsCls = XposedHelpers.findClass("com.android.systemui.statusbar.LightRevealScrim", cl);
            XposedBridge.hookAllMethods(lrsCls, "setRevealAmount", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        View v = (View) param.thisObject;
                        sLightRevealScrimRef = new WeakReference<>(v);
                        if (sAppContext == null && v != null && v.getContext() != null) {
                            sAppContext = v.getContext().getApplicationContext();
                        }
                    } catch (Throwable ignored) {}
                }
            });
            XposedHelpers.findAndHookMethod(lrsCls, "onDraw", Canvas.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sFullAodActive) {
                        param.setResult(null);
                    }
                }
            });
            XposedBridge.hookAllMethods(lrsCls, "updateScrimOpaque", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sFullAodActive && !sTimedOut) {
                        try {
                            XposedHelpers.callMethod(param.thisObject, "setScrimOpaque", false);
                        } catch (Throwable ignored) {}
                        param.setResult(null);
                    } else if (sTimedOut) {
                        try {
                            XposedHelpers.callMethod(param.thisObject, "setScrimOpaque", true);
                        } catch (Throwable ignored) {}
                        param.setResult(null);
                    }
                }
            });
            Log.i(TAG, "Hooked LightRevealScrim");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook LightRevealScrim", t);
        }
    }

    // ==================== H2: Dimming & DozeScreenBrightness ====================
    private static void hookDimAndBrightness(ClassLoader cl) {
        try {
            Class<?> repoCls = XposedHelpers.findClass(
                    "com.android.systemui.keyguard.data.repository.AodDimRepository", cl);
            XposedBridge.hookAllConstructors(repoCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sAodDimRepoRef = new WeakReference<>(param.thisObject);
                    Log.i(TAG, "Captured AodDimRepository instance");
                }
            });
            XposedBridge.hookAllMethods(repoCls, "setDimAmount", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sAodDimRepoRef = new WeakReference<>(param.thisObject);
                    if (sTimedOut) return;
                    if (sDarkFieldHold && param.args != null && param.args.length > 0) {
                        param.args[0] = computeAdaptiveFrontDim();
                        return;
                    }
                    if (sFullAodActive && param.args != null && param.args.length > 0) {
                        float req = (Float) param.args[0];
                        if (req < 0.05f) {
                            float target = computeAdaptiveFrontDim();
                            param.args[0] = target;
                            Log.i(TAG, "AodDimRepository.setDimAmount: " + req + " -> " + target);
                        }
                    }
                }
            });
            XposedBridge.hookAllMethods(repoCls, "setWallpaperDimAmount", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sAodDimRepoRef = new WeakReference<>(param.thisObject);
                    if (sTimedOut) return;
                    if (sDarkFieldHold && param.args != null && param.args.length > 0) {
                        param.args[0] = computeAdaptiveWallpaperDim();
                        return;
                    }
                    if (sFullAodActive && param.args != null && param.args.length > 0) {
                        float req = (Float) param.args[0];
                        if (req < 0.05f) {
                            float target = computeAdaptiveWallpaperDim();
                            param.args[0] = target;
                            Log.i(TAG, "AodDimRepository.setWallpaperDimAmount: " + req + " -> " + target);
                        }
                    }
                }
            });
            Log.i(TAG, "Hooked AodDimRepository");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook AodDimRepository", t);
        }

        try {
            Class<?> dsbCls = XposedHelpers.findClass("com.android.systemui.doze.DozeScreenBrightness", cl);
            XposedBridge.hookAllMethods(dsbCls, "computeScrimOpacity", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    FullAodConfig.refreshIfNeeded(sAppContext);
                    if (FullAodConfig.isEnabled() && sFullAodActive) {
                        int opacity = Math.round(computeAdaptiveFrontDim() * 255f);
                        param.setResult(opacity);
                    }
                }
            });
            XposedBridge.hookAllMethods(dsbCls, "computeWallpaperScrimOpacity", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    FullAodConfig.refreshIfNeeded(sAppContext);
                    if (FullAodConfig.isEnabled() && sFullAodActive) {
                        int opacity = Math.round(computeAdaptiveWallpaperDim() * 255f);
                        param.setResult(opacity);
                    }
                }
            });
            Log.i(TAG, "Hooked DozeScreenBrightness scrim opacity");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook DozeScreenBrightness", t);
        }

        try {
            Class<?> dsbExCls = XposedHelpers.findClass("com.nothing.systemui.doze.DozeScreenBrightnessEx", cl);
            XposedBridge.hookAllMethods(dsbExCls, "initBrightness", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof Context) {
                        sAppContext = ((Context) param.args[0]).getApplicationContext();
                    }
                }
            });
            // Passive observer ONLY: never skip notifySetupBrightness()!
            XposedBridge.hookAllMethods(dsbExCls, "updateDozeBrightness", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        int bucket = (Integer) param.args[0];
                        int sensorVal = (Integer) param.args[1];
                        int effective = (bucket != -1) ? bucket : sensorVal;
                        if (effective >= 0) {
                            sLastLightSceneValue = effective;
                            Log.i(TAG, "DozeScreenBrightnessEx.updateDozeBrightness: bucket=" + bucket
                                    + " sensorVal=" + sensorVal + " -> lightScene=" + effective);
                            if (sFullAodActive) {
                                applyCurrentStateOnMainThread();
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
            Log.i(TAG, "Hooked DozeScreenBrightnessEx (observer)");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook DozeScreenBrightnessEx", t);
        }
    }

    // ==================== H3: Nothing Dual-Track Wallpapers ====================
    private static void hookNothingWallpapers(ClassLoader cl) {
        try {
            Class<?> ntWpCls = XposedHelpers.findClass(
                    "com.nothing.systemui.wallpaper.NTLockscreentWallpaperController", cl);
            XposedBridge.hookAllMethods(ntWpCls, "setSurfaceBehindAlpha", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sFullAodActive && param.args != null && param.args.length > 0) {
                        boolean kgVis = false;
                        try {
                            kgVis = XposedHelpers.getBooleanField(param.thisObject, "keyguardVisibile");
                        } catch (Throwable ignored) {}
                        if (kgVis) {
                            param.args[0] = 0.0f; // wallpaperAlpha = 1f - 0f = 1.0f
                        }
                    }
                }
            });
            XposedBridge.hookAllMethods(ntWpCls, "updateWallpaperAlpha", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sFullAodActive) {
                        try {
                            boolean kgVis = XposedHelpers.getBooleanField(param.thisObject, "keyguardVisibile");
                            if (kgVis) {
                                XposedHelpers.setFloatField(param.thisObject, "wallpaperAlpha", 1.0f);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            });
            Log.i(TAG, "Hooked NTLockscreentWallpaperController");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook NTLockscreentWallpaperController", t);
        }

        try {
            Class<?> depthCls = XposedHelpers.findClass(
                    "com.nothing.wallpaper.NTDepthEffectWallpaperController", cl);
            XposedBridge.hookAllMethods(depthCls, "setDarkAmount", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sFullAodActive && param.args != null && param.args.length > 0) {
                        param.args[0] = 0.0f;
                    }
                }
            });
            Log.i(TAG, "Hooked NTDepthEffectWallpaperController");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook NTDepthEffectWallpaperController", t);
        }
    }

    // ==================== H4: Full Lockscreen Elements ====================
    private static void hookLockscreenElements(ClassLoader cl) {
        // 1. LockscreenToAodTransitionViewModel: keep lockscreen alpha & shortcuts alpha at 1.0f
        try {
            Class<?> l2aCls = XposedHelpers.findClass(
                    "com.android.systemui.keyguard.ui.viewmodel.LockscreenToAodTransitionViewModel", cl);
            XposedBridge.hookAllMethods(l2aCls, "lockscreenAlpha$lambda$6", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (FullAodConfig.isEnabled()) {
                        param.setResult(1.0f);
                    }
                }
            });
            XposedBridge.hookAllMethods(l2aCls, "shortcutsAlpha$lambda$3", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (FullAodConfig.isEnabled() && FullAodConfig.isKeepShortcuts()) {
                        param.setResult(1.0f);
                    }
                }
            });
            Log.i(TAG, "Hooked LockscreenToAodTransitionViewModel");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook LockscreenToAodTransitionViewModel", t);
        }

        // 2. ToAodEndStateTransitionViewModel: keep shortcutsAlpha = lockscreenAlpha (1.0f)
        try {
            Class<?> endCls = XposedHelpers.findClass(
                    "com.android.systemui.keyguard.ui.viewmodel.ToAodEndStateTransitionViewModel", cl);
            XposedBridge.hookAllConstructors(endCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (FullAodConfig.isEnabled() && FullAodConfig.isKeepShortcuts()) {
                            Object lsAlpha = XposedHelpers.getObjectField(param.thisObject, "lockscreenAlpha");
                            if (lsAlpha != null) {
                                XposedHelpers.setObjectField(param.thisObject, "shortcutsAlpha", lsAlpha);
                                Log.i(TAG, "ToAodEndStateTransitionViewModel: redirected shortcutsAlpha -> 1.0f flow");
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook ToAodEndStateTransitionViewModel", t);
        }

        // 3. NTWidgetContainerViewModel$VisibilityDecision: keep Nothing widgets visible in DOZE_AOD
        try {
            Class<?> vdCls = XposedHelpers.findClass(
                    "com.nothing.systemui.keyguard.ui.viewmodel.NTWidgetContainerViewModel$VisibilityDecision", cl);
            XposedBridge.hookAllConstructors(vdCls, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sFullAodActive && !sTimedOut && FullAodConfig.isShowWidgets()
                            && param.args != null && param.args.length >= 1
                            && Boolean.FALSE.equals(param.args[0])) {
                        param.args[0] = Boolean.TRUE;
                        if (!sClockReleased) {
                            Log.i(TAG, "Clock released: widgets/wallpaper landing (VisibilityDecision)");
                            releaseEntrance();
                            for (int ms : new int[]{150, 400, 800, 1500}) {
                                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                                        new Runnable() {
                                            public void run() {
                                                forceClockVisible();
                                            }
                                        }, ms);
                            }
                        }
                        Log.i(TAG, "NTWidgetContainerViewModel.VisibilityDecision forced visible=true in DOZE_AOD");
                    }
                }
            });
            Log.i(TAG, "Hooked NTWidgetContainerViewModel$VisibilityDecision");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook NTWidgetContainerViewModel$VisibilityDecision", t);
        }

        // 4. NotificationStackScrollLayout: keep notification cards visible during DOZE_AOD
        try {
            Class<?> nsslCls = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout", cl);
            XposedBridge.hookAllMethods(nsslCls, "setHideAmount", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sNsslRef = new WeakReference<>((View) param.thisObject);
                    if (sFullAodActive && FullAodConfig.isShowNotifications()
                            && param.args != null && param.args.length >= 2) {
                        float a0 = (Float) param.args[0];
                        float a1 = (Float) param.args[1];
                        if (a0 > 0.01f || a1 > 0.01f) {
                            param.args[0] = 0.0f;
                            param.args[1] = 0.0f;
                        }
                    }
                }
            });
            XposedBridge.hookAllMethods(nsslCls, "setAlpha", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sNsslRef = new WeakReference<>((View) param.thisObject);
                    if (sFullAodActive && FullAodConfig.isShowNotifications()
                            && param.args != null && param.args.length >= 1) {
                        float a = (Float) param.args[0];
                        if (a < 0.99f) {
                            param.args[0] = 1.0f;
                        }
                    }
                }
            });
            Log.i(TAG, "Hooked NotificationStackScrollLayout");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook NotificationStackScrollLayout", t);
        }

        // 5. KeyguardRootViewVisibilityController: diagnostic visibility check
        try {
            Class<?> krvCls = XposedHelpers.findClass(
                    "com.nothing.systemui.keyguard.KeyguardRootViewVisibilityController", cl);
            XposedBridge.hookAllConstructors(krvCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sKgVisControllerRef = new WeakReference<>(param.thisObject);
                }
            });
            XposedBridge.hookAllMethods(krvCls, "setVisibility", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sKgVisControllerRef = new WeakReference<>(param.thisObject);
                    if (sTimedOut && param.args != null && param.args.length >= 1) {
                        param.args[0] = View.GONE;
                        return;
                    }
                    if (sFullAodActive && !sTimedOut && param.args != null && param.args.length >= 1) {
                        int vis = (Integer) param.args[0];
                        if (vis != View.VISIBLE) {
                            Log.i(TAG, "KeyguardRootViewVisibilityController.setVisibility(" + vis
                                    + ") intercepted -> VISIBLE in DOZE_AOD");
                            param.args[0] = View.VISIBLE;
                        }
                    }
                }
            });
            Log.i(TAG, "Hooked KeyguardRootViewVisibilityController");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook KeyguardRootViewVisibilityController", t);
        }
    }
}
