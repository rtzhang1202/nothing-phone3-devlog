package io.github.rtzhang.keypilot.config;

import android.view.KeyEvent;

/**
 * The four physical hardware buttons on Nothing Phone (3) (A024 / Metroid).
 * Scancodes, KeyCodes, and /dev/input/event* nodes are verified by P1/P2/P4 dumps.
 */
public enum KeyId {
    ESSENTIAL(
            "essential",
            "Essential Key",
            "event0 · scancode 250 · KEYCODE_UNKNOWN(0)",
            250,
            KeyEvent.KEYCODE_UNKNOWN,
            "/dev/input/event0"),
    POWER(
            "power",
            "电源键 (Power)",
            "event2 · scancode 116 · KEYCODE_POWER(26)",
            116,
            KeyEvent.KEYCODE_POWER,
            "/dev/input/event2"),
    VOLUME_UP(
            "volume_up",
            "音量+ (Volume Up)",
            "event0 · scancode 115 · KEYCODE_VOLUME_UP(24)",
            115,
            KeyEvent.KEYCODE_VOLUME_UP,
            "/dev/input/event0"),
    VOLUME_DOWN(
            "volume_down",
            "音量− (Volume Down)",
            "event3 · scancode 114 · KEYCODE_VOLUME_DOWN(25)",
            114,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            "/dev/input/event3");

    public final String id;
    public final String displayName;
    public final String hardwareSummary;
    public final int scanCode;
    public final int keyCode;
    public final String eventNode;

    KeyId(String id, String displayName, String hardwareSummary,
          int scanCode, int keyCode, String eventNode) {
        this.id = id;
        this.displayName = displayName;
        this.hardwareSummary = hardwareSummary;
        this.scanCode = scanCode;
        this.keyCode = keyCode;
        this.eventNode = eventNode;
    }

    public static KeyId fromId(String id) {
        if (id == null) {
            return null;
        }
        for (KeyId k : values()) {
            if (k.id.equals(id)) {
                return k;
            }
        }
        return null;
    }

    /** Match hardware scancode first (handles Essential Key whose keyCode==0), then keyCode. */
    public static KeyId fromKeyEvent(KeyEvent event) {
        if (event == null) {
            return null;
        }
        int sc = event.getScanCode();
        if (sc == 250) return ESSENTIAL;
        if (sc == 116) return POWER;
        if (sc == 115) return VOLUME_UP;
        if (sc == 114) return VOLUME_DOWN;

        int kc = event.getKeyCode();
        if (kc == KeyEvent.KEYCODE_POWER) return POWER;
        if (kc == KeyEvent.KEYCODE_VOLUME_UP) return VOLUME_UP;
        if (kc == KeyEvent.KEYCODE_VOLUME_DOWN) return VOLUME_DOWN;
        return null;
    }
}
