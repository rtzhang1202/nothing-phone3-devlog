package io.github.rtzhang.keypilot.hook;

/**
 * Lock-free immutable snapshot of current foreground app & device interaction state
 * captured inside system_server when a hardware key event arrives.
 */
public final class ContextSnapshot {

    public static final ContextSnapshot EMPTY =
            new ContextSnapshot("", "", false, true, false, false, 0L);

    public final String topPackage;
    public final String topActivity;
    public final boolean isKeyguardLocked;
    public final boolean isInteractive;
    public final boolean isInMultiWindow;
    public final boolean isExternalDevice;
    public final long updatedAtMs;

    public ContextSnapshot(String topPackage,
                           String topActivity,
                           boolean isKeyguardLocked,
                           boolean isInteractive,
                           boolean isInMultiWindow,
                           boolean isExternalDevice,
                           long updatedAtMs) {
        this.topPackage = topPackage != null ? topPackage : "";
        this.topActivity = topActivity != null ? topActivity : "";
        this.isKeyguardLocked = isKeyguardLocked;
        this.isInteractive = isInteractive;
        this.isInMultiWindow = isInMultiWindow;
        this.isExternalDevice = isExternalDevice;
        this.updatedAtMs = updatedAtMs;
    }

    @Override
    public String toString() {
        return "ctx{pkg=" + topPackage
                + ", act=" + topActivity
                + ", locked=" + isKeyguardLocked
                + ", screenOn=" + isInteractive
                + ", mw=" + isInMultiWindow
                + ", ext=" + isExternalDevice + "}";
    }
}
