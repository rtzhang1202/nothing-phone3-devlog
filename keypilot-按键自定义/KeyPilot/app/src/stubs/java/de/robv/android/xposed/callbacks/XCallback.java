package de.robv.android.xposed.callbacks;

import java.io.Serializable;

/** Compile-time stub matching XposedBridgeApi-82. Never packaged into classes.dex. */
public abstract class XCallback implements Comparable<XCallback> {
    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOWEST = -10000;
    public static final int PRIORITY_HIGHEST = 10000;

    public final int priority;

    protected XCallback() {
        this.priority = PRIORITY_DEFAULT;
    }

    protected XCallback(int priority) {
        this.priority = priority;
    }

    @Override
    public int compareTo(XCallback other) {
        if (this == other) return 0;
        return other.priority - this.priority;
    }

    public abstract static class Param {
        public final Object[] callbacks;

        protected Param() {
            this.callbacks = null;
        }

        public Object getObjectExtra(String key) {
            throw new UnsupportedOperationException("Stub");
        }

        public void setObjectExtra(String key, Object o) {
            throw new UnsupportedOperationException("Stub");
        }

        public Serializable getExtra() {
            throw new UnsupportedOperationException("Stub");
        }
    }
}
