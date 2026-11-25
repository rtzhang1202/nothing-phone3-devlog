package io.github.rtzhang.keypilot.config;

/**
 * Three gestures per physical key, matching Nothing OS native thresholds:
 * long press >= 500ms, double press window <= 300ms, short press < 500ms without 2nd press.
 */
public enum GestureType {
    SHORT("short", "短按", "<500ms 无二次按压"),
    LONG("long", "长按", "持续按住 ≥500ms"),
    DOUBLE("double", "双按", "300ms 内连续按两次");

    public final String id;
    public final String displayName;
    public final String hint;

    GestureType(String id, String displayName, String hint) {
        this.id = id;
        this.displayName = displayName;
        this.hint = hint;
    }

    public static GestureType fromId(String id) {
        if (id == null) {
            return null;
        }
        for (GestureType g : values()) {
            if (g.id.equals(id)) {
                return g;
            }
        }
        return null;
    }
}
