package io.github.rtzhang.keypilot.config;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.EnumMap;
import java.util.Map;

/**
 * Per-app / context-aware override profile.
 * Any (KeyId, GestureType) not explicitly bound (or set to INHERIT) falls back
 * to lower-priority profiles and finally to the global v1 4x3 keys matrix.
 */
public final class ActionProfile {

    public String id;
    public String name;
    public boolean enabled;
    public int priority;
    public boolean experimental;
    public ContextMatcher match;
    private final EnumMap<KeyId, EnumMap<GestureType, ActionSpec>> bindings;

    public ActionProfile(String id, String name, boolean enabled, int priority,
                         ContextMatcher match, boolean experimental) {
        this.id = (id != null && !id.trim().isEmpty())
                ? id.trim() : ("prof_" + System.currentTimeMillis());
        this.name = (name != null && !name.trim().isEmpty()) ? name.trim() : "未命名场景";
        this.enabled = enabled;
        this.priority = priority;
        this.experimental = experimental;
        this.match = match != null ? match : ContextMatcher.any();
        this.bindings = new EnumMap<>(KeyId.class);
    }

    /** Returns overridden ActionSpec or null if this slot inherits from lower/global layers. */
    public ActionSpec getBinding(KeyId key, GestureType gesture) {
        EnumMap<GestureType, ActionSpec> gMap = bindings.get(key);
        if (gMap == null) {
            return null;
        }
        ActionSpec spec = gMap.get(gesture);
        if (spec == null || spec.isInherit()) {
            return null;
        }
        return spec;
    }

    public void setBinding(KeyId key, GestureType gesture, ActionSpec spec) {
        if (key == null || gesture == null) return;
        if (spec == null || spec.isInherit()) {
            EnumMap<GestureType, ActionSpec> gMap = bindings.get(key);
            if (gMap != null) {
                gMap.remove(gesture);
                if (gMap.isEmpty()) {
                    bindings.remove(key);
                }
            }
            return;
        }
        EnumMap<GestureType, ActionSpec> gMap = bindings.get(key);
        if (gMap == null) {
            gMap = new EnumMap<>(GestureType.class);
            bindings.put(key, gMap);
        }
        gMap.put(gesture, spec);
    }

    public int overrideCount() {
        int c = 0;
        for (EnumMap<GestureType, ActionSpec> gm : bindings.values()) {
            for (ActionSpec s : gm.values()) {
                if (s != null && !s.isInherit()) c++;
            }
        }
        return c;
    }

    public String overrideSummary() {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (KeyId k : KeyId.values()) {
            EnumMap<GestureType, ActionSpec> gm = bindings.get(k);
            if (gm == null) continue;
            for (GestureType g : GestureType.values()) {
                ActionSpec s = gm.get(g);
                if (s == null || s.isInherit()) continue;
                if (count > 0) sb.append(" · ");
                if (count >= 3) {
                    sb.append("…等 ").append(overrideCount()).append(" 项");
                    return sb.toString();
                }
                sb.append(shortKeyLabel(k)).append(g.displayName).append("→").append(s.type.displayName);
                count++;
            }
        }
        return count == 0 ? "（未设置覆写，全部继承全局）" : sb.toString();
    }

    private static String shortKeyLabel(KeyId k) {
        switch (k) {
            case ESSENTIAL: return "EK";
            case POWER: return "电源";
            case VOLUME_UP: return "音量+";
            case VOLUME_DOWN: return "音量−";
            default: return k.id;
        }
    }

    public ActionProfile copy() {
        try {
            return fromJson(toJson());
        } catch (Throwable ignored) {
            return new ActionProfile(id, name, enabled, priority, match, experimental);
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("name", name);
        obj.put("enabled", enabled);
        obj.put("priority", priority);
        if (experimental) {
            obj.put("experimental", true);
        }
        obj.put("match", match.toJson());

        JSONObject bindObj = new JSONObject();
        for (Map.Entry<KeyId, EnumMap<GestureType, ActionSpec>> ke : bindings.entrySet()) {
            EnumMap<GestureType, ActionSpec> gm = ke.getValue();
            if (gm == null || gm.isEmpty()) continue;
            JSONObject gObj = new JSONObject();
            for (Map.Entry<GestureType, ActionSpec> ge : gm.entrySet()) {
                if (ge.getValue() != null && !ge.getValue().isInherit()) {
                    gObj.put(ge.getKey().id, ge.getValue().toJson());
                }
            }
            if (gObj.length() > 0) {
                bindObj.put(ke.getKey().id, gObj);
            }
        }
        obj.put("bindings", bindObj);
        return obj;
    }

    public static ActionProfile fromJson(JSONObject obj) {
        if (obj == null) {
            return null;
        }
        String id = obj.optString("id", "prof_" + System.currentTimeMillis());
        String name = obj.optString("name", "未命名场景");
        boolean enabled = obj.optBoolean("enabled", true);
        int priority = obj.optInt("priority", 100);
        boolean exp = obj.optBoolean("experimental", false);
        ContextMatcher match = ContextMatcher.fromJson(obj.optJSONObject("match"));

        ActionProfile p = new ActionProfile(id, name, enabled, priority, match, exp);
        JSONObject bindObj = obj.optJSONObject("bindings");
        if (bindObj != null) {
            for (KeyId k : KeyId.values()) {
                JSONObject gObj = bindObj.optJSONObject(k.id);
                if (gObj == null) continue;
                for (GestureType g : GestureType.values()) {
                    JSONObject aObj = gObj.optJSONObject(g.id);
                    if (aObj != null) {
                        p.setBinding(k, g, ActionSpec.fromJson(aObj));
                    }
                }
            }
        }
        return p;
    }
}
