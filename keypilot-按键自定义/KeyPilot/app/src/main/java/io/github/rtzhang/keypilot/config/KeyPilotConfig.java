package io.github.rtzhang.keypilot.config;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;

/**
 * Top-level configuration model & JSON v2 protocol codec shared between the KeyPilot UI app
 * and the system_server Xposed hook.
 *
 * 100% backward compatible with v1 configs (version=1 without profiles/blacklist/safety switches).
 */
public final class KeyPilotConfig {

    public static final int SCHEMA_VERSION = 2;
    public static final String MODULE_PKG = "io.github.rtzhang.keypilot";
    public static final String ACTION_CONFIG_UPDATE = "io.github.rtzhang.keypilot.CONFIG_UPDATE";
    public static final String EXTRA_CONFIG_JSON = "config_json";
    public static final String EXTRA_VERSION = "version";
    public static final String EXTRA_TIMESTAMP = "timestamp";
    public static final String SYSTEM_CONFIG_PATH = "/data/system/keypilot.json";
    public static final String APP_CONFIG_FILENAME = "keypilot_config.json";

    // ---- CONFIG_UPDATE bridge commands (docs/DESIGN.md §3.5) ----
    /** When present the broadcast is a command instead of a full config payload. */
    public static final String EXTRA_COMMAND = "command";
    /** Try-launch a (possibly non-exported) activity from system_server. */
    public static final String CMD_TRY_LAUNCH = "TRY_LAUNCH";
    /** Push the activity label map to the hook-side mirror file. */
    public static final String CMD_LABELS_UPDATE = "LABELS_UPDATE";
    public static final String EXTRA_TRY_PACKAGE = "try_package";
    public static final String EXTRA_TRY_CLASS = "try_class";
    public static final String EXTRA_TRY_TOKEN = "try_token";
    public static final String EXTRA_LABELS_JSON = "labels_json";
    /** Hook-side mirror of the activity labels (system_server uid=1000). */
    public static final String SYSTEM_LABELS_PATH = "/data/system/keypilot_labels.json";
    /** StatusProvider.call() method used by the hook to return a TRY_LAUNCH result. */
    public static final String METHOD_TRY_LAUNCH_RESULT = "try_launch_result";

    public static final int DEFAULT_LONG_PRESS_MS = 500;
    public static final int DEFAULT_DOUBLE_PRESS_WINDOW_MS = 300;

    // Nothing OS 5.1 official Essential Key remapping coexistence policies (Safety Switch #6)
    public static final String POLICY_TAKEOVER = "takeover";
    public static final String POLICY_YIELD_SINGLE_CLICK = "yield_single_click";
    public static final String POLICY_YIELD_ALL = "yield_all";

    // ---- v1 fields (preserved intact) ----
    public boolean masterEnabled;
    public boolean showToast;
    public boolean hapticFeedback;
    public boolean preserveImeVoice;
    public int longPressMs;
    public int doublePressWindowMs;

    /**
     * Safety redline #1: Power long press is ALWAYS passed through to native GlobalActions
     * unless the user explicitly confirmed overriding it in the UI (or set this flag true).
     */
    public boolean powerLongPressExplicitlyBound;

    public long updatedAt;
    private final EnumMap<KeyId, KeyBindings> keys;

    // ---- v2 P1 fields: 6 safety switches + ordered per-app profiles ----
    public boolean allowWhenScreenOff;
    public boolean allowWhenKeyguardLocked;
    public String officialRemapPolicy;
    public final List<String> blacklistPackages;
    public final List<ActionProfile> profiles;

    public static final class KeyBindings {
        public boolean enabled;
        private final EnumMap<GestureType, ActionSpec> gestures;

        public KeyBindings(boolean enabled) {
            this.enabled = enabled;
            this.gestures = new EnumMap<>(GestureType.class);
            for (GestureType g : GestureType.values()) {
                this.gestures.put(g, ActionSpec.system());
            }
        }

        public ActionSpec get(GestureType gesture) {
            ActionSpec s = gestures.get(gesture);
            return s != null ? s : ActionSpec.system();
        }

        public void set(GestureType gesture, ActionSpec spec) {
            gestures.put(gesture, spec != null ? spec : ActionSpec.system());
        }

        public boolean hasAnyCustomAction() {
            if (!enabled) {
                return false;
            }
            for (GestureType g : GestureType.values()) {
                if (!get(g).isSystem()) {
                    return true;
                }
            }
            return false;
        }
    }

    public KeyPilotConfig() {
        this.masterEnabled = true;
        this.showToast = true;
        this.hapticFeedback = true;
        this.preserveImeVoice = false;
        this.longPressMs = DEFAULT_LONG_PRESS_MS;
        this.doublePressWindowMs = DEFAULT_DOUBLE_PRESS_WINDOW_MS;
        this.powerLongPressExplicitlyBound = false;
        this.updatedAt = System.currentTimeMillis();
        this.keys = new EnumMap<>(KeyId.class);
        for (KeyId k : KeyId.values()) {
            this.keys.put(k, new KeyBindings(true));
        }
        // v2 P1 safe defaults (preserve v1 behavior identically)
        this.allowWhenScreenOff = true;
        this.allowWhenKeyguardLocked = true;
        this.officialRemapPolicy = POLICY_TAKEOVER;
        this.blacklistPackages = new ArrayList<>();
        this.profiles = new ArrayList<>();
    }

    /** Safe default: every key and every gesture passes through to native system behavior. */
    public static KeyPilotConfig createDefault() {
        return new KeyPilotConfig();
    }

    /**
     * v1 TESTPLAN preset (preserved 100% unchanged for v1 regression testing).
     */
    public static KeyPilotConfig createTestPlanPreset() {
        KeyPilotConfig cfg = createDefault();
        cfg.setAction(KeyId.ESSENTIAL, GestureType.SHORT, ActionSpec.simple(ActionType.CAMERA));
        cfg.setAction(KeyId.ESSENTIAL, GestureType.LONG, ActionSpec.simple(ActionType.FLASHLIGHT));
        cfg.setAction(KeyId.ESSENTIAL, GestureType.DOUBLE,
                ActionSpec.launchApp("com.google.android.calculator", "计算器"));
        cfg.setAction(KeyId.VOLUME_UP, GestureType.DOUBLE, ActionSpec.simple(ActionType.SCREENSHOT));
        cfg.setAction(KeyId.VOLUME_DOWN, GestureType.DOUBLE, ActionSpec.simple(ActionType.MEDIA_PLAY_PAUSE));
        cfg.setAction(KeyId.POWER, GestureType.DOUBLE,
                ActionSpec.launchApp(MODULE_PKG, "KeyPilot"));
        cfg.powerLongPressExplicitlyBound = false;
        return cfg;
    }

    /**
     * v2 P1 Context-Aware TESTPLAN preset:
     * - Global EK short -> Open Camera; Inside Camera -> EK short = Shutter (prof_camera_shutter)
     * - Global EK double -> Alipay Scan (A6 built-in preset); EK long -> Flashlight
     * - Vol+ double -> Screenshot; Vol- double -> Media Transport toggle; Power double -> KeyPilot
     * - Profiles: Camera Shutter (B4), Reader Page Turn (B1), Music Track Skip (B5), Game Bypass (B6)
     */
    public static KeyPilotConfig createV2ContextPreset() {
        KeyPilotConfig cfg = createTestPlanPreset();
        cfg.setAction(KeyId.ESSENTIAL, GestureType.DOUBLE,
                ActionSpec.inAppPreset("alipay_scan", "支付宝：扫一扫"));
        cfg.setAction(KeyId.VOLUME_DOWN, GestureType.DOUBLE,
                ActionSpec.mediaTransport(ActionSpec.MEDIA_CMD_TOGGLE, 15000, null, true));
        cfg.profiles.clear();
        cfg.profiles.addAll(SceneTemplates.createV2DefaultProfiles());
        cfg.sortProfilesByPriority();
        return cfg;
    }

    public KeyBindings getKey(KeyId keyId) {
        KeyBindings kb = keys.get(keyId);
        if (kb == null) {
            kb = new KeyBindings(true);
            keys.put(keyId, kb);
        }
        return kb;
    }

    /** v1 global effective action lookup (preserved for backward compatibility & global fallback). */
    public ActionSpec getEffectiveAction(KeyId keyId, GestureType gesture) {
        if (!masterEnabled) {
            return ActionSpec.system();
        }
        KeyBindings kb = getKey(keyId);
        if (!kb.enabled) {
            return ActionSpec.system();
        }
        // Safety redline #1: Power long press stays native unless explicitly confirmed
        if (keyId == KeyId.POWER && gesture == GestureType.LONG && !powerLongPressExplicitlyBound) {
            return ActionSpec.system();
        }
        return kb.get(gesture);
    }

    public boolean shouldInterceptKey(KeyId keyId) {
        if (!masterEnabled) {
            return false;
        }
        KeyBindings kb = getKey(keyId);
        if (!kb.enabled) {
            return false;
        }
        for (GestureType g : GestureType.values()) {
            if (!getEffectiveAction(keyId, g).isSystem()) {
                return true;
            }
        }
        // Also check if any enabled profile overrides this key
        for (ActionProfile p : profiles) {
            if (!p.enabled) continue;
            for (GestureType g : GestureType.values()) {
                ActionSpec s = p.getBinding(keyId, g);
                if (s != null && !s.isSystem() && !s.isInherit()) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isBlacklisted(String pkg) {
        if (pkg == null || pkg.isEmpty() || blacklistPackages.isEmpty()) {
            return false;
        }
        return ContextMatcher.matchesAnyPattern(pkg, blacklistPackages);
    }

    public void sortProfilesByPriority() {
        Collections.sort(profiles, new Comparator<ActionProfile>() {
            @Override
            public int compare(ActionProfile a, ActionProfile b) {
                return Integer.compare(b.priority, a.priority);
            }
        });
    }

    public void setAction(KeyId keyId, GestureType gesture, ActionSpec spec) {
        getKey(keyId).set(gesture, spec);
        if (keyId == KeyId.POWER && gesture == GestureType.LONG) {
            if (spec == null || spec.isSystem()) {
                powerLongPressExplicitlyBound = false;
            }
        }
        this.updatedAt = System.currentTimeMillis();
    }

    public void setKeyEnabled(KeyId keyId, boolean enabled) {
        getKey(keyId).enabled = enabled;
        this.updatedAt = System.currentTimeMillis();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("version", SCHEMA_VERSION);
        root.put("masterEnabled", masterEnabled);
        root.put("showToast", showToast);
        root.put("hapticFeedback", hapticFeedback);
        root.put("preserveImeVoice", preserveImeVoice);
        root.put("longPressMs", clamp(longPressMs, 250, 1500));
        root.put("doublePressWindowMs", clamp(doublePressWindowMs, 150, 600));
        root.put("powerLongPressExplicitlyBound", powerLongPressExplicitlyBound);
        root.put("allowWhenScreenOff", allowWhenScreenOff);
        root.put("allowWhenKeyguardLocked", allowWhenKeyguardLocked);
        root.put("officialRemapPolicy", normalizePolicy(officialRemapPolicy));
        root.put("updatedAt", updatedAt);

        JSONArray blArr = new JSONArray();
        for (String p : blacklistPackages) {
            if (p != null && !p.trim().isEmpty()) {
                blArr.put(p.trim());
            }
        }
        root.put("blacklistPackages", blArr);

        JSONObject keysObj = new JSONObject();
        for (KeyId k : KeyId.values()) {
            KeyBindings kb = getKey(k);
            JSONObject kObj = new JSONObject();
            kObj.put("enabled", kb.enabled);
            JSONObject gObj = new JSONObject();
            for (GestureType g : GestureType.values()) {
                gObj.put(g.id, kb.get(g).toJson());
            }
            kObj.put("gestures", gObj);
            keysObj.put(k.id, kObj);
        }
        root.put("keys", keysObj);

        JSONArray profArr = new JSONArray();
        for (ActionProfile p : profiles) {
            if (p != null) {
                profArr.put(p.toJson());
            }
        }
        root.put("profiles", profArr);
        return root;
    }

    public String toJsonString(int indentSpaces) {
        try {
            return indentSpaces > 0 ? toJson().toString(indentSpaces) : toJson().toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    public static KeyPilotConfig fromJsonString(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return createDefault();
        }
        try {
            JSONObject root = new JSONObject(raw);
            return fromJson(root);
        } catch (Throwable ignored) {
            return createDefault();
        }
    }

    public static KeyPilotConfig fromJson(JSONObject root) {
        KeyPilotConfig cfg = createDefault();
        if (root == null) {
            return cfg;
        }
        cfg.masterEnabled = root.optBoolean("masterEnabled", true);
        cfg.showToast = root.optBoolean("showToast", true);
        cfg.hapticFeedback = root.optBoolean("hapticFeedback", true);
        cfg.preserveImeVoice = root.optBoolean("preserveImeVoice", false);
        cfg.longPressMs = clamp(root.optInt("longPressMs", DEFAULT_LONG_PRESS_MS), 250, 1500);
        cfg.doublePressWindowMs = clamp(
                root.optInt("doublePressWindowMs", DEFAULT_DOUBLE_PRESS_WINDOW_MS), 150, 600);
        cfg.powerLongPressExplicitlyBound = root.optBoolean("powerLongPressExplicitlyBound", false);
        cfg.allowWhenScreenOff = root.optBoolean("allowWhenScreenOff", true);
        cfg.allowWhenKeyguardLocked = root.optBoolean("allowWhenKeyguardLocked", true);
        cfg.officialRemapPolicy = normalizePolicy(root.optString("officialRemapPolicy", POLICY_TAKEOVER));
        cfg.updatedAt = root.optLong("updatedAt", System.currentTimeMillis());

        JSONArray blArr = root.optJSONArray("blacklistPackages");
        if (blArr != null) {
            for (int i = 0; i < blArr.length(); i++) {
                String p = blArr.optString(i, "").trim();
                if (!p.isEmpty() && !cfg.blacklistPackages.contains(p)) {
                    cfg.blacklistPackages.add(p);
                }
            }
        }

        JSONObject keysObj = root.optJSONObject("keys");
        if (keysObj != null) {
            for (KeyId k : KeyId.values()) {
                JSONObject kObj = keysObj.optJSONObject(k.id);
                if (kObj == null) continue;
                KeyBindings kb = cfg.getKey(k);
                kb.enabled = kObj.optBoolean("enabled", true);
                JSONObject gObj = kObj.optJSONObject("gestures");
                if (gObj != null) {
                    for (GestureType g : GestureType.values()) {
                        JSONObject aObj = gObj.optJSONObject(g.id);
                        if (aObj != null) {
                            kb.set(g, ActionSpec.fromJson(aObj));
                        }
                    }
                }
            }
        }

        JSONArray profArr = root.optJSONArray("profiles");
        if (profArr != null) {
            for (int i = 0; i < profArr.length(); i++) {
                ActionProfile p = ActionProfile.fromJson(profArr.optJSONObject(i));
                if (p != null) {
                    cfg.profiles.add(p);
                }
            }
            cfg.sortProfilesByPriority();
        }

        // Enforce invariant: if power.long is system everywhere, clear explicit flag
        if (cfg.getKey(KeyId.POWER).get(GestureType.LONG).isSystem()
                && !cfg.hasProfilePowerLongOverride()) {
            cfg.powerLongPressExplicitlyBound = false;
        }
        return cfg;
    }

    private boolean hasProfilePowerLongOverride() {
        for (ActionProfile p : profiles) {
            ActionSpec s = p.getBinding(KeyId.POWER, GestureType.LONG);
            if (s != null && !s.isSystem() && !s.isInherit()) {
                return true;
            }
        }
        return false;
    }

    public static String normalizePolicy(String policy) {
        if (POLICY_YIELD_SINGLE_CLICK.equals(policy)) return POLICY_YIELD_SINGLE_CLICK;
        if (POLICY_YIELD_ALL.equals(policy)) return POLICY_YIELD_ALL;
        return POLICY_TAKEOVER;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public String summaryStats() {
        int bound = 0;
        for (KeyId k : KeyId.values()) {
            for (GestureType g : GestureType.values()) {
                if (!getEffectiveAction(k, g).isSystem()) {
                    bound++;
                }
            }
        }
        int activeProfiles = 0;
        for (ActionProfile p : profiles) {
            if (p.enabled) activeProfiles++;
        }
        return "v" + SCHEMA_VERSION
                + " master=" + masterEnabled
                + " bound=" + bound + "/12"
                + " profiles=" + activeProfiles + "/" + profiles.size()
                + " bl=" + blacklistPackages.size()
                + " os51=" + officialRemapPolicy
                + " pwrLongOverride=" + powerLongPressExplicitlyBound;
    }
}
