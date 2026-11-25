package de.robv.android.xposed;

import java.lang.reflect.Field;

/** Compile-time stub matching XposedBridgeApi-82. Never packaged into classes.dex. */
public final class XposedHelpers {
    XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException("Stub");
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException("Stub");
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        throw new UnsupportedOperationException("Stub");
    }

    public static Field findFieldIfExists(Class<?> clazz, String fieldName) {
        throw new UnsupportedOperationException("Stub");
    }

    public static Field findFirstFieldByExactType(Class<?> clazz, Class<?> type) {
        throw new UnsupportedOperationException("Stub");
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("Stub");
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            String className, ClassLoader classLoader, String methodName,
            Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("Stub");
    }

    public static Object getObjectField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("Stub");
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        throw new UnsupportedOperationException("Stub");
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("Stub");
    }

    public static int getIntField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("Stub");
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        throw new UnsupportedOperationException("Stub");
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        throw new UnsupportedOperationException("Stub");
    }

    public static Object callMethod(
            Object obj, String methodName, Class<?>[] parameterTypes, Object... args) {
        throw new UnsupportedOperationException("Stub");
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        throw new UnsupportedOperationException("Stub");
    }

    public static Object callStaticMethod(
            Class<?> clazz, String methodName, Class<?>[] parameterTypes, Object... args) {
        throw new UnsupportedOperationException("Stub");
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        throw new UnsupportedOperationException("Stub");
    }
}
