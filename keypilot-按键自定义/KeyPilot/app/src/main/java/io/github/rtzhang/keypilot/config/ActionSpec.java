package io.github.rtzhang.keypilot.config;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializable action binding for a single (KeyId, GestureType) slot (v1 + v2 P1).
 * 100% backward compatible with v1 JSON objects.
 */
public final class ActionSpec {

    public static final String INTENT_TYPE_ACTIVITY = "activity";
    public static final String INTENT_TYPE_BROADCAST = "broadcast";
    public static final String INTENT_TYPE_SERVICE = "service";

    // Shutter 3-tier fallback constants (Safety Switch #5)
    public static final String SHUTTER_TIER_AUTO = "auto";
    public static final String SHUTTER_TIER_SYSTEM_KEY = "system_key";
    public static final String SHUTTER_TIER_A11Y_NODE = "a11y_node";
    public static final String SHUTTER_TIER_A11Y_COORD = "a11y_coord";

    public static final String SHUTTER_KEY_AUTO = "auto";
    public static final String SHUTTER_KEY_VOLUME_DOWN = "volume_down";
    public static final String SHUTTER_KEY_VOLUME_UP = "volume_up";
    public static final String SHUTTER_KEY_CAMERA = "keycode_camera";

    // Media transport commands
    public static final String MEDIA_CMD_TOGGLE = "toggle";
    public static final String MEDIA_CMD_PLAY = "play";
    public static final String MEDIA_CMD_PAUSE = "pause";
    public static final String MEDIA_CMD_NEXT = "next";
    public static final String MEDIA_CMD_PREV = "prev";
    public static final String MEDIA_CMD_SEEK_FORWARD = "seek_forward";
    public static final String MEDIA_CMD_SEEK_BACK = "seek_back";

    // ---- v1 fields ----
    public final ActionType type;
    public final String packageName;
    public final String className;
    public final String intentAction;
    public final String intentType;
    public final String dataUri;
    public final String label;
    public final Map<String, Object> extras;

    // ---- v2 P1 fields (optional, defaulted cleanly for v1 JSON) ----
    public final boolean passthroughDefault;
    public final String shutterTier;
    public final String shutterKey;
    public final int keyCode;
    public final int pulseMs;
    public final int repeat;
    public final String mediaCommand;
    public final int seekMs;
    public final boolean simulateMediaButton;
    public final String presetId;
    public final boolean edgeToast;

    public ActionSpec(ActionType type,
                      String packageName,
                      String className,
                      String intentAction,
                      String intentType,
                      String dataUri,
                      String label,
                      Map<String, Object> extras) {
        this(type, packageName, className, intentAction, intentType, dataUri, label, extras,
                false, null, null, 0, 35, 1, null, 15000, false, null, true);
    }

    public ActionSpec(ActionType type,
                      String packageName,
                      String className,
                      String intentAction,
                      String intentType,
                      String dataUri,
                      String label,
                      Map<String, Object> extras,
                      boolean passthroughDefault,
                      String shutterTier,
                      String shutterKey,
                      int keyCode,
                      int pulseMs,
                      int repeat,
                      String mediaCommand,
                      int seekMs,
                      boolean simulateMediaButton,
                      String presetId,
                      boolean edgeToast) {
        this.type = type != null ? type : ActionType.SYSTEM;
        this.packageName = emptyToNull(packageName);
        this.className = emptyToNull(className);
        this.intentAction = emptyToNull(intentAction);
        this.intentType = emptyToNull(intentType);
        this.dataUri = emptyToNull(dataUri);
        this.label = emptyToNull(label);
        if (extras != null && !extras.isEmpty()) {
            this.extras = Collections.unmodifiableMap(new LinkedHashMap<>(extras));
        } else {
            this.extras = Collections.emptyMap();
        }
        this.passthroughDefault = passthroughDefault;
        this.shutterTier = emptyToNull(shutterTier);
        this.shutterKey = emptyToNull(shutterKey);
        this.keyCode = keyCode;
        this.pulseMs = Math.max(10, Math.min(3000, pulseMs > 0 ? pulseMs : 35));
        this.repeat = Math.max(1, Math.min(10, repeat > 0 ? repeat : 1));
        this.mediaCommand = emptyToNull(mediaCommand);
        this.seekMs = Math.max(1000, Math.min(120000, seekMs > 0 ? seekMs : 15000));
        this.simulateMediaButton = simulateMediaButton;
        this.presetId = emptyToNull(presetId);
        this.edgeToast = edgeToast;
    }

    public static ActionSpec system() {
        return new ActionSpec(ActionType.SYSTEM, null, null, null, null, null, null, null);
    }

    public static ActionSpec inherit() {
        return new ActionSpec(ActionType.INHERIT, null, null, null, null, null, null, null);
    }

    public static ActionSpec simple(ActionType type) {
        return new ActionSpec(type, null, null, null, null, null, null, null);
    }

    public static ActionSpec launchApp(String packageName, String label) {
        return new ActionSpec(ActionType.LAUNCH_APP, packageName, null, null, null, null, label, null);
    }

    public static ActionSpec launchActivity(String packageName, String className, String label) {
        return new ActionSpec(ActionType.LAUNCH_ACTIVITY, packageName, className, null, null, null, label, null);
    }

    public static ActionSpec customIntent(String intentType,
                                          String action,
                                          String packageName,
                                          String className,
                                          String dataUri,
                                          Map<String, Object> extras) {
        String normType = (intentType == null || intentType.trim().isEmpty())
                ? INTENT_TYPE_ACTIVITY : intentType.trim();
        return new ActionSpec(ActionType.CUSTOM_INTENT, packageName, className,
                action, normType, dataUri, null, extras);
    }

    public static ActionSpec cameraApp(String packageName, String label) {
        return new ActionSpec(ActionType.CAMERA, packageName, null, null, null, null, label, null);
    }

    public static ActionSpec cameraShutter(String shutterTier, String shutterKey, int pulseMs,
                                           boolean passthroughDefault) {
        return new ActionSpec(ActionType.CAMERA_SHUTTER, null, null, null, null, null, null, null,
                passthroughDefault,
                shutterTier != null ? shutterTier : SHUTTER_TIER_AUTO,
                shutterKey != null ? shutterKey : SHUTTER_KEY_AUTO,
                0, pulseMs > 0 ? pulseMs : 35, 1, null, 15000, false, null, true);
    }

    public static ActionSpec keyInject(int keyCode, int pulseMs, int repeat, String label,
                                       boolean passthroughDefault) {
        return new ActionSpec(ActionType.KEY_INJECT, null, null, null, null, null, label, null,
                passthroughDefault, null, null, keyCode, pulseMs > 0 ? pulseMs : 35,
                repeat > 0 ? repeat : 1, null, 15000, false, null, true);
    }

    public static ActionSpec mediaTransport(String command, int seekMs, String targetPackage,
                                            boolean simulateMediaButton) {
        return new ActionSpec(ActionType.MEDIA_TRANSPORT, targetPackage, null, null, null, null, null, null,
                false, null, null, 0, 35, 1,
                command != null ? command : MEDIA_CMD_TOGGLE,
                seekMs > 0 ? seekMs : 15000, simulateMediaButton, null, true);
    }

    public static ActionSpec deepLink(String uri, String packageName, String label) {
        return new ActionSpec(ActionType.DEEP_LINK, packageName, null, null, null, uri, label, null);
    }

    public static ActionSpec inAppPreset(String presetId, String label) {
        return new ActionSpec(ActionType.IN_APP_PRESET, null, null, null, null, null, label, null,
                false, null, null, 0, 35, 1, null, 15000, false, presetId, true);
    }

    public static ActionSpec contextVarBroadcast(String action, Map<String, Object> extras,
                                                 boolean passthroughDefault) {
        return new ActionSpec(ActionType.CONTEXT_VAR_BROADCAST, null, null, action,
                INTENT_TYPE_BROADCAST, null, null, extras,
                passthroughDefault, null, null, 0, 35, 1, null, 15000, false, null, true);
    }

    public ActionSpec withPassthroughDefault(boolean passthrough) {
        return new ActionSpec(type, packageName, className, intentAction, intentType, dataUri, label,
                extras, passthrough, shutterTier, shutterKey, keyCode, pulseMs, repeat,
                mediaCommand, seekMs, simulateMediaButton, presetId, edgeToast);
    }

    public boolean isSystem() {
        return type == ActionType.SYSTEM;
    }

    public boolean isInherit() {
        return type == ActionType.INHERIT;
    }

    public String summary() {
        String base;
        switch (type) {
            case LAUNCH_APP:
                if (label != null && packageName != null) {
                    base = "启动应用：" + label + " (" + packageName + ")";
                } else {
                    base = "启动应用：" + (packageName != null ? packageName : "未指定");
                }
                break;
            case LAUNCH_ACTIVITY:
                if (packageName != null && className != null) {
                    base = "Activity：" + packageName + "/" + shortenClass(packageName, className);
                } else {
                    base = "启动指定 Activity";
                }
                break;
            case CUSTOM_INTENT:
                String mode = intentType != null ? intentType : INTENT_TYPE_ACTIVITY;
                String target = intentAction != null ? intentAction : (packageName != null ? packageName : "未配置");
                base = "Intent[" + mode + "]：" + target;
                break;
            case CAMERA:
                base = packageName != null ? "相机：" + (label != null ? label : packageName) : "相机 (默认)";
                break;
            case CAMERA_SHUTTER:
                String tier = shutterTier != null ? shutterTier : SHUTTER_TIER_AUTO;
                String sk = shutterKey != null ? shutterKey : SHUTTER_KEY_AUTO;
                base = "相机快门 (" + tier + "/" + sk + " · " + pulseMs + "ms)";
                break;
            case KEY_INJECT:
                base = "注入按键：" + (label != null ? label : ("KeyCode " + keyCode))
                        + (repeat > 1 ? " ×" + repeat : "");
                break;
            case MEDIA_TRANSPORT:
                String cmd = mediaCommand != null ? mediaCommand : MEDIA_CMD_TOGGLE;
                base = "精准媒体：" + cmd
                        + (simulateMediaButton ? " [模拟键]" : "")
                        + (packageName != null ? " (" + packageName + ")" : "");
                break;
            case DEEP_LINK:
                base = "Deep Link：" + (label != null ? label : (dataUri != null ? dataUri : "未配置"));
                break;
            case IN_APP_PRESET:
                SceneTemplates.BuiltInPreset p = SceneTemplates.findPreset(presetId);
                base = "内置功能：" + (label != null ? label : (p != null ? p.title : String.valueOf(presetId)));
                break;
            case CONTEXT_VAR_BROADCAST:
                base = "前台变量广播：" + (intentAction != null ? intentAction : "io.github.rtzhang.keypilot.CONTEXT_TRIGGER");
                break;
            default:
                base = type.displayName;
                break;
        }
        return passthroughDefault ? base + " [不吞键]" : base;
    }

    private static String shortenClass(String pkg, String cls) {
        if (pkg != null && cls != null && cls.startsWith(pkg + ".")) {
            return cls.substring(pkg.length());
        }
        return cls;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("type", type.id);
        if (packageName != null) obj.put("package", packageName);
        if (className != null) obj.put("class", className);
        if (intentAction != null) obj.put("action", intentAction);
        if (intentType != null) obj.put("intentType", intentType);
        if (dataUri != null) obj.put("dataUri", dataUri);
        if (label != null) obj.put("label", label);
        if (!extras.isEmpty()) {
            JSONObject ex = new JSONObject();
            for (Map.Entry<String, Object> e : extras.entrySet()) {
                ex.put(e.getKey(), e.getValue());
            }
            obj.put("extras", ex);
        }
        // v2 P1 optional fields (omit defaults to keep JSON compact)
        if (passthroughDefault) obj.put("passthroughDefault", true);
        if (shutterTier != null) obj.put("shutterTier", shutterTier);
        if (shutterKey != null) obj.put("shutterKey", shutterKey);
        if (keyCode != 0) obj.put("keyCode", keyCode);
        if (type == ActionType.CAMERA_SHUTTER || type == ActionType.KEY_INJECT) {
            obj.put("pulseMs", pulseMs);
        }
        if (repeat > 1) obj.put("repeat", repeat);
        if (mediaCommand != null) obj.put("command", mediaCommand);
        if (type == ActionType.MEDIA_TRANSPORT && seekMs != 15000) obj.put("seekMs", seekMs);
        if (simulateMediaButton) obj.put("simulateMediaButton", true);
        if (presetId != null) obj.put("presetId", presetId);
        if (!edgeToast) obj.put("edgeToast", false);
        return obj;
    }

    public static ActionSpec fromJson(JSONObject obj) {
        if (obj == null) {
            return system();
        }
        ActionType type = ActionType.fromId(obj.optString("type", ActionType.SYSTEM.id));
        String pkg = emptyToNull(obj.optString("package", null));
        String cls = emptyToNull(obj.optString("class", null));
        String action = emptyToNull(obj.optString("action", null));
        String intentType = emptyToNull(obj.optString("intentType", null));
        String dataUri = emptyToNull(obj.optString("dataUri", null));
        if (dataUri == null) {
            // Also accept "uri" alias used in V2-PLAN deep_link examples
            dataUri = emptyToNull(obj.optString("uri", null));
        }
        String label = emptyToNull(obj.optString("label", null));
        Map<String, Object> extras = new LinkedHashMap<>();
        JSONObject ex = obj.optJSONObject("extras");
        if (ex != null) {
            Iterator<String> it = ex.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = ex.opt(k);
                if (v != null && v != JSONObject.NULL) {
                    extras.put(k, v);
                }
            }
        }
        boolean passthrough = obj.optBoolean("passthroughDefault", false);
        String sTier = emptyToNull(obj.optString("shutterTier", null));
        String sKey = emptyToNull(obj.optString("shutterKey", null));
        int kc = obj.optInt("keyCode", 0);
        int pulse = obj.optInt("pulseMs", 35);
        int rep = obj.optInt("repeat", 1);
        String mCmd = emptyToNull(obj.optString("command", null));
        int sMs = obj.optInt("seekMs", 15000);
        boolean simMedia = obj.optBoolean("simulateMediaButton", false);
        String pId = emptyToNull(obj.optString("presetId", null));
        boolean eToast = obj.optBoolean("edgeToast", true);

        return new ActionSpec(type, pkg, cls, action, intentType, dataUri, label, extras,
                passthrough, sTier, sKey, kc, pulse, rep, mCmd, sMs, simMedia, pId, eToast);
    }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    @Override
    public String toString() {
        return summary();
    }
}
