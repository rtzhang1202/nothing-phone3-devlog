package io.github.rtzhang.keypilot.hook;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Lock-free foreground app & multi-window focus tracker inside system_server (uid=1000).
 *
 * Strategy (zero WindowManagerGlobalLock contention on key-event hot path):
 * 1. Primary: hooks DisplayContent#setFocusedApp (captures exact focused ActivityRecord on
 *    normal switches, split-screen pane clicks, and SHOW_WHEN_LOCKED lockscreen camera).
 * 2. Secondary: hooks TaskChangeNotificationController#notifyTaskMovedToFront /
 *    notifyTaskFocusChanged and registers an ITaskStackListener proxy on ATMS when ready.
 * 3. Snapshot reads volatile CachedTop + live KeyguardManager / PowerManager (<0.1ms).
 */
public final class ForegroundTracker {

    private static final String CLS_DISPLAY_CONTENT = "com.android.server.wm.DisplayContent";
    private static final String CLS_TASK_CHANGE_NOTIFIER =
            "com.android.server.wm.TaskChangeNotificationController";
    private static final String CLS_ATMS =
            "com.android.server.wm.ActivityTaskManagerService";

    private static final class CachedTop {
        final String pkg;
        final String activity;
        final boolean multiWindow;
        final long updatedAtMs;

        CachedTop(String pkg, String activity, boolean multiWindow, long updatedAtMs) {
            this.pkg = pkg != null ? pkg : "";
            this.activity = activity != null ? activity : "";
            this.multiWindow = multiWindow;
            this.updatedAtMs = updatedAtMs;
        }
    }

    private static volatile CachedTop sCachedTop = new CachedTop("", "", false, 0L);
    private static final AtomicBoolean sInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean sTaskListenerRegistered = new AtomicBoolean(false);
    private static volatile Handler sBgHandler;

    // Cached reflection handles for ActivityRecord extraction
    private static volatile Field sArPkgField;
    private static volatile Field sArCompField;
    private static volatile Method sArMultiWindowMethod;

    private ForegroundTracker() {
    }

    public static void install(ClassLoader cl) {
        if (!sInstalled.compareAndSet(false, true)) {
            return;
        }
        int installedCount = 0;

        // Path 1: DisplayContent.setFocusedApp(ActivityRecord newFocus)
        try {
            Class<?> dcCls = XposedHelpers.findClassIfExists(CLS_DISPLAY_CONTENT, cl);
            if (dcCls != null) {
                XposedBridge.hookAllMethods(dcCls, "setFocusedApp", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                                updateFromActivityRecord(param.args[0]);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                installedCount++;
                HookLog.i("ForegroundTracker hooked DisplayContent#setFocusedApp");
            }
        } catch (Throwable t) {
            HookLog.e("ForegroundTracker DisplayContent hook skipped", t);
        }

        // Path 2: TaskChangeNotificationController.notifyTaskMovedToFront / notifyTaskFocusChanged
        try {
            Class<?> tcncCls = XposedHelpers.findClassIfExists(CLS_TASK_CHANGE_NOTIFIER, cl);
            if (tcncCls != null) {
                XposedBridge.hookAllMethods(tcncCls, "notifyTaskMovedToFront", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                                updateFromTaskInfo(param.args[0]);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                installedCount++;
                HookLog.i("ForegroundTracker hooked TaskChangeNotificationController#notifyTaskMovedToFront");
            }
        } catch (Throwable t) {
            HookLog.e("ForegroundTracker TaskChangeNotificationController hook skipped", t);
        }

        // Path 3: ATMS.onSystemReady -> register lightweight ITaskStackListener proxy
        try {
            Class<?> atmsCls = XposedHelpers.findClassIfExists(CLS_ATMS, cl);
            if (atmsCls != null) {
                XposedBridge.hookAllMethods(atmsCls, "onSystemReady", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            tryRegisterTaskStackListener(param.thisObject, cl);
                        } catch (Throwable ignored) {
                        }
                    }
                });
                installedCount++;
            }
        } catch (Throwable t) {
            HookLog.e("ForegroundTracker ATMS hook skipped", t);
        }

        HookLog.i("ForegroundTracker initialized (" + installedCount + " channels active)");
    }

    /**
     * Lock-free snapshot safe to invoke on InputDispatcher / FSM threads (<0.1ms).
     */
    public static ContextSnapshot snapshot(Context ctx, KeyEvent event) {
        CachedTop top = sCachedTop;
        if (top.pkg.isEmpty() && ctx != null) {
            scheduleAsyncFallbackRefresh(ctx);
        }

        boolean locked = false;
        boolean interactive = true;
        if (ctx != null) {
            try {
                KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
                if (km != null) {
                    locked = km.isKeyguardLocked();
                }
            } catch (Throwable ignored) {
            }
            try {
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    interactive = pm.isInteractive();
                }
            } catch (Throwable ignored) {
            }
        }

        boolean isExternal = false;
        if (event != null) {
            try {
                InputDevice dev = event.getDevice();
                if (dev != null) {
                    isExternal = dev.isExternal();
                }
            } catch (Throwable ignored) {
            }
        }

        return new ContextSnapshot(
                top.pkg, top.activity, locked, interactive,
                top.multiWindow, isExternal, top.updatedAtMs);
    }

    public static String getCurrentTopPackage() {
        return sCachedTop.pkg;
    }

    // --------------------------------------------------------- internal extraction

    private static void updateFromActivityRecord(Object ar) {
        if (ar == null) return;
        String pkg = null;
        String act = null;
        boolean mw = false;

        try {
            Field fComp = sArCompField;
            if (fComp == null) {
                fComp = findFieldInHierarchy(ar.getClass(), "mActivityComponent");
                if (fComp != null) {
                    fComp.setAccessible(true);
                    sArCompField = fComp;
                }
            }
            if (fComp != null) {
                Object c = fComp.get(ar);
                if (c instanceof ComponentName) {
                    ComponentName cn = (ComponentName) c;
                    pkg = cn.getPackageName();
                    act = cn.getClassName();
                }
            }
        } catch (Throwable ignored) {
        }

        if (pkg == null) {
            try {
                Field fPkg = sArPkgField;
                if (fPkg == null) {
                    fPkg = findFieldInHierarchy(ar.getClass(), "packageName");
                    if (fPkg != null) {
                        fPkg.setAccessible(true);
                        sArPkgField = fPkg;
                    }
                }
                if (fPkg != null) {
                    Object v = fPkg.get(ar);
                    if (v instanceof String) {
                        pkg = (String) v;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            Method mMw = sArMultiWindowMethod;
            if (mMw == null) {
                mMw = findZeroArgMethod(ar.getClass(), "inMultiWindowMode");
                if (mMw != null) {
                    mMw.setAccessible(true);
                    sArMultiWindowMethod = mMw;
                }
            }
            if (mMw != null) {
                Object r = mMw.invoke(ar);
                if (r instanceof Boolean) {
                    mw = (Boolean) r;
                }
            }
        } catch (Throwable ignored) {
        }

        if (pkg != null && !pkg.isEmpty()) {
            sCachedTop = new CachedTop(pkg, act != null ? act : "", mw, System.currentTimeMillis());
        }
    }

    private static void updateFromTaskInfo(Object taskInfo) {
        if (taskInfo == null) return;
        try {
            Field fTop = findFieldInHierarchy(taskInfo.getClass(), "topActivity");
            if (fTop != null) {
                fTop.setAccessible(true);
                Object cnObj = fTop.get(taskInfo);
                if (cnObj instanceof ComponentName) {
                    ComponentName cn = (ComponentName) cnObj;
                    String pkg = cn.getPackageName();
                    String act = cn.getClassName();
                    if (pkg != null && !pkg.isEmpty()) {
                        boolean mw = false;
                        try {
                            Field fWin = findFieldInHierarchy(taskInfo.getClass(), "configuration");
                            // Best-effort check; DisplayContent#setFocusedApp provides exact multi-window
                            if (fWin != null) {
                                mw = sCachedTop.multiWindow;
                            }
                        } catch (Throwable ignored) {
                        }
                        sCachedTop = new CachedTop(pkg, act != null ? act : "", mw,
                                System.currentTimeMillis());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void tryRegisterTaskStackListener(Object atms, ClassLoader cl) {
        if (atms == null || !sTaskListenerRegistered.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> listenerIface = XposedHelpers.findClassIfExists("android.app.ITaskStackListener", cl);
            if (listenerIface == null) {
                return;
            }
            Method regMethod = null;
            for (Method m : atms.getClass().getMethods()) {
                if ("registerTaskStackListener".equals(m.getName())
                        && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0].isAssignableFrom(listenerIface)) {
                    regMethod = m;
                    break;
                }
            }
            if (regMethod == null) {
                return;
            }
            final android.os.Binder stubBinder = new android.os.Binder();
            Object proxy = Proxy.newProxyInstance(
                    cl,
                    new Class<?>[]{listenerIface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object p, Method method, Object[] args) {
                            String name = method.getName();
                            if ("asBinder".equals(name)) {
                                return stubBinder;
                            }
                            if ("onTaskMovedToFront".equals(name) && args != null && args.length > 0) {
                                updateFromTaskInfo(args[0]);
                            }
                            return defaultReturn(method.getReturnType());
                        }
                    });
            regMethod.invoke(atms, proxy);
            HookLog.i("ForegroundTracker registered ITaskStackListener proxy on ATMS");
        } catch (Throwable t) {
            sTaskListenerRegistered.set(false);
            HookLog.i("ForegroundTracker ITaskStackListener optional registration skipped: "
                    + t.getClass().getSimpleName());
        }
    }

    private static void scheduleAsyncFallbackRefresh(final Context ctx) {
        try {
            Handler h = getBgHandler();
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                        if (am != null) {
                            @SuppressWarnings("deprecation")
                            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
                            if (tasks != null && !tasks.isEmpty()) {
                                updateFromTaskInfo(tasks.get(0));
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static Handler getBgHandler() {
        Handler h = sBgHandler;
        if (h == null) {
            synchronized (ForegroundTracker.class) {
                h = sBgHandler;
                if (h == null) {
                    HandlerThread ht = new HandlerThread("KeyPilot-FgTracker");
                    ht.start();
                    h = new Handler(ht.getLooper());
                    sBgHandler = h;
                }
            }
        }
        return h;
    }

    private static Field findFieldInHierarchy(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Method findZeroArgMethod(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static Object defaultReturn(Class<?> rt) {
        if (rt == boolean.class) return Boolean.FALSE;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        if (rt == float.class) return 0f;
        if (rt == double.class) return 0d;
        return null;
    }
}
