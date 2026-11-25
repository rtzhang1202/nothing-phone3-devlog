package io.github.rtzhang.keypilot.config;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import io.github.rtzhang.keypilot.hook.ContextSnapshot;

/**
 * Matches foreground app package (exact or wildcard '*'), excluded packages,
 * optional activity regex, keyguard state, and screen interactive state.
 */
public final class ContextMatcher {

    public static final String KEYGUARD_ANY = "any";
    public static final String KEYGUARD_UNLOCKED_ONLY = "unlocked_only";
    public static final String KEYGUARD_LOCKED_ONLY = "locked_only";

    public static final String SCREEN_ANY = "any";
    public static final String SCREEN_ON_ONLY = "on_only";
    public static final String SCREEN_OFF_ONLY = "off_only";

    public final List<String> packages;
    public final List<String> excludePackages;
    public final String activityRegex;
    public final String keyguard;
    public final String screen;

    private volatile Pattern compiledActivityPattern;
    private volatile boolean patternCompileAttempted;

    public ContextMatcher(List<String> packages,
                          List<String> excludePackages,
                          String activityRegex,
                          String keyguard,
                          String screen) {
        this.packages = cleanList(packages);
        this.excludePackages = cleanList(excludePackages);
        this.activityRegex = activityRegex != null ? activityRegex.trim() : "";
        this.keyguard = normalizeKeyguard(keyguard);
        this.screen = normalizeScreen(screen);
    }

    public static ContextMatcher forPackages(List<String> packages) {
        return new ContextMatcher(packages, null, "", KEYGUARD_ANY, SCREEN_ON_ONLY);
    }

    public static ContextMatcher any() {
        return new ContextMatcher(null, null, "", KEYGUARD_ANY, SCREEN_ANY);
    }

    public boolean matches(ContextSnapshot snap) {
        if (snap == null) {
            return false;
        }
        // 1. Screen constraint
        if (SCREEN_ON_ONLY.equals(screen) && !snap.isInteractive) {
            return false;
        }
        if (SCREEN_OFF_ONLY.equals(screen) && snap.isInteractive) {
            return false;
        }

        // 2. Keyguard constraint
        if (KEYGUARD_UNLOCKED_ONLY.equals(keyguard) && snap.isKeyguardLocked) {
            return false;
        }
        if (KEYGUARD_LOCKED_ONLY.equals(keyguard) && !snap.isKeyguardLocked) {
            return false;
        }

        // 3. Exclude packages check
        String topPkg = snap.topPackage != null ? snap.topPackage : "";
        if (!excludePackages.isEmpty() && matchesAnyPattern(topPkg, excludePackages)) {
            return false;
        }

        // 4. Include packages check (empty or "*" matches any foreground app)
        if (!packages.isEmpty() && !packages.contains("*")) {
            if (topPkg.isEmpty() || !matchesAnyPattern(topPkg, packages)) {
                return false;
            }
        }

        // 5. Optional activity regex
        if (!activityRegex.isEmpty()) {
            Pattern p = getActivityPattern();
            if (p != null) {
                String act = snap.topActivity != null ? snap.topActivity : "";
                if (!p.matcher(act).find()) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean matchesAnyPattern(String value, List<String> patterns) {
        if (value == null || patterns == null) {
            return false;
        }
        for (String pat : patterns) {
            if (matchesGlob(value, pat)) {
                return true;
            }
        }
        return false;
    }

    /** Matches exact string or glob with '*' wildcards (e.g., "com.google.android.GoogleCamera*"). */
    public static boolean matchesGlob(String value, String pattern) {
        if (value == null || pattern == null || pattern.isEmpty()) {
            return false;
        }
        if ("*".equals(pattern)) {
            return true;
        }
        if (pattern.indexOf('*') < 0) {
            return pattern.equals(value);
        }
        // Simple prefix/suffix/multi-star glob match without regex allocation
        String[] parts = pattern.split("\\*", -1);
        int pos = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                continue;
            }
            if (i == 0) {
                if (!value.startsWith(part)) return false;
                pos = part.length();
            } else if (i == parts.length - 1) {
                if (!value.endsWith(part) || value.length() - part.length() < pos) return false;
                pos = value.length();
            } else {
                int idx = value.indexOf(part, pos);
                if (idx < 0) return false;
                pos = idx + part.length();
            }
        }
        return true;
    }

    private Pattern getActivityPattern() {
        if (!patternCompileAttempted) {
            patternCompileAttempted = true;
            try {
                compiledActivityPattern = Pattern.compile(activityRegex);
            } catch (Throwable ignored) {
                compiledActivityPattern = null;
            }
        }
        return compiledActivityPattern;
    }

    public String summary() {
        String pkgStr;
        if (packages.isEmpty() || packages.contains("*")) {
            pkgStr = "任意应用";
        } else if (packages.size() <= 3) {
            pkgStr = join(packages, ", ");
        } else {
            pkgStr = packages.get(0) + ", " + packages.get(1) + " 等 " + packages.size() + " 个应用";
        }
        StringBuilder sb = new StringBuilder(pkgStr);
        if (!excludePackages.isEmpty()) {
            sb.append(" (排除 ").append(excludePackages.size()).append(")");
        }
        if (KEYGUARD_UNLOCKED_ONLY.equals(keyguard)) sb.append(" · 仅解锁");
        else if (KEYGUARD_LOCKED_ONLY.equals(keyguard)) sb.append(" · 仅锁屏");
        if (SCREEN_ON_ONLY.equals(screen)) sb.append(" · 亮屏");
        else if (SCREEN_OFF_ONLY.equals(screen)) sb.append(" · 息屏");
        return sb.toString();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        JSONArray pkgs = new JSONArray();
        for (String p : packages) pkgs.put(p);
        obj.put("packages", pkgs);

        if (!excludePackages.isEmpty()) {
            JSONArray ex = new JSONArray();
            for (String p : excludePackages) ex.put(p);
            obj.put("excludePackages", ex);
        }
        if (!activityRegex.isEmpty()) {
            obj.put("activityRegex", activityRegex);
        }
        obj.put("keyguard", keyguard);
        obj.put("screen", screen);
        return obj;
    }

    public static ContextMatcher fromJson(JSONObject obj) {
        if (obj == null) {
            return any();
        }
        List<String> pkgs = readStringArray(obj.optJSONArray("packages"));
        List<String> ex = readStringArray(obj.optJSONArray("excludePackages"));
        String actRx = obj.optString("activityRegex", "");
        String kg = obj.optString("keyguard", KEYGUARD_ANY);
        String scr = obj.optString("screen", SCREEN_ANY);
        return new ContextMatcher(pkgs, ex, actRx, kg, scr);
    }

    private static List<String> readStringArray(JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "").trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static List<String> cleanList(List<String> in) {
        if (in == null || in.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (s != null) {
                String t = s.trim();
                if (!t.isEmpty() && !out.contains(t)) {
                    out.add(t);
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static String normalizeKeyguard(String kg) {
        if (KEYGUARD_UNLOCKED_ONLY.equals(kg)) return KEYGUARD_UNLOCKED_ONLY;
        if (KEYGUARD_LOCKED_ONLY.equals(kg)) return KEYGUARD_LOCKED_ONLY;
        return KEYGUARD_ANY;
    }

    private static String normalizeScreen(String scr) {
        if (SCREEN_ON_ONLY.equals(scr)) return SCREEN_ON_ONLY;
        if (SCREEN_OFF_ONLY.equals(scr)) return SCREEN_OFF_ONLY;
        return SCREEN_ANY;
    }

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
