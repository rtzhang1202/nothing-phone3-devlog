package io.github.rtzhang.nothingfullaod;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) return;
        String pkg = lpparam.packageName;
        if ("com.android.systemui".equals(pkg)) {
            Log.i(FullAodConfig.TAG, "Loading SystemUiHook into " + pkg + " (process=" + lpparam.processName + ")");
            SystemUiHook.install(lpparam);
        } else if ("android".equals(pkg) || "system".equals(pkg)) {
            Log.i(FullAodConfig.TAG, "Loading FrameworkBrightnessHook into " + pkg + " (process=" + lpparam.processName + ")");
            FrameworkBrightnessHook.install(lpparam);
        }
    }
}
