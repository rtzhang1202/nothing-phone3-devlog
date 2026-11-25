package io.github.rtzhang.keypilot.hook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import io.github.rtzhang.keypilot.config.ActionProfile;
import io.github.rtzhang.keypilot.config.ActionSpec;
import io.github.rtzhang.keypilot.config.GestureType;
import io.github.rtzhang.keypilot.config.KeyId;
import io.github.rtzhang.keypilot.config.KeyPilotConfig;

/**
 * Deterministic v2 P1 action resolver:
 * 1. Master switch / key switch / Power-Long safety redline #1
 * 2. External device guard (Bluetooth selfie stick volume passthrough)
 * 3. Safety Switch #1: Global App Blacklist (0ms passthrough)
 * 4. Ordered ActionProfiles (highest priority first; un-overridden slots fall through)
 * 5. Safety Switch #2: Screen-off / Keyguard global gates
 * 6. Safety Switch #6: Nothing OS 5.1 Official Essential Key Remap coexistence policy
 * 7. v1 Global 4x3 keys matrix fallback
 */
public final class ProfileResolver {

    public static final class Resolution {
        public final ActionSpec action;
        public final String source;
        public final boolean matchedProfile;

        public Resolution(ActionSpec action, String source, boolean matchedProfile) {
            this.action = action != null ? action : ActionSpec.system();
            this.source = source != null ? source : "global";
            this.matchedProfile = matchedProfile;
        }
    }

    private ProfileResolver() {
    }

    public static Resolution resolve(KeyPilotConfig cfg,
                                     KeyId key,
                                     GestureType gesture,
                                     ContextSnapshot snap) {
        if (cfg == null || key == null || gesture == null) {
            return new Resolution(ActionSpec.system(), "null_guard", false);
        }
        ContextSnapshot ctx = snap != null ? snap : ContextSnapshot.EMPTY;

        // 1. Master & per-key enable gates
        if (!cfg.masterEnabled || !cfg.getKey(key).enabled) {
            return new Resolution(ActionSpec.system(), "disabled", false);
        }

        // 2. Safety Redline #1: Power Long Press ALWAYS native unless explicitly confirmed
        if (key == KeyId.POWER && gesture == GestureType.LONG && !cfg.powerLongPressExplicitlyBound) {
            return new Resolution(ActionSpec.system(), "power_long_redline", false);
        }

        // 3. External Bluetooth shutter / keyboard volume key passthrough guard (Q11)
        if (ctx.isExternalDevice && (key == KeyId.VOLUME_UP || key == KeyId.VOLUME_DOWN)) {
            return new Resolution(ActionSpec.system(), "external_device", false);
        }

        // 4. Safety Switch #1: Global App Blacklist (bank / payment / game 0ms bypass)
        if (cfg.isBlacklisted(ctx.topPackage)) {
            return new Resolution(ActionSpec.system(), "blacklist:" + ctx.topPackage, false);
        }

        // 5. Safety Switch #6 (yield_all): completely yield Essential Key to Nothing OS 5.1 official remap
        if (key == KeyId.ESSENTIAL && KeyPilotConfig.POLICY_YIELD_ALL.equals(cfg.officialRemapPolicy)) {
            return new Resolution(ActionSpec.system(), "official_yield_all", false);
        }

        // 6. Ordered per-app ActionProfiles (highest priority first)
        if (!cfg.profiles.isEmpty()) {
            List<ActionProfile> ordered = new ArrayList<>(cfg.profiles);
            Collections.sort(ordered, new Comparator<ActionProfile>() {
                @Override
                public int compare(ActionProfile a, ActionProfile b) {
                    return Integer.compare(b.priority, a.priority);
                }
            });
            for (ActionProfile p : ordered) {
                if (p == null || !p.enabled || p.match == null) continue;
                if (!p.match.matches(ctx)) continue;
                ActionSpec override = p.getBinding(key, gesture);
                if (override != null && !override.isInherit()) {
                    // Re-verify Safety Redline #1 even inside profile
                    if (key == KeyId.POWER && gesture == GestureType.LONG
                            && !cfg.powerLongPressExplicitlyBound) {
                        return new Resolution(ActionSpec.system(), "power_long_redline", false);
                    }
                    return new Resolution(override, "profile:" + p.id, true);
                }
            }
        }

        // 7. Safety Switch #2: Global Screen-Off & Keyguard gates (for global fallback actions)
        if (!ctx.isInteractive && !cfg.allowWhenScreenOff) {
            return new Resolution(ActionSpec.system(), "screen_off_gate", false);
        }
        if (ctx.isKeyguardLocked && !cfg.allowWhenKeyguardLocked) {
            return new Resolution(ActionSpec.system(), "keyguard_gate", false);
        }

        // 8. Safety Switch #6 (yield_single_click):
        // When NOT matched by any per-app profile (e.g., outside Camera), yield EK single-click
        // to Nothing OS 5.1 official remap while keeping per-app overrides & long/double active!
        if (key == KeyId.ESSENTIAL
                && gesture == GestureType.SHORT
                && KeyPilotConfig.POLICY_YIELD_SINGLE_CLICK.equals(cfg.officialRemapPolicy)) {
            return new Resolution(ActionSpec.system(), "official_yield_single", false);
        }

        // 9. v1 Global 4x3 keys fallback
        return new Resolution(cfg.getEffectiveAction(key, gesture), "global", false);
    }

    /**
     * Context-aware fast check at key DOWN entry: returns false (0ms native passthrough!)
     * if all 3 gestures for this key resolve to SYSTEM in the current foreground context
     * (e.g., blacklisted app, game bypass profile, screen-off gate, or yield_all).
     */
    public static boolean shouldInterceptKey(KeyPilotConfig cfg, KeyId key, ContextSnapshot snap) {
        if (cfg == null || key == null || !cfg.masterEnabled || !cfg.getKey(key).enabled) {
            return false;
        }
        ContextSnapshot ctx = snap != null ? snap : ContextSnapshot.EMPTY;
        if (ctx.isExternalDevice && (key == KeyId.VOLUME_UP || key == KeyId.VOLUME_DOWN)) {
            return false;
        }
        if (cfg.isBlacklisted(ctx.topPackage)) {
            return false;
        }
        if (key == KeyId.ESSENTIAL && KeyPilotConfig.POLICY_YIELD_ALL.equals(cfg.officialRemapPolicy)) {
            return false;
        }
        for (GestureType g : GestureType.values()) {
            Resolution r = resolve(cfg, key, g, ctx);
            if (!r.action.isSystem()) {
                return true;
            }
        }
        return false;
    }
}
