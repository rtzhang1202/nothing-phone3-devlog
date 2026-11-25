package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

import de.robv.android.xposed.IXposedHookLoadPackage;

/** Compile-time stub matching XposedBridgeApi-82. Never packaged into classes.dex. */
public abstract class XC_LoadPackage extends XCallback implements IXposedHookLoadPackage {
    protected XC_LoadPackage() {
        super();
    }

    public static final class LoadPackageParam extends XCallback.Param {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
        public boolean isFirstApplication;

        LoadPackageParam() {
            super();
        }
    }
}
