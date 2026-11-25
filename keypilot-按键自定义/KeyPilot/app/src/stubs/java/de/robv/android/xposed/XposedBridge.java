package de.robv.android.xposed;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.util.Set;

/** Compile-time stub matching XposedBridgeApi-82. Never packaged into classes.dex. */
public final class XposedBridge {
    public static final ClassLoader BOOTCLASSLOADER = ClassLoader.getSystemClassLoader();
    public static int XPOSED_BRIDGE_VERSION = 82;

    XposedBridge() {
    }

    public static native int getXposedVersion();

    public static synchronized void log(String text) {
        throw new UnsupportedOperationException("Stub");
    }

    public static synchronized void log(Throwable t) {
        throw new UnsupportedOperationException("Stub");
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        throw new UnsupportedOperationException("Stub");
    }

    public static void unhookMethod(Member hookMethod, XC_MethodHook callback) {
        throw new UnsupportedOperationException("Stub");
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> hookClass, String methodName, XC_MethodHook callback) {
        throw new UnsupportedOperationException("Stub");
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(
            Class<?> hookClass, XC_MethodHook callback) {
        throw new UnsupportedOperationException("Stub");
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws NullPointerException, IllegalAccessException, IllegalArgumentException,
            InvocationTargetException {
        throw new UnsupportedOperationException("Stub");
    }
}
