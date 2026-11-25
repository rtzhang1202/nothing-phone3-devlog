package io.github.rtzhang.keypilot;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.rtzhang.keypilot.hook.ConfigBridge;
import io.github.rtzhang.keypilot.hook.EssentialKeyHook;
import io.github.rtzhang.keypilot.hook.ForegroundTracker;
import io.github.rtzhang.keypilot.hook.HookLog;
import io.github.rtzhang.keypilot.hook.StandardKeysHook;

/**
 * KeyPilot Xposed entry point (API 82, v2 P1).
 *
 * Scopes (assets/xposed_scope):
 * - "android" (system_server):
 *     1. ForegroundTracker -> DisplayContent#setFocusedApp + TaskChangeNotificationController + ITaskStackListener
 *     2. EssentialKeyHook  -> com.android.server.wm.NtEssentialKeyImpl.onRecord(KeyEvent)
 *     3. StandardKeysHook  -> com.android.server.policy.PhoneWindowManager.interceptKeyBeforeQueueing
 *     4. ConfigBridge      -> dynamic BroadcastReceiver + /data/system/keypilot.json persistence
 * - "com.nothing.ntessentialrecorder":
 *     Read-only telemetry hook on MainService.onBind/onUnbind for diagnostic verification.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String PKG_ANDROID = "android";
    private static final String PKG_ESSENTIAL_RECORDER = "com.nothing.ntessentialrecorder";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (lpparam == null || lpparam.packageName == null) {
                return;
            }
            if (PKG_ANDROID.equals(lpparam.packageName)) {
                HookLog.i("MainHook loaded in system_server v2-P1 (process=" + lpparam.processName + ")");
                ConfigBridge.ensureInitialized(ConfigBridge.resolveSystemContext());
                ForegroundTracker.install(lpparam.classLoader);
                EssentialKeyHook.install(lpparam.classLoader);
                StandardKeysHook.install(lpparam.classLoader);
            } else if (PKG_ESSENTIAL_RECORDER.equals(lpparam.packageName)) {
                installRecorderTelemetry(lpparam.classLoader);
            }
        } catch (Throwable t) {
            HookLog.e("MainHook.handleLoadPackage error", t);
        }
    }

    /** Non-invasive diagnostic hook to confirm whether EssentialRecorder was woken up. */
    private static void installRecorderTelemetry(ClassLoader cl) {
        try {
            Class<?> mainSvc = XposedHelpers.findClassIfExists(
                    "com.nothing.ntessentialrecorder.MainService", cl);
            if (mainSvc == null) {
                HookLog.i("EssentialRecorder telemetry: MainService class not found");
                return;
            }
            XposedBridge.hookAllMethods(mainSvc, "onBind", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    HookLog.i("[RecorderTelemetry] MainService.onBind invoked (native Essential Key active)");
                }
            });
            XposedBridge.hookAllMethods(mainSvc, "onUnbind", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    HookLog.i("[RecorderTelemetry] MainService.onUnbind invoked");
                }
            });
            HookLog.i("EssentialRecorder telemetry installed on MainService");
        } catch (Throwable t) {
            HookLog.e("installRecorderTelemetry error", t);
        }
    }
}
